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

# Coordinator-layer modules being migrated off hand-written Clojure (engine core
# port, north thread 019f9f0e-de2a-7759-bb10-db8b14be6fad). They live in src/
# rather than src/fram/ because their namespaces are bare (`pull`, `resolve-core`)
# — coord_daemon.clj load-file's the coordinator layer instead of requiring it.
#   pull         M0, ADOPTED: src/pull.bclj is a RENDERED VIEW of the fact graph
#                (`;; @upstream:graph`) — edit it with the graph-edit verbs, never
#                as text, then rerun this script. out/pull.clj is what
#                coord_daemon.clj `(require 'pull)`s; the hand-written pull.clj is
#                gone (byte-identical golden parity, tests/goldens/pull).
#   resolve_core M1 Cut A: the CRDT order-key algebra + form vocabulary that
#                resolve.clj now aliases (rc/*), and which coord_daemon.clj and
#                tests/coord_crdt_*.clj read through it.
#   resolve_read M1 Cut B: the view-relative read layer (election, node reads,
#                ordered-tree navigation) that resolve.clj now wraps as rr/*.
#                The ^:dynamic state stays in resolve.clj and is passed in.
#   resolve_binds M1 Cut C: the binding extractor — what a destructuring
#                pattern, param vector, let/for binding vector or match pattern
#                binds, and in what order (rb/*).
#   resolve_modules M1 Cut D: one module's frame (top-level defs, types,
#                synthesized accessors) and its import/export surface (rm/*).
#   resolve_render M1 Cut E: render a node back to source (render-sym,
#                node->str, node->canon) and the O(N) anchor search over a def
#                subtree that replace-in-body addresses (rv/*).
#   resolve_walk  M1 Cut G: the lexical walk itself — descend the ordered AST,
#                carry the scope stack, and write each reference's refers_to
#                (plus the comment resolver and the per-src walk driver) (rw/*).
#   resolve_query M1 Cut F: the code queries that run ON the resolved graph
#                — call-edges, blast-closure, binding-privacy and the
#                stratified-Datalog dead-private query (rq/*).
#   resolve_verbs M1 Cut H: the authoring verbs themselves — rename,
#                upsert/insert/set-body/replace-in-body/delete/reorder and the
#                graph-path op dispatch (rvb/*). The dynamic state + the host
#                helpers they call are passed in as one Verb record.
#   resolve_mint  M1 Cut I: the mint/author layer — a datum enters the store as
#                FACTS (register!/mint-leaf!/mint-datum!), the live fN edge
#                reader, fact retirement, the no-capture scan and the
#                projection writer (rmi/*). The dynamic state is passed in as
#                one Mint record.
#   fri_port     M3: typed implementation of the mmap image writer/reader.
#   fri          M3: public facade preserving the original `fri` namespace and
#                its dynamic cache-cap test seam.
for m in pull resolve_core resolve_read resolve_binds resolve_modules resolve_render resolve_query resolve_walk resolve_mint resolve_verbs fri_port fri; do
  BEAGLE_EMIT_SRCLOC=0 direnv exec "$BEAGLE" "$BEAGLE/bin/beagle-build" \
    "$SRC/$m.bclj" "$OUT/$m.clj" >/dev/null
  echo "  built $m"
done

# codegraph — the code-as-facts analysis driver. Lives outside src/fram (it is a
# TENANT of the engine, renting only fram.store + fram.datalog; see
# tests/codegraph_seam_test.clj) and emits to out/codegraph.clj, so
# `bb -cp out:codegraph/src:. -m codegraph <corpus.facts>` runs it. Its upstream
# is the FACT GRAPH (`;; @upstream:graph`): the .bclj is a regenerated view, so
# edit it with the graph-edit verbs, never as text — then rerun this script.
# The analysis modules alongside it (callgraph, rep_jurisdiction, roundtrip_fram,
# supersession_check, rename) are graph-upstream too; each emits to
# out/<module>.clj, so `bb -cp out:. -m <ns> ...` runs it. callgraph must build
# BEFORE nothing in particular (Beagle resolves the require from the .bclj), but
# codegraph rents its parse-corpus/build-graph at runtime from out/callgraph.clj.
for m in codegraph callgraph rep_jurisdiction roundtrip_fram supersession_check rename; do
  BEAGLE_EMIT_SRCLOC=0 direnv exec "$BEAGLE" "$BEAGLE/bin/beagle-build" \
    "$HERE/codegraph/src/$m.bclj" "$OUT/$m.clj" >/dev/null
  echo "  built $m"
done
echo "fram built -> $OUT"
