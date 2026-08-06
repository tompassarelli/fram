#!/usr/bin/env bash
# The wasm host-import regime end to end: an external engine embedder supplies
# every fram_host_v1 hook as a named import and must answer byte-for-byte like
# the native lp64 embed library on the same frames.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
builder="$repo/bin/fram-native-build"
frames="$repo/tests/wasm_embed/frames"
space="fram-wasm-embed"

skip() {
  echo "fram wasm embed smoke: SKIP ($*)"
  exit 0
}

fail() {
  echo "fram wasm embed smoke: FAIL: $*" >&2
  exit 1
}

wasi_cc="${FRAM_WASI_CC:-${WASI_CC:-}}"
[[ -n "$wasi_cc" && -x "$(command -v "$wasi_cc" 2>/dev/null || true)" ]] ||
  skip "set FRAM_WASI_CC to a wasi C17 compiler"
command -v wasm-tools >/dev/null 2>&1 || skip "wasm-tools is not on PATH"
python3 -c 'import wasmtime' >/dev/null 2>&1 ||
  skip "python3 cannot import wasmtime"

scratch="$(mktemp -d)"
trap 'rm -rf "${scratch:?}"' EXIT INT TERM

mapfile -t sources < <(sed 's|^|'"$repo"'/|' "$repo/native/core_closure_sources.txt")

wasm_artifact="$(FRAM_WASI_CC="$wasi_cc" "$builder" --host wasm-embed \
  --abi wasm32 "${sources[@]}")" || fail "wasm-embed build failed"
wasm_again="$(FRAM_WASI_CC="$wasi_cc" "$builder" --host wasm-embed \
  --abi wasm32 "${sources[@]}")" || fail "wasm-embed rebuild failed"
[[ "$wasm_again" == "$wasm_artifact" ]] ||
  fail "wasm-embed build is not content-addressed: $wasm_artifact vs $wasm_again"
[[ -f "$wasm_artifact/lib/libfram.wasm" ]] ||
  fail "wasm-embed artifact omitted lib/libfram.wasm"

for receipt in \
  'native-host-abi PASS host=wasm-embed exports=9 version=1' \
  'native-wasm-seams PASS ledger=native/wasm-embed.seams' \
  'native-qbe-frontier REFUSED scope=fram-native-server@wasm32 ledger=abi-profile/wasm32'; do
  grep -Fqx "$receipt" "$wasm_artifact/native-host.report.txt" ||
    fail "wasm-embed report omitted: $receipt"
done

grep -v '^[[:space:]]*#' "$repo/native/wasm-embed.seams" |
  grep -v '^[[:space:]]*$' >"$scratch/seams.expected"
cmp -s "$scratch/seams.expected" "$wasm_artifact/wasm-embed.seams" ||
  fail "linked seams differ from native/wasm-embed.seams"
awk '$1 == "import" && $2 == "wasi_snapshot_preview1" { print $3 }' \
  "$wasm_artifact/wasm-embed.seams" >"$scratch/wasi.observed"
printf '%s\n' clock_time_get environ_get environ_sizes_get fd_close fd_seek \
  fd_write proc_exit >"$scratch/wasi.expected"
cmp -s "$scratch/wasi.expected" "$scratch/wasi.observed" ||
  fail "wasi import set moved: $(tr '\n' ' ' <"$scratch/wasi.observed")"
awk '$1 == "export" { print $2 }' "$wasm_artifact/wasm-embed.seams" \
  >"$scratch/exports.observed"
printf '%s\n' _initialize fram_abi_version fram_buffer_release fram_close \
  fram_open fram_query fram_snapshot fram_transact fram_wasm_alloc \
  fram_wasm_free memory | LC_ALL=C sort >"$scratch/exports.expected"
cmp -s "$scratch/exports.expected" "$scratch/exports.observed" ||
  fail "export set moved: $(tr '\n' ' ' <"$scratch/exports.observed")"

embed_artifact="$("$builder" --host embed "${sources[@]}")" ||
  fail "native embed oracle build failed"
"${CC:-cc}" -std=c17 -pedantic -Wall -Wextra -Werror -pthread \
  -I"$embed_artifact/include" "$repo/tests/wasm_embed/frames_driver.c" \
  "$embed_artifact/lib/libfram.a" -o "$scratch/frames_driver" ||
  fail "native oracle driver did not compile"
"$scratch/frames_driver" "$frames" "$frames/manifest.txt" \
  "$frames/manifest-reopen.txt" "$scratch/native.framlog" "$space" \
  >"$scratch/native.transcript" ||
  fail "native oracle reported a failure: $(tail -3 "$scratch/native.transcript")"

python3 "$repo/tests/wasm_embed/embedder.py" \
  "$wasm_artifact/lib/libfram.wasm" "$frames" "$frames/manifest.txt" \
  "$frames/manifest-reopen.txt" "$scratch/wasm.framlog" "$scratch/wasm.tally" \
  "$space" >"$scratch/wasm.transcript" ||
  fail "external wasm embedder reported a failure: $(tail -3 "$scratch/wasm.transcript")"

if ! cmp -s "$scratch/native.transcript" "$scratch/wasm.transcript"; then
  fail "$(printf 'wasm responses diverge from the native oracle:\n%s' \
    "$(diff "$scratch/native.transcript" "$scratch/wasm.transcript" |
       cut -c1-160 | head -6)")"
fi
cmp -s "$scratch/native.framlog" "$scratch/wasm.framlog" ||
  fail "the FRAMLOG written through the imports differs from the native one"
[[ -s "$scratch/wasm.framlog" ]] || fail "no FRAMLOG bytes were written"
# Every refused WASI import must record zero calls; the embedder answers only
# the shim's clock and environment, which have no fram_host_v1 field.
! grep -q '^wasi ' "$scratch/wasm.tally" ||
  fail "the host-import path called a refused WASI import: $(grep '^wasi ' "$scratch/wasm.tally" | tr '\n' ' ')"
if awk '$1 == "served" && $2 != "clock_time_get" && $2 != "environ_get" &&
        $2 != "environ_sizes_get"' "$scratch/wasm.tally" | grep -q .; then
  fail "an unexpected WASI import was served: $(awk '$1 == "served" { printf "%s ", $2 }' "$scratch/wasm.tally")"
fi
grep -q '^served clock_time_get ' "$scratch/wasm.tally" ||
  fail "the monotonic clock import was never called; the seam ledger claims it is live"
for hook in allocate deallocate clock_milliseconds storage_append \
  storage_close storage_read storage_size storage_sync; do
  grep -q "^host $hook " "$scratch/wasm.tally" ||
    fail "the $hook import was never called"
done

printf 'fram wasm embed smoke: PASS frames=%s framlog=%s refused-wasi-calls=0 served-wasi=%s\n' \
  "$(grep -c '^frame ' "$scratch/wasm.transcript")" \
  "$(sha256sum "$scratch/wasm.framlog" | sed 's/ .*//')" \
  "$(awk '$1 == "served" { printf "%s=%s ", $2, $3 }' "$scratch/wasm.tally")"
