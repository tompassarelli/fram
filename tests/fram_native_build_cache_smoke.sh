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

#include "native_shim.h"

#include <stdint.h>

typedef int64_t native_m0_type_0;
typedef struct native_m0_type_1 {
  int64_t field_0;
  uint64_t field_1;
  uint64_t field_2;
  int64_t field_3;
  native_vec *field_4;
} native_m0_type_1;
typedef const char *native_m0_type_2;
typedef native_vec *native_m0_type_4;
typedef struct native_m0_type_5 {
  int64_t field_0;
  uint64_t field_1;
  uint64_t field_2;
  native_vec *field_3;
} native_m0_type_5;
typedef struct native_m0_type_6 {
  int64_t field_0;
  native_vec *field_1;
} native_m0_type_6;
typedef struct native_m0_type_7 { int64_t field_0; } native_m0_type_7;
typedef uint64_t native_m0_type_8;
typedef struct native_m0_type_9 {
  int64_t field_0;
  uint64_t field_1;
} native_m0_type_9;

native_m0_type_1 native_m0_fn_2(native_arena *arena, const native_capability *capability, native_m0_type_2 native_v_0, native_m0_type_2 native_v_1, native_m0_type_4 native_v_2);
native_m0_type_9 native_m0_fn_3(native_m0_type_1 native_v_0);
native_m0_type_6 native_m0_fn_5(native_arena *arena, const native_capability *capability, native_m0_type_5 native_v_0);
native_m0_type_0 native_m0_fn_7(void);
native_m0_type_5 native_m0_fn_11(const native_capability *capability, native_m0_type_1 native_v_0, native_m0_type_7 native_v_1);
native_m0_type_8 native_m0_fn_13(native_m0_type_7 native_v_0);
native_m0_type_8 native_m0_fn_17(const native_capability *capability, native_m0_type_5 native_v_0);
native_m0_type_7 native_m0_fn_19(native_arena *arena, native_m0_type_4 native_v_0);

#endif
C
  cat >"$out/module_0.c" <<'C'
#include "module_0.h"

native_m0_type_1 native_m0_fn_2(native_arena *arena,
                                  const native_capability *capability,
                                  native_m0_type_2 native_v_0,
                                  native_m0_type_2 native_v_1,
                                  native_m0_type_4 native_v_2) {
  (void)arena;
  (void)capability;
  (void)native_v_0;
  (void)native_v_1;
  (void)native_v_2;
  return (native_m0_type_1){.field_0 = 0};
}

native_m0_type_9 native_m0_fn_3(native_m0_type_1 native_v_0) {
  return (native_m0_type_9){.field_0 = native_v_0.field_0, .field_1 = 0};
}

native_m0_type_6 native_m0_fn_5(native_arena *arena,
                                  const native_capability *capability,
                                  native_m0_type_5 native_v_0) {
  (void)arena;
  (void)capability;
  return (native_m0_type_6){.field_0 = native_v_0.field_0,
                            .field_1 = (native_vec *)0};
}

native_m0_type_0 native_m0_fn_7(void) { return 1; }

native_m0_type_5 native_m0_fn_11(const native_capability *capability,
                                   native_m0_type_1 native_v_0,
                                   native_m0_type_7 native_v_1) {
  (void)capability;
  return (native_m0_type_5){.field_0 = native_v_0.field_0 + native_v_1.field_0};
}

native_m0_type_8 native_m0_fn_13(native_m0_type_7 native_v_0) {
  (void)native_v_0;
  return 0;
}

native_m0_type_8 native_m0_fn_17(const native_capability *capability,
                                   native_m0_type_5 native_v_0) {
  (void)capability;
  (void)native_v_0;
  return 0;
}

native_m0_type_7 native_m0_fn_19(native_arena *arena,
                                   native_m0_type_4 native_v_0) {
  (void)arena;
  return (native_m0_type_7){.field_0 = native_v_0 == (native_vec *)0 ? 2 : 0};
}
C
  printf '%s\n' 'export function w $main() { ret 0 }' >"$out/module_0.ssa"
  cat >"$out/native_shim.h" <<'C'
