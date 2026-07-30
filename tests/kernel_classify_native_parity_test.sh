#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$repo_root/tests/fixtures/kernel_classify_native_parity"
required_beagle="ce949bb3313f0ea65d86e0b3077d3e654fd8ad55"
beagle_home="${BEAGLE_HOME:-$HOME/code/beagle/main}"
toolchain_home="${BEAGLE_TOOLCHAIN_HOME:-$HOME/code/beagle/main}"

die() {
  echo "kernel_classify_native_parity: $*" >&2
  exit 2
}

for command in cmp diff direnv git rg sha256sum; do
  command -v "$command" >/dev/null ||
    die "required command is unavailable: $command"
done

[[ -x "$beagle_home/bin/beagle" ]] ||
  die "Beagle CLI is unavailable at $beagle_home/bin/beagle"
[[ -d "$toolchain_home/.direnv" ]] ||
  die "allowed Beagle toolchain direnv is unavailable at $toolchain_home"

beagle_head="$(git -C "$beagle_home" rev-parse HEAD 2>/dev/null)" ||
  die "BEAGLE_HOME is not a Git checkout: $beagle_home"
git -C "$beagle_home" cat-file -e "$required_beagle^{commit}" 2>/dev/null ||
  die "Beagle $required_beagle is unavailable in BEAGLE_HOME"
git -C "$beagle_home" merge-base --is-ancestor "$required_beagle" "$beagle_head" ||
  die "Beagle $required_beagle has not landed in BEAGLE_HOME (found $beagle_head)"
git -C "$beagle_home" diff --quiet HEAD -- ||
  die "BEAGLE_HOME has tracked changes; parity evidence requires an exact commit"

git -C "$repo_root" diff --quiet HEAD -- \
  src/fram/kernel_classify.bclj out/fram/kernel_classify.clj ||
  die "kernel-classify source or managed projection differs from Fram HEAD"
fram_head="$(git -C "$repo_root" rev-parse HEAD)"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/fram-kernel-native-parity.XXXXXX")"
trap 'rm -rf "${tmp_dir:?}"' EXIT
zig_lib="$tmp_dir/zig-lib"
mkdir -p "$zig_lib"

if ! BEAGLE_EMIT_SRCLOC=0 "$beagle_home/bin/beagle" \
  build --target zig --lib "$zig_lib" \
  "$repo_root/src/fram/kernel_classify.bclj" \
  >"$tmp_dir/beagle.stdout" 2>"$tmp_dir/beagle.stderr"; then
  cat "$tmp_dir/beagle.stderr" >&2
  die "Beagle Zig library emission failed"
fi
[[ ! -s "$tmp_dir/beagle.stdout" ]] ||
  die "Beagle Zig library emission wrote unexpected stdout"
[[ -f "$zig_lib/fram_kernel_classify.zig" ]] ||
  die "Beagle did not stage fram_kernel_classify.zig"
[[ -f "$zig_lib/beagle_rt.zig" ]] ||
  die "Beagle did not stage beagle_rt.zig"
if rg -n 'pub fn (__beagle_)?main\(' \
  "$zig_lib/fram_kernel_classify.zig" "$zig_lib/beagle_rt.zig" >/dev/null; then
  die "Beagle --lib staged a generated entry point"
fi

cp "$fixture/host.zig" "$fixture/corpus.tsv" "$zig_lib/"

sdeps="{:paths [\"$repo_root/out\"]}"
(
  cd "$tmp_dir"
  DIRENV_LOG_FORMAT= direnv exec "$toolchain_home" \
    clojure -Sdeps "$sdeps" -M \
    "$fixture/managed_runner.clj" "$fixture/corpus.tsv"
) >"$tmp_dir/managed.out"

(
  cd "$tmp_dir"
  DIRENV_LOG_FORMAT= direnv exec "$toolchain_home" \
    zig build-exe "$zig_lib/host.zig" \
    "-femit-bin=$tmp_dir/native-host"
)

"$tmp_dir/native-host" \
  >"$tmp_dir/native.stdout" 2>"$tmp_dir/native.out"
[[ ! -s "$tmp_dir/native.stdout" ]] ||
  die "handwritten Zig host wrote unexpected stdout"

if ! cmp -s "$tmp_dir/managed.out" "$tmp_dir/native.out"; then
  diff -u "$tmp_dir/managed.out" "$tmp_dir/native.out" || true
  die "managed Clojure and handwritten Zig outputs differ"
fi

line_count="$(wc -l <"$tmp_dir/managed.out")"
byte_count="$(wc -c <"$tmp_dir/managed.out")"
output_sha="$(sha256sum "$tmp_dir/managed.out" | cut -d' ' -f1)"
zig_version="$(
  DIRENV_LOG_FORMAT= direnv exec "$toolchain_home" zig version
)"
jvm_version="$(
  DIRENV_LOG_FORMAT= direnv exec "$toolchain_home" \
    clojure -M -e \
    '(print (str (System/getProperty "java.vm.name") " " (System/getProperty "java.version") " / Clojure " (clojure-version)))'
)"

echo "kernel_classify_native_parity: PASS"
echo "fram_head=$fram_head"
echo "beagle_head=$beagle_head"
echo "beagle_requirement=$required_beagle"
echo "zig=$zig_version"
echo "managed=$jvm_version"
echo "lines=$line_count bytes=$byte_count sha256=$output_sha"
