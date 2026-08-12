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

Every guarantee below binds to the occurrence-native server, binary FRAMLOG,
and FRAMRPC v1 in this source tree.

## Durability

Durability is stated per **storage regime**, because what "the bytes are down"
means is the storage owner's answer, not the engine's:

- **POSIX storage** — the engine owns the file. `storage_sync` is `fsync` on
  the log descriptor, and the barrier completes inside the commit path before
  any acknowledgement leaves.
- **Host storage table** — an embedder supplies the storage hooks
  ([isolation and deployment](isolation-and-deployment.md#the-wasm-embed-contract)).
  `storage_sync` returning zero means only that the host accepted the bytes.
  Where the host's own commit is asynchronous — a Durable Object's storage is
  the case in hand — durability lands after the guest call unwinds, so **the
  embedder must await its store's commit before acknowledging the write**. An
  embedder that answers the caller first has moved the barrier, not removed it.

D1-D5 below are the POSIX regime. D6 states what the engine still guarantees
when the barrier belongs to a host.

| # | Guarantee | Status | Gate |
|---|---|---|---|
| D1 | An acked commit is durable: its FRAMLOG frame is covered by a successful `force(true)` barrier before the cohort's immutable head is published | BACKED | `database_test.clj` asserts two frames/one barrier and no publication on an injected barrier failure; concurrent gate verifies ack/frame identity |
| D2 | A torn trailing frame never corrupts state: authority boot truncates to `:valid-bytes`; exactly the longest committed prefix survives, at every possible cut offset | BACKED | [`../tests/framlog_torn_sweep_test.clj`](../tests/framlog_torn_sweep_test.clj) — every-byte cut sweep, 3,984 boots, exact-image oracle with negative control; plus `database_test.clj` |
| D3 | A passive recovery open (no writer authority) reports a torn tail and never rewrites the log | BACKED | sweep passive arm asserts byte-identical file at every cut; plus `database_test.clj` |
| D4 | An append-path failure fences the writer (`:durability-ambiguous` / `:recovery-required` / `:database-corrupt` / `:database-state-invalid`); every waiter in a failed cohort is refused and writes stay fenced until restart | BACKED | `database_test.clj` fault injection covers singleton and cohort barriers before append, after force, and corrupt replay |
| D5 | Damaged committed bytes are detected loudly on replay; replay never silently produces a divergent image | BACKED | sweep flip arm — 3,968 single-bit/high-bit flips: every one detected by CRC or repaired-to-prefix, zero divergent images. Residual (named, unswept): multi-byte garbage tails; header-region corruption is gated separately in `database_test.clj` |
| D6 | Under a host storage table the engine appends whole frames in commit order and never rewrites committed bytes. An isolate that dies before its host's commit lands therefore loses a suffix and never a prefix: the durable image is exactly what the last landed commit left, and a fresh instance opens it and serves | PARTIAL | [`../tests/fram_wasm_embed_smoke.sh`](../tests/fram_wasm_embed_smoke.sh) — the FRAMLOG written through the import hooks is byte-identical to the native oracle's over the whole frame matrix. The isolate-death arm is an out-of-tree adapter probe (one transaction whose host commit never landed: the durable image stayed byte-identical to the prior commit, the guest's uncommitted tail died with the instance, and a fresh instance reopened that image and served) and does not gate |

D6 places two obligations on the host, and neither is checkable by the engine.
One commit must land **atomically** and commits must land **in order**: replay
reads from byte zero, so a host that applies half of one commit, or overtakes an
earlier one, damages the middle of the image rather than its tail — which is
detected loudly (D5) but is not repairable by truncation the way a torn tail is
(D2). Serialize commits, one in flight.

## Atomicity

| # | Guarantee | Status | Gate |
|---|---|---|---|
| A1 | One RPC batch remains one transaction frame; a sequencer cohort prepares FIFO on a private root and publishes all covered frames together after one barrier, or publishes none | BACKED | [`../tests/native_rpc_server_test.clj`](../tests/native_rpc_server_test.clj) batch path; `database_test.clj` two-frame cohort success/failure arms |
| A2 | A write, batch, or successful lease acquire, renew, or release encodes its exact predicted success response before append. Any Term depth, node, or frame-size rejection appends nothing and leaves version and state unchanged | BACKED | [`../tests/native_rpc_server_test.clj`](../tests/native_rpc_server_test.clj) pins JVM response rejection before mutation; [`../tests/fram_wasm_embed_smoke.sh`](../tests/fram_wasm_embed_smoke.sh) proves native lp64/wasm32 parity |

## Isolation and concurrency

| # | Guarantee | Status | Gate |
|---|---|---|---|
| I1 | One active writer per `SpaceId`; its singleton FIFO commit sequencer orders every mutation, while readers take only immutable published snapshots. Under POSIX storage the engine enforces this with a `flock` on the log and fails a second server closed; under a host storage table the exclusivity is the embedder's grant and the engine cannot check it | BACKED for the POSIX regime | [`../tests/writer_authority_test.clj`](../tests/writer_authority_test.clj), [`../tests/framrpc_write_conc_test.clj`](../tests/framrpc_write_conc_test.clj); the host-storage clause is an obligation, not a guarantee — see [isolation and deployment](isolation-and-deployment.md#deployment-shapes) |
| I2 | OCC: a stale or future `expected-version` returns `:rpc/conflict` without moving the version | BACKED | `native_rpc_server_test.clj`; race shape in `database_test.clj` (24 racers → exactly 1 ok) |
| I3 | K concurrent socket writers: every acked proposition is durable exactly once, per-writer issue order is preserved, tx-sequence strictly rises, each ack's version equals its frame's tx-seq, and the published root contains that transaction before its ack | BACKED | [`../tests/framrpc_write_conc_test.clj`](../tests/framrpc_write_conc_test.clj) — 8 writers × 25 + 80 OCC racers, durable-frame/barrier/publication verification |
| I4 | Reads do not convoy writes: validate is a non-convoying read; during a 2 s slow query or validate, a lone write acks ≤ 250 ms and ten concurrent writes ack ≤ 1 s (mutations share cohort barriers — see capacity notes) | BACKED | [`../tests/framrpc_latency_convoy_test.clj`](../tests/framrpc_latency_convoy_test.clj) — injected-delay convoy + disconnect-cancels-work |

## Ordering and recovery

| # | Guarantee | Status | Gate |
|---|---|---|---|
| O1 | `tx-sequence` + `op-ordinal` define exact logical order; mutation receipts return occurrence coordinates | BACKED | [`../tests/triple_kernel_test.clj`](../tests/triple_kernel_test.clj), `database_test.clj`, [`../tests/model_generative_test.clj`](../tests/model_generative_test.clj) — seeded op sequences compared against a pure model after every op |
| R1 | Replay restores logical order and occurrence liveness; restart resumes at the next tx without duplication | PARTIAL | `native_rpc_server_test.clj` restart — idle server, tiny corpus; `model_generative_test.clj` cold-restart arm compares the model and a byte-exact store dump per generated sequence; no restart-under-load. At-scale boot cost is measured but ungated — see the capacity section |
| R2 | Old flat logs are accepted only by the one-shot migration command | BACKED | [`../tests/triple_log_migration_test.clj`](../tests/triple_log_migration_test.clj) |
| R3 | A snapshot image is an accelerator that cannot change an answer: with a valid image the boot installs it and replays only the FRAMLOG tail past its watermark; an image that fails to install, or whose tail does not continue, degrades to the full fold and reports it instead of failing the boot. `rpc/checkpoint` appends nothing to the log, so history stays authoritative | PARTIAL | [`../tests/fram_snapshot_boot_test.sh`](../tests/fram_snapshot_boot_test.sh) — fold, image, and damaged-image boots answer byte-identically, and the damaged arm reports `degraded to full fold`; [`../tests/fram_wasm_embed_smoke.sh`](../tests/fram_wasm_embed_smoke.sh) runs the same route through host storage. Neither runs on the hosted runner: the snapshot gate builds the full native server and is dispositioned `exclude-runner` in the CI manifest, so it gates in the flake devShell and before a release, not on every push |
| R4 | A completed `fram-backup` is the exact authoritative FRAMLOG prefix through one native checkpoint watermark, bound to its SpaceId, served version, and native artifact READY receipt by canonical SHA-256 metadata. Restore starts from fresh storage, refuses a different SpaceId without mutation, reaches the recorded version before readiness, and accepts durable writes afterward | PARTIAL | [`../tests/fram_backup_restore_test.sh`](../tests/fram_backup_restore_test.sh) — live write after cutoff is excluded; verify, wrong-SpaceId boot, exact restored version, post-restore write, and restart are gated. The full native build is `exclude-runner`, and the corpus is small; remote-copy transport and large-store recovery time remain deployment obligations |

## Wire

| # | Guarantee | Status | Gate |
|---|---|---|---|
| N1 | Closed 13-operation FRAMRPC v1 data surface — plus one fixed `rpc/checkpoint` native operator capability that is absent from `framClient` and cannot select another operation; unknown tags, fields, or trailing bytes are rejected; EDN is refused on the wire | BACKED | [`../tests/fram_rpc_v1_test.clj`](../tests/fram_rpc_v1_test.clj), `native_rpc_server_test.clj`, [`../tests/native_rpc_boundary_ratchet_test.clj`](../tests/native_rpc_boundary_ratchet_test.clj), [`../tests/fram_backup_restore_test.sh`](../tests/fram_backup_restore_test.sh) |
| N2 | Limits: body ≤ 1,048,576 B; frame ≤ 1,048,602 B; string ≤ 1 MiB; term nodes ≤ 65,536; depth ≤ 256 | PARTIAL | [`../tests/fram_rpc_v1_test.clj`](../tests/fram_rpc_v1_test.clj) gates the JVM encode/decode boundaries; native lp64/wasm32 mutation gates cover response body size and depth, while direct native encode-side string and node at-limit/one-over cases remain ungated |
| N3 | `:rpc/scan`, `:rpc/query`, and `:rpc/occurrences` accept the same page cursor. List bounds derive from the TermCodecV1 depth budget of 256: a request list is capped at **250** values (less a six-deep request envelope), an unpaged reply at **248** rows (less an eight-deep response envelope), and a mutation batch at **247** actions (less a nine-deep receipt envelope). Depth violations refuse typed `:term-depth-exceeded`. Before a write, batch, or successful lease acquire, renew, or release commits, the runtime constructs and encodes its exact predicted response using the real SpaceId, operation, served version, occurrence coordinates or lease epoch, and the lease operation's frozen expiry. A response that exceeds the wire byte budget refuses typed `:rpc-frame-too-large` without advancing version or state. Paging is the escape for reads and answers the same relation. The cliff binds every response, so the contract's 4096-row page ceiling is unusable; keep pages well under 248 rows | BACKED | [`../tests/native_rpc_server_test.clj`](../tests/native_rpc_server_test.clj) pins paged reassembly, cursor pinning, bounded unpaged fold, depth rejection, and byte-size rejection with unchanged version and state; [`../tests/fram_wasm_embed_smoke.sh`](../tests/fram_wasm_embed_smoke.sh) proves the same mutation atomicity across native lp64 and wasm32 |

## Query

| # | Guarantee | Status | Gate |
|---|---|---|---|
| Q1 | Query semantics per [`query-reference.md`](query-reference.md) | BACKED | [`../tests/triple_query_test.clj`](../tests/triple_query_test.clj), aggregate/projection tests |
| Q2 | A page cursor pins its resolved upper sequence and lower-exclusive occurrence bound across intervening commits; eviction may recompute from the pinned immutable root without changing rows | PARTIAL | gated for query, scan, and occurrence loops; retained-root and ordered-result envelopes each cover four versions |
| Q3 | Five positive text relations read live String values through one shared index: `text-match` (unordered token conjunction), `text-phrase` (token order preserved), `text-substring` (case-folded, punctuation kept), `text-stem` (English stemming), and 4-arity `text-search` (ranked, exact before stem before substring). All are positive-only, need a bound String needle, and reject empty or punctuation-only needles. One index build is bounded at 64 MiB and fails typed `:query-text-index-limit`; the analyzers behind phrase, substring, stem, and search realize on first use, so a `text-match` query pays nothing for them | BACKED | [`../tests/text_match_test.clj`](../tests/text_match_test.clj), [`../tests/text_index_cache_test.clj`](../tests/text_index_cache_test.clj), differential and performance gates |
| Q3a | The text index is exact-snapshot keyed, single-flight, and retained across requests for four versions — **JVM route only**. The native route builds the source per query, plan-gated so an unqueried text relation costs nothing, and leans on the ordered-result cache (Q5) for repeats | PARTIAL | `text_index_cache_test.clj` covers the JVM cache; native rebuild cost is unmeasured |
| Q4 | Budgets: step budget 10,000,000; timeout `min(60000, requested else 5000)` ms | UNBACKED | numbers live in source only; no gate exercises the limits |
| Q5 | Complete deterministically ordered results are reused by snapshot generation, SpaceId, resolved `{lower-exclusive, upper-inclusive}` view, operation, and canonical request digest; concurrent misses share one computation | PARTIAL | `native_rpc_server_test.clj` exercises one evaluator run for two concurrent misses, selector-equivalent historical reuse, lower-bound separation, bounds, counters, and restart reset |
| Q6 | `current`, inclusive `as-of U`, and `since L upper` compose transaction-exact state plus occurrence history; `(L,U]` never materializes the full occurrence relation | BACKED | `datalog_diff_test.clj` 7/7 including temporal and text-match source arms; `model_generative_test.clj` compares as-of state and since events after every generated operation; `native_rpc_server_test.clj` covers composition and cursors |
| Q7 | A historical miss uses the newest valid prefix-bound checkpoint and replays its tail; corrupt derived state falls back to canonical history. Sealed ranges preserve rows, while unavailable and explicitly expired ranges remain distinct | PARTIAL | `native_rpc_server_test.clj` covers corrupt FRI fallback, sealed-range parity, manifest reload, and both typed errors; active-log compaction and production retention policy are not yet gated |

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

Reference workload NW-1 is a coordination substrate observed near 350k live Triples: mostly single-proposition writes, occasional atomic batches, targeted reads, paged projection drains, at most eight concurrent bulk clients, one bounded listener poller, listener leases, and interactive/sweep traffic. Whole-corpus client filtering is excluded.

| Dimension | Contract |
|---|---|
| Request/response | body ≤ 1 MiB; frame ≤ 1 MiB; bulk reads paginate; a request list is bounded at 250 values and an unpaged reply at 248 rows; mutation replies are exact-preflighted before commit under N3 |
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
Datalog projection from scratch on every page. The server now evaluates one
miss per immutable snapshot and request digest, then slices the cached ordered
vector for repeats and pages. A new snapshot still pays the unchanged scan
evaluator cost; indexed evaluation remains open. The gate
(`bench/in-class/golden.edn`, receipt
[`bench/in-class/results/2026-08-02-framrpc-main.md`](../bench/in-class/results/2026-08-02-framrpc-main.md)) pins today's floor so
the fix is measurable. The 2026-07-28 flat-engine receipts (500–551
writes/s) must not be quoted for head. Remaining envelope work:

- the 30k-scale arm was deliberately skipped (extrapolated hours per run —
  evidence in the receipt); it remains open for indexed evaluation;
- restart cost is bounded by store materialization, not by log length. `rpc/checkpoint` writes a snapshot image and a restart installs it and replays only the tail past its watermark, but on assert-heavy stores that costs the same as folding the whole log, because rebuilding the in-memory store dominates both. Re-measured after the v0.5.0 store materialization fix (fold / snapshot+tail, one store per size): 300 triples — native 19.5/21.0 ms, wasm 8.7/11.2 ms; 1,000 triples — native 71.6/79.9 ms, wasm 28.6/43.6 ms; 3,000 triples — native 230.0/242.1 ms, wasm 107.7/121.5 ms; 5,000 triples — native 380.4/404.3 ms, wasm 176.7/207.5 ms. Boot time and boot memory now grow about linearly in live triples (native time n^1.05-1.06, wasm time n^1.03-1.09, wasm pages n^1.00), and the 5,000-triple store opens in 475 wasm pages and 31.5 MB of native RSS. Before that fix the same measurement grew as roughly n^2 in both, and **that superlinearity was the named scale limit of the release**; removing it is what the fix did. The budget and limits matrix in the release notes was certified on the pre-fix engine and is therefore conservative — full re-certification is queued. The 2026-08-02 rows above are the JVM route at a different revision and do not compare;
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
- connection admission is bounded. The managed/Graal server defaults to 32
  running connections and 128 pending accepted connections; operators may set
  `FRAM_CONNECTION_WORKERS` and `FRAM_CONNECTION_QUEUE` inside their validated
  ceilings. A connection beyond that envelope is closed before request decode,
  so saturation does not promise a FRAMRPC response. Shutdown stops admission,
  cancels active reads, gives finite requests a bounded drain, and then closes
  remaining transports. Existing concurrency gates cover admitted traffic;
  saturation rejection itself has no dedicated gate;
- W5 landed runtime telemetry and heap bounds. The observability floor still
  gates every envelope entry.

## Explicit non-guarantees

- No engine access control: isolate by process, network, `SpaceId`, and log
  ([`isolation-and-deployment.md`](isolation-and-deployment.md)).
- Single-machine, single-writer receipts — not distributed consensus.
- Equal propositions are not deduplicated: assertion always creates a new occurrence.
