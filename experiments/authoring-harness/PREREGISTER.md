# Pre-registration — predictions committed BEFORE numbers

## Disclosure (honesty: rule 5)
A harness-VALIDATION pilot of `greet` was run during bring-up (to debug harness.sh), so the greet
point prediction below is informed, not blind. The genuinely-untested prediction is the CURVE SHAPE
across N (only N=1 has ever been measured). Disclosed so the prediction isn't dressed as blind.

## P1 — greet (N=1), rename `base`->`greeting`
- arm-G `edit` (daemon identity re-point): ~constant ~300ms, dominated by bb-CLI startup + socket
  round-trip; the daemon op core is sub-ms. O(1) in N by construction (+1 assert / +1 retract).
- arm-LSP `rename` (clojure-lsp, warm): ~100-120ms (GraalVM native + cached analysis).
- **arm-G LOSES the rename-op wall-time ~2-3x at the floor.** Predicted, and it must show (rule 6: if
  the graph wins a column at N=1, distrust the measurement).
- render + recompile: arm-G pays a large typed-build recompile (~5s, beagle typecheck+emit) vs arm-LSP
  dynamic clj load (~tens of ms); NOT apples-to-apples (typed vs dynamic), reported as attributed but
  confounded. Shared-ish, not the clean cross-arm axis.
- Classification: measured-with-config.

## P2 — THE CURVE (cost vs N), the genuinely-open prediction
- arm-G `edit` is **flat in N** (one edge re-point regardless of how many references; O(1)).
- arm-LSP `rename` wall-time: predicted **near-flat or mildly growing** in N — clojure-lsp has the
  analysis cached and edits are cheap; the EDIT half is likely ~flat, so the latency axis may be a
  constant gap (graph slower at every N), NOT a diverging gap. Only divergence would be thesis-relevant
  on the latency axis; a constant gap is "graph pays fixed overhead," which amortizes against the
  (shared, N-independent) recompile.
- **Falsifier:** if arm-G `edit` is NOT flat (scales with N), the O(1) claim is unsupported at scale —
  reported straight. If arm-LSP `rename` grows steeply with N, the divergence is real and reported.
- Honest expectation: the dominant end-to-end cost for BOTH arms at any N is recompile, which is
  N-independent and (for arm-G) typed — so the rename-op difference, while real, may be a small slice of
  wall-time. That itself is a finding (the substrate op is cheap; the build dominates).

## P3 — fram-own-source higher-N (IF a clean scenario is reachable)
Same shape as P2. Correctness gate = recompile-clean (un-re-pointed ref -> undefined -> build fails),
classified separately from greet's behavioral oracle. If no clean single-module scenario exists without
porting, HALT (do not synthesize a gamed fixture, do not port).

