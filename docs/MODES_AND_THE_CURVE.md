# Modes and the Curve — the corrected thesis shape for concurrent authoring

**Status:** Corrected record + experiment plan. Supersedes the framing scattered
across the #11 / #11b / propagation receipts. Companion to
[VIEWS_AND_BRANCHES.md](VIEWS_AND_BRANCHES.md) (the view model) and
[WHY_FRAM_EXISTS.md](WHY_FRAM_EXISTS.md) (the atom). This doc is about the
*concurrent-authoring* claim only.

---

## 0. The correction (read this first)

Three **clean stories** were told about Fram-vs-git on concurrent authoring. All
three are wrong, in different directions:

1. *"Git wins validation compute"* (a separate efficiency axis) — the agent's clean story.
2. *"Git wins nothing"* — the overcorrection.
3. *"Fram accepts incoherence; git is safe"* — the lazy trade story.

The right shape:

> **Git is one fixed operating point. Fram is the curve through that point and beyond.**

Git hard-codes a single tradeoff: *validate the joint state of a change set
before exposing it*, with a *coarse* (whole-file/tree) gate, because text has no
smaller unit of meaning. Fram makes that tradeoff **programmable** — but be exact
about three tiers, or the pitch collapses them (the "headwind" failure mode, one
level up):

- **SHOWN (banked, measured):** Fram occupies points on the curve *past* git's —
  the eager-visibility corner (RAW propagation ~0) and per-claim failure isolation.
  Git's substrate forbids these.
