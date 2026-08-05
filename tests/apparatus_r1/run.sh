#!/usr/bin/env bash
# ============================================================================
# R-1 apparatus driver — TEST-ONLY, non-production. Runs every cell, proving the
# S1-S4 sensitivity split (RED vs B-prime, GREEN vs B2), the cross-runtime
# identity oracle (bb==JVM==JS), the residual oracle, the K0-K8 kill matrix, the
# external kill runner + acked-write ledger, the exact-mode/doctor cells, and the
# 104k reference perf/RSS run. Exits NONZERO if any cell returns an unexpected
# verdict (each cell exits nonzero on mismatch via r1.harness/finish!).
#
# SAFETY: all corpora + processes are scratch under a single /tmp dir. No fram
# daemon is started; no port is bound; 127.0.0.1:7977, canonical logs, user state,
# out/ and package files are never touched. Ports (if ever needed) start at 8500.
# ============================================================================
set -u
cd "$(dirname "$0")"
HERE="$(pwd -P)"
export FRAM_PORT_BASE=8500                       # scratch floor; nothing binds it here

SCRATCH="${R1_SCRATCH:-/tmp/r1-apparatus-$$}"
mkdir -p "$SCRATCH"

cleanup() {
  # Reap any actor children we may have spawned (match our scratch path only).
  pkill -KILL -f "cells/actor.clj .*${SCRATCH##*/}" 2>/dev/null || true
  pkill -KILL -f "apparatus_r1/cells/actor.clj" 2>/dev/null || true
  # Remove the scratch tree by its literal absolute path (brace-guarded).
  rm -rf "${SCRATCH:?}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

FAIL=0
run() { # <label> <cmd...>
  local label="$1"; shift
  echo; echo "########## $label ##########"
  if "$@"; then echo ">>> $label: OK"; else echo ">>> $label: UNEXPECTED (exit $?)"; FAIL=1; fi
}

# ---- 1. cross-runtime identity oracle: bb==JVM==JS, both models ----
echo "########## identity oracle (bb / JVM / JS) ##########"
ID="$SCRATCH/ident"; mkdir -p "$ID"
bb -cp lib identity/mkcorpus.clj "$ID" || FAIL=1
for MODEL in b2 bprime; do
  bb  -cp lib identity/kev.clj $MODEL "$ID/coordination.log" "$ID/telemetry.log" > "$ID/bb.$MODEL"  || FAIL=1
  clojure -Sdeps '{:paths ["lib"]}' -M identity/kev.clj $MODEL "$ID/coordination.log" "$ID/telemetry.log" > "$ID/jvm.$MODEL" 2>/dev/null || FAIL=1
  node identity/kev.mjs $MODEL "$ID/coordination.log" "$ID/telemetry.log" > "$ID/js.$MODEL" || FAIL=1
  if diff -q "$ID/bb.$MODEL" "$ID/jvm.$MODEL" >/dev/null && diff -q "$ID/bb.$MODEL" "$ID/js.$MODEL" >/dev/null; then
    echo "  TRIRUNTIME-AGREE bb==JVM==JS ($MODEL)"
  else
    echo "  TRIRUNTIME-DISAGREE ($MODEL)"; diff "$ID/bb.$MODEL" "$ID/jvm.$MODEL"; diff "$ID/bb.$MODEL" "$ID/js.$MODEL"; FAIL=1
  fi
done
# S1 divergence: B2 preserves the byte-identical append (tx=2 twice), B-prime suppresses (once).
b2n=$(grep -c '^2	' "$ID/bb.b2"); bpn=$(grep -c '^2	' "$ID/bb.bprime")
echo "  S1 multiplicity of tx=2 — B2=$b2n (expect 2) B-prime=$bpn (expect 1)"
[ "$b2n" = 2 ] && [ "$bpn" = 1 ] || { echo "  S1 IDENTITY DIVERGENCE UNEXPECTED"; FAIL=1; }

# ---- 2. reference-model cells (bb) ----
run "sensitivity S1-S4"      bb -cp lib cells/sensitivity.clj
run "residual oracle"        bb -cp lib cells/residual.clj    "$SCRATCH/residual"
run "mode/doctor cells"      bb -cp lib cells/mode_cells.clj  "$SCRATCH/mode"
run "K0-K8 + reverse-pin"    bb -cp lib cells/kmatrix.clj     "$SCRATCH/kmatrix"
run "kill runner + ledger"   bb -cp lib cells/kill_ledger.clj "$SCRATCH/kill"

# ---- 3. 104k perf/RSS (JVM, compiled, production-representative; 512MiB heap) ----
run "104k perf/RSS"          clojure -J-Xmx512m -Sdeps '{:paths ["lib"]}' -M cells/perf_104k.clj "$SCRATCH/perf"

# ---- 4. negative control: the apparatus MUST fail loud on a wrong verdict ----
echo; echo "########## negative control (must exit nonzero) ##########"
if bb -cp lib cells/negcontrol.clj >/dev/null 2>&1; then
  echo ">>> negative control DID NOT FAIL — harness is untrustworthy"; FAIL=1
else
  echo ">>> negative control failed loud as required (self-check OK)"
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "==== R-1 APPARATUS: ALL CELLS MATCHED CONTRACT EXPECTATION ===="
else
  echo "==== R-1 APPARATUS: UNEXPECTED VERDICT — see above ===="
fi
exit "$FAIL"
