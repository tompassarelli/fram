;; datalog_shared_index_test.clj — the SHARED base-index path (d/base-index +
;; d/fixpoint-bi) must produce the SAME least fixpoint as the classic per-call
;; d/fixpoint (which datalog_diff_test already pins to the scan-join oracle).
;;
;; This is the engine half of the coordinator's per-version projection cache: a
;; base index built ONCE over an immutable EDB is threaded, unchanged, through
;; every query and every stratum. The safety invariant is that base-relation
;; tuples are invariant across a whole stratified evaluation (base rels are never
;; rule heads), so reusing their index while indexing only the freshly-derived
;; relations of higher strata is exactly correct.
;;
;; >=400 DETERMINISTIC generated programs (seeded Random). Each asserts, over one
;; random EDB:
;;   (1) single group: fixpoint-bi(edb, base-index(edb), rules) == fixpoint(edb, rules)
;;   (2) MULTI-STRATUM reuse: threading ONE base-index through a reduce over
;;       several rule groups (db grows with derived relations each group) ==
;;       the plain per-call fixpoint reduce.
;;   (3) no leak: the same base-index reused for program A, then B, then A again
;;       yields identical results for A.
;;   bb -cp out tests/datalog_shared_index_test.clj
(require '[fram.datalog :as d])

(def SEED 20260724)
(def N-PROGRAMS 500)
(def rng (java.util.Random. SEED))
(defn ri [n] (.nextInt rng (int n)))
(defn pick [coll] (nth (vec coll) (ri (count coll))))
(defn chance [num den] (< (ri den) num))

(def consts ["k0" "k1" "k2" "k3" "k4"])
(def vars ["x" "y" "z" "w"])
(def base-rels [["e" 2] ["f" 2] ["g" 3]])   ; the EDB relations (never heads)
(def head-rels ["p" "q"])                    ; derived heads, arity 2 (disjoint from base)
(def head-arity 2)

(defn a-term [] (if (chance 3 5) {:var (pick vars)} (pick consts)))
(defn gen-edb []
  (into {} (for [[rel ar] base-rels]
             [rel (set (for [_ (range (+ 2 (ri 7)))]
                         (vec (for [_ (range ar)] (pick consts)))))])))
(defn gen-positive-lit [allow-recursion]
  (let [[rel ar] (if (and allow-recursion (chance 2 5))
                   [(pick head-rels) head-arity]
                   (pick base-rels))]
    {:rel rel :args (vec (for [_ (range ar)] (a-term))) :neg false}))
(defn vars-in [args] (set (keep (fn [t] (when (map? t) (:var t))) args)))
(defn gen-rule []
  (let [n-pos (inc (ri 3))
        pos (vec (for [i (range n-pos)] (gen-positive-lit (pos? i))))
        bound (reduce into #{} (map (comp vars-in :args) pos))
        hargs (if (seq bound)
                (vec (for [_ (range head-arity)] {:var (pick bound)}))
                (vec (for [_ (range head-arity)] (pick consts))))]
    {:head {:rel (pick head-rels) :args hargs} :body pos}))
(defn gen-group [] (vec (for [_ (range (inc (ri 3)))] (gen-rule))))   ; 1..3 rules

;; a program: one EDB + 1..3 rule GROUPS (treated as strata for the reduce).
(defn gen-program []
  {:db0 (gen-edb) :strata (vec (for [_ (range (inc (ri 3)))] (gen-group)))})

(defn eval-plain [edb strata]
  (reduce (fn [acc s] (d/fixpoint acc s)) edb strata))
(defn eval-shared [edb strata]
  (let [bi (d/base-index edb)]
    (reduce (fn [acc s] (d/fixpoint-bi acc bi s)) edb strata)))

(defn diff-program [{:keys [db0 strata]}]
  (let [plain (eval-plain db0 strata)
        shared (eval-shared db0 strata)
        rels (into (set (keys plain)) (keys shared))]
    {:ok (every? (fn [rel] (= (set (d/facts plain rel)) (set (d/facts shared rel)))) rels)
     :derived (some (fn [rel] (seq (d/facts plain rel))) head-rels)
     :multi (> (count strata) 1)}))

(def results (mapv (fn [_] (diff-program (gen-program))) (range N-PROGRAMS)))
(def fails (filter (comp not :ok) results))
(def n-derived (count (filter :derived results)))
(def n-multi (count (filter :multi results)))

;; single-group: fixpoint-bi with a fresh base-index equals fixpoint (arity {} path).
(def sg-db {"e" #{["k0" "k1"] ["k1" "k2"] ["k2" "k3"]}})
(def sg-rules
  [{:head {:rel "p" :args [{:var "x"} {:var "y"}]}
    :body [{:rel "e" :args [{:var "x"} {:var "y"}] :neg false}]}
   {:head {:rel "p" :args [{:var "x"} {:var "z"}]}
    :body [{:rel "e" :args [{:var "x"} {:var "y"}] :neg false}
           {:rel "p" :args [{:var "y"} {:var "z"}] :neg false}]}])
(def sg-ok
  (= (set (d/facts (d/fixpoint sg-db sg-rules) "p"))
     (set (d/facts (d/fixpoint-bi sg-db (d/base-index sg-db) sg-rules) "p"))))

;; no-leak: one base-index reused for A, then B, then A again — A is stable.
(def pa (gen-program))
(def pb (gen-program))
(def bi-a (d/base-index (:db0 pa)))
(def a1 (reduce (fn [acc s] (d/fixpoint-bi acc bi-a s)) (:db0 pa) (:strata pa)))
(def _b (let [bi-b (d/base-index (:db0 pb))]
          (reduce (fn [acc s] (d/fixpoint-bi acc bi-b s)) (:db0 pb) (:strata pb))))
(def a2 (reduce (fn [acc s] (d/fixpoint-bi acc bi-a s)) (:db0 pa) (:strata pa)))
(def leak-free (= a1 a2))

(println (str "  generated programs: " N-PROGRAMS
              "  (derived: " n-derived ", multi-stratum: " n-multi ")"))

(def checks
  [[(str "shared base-index matches per-call fixpoint on all " N-PROGRAMS " programs") (empty? fails)]
   ["corpus is non-trivial: >=200 programs derive facts"        (>= n-derived 200)]
   ["corpus exercises multi-stratum reuse: >=200 programs"      (>= n-multi 200)]
   ["single-group fixpoint-bi (fresh base-index) == fixpoint"   sg-ok]
   ["base-index reuse across A,B,A leaves A identical (no leak)" leak-free]])

(def failed (filter (comp not second) checks))
(doseq [[label ok] checks] (println (str "  [" (if ok "PASS" "FAIL") "]  " label)))
(println (str "\ndatalog shared-index: " (- (count checks) (count failed)) " / " (count checks) " PASS"))
(when (seq failed)
  (println "first mismatching program:" (pr-str (:db0 (:program (first fails)))))
  (System/exit 1))
