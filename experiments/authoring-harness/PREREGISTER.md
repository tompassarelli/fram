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

## P-LARGE-N (2026-06-21, LOOP-SPEC v2) — arm-LSP rename wall-time vs N on VANILLA honeysql (no port, falsifier-first)
The large-N divergence question has two halves: (a) does clojure-lsp's rename wall-time CLIMB with N? (text
side) and (b) does the graph's edit stay flat? (graph side). (b)'s mechanism is already established (O(1)
daemon op + cheap O(N)-once bound_to install, measured flat at N=1..4). (a) is the gating unknown and it needs
**NO port**: clojure-lsp renames `honey.sql.util/str` (N≈79) on the real honeysql Clojure source directly.
Measure it FIRST — it decides whether the expensive arm-G port is worth doing.

- **Method:** within ONE honeysql codebase (size + warm cache held constant), `clojure-lsp rename --dry` (no
  mutation, re-runnable) on several `honey.sql.util` / `honey.sql` vars across a range of N (edit-set size),
  warm (diagnostics prime first), timed. Each gives (N = edit count from the dry diff, wall-time).
- **Prediction:** based on the small-N finding (flat ~206ms, analysis-dominated), I predict lsp's rename
  stays **~FLAT in N even at N≈79** — the edit-set application is cheap; the cost is project analysis, which is
  cached and N-independent. If so: **no divergence even at scale → the cost-curve question resolves NULL
  without the port**, and the port becomes confirmatory-only (arm-G's O(1) is already mechanism-established).
- **Falsifier:** if lsp wall-time climbs materially with N (the 79-site rename much slower than a 2-site rename
  in the same warm project), divergence exists on the text side → the big honeysql port (arm-G at N≈79, under
  the fidelity gate) becomes high-value to confirm arm-G stays flat, and I do it (marathon; beagle gaps jump
  the queue).
- This is the same falsifier-first move as the Tier-2 lsp gate: measure the cheap threatening thing before
  building the favorable arm.

