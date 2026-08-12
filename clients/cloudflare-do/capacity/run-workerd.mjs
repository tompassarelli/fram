// SPDX-License-Identifier: MIT OR Apache-2.0
// Execute the fixed corpus against actual workerd (through Miniflare).
import {
  existsSync,
  mkdirSync,
  readdirSync,
  renameSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { CAPACITY_RUNTIME_CONFIGURATION } from "./config.mjs";
import { canonicalJson, measureBundle } from "./receipt.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const [bundleArgument, corpusArgument, outputArgument, progressArgument] =
  process.argv.slice(2);
if (!bundleArgument || !corpusArgument || !outputArgument) {
  throw new Error(
    "usage: bun capacity/run-workerd.mjs BUNDLE-DIR CORPUS-DIR " +
      "OUTPUT.json [PROGRESS.json]",
  );
}
const bundle = resolve(bundleArgument);
const corpus = resolve(corpusArgument);
const output = resolve(outputArgument);
const progressOutput = resolve(progressArgument ?? `${output}.progress.json`);
const profile = JSON.parse(readFileSync(`${corpus}/profile.json`, "utf8"));
const deploymentBundle = measureBundle(bundle);
const workerFiles = deploymentBundle.files.filter(
  (file) => file.path === "worker.js",
);
const wasmFiles = deploymentBundle.files.filter((file) =>
  file.path.endsWith(".wasm"),
);
if (
  deploymentBundle.files.length !== 2 ||
  workerFiles.length !== 1 ||
  wasmFiles.length !== 1
) {
  throw new Error(
    "Wrangler bundle must contain exactly worker.js and one compiled Wasm module; " +
      `found ${readdirSync(bundle).sort().join(", ")}`,
  );
}
const workerPath = resolve(bundle, workerFiles[0].path);
const wasmPath = resolve(bundle, wasmFiles[0].path);

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

function nonnegativeIntegerFile(path) {
  const value = Number(readFileSync(path, "utf8").trim());
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function processTreeMemory() {
  const locator = process.env.FRAM_CF_CGROUP_LOCATOR;
  if (!locator || !existsSync(locator)) return null;
  const cgroup = readFileSync(locator, "utf8").trim();
  if (!cgroup || !existsSync(`${cgroup}/memory.current`)) return null;
  return {
    currentBytes: nonnegativeIntegerFile(`${cgroup}/memory.current`),
    cumulativePeakBytes: nonnegativeIntegerFile(`${cgroup}/memory.peak`),
  };
}

const load = manifest("manifest-load.txt");
const verify = manifest("manifest-verify.txt");
let progress = {
  schema: "fram-cloudflare-workerd-progress/v1",
  phase: "starting",
  lastCompletedPhase: "not-started",
  completedLoadFrames: 0,
  totalLoadFrames: load.length,
  completedVerifyFrames: 0,
  totalVerifyFrames: verify.length,
  loadedGuestLinearMemoryBytes: null,
  reopenedGuestLinearMemoryBytes: null,
  runtimeConfigurationObserved: false,
  deploymentBundle,
  processTreeMemory: null,
  processTreeCumulativePeakAtLoadedBytes: null,
  processTreeCumulativePeakAfterReopenBytes: null,
};

function recordProgress(update) {
  const memory = processTreeMemory();
  progress = {
    ...progress,
    ...update,
    processTreeMemory: memory,
  };
  if (update.phase === "loaded") {
    progress.processTreeCumulativePeakAtLoadedBytes =
      memory?.cumulativePeakBytes ?? null;
  }
  if (update.phase === "reopened") {
    progress.processTreeCumulativePeakAfterReopenBytes =
      memory?.cumulativePeakBytes ?? null;
  }
  mkdirSync(dirname(progressOutput), { recursive: true });
  const temporary = `${progressOutput}.tmp`;
  writeFileSync(temporary, canonicalJson(progress));
  renameSync(temporary, progressOutput);
  process.stdout.write(
    `capacity-progress: phase=${progress.phase} ` +
      `load=${progress.completedLoadFrames}/${progress.totalLoadFrames} ` +
      `verify=${progress.completedVerifyFrames}/${progress.totalVerifyFrames}\n`,
  );
  return progress;
}

recordProgress({});

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
  modulesRoot: bundle,
  modules: [
    { type: "ESModule", path: workerPath },
    { type: "CompiledWasm", path: wasmPath },
  ],
  scriptPath: workerPath,
  cf: false,
  compatibilityDate: "2026-08-01",
  durableObjects: { FRAM: { className: "CapacityFram", useSQLite: true } },
}));

