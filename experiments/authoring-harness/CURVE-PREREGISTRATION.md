# Reconstruction-cost curve — PRE-REGISTRATION (2026-06-20, before high-N measurement)

Written BEFORE measuring graph/text cost at N≫1, so the prediction is the theory's, not
chosen after seeing the number (anti-Edison: the arrow points prediction→measurement). The
thing chased is a **shape**, not a magnitude.

## What theory (the addressing thesis) predicts — the falsifiable shape
On a **rename of a referenced definition** (a scattered-relationship op), as N = the number of
references grows:
- **Graph arm:** reconstruction cost is **~constant in N**. The rename is 1 verb → 2 claim ops
  (1 assert + 1 retract on the binding's identity) regardless of N; the N references re-point by
  identity with **0 additional ops, 0 per-site verification, 0 collision risk** (correct by
  construction). Predicted: **flat** across N = 1, ~12, ~79.
- **Text arm (best realistic tooling — `clojure-lsp` rename, NOT sed):** the rename *action* is
  ~1 command (≈ constant), but the cost that **grows with N** is **verification + collision**:
  confirming all N sites re-pointed, and ensuring the rename did not false-hit the old name in
  strings/comments/unrelated scopes (the LSP cannot know semantics the graph encodes). Predicted:
  **grows with N** (≈ linear in scattered sites / distinct caller files).

## Falsifier (pre-committed)
If at N≈79 the **graph** cost is NOT flat — the verb's cost scales with N, or re-pointing is not
free, or the recompile/oracle fails — the constant-cost claim is **not supported at this scale**.
Report it straight. A broken curve at high N is worth more than a clean win: it is a real finding,
not a confirmation.

## Metric (per arm; symmetric quality floor — both arms best-realistic tooling)
Reconstruction-cost primitives (counts, never wall-clock): graph ops; text rename-action + sites
to verify + **collision count** (old-name mentions in strings/comments the rename must not corrupt
— the durable edge that does NOT shrink with better tooling). **Symmetry test for any engineering
fix: would I apply the same standard to the arm I hope loses?** Fix only conservatively-obvious
slowness, on both arms equally — that removes a confound; it does not optimize toward a bias.

## N points (measured before porting, via clj-kondo, applying the honest filter)
- **N=1** — S3 (greet, one reference). Tie, as predicted at the floor.
- **N≈12** — honeysql `sql-kw` carve (`util/str` scattered cross-function, 4 callers).
- **N≈79 callers / ~200 :clj sites** — full `honey.sql` (`util/str`). GENUINELY scattered (79
  distinct caller functions) — verified by measurement to be real, NOT self-ref-inflated. (The
  raw "241" was `.cljc`-doubled token count; distinct-callers = 79 is doubling-robust.)

## Honest labels
Mechanism-at-scale, **self-sourced** N (honeysql has no issue-sourced multi-site rename — its
renames are keyword-deprecations). The claim earned is the **curve's shape on real code under
symmetric good engineering**, not a cherry-picked magnitude. Issue-sourced refactoring is a
separate, later arm.
