---
name: fram-modeling
description: >-
  Use when BUILDING a program, app, or tool on the Fram engine — modeling
  data/logic as recursive Triples + Datalog instead of SQL/records/imperative
state. Covers append-only occurrence history, live-view queries, and Datalog
derivation. Formerly named fact-modeling. NOT for one-off store reads.
---

# Fram modeling — building on the Fram engine (triples + Datalog)

The thesis (ADR 0001, archived in `fram:docs/archive/`): **the program/app/work IS a
graph of triples.** Data, logic, and structure live as triples, so each is *reasoned*
(Datalog: blast radius, transitive closure) and *repaired* (graph edits) the same
uniform way. Text and SQL are projections, never the truth. For **greenfield**, the
triple store is the backend — not SQL (persisting to SQL then rebuilding a graph to
ask relational questions reintroduces the reconstruction tax the engine exists to
kill).

## 0. The surface is GENERATED — never trust a static list

Like beagle-authoring ("the compiler is the source of truth"), the Fram API churns.
Inspect the source-head `fram:README.md`, `fram:docs/query-reference.md`, and the
typed definitions under `fram:src/fram/` instead of relying on a static cheatsheet.

## 1. The operating model (this does not churn)

- **Rent the engine from bb:** `bb -cp "$FRAM_OUT" your.clj` (`FRAM_OUT` defaults to
  `fram:out`); `(require '[fram.store :as c] '[fram.types :as t] '[fram.datalog :as d])`.
- **Append-only — never mutate.** The unit of write is a proposition, `(t/triple s p
  o)`, wrapped in `c/assert-operation` and committed with `c/commit-transaction!` on a
  `c/new-term-store` context. An **update is a retraction plus an assertion in one
  transaction** (`c/retract-operation` + `c/assert-operation`): the kernel records the
  withdrawal link between the two occurrences itself, so nothing at the user level
  reifies "supersedes". The old assertion stays, marked not live — so **history/audit
  is intrinsic**, free.
- **Query the LIVE view.** `c/live-propositions` returns the live view only; the
  history surface is `c/semantic-history`, `c/live-occurrences`, and
  `c/withdrawal-triples`. Any Atom is a Term, so a node is named by the thing it
  already is — there is no id-minting step and no reverse map to keep.
- **Reason with Datalog, not imperative walks** — *when the question is
  relational/recursive*. A transitive closure ("what does X transitively depend on /
  what breaks if I change X") is two `d/rule`s over `d/triple-relation`, run with
  `d/run-rules!` and read with `d/facts`; ready/blocked-style derivation is
  `d/negated-literal` + `d/run-strata!` (stratified negation). The graph is always
  current; the answer is scope-correct (binding identity, not name match).
- **Know when NOT to.** A flat per-row filter (no joins/recursion) is fine as plain
  code — expressing it as Datalog is a *tax* (you re-state predicate schema the index
  already owns, and it measured net-negative when tried). Datalog earns its keep on
  the *relational/recursive* questions.
- **No schema/migrations.** Predicates are open; adding a field is just a new fact —
  no `CREATE TABLE`/`ALTER`.

## 2. Ground-truth examples (read these, don't reinvent)

- **Update as retract-plus-assert, with the superseded assertion still queryable:**
  `fram:codegraph/src/rename.bclj` and `fram:codegraph/src/supersession_check.bclj`.
- **Transitive closure as two rules over the live propositions:**
  `fram:codegraph/src/codegraph.bclj` (`closure-line!`), checked against an in-process
  closure over the same edges.
- **Reason/repair over code:** `fram:out/resolve.clj` (refers_to, rename/delete/callgraph) — and the **code-as-facts** skill for querying a Beagle tree relationally.

## 3. Discipline (the smell tests)
- If you reach for a mutable map/atom of records as the app's data model, stop — that
  data should be facts (you lose history + reasoning otherwise). That's the
  SQL-vs-facts mistake, in-process.
- If you hand-roll a transitive closure with `loop/recur`, stop — it's a 2-rule
  `reaches`. (The one place imperative is right: flat filters.)
- If you find yourself minting ids for nodes, stop — the store interns Terms itself,
  so hand it the name the thing already has and read it straight back.

The family: Beagle text edits → beagle-authoring · graph-upstream files and
relational code queries (edit channel + blast zone) → code-as-facts · building
apps on the engine → fram-modeling. Loop vocabulary:
`beagle:docs/authoring-loops.md`.
