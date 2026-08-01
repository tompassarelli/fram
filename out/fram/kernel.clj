(ns fram.kernel
  (:require [fram.types :as t]))

(defn ^Boolean triple-eq? [left right]
  (= left right))

(defn by-slot0 [triples term]
  (filterv (fn [value] (= term (t/triple-slot0 value))) triples))

(defn by-slot1 [triples term]
  (filterv (fn [value] (= term (t/triple-slot1 value))) triples))

(defn by-slot2 [triples term]
  (filterv (fn [value] (= term (t/triple-slot2 value))) triples))

(defn by-slot01 [triples slot0 slot1]
  (filterv (fn [value] (and (= slot0 (t/triple-slot0 value)) (= slot1 (t/triple-slot1 value)))) triples))

(defn by-slot12 [triples slot1 slot2]
  (filterv (fn [value] (and (= slot1 (t/triple-slot1 value)) (= slot2 (t/triple-slot2 value)))) triples))

(defn by-slot02 [triples slot0 slot2]
  (filterv (fn [value] (and (= slot0 (t/triple-slot0 value)) (= slot2 (t/triple-slot2 value)))) triples))

(defn ^Boolean assertion-occurrence? [value]
  (and (t/triple? value) (and (t/occurrence-coordinate? (t/triple-slot0 value)) (and (= t/asserts (t/triple-slot1 value)) (t/triple? (t/triple-slot2 value))))))

(defn ^Boolean retraction-occurrence? [value]
  (and (t/triple? value) (and (t/occurrence-coordinate? (t/triple-slot0 value)) (and (= t/retracts (t/triple-slot1 value)) (t/triple? (t/triple-slot2 value))))))

(defn ^Boolean operation-occurrence? [value]
  (or (assertion-occurrence? value) (retraction-occurrence? value)))

(defn ^Boolean withdrawal? [value]
  (and (t/triple? value) (and (t/occurrence-coordinate? (t/triple-slot0 value)) (and (= t/withdraws (t/triple-slot1 value)) (t/occurrence-coordinate? (t/triple-slot2 value))))))

(defn occurrence-of [event]
  (if (operation-occurrence? event) (t/triple-slot0 event) (throw (ex-info "fram: value is not an operation occurrence" {:type :invalid-operation-occurrence}))))

(defn proposition-of [event]
  (if (operation-occurrence? event) (t/triple-slot2 event) (throw (ex-info "fram: value is not an operation occurrence" {:type :invalid-operation-occurrence}))))

(defn withdrawal-source [value]
  (if (withdrawal? value) (t/triple-slot0 value) (throw (ex-info "fram: value is not a withdrawal" {:type :invalid-withdrawal}))))

(defn withdrawal-target [value]
  (if (withdrawal? value) (t/triple-slot2 value) (throw (ex-info "fram: value is not a withdrawal" {:type :invalid-withdrawal}))))
