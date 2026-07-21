(ns fram.datalog
  (:require [fram.store :as c]
            [fram.types :as t]))

(def ^:dynamic *query-control* nil)

(defn- query-check []
  (if (nil? *query-control*) nil (let [steps (.incrementAndGet (:steps *query-control*))
   now (System/nanoTime)
   cancelled (deref (:cancelled *query-control*))
   code (cond
  (some? cancelled) :query-cancelled
  (> steps (:max-steps *query-control*)) :query-work-limit
  (>= now (:deadline-ns *query-control*)) :query-time-limit
  :else nil)]
  (if (nil? code) nil (throw (ex-info (str "query evaluation stopped: " (name code)) {:type :fram-query-abort :code code :reason cancelled :steps steps :max-steps (:max-steps *query-control*) :timeout-ms (:timeout-ms *query-control*)}))))))

(defn v [name]
  {:var name})

(defn lit [^String rel args]
  {:rel rel :args args :neg false})

(defn nlit [^String rel args]
  {:rel rel :args args :neg true})

(defn rule [^String hrel hargs body]
  {:head {:rel hrel :args hargs} :body body})

(defn- ^Boolean var? [t]
  (and (map? t) (contains? t :var)))

(defn- ^Boolean pred? [litt]
  (and (map? litt) (contains? litt :pred)))

