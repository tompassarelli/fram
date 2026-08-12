// SPDX-License-Identifier: MIT OR Apache-2.0
import { readFileSync, writeFileSync } from "node:fs";
import { canonicalJson, parseProperties } from "./receipt.mjs";

const [profilePath, propertiesPath, outputPath] = process.argv.slice(2);
if (!profilePath || !propertiesPath || !outputPath) {
  throw new Error(
    "usage: bun write-functional-failure.mjs PROFILE PROPERTIES OUTPUT",
  );
}
const profile = JSON.parse(readFileSync(profilePath, "utf8"));
const properties = parseProperties(readFileSync(propertiesPath, "utf8"));
writeFileSync(
  outputPath,
  canonicalJson({
    schema: "fram-cloudflare-workerd-functional/v1",
    pass: false,
    corpus: profile,
    loadFrames: 0,
    verifyFrames: 0,
    responseBytes: 0,
    guestLinearMemoryHighWaterBytes: null,
    guestArenaPeakLiveBytes: null,
    durableLogBytes: 0,
    durableImageBytes: 0,
    storageCommits: 0,
    reopenedFromDurableStorage: false,
    failure: {
      result: properties.Result,
      exitStatus: Number(properties.ExecMainStatus),
    },
  }),
);
