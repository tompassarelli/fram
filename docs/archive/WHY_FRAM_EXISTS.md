> **HISTORICAL — design provenance only.**

# Why Fram Exists

**Status:** Historical positioning rationale, retired 2026-08-04. The current model is
[`src/fram/types.bgl`](../../src/fram/types.bgl) and
[`tests/triple_kernel_test.clj`](../../tests/triple_kernel_test.clj). Vocabulary
and the canonical normalized example are defined once in
[`ontology.md`](../ontology.md); this document defers to it.

## Verdict

Fram exists to make one small language uniform all the way down:

```text
Atom   := String | Int | Float | Bool | Keyword | Instant
Term   := Atom | Triple
Triple := (Term, Term, Term)
```

A Triple can occupy `t1`, `t2`, or `t3` of another Triple. The positions
have no kernel-assigned roles. This gives Fram recursive propositions,
coordinates, history, and metadata without a privileged attribute position or a
second statement-object mechanism.

The decisive feature is therefore **recursive Triple-as-Term**, not “every row
has an id.” Proposition content and assertion occurrence are deliberately
separate.

## Proposition versus occurrence

This Triple is proposition content:

```text
("Alice", :email, "alice@example.com")
```

An assertion of it is located by ordinary Triples:

```text
tx := ("people", :kernel/tx-sequence, 1842)
op := (tx, :kernel/op-ordinal, 7)

(op, :kernel/asserts,
     ("Alice", :email, "alice@example.com"))
```

The same proposition can be asserted again at another coordinate. A retraction
is `(op, :kernel/retracts, proposition)`; when it withdraws an earlier
assertion, `(retraction-op, :kernel/withdraws, assertion-op)` records the exact
target. No semantic CID, fact-id, or stored reified row is needed.

Logical order comes from transaction sequence plus operation ordinal. Wall
clock is ordinary metadata such as `(tx, :kernel/recorded-at, Instant(...))`.
Valid time, observation time, calendars, uncertainty, and time zones belong in
additional Triples chosen by the domain. Correcting any of them does not change
the proposition or occurrence coordinate.

## Why an EAV/datom substrate is not the same model

An EAV datom gives its positions different jobs: entity, registered attribute,
and value. The attribute position commonly owns schema and cardinality. Fram's
three positions are peers and each accepts the same recursive `Term` sum.

One can emulate Fram on EAV by creating an entity for every proposition and
storing its three positions as attributes. That is logically possible, but the
recursive Triple is then an application convention assembled from several
foreign atoms. Fram owns the smaller matching primitive directly.

Datomic's transaction entity and ordered transaction coordinate solve useful
transaction-level history problems. Fram uses the same important separation
between logical order and wall clock, but it represents assertion occurrences
at operation granularity and exposes them through the same Triple language as
domain data.

## Why RDF and RDF-star are close but not identical

RDF triples have named subject/predicate/object roles. Classic RDF needs a
statement description or named graph when metadata must target one assertion.
RDF-star makes an embedded triple available in selected statement positions and
is much closer to Fram's goal.

Fram makes recursion the kernel rule, not an annotation feature: a Triple is a
Term in **every** slot, and transaction/occurrence coordinates are Triples too.
There is no separate quoted-triple category and no distinct reification
vocabulary required by storage.

## What does not justify a custom engine

These are useful properties, but none is the reason Fram exists:

- Datalog, recursion, stratified negation, aggregation, and indexes are well
  understood elsewhere.
- A sole writer with concurrent readers and optimistic version checks is a
  conventional, correct concurrency shape.
- Schema and vocabulary can be represented as data in many systems.
- An opaque UUID could identify an occurrence; Fram's recursive coordinate is a
  semantic uniformity choice, not a uniqueness breakthrough.
- Performance alone is not architectural necessity. An established database
  may be faster, more mature, and operationally cheaper.

If the required model were ordinary records, EAV, or non-recursive triples,
using an established database would be the right default.

## Physical implementation is not semantic identity

The engine interns Atoms and recursive Triples. `TermStore` uses integer term
handles, `AtomRow`, `TripleRow`, transaction rows, operation rows, and
slot-addressed buckets for compact storage and indexing. Those values are
private implementation coordinates. They may change across dump/load or a new
engine implementation without changing a Term or occurrence coordinate.

The semantic boundary exposes Terms, Triples, transaction coordinates,
occurrence coordinates, and ordinary assertion/retraction/withdrawal Triples.
The FRAMLOG and FRAMRPC codecs preserve that boundary exactly.

## The Turtle thesis

“Turtles” means *turtles all the way down*: prefer uniform recursive Triples
where the model permits. It is an architectural prior, not a type, row, id, or
log format. Code therefore says `Term`, `Triple`, `TripleRow`, and
`t1`/`t2`/`t3`. The binding terminology decision is recorded in
[`naming.md`](../naming.md).

## Decision

Own the small recursive-Term kernel and keep everything else replaceable:

- binary FRAMRPC for the live engine boundary;
- JSON at authenticated public edges;
- stratified Datalog as a projection over live Triples and occurrences;
- private storage handles hidden below the semantic API;
- domain meaning, including time and provenance, expressed with more Triples.

Revisit this decision if an available engine exposes the same recursive,
slot-neutral Term model and exact occurrence history as its native boundary at a
lower total cost. Do not defend Fram using stale claims about per-row IDs,
special reification, query novelty, or performance.
