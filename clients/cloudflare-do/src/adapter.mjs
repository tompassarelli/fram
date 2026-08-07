// SPDX-License-Identifier: MIT OR Apache-2.0
//
// fram_host_v1 over an async, Durable-Object-shaped storage API.
//
// fram's storage hooks must return an i32 before the guest call unwinds and DO
// storage is async, so both storage objects are resident in host memory for the
// duration of a guest call and the store is touched only on either side of it.

import { assertSeams } from "./seams.mjs";

const PAGE_BYTES = 65536;
const FRAM_ABI_VERSION = 1;

// wasm32 layouts of the public ABI structs (native/fram.h under -m32).
export const OPTIONS_SIZE = 32; // abi, size, space, path, host, pad, budget u64
export const ERROR_SIZE = 516; // code i32 + message[512]
export const BUFFER_SIZE = 16; // data, length, release_context, release
const ERROR_MESSAGE_OFFSET = 4;
const OPTIONS_BUDGET_OFFSET = 24;

// fram_wasm_host.c discriminates the two storage objects by context alone.
export const LOG_CONTEXT = 0;
export const IMAGE_CONTEXT = 1;

const WASI_ENOSYS = 52;

// ---------------------------------------------------------------------------
// The async store
// ---------------------------------------------------------------------------

/**
 * One storage object as chunked values in a DurableObjectStorage-shaped KV.
 *
 * DO caps a single value at 128 KiB and a single put() batch at 128 keys, so
 * the object is sliced into fixed chunks and only the chunks at or after the
 * lowest modified byte are rewritten. `meta` carries the authoritative length:
 * a chunk may legally hold stale bytes past it after a truncate.
 */
export class ChunkedRange {
  constructor(storage, options = {}) {
    this.storage = storage;
    this.prefix = options.prefix ?? "framlog/";
    this.chunkBytes = options.chunkBytes ?? 64 * 1024;
    this.batchKeys = options.batchKeys ?? 100;
    this.metaKey = `${this.prefix}meta`;
    this.chunkCount = null; // unknown until load()
    this.puts = 0;
    this.gets = 0;
    this.deletes = 0;
    this.bytesWritten = 0;
    this.bytesRead = 0;
  }

