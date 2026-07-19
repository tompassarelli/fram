;; ============================================================================
;; dead_private_datalog_receipt.clj — the identity-keyed STRATIFIED-Datalog code
;; query: private def-/defn- bindings UNREACHABLE from public roots are `dead`.
;;   bb -cp out dead_private_datalog_receipt.clj
;;
;; resolve/dead-private-bindings runs fram.datalog/run-strata over resolve/call-edges:
;;   stratum 1 (positive, recursive) — live(x) reachable from public roots via calls
;;   stratum 2 (negated)             — dead(p) :- private(p), NOT live(p)
;; Proven here on a synthetic identity-keyed graph (fast, no corpus load):
;;   D1 unreachable private RECURSIVE CYCLE  (cyc1<->cyc2)  -> dead
;;   D2 unreachable private orphan           (orphan, privN) -> dead
;;   D3 reachable private CHAIN              (pub->h1->h2, pubM->z) -> LIVE (not dead)
;;   D4 public roots                         (pub, pubM, util) -> never dead
;;   D5 SAME-SPELLING cross-module distinct  (mod1#helper dead, mod2#helper root live)
;; ============================================================================
(binding [*command-line-args* []] (load-file "chartroom/src/resolve.clj"))

;; call-edges shape: {:defn-meta {leaf -> meta} :edges [[caller callee]]}. Leaves are the
;; @mod#int identity keys (here strings standing in for node ids — dead-private-bindings is
;; keyed purely on identity, so same-spelling names in different modules are distinct keys).
(def defn-meta {"pub" {} "h1" {} "h2" {}
                "cyc1" {} "cyc2" {} "orphan" {} "privN" {}
                "mod2#helper" {} "mod1#helper" {} "z" {} "util" {}})
(def edges [["pub" "h1"] ["h1" "h2"]            ; public -> private chain (live)
            ["cyc1" "cyc2"] ["cyc2" "cyc1"]     ; unreachable private cycle (dead)
            ["mod2#helper" "z"]])               ; public mod2#helper -> private z (live)
(def privacy {"pub" :public "h1" :private "h2" :private
              "cyc1" :private "cyc2" :private "orphan" :private "privN" :private
              "mod2#helper" :public "mod1#helper" :private "z" :private "util" :public})

(def dead (resolve/dead-private-bindings {:defn-meta defn-meta :edges edges} privacy))
(def expect #{"cyc1" "cyc2" "orphan" "privN" "mod1#helper"})
(def live-privates #{"h1" "h2" "z"})            ; reachable privates must NOT be dead
(def roots #{"pub" "mod2#helper" "util"})       ; public roots must NEVER be dead

(println "dead private (unreachable):" (sort dead))
(def d1 (and (dead "cyc1") (dead "cyc2")))                       ; unreachable recursive cycle
(def d2 (and (dead "orphan") (dead "privN")))                    ; unreachable orphans
(def d3 (not (some dead live-privates)))                         ; reachable private chains live
(def d4 (not (some dead roots)))                                 ; public roots never dead
(def d5 (and (dead "mod1#helper") (not (dead "mod2#helper"))))   ; same spelling, different modules -> distinct
(def exact (= dead expect))
(println "  D1 unreachable recursive cycle dead:" d1)
(println "  D2 unreachable orphans dead        :" d2)
(println "  D3 reachable private chains live   :" d3)
(println "  D4 public roots never dead         :" d4)
(println "  D5 same-spelling cross-module split:" d5)
(println "  exact dead set == expected         :" exact)

(println "\n=== VERDICT ===")
(if (and d1 d2 d3 d4 d5 exact)
  (do (println "PASS — stratified-Datalog dead(private) over identity-keyed call edges: unreachable"
               "private (incl. recursive cycles) dead, reachable private live, cross-module distinct.")
      (System/exit 0))
  (do (println "FAIL — D1" d1 "D2" d2 "D3" d3 "D4" d4 "D5" d5 "exact" exact) (System/exit 1)))
