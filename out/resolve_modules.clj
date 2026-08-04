(ns resolve-modules
  (:require [clojure.string :as str]
            [resolve-ident :as ri]
            [resolve-core :as rc]
            [resolve-read :as rr]
            [resolve-binds :as rb]))

(defn- sv [ctx view e]
  (rr/sym-val ctx view e))

(defn- hd [ctx view e]
  (rr/head-sym ctx view e))

(defn- wrapper [ctx view ents]
  (loop [i 0]
  (if (>= i (count ents)) nil (let [e (nth ents i)]
  (if (= "beagle-file" (hd ctx view e)) e (recur (inc i)))))))

(defn unwrap-def [ctx view form]
  (if (= "js/export" (hd ctx view form)) (nth (rr/ordered-children ctx form) 1 nil) form))

(defn forms-of [ctx view ents]
  (vec (rest (rr/ordered-children ctx (wrapper ctx view ents)))))

(defn ns-form [ctx view ents]
  (let [fs (forms-of ctx view ents)]
  (loop [i 0]
  (if (>= i (count fs)) nil (let [f (nth fs i)]
  (if (= "ns" (hd ctx view f)) f (recur (inc i))))))))

(defn module-name [ctx view ents]
  (let [nf (ns-form ctx view ents)]
  (if (nil? nf) nil (sv ctx view (nth (rr/ordered-children ctx nf) 1 nil)))))

(defn- logical-name-leaf* [ctx view node]
  (let [outer (rr/unwrap-meta ctx view node)
   leaf (if (= "list" (rr/kind-of ctx view outer)) (nth (rr/ordered-children ctx outer) 0 nil) outer)]
  (rr/unwrap-meta ctx view leaf)))

(defn module-defs [ctx view ents]
  (reduce (fn [acc f] (let [d (unwrap-def ctx view f)
   h (str (hd ctx view d))]
  (cond
  (contains? rc/TOPLEVEL-VALUE-DEFS h) (let [nl (logical-name-leaf* ctx view (nth (rr/ordered-children ctx d) 1 nil))
   nm (sv ctx view nl)]
  (if (or (nil? nm) (nil? nl)) acc (assoc acc nm nl)))
  (contains? #{"definterface" "defprotocol"} h) (reduce (fn [a m] (let [nl (logical-name-leaf* ctx view m)
   nm (sv ctx view nl)]
  (if (or (nil? nm) (nil? nl)) a (assoc a nm nl)))) acc (vec (drop 2 (rr/ordered-children ctx d))))
  :else acc))) {} (forms-of ctx view ents)))

(defn merge-import-opts [ctx view acc modn kids]
  (let [idx (fn [kw] (loop [i 0]
  (if (>= i (count kids)) nil (if (= kw (str (sv ctx view (nth kids i)))) i (recur (inc i))))))
   ri (idx ":refer")
   ai (idx ":as")
   rri (idx ":rename")
   nb (if (nil? ri) nil (nth kids (inc ri) nil))
   refers (if (and (some? nb) (rb/brackets? ctx view nb)) (vec (keep (fn [k] (sv ctx view k)) (vec (rest (rr/ordered-children ctx nb))))) [])
   alias (if (nil? ai) nil (sv ctx view (nth kids (inc ai) nil)))
   rmap (let [mb (if (nil? rri) nil (nth kids (inc rri) nil))]
  (if (and (some? mb) (rb/map-node? ctx view mb)) (loop [cs (vec (rest (rr/ordered-children ctx mb)))
   m []]
  (if (< (count cs) 2) m (recur (vec (drop 2 cs)) (conj m [(sv ctx view (nth cs 0)) (sv ctx view (nth cs 1))])))) []))
   a1 (if (empty? refers) acc (assoc acc :refer (reduce (fn [m n] (assoc m n modn)) (:refer acc {}) refers)))
   a2 (if (nil? alias) a1 (assoc a1 :as (assoc (:as a1 {}) alias modn)))]
  (if (empty? rmap) a2 (assoc a2 :rename (reduce (fn [m p] (assoc m (nth p 1) [modn (nth p 0)])) (:rename a2 {}) rmap)))))

