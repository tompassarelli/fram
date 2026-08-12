// SPDX-License-Identifier: MIT OR Apache-2.0
import { describe, expect, test } from "bun:test";
import {
  COMPRESSED_LIMIT_BYTES,
  ISOLATE_MEMORY_LIMIT_BYTES,
  RAW_BUNDLE_LIMIT_BYTES,
  canonicalJson,
  makeReceipt,
  parseWranglerUpload,
} from "./receipt.mjs";

function fixture(overrides = {}) {
  return {
    plan: "free",
    bundle: { emittedBytes: 700, conservativeGzipBytes: 400, files: [] },
    wrangler: {
      reportedRawBytes: 700,
      reportedGzipBytes: 400,
      note: "fixture",
    },
    functional: {
      pass: true,
      corpus: { schema: "fram-wiki-capacity-corpus/v1" },
      loadFrames: 1,
      verifyFrames: 2,
      responseBytes: 3,
      reopenedFromDurableStorage: true,
      durableLogBytes: 4,
      durableImageBytes: 0,
      storageCommits: 1,
      guestLinearMemoryHighWaterBytes: 8 * 1024 * 1024,
      guestArenaPeakLiveBytes: 1024,
    },
    cgroup: {
      Result: "success",
      ExecMainStatus: "0",
      MemoryPeak: `${64 * 1024 * 1024}`,
      MemoryMax: `${ISOLATE_MEMORY_LIMIT_BYTES}`,
      MemorySwapMax: "0",
    },
    wasm: {
      abi: "wasm32",
      artifactAddress: "fixture",
      framCommit: "0".repeat(40),
      host: "wasm-embed",
      wasmBytes: 123,
      wasmSha256: "0".repeat(64),
    },
    ...overrides,
  };
}

describe("Cloudflare capacity receipt", () => {
  test("parses Wrangler's displayed upload units", () => {
    expect(
      parseWranglerUpload("Total Upload: 1.50 MiB / gzip: 200.25 KiB"),
    ).toMatchObject({
      reportedRawBytes: 1572864,
      reportedGzipBytes: 205056,
    });
  });

  test("passes only when every bundle, functional, and cgroup check passes", () => {
    const receipt = makeReceipt(fixture());
    expect(receipt.pass).toBe(true);
    expect(receipt.bundle.wranglerRawBundleLimitBytes).toBe(
      RAW_BUNDLE_LIMIT_BYTES,
    );
    expect(receipt.bundle.platformCompressedBundleLimitBytes).toBe(
      COMPRESSED_LIMIT_BYTES.free,
    );
    expect(receipt.memory.productionIsolatePeakMeasured).toBe(false);
    expect(receipt.exclusions.backupPeakMeasured).toBe(false);
    expect(receipt.exclusions.releaseProof).toBe(false);
  });

  test("fails at a process peak above the enforced ceiling", () => {
    const input = fixture();
    input.cgroup.MemoryPeak = `${ISOLATE_MEMORY_LIMIT_BYTES + 1}`;
    const receipt = makeReceipt(input);
    expect(receipt.pass).toBe(false);
    expect(receipt.checks.workerdProcessTreePeakWithin128MiB).toBe(false);
  });

  test("fails when the selected plan's compressed upload is too large", () => {
    const input = fixture();
    input.wrangler.reportedGzipBytes = COMPRESSED_LIMIT_BYTES.free + 1;
    const receipt = makeReceipt(input);
    expect(receipt.pass).toBe(false);
    expect(receipt.checks.wranglerCompressedWithinPlanLimit).toBe(false);
  });

  test("canonical JSON sorts recursively and ends in one newline", () => {
    expect(canonicalJson({ z: { b: 2, a: 1 }, a: 0 })).toBe(
      '{\n  "a": 0,\n  "z": {\n    "a": 1,\n    "b": 2\n  }\n}\n',
    );
  });
});
