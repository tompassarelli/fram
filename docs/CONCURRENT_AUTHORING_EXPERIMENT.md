# Concurrent Authoring Experiment — Pre-Registered Protocol (v2, red-teamed)

**Status: DRAFT for sign-off. Freeze + commit before any agent runs.**

v1 claimed "Fram wins concurrent top-level authoring." A red-team gutted it: run fairly,
that claim **ties**, because a competent rebasing git queue merges concurrent append/insert
clean and both arms are compile+test gated. v2 is the honest, hostile-proof reframe:

> **Per-operation decomposition, measured by RECONSTRUCTION COST.** Append/insert **tie** a
> competent git queue (pre-conceded). Fram **separates** exactly where text *structurally
> must* rework — concurrent **rename-of-a-referenced-def** and **coupled mutual-refs** — and
> we measure *how much rework*, not who finishes.

This matches the project's own `MODES_AND_THE_CURVE.md`: *"Fram dominates the space, not a
single operating point."* A room can't gut "we win exactly here, tie exactly there, and the
where is principled." It guts "we win everything." Report every row whichever way it falls.

---

## 0. PRECONDITIONS (gate — verify before any run)

1. **`FRAM_FLIP=1`, graph-canonical.** Falsifiable check (from `experiments/THESIS.md`):
   **delete the `.bclj` and the module still resolves + compiles from the log alone.** If the
   run is text-canonical (`route-edit-text`, FLIP off), it tests *graphify*, not the thesis
   (`experiments/ARCHIVE-INVALID/INVALID.md`). Do not run until "no `.bclj`" is green.
2. **Pin the exact Arm-F interface** agents drive, in writing. Today the MCP surface is
   `{add-def, set-body, rename-def}` — it has **no `insert-form-:after`**, so the
   insert-anywhere commute H1 is about is unreachable through MCP; it fires only through the
   raw `:edit-min` wire op. **Decision required:** either (a) add an `insert-form-:after` MCP
   verb so commute is agent-reachable, or (b) declare agents drive raw wire ops and **drop the
   "equal-fluency agents" framing** — hand-written f-path ties are a best-case harness, not a
   fluent agent. Pick one and record it; the comparison is uninterpretable otherwise.
3. **Apparatus exists and runs.** Before freeze, the LLM-agent harness and the steelman git
   queue (§2) must exist and pass an end-to-end smoke (R=1, no scoring). Freeze over an
   apparatus that demonstrably runs, not an aspirational one. (Today: neither exists — the
   "concurrency" receipts are Clojure futures / a discrete-event sim, not real agents.)

---

## 1. Claims under test (layer two)

Layer one (banked, isolated): commute, O(1) identity rename + the snapshot-window race fix
(`cnf_rename_race_receipt`), warm reads, scoped gate. Layer two (unproven): do real agents
*use* these to lower reconstruction cost under concurrency, per operation.

- **Claim A (safety):** under concurrent load, neither arm SHIPS incoherent code (both gated)
  — but their *failure modes* differ (git fails closed: a conflict blocks; dense-fN/coupled
  Fram can fail *open*: commits a compiling-but-misordered coupled state, only *detected*).
- **Claim B (the real one — reconstruction cost):** for the operations where text must
  rework, Fram's agents pay **less reconstruction work** (collisions + retries-to-green +
  re-reads/tool-calls per agent) than the steelman git queue. This is the project's tier-2
  thesis (reasoning cost = reconstruction work, NOT wall-clock, NOT completion-rate).

**Falsifier (pre-committed):** if, at the flipped substrate with concurrency-calibrated
agents, Fram shows **no reconstruction-cost advantage on rename-of-ref-def or coupled refs**,
the top-level-CRDT-coordination claim is **not supported.** Say so.

---

## 2. Arms — identical task, agents, N; the git arm is a STEELMAN

**Arm G (git) must be git at its competent best**, published alongside this doc for audit:
- branch/worktree per agent; an **N-agent merge-queue** (not a single rebase) that **rebases
  each branch onto latest-green, runs the frozen test-gate, retries on conflict**;
- **pull-rebase-before-edit**; **formatter-normalized** files; a **structure-aware
  `.gitattributes` merge driver**; **def/file ownership partitioning** (the honest hard case
  for Fram to beat);
- a **real ~30-form module with realistic whitespace** (git's diff3 best case — NOT the
  3-line-gap `git_append_baseline.sh` synthetic, which is a strawman and is retired here).
- Drop the no-VCS LWW lost-update sub-mode — a competent git workflow never hits it.

**Arm F (Fram):** warm daemon (non-7977, /tmp log), the pinned interface from §0.2,
`bound_to` identity, query surface.

Pre-run Arm G per operation and record git's per-op collision rate as the **declared null**
(expectation: ~0 on append/insert — that's *why* those tie, and conceding it up front is
what makes the rename/coupled win credible).