- **ARGUED (architectural, UNBUILT):** Fram can *also* sit at git's own point —
  git-mode (stage → joint-validate → publish, with a view boundary where
  speculative claims aren't visible to ordinary readers). Rests on staging+gating
  being substrate-neutral — true, but **not demonstrated**. The view model is
  *designed* (VIEWS_AND_BRANCHES); the mode is *unbuilt*.
- **SEQUEL (a real build):** Fram sits at git's point *more cheaply* (scoped gate).
  Probe-scoped (§7 Phase 0): YELLOW→RED, a build, not a measurement.

**One-line thesis (talk-safe):** *Git gives you one hard-coded tradeoff; Fram is
designed to make it programmable.* Today: **Fram reaches points git's substrate
forbids — measured; it can in principle occupy git's point too — argued;
undercutting git's own gate is named future work.** NOT "Fram is the whole curve,
measured." Never collapse *shown* and *argued* into one word.

---

## 1. What git's "win" actually is — collapse R1 + #11b into ONE

Git's lone substrate property is: **validate the JOINT state of a change set
before that state is visible to anyone.** It was logged as *two* git wins. It is
**one win seen twice**:

| Face | Receipt | What it shows |
|---|---|---|
| **Compute** | #11 R1 | one joint validation amortized over K edits vs K individual ones |
| **Coherence** | #11b | rejects the individually-valid-but-jointly-incoherent combination |

- The **compute count** (K vs 1) is a **harness artifact**, not a substrate win.
  Fram can batch-validate *behind* eager landing and erase the pure compute
  delta. Log it as a benchmark *configuration asymmetry*, not a git property.
- The **coherence** property is **real and non-erasable**: to validate-before-expose
  you must hold a barrier — and the barrier is exactly what costs propagation.

So git's column is **one entry**: *joint pre-exposure validation, priced in
barrier latency.* Not two. R1 is its compute face; #11b is its coherence face.

---

## 2. Where Fram beats git on git's OWN turf (and the caveat)

Git's gate is **coarse**: it validates whole files/trees. The claim graph **knows
the dependency structure** (which claim references which), so Fram can gate on the
**affected sub-graph** — validate the binding-set that actually changed, not 184
modules because one line moved. *Same guarantee* (no incoherent state exposed),
*cheaper enforcement.*

> ⚠️ **UNMEASURED — the closest proxy is a CONFOUND, not a headwind.** The
> propagation receipt (`cnf_propagation.clj`) measured S3.3 *scoped re-resolve* on
> the real corpus (K=11): it did NOT beat whole-corpus — flat ~2.9–3.4 s across all
> modules (hub `cnf` ≈ leaf `fold`), because `materialize-refers-scoped!` runs
> `corpus-from-store!` over **all K** (a fixed whole-corpus cost) before walking the
> affected modules. **Keep two claims distinct — do not conflate them:**
>
> 1. **About the current derived-read path (REAL finding):** S3.3 scoped re-resolve
>    does not beat whole-corpus at K=11; the fixed `corpus-from-store!` scan
>    dominates. True, and it is the swarm-mode *derived-read* cost.
> 2. **About the cheaper-GATE thesis (UNMEASURED):** re-resolve recomputes *all*
>    `refers_to` edges; a coherence gate checks *reference-validity over the affected
>    subgraph*. Different operations — and the ~3 s floor lives entirely in
>    `corpus-from-store!`, which a coherence gate **need not invoke**. So the proxy
>    is a **confound** for the gate thesis: it measured the wrong operation. The
>    cheaper-gate claim is **unmeasured — not refuted, and NOT a headwind.**
>
> The load-bearing open question, answerable *cheaply before building anything*:
> can a coherence gate read the scope it needs from a **maintained** index (e.g. the
> incrementally-updated name/export indexes, `export-snapshot`) **without** rebuilding
> whole-corpus binding tables via `corpus-from-store!`? If yes → cheaper-gate is
> achievable and the minimal gate receipt is cheap. If no → it needs new incremental
> indexing (a real build). Leg 1 begins with that feasibility probe (§7).

---

## 3. What Fram can do that git's substrate forbids

- **Per-edit immediate visibility** (propagation / swarm-mode): git can't — the
  barrier is load-bearing for git's coherence.
- **Per-edit failure isolation**: one bad edit doesn't poison a batch.
- **Commuting concurrent edits** (C-mode): same-point structural edits converge
  via deterministic ordering keys instead of conflicting (git) / corrupting
  (Fram-today).

**The structural caveat — say it or get caught:** you cannot have *both* extremes
on the *same* edit. Immediate-visibility and validate-before-expose are
genuinely exclusive for a given commit. So Fram does **not** dominate git at a
single operating point. Fram dominates the **space**: it can sit at git's point
(cheaper gate) or move off it, and *per-edit* it can choose. **Git is a point;
Fram is the curve, and the curve passes through git's point.**

---

## 4. The four modes (the map)

| Mode | Visibility | Validation | Proves | Status |
|---|---|---|---|---|
| **Git** | after merge/gate | joint, whole text/tree | coherent but queued | external baseline (banked: `git_append_baseline.sh`) |
| **Fram git-mode** | after graph gate (staging view → main) | joint, **affected subgraph** | same guarantee, hopefully cheaper | **PRIMITIVE DESIGNED (views), MODE NOT BUILT**; cheaper = **MUST MEASURE** |
| **Fram swarm-mode** | before full validation (eager to main) | per-claim / deferred / scoped | fast visibility, failure isolation, concurrency | **partially measured** (propagation receipt = this point) |
| **Fram C-mode** | deterministic ordering keys | structural commutation | same-point edits converge vs conflict/corrupt | hazard + git-baseline **BANKED**; fix = build-and-confirm demo, **DEFERRED** |

---

## 5. Record correction — what each BANKED receipt actually proves

| Receipt | Old label | Corrected label |
|---|---|---|
| #11 R1 | "git wins validation throughput" | git's fixed point, **compute face**; the K-vs-1 count is a config artifact (Fram can batch). Not a standalone git win. |
| #11 R2/R3 | "Fram wins landing latency / isolation" | Fram **swarm-mode** points — what you buy by *leaving* git's corner. One point on the curve git can't reach, not an abstract "Fram wins." |
| #11b | "git wins coupled coherence" | git's fixed point, **coherence face** — the *same* win as R1, other face; the honest cost of swarm-mode (expose-before-validate). |
| #31 locality (`4c6a0bf`) | "Fram structural-position bug" | the **edit-safety** axis at the same insertion point: Fram-today *silently corrupts* (multi-valued fN, no commit-time conflict detection — LIVE). |
| git baseline (`812fc50`) | — | the **edit-safety** axis from git's side: git *conflicts* at the same point (raw AND merge-queue); auto-merges different points. |

#31 + the baseline together are **C-mode's motivation**: at the same insertion
point git conflicts, Fram-today corrupts, C would converge — carrying the #11b
**decoupling tax** (machine-chosen order; see #32).

---

## 6. The honesty ledger (banked vs plausible vs must-measure)

- **BANKED:** swarm-mode propagation split (raw synchronous / derived lazy-on-read);
  the edit-safety same-point hazard on *both* systems; git's joint-coherence win
  (one, real, priced in latency).
- **PLAUSIBLE, NOT BANKED:** Fram **git-mode**. The substrate has a *view model*
  (VIEWS_AND_BRANCHES.md: main = privileged published view) — staging-view vs
  published-view is the natural primitive for stage→gate→publish. But the
  *daemon mode* that stages a batch, joint-validates it, and publishes to main
  only on pass — **with a real view boundary so speculative claims are NOT visible
  to ordinary readers** — is **not built**. Until it is, "Fram can do git's
  guarantee" is *architectural*, not demonstrated.
- **UNMEASURED (the proxy is a confound); Phase-0 probe ran:** that Fram's git-mode
  gate is **cheaper** than git's. The only proxy (S3.3 scoped re-resolve) measured a
  *different operation* dominated by `corpus-from-store!`; not evidence about gate
  cost (§2). The Phase-0 feasibility probe (read-only) then asked "can a gate avoid
  `corpus-from-store!`?" and found: **the cheaper-gate is a real build, not a cheap
  measurement.** The gate's hard part — "does each staged reference resolve *in
  scope*" — needs per-module def-binding/export **frame tables** that are derived
  from AST structure (no `:variant-of`-style predicate; bindings are extracted by
  walking forms) and are **not maintained**. Spectrum: best case **YELLOW** (use the
  existing `*corpus-scope*` hook + import graph to build only the import-closure's
  frames), worst case **RED** (build maintained `exports_of` indexes). Either way a
  real arc. **So cheaper-gate is the *sequel*, not a near-term measurement.**
- **REAL, SEPARATE finding:** the *current derived-read path* (S3.3 scoped
  re-resolve) does not beat whole-corpus at K=11. This bounds swarm-mode's
  derived-read cost; it is not about the gate.

---

## 7. Sequenced experiment plan (one rig: the multi-client daemon harness)

**Leg 2 — swarm-mode point (BANKED): propagation receipt.** `cnf_propagation.clj`,
isolated daemon on a `/tmp` copy of `.fram/code.log` (K=11). *Not "Fram wins" — one
point on the curve, the eager-expose corner git cannot reach.* Results:
- **RAW `:query`: SYNCHRONOUS, ~0.1 ms, 12/12 reflected on the first read** (no
  polling). Eager visibility is real.
- **DERIVED `:callers`/refers_to: LAZY-ON-READ.** First reader after a write pays
  the resolve walk; NO-OP repeats ~0; no background re-resolve.
- **SURPRISE: scoped *re-resolve* did NOT beat whole-corpus** — flat ~2.9–3.4 s
  across all 11 modules (hub `cnf` ≈ leaf `fold`), because `corpus-from-store!` runs
  over all K every time (see §2). This bounds the *derived-read* cost; it is a
  **confound** for the cheaper-*gate* thesis (different operation), which stays
  **unmeasured**, not failed. The measure-first win was catching that distinction
  before it became a false concession.

**Leg 1 — git-mode (the "better, not just different" proof). DO NOT build broad
git-mode infrastructure first.** Split it; the first phase is cheap and decides
whether the rest is worth building.

- **Phase 0 — feasibility probe (cheap, investigation, the fork-decider).** Answer
  ONE question: *can a coherence gate validate the affected subgraph WITHOUT the
  `corpus-from-store!` whole-corpus fixed cost that floored scoped re-resolve?*
  Determine what `corpus-from-store!` builds, why re-resolve needs it, and whether a
  reference-validity check can read scope from a **maintained** index (the
  incrementally-updated name/CNF indexes, `export-snapshot`) instead of rebuilding
  binding tables. **If the gate can avoid the fixed scan → Phase 1/2 are cheap and
  worth it now. If the gate still needs `corpus-from-store!` → STOP and report: the
  better-than-git half needs new incremental indexing (a real build arc), and the
  honest talk closes on the banked "more than git" half.**
- **Phase 1 — design the minimal gate** (only if Phase 0 is green): the exact
  staged↔published view boundary (speculative claims NOT visible to ordinary
  readers), what "joint-validity-on-visibility" means in Fram terms, the
  affected-subgraph input.
- **Phase 2 — smallest receipt**: stage a batch in a non-main view; joint-validate
  the affected subgraph; publish only on pass; prove speculative claims are invisible
  pre-publish; **measure gate time and scope**; include ≥1 leaf and ≥1 hub edit and a
  larger synthetic K (K=11 is too small to show scaling); compare to the merge queue.
  Do NOT claim "cheaper than git" unless the gate demonstrably avoids the fixed scan.

**Phase 0 RESULT (ran, read-only — `86970d4`/this arc):** the cheaper-gate is **a
real build, not a cheap measurement.** The gate's hard part ("does each staged
reference resolve *in scope*") needs per-module def-binding/export frame tables that
are AST-structural and unmaintained (`corpus-from-store!` rebuilds them). Best case
YELLOW (leverage the existing `*corpus-scope*` hook + import graph to scope frame
building to the import-closure); worst case RED (build maintained `exports_of`
indexes). RED-vs-YELLOW doesn't change the near-term decision — both are a real arc.
**Decision: Phase 1/2 DEFERRED. The talk stands on the banked "more than git" half;
the cheaper-gate is a specified sequel** (its first task: resolve RED-vs-YELLOW by
testing whether `*corpus-scope*` can build only the import-closure's frames).

**Leg 3 — the curve (synthesis).** Same workload, sweep Fram from git-mode to
swarm-mode; plot git as a single fixed dot on the coherence↔visibility frontier.
The talk slide: *"Here is git. Here is the curve Fram can occupy. Git is one
point on it; Fram chose to leave."*

**Leg 4 — C-mode (deferred demo).** Tiebroken/actor-id ordering key; same-point
edits converge. Sign is known (build-and-confirm); carries the #11b decoupling
tax (machine-chosen order → could be wrong if `fooB` reads `fooA`). Build only as
a *live demo* after Legs 1–3 (#32).

**Do NOT** build any experiment whose purpose is to *deny git the joint-coherence
win.* It is real and conceded; such an experiment would be a strawman against our
own honest result.

---

## 8. Meta — why "I step away and this happens"

The failure mode: agents and advisors generate a **clean story** when the truth
is a messier trade, and the clean story is seductive in whatever direction you're
already leaning. Three clean stories appeared here, all wrong (§0).

The discipline, applied whenever the substrate looks like it **loses OR wins
cleanly** — ask which of three it is, and refuse any framing that collapses them:

1. a **harness artifact** (erasable — like the K-vs-1 compute count), or
2. a **fixed point of git** (real, priced — like joint-coherence), or
3. a **programmable choice for Fram** (a point on the curve — like eager visibility).

"Git wins compute" collapsed (1) into (2). "Git wins nothing" denied (2). "Fram
accepts incoherence" mistook a *choice* (3) for a *constraint*. "The curve passes
through git's point" collapsed *shown* into *argued*. The correct claim keeps every
tier distinct: git is a point; Fram **demonstrably reaches points past git's**
(shown, §0); occupying git's *own* point is **architectural** (argued, unbuilt);
the **cheaper** gate there is a **sequel build** (§7 Phase 0). Same discipline,
applied to the geometry: never let *shown*, *argued*, and *future* wear one word.

---

## 9. Close-out (research arc ended here) — banked / architectural / non-claims / sequel

**BANKED (measured or code-true):**
- RAW propagation is synchronous (~0.1 ms, first read reflects the write; no
  polling). Eager per-edit visibility is real — a corner git's barrier forbids.
- DERIVED reads are lazy-on-read (reader pays the resolve walk; NO-OP repeats ~0;
  no background re-resolve).
- Same-insertion-point edit-safety: git **conflicts** (raw *and* merge-queue);
  Fram-today **silently corrupts** (`fN` multi-valued, no commit-time conflict
  detection — LIVE). Different-point growth: git auto-merges.
- Git's lone substrate win is **one** thing, two faces (compute + coherence):
  joint pre-exposure validation, priced in barrier latency.
- The *current derived-read* scoped re-resolve does **not** beat whole-corpus at
  K=11 (fixed `corpus-from-store!` scan dominates).

**ARGUED (architectural, true-but-unbuilt — say "in principle"):**
- Fram can occupy git's own point (git-mode: stage → joint-validate → publish with
  a real staging/published view boundary). Substrate-neutral, not demonstrated.

**EXPLICIT NON-CLAIMS (do not assert):**
- NOT "Fram is the whole curve, measured." (Only the past-git half is measured.)
- NOT "Fram's gate is cheaper than git's." (Sequel; probe came back YELLOW→RED.)
- NOT "Fram does git's thing better." (Better-half = the sequel; unbuilt.)
- NOT "dense `fN` over-serializes." (It *under-protects* / corrupts — §5.)
- NOT "scoped re-resolve proves anything about gate cost." (Confound — §2.)

**SEQUEL BACKLOG (not under talk deadline):**
- git-mode gate + the cheaper-gate measurement (#34) — first task: resolve
  RED-vs-YELLOW (can `*corpus-scope*` build only the import-closure's frames?).
- C-mode tiebroken ordering key (#32) — known-positive demo, carries the #11b
  decoupling tax (machine-chosen order).
- add-comment authoring verb (#30) → unblocks schema/lookup honesty note (#28).
- Beagle README (#23) — blocked on org-map facts (firnos→?, chelonia→?,
  gjøa-location; org name = Autonymy is settled).

The arc closes on a **real result**: banked wins, banked holes, three seductive
false stories caught (premise inversion, confound-vs-headwind, shown-vs-argued).
That is research.

---

## 10. Overnight build — Systems 1 & 2 (banked) + the commute decision

The structural-rep fork (§5 C-mode / #32) was **settled to C (commute)** with the
hard rule that *backward-compat is never a decider* — only correctness + the desired
thesis design. With migration deleted from the scale, A (single-valued-reject,
serializing) had no remaining advantage over C (commuting); both meet the
no-silent-misorder bar with the same resolved-`refers_to` guard, and C wins the
thesis. (Memory: `no-backward-compat-axis`.)

**System 1 — commute-mode (BUILT, verified). Commits `8ab3a40`, `44964cd`.**
- **Part 1 — D, atomic append-position allocation.** Implemented as the positional
  analog of the existing `reserve-name-ints!` name allocator: the verb marks a
  top-level append with the `f+append` sentinel; the daemon allocates the real `fN`
  from the parent's live max **under the dlock**. Two concurrent appends get DISTINCT
  positions and **both land** (commute) — no duplicate index. Chosen over the
  tiebroken-key re-key on correctness+risk (mirrors proven code; leaves
  `ordered-children`/`set-body`/render untouched). **R1 receipt** (`cnf_commute_receipt.clj`):
  two concurrent `:edit-min` appends to `kernel` → 43 forms, 43 DISTINCT `fN`, 0
  duplicate positions, both defs present. **#31 (4c6a0bf) CLOSED via commute.**
- **Part 2 — coupling guard (no-silent-misorder bar).** Delta-based forward-ref
  detection over resolved `refers_to`: a reference whose target sits at a higher
  top-level `fN` in the same module is a forward-ref. **R3/R4 receipt**
  (`cnf_gate_receipt.clj`): R4 (coupled bad order, load-time value ref) → NEW
  forward-ref DETECTED; R3 (good order) → none. PASS.
- **MEASURE-FIRST FINDING:** kernel has **39 legitimate pre-existing forward-refs** —
  Beagle functions resolve at *call* time, so forward-ref is NOT a blanket coherence
  error. Hence the guard is **delta-based** (flag only what an edit introduces). The
  coupling-misorder hazard the bar worried about is therefore **NARROW**: it bites
  only load-time *value* mutual-refs; FUNCTION couplings commute safely in any order.
  So D's commute is safe for the common case, and the residual load-time case is
  **detectable (not silent)**. Caveat: the delta check is conservative (would also
  flag a legitimate function forward-ref an edit adds) — load-time-value-only
  refinement is future work; it is NOT wired inline-reject (that would reject valid
  function forward-refs) — it is a detector/gate-core, the correct choice.

**System 2 — the gate-core (BANKED) = the same delta resolved-`refers_to` coherence
check.** It validates references over the affected subgraph without rendering
graph→Clojure (the gate is pure-graph; materialization is paid only to RUN code).
The full staging↔published *view boundary* + publish-on-pass ceremony is the
production wrapper (documented, not the substance); the substance — the scoped
coherence check + its cost — is what System 3 measures.

**System 3 — K-sweep (the cheaper-than-git measurement):** see §11 below.

---

## 11. System 3 — gate cost vs corpus size K (MEASURED, decomposed)

`cnf_ksweep.clj` + `ksweep_run.sh`, over real-module SUBSET corpora (K∈{2,4,8,11}
ingested from the `.bclj` sources). All times ms; materialization (frame build) kept
SEPARATE from coordination (coherence scan); the unscopable `by-p NAME` floor isolated.

| K | claims | scan_floor (O K) | whole_frame (MAT, O K) | scoped_frame (MAT) | coh_scan (COORD, whole) | fwd_refs |
|---|---|---|---|---|---|---|
| 2  | 28060  | 3  | 107 | 33 | 142 | 16 |
| 4  | 34453  | 5  | 149 | 38 | 278 | 19 |
| 8  | 83065  | 14 | 517 | 82 | 643 | 27 |
| 11 | 102371 | 14 | 595 | 92 | 775 | 50 |

**What's measured and true:**
- **Scoped materialization beats whole, and the advantage WIDENS with K.** scoped/whole
  frame = 0.31 → 0.26 → 0.16 → 0.15 (3.2× → 6.5× cheaper). The saving is exactly the
  unaffected modules' frame builds: 74 → 503 ms, growing O(K). This is the substrate
  win text can't have: git's coarse gate must re-validate the whole tree (O total code,
  no smaller unit); Fram scopes the materialization to the affected module.
- **Coordination is O(K) too** (coh_scan 142→775), tracking claims/edges.
- **Decomposition honored** (the materialization-vs-coordination flag): frame-build and
  coherence-scan are reported as separate numbers — neither folded into the other — and
  *neither includes a graph→Clojure render* (the gate is pure-graph; that materialization
  is paid only to RUN code, never to gate).

**What's NOT yet measured (honest gaps — do not overclaim):**
- **coh_scan was measured WHOLE-only.** I scoped the *materialization* half (frames), not
  the *coordination* half (the coherence scan still does `parent-slot-index` over all
  claims + all `refers_to` edges = O(K)). A scoped coherence scan (restrict to the
  affected module's edges) is the remaining measurement to fully earn "scoped gate
  cheaper." So **"cheaper than git" is PARTIALLY earned: materialization yes-and-widening;
  coordination-scoping unmeasured.**
- **Scoped is not flat.** An O(K) name-grouping floor remains (`by-p NAME`, scan_floor
  3→14, plus the coh_scan's whole-claims scan). A *maintained* name/export index would
  remove it; that index is the same "real build" the §7 Phase-0 probe flagged (YELLOW).
- I did **not** run git's compiler head-to-head. git's coarse gate is O(total code) *by
  construction* (text has no sub-file unit); Fram's WHOLE gate is the O(K) analog and the
  SCOPED materialization is the sub-O(K) win. The architectural class is measured; a
  compiler-vs-gate wall-clock is not (and would compare tools, not the substrate point).

---

## 12. SCOREBOARD (take it apart)

Status legend: **MEASURED** (number, this arc) · **BANKED** (named, real) · **ARGUED**
(architectural, unbuilt) · **GAP** (not yet measured) · **LIMIT** (known cost/worse).

| Claim | Status | Number / evidence |
|---|---|---|
| Eager per-edit visibility (RAW read) — git can't | **MEASURED** | ~0.1 ms, synchronous, 12/12 reflected on first read (`cnf_propagation`, `9ccf5a7`) |
| Derived reads lazy-on-read (reader pays) | **MEASURED** | NO-OP ~0; first-read re-resolve cost (`cnf_propagation`) |
| Concurrent same-point appends **COMMUTE** — git can't | **MEASURED** | R1: 2 concurrent → distinct `fN`, 0 duplicate, both land (`8ab3a40`) |
| #31 duplicate-index corruption **closed** | **MEASURED** | was LIVE (`4c6a0bf`) → R1 PASS |
| Coupling-misorder **surfaced, not silent** | **MEASURED** | R4 detected / R3 clean (`44964cd`); hazard NARROW (load-time value mutual-refs only; functions commute) |
| Same insertion point: **git conflicts** (raw + queue) | **MEASURED** | `git_append_baseline` (`812fc50`) |
| Git's joint-coherence (green-main) win | **BANKED** | `#11b` — git wins, priced in barrier latency |
| Scoped gate **materialization** cheaper than whole, widening with K | **MEASURED** | K-sweep: 3.2×→6.5×; saving 74→503 ms (`§11`) |
| Scoped gate **coordination** cheaper than whole | **GAP** | coh_scan measured whole-only (O K); scoping it = next measurement |
| Fram occupies git's point (git-mode) | **ARGUED** | gate-CORE built (delta coherence check); staging↔published view-boundary + publish = unbuilt production wrapper |
| **"Cheaper than git" gate (full)** | **PARTIAL** | materialization earned + widening; coordination-scoping unmeasured |
| O(K) name-grouping floor under scoping | **LIMIT** | scan_floor + whole-claims coh scan; a maintained index removes it (same YELLOW build, §7) |
| Full insert-anywhere commute (fractional-CRDT keys) | **ARGUED** | append-commute built (D); middle-insert commute = documented generalization |

**Net (no top-to-bottom "Fram wins"):** Fram **measurably reaches modes git's
substrate forbids** — eager visibility, commuting concurrent appends, per-claim
granularity — and **closed the live #31 corruption** while keeping commute. On git's
own turf (the coherence gate), Fram **scopes the materialization sublinearly and the
advantage widens with K**, but the coordination-scoping and the staging view-boundary
are **not yet built/measured**, so "does git's thing cheaper, end to end" is **partially
earned, not banked**. Git still wins continuously-green-main (a real, priced trade).
The qualified claim is the honest one — and it is strong: *git is one fixed point; Fram
is demonstrably more of the curve than last night, with the cheaper-gate half scoped and
its remaining measurement named.*