#ifndef FAKE_NATIVE_SHIM_H
#define FAKE_NATIVE_SHIM_H

#include <stdint.h>

typedef struct native_arena { int marker; } native_arena;
typedef struct native_capability { int marker; } native_capability;
typedef struct native_vec { int64_t length; } native_vec;

int fake_native_shim(void);
#endif
C
  cat >"$out/native_shim.c" <<'C'
#include "native_shim.h"
int fake_native_shim(void) { return 0; }
C
  missing_symbol=0
  duplicate_symbol=0
  bad_arity=0
  for source in "${sources[@]}"; do
    grep -Fq MISSING_SERVE_FLAT_SYMBOL "$source" && missing_symbol=1
    grep -Fq DUPLICATE_SERVE_FLAT_SYMBOL "$source" && duplicate_symbol=1
    grep -Fq BAD_SERVE_FLAT_ARITY "$source" && bad_arity=1
  done
  if [[ "$bad_arity" == 1 ]]; then
    sed -i \
      '/native_m0_fn_5/s/);$/, native_m0_type_0 native_v_1);/' \
      "$out/module_0.h"
  fi
  {
    printf '%s\n' \
      'stage source-seal ACCEPTED' \
      'stage source-to-typed ACCEPTED' \
      'stage typed-to-native COMPLETE' \
      'native-lowering-result NativeLoweringCompleteV0' \
      'materialize-c17 OK module_0.h module_0.c' \
      'materialize-qbe OK module_0.ssa' \
      'lowered fn_7 serve-flat-generated-abi 1 blocks' \
      'lowered fn_2 serve-flat-store-boot 1 blocks' \
      'lowered fn_11 serve-flat-store-dispatch! 1 blocks' \
      'lowered fn_3 serve-flat-store-shutdown 1 blocks' \
      'lowered fn_19 serve-flat-codec-read-request 1 blocks' \
      'lowered fn_5 serve-flat-codec-write-response 1 blocks' \
      'lowered fn_13 serve-flat-codec-release-request 1 blocks'
    [[ "$missing_symbol" == 1 ]] ||
      printf '%s\n' 'lowered fn_17 serve-flat-codec-release-response 1 blocks'
    [[ "$duplicate_symbol" == 0 ]] ||
      printf '%s\n' 'lowered fn_23 serve-flat-store-boot 1 blocks'
    printf '%s\n' \
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
#include "serve_flat_symbols.h"

static void clear_error(char *error, size_t capacity) {
  if (capacity > 0u) {
    error[0] = '\0';
  }
}

uint32_t fram_serve_flat_generated_abi(void) {
  native_arena arena = {0};
  native_capability capability = {0};
  native_vec bytes = {0};
  fram_serve_flat_store_boot_return boot =
      FRAM_SERVE_FLAT_CALL_STORE_BOOT(
          &arena, &capability, "log", "space", &bytes);
  fram_serve_flat_codec_read_request_return request =
      FRAM_SERVE_FLAT_CALL_CODEC_READ_REQUEST(
          &arena, &capability, &bytes);
  fram_serve_flat_store_dispatch_return dispatched =
      FRAM_SERVE_FLAT_CALL_STORE_DISPATCH(&arena, &capability, boot, request);
  fram_serve_flat_store_shutdown_return stopped =
      FRAM_SERVE_FLAT_CALL_STORE_SHUTDOWN(&arena, &capability, boot);
  fram_serve_flat_codec_write_response_return written =
      FRAM_SERVE_FLAT_CALL_CODEC_WRITE_RESPONSE(
          &arena, &capability, dispatched);
  fram_serve_flat_codec_release_request_return request_released =
      FRAM_SERVE_FLAT_CALL_CODEC_RELEASE_REQUEST(
          &arena, &capability, request);
  fram_serve_flat_codec_release_response_return response_released =
      FRAM_SERVE_FLAT_CALL_CODEC_RELEASE_RESPONSE(
          &arena, &capability, dispatched);
  fram_serve_flat_generated_abi_return abi =
      FRAM_SERVE_FLAT_CALL_GENERATED_ABI(&arena, &capability);
  int64_t direct_status = stopped.field_0 + written.field_0;
  int64_t valid_log_bytes = boot.field_3;
  native_vec *boot_append = boot.field_4;
  native_vec *response_append = dispatched.field_3;
  native_vec *response_bytes = written.field_1;

  (void)direct_status;
  (void)valid_log_bytes;
  (void)boot_append;
  (void)response_append;
  (void)response_bytes;
  (void)request_released;
  (void)response_released;
  return (uint32_t)abi;
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
symbols_header="$host_artifact/serve_flat_symbols.h"
[[ -f "$symbols_header" ]] || fail "serve-flat host omitted its generated symbol header"
[[ "$(grep -c '^#define FRAM_SERVE_FLAT_SYMBOL_' "$symbols_header")" == "8" ]] ||
  fail "serve-flat symbol header did not contain exactly eight symbol mappings"
[[ "$(grep -c '^#define FRAM_SERVE_FLAT_CALL_' "$symbols_header")" == "8" ]] ||
  fail "serve-flat symbol header did not contain exactly eight normalized calls"
[[ "$(grep -Ec '^typedef [A-Za-z_][A-Za-z0-9_]* fram_serve_flat_[a-z_]+_(return|arg_[0-9]+);$' "$symbols_header")" == "18" ]] ||
  fail "serve-flat symbol header did not contain all eighteen stable type aliases"
required_symbol_lines=(
  '#define FRAM_SERVE_FLAT_SYMBOL_GENERATED_ABI native_m0_fn_7'
  '#define FRAM_SERVE_FLAT_SYMBOL_STORE_BOOT native_m0_fn_2'
  '#define FRAM_SERVE_FLAT_SYMBOL_STORE_DISPATCH native_m0_fn_11'
  '#define FRAM_SERVE_FLAT_SYMBOL_STORE_SHUTDOWN native_m0_fn_3'
  '#define FRAM_SERVE_FLAT_SYMBOL_CODEC_READ_REQUEST native_m0_fn_19'
  '#define FRAM_SERVE_FLAT_SYMBOL_CODEC_WRITE_RESPONSE native_m0_fn_5'
  '#define FRAM_SERVE_FLAT_SYMBOL_CODEC_RELEASE_REQUEST native_m0_fn_13'
  '#define FRAM_SERVE_FLAT_SYMBOL_CODEC_RELEASE_RESPONSE native_m0_fn_17'
  'typedef native_m0_type_1 fram_serve_flat_store_boot_return;'
  'typedef native_m0_type_2 fram_serve_flat_store_boot_arg_0;'
  'typedef native_m0_type_2 fram_serve_flat_store_boot_arg_1;'
  'typedef native_m0_type_4 fram_serve_flat_store_boot_arg_2;'
  'typedef native_m0_type_5 fram_serve_flat_store_dispatch_return;'
  'typedef native_m0_type_9 fram_serve_flat_store_shutdown_return;'
  'typedef native_m0_type_7 fram_serve_flat_codec_read_request_return;'
  'typedef native_m0_type_4 fram_serve_flat_codec_read_request_arg_0;'
  'typedef native_m0_type_6 fram_serve_flat_codec_write_response_return;'
  'typedef native_m0_type_5 fram_serve_flat_codec_write_response_arg_0;'
  'typedef native_m0_type_8 fram_serve_flat_codec_release_request_return;'
  'typedef native_m0_type_7 fram_serve_flat_codec_release_request_arg_0;'
  'typedef native_m0_type_8 fram_serve_flat_codec_release_response_return;'
  'typedef native_m0_type_5 fram_serve_flat_codec_release_response_arg_0;'
  '#define FRAM_SERVE_FLAT_CALL_GENERATED_ABI(arena, capability) FRAM_SERVE_FLAT_SYMBOL_GENERATED_ABI()'
  '#define FRAM_SERVE_FLAT_CALL_STORE_BOOT(arena, capability, arg_0, arg_1, arg_2) FRAM_SERVE_FLAT_SYMBOL_STORE_BOOT((arena), (capability), (arg_0), (arg_1), (arg_2))'
  '#define FRAM_SERVE_FLAT_CALL_STORE_DISPATCH(arena, capability, arg_0, arg_1) FRAM_SERVE_FLAT_SYMBOL_STORE_DISPATCH((capability), (arg_0), (arg_1))'
  '#define FRAM_SERVE_FLAT_CALL_STORE_SHUTDOWN(arena, capability, arg_0) FRAM_SERVE_FLAT_SYMBOL_STORE_SHUTDOWN((arg_0))'
  '#define FRAM_SERVE_FLAT_CALL_CODEC_READ_REQUEST(arena, capability, arg_0) FRAM_SERVE_FLAT_SYMBOL_CODEC_READ_REQUEST((arena), (arg_0))'
  '#define FRAM_SERVE_FLAT_CALL_CODEC_WRITE_RESPONSE(arena, capability, arg_0) FRAM_SERVE_FLAT_SYMBOL_CODEC_WRITE_RESPONSE((arena), (capability), (arg_0))'
  '#define FRAM_SERVE_FLAT_CALL_CODEC_RELEASE_RESPONSE(arena, capability, arg_0) FRAM_SERVE_FLAT_SYMBOL_CODEC_RELEASE_RESPONSE((capability), (arg_0))'
)
for required_line in "${required_symbol_lines[@]}"; do
  grep -Fqx -- "$required_line" "$symbols_header" ||
    fail "serve-flat symbol header omitted: $required_line"
done
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

printf '%s\n' '(ns demo.missing-symbol)' ';; MISSING_SERVE_FLAT_SYMBOL' \
  >"$scratch/sources/missing-symbol.bclj"
if "${build_env[@]}" "$builder" --host serve-flat --adapter "$adapter" \
    "$scratch/sources/missing-symbol.bclj" \
    >"$scratch/missing-symbol.out" 2>"$scratch/missing-symbol.err"; then
  fail "serve-flat host accepted a missing logical symbol"
fi
grep -Fq \
  'exactly one lowered row for serve-flat-codec-release-response (found 0)' \
  "$scratch/missing-symbol.err" ||
  fail "missing serve-flat logical symbol did not fail before link"

printf '%s\n' '(ns demo.duplicate-symbol)' ';; DUPLICATE_SERVE_FLAT_SYMBOL' \
  >"$scratch/sources/duplicate-symbol.bclj"
if "${build_env[@]}" "$builder" --host serve-flat --adapter "$adapter" \
    "$scratch/sources/duplicate-symbol.bclj" \
    >"$scratch/duplicate-symbol.out" 2>"$scratch/duplicate-symbol.err"; then
  fail "serve-flat host accepted a duplicate logical symbol"
fi
grep -Fq 'exactly one lowered row for serve-flat-store-boot (found 2)' \
  "$scratch/duplicate-symbol.err" ||
  fail "duplicate serve-flat logical symbol did not fail before link"

printf '%s\n' '(ns demo.bad-arity)' ';; BAD_SERVE_FLAT_ARITY' \
  >"$scratch/sources/bad-arity.bclj"
if "${build_env[@]}" "$builder" --host serve-flat --adapter "$adapter" \
    "$scratch/sources/bad-arity.bclj" \
    >"$scratch/bad-arity.out" 2>"$scratch/bad-arity.err"; then
  fail "serve-flat host accepted an unexpected generated arity"
fi
grep -Fq \
  'serve-flat-codec-write-response has 2 source arguments; expected 1' \
  "$scratch/bad-arity.err" ||
  fail "unexpected serve-flat prototype arity did not fail before link"

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
