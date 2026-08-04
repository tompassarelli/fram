# Guarantees

This document is the current source-head guarantee, concurrency, workload, and client-obligation contract, with every claim bound to a named gate or explicit gap.

A guarantee enters this contract only with its gate named. Removing or
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
| D1 | An acked commit is durable: its FRAMLOG frame is covered by a successful `force(true)` barrier before the cohort's immutable head is published | BACKED | `coord_test.clj` asserts two frames/one barrier and no publication on an injected barrier failure; concurrent gate verifies ack/frame identity |
| D2 | A torn trailing frame never corrupts state: authority boot truncates to `:valid-bytes`; exactly the longest committed prefix survives, at every possible cut offset | BACKED | [`../tests/framlog_torn_sweep_test.clj`](../tests/framlog_torn_sweep_test.clj) — every-byte cut sweep, 3,984 boots, exact-image oracle with negative control; plus `coord_test.clj` |
| D3 | A standby (no writer authority) reports a torn tail and never rewrites the log | BACKED | sweep passive arm asserts byte-identical file at every cut; plus `coord_test.clj` |
| D4 | An append-path failure fences the writer (`:durability-ambiguous` / `:recovery-required` / `:coordinator-corrupt`); every waiter in a failed cohort is refused and writes stay fenced until restart | BACKED | `coord_test.clj` fault injection covers singleton and cohort barriers before append, after force, and corrupt replay |
| D5 | Damaged committed bytes are detected loudly on replay; replay never silently produces a divergent image | BACKED | sweep flip arm — 3,968 single-bit/high-bit flips: every one detected by CRC or repaired-to-prefix, zero divergent images. Residual (named, unswept): multi-byte garbage tails; header-region corruption is gated separately in `coord_test.clj` |

## Atomicity

| # | Guarantee | Status | Gate |
|---|---|---|---|
| A1 | One RPC batch remains one transaction frame; a sequencer cohort prepares FIFO on a private root and publishes all covered frames together after one barrier, or publishes none | BACKED | [`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj) batch path; `coord_test.clj` two-frame cohort success/failure arms |

## Isolation and concurrency

| # | Guarantee | Status | Gate |
|---|---|---|---|
| I1 | One active coordinator per `SpaceId`; its singleton FIFO sequencer orders every mutation, while readers take only immutable published snapshots | BACKED | [`../tests/coord_writer_authority_test.clj`](../tests/coord_writer_authority_test.clj), [`../tests/framrpc_write_conc_test.clj`](../tests/framrpc_write_conc_test.clj) |
| I2 | OCC: a stale or future `expected-version` returns `:rpc/conflict` without moving the version | BACKED | `native_rpc_daemon_test.clj`; race shape in `coord_test.clj` (24 racers → exactly 1 ok) |
| I3 | K concurrent socket writers: every acked proposition is durable exactly once, per-writer issue order is preserved, tx-sequence strictly rises, each ack's version equals its frame's tx-seq, and the published root contains that transaction before its ack | BACKED | [`../tests/framrpc_write_conc_test.clj`](../tests/framrpc_write_conc_test.clj) — 8 writers × 25 + 80 OCC racers, durable-frame/barrier/publication verification |
| I4 | Reads do not convoy writes: validate is a non-convoying read; during a 2 s slow query or validate, a lone write acks ≤ 250 ms and ten concurrent writes ack ≤ 1 s (mutations share cohort barriers — see capacity notes) | BACKED | [`../tests/framrpc_latency_convoy_test.clj`](../tests/framrpc_latency_convoy_test.clj) — injected-delay convoy + disconnect-cancels-work |

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
| N3 | `:rpc/scan` and `:rpc/occurrences` accept the `:rpc/query` page cursor; an unpaged reply past ~250 rows (TermCodecV1 depth bound 256, measured 2026-08-02) still fails typed `:term-depth-exceeded`, now from a bounded fold instead of the full corpus. The depth cliff binds EVERY paged response: any `:rpc/query` page near 250–300 rows hits it too, so the contract's 4096-row page ceiling is unusable — the effective page bound is ~200 rows (bench-measured) | PARTIAL | [`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj) paged reassembly, cursor pinning, bounded unpaged fold; no at-scale (350k-Triple) gate |

## Query

