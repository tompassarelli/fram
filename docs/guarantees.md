# Guarantees

**Status:** the current guarantee contract for the source-head engine.

A guarantee enters this document only with its gate named. Removing or
excluding a gate removes the guarantee. A failure observed in production must
land on exactly one line of this document: either a guaranteed line (a Fram
defect — the guarantee was false, and its gate gets strengthened with the
counterexample) or an unguaranteed line (the guarantee was missing — it gets
written here, then gated).

Vocabulary:

- **BACKED** — an enforced CI gate pins the guarantee
  ([`../tests/occurrence_native_ci_manifest.txt`](../tests/occurrence_native_ci_manifest.txt)
  disposition `run-*`).
- **PARTIAL** — executable evidence exists but does not gate, covers one
  scenario, or covers only part of the stated guarantee.
- **UNBACKED** — mechanism or prose only. Listed deliberately: an unbacked
  guarantee is a work order, not a marketing line.

## Surface binding

Two runtime surfaces exist until cluster migration completes:

- **head** — the occurrence-native coordinator, binary FRAMLOG, and FRAMRPC v1
  (this source tree). Every guarantee below binds to head unless marked.
- **v0.3** — the deployed flat-log generation governed by
  [`coordinator-cutover.md`](coordinator-cutover.md). Its guarantee suite is
  excluded at head; v0.3 guarantees are frozen with that release and are not
  restated here. Head does not serve v0.3 EDN-line clients (see
  non-guarantees).

## Durability

| # | Guarantee | Status | Gate |
|---|---|---|---|
| D1 | An acked commit is durable: one FRAMLOG frame is appended, flushed, and `force(true)`-synced before the new head is published | PARTIAL | mechanism `coord.clj` `append-frame-durable!`; no gate asserts the sync ordering — torn-sweep + crash-kill work in flight |
| D2 | A torn trailing frame never corrupts state: authority boot truncates to `:valid-bytes`; exactly the longest committed prefix survives, at every possible cut offset | BACKED | [`../tests/framlog_torn_sweep_test.clj`](../tests/framlog_torn_sweep_test.clj) — every-byte cut sweep, 3,984 boots, exact-image oracle with negative control; plus `coord_test.clj` |
| D3 | A standby (no writer authority) reports a torn tail and never rewrites the log | BACKED | sweep passive arm asserts byte-identical file at every cut; plus `coord_test.clj` |
| D4 | An append-path failure fences the writer (`:durability-ambiguous` / `:recovery-required` / `:coordinator-corrupt`); writes are refused until restart | PARTIAL | `coord_test.clj` fault injection at three points on `append-frame-durable!` |
| D5 | Damaged committed bytes are detected loudly on replay; replay never silently produces a divergent image | BACKED | sweep flip arm — 3,968 single-bit/high-bit flips: every one detected by CRC or repaired-to-prefix, zero divergent images. Residual (named, unswept): multi-byte garbage tails; header-region corruption is gated separately in `coord_test.clj` |

## Atomicity

| # | Guarantee | Status | Gate |
|---|---|---|---|
| A1 | A batch prepares under one locked snapshot and commits as one transaction frame (one fsync), or fails before any append | PARTIAL | happy path in [`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj); no crash-mid-batch gate |

## Isolation and concurrency

| # | Guarantee | Status | Gate |
|---|---|---|---|
| I1 | One active coordinator per `SpaceId`; accepted transactions serialize under a single lock | BACKED | [`../tests/coord_writer_authority_test.clj`](../tests/coord_writer_authority_test.clj) |
| I2 | OCC: a stale or future `expected-version` returns `:rpc/conflict` without moving the version | BACKED | `native_rpc_daemon_test.clj`; race shape in `coord_test.clj` (24 racers → exactly 1 ok) |
| I3 | K concurrent socket writers: every acked fact is durable exactly once, per-writer issue order is preserved, tx-sequence strictly rises, each ack's version equals its frame's tx-seq | BACKED | [`../tests/framrpc_write_conc_test.clj`](../tests/framrpc_write_conc_test.clj) — 8 writers × 25 + 80 OCC racers, durable-frame verification |
| I4 | Reads do not convoy writes: validate is a non-convoying read; during a 2 s slow query or validate, a lone write acks ≤ 250 ms and ten concurrent writes ack ≤ 1 s (writes serialize per-commit fsync — see capacity notes) | BACKED | [`../tests/framrpc_latency_convoy_test.clj`](../tests/framrpc_latency_convoy_test.clj) — injected-delay convoy + disconnect-cancels-work |

## Ordering and recovery

| # | Guarantee | Status | Gate |
|---|---|---|---|
| O1 | `tx-sequence` + `op-ordinal` define exact logical order; mutation receipts return occurrence coordinates | BACKED | [`../tests/triple_kernel_test.clj`](../tests/triple_kernel_test.clj), `coord_test.clj`, [`../tests/model_generative_test.clj`](../tests/model_generative_test.clj) — seeded op sequences compared against a pure model after every op |
| R1 | Replay restores logical order and occurrence liveness; restart resumes at the next tx without duplication | PARTIAL | `native_rpc_daemon_test.clj` restart — idle daemon, tiny corpus; `model_generative_test.clj` cold-restart arm compares the model and a byte-exact store dump per generated sequence; no restart-under-load, no at-scale replay bound |
| R2 | Old flat logs are accepted only by the one-shot migration command | BACKED | [`../tests/triple_log_migration_test.clj`](../tests/triple_log_migration_test.clj) |

## Wire

| # | Guarantee | Status | Gate |
|---|---|---|---|
| N1 | Closed 13-operation FRAMRPC v1; unknown tags, fields, or trailing bytes are rejected; EDN is refused on the wire | BACKED | [`../tests/fram_rpc_v1_test.clj`](../tests/fram_rpc_v1_test.clj), `native_rpc_daemon_test.clj`, boundary ratchet |
| N2 | Limits: body ≤ 1,048,576 B; frame ≤ 1,048,602 B; string ≤ 1 MiB; term nodes ≤ 65,536; depth ≤ 256 | PARTIAL | decode-side truncation and oversize gated; encode-side enforcement untested |
| N3 | `:rpc/scan` and `:rpc/occurrences` accept the `:rpc/query` page cursor; an unpaged reply past ~250 rows (TermCodecV1 depth bound 256, measured 2026-08-02) still fails typed `:term-depth-exceeded`, now from a bounded fold instead of the full corpus. The depth cliff binds EVERY paged response: any `:rpc/query` page near 250–300 rows hits it too, so the contract's 4096-row page ceiling is unusable — the effective page bound is ~200 rows (bench-measured) | PARTIAL | [`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj) paged reassembly, cursor pinning, bounded unpaged fold; no at-scale (350k-fact) gate |

