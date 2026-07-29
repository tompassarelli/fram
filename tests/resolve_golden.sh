#!/usr/bin/env bash
# resolve golden gate — the eleven-comparisons equivalent for resolve.clj.
#
# Captures the FULL observable behavior of the resolve engine (whatever
# implementation currently answers to `$FRAM_RESOLVE`) so a Beagle port can be
# diffed against the original BYTE-FOR-BYTE. Per the migration order on north
# thread 019f9f0e-de2a-7759-bb10-db8b14be6fad, goldens are captured FROM THE
# ORIGINAL before a line of Beagle is written.
#
#   tests/resolve_golden.sh capture <outdir>   # run + write the golden tree
#   tests/resolve_golden.sh verify  <outdir>   # run + diff against it (rc!=0 on drift)
#
# What is compared (nothing is masked — no sed, no sort, no filtering):
#   G1  callgraph JSON over the fram src/fram corpus (14 modules)   stdout
#   G2  callgraph stderr summary line for the same corpus
#   G3  resolve mode over the codegraph .bjs corpus                 stdout+stderr
#   G4  resolve mode's PROJECTED output EDN (identity projection)   every file
#   G5  rename accept  (mod-a/mod-b cross-module)                   out+err+rc
#   G6  rename reject  (trap-collision -> the documented rc 3)      out+err+rc
#   G7  delete         (trap)                                       out+err+rc
#   G8  reorder        (trap)                                       out+err+rc
#   G9  rename over the trap corpus (shadowing/capture traps)       out+err+rc
#   G10 delete of a nonexistent victim (rc 5 contract)              out+err+rc
#   G11 callgraph JSON over the codegraph .bjs corpus               stdout
#
# Env: FRAM_RESOLVE (default ./out/resolve.clj), BEAGLE_HOME, FRAM_BEAGLE.
set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:?usage: resolve_golden.sh capture|verify <dir>}"
GOLD="${2:?usage: resolve_golden.sh capture|verify <dir>}"
RESOLVE="${FRAM_RESOLVE:-$HERE/out/resolve.clj}"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle/main}"
BEAGLE_CLI="${FRAM_BEAGLE:-$BEAGLE/bin/beagle}"
[ -x "$BEAGLE_CLI" ] || { echo "resolve_golden: no Beagle CLI (set FRAM_BEAGLE)" >&2; exit 2; }

# FIXED work dir, deliberately not mktemp: resolve echoes the absolute path of
# every file it projects to stderr, so a randomized dir would differ run-to-run
# and force a mask. A stable path keeps the comparison a straight cmp with ZERO
# masks — the run-to-run difference is removed at the source, not filtered out.
WORK="${TMPDIR:-/tmp}/resolve-golden-run"

# …and because the path is FIXED, two concurrent runs (two agent lanes, a rerun
# while the first is still going) rm -rf each other's corpus mid-flight. That
# failure does NOT read as a lock error — it reads as a bogus
#   resolve_golden: emit-edn failed for /tmp/resolve-golden-run/corpus/<m>.bclj
#   cat: /tmp/resolve-golden-run/fram-<m>.edn.err: No such file or directory
# (the .err file the shell itself just created is already gone), which is easy to
# misread as a code defect. Observed 2026-07-27; it cost a whole lane. So take a
# lock for the run. The lock lives OUTSIDE $WORK because $WORK is deleted below.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$WORK.lock"
  flock -w 1800 9 || { echo "resolve_golden: another run holds $WORK.lock" >&2; exit 2; }
fi

rm -rf "${WORK:?}"; mkdir -p "$WORK"

# ---------------------------------------------------------------- corpora ---
# fram corpus: the 14 engine modules already authored in Beagle.
FRAM_MODULES="types store schema datalog kernel fold import export query tools authority world claims main"
# codegraph corpus: the .bjs trap/shadowing files the rename engine was built against.
BJS="trap mod-a mod-b trap-collision shadow forshadow mapshadow torture types re-a re-b re-c"

emit () { # emit <src> <dest.edn>
  "$BEAGLE_CLI" facts-roundtrip --emit-edn "$1" > "$2" 2>"$2.err" || {
    echo "resolve_golden: emit-edn failed for $1" >&2; cat "$2.err" >&2; exit 2; }
}

# The corpus is COPIED to a stable path before emitting. resolve keys every
# callgraph node by its source's ABSOLUTE path, so emitting straight out of
# $HERE would bake this checkout's directory into the golden and make it
# unverifiable from any other clone or worktree. Copying first makes the golden
# location-independent without masking anything.
CORPUS="$WORK/corpus"; mkdir -p "$CORPUS"

