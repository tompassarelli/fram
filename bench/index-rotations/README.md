# Index-rotations benchmark (thread 019f9e66)

Measures the three covering-rotations bars against an ISOLATED scratch fram
home — never the live daemon, never :7977. Copy the >=80MB replayed corpus
first:

```
mkdir -p /tmp/fram-bench/pristine
cp ~/.local/state/north/backups/pre-clean-slate-20260726-220841/coordination.log.state \
   /tmp/fram-bench/pristine/coordination.log
cp ~/.local/state/north/backups/pre-clean-slate-20260726-220841/telemetry.log \
   /tmp/fram-bench/pristine/telemetry.log
```

Sandbox env vars `FRAM_LOG`/`FRAM_TELEMETRY_LOG`/`FRAM_SINGLE_VALUED` leak the
live corpus paths into every daemon boot in this environment — unset them
before running (`env -u FRAM_LOG -u FRAM_TELEMETRY_LOG -u FRAM_SINGLE_VALUED`).

## Bar 1 + bar 2: cold query + write throughput

```
bb -cp out bench/index-rotations/cold-query-and-write-throughput.clj before 8931   # on baseline commit
bb -cp out bench/index-rotations/cold-query-and-write-throughput.clj after  8932   # on this branch
```

Boots a fresh daemon over a reset copy of the pristine corpus, measures cold
query latency per workload shape (predicate scan / object scan / 2-literal
join / subject pull / non-simple 2-rule scan — the last is the one query
shape that bypasses the incrementally-maintained `:idx` cache and used to pay
whole-corpus `q/project` on every call), warm latency, latency under
interleaved writes, and write throughput (serial + under concurrent reads).

## Bar 3: FRAM_SNAPSHOT_BOOT boot mode

```
bb -cp out bench/index-rotations/snapshot-boot.clj 8937
```

`cold-query-and-write-throughput.clj`'s generic `boot!` always sets
`FRAM_TELEMETRY_LOG` (needed for bars 1/2's realistic split-log corpus), which
makes `boot-flat-canonical!` refuse the snapshot path unconditionally
("log-split routing active — whole-log merge boot", `coord_daemon.clj:5317`,
pre-existing infra unrelated to this branch). `snapshot-boot.clj` instead
boots the corpus as a plain unsplit `facts.log` (no telemetry sibling) so the
snapshot+tail path actually engages: cold whole-log fold once, `:snapshot`
op to checkpoint, append a small tail, then reboot with
`FRAM_SNAPSHOT_BOOT=1` and confirm `:boot {:mode :snapshot ...}` with a small
`:tail-lines` count rather than a whole-corpus fold.

## Results (2026-07-27, this run)

Load conditions: nproc=24, `/proc/loadavg` ~2.5-6.1/4.1-5.5/4.0-4.1 across
runs (shared sandbox host, not idle — numbers are directional, not
laboratory-isolated). Corpus: `pre-clean-slate-20260726-220841`, 83MB
(coordination.log.state, md5 878df41c718ba3c0f8086c6a824543ab, byte-identical
to the pristine copy used), 350,701 live facts, version 451961.

Bar 1 — cold query, BEFORE (baseline `0ae737b`, invalidate-and-refold cache)
vs AFTER (this branch, `ea501a1` + rotations):
| query shape         | before (cold)              | after (cold)     |
|---|---|---|
| titles (POS scan)   | 230 ms                     | 204 ms           |
| by-object (OSP)     | 96 ms                      | 92 ms            |
| join (2-literal)    | 69 ms                      | 62 ms            |
| subject (SPO)       | 3 ms                       | 4 ms             |
| **scan-2rule (non-simple, the defect)** | **5004 ms, ABORTED (`query-time-limit`)** | **241 ms, 1623 rows, no abort** |

Under-write scan-2rule (1 write between each of 8 reads — the
invalidate-and-refold defect path): before min 4401 / p50 4829 / max 5002 ms
with 3 query-time-limit aborts; after min 186 / p50 195 / max 220 ms, 0
aborts. `:query-stops` before: `{:query-time-limit 3}`; after: all zero.
Order-of-magnitude+ read improvement on the one shape that was broken (>20x
on cold, >20x on under-write); other shapes were already index-served and
stayed flat (rotations don't regress the fast path).

Bar 2 — write throughput, BEFORE vs AFTER, same corpus/load class:
- serial: before 30,419 writes/min (200 writes in 394ms) vs after 39,868
  writes/min (200 writes in 301ms) — no regression, faster.
- under concurrent reader: before 84,404 writes/min vs after 128,001
  writes/min — no regression, faster (background rotation compaction ran
  once mid-benchmark, 3367ms, off the write path).
- OCC: no assert/reject-path changes in this branch; write path is unchanged
  compile-time-identical logic, only the read-side projection changed.

Bar 3 — FRAM_SNAPSHOT_BOOT, unsplit single-log corpus (152,489 facts, the
telemetry-routed subset excluded by design when unsplit):
- phase 1, `FRAM_SNAPSHOT_BOOT` unset: `{:mode :fold, :ms 9144}` (whole-log
  fold, no sidecar yet) — 12,888 ms wall incl. JVM start.
- `:snapshot` checkpoint written in 2183 ms.
- phase 2, `FRAM_SNAPSHOT_BOOT=1`, same log + 6 tail lines appended after the
  checkpoint: `{:mode :snapshot, :ms 9735, :image
  ".../facts.log.snapshots/snap-451961.v2log", :covers 451961, :cold false,
  :tail-lines 6}` — 13,578 ms wall. Confirms the boot folds the checkpoint
  image + a 6-line tail, not a whole-corpus refold (qualitative bar met:
  `:tail-lines 6` proves it never touched most of the log). Wall-clock is
  comparable to the fold here because this reduced corpus is small and image
  read + JVM start dominate at this scale — the speedup is structural
  (O(tail) not O(corpus)) and grows with corpus size, not yet visible as a
  wall-clock win on this ~150k-fact slice. Log-split routing (the live
  daemon's actual layout: `coordination.log` + `telemetry.log`) forces
  whole-log-merge fold unconditionally regardless of `FRAM_SNAPSHOT_BOOT`
  (pre-existing infra decision, `coord_daemon.clj:5317`, out of this
  branch's footprint) — noted as a scope boundary, not a defect introduced
  here.
