// SPDX-License-Identifier: MIT OR Apache-2.0
// The durability half of the harness, in plain Node over the memory storage:
// what a lost isolate, a rejected commit, and a shrinking object leave behind.
//
//   node test/run-node.mjs WASM FRAMES-DIR
import { readFileSync } from "node:fs";
import {
  ChunkedRange,
  DurableFramStore,
  FramDurableObjectBase,
  FramInstance,
  MemoryStorage,
} from "../src/adapter.mjs";

const [wasmPath, framesDir] = process.argv.slice(2);
const SPACE = "fram-wasm-embed";
const module = new WebAssembly.Module(readFileSync(wasmPath));
const frame = (name) => new Uint8Array(readFileSync(`${framesDir}/${name}`));

const failures = [];
const notes = [];

function check(condition, detail) {
  if (condition) {
    notes.push(`  ok   ${detail}`);
  } else {
    failures.push(detail);
    notes.push(`  FAIL ${detail}`);
  }
}

async function open(store) {
  const instance = await FramInstance.instantiate(module, {
    store,
    nowMs: () => 1700000000000,
    arena: { initialPages: 8 },
  });
  const opened = await instance.open(SPACE, "in-memory");
  if (opened.status !== 0) {
    throw new Error(`open failed ${opened.status} ${opened.message}`);
  }
  return instance;
}

const durableLog = (storage) =>
  new ChunkedRange(storage, { prefix: "framlog/" }).load();

function samePrefix(shorter, longer) {
  return shorter.every((byte, index) => byte === longer[index]);
}

// -- 1. a lost isolate leaves a SHORT log, never a torn one ------------------
{
  const storage = new MemoryStorage();
  let fram = await open(new DurableFramStore(storage));
  await fram.transact(frame("03-assert-unicode.bin"));
  await fram.transact(frame("04-batch-mixed.bin"));
  const landed = await durableLog(storage);

  // The isolate is "dead": the guest still sees storage_sync return 0 and
  // appends into host memory, but no commit is ever acknowledged.
  const doomed = new DurableFramStore(storage);
  doomed.commit = () => Promise.resolve();
  fram = await open(doomed);
  const lost = await fram.transact(frame("12-retract.bin"));
  const believed = fram.log.length;
  const after = await durableLog(storage);

  check(lost.status === 0, "the guest was answered by the doomed isolate");
  check(
    after.length === landed.length && samePrefix(after, landed),
    `the durable log is unchanged at ${after.length} bytes ` +
      `(the guest believed ${believed})`,
  );

  const reborn = await open(new DurableFramStore(storage));
  const status = await reborn.query(frame("02-status.bin"));
  const scan = await reborn.query(frame("13-scan-after.bin"));
  const closed = await reborn.close();
  check(
    status.status === 0 && scan.status === 0 && closed.status === 0,
    "a fresh instance reopened the short log and answered",
  );
}

// -- 2. a rejected commit fences the instance and the object drops it --------
{
  const storage = new MemoryStorage();
  const state = { storage };
  const object = new FramDurableObjectBase(state, {}, module, {
    spaceId: SPACE,
    instance: { nowMs: () => 1700000000000, arena: { initialPages: 8 } },
  });
  await object.transact(frame("03-assert-unicode.bin"));
  const landed = await durableLog(storage);
  const first = await object.fram();

  let refuse = true;
  const inner = storage.transaction.bind(storage);
  storage.transaction = async (body) => {
    if (refuse) throw new Error("storage said no");
    return inner(body);
  };

  let raised = null;
  try {
    await object.transact(frame("04-batch-mixed.bin"));
  } catch (error) {
    raised = error;
  }
  check(
    raised !== null && raised.name === "FramStorageError",
    `the rejected commit surfaced as ${raised && raised.name}`,
  );
  check(first.poisoned !== null, "the instance that saw it is fenced");
  check(
    first.log.dirty && Number.isFinite(first.log.lowWater),
    `the dirty range came back (low water ${first.log.lowWater})`,
  );
  let reused = null;
  try {
    await first.query(frame("02-status.bin"));
  } catch (error) {
    reused = error;
  }
  check(reused !== null, "the fenced instance refuses further calls");

  const stillThere = await durableLog(storage);
  check(
    stillThere.length === landed.length && samePrefix(stillThere, landed),
    `the durable log kept its ${landed.length} acked bytes`,
  );

  refuse = false;
  const second = await object.fram();
  check(second !== first, "the object reopened from the durable bytes");
  const answered = await object.query(frame("02-status.bin"));
  check(answered.status === 0, "the reopened object answers again");
  const replayed = await durableLog(storage);
  check(
    replayed.length === landed.length,
    `the reopened log is the acked one, ${replayed.length} bytes`,
  );
}

