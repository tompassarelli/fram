# Coming from Datomic, DataScript, or Datahike

This document is for people who already think in datoms. It maps that model
onto Fram's, states every difference that will bite, and lists what is honestly
not here yet.

**Scope.** Everything below describes the native engine — the Beagle sources
behind `bin/fram` and `bin/fram-server` — and the closed FRAMRPC v1 surface it
serves. The retained JVM route genuinely differs in two places (text-index
retention and transaction metadata); both are called out inline rather than
averaged away. Vocabulary is the [glossary](glossary.md); guarantee statuses
cited as `D1`, `Q7`, `P2` are rows of [guarantees](guarantees.md).

## 1. The mental-model bridge

A datom is `(e, a, v, tx, added?)`: content, the act, and time fused into one
row. Fram splits that fusion in two.

- A **proposition** is the content: one `Triple` of three `Term`s, timeless and
  structurally identified. Assert the same content twice and it is still one
  interned term.
- An **occurrence** is the act: an assertion or retraction at a coordinate.
  The coordinate is itself made of Triples, so you can query it and make
  statements about it.

```text
tx          := ("my-space", :kernel/tx-sequence, 7)
occurrence  := (tx, :kernel/op-ordinal, 0)
proposition := ("Alice", :email, "alice@example.com")

(occurrence, :kernel/asserts, proposition)
(tx, :kernel/recorded-at, Instant(...))
```

Three consequences worth pausing on:

**There is no transaction entity.** `tx` is a Triple built from the space id and
a sequence number, not a minted entity you attach attributes to. Provenance
about an act attaches to the act's coordinate directly, because the coordinate
is an ordinary Term.

