(ns resolve-binds
  (:require [resolve-ident :as ri]
            [resolve-read :as rr]))

(defn- sv [ctx view e]
  (rr/sym-val ctx view e))

(defn ^Boolean brackets? [ctx view e]
  (= "#%brackets" (rr/head-sym ctx view e)))

(defn ^Boolean map-node? [ctx view e]
  (= "#%map" (rr/head-sym ctx view e)))

(defn- tail [ctx e]
  (vec (rest (rr/ordered-children ctx e))))

(defn- ^Boolean binding-form? [ctx view node]
  (let [n (rr/unwrap-meta ctx view node)]
  (or (some? (sv ctx view n)) (brackets? ctx view n) (map-node? ctx view n))))

(defn typed-binding-parts [ctx view node]
  (let [n (rr/unwrap-meta ctx view node)
   children (rr/ordered-children ctx n)]
  (if (and (= "list" (rr/kind-of ctx view n)) (= 2 (count children)) (binding-form? ctx view (nth children 0 nil))) (do
  [(nth children 0) (nth children 1)]))))

(defn signature-tail [ctx view forms ^Boolean macro?]
  (let [params (nth forms 0 nil)]
  (if (and (some? params) (brackets? ctx view params)) (do
  (let [after-params (vec (rest forms))
   return-type (if macro? nil (nth after-params 0 nil))
   after-return (if macro? after-params (vec (rest after-params)))
   raises? (and (not macro?) (= ":raises" (str (sv ctx view (nth after-return 0 nil)))))
   raises-type (if raises? (nth after-return 1 nil) nil)
   body (if raises? (vec (drop 2 after-return)) after-return)]
  {:params params :return-type return-type :raises-type raises-type :body body})))))

(defn param-type-nodes [ctx view bracket]
  (vec (keep (fn [node] (let [parts (typed-binding-parts ctx view node)]
  (if (some? parts) (do
  (nth parts 1))))) (tail ctx bracket))))

(defn let-type-nodes [ctx view bracket]
  (loop [nodes (tail ctx bracket)
   types []]
  (if (empty? nodes) types (let [parts (typed-binding-parts ctx view (nth nodes 0 nil))
   type-node (if (nil? parts) nil (nth parts 1 nil))]
  (recur (vec (drop 2 nodes)) (if (nil? type-node) types (conj types type-node)))))))

(defn for-type-nodes [ctx view bracket]
  (loop [nodes (tail ctx bracket)
   types []]
  (if (empty? nodes) types (let [binding (nth nodes 0 nil)
   marker (str (sv ctx view binding))
   value (nth nodes 1 nil)
   rest-nodes (vec (drop 2 nodes))]
  (cond
  (= ":let" marker) (recur rest-nodes (into types (if (and (some? value) (brackets? ctx view value)) (let-type-nodes ctx view value) [])))
  (contains? #{":when" ":while"} marker) (recur rest-nodes types)
  :else (let [parts (typed-binding-parts ctx view binding)
   type-node (if (nil? parts) nil (nth parts 1 nil))]
  (recur rest-nodes (if (nil? type-node) types (conj types type-node)))))))))

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
  (= "list" (rr/kind-of ctx view n)) (let [parts (typed-binding-parts ctx view n)]
  (if (nil? parts) [] (collect-bind-syms ctx view (nth parts 0))))
  :else []))))

(defn collect-or-vals [ctx view node]
  (if (nil? node) [] (let [parts (typed-binding-parts ctx view node)
   binding (if (nil? parts) node (nth parts 0))]
  (cond
  (map-node? ctx view binding) (loop [ks (tail ctx binding)
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
  (brackets? ctx view binding) (reduce (fn [acc k] (into acc (collect-or-vals ctx view k))) [] (tail ctx binding))
  :else []))))

(defn param-binds [ctx view bracket]
  (loop [ks (tail ctx bracket)
   binds []]
  (if (empty? ks) binds (let [node (first ks)
   parts (typed-binding-parts ctx view node)
   binding (if (nil? parts) node (nth parts 0))]
  (recur (vec (rest ks)) (into binds (collect-bind-syms ctx view binding)))))))

(defn let-bind-pairs [ctx view bracket]
  (loop [ks (tail ctx bracket)
   acc []]
  (if (empty? ks) acc (let [raw-pat (first ks)
   parts (typed-binding-parts ctx view raw-pat)
   pat (if (nil? parts) raw-pat (nth parts 0))
   val (nth ks 1 nil)]
  (recur (vec (drop 2 ks)) (conj acc [(collect-bind-syms ctx view pat) val (collect-or-vals ctx view pat)]))))))

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
  :else (let [parts (typed-binding-parts ctx view k)
   binding (if (nil? parts) k (nth parts 0))]
  (recur ks2 (conj acc [:bind (collect-bind-syms ctx view binding) v (collect-or-vals ctx view binding)]))))))))

(defn frame-of [ctx view bsyms]
  (reduce (fn [acc b] (assoc acc (sv ctx view b) b)) {} bsyms))

(defn match-pat-binds [ctx view pat]
  (if (nil? pat) [] (let [n (rr/unwrap-meta ctx view pat)
   v (sv ctx view n)]
  (cond
  (some? v) (if (= "_" (str v)) [] [n])
  (or (= "list" (rr/kind-of ctx view n)) (brackets? ctx view n)) (reduce (fn [acc k] (into acc (match-pat-binds ctx view k))) [] (tail ctx n))
  :else []))))