  #chunkKey(index) {
    // Zero-padded so a list() is lexicographically ordered.
    return `${this.prefix}${String(index).padStart(8, "0")}`;
  }

  async load() {
    const meta = await this.storage.get(this.metaKey);
    this.gets += 1;
    if (!meta) {
      this.chunkCount = 0;
      return new Uint8Array(0);
    }
    // The written chunk size wins: a range written under another configuration
    // still reads back at its own boundaries.
    if (meta.chunkBytes) this.chunkBytes = meta.chunkBytes;
    const length = meta.length;
    const chunks = Math.ceil(length / this.chunkBytes);
    this.chunkCount = chunks;
    const image = new Uint8Array(length);
    for (let base = 0; base < chunks; base += this.batchKeys) {
      const keys = [];
      for (let i = base; i < Math.min(base + this.batchKeys, chunks); i++) {
        keys.push(this.#chunkKey(i));
      }
      const got = await this.storage.get(keys);
      this.gets += 1;
      for (let i = base; i < base + keys.length; i++) {
        const value = got.get(this.#chunkKey(i));
        if (value === undefined) {
          throw new Error(
            `${this.prefix} chunk ${i} is missing; the object is torn`,
          );
        }
        const bytes = new Uint8Array(
          value.buffer ?? value,
          value.byteOffset ?? 0,
          value.byteLength ?? value.length,
        );
        const at = i * this.chunkBytes;
        image.set(bytes.subarray(0, Math.min(bytes.length, length - at)), at);
        this.bytesRead += bytes.length;
      }
    }
    return image;
  }

  /**
   * The writes that publish `bytes[0..length)`, rewriting only chunks at or
   * after `lowWater`. Slices eagerly: the plan owns its copies, so the caller
   * may keep appending while the transaction is still queued.
   */
  plan(bytes, length, lowWater) {
    if (this.chunkCount === null) {
      throw new Error(`${this.prefix} was never loaded; stale chunks unknown`);
    }
    const first = Math.floor(lowWater / this.chunkBytes);
    const chunks = Math.ceil(length / this.chunkBytes);
    const writes = [];
    for (let i = first; i < chunks; i++) {
      const at = i * this.chunkBytes;
      const end = Math.min(at + this.chunkBytes, length);
      writes.push([this.#chunkKey(i), bytes.slice(at, end)]);
    }
    const stale = [];
    for (let i = chunks; i < this.chunkCount; i++) stale.push(this.#chunkKey(i));
    return { writes, stale, chunks, length };
  }

  async applyTo(txn, plan) {
    for (let base = 0; base < plan.writes.length; base += this.batchKeys) {
      const batch = plan.writes.slice(base, base + this.batchKeys);
      await txn.put(Object.fromEntries(batch));
      this.puts += 1;
      for (const [, value] of batch) this.bytesWritten += value.length;
    }
    if (plan.stale.length) {
      await txn.delete(plan.stale);
      this.deletes += 1;
    }
    await txn.put(this.metaKey, {
      length: plan.length,
      chunkBytes: this.chunkBytes,
    });
    this.puts += 1;
  }

  /** Adopt the plan's chunk count; only a landed transaction may call this. */
  settle(plan) {
    this.chunkCount = plan.chunks;
  }
}

/**
 * The FRAMLOG and the snapshot image as two key ranges in one DO storage.
 *
 * Commits are serialised and atomic across both ranges: fram replays the log
 * from byte zero and boots through the image when one is present, so the pair
 * must never be observed half-published.
 */
export class DurableFramStore {
  constructor(storage, options = {}) {
    const shared = {
      chunkBytes: options.chunkBytes,
      batchKeys: options.batchKeys,
    };
    this.storage = storage;
    this.ranges = {
      log: new ChunkedRange(storage, {
        ...shared,
        prefix: options.logPrefix ?? "framlog/",
      }),
      image: new ChunkedRange(storage, {
        ...shared,
        prefix: options.imagePrefix ?? "framimage/",
      }),
    };
    this.commits = 0;
    this.queue = Promise.resolve();
  }

  load(which = "log") {
    return this.ranges[which].load();
  }

  /** parts: [{ which, bytes, length, lowWater }] */
  commit(parts) {
    const staged = parts.map(({ which, bytes, length, lowWater }) => {
      const range = this.ranges[which];
      return { range, plan: range.plan(bytes, length, lowWater) };
    });
    const run = async () => {
      await this.storage.transaction(async (txn) => {
        for (const { range, plan } of staged) await range.applyTo(txn, plan);
      });
      for (const { range, plan } of staged) range.settle(plan);
      this.commits += 1;
    };
    const result = this.queue.then(run, run);
    this.queue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  stats() {
    const of = (range) => ({
      puts: range.puts,
      gets: range.gets,
      deletes: range.deletes,
      bytesWritten: range.bytesWritten,
      bytesRead: range.bytesRead,
      chunks: range.chunkCount,
    });
    return {
      commits: this.commits,
      log: of(this.ranges.log),
      image: of(this.ranges.image),
    };
  }
}

/** The same interface over a plain Map, for a runtime with no DO storage. */
export class MemoryStorage {
  constructor(map = new Map()) {
    this.map = map;
    this.latencyMs = 0;
  }

  async #tick() {
    if (this.latencyMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, this.latencyMs));
    }
  }

  async get(keyOrKeys) {
    await this.#tick();
    if (Array.isArray(keyOrKeys)) {
      const out = new Map();
      for (const key of keyOrKeys) {
        if (this.map.has(key)) out.set(key, this.map.get(key));
      }
      return out;
    }
    return this.map.get(keyOrKeys);
  }

  async put(keyOrEntries, value) {
    await this.#tick();
    if (typeof keyOrEntries === "object" && value === undefined) {
      for (const [key, item] of Object.entries(keyOrEntries)) {
        this.map.set(key, item);
      }
      return;
    }
    this.map.set(keyOrEntries, value);
  }

  async delete(keyOrKeys) {
    await this.#tick();
    for (const key of Array.isArray(keyOrKeys) ? keyOrKeys : [keyOrKeys]) {
      this.map.delete(key);
    }
  }

  // Atomic like the real one: a throwing body publishes nothing.
  async transaction(body) {
    const staging = new MemoryStorage(new Map(this.map));
    staging.latencyMs = this.latencyMs;
    const result = await body(staging);
    this.map = staging.map;
    return result;
  }
}

// ---------------------------------------------------------------------------
// The synchronous side: host memory image + guest-memory allocator
// ---------------------------------------------------------------------------

/**
 * One storage object as the synchronous hooks see it. Reads are served from it,
 * appends land in it, and `lowWater` remembers the earliest byte a commit must
 * rewrite.
 */
class HostImage {
  constructor(bytes) {
    this.bytes = bytes;
    this.capacity = bytes.length;
    this.length = bytes.length;
    this.lowWater = Number.POSITIVE_INFINITY;
    this.dirty = false;
    this.syncs = 0;
  }

  #reserve(extra) {
    if (this.length + extra <= this.capacity) return;
    let capacity = Math.max(this.capacity * 2, 64 * 1024);
    while (capacity < this.length + extra) capacity *= 2;
    const grown = new Uint8Array(capacity);
    grown.set(this.bytes.subarray(0, this.length));
    this.bytes = grown;
    this.capacity = capacity;
  }

  append(chunk) {
    this.#reserve(chunk.length);
    this.bytes.set(chunk, this.length);
    this.lowWater = Math.min(this.lowWater, this.length);
    this.length += chunk.length;
    this.dirty = true;
  }

  truncate(length) {
    if (length > this.length) return false;
    this.lowWater = Math.min(this.lowWater, length);
    this.length = length;
    this.dirty = true;
    return true;
  }

  read(offset, length) {
    if (offset + length > this.length) return null;
    return this.bytes.subarray(offset, offset + length);
  }

  takeCommit() {
    const low = Number.isFinite(this.lowWater) ? this.lowWater : 0;
    this.lowWater = Number.POSITIVE_INFINITY;
    this.dirty = false;
    return low;
  }

  // A rejected commit published nothing, so the range it covered is dirty again.
  restoreCommit(low) {
    this.lowWater = Math.min(this.lowWater, low);
    this.dirty = true;
  }
}

/**
 * fram's general allocator, living in a host-owned partition of guest memory.
 *
 * fram_host_v1.allocate is not only for responses: the engine's own long-lived
 * structures come through it, so a bump arena leaks the index on every open.
 * deallocate carries no size, so each block gets a 16-byte header and freed
 * blocks return to a power-of-two size-class free list.
 */
class GuestArena {
  static HEADER = 16;

  constructor(memory, options = {}) {
    this.memory = memory;
    this.initialPages = options.initialPages ?? 128; // 8 MiB
    this.growPages = options.growPages ?? 128;
    this.base = 0;
    this.next = 0;
    this.end = 0;
    this.free = new Map();
    this.allocations = 0;
    this.deallocations = 0;
    this.reuses = 0;
    this.grows = 0;
    this.liveBytes = 0;
    this.peakLiveBytes = 0;
  }

  reserve() {
    const before = this.memory.grow(this.initialPages);
    if (before < 0) throw new Error("cannot reserve the host arena");
    this.base = before * PAGE_BYTES;
    this.next = this.base;
    this.end = this.base + this.initialPages * PAGE_BYTES;
  }

  #view() {
    return new DataView(this.memory.buffer);
  }

  static #classOf(size) {
    let cls = 32;
    while (cls < size) cls *= 2;
    return cls;
  }

  allocate(size) {
    this.allocations += 1;
    const cls = GuestArena.#classOf(size);
    const bucket = this.free.get(cls);
    if (bucket && bucket.length) {
      this.reuses += 1;
      const pointer = bucket.pop();
      this.liveBytes += cls;
      this.peakLiveBytes = Math.max(this.peakLiveBytes, this.liveBytes);
      return pointer;
    }
    const need = GuestArena.HEADER + cls;
    let header = (this.next + 15) & ~15;
    if (header + need > this.end) {
      // The arena is contiguous with the top of linear memory only while
      // nothing else has grown it; restart the bump pointer if it moved.
      const pages = Math.max(this.growPages, Math.ceil(need / PAGE_BYTES));
      const before = this.memory.grow(pages);
      if (before < 0) return 0; // NULL -> fram surfaces FRAM_OUT_OF_MEMORY
      this.grows += 1;
      const fresh = before * PAGE_BYTES;
      if (fresh !== this.end) this.next = fresh;
      this.end = fresh + pages * PAGE_BYTES;
      header = (this.next + 15) & ~15;
      if (header + need > this.end) return 0;
    }
    this.next = header + need;
    const view = this.#view();
    view.setUint32(header, cls, true);
    view.setUint32(header + 4, 0x4652414d, true); // 'FRAM'
    this.liveBytes += cls;
    this.peakLiveBytes = Math.max(this.peakLiveBytes, this.liveBytes);
    return header + GuestArena.HEADER;
  }

  deallocate(pointer) {
    this.deallocations += 1;
    if (!pointer) return;
    const header = pointer - GuestArena.HEADER;
    const view = this.#view();
    if (view.getUint32(header + 4, true) !== 0x4652414d) {
      throw new Error(`deallocate(${pointer}) is not a host arena block`);
    }
    const cls = view.getUint32(header, true);
    let bucket = this.free.get(cls);
    if (!bucket) this.free.set(cls, (bucket = []));
    bucket.push(pointer);
    this.liveBytes -= cls;
  }

  get reservedBytes() {
    return this.end - this.base;
  }
}

