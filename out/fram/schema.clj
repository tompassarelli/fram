(ns fram.schema
  (:require [fram.store :as c]
            [fram.types :as t]))

(defn setup! [ctx tx]
  (do
  (declare predicate-ids resolve-predicate predicate-name register-predicate! alias-predicate! rename-predicate!)
  (let [name-p (c/value! ctx "name")
   sup-p (c/value! ctx "store-supersedes")
   card-p (c/value! ctx "cardinality")
   kind-p (c/value! ctx "value_kind")
   pname-p (c/value! ctx "predicate_name")
   palias-p (c/value! ctx "predicate_alias")
   single-v (c/value! ctx "single")
   multi-v (c/value! ctx "multi")
   literal-v (c/value! ctx "literal")
   ref-v (c/value! ctx "ref")]
  (c/set-supersedes-pred! ctx sup-p)
  (register-predicate! ctx "name" tx)
  (register-predicate! ctx "store-supersedes" tx)
  (register-predicate! ctx "cardinality" tx)
  (register-predicate! ctx "value_kind" tx)
  (register-predicate! ctx "predicate_name" tx)
  (register-predicate! ctx "predicate_alias" tx)
  (c/fact! ctx name-p card-p single-v tx)
  (c/fact! ctx card-p card-p single-v tx)
  (c/fact! ctx kind-p card-p single-v tx)
  (c/fact! ctx pname-p card-p single-v tx)
  (c/fact! ctx palias-p card-p multi-v tx)
  (c/fact! ctx sup-p card-p multi-v tx)
  (c/fact! ctx name-p kind-p literal-v tx)
  (c/fact! ctx card-p kind-p literal-v tx)
  (c/fact! ctx kind-p kind-p literal-v tx)
  (c/fact! ctx pname-p kind-p literal-v tx)
  (c/fact! ctx palias-p kind-p literal-v tx)
  (c/fact! ctx sup-p kind-p ref-v tx)
  ctx)))

(defn ^String cardinality [ctx ^String pname]
  (let [pid (resolve-predicate ctx pname)
   card-pid (resolve-predicate ctx "cardinality")
   cs (if (and (some? pid) (some? card-pid)) (c/by-lp ctx pid card-pid) [])]
  (if (empty? cs) "multi" (c/literal ctx (:r (c/fact-of ctx (first cs)))))))

(defn- replace! [ctx subj pid new-cid tx]
  (let [sup (c/value! ctx "store-supersedes")]
  (doseq [old (c/by-lp ctx subj pid)]
  (if (not (= old new-cid)) (do
  (c/fact! ctx new-cid sup old tx))))))

(defn assert! [ctx subj ^String pname v tx]
  (let [pid (register-predicate! ctx pname tx)
   new-cid (c/fact! ctx subj pid (c/value! ctx v) tx)]
  (if (= "single" (cardinality ctx pname)) (do
  (replace! ctx subj pid new-cid tx)))
  new-cid))

(defn link! [ctx subj ^String pname target tx]
  (let [pid (register-predicate! ctx pname tx)
   new-cid (c/fact! ctx subj pid target tx)]
  (if (= "single" (cardinality ctx pname)) (do
  (replace! ctx subj pid new-cid tx)))
  new-cid))

(defn lookup-all [ctx subj ^String pname]
  (let [pid (resolve-predicate ctx pname)
   cids (if (some? pid) (c/by-lp ctx subj pid) [])]
  (mapv (fn [cid] (let [r (:r (c/fact-of ctx cid))]
  (if (c/value-object? ctx r) (c/literal ctx r) r))) cids)))

(defn lookup [ctx subj ^String pname]
  (let [all (lookup-all ctx subj pname)]
  (if (empty? all) nil (first all))))

(defn find-by [ctx ^String pname v]
  (let [pid (resolve-predicate ctx pname)
   vid (c/value-id ctx v)
   cids (if (and (some? pid) (some? vid)) (c/by-pr ctx pid vid) [])]
  (mapv (fn [cid] (:l (c/fact-of ctx cid))) cids)))

(defn def-predicate! [ctx ^String pname ^String card ^String kind tx]
  (let [pid (register-predicate! ctx pname tx)]
  (assert! ctx pid "cardinality" card tx)
  (assert! ctx pid "value_kind" kind tx)
  pid))

