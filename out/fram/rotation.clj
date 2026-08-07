(ns fram.rotation
  (:require [fram.types :as t]
            [fram.store :as store]))

(defrecord Bucket [width keys events slots])

(defn bucket-width [r] (:width r))

(defn bucket-keys [r] (:keys r))

(defn bucket-events [r] (:events r))

(defn bucket-slots [r] (:slots r))

(defrecord Rotation [space-id version events by-occurrence spo pos osp])

(defn rotation-space-id [r] (:space-id r))

(defn rotation-version [r] (:version r))

(defn rotation-events [r] (:events r))

(defn rotation-by-occurrence [r] (:by-occurrence r))

(defn rotation-spo [r] (:spo r))

(defn rotation-pos [r] (:pos r))

(defn rotation-osp [r] (:osp r))

(def empty-events [])

(def empty-keys [])

(def empty-event-lists [])

(def empty-positions [])

(def empty-slot-rows [])

(def bucket-initial-slots 64)

(def bucket-slot-load 4)

(defn- fresh-slot-rows [width]
  (loop [rows empty-slot-rows
   position 0]
  (if (>= position width) rows (recur (conj rows empty-positions) (inc position)))))

(defn- ^Bucket empty-bucket []
  (->Bucket bucket-initial-slots empty-keys empty-event-lists (fresh-slot-rows bucket-initial-slots)))

(defn- bucket-slot [key width]
  (mod (hash key) width))

(defn- bucket-entry [^Bucket bucket key]
  (let [keys (bucket-keys bucket)
   positions (nth (bucket-slots bucket) (bucket-slot key (bucket-width bucket)))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (= (nth keys position) key) position (recur (inc offset))))))))

(defn- bucket-get [^Bucket bucket key]
  (let [position (bucket-entry bucket key)]
  (if (>= position 0) (nth (bucket-events bucket) position) empty-events)))

(defn- ^Bucket bucket-rehash [^Bucket bucket width]
  (let [keys (bucket-keys bucket)]
  (loop [rows (fresh-slot-rows width)
   position 0]
  (if (>= position (count keys)) (->Bucket width keys (bucket-events bucket) rows) (let [slot (bucket-slot (nth keys position) width)]
  (recur (assoc rows slot (conj (nth rows slot) position)) (inc position)))))))

(defn- ^Bucket bucket-set [^Bucket bucket key events]
  (let [at (bucket-entry bucket key)]
  (if (>= at 0) (->Bucket (bucket-width bucket) (bucket-keys bucket) (assoc (bucket-events bucket) at events) (bucket-slots bucket)) (let [width (bucket-width bucket)
   position (count (bucket-keys bucket))
   keys (conj (bucket-keys bucket) key)
   lists (conj (bucket-events bucket) events)
   slot (bucket-slot key width)
   rows (assoc (bucket-slots bucket) slot (conj (nth (bucket-slots bucket) slot) position))
   grown (->Bucket width keys lists rows)]
  (if (> (count keys) (* bucket-slot-load width)) (bucket-rehash grown (* 2 width)) grown)))))

(defn- one-event [event]
  [event])

(defn- occurrence-key [occurrence]
  [occurrence])

(defn occurrence-of [event]
  (t/triple-t1 event))

(defn proposition-of [event]
  (t/triple-t3 event))

(defn ^Boolean assertion-occurrence? [event]
  (and (t/triple? event) (and (= t/asserts (t/triple-t2 event)) (and (t/occurrence-coordinate? (t/triple-t1 event)) (t/triple? (t/triple-t3 event))))))

(defn- checked-assertion [event]
  (if (assertion-occurrence? event) event (throw (ex-info "fram: rotations cover assertion occurrences only" {:type :invalid-rotation-occurrence}))))

(defn- ^Bucket bucket-add [^Bucket bucket key event]
  (bucket-set bucket key (conj (bucket-get bucket key) event)))

(defn- without-event [events target]
  (filterv (fn [event] (not (= event target))) events))

(defn- ^Bucket bucket-del [^Bucket bucket key event]
  (bucket-set bucket key (without-event (bucket-get bucket key) event)))