(defn edb [ctx]
  (reduce (fn [db cid] (let [cl (c/fact-of ctx cid)
   l (:l cl)
   p (:p cl)
   r (:r cl)
   db1 (update db "triple" (fn [s] (conj (or s #{}) [l p r])))]
  (update db1 "fact-id" (fn [s] (conj (or s #{}) [cid l p r]))))) {} (c/current-facts ctx)))

(defn- unify [term val subst]
  (if (var? term) (let [n (:var term)]
  (if (contains? subst n) (if (= (get subst n) val) subst nil) (assoc subst n val))) (if (= term val) subst nil)))

(defn- unify-args [args tuple subst]
  (query-check)
  (if (not (= (count args) (count tuple))) nil (loop [a args
   t tuple
   s subst]
  (cond
  (nil? s) nil
  (empty? a) s
  :else (recur (rest a) (rest t) (unify (first a) (first t) s))))))

(defn- ground [args subst]
  (mapv (fn [t] (if (var? t) (get subst (:var t)) t)) args))

(defn- num-of [v]
  (let [s (str v)
   l (parse-long s)]
  (if (some? l) (double l) (parse-double s))))

(defn- ^Boolean eval-pred [litt subst]
  (let [op (:pred litt)
   g (ground (:args litt) subst)
   a (nth g 0)
   b (nth g 1)]
  (cond
  (= op :eq) (= a b)
  (= op :ne) (not (= a b))
  :else (let [na (num-of a)
   nb (num-of b)]
  (if (or (nil? na) (nil? nb)) false (cond
  (= op :lt) (< na nb)
  (= op :le) (<= na nb)
  (= op :gt) (> na nb)
  (= op :ge) (>= na nb)
  :else false))))))

(defn- match-lit [db litt subst]
  (cond
  (pred? litt) (if (eval-pred litt subst) [subst] [])
  (:neg litt) (let [g (ground (:args litt) subst)]
  (if (contains? (get db (:rel litt) #{}) g) [] [subst]))
  :else (let [tuples (vec (get db (:rel litt) #{}))
   args (:args litt)]
  (filterv (fn [s] (some? s)) (mapv (fn [tup] (unify-args args tup subst)) tuples)))))

(defn- eval-body [db body subst]
  (reduce (fn [substs litt] (reduce (fn [acc s] (vec (concat acc (match-lit db litt s)))) [] substs)) [subst] body))

(defn- derive-rule [db r]
  (let [head (:head r)]
  (reduce (fn [acc s] (conj acc (ground (:args head) s))) #{} (eval-body db (:body r) {}))))

(defn- positive-positions [body]
  (loop [i 0
   ls body
   acc []]
  (if (empty? ls) acc (recur (+ i 1) (rest ls) (if (or (:neg (first ls)) (:pred (first ls))) acc (conj acc i))))))

(defn- eval-body-pinned [db delta body pin]
  (loop [i 0
   ls body
   substs [{}]]
  (if (empty? ls) substs (let [litt (first ls)
   src (if (:neg litt) db (if (= i pin) delta db))
   substs2 (reduce (fn [acc s] (vec (concat acc (match-lit src litt s)))) [] substs)]
  (recur (+ i 1) (rest ls) substs2)))))

(defn- derive-rule-delta [db delta r]
  (let [head (:head r)
   body (:body r)]
  (reduce (fn [acc pin] (reduce (fn [a s] (conj a (ground (:args head) s))) acc (eval-body-pinned db delta body pin))) #{} (positive-positions body))))

(defn- rule-head-rels [rules]
  (vec (reduce (fn [acc r] (conj acc (:rel (:head r)))) #{} rules)))

(defn- new-only [cand db rels]
  (reduce (fn [acc rel] (let [n (reduce (fn [s t] (if (contains? (get db rel #{}) t) s (conj s t))) #{} (get cand rel #{}))]
  (if (empty? n) acc (assoc acc rel n)))) {} rels))

(defn- db-merge [db delta rels]
  (reduce (fn [acc rel] (let [d (get delta rel #{})]
  (if (empty? d) acc (update acc rel (fn [s] (reduce (fn [a t] (conj a t)) (or s #{}) d)))))) db rels))

(defn- ^Boolean delta-empty? [delta rels]
  (loop [rs rels]
  (if (empty? rs) true (if (empty? (get delta (first rs) #{})) (recur (rest rs)) false))))

(defn- snr-round [db delta rules rels]
  (let [cand (reduce (fn [acc r] (let [rel (:rel (:head r))
   hs (derive-rule-delta db delta r)]
  (update acc rel (fn [s] (reduce (fn [a h] (conj a h)) (or s #{}) hs))))) {} rules)]
  (new-only cand db rels)))

(defn- fixpoint-oracle [db0 rules]
  (let [rels (rule-head-rels rules)
   seeded (reduce (fn [d r] (let [rel (:rel (:head r))
   heads (derive-rule db0 r)]
  (update d rel (fn [s] (reduce (fn [a h] (conj a h)) (or s #{}) heads))))) db0 rules)
   delta0 (new-only seeded db0 rels)]
  (loop [db seeded
   delta delta0]
  (if (delta-empty? delta rels) db (let [nw (snr-round db delta rules rels)]
  (recur (db-merge db nw rels) nw))))))

(defn- index-tuple [idx ^String rel tup]
  (query-check)
  (loop [i 0
   acc idx]
  (if (>= i (count tup)) acc (let [val (nth tup i)
   byrel (get acc rel {})
   bypos (get byrel i {})
   cur (get bypos val [])
   bypos2 (assoc byrel i (assoc bypos val (conj cur tup)))]
  (recur (+ i 1) (assoc acc rel bypos2))))))

(defn- index-rel [idx ^String rel tuples]
  (reduce (fn [acc t] (index-tuple acc rel t)) idx tuples))

(defn- build-index [db]
  (reduce (fn [acc rel] (index-rel acc rel (vec (get db rel #{})))) {} (vec (keys db))))

(defn- index-delta [idx delta rels]
  (reduce (fn [acc rel] (index-rel acc rel (vec (get delta rel #{})))) idx rels))

(defn- idx-lookup [idx ^String rel i val]
  (get (get (get idx rel {}) i {}) val []))

(defn- indexed-candidates [idx ^String rel args subst]
  (loop [i 0
   as args
   best nil
   found false]
  (if (empty? as) (if found best nil) (let [a (first as)]
  (if (var? a) (if (contains? subst (:var a)) (let [c (idx-lookup idx rel i (get subst (:var a)))]
  (recur (+ i 1) (rest as) (if (or (not found) (< (count c) (count best))) c best) true)) (recur (+ i 1) (rest as) best found)) (let [c (idx-lookup idx rel i a)]
  (recur (+ i 1) (rest as) (if (or (not found) (< (count c) (count best))) c best) true)))))))

(defn- match-lit-idx [db idx litt subst]
  (cond
  (pred? litt) (if (eval-pred litt subst) [subst] [])
  (:neg litt) (let [g (ground (:args litt) subst)]
  (if (contains? (get db (:rel litt) #{}) g) [] [subst]))
  :else (let [args (:args litt)
   rel (:rel litt)
   cands (indexed-candidates idx rel args subst)]
  (if (nil? cands) (let [tuples (vec (get db rel #{}))]
  (filterv (fn [s] (some? s)) (mapv (fn [tup] (unify-args args tup subst)) tuples))) (filterv (fn [s] (some? s)) (mapv (fn [tup] (unify-args args tup subst)) cands))))))

(defn- eval-body-idx [db idx body subst]
  (reduce (fn [substs litt] (reduce (fn [acc s] (vec (concat acc (match-lit-idx db idx litt s)))) [] substs)) [subst] body))

(defn- derive-rule-idx [db idx r]
  (let [head (:head r)]
  (reduce (fn [acc s] (conj acc (ground (:args head) s))) #{} (eval-body-idx db idx (:body r) {}))))

(defn- eval-body-pinned-idx [db idx delta delta-idx body pin]
  (loop [i 0
   ls body
   substs [{}]]
  (if (empty? ls) substs (let [litt (first ls)
   use-delta (and (not (:neg litt)) (= i pin))
   substs2 (reduce (fn [acc s] (vec (concat acc (if use-delta (match-lit-idx delta delta-idx litt s) (match-lit-idx db idx litt s))))) [] substs)]
  (recur (+ i 1) (rest ls) substs2)))))

(defn- derive-rule-delta-idx [db idx delta delta-idx r]
  (let [head (:head r)
   body (:body r)]
  (reduce (fn [acc pin] (reduce (fn [a s] (conj a (ground (:args head) s))) acc (eval-body-pinned-idx db idx delta delta-idx body pin))) #{} (positive-positions body))))

(defn- snr-round-idx [db idx delta rules rels]
  (let [delta-idx (build-index delta)
   cand (reduce (fn [acc r] (let [rel (:rel (:head r))
   hs (derive-rule-delta-idx db idx delta delta-idx r)]
  (update acc rel (fn [s] (reduce (fn [a h] (conj a h)) (or s #{}) hs))))) {} rules)]
  (new-only cand db rels)))

(defn fixpoint [db0 rules]
  (let [rels (rule-head-rels rules)
   idx0 (build-index db0)
   seeded (reduce (fn [d r] (let [rel (:rel (:head r))
   heads (derive-rule-idx db0 idx0 r)]
  (update d rel (fn [s] (reduce (fn [a h] (conj a h)) (or s #{}) heads))))) db0 rules)
   delta0 (new-only seeded db0 rels)
   idx-seeded (index-delta idx0 delta0 rels)]
  (loop [db seeded
   idx idx-seeded
   delta delta0]
  (if (delta-empty? delta rels) db (let [nw (snr-round-idx db idx delta rules rels)]
  (recur (db-merge db nw rels) (index-delta idx nw rels) nw))))))

(defn run-rules [ctx rules]
  (fixpoint (edb ctx) rules))

(defn run-strata [ctx strata]
  (reduce (fn [db stratum] (fixpoint db stratum)) (edb ctx) strata))

(defn- neg-lits [stratum]
  (reduce (fn [acc r] (vec (concat acc (filterv (fn [l] (:neg l)) (:body r))))) [] stratum))

(defn strata-violations [strata]
  (loop [i 0
   lower #{}
   probs []]
  (if (>= i (count strata)) probs (let [stratum (nth strata i)
   this-rels (reduce (fn [acc r] (conj acc (:rel (:head r)))) #{} stratum)
   bad-fwd (filterv (fn [nl] (not (or (= "triple" (:rel nl)) (or (= "fact-id" (:rel nl)) (contains? lower (:rel nl)))))) (neg-lits stratum))
   bad-self (filterv (fn [nl] (contains? this-rels (:rel nl))) (neg-lits stratum))
   probs2 (vec (concat probs (concat (mapv (fn [nl] (str "stratum " i ": negated '" (:rel nl) "' is not EDB or a lower stratum")) bad-fwd) (mapv (fn [nl] (str "stratum " i ": negated '" (:rel nl) "' is also derived in the SAME stratum (recursion through negation — not stratifiable)")) bad-self))))]
  (recur (+ i 1) (reduce (fn [acc rel] (conj acc rel)) lower this-rels) probs2)))))

(defn facts [db ^String rel]
  (vec (get db rel #{})))
