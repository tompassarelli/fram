---
name: fram-modeling
description: >-
  Use when BUILDING a program, app, or tool on the Fram engine — modeling
  data as recursive Terms/Triples and querying it through FRAMRPC structured
  plans instead of SQL/records/imperative state. Covers append-only occurrence
  history, immutable snapshots, paging, and Datalog derivation. NOT for
  one-off store reads or graph-authoring edits.
---

# Fram modeling — building on the Fram engine (recursive triples + FRAMRPC)

The current contract is in `fram:README.md`, `fram:docs/architecture.md`,
`fram:docs/query-reference.md`, `fram:docs/ontology.md`, and
`fram:docs/guarantees.md`. Documents under `fram:docs/archive/` are historical
provenance and must not drive a design. Fram’s semantic model is recursive:
`Atom := String | Int | Float | Bool | Keyword | Instant`, `Term := Atom | Triple`,
and `Triple := (Term, Term, Term)`. Positions are neutral; domain roles come
from asserted vocabulary, not a privileged subject/predicate/object schema.

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

## 1. The operating model

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

## 2. Ground-truth examples (read these, don’t reinvent)

- **Recursive terms and occurrence semantics:** `fram:README.md` and
  `fram:docs/ontology.md`.
- **Structured recursive query:** `fram:docs/query-reference.md` and
  `fram:clients/bun/README.md`.
- **Executable contracts:** `fram:tests/triple_kernel_test.clj`,
  `fram:tests/triple_query_test.clj`, and
  `fram:tests/native_rpc_server_test.clj`.
- **Beagle-authored engine code:** `fram:src/` is authoritative; generated
  Clojure in `fram:out/` is a build projection. For editing that source, use
  the `beagle-authoring` skill and its compiler-first loop.

## 3. Discipline (the smell tests)

- If a mutable map or record is standing in for durable domain state, stop: put
  that state in Triples so history and recursive queries remain available.
- If you hand-roll a relational or transitive walk, express it as a structured
  Datalog rule set and verify it against the query contract. Keep flat filters
  and presentation logic imperative.
- If you mint opaque ids for values that already have identity as Terms, stop.
  Use the Term directly; occurrence coordinates are created by the engine for
  history, not by the application as a reverse map.
- If you bypass FRAMRPC to reach an internal store helper, stop and confirm that
  the task is engine implementation work rather than application modeling.

The family: Beagle text edits → `beagle-authoring`; graph-upstream files and
relational code queries → `code-as-facts`; applications on the engine → this
skill. The source loop is documented in `beagle:docs/authoring-loops.md`.
