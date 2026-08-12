// SPDX-License-Identifier: MIT OR Apache-2.0
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { CAPACITY_RUNTIME_CONFIGURATION } from "./config.mjs";
import { canonicalJson, parseProperties } from "./receipt.mjs";

const [profilePath, propertiesPath, progressPath, outputPath] =
  process.argv.slice(2);
if (!profilePath || !propertiesPath || !progressPath || !outputPath) {
  throw new Error(
    "usage: bun write-functional-failure.mjs PROFILE PROPERTIES PROGRESS OUTPUT",
  );
}
const profile = JSON.parse(readFileSync(profilePath, "utf8"));
const properties = parseProperties(readFileSync(propertiesPath, "utf8"));
const progress = existsSync(progressPath)
  ? JSON.parse(readFileSync(progressPath, "utf8"))
  : {
      schema: "fram-cloudflare-workerd-progress/v1",
      phase: "not-started",
      lastCompletedPhase: "not-started",
      completedLoadFrames: 0,
      completedVerifyFrames: 0,
      loadedGuestLinearMemoryBytes: null,
      reopenedGuestLinearMemoryBytes: null,
      runtimeConfigurationObserved: false,
      processTreeMemory: null,
      processTreeCumulativePeakAtLoadedBytes: null,
      processTreeCumulativePeakAfterReopenBytes: null,
    };
if (!existsSync(progressPath)) {
  writeFileSync(progressPath, canonicalJson(progress));
}
writeFileSync(
  outputPath,
  canonicalJson({
    schema: "fram-cloudflare-workerd-functional/v1",
    pass: false,
    corpus: profile,
    loadFrames: progress.completedLoadFrames,
    verifyFrames: progress.completedVerifyFrames,
    responseBytes: 0,
    runtimeConfiguration: {
      ...CAPACITY_RUNTIME_CONFIGURATION,
      observedAtRuntime: progress.runtimeConfigurationObserved === true,
    },
    deploymentBundle: progress.deploymentBundle ?? null,
    guestLinearMemoryHighWaterBytes: null,
    loadedGuestLinearMemoryBytes: progress.loadedGuestLinearMemoryBytes,
    reopenedGuestLinearMemoryBytes: progress.reopenedGuestLinearMemoryBytes,
    conservativeLoadedPlusReopenedGuestLinearBytes: null,
    workerdProcessTreeCumulativePeakAtLoadedBytes:
      progress.processTreeCumulativePeakAtLoadedBytes,
    workerdProcessTreeCumulativePeakAfterReopenBytes:
      progress.processTreeCumulativePeakAfterReopenBytes,
    guestArenaPeakLiveBytes: null,
    durableLogBytes: 0,
    durableImageBytes: 0,
    storageCommits: 0,
    reopenedFromDurableStorage: false,
    failure: {
      memoryResult: properties.MemoryResult,
      controllerExitStatus: Number(properties.ControllerExitStatus),
      lastObservedPhase: progress.phase,
      lastCompletedPhase: progress.lastCompletedPhase,
      completedLoadFrames: progress.completedLoadFrames,
      completedVerifyFrames: progress.completedVerifyFrames,
      processTreeMemoryAtLastProgress: progress.processTreeMemory,
    },
  }),
);
