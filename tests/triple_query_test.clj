;; Recursive Term query core: typed plans, slot-neutral matching, explicit
;; occurrence history, stratified negation, recursion, and stable paging.
(require '[fram.datalog :as d]
         '[fram.query :as q]
         '[fram.store :as store]
         '[fram.types :as t])

(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))
(defn v [name] (d/variable name))
(defn c [value] (d/constant value))
(defn rel [name arguments] (d/relation-literal name arguments))
(defn neg [name arguments] (d/negated-literal name arguments))
(defn rule [name head body] (d/rule name head body))
(defn plan [name strata] (q/query-plan (q/relation-find name) strata))
(defn rows [result] (set (q/result-rows result)))

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

(def slot-rules
  [(rule "slot-matches" [(c node-a) (v "s1") (v "s2")]
         [(rel "triple" [(c node-a) (v "s1") (v "s2")])])
   (rule "slot-matches" [(v "s0") (c nested-slot1) (v "s2")]
         [(rel "triple" [(v "s0") (c nested-slot1) (v "s2")])])
   (rule "slot-matches" [(v "s0") (v "s1") (c node-a)]
         [(rel "triple" [(v "s0") (v "s1") (c node-a)])])])
(def slot-plan (plan "slot-matches" [slot-rules]))

(let [compiled (q/compile-query slot-plan)
      result (q/run (conj live nested-middle) slot-plan)
      found (rows result)]
  (check! "typed plan compiles" (and (q/compile-ok? compiled)
                                      (q/query-plan? (q/compiled-plan compiled))))
  (check! "recursive constant matches slot0" (contains? found [node-a :edge node-b]))
  (check! "recursive constant matches slot1" (contains? found ["left" nested-slot1 "right"]))
  (check! "recursive constant matches slot2" (contains? found ["container" :contains node-a])))

(def closure-rules
  [(rule "reaches" [(v "from") (v "to")]
         [(rel "triple" [(v "from") (c :edge) (v "to")])])
   (rule "reaches" [(v "from") (v "to")]
         [(rel "triple" [(v "from") (c :edge) (v "via")])
          (rel "reaches" [(v "via") (v "to")])])])
(def closure-plan (plan "reaches" [closure-rules]))

(check! "recursive rule keeps recursive terms as values"
        (contains? (rows (q/run live closure-plan)) [node-a node-c]))

(def negation-plan
  (plan "terminal"
        [[(rule "outgoing" [(v "node")]
                [(rel "triple" [(v "node") (c :edge) (v "next")])])]
         [(rule "terminal" [(v "node")]
                [(rel "triple" [(v "prior") (c :edge) (v "node")])
                 (neg "outgoing" [(v "node")])])]]))

(check! "stratified negation keeps recursive Term equality"
        (= #{[node-c]} (rows (q/run live negation-plan))))

(def occurrence-plan
  (plan "events"
        [[(rule "events" [(v "where") (v "action") (v "value")]
                [(rel "occurrence" [(v "where") (v "action") (v "value")])])]]))

(let [without-history (q/run live occurrence-plan)
      with-history (q/run-plan-projected
                    (q/project-with-occurrences live history)
                    occurrence-plan)
      found (rows with-history)
      tx1 (t/transaction-coordinate "msa-space" 1)
      tx2 (t/transaction-coordinate "msa-space" 2)]
  (check! "ordinary projection does not expose occurrence history"
          (and (q/result-ok? without-history)
               (empty? (q/result-rows without-history))))
  (check! "assert occurrence has exact coordinate"
          (contains? found [(t/occurrence-coordinate tx1 0) t/asserts edge-ab]))
  (check! "retract occurrence has exact coordinate"
          (contains? found [(t/occurrence-coordinate tx2 0) t/retracts nested-middle]))
  (check! "history relation contains every operation occurrence"
          (= (count history) (count found))))

(let [legacy-relation (str "fac" "t-id")
      invalid-plan
      (plan "x" [[(rule "x" [(v "value")]
                              [(rel legacy-relation [(v "value")])])]])
      invalid (q/compile-query invalid-plan)]
  (check! "legacy four-cell relation has no compatibility alias"
          (and (not (q/compile-ok? invalid))
               (= :query-unknown-relation
                  (q/error-code (first (q/compile-errors invalid)))))))

(def all-live-plan
  (plan "all-live"
        [[(rule "all-live" [(v "a") (v "b") (v "c")]
                [(rel "triple" [(v "a") (v "b") (v "c")])])]]))

(loop [after nil collected []]
  (let [page (q/run-page live all-live-plan 1 after)
        collected2 (vec (concat collected (q/page-rows page)))]
    (if (q/page-more? page)
      (recur (q/page-next page) collected2)
      (check! "paging drains recursive Term rows without loss or duplication"
              (and (q/page-ok? page)
                   (= (set collected2)
                      (set (map (fn [value]
                                  [(t/triple-slot0 value)
                                   (t/triple-slot1 value)
                                   (t/triple-slot2 value)])
                                live))))))))

(doseq [[label passed] @checks]
  (println (if passed "PASS" "FAIL") "-" label))

(when-not (every? second @checks)
  (throw (ex-info "triple query test failed" {:checks @checks})))

(println "triple query checks:" (count @checks) "passed")
