#!/usr/bin/env bash
# ============================================================================
# ksweep_run.sh — System 3 driver: gate cost vs corpus size K.
# Builds real-module SUBSET corpora at K in {2,4,8,ALL} via fram-ingest-code,
# measures each with coord_ksweep.clj, keeping MATERIALIZATION (frame build) and
# COORDINATION (coherence scan) separate. SAFE: /tmp corpora only.
# ============================================================================
set -u
cd /home/tom/code/fram
mapfile -t ALL < <(ls src/fram/*.bclj)
TOT=${#ALL[@]}
echo "=== System 3 K-SWEEP — gate cost vs corpus size K ==="
echo "available .bclj modules: $TOT"
echo "scan_floor=O(K) by-p NAME (unscopable) | whole_frame=O(K) MATERIALIZATION | scoped_frame=O(affected) MATERIALIZATION | coh_scan=COORDINATION/gate"
echo
for K in 2 4 8 "$TOT"; do
  LOG="/tmp/store-ksweep-K${K}.log"
  SUB=("${ALL[@]:0:$K}")
  if ! bb bin/fram-ingest-code "${SUB[@]}" --out "$LOG" >/tmp/ingest-K${K}.log 2>&1; then
    echo "K=$K ingest FAILED:"; tail -3 /tmp/ingest-K${K}.log; continue
  fi
  if ! bb -cp out coord_ksweep.clj "$LOG" 2>/tmp/ksweep-err-K${K}.log; then
    echo "K=$K measure FAILED:"; tail -8 /tmp/ksweep-err-K${K}.log
  fi
done
echo
echo "=== END K-SWEEP ==="
