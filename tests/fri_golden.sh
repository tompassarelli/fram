#!/usr/bin/env bash
# FRI2 cache and rotation gate for the current binary persistence contract.
set -euo pipefail

cd "$(dirname "$0")/.."

env -u FRAM_TELEMETRY_LOG bb -cp out tests/fri_cache_v2_test.clj
env -u FRAM_TELEMETRY_LOG bb -cp out tests/rotations_v2_test.clj

if rg -n 'clojure\.edn|pr-str|read-string' \
  fri.clj src/fri.bclj src/fri_port.bclj out/fri.clj out/fri_port.clj; then
  echo "fri-v2: EDN parser/printer remains in the cache persistence spine" >&2
  exit 1
fi

echo "fri-v2: cache, rotations, and binary persistence passed"
