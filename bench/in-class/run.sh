#!/usr/bin/env bash
# Durable sole-writer comparison. Scratch files only; no live Fram port/log.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SIZES="${BENCH_SIZES:-3000,30000}"
RUNS="${BENCH_RUNS:-2}"
ADAPTERS="${BENCH_ADAPTERS:-fram,sqlite}"
OUTPUT="${BENCH_OUTPUT:-$(mktemp /tmp/fram-in-class.XXXXXX.jsonl)}"

[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || {
  echo "BENCH_RUNS must be a positive integer" >&2
  exit 2
}
: >"$OUTPUT"

run_adapter() {
  local adapter="$1" size="$2" run="$3" raw row
  case "$adapter" in
    fram)
      raw="$(cd "$ROOT" && bb -cp out "$HERE/adapters/fram.clj" "$size" "$run")"
      ;;
    sqlite)
      raw="$(python3 "$HERE/adapters/sqlite.py" "$size" "$run")"
      ;;
    *)
      echo "unknown adapter: $adapter" >&2
      return 2
      ;;
  esac
  printf '%s\n' "$raw" >&2
  row="$(sed -n 's/^BENCHROW //p' <<<"$raw")"
  [[ "$(wc -l <<<"$row")" -eq 1 && -n "$row" ]] || {
    echo "$adapter emitted zero or multiple BENCHROW records" >&2
    return 1
  }
  printf '%s\n' "$row" >>"$OUTPUT"
}

IFS=',' read -r -a sizes <<<"$SIZES"
IFS=',' read -r -a adapters <<<"$ADAPTERS"
for size in "${sizes[@]}"; do
  [[ "$size" =~ ^[1-9][0-9]*$ && $((size % 3)) -eq 0 ]] || {
    echo "each BENCH_SIZES value must be a positive multiple of 3 live triples: $size" >&2
    exit 2
  }
  for run in $(seq 1 "$RUNS"); do
    for adapter in "${adapters[@]}"; do
      echo "running adapter=$adapter corpus-triples=$size run=$run" >&2
      run_adapter "$adapter" "$size" "$run"
    done
  done
done

bb "$HERE/report.bb" "$OUTPUT"
if [[ -f "$HERE/golden.edn" && "${BENCH_CHECK_GOLDEN:-1}" = "1" ]]; then
  bb "$HERE/check-golden.bb" "$OUTPUT" "$HERE/golden.edn"
fi
echo "raw-results=$OUTPUT" >&2
