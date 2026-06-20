# Reconstruction-cost curve — PRE-REGISTRATION (2026-06-20, before high-N measurement)

Written BEFORE measuring at N≫1 (anti-Edison: prediction→measurement). Chasing a SHAPE on a
defensible axis — not a magnitude, not the column the graph happens to win. Final frame; revisions in git.

## Arms — two; nothing invented, nothing built
- **arm-G (graph):** Beagle-as-graph, fram rename verb. Re-points by identity.
- **arm-LSP (incumbent):** raw Clojure + the **real `clojure-lsp` rename** — the best realistic
  dynamic-text tooling, **measured, not modeled.**
- **Held constant:** runtime (both execute as Clojure on bb), the logical program, the agent-blind oracle.
- **CUT — Beagle-text / find-replace.** Typed `.bclj` has no LSP (clojure-lsp/clj-kondo parse-fail) and
  we are NOT building one. Find-replace was only ever a tooling-reality sidecar — cutting it **deletes**
  that confound instead of annotating it, and it's truer to the thesis (the graph IS the representation;
  text is a projection). **Do NOT build or estimate `beagle-lsp`** — building measures my LSP effort,
  estimating models the number I want; the graph's point is correct rename *without that tooling existing*.
- **FUTURE, not core:** Clojure-graph vs Clojure-text — ingest plain Clojure as *untyped*-Beagle into the
  graph (untyped Beagle compiles) → same language, same dynamism, representation ALONE differs = the
  **pure-addressing isolation.** A separate exploratory arm, parked.

## What the two arms actually measure — TWO variables, named honestly
Beagle-graph vs Clojure-text moves **both addressing** (identity-graph vs positional-text) **and typing**
(typed vs dynamic); only runtime/program/oracle are held constant. So this is **NOT "representation
alone."** It is the **COMBINED typed-identity-graph stack vs the dynamic-text stack.** That combined
claim IS the AI-era "dynamic languages break under agent refactoring" frame, told cleanly with one
porting effort — which is exactly why there is **no third (TypeScript/Python) arm**: a foreign language
changes the runtime too and lets a skeptic dismiss the result as "unrelated languages benchmark
differently," collapsing the controlled comparison. The frame we want is already what two arms measure.

## TWO headline tiers — kept separate so Tier 1 stays unimpeachable
**Tier 1 — irreducible structural core (language- AND tooling-independent).** A rename that operates on
IDENTITY (a) **cannot false-hit** the old name in a string/comment, and (b) **cannot leave an unverified
reference** — both **by construction**, regardless of language, typing, or what it's compared against. A
structural property of the graph, stated on its own terms; nothing blends in. Demonstrated, not
benchmarked: the graph rename recompiles + passes the agent-blind oracle with **zero per-site
verification at every N.** State it flatly.

**Tier 2 — the combined empirical claim.** The typed-identity-graph stack refactors more reliably than
the dynamic-text stack **even on its best tooling (clojure-lsp).** Part of clojure-lsp's incompleteness
is **dynamism** (it can miss macro/dynamic/reflective refs *because Clojure is dynamic*); part is
**addressing**. Real and industry-relevant, but **explicitly COMBINED** — not pure addressing. This is
the head-to-head measured across the full vector below.

Keep them separate: Tier 1 is bulletproof; Tier 2 carries the broader story honestly.

## Full metric vector — predicted on EVERY axis, including where the graph LOSES
A graph-wins-every-column table is the tell to distrust. Predicted before measuring:

| axis | arm-G (graph) | arm-LSP (clojure-lsp) | tier / verdict |
|---|---|---|---|
| **collision / false-hit** (old name in str/comment) | immune by construction | also safe (semantic) | **TIE vs lsp** — a Tier-1 *guarantee* for the graph, but not a discriminator vs a semantic renamer (it discriminated vs the cut find-replace arm) |
| **unverified / missed refs** | none by construction | static analysis can miss dynamic/macro/reflective refs → must verify | **graph wins — Tier 2** (blends addressing + dynamism) |
| **flatness shape** (cost vs N) | ~constant | verification/completeness-risk grows with N | graph wins — the thesis shape |
| **machine wall-time** (raw s) | **slower**, ~fixed overhead (daemon + render + recompile) | fast (in-memory) | **graph LOSES at low N**; language-confounded (lsp→Clojure, graph→Beagle); cross-point if any reported honestly |
| **compute** | **higher** (render-recompile) | lower (in-memory) | **graph LOSES**; language-confounded |

**Honest thesis:** the graph **pays raw compute/latency to BUY a by-construction correctness guarantee
(Tier 1) + flatness, and an empirically more-reliable refactor than the best dynamic-text tooling
(Tier 2).** Lose latency, win correctness + flatness. Caveat, stated not papered: wall-time/compute and
part of the completeness gap carry a language/typing difference — **only Tier 1 is fully clean.**

## Falsifier (pre-committed)
If at N≈79 the graph is NOT flat (the verb scales with N, re-pointing isn't free, or recompile/oracle
fails), the constant-cost claim is **not supported at this scale** — reported straight. A broken curve
at high N beats a clean win.

