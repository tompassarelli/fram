#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

die() {
  echo "materialize-publication-roots: $*" >&2
  exit 2
}

usage() {
  cat <<'USAGE'
Usage: materialize-publication-roots.sh \
  --source-root DIR --native-cache DIR --wasm-cache DIR --output DIR

Materializes the independent static-native and wasm32 publication roots in
parallel. On success, DIR contains native.artifact, wasm.artifact, timings.tsv,
and the two builder logs.

FRAM_BEAGLE and FRAM_WASI_CC must name the pinned compiler and WASI compiler.
FRAM_NATIVE_CC may override the static native compiler; otherwise musl-gcc is
resolved from PATH.
USAGE
}

source_root=""
native_cache=""
wasm_cache=""
output=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-root) [[ $# -ge 2 ]] || die "$1 needs a directory"; source_root="$2"; shift 2 ;;
    --native-cache) [[ $# -ge 2 ]] || die "$1 needs a directory"; native_cache="$2"; shift 2 ;;
    --wasm-cache) [[ $# -ge 2 ]] || die "$1 needs a directory"; wasm_cache="$2"; shift 2 ;;
    --output) [[ $# -ge 2 ]] || die "$1 needs a directory"; output="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
[[ -n "$source_root" && -n "$native_cache" && -n "$wasm_cache" &&
  -n "$output" ]] || die "all four directory options are required"
source_root="$(realpath "$source_root")"
builder="$source_root/bin/fram-native-build"
closure="$source_root/native/core_closure_sources.txt"
[[ -x "$builder" ]] || die "native builder is unavailable: $builder"
[[ -f "$closure" && ! -L "$closure" ]] || die "source closure is unavailable: $closure"
[[ -n "${FRAM_BEAGLE:-}" ]] || die "FRAM_BEAGLE must name the pinned compiler"
[[ -n "${FRAM_WASI_CC:-}" ]] || die "FRAM_WASI_CC must name the WASI compiler"
native_cc="${FRAM_NATIVE_CC:-$(command -v musl-gcc || true)}"
[[ -n "$native_cc" && -x "$native_cc" ]] ||
  die "FRAM_NATIVE_CC must name an executable, or musl-gcc must be on PATH"

mkdir -p "$native_cache" "$wasm_cache" "$output"
output="$(cd "$output" && pwd -P)"
mapfile -t sources < <(sed "s#^#$source_root/#" "$closure")
[[ "${#sources[@]}" -gt 0 ]] || die "$closure is empty"
for source in "${sources[@]}"; do
  [[ -f "$source" ]] || die "closure source is unavailable: $source"
done

native_result="$output/native.artifact"
wasm_result="$output/wasm.artifact"
native_log="$output/native.log"
wasm_log="$output/wasm.log"
timings="$output/timings.tsv"
rm -f "$native_result" "$wasm_result" "$native_log" "$wasm_log" "$timings"

native_pid=""
wasm_pid=""
cleanup() {
  local pid
  for pid in "$native_pid" "$wasm_pid"; do
    [[ -n "$pid" ]] || continue
    if kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_native() {
  local started finished status
  started="$(date +%s%N)"
  set +e
  timeout --foreground --signal=TERM --kill-after=10s 2100s \
    nice -n 19 env \
      FRAM_BEAGLE="$FRAM_BEAGLE" \
      FRAM_NATIVE_CC="$native_cc" \
      FRAM_NATIVE_STATIC=1 \
      FRAM_NATIVE_CACHE="$native_cache" \
      "$builder" --host server "${sources[@]}" >"$native_result" 2>"$native_log"
  status=$?
  set -e
  finished="$(date +%s%N)"
  printf 'native\t%s\t%s\t%s\n' "$started" "$finished" "$status" \
    >"$output/native.timing"
  return "$status"
}

run_wasm() {
  local started finished status
  started="$(date +%s%N)"
  set +e
  timeout --foreground --signal=TERM --kill-after=10s 1500s \
    nice -n 19 env \
      FRAM_BEAGLE="$FRAM_BEAGLE" \
      FRAM_WASI_CC="$FRAM_WASI_CC" \
      FRAM_NATIVE_CACHE="$wasm_cache" \
      "$builder" --host wasm-embed --abi wasm32 \
        "${sources[@]}" >"$wasm_result" 2>"$wasm_log"
  status=$?
  set -e
  finished="$(date +%s%N)"
  printf 'wasm\t%s\t%s\t%s\n' "$started" "$finished" "$status" \
    >"$output/wasm.timing"
  return "$status"
}

run_native &
native_pid=$!
run_wasm &
wasm_pid=$!
set +e
wait "$native_pid"
native_status=$?
native_pid=""
wait "$wasm_pid"
wasm_status=$?
wasm_pid=""
set -e
cat "$output/native.timing" "$output/wasm.timing" >"$timings"

if [[ "$native_status" -ne 0 || "$wasm_status" -ne 0 ]]; then
  [[ "$native_status" == 0 ]] || tail -n 240 -- "$native_log" >&2
  [[ "$wasm_status" == 0 ]] || tail -n 240 -- "$wasm_log" >&2
  die "root materialization failed (native=$native_status wasm=$wasm_status)"
fi

for result in "$native_result" "$wasm_result"; do
  mapfile -t result_lines <"$result"
  [[ "${#result_lines[@]}" == 1 && -d "${result_lines[0]}" &&
    -f "${result_lines[0]}/READY" ]] ||
    die "builder did not return one immutable READY directory: $result"
done
trap - EXIT INT TERM
