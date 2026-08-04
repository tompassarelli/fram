(ns resolve-binds
  (:require [resolve-ident :as ri]
            [resolve-core :as rc]
            [resolve-read :as rr]))

(defn- sv [ctx view e]
  (rr/sym-val ctx view e))

(defn- ^Boolean type-colon? [v]
  (and (string? v) (contains? rc/TYPE-COLON (str v))))

(defn ^Boolean brackets? [ctx view e]
  (= "#%brackets" (rr/head-sym ctx view e)))

(defn ^Boolean map-node? [ctx view e]
  (= "#%map" (rr/head-sym ctx view e)))

(defn- tail [ctx e]
  (vec (rest (rr/ordered-children ctx e))))

(defn collect-bind-syms [ctx view node]
  (if (nil? node) [] (let [n (rr/unwrap-meta ctx view node)
   v (sv ctx view n)]
  (cond
  (some? v) (if (contains? #{"_" "&"} (str v)) [] [n])
  (brackets? ctx view n) (reduce (fn [acc k] (into acc (collect-bind-syms ctx view k))) [] (tail ctx n))
  (map-node? ctx view n) (loop [ks (tail ctx n)
   acc []]
  (if (empty? ks) acc (let [k (first ks)
   kv (sv ctx view k)
   v2 (nth ks 1 nil)
   ks2 (vec (drop 2 ks))]
  (cond
  (contains? #{":keys" ":strs" ":syms"} (str kv)) (recur ks2 (into acc (if (and (some? v2) (brackets? ctx view v2)) (vec (keep (fn [c] (let [leaf (rr/unwrap-meta ctx view c)]
  (if (some? (sv ctx view leaf)) (do
  leaf)))) (tail ctx v2))) [])))
  (= ":as" (str kv)) (recur ks2 (into acc (collect-bind-syms ctx view v2)))
  (= ":or" (str kv)) (recur ks2 acc)
  (some? kv) (recur ks2 (if (nil? k) acc (conj acc k)))
  :else (recur ks2 (into acc (collect-bind-syms ctx view k)))))))
  (= "list" (rr/kind-of ctx view n)) (loop [ks (rr/ordered-children ctx n)
   acc []]
  (if (empty? ks) acc (let [k (first ks)]
  (if (type-colon? (sv ctx view k)) acc (recur (vec (rest ks)) (into acc (collect-bind-syms ctx view k)))))))
  :else []))))

(defn collect-or-vals [ctx view node]
  (if (nil? node) [] (cond
  (map-node? ctx view node) (loop [ks (tail ctx node)
   acc []]
  (if (empty? ks) acc (let [k (first ks)
   kv (sv ctx view k)
   v2 (nth ks 1 nil)
   ks2 (vec (drop 2 ks))]
  (cond
  (= ":or" (str kv)) (recur ks2 (into acc (if (and (some? v2) (map-node? ctx view v2)) (vec (keep-indexed (fn [i cc] (if (odd? i) cc nil)) (tail ctx v2))) [])))
  (contains? #{":as" ":keys" ":strs" ":syms"} (str kv)) (recur ks2 acc)
  (some? kv) (recur ks2 acc)
  :else (recur ks2 (into acc (collect-or-vals ctx view k)))))))
  (brackets? ctx view node) (reduce (fn [acc k] (into acc (collect-or-vals ctx view k))) [] (tail ctx node))
  :else [])))

(defn param-binds [ctx view bracket]
  (loop [ks (tail ctx bracket)
   binds []
   skip false]
  (if (empty? ks) binds (let [k (first ks)
   v (sv ctx view k)
   ks2 (vec (rest ks))]
  (cond
  skip (recur ks2 binds false)
  (type-colon? v) (recur ks2 binds true)
  :else (recur ks2 (into binds (collect-bind-syms ctx view k)) false))))))

(defn let-bind-pairs [ctx view bracket]
  (loop [ks (tail ctx bracket)
   acc []]
  (if (empty? ks) acc (let [pat (first ks)
   after (if (type-colon? (sv ctx view (nth ks 1 nil))) (vec (drop 3 ks)) (vec (rest ks)))
   val (nth after 0 nil)]
  (recur (vec (rest after)) (conj acc [(collect-bind-syms ctx view pat) val (collect-or-vals ctx view pat)]))))))

(defn for-bind-pairs [ctx view bracket]
  (loop [ks (tail ctx bracket)
   acc []]
  (if (empty? ks) acc (let [k (first ks)
   kv (sv ctx view k)
   v (nth ks 1 nil)
   ks2 (vec (drop 2 ks))]
  (cond
  (contains? #{":when" ":while"} (str kv)) (recur ks2 (conj acc [:expr v]))
  (= ":let" (str kv)) (recur ks2 (into acc (if (and (some? v) (brackets? ctx view v)) (mapv (fn [p] [:bind (nth p 0 nil) (nth p 1 nil) (nth p 2 nil)]) (let-bind-pairs ctx view v)) [])))
  (type-colon? (sv ctx view v)) (recur (vec (drop 4 ks)) (conj acc [:bind (collect-bind-syms ctx view k) (nth ks 3 nil) (collect-or-vals ctx view k)]))
  :else (recur ks2 (conj acc [:bind (collect-bind-syms ctx view k) v (collect-or-vals ctx view k)])))))))

(defn frame-of [ctx view bsyms]
  (reduce (fn [acc b] (assoc acc (sv ctx view b) b)) {} bsyms))

(defn match-pat-binds [ctx view pat]
  (if (nil? pat) [] (let [n (rr/unwrap-meta ctx view pat)
   v (sv ctx view n)]
  (cond
  (some? v) (if (= "_" (str v)) [] [n])
  (or (= "list" (rr/kind-of ctx view n)) (brackets? ctx view n)) (reduce (fn [acc k] (into acc (match-pat-binds ctx view k))) [] (tail ctx n))
  :else []))))
