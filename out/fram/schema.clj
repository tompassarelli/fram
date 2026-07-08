(ns fram.schema
  (:require [fram.store :as c]
            [fram.types :as t]))

(defn setup! [ctx tx]
  (let [name-p (c/value! ctx "name")
   sup-p (c/value! ctx "store-supersedes")
   card-p (c/value! ctx "cardinality")
   kind-p (c/value! ctx "value_kind")]
  (c/set-supersedes-pred! ctx sup-p)
  (c/fact! ctx name-p card-p (c/value! ctx "single") tx)
  (c/fact! ctx card-p card-p (c/value! ctx "single") tx)
  (c/fact! ctx kind-p card-p (c/value! ctx "single") tx)
  (c/fact! ctx sup-p card-p (c/value! ctx "multi") tx)
  ctx))

(defn ^String cardinality [ctx ^String pname]
  (let [pid (c/value-id ctx pname)
   card-pid (c/value-id ctx "cardinality")
   cs (if (and (some? pid) (some? card-pid)) (c/by-lp ctx pid card-pid) [])]
  (if (empty? cs) "multi" (c/literal ctx (:r (c/fact-of ctx (first cs)))))))

(defn- replace! [ctx subj pid new-cid tx]
  (let [sup (c/value! ctx "store-supersedes")]
  (doseq [old (c/by-lp ctx subj pid)]
  (if (not (= old new-cid)) (do
  (c/fact! ctx new-cid sup old tx))))))

(defn assert! [ctx subj ^String pname v tx]
  (let [pid (c/value! ctx pname)
   new-cid (c/fact! ctx subj pid (c/value! ctx v) tx)]
  (if (= "single" (cardinality ctx pname)) (do
  (replace! ctx subj pid new-cid tx)))
  new-cid))

(defn link! [ctx subj ^String pname target tx]
  (let [pid (c/value! ctx pname)
   new-cid (c/fact! ctx subj pid target tx)]
  (if (= "single" (cardinality ctx pname)) (do
  (replace! ctx subj pid new-cid tx)))
  new-cid))

(defn lookup-all [ctx subj ^String pname]
  (let [pid (c/value-id ctx pname)
   cids (if (some? pid) (c/by-lp ctx subj pid) [])]
  (mapv (fn [cid] (let [r (:r (c/fact-of ctx cid))]
  (if (c/value-object? ctx r) (c/literal ctx r) r))) cids)))

(defn lookup [ctx subj ^String pname]
  (let [all (lookup-all ctx subj pname)]
  (if (empty? all) nil (first all))))

(defn find-by [ctx ^String pname v]
  (let [pid (c/value-id ctx pname)
   vid (c/value-id ctx v)
   cids (if (and (some? pid) (some? vid)) (c/by-pr ctx pid vid) [])]
  (mapv (fn [cid] (:l (c/fact-of ctx cid))) cids)))

(defn def-predicate! [ctx ^String pname ^String card ^String kind tx]
  (let [pid (c/value! ctx pname)]
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