## N points
- **N=1** — S3 (greet, one reference). Tie at the floor, as predicted.
- **N≈12** — honeysql `sql-kw` carve (`util/str`, 4 distinct callers).
- **N≈79 callers / ~200 :clj sites** — full `honey.sql` (`util/str`). Measured genuinely-scattered (79
  distinct callers), NOT self-ref-inflated. (Raw "241" was `.cljc`-doubled tokens; 79 callers is robust.)

## Honest labels
Mechanism-at-scale, **self-sourced** N (honeysql has no issue-sourced multi-site rename). Tier 1 is
unimpeachable + language-clean; Tier 2 is the typed-graph-vs-dynamic-text combined story. Not a
cherry-picked magnitude. Issue-sourced refactoring + pure-addressing (Clojure-graph) are later arms.

## Sharpened before the N≈12 run — measure DIVERGENCE, not just level (added pre-run)
1. **Measure lsp's wall-time at EVERY N, not just the graph's.** "lsp's edit+verification grows with N"
   bundles two things. The EDIT half (lsp rename wall-time) is plausibly ~FLAT in N (cached analysis +
   cheap edits) — a guess the run must SETTLE, so lsp wall-time goes in the vector at every N.
   **Latency-LEVEL (graph slower at every N) is NOT latency-DIVERGENCE (does the gap widen).** Only
   divergence is thesis-relevant; if lsp is also flat, the latency axis shows a constant gap, not
   divergence, and "graph loses latency" must not be dressed up as the latency-axis thesis result.
2. **Verification-burden metric — gives Tier 2 a number even when lsp is fully correct.** Define it as
   the **count of textual `\b<oldname>\b` occurrences NOT in lsp's edit-set** = occurrences a rigorous
   agent must inspect to confirm none is a missed dynamic/string-embedded reference (the tool left them
   untouched; are they correctly prose, or a missed ref?). Deterministic (grep-count minus edit-set
   count), expected to grow with N, and the **graph's equivalent is structurally 0** (non-references are
   separate claim nodes; nothing to disposition). This is the Tier-1 guarantee turned into a measured
   cost: the text arm pays to verify what the graph makes free, whether or not lsp ever fails. The
   measured-MISS stays the stronger, contingent-on-a-dynamic-ref result; verification-burden is the
   weaker, ALWAYS-available one. **If it comes out ~0 for honeysql's clean static symbols, that is itself
   the honest ceiling** (Tier 2 is weak on honeysql), stated now not discovered later.

## Reframed before the honeysql run (advisor relay, 2026-06-20) — the deliverable is the CURVE, not a count
The honeysql run's product is **NOT a rename-count** ("graph renames 79 sites"). That is throughput
theater — exactly the magnitude-brag this project is disciplined against — and it adds no mechanism: the
re-point of one `bound_to` edge is **O(1) regardless of N**, already proven at N=1. Worse, "O(1) rename
across 79 sites" silently **conflates two different things**: the *semantic re-point* is O(1), but
**time-to-compiled-correct is O(affected modules) for BOTH arms** (graph and lsp both re-render +
recompile every touched module). So that phrase must never stand unqualified next to a large N.

**The actual prize of the honeysql run = the SHAPE of both end-to-end cost curves vs N**, and one
specific pre-registered hypothesis:

- **Amortization question (pre-committed, two outcomes):** is the N=1 warm-vs-warm **3–10× graph latency
  penalty** (1) **FIXED overhead** (daemon + render startup) that **amortizes toward parity** as N grows
  (→ "the graph pays a fixed startup cost that is negligible at the N anyone actually refactors at" —
  honest and thesis-adjacent), or (2) **per-reference cost** that persists/grows (→ "the graph is simply
  slower per reference")? **This is the one thing N≈79 settles that N=1 cannot.** It is a hypothesis to
  MEASURE, not assert — either outcome is a result.
- **Measure BOTH curves, not just the graph's.** arm-G end-to-end and arm-LSP end-to-end at N=1/≈12/≈79;
  plus arm-LSP rename wall-time alone at each N (latency-LEVEL vs latency-DIVERGENCE, per the sharpening
  section above).
- **Completeness null, stated plainly up front:** honeysql's symbols are static → clojure-lsp is
  complete → completeness **TIES** and verification-burden is **~0**. honeysql's only wins are the
  **Tier-1 structural guarantee** and the **beagle bugs it surfaces**. **honeysql is NOT the Tier-2
  result** — it is mechanism-demo + cost-curve. The measured Tier-2 miss lives on a separate dynamic-ref
  target (gated on the `refers_to`-coverage precondition; see STATUS.md decision #1).

**Deliverable list for the honeysql run:** two end-to-end cost curves (arm-G, arm-LSP) at N=1/≈12/≈79;
arm-LSP rename wall-time at each N; verification-burden at each N (expected ~0); the **amortization
verdict** (fixed-and-amortizing vs per-reference). Not a rename count.
