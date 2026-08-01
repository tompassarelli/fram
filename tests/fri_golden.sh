#!/usr/bin/env bash
# FRI2 is a model cut, not a compatibility port. The gate proves the new cache
# contract directly and ratchets legacy assertion-id vocabulary out of runtime.
set -euo pipefail

cd "$(dirname "$0")/.."

env -u FRAM_TELEMETRY_LOG bb -cp out tests/fri_cache_v2_test.clj
env -u FRAM_TELEMETRY_LOG bb -cp out tests/rotations_v2_test.clj

if rg -n '(?i)\bcid\b|fact-id|StoredFact|StoredTxOf' \
  fri.clj rotations.clj src/fri.bclj src/fri_port.bclj \
  out/fri.clj out/fri_port.clj; then
  echo "fri-v2: legacy assertion-id vocabulary remains in runtime" >&2
  exit 1
fi

echo "fri-v2: cache, rotations, and source ratchet passed"
