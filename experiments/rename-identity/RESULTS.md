# References carry identity, not spelling

A code reference can store the **identity** of what it points at (a stable node id) or
its **spelling** (a name string). If it stores identity, renaming a definition is *one
edit* — every reference re-points — **and this holds even for references that do not
survive compilation to the target language.** A tool that reads the compiled target is
structurally blind to those references, however good it is. This file demonstrates that,
and `reproduce.sh` lets you watch a compiler — not us — confirm it.

---

## Read this first: what this is NOT

We did **not** beat `clojure-lsp` at analyzing Clojure. clojure-lsp is excellent at its
job. A *native* Clojure project that expresses these type relationships (clojure.spec,
malli) keeps them in the source clojure-lsp reads, and it chases them fine.

The claim is narrower and more general: **type-level references are erased at lowering.**
Beagle's `(defn q [xs :- (Vec Claim)] ...)` compiles to Clojure with the `(Vec Claim)`
annotation **gone** — it has no surface form in the target. *Any* tool reading the
**target** is blind to it, however capable. That is not a Clojure-tooling deficiency and
not a Beagle quirk — it is what happens to **TypeScript generics lowered to JS**, to
**Rust trait bounds / lifetimes**, to any reference that does not survive lowering. The
graph holds those references across the gap because it operates on the *source identity*,
not the lowered text. Concede the narrow thing (an LSP on the *source* language sees
these); hold the real thing (references erased at lowering are invisible to anything
reading the lowered form).

## The phenomenon (the headline)

In the claim graph, every reference carries `refers_to <binding-node-id>` — the
*identity* of its binding, resolved by a scope-correct lexical walk, not its name. So
renaming a definition supersedes **one** claim (the binding's name); every reference
renders the binding's *current* name and re-points for free, exactly under shadowing.
This is the same idea as **Unison's content-addressed definitions** and **JetBrains MPS's
node-id references** (names are metadata; text is a view). It generalizes to any language
with names that don't survive lowering.

## The demonstration (Beagle — because it has a recompile oracle)

Rename the record type `Claim` → `Datum` across `fram/src` (11 modules):

| | references re-pointed | recompiles? |
|---|---|---|
| **Claim graph** (one identity edit) | **70 references** (71 `Datum` tokens incl. the new definition) | **`11 built, 0 error(s)`** |
| **clojure-lsp** (best shot, on emitted `.clj`) | renames the value refs present in the target — **0 of the type-annotation references** (59 in source → **0** survive to emitted `.clj`) | n/a (renames the lowered layer) |

On the `.bclj` **source**, clojure-lsp/clj-kondo **parse-fail** (`(def x :- T v)` is a
4-arg `def`) and are *destructive* — they rename a return type but miss the parameter type
on the same line.

## The number, in proportion

**70-vs-12 is an illustration, not the headline.** The magnitude — 59 erased type-annotation
references — is inflated by Beagle specifically: its types are expressive enough (`(Vec Claim)`) that
the target (Clojure) structurally cannot carry them, so the erased-reference set is large.
A skeptic attacking *the size of the gap* is right. The claim is the **phenomenon**
(identity-not-spelling; references survive lowering *in the graph*), which is true
regardless of magnitude. Cite 70-vs-12 as the cleanest instance; never as the claim.

## The oracle is the verification — not "trust me"

Correctness here is checked by **the Beagle compiler**, not by us. The graph's complete
rename recompiles `11 built, 0 error(s)`. Revert **one** type-annotation reference (an
incomplete rename) and the compiler rejects it —
`call to k/q-by-l: arg 1 expected (Vec Datum), got (Vec k/Claim)`, build fails. So
clean-recompile genuinely *discriminates* correct from incorrect — it is not "the graph
differs from the LSP's wrong answer," it is "the compiler says the graph's answer is right
and the incomplete one is wrong." `reproduce.sh` runs both so you watch the oracle fire.

## Scope — what this does NOT show (tier-1, read/edit-side)

The graph **transports** type references; it does **not understand** types — type-checking
is delegated to Beagle's checker (the recompile oracle). And the actual thesis —
**tier-2: many agents authoring one codebase concurrently through a live graph beats
file-based editing** — has **zero numbers here and is still owed.** This is the read-side
advantage, verified and honestly bounded. The write-side is untouched.

## Reproduce

```
bash reproduce.sh
```

Needs `racket`, `babashka`, Beagle (`BEAGLE=~/code/beagle`), and `clojure-lsp` (the
baseline; the oracle runs without it). You will watch the compiler report `0 error(s)` on
the graph's complete rename and `1 error(s)` on an incomplete one — the verification,
firing in front of you.
