(ns fram.fold
  (:require [fram.kernel :as k]))

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

(defn- ^Boolean valid-fact-op? [^FactOp a]
  (and (some? (:l a)) (and (some? (:p a)) (some? (:r a)))))

(defn- ^String registry-key [^FactOp a]
  (if (= (:p a) "predicate_name") (str (:l a) "\u0001" (:p a)) (str (:l a) "\u0001" (:p a) "\u0001" (:r a))))

(defn predicate-registry-of [ops]
  (let [latest (reduce (fn [m a] (if (or (not (valid-fact-op? a)) (not (or (= (:p a) "predicate_name") (= (:p a) "predicate_alias")))) m (let [kk (registry-key a)
   prior (get m kk)
   atx (tx-of a)]
  (if (and (some? prior) (> (:tx prior) atx)) m (assoc m kk a))))) {} ops)
   facts (reduce (fn [acc e] (let [v (nth e 1)]
  (if (= (:op v) "assert") (conj acc (k/->Fact (:l v) (:p v) (:r v))) acc))) [] latest)]
  (k/predicate-registry facts)))

(defrecord Card [tx single live])

(defn card-tx [r] (:tx r))

(defn card-single [r] (:single r))

(defn card-live [r] (:live r))

(def meta-single-seed {"cardinality" true "value_kind" true "name" true "acyclic" true "predicate_name" true})

(defn- card-map-r [reg ops]
  (let [latest (reduce (fn [m a] (if (not (= (k/predicate-id reg (:p a)) (k/predicate-id reg "cardinality"))) m (let [identity (k/predicate-id reg (:l a))
   atx (tx-of a)
   prev (get m identity)]
  (if (and (some? prev) (> (:tx prev) atx)) m (assoc m identity (->Card atx (= (:r a) "single") (= (:op a) "assert"))))))) {} ops)]
  (reduce (fn [acc e] (let [identity (nth e 0)
   pn (k/predicate-name reg identity)
   c (nth e 1)]
  (if (:live c) (assoc acc pn (:single c)) acc))) meta-single-seed latest)))

(defn card-map [ops]
  (let [valid (filterv valid-fact-op? ops)
   reg (predicate-registry-of valid)]
  (card-map-r reg valid)))

(defrecord Latest [tx op l p r frame])

(defn latest-tx [r] (:tx r))

(defn latest-op [r] (:op r))

(defn latest-l [r] (:l r))

(defn latest-p [r] (:p r))

(defn latest-r [r] (:r r))

(defn latest-frame [r] (:frame r))

(defn- ^String key-of [reg cmap ^FactOp a]
  (let [identity (k/predicate-key reg (:p a))]
  (if (k/single-eff-reg? reg cmap (:p a)) (str (:l a) "\u0001" identity) (str (:l a) "\u0001" identity "\u0001" (:r a)))))

(defn- keyed-latest [reg cmap ops]
  (reduce (fn [m a] (let [k (key-of reg cmap a)
   prev (get m k)
   atx (tx-of a)]
  (if (and (some? prev) (> (:tx prev) atx)) m (assoc m k (->Latest atx (:op a) (:l a) (k/predicate-name reg (:p a)) (:r a) (:frame a)))))) {} ops))

(defn ^Fold fold [ops]
  (let [valid (filterv valid-fact-op? ops)
   reg (predicate-registry-of valid)
   cmap (card-map-r reg valid)
   keyed (keyed-latest reg cmap valid)
   ordered (sort-by first (vec keyed))
   facts (reduce (fn [acc e] (let [v (nth e 1)]
  (if (= (:op v) "assert") (conj acc (k/->Fact (:l v) (:p v) (:r v))) acc))) [] ordered)]
  (->Fold facts (max-tx ops))))

(defn fold-latest [ops]
  (let [valid (filterv valid-fact-op? ops)
   reg (predicate-registry-of valid)
   keyed (keyed-latest reg (card-map-r reg valid) valid)]
  (reduce (fn [acc e] (let [v (nth e 1)]
  (if (= (:op v) "assert") (conj acc v) acc))) [] keyed)))

(defn refold-order [facts]
  (let [reg (k/predicate-registry facts)
   ops (mapv (fn [c] (->FactOp 0 "assert" (:l c) (:p c) (:r c) "live")) facts)
   cmap (card-map-r reg ops)
   keyed (reduce (fn [m c] (assoc m (key-of reg cmap (->FactOp 0 "assert" (:l c) (:p c) (:r c) "live")) (k/->Fact (:l c) (k/predicate-name reg (:p c)) (:r c)))) {} facts)
   ordered (sort-by first (vec keyed))]
  (reduce (fn [acc e] (conj acc (nth e 1))) [] ordered)))