(defn parse-require [ctx view ents]
  (let [fs (forms-of ctx view ents)
   bare (reduce (fn [acc f] (if (= "require" (hd ctx view f)) (let [kids (rr/ordered-children ctx f)]
  (merge-import-opts ctx view acc (sv ctx view (nth kids 1 nil)) (vec (drop 2 kids)))) acc)) {:refer {} :as {} :rename {}} fs)
   nf (ns-form ctx view ents)
   reqs (if (nil? nf) nil (loop [cs (rr/ordered-children ctx nf)]
  (if (empty? cs) nil (let [c (nth cs 0)]
  (if (and (= "list" (rr/kind-of ctx view c)) (= ":require" (str (sv ctx view (nth (rr/ordered-children ctx c) 0 nil))))) c (recur (vec (rest cs))))))))]
  (if (nil? reqs) bare (reduce (fn [acc spec] (if (rb/brackets? ctx view spec) (let [kids (vec (rest (rr/ordered-children ctx spec)))]
  (merge-import-opts ctx view acc (sv ctx view (nth kids 0 nil)) (vec (rest kids)))) acc)) bare (vec (rest (rr/ordered-children ctx reqs)))))))

(defn module-exports [ctx view ents]
  (reduce (fn [acc f] (if (= "js/export" (hd ctx view f)) (let [raw (nth (rr/ordered-children ctx f) 1 nil)
   d (rr/unwrap-meta ctx view raw)
   h (str (hd ctx view d))]
  (cond
  (contains? rc/TOPLEVEL-VALUE-DEFS h) (let [nl (logical-name-leaf* ctx view (nth (rr/ordered-children ctx d) 1 nil))
   nm (sv ctx view nl)]
  (if (or (nil? nm) (nil? nl)) acc (assoc acc nm nl)))
  (some? (sv ctx view d)) (assoc acc (sv ctx view d) d)
  :else acc)) acc)) {} (forms-of ctx view ents)))

(defn logical-name-leaf [ctx view node]
  (logical-name-leaf* ctx view node))

(defn type-name-leaf [ctx view d]
  (let [children (rr/ordered-children ctx d)
   name-index (rc/type-name-index (hd ctx view d) (sv ctx view (nth children 1 nil)))]
  (logical-name-leaf ctx view (nth children name-index nil))))

(defn module-types [ctx view ents]
  (let [defs (filterv (fn [f] (contains? rc/TYPE-DEFS (str (hd ctx view (unwrap-def ctx view f))))) (forms-of ctx view ents))
   names (reduce (fn [acc f] (let [nl (type-name-leaf ctx view (unwrap-def ctx view f))
   nm (sv ctx view nl)]
  (if (or (nil? nm) (nil? nl)) acc (assoc acc nm nl)))) {} defs)
   variants (reduce (fn [acc f] (let [d (unwrap-def ctx view f)]
  (if (= "defunion" (hd ctx view d)) (reduce (fn [a v] (let [vn (logical-name-leaf ctx view v)
   nm (sv ctx view vn)]
  (if (or (nil? nm) (nil? vn)) a (assoc a nm vn)))) acc (let [children (rr/ordered-children ctx d)]
  (vec (drop (inc (rc/type-name-index (hd ctx view d) (sv ctx view (nth children 1 nil)))) children)))) acc))) {} defs)]
  (merge variants names)))

(defn module-accessors [ctx view ents]
  (reduce (fn [acc f] (let [d (unwrap-def ctx view f)]
  (if (contains? #{"defrecord" "deftype"} (str (hd ctx view d))) (let [nl (type-name-leaf ctx view d)
   nm (sv ctx view nl)
   fb (first (filterv (fn [k] (rb/brackets? ctx view k)) (vec (drop 2 (rr/ordered-children ctx d)))))]
  (if (or (nil? nm) (nil? fb)) acc (let [pfx (str/lower-case (str nm))]
  (reduce (fn [a b] (let [fld (sv ctx view b)]
  (if (nil? fld) a (assoc a (str pfx "-" (str fld)) [nl fld])))) acc (rb/param-binds ctx view fb))))) acc))) {} (forms-of ctx view ents)))

(defn form-binding-leaves [ctx view form]
  (let [d (unwrap-def ctx view form)
   h (str (hd ctx view d))
   children (rr/ordered-children ctx d)
   top (type-name-leaf ctx view d)
   top-name (sv ctx view top)
   base (if (and (rc/named-def-head? h) (some? top) (some? top-name)) {[:top top-name] top} {})]
  (cond
  (= "defunion" h) (reduce (fn [acc node] (let [leaf (logical-name-leaf ctx view node)
   nm (sv ctx view leaf)]
  (if (or (nil? leaf) (nil? nm)) acc (assoc acc [:variant nm] leaf)))) base (vec (drop (inc (rc/type-name-index h (sv ctx view (nth children 1 nil)))) children)))
  (contains? #{"definterface" "defprotocol"} h) (reduce (fn [acc node] (let [leaf (logical-name-leaf ctx view node)
   nm (sv ctx view leaf)]
  (if (or (nil? leaf) (nil? nm)) acc (assoc acc [:member nm] leaf)))) base (vec (drop 2 children)))
  :else base)))
