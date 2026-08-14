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
In FNF, every particular domain fact has identity as a Term. Its relationship
to a subject, value, and classification are separate Triples; no domain fact is
compressed into a specialized predicate/scalar cell. An Atom is opaque identity
or literal, never a packed relationship. Do not continue to shape design,
writes, or queries until the model passes the FNF gate below.

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

- Use this closed normal form for each particular domain fact. `F` is a
  metavariable for one opaque or minted Term, not Fram syntax:

  ```text
  (subject, relation, F)
  (F, :value, value)
  (F, :member_of, class)
  ```

  `relation` states how the subject relates to the identified fact. Prefer the
  precise domain affordance: `:contactable_at`, `:followable_at`,
  `:assigned_to`, or another established relation. Use `:has` only when
  possession really is the intended relationship; it otherwise hides questions
  of ownership, exclusivity, delegation, and access. `:value` and `:member_of`
  are FNF structural vocabulary. These three propositions end the
  decomposition: do not create another fact identity merely to reify them.
  Every additional domain assertion about `subject`, `F`, or `value` is itself
  another particular fact and takes this same form recursively.
- Reject both compressed email forms:

  ```text
  ("Alice", :contact/personal-email, "alice@example.com")
  ("Alice", :contactable_at, "alice@example.com")
  ```

  The first hides classification in a spelling namespace. The second removes
  the namespace but still points its domain relation directly at a scalar,
  omitting the identity and classification of this particular contact fact.
  Renaming the predicate does not normalize the model.
- Normalize the particular fact instead. Let `E` name that fact:

  ```text
  ("Alice", :contactable_at, E)
  (E, :value, "alice@example.com")
  (E, :member_of, :personal_emails)
  ```

  `E` exists because FNF gives every particular domain fact identity, not
  because an annotation later happened to need an id. Its printed form may be
  `personal-email#1515` for human readability, but no consumer may infer its
  class from that prefix; the `:member_of` Triple is authoritative.
- Keep three identities separate: `E` is the application-level identity of the
  particular domain fact; each exact structural Triple is proposition content;
  and Fram creates a distinct occurrence coordinate for each assertion or
  retraction. Never mint one as a substitute for another. FNF does not turn
  `fact` into a new kernel type or erase Fram's fact-as-view-status definition.
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
  A multiword lexical label such as `:member_of` is fine only as one opaque
  relation name; no consumer may recover extra facts by splitting it.
- State vocabulary membership explicitly rather than spelling a namespace:

  ```text
  (:contactable_at, :member_of, :contact_relations)
  ```

  Membership is semantic. `grouped under` is merely an organizational or
  presentation relationship and must not stand in for `:member_of`.
- The reserved `:kernel/*` vocabulary is closed engine protocol, not a pattern
  for application terms.
- Before presenting or implementing a model, inspect every proposed domain
  Atom containing `/` or another encoded component. If the component implies a
  join, hierarchy, type, ownership, version, or lifecycle fact, make that fact
  an explicit Triple. Renaming `/` to `-`, `_`, `.`, or a prefix does not
  normalize anything. Reject the model until the structure is asserted.

The FNF gate passes only when all six answers are yes:

1. Does every particular domain fact have its own Term `F`?
2. Are its subject relationship, value, and classification stated as
   `(subject, relation, F)`, `(F, :value, value)`, and
   `(F, :member_of, class)`?
3. Does every domain relation point to the identified fact rather than directly
   to a scalar value?
4. Can every membership and relationship be discovered by querying Triples
   rather than parsing Atom text?
5. Would changing an Atom's presentation spelling leave domain structure
   intact?
6. Have fact identity, proposition identity, and assertion-occurrence identity
   remained distinct?

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
- **Kernel normalization and the Datomic contrast:**
  `fram:docs/ontology.md` and `fram:docs/coming-from-datomic.md`. Their flat
  Triples are legal Fram, but a domain fact in an application model must also
  pass this skill's stricter FNF gate.
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
- If any particular domain fact is flattened into an attribute/value cell,
  stop. Give the fact a Term and separately state its subject relationship,
  value, and membership. This is mandatory, not conditional on continuity,
  annotation, or anticipated reuse.
- If a domain Keyword or String uses namespace spelling to imply membership,
  stop and replace that spelling with an opaque Term plus an asserted
  `:member_of` proposition. Keyword versus String carries type, not membership.
- If you bypass FRAMRPC to reach an internal store helper, stop and confirm that
  the task is engine implementation work rather than application modeling.

The family: Beagle text edits → `beagle-authoring`; graph-upstream files and
relational code queries → `code-as-facts`; applications on the engine → this
skill. The source loop is documented in `beagle:docs/authoring-loops.md`.
