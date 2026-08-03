(ns fram.rotation
  (:require [fram.types :as t]
            [fram.store :as store]))

(defrecord Rotation [space-id version events by-occurrence spo pos osp])

(defn rotation-space-id [r] (:space-id r))

(defn rotation-version [r] (:version r))

(defn rotation-events [r] (:events r))

(defn rotation-by-occurrence [r] (:by-occurrence r))

(defn rotation-spo [r] (:spo r))

(defn rotation-pos [r] (:pos r))

(defn rotation-osp [r] (:osp r))

(def empty-events [])

(def empty-bucket {})

(def empty-occurrences {})

(defn occurrence-of [event]
  (t/triple-slot0 event))

(defn proposition-of [event]
  (t/triple-slot2 event))

(defn ^Boolean assertion-occurrence? [event]
  (and (t/triple? event) (and (= t/asserts (t/triple-slot1 event)) (and (t/occurrence-coordinate? (t/triple-slot0 event)) (t/triple? (t/triple-slot2 event))))))

(defn- assertion! [event]
  (if (assertion-occurrence? event) event (throw (ex-info "fram: rotations cover assertion occurrences only" {:type :invalid-rotation-occurrence}))))

(defn- bucket-add [bucket key event]
  (assoc bucket key (conj (get bucket key empty-events) event)))

(defn- without-event [events target]
  (filterv (fn [event] (not (= event target))) events))

(defn- bucket-del [bucket key event]
  (let [remaining (without-event (get bucket key empty-events) event)]
  (if (empty? remaining) (dissoc bucket key) (assoc bucket key remaining))))

(defn- ^Rotation rotate-add [^Rotation rotation event]
  (let [proposition (proposition-of (assertion! event))
   slot0 (t/triple-slot0 proposition)
   slot1 (t/triple-slot1 proposition)
   slot2 (t/triple-slot2 proposition)]
  (->Rotation (rotation-space-id rotation) (rotation-version rotation) (conj (rotation-events rotation) event) (assoc (rotation-by-occurrence rotation) (occurrence-of event) event) (bucket-add (bucket-add (bucket-add (rotation-spo rotation) [slot0] event) [slot0 slot1] event) [slot0 slot1 slot2] event) (bucket-add (bucket-add (rotation-pos rotation) [slot1] event) [slot1 slot2] event) (bucket-add (bucket-add (rotation-osp rotation) [slot2] event) [slot2 slot0] event))))

(defn- ^Rotation rotate-del [^Rotation rotation event]
  (let [proposition (proposition-of (assertion! event))
   slot0 (t/triple-slot0 proposition)
   slot1 (t/triple-slot1 proposition)
   slot2 (t/triple-slot2 proposition)]
  (->Rotation (rotation-space-id rotation) (rotation-version rotation) (without-event (rotation-events rotation) event) (dissoc (rotation-by-occurrence rotation) (occurrence-of event)) (bucket-del (bucket-del (bucket-del (rotation-spo rotation) [slot0] event) [slot0 slot1] event) [slot0 slot1 slot2] event) (bucket-del (bucket-del (rotation-pos rotation) [slot1] event) [slot1 slot2] event) (bucket-del (bucket-del (rotation-osp rotation) [slot2] event) [slot2 slot0] event))))

(defn- ^Rotation empty-rotation [^String space-id version]
  (->Rotation space-id version empty-events empty-occurrences empty-bucket empty-bucket empty-bucket))

(defn ^String space-id [^Rotation rotation]
  (rotation-space-id rotation))

(defn version [^Rotation rotation]
  (rotation-version rotation))

(defn all-occurrences [^Rotation rotation]
  (rotation-events rotation))

(defn occurrence-count [^Rotation rotation]
  (count (rotation-events rotation)))

(defn by-slot0 [^Rotation rotation slot0]
  (get (rotation-spo rotation) [slot0] empty-events))

(defn by-slot01 [^Rotation rotation slot0 slot1]
  (get (rotation-spo rotation) [slot0 slot1] empty-events))