// ---------------------------------------------------------------------------
// The instance
// ---------------------------------------------------------------------------

/** A rejected storage commit fences the instance that raised it. */
export class FramStorageError extends Error {
  constructor(cause) {
    super(`the storage commit was rejected: ${cause.message}`, { cause });
    this.name = "FramStorageError";
  }
}

export class FramInstance {
  /**
   * @param {WebAssembly.Module} module libfram.wasm, host=wasm-embed
   * @param {object} options
   *   store  - DurableFramStore-shaped: load(which), commit(parts)
   *   nowMs  - () => epoch milliseconds; defaults to Date.now
   *   arena  - GuestArena options
   *   memoryBudgetBytes - fram_open_options_v1.memory_budget_bytes; 0 = default
   */
  static async instantiate(module, options = {}) {
    const instance = new FramInstance(options);
    await instance.#boot(module);
    return instance;
  }

  constructor(options = {}) {
    this.store = options.store;
    this.nowMs = options.nowMs ?? (() => Date.now());
    this.arenaOptions = options.arena ?? {};
    this.memoryBudgetBytes = BigInt(options.memoryBudgetBytes ?? 0);
    this.hostCalls = Object.create(null);
    this.wasiCalls = Object.create(null);
    this.wasiRefused = Object.create(null);
    this.commits = 0;
    this.log = null;
    this.image = null;
    this.opened = false;
    this.closed = false;
    this.poisoned = null;
    this.gate = Promise.resolve();
  }

