#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bb -cp out tests/daemon_read_cli_test.clj

scratch="$(mktemp -d)"
trap 'rm -rf "${scratch:?}"' EXIT
mkdir -p "$scratch/threads"
printf '%s\n' \
  '{:tx 1, :op "assert", :l "@offline", :p "title", :r "cold fallback", :by "fixture"}' \
  > "$scratch/coordination.log"

actual="$(
  FRAM_PORT=1 \
  FRAM_COORD_RETRY_WINDOW_MS=0 \
  FRAM_LOG="$scratch/coordination.log" \
  FRAM_THREADS="$scratch/threads" \
  "$ROOT/bin/fram" show offline
)"

[[ "$actual" == '  title  cold fallback' ]] || {
  printf 'daemon_read_perf_ratchet: cold fallback mismatch\n%s\n' "$actual" >&2
  exit 1
}

echo "daemon_read_perf_ratchet: PASS — warm exact show/write select no whole-log read; cold fallback answers"
