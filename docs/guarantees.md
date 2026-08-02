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
| D2 | A torn trailing frame never corrupts state: authority boot truncates to `:valid-bytes`; exactly the longest committed prefix survives, at every possible cut offset | PARTIAL | one scenario gated in [`../tests/coord_test.clj`](../tests/coord_test.clj); exhaustive byte-cut sweep `tests/framlog_torn_sweep_test.clj` in flight |
| D3 | A standby (no writer authority) reports a torn tail and never rewrites the log | PARTIAL | single scenario in `coord_test.clj`; sweep passive arm in flight |
| D4 | An append-path failure fences the writer (`:durability-ambiguous` / `:recovery-required` / `:coordinator-corrupt`); writes are refused until restart | PARTIAL | `coord_test.clj` fault injection at three points on `append-frame-durable!` |
| D5 | Damaged committed bytes are detected loudly on replay; replay never silently produces a divergent image | UNBACKED | corrupt-tail arm of the sweep in flight; per-frame checksum is future work |

## Atomicity

| # | Guarantee | Status | Gate |
|---|---|---|---|
| A1 | A batch prepares under one locked snapshot and commits as one transaction frame (one fsync), or fails before any append | PARTIAL | happy path in [`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj); no crash-mid-batch gate |

## Isolation and concurrency

| # | Guarantee | Status | Gate |
|---|---|---|---|
| I1 | One active coordinator per `SpaceId`; accepted transactions serialize under a single lock | BACKED | [`../tests/coord_writer_authority_test.clj`](../tests/coord_writer_authority_test.clj) |
| I2 | OCC: a stale or future `expected-version` returns `:rpc/conflict` without moving the version | BACKED | `native_rpc_daemon_test.clj`; race shape in `coord_test.clj` (24 racers → exactly 1 ok) |
| I3 | K concurrent socket writers: every acked fact is durable exactly once, per-writer issue order is preserved, tx-sequence strictly rises | UNBACKED | port of the K=8 socket-writer + durable-log-byte verification pattern (dead `store_write_conc_test.clj`) — planned |
| I4 | Ack latency is bounded under contention (no lock convoy) | UNBACKED | port of the injected-delay ≤250 ms pattern (dead `coord_lock_convoy_test.clj`) — planned |

## Ordering and recovery

| # | Guarantee | Status | Gate |
|---|---|---|---|
| O1 | `tx-sequence` + `op-ordinal` define exact logical order; mutation receipts return occurrence coordinates | BACKED | [`../tests/triple_kernel_test.clj`](../tests/triple_kernel_test.clj), `coord_test.clj` |
| R1 | Replay restores logical order and occurrence liveness; restart resumes at the next tx without duplication | PARTIAL | `native_rpc_daemon_test.clj` restart — idle daemon, tiny corpus; no restart-under-load, no at-scale replay bound |
| R2 | Old flat logs are accepted only by the one-shot migration command | BACKED | [`../tests/triple_log_migration_test.clj`](../tests/triple_log_migration_test.clj) |

## Wire

| # | Guarantee | Status | Gate |
|---|---|---|---|
| N1 | Closed 13-operation FRAMRPC v1; unknown tags, fields, or trailing bytes are rejected; EDN is refused on the wire | BACKED | [`../tests/fram_rpc_v1_test.clj`](../tests/fram_rpc_v1_test.clj), `native_rpc_daemon_test.clj`, boundary ratchet |
| N2 | Limits: body ≤ 1,048,576 B; frame ≤ 1,048,602 B; string ≤ 1 MiB; term nodes ≤ 65,536; depth ≤ 256 | PARTIAL | decode-side truncation and oversize gated; encode-side enforcement untested |
| N3 | `:rpc/scan` and `:rpc/occurrences` responses share the 1 MiB frame cap and accept no page cursor — an undocumented hard cliff on large corpora | UNBACKED | measurement in flight; resolution is paginate-or-pin |

## Query

| # | Guarantee | Status | Gate |
|---|---|---|---|
| Q1 | Query semantics per [`query-reference.md`](query-reference.md) | BACKED | [`../tests/triple_query_test.clj`](../tests/triple_query_test.clj), aggregate/projection tests |
| Q2 | A page cursor pins its snapshot across intervening commits | PARTIAL | gated for one loop; snapshot retention limit is 4 pinned pages and eviction behavior is unspecified |
| Q3 | Budgets: step budget 10,000,000; timeout `min(60000, requested else 5000)` ms | UNBACKED | numbers live in source only; no gate exercises the limits |

## Capacity and performance envelope — the open rungs

**No current-engine performance number is published today.** The 2026-07-28
in-class receipts (500–551 durable writes/s, etc.) measured the removed flat
engine and must not be quoted for head. The envelope work is:

- re-baseline `bench/in-class` through a FRAMRPC adapter and re-accept the
  golden ratchet against head — this pins boot-to-serving, targeted-read
  latency, durable write throughput (single and batch), and sustained
  write-under-read;
- restart cost is O(full log) at head (no checkpoint); the bound is unmeasured;
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
