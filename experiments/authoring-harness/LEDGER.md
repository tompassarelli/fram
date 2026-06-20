# Ledger — canonical measured log (append-only; this is your memory)

## Corpus index (Beagle-reachable AS IT STANDS — no porting allowed; STEP 2)
Reachability checked 2026-06-20 by `git ls-files '*.bclj'` + dir scan. NO porting in this loop.
- [ ] **greet** (N=1) — `experiments/authoring-harness/greet.bclj`, committed, behavioral oracle. REACHABLE. The clean scenario.
- [~] **fram-own-source** (N>1 candidates) — `src/fram/*.bclj` (cnf/datalog/export/fold/import/kernel/main/query/schema/tools/types) are committed, compiling, REAL Beagle. Reachable WITHOUT porting. Needs: a clean single-module def with intra-module refs + arm-LSP clj buildable standalone. PROBE before use.
- [x] ~~hiccup.util (S3)~~ — port was EPHEMERAL (/tmp, never committed); GONE. Re-creating = PORTING = forbidden in-loop. Not reachable.
- [x] ~~honeysql util/str (curve)~~ — carve was EPHEMERAL (/tmp/s5-honeysql, never committed); GONE + had the multi-arity-fn beagle bug. Re-creating = PORTING = forbidden. Not reachable.
- [x] ~~datahike~~ — raw Clojure, never Beagle. NOT Beagle-reachable for arm-G (it was the clojure-lsp falsifier corpus only). Not a cost-curve scenario.

CONSEQUENCE (stated, per spec — thin corpus is a limitation, not a confound): the intended higher-N
corpus (hiccup/honeysql) is gone and re-creating it is porting (outer-loop decision, Tom's). The only
non-porting higher-N source is Fram's own `src/fram/*.bclj`; if it doesn't yield a CLEAN single-module
scenario, the curve has one clean point (greet N=1) and the loop halts with that as the stated limit.

