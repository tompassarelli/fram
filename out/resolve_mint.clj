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

(defn- clj-meta->beagle-meta [mt]
  (cond
  (and (= 1 (count mt)) (contains? mt :tag) (symbol? (:tag mt))) (:tag mt)
  (and (= 1 (count mt)) (true? (val (first mt)))) (key (first mt))
  :else mt))

(defn- reader-meta [d]
  (if (instance? clojure.lang.IObj d) (not-empty (apply dissoc (meta d) [:line :column :end-line :end-column :file])) nil))

(defn mint-datum! [^Mint m ^String src d]
  (let [mt (reader-meta d)]
  (if (some? mt) (mint-datum! m src (list (symbol "#%meta") (clj-meta->beagle-meta mt) (with-meta d nil))) (cond
  (nil? d) (mint-leaf! m src "symbol" "nil")
  (symbol? d) (mint-leaf! m src "symbol" (str d))
  (keyword? d) (mint-leaf! m src "symbol" (str d))
  (string? d) (mint-leaf! m src "string" d)
  (boolean? d) (mint-leaf! m src "symbol" (if d "true" "false"))
  (char? d) (mint-leaf! m src "char" (str d))
  (number? d) (mint-leaf! m src "number" (str d))
  (or (list? d) (seq? d) (vector? d) (map? d)) (let [ctx (:ctx m)
   tx (:tx m)
   head (cond
  (vector? d) [(symbol "#%brackets")]
  (map? d) [(symbol "#%map")]
  :else [])
   elems (vec (concat head (if (map? d) (apply concat (seq d)) (seq d))))
   e (register! m src (c/entity! ctx))]
  (do
  (c/fact! ctx e (:KIND m) (c/value! ctx "list") tx)
  (doseq [i (range (count elems))]
  (c/fact! ctx e (c/value! ctx (str "f" i)) (mint-datum! m src (nth elems i)) tx))
  e))
  (instance? java.util.regex.Pattern d) (mint-datum! m src (list (symbol "#%regex") (.pattern d)))
  (set? d) (mint-datum! m src (apply list (cons (symbol "#%set") (seq d))))
  :else (mint-leaf! m src "other" (pr-str d))))))

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
