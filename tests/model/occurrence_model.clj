;; Pure oracle for tests/model_generative_test.clj: expected live propositions,
;; occurrence coordinates, history, and receipts from an operation sequence.
;;
;; INVARIANT: every rule here is restated from docs/architecture.md,
;; docs/concurrency-and-writes.md, and docs/guarantees.md — never transcribed
;; from src/fram/store.bclj or coord.clj, so a divergence is evidence and not a
;; shared mistake. Depends on `fram.types` for Term construction only.
(ns occurrence-model
  (:require [fram.types :as t]))

(def tx-sequence-predicate :kernel/tx-sequence)
(def op-ordinal-predicate :kernel/op-ordinal)
(def asserts-predicate :kernel/asserts)
(def retracts-predicate :kernel/retracts)
(def withdraws-predicate :kernel/withdraws)
(def supersedes-predicate :kernel/supersedes)

;; Metadata assertions ride the same transaction, after every source operation,
;; in this fixed predicate order.
(def metadata-order
  [:kernel/recorded-at :kernel/asserted-by :kernel/source-frame
   :kernel/withdraws :kernel/supersedes])

(def ^:private metadata-source-key
  {:kernel/recorded-at :recorded-at
   :kernel/asserted-by :asserted-by
   :kernel/source-frame :source-frame
   :kernel/withdraws :withdraws
   :kernel/supersedes :supersedes})

;; ---------------------------------------------------------------- coordinates

(defn tx-coordinate [space-id sequence]
  (t/triple space-id tx-sequence-predicate sequence))

(defn occ-coordinate [tx ordinal]
  (t/triple tx op-ordinal-predicate ordinal))

(defn tx-coordinate? [value]
  (and (t/triple? value)
       (string? (t/triple-slot0 value))
       (pos? (count (t/triple-slot0 value)))
       (= tx-sequence-predicate (t/triple-slot1 value))
       (integer? (t/triple-slot2 value))
       (not (neg? (t/triple-slot2 value)))))

(defn occ-coordinate? [value]
  (and (t/triple? value)
       (tx-coordinate? (t/triple-slot0 value))
       (= op-ordinal-predicate (t/triple-slot1 value))
       (integer? (t/triple-slot2 value))
       (not (neg? (t/triple-slot2 value)))))

;; Int atoms widen to Long and non-integral numbers to Double before a
;; proposition is durable, so equal-proposition identity is decided on the
;; canonical form.
(defn canonical [value]
  (cond
    (t/triple? value) (t/triple (canonical (t/triple-slot0 value))
                                (canonical (t/triple-slot1 value))
                                (canonical (t/triple-slot2 value)))
    (integer? value) (long value)
    (t/instant? value) value
    (number? value) (double value)
    :else value))

;; --------------------------------------------------------------------- state

(defn new-model
  "OPTIONS may carry :dedupe-live-assertions? — a deliberate negation of the
   new-occurrence-per-assert contract, used only as a harness negative control."
  ([space-id] (new-model space-id nil))
  ([space-id options]
   {:space-id space-id
    :next-sequence 1
    :ops []
    :active {}
    :dedupe-live-assertions? (boolean (:dedupe-live-assertions? options))}))

(defn current-transaction [model]
  (tx-coordinate (:space-id model) (dec (:next-sequence model))))

(defn- append-operation [model {:keys [action proposition]} sequence ordinal]
  (let [position (count (:ops model))
        stack (get (:active model) proposition [])
        row {:sequence sequence :ordinal ordinal :action action
             :proposition proposition :live? (= :assert action) :withdraws nil}]
    (if (= :assert action)
      (-> model
          (update :ops conj
                  (cond-> row
                    (and (:dedupe-live-assertions? model) (seq stack))
                    (assoc :live? false)))
          (assoc-in [:active proposition] (conj stack position)))
      (if (empty? stack)
        (update model :ops conj row)
        (let [target (peek stack)]
          (-> model
              (assoc-in [:ops target :live?] false)
              (update :ops conj (assoc row :withdraws target))
              (assoc-in [:active proposition] (pop stack))))))))

