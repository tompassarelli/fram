;; datalog_predicate_test.clj — COMPARISON PREDICATES as engine filter literals.
;; A body element {:pred op :args [a b]} FILTERS a substitution: it never binds and
;; reads neither db, delta, nor index, so the scan (oracle) and indexed fixpoint
;; behave identically. Ops :eq :ne (raw string) and :lt :le :gt :ge (numeric; a
;; non-numeric operand drops the row, never errors). Proves: numeric range filters,
;; disequality, equality-on-join, a predicate inside a recursive rule, silent drop
;; of a non-numeric operand, query-surface validation, and a DIFFERENTIAL check
;; that the scan oracle and the indexed fixpoint agree on predicate programs.
;;   bb -cp out tests/datalog_predicate_test.clj
(require '[fram.kernel :as k]
         '[fram.query :as q]
         '[fram.datalog :as d])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

;; ---------------------------------------------------------------------------
;; (A) query-surface behavior via q/run over string facts
;; ---------------------------------------------------------------------------

;; (1) :gt / :lt numeric range filter (constant operand is a bare number)
(let [facts [(k/->Fact "@a" "count" "150")
             (k/->Fact "@b" "count" "50")
             (k/->Fact "@c" "count" "200")]
      big {:head {:rel "big" :args [{:var "x"}]}
           :body [{:rel "fact" :args [{:var "x"} "count" {:var "c"}]}
                  {:pred :gt :args [{:var "c"} 100]}]}
      small {:head {:rel "small" :args [{:var "x"}]}
             :body [{:rel "fact" :args [{:var "x"} "count" {:var "c"}]}
                    {:pred :lt :args [{:var "c"} 100]}]}]
  (chk ":gt numeric filter keeps only values > 100"
       (= #{["@a"] ["@c"]} (set (:ok (q/run facts {:find "big" :rules [big]})))))
  (chk ":lt numeric filter keeps only values < 100"
       (= #{["@b"]} (set (:ok (q/run facts {:find "small" :rules [small]}))))))

;; (2) :ne disequality (raw string) and :eq on joined vars
(let [facts [(k/->Fact "@a" "rel" "@b")
             (k/->Fact "@a" "rel" "@a")
             (k/->Fact "@b" "rel" "@c")]
      distinct-r {:head {:rel "distinct" :args [{:var "x"} {:var "y"}]}
                  :body [{:rel "fact" :args [{:var "x"} "rel" {:var "y"}]}
                         {:pred :ne :args [{:var "x"} {:var "y"}]}]}
      same-r {:head {:rel "same" :args [{:var "x"}]}
              :body [{:rel "fact" :args [{:var "x"} "rel" {:var "y"}]}
                     {:pred :eq :args [{:var "x"} {:var "y"}]}]}]
  (chk ":ne drops the equal pair, keeps the distinct pairs"
       (= #{["@a" "@b"] ["@b" "@c"]} (set (:ok (q/run facts {:find "distinct" :rules [distinct-r]})))))
  (chk ":eq on joined vars keeps only the self-related row"
       (= #{["@a"]} (set (:ok (q/run facts {:find "same" :rules [same-r]}))))))

;; (3) predicate INSIDE a recursive rule (filters, recursion still terminates)
;; 3-cycle n1->n2->n3->n1 ; reaches transitive closure, but the recursive step
;; drops self-reach with :ne — so reaches = all 6 non-self ordered pairs.
(let [facts [(k/->Fact "@n1" "edge" "@n2")
             (k/->Fact "@n2" "edge" "@n3")
             (k/->Fact "@n3" "edge" "@n1")]
      reaches [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                :body [{:rel "fact" :args [{:var "x"} "edge" {:var "y"}]}]}
               {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
                :body [{:rel "fact" :args [{:var "x"} "edge" {:var "y"}]}
                       {:rel "reaches" :args [{:var "y"} {:var "z"}]}
                       {:pred :ne :args [{:var "x"} {:var "z"}]}]}]
      got (set (:ok (q/run facts {:find "reaches" :rules reaches})))]
  (chk "predicate inside a recursive rule filters self-reach; closure otherwise complete"
       (= #{["@n1" "@n2"] ["@n1" "@n3"] ["@n2" "@n3"] ["@n2" "@n1"] ["@n3" "@n1"] ["@n3" "@n2"]}
          got)))

;; (4) non-numeric operand at an ordering pred -> row silently dropped, still :ok
(let [facts [(k/->Fact "@a" "count" "abc")
             (k/->Fact "@b" "count" "150")]
      big {:head {:rel "big" :args [{:var "x"}]}
           :body [{:rel "fact" :args [{:var "x"} "count" {:var "c"}]}
                  {:pred :gt :args [{:var "c"} 100]}]}
      r (q/run facts {:find "big" :rules [big]})]
  (chk "non-numeric operand at an ordering pred drops the row (no error)"
       (and (contains? r :ok) (= #{["@b"]} (set (:ok r))))))

;; ---------------------------------------------------------------------------
;; (B) query-surface VALIDATION
;; ---------------------------------------------------------------------------
(def vfacts [(k/->Fact "@a" "p" "@b")])
(defn perr? [q] (contains? (q/run vfacts q) :error))

(chk "unbound predicate var rejected"
     (perr? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"}]}
                                :body [{:rel "fact" :args [{:var "x"} "p" {:var "y"}]}
                                       {:pred :gt :args [{:var "z"} 5]}]}]}))
(chk "bad predicate op rejected"
     (perr? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"}]}
                                :body [{:rel "fact" :args [{:var "x"} "p" {:var "y"}]}
                                       {:pred :gtx :args [{:var "x"} {:var "y"}]}]}]}))
(chk "predicate :args not a 2-vector rejected"
     (perr? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"}]}
                                :body [{:rel "fact" :args [{:var "x"} "p" {:var "y"}]}
                                       {:pred :gt :args [{:var "x"}]}]}]}))
;; a predicate does NOT bind: a head var supplied only by a pred is unbound.
(chk "head var bound only by a predicate -> unbound-head-var error"
     (perr? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"}]}
                                :body [{:rel "fact" :args [{:var "y"} "p" {:var "z"}]}
                                       {:pred :eq :args [{:var "x"} {:var "y"}]}]}]}))
(chk "well-formed predicate program still validates + runs"
     (contains? (q/run vfacts {:find "r" :rules [{:head {:rel "r" :args [{:var "x"}]}
                                                  :body [{:rel "fact" :args [{:var "x"} "p" {:var "y"}]}
                                                         {:pred :ne :args [{:var "x"} {:var "y"}]}]}]}) :ok))

;; ---------------------------------------------------------------------------
;; (C) DIFFERENTIAL — the scan oracle and the indexed fixpoint agree on
;; predicate programs. `oracle` (fixpoint-oracle) uses match-lit; `fixpoint` uses
;; match-lit-idx; both route a :pred literal through eval-pred. They must produce
;; SET-IDENTICAL least fixpoints. Both take (db0, rules) directly.
;; ---------------------------------------------------------------------------
(def oracle #'fram.datalog/fixpoint-oracle)
(defn rel-set [db rel] (set (d/facts db rel)))
(defn agree? [db0 rules]
  (let [i (d/fixpoint db0 rules)
        o (oracle db0 rules)
        rels (into (set (keys i)) (keys o))]
    (every? (fn [r] (= (rel-set i r) (rel-set o r))) rels)))

;; P1: numeric gt filter over a base relation
(def p1-db {"w" #{["n1" "5"] ["n2" "15"] ["n3" "25"]}})
(def p1-rules [{:head {:rel "big" :args [{:var "x"}]}
                :body [{:rel "w" :args [{:var "x"} {:var "c"}]}
                       {:pred :gt :args [{:var "c"} 10]}]}])

;; P2: :ne inside a recursive closure over a cycle
(def p2-db {"edge" #{["n1" "n2"] ["n2" "n3"] ["n3" "n1"]}})
(def p2-rules [{:head {:rel "reach" :args [{:var "x"} {:var "y"}]}
                :body [{:rel "edge" :args [{:var "x"} {:var "y"}]}]}
               {:head {:rel "reach" :args [{:var "x"} {:var "z"}]}
                :body [{:rel "edge" :args [{:var "x"} {:var "y"}]}
                       {:rel "reach" :args [{:var "y"} {:var "z"}]}
                       {:pred :ne :args [{:var "x"} {:var "z"}]}]}])

;; P3: :eq join + :le numeric, combined with a range-restricted negation
(def p3-db {"e" #{["a" "1"] ["b" "2"] ["a" "3"] ["c" "1"]}
            "down" #{["a"]}})
(def p3-rules [{:head {:rel "p" :args [{:var "x"} {:var "v"}]}
                :body [{:rel "e" :args [{:var "x"} {:var "v"}]}
                       {:pred :le :args [{:var "v"} 2]}
                       {:rel "down" :args [{:var "x"}] :neg true}]}])

;; P4: :eq forcing two positions equal
(def p4-db {"pair" #{["a" "a"] ["a" "b"] ["b" "b"]}})
(def p4-rules [{:head {:rel "eqp" :args [{:var "x"}]}
                :body [{:rel "pair" :args [{:var "x"} {:var "y"}]}
                       {:pred :eq :args [{:var "x"} {:var "y"}]}]}])

(chk "differential P1 (:gt filter) — indexed == oracle" (agree? p1-db p1-rules))
(chk "differential P2 (:ne in recursion) — indexed == oracle" (agree? p2-db p2-rules))
(chk "differential P3 (:le + eq + negation) — indexed == oracle" (agree? p3-db p3-rules))
(chk "differential P4 (:eq equating positions) — indexed == oracle" (agree? p4-db p4-rules))

;; explicit value checks anchor the differential (both engines, one asserted value)
(chk "P1 indexed value correct"    (= #{["n2"] ["n3"]} (rel-set (d/fixpoint p1-db p1-rules) "big")))
(chk "P2 indexed value correct (6 non-self pairs)"
     (= 6 (count (rel-set (d/fixpoint p2-db p2-rules) "reach"))))
(chk "P3 indexed value correct (a excluded by neg; v<=2)"
     (= #{["b" "2"] ["c" "1"]} (rel-set (d/fixpoint p3-db p3-rules) "p")))
(chk "P4 indexed value correct"    (= #{["a"] ["b"]} (rel-set (d/fixpoint p4-db p4-rules) "eqp")))

;; --- report ---
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.datalog predicates:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.datalog predicates:" (count fails) "FAILED") (System/exit 1))))
