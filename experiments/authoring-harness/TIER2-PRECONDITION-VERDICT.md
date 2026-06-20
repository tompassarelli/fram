# Tier-2 precondition — VERDICT (measured in-code, 2026-06-20)

**Question (advisor 2026-06-20):** a measured Tier-2 gap needs clojure-lsp to MISS a reference AND Fram's
`refers_to` to CATCH it. *Does Fram's walk cover any reference class lsp misses?* Verified directly from the
resolver source — a 3-probe, read-only investigation (workflow `wa1so0ko3`): (1) Fram's walk mechanism, (2)
clojure-lsp/clj-kondo coverage, (3) candidate dynamic-ref classes with local corpora.

## VERDICT: NO. Fram's current graph shares clojure-lsp's blind spots exactly. **(b) is a BUILD, not a pick.**

### 1. Fram's `refers_to` is a purely LEXICAL-SURFACE walk
- **materialization_source = lexical-surface-AST.** The AST claims feeding the resolver come from
  `read-beagle-syntax` → `syntax->datum` (the parsed reader datum); there is **no macroexpand step** anywhere
  in the projection or resolver (`claims-roundtrip.rkt:502-505`, `parse.rkt:282`).
- **sees_macros = NO.** `walk` dispatches only on node kind `"symbol"`/`"list"` (`resolve.clj:313-344`); a
  symbol introduced only by macroexpansion never gets an edge. The defmacro path merely widens the dirty set,
  never expands ("dormant in fram, which has zero defmacro" — `resolve.clj:773`).
- **sees_keyword_dispatch = NO.** Keywords mint inert leaf nodes (`resolve.clj:868`); `walk` has no keyword
  arm → no edge. Zero machinery for multimethod / re-frame / integrant / malli / spec keyword registries. The
  sole edge-writer `bind!` (`resolve.clj:260`) always targets a *binding node* (var/param/type/accessor),
  never a keyword- or string-named handler.
- **Edges it DOES carry:** local-binding, module-local def, cross-module `:refer/:as/:rename`, type refs,
  `->Name`/`map->Name` ctors, record field-accessors, comment-identifier mentions, + the durable `bound_to`
  identity edge. **All are symbol / var / type references over surface syntax** — i.e. exactly clj-kondo's domain.

### 2. clojure-lsp (clj-kondo) — complete on static vars, misses 4 classes
Verified empirically against installed clj-kondo 2026.01.19 / clojure-lsp 2025.11.28 (probe `:analysis` EDN):
- **COMPLETE:** static `def`/`defn` var refs (incl. the honeysql `util/str` 79-caller case → completeness
  **TIE**), locals, ns-aliases.
- **STRUCTURALLY MISSES:** (a) macroexpansion-generated refs (sees the macro *call*, not its expansion);
  (b) multimethod keyword dispatch (dispatch values aren't vars; keyword is a bare token, no edge to handler);
  (c) keyword-keyed registries — re-frame / integrant / malli / spec (handler-naming keyword and invocation
  keyword are unlinked tokens); (d) reflective / string-built — `resolve`/`intern`/`ns-resolve`/`(symbol (str
  …))` (var name is runtime data). Root cause: a static source-form analyzer with no user-macro execution, no
  registry model, no value-flow.

### 3. The cross-reference — the load-bearing table
For EVERY candidate reference class lsp misses, a lexical walk (= Fram's current graph) **ALSO misses it**:

| class | lsp misses | lexical-walk (Fram now) misses | local corpus |
|---|---|---|---|
| multimethod keyword dispatch | yes | **yes** | datahike store/connector/writer; cljs compiler `emit*` |
| aero reader-tag / class dispatch | yes | **yes** | biff config; datahike `print-method` |
| malli registry keys | yes | **yes** | datahike `api/types` ↔ `specification` |
| clojure.spec `::keys` | yes | **yes** | cljs `spec.alpha` (engine) |
| namespaced context-map keys (integrant-shaped) | yes | **yes** | biff `impl/*` |
| macro-generated def names | yes | **yes** | datahike `emit-api`; electric `e/defn` |
| reflective / string-built | yes | **yes** (everyone) | datahike / biff / cljs |

⇒ **No class where lsp misses but Fram catches today. No free measured gap.** (No re-frame Clojure source
exists locally; no integrant `init-key` either — biff uses keyword-keyed context maps. datahike is the
richest local corpus for multimethods + macro-generated API + malli + reflective.)

## What this means (honest)
- Fram's **Tier-1 structural guarantee is real, but its SCOPE = exactly the reference classes a lexical walk
  sees = the classes clojure-lsp already handles with full completeness.** On those classes the graph's win is
  "**by-construction vs must-verify**," NOT "catches something lsp misses."
- The Tier-2 "**graph catches references text misses**" claim is **NOT available as-built.** It requires
  materializing edges *beyond* the lexical walk. The discipline worked: we measured before asserting and found
  the advantage narrower than the loose framing implied — exactly the ceiling the advisor predicted.

## The reframe that matters: ANALYZER vs SUBSTRATE
The finding sharpens, not deflates, the thesis — by separating two questions the loose Tier-2 framing conflated:
- **ANALYZER question** — "can you *compute* a dynamic edge from source?" Here **graph == lsp** (both lexical;
  neither computes a keyword-dispatch or macro-generated edge). This is *not* the addressing thesis.
- **SUBSTRATE / addressing question** — "once an edge *exists*, can you record it as durable IDENTITY and
  propagate a rename through it uniformly?" Here the **graph wins by construction**: a dispatch/registry edge is
  just another `refers_to`/`bound_to` claim; **text has no slot** for a non-lexical edge and must re-derive from
  spelling on every rename. This *is* the addressing thesis.

So the cleanest Tier-2 demonstration **isolates the substrate**: reify ONE dynamic edge (keyword → defmethod),
rename, show uniform propagation; lsp can't, because the edge is invisible to static text **and** it has
nowhere durable to keep it.

## (b) build options + price (this becomes the NAMED Tier-2 plan)
1. **Minimal substrate demo (cheapest, purest, ~hours).** Hand-assert a `bound_to` claim linking a dynamic
   ref (a multimethod dispatch-keyword use-site) to its `defmethod`, rename, show propagation vs lsp's miss.
   Isolates substrate from analyzer. Risk: a skeptic calls the hand-authored edge "cheating" → frame as
   "the substrate has a *slot* for the edge; text has none — *who* authors it is orthogonal."
2. **Keyword-dispatch materialization pass (~1–2 days).** Extend the resolver to emit a `dispatch-key →
   defmethod` edge. General, real. Corpus: datahike multimethods.
3. **Expand-then-index (macro-generated defs).** Larger — needs an expansion phase fram deliberately lacks.
- **Symmetric-engineering check (make it either way):** clj-kondo has a *hooks* system (per-macro, hand-written,
  re-derives each run) + community re-frame configs. So the fair claim is "**graph: one uniform durable slot**"
  vs "**lsp: per-pattern hook + recompute**," and the target that no static tool can win even with hooks is a
  **runtime-computed dispatch** (value/registration computed at runtime) — only a substrate with a slot can carry it.

## Bottom line for the proposal
**Wednesday:** name this plan (reify-one-dynamic-edge demo on datahike multimethods). Tier-1 + N=1 + the latency
tradeoff + this named plan = honest and complete. **October:** do build option 1 (and/or 2), measure. **Do NOT
claim a measured Tier-2 before the edge is reified.**
