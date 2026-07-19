;; datalog_diff_test.clj — DIFFERENTIAL / PROPERTY test: the indexed evaluator
;; (d/fixpoint) must produce a SET-IDENTICAL least fixpoint to the retained
;; scan-join oracle (d/fixpoint-oracle) on every program.
;;
;; >=500 DETERMINISTIC generated programs (seeded java.util.Random, fixed seed →
;; identical corpus every run). Each program is a random EDB (db0) + random rules
;; exercising: multi-literal positive joins, repeated-var equality joins, constants,
;; RECURSION (a body literal referencing a head relation), NEGATION over a frozen
;; base relation (range-restricted), and mixed arity (2 and 3). Negation is
;; restricted to BASE relations that are never a rule head, so it always reads a
;; frozen relation — exactly the stratified-safe case production enforces — which
;; makes the two evaluators' answers order-INdependent and thus directly comparable.
;;
;; Both evaluators take db0 + rules directly (no store needed): fixpoint's public
;; signature is (db0, rules). We compare every relation in the union of both result
;; maps as sets. Any divergence is a real bug and fails the run with the program.
;;
;; Also probes, explicitly: index lifecycle/no-leak (repeated + interleaved runs are
;; identical and independent), deep recursion, and negation finite-complement.
;;   bb -cp out tests/datalog_diff_test.clj
(require '[fram.datalog :as d])

;; the retained scan-join oracle is a PRIVATE engine reference (not a public verb);
;; reach it through its var. `oracle` has the same (db0, rules) signature as d/fixpoint.
(def oracle #'fram.datalog/fixpoint-oracle)

(def SEED 20260720)
(def N-PROGRAMS 600)                      ; > the 500 bar
(def rng (java.util.Random. SEED))

(defn ri [n] (.nextInt rng (int n)))      ; [0,n)
(defn pick [coll] (nth (vec coll) (ri (count coll))))
(defn chance [num den] (< (ri den) num))

(def consts ["k0" "k1" "k2" "k3" "k4"])   ; small domain → dense joins, real recursion
(def vars   ["x" "y" "z" "w"])
(def base-rels [["e" 2] ["f" 2] ["g" 3]]) ; base relation name + arity
(def head-rels ["p" "q"])                 ; derived heads, fixed arity 2
(def head-arity 2)

;; a term is a logic var {:var n} or a bare constant string
(defn a-term [] (if (chance 3 5) {:var (pick vars)} (pick consts)))

;; --- generate one random program -> {:db0 .. :rules .. :flags ..} -----------
(defn gen-edb []
  (into {} (for [[rel ar] base-rels]
             [rel (set (for [_ (range (+ 2 (ri 7)))]      ; 2..8 tuples
                         (vec (for [_ (range ar)] (pick consts)))))])))

(defn gen-positive-lit [allow-recursion]
  (let [[rel ar] (if (and allow-recursion (chance 2 5))
                   [(pick head-rels) head-arity]          ; recursive/derived ref
                   (pick base-rels))]
    {:rel rel :args (vec (for [_ (range ar)] (a-term))) :neg false}))

(defn vars-in [args] (set (keep (fn [t] (when (map? t) (:var t))) args)))

(defn gen-body []
  (let [n-pos (inc (ri 3))                                ; 1..3 positive literals
        pos   (vec (for [i (range n-pos)] (gen-positive-lit (pos? i))))
        bound (reduce into #{} (map (comp vars-in :args) pos))
        ;; optional negated BASE literal, range-restricted to already-bound vars
        neg   (when (and (seq bound) (chance 2 5))
                (let [[rel ar] (pick base-rels)]
                  {:rel rel :neg true
                   :args (vec (for [_ (range ar)]
                                (if (chance 3 5) {:var (pick bound)} (pick consts))))}))]
    {:body (if neg (conj pos neg) pos) :bound bound}))

(defn gen-rule []
  (let [{:keys [body bound]} (gen-body)
        ;; head vars drawn from positively-bound vars → range-safe, productive
        hargs (if (seq bound)
                (vec (for [_ (range head-arity)] {:var (pick bound)}))
                (vec (for [_ (range head-arity)] (pick consts))))]
    {:head {:rel (pick head-rels) :args hargs} :body body}))

(defn gen-program []
  (let [rules (vec (for [_ (range (inc (ri 4)))] (gen-rule)))]  ; 1..4 rules
    {:db0 (gen-edb) :rules rules}))

;; --- differential comparison -------------------------------------------------
(defn rel-set [db rel] (set (d/facts db rel)))

(defn diff-program [{:keys [db0 rules]}]
  (let [idx (d/fixpoint db0 rules)
        ora (oracle db0 rules)
        rels (into (set (keys idx)) (keys ora))
        mism (filter (fn [rel] (not= (rel-set idx rel) (rel-set ora rel))) rels)]
    {:ok (empty? mism)
     :mismatch (first mism)
     :idx idx :ora ora
     ;; corpus-shape flags
     :derived (some (fn [rel] (seq (rel-set idx rel))) head-rels)
     :recursive (some (fn [r] (some (fn [l] (and (not (:neg l))
                                                 (contains? (set head-rels) (:rel l))))
                                    (:body r)))
                      rules)
     :negated (some (fn [r] (some :neg (:body r))) rules)}))

;; --- run the corpus ----------------------------------------------------------
(def results (mapv (fn [_] (diff-program (gen-program))) (range N-PROGRAMS)))
(def fails (filter (comp not :ok) results))
(def n-derived   (count (filter :derived results)))
(def n-recursive (count (filter :recursive results)))
(def n-negated   (count (filter :negated results)))

(println (str "  generated programs: " N-PROGRAMS
              "  (with derived facts: " n-derived
              ", recursive: " n-recursive
              ", with negation: " n-negated ")"))

;; --- explicit lifecycle / leak / recursion / negation probes -----------------
;; (a) determinism + no-leak: same program twice is identical; an interleaved
;;     unrelated program does not pollute a repeated run.
(def pgm-a (gen-program))
(def pgm-b (gen-program))
(def a1 (d/fixpoint (:db0 pgm-a) (:rules pgm-a)))
(def _b (d/fixpoint (:db0 pgm-b) (:rules pgm-b)))
(def a2 (d/fixpoint (:db0 pgm-a) (:rules pgm-a)))
(def leak-free (= a1 a2))

;; (b) deep recursion: 60-node chain closure = 60*59/2 = 1770 pairs, both agree.
(def chain-db {"edge" (set (for [i (range 1 60)] [(str "n" i) (str "n" (inc i))]))})
(def chain-rules
  [{:head {:rel "reach" :args [{:var "x"} {:var "y"}]}
    :body [{:rel "edge" :args [{:var "x"} {:var "y"}] :neg false}]}
   {:head {:rel "reach" :args [{:var "x"} {:var "z"}]}
    :body [{:rel "edge" :args [{:var "x"} {:var "y"}] :neg false}
           {:rel "reach" :args [{:var "y"} {:var "z"}] :neg false}]}])
(def chain-idx (rel-set (d/fixpoint chain-db chain-rules) "reach"))
(def chain-ora (rel-set (oracle chain-db chain-rules) "reach"))
(def chain-ok (and (= chain-idx chain-ora) (= 1770 (count chain-idx))))

;; (c) negation finite-complement: node & not(down) → up. Frozen base "down".
(def neg-db {"node" #{["a"] ["b"] ["c"] ["d"]} "down" #{["a"] ["b"]}})
(def neg-rules
  [{:head {:rel "up" :args [{:var "n"}]}
    :body [{:rel "node" :args [{:var "n"}] :neg false}
           {:rel "down" :args [{:var "n"}] :neg true}]}])
(def neg-idx (rel-set (d/fixpoint neg-db neg-rules) "up"))
(def neg-ora (rel-set (oracle neg-db neg-rules) "up"))
(def neg-ok (and (= neg-idx neg-ora) (= #{["c"] ["d"]} neg-idx)))

(def checks
  [[(str "differential: all " N-PROGRAMS " generated programs match the oracle") (empty? fails)]
   ["corpus is non-trivial: >=300 programs derive facts"          (>= n-derived 300)]
   ["corpus exercises recursion: >=150 recursive programs"        (>= n-recursive 150)]
   ["corpus exercises negation: >=100 programs with negation"     (>= n-negated 100)]
   ["index no-leak: repeated run identical across an interleaved run" leak-free]
   ["deep recursion (60-chain = 1770) agrees with oracle"         chain-ok]
   ["negation finite-complement agrees with oracle"               neg-ok]])

(when (seq fails)
  (let [f (first fails)]
    (println "\n  FIRST MISMATCH on relation" (:mismatch f))
    (println "   idx:" (rel-set (:idx f) (:mismatch f)))
    (println "   ora:" (rel-set (:ora f) (:mismatch f)))))

(let [bad (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? bad)
    (println (str "\ndatalog differential: " (count checks) " / " (count checks) " PASS"))
    (do (println (str "\ndatalog differential: " (count bad) " FAILED")) (System/exit 1))))
