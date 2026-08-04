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
[[ "${1:-}" == "native-exe" ]] || exit 97
shift
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

echo "fram native build cache smoke: PASS"