  #tick(table, name) {
    table[name] = (table[name] ?? 0) + 1;
  }

  #bytes() {
    return new Uint8Array(this.memory.buffer);
  }

  #view() {
    return new DataView(this.memory.buffer);
  }

  #object(context) {
    return context === IMAGE_CONTEXT ? this.image : this.log;
  }

  #imports() {
    const self = this;
    const fram_host_v1 = {
      clock_milliseconds(_context, outPointer) {
        self.#tick(self.hostCalls, "clock_milliseconds");
        self.#view().setBigInt64(outPointer, BigInt(self.nowMs()), true);
        return 0;
      },
      storage_size(context, outPointer) {
        self.#tick(self.hostCalls, "storage_size");
        const object = self.#object(context);
        self.#view().setBigUint64(outPointer, BigInt(object.length), true);
        return 0;
      },
      storage_read(context, offset, destination, length) {
        self.#tick(self.hostCalls, "storage_read");
        const slice = self.#object(context).read(Number(offset), length);
        if (slice === null) return 1;
        self.#bytes().set(slice, destination);
        return 0;
      },
      storage_truncate(context, length) {
        self.#tick(self.hostCalls, "storage_truncate");
        return self.#object(context).truncate(Number(length)) ? 0 : 1;
      },
      storage_append(context, pointer, length) {
        self.#tick(self.hostCalls, "storage_append");
        self
          .#object(context)
          .append(self.#bytes().subarray(pointer, pointer + length));
        return 0;
      },
      // The commit the guest believes in happens here; the commit Cloudflare
      // believes in happens in #commit(), after the guest call unwinds.
      storage_sync(context) {
        self.#tick(self.hostCalls, "storage_sync");
        self.#object(context).syncs += 1;
        self.syncPending = true;
        return 0;
      },
      storage_close(_context) {
        self.#tick(self.hostCalls, "storage_close");
        self.syncPending = true;
        return 0;
      },
      allocate(_context, size) {
        self.#tick(self.hostCalls, "allocate");
        return self.arena.allocate(size);
      },
      deallocate(_context, pointer) {
        self.#tick(self.hostCalls, "deallocate");
        self.arena.deallocate(pointer);
      },
    };

    const refuse =
      (name) =>
      (..._arguments) => {
        self.#tick(self.wasiRefused, name);
        return WASI_ENOSYS;
      };
    const wasi_snapshot_preview1 = {
      // The Beagle shim's monotonic clock and getenv have no fram_host_v1
      // field, so these three are the capabilities the regime still takes from
      // WASI. Everything else is counted and refused.
      clock_time_get(_id, _precision, outPointer) {
        self.#tick(self.wasiCalls, "clock_time_get");
        self.#view().setBigUint64(
          outPointer,
          BigInt(Math.round(self.nowMs())) * 1000000n,
          true,
        );
        return 0;
      },
      environ_sizes_get(countPointer, sizePointer) {
        self.#tick(self.wasiCalls, "environ_sizes_get");
        const view = self.#view();
        view.setUint32(countPointer, 0, true);
        view.setUint32(sizePointer, 0, true);
        return 0;
      },
      environ_get(_environ, _buffer) {
        self.#tick(self.wasiCalls, "environ_get");
        return 0;
      },
      fd_close: refuse("fd_close"),
      fd_seek: refuse("fd_seek"),
      fd_write: refuse("fd_write"),
      proc_exit(code) {
        self.#tick(self.wasiRefused, "proc_exit");
        throw new Error(`the guest called proc_exit(${code})`);
      },
    };

    return { fram_host_v1, wasi_snapshot_preview1 };
  }

  async #boot(module) {
    const started = nowHiRes();
    const imports = this.#imports();
    assertSeams(module, imports);
    const instance = await WebAssembly.instantiate(module, imports);
    this.exports = instance.exports;
    this.memory = this.exports.memory;
    this.exports._initialize();
    this.arena = new GuestArena(this.memory, this.arenaOptions);
    this.arena.reserve();
    this.instantiateMs = nowHiRes() - started;
  }

  // -- guest memory helpers -------------------------------------------------

  alloc(size) {
    const pointer = this.exports.fram_wasm_alloc(size);
    if (!pointer) throw new Error(`fram_wasm_alloc(${size}) returned NULL`);
    this.#bytes().fill(0, pointer, pointer + size);
    return pointer;
  }

  free(pointer) {
    this.exports.fram_wasm_free(pointer);
  }

  write(pointer, payload) {
    this.#bytes().set(payload, pointer);
  }

  read(pointer, length) {
    return this.#bytes().slice(pointer, pointer + length);
  }

  readCString(pointer, limit) {
    const bytes = this.#bytes();
    let end = pointer;
    while (end < pointer + limit && bytes[end] !== 0) end += 1;
    return new TextDecoder().decode(bytes.subarray(pointer, end));
  }

  putCString(text) {
    const raw = new TextEncoder().encode(`${text}\0`);
    const pointer = this.alloc(raw.length);
    this.write(pointer, raw);
    return pointer;
  }

  // -- the public database surface ------------------------------------------

  /**
   * Load both storage objects and open the database over them. Resolves only
   * after the open's own writes are durable.
   */
  open(spaceId, logLabel = "in-memory") {
    return this.#serialise(async () => {
      if (this.opened) throw new Error("this instance is already open");
      // The whole synchronous illusion starts here: two awaits, then both
      // objects are resident and every storage hook can answer without yielding.
      this.log = new HostImage(await this.store.load("log"));
      this.image = new HostImage(await this.store.load("image"));
      this.syncPending = false;

      const options = this.alloc(OPTIONS_SIZE);
      const view = this.#view();
      view.setUint32(options + 0, FRAM_ABI_VERSION, true);
      view.setUint32(options + 4, OPTIONS_SIZE, true);
      view.setUint32(options + 8, this.putCString(spaceId), true);
      view.setUint32(options + 12, this.putCString(logLabel), true);
      view.setUint32(options + 16, 0, true); // host == NULL selects the imports
      this.#view().setBigUint64(
        options + OPTIONS_BUDGET_OFFSET,
        this.memoryBudgetBytes,
        true,
      );
      const databaseOut = this.alloc(4);
      const error = this.alloc(ERROR_SIZE);

      const status = this.exports.fram_open(options, databaseOut, error);
      const message = this.readCString(
        error + ERROR_MESSAGE_OFFSET,
        ERROR_SIZE - ERROR_MESSAGE_OFFSET,
      );
      await this.#commit();
      if (status === 0) {
        this.database = this.#view().getUint32(databaseOut, true);
        this.opened = true;
      }
      return { status, message };
    });
  }

  /** entry: "q" | "t" | "s" */
  call(entry, frame) {
    return this.#serialise(() => this.#callNow(entry, frame));
  }

  query(frame) {
    return this.call("q", frame);
  }

  transact(frame) {
    return this.call("t", frame);
  }

  snapshot(frame) {
    return this.call("s", frame);
  }

  /**
   * Take a checkpoint: FRAME is a canonical `:rpc/checkpoint` request, which
   * fram answers by rewriting the snapshot image. Resolves once both the image
   * and the log are durable.
   */
  checkpoint(frame) {
    return this.#serialise(async () => {
      const result = await this.#callNow("t", frame);
      return { ...result, imageBytes: this.image.length };
    });
  }

  close() {
    return this.#serialise(async () => {
      const error = this.alloc(ERROR_SIZE);
      const status = this.exports.fram_close(this.database, error);
      const message = this.readCString(
        error + ERROR_MESSAGE_OFFSET,
        ERROR_SIZE - ERROR_MESSAGE_OFFSET,
      );
      this.free(error);
      await this.#commit();
      this.closed = true;
      this.opened = false;
      return { status, message };
    });
  }

  async #callNow(entry, frame) {
    const entryPoint =
      entry === "t"
        ? this.exports.fram_transact
        : entry === "s"
          ? this.exports.fram_snapshot
          : this.exports.fram_query;

    const requestPointer = this.alloc(frame.length);
    this.write(requestPointer, frame);
    const slice = this.alloc(8);
    const view = this.#view();
    view.setUint32(slice + 0, requestPointer, true);
    view.setUint32(slice + 4, frame.length, true);
    const buffer = this.alloc(BUFFER_SIZE);
    const error = this.alloc(ERROR_SIZE);

    const status = entryPoint(this.database, slice, buffer, error);

    const after = this.#view();
    const data = after.getUint32(buffer + 0, true);
    const length = after.getUint32(buffer + 4, true);
    const response = length ? this.read(data, length) : new Uint8Array(0);
    const message = this.readCString(
      error + ERROR_MESSAGE_OFFSET,
      ERROR_SIZE - ERROR_MESSAGE_OFFSET,
    );

    // release is a table index; only the guest can call it.
    this.exports.fram_buffer_release(buffer);
    const cleared = this.#view();
    const released =
      cleared.getUint32(buffer + 0, true) === 0 &&
      cleared.getUint32(buffer + 4, true) === 0 &&
      cleared.getUint32(buffer + 12, true) === 0;

    this.free(requestPointer);
    this.free(slice);
    this.free(buffer);
    this.free(error);

    await this.#commit();
    return { status, message, response, released };
  }

  /**
   * One guest call at a time. The guest is single-threaded and its pointers
   * live across the awaits inside a call, so two overlapping requests on one
   * instance would interleave allocations into each other's frames.
   */
  #serialise(body) {
    const run = async () => {
      this.#assertUsable();
      return body();
    };
    const result = this.gate.then(run, run);
    this.gate = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  #assertUsable() {
    if (this.poisoned) throw this.poisoned;
    if (this.closed) throw new Error("this fram instance is closed");
  }

  async #commit() {
    if (!this.syncPending && !this.log.dirty && !this.image.dirty) return;
    this.syncPending = false;
    const parts = [];
    for (const [which, object] of [
      ["log", this.log],
      ["image", this.image],
    ]) {
      if (object.dirty) {
        parts.push({
          which,
          object,
          bytes: object.bytes,
          length: object.length,
          lowWater: object.takeCommit(),
        });
      }
    }
    if (!parts.length) return;
    try {
      await this.store.commit(parts);
    } catch (error) {
      // Nothing landed, so the ranges are dirty again; this instance is fenced
      // because it can no longer tell the guest's bytes from the durable ones.
      for (const part of parts) part.object.restoreCommit(part.lowWater);
      this.poisoned = new FramStorageError(error);
      throw this.poisoned;
    }
    this.commits += 1;
  }

  /** The log as the guest currently sees it (host memory, not yet durable). */
  logBytes() {
    return this.log.bytes.slice(0, this.log.length);
  }

  /** The snapshot image as the guest currently sees it. */
  imageBytes() {
    return this.image.bytes.slice(0, this.image.length);
  }

  stats() {
    return {
      instantiateMs: this.instantiateMs,
      linearMemoryBytes: this.memory.buffer.byteLength,
      arenaReservedBytes: this.arena.reservedBytes,
      arenaPeakLiveBytes: this.arena.peakLiveBytes,
      arenaLiveBytes: this.arena.liveBytes,
      arenaAllocations: this.arena.allocations,
      arenaDeallocations: this.arena.deallocations,
      arenaReuses: this.arena.reuses,
      arenaGrows: this.arena.grows,
      commits: this.commits,
      logBytes: this.log ? this.log.length : 0,
      imageBytes: this.image ? this.image.length : 0,
      poisoned: this.poisoned ? this.poisoned.message : null,
      hostCalls: { ...this.hostCalls },
      wasiCalls: { ...this.wasiCalls },
      wasiRefused: { ...this.wasiRefused },
    };
  }
}

