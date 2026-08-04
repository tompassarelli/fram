#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
builder="$repo/bin/fram-native-build"
scratch="$(mktemp -d)"
trap 'rm -rf "${scratch:?}"' EXIT INT TERM

fail() {
  echo "fram native build cache smoke: FAIL: $*" >&2
  exit 1
}

mkdir -p "$scratch/tool/bin" "$scratch/cache" "$scratch/sources"
calls="$scratch/native-exe.calls"
: >"$calls"

cat >"$scratch/tool/bin/beagle" <<'FAKE_BEAGLE'
#!/usr/bin/env bash
set -euo pipefail
command="${1:-}"
shift
if [[ "$command" == "native-module" ]]; then
  out=""
  sources=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --out) out="$2"; shift 2 ;;
      --) shift; sources+=("$@"); break ;;
      *) sources+=("$1"); shift ;;
    esac
  done
  [[ -n "$out" && ${#sources[@]} -gt 0 ]] || exit 96
  printf '%s\n' native-module >>"$FAKE_NATIVE_CALLS"
  mkdir -p "$out"
  cat >"$out/module_0.h" <<'C'
#ifndef FAKE_MODULE_0_H
#define FAKE_MODULE_0_H
int fake_native_module(void);
#endif
C
  cat >"$out/module_0.c" <<'C'
#include "module_0.h"
int fake_native_module(void) { return 0; }
C
  printf '%s\n' 'export function w $main() { ret 0 }' >"$out/module_0.ssa"
  cat >"$out/native_shim.h" <<'C'
#ifndef FAKE_NATIVE_SHIM_H
#define FAKE_NATIVE_SHIM_H
int fake_native_shim(void);
#endif
C
  cat >"$out/native_shim.c" <<'C'
#include "native_shim.h"
int fake_native_shim(void) { return 0; }
C
  {
    printf '%s\n' \
      'stage source-seal ACCEPTED' \
      'stage source-to-typed ACCEPTED' \
      'stage typed-to-native COMPLETE' \
      'native-lowering-result NativeLoweringCompleteV0' \
      'materialize-c17 OK module_0.h module_0.c' \
      'materialize-qbe OK module_0.ssa' \
      'obligation-projection PASS valid-ssa' \
      'obligation-projection PASS exhaustive-matches' \
      'obligation-projection PASS closed-layouts' \
      'obligation-projection PASS checked-arithmetic' \
      'obligation-projection PASS legal-abi' \
      'obligation-projection PASS discharged-tokens' \
      'obligation-projection PASS bounded-effects' \
      'result PASS'
  } >"$out/report.txt"
  exit 0
fi
[[ "$command" == "native-exe" ]] || exit 97
out=""
entry=""
cc=""
artifacts=""
sources=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --entry) entry="$2"; shift 2 ;;
    --cc) cc="$2"; shift 2 ;;
    --artifacts) artifacts="$2"; shift 2 ;;
    --) shift; sources+=("$@"); break ;;
    *) sources+=("$1"); shift ;;
  esac
done
printf '%s\n' "$entry" >>"$FAKE_NATIVE_CALLS"
mkdir -p "$artifacts" "$(dirname "$out")"
printf '%s\n' '/* fake module */' >"$artifacts/module_0.h"
printf '%s\n' '/* fake module */' >"$artifacts/module_0.c"
printf '%s\n' 'export function w $main() { ret 0 }' >"$artifacts/module_0.ssa"
printf '%s\n' '/* fake shim */' >"$artifacts/native_shim.c"
printf '%s\n' '/* fake shim */' >"$artifacts/native_shim.h"
printf '%s\n' '/* fake entry */' >"$artifacts/native_entry.c"
cat >"$artifacts/probe.c" <<'C'
int main(void) { return 0; }
C
"$cc" -std=c17 -pedantic -Wall -Wextra -Werror \
  "$artifacts/probe.c" -o "$out"

bad=0
slow=0
for source in "${sources[@]}"; do
  grep -Fq BAD_OBLIGATION "$source" && bad=1
  grep -Fq SLOW_BUILD "$source" && slow=1
