# Beagle dogfooding — findings (gap list)

A real artifact, not scar tissue. The discipline: when typing dynamic data forces a
contortion, write it down here as "Beagle lacks X" — don't silently absorb it as
`Any` in the code. (Policy lives in the global beagle-authoring skill, §4.)

The probe that matters is **not** "does interop compile?" (it does, trivially) — it's
**"can I replace this `Any` with a real type, or does Beagle force me back to `Any`?"**

## How this list was built

Two passes:
1. **Small probes** (reachability dedup, the gateway gatepolicy module). Found **no** gaps —
   fn-types, records, typed maps, `Float` all expressed cleanly; the `Any` was author laziness.
2. **The engine-typing pass** — typed the *whole* Fram core (store/schema/datalog/query/tools/main,
   ~141 `:- Any` sites). **106 sites took real types; 35 remain justified boundaries.** Typing at
   that scale is what surfaced the real gaps below — and fixed a latent `schema/value-id` nil bug
   that `Any` had been masking (it's genuinely `Int?`; now guarded before the index lookups).

So: Beagle's coverage is strong for *leaf* types and records, and the bail-to-`Any` reflex is
usually laziness — but typing a real interpreter end-to-end hits genuine, recordable limits.

## Status (2026-06-17) — the loop is closing

These gaps don't just sit in a doc; they're being fixed in the Beagle compiler itself:
- **G5 (enforce `defenum`) — SHIPPED** (beagle `74d3eab`). A non-member keyword is now a
  type error at constructor fields, defn args, and `=`/`not=`. Full suite green; fram
  recompiles clean. (Imported-enum + bare-return enforcement = noted follow-ups.)
- **G1 (`defalias`) — SHIPPED** (beagle `d9bb91e`). `(defalias Name <type>)` resolves at
  `parse-type`; purely additive (a meta-form, zero emit/checker change). File-local v1;
  cross-module export is a v2 follow-up.
- **G4 (kw-access slice) — SHIPPED** (beagle `b39b885`). `(:kw v)` over a record-union now
  discriminates by key (the `{:ok}|{:error}` win) instead of degrading to `Any`, and is
  **nil-correct**: non-null only when *every* member declares the key, else `(U field Nil)`.
  An adversarial soundness review **rejected** the first attempt (it dropped `nil` on partial
  unions, and narrowed a map-pattern var the emitter never binds) — both fixed (nil-correct
  kept; the map-pattern narrowing dropped). **Deferred**: map-pattern *destructuring*
  narrowing + match exhaustiveness over record-unions (need emit to bind the pattern var
  first); full row-polymorphism (the multi-week half).
- **G7 (`for`/`doseq` `:- T`) — SHIPPED** (beagle `a348b6a`). Parity with `loop`; the
  annotation is honored (binds the declared type, not `Any`).
- **G2 (`(Atom T)`) — SHIPPED SOUND** (beagle `4583c87` + fram `aa0db5f`). The naive impl was
  caught unsound *twice* (covariant mutable cell — the array-covariance poison hole). The
  shipped fix makes `(Atom T)` **invariant**: a dedicated `type-invariant-equal?` compares the
  element where `Any` is **not** a wildcard (so the hole stays closed) but prim names unqualify
  (`Store ≡ t/Store`). `atom`/`deref`/`reset!`/`swap!` are poly-typed (swap!'s fn-union enforces
  the fn returns the element type). fram adopted uniform `(Atom Store)` — `deref ctx` reads
  `Store` precisely, the `let`-recovery is gone, all 18 suites green. The poison probe now errors
  *both* directions; 5 new beagle tests lock it in (suite 1394/1394).
- **G6 — SUBSUMED, no separate fix.** The `Any`-assignability hole is inherent to `Any`
  (assignable to anything); the only remedy is reducing *upstream* `Any`, which G1/G3/G4 do.
  Documented, not a code change.
- **G3 (heterogeneous tuple `HVec`) — SHIPPED** (beagle `ecc853a`). `(HVec a b c)`, one-directional
  `HVec <: Vec`, `nth`/`first`/`second` positional narrowing (constant in-bounds index only;
  dynamic/OOB → the element LUB, never a fabricated position), and an expected-directed literal
  check for construction that does **not** change `vector`'s default `(Vec T)` (the cheap
  `vector`→HVec hack broke 24 tests and was reverted). fram has no retrofit site — its
  heterogeneous tuples are justified-`(Vec Any)` in the deliberately-generic datalog engine.
- **G4-emit (map-pattern destructuring) — SHIPPED** (beagle `4203c1b`). Fixed a *latent bug*:
  `{:k x}` in `match` emitted the var **free** (undefined at runtime); now emit binds it
  (clj + js) and the checker narrows it to the field type. fram doesn't use map patterns (it
  discriminates via `if` + the kw-access slice), so this is additive.

