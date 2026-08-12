// SPDX-License-Identifier: MIT OR Apache-2.0
import { createHash } from "node:crypto";
import {
  readFileSync,
  readdirSync,
  statSync,
} from "node:fs";
import { gzipSync } from "node:zlib";
import { relative, resolve } from "node:path";

export const RAW_BUNDLE_LIMIT_BYTES = 64 * 1024 * 1024;
export const COMPRESSED_LIMIT_BYTES = Object.freeze({
  free: 3 * 1024 * 1024,
  paid: 10 * 1024 * 1024,
});
export const ISOLATE_MEMORY_LIMIT_BYTES = 128 * 1024 * 1024;

export function canonicalJson(value) {
  const normalize = (one) => {
    if (Array.isArray(one)) return one.map(normalize);
    if (one && typeof one === "object") {
      return Object.fromEntries(
        Object.keys(one)
          .sort()
          .map((key) => [key, normalize(one[key])]),
      );
    }
    return one;
  };
  return `${JSON.stringify(normalize(value), null, 2)}\n`;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function filesUnder(root, at = root) {
  return readdirSync(at, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap((entry) => {
      const path = resolve(at, entry.name);
      return entry.isDirectory() ? filesUnder(root, path) : [path];
    });
}

export function measureBundle(bundleDirectory) {
  const root = resolve(bundleDirectory);
  const files = filesUnder(root)
    .filter((path) => {
      const name = relative(root, path).replaceAll("\\", "/");
      return name !== "README.md" && !name.endsWith(".map");
    })
    .map((path) => {
      const bytes = readFileSync(path);
      return {
        path: relative(root, path).replaceAll("\\", "/"),
        bytes: bytes.length,
        gzipBytes: gzipSync(bytes, { level: 9, mtime: 0 }).length,
        sha256: sha256(bytes),
      };
    });
  return {
    files,
    emittedBytes: files.reduce((sum, file) => sum + file.bytes, 0),
    conservativeGzipBytes: files.reduce(
      (sum, file) => sum + file.gzipBytes,
      0,
    ),
  };
}

function unitBytes(value, unit) {
  const scale = { B: 1, KiB: 1024, MiB: 1024 * 1024 }[unit];
  if (!scale) throw new Error(`unsupported Wrangler size unit: ${unit}`);
  return Math.round(Number(value) * scale);
}

export function parseWranglerUpload(text) {
  const match = text.match(
    /Total Upload:\s*([0-9.]+)\s*(B|KiB|MiB)\s*\/\s*gzip:\s*([0-9.]+)\s*(B|KiB|MiB)/i,
  );
  if (!match) throw new Error("Wrangler output omitted Total Upload / gzip");
  return {
    reportedRawBytes: unitBytes(match[1], match[2]),
    reportedGzipBytes: unitBytes(match[3], match[4]),
    note: "Wrangler CLI values are converted from its displayed rounded units",
  };
}

export function parseProperties(text) {
  return Object.fromEntries(
    text
      .trim()
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const at = line.indexOf("=");
        if (at <= 0) throw new Error(`invalid systemd property row: ${line}`);
        return [line.slice(0, at), line.slice(at + 1)];
      }),
  );
}

function integerProperty(properties, name) {
  const value = Number(properties[name]);
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`systemd ${name} is not a non-negative integer`);
  }
  return value;
}