(defn by-slot1 [^Rotation rotation slot1]
  (get (rotation-pos rotation) [slot1] empty-events))

(defn by-slot12 [^Rotation rotation slot1 slot2]
  (get (rotation-pos rotation) [slot1 slot2] empty-events))

(defn by-slot2 [^Rotation rotation slot2]
  (get (rotation-osp rotation) [slot2] empty-events))

(defn by-slot02 [^Rotation rotation slot0 slot2]
  (get (rotation-osp rotation) [slot2 slot0] empty-events))

(defn by-proposition [^Rotation rotation proposition]
  (get (rotation-spo rotation) [(t/triple-slot0 proposition) (t/triple-slot1 proposition) (t/triple-slot2 proposition)] empty-events))

(defn matching [^Rotation rotation slot0 slot1 slot2]
  (cond
  (and (some? slot0) (and (some? slot1) (some? slot2))) (by-proposition rotation (t/triple slot0 slot1 slot2))
  (and (some? slot0) (some? slot1)) (by-slot01 rotation slot0 slot1)
  (and (some? slot1) (some? slot2)) (by-slot12 rotation slot1 slot2)
  (and (some? slot0) (some? slot2)) (by-slot02 rotation slot0 slot2)
  (some? slot0) (by-slot0 rotation slot0)
  (some? slot1) (by-slot1 rotation slot1)
  (some? slot2) (by-slot2 rotation slot2)
  :else (rotation-events rotation)))

(defn ^Boolean live-occurrence? [^Rotation rotation occurrence]
  (contains? (rotation-by-occurrence rotation) occurrence))

(defn event-at [^Rotation rotation occurrence]
  (get (rotation-by-occurrence rotation) occurrence))

(defn newest-first [events]
  (vec (reverse events)))

(defn newest [events]
  (if (empty? events) nil (last events)))

(defn propositions [events]
  (mapv (fn [event] (proposition-of event)) events))

(defn subjects [events]
  (mapv (fn [event] (t/triple-slot0 (proposition-of event))) events))

(defn values [events]
  (mapv (fn [event] (t/triple-slot2 (proposition-of event))) events))

(defn occurrences [events]
  (mapv (fn [event] (occurrence-of event)) events))

(defn ^Rotation project [ctx]
  (reduce (fn [rotation event] (rotate-add rotation event)) (empty-rotation (store/space-id ctx) (store/current-sequence ctx)) (store/live-occurrences ctx)))

(defn- ^Rotation apply-frame [^Rotation rotation ^String space-id frame]
  (let [coordinate (t/transaction-coordinate space-id (t/transactionframe-sequence frame))
   operations (t/transactionframe-operations frame)]
  (loop [current rotation
   ordinal 0]
  (if (>= ordinal (count operations)) current (let [operation (nth operations ordinal)
   proposition (t/commitoperation-proposition operation)]
  (if (= t/assert-action (t/commitoperation-action operation)) (recur (rotate-add current (t/assertion-occurrence (t/occurrence-coordinate coordinate ordinal) proposition)) (inc ordinal)) (let [target (newest (by-proposition current proposition))]
  (recur (if (some? target) (rotate-del current target) current) (inc ordinal)))))))))

(defn ^Rotation refresh [^Rotation rotation ctx]
  (let [space (store/space-id ctx)
   target (store/current-sequence ctx)
   pinned (rotation-version rotation)]
  (if (not (= space (rotation-space-id rotation))) (throw (ex-info "fram: rotation belongs to a different space" {:type :rotation-space-mismatch})) (if (> pinned target) (throw (ex-info "fram: rotation is ahead of the store it projects" {:type :rotation-ahead-of-store})) (if (= pinned target) rotation (assoc (reduce (fn [current frame] (apply-frame current space frame)) rotation (store/transaction-frames-between (deref ctx) pinned target)) :version target))))))

(defn ^Boolean pinned? [^Rotation rotation ctx]
  (and (= (store/space-id ctx) (rotation-space-id rotation)) (= (store/current-sequence ctx) (rotation-version rotation))))
