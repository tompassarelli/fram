# Concurrency and writes

**Status:** Current source-head transaction contract.

Fram is a single-writer engine with concurrent clients. One active coordinator
holds writer authority for a `SpaceId` and serializes accepted transactions. A
standby may read the durable prefix but cannot append while it lacks authority.
This is a single-machine concurrency model, not distributed consensus.

## Optimistic concurrency

A native mutation may carry `expected-version`. Under the coordinator lock,
the value must equal the current logical version before any operation is
prepared. A stale or future value returns `:rpc/conflict` without moving the
version.

Two requests with the same expected version can race, but once one commits and
advances the head, the other observes a mismatch. Unguarded requests still
serialize; they simply do not ask Fram to reject intervening commits.

Lease fencing is an additional resource-level guard. A write carrying a fence
must match the current unexpired holder and epoch. It does not replace the
single writer or transaction version.

## Transaction and occurrence order

One accepted transaction receives:

```text
(space, :kernel/tx-sequence, sequence)
```

Its actions execute in request order. Every committed action receives:

```text
(transaction-coordinate, :kernel/op-ordinal, ordinal)
```

The resulting assertion or retraction occurrence is returned in the mutation
receipt. Batch actions either prepare under the same locked snapshot and commit
as one transaction, or fail before an append.

## Liveness is occurrence-based

Asserting a proposition always creates a new occurrence, even if equal
proposition content is already live. Equal propositions are structural equals,
but their assertion occurrences remain distinct.

Retracting an exact proposition withdraws its latest live equal occurrence.
Liveness here is store liveness: the newest equal occurrence the store still
holds live, including one that supersession has already suppressed from the
effective projection.
The semantic history records both the retraction and its exact withdrawal
target. Retracting a proposition with no live match is an explicit no-op: its
receipt says unchanged and the logical version does not move.

Cardinality, uniqueness, referential integrity, and domain replacement policy
are not implicit in any of the three slots. A domain can express and validate
those rules above the kernel; the storage transaction does not silently replace
a value merely because it occupies `slot1`.

## Durability

The writer appends one binary FRAMLOG transaction frame before publishing the
new in-memory head. Replay restores logical order and occurrence liveness. Boot
rejects invalid structure and repairs only a recognized torn tail when running
with active writer authority; a standby never rewrites the log.

The old flat log is accepted only by the one-shot migration command. Serving
does not keep a dual-write or dual-read compatibility path.

## Running locally

```sh
export FRAM_SPACE_ID=fram-demo
export FRAM_LOG=/tmp/fram-demo.framlog
bin/fram-up
bin/fram version
bin/fram tell :email :grouped-under :contact
bin/fram tell Alice :email alice@example.com
bin/fram occurrences
```

The executable contracts are
[`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj),
[`../tests/triple_kernel_test.clj`](../tests/triple_kernel_test.clj), and
[`../tests/triple_log_migration_test.clj`](../tests/triple_log_migration_test.clj).
The deployed v0.3 generation handoff remains governed by the separately
versioned [`coordinator-cutover.md`](coordinator-cutover.md) until cluster
migration replaces that runtime.
