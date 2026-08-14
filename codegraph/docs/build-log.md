# Build log

Chartroom was built in stages ("turtles"), each one ending at a falsifiable
result before the next began. Measured numbers live in
[RESULTS.md](../RESULTS.md); this is the stage-by-stage narrative.

Syntax note: the receipts below preserve their historical Beagle spelling.
Current bindings are structural per entry—`[x]`, `[(x T)]`, or
`[x (y T constraint)]`—and a return type follows the parameter vector. Flattened
adjacent metadata such as `[(x T) constraint]` is invalid.

- **Turtle #1** — `emit-claims` backend in beagle. ✅ landed on beagle `main`.
- **Turtle #2** — load → Fram → leverage benchmark. ✅ bet **validated** (RESULTS.md).
- **Turtle #3** — source-of-truth round-trip gate. ✅ **semantic** round-trip
  (datum-identical), **1100/1100 forms, 97/97 files**, through a real Fram store,
  with an idempotent formatter. Comments/whitespace are *not* preserved (a view,
  not the bytes); byte-identity holds only on already-canonical source (RESULTS.md).
- **Turtle #4** — graph-native authoring. ✅ rename as an occurrence-aware
  proposition edit, with each
  load-bearing claim **falsified before resting on it**: the adversarial trap
  (`red` vs substring `red-zone` vs string `"red"` — graph touches only the
  symbol; even `\bred\b` sed corrupts the rest), withdrawal verified against an
  exact assertion occurrence (old and new proposition content remains in
  history), and faithfulness *across* the mutation (mutated tree == original
  with exactly the rename, nothing else).
  An adversarial syntax sweep found + fixed 2 projector assassins (`#{}`, `#""`).
- **Turtle #5** — from representing to **enforcing**. ✅ The graph now refuses what
  text can't see. A real **lexical resolver** (`resolve.clj`) adds `refers_to`
  identity edges; the load-bearing **shadowing** falsifier passes (a `let`-shadowed
  `red` is a different node than the top-level def, so rename touches exactly the
  right one — `sed` can't). It retires three problems at once, each falsified:
  rename is **O(1)** (1 claim), shadow-correct (`let`/params/`{:keys}`/`for`),
  **cross-module** through `:refer`/`:as`/`:rename`/**re-export chains**, and
  **type-aware** (rename a `defrecord`, its structural binding type children
  follow; the historical corpus spelled these `:- T`); collision
  refusal is **exact**, orphaned-ref-on-delete is **refused**; non-corrupting
  (49/49 src/gjoa projection-identity). **The engine exists, breadth included.**
  Final three, three outcomes: syntax-quote **CLOSED** (`,x` escapes, quoted data
  preserved); type-checking **DELEGATED** to `beagle check` via `bin/safe-rename`
  (consults a type oracle, fails closed — the graph still does *not* understand
  types); macro-introduced bindings **BOUNDED** (surface-opaque by design;
  through-macro refactoring is out of scope — it needs expansion-projection).
- **Turtle #6** — comments as **resolved references**. ✅ The dropped-comment caveat,
  lifted: a comment is captured below the reader (by srcloc, **no reader change**),
  tokenized into text + symbol segments, and a mention that resolves carries
  `refers_to` — so it **renames scope-correctly** while substrings (`red-zone`) and
  quoted `"red"` do not. The Turtle #5 identity machinery generalizes for **zero**
  rename-core change. Falsifier `test/comment-falsifier.sh` (10/10); render is a
  fixpoint *with* comments; the **1100/1100, 97/97** datum gate stays green (comments
  live outside the datum). Scope: line comments, top-level (block `#| |#` and in-form
  comments are follow-ups); layout reflows; unresolved prose stays verbatim (and is
  staleness-*flaggable* because the graph knows the identities).
