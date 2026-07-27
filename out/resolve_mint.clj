(ns resolve-mint
  (:require [fram.types :as t]
            [fram.store :as c]))

(def FN-RE (re-pattern "f\\d+"))

(defrecord Mint [ctx tx SUP KIND Vp ents])

(defn mint-ctx [r] (:ctx r))

(defn mint-tx [r] (:tx r))

(defn mint-SUP [r] (:SUP r))

(defn mint-KIND [r] (:KIND r))

(defn mint-Vp [r] (:Vp r))

(defn mint-ents [r] (:ents r))

(defrecord FnEdge [idx cid child])

(defn fnedge-idx [r] (:idx r))

(defn fnedge-cid [r] (:cid r))

(defn fnedge-child [r] (:child r))

(defn register! [^Mint m ^String src e]
  (let [ents (:ents m)]
  (do
  (swap! ents (fn [tbl] (assoc tbl src (conj (get tbl src []) e))))
  e)))

(defn mint-leaf! [^Mint m ^String src kind v]
  (let [ctx (:ctx m)
   tx (:tx m)
   e (register! m src (c/entity! ctx))]
  (do
  (c/fact! ctx e (:KIND m) (c/value! ctx kind) tx)
  (c/fact! ctx e (:Vp m) (c/value! ctx v) tx)
  e)))

(defn fN-facts [^Mint m parent]
  (let [ctx (:ctx m)
   rows (reduce (fn [acc cid] (let [f (c/fact-of ctx cid)
   pi (if (nil? f) nil (:p f))
   p (if (int? pi) (c/literal ctx pi) nil)
   r (if (nil? f) nil (:r f))]
  (if (and (string? p) (some? (re-matches FN-RE (str p)))) (let [n (parse-long (subs (str p) 1))]
  (if (nil? n) acc (conj acc (->FnEdge n cid r)))) acc))) [] (c/by-l ctx parent))]
  (mapv (fn [e] [(:idx e) (:cid e) (:child e)]) (sort-by (fn [e] (:idx e)) rows))))

(defn retire-fact! [^Mint m oldc]
  (let [ctx (:ctx m)]
  (c/fact! ctx (c/entity! ctx) (:SUP m) oldc (:tx m))))
