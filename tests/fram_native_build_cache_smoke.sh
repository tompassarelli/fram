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
calls="$scratch/materializer.calls"
: >"$calls"

cat >"$scratch/tool/bin/beagle" <<'FAKE_BEAGLE'
#!/usr/bin/env bash
set -euo pipefail
command="${1:-}"
shift
if [[ "$command" == "build" ]]; then
  out=""
  abi=""
  materializers=()
  sources=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --out) out="$2"; shift 2 ;;
      --materializer) materializers+=("$2"); shift 2 ;;
      --abi) abi="$2"; shift 2 ;;
      --entry) shift 2 ;;
      --) shift; sources+=("$@"); break ;;
      *) sources+=("$1"); shift ;;
    esac
  done
  want_qbe=0
  case "${materializers[*]}" in
    "c17 qbe") want_qbe=1 ;;
    "c17") ;;
    *) exit 96 ;;
  esac
  [[ -n "$out" && -n "$abi" && ${#sources[@]} -gt 0 ]] || exit 96
  printf '%s\n' "build-$(IFS=+; printf '%s' "${materializers[*]}")" \
    >>"$FAKE_NATIVE_CALLS"
  mkdir -p "$out"
  # Mimic beagle-build-core: managed artifacts are wiped before the run.
  rm -f "$out/module_0.h" "$out/module_0.c" "$out/module_0.ssa" \
    "$out/native_shim.h" "$out/native_shim.c" "$out/report.txt" \
    "$out/native_unicode15_data.h" "$out/UNICODE-LICENSE.txt"
  printf '%s\n' 'fake source facts' >"$out/source.facts"
  printf '%s\n' 'fake frozen native program' >"$out/module.native-program"
  sha256sum "$out/module.native-program" | sed 's/ .*//' \
    >"$out/module.native-program.sha256"
  cat >"$out/module_0.h" <<'C'
#ifndef FAKE_MODULE_0_H
#define FAKE_MODULE_0_H

#include "native_shim.h"

#include <stdbool.h>
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
  native_m0_type_1 field_4;
  bool field_5;
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

native_m0_type_1 native_m0_fn_2(native_arena *arena, const native_capability *capability, native_m0_type_2 native_v_0, native_m0_type_2 native_v_1, native_m0_type_4 native_v_2, native_m0_type_4 native_v_3);
native_m0_type_9 native_m0_fn_3(native_m0_type_1 native_v_0);
native_m0_type_6 native_m0_fn_5(native_arena *arena, const native_capability *capability, native_m0_type_5 native_v_0);
native_m0_type_0 native_m0_fn_7(void);
native_m0_type_5 native_m0_fn_11(native_arena *arena, const native_capability *capability, native_m0_type_1 native_v_0, native_m0_type_7 native_v_1, native_m0_type_0 native_v_2);
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
                                  native_m0_type_4 native_v_2,
                                  native_m0_type_4 native_v_3) {
  (void)arena;
  (void)capability;
  (void)native_v_0;
  (void)native_v_1;
  (void)native_v_2;
  (void)native_v_3;
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

native_m0_type_0 native_m0_fn_7(void) { return 4; }

native_m0_type_5 native_m0_fn_11(native_arena *arena,
                                   const native_capability *capability,
                                   native_m0_type_1 native_v_0,
                                   native_m0_type_7 native_v_1,
                                   native_m0_type_0 native_v_2) {
  (void)arena;
  (void)capability;
  return (native_m0_type_5){
      .field_0 = native_v_0.field_0 + native_v_1.field_0 + native_v_2};
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
  cat >"$out/native_shim.h" <<'C'
#ifndef FAKE_NATIVE_SHIM_H
#define FAKE_NATIVE_SHIM_H

#include <stdint.h>

typedef struct native_arena { int marker; } native_arena;
typedef struct native_capability { int marker; } native_capability;
typedef struct native_vec { int64_t length; } native_vec;

#define NATIVE_TRAP_INVALID_ARGUMENT UINT32_C(1)
#define NATIVE_TRAP_OVERFLOW UINT32_C(2)
#define NATIVE_TRAP_ARENA_EXHAUSTED UINT32_C(3)
#define NATIVE_TRAP_OUT_OF_RANGE UINT32_C(4)
#define NATIVE_TRAP_IO UINT32_C(5)

typedef void (*native_trap_reporter)(uint32_t code);
void native_set_trap_reporter(native_trap_reporter reporter);

int fake_native_shim(void);
#endif
C
    printf '%s\n' '/* fake Unicode data */' >"$out/native_unicode15_data.h"
    printf '%s\n' 'fake Unicode license' >"$out/UNICODE-LICENSE.txt"
  [[ "$want_qbe" == 0 ]] ||
    printf '%s\n' 'export function w $main() { ret 0 }' >"$out/module_0.ssa"
  cat >"$out/native_shim.c" <<'C'
#include "native_shim.h"
int fake_native_shim(void) { return 0; }
void native_set_trap_reporter(native_trap_reporter reporter) { (void)reporter; }
C
  missing_symbol=0
  duplicate_symbol=0
  bad_arity=0
  for source in "${sources[@]}"; do
    grep -Fq MISSING_SERVER_SYMBOL "$source" && missing_symbol=1
    grep -Fq DUPLICATE_SERVER_SYMBOL "$source" && duplicate_symbol=1
    grep -Fq BAD_SERVER_ARITY "$source" && bad_arity=1
  done
  if [[ "$bad_arity" == 1 ]]; then
    sed -i \
      '/native_m0_fn_5/s/);$/, native_m0_type_0 native_v_1);/' \
      "$out/module_0.h"
  fi
  {
    printf '%s\n' \
      'stage source-freeze ACCEPTED' \
      'stage source-to-typed ACCEPTED' \
      'stage typed-to-native COMPLETE' \
      'native-lowering-result NativeLoweringCompleteV0'
    printf '%s\n' 'materialize-c17 OK module_0.h module_0.c'
    if [[ "$want_qbe" == 1 ]]; then
      if [[ -n "${FAKE_QBE_REFUSAL:-}" ]]; then
        printf 'materialize-qbe REFUSED %s\n' "$FAKE_QBE_REFUSAL"
      else
        printf '%s\n' 'materialize-qbe OK module_0.ssa'
      fi
    fi
    printf '%s\n' \
      'lowered fn_7 server-generated-abi 1 blocks' \
      'lowered fn_2 server-store-boot! 1 blocks' \
      'lowered fn_11 server-store-dispatch! 1 blocks' \
      'lowered fn_3 server-store-shutdown 1 blocks' \
      'lowered fn_19 server-codec-read-request! 1 blocks' \
      'lowered fn_5 server-codec-write-response! 1 blocks' \
      'lowered fn_13 server-codec-release-request 1 blocks'
    [[ "$missing_symbol" == 1 ]] ||
      printf '%s\n' 'lowered fn_17 server-codec-release-response 1 blocks'
    [[ "$duplicate_symbol" == 0 ]] ||
      printf '%s\n' 'lowered fn_23 server-store-boot! 1 blocks'
    printf '%s\n' \
      'obligation-projection PASS valid-ssa' \
      'obligation-projection PASS exhaustive-matches' \
      'obligation-projection PASS closed-layouts' \
      'obligation-projection PASS checked-arithmetic' \
      'obligation-projection PASS legal-abi' \
      'obligation-projection PASS discharged-tokens' \
      'obligation-projection PASS bounded-effects'
    if [[ "$want_qbe" == 1 && -n "${FAKE_QBE_REFUSAL:-}" ]]; then
      printf '%s\n' 'result FAIL materialization'
    else
      printf '%s\n' 'result PASS'
    fi
  } >"$out/report.txt"
  # A refused sibling makes beagle exit before persisting C17's artifacts.
  if [[ "$want_qbe" == 1 && -n "${FAKE_QBE_REFUSAL:-}" ]]; then
    rm -f "$out/module_0.h" "$out/module_0.c" "$out/native_shim.h" \
      "$out/native_shim.c" "$out/native_unicode15_data.h" \
      "$out/UNICODE-LICENSE.txt"
    exit 1
  fi
  exit 0
fi
exit 97
FAKE_BEAGLE
chmod +x "$scratch/tool/bin/beagle"

printf '%s\n' '#lang beagle' '(ns demo.main)' '(defn start [] -> Nil nil)' \
  >"$scratch/sources/good.bgl"
ledger="$scratch/qbe-frontier.ledger"
printf '%s\n' '# scratch QBE frontier ledger' >"$ledger"
build_env=(
  env
  FRAM_BEAGLE="$scratch/tool/bin/beagle"
  FRAM_NATIVE_CACHE="$scratch/cache"
  FRAM_NATIVE_CC="${CC:-cc}"
  FRAM_QBE_FRONTIER_LEDGER="$ledger"
  FAKE_NATIVE_CALLS="$calls"
)

adapter="$scratch/sources/server_generated.c"
cat >"$adapter" <<'C'
#include "server_host.h"
#include "server_symbols.h"

#include <stdlib.h>

static void clear_error(char *error, size_t capacity) {
  if (capacity > 0u) {
    error[0] = '\0';
  }
}

uint32_t fram_server_generated_abi(void) {
  native_arena arena = {0};
  native_capability capability = {0};
  native_vec bytes = {0};
  fram_server_store_boot_return boot =
      FRAM_SERVER_CALL_STORE_BOOT(
          &arena, &capability, "log", "space", &bytes, &bytes);
  fram_server_codec_read_request_return request =
      FRAM_SERVER_CALL_CODEC_READ_REQUEST(
          &arena, &capability, &bytes);
  fram_server_store_dispatch_return dispatched =
      FRAM_SERVER_CALL_STORE_DISPATCH(
          &arena, &capability, boot, request, 0);
  fram_server_store_shutdown_return stopped =
      FRAM_SERVER_CALL_STORE_SHUTDOWN(&arena, &capability, boot);
  fram_server_codec_write_response_return written =
      FRAM_SERVER_CALL_CODEC_WRITE_RESPONSE(
          &arena, &capability, dispatched);
  fram_server_codec_release_request_return request_released =
      FRAM_SERVER_CALL_CODEC_RELEASE_REQUEST(
          &arena, &capability, request);
  fram_server_codec_release_response_return response_released =
      FRAM_SERVER_CALL_CODEC_RELEASE_RESPONSE(
          &arena, &capability, dispatched);
  fram_server_generated_abi_return abi =
      FRAM_SERVER_CALL_GENERATED_ABI(&arena, &capability);
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

int fram_server_store_boot(const char *log_path,
                               const char *space_id,
                               uint64_t memory_budget_bytes,
                               fram_server_store **store_out,
                               char *error,
                               size_t capacity) {
  (void)log_path;
  (void)space_id;
  (void)memory_budget_bytes;
  *store_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVER_FATAL;
}

int fram_server_store_boot_with_host(const char *log_path,
                                     const char *space_id,
                                     const fram_server_host_v1 *host,
                                     fram_server_store **store_out,
                                     char *error, size_t capacity) {
  (void)log_path;
  (void)space_id;
  (void)host;
  *store_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVER_FATAL;
}

int fram_server_store_dispatch(fram_server_store *store,
                                   const fram_server_request *request,
                                   fram_server_response **response_out,
                                   char *error,
                                   size_t capacity) {
  (void)store;
  (void)request;
  *response_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVER_FATAL;
}

int fram_server_store_shutdown(fram_server_store *store,
                                   char *error,
                                   size_t capacity) {
  (void)store;
  clear_error(error, capacity);
  return FRAM_SERVER_OK;
}

int fram_server_codec_read_request(int fd,
                                       fram_server_request **request_out,
                                       char *error,
                                       size_t capacity) {
  (void)fd;
  *request_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVER_FATAL;
}

int fram_server_codec_decode_request(const uint8_t *bytes, size_t length,
                                     fram_server_request **request_out,
                                     char *error, size_t capacity) {
  (void)bytes;
  (void)length;
  *request_out = NULL;
  clear_error(error, capacity);
  return FRAM_SERVER_FATAL;
}

int fram_server_codec_encode_response(const fram_server_response *response,
                                      uint8_t **bytes_out,
                                      size_t *length_out, char *error,
                                      size_t capacity) {
  (void)response;
  *bytes_out = NULL;
  *length_out = 0u;
  clear_error(error, capacity);
  return FRAM_SERVER_FATAL;
}

void fram_server_codec_release_bytes(uint8_t *bytes) { free(bytes); }

int fram_server_codec_write_response(
    int fd,
    const fram_server_response *response,
    char *error,
    size_t capacity) {
  (void)fd;
  (void)response;
  clear_error(error, capacity);
  return FRAM_SERVER_FATAL;
}

void fram_server_codec_release_request(fram_server_request *request) {
  (void)request;
}

void fram_server_codec_release_response(fram_server_response *response) {
  (void)response;
}
C

cp "$adapter" "$scratch/server_generated.pristine.c"

calls_before_host="$(wc -l <"$calls")"
host_artifact="$("${build_env[@]}" "$builder" --host server \
  --adapter "$adapter" "$scratch/sources/good.bgl")" ||
  fail "server host build failed"
[[ -f "$host_artifact/READY" && -x "$host_artifact/bin/fram-server-native" ]] ||
  fail "server host artifact is not ready and executable"
grep -Fqx 'native-host-abi PASS host=server exports=8' \
  "$host_artifact/native-host.report.txt" ||
  fail "server host artifact omitted its eight-export receipt"
symbols_header="$host_artifact/server_symbols.h"
[[ -f "$symbols_header" ]] || fail "server host omitted its generated symbol header"
[[ "$(grep -c '^#define FRAM_SERVER_SYMBOL_' "$symbols_header")" == "8" ]] ||
  fail "server symbol header did not contain exactly eight symbol mappings"
[[ "$(grep -c '^#define FRAM_SERVER_CALL_' "$symbols_header")" == "8" ]] ||
  fail "server symbol header did not contain exactly eight normalized calls"
[[ "$(grep -Ec '^typedef [A-Za-z_][A-Za-z0-9_]* fram_server_[a-z_]+_(return|arg_[0-9]+);$' "$symbols_header")" == "20" ]] ||
  fail "server symbol header did not contain all twenty stable type aliases"
required_symbol_lines=(
  '#define FRAM_SERVER_SYMBOL_GENERATED_ABI native_m0_fn_7'
  '#define FRAM_SERVER_SYMBOL_STORE_BOOT native_m0_fn_2'
  '#define FRAM_SERVER_SYMBOL_STORE_DISPATCH native_m0_fn_11'
  '#define FRAM_SERVER_SYMBOL_STORE_SHUTDOWN native_m0_fn_3'
  '#define FRAM_SERVER_SYMBOL_CODEC_READ_REQUEST native_m0_fn_19'
  '#define FRAM_SERVER_SYMBOL_CODEC_WRITE_RESPONSE native_m0_fn_5'
  '#define FRAM_SERVER_SYMBOL_CODEC_RELEASE_REQUEST native_m0_fn_13'
  '#define FRAM_SERVER_SYMBOL_CODEC_RELEASE_RESPONSE native_m0_fn_17'
  'typedef native_m0_type_1 fram_server_store_boot_return;'
  'typedef native_m0_type_2 fram_server_store_boot_arg_0;'
  'typedef native_m0_type_2 fram_server_store_boot_arg_1;'
  'typedef native_m0_type_4 fram_server_store_boot_arg_2;'
  'typedef native_m0_type_4 fram_server_store_boot_arg_3;'
  'typedef native_m0_type_5 fram_server_store_dispatch_return;'
  'typedef native_m0_type_0 fram_server_store_dispatch_arg_2;'
  'typedef native_m0_type_9 fram_server_store_shutdown_return;'
  'typedef native_m0_type_7 fram_server_codec_read_request_return;'
  'typedef native_m0_type_4 fram_server_codec_read_request_arg_0;'
  'typedef native_m0_type_6 fram_server_codec_write_response_return;'
  'typedef native_m0_type_5 fram_server_codec_write_response_arg_0;'
  'typedef native_m0_type_8 fram_server_codec_release_request_return;'
  'typedef native_m0_type_7 fram_server_codec_release_request_arg_0;'
  'typedef native_m0_type_8 fram_server_codec_release_response_return;'
  'typedef native_m0_type_5 fram_server_codec_release_response_arg_0;'
  '#define FRAM_SERVER_CALL_GENERATED_ABI(arena, capability) FRAM_SERVER_SYMBOL_GENERATED_ABI()'
  '#define FRAM_SERVER_CALL_STORE_BOOT(arena, capability, arg_0, arg_1, arg_2, arg_3) FRAM_SERVER_SYMBOL_STORE_BOOT((arena), (capability), (arg_0), (arg_1), (arg_2), (arg_3))'
  '#define FRAM_SERVER_CALL_STORE_DISPATCH(arena, capability, arg_0, arg_1, arg_2) FRAM_SERVER_SYMBOL_STORE_DISPATCH((arena), (capability), (arg_0), (arg_1), (arg_2))'
  '#define FRAM_SERVER_CALL_STORE_SHUTDOWN(arena, capability, arg_0) FRAM_SERVER_SYMBOL_STORE_SHUTDOWN((arg_0))'
  '#define FRAM_SERVER_CALL_CODEC_READ_REQUEST(arena, capability, arg_0) FRAM_SERVER_SYMBOL_CODEC_READ_REQUEST((arena), (arg_0))'
  '#define FRAM_SERVER_CALL_CODEC_WRITE_RESPONSE(arena, capability, arg_0) FRAM_SERVER_SYMBOL_CODEC_WRITE_RESPONSE((arena), (capability), (arg_0))'
  '#define FRAM_SERVER_CALL_CODEC_RELEASE_RESPONSE(arena, capability, arg_0) FRAM_SERVER_SYMBOL_CODEC_RELEASE_RESPONSE((capability), (arg_0))'
)
for required_line in "${required_symbol_lines[@]}"; do
  grep -Fqx -- "$required_line" "$symbols_header" ||
    fail "server symbol header omitted: $required_line"
done
if "$host_artifact/bin/fram-server-native" serve not-a-port \
    >"$scratch/host.out" 2>"$scratch/host.err"; then
  fail "server host accepted an invalid port"
fi
grep -Fq 'fram-server-native: invalid port: not-a-port' \
  "$scratch/host.err" || fail "linked server host main did not run"

host_hit="$("${build_env[@]}" "$builder" --host server \
  --adapter "$adapter" "$scratch/sources/good.bgl")" ||
  fail "server host cache hit failed"
[[ "$host_hit" == "$host_artifact" ]] || fail "server host missed the cache"
[[ "$(wc -l <"$calls")" == "$((calls_before_host + 1))" ]] ||
  fail "server host cache hit rebuilt a materializer projection"

calls_before_embed="$(wc -l <"$calls")"
embed_artifact="$("${build_env[@]}" "$builder" --host embed \
  --adapter "$adapter" "$scratch/sources/good.bgl")" ||
  fail "embed host build failed"
[[ -f "$embed_artifact/READY" && -f "$embed_artifact/include/fram.h" &&
  -f "$embed_artifact/lib/libfram.a" &&
  -f "$embed_artifact/lib/libfram.so" ]] ||
  fail "embed host artifact omitted its public libraries"
grep -Fqx 'native-host-abi PASS host=embed exports=7 version=1' \
  "$embed_artifact/native-host.report.txt" ||
  fail "embed host artifact omitted its public ABI receipt"
cat >"$scratch/embed-consumer.c" <<'C'
#include <fram.h>
int main(void) { return fram_abi_version() == FRAM_ABI_VERSION ? 0 : 1; }
C
"${CC:-cc}" -std=c17 -pedantic -Wall -Wextra -Werror -pthread \
  -I"$embed_artifact/include" "$scratch/embed-consumer.c" \
  "$embed_artifact/lib/libfram.a" -o "$scratch/embed-static"
"$scratch/embed-static" || fail "static embed library did not run"
"${CC:-cc}" -std=c17 -pedantic -Wall -Wextra -Werror -pthread \
  -I"$embed_artifact/include" "$scratch/embed-consumer.c" \
  -L"$embed_artifact/lib" -Wl,-rpath,"$embed_artifact/lib" -lfram \
  -o "$scratch/embed-shared"
"$scratch/embed-shared" || fail "shared embed library did not run"
embed_hit="$("${build_env[@]}" "$builder" --host embed \
  --adapter "$adapter" "$scratch/sources/good.bgl")" ||
  fail "embed host cache hit failed"
[[ "$embed_hit" == "$embed_artifact" ]] || fail "embed host missed the cache"
[[ "$(wc -l <"$calls")" == "$calls_before_embed" ]] ||
  fail "embed host rebuilt the shared native program"
[[ "$(find "$scratch/cache/.programs" -mindepth 2 -maxdepth 2 \
  -name READY | wc -l)" == "1" ]] ||
  fail "server and embed did not share exactly one native program"

# The wasm-embed host is refusal-visible without its toolchain: it names the
# missing tool instead of degrading to the native compiler. Its positive path
# needs a real wasi link and lives in tests/fram_wasm_embed_smoke.sh.
if env -u FRAM_WASI_CC -u WASI_CC "${build_env[@]}" "$builder" \
    --host wasm-embed --abi wasm32 "$scratch/sources/good.bgl" \
    >"$scratch/wasm-no-cc.out" 2>"$scratch/wasm-no-cc.err"; then
  fail "wasm-embed host built without a wasi compiler"
fi
grep -Fq 'set FRAM_WASI_CC to an executable wasi C17 compiler' \
  "$scratch/wasm-no-cc.err" ||
  fail "wasm-embed host did not name the missing FRAM_WASI_CC"
if "${build_env[@]}" "$builder" --host wasm-embed \
    "$scratch/sources/good.bgl" \
    >"$scratch/wasm-no-abi.out" 2>"$scratch/wasm-no-abi.err"; then
  fail "wasm-embed host built at the default ABI"
fi
grep -Fq -- '--host wasm-embed needs --abi wasm32' "$scratch/wasm-no-abi.err" ||
  fail "wasm-embed host did not name its ABI coupling"
if "${build_env[@]}" "$builder" --host embed --abi wasm32 \
    "$scratch/sources/good.bgl" \
    >"$scratch/embed-wasm-abi.out" 2>"$scratch/embed-wasm-abi.err"; then
  fail "embed host accepted a non-native ABI"
fi
grep -Fq -- '--abi wasm32 needs --host program' "$scratch/embed-wasm-abi.err" ||
  fail "embed host did not refuse a non-native ABI by name"

printf '%s\n' '#lang beagle' '(ns demo.missing-symbol)' \
  ';; MISSING_SERVER_SYMBOL' >"$scratch/sources/missing-symbol.bgl"
if "${build_env[@]}" "$builder" --host server --adapter "$adapter" \
    "$scratch/sources/missing-symbol.bgl" \
    >"$scratch/missing-symbol.out" 2>"$scratch/missing-symbol.err"; then
  fail "server host accepted a missing logical symbol"
fi
grep -Fq \
  'exactly one lowered row for server-codec-release-response (found 0)' \
  "$scratch/missing-symbol.err" ||
  fail "missing server logical symbol did not fail before link"

printf '%s\n' '#lang beagle' '(ns demo.duplicate-symbol)' \
  ';; DUPLICATE_SERVER_SYMBOL' >"$scratch/sources/duplicate-symbol.bgl"
if "${build_env[@]}" "$builder" --host server --adapter "$adapter" \
    "$scratch/sources/duplicate-symbol.bgl" \
    >"$scratch/duplicate-symbol.out" 2>"$scratch/duplicate-symbol.err"; then
  fail "server host accepted a duplicate logical symbol"
fi
grep -Fq 'exactly one lowered row for server-store-boot! (found 2)' \
  "$scratch/duplicate-symbol.err" ||
  fail "duplicate server logical symbol did not fail before link"

printf '%s\n' '#lang beagle' '(ns demo.bad-arity)' ';; BAD_SERVER_ARITY' \
  >"$scratch/sources/bad-arity.bgl"
if "${build_env[@]}" "$builder" --host server --adapter "$adapter" \
    "$scratch/sources/bad-arity.bgl" \
    >"$scratch/bad-arity.out" 2>"$scratch/bad-arity.err"; then
  fail "server host accepted an unexpected generated arity"
fi
grep -Fq \
  'server-codec-write-response! has 2 source arguments; expected 1' \
  "$scratch/bad-arity.err" ||
  fail "unexpected server prototype arity did not fail before link"

sed '/^void fram_server_codec_release_response/,/^}/d' \
  "$adapter" >"$adapter.incomplete"
mv "$adapter.incomplete" "$adapter"
if "${build_env[@]}" "$builder" --host server --adapter "$adapter" \
    "$scratch/sources/good.bgl" \
    >"$scratch/missing-export.out" 2>"$scratch/missing-export.err"; then
  fail "server host linked without all eight generated ABI exports"
fi
grep -Fq 'fram_server_codec_release_response' "$scratch/missing-export.err" ||
  fail "server host link did not name the missing ABI export"
[[ "$(find "$scratch/cache" -mindepth 2 -maxdepth 2 -name READY | wc -l)" == "2" ]] ||
  fail "failed server host link exposed a READY artifact"
[[ -z "$(find "$scratch/cache/.tmp" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
  fail "failed server host link left temporary artifacts"
[[ -z "$(find "$scratch/cache/.programs/.tmp" -mindepth 1 -maxdepth 1 \
  -print -quit)" ]] ||
  fail "failed native program build left temporary artifacts"

cp "$scratch/server_generated.pristine.c" "$adapter"

grep -Fqx 'native-qbe-frontier OK scope=fram-native-server ledger=clean' \
  "$host_artifact/native-host.report.txt" ||
  fail "clean QBE run did not leave a frontier receipt in the host report"
[[ -f "$host_artifact/qbe-frontier.txt" &&
  -f "$host_artifact/qbe-probe.report.txt" ]] ||
  fail "artifact omitted its QBE frontier receipt"
grep -Fqx 'native-qbe-frontier OK scope=fram-native-server ledger=clean' \
  "$embed_artifact/native-host.report.txt" ||
  fail "embed host report omitted its QBE frontier receipt"

# A recorded refusal that no longer reproduces fails until it is deleted.
printf '%s\n' \
  "$(printf 'fram-native-server\tshape-outside-slice\t-')" >>"$ledger"
if "${build_env[@]}" "$builder" --host server --adapter "$adapter" \
    "$scratch/sources/good.bgl" >"$scratch/stale.out" 2>"$scratch/stale.err"; then
  fail "stale QBE frontier entry did not fail the build"
fi
grep -Fq 'QBE frontier ledger is STALE for scope fram-native-server' \
  "$scratch/stale.err" || fail "stale QBE frontier entry failed for the wrong reason"
printf '%s\n' '# scratch QBE frontier ledger' >"$ledger"

# An unrecorded refusal fails: the frontier may not grow.
qbe_env=("${build_env[@]}"
  FAKE_QBE_REFUSAL='unsupported native value-semantics op: hash')
printf '%s\n' '#lang beagle' '(ns demo.refused)' '(defn start [] -> Nil nil)' \
  >"$scratch/sources/refused.bgl"
if "${qbe_env[@]}" "$builder" --host server --adapter "$adapter" \
    "$scratch/sources/refused.bgl" \
    >"$scratch/grew.out" 2>"$scratch/grew.err"; then
  fail "unrecorded QBE refusal did not fail the build"
fi
grep -Fq 'QBE frontier GREW for scope fram-native-server' "$scratch/grew.err" ||
  fail "unrecorded QBE refusal failed for the wrong reason"
grep -Fq 'unsupported-value-semantics	hash' "$scratch/grew.err" ||
  fail "QBE frontier failure did not name the observed refusal key"

# Recorded, the same refusal builds — and C17's artifacts survive it.
printf '%s\n' \
  "$(printf 'fram-native-server\tunsupported-value-semantics\thash')" >>"$ledger"
calls_before_refusal="$(wc -l <"$calls")"
refused_artifact="$("${qbe_env[@]}" "$builder" --host server \
  --adapter "$adapter" "$scratch/sources/refused.bgl")" ||
  fail "recorded QBE refusal blocked a complete C17 build"
[[ -f "$refused_artifact/module_0.c" && -f "$refused_artifact/module_0.h" &&
  -f "$refused_artifact/native_shim.c" &&
  -x "$refused_artifact/bin/fram-server-native" ]] ||
  fail "QBE refusal discarded C17's artifacts"
[[ ! -f "$refused_artifact/module_0.ssa" ]] ||
  fail "refused QBE run published a module_0.ssa"
grep -Fqx 'native-qbe-frontier REFUSED scope=fram-native-server ledger=unsupported-value-semantics/hash' \
  "$refused_artifact/native-host.report.txt" ||
  fail "refused QBE run did not attribute its frontier in the host report"
grep -Fq 'materialize-qbe REFUSED unsupported native value-semantics op: hash' \
  "$refused_artifact/qbe-probe.report.txt" ||
  fail "refused QBE run did not preserve the probe report"
[[ "$(sed -n "$((calls_before_refusal + 1)),\$p" "$calls" | head -2 | tr '\n' ' ')" == \
  "build-c17+qbe build-c17 " ]] ||
  fail "QBE refusal did not recover C17 through a second materialization"

# --regen-qbe-frontier records the observed frontier for a program scope.
printf '%s\n' '# scratch QBE frontier ledger' >"$ledger"
"${qbe_env[@]}" "$builder" --host program --entry demo.main/start \
  "$scratch/sources/refused.bgl" >"$scratch/regen.out" 2>"$scratch/regen.err" &&
  fail "unrecorded QBE refusal did not fail --host program"
"${qbe_env[@]}" "$builder" --host program --regen-qbe-frontier \
  --entry demo.main/start "$scratch/sources/refused.bgl" \
  >"$scratch/regen.out" 2>"$scratch/regen.err" ||
  fail "--regen-qbe-frontier failed"
grep -Fq "$(printf 'demo.main/start\tunsupported-value-semantics\thash')" \
  "$ledger" || fail "--regen-qbe-frontier did not record the observed refusal"
program_dir="$("${qbe_env[@]}" "$builder" --host program --entry demo.main/start \
  "$scratch/sources/refused.bgl")" ||
  fail "recorded QBE refusal blocked --host program"
[[ -f "$program_dir/module_0.c" && -f "$program_dir/READY" ]] ||
  fail "--host program did not persist the C17 projection"

echo "fram native build cache smoke: PASS"
