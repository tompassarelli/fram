#!/usr/bin/env bash
# regen: MOAT_N=500 bash bench/moat/blast-radius.sh
source "$(dirname "$0")/common.sh"
make_chain_fixture
bootstrap_graph
t0=$(ns)
GRAPH_REPLY=$(cd "$ROOT" && bb -cp out -e "(load-file \"coord_daemon.clj\") (prn (client $PORT {:op :blast :module \"fixture\" :name \"target\"}))")
GRAPH_MS=$(ms "$t0" "$(ns)")
GRAPH_COUNT=$(sed -n 's/.*:count \([0-9][0-9]*\).*/\1/p' <<<"$GRAPH_REPLY")
t0=$(ns)
MANUAL_COUNT=$(awk '/^\(defn d[0-9]+ / { n++ } END { print n+0 }' "$WORK/graph-src/fixture.bclj")
TEXT_MS=$(ms "$t0" "$(ns)")
[[ "$GRAPH_COUNT" = "$MANUAL_COUNT" ]] || { echo "correctness mismatch graph=$GRAPH_COUNT manual=$MANUAL_COUNT" >&2; exit 1; }
printf 'blast-radius nodes=%s graph-bootstrap-ms=%s graph-query-ms=%s grep+manual-rederive-ms=%s correctness=match\n' "$GRAPH_COUNT" "$BOOT_MS" "$GRAPH_MS" "$TEXT_MS"
stop_graph