export function makeReceipt({
  plan,
  bundle,
  wrangler,
  functional,
  cgroup,
  wasm,
}) {
  const compressedLimit = COMPRESSED_LIMIT_BYTES[plan];
  if (!compressedLimit) throw new Error(`unknown Cloudflare plan: ${plan}`);
  const memoryPeakBytes = integerProperty(cgroup, "MemoryPeak");
  const memoryMaxBytes = integerProperty(cgroup, "MemoryMax");
  const memorySwapMaxBytes = integerProperty(cgroup, "MemorySwapMax");
  const exitStatus = integerProperty(cgroup, "ExecMainStatus");
  const guestLinearMemoryMeasured =
    Number.isSafeInteger(functional.guestLinearMemoryHighWaterBytes) &&
    functional.guestLinearMemoryHighWaterBytes > 0;
  const checks = {
    cgroupLimitIs128MiB: memoryMaxBytes === ISOLATE_MEMORY_LIMIT_BYTES,
    workerdProcessSucceeded: cgroup.Result === "success" && exitStatus === 0,
    cgroupSwapDisabled: memorySwapMaxBytes === 0,
    emittedRawWithinWranglerLimit:
      bundle.emittedBytes <= RAW_BUNDLE_LIMIT_BYTES,
    functionalCorpusPassed: functional.pass === true,
    guestLinearMemoryMeasured,
    guestLinearMemoryWithin128MiB:
      guestLinearMemoryMeasured &&
      functional.guestLinearMemoryHighWaterBytes <= ISOLATE_MEMORY_LIMIT_BYTES,
    workerdProcessTreePeakWithin128MiB:
      memoryPeakBytes <= ISOLATE_MEMORY_LIMIT_BYTES,
    wranglerCompressedWithinPlanLimit:
      wrangler.reportedGzipBytes <= compressedLimit &&
      bundle.conservativeGzipBytes <= compressedLimit,
    wranglerRawWithinLimit:
      wrangler.reportedRawBytes <= RAW_BUNDLE_LIMIT_BYTES,
  };
  return {
    schema: "fram-cloudflare-capacity/v1",
    evidenceKind: "capacity-only-not-release-proof",
    pass: Object.values(checks).every(Boolean),
    checks,
    dependencies: {
      miniflare: {
        version: "5.20260804.1-alpha",
        sourceRevision: "15fc56824836570ca291aa148be72d2d62f59566",
      },
      workerd: {
        version: "1.20260804.1",
        sourceRevision: "abd3d71c2d9a3bd6f27072091d9368fd18ca02e6",
      },
      wrangler: {
        version: "4.121.0",
        sourceRevision: "15fc56824836570ca291aa148be72d2d62f59566",
      },
    },
    bundle: {
      cloudflarePlan: plan,
      wranglerRawBundleLimitBytes: RAW_BUNDLE_LIMIT_BYTES,
      platformCompressedBundleLimitBytes: compressedLimit,
      wranglerReportedRawBytes: wrangler.reportedRawBytes,
      wranglerReportedGzipBytes: wrangler.reportedGzipBytes,
      wranglerReportNote: wrangler.note,
      emittedModuleBytes: bundle.emittedBytes,
      conservativePerModuleGzipBytes: bundle.conservativeGzipBytes,
      files: bundle.files,
    },
    functional: {
      corpus: functional.corpus,
      loadFrames: functional.loadFrames,
      verifyFrames: functional.verifyFrames,
      responseBytes: functional.responseBytes,
      reopenedFromDurableStorage: functional.reopenedFromDurableStorage,
      durableLogBytes: functional.durableLogBytes,
      durableImageBytes: functional.durableImageBytes,
      storageCommits: functional.storageCommits,
      reopenedTitleResponseSha256: functional.reopenedTitleResponseSha256,
    },
    memory: {
      cloudflareDocumentedIsolateLimitBytes: ISOLATE_MEMORY_LIMIT_BYTES,
      enforcement: "linux-cgroup-v2-workerd-process-tree",
      enforcedProcesses: "workerd and every descendant",
      controllerProcesses: "Bun and Miniflare excluded",
      relationshipToProduction:
        "conservative whole-workerd-runtime proxy for one workload, not isolate accounting",
      workerdProcessTreeLimitBytes: memoryMaxBytes,
      workerdProcessTreePeakBytes: memoryPeakBytes,
      workerdProcessTreeSwapMaxBytes: memorySwapMaxBytes,
      guestLinearMemoryHighWaterBytes:
        functional.guestLinearMemoryHighWaterBytes,
      guestArenaPeakLiveBytes: functional.guestArenaPeakLiveBytes,
      productionIsolatePeakMeasured: false,
      proxyNote:
        "The cgroup includes the full workerd runtime subtree; Cloudflare production isolate-only accounting is not locally observable.",
    },
    wasm: {
      abi: wasm.abi,
      artifactAddress: wasm.artifactAddress,
      framCommit: wasm.framCommit,
      host: wasm.host,
      wasmBytes: wasm.wasmBytes,
      wasmSha256: wasm.wasmSha256,
    },
    exclusions: {
      accessIdentityAdminAndDeploymentProofMeasured: false,
      backupPeakMeasured: false,
      backupPeakReason:
        "The capacity corpus does not invoke the separate export or restore capability.",
      productionIsolatePeakMeasured: false,
      releaseProof: false,
    },
  };
}

export function loadReceiptInputs({
  plan,
  bundleDirectory,
  wranglerLog,
  functionalResult,
  cgroupProperties,
  wasmProvenance,
}) {
  for (const path of [wranglerLog, functionalResult, cgroupProperties,
    wasmProvenance]) {
    if (!statSync(path).isFile()) throw new Error(`not a file: ${path}`);
  }
  return makeReceipt({
    plan,
    bundle: measureBundle(bundleDirectory),
    wrangler: parseWranglerUpload(readFileSync(wranglerLog, "utf8")),
    functional: JSON.parse(readFileSync(functionalResult, "utf8")),
    cgroup: parseProperties(readFileSync(cgroupProperties, "utf8")),
    wasm: JSON.parse(readFileSync(wasmProvenance, "utf8")),
  });
}
