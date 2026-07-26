#!/usr/bin/env bash
# Recompile Fram's Beagle (.bclj) sources to Clojure into out/.
#
# You do NOT need this to run Fram — the compiled Clojure in out/ is
# committed and runs on babashka (bin/fram). You only need this to rebuild
# from the .bclj sources, which requires Beagle (a typed Lisp that compiles to
# Clojure) at $BEAGLE_HOME (default ~/code/beagle), entered via direnv.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"; OUT="$HERE/out"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle}"

mkdir -p "$OUT/fram"
cp "$SRC/fram/rt.clj" "$OUT/fram/rt.clj"     # hand-written runtime ships as-is
cp "$SRC/fram/json.clj" "$OUT/fram/json.clj" # JSON runtime for the clockify module
cp "$SRC/fram/authority_json.clj" "$OUT/fram/authority_json.clj" # strict raw JSON host boundary
for m in types store schema datalog kernel fold import export query tools authority world claims main; do
  BEAGLE_EMIT_SRCLOC=0 direnv exec "$BEAGLE" "$BEAGLE/bin/beagle-build" \
    "$SRC/fram/$m.bclj" "$OUT/fram/$m.clj" >/dev/null
  echo "  built fram/$m"
done

# codegraph — the code-as-facts analysis driver. Lives outside src/fram (it is a
# TENANT of the engine, renting only fram.store + fram.datalog; see
# tests/codegraph_seam_test.clj) and emits to out/codegraph.clj, so
# `bb -cp out:codegraph/src:. -m codegraph <corpus.facts>` runs it. Its upstream
# is the FACT GRAPH (`;; @upstream:graph`): the .bclj is a regenerated view, so
# edit it with the graph-edit verbs, never as text — then rerun this script.
# The analysis modules alongside it (roundtrip_fram, ...) are graph-upstream too;
# each emits to out/<module>.clj so `bb -cp out -m <ns> ...` runs it.
for m in codegraph roundtrip_fram; do
  BEAGLE_EMIT_SRCLOC=0 direnv exec "$BEAGLE" "$BEAGLE/bin/beagle-build" \
    "$HERE/codegraph/src/$m.bclj" "$OUT/$m.clj" >/dev/null
  echo "  built $m"
done
echo "fram built -> $OUT"
