# Loop Spec — autonomous authoring-harness measurement (your constitution)

## Thesis
Fram stores code as a graph of identity-addressed claims, not text. A reference carries
refers_to <node-id> (identity, not spelling), so a rename is one edit to one node and every
reference re-points for free. Text addresses by spelling+position; both move on edit, so a
rename means reconstructing scattered spellings. The discriminator is reconstruction cost vs N.

## What is already settled (do NOT re-litigate, do NOT try to reopen)
- There is NO analyzer-based Tier-2 miss to find. Verified by direct read: refers_to is an
  unexpanded-surface lexical walk (syntax->datum, zero macroexpand; resolve.clj has no expand
  pass). The graph sees EXACTLY the reference classes clojure-lsp/clj-kondo see — across symbol,
  keyword, and macro — and no others. Hunting for a reference class the graph catches and lsp
  misses will run forever and is FORBIDDEN.
- The substrate advantage is entirely Tier-1 and is DEMONSTRATED, not benchmarked: O(1)
  identity re-point; no false-hits in strings/comments by construction; a durable slot for an
  edge text has nowhere to keep. State this as a property of the current implementation, never
  as "graphs can never compute more."
- (ii) building a graph arm to win a keyword-rename gap is DEAD (value-is-spelling: a
  wire-contract keyword is unrenameable for everyone; an internal one namespaces and lsp renames
  it). Do not build it.

## What this loop RUNS — TWO TRACKS (supersedes the single-axis v1 scope, 2026-06-21)
Two tracks run concurrently; alternate as the data dictates; keep BOTH alive. Both obey the iron rules +
attribution discipline below, and their results stay in SEPARATE ledger columns — a substrate measurement and
a perceived-latency win never mix.
- **TRACK A — MEASUREMENT.** Cost curves under real refactors, full PER-LAYER attribution. The corpus now
  GROWS by porting under a fidelity gate (see TRACK A section). Priority: the large-N divergence answer
  (honeysql `util/str`, N≈79).
- **TRACK B — OPTIMIZATION.** Live build work: drive Fram toward the substrate's theoretical floor by closing
  execution-layer cost. Real implementation, in dependency order (see TRACK B section).

## The iron rules (violating any one invalidates every number you produce)
1. TERMINATION: a scenario ends when it is CORRECTLY MEASURED — including an honest FALSE or a
   tie. You NEVER run "until the graph wins." There is no target result. If you ever notice
   yourself tuning toward a desired outcome, STOP and log it as a discipline breach.
2. GRADIENT DESCENT RUNS ON ENGINEERING EXECUTION ONLY. You may close MCP / render / daemon /
   tooling gaps so execution latency stops masquerading as substrate cost. You may NEVER tune
   the result. If the thesis is true, closing execution gaps reveals it on its own.
3. SUBSTRATE-VS-EXECUTION ATTRIBUTION: any time text beats the graph on any axis, decompose the
   loss BEFORE drawing any conclusion. Was it the MCP round-trip? Babashka startup? Render?
   Recompile? The daemon wire op? Or a real structural cost in the graph algorithm? Only the
   LAST is a statement about the substrate. A slow MCP path reported as "the graph loses" is a
   lie. Every loss is attributed to a named layer or it is not characterized at all.
4. SYMMETRIC ENGINEERING: the text arm always gets its strongest realistic tooling — the real
   clojure-lsp rename, never a strawman find-replace. The test for any fix: would you apply it to
   the arm you hope loses?
5. PRE-REGISTER BEFORE MEASURING: write your prediction to PREREGISTER.md and commit it BEFORE
   you run the numbers. The arrow points prediction -> measurement, never the reverse.
6. DISTRUST THE CLEAN NUMBER: a result you want to be true is the one to re-check. A
   graph-wins-every-column table is the tell to distrust — the graph PAYS latency/compute; if a
   column doesn't show it losing somewhere, suspect the measurement.
7. CLASSIFY EVERY RESULT: measured-with-config / argued / external-evidence. An honest FALSE is
   a result and gets banked like any other.
8. DISK IS TRUTH: read LEDGER.md and PREREGISTER.md FIRST every cycle; write LEDGER.md LAST.
   "What to do next" is a deterministic function of what is committed, reconstructable after any
   compaction.

## The cycle
read LEDGER.md + PREREGISTER.md  ->  pick next corpus scenario  ->  write prediction to
PREREGISTER.md, commit  ->  run harness.sh  ->  attribute every loss to a layer  ->  write
result + per-layer breakdown + classification to LEDGER.md, commit  ->  next scenario.

## TRACK A — corpus grows by PORTING under a fidelity gate (supersedes v1 "no porting")
The v1 "no porting" rule is LIFTED, replaced by a FIDELITY GATE. You MAY port a real Clojure project
(honeysql, hiccup, anything with a real test suite) into Beagle to create a scenario.
- A port is a VALID scenario **IFF the ported `.bclj` passes the ORIGINAL project's OWN test suite.**
- A port that FAILS the original's tests is **REJECTED** — not measured, not banked, not patched into looking
  like it passes. The test-pass gate is the whole reason porting is now safe: it stops a sloppy port from
  silently contaminating the cross-arm comparison. NON-NEGOTIABLE.
