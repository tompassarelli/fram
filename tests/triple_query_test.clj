;; Recursive Term query core: typed plans, slot-neutral matching, occurrence
;; history, stratified negation, recursion, and bounded paging.
(require '[fram.datalog :as d]
         '[fram.query :as q]
         '[fram.store :as store]
         '[fram.types :as t])

(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(def node-a (t/triple :node :key "a"))
(def node-b (t/triple :node :key "b"))
(def node-c (t/triple :node :key "c"))
(def nested-slot1 (t/triple :relation :key "label"))
(def edge-ab (t/triple node-a :edge node-b))
(def edge-bc (t/triple node-b :edge node-c))
(def nested-middle (t/triple "left" nested-slot1 "right"))
(def nested-right (t/triple "container" :contains node-a))

(def term-store (store/new-term-store "msa-space"))
(store/commit-transaction!
 term-store
 [(store/assert-operation edge-ab)
  (store/assert-operation edge-bc)
  (store/assert-operation nested-middle)
  (store/assert-operation nested-right)])
(store/commit-transaction! term-store [(store/retract-operation nested-middle)])

(def live (store/live-propositions term-store))
(def history (store/operation-occurrences term-store))

(def slot-query
  {:find "slot-matches"
   :rules
   [{:head {:rel "slot-matches" :args [node-a {:var "s1"} {:var "s2"}]}
     :body [{:rel "triple" :args [node-a {:var "s1"} {:var "s2"}]}]}
    {:head {:rel "slot-matches" :args [{:var "s0"} nested-slot1 {:var "s2"}]}
     :body [{:rel "triple" :args [{:var "s0"} nested-slot1 {:var "s2"}]}]}
    {:head {:rel "slot-matches" :args [{:var "s0"} {:var "s1"} node-a]}
     :body [{:rel "triple" :args [{:var "s0"} {:var "s1"} node-a]}]}]})

(let [compiled (q/compile-query slot-query)
      result (q/run (conj live nested-middle) slot-query)
      rows (set (:ok result))]
  (check! "boundary compiles to typed QueryPlan" (q/query-plan? (:ok compiled)))
  (check! "recursive constant matches slot0" (contains? rows [node-a :edge node-b]))
  (check! "recursive constant matches slot1" (contains? rows ["left" nested-slot1 "right"]))
  (check! "recursive constant matches slot2" (contains? rows ["container" :contains node-a])))

(def closure-query
  {:find "reaches"
   :rules
   [{:head {:rel "reaches" :args [{:var "from"} {:var "to"}]}
     :body [{:rel "triple" :args [{:var "from"} :edge {:var "to"}]}]}
    {:head {:rel "reaches" :args [{:var "from"} {:var "to"}]}
     :body [{:rel "triple" :args [{:var "from"} :edge {:var "via"}]}
            {:rel "reaches" :args [{:var "via"} {:var "to"}]}]}]})

(let [rows (set (:ok (q/run live closure-query)))]
  (check! "recursive rule keeps recursive terms as values"
          (contains? rows [node-a node-c])))

(def negation-query
  {:find "terminal"
   :strata
   [[{:head {:rel "outgoing" :args [{:var "node"}]}
      :body [{:rel "triple" :args [{:var "node"} :edge {:var "next"}]}]}]
    [{:head {:rel "terminal" :args [{:var "node"}]}
      :body [{:rel "triple" :args [{:var "prior"} :edge {:var "node"}]}
             {:rel "outgoing" :args [{:var "node"}] :neg true}]}]]})

(check! "stratified negation keeps recursive-term equality"
        (= #{[node-c]} (set (:ok (q/run live negation-query)))))

(def occurrence-query
  {:find "events"
   :rules
   [{:head {:rel "events" :args [{:var "where"} {:var "action"} {:var "value"}]}
     :body [{:rel "occurrence"
             :args [{:var "where"} {:var "action"} {:var "value"}]}]}]})

(let [without-history (q/run live occurrence-query)
      with-history (q/run-plan-projected
                    (q/project-with-occurrences live history)
                    (:ok (q/compile-query occurrence-query)))
      rows (set (:ok with-history))
      tx1 (t/transaction-coordinate "msa-space" 1)
      tx2 (t/transaction-coordinate "msa-space" 2)]
  (check! "ordinary projection does not expose occurrence history"
          (empty? (:ok without-history)))
  (check! "assert occurrence has exact transaction and ordinal coordinate"
          (contains? rows [(t/occurrence-coordinate tx1 0) t/asserts edge-ab]))
  (check! "retract occurrence has exact transaction and ordinal coordinate"
          (contains? rows [(t/occurrence-coordinate tx2 0) t/retracts nested-middle]))
  (check! "history relation contains every operation occurrence"
          (= (count history) (count rows))))

(let [legacy-relation (str "fact" "-id")
      invalid (q/compile-query
               {:find "x"
                :rules [{:head {:rel "x" :args [{:var "v"}]}
                         :body [{:rel legacy-relation :args [{:var "v"}]}]}]})]
  (check! "four-cell identity relation has no compatibility alias"
          (contains? invalid :error)))

(def all-live-query
  {:find "all-live"
   :rules
   [{:head {:rel "all-live" :args [{:var "a"} {:var "b"} {:var "c"}]}
     :body [{:rel "triple" :args [{:var "a"} {:var "b"} {:var "c"}]}]}]})

(loop [after nil collected []]
  (let [page (q/run-page live all-live-query 1 after)
        collected2 (vec (concat collected (:ok page)))]
    (if (:more page)
      (recur (:next page) collected2)
      (check! "paging drains recursive Term rows without loss or duplication"
              (= (set collected2)
                 (set (map (fn [value]
                             [(t/triple-slot0 value)
                              (t/triple-slot1 value)
                              (t/triple-slot2 value)])
                           live)))))))

(doseq [[label passed] @checks]
  (println (if passed "PASS" "FAIL") "-" label))

(when-not (every? second @checks)
  (throw (ex-info "triple query test failed" {:checks @checks})))

(println "triple query checks:" (count @checks) "passed")