| # | Guarantee | Status | Gate |
|---|---|---|---|
| Q1 | Query semantics per [`query-reference.md`](query-reference.md) | BACKED | [`../tests/triple_query_test.clj`](../tests/triple_query_test.clj), aggregate/projection tests |
| Q2 | A page cursor pins its resolved upper sequence and lower-exclusive occurrence bound across intervening commits; eviction may recompute from the pinned immutable root without changing rows | PARTIAL | gated for query, scan, and occurrence loops; retained-root and ordered-result envelopes each cover four versions |
| Q3 | Positive `text-match(entity, attribute, needle)` uses Unicode word/case-folded conjunction semantics over live string values; its index is exact-snapshot keyed, single-flight, and bounded to four versions/64 MiB | BACKED | [`../tests/text_match_test.clj`](../tests/text_match_test.clj), [`../tests/text_index_cache_test.clj`](../tests/text_index_cache_test.clj), differential and performance gates |
| Q4 | Budgets: step budget 10,000,000; timeout `min(60000, requested else 5000)` ms | UNBACKED | numbers live in source only; no gate exercises the limits |
| Q5 | Complete deterministically ordered results are reused by snapshot generation, SpaceId, resolved `{lower-exclusive, upper-inclusive}` view, operation, and canonical request digest; concurrent misses share one computation | PARTIAL | `native_rpc_daemon_test.clj` exercises one evaluator run for two concurrent misses, selector-equivalent historical reuse, lower-bound separation, bounds, counters, and restart reset |
| Q6 | `current`, inclusive `as-of U`, and `since L upper` compose transaction-exact state plus occurrence history; `(L,U]` never materializes the full occurrence relation | BACKED | `datalog_diff_test.clj` 7/7 including temporal and text-match source arms; `model_generative_test.clj` compares as-of state and since events after every generated operation; `native_rpc_daemon_test.clj` covers composition and cursors |
| Q7 | A historical miss uses the newest valid prefix-bound checkpoint and replays its tail; corrupt derived state falls back to canonical history. Sealed ranges preserve rows, while unavailable and explicitly expired ranges remain distinct | PARTIAL | `native_rpc_daemon_test.clj` covers corrupt FRI fallback, sealed-range parity, manifest reload, and both typed errors; active-log compaction and production retention policy are not yet gated |

## Profiles

The relational profile is advisory at this slice. Its declared rules are R1:
all positions are Atoms; R2: t1 is a non-blank String or Keyword; R3: t2 is
a non-blank String or Keyword; R4: t3 is a non-nil Atom (the blank String is
valid). Only the exact `:kernel/profile` anchoring proposition is exempt.

R5 — the declared-vocabulary rule — is listed separately because a profile
opts into it: a space that does not list `R5` beside R1-R4 keeps every
verdict it had. Where it is listed, a proposition whose t2 is a namespaced
Keyword violates R5 unless that Keyword's grouping is asserted in the same
space as `(predicate, :grouped-under, anything)`. The engine's `:kernel/*`
occurrence vocabulary is exempt, as the ontology's regress rule records.

| # | Guarantee | Status | Gate |
|---|---|---|---|
| P1 | A space without a complete profile declaration retains current freeform write behavior | BACKED | [`../tests/profile_lint_test.clj`](../tests/profile_lint_test.clj) undeclared arm; observe logic is not called from the commit path |
| P2 | Enforce mode rejects a violating operation before append, atomically for a batch | UNBACKED | enforce mode is outside the observe-only slice |
| P3 | The prospective admission verdict and advisory lint verdict agree for R1-R4 | BACKED | `profile_lint_test.clj` differential corpus, including one negative per rule and the former namespace-carve-out shape |
| P4 | Tightening a profile preserves committed occurrences and reports older violations by occurrence coordinate | UNBACKED | evolution and occurrence-addressed reporting are outside the observe-only slice |
| P5 | Under R5 a declaring space rejects a namespaced non-`:kernel/` t2 whose grouping is unasserted, and spaces that omit R5 are unaffected | BACKED | `profile_lint_test.clj` R5 arms: ungrouped reject, grouped accept, omitting space, `:kernel/` exemption |

## Write semantics

One writer serializes accepted transactions while clients read immutable published snapshots. Native mutations may carry `expected-version`; stale or future values fail `:rpc/conflict` without advancing the version. Lease fencing is an additional resource guard, not a replacement for transaction OCC or sole-writer authority.

Actions in a batch receive ordered occurrence coordinates and commit as one frame or not at all. Every assertion creates a new occurrence even when equal proposition content is live. Retraction withdraws the latest live equal occurrence and records its exact target; retracting without a live match is an explicit unchanged receipt and does not advance the version. Cardinality, uniqueness, referential integrity, and replacement policy are domain rules, never implied by `t1`, `t2`, or `t3`.