;; --------------------------------------------------------------- projections

(defn- op-occurrence [model position]
  (let [row (nth (:ops model) position)]
    (occ-coordinate (tx-coordinate (:space-id model) (:sequence row))
                    (:ordinal row))))

(defn- op-event [model position]
  (let [row (nth (:ops model) position)]
    (t/triple (op-occurrence model position)
              (if (= :assert (:action row)) asserts-predicate retracts-predicate)
              (:proposition row))))

(defn all-events [model]
  (mapv #(op-event model %) (range (count (:ops model)))))

(defn operation-events-between [model lower-exclusive upper-inclusive]
  (into []
        (comp
         (filter (fn [position]
                   (let [sequence (:sequence (nth (:ops model) position))]
                     (< lower-exclusive sequence (inc upper-inclusive)))))
         (map #(op-event model %)))
        (range (count (:ops model)))))

(defn history [model]
  (into []
        (mapcat (fn [position]
                  (let [target (:withdraws (nth (:ops model) position))]
                    (if target
                      [(op-event model position)
                       (t/triple (op-occurrence model position)
                                 withdraws-predicate
                                 (op-occurrence model target))]
                      [(op-event model position)]))))
        (range (count (:ops model)))))

(defn- live-positions [model]
  (filterv #(:live? (nth (:ops model) %)) (range (count (:ops model)))))

(defn store-live-occurrences [model]
  (mapv #(op-event model %) (live-positions model)))

(defn store-live-propositions [model]
  (mapv #(:proposition (nth (:ops model) %)) (live-positions model)))

(defn store-live-propositions-as-of [model upper-inclusive]
  (let [{:keys [live]}
        (reduce
         (fn [{:keys [active live] :as state} position]
           (let [{:keys [sequence action proposition]} (nth (:ops model) position)]
             (if (> sequence upper-inclusive)
               state
               (let [stack (get active proposition [])]
                 (if (= :assert action)
                   {:active (assoc active proposition (conj stack position))
                    :live (conj live position)}
                   (if (empty? stack)
                     state
                     {:active (assoc active proposition (pop stack))
                      :live (disj live (peek stack))}))))))
         {:active {} :live #{}}
         (range (count (:ops model))))]
    (mapv #(:proposition (nth (:ops model) %)) (sort live))))

(defn- relation-proposition? [predicate value]
  (and (t/triple? value)
       (occ-coordinate? (t/triple-slot0 value))
       (= predicate (t/triple-slot1 value))
       (occ-coordinate? (t/triple-slot2 value))))

(defn supersession-triples [model]
  (filterv #(relation-proposition? supersedes-predicate %)
           (store-live-propositions model)))

(defn- live-withdrawal-propositions [model]
  (filterv #(relation-proposition? withdraws-predicate %)
           (store-live-propositions model)))

(defn withdrawal-triples [model]
  (vec
   (distinct
    (concat
     (into []
           (comp (filter #(:withdraws (nth (:ops model) %)))
                 (map (fn [position]
                        (t/triple (op-occurrence model position)
                                  withdraws-predicate
                                  (op-occurrence
                                   model
                                   (:withdraws (nth (:ops model) position)))))))
           (range (count (:ops model))))
     (live-withdrawal-propositions model)))))

;; An occurrence named as the target of a live supersession or withdrawal
;; relation is no longer effective, even though its store row stays live.
(defn- suppressed-coordinates [model]
  (into #{}
        (map t/triple-slot2)
        (concat (supersession-triples model)
                (live-withdrawal-propositions model))))

(defn live-occurrences [model]
  (let [suppressed (suppressed-coordinates model)]
    (filterv #(not (contains? suppressed (t/triple-slot0 %)))
             (store-live-occurrences model))))

(defn live-propositions [model]
  (mapv t/triple-slot2 (live-occurrences model)))

(defn occurrence-event [model coordinate]
  (some #(when (= coordinate (t/triple-slot0 %)) %) (all-events model)))

;; ------------------------------------------------------------------ mutation

(defn- metadata-operations [tx operations request]
  (let [per-source
        (into []
              (mapcat
               (fn [[ordinal operation]]
                 (let [source (occ-coordinate tx ordinal)]
                   (keep (fn [predicate]
                           (let [raw (get operation (metadata-source-key predicate))
                                 value (if (contains? #{withdraws-predicate
                                                        supersedes-predicate}
                                                      predicate)
                                         raw
                                         (some-> raw canonical))]
                             (when (some? value)
                               {:action :assert
                                :proposition (t/triple source predicate value)})))
                         metadata-order))))
              (map-indexed vector operations))
        tx-metadata
        (cond-> []
          (:recorded-at request)
          (conj {:action :assert
                 :proposition (t/triple tx :kernel/recorded-at
                                        (canonical (:recorded-at request)))})
          (:actor request)
          (conj {:action :assert
                 :proposition (t/triple tx :kernel/asserted-by
                                        (canonical (:actor request)))}))]
    (into per-source tx-metadata)))

(defn commit
  "Model one transaction. Returns {:model model' :receipt receipt}."
  [model {:keys [operations base] :as request}]
  (let [current (current-transaction model)]
    (if (and base (not= base current))
      {:model model
       :receipt {:reject :conflict :expected base :current current}}
      (let [sequence (:next-sequence model)
            tx (tx-coordinate (:space-id model) sequence)
            source (mapv (fn [operation]
                           {:action (:action operation)
                            :proposition (canonical (:proposition operation))})
                         operations)
            all (into source (metadata-operations tx operations request))
            first-position (count (:ops model))
            committed (-> (reduce (fn [acc [ordinal operation]]
                                    (append-operation acc operation sequence ordinal))
                                  model
                                  (map-indexed vector all))
                          (assoc :next-sequence (inc sequence)))
            source-positions (range first-position
                                    (+ first-position (count source)))
            coordinates (into #{} (map #(op-occurrence committed %)) source-positions)]
        {:model committed
         :receipt {:ok tx
                   :occurrences (mapv #(op-event committed %) source-positions)
                   :withdrawals (filterv #(contains? coordinates (t/triple-slot0 %))
                                         (withdrawal-triples committed))
                   :operation-count (count all)}}))))

(defn assert-proposition [model proposition options]
  (commit model (assoc options :operations
                       [{:action :assert :proposition proposition
                         :supersedes (:supersedes options)
                         :source-frame (:source-frame options)}])))

(defn retract-proposition [model proposition options]
  (commit model (assoc options :operations
                       [{:action :retract :proposition proposition
                         :withdraws (:withdraws options)
                         :source-frame (:source-frame options)}])))

(defn withdraw-occurrence [model target options]
  (let [event (occurrence-event model target)
        effective (into #{} (map t/triple-slot0) (live-occurrences model))]
    (cond
      (nil? event)
      {:model model :receipt {:reject :unknown-occurrence :occurrence target}}

      (not= asserts-predicate (t/triple-slot1 event))
      {:model model :receipt {:reject :not-assertion-occurrence :occurrence target}}

      (not (contains? effective target))
      {:model model :receipt {:reject :occurrence-not-live :occurrence target}}

      :else
      ;; The public target must be the same occurrence a bare retract would
      ;; withdraw: the latest store-live equal one.
      (let [proposition (t/triple-slot2 event)
            matching (filterv #(= proposition (t/triple-slot2 %))
                              (store-live-occurrences model))
            current (some-> (peek matching) t/triple-slot0)]
        (if (not= target current)
          {:model model
           :receipt {:reject :withdrawal-target-not-current
                     :occurrence target :current current}}
          (retract-proposition model proposition
                               (assoc options :withdraws target)))))))

(defn supersede [model target replacement options]
  (if-not (some #{target} (map t/triple-slot0 (live-occurrences model)))
    {:model model :receipt {:reject :occurrence-not-live :occurrence target}}
    (assert-proposition model replacement (assoc options :supersedes target))))