// -- 3. one boot however many callers race for it ----------------------------
{
  const storage = new MemoryStorage();
  storage.latencyMs = 2; // every storage touch yields, so the race is real
  const object = new FramDurableObjectBase({ storage }, {}, module, {
    spaceId: SPACE,
    instance: { nowMs: () => 1700000000000, arena: { initialPages: 8 } },
  });
  const settled = await Promise.all(
    Array.from({ length: 8 }, () => object.fram()),
  );
  check(
    new Set(settled).size === 1,
    `8 racing callers booted ${new Set(settled).size} instance(s)`,
  );
  const answers = await Promise.all(
    Array.from({ length: 8 }, () => object.query(frame("02-status.bin"))),
  );
  check(
    answers.every((one) => one.status === 0),
    "8 concurrent calls on one instance all answered",
  );
  storage.latencyMs = 0;
}

// -- 4. a >64 KiB log: multi-chunk commit, torn tail, stale-chunk delete -----
{
  const storage = new MemoryStorage();
  const store = new DurableFramStore(storage);
  const fram = await open(store);
  const bulk = [
    "30-batch-bulk-a.bin",
    "30-batch-bulk-b.bin",
    "30-batch-bulk-c.bin",
  ].map(frame);
  let rounds = 0;
  while (fram.log.length < 96 * 1024 && rounds < 400) {
    const result = await fram.transact(bulk[rounds % bulk.length]);
    if (result.status !== 0) break;
    rounds += 1;
  }
  const big = await durableLog(storage);
  check(
    big.length > 65536,
    `the durable log spans chunks at ${big.length} bytes (${rounds} rounds)`,
  );
  const chunkKeys = [...storage.map.keys()].filter(
    (key) => key.startsWith("framlog/") && !key.endsWith("meta"),
  );
  check(
    chunkKeys.length === Math.ceil(big.length / 65536),
    `${chunkKeys.length} chunk keys for ${big.length} bytes`,
  );
  check(
    samePrefix(fram.logBytes(), big) && fram.log.length === big.length,
    "the durable bytes equal the bytes the guest holds",
  );

  // The tail transaction is lost with the isolate: the last chunk must stay
  // exactly as acked rather than half-rewritten.
  const doomed = new DurableFramStore(storage);
  doomed.commit = () => Promise.resolve();
  const dying = await open(doomed);
  await dying.transact(frame("30-batch-bulk-a.bin"));
  const afterTear = await durableLog(storage);
  check(
    afterTear.length === big.length && samePrefix(afterTear, big),
    `the torn tail left the ${big.length}-byte log byte-identical`,
  );
  const survivor = await open(new DurableFramStore(storage));
  const answered = await survivor.query(frame("40-query-bulk-at-limit.bin"));
  check(
    answered.status === 0,
    "the multi-chunk log reopened after the lost tail",
  );
  await survivor.close();
}

// -- 5. the stale-chunk delete path -----------------------------------------
{
  const storage = new MemoryStorage();
  const store = new DurableFramStore(storage);
  await store.load("log");
  const wide = new Uint8Array(200 * 1024).fill(7);
  await store.commit([
    { which: "log", bytes: wide, length: wide.length, lowWater: 0 },
  ]);
  const before = [...storage.map.keys()].filter((key) =>
    key.startsWith("framlog/0"),
  ).length;
  await store.commit([
    { which: "log", bytes: wide, length: 40 * 1024, lowWater: 0 },
  ]);
  const after = [...storage.map.keys()].filter((key) =>
    key.startsWith("framlog/0"),
  ).length;
  const reread = await durableLog(storage);
  check(before === 4 && after === 1, `chunk keys went ${before} -> ${after}`);
  check(
    reread.length === 40 * 1024 && reread.every((byte) => byte === 7),
    `the shrunk object reads back as ${reread.length} bytes`,
  );

  // The two ranges are published in one transaction, so a reader never sees a
  // log that its image does not cover.
  const both = new DurableFramStore(new MemoryStorage());
  await both.load("log");
  await both.load("image");
  await both.commit([
    { which: "log", bytes: wide, length: 70 * 1024, lowWater: 0 },
    { which: "image", bytes: wide, length: 3 * 1024, lowWater: 0 },
  ]);
  check(
    both.commits === 1 && both.stats().log.chunks === 2,
    `one transaction published both ranges (${both.stats().log.chunks} log ` +
      `chunks, ${both.stats().image.chunks} image chunk)`,
  );
}

process.stdout.write(`${notes.join("\n")}\n`);
if (failures.length) {
  process.stdout.write(`run-node: FAIL\n${failures.join("\n")}\n`);
  process.exitCode = 1;
} else {
  process.stdout.write(`run-node: PASS checks=${notes.length}\n`);
}
