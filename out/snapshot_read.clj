(ns snapshot-read
  (:require [fram.store :as c]
            [fram.schema :as s]
            [fram.types :as t]))

(defn live-cids-lp [ctx te pid]
  (filterv (fn [cid] (c/live? ctx cid)) (c/raw-by-lp ctx te pid)))

(defn seq-of [ctx cid]
  (let [txid (c/fact-tx ctx cid)]
  (if (nil? txid) 0 (c/tx-seq ctx txid))))

(defn base-version [ctx te pid]
  (reduce max 0 (map (fn [cid] (seq-of ctx cid)) (live-cids-lp ctx te pid))))

(defn current-seq [ctx]
  (c/current-sequence ctx))

(defn agent-of [ctx cid]
  (let [txid (c/fact-tx ctx cid)]
  (if (nil? txid) nil (c/tx-agent ctx txid))))

(defn observed-of [ctx cid]
  (let [txid (c/fact-tx ctx cid)]
  (if (nil? txid) nil (c/tx-observed ctx txid))))

(defn ts-of [ctx cid]
  (let [txid (c/fact-tx ctx cid)]
  (if (nil? txid) nil (c/tx-ts ctx txid))))

(defn causal-key [ctx cid]
  (let [obs (observed-of ctx cid)]
  [(if (nil? obs) (seq-of ctx cid) obs) cid (str (agent-of ctx cid))]))

(defn superseded-as-of [ctx s]
  (let [sup (c/supersedes-pred ctx)]
  (reduce (fn [acc cid] (let [p (c/fact-p ctx cid)
   r (c/fact-r ctx cid)]
  (if (and (= p sup) (<= (seq-of ctx cid) s) (int? r)) (conj acc r) acc))) #{} (c/all-facts ctx))))

(defn live-as-of [ctx s]
  (let [sup (c/supersedes-pred ctx)
   gone (superseded-as-of ctx s)]
  (reduce (fn [acc cid] (let [p (c/fact-p ctx cid)]
  (if (and (<= (seq-of ctx cid) s) (not= p sup) (not (contains? gone cid))) (conj acc cid) acc))) #{} (c/all-facts ctx))))

(defn live-as-of-lp [ctx s te pid]
  (let [all (live-as-of ctx s)]
  (filterv (fn [cid] (and (= (c/fact-l ctx cid) te) (= (c/fact-p ctx cid) pid))) all)))

(defn live-r-on [ctx cid pid]
  (if (nil? pid) nil (let [fcid (first (live-cids-lp ctx cid pid))
   f (if (nil? fcid) nil (c/fact-of ctx fcid))
   r (if (nil? f) nil (:r f))]
  (if (int? r) r nil))))

(defn withdrawal-of [ctx cid]
  (let [session (s/session! ctx)
   wb (s/resolve-predicate session "withdrawn_by")
   by-id (live-r-on ctx cid wb)]
  (if (nil? by-id) nil (let [at-id (live-r-on ctx cid (s/resolve-predicate session "withdrawn_at"))
   reason-id (live-r-on ctx cid (s/resolve-predicate session "withdrawn_reason"))]
  {:by (c/literal ctx by-id) :at (if (nil? at-id) nil (c/literal ctx at-id)) :reason (if (nil? reason-id) nil (c/literal ctx reason-id))}))))

(defn ^Boolean withdrawn? [ctx cid]
  (boolean (withdrawal-of ctx cid)))

(defn live-members [ctx te pid policy]
  (let [live (live-cids-lp ctx te pid)]
  (if (= policy :add-wins) (let [all (c/raw-by-lp ctx te pid)
   resurrected (filterv (fn [cid] (and (not (c/live? ctx cid)) (withdrawn? ctx cid))) all)]
  (vec (distinct (concat live resurrected)))) live)))

(defn view-selects [ctx ^String view]
  (let [session (s/session! ctx)
   ve (s/resolve-name session view)
   sel (s/resolve-predicate session "selects")]
  (if (or (nil? ve) (nil? sel)) nil (reduce (fn [acc cid] (let [f (c/fact-of ctx cid)
   r (if (nil? f) nil (:r f))]
  (if (int? r) (conj acc r) acc))) #{} (live-cids-lp ctx ve sel)))))

(defn pool-of [ctx view cids]
  (if (nil? view) cids (let [sel (view-selects ctx view)]
  (if (or (nil? sel) (empty? sel)) cids (let [in-view (filterv (fn [cid] (contains? sel cid)) cids)]
  (if (empty? in-view) cids in-view))))))

(defn elect [ctx view cids]
  (if (empty? cids) nil (first (sort-by (fn [cid] [cid (str (agent-of ctx cid))]) (pool-of ctx view cids)))))

(defn elect-causal [ctx view cids]
  (if (empty? cids) nil (first (sort-by (fn [cid] (causal-key ctx cid)) (pool-of ctx view cids)))))
