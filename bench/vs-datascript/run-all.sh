#!/usr/bin/env bash
# One command reproduces the fram-vs-DataScript comparison table (thread
# 019fa01d-ee47-7344-ba27-e7b0e63c86d2). Runs from the repo root.
#
#   bench/vs-datascript/run-all.sh
#
# Stages the same 350k-fact corpus bench/index-rotations/ uses (scratch-only,
# never touches the live :7977 daemon), boots fram's own bench harness against
# it on a HIGH scratch port, runs the in-process DataScript harness against
# the identical corpus, then prints the combined table.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

PORT="${1:-8951}"

if [[ ! -f /tmp/fram-bench/pristine/coordination.log ]]; then
  echo "pristine corpus missing -- see bench/index-rotations/README.md for how to stage it" >&2
  exit 1
fi
mkdir -p /tmp/fram-bench/home

echo "=== fram (this branch) ==="
env -u FRAM_LOG -u FRAM_TELEMETRY_LOG -u FRAM_SINGLE_VALUED \
  bb -cp out bench/index-rotations/cold-query-and-write-throughput.clj vsds "$PORT"

echo
echo "=== DataScript 1.7.3 (same corpus, in-process) ==="
(cd "$HERE" && clojure -M compare.clj)

echo
echo "=== combined table ==="
bb "$HERE/report.bb"
