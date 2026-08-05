# Glossary

This document is the single source for Fram's current semantic, storage, query, wire, and deployment vocabulary.

## Semantic kernel

**Atom** — A non-recursive leaf: String, Int, Float, Bool, Keyword, or Instant.

**Term** — Any value accepted in a Triple: an Atom or another Triple.

**Triple** — Exactly three neutral Terms, recursively; the kernel assigns no subject, predicate, object, entity, or attribute role to a position.

**t1 / t2 / t3** — The first, second, and third positional addresses of `Triple := (Term, Term, Term)`; these public names never appear on the binary wire.

**proposition** — A Triple used as statement content, independent of whether, when, or how often it is asserted.

**transaction coordinate** — `(space, :kernel/tx-sequence, sequence)`, the durable logical address of one accepted transaction.

**occurrence** — One assertion or retraction at `(transaction-coordinate, :kernel/op-ordinal, ordinal)`; equal propositions at different coordinates remain distinct.

**live set** — The propositions whose assertion occurrences remain in force after exact retractions; full occurrence history is retained.

**fact** — A proposition admitted by a particular view's rules, not a stored kernel type; on the historical v0.3 line it meant one stored subject–predicate–object record.

**Turtle** — The “turtles all the way down” architecture prior: prefer the same recursive Triple language for data, coordinates, history, and metadata; never a primitive or storage type.

**profile** — An optional, stored contract that validates a space's propositions above the unchanged kernel.

**EAV reading** — The relational profile's role names for the positions: entity (t1), attribute (t2), value (t3). Profile vocabulary only, never kernel roles.

## Storage and query

**FRAMLOG** — The authoritative binary append-only history for one SpaceId.

**fold / replay** — Applying ordered FRAMLOG transactions to reconstruct logical version, occurrence liveness, live propositions, and indexes.

**snapshot** — An immutable `{version, root}` query view published by the writer.

**checkpoint** — A derived, prefix-bound image used to accelerate historical reconstruction; invalid checkpoints fall back to canonical replay.

**epoch** — An inclusive transaction-sequence range sealed as a canonical FRAMLOG segment and named by a fingerprinted range manifest.

**rotation** — A disposable index of live occurrences by individual Triple positions and position pairs; `SPO`/`POS`/`OSP` are private physical names.

**projection** — A rebuildable view of Terms or occurrences, including query relations, profiles, and indexes; never authoritative history.

**SpaceId** — The immutable identity binding a server, its FRAMLOG, and every accepted request into one database trust domain.

## Wire and deployment

**FRAMRPC** — Fram's private binary protocol for typed recursive Terms and a closed thirteen-operation data surface.

**writer** — The sole active server generation authorized to append to a SpaceId's log. Native production has no standby-serving mode.

**selector** — The operator-owned front end that holds and drains public connections, checks deployment generations, and switches all routes together.

**blue/green generation** — A v0.3 compatibility deployment copy used by the frozen writer-handoff protocol.

**promote** — A v0.3 writer-handoff operation that grants a prepared standby authority after it proves agreement with the former writer's final durable marker.

**wire skew** — A client/server protocol-version or format mismatch that can make healthy endpoints reject, drop, or hang requests.

**v0.3 line** — The deployed compatibility release using the older flat store, EDN line protocol, and blue/green cutover contract until cluster migration.