(defn- ^Rotation rotate-add [^Rotation rotation event]
  (let [proposition (proposition-of (checked-assertion event))
   t1 (t/triple-t1 proposition)
   t2 (t/triple-t2 proposition)
   t3 (t/triple-t3 proposition)]
  (->Rotation (rotation-space-id rotation) (rotation-version rotation) (conj (rotation-events rotation) event) (bucket-set (rotation-by-occurrence rotation) (occurrence-key (occurrence-of event)) (one-event event)) (bucket-add (bucket-add (bucket-add (rotation-spo rotation) [t1] event) [t1 t2] event) [t1 t2 t3] event) (bucket-add (bucket-add (rotation-pos rotation) [t2] event) [t2 t3] event) (bucket-add (bucket-add (rotation-osp rotation) [t3] event) [t3 t1] event))))

(defn- ^Rotation rotate-del [^Rotation rotation event]
  (let [proposition (proposition-of (checked-assertion event))
   t1 (t/triple-t1 proposition)
   t2 (t/triple-t2 proposition)
   t3 (t/triple-t3 proposition)]
  (->Rotation (rotation-space-id rotation) (rotation-version rotation) (without-event (rotation-events rotation) event) (bucket-set (rotation-by-occurrence rotation) (occurrence-key (occurrence-of event)) empty-events) (bucket-del (bucket-del (bucket-del (rotation-spo rotation) [t1] event) [t1 t2] event) [t1 t2 t3] event) (bucket-del (bucket-del (rotation-pos rotation) [t2] event) [t2 t3] event) (bucket-del (bucket-del (rotation-osp rotation) [t3] event) [t3 t1] event))))

(defn- ^Rotation empty-rotation [^String space-id version]
  (->Rotation space-id version empty-events (empty-bucket) (empty-bucket) (empty-bucket) (empty-bucket)))

(defn ^String space-id [^Rotation rotation]
  (rotation-space-id rotation))

(defn version [^Rotation rotation]
  (rotation-version rotation))

(defn all-occurrences [^Rotation rotation]
  (rotation-events rotation))

(defn occurrence-count [^Rotation rotation]
  (count (rotation-events rotation)))

(defn by-t1 [^Rotation rotation t1]
  (bucket-get (rotation-spo rotation) [t1]))

(defn by-t12 [^Rotation rotation t1 t2]
  (bucket-get (rotation-spo rotation) [t1 t2]))

(defn by-t2 [^Rotation rotation t2]
  (bucket-get (rotation-pos rotation) [t2]))

(defn by-t23 [^Rotation rotation t2 t3]
  (bucket-get (rotation-pos rotation) [t2 t3]))

(defn by-t3 [^Rotation rotation t3]
  (bucket-get (rotation-osp rotation) [t3]))

(defn by-t13 [^Rotation rotation t1 t3]
  (bucket-get (rotation-osp rotation) [t3 t1]))

(defn by-proposition [^Rotation rotation proposition]
  (bucket-get (rotation-spo rotation) [(t/triple-t1 proposition) (t/triple-t2 proposition) (t/triple-t3 proposition)]))

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
  (not (empty? (bucket-get (rotation-by-occurrence rotation) (occurrence-key occurrence)))))

(defn event-at [^Rotation rotation occurrence]
  (let [events (bucket-get (rotation-by-occurrence rotation) (occurrence-key occurrence))]
  (if (empty? events) nil (nth events 0))))

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

(defrecord BucketBuild [width keys events slots])

(defn bucketbuild-width [r] (:width r))

(defn bucketbuild-keys [r] (:keys r))

(defn bucketbuild-events [r] (:events r))

(defn bucketbuild-slots [r] (:slots r))

(defrecord RotationBuild [events by-occurrence spo pos osp])

(defn rotationbuild-events [r] (:events r))

(defn rotationbuild-by-occurrence [r] (:by-occurrence r))

(defn rotationbuild-spo [r] (:spo r))

(defn rotationbuild-pos [r] (:pos r))

(defn rotationbuild-osp [r] (:osp r))

(def empty-event-cells [])

(def empty-position-cells [])

(defn- fresh-position-cells [width]
  (loop [cells empty-position-cells
   position 0]
  (if (>= position width) cells (recur (conj cells (atom empty-positions)) (inc position)))))

(defn- cells-add! [cells slot position]
  (do
  (swap! (nth cells slot) conj position)
  cells))

