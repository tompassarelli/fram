---
name: fram-modeling
description: >-
  Use when BUILDING a program, app, or tool on the Fram engine, including
  designing its ontology, vocabulary, shapes, or schema conventions — model
  data in Fact Normal Form as recursive Terms/Triples and query it through
  FRAMRPC structured plans instead of SQL/records/imperative state. Covers
  normalization, append-only occurrence history, immutable snapshots, paging,
  and Datalog derivation. NOT for one-off store reads or graph-authoring edits.
---

# Fram modeling — Fact Normal Form over recursive Triples

The current contract is in `fram:README.md`, `fram:docs/architecture.md`,
`fram:docs/query-reference.md`, `fram:docs/ontology.md`, and
`fram:docs/guarantees.md`; use `fram:docs/coming-from-datomic.md` when a design
starts to resemble attributes, entity types, or schema migrations. Documents
under `fram:docs/archive/` are historical provenance and must not drive a
design. Fram’s semantic model is recursive: `Atom := String | Int | Float |
Bool | Keyword | Instant`, `Term := Atom | Triple`, and `Triple := (Term, Term,
Term)`. Positions are neutral; domain roles come from asserted vocabulary, not
a privileged subject/predicate/object schema.

**Fact Normal Form (FNF) is the admission condition, not a naming preference.**
In FNF, an Atom is an opaque identity or literal and every semantic relation is
established by a Triple. A model has not passed this skill until every relation
needed to interpret, join, group, or validate the domain exists as Triples.
Structure left inside an Atom's spelling is absent from the database. Do not
continue to shape design, writes, or queries until the model passes the FNF
gate below.

FNF is the operator's preferred application-modeling discipline, not another
kernel primitive. Fram can store propositions outside this discipline, and
calling the discipline *Fact* Normal Form does not erase Fram's distinction
between proposition, assertion occurrence, and fact-as-view-status.

For greenfield work, model domain state as live Triples and let history,
identity coordinates, and metadata use the same recursive vocabulary. SQL,
records, maps, and text may be projections or local control data, but they are
not a second semantic source of truth.

## 0. Re-ground before designing

Read the current documentation named above, then inspect the typed definitions
under `fram:src/fram/` and the official client under `fram:clients/bun/`.
The public data boundary is FRAMRPC v1, not an incidental internal Clojure
function. The checkout CLI requires `FRAM_SPACE_ID` and routes data commands
through `fram:bin/fram`; Bun applications use `fram:clients/bun/framrpc.mjs`.
The native-first server is the default launcher; `jvm-dev` and `jvm-oracle` are
explicit development routes.

## 1. Establish Fact Normal Form before modeling

- The entire rule is: **Atoms are opaque; every semantic relation is a
  Triple.** Reject any model that requires a consumer to parse an Atom to
  recover type, grouping, ownership, containment, version, or identity-space
  membership.
- The documentation's canonical example demonstrates one part of FNF:

  ```text
  REJECT
  ("Alice", :contact/email, "alice@example.com")

  EXPLICIT VOCABULARY RELATION
  (:email, :grouped-under, :contact)
  ("Alice", :email, "alice@example.com")
  ```

  The rejected slash only suggests structure to a reader. The normalized
  grouping is stored, independently queryable, and available to joins. The
  second Triple is FNF only when the intended domain statement really is the
  binary relation “Alice has email value X.” It does not decide whether a
  particular email association has domain identity.
- When the domain recognizes one personal-email association as a thing with
  its own identity, keep that identity explicit. Let `E` denote an opaque or
  minted Term for that association (`E` is a metavariable here, not CLI
  syntax):

  ```text
  ("Alice", :has, E)
  (E, :value, "alice@example.com")
  (E, :grouped-under, :personal-emails)
  ```

  This is not compulsory RDF-style reification. `E` exists because the domain
  says the association persists, changes, or participates in other relations.
  If only the proposition needs annotation, the proposition is already a Term:

  ```text
  (("Alice", :email, "alice@example.com"), :verified-by, "mail-checker-1")
  ```

  Keep three identities separate: a domain thing such as `E`; the structural
  Triple naming proposition content; and the occurrence coordinate Fram creates
  for one assertion or retraction. Never mint one as a substitute for another.
- Present stored data as Triples only. Introduce any metavariable in prose, as
  with `E` above. Do not put `:=` declarations inside a fact block: `:=` is not
  Fram syntax and readers have already mistaken explanatory aliases for writes.
- Treat a leading `:` only as local EDN syntax for a Keyword Atom. It does not
  mark a predicate, property, function, or privileged slot. `:within` and
  `"within"` are different Atom types; either can occur in any Triple position.
  A bare CLI subject becoming an `"@..."` String is CLI shorthand, not ontology.
- Never encode semantic structure in an Atom's spelling. Reject domain
  vocabulary such as `:proposal/within`, `:thread/title`, or `:shape/requires`:
  each is one opaque Keyword, and the slash creates no relationship Fram can
  join. Replacing the slash with a hyphen, underscore, prefix, suffix, or
  compound String has the same problem if a consumer interprets its pieces.
  A multiword lexical label such as `:grouped-under` is fine only as one opaque
  relation name; no consumer may recover extra facts by splitting it.