- **An upstream Beagle gap that blocks a port JUMPS THE QUEUE — it is the highest-leverage thing you can do,
  not a distraction.** This experiment is Beagle's best stress test: porting real Clojure exercises emit and
  compilation paths nothing else hits (honeysql already surfaced 2 compiler bugs + a multi-arity-fn gap — that
  IS the work). A port that fails the fidelity gate because Beagle can't compile something is a **FINDING**,
  not just a rejected scenario: the honest response is "rejected because Beagle is missing X → go add X," then
  retry the port. An unblocked upstream unblocks every downstream scenario stacked behind it. Behavior-
  preserving workarounds in the port are a stopgap only; the real fix is upstream. Faking a pass is never allowed.
- **PRIORITY: honeysql `util/str` at N≈79** — the one regime where the pre-registered latency question (does
  clojure-lsp's rename climb with N while the graph's O(1) edit stays flat) can actually be answered.
  Pre-register the large-N prediction BEFORE measuring, same as every scenario.

## TRACK B — optimization, live build work, in dependency order
Drive execution-layer cost down toward the substrate floor. Each item changes whether the next is worth it.
1. **In-memory compile path.** Today: graph → render `.bclj` text → parse → typecheck → emit clj. Delete the
   text round-trip: graph → typed-AST-in-memory → emit, never serializing intermediate text. MUST keep the
   type layer (stays the typed arm; a path that emits UNTYPED clj is a DIFFERENT arm, labeled as such, never
   silently substituted). Measure render+reparse (deletable) vs typecheck+emit (the real typing tax) once the
   round-trip is gone. *Coordination:* beagle's parse/emit + fram's graph→AST are both in scope (no
   ownership) — announce the AST-ingest seam in agentchat (read-then-write, poll for the other agent's
   in-flight work, don't clobber) and build it. The DECOMPOSITION measurement (parse vs check vs emit, via
   beagle's existing `syntax`/`check`/`build` sub-commands) is the cheap first step that quantifies the prize.
2. **Incremental typecheck.** Likely highest-leverage (the typing tax is the dominant end-to-end cost and is
   currently rechecking the whole module per edit). Recheck only the changed def + its dependents. *Lane:*
   beagle's to build; ours to measure the opportunity and the after.
3. **Optimistic recompilation, aggressiveness knob** (recompile-every-edit | debounce | on-idle). Speculative;
   hash + hold + serve-instant + prune-if-superseded. **LATENCY-HIDING, not cost reduction:** the warm-hit
   time is a SEPARATE COLUMN from compute cost — reporting a pre-warmed hit as the rename cost is the
   warm-graph-vs-cold-lsp flatter (caught once; do NOT reintroduce). It's the daemon warm-store pattern one
   layer up (fram-side orchestration, ours). Build THIRD — only pays after #1 shrinks the speculative window.

*Coordination on items 1-2 (which touch Beagle):* no ownership. Announce in agentchat, fix it, keep the other
agent in the loop; poll the file for their in-flight work and don't clobber. Beagle gaps the experiment
surfaces are top-priority fixes (they jump the queue), not parked tickets.

## HALT — the ONLY condition now (supersedes v1 "flat curves / exhausted corpus")
You do NOT halt at flat curves or an exhausted pre-existing corpus. Run BOTH tracks until GENUINE diminishing
returns. At that point you do NOT stop on your own authority: write a STATUS block to LEDGER.md (what both
tracks measured/built, the large-N divergence result, the optimization wins per layer, the open question) and
surface it to Tom for the outer-loop call. **Diminishing returns is adjudicated by TOM, not a hardcoded
threshold.** Until then there is ALWAYS a next thing: an un-ported large-N target, an unbuilt optimization, a
re-measurement after an optimization lands, or — first in line — an upstream Beagle gap blocking a port.

## What is NOT yours to decide (reserved for Tom, the outer loop)
The strategic call: have we maxed out the technology, or is there a fundamental rethink the loop
can't reach? You MEASURE. You do NOT pivot the strategy, invent a new thesis, or change the
experiment's frame. If you believe a rethink is needed, write it as the open question in the
SUMMARY and halt — do not act on it.

## Hard constraints (never violate)
- NEVER touch port 7977 or ~/.local/state/lodestar/claims.log (the live lodestar coordinator).
  All runs use isolated /tmp logs + non-7977 ports.
- Work on main; commit straight to main. Do NOT push public main (that needs Tom).
- **No ownership of Beagle (or Fram); the agents COORDINATE.** Before touching Beagle, read
  `~/code/agentchat/agentchat.md` (read-then-write, append-only), say what you're about to change, and poll
  the file so you don't clobber the other agent's in-flight work. On collision (same file, or your change
  would undo theirs), resolve it IN agentchat — don't defer on principle, don't bulldoze. Upstream gaps the
  experiment surfaces: announce, FIX (coordinating if they're mid-flight in the same area), drive to resolved.
  "File it and move on" is gone; "file it, fix it, keep them in the loop" is the rule, and such fixes JUMP THE QUEUE.
- chartroom/resolve.clj is the code under experiment — do not treat it as stable.
