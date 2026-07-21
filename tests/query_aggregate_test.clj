;; query_aggregate_test.clj — post-fixpoint AGGREGATES on the structured query
;; surface. Proves: grouped :count / :count-distinct / :sum / :avg / :min / :max
;; with correct numeric semantics (long-preserving sum, double avg, numeric
;; min/max), empty-relation and global-group behavior, non-numeric rejection,
;; aggregate-spec validation, the output-group cap, run-page rejection, and
;; composition with recursion + stratified negation.
;;   bb -cp out tests/query_aggregate_test.clj
(require '[fram.kernel :as k]
         '[fram.query :as q])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

;; deg(x,y) :- fact(x,"depends_on",y)  — a derived edge relation
(def deg-rule
  {:head {:rel "deg" :args [{:var "x"} {:var "y"}]}
   :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}]})

;; --- (1) grouped :count over a derived relation -----------------------------
(let [facts [(k/->Fact "@a" "depends_on" "@b")
             (k/->Fact "@a" "depends_on" "@c")
             (k/->Fact "@b" "depends_on" "@c")]
      r (q/run facts {:find {:rel "deg" :group [0] :agg [{:op :count}]}
                      :rules [deg-rule]})]
  (chk "degree :count groups by position 0 with exact counts"
       (= {:ok [["@a" 2] ["@b" 1]]} r)))

;; --- (2) :sum (long-preserving) / :avg (double) / :min / :max per group ------
(let [facts [(k/->Fact "@a" "n" "3") (k/->Fact "@a" "n" "5")
             (k/->Fact "@b" "n" "10") (k/->Fact "@b" "n" "20")]
      nv {:head {:rel "nv" :args [{:var "x"} {:var "v"}]}
          :body [{:rel "fact" :args [{:var "x"} "n" {:var "v"}]}]}
      run-agg (fn [op] (q/run facts {:find {:rel "nv" :group [0] :agg [{:op op :arg 1}]}
                                     :rules [nv]}))]
  (chk ":sum is integer-preserving when all values are longs"
       (= {:ok [["@a" 8] ["@b" 30]]} (run-agg :sum)))
  (chk ":sum values are Longs (not doubles)"
       (every? #(instance? Long (second %)) (:ok (run-agg :sum))))
  (chk ":avg is always a double"
       (= {:ok [["@a" 4.0] ["@b" 15.0]]} (run-agg :avg)))
  (chk ":min returns the numeric minimum per group"
       (= {:ok [["@a" 3] ["@b" 10]]} (run-agg :min)))
  (chk ":max returns the numeric maximum per group"
       (= {:ok [["@a" 5] ["@b" 20]]} (run-agg :max))))

;; --- (2b) :sum falls to double when any value is fractional; :avg double -----
(let [facts [(k/->Fact "@c" "d" "1.5") (k/->Fact "@c" "d" "2.5")]
      dv {:head {:rel "dv" :args [{:var "x"} {:var "v"}]}
          :body [{:rel "fact" :args [{:var "x"} "d" {:var "v"}]}]}]
  (chk ":sum over fractional values yields a double"
       (= {:ok [["@c" 4.0]]} (q/run facts {:find {:rel "dv" :group [0] :agg [{:op :sum :arg 1}]}
                                           :rules [dv]})))
  (chk ":avg over fractional values"
       (= {:ok [["@c" 2.0]]} (q/run facts {:find {:rel "dv" :group [0] :agg [{:op :avg :arg 1}]}
                                           :rules [dv]}))))

;; --- (3) :count-distinct over a TRANSITIVE CLOSURE (recursive reaches) -------
;; chain @a->@b->@c->@d ; reaches = transitive closure ; distinct targets per src
(let [facts [(k/->Fact "@a" "depends_on" "@b")
             (k/->Fact "@b" "depends_on" "@c")
             (k/->Fact "@c" "depends_on" "@d")]
      reaches [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}]}
               {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
                :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}
                       {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}]
      r (q/run facts {:find {:rel "reaches" :group [0] :agg [{:op :count-distinct :arg 1}]}
                      :rules reaches})]
  (chk ":count-distinct over transitive closure (reachable set size per source)"
       (= {:ok [["@a" 3] ["@b" 2] ["@c" 1]]} r)))

;; --- (4) empty relation -> {:ok []} (count-over-empty is empty, not 0) -------
(let [facts [(k/->Fact "@a" "depends_on" "@b")]
      z {:head {:rel "z" :args [{:var "x"} {:var "y"}]}
         :body [{:rel "fact" :args [{:var "x"} "no_such_pred" {:var "y"}]}]}
      r (q/run facts {:find {:rel "z" :group [0] :agg [{:op :count}]} :rules [z]})]
  (chk "empty relation -> {:ok []} (no zero row)" (= {:ok []} r)))

