;; datalog_fn_test.clj — ARITHMETIC FN CLAUSES as engine binding literals.
;; A body element {:fn op :args [a b] :bind "h"} grounds its two args, computes a
;; binary arithmetic value, and BINDS a fresh var to the CANONICAL STRING of that
;; value (keeping the query db all-String). Ops :+ :- :* :/ :mod, binary. Integer
;; preservation mirrors :sum (:+ :- :* stay long when both operands parse-long, else
;; double; :/ is ALWAYS double); failure (non-numeric operand, div/mod by zero, mod
;; of a non-long) DROPS the row — never an error — exactly like a predicate filter.
;; A fn :bind counts as a binding for later :pred/:neg/head vars. fn clauses ignore
;; db/delta/index, so the scan oracle and the indexed fixpoint route them identically
;; (differential agreement by construction). fn clauses are REJECTED at validation in
;; any rule whose head rel is recursive (termination guard).
;;   bb -cp out tests/datalog_fn_test.clj
(require '[fram.kernel :as k]
         '[fram.query :as q]
         '[fram.datalog :as d])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

;; a rule computing h = (op c s) over @<e> "c"/"s" facts, head r(x,h).
(defn cs-rule [op]
  {:head {:rel "r" :args [{:var "x"} {:var "h"}]}
   :body [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
          {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
          {:fn op :args [{:var "c"} {:var "s"}] :bind "h"}]})
(defn run-cs [facts op]
  (set (:ok (q/run facts {:find "r" :rules [(cs-rule op)]}))))

;; ---------------------------------------------------------------------------
;; (A) all 5 ops, happy path (c=5 s=3)
;; ---------------------------------------------------------------------------
(def f53 [(k/->Fact "@a" "c" "5") (k/->Fact "@a" "s" "3")])
(chk ":+ binds the canonical string sum"        (= #{["@a" "8"]}  (run-cs f53 :+)))
(chk ":- binds the canonical string difference" (= #{["@a" "2"]}  (run-cs f53 :-)))
(chk ":* binds the canonical string product"    (= #{["@a" "15"]} (run-cs f53 :*)))
(chk ":mod binds integer modulus (5 mod 3 = 2)" (= #{["@a" "2"]}  (run-cs f53 :mod)))
(chk ":/ 5/3 is a double string"                (= #{["@a" (str (/ 5.0 3.0))]} (run-cs f53 :/)))

;; ---------------------------------------------------------------------------
;; (B) integer preservation & division-is-double
;; ---------------------------------------------------------------------------
;; :+ over two longs stays "8" (a long, not "8.0")
(chk "long preservation: :+ 5 3 = \"8\" (not \"8.0\")" (= #{["@a" "8"]} (run-cs f53 :+)))
;; :/ is ALWAYS double: 6/2 -> "3.0", 7/2 -> "3.5"
(let [facts [(k/->Fact "@p" "c" "6") (k/->Fact "@p" "s" "2")
             (k/->Fact "@q" "c" "7") (k/->Fact "@q" "s" "2")]]
  (chk ":/ is always double: 6/2 -> \"3.0\", 7/2 -> \"3.5\""
       (= #{["@p" "3.0"] ["@q" "3.5"]} (run-cs facts :/))))
;; a fractional operand forces double: 1.5 + 2 -> "3.5"
(let [facts [(k/->Fact "@a" "c" "1.5") (k/->Fact "@a" "s" "2")]]
  (chk "mixed long/double operand -> double: 1.5 + 2 = \"3.5\""
       (= #{["@a" "3.5"]} (run-cs facts :+))))
;; * with two longs stays long: 4 * 3 = "12"
(let [facts [(k/->Fact "@a" "c" "4") (k/->Fact "@a" "s" "3")]]
  (chk ":* two longs stays long: 4 * 3 = \"12\"" (= #{["@a" "12"]} (run-cs facts :*))))

;; ---------------------------------------------------------------------------
;; (C) failure modes DROP the row (never an error)
;; ---------------------------------------------------------------------------
;; div-by-zero -> dropped
(let [facts [(k/->Fact "@a" "c" "5") (k/->Fact "@a" "s" "0")]
      r (q/run facts {:find "r" :rules [(cs-rule :/)]})]
  (chk "division by zero drops the row (still :ok, empty)" (= {:ok []} r)))
;; mod-by-zero -> dropped
(let [facts [(k/->Fact "@a" "c" "5") (k/->Fact "@a" "s" "0")]
      r (q/run facts {:find "r" :rules [(cs-rule :mod)]})]
  (chk "mod by zero drops the row" (= {:ok []} r)))
;; mod with a non-long operand -> dropped
(let [facts [(k/->Fact "@a" "c" "5.5") (k/->Fact "@a" "s" "2")]
      r (q/run facts {:find "r" :rules [(cs-rule :mod)]})]
  (chk "mod of a non-long operand drops the row" (= {:ok []} r)))
;; non-numeric operand -> dropped
(let [facts [(k/->Fact "@a" "c" "abc") (k/->Fact "@a" "s" "2")]
      r (q/run facts {:find "r" :rules [(cs-rule :+)]})]
  (chk "non-numeric operand drops the row (no error)" (= {:ok []} r)))
;; the survivor is kept while the failing row is dropped (mixed batch)
(let [facts [(k/->Fact "@a" "c" "abc") (k/->Fact "@a" "s" "2")
             (k/->Fact "@b" "c" "4")   (k/->Fact "@b" "s" "2")]]
  (chk "failing row dropped, valid row kept" (= #{["@b" "6"]} (run-cs facts :+))))

;; ---------------------------------------------------------------------------
;; (D) constant args
;; ---------------------------------------------------------------------------
(let [facts [(k/->Fact "@a" "c" "5")]
      rule {:head {:rel "r" :args [{:var "x"} {:var "h"}]}
            :body [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                   {:fn :* :args [{:var "c"} 2] :bind "h"}]}]
  (chk "constant numeric arg: c * 2 = \"10\""
       (= #{["@a" "10"]} (set (:ok (q/run facts {:find "r" :rules [rule]}))))))

;; ---------------------------------------------------------------------------
;; (E) chained fns — z1 feeds z2
;; ---------------------------------------------------------------------------
(let [facts [(k/->Fact "@a" "c" "5") (k/->Fact "@a" "s" "3")]
      rule {:head {:rel "r" :args [{:var "x"} {:var "z2"}]}
            :body [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                   {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
                   {:fn :+ :args [{:var "c"} {:var "s"}] :bind "z1"}   ; 5+3 = 8
                   {:fn :* :args [{:var "z1"} {:var "c"}] :bind "z2"}]} ; 8*5 = 40
      r (set (:ok (q/run facts {:find "r" :rules [rule]})))]
  (chk "chained fns: z1=(+ 5 3)=8, z2=(* z1 5)=40" (= #{["@a" "40"]} r)))

;; ---------------------------------------------------------------------------
;; (F) a fn reading a fn-free RECURSIVE closure — ALLOWED (weighted head is not
;;     recursive; it only READS the recursive `reaches`). Validates and computes.
;; ---------------------------------------------------------------------------
(let [facts [(k/->Fact "@a" "depends_on" "@b")
             (k/->Fact "@b" "depends_on" "@c")
             (k/->Fact "@a" "w" "10")]
      rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
              :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}]}
             {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
              :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}
                     {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}
             ;; weighted(x,y,h) reads the recursive closure + a weight, doubles it
             {:head {:rel "weighted" :args [{:var "x"} {:var "y"} {:var "h"}]}
              :body [{:rel "reaches" :args [{:var "x"} {:var "y"}]}
                     {:rel "fact" :args [{:var "x"} "w" {:var "w"}]}
                     {:fn :* :args [{:var "w"} 2] :bind "h"}]}]
      r (q/run facts {:find "weighted" :rules rules})]
  ;; @a reaches @b and @c (closure); weight 10 -> h = "20"
  (chk "fn over a fn-free recursive closure validates + computes"
       (= #{["@a" "@b" "20"] ["@a" "@c" "20"]} (set (:ok r)))))

;; ---------------------------------------------------------------------------
;; (G) fn :bind feeds a later :pred and a later :neg (it counts as a binding)
;; ---------------------------------------------------------------------------
(let [facts [(k/->Fact "@a" "c" "5") (k/->Fact "@a" "s" "3")   ; h = 8
             (k/->Fact "@b" "c" "1") (k/->Fact "@b" "s" "1")]  ; h = 2
      ;; keep only rows whose computed h > 5
      rule {:head {:rel "r" :args [{:var "x"} {:var "h"}]}
            :body [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                   {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
                   {:fn :+ :args [{:var "c"} {:var "s"}] :bind "h"}
                   {:pred :gt :args [{:var "h"} 5]}]}
      r (set (:ok (q/run facts {:find "r" :rules [rule]})))]
  (chk "fn :bind feeds a later :pred (h > 5 keeps only @a)" (= #{["@a" "8"]} r)))

;; the negated relation must be a DERIVED head (a frozen lower stratum) — the query
;; surface's base rel is "fact"; negating it hits strata's "triple"/"fact-id" gate.
(let [facts [(k/->Fact "@a" "c" "5") (k/->Fact "@a" "s" "3")   ; h = 8
             (k/->Fact "@b" "c" "1") (k/->Fact "@b" "s" "1")   ; h = 2
             (k/->Fact "8" "banned" "yes")]                    ; a fact keyed by "8"
      qy {:find "r"
          :strata [[{:head {:rel "banned" :args [{:var "v"}]}
                     :body [{:rel "fact" :args [{:var "v"} "banned" "yes"]}]}]
                   [{:head {:rel "r" :args [{:var "x"} {:var "h"}]}
                     :body [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                            {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
                            {:fn :+ :args [{:var "c"} {:var "s"}] :bind "h"}
                            {:rel "banned" :args [{:var "h"}] :neg true}]}]]}
      r (set (:ok (q/run facts qy)))]
  ;; @a's h = "8" IS banned -> dropped; @b's h = "2" survives
  (chk "fn :bind feeds a later :neg (@a's h=\"8\" is banned, dropped)"
       (= #{["@b" "2"]} r)))

;; ---------------------------------------------------------------------------
;; (H) query-surface VALIDATION
;; ---------------------------------------------------------------------------
(def vfacts [(k/->Fact "@a" "c" "5") (k/->Fact "@a" "s" "3")])
(defn ferr? [q] (contains? (q/run vfacts q) :error))
(defn body-rule [body] {:find "r" :rules [{:head {:rel "r" :args [{:var "x"} {:var "h"}]} :body body}]})

(chk "bad fn op rejected"
     (ferr? (body-rule [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                        {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
                        {:fn :pow :args [{:var "c"} {:var "s"}] :bind "h"}])))
(chk "fn :args not a 2-vector rejected"
     (ferr? (body-rule [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                        {:fn :+ :args [{:var "c"}] :bind "h"}])))
(chk "fn unbound arg var rejected"
     (ferr? (body-rule [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                        {:fn :+ :args [{:var "c"} {:var "s"}] :bind "h"}])))  ; s never bound
(chk "fn :bind rebind (already-bound var) rejected"
     (ferr? (body-rule [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                        {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
                        {:fn :+ :args [{:var "c"} {:var "s"}] :bind "c"}])))  ; :bind "c" already bound
(chk "fn :bind non-string rejected"
     (ferr? (body-rule [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                        {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
                        {:fn :+ :args [{:var "c"} {:var "s"}] :bind 7}])))

;; fn :bind SATISFIES head range-safety (a head var supplied only by a fn :bind is bound)
(chk "fn :bind satisfies head safety (validates + runs)"
     (contains? (q/run vfacts (body-rule [{:rel "fact" :args [{:var "x"} "c" {:var "c"}]}
                                          {:rel "fact" :args [{:var "x"} "s" {:var "s"}]}
                                          {:fn :+ :args [{:var "c"} {:var "s"}] :bind "h"}])) :ok))

;; fn in a RECURSIVE rule is REJECTED with the named error
(let [rec-rules [{:head {:rel "reach" :args [{:var "x"} {:var "y"}]}
                  :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                 ;; recursive head `reach` also carrying a fn -> must be rejected
                 {:head {:rel "reach" :args [{:var "x"} {:var "h"}]}
                  :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}
                         {:rel "reach" :args [{:var "y"} {:var "z"}]}
                         {:fn :+ :args [{:var "x"} {:var "z"}] :bind "h"}]}]
      errs (q/validate {:find "reach" :rules rec-rules})]
  (chk "fn in a recursive rule is REJECTED"
       (not (empty? errs)))
  (chk "recursion-rejection error names the rel and the unbounded-growth reason"
       (and (some #(re-find #"recursive" %) errs)
            (some #(re-find #"grow without bound" %) errs)
            (some #(re-find #"reach" %) errs))))

;; a fn in a self-directly-recursive single rule is also rejected
(let [errs (q/validate {:find "loop"
                        :rules [{:head {:rel "loop" :args [{:var "x"} {:var "h"}]}
                                 :body [{:rel "fact" :args [{:var "x"} "n" {:var "n"}]}
                                        {:rel "loop" :args [{:var "x"} {:var "m"}]}
                                        {:fn :+ :args [{:var "n"} {:var "m"}] :bind "h"}]}]})]
  (chk "fn in a directly self-recursive rule rejected" (not (empty? errs))))

;; ---------------------------------------------------------------------------
;; (I) DIFFERENTIAL — scan oracle (fixpoint-oracle) vs indexed fixpoint agree on
;; fn programs. Both engines route a fn literal through eval-fn (ignoring db/idx),
;; so they must be SET-IDENTICAL. Both take (db0, rules) directly. Only NON-recursive
;; fn rules are used (fn-in-recursion is rejected upstream and could not terminate).
;; ---------------------------------------------------------------------------
(def oracle #'fram.datalog/fixpoint-oracle)
(defn rel-set [db rel] (set (d/facts db rel)))
(defn agree? [db0 rules]
  (let [i (d/fixpoint db0 rules)
        o (oracle db0 rules)
        rels (into (set (keys i)) (keys o))]
    (every? (fn [r] (= (rel-set i r) (rel-set o r))) rels)))

;; F1: non-recursive — sum two base columns, plus a chained product
(def f1-db {"v" #{["a" "5" "3"] ["b" "10" "2"] ["c" "7" "7"]}})
(def f1-rules [{:head {:rel "out" :args [{:var "x"} {:var "z2"}]}
                :body [{:rel "v" :args [{:var "x"} {:var "p"} {:var "q"}]}
                       {:fn :+ :args [{:var "p"} {:var "q"}] :bind "z1"}
                       {:fn :* :args [{:var "z1"} 2] :bind "z2"}]}])

;; F2: fn OVER a fn-free recursive closure (closure recursive; the fn rule is not)
(def f2-db {"edge" #{["n1" "n2"] ["n2" "n3"] ["n3" "n4"]}
            "wt"   #{["n1" "100"] ["n2" "200"]}})
(def f2-rules [{:head {:rel "reach" :args [{:var "x"} {:var "y"}]}
                :body [{:rel "edge" :args [{:var "x"} {:var "y"}]}]}
               {:head {:rel "reach" :args [{:var "x"} {:var "z"}]}
                :body [{:rel "edge" :args [{:var "x"} {:var "y"}]}
                       {:rel "reach" :args [{:var "y"} {:var "z"}]}]}
               ;; non-recursive fn head reading the recursive closure + a weight
               {:head {:rel "cost" :args [{:var "x"} {:var "y"} {:var "h"}]}
                :body [{:rel "reach" :args [{:var "x"} {:var "y"}]}
                       {:rel "wt" :args [{:var "x"} {:var "w"}]}
                       {:fn :/ :args [{:var "w"} 2] :bind "h"}]}])

(chk "differential F1 (chained fns, non-recursive) — indexed == oracle" (agree? f1-db f1-rules))
(chk "differential F2 (fn over a recursive closure) — indexed == oracle" (agree? f2-db f2-rules))

;; explicit value anchors (both engines evaluated; one asserted value)
(chk "F1 indexed value correct"
     (= #{["a" "16"] ["b" "24"] ["c" "28"]} (rel-set (d/fixpoint f1-db f1-rules) "out")))
(chk "F2 indexed value correct (n1 reaches n2/n3/n4, w=100 -> \"50.0\"; n2 reaches n3/n4, w=200 -> \"100.0\")"
     (= #{["n1" "n2" "50.0"] ["n1" "n3" "50.0"] ["n1" "n4" "50.0"]
          ["n2" "n3" "100.0"] ["n2" "n4" "100.0"]}
        (rel-set (d/fixpoint f2-db f2-rules) "cost")))

;; --- report ---
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.datalog fn clauses:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.datalog fn clauses:" (count fails) "FAILED") (System/exit 1))))