/**
 * A Durable Object that owns one FRAMLOG and its snapshot image.
 *
 * The instance is created lazily per isolate and kept for the object's
 * lifetime: instantiation is the expensive step and the state it holds is
 * exactly what the DO already guarantees single-writer access to.
 */
export class FramDurableObjectBase {
  constructor(state, env, module, options = {}) {
    this.state = state;
    this.env = env;
    this.module = module;
    this.spaceId = options.spaceId ?? "fram";
    this.logLabel = options.logLabel ?? "in-memory";
    this.storeOptions = options.store ?? {};
    this.instanceOptions = options.instance ?? {};
    this.instance = null;
    this.store = null;
    this.booting = null;
  }

  /** The open instance, booting it at most once however many requests race. */
  fram() {
    if (this.instance) return Promise.resolve(this.instance);
    // Assigned before the first await: a second caller in the same turn joins
    // this boot instead of building a second image over the same storage.
    if (!this.booting) {
      this.booting = this.#boot().then(
        (instance) => {
          this.instance = instance;
          this.booting = null;
          return instance;
        },
        (error) => {
          this.booting = null;
          throw error;
        },
      );
    }
    return this.booting;
  }

  async #boot() {
    const store = new DurableFramStore(this.state.storage, this.storeOptions);
    const instance = await FramInstance.instantiate(this.module, {
      ...this.instanceOptions,
      store,
    });
    const opened = await instance.open(this.spaceId, this.logLabel);
    this.openResult = opened;
    if (opened.status !== 0) {
      throw new Error(`fram_open failed: ${opened.status} ${opened.message}`);
    }
    this.store = store;
    return instance;
  }

  query(frame) {
    return this.#use((instance) => instance.query(frame));
  }

  transact(frame) {
    return this.#use((instance) => instance.transact(frame));
  }

  snapshot(frame) {
    return this.#use((instance) => instance.snapshot(frame));
  }

  checkpoint(frame) {
    return this.#use((instance) => instance.checkpoint(frame));
  }

  async #use(body) {
    const instance = await this.fram();
    try {
      return await body(instance);
    } catch (error) {
      // A fenced instance holds bytes no one can place against storage; drop it
      // so the next request reopens from what is actually durable.
      if (instance.poisoned) this.#drop();
      throw error;
    }
  }

  #drop() {
    this.instance = null;
    this.booting = null;
    this.store = null;
  }

  /** Drop the guest without touching storage; the next call reopens from it. */
  async recycle() {
    const instance = this.instance;
    this.#drop();
    if (instance && !instance.closed && !instance.poisoned) {
      return instance.close();
    }
    return null;
  }
}

export function nowHiRes() {
  // Local workerd advances this during pure CPU; deployed Workers documents a
  // clock that only moves at I/O, so in-isolate deltas are not portable.
  return typeof performance !== "undefined" && performance.now
    ? performance.now()
    : Date.now();
}

export function hex(bytes) {
  let out = "";
  for (let i = 0; i < bytes.length; i++) {
    out += bytes[i].toString(16).padStart(2, "0");
  }
  return out;
}
