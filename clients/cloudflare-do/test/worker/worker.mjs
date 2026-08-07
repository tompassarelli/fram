// SPDX-License-Identifier: MIT OR Apache-2.0
// The Durable Object under test: one FRAMLOG plus its snapshot image per
// object, in real DurableObjectStorage, driven by the published adapter.
import framModule from "../../lib/libfram.wasm";
import framesBin from "../bundle/frames.bin";
import framesJson from "../bundle/frames.json";
import {
  ChunkedRange,
  FramDurableObjectBase,
  hex,
} from "../../src/adapter.mjs";

const catalogue = JSON.parse(framesJson);
const blob = new Uint8Array(framesBin);
const SPACE = "fram-wasm-embed";

function frame(name) {
  const entry = catalogue.table[name];
  return blob.subarray(entry.offset, entry.offset + entry.length);
}

function manifest(which) {
  const found = catalogue.manifests.find((m) => m.manifest === which);
  if (!found) throw new Error(`no such manifest: ${which}`);
  return found.rows;
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export class FramLog extends FramDurableObjectBase {
  constructor(state, env) {
    super(state, env, framModule, {
      spaceId: SPACE,
      logLabel: "in-memory",
      instance: {
        nowMs: () => 1700000000000,
        arena: { initialPages: 128 },
      },
    });
  }

  async fetch(request) {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/pass") return await this.pass(url);
      if (url.pathname === "/dump") return await this.dump(url);
      if (url.pathname === "/grow") return await this.grow(url);
      if (url.pathname === "/keys") return await this.keys(url);
      if (url.pathname === "/concurrent-boot") return await this.race(url);
      if (url.pathname === "/ready") return json({ ready: true });
    } catch (error) {
      return json({ fatal: `${error.message}`, stack: error.stack }, 500);
    }
    return json({ fatal: `no such route: ${url.pathname}` }, 404);
  }

  /** One transcript pass: a fresh instance over the same storage. */
  async pass(url) {
    const label = url.searchParams.get("label") ?? "open";
    const which = url.searchParams.get("manifest") ?? "manifest.txt";
    const out = [];
    let failures = 0;
    let fatal = null;

    await this.recycle();
    const instance = await this.fram();
    const opened = this.openResult;
    out.push(`${label} ${opened.status} "${opened.message}"`);

    for (const { entry, name, declared } of manifest(which)) {
      const bytes = frame(name);
      if (bytes.length !== declared) {
        out.push(`frame ${name} READ-MISMATCH`);
        failures += 1;
        continue;
      }
      let result;
      try {
        result =
          entry === "t"
            ? await this.transact(bytes)
            : entry === "s"
              ? await this.snapshot(bytes)
              : await this.query(bytes);
      } catch (error) {
        fatal = `frame ${name}: ${error.message}`;
        break;
      }
      out.push(`frame ${name} ${result.status} ${hex(result.response)}`);
      if (result.status !== 0) failures += 1;
      if (!result.released) {
        out.push(`frame ${name} RELEASE-DID-NOT-CLEAR`);
        failures += 1;
      }
    }

    const stats = instance.stats();
    const storage = this.store.stats();
    if (!fatal) {
      const closed = await this.recycle();
      out.push(`close ${closed.status} "${closed.message}"`);
    }
    return json({ label, fatal, failures, out, stats, storage });
  }

  /** The durable bytes of one range, read back through a fresh reader. */
  async dump(url) {
    const which = url.searchParams.get("range") ?? "log";
    const prefix = which === "image" ? "framimage/" : "framlog/";
    const bytes = await new ChunkedRange(this.state.storage, { prefix }).load();
    return new Response(bytes, {
      headers: { "content-type": "application/octet-stream" },
    });
  }

  /** Transact a cycle of frames until the durable log passes `bytes`. */
  async grow(url) {
    const target = Number(url.searchParams.get("bytes") ?? 96 * 1024);
    const limit = Number(url.searchParams.get("limit") ?? 400);
    const names = (
      url.searchParams.get("frames") ??
      "30-batch-bulk-a.bin,30-batch-bulk-b.bin,30-batch-bulk-c.bin"
    ).split(",");
    const instance = await this.fram();
    let rounds = 0;
    let failures = 0;
    while (instance.log.length < target && rounds < limit) {
      const result = await this.transact(frame(names[rounds % names.length]));
      if (result.status !== 0) failures += 1;
      rounds += 1;
    }
    return json({
      rounds,
      failures,
      guestLogBytes: instance.log.length,
      reached: instance.log.length >= target,
      storage: this.store.stats(),
    });
  }

  /** The live key inventory, which is how multi-chunk coverage is observed. */
  async keys(url) {
    const prefix = url.searchParams.get("prefix") ?? "framlog/";
    const listed = await this.state.storage.list({ prefix });
    const meta = await this.state.storage.get(`${prefix}meta`);
    return json({
      prefix,
      keys: [...listed.keys()].sort(),
      meta: meta ?? null,
    });
  }

  /**
   * Two boots demanded in one turn. The memo must answer both with the same
   * instance; two instances over one storage would mean two divergent images.
   */
  async race(url) {
    const width = Number(url.searchParams.get("width") ?? 8);
    await this.recycle();
    const demands = [];
    for (let i = 0; i < width; i++) demands.push(this.fram());
    const settled = await Promise.all(demands);
    const distinct = new Set(settled).size;
    const answered = await Promise.all(
      settled.map(() => this.query(frame("02-status.bin"))),
    );
    return json({
      width,
      distinct,
      identical: settled.every((one) => one === settled[0]),
      statuses: answered.map((one) => one.status),
      guestLogBytes: settled[0].log.length,
      storage: this.store.stats(),
    });
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const id = env.FRAM.idFromName(url.searchParams.get("id") ?? "matrix");
    return env.FRAM.get(id).fetch(request);
  },
};
