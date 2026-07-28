#!/usr/bin/env bash
# Store-ID hash tries versus immutable mmap rotations. Scratch state only.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CONTRACT="$HERE/scenario-contract.edn"
SIZES="${INDEX_BENCH_SIZES:-3000,30000,300000}"
ROTATION_SIZE="${INDEX_BENCH_ROTATION_SIZE:-350701}"
RUNS="${INDEX_BENCH_RUNS:-2}"
ENGINES="${INDEX_BENCH_ENGINES:-store-id-hash,mmap-rotations}"
OUTPUT="${INDEX_BENCH_OUTPUT:-$(mktemp /tmp/fram-index-architecture.XXXXXX.jsonl)}"

[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || {
  echo "INDEX_BENCH_RUNS must be a positive integer" >&2
  exit 2
}

: >"$OUTPUT"

run_adapter() {
  local engine="$1" size="$2" run="$3" raw rows
  raw="$(cd "$ROOT" && bb -cp out "$HERE/adapters/index_architecture.clj" \
    "$engine" "$size" "$run" "$CONTRACT")"
  printf '%s\n' "$raw" >&2
  rows="$(sed -n 's/^BENCHROW //p' <<<"$raw")"
  [[ -n "$rows" ]] || {
    echo "$engine emitted no BENCHROW records" >&2
    return 1
  }
  printf '%s\n' "$rows" >>"$OUTPUT"
}

IFS=',' read -r -a sizes <<<"$SIZES"
sizes+=("$ROTATION_SIZE")
IFS=',' read -r -a engines <<<"$ENGINES"

for size in "${sizes[@]}"; do
  [[ "$size" =~ ^[1-9][0-9]*$ ]] || {
    echo "index corpus sizes must be positive integers: $size" >&2
    exit 2
  }
  for run in $(seq 1 "$RUNS"); do
    if (( run % 2 == 0 )); then
      for ((index=${#engines[@]} - 1; index >= 0; index--)); do
        engine="${engines[$index]}"
        echo "running engine=$engine corpus-triples=$size run=$run" >&2
        run_adapter "$engine" "$size" "$run"
      done
    else
      for engine in "${engines[@]}"; do
        echo "running engine=$engine corpus-triples=$size run=$run" >&2
        run_adapter "$engine" "$size" "$run"
      done
    fi
  done
done

bb "$HERE/index-report.bb" "$OUTPUT" "$CONTRACT" "$RUNS"
echo "raw-results=$OUTPUT" >&2
