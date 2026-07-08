(ns fram.fold
  (:require [fram.kernel :as k]
            [clojure.string :as str]))

(defrecord Assertion [tx op l p r frame])

(defn assertion-tx [r] (:tx r))

(defn assertion-op [r] (:op r))

(defn assertion-l [r] (:l r))

(defn assertion-p [r] (:p r))

(defn assertion-r [r] (:r r))

(defn assertion-frame [r] (:frame r))

(defrecord Fold [claims version])

(defn fold-claims [r] (:claims r))

(defn fold-version [r] (:version r))

(defn- tx-of [^Assertion a]
  (let [t (:tx a)]
  (if (int? t) t 0)))

(defn max-tx [as]
  (reduce (fn [m a] (let [t (tx-of a)]
  (if (> t m) t m))) 0 as))

(defrecord Card [tx single live])

(defn card-tx [r] (:tx r))

(defn card-single [r] (:single r))

(defn card-live [r] (:live r))

(def meta-single-seed {"cardinality" true "value_kind" true "name" true "acyclic" true})

(defn- ^String strip-at [^String s]
  (if (str/starts-with? s "@") (subs s 1) s))

(defn card-map [asserts]
  (let [latest (reduce (fn [m a] (if (not (= (:p a) "cardinality")) m (let [pn (strip-at (:l a))
   atx (tx-of a)
   prev (get m pn)]
  (if (and (some? prev) (> (:tx prev) atx)) m (assoc m pn (->Card atx (= (:r a) "single") (= (:op a) "assert"))))))) {} asserts)]
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

(defn- ^String key-of [cmap ^Assertion a]
  (if (k/single-eff? cmap (:p a)) (str (:l a) "\u0001" (:p a)) (str (:l a) "\u0001" (:p a) "\u0001" (:r a))))

(defn- keyed-latest [cmap asserts]
  (reduce (fn [m a] (let [k (key-of cmap a)
   prev (get m k)
   atx (tx-of a)]
  (if (and (some? prev) (> (:tx prev) atx)) m (assoc m k (->Latest atx (:op a) (:l a) (:p a) (:r a) (:frame a)))))) {} asserts))

(defn ^Fold fold [asserts]
  (let [valid (filterv (fn [a] (and (some? (:l a)) (and (some? (:p a)) (some? (:r a))))) asserts)
   cmap (card-map valid)
   keyed (keyed-latest cmap valid)
   claims (reduce (fn [acc v] (if (= (:op v) "assert") (conj acc (k/->Claim (:l v) (:p v) (:r v))) acc)) [] (vec (vals keyed)))]
  (->Fold claims (max-tx asserts))))

(defn fold-latest [asserts]
  (filterv (fn [v] (= (:op v) "assert")) (vec (vals (keyed-latest (card-map asserts) asserts)))))

(defn refold-order [claims]
  (let [cmap (card-map (mapv (fn [c] (->Assertion 0 "assert" (:l c) (:p c) (:r c) "live")) claims))]
  (vec (vals (reduce (fn [m c] (assoc m (key-of cmap (->Assertion 0 "assert" (:l c) (:p c) (:r c) "live")) c)) {} claims)))))