;; --- (5) :group [] global aggregate -----------------------------------------
(let [facts [(k/->Fact "@a" "depends_on" "@b")
             (k/->Fact "@a" "depends_on" "@c")
             (k/->Fact "@b" "depends_on" "@c")]
      r (q/run facts {:find {:rel "deg" :group [] :agg [{:op :count}]} :rules [deg-rule]})]
  (chk ":group [] is one global group (single row, no group cols)"
       (= {:ok [[3]]} r)))

;; --- (6) non-numeric at a :sum position -> whole query {:error} --------------
(let [facts [(k/->Fact "@a" "name" "alice") (k/->Fact "@a" "name" "bob")]
      nm {:head {:rel "nm" :args [{:var "x"} {:var "v"}]}
          :body [{:rel "fact" :args [{:var "x"} "name" {:var "v"}]}]}
      r (q/run facts {:find {:rel "nm" :group [0] :agg [{:op :sum :arg 1}]} :rules [nm]})]
  (chk "non-numeric at :sum position -> {:error}" (contains? r :error))
  (chk ":error names the position and an example non-numeric value"
       (and (some #(re-find #"position 1" %) (:error r))
            (some #(re-find #"non-numeric" %) (:error r)))))

;; --- (7) aggregate-spec validation ------------------------------------------
(def vfacts [(k/->Fact "@a" "depends_on" "@b")])
(defn agg-err? [find] (contains? (q/run vfacts {:find find :rules [deg-rule]}) :error))
(chk "unknown :rel rejected"        (agg-err? {:rel "nope" :group [] :agg [{:op :count}]}))
(chk "base-relation :rel rejected"  (agg-err? {:rel "fact" :group [] :agg [{:op :count}]}))
(chk ":group position >= arity rejected" (agg-err? {:rel "deg" :group [5] :agg [{:op :count}]}))
(chk "unknown :op rejected"         (agg-err? {:rel "deg" :group [0] :agg [{:op :median :arg 1}]}))
(chk ":sum missing :arg rejected"   (agg-err? {:rel "deg" :group [0] :agg [{:op :sum}]}))
(chk ":agg position >= arity rejected" (agg-err? {:rel "deg" :group [0] :agg [{:op :sum :arg 9}]}))
(chk "well-formed aggregate spec still validates+runs"
     (contains? (q/run vfacts {:find {:rel "deg" :group [0] :agg [{:op :count}]} :rules [deg-rule]}) :ok))

;; --- (8) output-group count over the cap -> :over-limit ----------------------
;; Self-calibrates: a cross-join of m subjects grouped by both positions yields
;; m^2 distinct groups; choose m so m^2 strictly exceeds q/max-results.
(let [cap q/max-results
      m (inc (long (Math/ceil (Math/sqrt (double cap)))))
      big (mapv (fn [i] (k/->Fact (str "@s" i) "p" (str "@o" i))) (range m))
      pair {:head {:rel "pair" :args [{:var "a"} {:var "b"}]}
            :body [{:rel "fact" :args [{:var "a"} {:var "pp"} {:var "qq"}]}
                   {:rel "fact" :args [{:var "b"} {:var "ss"} {:var "tt"}]}]}
      r (q/run big {:find {:rel "pair" :group [0 1] :agg [{:op :count}]} :rules [pair]})]
  (chk "aggregate output over the cap -> :over-limit (not :ok)"
       (and (not (contains? r :ok)) (contains? r :error)))
  (chk ":over-limit reports the group count and the cap"
       (and (= (:max r) cap) (> (:over-limit r) cap))))

;; --- (9) run-page rejects aggregate :find (not pageable in v1) ---------------
(let [r (q/run-page vfacts {:find {:rel "deg" :group [0] :agg [{:op :count}]} :rules [deg-rule]} 10 nil)]
  (chk "run-page + aggregate :find -> {:error} (not pageable)"
       (and (contains? r :error) (not (contains? r :ok)))))

;; --- (10) aggregate composes with recursion (above) + stratified negation ----
;; stratum 0: done(x) :- fact(x,kind,hub) ; stratum 1: open(x) :- fact(x,dep,_), NOT done(x)
(let [facts [(k/->Fact "@a" "depends_on" "@b")
             (k/->Fact "@b" "depends_on" "@c")
             (k/->Fact "@b" "kind" "hub")]
      r (q/run facts
          {:find {:rel "open" :group [] :agg [{:op :count}]}
           :strata [[{:head {:rel "done" :args [{:var "x"}]}
                      :body [{:rel "fact" :args [{:var "x"} "kind" "hub"]}]}]
                    [{:head {:rel "open" :args [{:var "x"}]}
                      :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}
                             {:rel "done" :args [{:var "x"}] :neg true}]}]]})]
  ;; open = {@a} (@a depends, not a hub); @b depends but IS a hub -> excluded. count = 1.
  (chk "aggregate over a stratified-negation program (global count)"
       (= {:ok [[1]]} r)))

;; ============================================================================
;; HAVING — post-grouping numeric filter on {:agg i} vs :val, clauses ANDed.
;; ============================================================================

;; deg count per subject: @a 2, @b 1.
(def having-facts [(k/->Fact "@a" "depends_on" "@b")
                   (k/->Fact "@a" "depends_on" "@c")
                   (k/->Fact "@b" "depends_on" "@c")])
;; nv values: @a {3,5}, @b {10,20}.
(def nvh {:head {:rel "nv" :args [{:var "x"} {:var "v"}]}
          :body [{:rel "fact" :args [{:var "x"} "n" {:var "v"}]}]})
(def nvh-facts [(k/->Fact "@a" "n" "3") (k/->Fact "@a" "n" "5")
                (k/->Fact "@b" "n" "10") (k/->Fact "@b" "n" "20")])

;; (H1) count > N keeps only groups above the threshold
(chk "having :gt on :count keeps @a (2) drops @b (1)"
     (= {:ok [["@a" 2]]}
        (q/run having-facts {:find {:rel "deg" :group [0] :agg [{:op :count}]
                                    :having [{:op :gt :agg 0 :val 1}]} :rules [deg-rule]})))

;; (H2) sum >= N
(chk "having :ge on :sum keeps @b (30) drops @a (8)"
     (= {:ok [["@b" 30]]}
        (q/run nvh-facts {:find {:rel "nv" :group [0] :agg [{:op :sum :arg 1}]
                                 :having [{:op :ge :agg 0 :val 30}]} :rules [nvh]})))

;; (H3) having over :min and over :max
(chk "having :gt on :min keeps @b (10) drops @a (3)"
     (= {:ok [["@b" 10]]}
        (q/run nvh-facts {:find {:rel "nv" :group [0] :agg [{:op :min :arg 1}]
                                 :having [{:op :gt :agg 0 :val 5}]} :rules [nvh]})))
(chk "having :ge on :max keeps @b (20) drops @a (5)"
     (= {:ok [["@b" 20]]}
        (q/run nvh-facts {:find {:rel "nv" :group [0] :agg [{:op :max :arg 1}]
                                 :having [{:op :ge :agg 0 :val 20}]} :rules [nvh]})))

;; (H4) :eq on count
(chk "having :eq on :count keeps @b (1)"
     (= {:ok [["@b" 1]]}
        (q/run having-facts {:find {:rel "deg" :group [0] :agg [{:op :count}]
                                    :having [{:op :eq :agg 0 :val 1}]} :rules [deg-rule]})))

;; (H5) :having [] is a no-op (all groups survive)
(chk "having [] filters nothing"
     (= {:ok [["@a" 2] ["@b" 1]]}
        (q/run having-facts {:find {:rel "deg" :group [0] :agg [{:op :count}] :having []}
                             :rules [deg-rule]})))

;; (H6) all rows filtered out -> {:ok []}
(chk "having that excludes every group -> {:ok []}"
     (= {:ok []}
        (q/run having-facts {:find {:rel "deg" :group [0] :agg [{:op :count}]
                                    :having [{:op :gt :agg 0 :val 100}]} :rules [deg-rule]})))

;; (H7) global :group [] + having (single row survives / drops)
(chk "global group [] with satisfied having keeps the single row"
     (= {:ok [[3]]}
        (q/run having-facts {:find {:rel "deg" :group [] :agg [{:op :count}]
                                    :having [{:op :gt :agg 0 :val 2}]} :rules [deg-rule]})))
(chk "global group [] with unsatisfied having -> {:ok []}"
     (= {:ok []}
        (q/run having-facts {:find {:rel "deg" :group [] :agg [{:op :count}]
                                    :having [{:op :gt :agg 0 :val 5}]} :rules [deg-rule]})))

;; (H8) :ge on :avg (documented-fragile equality avoided; ordering is fine)
(chk "having :ge on :avg keeps @b (15.0) drops @a (4.0)"
     (= {:ok [["@b" 15.0]]}
        (q/run nvh-facts {:find {:rel "nv" :group [0] :agg [{:op :avg :arg 1}]
                                 :having [{:op :ge :agg 0 :val 15}]} :rules [nvh]})))

;; (H9) multiple clauses are ANDed
(chk "two having clauses are conjoined"
     (= {:ok [["@b" 30]]}
        (q/run nvh-facts {:find {:rel "nv" :group [0] :agg [{:op :sum :arg 1}]
                                 :having [{:op :ge :agg 0 :val 10} {:op :gt :agg 0 :val 8}]}
                          :rules [nvh]})))

;; (H10) having validation
(defn having-err? [find] (contains? (q/run vfacts {:find find :rules [deg-rule]}) :error))
(chk "having non-vector rejected"
     (having-err? {:rel "deg" :group [0] :agg [{:op :count}] :having 5}))
(chk "having bad op rejected"
     (having-err? {:rel "deg" :group [0] :agg [{:op :count}] :having [{:op :foo :agg 0 :val 1}]}))
(chk "having :agg out of range rejected"
     (having-err? {:rel "deg" :group [0] :agg [{:op :count}] :having [{:op :gt :agg 5 :val 1}]}))
(chk "having :agg negative rejected"
     (having-err? {:rel "deg" :group [0] :agg [{:op :count}] :having [{:op :gt :agg -1 :val 1}]}))
(chk "having :val non-numeric rejected"
     (having-err? {:rel "deg" :group [0] :agg [{:op :count}] :having [{:op :gt :agg 0 :val "x"}]}))
(chk "having clause non-map rejected"
     (having-err? {:rel "deg" :group [0] :agg [{:op :count}] :having [5]}))

;; (H11) CAP FIRES POST-HAVING: the exact over-cap program of test (8), but a having
;; that trims every group flips :over-limit -> {:ok}. (Same m^2 > cap group set;
;; per-pair count is 1, so :gt 1 drops all -> {:ok []}, never :over-limit.)
(let [cap q/max-results
      m (inc (long (Math/ceil (Math/sqrt (double cap)))))
      big (mapv (fn [i] (k/->Fact (str "@s" i) "p" (str "@o" i))) (range m))
      pair {:head {:rel "pair" :args [{:var "a"} {:var "b"}]}
            :body [{:rel "fact" :args [{:var "a"} {:var "pp"} {:var "qq"}]}
                   {:rel "fact" :args [{:var "b"} {:var "ss"} {:var "tt"}]}]}
      r (q/run big {:find {:rel "pair" :group [0 1] :agg [{:op :count}]
                           :having [{:op :gt :agg 0 :val 1}]} :rules [pair]})]
  (chk "over-cap group set trimmed by having -> {:ok} (cap checks survivors)"
       (and (contains? r :ok) (not (contains? r :error))))
  (chk "post-having survivors are empty here (all per-pair counts are 1)"
       (= {:ok []} r)))

;; (H12) having over a RECURSIVE-closure aggregate (reaches = transitive closure)
(let [facts [(k/->Fact "@a" "depends_on" "@b")
             (k/->Fact "@b" "depends_on" "@c")
             (k/->Fact "@c" "depends_on" "@d")]
      reaches [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}]}
               {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
                :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}
                       {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}]
      r (q/run facts {:find {:rel "reaches" :group [0] :agg [{:op :count-distinct :arg 1}]
                             :having [{:op :ge :agg 0 :val 2}]} :rules reaches})]
  (chk "having over transitive-closure aggregate keeps @a (3), @b (2), drops @c (1)"
       (= {:ok [["@a" 3] ["@b" 2]]} r)))

;; (H13) having over a STRATIFIED-NEGATION aggregate
(let [facts [(k/->Fact "@a" "depends_on" "@b")
             (k/->Fact "@b" "depends_on" "@c")
             (k/->Fact "@b" "kind" "hub")]
      base {:find {:rel "open" :group [] :agg [{:op :count}]}
            :strata [[{:head {:rel "done" :args [{:var "x"}]}
                       :body [{:rel "fact" :args [{:var "x"} "kind" "hub"]}]}]
                     [{:head {:rel "open" :args [{:var "x"}]}
                       :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}
                              {:rel "done" :args [{:var "x"}] :neg true}]}]]}]
  (chk "having (satisfied) over a stratified-negation aggregate keeps the row"
       (= {:ok [[1]]} (q/run facts (assoc-in base [:find :having] [{:op :ge :agg 0 :val 1}]))))
  (chk "having (unsatisfied) over a stratified-negation aggregate -> {:ok []}"
       (= {:ok []} (q/run facts (assoc-in base [:find :having] [{:op :gt :agg 0 :val 5}])))))

;; --- report ---
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.query aggregates:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.query aggregates:" (count fails) "FAILED") (System/exit 1))))
