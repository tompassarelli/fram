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

(defrecord Claim [l p r])

(defn claim-l [r] (:l r))

(defn claim-p [r] (:p r))

(defn claim-r [r] (:r r))

(defn ^Boolean claim-eq? [^Claim a ^Claim b]
  (and (= (:l a) (:l b)) (= (:p a) (:p b)) (= (:r a) (:r b))))

(defn q-lp [claims ^String l ^String p]
  (filterv (fn [c] (and (= (:l c) l) (= (:p c) p))) claims))

(defn q-by-l [claims ^String l]
  (filterv (fn [c] (= (:l c) l)) claims))

(defn one [claims ^String l ^String p]
  (let [hits (q-lp claims l p)]
  (if (empty? hits) nil (:r (first hits)))))

(defn many [claims ^String l ^String p]
  (mapv (fn [c] (:r c)) (q-lp claims l p)))

(defn- uniq [xs]
  (loop [r xs
   seen {}
   acc []]
  (if (empty? r) acc (let [x (first r)]
  (if (some? (get seen x)) (recur (rest r) seen acc) (recur (rest r) (assoc seen x true) (conj acc x)))))))

(defn thread-ids [claims]
  (filterv (fn [s] (some? (one claims s "title"))) (uniq (mapv (fn [c] (:l c)) claims))))

(defn- drop-lp [claims ^String l ^String p]
  (filterv (fn [x] (not (and (= (:l x) l) (= (:p x) p)))) claims))

(defn- ^Boolean has-claim? [claims ^Claim c]
  (loop [r claims]
  (if (empty? r) false (if (claim-eq? (first r) c) true (recur (rest r))))))

(defn apply-assert-c [cmap claims ^Claim c]
  (if (single-eff? cmap (:p c)) (conj (drop-lp claims (:l c) (:p c)) c) (if (has-claim? claims c) claims (conj claims c))))

(defn apply-retract-c [cmap claims ^Claim c]
  (if (single-eff? cmap (:p c)) (drop-lp claims (:l c) (:p c)) (filterv (fn [x] (not (claim-eq? x c))) claims)))

(defn apply-assert [claims ^Claim c]
  (apply-assert-c {} claims c))

(defn apply-retract [claims ^Claim c]
  (apply-retract-c {} claims c))

(defn ^Boolean reachable-from? [succ frontier ^String target]
  (loop [front frontier
   seen #{}]
  (cond
  (empty? front) false
  (= (first front) target) true
  (contains? seen (first front)) (recur (vec (rest front)) seen)
  :else (recur (vec (concat (rest front) (succ (first front)))) (conj seen (first front))))))

(defn ^Boolean cycle? [claims ^String pred ^String te]
  (let [succ (fn [x] (many claims x pred))]
  (reachable-from? succ (succ te) te)))

(def ref-preds-fallback ["depends_on" "part_of" "relates_to" "clarifies" "amends"])

(def acyclic-preds-fallback ["depends_on" "part_of"])

(defn- ^String strip-at [^String s]
  (if (str/starts-with? s "@") (subs s 1) s))

(defn- preds-claiming [claims ^String mp ^String mv]
  (uniq (mapv (fn [c] (strip-at (:l c))) (filterv (fn [c] (and (= (:p c) mp) (= (:r c) mv))) claims))))

(defn ref-preds-of [claims]
  (let [d (preds-claiming claims "value_kind" "ref")]
  (if (empty? d) ref-preds-fallback d)))

(defn acyclic-preds-of [claims]
  (let [d (preds-claiming claims "acyclic" "true")]
  (if (empty? d) acyclic-preds-fallback d)))

(defn violations [claims ^String te]
  (let [ids (thread-ids claims)
   rv (reduce (fn [acc p] (reduce (fn [a rt] (if (not (vec-contains? ids rt)) (conj a (str p " references missing entity " rt)) a)) acc (many claims te p))) [] (ref-preds-of claims))
   cv (reduce (fn [acc p] (if (cycle? claims p te) (conj acc (str p " cycle")) acc)) rv (acyclic-preds-of claims))]
  cv))

(defrecord Index [single bypred subjects revdep ref-preds acyclic-preds])

(defn index-single [r] (:single r))

(defn index-bypred [r] (:bypred r))

(defn index-subjects [r] (:subjects r))

(defn index-revdep [r] (:revdep r))

(defn index-ref-preds [r] (:ref-preds r))

(defn index-acyclic-preds [r] (:acyclic-preds r))

(defn ^Index build-index [claims]
  (let [single (reduce (fn [m c] (assoc m (str (:l c) "\u0001" (:p c)) (:r c))) {} claims)
   bypred (reduce (fn [m c] (let [kk (str (:l c) "\u0001" (:p c))]
  (assoc m kk (conj (get m kk []) (:r c))))) {} claims)
   subjects (uniq (mapv (fn [c] (:l c)) claims))
   revdep (reduce (fn [m c] (if (= (:p c) "depends_on") (assoc m (:r c) (conj (get m (:r c) []) (:l c))) m)) {} claims)]
  (->Index single bypred subjects revdep (ref-preds-of claims) (acyclic-preds-of claims))))

(defn one-i [^Index idx ^String l ^String p]
  (get (:single idx) (str l "\u0001" p)))

(defn many-i [^Index idx ^String l ^String p]
  (get (:bypred idx) (str l "\u0001" p) []))

(defn thread-ids-i [^Index idx]
  (filterv (fn [s] (some? (one-i idx s "title"))) (:subjects idx)))

(defn dependents-i [^Index idx ^String te]
  (get (:revdep idx) te []))

(defn ^Boolean cycle-i? [^Index idx ^String pred ^String te]
  (let [succ (fn [x] (many-i idx x pred))]
  (reachable-from? succ (succ te) te)))

(defn violations-i [^Index idx ^String te]
  (let [rv (reduce (fn [acc p] (reduce (fn [a rt] (if (nil? (one-i idx rt "title")) (conj a (str p " references missing entity " rt)) a)) acc (many-i idx te p))) [] (:ref-preds idx))
   cv (reduce (fn [acc p] (if (cycle-i? idx p te) (conj acc (str p " cycle")) acc)) rv (:acyclic-preds idx))]
  cv))