- Assert grouping and use the atomic relation separately:

  ```text
  (:within, :grouped-under, :proposal)
  ("proposal-a", :within, "thread-a")
  ```

  Query the grouping proposition when asking which vocabulary belongs to a
  domain. Do not mint one copy of the same relation per domain merely to recover
  a namespace convention.
- Keep a displayed compound identity such as `personal-email#1515` only when
  it is deliberately opaque. Its prefix may help a human read logs, but it
  cannot establish that the Term is a personal email; assert that classification
  separately. The reserved `:kernel/*` vocabulary is closed engine protocol,
  not a pattern for application terms.
- Before presenting or implementing a model, inspect every proposed domain
  Atom containing `/` or another encoded component. If the component implies a
  join, hierarchy, type, ownership, version, or lifecycle fact, make that fact
  an explicit Triple. Renaming `/` to `-`, `_`, `.`, or a prefix does not
  normalize anything. Reject the model until the structure is asserted.

The FNF gate passes only when all five answers are yes:

1. Can every domain grouping and relationship be discovered by querying
   Triples rather than parsing Atom text?
2. Would changing an Atom's presentation spelling leave the represented
   domain structure intact?
3. Does every Atom denote one opaque identity or literal rather than a packed
   record, path, namespace, type tag, or relation?
4. Does every separately identifiable domain thing have its own Term and all
   of its relationships stated as Triples?
5. Have domain identity, proposition identity, and assertion-occurrence
   identity remained distinct?

## 2. The operating model

- **Write through the public boundary.** `fram:bin/fram tell`, `retract`, and
  `validate` are convenient CLI projections. For applications, use the Bun
  client’s `assert`, `retract`, or atomic `batch` methods. Every mutation is
  append-only; replacing a value is a retraction plus an assertion in one
  transaction. Never edit FRAMLOG or generated `fram:out/` directly.
- **History is intrinsic.** An assertion creates an occurrence coordinate.
  Retraction records a withdrawal Triple targeting the exact occurrence; the
  old proposition remains addressable in history but is absent from the live
  view. Transaction sequence plus operation ordinal define logical order; wall
  clock time is metadata.
- **Query immutable views.** Use `bin/fram query` or the Bun client’s `query`,
  with `current`, `asOf`, or `since` selectors. Base relations are
  `triple(t1,t2,t3)` for live propositions and
  `occurrence(coordinate,action,proposition)` for history. Queries are
  structured plans, never query-text parsing; page nontrivial results and carry
  the opaque cursor unchanged so the snapshot stays pinned.
- **Use Datalog for joins and recursion.** Multiple rules reach a semi-naive
  fixpoint; stratified negation belongs in ordered strata. The query reference
  also defines predicates, arithmetic, aggregates, and the five positive text
  relations (`text-match`, `text-phrase`, `text-substring`, `text-stem`,
  `text-search`). A flat filter is ordinary application code when no join or
  recursion is involved.
- **No schema migrations.** Predicates are open. Adding a domain property is a
  new Triple; cardinality, uniqueness, and replacement policy are explicit
  domain rules, not implied by Triple positions.

## 3. Ground-truth examples (read these, don’t reinvent)

- **Recursive terms and occurrence semantics:** `fram:README.md` and
  `fram:docs/ontology.md`.
- **Fact Normal Form vocabulary rules and the Datomic contrast:**
  `fram:docs/ontology.md` and `fram:docs/coming-from-datomic.md`.
- **Structured recursive query:** `fram:docs/query-reference.md` and
  `fram:clients/bun/README.md`.
- **Executable contracts:** `fram:tests/triple_kernel_test.clj`,
  `fram:tests/triple_query_test.clj`, and
  `fram:tests/native_rpc_server_test.clj`.
- **Beagle-authored engine code:** `fram:src/` is authoritative; generated
  Clojure in `fram:out/` is a build projection. For editing that source, use
  the `beagle-authoring` skill and its compiler-first loop.

## 4. Discipline (the smell tests)

- If a mutable map or record is standing in for durable domain state, stop: put
  that state in Triples so history and recursive queries remain available.
- If you hand-roll a relational or transitive walk, express it as a structured
  Datalog rule set and verify it against the query contract. Keep flat filters
  and presentation logic imperative.
- If you mint opaque ids for values that already have identity as Terms, stop.
  Use the Term directly; occurrence coordinates are created by the engine for
  history, not by the application as a reverse map.
- If a domain thing has continuity or relations of its own but is flattened
  into an attribute/value cell, stop and give it a Term. If a proposition alone
  needs annotation, nest that Triple instead of manufacturing a statement id.
- If a domain Keyword or String uses namespace spelling to imply membership,
  stop and replace that spelling with an atomic term plus an asserted grouping
  proposition. Keyword versus String carries type, not grouping semantics.
- If you bypass FRAMRPC to reach an internal store helper, stop and confirm that
  the task is engine implementation work rather than application modeling.

The family: Beagle text edits → `beagle-authoring`; graph-upstream files and
relational code queries → `code-as-facts`; applications on the engine → this
skill. The source loop is documented in `beagle:docs/authoring-loops.md`.
