(ns fram.kernel
  (:require [clojure.string :as str]
            [fram.kernel-host :as kernel-host]))

(def single-valued (let [env (fram.kernel-host/getenv "FRAM_SINGLE_VALUED")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["title" "owner" "lead" "driver" "source" "part_of" "do_on" "valid_until" "estimate_hours" "created_at" "updated_at" "name" "body" "created_by" "committed" "outcome" "abandoned" "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"])))

(def terminal-preds (let [env (fram.kernel-host/getenv "FRAM_TERMINAL_PREDS")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["outcome" "abandoned" "superseded_by"])))

(def withdrawn-preds (let [env (fram.kernel-host/getenv "FRAM_WITHDRAWN_PREDS")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["abandoned"])))

(defn ^Boolean vec-contains? [xs ^String s]
  (loop [r xs]
  (if (empty? r) false (if (= (first r) s) true (recur (rest r))))))

(defn ^Boolean single-valued-from-env? []
  (let [env (fram.kernel-host/getenv "FRAM_SINGLE_VALUED")]
  (and (some? env) (not (= env "")))))

(defn- ^String sorted-join [xs]
  (str/join "," (vec (sort xs))))

(defn ^String vocab-fingerprint []
  (str "single=" (sorted-join single-valued) " |terminal=" (sorted-join terminal-preds) " |withdrawn=" (sorted-join withdrawn-preds)))

(defn ^String cards-fingerprint [cmap]
  (let [pairs (reduce (fn [acc e] (conj acc (str (nth e 0) "=" (if (= (nth e 1) true) "single" "multi")))) [] cmap)]
  (str/join "," (vec (sort pairs)))))

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

(defrecord PredicateSetting [predicate value])

(defn predicatesetting-predicate [r] (:predicate r))

(defn predicatesetting-value [r] (:value r))

(defrecord PredicateRegistry [by-name canonical])

(defn predicateregistry-by-name [r] (:by-name r))

(defn predicateregistry-canonical [r] (:canonical r))

(defn- ^Boolean registry-fact? [^Fact c]
  (or (= (:p c) "predicate_name") (= (:p c) "predicate_alias")))

(defn- bind-predicate-spelling [m ^String spelling ^String identity]
  (let [prior (get m spelling)]
  (if (and (some? prior) (not (= prior identity))) (throw (ex-info (str "predicate spelling collision: " spelling " resolves to both " prior " and " identity) {:predicate spelling :left prior :right identity})) (assoc m spelling identity))))

(defn ^PredicateRegistry predicate-registry [facts]
  (let [registry-facts (filterv registry-fact? facts)
   canonical (reduce (fn [m c] (if (= (:p c) "predicate_name") (assoc m (:l c) (:r c)) m)) {} registry-facts)
   by-name (reduce (fn [m c] (bind-predicate-spelling m (:r c) (:l c))) {} registry-facts)]
  (->PredicateRegistry by-name canonical)))

(defn ^String predicate-id [^PredicateRegistry reg ^String spelling]
  (if (str/starts-with? spelling "@") spelling (let [identity (get (:by-name reg) spelling)]
  (if (some? identity) identity (str "@" spelling)))))

(defn ^String predicate-key [^PredicateRegistry reg ^String spelling]
  (let [identity (get (:by-name reg) spelling)]
  (if (some? identity) identity spelling)))

(defn ^String predicate-name [^PredicateRegistry reg ^String spelling]
  (let [identity (predicate-id reg spelling)
   canonical (get (:canonical reg) identity)]
  (if (some? canonical) canonical (strip-at identity))))

(defn ^Boolean single-eff-reg? [^PredicateRegistry reg cmap ^String p]
  (let [identity (predicate-id reg p)
   by-id (get cmap identity)
   by-name (get cmap (predicate-name reg p))
   explicit (if (nil? by-id) by-name by-id)]
  (if (some? explicit) explicit (loop [xs single-valued]
  (if (empty? xs) (single? p) (if (= identity (predicate-id reg (first xs))) true (recur (rest xs))))))))

(def ref-kind-fallback {"depends_on" "ref" "part_of" "ref" "relates_to" "ref" "clarifies" "ref" "amends" "ref"})

(def acyclic-kind-fallback {"depends_on" "true" "part_of" "true"})

(defn- predicate-property-values [^PredicateRegistry reg facts ^String property]
  (let [property-id (predicate-id reg property)]
  (reduce (fn [m c] (if (= (predicate-id reg (:p c)) property-id) (assoc m (:l c) (:r c)) m)) {} facts)))

(defn- registry-map-value [^PredicateRegistry reg values ^String pname]
  (let [identity (predicate-id reg pname)]
  (reduce (fn [found e] (let [spelling (nth e 0)
   value (nth e 1)]
  (if (= identity (predicate-id reg spelling)) value found))) nil values)))

(defn- effective-predicate-value-r [^PredicateRegistry reg explicit configured fallback ^String pname]
  (let [fact-value (registry-map-value reg explicit pname)]
  (if (some? fact-value) fact-value (let [configured-value (registry-map-value reg configured pname)]
  (if (some? configured-value) configured-value (registry-map-value reg fallback pname))))))

(defn effective-predicate-value [facts configured fallback ^String pname ^String property]
  (let [reg (predicate-registry facts)
   explicit (predicate-property-values reg facts property)]
  (effective-predicate-value-r reg explicit configured fallback pname)))

(defn ^String cardinality-of [facts configured ^String pname]
  (let [reg (predicate-registry facts)
   explicit (predicate-property-values reg facts "cardinality")
   value (effective-predicate-value-r reg explicit configured {} pname)]
  (if (some? value) value (if (single-eff-reg? reg {} pname) "single" "multi"))))

(defn- predicate-settings-map [configured]
  (reduce (fn [values setting] (assoc values (:predicate setting) (:value setting))) {} configured))

(defn ^String value-kind-of [facts configured ^String pname]
  (let [value (effective-predicate-value facts (predicate-settings-map configured) ref-kind-fallback pname "value_kind")]
  (if (some? value) value "literal")))

(defn ^Boolean acyclic-of? [facts configured ^String pname]
  (= "true" (effective-predicate-value facts configured acyclic-kind-fallback pname "acyclic")))

(defn- property-predicates [^PredicateRegistry reg facts ^String property]
  (let [property-id (predicate-id reg property)]
  (mapv (fn [c] (predicate-name reg (:l c))) (filterv (fn [c] (= (predicate-id reg (:p c)) property-id)) facts))))

(defn- canonical-predicates [^PredicateRegistry reg preds]
  (uniq (mapv (fn [p] (predicate-name reg p)) preds)))

(defn- predicates-with-value [facts ^String property ^String wanted fallback-preds fallback]
  (let [reg (predicate-registry facts)
   explicit (predicate-property-values reg facts property)
   candidates (canonical-predicates reg (vec (concat fallback-preds (property-predicates reg facts property))))]
  (filterv (fn [p] (= wanted (effective-predicate-value-r reg explicit {} fallback p))) candidates)))

(defn ref-preds-of [facts]
  (predicates-with-value facts "value_kind" "ref" ref-preds-fallback ref-kind-fallback))

(defn acyclic-preds-of [facts]
  (predicates-with-value facts "acyclic" "true" acyclic-preds-fallback acyclic-kind-fallback))

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
