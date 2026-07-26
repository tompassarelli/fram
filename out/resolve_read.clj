(ns resolve-read
  (:require [fram.types :as t]
            [fram.store :as c]
            [resolve-core :as rc]))

(def SEG-RE (re-pattern "seg\\d+"))

(defrecord OrdPair [key child])

(defn ordpair-key [r] (:key r))

(defn ordpair-child [r] (:child r))

(defrecord SegPair [idx child])

(defn segpair-idx [r] (:idx r))

(defn segpair-child [r] (:child r))

(defn view-cids [ctx v cids]
  (let [SEL (c/value-id ctx "selects")
   ve (c/value-id ctx v)]
  (if (or (nil? SEL) (nil? ve)) nil (let [sel (reduce (fn [acc scid] (let [f (c/fact-of ctx scid)]
  (if (nil? f) acc (conj acc (:r f))))) #{} (c/by-lp ctx ve SEL))]
  (filterv (fn [cid] (contains? sel cid)) cids)))))

(defn- pool-of [ctx view cids]
  (let [in-view (if (nil? view) nil (view-cids ctx view cids))]
  (if (or (nil? in-view) (empty? in-view)) cids in-view)))

(defn- ^String agent-of [s cid]
  (let [txid (get (:tx-of s) cid)
   m (if (nil? txid) nil (get (:txs s) txid))]
  (if (nil? m) "" (str (:agent m)))))

(defn select-main-1 [ctx view cids]
  (if (empty? cids) nil (if (nil? ctx) (first cids) (let [s (deref ctx)]
  (first (sort-by (fn [cid] [cid (agent-of s cid)]) (pool-of ctx view cids)))))))

(defn select-causal-1 [ctx view cids]
  (if (empty? cids) nil (if (nil? ctx) (first cids) (let [s (deref ctx)]
  (first (sort-by (fn [cid] (let [txid (get (:tx-of s) cid)
   m (if (nil? txid) nil (get (:txs s) txid))
   obs (if (nil? m) nil (:observed m))
   sq (if (nil? m) nil (:seq m))]
  [(if (nil? obs) (if (nil? sq) 0 sq) obs) cid (if (nil? m) "" (str (:agent m)))])) (pool-of ctx view cids)))))))

(defn- fact-r [ctx cid]
  (let [f (if (nil? cid) nil (c/fact-of ctx cid))
   r (if (nil? f) nil (:r f))]
  (if (int? r) r nil)))

(defn pred-val [ctx view e pname]
  (if (or (nil? ctx) (nil? e)) nil (let [P (c/value-id ctx pname)]
  (if (nil? P) nil (let [r (fact-r ctx (select-main-1 ctx view (c/by-lp ctx e P)))]
  (if (nil? r) nil (c/literal ctx r)))))))

(defn kind-of [ctx view e]
  (pred-val ctx view e "kind"))

(defn sym-val [ctx view e]
  (if (= "symbol" (kind-of ctx view e)) (pred-val ctx view e "v") nil))

(defn ordered-children [ctx e]
  (if (or (nil? ctx) (nil? e)) [] (let [pairs (reduce (fn [acc cid] (let [f (c/fact-of ctx cid)
   pi (if (nil? f) nil (:p f))
   k (if (int? pi) (rc/ord-parse (c/literal ctx pi)) nil)
   r (fact-r ctx cid)]
  (if (or (nil? k) (nil? r)) acc (conj acc (->OrdPair k r))))) [] (c/by-l ctx e))]
  (mapv (fn [pr] (:child pr)) (sort-by (fn [pr] (:key pr)) rc/ord-cmp pairs)))))

(defn ordered-segs [ctx e]
  (if (or (nil? ctx) (nil? e)) [] (let [pairs (reduce (fn [acc cid] (let [f (c/fact-of ctx cid)
   pi (if (nil? f) nil (:p f))
   p (if (int? pi) (c/literal ctx pi) nil)
   r (fact-r ctx cid)]
  (if (and (string? p) (some? (re-matches SEG-RE (str p))) (some? r)) (let [n (parse-long (subs (str p) 3))]
  (if (nil? n) acc (conj acc (->SegPair n r)))) acc))) [] (c/by-l ctx e))]
  (mapv (fn [pr] (:child pr)) (sort-by (fn [pr] (:idx pr)) pairs)))))

(defn head-sym [ctx view e]
  (if (= "list" (kind-of ctx view e)) (sym-val ctx view (first (ordered-children ctx e))) nil))

(defn unwrap-meta [ctx view e]
  (loop [e e
   n 0]
  (if (and (some? e) (< n 64) (= "#%meta" (head-sym ctx view e))) (recur (nth (ordered-children ctx e) 2 nil) (inc n)) e)))

(defn bound-target [ctx view BOUND L]
  (if (or (nil? ctx) (nil? BOUND) (nil? L)) nil (fact-r ctx (select-main-1 ctx view (c/by-lp ctx L BOUND)))))

(defn refers-target [ctx view BOUND REFERS L]
  (let [bt (bound-target ctx view BOUND L)]
  (if (some? bt) bt (if (or (nil? ctx) (nil? REFERS) (nil? L)) nil (fact-r ctx (select-main-1 ctx view (c/by-lp ctx L REFERS)))))))

(defn ^Boolean live-node? [ctx KIND e]
  (if (or (nil? ctx) (nil? KIND) (nil? e)) false (not (empty? (c/by-lp ctx e KIND)))))