## Query

| # | Guarantee | Status | Gate |
|---|---|---|---|
| Q1 | Query semantics per [`query-reference.md`](query-reference.md) | BACKED | [`../tests/triple_query_test.clj`](../tests/triple_query_test.clj), aggregate/projection tests |
| Q2 | A page cursor pins its snapshot across intervening commits; eviction may recompute from the pinned immutable root without changing rows | PARTIAL | gated for query, scan, and occurrence loops; retained-root and ordered-result envelopes each cover four versions |
| Q3 | Budgets: step budget 10,000,000; timeout `min(60000, requested else 5000)` ms | UNBACKED | numbers live in source only; no gate exercises the limits |
| Q4 | Complete deterministically ordered results are reused by snapshot generation, SpaceId, version, operation, and canonical request digest; concurrent misses share one computation | PARTIAL | `native_rpc_daemon_test.clj` exercises one evaluator run for two concurrent misses, version separation, historical reuse, bounds, counters, and restart reset |

## Capacity and performance envelope — the open rungs

**The first current-engine numbers exist (2026-08-02, 3,000 live triples,
paired runs, golden-ratcheted) and they are honest, not flattering:**
boot-to-serving 370 ms; cold two-relation join 7.4 s; write-under-read
66 ops/s; mixed 1W/3R 0.128 ops/s. The accepted floor predates ordered-result
reuse: a non-direct (multi-relation) query formerly re-ran the whole-corpus
Datalog projection from scratch on every page. The daemon now evaluates one
miss per immutable snapshot and request digest, then slices the cached ordered
vector for repeats and pages. A new snapshot still pays the unchanged scan
evaluator cost; indexed evaluation remains open. The gate
(`bench/in-class/golden.edn`, receipt
`bench/in-class/results/2026-08-02-framrpc-main.*`) pins today's floor so
the fix is measurable. The 2026-07-28 flat-engine receipts (500–551
writes/s) must not be quoted for head. Remaining envelope work:

- the 30k-scale arm was deliberately skipped (extrapolated hours per run —
  evidence in the receipt); it remains open for indexed evaluation;
- restart cost is O(full log) at head (no checkpoint); the bound is unmeasured;
- committing or projecting a deeply nested recursive Term costs O(depth²)
  (measured via the generative harness: seed runtime 5.1 s at depth 8, 7.9 s
  at 60, 46.2 s at 240) — functional to the 256 depth bound but
  seconds-expensive near it; a known characteristic, not yet optimized;
- writes serialize through one per-commit fsync under the coordinator lock
  (~35 ms/commit observed on local disk → tens of committed tx/s serialized;
  batches amortize). Group commit does not exist. The seven read-only FRAMRPC
  operations run against pinned immutable roots outside that lock; mutations
  retain synchronous per-frame forcing;
- overload behavior is **unspecified**: the head daemon currently has no
  admission control (unbounded connection futures). Until bounded admission
  lands, nothing can be promised about behavior at saturation — that is a
  gap, stated as one;
- the daemon currently emits no runtime telemetry and sets no heap bounds. A
  performance guarantee without observability is unfalsifiable in production,
  so the observability floor gates every envelope entry.

## Explicit non-guarantees

- No engine access control: isolate by process, network, `SpaceId`, and log
  ([`isolation-and-deployment.md`](isolation-and-deployment.md)).
- Single-machine, single-writer receipts — not distributed consensus.
- Head does not serve v0.3 EDN-line clients; that path is migration-only.
- Equal propositions are not deduplicated: assertion always creates a new
  occurrence (this is contract, not a defect —
  [`concurrency-and-writes.md`](concurrency-and-writes.md)).