---

## 3. Task — per-operation, TOP-LEVEL by construction

One real ~30-form module. The measured task decomposes into **separately-scored operations**,
each run concurrently across agents:

- **append / insert-at-anchor** — each agent adds a distinct top-level def. *(Pre-conceded
  likely tie; reported anyway.)*
- **rename-of-a-referenced-def** — one agent renames a def others reference; tests the
  identity layer + the snapshot-window fix under real agents.
- **coupled mutual-refs** — two agents add forms with a load-time value mutual reference
  (the case git's diff3 can't order and Fram commutes-but-must-detect).

**Excluded by construction:** concurrent **in-body** edits of the same def (CRDT covers
top-level only — `[[crdt-ordering-scope-top-level-only]]`) **and** concurrent **reorder** of
top-level forms (no reorder verb exists; two reorders contend on the same ordering keys —
outside the insert-distinct-position commute). If a realistic task *needs* either, that is a
**recorded finding** ("in-body / reorder concurrency is the next required arc"), **not** a
free pass and **never** in a headline number — it is evidence top-level-only coverage is
*insufficient*, reported as a limitation.

---

## 4. Confound control — concurrent calibration, with teeth

Solo fluency ≠ fluency-under-concurrency (OCC-reject-then-re-author, conflict-retry, reading
freshness mid-flight) — and *that* is the skill the measured task uses, where git agents have
rich priors and Fram's young verbs do not.

1. **Concurrent calibration gate:** before scoring, each agent passes a **2-agent concurrent
   micro-task forcing ≥1 reject/retry cycle** on **each** arm. Only agents passing on both
   proceed. (The prior-art sweep on a familiar authoring surface, if adopted, *shrinks* this
   confound — fold its recommendation in here.)
2. **Equal materials:** parity cheatsheets both arms; counterbalanced arm order; same model +
   scaffold.
3. **Declared live confounds:** record which `AGENT_INTERFACE.md` frictions are live during
   the run (no status tool, `callers-of` = whole-corpus scan, etc. — several mapped-not-built)
   so a Fram result is interpreted against a *named*, not hidden, interface maturity.

---

## 5. Metric — reconstruction cost, frozen

- **Primary (Claim B):** per-agent **reconstruction work** = collisions + retries-to-green +
  re-reads/tool-calls/backtracks (non-gameable primitive **counts**, never wall-clock).
- **Coherent** ≝ compiles AND a **frozen, content-hashed, blind-authored** test suite passes
  — AND, for Arm F, **only after the coupling/forward-ref detector** (`cnf_gate_receipt`) runs
  on the integrated result (so "compiles" doesn't launder Fram's expose-then-detect cost into
  a win).
- **Collide**, split by failure MODE: *(G)* conflict-requiring-human vs auto-resolved-retry;
  *(F)* rejected-edit (loud/safe) vs test-caught-incoherence (silent corruption the gate
  caught) vs lost-update. A loud safe stop ≠ a silent corruption — do not equate them.
- **Δ + power:** set Δ (smallest reconstruction-cost gap that changes the talk's claim)
  **concretely in this file at freeze.** Choose ONE: (a) **existence-proof / case-study** —
  drop the CI rule, publish the per-rep table as demonstrations; or (b) **rate claim** with
  **R ≥ 25 PAIRED reps** (identical scenario seed across arms), McNemar / exact paired
  binomial, N as a random effect. (R≥3 + "CI excludes 0" is incoherent — it defaults to tie
  by underpowering.)

---

## 6. Run plan

N = 3–5 agents; R per §5(Δ). Per-operation table, **all rows published** even when
append/insert tie. Isolation: Fram /tmp log + non-7977 port; git throwaway clone; **never**
7977 / canonical `claims.log`. No rep dropped.

---

## 7. Pre-committed interpretation

- **Fram separates on rename/coupled** (lower reconstruction cost, at equal fluency) → the
  talk: *"text ties on the easy cases; the graph separates precisely where text structurally
  must rework — measured."*
- **Tie even there** → falsifier (§1) fires: the coordination claim is not supported at this
  scale; honest, points at interface/next-arc.
- **Fram loses** → diagnose (substrate vs young interface vs uncovered case); reported.

## 8. NOT claimed
Not in-body or reorder concurrency. Not content-addressing (b). Not "agents better" in
general. Only: **per-operation reconstruction cost, concurrent, top-level, this module,
this scale** — append/insert tie a competent git queue; the graph's edge is rename-of-ref-def
and coupled mutual-refs.

---
*Prerequisites before this can run: (i) the LLM-agent harness, (ii) the steelman merge-queue
(§2), (iii) the §0.2 interface decision (+ `insert-form-:after` MCP verb if MCP). All three
are unbuilt today. The snapshot-window race fix (§1) is done + receipted.*
