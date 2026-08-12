// SPDX-License-Identifier: MIT OR Apache-2.0
// Execute the fixed corpus against actual workerd (through Miniflare).
import {
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { canonicalJson } from "./receipt.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const client = resolve(here, "..");
const [corpusArgument, outputArgument] = process.argv.slice(2);
if (!corpusArgument || !outputArgument) {
  throw new Error("usage: bun capacity/run-workerd.mjs CORPUS-DIR OUTPUT.json");
}
const corpus = resolve(corpusArgument);
const output = resolve(outputArgument);
const profile = JSON.parse(readFileSync(`${corpus}/profile.json`, "utf8"));

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function manifest(name) {
  return readFileSync(`${corpus}/${name}`, "utf8")
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      const [entry, filename, declared, digest, operation] = line.split(" ");
      const bytes = new Uint8Array(readFileSync(`${corpus}/${filename}`));
      if (bytes.length !== Number(declared)) {
        throw new Error(`${filename}: ${bytes.length} bytes, declared ${declared}`);
      }
      if (sha256(bytes) !== digest) {
        throw new Error(`${filename}: sha256 does not match the manifest`);
      }
      return { entry, filename, operation, bytes };
    });
}

async function requestJson(mf, path, init = undefined) {
  const response = await mf.dispatchFetch(`http://localhost${path}`, init);
  const body = await response.json();
  if (!response.ok) throw new Error(`${path}: ${body.error ?? response.status}`);
  return body;
}

async function exchange(mf, row) {
  const body = await requestJson(mf, "/exchange", {
    method: "POST",
    headers: { "x-fram-entry": row.entry },
    body: row.bytes,
  });
  if (body.status !== 0 || body.released !== true || body.responseBytes <= 0) {
    throw new Error(
      `${row.filename}: status=${body.status} released=${body.released} ` +
        `responseBytes=${body.responseBytes} message=${body.message}`,
    );
  }
  if (body.responseHex.length !== body.responseBytes * 2) {
    throw new Error(`${row.filename}: response hex length is inconsistent`);
  }
  return { bytes: body.responseBytes, sha256: sha256(Buffer.from(body.responseHex, "hex")) };
}

const mf = new Miniflare(convertV4MiniflareOptions({
  modulesRoot: client,
  modules: [
    { type: "ESModule", path: `${here}/worker.mjs` },
    { type: "ESModule", path: `${client}/src/adapter.mjs` },
    { type: "ESModule", path: `${client}/src/seams.mjs` },
    { type: "CompiledWasm", path: `${client}/lib/libfram.wasm` },
  ],
  scriptPath: `${here}/worker.mjs`,
  cf: false,
  compatibilityDate: "2026-08-01",
  durableObjects: { FRAM: { className: "CapacityFram", useSQLite: true } },
}));

try {
  const load = manifest("manifest-load.txt");
  const verify = manifest("manifest-verify.txt");
  let responseBytes = 0;
  for (const row of load) responseBytes += (await exchange(mf, row)).bytes;
  const loaded = await requestJson(mf, "/stats");
  const recycled = await requestJson(mf, "/recycle", { method: "POST" });
  if (recycled.closed?.status !== 0) {
    throw new Error(`first recycle failed: ${recycled.closed?.message}`);
  }
  let titleResponseSha256 = null;
  for (const row of verify) {
    const result = await exchange(mf, row);
    responseBytes += result.bytes;
    if (row.filename === "verify-title.bin") titleResponseSha256 = result.sha256;
  }
  const expectedTitleResponseSha256 = readFileSync(
    `${corpus}/expected-title-response.sha256`,
    "utf8",
  ).trim();
  if (titleResponseSha256 !== expectedTitleResponseSha256) {
    throw new Error(
      `reopened title scan mismatch: expected ${expectedTitleResponseSha256}, ` +
        `got ${titleResponseSha256}`,
    );
  }
  const reopened = await requestJson(mf, "/stats");
  const finalRecycle = await requestJson(mf, "/recycle", { method: "POST" });
  if (finalRecycle.closed?.status !== 0) {
    throw new Error(`final recycle failed: ${finalRecycle.closed?.message}`);
  }

  const engineSamples = [loaded.engine, recycled.before, reopened.engine,
    finalRecycle.before].filter(Boolean);
  const highWater = Math.max(...engineSamples.map((one) => one.linearMemoryBytes));
  const arenaPeak = Math.max(...engineSamples.map((one) => one.arenaPeakLiveBytes));
  if (loaded.engine.logBytes <= 0 || reopened.engine.logBytes !== loaded.engine.logBytes) {
    throw new Error(
      `durable reopen mismatch: loaded=${loaded.engine.logBytes}, ` +
        `reopened=${reopened.engine.logBytes}`,
    );
  }
  const result = {
    schema: "fram-cloudflare-workerd-functional/v1",
    pass: true,
    corpus: profile,
    loadFrames: load.length,
    verifyFrames: verify.length,
    responseBytes,
    guestLinearMemoryHighWaterBytes: highWater,
    guestArenaPeakLiveBytes: arenaPeak,
    durableLogBytes: reopened.engine.logBytes,
    durableImageBytes: reopened.engine.imageBytes,
    storageCommits: loaded.storage.commits,
    reopenedFromDurableStorage: true,
    reopenedTitleResponseSha256: titleResponseSha256,
  };
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, canonicalJson(result));
  process.stdout.write(
    `run-workerd: PASS ${profile.expectedFacts} facts, ` +
      `linear-memory-high-water=${highWater} bytes\n`,
  );
} finally {
  await mf.dispose();
}