FRAM_EDN=""
for m in $FRAM_MODULES; do
  cp "$HERE/src/fram/$m.bclj" "$CORPUS/$m.bclj"
  emit "$CORPUS/$m.bclj" "$WORK/fram-$m.edn"
  FRAM_EDN="$FRAM_EDN $WORK/fram-$m.edn"
done
BJS_EDN=""
for b in $BJS; do
  cp "$HERE/codegraph/test/$b.bjs" "$CORPUS/$b.bjs"
  emit "$CORPUS/$b.bjs" "$WORK/bjs-$b.edn"
  BJS_EDN="$BJS_EDN $WORK/bjs-$b.edn"
done

# run <name> <mode> <args...> — capture stdout, stderr, rc AND the projected
# files the verb wrote, unmasked. Each case gets its own RESOLVE_OUT so one
# verb's projection can never leak into another's golden.
run () {
  local name="$1"; shift
  local d="$WORK/$name.d"; mkdir -p "$d"
  RESOLVE_OUT="$d" bb -cp "$HERE/out" "$RESOLVE" "$@" \
    >"$WORK/$name.out" 2>"$WORK/$name.err"
  echo "$?" > "$WORK/$name.rc"
  # fold the emitted projection into one comparable blob (sorted, headed by name)
  ( cd "$d" && for f in $(ls 2>/dev/null | LC_ALL=C sort); do echo "===== $f"; cat "$f"; done ) \
    > "$WORK/$name.proj"
}

# G1/G2 — callgraph over the fram corpus.
run callgraph-fram callgraph $FRAM_EDN
# G11 — callgraph over the .bjs corpus.
run callgraph-bjs  callgraph $BJS_EDN
# G3/G4 — resolve (identity projection) over the .bjs corpus.
run resolve-bjs    resolve   $BJS_EDN
# G5 — rename accepted, SCOPE-CORRECT: mod-a and mod-b each define their own
#      `red`; renaming mod-a's must move mod-a's call site and leave mod-b's alone.
run rename-accept  rename    red scarlet mod-a $WORK/bjs-mod-a.edn $WORK/bjs-mod-b.edn
# G6 — rename into an existing name in the same scope: the collision trap.
run rename-reject  rename    red crimson trap-collision $WORK/bjs-trap-collision.edn
# G9 — rename over the shadowing/string/comment trap corpus: `red` is also a
#      substring of "red flag raised", the whole of "red", and a prefix of red-zone.
run rename-trap    rename    red rouge trap $WORK/bjs-trap.edn
# G7 — delete accepted: `banner` has no referrers.
run delete-trap    delete    banner trap $WORK/bjs-trap.edn
# G7b — delete refused: `red` still has referrers (the orphan contract).
run delete-orphan  delete    red trap $WORK/bjs-trap.edn
# G10 — delete with no victim (the rc 5 contract).
run delete-novictim delete   no-such-def-anywhere trap $WORK/bjs-trap.edn
# G8 — reorder over the trap corpus: move `banner` to sit after `red`.
run reorder-trap   reorder   banner trap red $WORK/bjs-trap.edn
# G8b — reorder to the FRONT (empty anchor).
run reorder-front  reorder   use trap "" $WORK/bjs-trap.edn

CASES="callgraph-fram callgraph-bjs resolve-bjs rename-accept rename-reject rename-trap delete-trap delete-orphan delete-novictim reorder-trap reorder-front"

if [ "$MODE" = capture ]; then
  mkdir -p "$GOLD"
  for c in $CASES; do
    for ext in out err rc proj; do cp "$WORK/$c.$ext" "$GOLD/$c.$ext"; done
  done
  echo "resolve_golden: captured $(echo $CASES | wc -w) cases -> $GOLD"
  wc -l "$GOLD"/*.out "$GOLD"/*.err | tail -1
  exit 0
fi

fail=0
for c in $CASES; do
  for ext in out err rc proj; do
    if ! cmp -s "$WORK/$c.$ext" "$GOLD/$c.$ext"; then
      echo "DRIFT $c.$ext"; diff -u "$GOLD/$c.$ext" "$WORK/$c.$ext" | head -40; fail=1
    fi
  done
done
[ $fail = 0 ] && echo "resolve_golden: ALL $(echo $CASES | wc -w) cases byte-identical"
exit $fail
