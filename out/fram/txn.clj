(ns fram.txn
  (:require [fram.types :as t]
            [fram.store :as store]
            [fram.rotation :as rot]))

(def mint-ordinal :mint-ordinal)

(defrecord Builder [coordinate operations minted])

(defn builder-coordinate [r] (:coordinate r))

(defn builder-operations [r] (:operations r))

(defn builder-minted [r] (:minted r))

(def empty-operations [])

(defn open [ctx]
  (atom (->Builder (t/transaction-coordinate (store/space-id ctx) (store/next-sequence ctx)) empty-operations 0)))

(defn coordinate [builder]
  (builder-coordinate (deref builder)))

(defn sequence-of [builder]
  (t/triple-slot2 (coordinate builder)))

(defn operations [builder]
  (builder-operations (deref builder)))

(defn operation-count [builder]
  (count (operations builder)))

(defn minted-count [builder]
  (builder-minted (deref builder)))

(defn mint-coordinate [transaction ordinal]
  (if (and (t/transaction-coordinate? transaction) (>= ordinal 0)) (t/triple transaction mint-ordinal ordinal) (throw (ex-info "fram: a mint coordinate requires a transaction coordinate and non-negative ordinal" {:type :invalid-mint-coordinate}))))

(defn ^Boolean mint-coordinate? [value]
  (and (t/triple? value) (and (t/transaction-coordinate? (t/triple-slot0 value)) (and (= mint-ordinal (t/triple-slot1 value)) (and (integer? (t/triple-slot2 value)) (>= (t/triple-slot2 value) 0))))))

(defn mint! [builder]
  (let [before (deref builder)
   ordinal (builder-minted before)]
  (do
  (swap! builder assoc :minted (inc ordinal))
  (mint-coordinate (builder-coordinate before) ordinal))))

(defn- append! [builder appended]
  (if (empty? appended) (throw (ex-info "fram: an empty operation vector has no write identity" {:type :empty-operation-vector})) (let [before (deref builder)
   start (count (builder-operations before))]
  (do
  (swap! builder assoc :operations (vec (concat (builder-operations before) appended)))
  (t/occurrence-coordinate (builder-coordinate before) (+ start (dec (count appended))))))))

(defn assert! [builder proposition]
  (append! builder [(store/assert-operation proposition)]))

(defn retract! [builder proposition]
  (append! builder [(store/retract-operation proposition)]))

(defn compile-single-update [view subject predicate value]
  (conj (mapv (fn [event] (store/retract-operation (rot/proposition-of event))) (rot/newest-first (rot/by-slot01 view subject predicate))) (store/assert-operation (t/triple subject predicate value))))

(defn update-single! [builder view subject predicate value]
  (append! builder (compile-single-update view subject predicate value)))

(defn commit! [ctx builder]
  (let [current (deref builder)
   pinned (builder-coordinate current)]
  (if (not (= (t/triple-slot0 pinned) (store/space-id ctx))) (throw (ex-info "fram: transaction belongs to a different space" {:type :transaction-space-mismatch})) (if (not (= (t/triple-slot2 pinned) (store/next-sequence ctx))) (throw (ex-info "fram: the store advanced under this transaction" {:type :transaction-sequence-drift :pinned (t/triple-slot2 pinned) :observed (store/next-sequence ctx)})) (store/commit-transaction! ctx (builder-operations current))))))
