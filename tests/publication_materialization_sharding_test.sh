#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
runner="$repo/scripts/materialize-publication-roots.sh"
scratch="$(mktemp -d)"
trap 'rm -rf "${scratch:?}"' EXIT INT TERM

fail() {
  echo "publication materialization sharding: FAIL: $*" >&2
  exit 1
}

source_root="$scratch/source"
mkdir -p "$source_root/bin" "$source_root/native" "$scratch/barrier" \
  "$scratch/artifacts" "$scratch/tool"
printf '%s\n' 'native/root.bgl' >"$source_root/native/core_closure_sources.txt"
printf '%s\n' '#lang beagle' '(ns native.root)' \
  '(defn root [] Nil nil)' >"$source_root/native/root.bgl"

cat >"$source_root/bin/fram-native-build" <<'BUILDER'
#!/usr/bin/env bash
set -euo pipefail
host=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) host="$2"; shift 2 ;;
    --abi) shift 2 ;;
    *) shift ;;
  esac
done
[[ "$host" == server || "$host" == wasm-embed ]] || exit 91
printf '%s\n' started >"$FAKE_BARRIER/$host.started"
for _ in $(seq 1 100); do
  [[ -f "$FAKE_BARRIER/server.started" &&
    -f "$FAKE_BARRIER/wasm-embed.started" ]] && break
  sleep 0.02
done
[[ -f "$FAKE_BARRIER/server.started" &&
  -f "$FAKE_BARRIER/wasm-embed.started" ]] || exit 92
artifact="$FAKE_ARTIFACT_ROOT/$host"
mkdir -p "$artifact"
printf 'fake-ready %s\n' "$host" >"$artifact/READY"
printf '%s\n' "$artifact"
BUILDER
chmod +x "$source_root/bin/fram-native-build"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$scratch/tool/compiler"
chmod +x "$scratch/tool/compiler"

FAKE_BARRIER="$scratch/barrier" \
FAKE_ARTIFACT_ROOT="$scratch/artifacts" \
FRAM_BEAGLE="$scratch/tool/compiler" \
FRAM_WASI_CC="$scratch/tool/compiler" \
FRAM_NATIVE_CC="$scratch/tool/compiler" \
timeout 10s "$runner" \
  --source-root "$source_root" \
  --native-cache "$scratch/native-cache" \
  --wasm-cache "$scratch/wasm-cache" \
  --output "$scratch/output" ||
  fail "bounded root supervisor failed"

[[ "$(<"$scratch/output/native.artifact")" == \
  "$scratch/artifacts/server" ]] || fail "native result was not captured"
[[ "$(<"$scratch/output/wasm.artifact")" == \
  "$scratch/artifacts/wasm-embed" ]] || fail "Wasm result was not captured"
[[ "$(awk -F'\t' '$1 == "native" && $4 == 0 { count++ } END { print count + 0 }' \
  "$scratch/output/timings.tsv")" == 1 ]] || fail "native timing is missing"
[[ "$(awk -F'\t' '$1 == "wasm" && $4 == 0 { count++ } END { print count + 0 }' \
  "$scratch/output/timings.tsv")" == 1 ]] || fail "Wasm timing is missing"

echo "publication materialization sharding: PASS"