## Workload envelope and client obligations

Reference workload NW-1 is a coordination substrate observed near 350k live Triples: mostly single-proposition writes, occasional atomic batches, targeted reads, paged projection drains, at most eight concurrent bulk clients, one subscription stream, listener leases, and interactive/sweep traffic. Whole-corpus client filtering is excluded.

| Dimension | Contract |
|---|---|
| Request/response | body ≤ 1 MiB; frame ≤ 1 MiB; bulk reads paginate; the effective page bound remains ~200 rows under N3 |
| Latency | targeted read p95 at 500k Triples and ≤8 clients is TBD and may not be cited |
| Writes | sustained single-transaction throughput and restart cost per 100k Triples are TBD and may not be cited |
| Contention | `:rpc/conflict` is normal contract behavior; retry from a fresh base |
| Restart | replay is O(full log); probe `:rpc/status` before serving |
| Overload | unspecified until bounded admission exists |

Clients must retry transient transport errors with bounded backoff, paginate every nontrivial read, never substitute a differently scoped result after timeout, and probe readiness after restart. A fail-closed reaction to one missing read amplifies transient flicker and is outside the contract. Every numeric TBD becomes a guarantee only when its gate is named here.

## Capacity and performance envelope — open rungs

**Current head capacity points (2026-08-02 receipt):**

| Live triples | Result |
|---:|---|
| 999 | Seed and query complete; no capacity failure observed. |
| 3,000 | Paired receipt: boot-to-serving 370 ms; cold two-relation join 7.4 s; write-under-read 66 ops/s; mixed 1W/3R 0.128 ops/s. |
| 9,999 | Functional failure: `:query-work-limit` during the capacity query. |
| 30,000 | Seed exceeded the 15-min timeout; no capacity result was accepted. |

The 7.4 s cold two-relation join is the historical pre-cache floor, not a current head capacity number. The accepted floor predates ordered-result
reuse: a non-direct (multi-relation) query formerly re-ran the whole-corpus
Datalog projection from scratch on every page. The daemon now evaluates one
miss per immutable snapshot and request digest, then slices the cached ordered
vector for repeats and pages. A new snapshot still pays the unchanged scan
evaluator cost; indexed evaluation remains open. The gate
(`bench/in-class/golden.edn`, receipt
[`bench/in-class/results/2026-08-02-framrpc-main.md`](../bench/in-class/results/2026-08-02-framrpc-main.md)) pins today's floor so
the fix is measurable. The 2026-07-28 flat-engine receipts (500–551
writes/s) must not be quoted for head. Remaining envelope work:

- the 30k-scale arm was deliberately skipped (extrapolated hours per run —
  evidence in the receipt); it remains open for indexed evaluation;
- restart cost is O(full log) at head (no checkpoint); the bound is unmeasured;
- committing or projecting a deeply nested recursive Term costs O(depth²)
  (measured via the generative harness: seed runtime 5.1 s at depth 8, 7.9 s
  at 60, 46.2 s at 240) — functional to the 256 depth bound but
  seconds-expensive near it; a known characteristic, not yet optimized;
- mutations serialize through a FIFO sequencer in cohorts of at most 32
  frame-bearing transactions, 1 MiB of request bodies, or 1 ms oldest-intent
  age. A cohort retains one FRAMLOG frame per transaction but shares one
  durability barrier and one immutable snapshot publication. In the same K=8,
  200-write concurrency probe on 2026-08-03, throughput moved from 108.2
  writes/s at `dd4aff2` to 241.2 writes/s with group commit (one run per side;
  shared-host variance is not characterized). The seven read-only FRAMRPC
  operations stay entirely on pinned published roots;
- overload behavior is **unspecified**: the head daemon currently has no
  admission control (unbounded connection futures). Until bounded admission
  lands, nothing can be promised about behavior at saturation — that is a
  gap, stated as one;
- W5 landed runtime telemetry and heap bounds. The observability floor still
  gates every envelope entry.

## Explicit non-guarantees

- No engine access control: isolate by process, network, `SpaceId`, and log
  ([`isolation-and-deployment.md`](isolation-and-deployment.md)).
- Single-machine, single-writer receipts — not distributed consensus.
- Head does not serve v0.3 EDN-line clients; that path is migration-only.
- Equal propositions are not deduplicated: assertion always creates a new occurrence.
