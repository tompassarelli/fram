> **HISTORICAL — design provenance only.**
> This document describes a removed architecture generation and is retained
> as evidence of how the design evolved. Nothing in it is a current runtime
> reference. For the live contract start at
> [`docs/architecture.md`](../architecture.md) and
> [`docs/guarantees.md`](../guarantees.md).
> Archived from `codegraph/README.md`; unqualified paths below are relative to
> the retained `codegraph/` subtree.

# Codegraph

**Status: historical Codegraph experiment documentation. Not a current
recursive-kernel runtime reference.** The implementation and receipts are kept
as evidence; references below to a reified fact graph describe that experiment's
legacy projection.

**Code as facts, on [Fram](https://github.com/tompassarelli/fram).** Codegraph
projects a beagle source tree into the same reified fact graph that North
uses for life and work, then *derives* code intelligence — call graphs, scope-correct caller
resolution, transitive leverage — as queries over that graph instead of as
bespoke passes over text.

The bet is the same one North makes, pointed at source: **a flat,
text-and-grep view of a codebase rots and can't compute relational questions;
the graph is always current and answers them for free.** The question Codegraph
exists to settle is whether that actually buys anything over the cold-parse,
one-hop tools we already have — or not.

## The pipeline

```
*.bjs ──beagle-facts───▶ CNF triples ──load──▶ Fram fact store ──Datalog──▶ leverage / callers
  (AST as facts)          [s "p" o]        (interned graph)      (transitive closure)
```

1. **`beagle-facts`** (in [beagle](https://github.com/tompassarelli/beagle), `bin/beagle-facts`) reflects a
   file's AST into newline-separated EDN fact triples `[subj "pred" obj]` —
   `form-kind`, `name`, `calls`, and a uniform **`child`** containment edge.
   It's a cross-cutting *analysis* projection, not a compile target: it projects
   `.bjs` / `.bclj` / `.bnix` alike, ignoring each file's `#lang`.
2. **`bin/emit-corpus`** runs that over a source tree → `build/<name>.facts`.
3. **`src/codegraph.bclj`** folds the triples into a Fram store, derives the
   **namespace-correct** function call graph (a call binds the defn in its own
   module — the scope a bare-symbol match ignores), and runs the benchmarks.

## Prerequisites

Codegraph is the glue layer over three sibling projects; clone them next to this
repo (the `~/code/<name>` layout the commands below assume) and have
[Babashka](https://github.com/babashka/babashka) (`bb`) on `PATH`:

- **[fram](https://github.com/tompassarelli/fram)** — the fact store + Datalog
  engine. Build its classpath dir (`fram/out`); the runner loads `-cp ~/code/fram/main/out`.
- **[beagle](https://github.com/tompassarelli/beagle)** — provides `bin/beagle-facts`
  and `bin/beagle-roundtrip` (the AST→facts projectors). `bin/*` here resolve it
  via `$BEAGLE` (default `$HOME/code/beagle/main`); override if you check it out elsewhere.
- **[gjoa](https://github.com/tompassarelli/gjoa)** — the live corpus the benchmarks
  in RESULTS.md run against. Any beagle source tree works; gjoa is just the one measured.

## Run it

```sh
bin/emit-corpus  ~/code/gjoa/src ~/code/gjoa/tools ~/code/gjoa/tests  build/gjoa.facts
bb -cp ~/code/fram/main/out:src:~/code/fram/main  -m codegraph  build/gjoa.facts
```

## What it proves (and doesn't)

See **[RESULTS.md](../../codegraph/RESULTS.md)** for the measured verdict. In short: on the live
gjoa corpus the graph answers two questions the incumbent cannot — **scope-correct
callers** (perfect precision where bare-symbol match is 33–67% wrong) and
**transitive blast radius** (the keystone a one-hop tool structurally can't
surface) — and Fram's Datalog computes the real call-graph closure correctly.

Two projections, two jobs (both derived from the same source):
- **Query projection** (`beagle-facts`) — compact AST facts with semantic
  overlays (`calls`/`name`/`child`). Great for leverage queries; lossy (drops
  types/params). ~18 triples/form.
- **Truth projection** (`beagle-roundtrip`) — verbose reader-datum facts that
  round-trip the program **losslessly** (types survive as tokens, comments as
  resolved references). The graph as a *source of truth*; text as a regenerable
  view. ~238 triples/form.

## Status

Built and validated in stages: projection → leverage benchmark → lossless
round-trip → graph-native rename → a shadow-correct lexical resolver →
rename-correct comments. Headline gates hold at **1100/1100 forms, 97/97 files**.
Measured results are in **[RESULTS.md](../../codegraph/RESULTS.md)**; the stage-by-stage build
log is in **[docs/build-log.md](../../codegraph/docs/build-log.md)**.

## License

Codegraph is dual-licensed under your choice of the [MIT License](../../codegraph/LICENSE-MIT)
or the [Apache License, Version 2.0](../../codegraph/LICENSE-APACHE)
(`MIT OR Apache-2.0`).