**There are no entity ids.** An "entity" is whatever Term you put in `t1` — a
String, a Keyword, a minted coordinate, or another Triple. Nothing mints an id
for you and nothing resolves one. Positions are coordinates named `t1`, `t2`,
`t3`; the entity/attribute/value reading is a **profile** you opt into, not
physics ([ontology](ontology.md#profiles-and-anchoring)).

**Content is a counted multiset, not a set.** Datomic's set semantics absorb a
redundant re-assertion. Fram records each act: two assertions of equal content
are two corroborating occurrences, and a retraction withdraws the newest live
equal occurrence and records exactly which one it killed
(`(retraction-op, :kernel/withdraws, assertion-op)`).

*Sidebar — the RDF-star prior.* RDF-star lets a quoted triple stand in the
object position so you can say things about statements. Fram generalizes that
to all three positions and any depth: a Triple is a Term, so
`(("Alice", :email, "a@example.com"), :verified-by, "checker-1")` is ordinary
data, not a reification pattern. This is exactly why time cannot live inside
the Triple: a quoted proposition has to be timeless, or it could not say which
version of the statement it quotes ([naming ledger](naming.md)).

## 2. The exact-difference table

| Datomic concept | In Fram | Status |
|---|---|---|
| Schema as data | The predicate registry is ordinary propositions in the same store (`fram.schema`) | **Present** |
| `:db/ident`, attribute rename | `name!` / `resolve-name`; a rename keeps the original Term as identity, demotes the old spelling to an alias, and preflights collisions before mutating anything | **Present, stronger** |
| `:db/cardinality` | Declared per predicate. `single` compiles **client-side** to "retract every live `(e, a, *)`, newest first, then assert" | **Declared, compiled by the caller — never enforced at the wire** |
| `:db/valueType` | `value_kind` is `literal` or `ref`. Client-side projections (pull, export, classification) read it; no engine code checks it | **Declared, unchecked** |
| `:db/unique` | The kernel and wire stay schema-neutral. The official Bun [`@tompassarelli/framrpc/schema`](../clients/bun/README.md#schema-aware-application-writes) entry point resolves an identity at one pinned snapshot, rejects duplicate owners, and protects its create/update batch with `expected-version` | **Application constraint, not a stored kernel invariant** |
| Upsert by identity | `@tompassarelli/framrpc/schema` provides `createUnique`, `upsertUnique`, `updateUnique`, multi-subject `updateUniqueMany`, and mixed `transactUnique`. Each attempt combines snapshot-pinned reads with one exactly preflighted OCC batch; planned create identities can satisfy same-batch reference guards, and a conflict retries from a fresh snapshot | **Present in the official Bun application layer; no dedicated wire operation** |
| `:db/isComponent`, cascade retract | Nothing | **Absent** |
| `retractEntity`, retract by pattern | Only exact-proposition retraction exists | **Absent** |
| Retraction semantics | Withdraws the newest live equal occurrence, records its exact target, and a retraction with no live match is an explicit unchanged receipt that does not advance the version | **Different** |
| Transaction metadata | The JVM route writes `:kernel/recorded-at` and `:kernel/asserted-by` about the tx coordinate. A FRAMRPC action carries a proposition and a subject policy — nothing else | **JVM route only** |
| `d/history`, `d/as-of`, `d/since` | The `occurrence` relation plus `:query/current`, `:query/as-of U`, `:query/since L upper`. A `(L,U]` window never materializes the full occurrence relation, and page cursors pin their snapshot | **Present, richer** (`Q6`) |
| `d/with` | Nothing on the wire. In-process staged builder reads are the read-side analogue | **Absent from FRAMRPC** |
| Tempids | `txn/mint!` hands out `(tx-coordinate, :mint-ordinal, n)`. Builder-local while you build, durable once the transaction commits | **Different** |
| AVET, range scans | Rotations index single positions and position pairs by equality only. Comparisons run as post-filters over bound rows | **Absent** |
| Query language | Structured, typed Datalog: rules or ordered strata, semi-naive fixpoint, stratified negation, aggregates. Not `:find`/`:where` text, and the evaluator never parses a query string ([query reference](query-reference.md)) | **Different** |
| Pull | An app-layer projection (`fram:src/pull.bclj`), not a FRAMRPC operation | **Different layer** |
| Excision | Retention exists as sealed epochs and typed unavailable/expired errors, but active-log compaction and retention policy are ungated (`Q7`) | **Partial** |

One structural note behind several rows: `fram.schema` is an **in-process**
layer used by embedded callers and the JVM checkout. The official Bun
`@tompassarelli/framrpc/schema` entry point is the corresponding remote
application layer: it compiles cardinality replacement, uniqueness checks, and
guarded updates into ordinary reads plus an `expected-version` FRAMRPC batch.
The wire remains deliberately kernel-level, so clients that bypass that entry
point can still write propositions that violate an application's constraints.

## 3. Workarounds you no longer need

- **Reification.** No `rdf:Statement`, no statement entity, no attribute
  triplet to describe one fact. Nest the Triple.
- **Join entities for n-ary relations.** A nested Triple carries the extra
  arguments; you do not mint an entity plus three attributes to hold them.
- **Tuple or composite attributes.** A composite value is just a Term.
- **Schema-before-write ceremony.** An unregistered predicate behaves as
  multi-cardinality; declaring is opt-in, and an undeclared space keeps
  freeform write behavior (`P1`).
- **Transaction-entity gymnastics for provenance.** Assert about the occurrence
  coordinate directly.
- **Audit side-tables.** History is Triples. "What did this retraction kill" is
  a `:kernel/withdraws` edge, and "what happened between these two
  transactions" is a since window.

## 4. Three adoption styles

**(a) Datomic-shaped, day one.** In-process, use `fram.schema` plus the pull
projection: register predicates with cardinality and value kind, write through
`assert!`, read nested shapes through pull. Nothing to declare in the store
beyond the registry itself. Single-cardinality supersession happens at that
layer, so keep every writer on it.

**(b) Declared.** Anchor a profile on the space
(`(space-id, :kernel/profile, (profile-id, "relational", "observe"))`) and list
its rules as ordinary propositions. `rpc/validate` then reports violations as
rows. The verdict is advisory today — the write path does not consult it.

**(c) Native.** Drop the role reading: put Triples in any position, quote
statements, query the `occurrence` relation directly, and let profiled and
unprofiled data coexist in one space during a migration. Lint reports; nothing
breaks.

These compose. A space can be written natively and read through a profile, or
carry a registry without a profile anchor.

## 5. The honest not-yet list

Each row is a work order, not a caveat to be argued away.

- **Enforce mode.** Profiles are observe-only. Prospective admission and
  advisory lint agree for the declared rules (`P3`), but rejecting a violating
  write before append is unbuilt (`P2`, UNBACKED).
- **Declarative, engine-wide uniqueness.** The official Bun schema entry point
  provides correct unique create/upsert and guarded updates for writes routed
  through it, including duplicate-owner rejection and conflict retries. Fram
  still has no stored uniqueness declaration or kernel admission rule, so
  arbitrary FRAMRPC writes can bypass that application constraint. Engine-wide
  enforcement also wants an index Fram does not have — see the next row.
- **No value-ordered index.** Equality-prefix probes cover attribute and
  attribute+value lookup in one hop, which is the common Datomic AVET use, but
  there is no ordered index and therefore no range scan. Comparisons filter
  after binding. Building one is deferred until a measured need exists.
- **No cascade retract, no retract-by-pattern.** Compute the set client-side
  and send exact retractions in one batch.
- **`value_kind` is a hint.** Declared and used by projections, checked by
  nothing.
- **Transaction metadata is not on the wire.** If you need actor or wall-clock
  time from a FRAMRPC client, assert it as ordinary propositions about the
  occurrence coordinate.
- **Text-index retention is JVM-route only** (`Q3a`). The native engine
  rebuilds the text source per query, plan-gated.
- **Retention and excision are partial** (`Q7`). Sealed ranges, unavailable,
  and expired are distinct and typed; active-log compaction and production
  retention policy are not gated.
- **Store materialization is superlinear.** Boot cost and memory grow as
  roughly the square of live triples, which — not log length — is what bounds
  a store today. The measured shape is in the capacity section of
  [guarantees](guarantees.md#capacity-and-performance-envelope--open-rungs).

**Profile roadmap**, in the order the code makes cheap: enforce mode at the
existing prepare-actions seam (whole-write rejection, sharing the prospective
verdict path); per-attribute kind rules as profile vocabulary, so `value_kind`
becomes a lint rule instead of a hint; declared index packs following the
text-index precedent, with a value-ordered pack as the first candidate and
uniqueness enforcement riding on it. None of this changes the kernel: a profile
is a stored contract above it, and the engine write path gains no schema
knowledge.

## 6. Recipe appendix

| You want | Do this |
|---|---|
| Lookup refs | For schema-aware Bun applications, use `@tompassarelli/framrpc/schema` identities and required-unique guards; duplicate owners reject instead of being selected arbitrarily. `resolve-name` remains the in-process registry convention, not an engine-wide uniqueness rule |
| Entity API | The pull projection over an immutable store, in process |
| `d/history` | The `occurrence` relation; follow `:kernel/withdraws` for what a retraction targeted |
| `d/as-of` / `d/since` | The `:query/as-of` and `:query/since` selectors. Since restricts every base relation to its window, not just `occurrence` |
| `d/with` | A staged builder read, in process only |
| Paging | Snapshot-pinned cursors. A paged current query stays stable because the continuation reuses the cursor's resolved version, not head. Keep pages well under the 248-row unpaged bound (`N3`) |
| Bulk load | Batches have a 247-action depth ceiling and must also fit the exact predicted response byte limit; a batch commits as one frame or not at all (`A1`, `N3`) |
| "Did my write land" | The mutation receipt returns occurrence coordinates, and `expected-version` gives you OCC: a stale or future version fails `:rpc/conflict` without moving the version (`I2`) |
