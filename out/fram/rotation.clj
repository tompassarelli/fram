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
  (t/triple-t1 event))

(defn proposition-of [event]
  (t/triple-t3 event))

(defn ^Boolean assertion-occurrence? [event]
  (and (t/triple? event) (and (= t/asserts (t/triple-t2 event)) (and (t/occurrence-coordinate? (t/triple-t1 event)) (t/triple? (t/triple-t3 event))))))

(defn- checked-assertion [event]
  (if (assertion-occurrence? event) event (throw (ex-info "fram: rotations cover assertion occurrences only" {:type :invalid-rotation-occurrence}))))

(defn- bucket-add [bucket key event]
  (assoc bucket key (conj (get bucket key empty-events) event)))

(defn- without-event [events target]
  (filterv (fn [event] (not (= event target))) events))

(defn- bucket-del [bucket key event]
  (let [remaining (without-event (get bucket key empty-events) event)]
  (if (empty? remaining) (dissoc bucket key) (assoc bucket key remaining))))

(defn- ^Rotation rotate-add [^Rotation rotation event]
  (let [proposition (proposition-of (checked-assertion event))
   t1 (t/triple-t1 proposition)
   t2 (t/triple-t2 proposition)
   t3 (t/triple-t3 proposition)]
  (->Rotation (rotation-space-id rotation) (rotation-version rotation) (conj (rotation-events rotation) event) (assoc (rotation-by-occurrence rotation) (occurrence-of event) event) (bucket-add (bucket-add (bucket-add (rotation-spo rotation) [t1] event) [t1 t2] event) [t1 t2 t3] event) (bucket-add (bucket-add (rotation-pos rotation) [t2] event) [t2 t3] event) (bucket-add (bucket-add (rotation-osp rotation) [t3] event) [t3 t1] event))))

(defn- ^Rotation rotate-del [^Rotation rotation event]
  (let [proposition (proposition-of (checked-assertion event))
   t1 (t/triple-t1 proposition)
   t2 (t/triple-t2 proposition)
   t3 (t/triple-t3 proposition)]
  (->Rotation (rotation-space-id rotation) (rotation-version rotation) (without-event (rotation-events rotation) event) (dissoc (rotation-by-occurrence rotation) (occurrence-of event)) (bucket-del (bucket-del (bucket-del (rotation-spo rotation) [t1] event) [t1 t2] event) [t1 t2 t3] event) (bucket-del (bucket-del (rotation-pos rotation) [t2] event) [t2 t3] event) (bucket-del (bucket-del (rotation-osp rotation) [t3] event) [t3 t1] event))))

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

(defn by-t1 [^Rotation rotation t1]
  (get (rotation-spo rotation) [t1] empty-events))

(defn by-t12 [^Rotation rotation t1 t2]
  (get (rotation-spo rotation) [t1 t2] empty-events))

(defn by-t2 [^Rotation rotation t2]
  (get (rotation-pos rotation) [t2] empty-events))

(defn by-t23 [^Rotation rotation t2 t3]
  (get (rotation-pos rotation) [t2 t3] empty-events))

(defn by-t3 [^Rotation rotation t3]
  (get (rotation-osp rotation) [t3] empty-events))

(defn by-t13 [^Rotation rotation t1 t3]
  (get (rotation-osp rotation) [t3 t1] empty-events))

(defn by-proposition [^Rotation rotation proposition]
  (get (rotation-spo rotation) [(t/triple-t1 proposition) (t/triple-t2 proposition) (t/triple-t3 proposition)] empty-events))

(defn matching [^Rotation rotation t1 t2 t3]
  (cond
  (and (some? t1) (and (some? t2) (some? t3))) (by-proposition rotation (t/triple t1 t2 t3))
  (and (some? t1) (some? t2)) (by-t12 rotation t1 t2)
  (and (some? t2) (some? t3)) (by-t23 rotation t2 t3)
  (and (some? t1) (some? t3)) (by-t13 rotation t1 t3)
  (some? t1) (by-t1 rotation t1)
  (some? t2) (by-t2 rotation t2)
  (some? t3) (by-t3 rotation t3)
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
  (mapv (fn [event] (t/triple-t1 (proposition-of event))) events))

(defn values [events]
  (mapv (fn [event] (t/triple-t3 (proposition-of event))) events))

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

(defn ^Rotation staged [^Rotation rotation ^String space-id sequence operations]
  (apply-frame rotation space-id (t/->TransactionFrame sequence operations)))

(defn ^Rotation refresh [^Rotation rotation ctx]
  (let [space (store/space-id ctx)
   target (store/current-sequence ctx)
   pinned (rotation-version rotation)]
  (if (not (= space (rotation-space-id rotation))) (throw (ex-info "fram: rotation belongs to a different space" {:type :rotation-space-mismatch})) (if (> pinned target) (throw (ex-info "fram: rotation is ahead of the store it projects" {:type :rotation-ahead-of-store})) (if (= pinned target) rotation (assoc (reduce (fn [current frame] (apply-frame current space frame)) rotation (store/transaction-frames-between (deref ctx) pinned target)) :version target))))))

(defn ^Boolean pinned? [^Rotation rotation ctx]
  (and (= (store/space-id ctx) (rotation-space-id rotation)) (= (store/current-sequence ctx) (rotation-version rotation))))
