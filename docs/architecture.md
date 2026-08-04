# Architecture

This document maps Fram's [shared vocabulary](glossary.md) onto durable storage, immutable query roots, and process boundaries at source head.

## Kernel and history

The kernel accepts the recursive grammar and neutral `t1`/`t2`/`t3` positions defined in the glossary. Domain roles come from ontology patterns, never position. A proposition enters history at ordinary Triple coordinates:

```text
tx := (space, :kernel/tx-sequence, sequence)
op := (tx, :kernel/op-ordinal, ordinal)
(op, :kernel/asserts, proposition)
```

Retractions use `:kernel/retracts`; `(retraction-op, :kernel/withdraws, assertion-op)` names the exact withdrawn assertion. Transaction sequence and operation ordinal define replay order. Recorded, valid, and observation time are ordinary metadata and never proposition identity. See [ontology](ontology.md) for modeling rules.

## Storage, writer, and readers

`TermStore` interns Atoms and recursive Triples. `AtomRow`, `TripleRow`, operation rows, integer handles, and `SPO`/`POS`/`OSP` rotations are private mechanics, not semantic identity.

Binary FRAMLOG v1 is authoritative. Its header fixes the SpaceId; transaction frames carry a logical sequence and ordered assert/retract operations using the recursive Term codec. Replay rebuilds liveness and indexes. A one-shot migration converts the legacy flat log; serving has no dual semantic path.

One active coordinator owns writer authority. Its FIFO sequencer prepares bounded transaction cohorts on private roots, appends one frame per transaction, forces the cohort once, and atomically publishes the final immutable root before acknowledgements. Standbys never append; readers use only published roots and never acquire the sequencer lock. Exact guarantees and the workload envelope live in [guarantees](guarantees.md).

Historical roots use validated prefix-bound FRI2 checkpoints plus tail replay. Canonical sealed epochs are named by fingerprinted range manifests; derived roots and results are bounded caches. Invalid derived state falls back to canonical history.

## Query projection

The evaluator exposes two materialized relations and one positive virtual relation:

```text
triple(t1, t2, t3)
occurrence(coordinate, action, proposition)
text-match(entity, attribute, needle)
```

Every cell is a Term. `triple` is live state, `occurrence` is explicit history, and `text-match` uses a lazy snapshot-scoped index. Rules, recursion, stratified negation, arithmetic, aggregates, temporal selectors, and paging are specified in the [query reference](query-reference.md).

## Boundaries

1. **FRAMLOG** is durable local history.
2. **FRAMRPC v1** is the private binary coordinator protocol: thirteen closed operations using the same recursive Term codec.
3. **CLI EDN** is local human syntax lowered to typed records before FRAMRPC; it is not the wire.
4. **Public JSON edges** are closed adapters. `bin/fram-mcp` exposes only the five verbs in the [tool catalog](tool-catalog.md).

Graph-authoring and deployment controls are separate sealed services. The pinned v0.3 blue/green control protocol remains in [coordinator cutover](coordinator-cutover.md); it does not enlarge FRAMRPC.

The engine has no tenant authorization. Loopback/private FRAMRPC, process, SpaceId, and log form a trust domain; authenticated TLS belongs at a gateway or sidecar. Bind, wire, deployment, and probe details are consolidated in [isolation and deployment](isolation-and-deployment.md).

## Executable contracts

- [`../src/fram/types.bclj`](../src/fram/types.bclj) and [`../src/fram/store.bclj`](../src/fram/store.bclj): recursive values, transactions, liveness, and projections.
- [`../src/coord_daemon_wire.bclj`](../src/coord_daemon_wire.bclj): FRAMRPC records and codec.
- [`../tests/triple_kernel_test.clj`](../tests/triple_kernel_test.clj), [`../tests/native_rpc_boundary_ratchet_test.clj`](../tests/native_rpc_boundary_ratchet_test.clj), and [`../tests/triple_log_migration_test.clj`](../tests/triple_log_migration_test.clj): kernel, boundary, and migration gates.

Historical Worlds, claims, Codegraph, pull, rationale, and positioning documents in [`archive/`](archive/README.md) add no current primitives or operations.
