(ns fram.kernel
  (:require [clojure.string :as str]))

(def single-valued (let [env (System/getenv "FRAM_SINGLE_VALUED")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["title" "owner" "lead" "driver" "source" "part_of" "do_on" "valid_until" "estimate_hours" "created_at" "updated_at" "name" "body" "created_by" "committed" "outcome" "abandoned" "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"])))

(def terminal-preds (let [env (System/getenv "FRAM_TERMINAL_PREDS")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["outcome" "abandoned" "superseded_by"])))

(def withdrawn-preds (let [env (System/getenv "FRAM_WITHDRAWN_PREDS")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["abandoned"])))

(defn ^Boolean vec-contains? [xs ^String s]
  (loop [r xs]
  (if (empty? r) false (if (= (first r) s) true (recur (rest r))))))

(defn ^Boolean single-valued-from-env? []
  (let [env (System/getenv "FRAM_SINGLE_VALUED")]
  (and (some? env) (not (= env "")))))

(defn- ^String sorted-join [xs]
  (str/join "," (vec (sort xs))))

(defn ^String vocab-fingerprint []
  (str "single=" (sorted-join single-valued) " |terminal=" (sorted-join terminal-preds) " |withdrawn=" (sorted-join withdrawn-preds)))

(defn ^String cards-fingerprint [cmap]
  (str/join "," (vec (sort (mapv (fn [e] (str (nth e 0) "=" (if (= (nth e 1) true) "single" "multi"))) (vec (seq cmap)))))))

(def single-valued-set (reduce (fn [m p] (assoc m p true)) {} single-valued))

(defn ^Boolean single? [^String p]
  (or (some? (get single-valued-set p)) (and (string? p) (str/starts-with? p "emoji_"))))

(defn ^Boolean single-eff? [cmap ^String p]
  (let [v (get cmap p)]
  (if (nil? v) (single? p) v)))

(defrecord Fact [l p r])

(defn fact-l [r] (:l r))

(defn fact-p [r] (:p r))

(defn fact-r [r] (:r r))

(defn ^Boolean fact-eq? [^Fact a ^Fact b]
  (and (= (:l a) (:l b)) (= (:p a) (:p b)) (= (:r a) (:r b))))

(defn q-lp [facts ^String l ^String p]
  (filterv (fn [c] (and (= (:l c) l) (= (:p c) p))) facts))

(defn q-by-l [facts ^String l]
  (filterv (fn [c] (= (:l c) l)) facts))

(defn one [facts ^String l ^String p]
  (let [hits (q-lp facts l p)]
  (if (empty? hits) nil (:r (first hits)))))

(defn many [facts ^String l ^String p]
  (mapv (fn [c] (:r c)) (q-lp facts l p)))

(defn- uniq [xs]
  (loop [r xs
   seen {}
   acc []]
  (if (empty? r) acc (let [x (first r)]
  (if (some? (get seen x)) (recur (rest r) seen acc) (recur (rest r) (assoc seen x true) (conj acc x)))))))

(defn entity-ids [facts]
  (uniq (mapv (fn [c] (:l c)) facts)))

(defn thread-ids [facts]
  (filterv (fn [s] (some? (one facts s "title"))) (entity-ids facts)))

(defn- drop-lp [facts ^String l ^String p]
  (filterv (fn [x] (not (and (= (:l x) l) (= (:p x) p)))) facts))

(defn- ^Boolean has-fact? [facts ^Fact c]
  (loop [r facts]
  (if (empty? r) false (if (fact-eq? (first r) c) true (recur (rest r))))))

(defn apply-assert-c [cmap facts ^Fact c]
  (if (single-eff? cmap (:p c)) (conj (drop-lp facts (:l c) (:p c)) c) (if (has-fact? facts c) facts (conj facts c))))

(defn apply-retract-c [cmap facts ^Fact c]
  (if (single-eff? cmap (:p c)) (drop-lp facts (:l c) (:p c)) (filterv (fn [x] (not (fact-eq? x c))) facts)))

(defn apply-assert [facts ^Fact c]
  (apply-assert-c {} facts c))

(defn apply-retract [facts ^Fact c]
  (apply-retract-c {} facts c))

