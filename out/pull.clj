(ns pull
  (:require [fram.store :as c]
            [fram.schema :as s]
            [fram.types :as t]
            [clojure.string :as str]))

^{:line 49 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (def default-max-depth 5)

^{:line 51 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (def default-max-nodes 1000)

^{:line 56 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (def reserved-preds ^{:line 57 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} #{"name" "store-supersedes" "cardinality" "value_kind" "withdrawn_by" "withdrawn_at" "withdrawn_reason"})

^{:line 60 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (defn- ^Boolean reserved-pred? [p]
  ^{:line 60 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (contains? reserved-preds p))

^{:line 63 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (defn- clamp [v default]
  ^{:line 64 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 64 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 64 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (integer? v) ^{:line 64 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (pos? v)) ^{:line 64 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (min v default) default))

^{:line 67 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (defn- ^Boolean valid-elem? [e]
  ^{:line 68 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (letfn [(valid-subpat? [sp] (or (and (vector? sp) (every? (fn [x] (elem? x)) sp)) (and (integer? sp) (pos? sp)) (= sp :...)))
          (elem? [x] (cond
  (= x :*) true
  (string? x) (not (str/blank? x))
  (map? x) (and (seq x) (every? (fn [k] (and (string? k) (not (str/blank? k)) (valid-subpat? (get x k)))) (keys x)))
  :else false))]
  ^{:line 83 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (elem? e)))

^{:line 88 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (defn validate [root pattern opts]
  ^{:line 89 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (let [e-root ^{:line 89 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 89 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (or ^{:line 89 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 89 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (string? root) ^{:line 89 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (not ^{:line 89 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (str/blank? root))) ^{:line 90 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 90 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (vector? root) ^{:line 90 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (seq root) ^{:line 91 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (every? ^{:line 91 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (fn [x] ^{:line 92 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 92 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (string? x) ^{:line 92 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (not ^{:line 92 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (str/blank? x)))) root))) ^{:line 94 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} [] ^{:line 95 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} ["root must be a subject-name string or a non-empty vector of name strings"])
   e-pat ^{:line 96 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 96 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (vector? pattern) ^{:line 98 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (vec ^{:line 98 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (mapcat ^{:line 98 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (fn [e] ^{:line 99 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 99 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (valid-elem? e) ^{:line 100 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} [] ^{:line 101 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} [^{:line 101 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (str "malformed pattern element: " ^{:line 101 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (pr-str e))])) pattern)) ^{:line 97 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} ["pattern must be a vector"])
   e-caps ^{:line 103 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (vec ^{:line 103 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (mapcat ^{:line 103 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (fn [k] ^{:line 104 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (let [v ^{:line 104 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (get opts k)]
  ^{:line 105 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 105 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 105 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (contains? opts k) ^{:line 106 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (not ^{:line 106 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 106 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (integer? v) ^{:line 106 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (pos? v)))) ^{:line 107 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} [^{:line 107 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (str k " must be a positive integer")] ^{:line 108 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} []))) ^{:line 109 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} [:max-depth :max-nodes]))
   e-asof ^{:line 110 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (let [v ^{:line 110 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (:as-of opts)]
  ^{:line 111 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 111 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 111 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (contains? opts :as-of) ^{:line 112 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (not ^{:line 112 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (and ^{:line 112 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (integer? v) ^{:line 112 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (>= v 0)))) ^{:line 113 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} [":as-of must be a non-negative integer"] ^{:line 114 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} []))]
  ^{:line 115 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (vec ^{:line 115 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (concat e-root e-pat e-caps e-asof))))

^{:line 122 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (defn run [store-atom root pattern opts]
  ^{:line 123 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (let [errs ^{:line 123 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (validate root pattern opts)]
  ^{:line 124 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 124 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (seq errs) ^{:line 125 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} {:error errs} ^{:line 126 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (let [st store-atom
   co0 ^{:line 127 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} {:store store-atom}
   asof ^{:line 128 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (:as-of opts)
   asof-set ^{:line 129 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 129 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (some? asof) ^{:line 129 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (coord/live-as-of co0 asof) nil)
   prov? ^{:line 130 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (boolean ^{:line 130 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (:provenance opts))
   max-depth ^{:line 131 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (clamp ^{:line 131 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (:max-depth opts) default-max-depth)
   max-nodes ^{:line 132 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (clamp ^{:line 132 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (:max-nodes opts) default-max-nodes)
   state ^{:line 133 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (atom 0)]
  ^{:line 134 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (letfn [(pid-of [p] (c/value-id st p))
          (nm-of [id] (or (s/name-of st id) id))
          (flive [cids] (if (some? asof-set) (filterv (fn [x] (contains? asof-set x)) cids) (filterv (fn [x] (c/live? st x)) cids)))
          (raw-lp [lid pid] (let [m (deref st)]
  (get (:idx-by-lp m) [lid pid] [])))
          (raw-pr [pid rid] (let [m (deref st)]
  (get (:idx-by-pr m) [pid rid] [])))
          (raw-l [lid] (let [m (deref st)]
  (get (:idx-by-l m) lid [])))
          (fwd-cids [lid pid] (cond
  (some? asof-set) (flive (raw-lp lid pid))
  prov? (vec (coord/live-members co0 lid pid :add-wins))
  :else (flive (raw-lp lid pid))))
          (rev-cids [pid rid] (flive (raw-pr pid rid)))
          (leaf [cid] (let [cl (c/fact-of st cid)
   r (:r cl)
   v (if (c/value-object? st r) (c/literal st r) (nm-of r))]
  (if prov? (let [tx (c/fact-tx st cid)
   wd (if (some? asof) nil (coord/withdrawn? co0 cid))
   ts (coord/ts-of co0 cid)
   base (cond-> {:val v :cid cid :by (coord/agent-of co0 cid) :seq (if (some? tx) (c/tx-seq st tx) 0) :withdrawn (boolean wd)} (some? ts) (assoc :ts ts))]
  (if wd (let [w (coord/withdrawal-of co0 cid)]
  (assoc base :withdrawn_by (:by w) :withdrawn_at (:at w) :withdrawn_reason (:reason w))) base)) v)))
          (values [pname pid lid] (let [cids (fwd-cids lid pid)]
  (if (seq cids) (do
  (let [vs (mapv (fn [x] (leaf x)) cids)]
  (if (= "single" (s/cardinality st pname)) (first vs) vs))))))
          (subpat->pattern [k sp] (cond
  (vector? sp) sp
  (integer? sp) (if (> sp 1) [{k (dec sp)}] [])
  (= sp :...) [{k :...}]
  :else []))
          (recur-target [tid subpat depth visited] (if (> (inc depth) max-depth) {:fram/id (nm-of tid) :fram/truncated true} (node tid (nm-of tid) subpat (inc depth) visited)))
          (elem [acc lid depth visited e] (cond
  (= e :*) (reduce (fn [a pid] (let [pname (c/literal st pid)]
  (if (reserved-pred? pname) a (let [v (values pname pid lid)]
  (if (some? v) (assoc a pname v) a))))) acc (distinct (map (fn [x] (:p (c/fact-of st x))) (flive (raw-l lid)))))
  (and (string? e) (str/starts-with? e "_")) (let [pid (pid-of (subs e 1))]
  (if (nil? pid) acc (let [ls (mapv (fn [x] (:l (c/fact-of st x))) (rev-cids pid lid))]
  (assoc acc e (mapv (fn [l] (node l (nm-of l) [] (inc depth) visited)) ls)))))
  (string? e) (let [pid (pid-of e)]
  (if (nil? pid) acc (let [v (values e pid lid)]
  (if (some? v) (assoc acc e v) acc))))
  (map? e) (reduce (fn [a k] (let [sp (get e k)]
  (if (str/starts-with? k "_") (let [pid (pid-of (subs k 1))]
  (if (nil? pid) a (let [ls (mapv (fn [x] (:l (c/fact-of st x))) (rev-cids pid lid))]
  (assoc a k (mapv (fn [l] (recur-target l (subpat->pattern k sp) depth visited)) ls))))) (let [pid (pid-of k)]
  (if (nil? pid) a (let [cids (fwd-cids lid pid)
   rendered (mapv (fn [cid] (let [r (:r (c/fact-of st cid))]
  (if (c/value-object? st r) (c/literal st r) (recur-target r (subpat->pattern k sp) depth visited)))) cids)]
  (if (seq rendered) (assoc a k (if (= "single" (s/cardinality st k)) (first rendered) rendered)) a))))))) acc (keys e))
  :else acc))
          (node [rid nm pat depth visited] (cond
  (contains? visited rid) {:fram/id nm :fram/cycle true}
  (>= (deref state) max-nodes) {:fram/id nm :fram/truncated true}
  :else (do
  (swap! state (fn [n] (inc n)))
  (reduce (fn [acc e] (elem acc rid depth (conj visited rid) e)) {:fram/id nm} pat))))]
  ^{:line 269 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (let [one ^{:line 269 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (fn [r] ^{:line 270 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (let [rid ^{:line 270 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (s/resolve-name st r)]
  ^{:line 271 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 271 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (nil? rid) ^{:line 271 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} {:fram/id r} ^{:line 271 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (node rid r pattern 0 ^{:line 271 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} #{}))))]
  ^{:line 272 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (if ^{:line 272 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (vector? root) ^{:line 272 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (mapv ^{:line 272 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (fn [r] ^{:line 272 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (one r)) root) ^{:line 272 :file "/tmp/fram-lane-lane-ms2aeg8w-4bd3d5f4-7b9d-4076-bf14-33204792623b/src/pull.bclj"} (one root))))))))
