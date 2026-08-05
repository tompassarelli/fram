(ns fram.kernel
  (:require [fram.types :as t]
            [clojure.string :as str]))

(def profile-anchor :kernel/profile)

(def ^String relational-profile-kind "relational")

(def ^String observe-profile-mode "observe")

(def ^String profile-includes "includes")

(def ^String profile-violation "violates")

(def relational-profile-rules ["R1" "R2" "R3" "R4"])

(def ^String vocabulary-profile-rule "R5")

(def vocabulary-grouping :grouped-under)

(def ^String kernel-vocabulary-prefix ":kernel/")

(defn profile-header [profile-id ^String kind ^String mode]
  (t/triple profile-id kind mode))

(defn relational-profile-declaration [^String space-id profile-id]
  (t/triple space-id profile-anchor (profile-header profile-id relational-profile-kind observe-profile-mode)))

(defn profile-rule [profile-id ^String rule]
  (t/triple profile-id profile-includes rule))

(defn- ^Boolean profile-anchor? [value]
  (= profile-anchor (t/triple-slot1 value)))

(defn- ^Boolean profile-has-rule? [triples profile-id ^String rule]
  (not (empty? (filterv (fn [value] (and (= profile-id (t/triple-slot0 value)) (and (= profile-includes (t/triple-slot1 value)) (= rule (t/triple-slot2 value))))) triples))))

(defn ^Boolean declared-relational-profile? [triples ^String space-id]
  (not (empty? (filterv (fn [value] (let [header (t/triple-slot2 value)]
  (and (= space-id (t/triple-slot0 value)) (and (profile-anchor? value) (and (t/triple? header) (and (= relational-profile-kind (t/triple-slot1 header)) (and (= observe-profile-mode (t/triple-slot2 header)) (every? (fn [rule] (profile-has-rule? triples (t/triple-slot0 header) rule)) relational-profile-rules)))))))) triples))))

(defn- space-profile-ids [triples ^String space-id]
  (mapv (fn [value] (t/triple-slot0 (t/triple-slot2 value))) (filterv (fn [value] (and (= space-id (t/triple-slot0 value)) (and (profile-anchor? value) (t/triple? (t/triple-slot2 value))))) triples)))

(defn ^Boolean declared-vocabulary-rule? [triples ^String space-id]
  (not (empty? (filterv (fn [profile-id] (profile-has-rule? triples profile-id vocabulary-profile-rule)) (space-profile-ids triples space-id)))))

(defn- ^Boolean namespaced-vocabulary? [value]
  (and (keyword? value) (let [spelling (str value)]
  (and (str/includes? spelling "/") (not (str/starts-with? spelling kernel-vocabulary-prefix))))))

(defn- ^Boolean grouped-vocabulary? [triples term]
  (not (empty? (filterv (fn [value] (and (= term (t/triple-slot0 value)) (= vocabulary-grouping (t/triple-slot1 value)))) triples))))

(defn vocabulary-lint-errors [triples proposition]
  (let [slot1 (t/triple-slot1 proposition)]
  (if (and (namespaced-vocabulary? slot1) (not (grouped-vocabulary? triples slot1))) [vocabulary-profile-rule] [])))

(defn- ^Boolean nonblank-string-or-keyword? [value]
  (or (keyword? value) (and (string? value) (> (count value) 0))))

(defn relational-admission-errors [proposition]
  (let [slot0 (t/triple-slot0 proposition)
   slot1 (t/triple-slot1 proposition)
   slot2 (t/triple-slot2 proposition)]
  (cond-> [] (not (and (t/atom? slot0) (and (t/atom? slot1) (t/atom? slot2)))) (conj "R1") (not (nonblank-string-or-keyword? slot0)) (conj "R2") (not (nonblank-string-or-keyword? slot1)) (conj "R3") (not (t/atom? slot2)) (conj "R4"))))

(defn relational-lint-errors [proposition]
  (let [slot0 (t/triple-slot0 proposition)
   slot1 (t/triple-slot1 proposition)
   slot2 (t/triple-slot2 proposition)
   r1 (and (t/atom? slot0) (and (t/atom? slot1) (t/atom? slot2)))
   r2 (or (keyword? slot0) (and (string? slot0) (not (= "" slot0))))
   r3 (or (keyword? slot1) (and (string? slot1) (not (= "" slot1))))
   r4 (t/atom? slot2)
   errors0 []
   errors1 (if r1 errors0 (conj errors0 "R1"))
   errors2 (if r2 errors1 (conj errors1 "R2"))
   errors3 (if r3 errors2 (conj errors2 "R3"))]
  (if r4 errors3 (conj errors3 "R4"))))

(defn lint-declared-profile [triples ^String space-id]
  (if (not (declared-relational-profile? triples space-id)) [] (let [vocabulary? (declared-vocabulary-rule? triples space-id)]
  (reduce (fn [violations proposition] (if (profile-anchor? proposition) violations (let [relational (relational-lint-errors proposition)
   rules (if vocabulary? (into relational (vocabulary-lint-errors triples proposition)) relational)]
  (reduce (fn [rows rule] (conj rows (t/triple proposition profile-violation rule))) violations rules)))) [] triples))))

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
