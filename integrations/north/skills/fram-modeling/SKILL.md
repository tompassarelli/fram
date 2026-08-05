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
  `fram:out`); `(require '[fram.cnf :as c] '[fram.datalog :as d] '[fram.schema :as s])`.
- **Append-only — never mutate.** You *assert* (`c/fact!`). An **update is a
  SUPERSEDING fact**: assert the new value, then a fact with the registered
  supersedes-pred pointing at the old fact id. The old value stays in the store
  (marked not-live) — so **history/audit is intrinsic**, free.
- **Query the LIVE view.** `c/by-lp` / `c/by-pr` / `c/current-facts` / `c/by-l`
  auto-filter superseded facts; `c/live?` tests one. `value!` is one-way
  (string→id) — keep your own id→string reverse map to render values back.
- **Reason with Datalog, not imperative walks** — *when the question is
  relational/recursive*. A transitive closure ("what does X transitively depend on /
  what breaks if I change X") is two `d/rule`s + `d/run-rules`; ready/blocked-style
  derivation is `d/nlit` + `d/run-strata` (stratified negation). The graph is always
  current; the answer is scope-correct (binding identity, not name match).
- **Know when NOT to.** A flat per-row filter (no joins/recursion) is fine as plain
  code — expressing it as Datalog is a *tax* (you re-state predicate schema the index
  already owns; measured net-negative in `north/cnf_lifecycle_test.clj` + the
  leverage probe). Datalog earns its keep on the *relational/recursive* questions.
- **No schema/migrations.** Predicates are open; adding a field is just a new fact —
  no `CREATE TABLE`/`ALTER`.

## 2. Ground-truth examples (read these, don't reinvent)

- **App data as facts (CRUD + history + reasoning):** `~/code/wake/web/spike/wake-on-facts/store.clj`
  — the gen-store CRUD seam, every op a fact op; the canonical add / update-as-supersede / tombstone / reaches gate.
- **App-level blast radius (scope-correct closure):** `~/code/wake/web/spike/app-blast-radius/cascade.clj`.
- **Stratified lifecycle (ready/blocked as rules) + the tax it can be:** `north:cnf_lifecycle_test.clj`.
- **Reason/repair over code:** `fram:out/resolve.clj` (refers_to, rename/delete/callgraph) — and the **code-as-facts** skill for querying a Beagle tree relationally.

## 3. Discipline (the smell tests)
- If you reach for a mutable map/atom of records as the app's data model, stop — that
  data should be facts (you lose history + reasoning otherwise). That's the
  SQL-vs-facts mistake, in-process.
- If you hand-roll a transitive closure with `loop/recur`, stop — it's a 2-rule
  `reaches`. (The one place imperative is right: flat filters.)
- `value!` returns a fresh-looking id but interns; never assume id→string without your
  own reverse map. Verify a round-trip on real data, like the spike's gate does.

The family: Beagle text edits → beagle-authoring · graph-upstream files and
relational code queries (edit channel + blast zone) → code-as-facts · building
apps on the engine → fram-modeling. Loop vocabulary:
`beagle:docs/authoring-loops.md`.
