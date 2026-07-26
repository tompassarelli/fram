(ns pull
  (:require [fram.store :as c]
            [fram.schema :as s]
            [fram.types :as t]
            [clojure.string :as str]))

(def default-max-depth 5)

(def default-max-nodes 1000)

(def reserved-preds #{"withdrawn_reason" "value_kind" "withdrawn_by" "store-supersedes" "name" "withdrawn_at" "cardinality"})

(defn- ^Boolean reserved-pred? [p]
  (contains? reserved-preds p))

(defn- clamp [v default]
  (if (and (integer? v) (pos? v)) (min v default) default))

(defn- ^Boolean valid-elem? [e]
  (letfn [(valid-subpat? [sp] (or (and (vector? sp) (every? (fn [x] (elem? x)) sp)) (and (integer? sp) (pos? sp)) (= sp :...)))
          (elem? [x] (cond
  (= x :*) true
  (string? x) (not (str/blank? x))
  (map? x) (and (seq x) (every? (fn [k] (and (string? k) (not (str/blank? k)) (valid-subpat? (get x k)))) (keys x)))
  :else false))]
  (elem? e)))

(defn validate [root pattern opts]
  (let [e-root (if (or (and (string? root) (not (str/blank? root))) (and (vector? root) (seq root) (every? (fn [x] (and (string? x) (not (str/blank? x)))) root))) [] ["root must be a subject-name string or a non-empty vector of name strings"])
   e-pat (if (vector? pattern) (vec (mapcat (fn [e] (if (valid-elem? e) [] [(str "malformed pattern element: " (pr-str e))])) pattern)) ["pattern must be a vector"])
   e-caps (vec (mapcat (fn [k] (let [v (get opts k)]
  (if (and (contains? opts k) (not (and (integer? v) (pos? v)))) [(str k " must be a positive integer")] []))) [:max-depth :max-nodes]))
   e-asof (let [v (:as-of opts)]
  (if (and (contains? opts :as-of) (not (and (integer? v) (>= v 0)))) [":as-of must be a non-negative integer"] []))]
  (vec (concat e-root e-pat e-caps e-asof))))

(defn run [store-atom root pattern opts]
  (let [errs (validate root pattern opts)]
  (if (seq errs) {:error errs} (let [st store-atom
   co0 {:store store-atom}
   asof (:as-of opts)
   asof-set (if (some? asof) (coord/live-as-of co0 asof) nil)
   prov? (boolean (:provenance opts))
   max-depth (clamp (:max-depth opts) default-max-depth)
   max-nodes (clamp (:max-nodes opts) default-max-nodes)
   state (atom 0)]
  (letfn [(pid-of [p] (c/value-id st p))
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
  (let [one (fn [r] (let [rid (s/resolve-name st r)]
  (if (nil? rid) {:fram/id r} (node rid r pattern 0 #{}))))]
  (if (vector? root) (mapv (fn [r] (one r)) root) (one root))))))))
