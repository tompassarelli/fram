# Measurements

Every number here is pinned to a receipt and a regeneration command. Read the
provenance line before quoting any of them: most of these receipts were produced
**outside this repository**, against builds that are not the current one, and are
therefore not reproducible from a committed Fram sha.

## The one CI-enforced gate that lives here

`bench/propagation/` is a Fram-local perf-regression gate, run in CI by
`bb -cp out bench/propagation/check-budget.clj`
([`../.github/workflows/ci.yml`](../.github/workflows/ci.yml)). It reproduces the
*shape* of the propagation thesis — graph propagation flat in K, a git
merge-queue climbing — and enforces a budget against this repo's own build.

The budget is ratio-based so it is machine-independent, with generous absolute
ceilings as catastrophe-catchers
([`../bench/propagation/perf-budget.edn`](../bench/propagation/perf-budget.edn)):

| Budget | Value | Meaning |
|---|---|---|
| `graph-prop-flatness-ratio-max` | 4.0 | graph propagation at K=8 over K=1 |
| `graph-beats-git-factor-min` | 8.0 | git propagation over graph propagation, at K=8 |
| `graph-prop-abs-ceiling-ms` | 50.0 | sanity ceiling on graph propagation |
| `graph-write-abs-ceiling-ms` | 700.0 | sanity ceiling on graph write (eager index) |
| `require-no-lost-writes` | true | landed = K/K in both arms |

**Its absolute numbers are its own.** They are not the external EXP-007 figures
below and should never be quoted as them.

Regenerate: `bb -cp out bench/propagation/sweep.clj` (with `SWEEP_KS=1,2,4,8`).

## External receipts — not reproducible from this repo

These live in the separate `after-text` experiments package. They are recorded
here for provenance, not as claims this repository can substantiate.

### Construction-path scaling vs zerolang

Building a medium app by incremental authoring, Fram is flat per-op while
zerolang's per-patch cost rises — it reloads, validates, and rewrites the whole
graph each edit: **2.3× @250 defs, 4.2× @500, 7.5× @1000**, the gap *growing*
with size, "O(N²)-shaped" (a curve and a source, not a formal fit).

This is construction-*path* scaling, not language speed.

**Caveats, load-bearing.** Measured against zerolang 0.3.4 and a **non-current**
Fram build (`e3f5df5` plus uncommitted optimizations), so it is not reproducible
from a committed Fram sha. The honest companion is that Fram trades away the
single-small-edit case — but that loss is receipted only *pre-optimization*
(`experiments/EXP-009-zerolang-single-op/RESULTS.md`); the later authoring-path
cuts flattened Fram's per-op cost, so whether Fram still loses a single small edit
at current code is **not established**.

Receipt: `experiments/EXP-010-zerolang-construction-scaling/RESULTS.md`
(`after-text` package).

### Propagation under K concurrent disjoint writers

External receipt (`after-text`
`experiments/EXP-007-propagation-ksweep/RESULTS.md`): graph ~1.6–2.2 ms (K=1…8)
vs a git push-hook queue ~50→314 ms.

Mirror cost, stated honestly: the graph **loses the write column** — ~175 ms
eager-index vs git's ~22–80 ms. It front-loads at write to keep reads and
propagation cheap.

**Caveat.** These are the external figures. The local gate above reproduces the
shape, not these numbers.

### Reference-site count on the honeysql corpus

**238 distinct reference sites** that a text tool must re-derive and rewrite for a
rename, against a ~2-triple graph edit.

**Caveat.** An external corpus, not regenerable from this repo. Receipt:
`experiments/EXP-002-owned-resolution-forcing/` (`after-text` package), regenerate
with `bb experiments/EXP-002-owned-resolution-forcing/probe.clj` inside that
package.