(defn- events-add! [cells at event]
  (do
  (swap! (nth cells at) conj event)
  cells))

(defn- events-reset! [cells at events]
  (do
  (reset! (nth cells at) events)
  cells))

(defn- ^BucketBuild open-bucket-build []
  (->BucketBuild bucket-initial-slots empty-keys empty-event-cells (fresh-position-cells bucket-initial-slots)))

(defn- build-entry [^BucketBuild build key]
  (let [keys (bucketbuild-keys build)
   positions (deref (nth (bucketbuild-slots build) (bucket-slot key (bucketbuild-width build))))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (= (nth keys position) key) position (recur (inc offset))))))))

(defn- ^BucketBuild build-rehash! [^BucketBuild build width]
  (let [keys (bucketbuild-keys build)]
  (loop [cells (fresh-position-cells width)
   position 0]
  (if (>= position (count keys)) (->BucketBuild width keys (bucketbuild-events build) cells) (recur (cells-add! cells (bucket-slot (nth keys position) width) position) (inc position))))))

(defn- ^BucketBuild build-append! [^BucketBuild build key events]
  (let [width (bucketbuild-width build)
   position (count (bucketbuild-keys build))
   keys (conj (bucketbuild-keys build) key)
   cells (conj (bucketbuild-events build) (atom events))
   grown (->BucketBuild width keys cells (cells-add! (bucketbuild-slots build) (bucket-slot key width) position))]
  (if (> (count keys) (* bucket-slot-load width)) (build-rehash! grown (* 2 width)) grown)))

(defn- ^BucketBuild build-add! [^BucketBuild build key event]
  (let [at (build-entry build key)]
  (if (>= at 0) (->BucketBuild (bucketbuild-width build) (bucketbuild-keys build) (events-add! (bucketbuild-events build) at event) (bucketbuild-slots build)) (build-append! build key (one-event event)))))

(defn- ^BucketBuild build-set! [^BucketBuild build key event]
  (let [at (build-entry build key)]
  (if (>= at 0) (->BucketBuild (bucketbuild-width build) (bucketbuild-keys build) (events-reset! (bucketbuild-events build) at (one-event event)) (bucketbuild-slots build)) (build-append! build key (one-event event)))))

(defn- close-event-cells [cells]
  (loop [lists empty-event-lists
   position 0]
  (if (>= position (count cells)) lists (recur (conj lists (deref (nth cells position))) (inc position)))))

(defn- close-position-cells [cells]
  (loop [rows empty-slot-rows
   position 0]
  (if (>= position (count cells)) rows (recur (conj rows (deref (nth cells position))) (inc position)))))

(defn- ^Bucket close-bucket-build [^BucketBuild build]
  (->Bucket (bucketbuild-width build) (bucketbuild-keys build) (close-event-cells (bucketbuild-events build)) (close-position-cells (bucketbuild-slots build))))

(defn- ^RotationBuild build-rotate-add! [^RotationBuild build event]
  (let [proposition (proposition-of (checked-assertion event))
   t1 (t/triple-t1 proposition)
   t2 (t/triple-t2 proposition)
   t3 (t/triple-t3 proposition)]
  (->RotationBuild (conj (rotationbuild-events build) event) (build-set! (rotationbuild-by-occurrence build) (occurrence-key (occurrence-of event)) event) (build-add! (build-add! (build-add! (rotationbuild-spo build) [t1] event) [t1 t2] event) [t1 t2 t3] event) (build-add! (build-add! (rotationbuild-pos build) [t2] event) [t2 t3] event) (build-add! (build-add! (rotationbuild-osp build) [t3] event) [t3 t1] event))))

(defn ^Rotation project! [ctx]
  (let [built (reduce (fn [build event] (build-rotate-add! build event)) (->RotationBuild empty-events (open-bucket-build) (open-bucket-build) (open-bucket-build) (open-bucket-build)) (store/live-occurrences ctx))]
  (->Rotation (store/space-id ctx) (store/current-sequence ctx) (rotationbuild-events built) (close-bucket-build (rotationbuild-by-occurrence built)) (close-bucket-build (rotationbuild-spo built)) (close-bucket-build (rotationbuild-pos built)) (close-bucket-build (rotationbuild-osp built)))))

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
