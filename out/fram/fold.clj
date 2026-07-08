(ns fram.fold
  (:require [fram.kernel :as k]
            [clojure.string :as str]))

(defrecord FactOp [tx op l p r frame])

(defn factop-tx [r] (:tx r))

(defn factop-op [r] (:op r))

(defn factop-l [r] (:l r))

(defn factop-p [r] (:p r))

(defn factop-r [r] (:r r))

(defn factop-frame [r] (:frame r))

(defrecord Fold [facts version])

(defn fold-facts [r] (:facts r))

(defn fold-version [r] (:version r))

(defn- tx-of [^FactOp a]
  (let [t (:tx a)]
  (if (int? t) t 0)))

(defn max-tx [ops]
  (reduce (fn [m a] (let [t (tx-of a)]
  (if (> t m) t m))) 0 ops))

(defrecord Card [tx single live])

(defn card-tx [r] (:tx r))

(defn card-single [r] (:single r))

(defn card-live [r] (:live r))

(def meta-single-seed {"cardinality" true "value_kind" true "name" true "acyclic" true})

(defn- ^String strip-at [^String s]
  (if (str/starts-with? s "@") (subs s 1) s))

(defn card-map [ops]
  (let [latest (reduce (fn [m a] (if (not (= (:p a) "cardinality")) m (let [pn (strip-at (:l a))
   atx (tx-of a)
   prev (get m pn)]
  (if (and (some? prev) (> (:tx prev) atx)) m (assoc m pn (->Card atx (= (:r a) "single") (= (:op a) "assert"))))))) {} ops)]
  (reduce (fn [acc e] (let [pn (nth e 0)
   c (nth e 1)]
  (if (:live c) (assoc acc pn (:single c)) acc))) meta-single-seed (vec (seq latest)))))

(defrecord Latest [tx op l p r frame])

(defn latest-tx [r] (:tx r))

(defn latest-op [r] (:op r))

(defn latest-l [r] (:l r))

(defn latest-p [r] (:p r))

(defn latest-r [r] (:r r))

(defn latest-frame [r] (:frame r))

(defn- ^String key-of [cmap ^FactOp a]
  (if (k/single-eff? cmap (:p a)) (str (:l a) "\u0001" (:p a)) (str (:l a) "\u0001" (:p a) "\u0001" (:r a))))

(defn- keyed-latest [cmap ops]
  (reduce (fn [m a] (let [k (key-of cmap a)
   prev (get m k)
   atx (tx-of a)]
  (if (and (some? prev) (> (:tx prev) atx)) m (assoc m k (->Latest atx (:op a) (:l a) (:p a) (:r a) (:frame a)))))) {} ops))

(defn ^Fold fold [ops]
  (let [valid (filterv (fn [a] (and (some? (:l a)) (and (some? (:p a)) (some? (:r a))))) ops)
   cmap (card-map valid)
   keyed (keyed-latest cmap valid)
   facts (reduce (fn [acc v] (if (= (:op v) "assert") (conj acc (k/->Fact (:l v) (:p v) (:r v))) acc)) [] (vec (vals keyed)))]
  (->Fold facts (max-tx ops))))

(defn fold-latest [ops]
  (filterv (fn [v] (= (:op v) "assert")) (vec (vals (keyed-latest (card-map ops) ops)))))

(defn refold-order [facts]
  (let [cmap (card-map (mapv (fn [c] (->FactOp 0 "assert" (:l c) (:p c) (:r c) "live")) facts))]
  (vec (vals (reduce (fn [m c] (assoc m (key-of cmap (->FactOp 0 "assert" (:l c) (:p c) (:r c) "live")) c)) {} facts)))))