(defn ^Boolean reachable-from? [succ frontier ^String target]
  (loop [front frontier
   seen #{}]
  (cond
  (empty? front) false
  (= (first front) target) true
  (contains? seen (first front)) (recur (vec (rest front)) seen)
  :else (recur (vec (concat (rest front) (succ (first front)))) (conj seen (first front))))))

(defn ^Boolean cycle? [facts ^String pred ^String te]
  (let [succ (fn [x] (many facts x pred))]
  (reachable-from? succ (succ te) te)))

(def ref-preds-fallback ["depends_on" "part_of" "relates_to" "clarifies" "amends"])

(def acyclic-preds-fallback ["depends_on" "part_of"])

(defn- ^String strip-at [^String s]
  (if (str/starts-with? s "@") (subs s 1) s))

(defn- preds-facting [facts ^String mp ^String mv]
  (uniq (mapv (fn [c] (strip-at (:l c))) (filterv (fn [c] (and (= (:p c) mp) (= (:r c) mv))) facts))))

(defn ref-preds-of [facts]
  (let [d (preds-facting facts "value_kind" "ref")]
  (if (empty? d) ref-preds-fallback d)))

(defn acyclic-preds-of [facts]
  (let [d (preds-facting facts "acyclic" "true")]
  (if (empty? d) acyclic-preds-fallback d)))

(defn violations [facts ^String te]
  (let [ids (entity-ids facts)
   rv (reduce (fn [acc p] (reduce (fn [a rt] (if (not (vec-contains? ids rt)) (conj a (str p " references missing entity " rt)) a)) acc (many facts te p))) [] (ref-preds-of facts))
   cv (reduce (fn [acc p] (if (cycle? facts p te) (conj acc (str p " cycle")) acc)) rv (acyclic-preds-of facts))]
  cv))

(defrecord Index [single bypred subjects entity-set revdep ref-preds acyclic-preds])

(defn index-single [r] (:single r))

(defn index-bypred [r] (:bypred r))

(defn index-subjects [r] (:subjects r))

(defn index-entity-set [r] (:entity-set r))

(defn index-revdep [r] (:revdep r))

(defn index-ref-preds [r] (:ref-preds r))

(defn index-acyclic-preds [r] (:acyclic-preds r))

(defn ^Index build-index [facts]
  (let [single (reduce (fn [m c] (assoc m (str (:l c) "\u0001" (:p c)) (:r c))) {} facts)
   bypred (reduce (fn [m c] (let [kk (str (:l c) "\u0001" (:p c))]
  (assoc m kk (conj (get m kk []) (:r c))))) {} facts)
   subjects (uniq (mapv (fn [c] (:l c)) facts))
   entity-set (reduce (fn [m s] (assoc m s true)) {} subjects)
   revdep (reduce (fn [m c] (if (= (:p c) "depends_on") (assoc m (:r c) (conj (get m (:r c) []) (:l c))) m)) {} facts)]
  (->Index single bypred subjects entity-set revdep (ref-preds-of facts) (acyclic-preds-of facts))))

(defn one-i [^Index idx ^String l ^String p]
  (get (:single idx) (str l "\u0001" p)))

(defn many-i [^Index idx ^String l ^String p]
  (get (:bypred idx) (str l "\u0001" p) []))

(defn ^Boolean entity-i? [^Index idx ^String te]
  (some? (get (:entity-set idx) te)))

(defn thread-ids-i [^Index idx]
  (filterv (fn [s] (some? (one-i idx s "title"))) (:subjects idx)))

(defn dependents-i [^Index idx ^String te]
  (get (:revdep idx) te []))

(defn ^Boolean cycle-i? [^Index idx ^String pred ^String te]
  (let [succ (fn [x] (many-i idx x pred))]
  (reachable-from? succ (succ te) te)))

(defn violations-i [^Index idx ^String te]
  (let [rv (reduce (fn [acc p] (reduce (fn [a rt] (if (not (entity-i? idx rt)) (conj a (str p " references missing entity " rt)) a)) acc (many-i idx te p))) [] (:ref-preds idx))
   cv (reduce (fn [acc p] (if (cycle-i? idx p te) (conj acc (str p " cycle")) acc)) rv (:acyclic-preds idx))]
  cv))
