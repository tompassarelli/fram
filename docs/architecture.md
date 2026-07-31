# Architecture and project layout

## The fold

The engine is domain-neutral. Its only unit is the typed triple — `(left
predicate right)`, e.g. `(@X depends_on @Y)` — carrying its own address. Triples
append to a durable log; the log folds into an in-memory graph; consumers query
and derive over that graph; every write serializes through one coordinator. There
is no notion of "thread", "module", or "task" in the engine.

```
triples ─assert─▶ coordination.log (append-only) ──fold──▶ in-memory graph
                                                      │
                   coordinator daemon ◀── agents query + assert concurrently
                                                      │
                     a consumer derives its own views over the graph (Datalog)
```

- Entities referenced by `@` are **interned** — rename a thing once, not in N
  files.
- **Derived state is never stored.** No `state` field exists in the engine; a
  consumer reads `committed` / `outcome` / `ready` / blast-radius *off the
  triples*.

## One engine, many consumers

Fram ships **no** domain verbs of its own. A consumer is a projection plus a
vocabulary onto the neutral engine — new domain, new graph, same engine.

- **[North](https://github.com/tompassarelli/north)** — life and work
  coordination. The `ready` / `blocked` / `leverage` verbs live there, not in the
  engine. North models work as **threads**, one Markdown file each (`@id` header
  of triples, `---`, prose body; see [`../THREAD-FORMAT.md`](../THREAD-FORMAT.md)).
  `bin/fram import` folds those files in, and North derives `ready` / `blocked` /
  `leverage` from them. The bundled `threads/` corpus is North-shaped *only
  because Fram was extracted from North* — that is the one reason threads appear
  in the engine repo at all.
- **[Codegraph](../codegraph/)** — code as triples, a module *inside* this repo. A
  Beagle module's AST *is* the graph and the `.bclj` text is a rendered view.
  There are no threads here: the unit is the *def*, the projection is the
  *resolver*, and references carry the binding's identity (`bound_to`), so a
  rename is a ~2-triple edit and code intelligence (call graphs, blast radius) is
  Datalog.
- **[Beagle](https://github.com/Autonymy/beagle)** — the typed Lisp Fram itself is
  authored in; it projects source into the graph through Codegraph.

`export` is the verified-lossless inverse of `import`
([`../tests/roundtrip_test.clj`](../tests/roundtrip_test.clj)): files are a view,
not a second source of truth.

**One engine, many memory-spaces.** Each consumer lives in its own graph — a
separate log — and one coordinator can host several, one log per account or
tenant. A hosted North and a code graph are separate memory-spaces in the same
engine, never co-mingled (see
[isolation-and-deployment.md](isolation-and-deployment.md)). *Hosting North as a
tenant of a shared engine is a direction, not yet shipped.*

## What the graph buys: reasoning and repair

These are the two reasons to put something in a graph instead of files.

**Reasoning — relational questions are cheap, exact, and always current.** "What
depends on this? what is unused? who calls this? what unblocks the most other
work?" are *relationship* questions, and over a graph a relationship question *is*
a Datalog query — no reconstruction tax, because the graph is canonical and
incremental rather than rebuilt per question. Pointed at code (Codegraph), the
same engine answers "what breaks if I change this?" **scope-correctly**: a call
binds the definition in its own module, so two same-named functions in different
modules do not collide, which is what bare-text grep gets wrong.

**Repair — change one node, the blast radius re-derives.** Because the graph
knows the real edges, a change propagates to exactly the affected sites,
deterministically: a graph operation, not a model guessing. Reasoning reads the
graph; repair reads it and acts.

## Identity-addressed code (Codegraph)

[Codegraph](../codegraph/) points the engine at *code*, with the log canonical.

- **References carry identity, not spelling.** A call site resolves to the
  binding's stable id (`bound_to @module#int`), so renaming a definition is a
  ~2-triple edit and every reference re-points *by identity* — where a text tool
  must rewrite every site. (Measured comparison against an external corpus:
  [measurements.md](measurements.md).)
- **The render is a pure function of the log.** `render(log) == render(text)`,
  byte-identical to each other because both derive from the graph. The general
  round-trip is *datum*-identical, not byte-identical to hand-authored source:
  comments and exact whitespace are not preserved.
- **Code intelligence as Datalog.** Scope-correct call graphs and transitive blast
  radius are queries, computed by binding identity rather than name-match.

The categorical line under all of this is **node-identity vs no-node-identity**:
text and git lack a stable per-node id, so they re-derive the program to answer a
relational question or to coordinate a concurrent edit. Identity-addressed
concurrency by itself is not unique — a node-id CRDT has it too. What is
distinctive here is pairing it with a faithful *typed* projection into an
existing language.

## Worlds and claims

Two optional layers, both pure derivations over the same substrate — zero engine
change, and their executable specs prove it.

- **Worlds** — a named, forkable lineage of immutable versions. A version fixes
  *which triples are in scope*, the thing you evaluate queries "at". Fork is O(1),
  one head triple; a new version is an immutable base plus a **sparse overlay**,
  so incremental ingestion supersedes only what changed and inherits the rest, and
  old generations stay queryable. Why it is called a world:
  [the naming ledger](naming.md).
- **fram.claims** — assertion under verification. A claim is an ordinary triple
  plus evidence edges plus a status **derived from view membership**: `verified`
  is never stored, never even interned. Rejection is a view convention; add
  `evidence.world` and "which verified claims does this generation-transition
  invalidate?" is one Datalog rule between two world heads. Design:
  [claims-design.md](claims-design.md) (**note**: that document's status header
  says the module does not exist yet — it does, at `../src/fram/claims.bclj`,
  compiled by `../build.sh`). Contract:
  [`../tests/claims_spec_test.clj`](../tests/claims_spec_test.clj).
- **Both composed, end to end** — an addendum to a plan set as a fork plus sparse
  overlay, with exactly the affected verified claims dropping back to pending:
  [`../tests/world_claims_addendum_demo.clj`](../tests/world_claims_addendum_demo.clj).

## Anti-rot: the engine is the source of truth

Static reference docs rot, so the repo hardcodes as little of its own surface as
possible. The engine and its generators are the truth:

| You want… | Source of truth (always current) |
|---|---|
| the engine verbs | `bin/fram` (no args prints the full usage) |
| the AI tool catalog | `bin/fram tools` (generated from the vocabulary) |
| the authoring API + signatures | `bb bin/fram-primer` (generated from `src/fram/*.bclj`) |
| the predicate vocabulary | `bin/fram doctor` (with `FRAM_SINGLE_VALUED` to override) |
| what is tested | `tests/` + [`../.github/workflows/ci.yml`](../.github/workflows/ci.yml) |

[`../scripts/readme-check.sh`](../scripts/readme-check.sh) runs in CI: it asserts
every `bin/fram <verb>` named in the README is real, `test -e`'s every referenced
path, checks the dual-license contract against sha256-pinned texts, fails on a
stale repo URL, and runs the core import / validate / call / query / export loop
against a scratch copy of `threads/`. A command that stops working turns CI red.

## Project layout

`bin/fram` with no arguments prints the canonical verb list — the source of
truth; do not trust a copy. The daemon is `bin/fram-daemon` / `bin/fram-up`; the
AI surface is also served over MCP by `bin/fram-mcp`. The life verbs (`ready` /
`blocked` / `leverage` / `next` / `capture`) belong to the *consumer* (North), not
the engine.

- `src/fram/*.bclj` — the engine, authored in Beagle: kernel, fold, Datalog,
  schema, import/export, CLI.
- `src/fram/rt.clj` — the thin Clojure host-interop runtime.
- `src/coord_*.bclj`, `src/resolve_*.bclj`, `src/pull.bclj` — coordinator-layer
  modules (commit decision, read layer, resolver, pull), graph-upstream.
- `src/zig/daemon.zig` — a second, independent daemon implementation in Zig,
  used as a parity oracle against the JVM coordinator
  ([`../tests/zig_occ_oracle_test.sh`](../tests/zig_occ_oracle_test.sh),
  [`../tests/kernel_classify_native_parity_test.sh`](../tests/kernel_classify_native_parity_test.sh)).
  These are not wired into `ci.yml` today.
- `out/` — the **committed** compiled Clojure, so Fram runs without Beagle. The
  resolver lands here as `out/resolve.clj`; there is no `resolve.clj` at the repo
  root.
- `codegraph/` — code as triples: minimal-op authoring verbs, code intelligence.
- `docs/` — conceptual sources of truth: `WHY_FRAM_EXISTS.md`,
  `VIEWS_AND_BRANCHES.md` (the write/read model), `adr/` (project boundaries).
- `tests/` — the suites. `bench/` — perf-regression gates (the propagation
  budget). The measured *receipts* cited in [measurements.md](measurements.md)
  live in the separate `after-text` package.

**Graph-upstream modules.** 25 `.bclj` modules across `src/` and `codegraph/src/`
carry `;; @upstream:graph — the code lives in the Fram fact graph; this text is a
generated view`. Edit those with the graph-edit verbs, never as text, then re-run
[`../build.sh`](../build.sh).

## Tests

Every suite lives in `tests/` and runs on babashka against the committed `out/`:

```sh
bb -cp out tests/roundtrip_test.clj       # triples <-> files round-trip is lossless
bb -cp out tests/coord_test.clj           # adversarial concurrency + durability
bb -cp out tests/query_test.clj           # structured Datalog query + boundary rejections
bb -cp out tests/fram_promotion_test.clj  # clean-commit, checkout-only daemon promotion
```

`ls tests/*_test.clj` is the full list; CI runs them all
([`../.github/workflows/ci.yml`](../.github/workflows/ci.yml)).

## Design discipline

- **Removed, not deprecated.** No back-compat shims; correctness and the desired
  design decide, never "things depend on it."
- **Derive, don't store.** Lifecycle and code intelligence are views over the
  graph, not maintained fields.
- **One graph per trust domain.** Share machinery, never data.

See [`WHY_FRAM_EXISTS.md`](WHY_FRAM_EXISTS.md) and the [ADRs](adr/) for the full
argument.
