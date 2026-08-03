# Architecture

**Status:** Current source-head architecture. The deployed v0.3 coordinator
still has explicitly version-scoped operational surfaces; those are compatibility
contracts, not alternative kernel semantics.

## One semantic language

Fram's public value model is deliberately small:

```text
Atom   := String | Int | Float | Bool | Keyword | Instant
Term   := Atom | Triple
Triple := (Term, Term, Term)
```

The three positions are named `slot0`, `slot1`, and `slot2`. They are neutral:
the kernel assigns none of them a subject, predicate, object, entity, or
attribute role. Ontologies may establish those roles by convention. Because a
Triple is itself a Term, any slot can contain another Triple.

The word “Turtle” is reserved for the *turtles all the way down* design thesis:
prefer this one recursive language wherever it fits. It is not a code type or
storage format.

## Proposition and history

A proposition is a Triple. An assertion is the proposition at a logical
occurrence coordinate:

```text
tx := (space, :kernel/tx-sequence, sequence)
op := (tx, :kernel/op-ordinal, ordinal)

(op, :kernel/asserts, proposition)
```

Retractions use `:kernel/retracts`. When a retraction withdraws one exact earlier
assertion, the history also contains:

```text
(retraction-op, :kernel/withdraws, assertion-op)
```

Equal propositions may therefore have distinct occurrences. Transaction
sequence and operation ordinal give exact replay order. Wall-clock time is an
ordinary relation such as `(tx, :kernel/recorded-at, instant)`; valid and
observation time remain domain relations. None changes proposition identity.

## Storage and durability

`TermStore` interns Atoms and recursive Triples. Its `AtomRow`, `TripleRow`,
transaction rows, operation rows, and integer handles are private physical
structures. A handle is allowed to change across dump/load or another engine
implementation; a Term is not.

The durable history is binary FRAMLOG v1. Its header fixes the `SpaceId`, and
transaction frames carry a logical sequence plus ordered assert/retract
operations encoded with the recursive Term codec. Replay rebuilds liveness and
indexes from that history. The one-shot migration converts the legacy flat log
to FRAMLOG; there is no permanent dual semantic path.

Historical query roots use prefix-bound FRI2 checkpoints plus transaction-tail
replay. A sealed epoch is a canonical FRAMLOG prefix named by a fingerprinted
binary range manifest; derived roots and results are bounded caches, while
completed canonical ranges are retained indefinitely unless an explicit
retention decision marks one expired.

The active writer owns one `SpaceId` and one history log. A standby can load and
serve the same durable prefix, but only the active process may append. The
active daemon orders mutations through one FIFO sequencer. It retains one
FRAMLOG frame per logical transaction, appends a bounded cohort contiguously,
forces that cohort once, then atomically publishes its final immutable store
root before acknowledging any member. Readers use only published roots and do
not acquire the sequencer lock.

## Query projection

The Datalog engine projects two exact base relations:

```text
triple(slot0, slot1, slot2)
occurrence(coordinate, action, proposition)
```

`triple` contains live propositions. `occurrence` contains explicit operation
history and is included only when that projection is requested. Both relations
have arity three and every cell is a Term. There is no compatibility relation
that exposes a semantic row id.

Rules, recursion, stratified negation, comparisons, arithmetic, aggregates, and
stable paging operate above this projection. See
[`query-reference.md`](query-reference.md).

## Process and wire boundaries

The source-head runtime has four boundaries:

1. **FRAMLOG** is durable local history.
2. **FRAMRPC v1** is the private binary coordinator protocol. It is a closed,
   thirteen-operation protocol with the same tagged recursive Term codec as the
   log.
3. **The CLI** accepts local EDN-shaped human syntax, lowers it immediately to
   typed Terms or typed query records, and speaks FRAMRPC. EDN is not the live
   engine wire.
4. **Public edges** use closed JSON. `bin/fram-mcp` exposes exactly `tell`,
   `retract`, `show`, `ask`, and `validate`; the Cloudflare shim maps tagged JSON
   Terms to FRAMRPC.

Graph-authoring controls and deployment controls are separate sealed services.
They are not extra public data verbs. The deployed v0.3 blue/green controller
has its own versioned operational protocol; see
[`coordinator-cutover.md`](coordinator-cutover.md).

## Security boundary

The engine has no tenant authentication or authorization. The coordinator binds
to loopback by default; a remote deployment keeps FRAMRPC private and terminates
authentication and TLS at a gateway or sidecar. `SpaceId`, process, log, and
network isolation define a trust domain. See
[`isolation-and-deployment.md`](isolation-and-deployment.md).

## Executable contracts

- [`../src/fram/types.bclj`](../src/fram/types.bclj) — Atom, Term, Triple, and
  occurrence constructors.
- [`../src/fram/store.bclj`](../src/fram/store.bclj) — interning, transactions,
  liveness, and history projection.
- [`../src/coord_daemon_wire.bclj`](../src/coord_daemon_wire.bclj) — FRAMRPC
  record and Term codec contract.
- [`../tests/triple_kernel_test.clj`](../tests/triple_kernel_test.clj) — recursive
  terms, coordinates, and liveness.
- [`../tests/triple_log_migration_test.clj`](../tests/triple_log_migration_test.clj)
  — one-shot migration and FRAMLOG bytes.
- [`../tests/native_rpc_boundary_ratchet_test.clj`](../tests/native_rpc_boundary_ratchet_test.clj)
  — closed native operation boundary.

Older Worlds, claims, Codegraph, and pull documents remain design or experiment
records where marked. They do not add primitives or operations to this
architecture.