done
[[ "$slow" == 0 ]] || sleep 0.2
{
  printf '%s\n' \
    'stage source-seal ACCEPTED' \
    'stage source-to-typed ACCEPTED' \
    'stage typed-to-native COMPLETE' \
    'native-lowering-result NativeLoweringCompleteV0' \
    'materialize-c17 OK module_0.h module_0.c' \
    'materialize-qbe OK module_0.ssa'
  printf 'obligation-projection %s valid-ssa\n' "$([[ "$bad" == 0 ]] && echo PASS || echo FAIL)"
  printf '%s\n' \
    'obligation-projection PASS exhaustive-matches' \
    'obligation-projection PASS closed-layouts' \
    'obligation-projection PASS checked-arithmetic' \
    'obligation-projection PASS legal-abi' \
    'obligation-projection PASS discharged-tokens' \
    'obligation-projection PASS bounded-effects' \
    'result PASS'
} >"$artifacts/report.txt"
{
  printf 'native-exe-entry PASS name=%s symbol=native_m0_fn_0 return=Nil abi=pure\n' "$entry"
  printf 'native-exe-c17 PASS compiler=%s output=%s\n' "$cc" "$out"
} >"$artifacts/native-exe.report.txt"
cat "$artifacts/native-exe.report.txt"
FAKE_BEAGLE
chmod +x "$scratch/tool/bin/beagle"

printf '%s\n' '(ns demo.main)' '(defn start [] -> Nil nil)' \
  >"$scratch/sources/good.bclj"
build_env=(
  env
  FRAM_BEAGLE="$scratch/tool/bin/beagle"
  FRAM_NATIVE_CACHE="$scratch/cache"
  FRAM_NATIVE_CC="${CC:-cc}"
  FAKE_NATIVE_CALLS="$calls"
)

artifact="$("${build_env[@]}" "$builder" --entry demo.main/start \
  "$scratch/sources/good.bclj")" || fail "initial build failed"
[[ "$artifact" == "$scratch/cache/"* ]] || fail "builder did not print its cache artifact"
[[ -f "$artifact/READY" && -x "$artifact/bin/fram-daemon-native" ]] ||
  fail "promoted artifact is not ready and executable"
"$artifact/bin/fram-daemon-native" || fail "linked native executable did not run"

hit="$("${build_env[@]}" "$builder" --entry demo.main/start \
  "$scratch/sources/good.bclj")" || fail "cache hit failed"
[[ "$hit" == "$artifact" ]] || fail "identical closure missed the cache"
[[ "$(wc -l <"$calls")" == "1" ]] || fail "cache hit rebuilt the artifact"

printf '%s\n' '(ns demo.slow)' ';; SLOW_BUILD' '(defn start [] -> Nil nil)' \
  >"$scratch/sources/slow.bclj"
"${build_env[@]}" "$builder" --entry demo.slow/start \
  "$scratch/sources/slow.bclj" >"$scratch/slow-a.out" &
first_pid=$!
"${build_env[@]}" "$builder" --entry demo.slow/start \
  "$scratch/sources/slow.bclj" >"$scratch/slow-b.out" &
second_pid=$!
wait "$first_pid" || fail "first concurrent build failed"
wait "$second_pid" || fail "second concurrent build failed"
cmp -s "$scratch/slow-a.out" "$scratch/slow-b.out" ||
  fail "concurrent builders observed different artifacts"
[[ "$(wc -l <"$calls")" == "2" ]] || fail "per-closure lock allowed a duplicate build"

printf '%s\n' '(ns demo.bad)' ';; BAD_OBLIGATION' '(defn start [] -> Nil nil)' \
  >"$scratch/sources/bad.bclj"
if "${build_env[@]}" "$builder" --entry demo.bad/start \
    "$scratch/sources/bad.bclj" >"$scratch/bad.out" 2>"$scratch/bad.err"; then
  fail "failed native obligation was promoted"
fi
grep -Fq 'obligation-projection PASS valid-ssa' "$scratch/bad.err" ||
  fail "failed obligation did not name the exact missing gate"
[[ "$(find "$scratch/cache" -mindepth 2 -maxdepth 2 -name READY | wc -l)" == "2" ]] ||
  fail "a failed build exposed a READY artifact"
