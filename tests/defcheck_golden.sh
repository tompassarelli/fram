#!/usr/bin/env bash
# Byte-for-byte gate for the graph-authored defcheck module. Its committed
# goldens were captured from the original Clojure implementation before cutover.
#
#   tests/defcheck_golden.sh capture tests/goldens/defcheck
#   tests/defcheck_golden.sh verify  tests/goldens/defcheck
#
# Compared without masks: stdout, stderr, and exit code for both the focused
# deterministic behavior probe and the existing untyped analyzer selftest.
set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:?usage: defcheck_golden.sh capture|verify <dir>}"
GOLD="${2:?usage: defcheck_golden.sh capture|verify <dir>}"
WORK="${TMPDIR:-/tmp}/defcheck-golden-run"

if command -v flock >/dev/null 2>&1; then
  exec 9>"$WORK.lock"
  flock -w 1800 9 || {
    echo "defcheck_golden: another run holds $WORK.lock" >&2
    exit 2
  }
fi

rm -rf "${WORK:?}"
mkdir -p "$WORK"

run_case() {
  local name="$1"
  shift
  (
    cd "$HERE"
    "$@"
  ) >"$WORK/$name.out" 2>"$WORK/$name.err"
  echo "$?" >"$WORK/$name.rc"
}

run_case behavior clojure -M tests/defcheck_golden.clj
run_case untyped clojure -M tests/store_defcheck_untyped_test.clj

CASES="behavior untyped"
if [ "$MODE" = capture ]; then
  mkdir -p "$GOLD"
  for c in $CASES; do
    for ext in out err rc; do
      cp "$WORK/$c.$ext" "$GOLD/$c.$ext"
    done
  done
  echo "defcheck_golden: captured 2 cases from graph-authored defcheck module -> $GOLD"
  exit 0
fi

if [ "$MODE" != verify ]; then
  echo "defcheck_golden: mode must be capture or verify" >&2
  exit 2
fi

fail=0
for c in $CASES; do
  for ext in out err rc; do
    if ! cmp -s "$WORK/$c.$ext" "$GOLD/$c.$ext"; then
      echo "DRIFT $c.$ext"
      diff -u "$GOLD/$c.$ext" "$WORK/$c.$ext" | head -80
      fail=1
    fi
  done
done

[ "$fail" = 0 ] && echo "defcheck_golden: ALL 2 cases byte-identical"
exit "$fail"
