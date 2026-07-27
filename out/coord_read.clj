(ns coord-read
  (:require [fram.store :as c]
            [fram.schema :as s]))

(defn live-cids-lp [ctx te pid]
  (c/by-lp ctx te pid))

(defn seq-of [ctx cid]
  (let [m (deref ctx)
   txid (get (:tx-of m) cid)
   tx (get (:txs m) txid)
   sq (if (nil? tx) nil (:seq tx))]
  (if (int? sq) sq 0)))

(defn base-version [ctx te pid]
  (reduce max 0 (map (fn [cid] (seq-of ctx cid)) (live-cids-lp ctx te pid))))

(defn current-seq [ctx]
  (let [m (deref ctx)]
  (:next-seq m)))

(defn agent-of [ctx cid]
  (let [m (deref ctx)
   txid (get (:tx-of m) cid)
   tx (get (:txs m) txid)]
  (if (nil? tx) nil (:agent tx))))

(defn observed-of [ctx cid]
  (let [m (deref ctx)
   txid (get (:tx-of m) cid)
   tx (get (:txs m) txid)
   obs (if (nil? tx) nil (:observed tx))]
  (if (int? obs) obs nil)))

(defn ts-of [ctx cid]
  (let [m (deref ctx)
   txid (get (:tx-of m) cid)
   tx (get (:txs m) txid)
   ts (if (nil? tx) nil (:ts tx))]
  (if (string? ts) ts nil)))

(defn causal-key [ctx cid]
  (let [obs (observed-of ctx cid)]
  [(if (nil? obs) (seq-of ctx cid) obs) cid (str (agent-of ctx cid))]))

(defn superseded-as-of [ctx s]
  (let [m (deref ctx)
   sup (:supersedes-pred m)]
  (reduce (fn [acc cid] (let [f (get (:facts m) cid)
   p (if (nil? f) nil (:p f))
   r (if (nil? f) nil (:r f))]
  (if (and (= p sup) (<= (seq-of ctx cid) s) (int? r)) (conj acc r) acc))) #{} (vec (keys (:facts m))))))

(defn live-as-of [ctx s]
  (let [m (deref ctx)
   sup (:supersedes-pred m)
   gone (superseded-as-of ctx s)]
  (reduce (fn [acc cid] (let [f (get (:facts m) cid)
   p (if (nil? f) nil (:p f))]
  (if (and (some? f) (<= (seq-of ctx cid) s) (not= p sup) (not (contains? gone cid))) (conj acc cid) acc))) #{} (vec (keys (:facts m))))))

(defn live-as-of-lp [ctx s te pid]
  (let [m (deref ctx)
   all (live-as-of ctx s)]
  (filterv (fn [cid] (let [f (get (:facts m) cid)]
  (and (some? f) (= (:l f) te) (= (:p f) pid)))) all)))

(defn live-r-on [ctx cid pid]
  (if (nil? pid) nil (let [fcid (first (c/by-lp ctx cid pid))
   f (if (nil? fcid) nil (c/fact-of ctx fcid))
   r (if (nil? f) nil (:r f))]
  (if (int? r) r nil))))

(defn withdrawal-of [ctx cid]
  (let [wb (c/value-id ctx "withdrawn_by")
   by-id (live-r-on ctx cid wb)]
  (if (nil? by-id) nil (let [at-id (live-r-on ctx cid (c/value-id ctx "withdrawn_at"))
   reason-id (live-r-on ctx cid (c/value-id ctx "withdrawn_reason"))]
  {:by (c/literal ctx by-id) :at (if (nil? at-id) nil (c/literal ctx at-id)) :reason (if (nil? reason-id) nil (c/literal ctx reason-id))}))))

(defn ^Boolean withdrawn? [ctx cid]
  (boolean (withdrawal-of ctx cid)))

(defn live-members [ctx te pid policy]
  (let [m (deref ctx)
   live (live-cids-lp ctx te pid)]
  (if (= policy :add-wins) (let [all (get (:idx-by-lp m) [te pid] [])
   resurrected (filterv (fn [cid] (and (contains? (:superseded m) cid) (withdrawn? ctx cid))) all)]
  (vec (distinct (concat live resurrected)))) live)))

(defn view-selects [ctx ^String view]
  (let [ve (s/resolve-name ctx view)
   sel (c/value-id ctx "selects")]
  (if (or (nil? ve) (nil? sel)) nil (reduce (fn [acc cid] (let [f (c/fact-of ctx cid)
   r (if (nil? f) nil (:r f))]
  (if (int? r) (conj acc r) acc))) #{} (c/by-lp ctx ve sel)))))

(defn pool-of [ctx view cids]
  (if (nil? view) cids (let [sel (view-selects ctx view)]
  (if (or (nil? sel) (empty? sel)) cids (let [in-view (filterv (fn [cid] (contains? sel cid)) cids)]
  (if (empty? in-view) cids in-view))))))

(defn elect [ctx view cids]
  (if (empty? cids) nil (first (sort-by (fn [cid] [cid (str (agent-of ctx cid))]) (pool-of ctx view cids)))))

(defn elect-causal [ctx view cids]
  (if (empty? cids) nil (first (sort-by (fn [cid] (causal-key ctx cid)) (pool-of ctx view cids)))))