[[ -z "$(find "$scratch/cache/.tmp" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
  fail "temporary artifacts survived the build"

adapter="$scratch/sources/serve_flat_generated.c"
cat >"$adapter" <<'C'
#include "serve_flat_host.h"

static void clear_error(char *error, size_t capacity) {
  if (capacity > 0u) {
    error[0] = '\0';
  }
}

uint32_t fram_serve_flat_generated_abi(void) {
  return FRAM_SERVE_FLAT_GENERATED_ABI;
}

int fram_serve_flat_store_boot(const char *log_path,
                               const char *space_id,
                               fram_serve_flat_store **store_out,
                               char *error,
                               size_t capacity) {
  (void)log_path;
  (void)space_id;
  *store_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVE_FLAT_FATAL;
}

int fram_serve_flat_store_dispatch(fram_serve_flat_store *store,
                                   const fram_serve_flat_request *request,
                                   fram_serve_flat_response **response_out,
                                   char *error,
                                   size_t capacity) {
  (void)store;
  (void)request;
  *response_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVE_FLAT_FATAL;
}

int fram_serve_flat_store_shutdown(fram_serve_flat_store *store,
                                   char *error,
                                   size_t capacity) {
  (void)store;
  clear_error(error, capacity);
  return FRAM_SERVE_FLAT_OK;
}

int fram_serve_flat_codec_read_request(int fd,
                                       fram_serve_flat_request **request_out,
                                       char *error,
                                       size_t capacity) {
  (void)fd;
  *request_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVE_FLAT_FATAL;
}

int fram_serve_flat_codec_write_response(
    int fd,
    const fram_serve_flat_response *response,
    char *error,
    size_t capacity) {
  (void)fd;
  (void)response;
  clear_error(error, capacity);
  return FRAM_SERVE_FLAT_FATAL;
}

void fram_serve_flat_codec_release_request(fram_serve_flat_request *request) {
  (void)request;
}

void fram_serve_flat_codec_release_response(fram_serve_flat_response *response) {
  (void)response;
}
C

calls_before_host="$(wc -l <"$calls")"
host_artifact="$("${build_env[@]}" "$builder" --host serve-flat \
  --adapter "$adapter" "$scratch/sources/good.bclj")" ||
  fail "serve-flat host build failed"
[[ -f "$host_artifact/READY" && -x "$host_artifact/bin/fram-daemon-native" ]] ||
  fail "serve-flat host artifact is not ready and executable"
grep -Fqx 'native-host-abi PASS host=serve-flat exports=8' \
  "$host_artifact/native-host.report.txt" ||
  fail "serve-flat host artifact omitted its eight-export receipt"
if "$host_artifact/bin/fram-daemon-native" serve \
    >"$scratch/host.out" 2>"$scratch/host.err"; then
  fail "serve-flat host accepted the unsupported serve contract"
fi
grep -Fq 'this host implements only the deployed serve-flat contract' \
  "$scratch/host.err" || fail "linked serve-flat host main did not run"

host_hit="$("${build_env[@]}" "$builder" --host serve-flat \
  --adapter "$adapter" "$scratch/sources/good.bclj")" ||
  fail "serve-flat host cache hit failed"
[[ "$host_hit" == "$host_artifact" ]] || fail "serve-flat host missed the cache"
[[ "$(wc -l <"$calls")" == "$((calls_before_host + 1))" ]] ||
  fail "serve-flat host cache hit rebuilt the native module"

sed '/^void fram_serve_flat_codec_release_response/,/^}/d' \
  "$adapter" >"$adapter.incomplete"
mv "$adapter.incomplete" "$adapter"
if "${build_env[@]}" "$builder" --host serve-flat --adapter "$adapter" \
    "$scratch/sources/good.bclj" \
    >"$scratch/missing-export.out" 2>"$scratch/missing-export.err"; then
  fail "serve-flat host linked without all eight generated ABI exports"
fi
grep -Fq 'fram_serve_flat_codec_release_response' "$scratch/missing-export.err" ||
  fail "serve-flat host link did not name the missing ABI export"
[[ "$(find "$scratch/cache" -mindepth 2 -maxdepth 2 -name READY | wc -l)" == "3" ]] ||
  fail "failed serve-flat host link exposed a READY artifact"
[[ -z "$(find "$scratch/cache/.tmp" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
  fail "failed serve-flat host link left temporary artifacts"

echo "fram native build cache smoke: PASS"