(defn name! [ctx subj ^String nm tx]
  (assert! ctx subj "name" nm tx))

(defn name-of [ctx subj]
  (lookup ctx subj "name"))

(defn resolve-name [ctx ^String nm]
  (let [ls (find-by ctx "name" nm)]
  (if (empty? ls) nil (first ls))))

(defn- predicate-ids [ctx ^String spelling]
  (let [vid (c/value-id ctx spelling)
   name-pid (c/value-id ctx "predicate_name")
   alias-pid (c/value-id ctx "predicate_alias")
   named (if (and (some? vid) (some? name-pid)) (c/by-pr ctx name-pid vid) [])
   aliased (if (and (some? vid) (some? alias-pid)) (c/by-pr ctx alias-pid vid) [])
   ids (mapv (fn [cid] (:l (c/fact-of ctx cid))) (concat named aliased))]
  (vec (distinct ids))))

(defn resolve-predicate [ctx ^String spelling]
  (let [ids (predicate-ids ctx spelling)]
  (cond
  (> (count ids) 1) (throw (ex-info (str "predicate spelling collision: " spelling) {:predicate spelling :ids ids}))
  (= (count ids) 1) (first ids)
  :else (c/value-id ctx spelling))))

(defn ^String predicate-name [ctx pid]
  (let [name-pid (c/value-id ctx "predicate_name")
   cids (if (some? name-pid) (c/by-lp ctx pid name-pid) [])
   raw (if (empty? cids) (if (c/value-object? ctx pid) (c/literal ctx pid) pid) (c/literal ctx (:r (c/fact-of ctx (last cids)))))]
  (if (string? raw) raw (str raw))))

(defn register-predicate! [ctx ^String spelling tx]
  (let [resolved (resolve-predicate ctx spelling)
   pid (if (some? resolved) resolved (c/value! ctx spelling))
   name-pid (c/value! ctx "predicate_name")
   alias-pid (c/value! ctx "predicate_alias")
   name-cids (c/by-lp ctx pid name-pid)]
  (if (empty? name-cids) (do
  (c/fact! ctx pid name-pid (c/value! ctx spelling) tx)))
  (let [canonical (predicate-name ctx pid)
   default-alias (str ":" canonical)
   alias-ids (predicate-ids ctx default-alias)]
  (if (and (not (empty? alias-ids)) (not (and (= 1 (count alias-ids)) (= pid (first alias-ids))))) (do
  (throw (ex-info (str "predicate alias collision: " default-alias) {:predicate canonical :alias default-alias :ids alias-ids}))))
  (if (empty? alias-ids) (do
  (c/fact! ctx pid alias-pid (c/value! ctx default-alias) tx))))
  pid))

(defn alias-predicate! [ctx ^String spelling ^String alias tx]
  (let [pid (register-predicate! ctx spelling tx)
   ids (predicate-ids ctx alias)]
  (if (and (not (empty? ids)) (not (and (= 1 (count ids)) (= pid (first ids))))) (do
  (throw (ex-info (str "predicate spelling collision: " alias) {:predicate spelling :alias alias :ids ids}))))
  (if (empty? ids) (do
  (c/fact! ctx pid (c/value! ctx "predicate_alias") (c/value! ctx alias) tx)))
  pid))

(defn rename-predicate! [ctx ^String spelling ^String new-name tx]
  (let [pid (register-predicate! ctx spelling tx)
   ids (predicate-ids ctx new-name)]
  (if (and (not (empty? ids)) (not (and (= 1 (count ids)) (= pid (first ids))))) (do
  (throw (ex-info (str "predicate spelling collision: " new-name) {:predicate spelling :new-name new-name :ids ids}))))
  (let [old-name (predicate-name ctx pid)]
  (if (not (= old-name new-name)) (do
  (let [old-ids (predicate-ids ctx old-name)
   alias-pid (c/value! ctx "predicate_alias")
   old-vid (c/value! ctx old-name)
   aliases (c/by-pr ctx alias-pid old-vid)]
  (if (and (= 1 (count old-ids)) (= pid (first old-ids)) (empty? aliases)) (do
  (c/fact! ctx pid alias-pid old-vid tx))))
  (assert! ctx pid "predicate_name" new-name tx)
  (alias-predicate! ctx new-name (str ":" new-name) tx))))
  pid))