**Backlog complete: G1–G7 all resolved** (G5/G1/G4-kwaccess/G7/G2/G3/G4-emit shipped sound +
CI-green; G6 subsumed). The hard three (G2 invariant Atom, G3 HVec, G4-emit) were done by hand
after the adversarial gate rejected the easy/unsound versions. The only thing deliberately NOT
built is **full row-polymorphism** (open-key flow-narrowing *outside* `match`) — a genuine
multi-week type-system project with no current consumer.

## Genuine Beagle gaps (prioritized — these feed the language)

| # | Gap | Impact in Fram | Suggested direction |
|---|---|---|---|
| G1 | **No type alias / synonym** (`deftype` removed, no `defalias`) | `Db`/`Subst`/`Tuple`/`Lit`/`Rule` (~50 sites) must be spelled inline — e.g. `(Map String (Set (Vec Any)))` re-typed at every signature. The single biggest source of `Any`-drift pressure. | A `(defalias Db (Map String (Set (Vec Any))))` form. **Highest value.** |
| G2 | **No parametric `(Atom T)`** | `(Atom Store)` is hard-rejected; bare `Atom` typechecks but `deref`/`swap!` erase the element type, so every store read recovers the shape with `(let [s :- Store (deref ctx)] …)`. Affects every `ctx` in store/schema/datalog. | Parametric `Atom` in the ctor allow-list. |
| G3 | **No heterogeneous tuple / `HVec`** | only homogeneous `(Vec T)`. A datalog fact `[cid l p r]` (`Int` then `String`s), a dump entry `[Int fact-map]`, an index key `[l p]` all collapse to `(Vec Any)`; reads via `(nth e 1)` degrade to `Any`. | `(HVec Int String String String)` / tuple types. |
| G4 | **No open / optional-key map (no row polymorphism)** | a `defrecord` always has *all* keys, so key-presence discrimination breaks: query's `:strata`-xor-`:rules`, `term-ok?`'s `(contains? t :var)`, the `{:ok …}`/`{:error …}` result envelopes. Forces `Any` at `query/run`, `tools/call` return, `main/route-write`. | Open/optional-key map types, or sum-of-records the checker can discriminate. |
| G5 | **`defenum` is not enforced by the checker** | `(->ToolSpec … :bogus …)` into an `Op`-typed field AND `(= op :nonmember)` against an `Op` both pass with 0 errors. `defenum` documents intent but doesn't yet *constrain*. | Make the checker reject non-member keywords against an enum-typed slot. |
| G6 | **The `Any`-assignability hole** | narrowing `Any → Int` via `(:seq m)` over a `(Map Keyword Any)` (e.g. `tx-seq`/`value-id` returns) passes `beagle check` only because `Any` is assignable to anything — the narrowed return type is documented intent, **not** verified. Largely a *consequence* of G3+G4 (those force `Map Keyword Any`, then field access is `Any`). | Tighter assignability from `Any`, or eliminate the upstream `Any` via G3/G4. |
| G7 | **`doseq`/`for` clause bindings reject `:- T`** | `(doseq [e :- (Vec Any) …])` is unparseable; you bind `e` plainly (infers `Any`) and re-annotate inside the body with a `let`. (`loop` bindings *do* accept `:- T`.) | Allow `:- T` in `for`/`doseq` binding clauses for parity with `loop`. |

### Non-gap (refactor cost, recorded so it isn't mistaken for one)
- **Map literals don't coerce to records:** `{:l 1 :p 2 :r 3}` is rejected where a record is
  expected, so adopting a record means refactoring producers to `(->Ctor …)`. We deliberately did
  **not** recordize `Fact`/`Tx`/`WriteIntent`: a hand-written `.clj` consumer
  (`coord/assemble-dump`) feeds plain maps into `load-store!`, and two suites assert whole-map
  equality — a record `≠` a map literal in Clojure, so recordizing would break green tests for zero
  runtime gain (every real consumer uses field access). Kept as `(Map Keyword Any)`. This is the
  "willing to NOT migrate when a typed seam doesn't earn it" discipline in action.

## Justified `Any` (the honest boundaries — 35 sites)

`Any` is correct where data is genuinely open or crosses a serialization/untrusted seam:
the value interner (`store/value!` — the engine accepts arbitrary EDN), the dump/load
serialization boundary (heterogeneous `[k v]` tuples → G3), the domain-agnostic actor token
(`begin-tx!`'s `agent`), polymorphic datalog terms (`{:var n}`-or-constant → G4), and the
open keyword-map query/result envelopes (→ G4). Each is annotated with *why* at the site.

## The conclusion (updated)

The first small probes found the gap was **discipline** (reflexive `Any`). Typing the whole
engine confirmed that *and* found where Beagle genuinely needs to grow: **G1 (type aliases)**
and **G4 (open-key maps / row polymorphism)** are the two that would convert the most remaining
justified-`Any` into real types, with **G5 (enforce `defenum`)** being a correctness gap in the
checker itself. These belong in Beagle's roadmap — that's the payoff of dogfooding: a typed
interpreter is the forcing function that a leaf-type probe never will be.
