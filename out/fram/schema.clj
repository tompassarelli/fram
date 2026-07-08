(ns fram.schema
  (:require [fram.store :as c]
            [fram.types :as t]))

^{:line 44 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn setup! [ctx tx]
  ^{:line 45 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [name-p ^{:line 45 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "name")
   sup-p ^{:line 45 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "store-supersedes")
   card-p ^{:line 45 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "cardinality")
   kind-p ^{:line 45 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "value_kind")]
  ^{:line 46 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/set-supersedes-pred! ctx sup-p)
  ^{:line 47 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact! ctx name-p card-p ^{:line 47 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "single") tx)
  ^{:line 48 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact! ctx card-p card-p ^{:line 48 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "single") tx)
  ^{:line 49 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact! ctx kind-p card-p ^{:line 49 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "single") tx)
  ^{:line 50 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact! ctx sup-p card-p ^{:line 50 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "multi") tx)
  ctx))

^{:line 53 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn ^String cardinality [ctx ^String pname]
  ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [pid ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value-id ctx pname)
   card-pid ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value-id ctx "cardinality")
   cs ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (and ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (some? pid) ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (some? card-pid)) ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/by-lp ctx pid card-pid) ^{:line 54 :file "/home/tom/code/fram/src/fram/schema.bclj"} [])]
  ^{:line 55 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 55 :file "/home/tom/code/fram/src/fram/schema.bclj"} (empty? cs) "multi" ^{:line 55 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/literal ctx ^{:line 55 :file "/home/tom/code/fram/src/fram/schema.bclj"} (:r ^{:line 55 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact-of ctx ^{:line 55 :file "/home/tom/code/fram/src/fram/schema.bclj"} (first cs)))))))

^{:line 58 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn- replace! [ctx subj pid new-cid tx]
  ^{:line 59 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [sup ^{:line 59 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx "store-supersedes")]
  ^{:line 60 :file "/home/tom/code/fram/src/fram/schema.bclj"} (doseq [old ^{:line 60 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/by-lp ctx subj pid)]
  ^{:line 61 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 61 :file "/home/tom/code/fram/src/fram/schema.bclj"} (not ^{:line 61 :file "/home/tom/code/fram/src/fram/schema.bclj"} (= old new-cid)) ^{:line 61 :file "/home/tom/code/fram/src/fram/schema.bclj"} (do
  ^{:line 61 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact! ctx new-cid sup old tx))))))

^{:line 64 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn assert! [ctx subj ^String pname v tx]
  ^{:line 65 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [pid ^{:line 65 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx pname)
   new-cid ^{:line 65 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact! ctx subj pid ^{:line 65 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx v) tx)]
  ^{:line 66 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 66 :file "/home/tom/code/fram/src/fram/schema.bclj"} (= "single" ^{:line 66 :file "/home/tom/code/fram/src/fram/schema.bclj"} (cardinality ctx pname)) ^{:line 66 :file "/home/tom/code/fram/src/fram/schema.bclj"} (do
  ^{:line 67 :file "/home/tom/code/fram/src/fram/schema.bclj"} (replace! ctx subj pid new-cid tx)))
  new-cid))

^{:line 70 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn link! [ctx subj ^String pname target tx]
  ^{:line 71 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [pid ^{:line 71 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx pname)
   new-cid ^{:line 71 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact! ctx subj pid target tx)]
  ^{:line 72 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 72 :file "/home/tom/code/fram/src/fram/schema.bclj"} (= "single" ^{:line 72 :file "/home/tom/code/fram/src/fram/schema.bclj"} (cardinality ctx pname)) ^{:line 72 :file "/home/tom/code/fram/src/fram/schema.bclj"} (do
  ^{:line 73 :file "/home/tom/code/fram/src/fram/schema.bclj"} (replace! ctx subj pid new-cid tx)))
  new-cid))

^{:line 78 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn lookup-all [ctx subj ^String pname]
  ^{:line 79 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [pid ^{:line 79 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value-id ctx pname)
   cids ^{:line 79 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 79 :file "/home/tom/code/fram/src/fram/schema.bclj"} (some? pid) ^{:line 79 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/by-lp ctx subj pid) ^{:line 79 :file "/home/tom/code/fram/src/fram/schema.bclj"} [])]
  ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (mapv ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (fn [cid] ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [r ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (:r ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact-of ctx cid))]
  ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value-object? ctx r) ^{:line 80 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/literal ctx r) r))) cids)))

^{:line 84 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn lookup [ctx subj ^String pname]
  ^{:line 85 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [all ^{:line 85 :file "/home/tom/code/fram/src/fram/schema.bclj"} (lookup-all ctx subj pname)]
  ^{:line 85 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 85 :file "/home/tom/code/fram/src/fram/schema.bclj"} (empty? all) nil ^{:line 85 :file "/home/tom/code/fram/src/fram/schema.bclj"} (first all))))

^{:line 87 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn find-by [ctx ^String pname v]
  ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [pid ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value-id ctx pname)
   vid ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value-id ctx v)
   cids ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (and ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (some? pid) ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (some? vid)) ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/by-pr ctx pid vid) ^{:line 88 :file "/home/tom/code/fram/src/fram/schema.bclj"} [])]
  ^{:line 89 :file "/home/tom/code/fram/src/fram/schema.bclj"} (mapv ^{:line 89 :file "/home/tom/code/fram/src/fram/schema.bclj"} (fn [cid] ^{:line 89 :file "/home/tom/code/fram/src/fram/schema.bclj"} (:l ^{:line 89 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/fact-of ctx cid))) cids)))

^{:line 91 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn def-predicate! [ctx ^String pname ^String card ^String kind tx]
  ^{:line 92 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [pid ^{:line 92 :file "/home/tom/code/fram/src/fram/schema.bclj"} (c/value! ctx pname)]
  ^{:line 93 :file "/home/tom/code/fram/src/fram/schema.bclj"} (assert! ctx pid "cardinality" card tx)
  ^{:line 94 :file "/home/tom/code/fram/src/fram/schema.bclj"} (assert! ctx pid "value_kind" kind tx)
  pid))

^{:line 97 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn name! [ctx subj ^String nm tx]
  ^{:line 98 :file "/home/tom/code/fram/src/fram/schema.bclj"} (assert! ctx subj "name" nm tx))

^{:line 100 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn name-of [ctx subj]
  ^{:line 101 :file "/home/tom/code/fram/src/fram/schema.bclj"} (lookup ctx subj "name"))

^{:line 103 :file "/home/tom/code/fram/src/fram/schema.bclj"} (defn resolve-name [ctx ^String nm]
  ^{:line 104 :file "/home/tom/code/fram/src/fram/schema.bclj"} (let [ls ^{:line 104 :file "/home/tom/code/fram/src/fram/schema.bclj"} (find-by ctx "name" nm)]
  ^{:line 104 :file "/home/tom/code/fram/src/fram/schema.bclj"} (if ^{:line 104 :file "/home/tom/code/fram/src/fram/schema.bclj"} (empty? ls) nil ^{:line 104 :file "/home/tom/code/fram/src/fram/schema.bclj"} (first ls))))
