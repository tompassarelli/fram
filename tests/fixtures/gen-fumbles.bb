#!/usr/bin/env bb
;; Generator for authoring-fumbles.edn — run once, then delete.

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

(def BASE "/home/tom/code/after-text/experiments/shared/capstone/arms/runs/treatment")

(defn slurp-output [run wd task att]
  (let [f (str BASE "/" run "/" wd "/" task "/attempt-" att ".output.txt")]
    (str/trim (slurp f))))

(def fumbles
  [
   ;; ─── EXP-021-t-r1 ───────────────────────────────────────────────────────

   {:id "EXP-021-t-r1/T1.1/0"
    :emitted (slurp-output "EXP-021-t-r1" "w0" "T1.1" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: .../wire.bclj: beagle-emit: don't know how to emit: (def-form 'host #f (call-form 'rt/env '(\"DAEMON_HOST\" \"127.0.0.1\")) #f #f)"
    :diagnosis "Used `(def host ...)` bare def-form inside `defn` body instead of `let` binding"
    :expected-fix "Replace `(do (def host ...) body)` with `(let [host ...] body)` in the function body"}

   {:id "EXP-021-t-r1/T1.1/1"
    :emitted (slurp-output "EXP-021-t-r1" "w0" "T1.1" 1)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation (consider adding `: ReturnType`); defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Whole-world lint gate failed on untyped parameters in gw.derive settled from a prior task; this task's own changeset was correctly typed"
    :expected-fix "Adapter needs def-level incremental error scoping to distinguish this task's own lint errors from prior-task pollution in the shared world"}

   {:id "EXP-021-t-r1/T3.1/0"
    :emitted (slurp-output "EXP-021-t-r1" "w1" "T3.1" 0)
    :failure-stage :gate
    :harness-error "gw tree builds + witness present, but T2a behavioral check FAILED: 2 misclassified, e.g. [[\"@019f5eed-0001-7000-8000-000000000001\" \"active\" \"dormant\"] [\"@019f5eed-0002-7000-8000-000000000002\" \"active\" \"dormant\"]]"
    :diagnosis "classify logic misidentifies threads that have a committed+driver+online-driver combo: emitted code uses `(has? \"committed\")` for `ready` but should check online-driver presence first"
    :expected-fix "Check `online-driver?` in the `active` branch before `committed?` only; threads with a driver not in the online set should be `dormant` not `active`"}

   {:id "EXP-021-t-r1/T3.1/1"
    :emitted (slurp-output "EXP-021-t-r1" "w1" "T3.1" 1)
    :failure-stage :gate
    :harness-error "gw tree builds + witness present, but T2a behavioral check FAILED: 1 misclassified, e.g. [[\"@019f5eed-0041-7000-8000-000000000041\" \"ready\" \"blocked\"]]"
    :diagnosis "classify dropped type annotations on params (triples, te, opts unannotated) and still has wrong blocked? logic: checks only first depends_on triple not all"
    :expected-fix "Add `:-` type annotations to all params AND check that a thread with depends_on whose target is non-terminal is classified blocked (any non-terminal dep suffices)"}

   {:id "EXP-021-t-r1/T3.2/0"
    :emitted (slurp-output "EXP-021-t-r1" "w1" "T3.2" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: .../derive.bclj: beagle: function type missing `->`: '(#%brackets Map)"
    :diagnosis "Used bracket type syntax `[Map]` for Beagle Vec type instead of `(Vec Map)`; Beagle parser rejects `[...]` in type position"
    :expected-fix "Replace `[Map]` with `(Vec Map)` (or a more specific element type); use `(Vec X)` form everywhere, never `[X]` in type annotations"}

   {:id "EXP-021-t-r1/T3.2/1"
    :emitted (slurp-output "EXP-021-t-r1" "w1" "T3.2" 1)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output is not readable EDN — Unmatched delimiter: ] (no #(…) shorthand, no 'quote, no :: keywords, no fences; emit plain EDN forms)"
    :diagnosis "Wrapped the EDN changeset in ```edn ... ``` markdown fences; the EDN reader sees backtick-fenced text and fails to parse it as a vector"
    :expected-fix "Emit the raw EDN vector directly with no surrounding markdown fences or prose"}

   {:id "EXP-021-t-r1/T3.2/2"
    :emitted (slurp-output "EXP-021-t-r1" "w1" "T3.2" 2)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output must be ONE EDN vector of step maps"
    :diagnosis "Emitted prose reasoning text before the fenced EDN block; the harness sees the prose as the output, not a vector of step maps"
    :expected-fix "Emit ONLY the EDN vector with no preceding prose or markdown fences"}

   {:id "EXP-021-t-r1/T5.2/0"
    :emitted (slurp-output "EXP-021-t-r1" "w3" "T5.2" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: .../derive.bclj: beagle [lint]: unused declare-extern: gw.rt/json-decode; unused declare-extern: w/gw.rt/socket-call"
    :diagnosis "Own code used wrong Beagle type `(Vector Object)` (should be `(Vec Any)`) and output wrapped in ```edn fences; fences stripped by harness but world gate failed on pre-existing gw.derive lint errors"
    :expected-fix "Use `(Vec Any)` not `(Vector Object)` for Beagle vector type; emit without markdown fences"}

   {:id "EXP-021-t-r1/T5.2/1"
    :emitted (slurp-output "EXP-021-t-r1" "w3" "T5.2" 1)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Own code used bare `Map` and `Vector` types without type params (e.g. `[triples :- Vector now-ms :- Long] :- Vector`) and world gate failed on gw.derive cross-pollution untyped functions"
    :expected-fix "Use `(Vec Any)` not bare `Vector`, `(Map Any Any)` not bare `Map`; prior settle also requires type annotations on all gw.derive helper functions"}

   {:id "EXP-021-t-r1/T5.2/2"
    :emitted (slurp-output "EXP-021-t-r1" "w3" "T5.2" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Own code used bare `Int` type for exp-ms (should be `Long`) and world gate continued to fail on gw.derive cross-pollution untyped functions"
    :expected-fix "Use `Long` not bare `Int` for millisecond values; world gate requires ALL functions in the world to be typed"}

   {:id "EXP-021-t-r1/T11.1/0"
    :emitted (slurp-output "EXP-021-t-r1" "w3" "T11.1" 0)
    :failure-stage :gate
    :harness-error "gw tree builds + witness present, but T2a behavioral check FAILED: gw.projections not loadable: Unable to resolve symbol: classify"
    :diagnosis "Used unqualified `classify` symbol in gw.projections; the function lives in gw.derive and must be referenced via the `d` alias as `d/classify`"
    :expected-fix "Replace bare `classify` with `d/classify` (or `gw.derive/classify`) to reference the derive module's function"}

   {:id "EXP-021-t-r1/T11.1/2"
    :emitted (slurp-output "EXP-021-t-r1" "w3" "T11.1" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Model correctly used `gw.derive/classify` in this attempt but world gate rejected the world due to untyped gw.derive functions settled from prior tasks"
    :expected-fix "Adapter needs def-level incremental scoping; prior-task untyped functions in gw.derive must be fixed at source"}

   {:id "EXP-021-t-r1/T11.3/0"
    :emitted (slurp-output "EXP-021-t-r1" "w4" "T11.3" 0)
    :failure-stage :gate
    :harness-error "gw tree builds + witness present, but T2a behavioral check FAILED: gw.projections not loadable: Unable to resolve symbol: obj-of"
    :diagnosis "Used unqualified `obj-of` in gw.projections; the function lives in gw.derive and must be referenced as `gw.derive/obj-of`"
    :expected-fix "Replace bare `obj-of` with `gw.derive/obj-of` (or `d/obj-of` via the alias declared in the module header)"}

   {:id "EXP-021-t-r1/T11.3/1"
    :emitted (slurp-output "EXP-021-t-r1" "w4" "T11.3" 1)
    :failure-stage :gate
    :harness-error "gw tree builds + witness present, but T2a behavioral check FAILED: gw.projections not loadable: Unable to resolve symbol: obj-of"
    :diagnosis "Same unqualified `obj-of` error persisted; model attempted different do_on logic but still referenced `obj-of` without `gw.derive/` qualification"
    :expected-fix "Qualify ALL cross-module symbol references; `obj-of` from gw.derive must be `gw.derive/obj-of` or use the declared alias `d/obj-of`"}

   {:id "EXP-021-t-r1/T11.3/2"
    :emitted (slurp-output "EXP-021-t-r1" "w4" "T11.3" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Model correctly qualified `gw.derive/obj-of` in this attempt but world gate rejected due to untyped gw.derive functions settled from prior tasks"
    :expected-fix "Adapter needs def-level incremental scoping; `gw.derive/obj-of` qualification was correct but cross-task pollution in world state caused gate failure"}

   {:id "EXP-021-t-r1/T13.1/1"
    :emitted (slurp-output "EXP-021-t-r1" "w4" "T13.1" 1)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output is not readable EDN — Invalid token: : (no #(…) shorthand, no 'quote, no :: keywords, no fences; emit plain EDN forms)"
    :diagnosis "Emitted JSON-style object syntax `{\"op\":\"upsert-form\",...}` mixed with EDN Beagle forms; JSON keys with colon-space are invalid EDN tokens"
    :expected-fix "Use EDN keyword keys (`:op`, `:module`) not JSON string keys (`\"op\"`); emit plain EDN maps, not JSON objects"}

   {:id "EXP-021-t-r1/T13.1/2"
    :emitted (slurp-output "EXP-021-t-r1" "w4" "T13.1" 2)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output is not readable EDN — Invalid token: : (no #(…) shorthand, no 'quote, no :: keywords, no fences; emit plain EDN forms)"
    :diagnosis "Continued JSON-style syntax with `{\"op\": \"upsert-form\", ...}` string keys and trailing commas; same invalid EDN token error as previous attempt"
    :expected-fix "Use EDN keyword keys (`:op`, `:module`, `:form`) not JSON string keys; remove trailing commas; emit canonical EDN step maps"}

   {:id "EXP-021-t-r1/T15.2/0"
    :emitted (slurp-output "EXP-021-t-r1" "w0" "T15.2" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Model emitted correctly-typed dag function but world gate failed on untyped gw.derive functions (has-pred?, pred-objs, etc.) from prior task settle"
    :expected-fix "Adapter needs def-level incremental check; this changeset's own code was correct but inherited world state caused the build failure"}

   {:id "EXP-021-t-r1/T15.2/1"
    :emitted (slurp-output "EXP-021-t-r1" "w0" "T15.2" 1)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p; (and 6 more untyped functions in gw.derive)"
    :diagnosis "Same cross-task pollution failure; model revised dag signature (reordered args, changed edge logic) but world gate still blocked on same gw.derive untyped functions"
    :expected-fix "Def-level incremental check would have returned OK for this task's defs; whole-world gate masked the distinction between own and inherited errors"}

   {:id "EXP-021-t-r1/T15.2/2"
    :emitted (slurp-output "EXP-021-t-r1" "w0" "T15.2" 2)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output must be ONE EDN vector of step maps"
    :diagnosis "Emitted prose reasoning paragraph before the EDN vector; harness sees the full text output and rejects it as not a vector"
    :expected-fix "Emit ONLY the EDN vector with no preceding reasoning text or prose; the entire output must be a single parseable EDN form"}

   {:id "EXP-021-t-r1/T2.1/0"
    :emitted (slurp-output "EXP-021-t-r1" "w2" "T2.1" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "occ-assert and occ-retract emitted with no type annotations on parameters (port, te, p, r untyped); world gate also blocked on gw.derive cross-pollution"
    :expected-fix "Add `(param :- Type)` annotations to every parameter: `[port :- Int te :- String p :- String r :- String]`"}

   {:id "EXP-021-t-r1/T2.1/1"
    :emitted (slurp-output "EXP-021-t-r1" "w2" "T2.1" 1)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Model added type annotations to its own occ functions but world gate continued to fail on gw.derive cross-pollution untyped functions"
    :expected-fix "Adapter needs def-level incremental check; this task's own functions are now correctly typed but inherited world pollution blocks the whole-world gate"}

   {:id "EXP-021-t-r1/T2.1/2"
    :emitted (slurp-output "EXP-021-t-r1" "w2" "T2.1" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-pred? has no return type annotation; defn has-pred? has untyped parameter(s): triples, e, p"
    :diagnosis "Model revised wire call format (string-based ops instead of map ops) but world gate still blocked on same gw.derive cross-pollution"
    :expected-fix "Adapter def-level incremental check needed; occ functions are correctly typed but pre-existing gw.derive untyped functions prevent world gate from passing"}

   ;; ─── EXP-021-t-r1-cascade2 ──────────────────────────────────────────────

   {:id "EXP-021-t-r1-cascade2/T3.1/0"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w2" "T3.1" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts"
    :diagnosis "All three emitted functions (has-fact?, get-fact, classify) have untyped parameters — no `:-` annotations on any parameter"
    :expected-fix "Add `(param :- Type)` to every parameter; use `(Vec (Vec String))` for triples, `String` for te/pred, `Any` for opts"}

   {:id "EXP-021-t-r1-cascade2/T3.1/1"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w2" "T3.1" 1)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output is not readable EDN — Invalid token: : (no #(…) shorthand, no 'quote, no :: keywords, no fences; emit plain EDN forms)"
    :diagnosis "Used `: Type` (colon-space) instead of `:- Type` (colon-hyphen) for type annotations; the bare `:` before `(Vector ...)` is an invalid EDN token"
    :expected-fix "Use `:- Type` not `: Type` for Beagle type annotations; the type annotation operator is `:-` not `:`"}

   {:id "EXP-021-t-r1-cascade2/T3.1/2"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w2" "T3.1" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn preds-of has untyped parameter(s): triples, te, pred"
    :diagnosis "Model re-emitted untyped functions again (triples, te, pred without `:-`), plus added a new untyped `preds-of` function"
    :expected-fix "Every parameter of every `defn` must have `(name :- Type)` form; unannotated params fail the lint gate"}

   {:id "EXP-021-t-r1-cascade2/T1.2/0"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w0" "T1.2" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts"
    :diagnosis "Model emitted well-structured wire functions but world gate blocked on gw.derive untyped functions settled from cascade2/T3.1/0"
    :expected-fix "Adapter def-level incremental check would pass this changeset; root cause is prior task's untyped gw.derive emit"}

   {:id "EXP-021-t-r1-cascade2/T1.2/1"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w0" "T1.2" 1)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output must be ONE EDN vector of step maps"
    :description "Wrapped the EDN changeset in ```edn ... ``` markdown fences with leading prose ('No other call sites. Emit arity...')"
    :diagnosis "Emitted reasoning prose and then fenced EDN block; harness cannot parse the full text as an EDN vector"
    :expected-fix "Emit only the raw EDN vector with no markdown fences and no prose text"}

   {:id "EXP-021-t-r1-cascade2/T1.2/2"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w0" "T1.2" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts"
    :diagnosis "Model emitted correct unfenced EDN but world gate blocked again on same gw.derive cross-pollution"
    :expected-fix "Adapter def-level incremental check would pass this changeset; cross-task pollution is the blocker"}

   {:id "EXP-021-t-r1-cascade2/T4.2/0"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w3" "T4.2" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts"
    :diagnosis "Model emitted correctly-typed http/wire functions but world gate blocked on gw.derive cross-pollution from cascade2/T3.1"
    :expected-fix "Adapter def-level incremental check would pass this changeset; inherited world state is the blocker"}

   {:id "EXP-021-t-r1-cascade2/T4.2/1"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w3" "T4.2" 1)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts; defn preds-of has untyped parameter(s): triples, te, pred"
    :diagnosis "Same world gate pollution; now preds-of also appeared as untyped (from cascade2/T3.1/2 settling)"
    :expected-fix "Adapter def-level incremental check needed; model's own http handlers were correctly typed"}

   {:id "EXP-021-t-r1-cascade2/T4.2/2"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w3" "T4.2" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts; defn preds-of has untyped parameter(s): triples, te, pred"
    :diagnosis "Model revised dispatch/handler design but world gate still blocked on same cross-task untyped functions in gw.derive"
    :expected-fix "Adapter def-level incremental check needed to unblock this task's correctly-typed handlers from cross-task pollution"}

   {:id "EXP-021-t-r1-cascade2/T5.2/0"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w4" "T5.2" 0)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts"
    :diagnosis "Model emitted correctly-typed presence functions using `Long/parseLong` interop but world gate blocked on gw.derive cross-pollution"
    :expected-fix "Adapter def-level incremental check would pass this changeset; own functions are well-typed"}

   {:id "EXP-021-t-r1-cascade2/T5.2/1"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w4" "T5.2" 1)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output must be ONE EDN vector of step maps"
    :diagnosis "Wrapped changeset in ```edn ... ``` fences; harness cannot parse the fence-wrapped text as an EDN vector"
    :expected-fix "Emit raw EDN vector only; remove all markdown fences"}

   {:id "EXP-021-t-r1-cascade2/T5.2/2"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w4" "T5.2" 2)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output must be ONE EDN vector of step maps"
    :diagnosis "Still wrapped in ```edn ... ``` fences after fence error was reported; model did not remove the markdown fences"
    :expected-fix "Emit raw EDN vector only; error message explicitly says 'no fences' — remove backtick fence delimiters entirely"}

   {:id "EXP-021-t-r1-cascade2/T2.1/0"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w5" "T2.1" 0)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output must be ONE EDN vector of step maps"
    :diagnosis "Wrapped OCC retry helper functions in ```edn ... ``` markdown fences"
    :expected-fix "Emit raw EDN vector only; remove markdown fences"}

   {:id "EXP-021-t-r1-cascade2/T2.1/1"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w5" "T2.1" 1)
    :failure-stage :parse
    :harness-error "STRUCTURAL_INVALID: output must be ONE EDN vector of step maps"
    :diagnosis "Still wrapped in ```edn fences after previous fence-error; model persisted the fence pattern across attempts"
    :expected-fix "Remove all markdown fences; the contract says emit ONLY the EDN vector with no fences"}

   {:id "EXP-021-t-r1-cascade2/T2.1/2"
    :emitted (slurp-output "EXP-021-t-r1-cascade2" "w5" "T2.1" 2)
    :failure-stage :gate
    :harness-error "build-all exit 1: beagle [lint]: defn has-fact? has untyped parameter(s): triples, te, pred; defn get-fact has untyped parameter(s): triples, te, pred; defn classify has untyped parameter(s): triples, te, opts; defn preds-of has untyped parameter(s): triples, te, pred"
    :diagnosis "Model finally emitted unfenced EDN with correctly-typed occ functions but world gate blocked on gw.derive cross-pollution"
    :expected-fix "Adapter def-level incremental check would pass this changeset; own occ functions are correctly typed"}
   ])

;; Write as EDN
(let [out-path "/home/tom/code/fram/tests/fixtures/authoring-fumbles.edn"
      freq-map (frequencies (map :failure-stage fumbles))
      parse-n (get freq-map :parse 0)
      gate-n  (get freq-map :gate 0)
      canon-n (get freq-map :canon 0)
      type-n  (get freq-map :type 0)
      subtypes {"fence/prose" (count (filter #(re-find #"fences\|prose" (or (:diagnosis %) "")) fumbles))
                "untyped-params" (count (filter #(re-find #"untyped param|untyped function|cross-task pollu" (or (:diagnosis %) "")) fumbles))
                "unqualified-ref" (count (filter #(re-find #"Unqualified|unqualified" (or (:diagnosis %) "")) fumbles))
                "bracket-type" (count (filter #(re-find #"bracket type|Vector Object|\[Map\]|colon-space" (or (:diagnosis %) "")) fumbles))
                "def-in-defn" (count (filter #(re-find #"def.*inside.*defn|bare def-form" (or (:diagnosis %) "")) fumbles))
                "json-style" (count (filter #(re-find #"JSON.style" (or (:diagnosis %) "")) fumbles))
                "behavioral" (count (filter #(re-find #"behavioral|misclassif" (or (:diagnosis %) "")) fumbles))}
      comment-block (str
";; authoring-fumbles — EXP-021 t-r1 run corpus (mined 2026-07-03)\n"
";;\n"
";; Failure-mode frequencies:\n"
";; :parse  " parse-n " — output not readable as EDN (fences, prose, JSON-style keys, bad token)\n"
";; :gate   " gate-n  " — EDN parsed OK but Beagle build/lint/behavioral gate rejected it\n"
";; :canon  " canon-n " — (none in this corpus)\n"
";; :type   " type-n  " — (none in this corpus; type errors show up at :gate stage)\n"
";;\n"
";; Top causes within :parse  (" parse-n "):\n"
";; markdown fences (```edn) ....... " (get subtypes "fence/prose" 0) "\n"
";; colon-space `: Type` vs `:-` .... 1\n"
";; JSON string keys `{\"op\":...}` ... 2\n"
";; prose before/after vector ........ 2\n"
";;\n"
";; Top causes within :gate   (" gate-n "):\n"
";; cross-task world pollution ......... ~12\n"
";; untyped own-code params ............. 5\n"
";; unqualified cross-module ref ......... 4  (classify, obj-of)\n"
";; wrong type syntax ([Map], Vector) .... 3\n"
";; wrong logic (behavioral fail) ........ 2\n"
";; def-in-defn body ..................... 1\n"
";;\n"
";; Coverage: EXP-021-t-r1 (w0-w6), EXP-021-t-r1-cascade2 (w0,w2-w5)\n"
";; EXP-021-t-r1-shakeout and EXP-021-t-r1-crash: HARNESS_ERROR, no model output — excluded\n")]
  (spit out-path (str comment-block (pr-str fumbles)))
  (println (str "Wrote " (count fumbles) " fixtures to " out-path))
  (println "Verifying EDN read...")
  (let [verified (edn/read-string (slurp out-path))]
    (println (str "OK — " (count verified) " entries"))))
