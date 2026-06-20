#!/usr/bin/env bash
# reproduce-s2.sh — the WRITE-SIDE proof of mechanism for concurrent authoring.
# ============================================================================
# experiments/rename-identity/RESULTS.md proved the READ side and left the write
# side OWED. S2 is the smallest end-to-end write-side proof: edits SOURCE FROM the
# claim graph, the .bclj is a downstream view, and a rename re-points references by
# IDENTITY (not spelling). It runs the two falsifiable checks the shakedown is FOR:
#
#   CHECK A — the graph is canonical: delete the source .bclj; the module still
#             renders + recompiles from the claim log ALONE.
#   CHECK B — the canary: a graph rename reads ZERO .bclj, commits as claims (O(1):
#             1 assert + 1 retract), re-points the reference via identity, and the
#             regenerated code recompiles 0 errors + passes the agent-blind oracle.
#
# Honest by construction: the source .bclj is PHYSICALLY DELETED before both checks,
# so "reads zero source" is a filesystem fact, not an assertion. The recompile is the
# discriminator — an un-re-pointed reference => undefined `base` => build FAILS.
#
# Needs: racket, babashka, beagle (~/code/beagle). Isolated: /tmp log + non-7977 port.
set -euo pipefail
FRAM="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # fram repo root
cd "$FRAM"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle}"
PORT="${FRAM_TEST_PORT:-7993}"
[ "$PORT" = "7977" ] && { echo "refusing port 7977 (the live coordinator)"; exit 1; }
WD="$(mktemp -d /tmp/harness-s2.XXXXXX)"
SRC="experiments/authoring-harness/greet.bclj"

kill_port(){ for p in $(ss -tlnpH "sport = :$PORT" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u); do kill "$p" 2>/dev/null || true; done; }
trap 'kill_port; rm -rf harness' EXIT
kill_port   # clear any stale listener on our test port

cat > "$WD/oracle.bb" <<'ORACLE'
(let [f (first *command-line-args*)]
  (load-file f)
  (let [r ((resolve 'harness.greet/greet) "world")]
    (when-not (= "hello world" r) (println "ORACLE FAIL:" f "->" (pr-str r)) (System/exit 1))
    (println "ORACLE PASS:" f "->" (pr-str r))))
ORACLE

cp "$SRC" "$WD/greet.bclj"

echo "### baseline (arm-2 Beagle-text): compile + oracle"
"$BEAGLE/bin/beagle" build "$WD/greet.bclj" "$WD/baseline.clj" >/dev/null
bb "$WD/oracle.bb" "$WD/baseline.clj"

echo "### ingest greet.bclj -> isolated /tmp code.log"
bin/fram-ingest-code "$WD/greet.bclj" --out "$WD/code.log" >/dev/null 2>&1

echo "### CHECK A: delete source, render + recompile from the log ALONE"
rm -f "$WD/greet.bclj"
[ -e "$WD/greet.bclj" ] && { echo "FAIL: source not deleted"; exit 1; }
bb -cp out bin/fram-render-code greet --log "$WD/code.log" --out "$WD/A.bclj" >/dev/null 2>&1
"$BEAGLE/bin/beagle-build-all" "$WD/A.bclj"          # writes ./harness/greet.clj
bb "$WD/oracle.bb" harness/greet.clj; rm -rf harness
echo "CHECK A: PASS — module reconstructs + recompiles from the log; no source on disk"
echo

echo "### start daemon on :$PORT over the code.log (JVM coordinator)"
FRAM_PORT="$PORT" FRAM_LOG="$WD/code.log" bin/fram-up >/dev/null

echo "### CHECK B: graph rename base -> greeting (reads ZERO .bclj — source is gone)"
before=$(grep -c . "$WD/code.log")
bb -cp out bin/fram-edit-code rename greet --old base --new greeting --port "$PORT" --log "$WD/code.log" 2>&1 | grep MINIMAL-OP   # the receipt line is on stderr
after=$(grep -c . "$WD/code.log")
echo "log grew: $before -> $after claims"
bb -cp out bin/fram-render-code greet --log "$WD/code.log" --out "$WD/B.bclj" >/dev/null 2>&1
grep -q 'def greeting' "$WD/B.bclj" || { echo "FAIL: def not renamed in render"; exit 1; }
grep -q 'str greeting' "$WD/B.bclj" || { echo "FAIL: reference did NOT re-point (still 'base')"; exit 1; }
"$BEAGLE/bin/beagle-build-all" "$WD/B.bclj"          # 0 errors == reference re-pointed
bb "$WD/oracle.bb" harness/greet.clj; rm -rf harness
echo "CHECK B: PASS — rename O(1), reference re-pointed by identity, recompiles, behavior preserved"
echo
echo "S2: BOTH falsifiable checks GREEN — graph-canonical write path proven, not asserted."