try {
  let responseBytes = 0;
  for (const [index, row] of load.entries()) {
    recordProgress({ phase: "load-in-flight" });
    responseBytes += (await exchange(mf, row)).bytes;
    recordProgress({
      phase: "load",
      lastCompletedPhase: "load",
      completedLoadFrames: index + 1,
    });
  }
  const loaded = await requestJson(mf, "/stats");
  if (
    canonicalJson(loaded.runtimeConfiguration) !==
    canonicalJson(CAPACITY_RUNTIME_CONFIGURATION)
  ) {
    throw new Error(
      "Worker runtime configuration did not match the capacity profile",
    );
  }
  const loadedProgress = recordProgress({
    phase: "loaded",
    lastCompletedPhase: "loaded",
    loadedGuestLinearMemoryBytes: loaded.engine.linearMemoryBytes,
    runtimeConfigurationObserved: true,
  });
  recordProgress({ phase: "recycle-in-flight" });
  const recycled = await requestJson(mf, "/recycle", { method: "POST" });
  if (recycled.closed?.status !== 0) {
    throw new Error(`first recycle failed: ${recycled.closed?.message}`);
  }
  recordProgress({ phase: "recycled", lastCompletedPhase: "recycled" });
  let titleResponseSha256 = null;
  for (const [index, row] of verify.entries()) {
    recordProgress({ phase: "reopen-in-flight" });
    const result = await exchange(mf, row);
    responseBytes += result.bytes;
    if (row.filename === "verify-title.bin") titleResponseSha256 = result.sha256;
    recordProgress({
      phase: "reopen-verify",
      lastCompletedPhase: "reopen-verify",
      completedVerifyFrames: index + 1,
    });
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
  if (
    canonicalJson(reopened.runtimeConfiguration) !==
      canonicalJson(CAPACITY_RUNTIME_CONFIGURATION)
  ) {
    throw new Error(
      "Worker runtime configuration did not match the capacity profile",
    );
  }
  const reopenedProgress = recordProgress({
    phase: "reopened",
    lastCompletedPhase: "reopened",
    reopenedGuestLinearMemoryBytes: reopened.engine.linearMemoryBytes,
  });
  recordProgress({ phase: "final-recycle-in-flight" });
  const finalRecycle = await requestJson(mf, "/recycle", { method: "POST" });
  if (finalRecycle.closed?.status !== 0) {
    throw new Error(`final recycle failed: ${finalRecycle.closed?.message}`);
  }
  recordProgress({
    phase: "final-recycled",
    lastCompletedPhase: "final-recycled",
  });

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
    deploymentBundle,
    runtimeConfiguration: {
      ...CAPACITY_RUNTIME_CONFIGURATION,
      observedAtRuntime: true,
    },
    guestLinearMemoryHighWaterBytes: highWater,
    loadedGuestLinearMemoryBytes: loaded.engine.linearMemoryBytes,
    reopenedGuestLinearMemoryBytes: reopened.engine.linearMemoryBytes,
    conservativeLoadedPlusReopenedGuestLinearBytes:
      loaded.engine.linearMemoryBytes + reopened.engine.linearMemoryBytes,
    workerdProcessTreeCumulativePeakAtLoadedBytes:
      loadedProgress.processTreeCumulativePeakAtLoadedBytes,
    workerdProcessTreeCumulativePeakAfterReopenBytes:
      reopenedProgress.processTreeCumulativePeakAfterReopenBytes,
    guestArenaPeakLiveBytes: arenaPeak,
    durableLogBytes: reopened.engine.logBytes,
    durableImageBytes: reopened.engine.imageBytes,
    storageCommits: loaded.storage.commits,
    reopenedFromDurableStorage: true,
    reopenedTitleResponseSha256: titleResponseSha256,
  };
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, canonicalJson(result));
  recordProgress({ phase: "complete", lastCompletedPhase: "complete" });
  process.stdout.write(
    `run-workerd: PASS ${profile.expectedFacts} facts, ` +
      `linear-memory-high-water=${highWater} bytes\n`,
  );
} finally {
  await mf.dispose();
}
