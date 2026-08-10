(ns fram.rotation
  (:require [fram.types :as t]
            [fram.store :as store]))

(defrecord Bucket [width keys events slots])

(defn bucket-width [r] (:width r))

(defn bucket-keys [r] (:keys r))

(defn bucket-events [r] (:events r))

(defn bucket-slots [r] (:slots r))

(defrecord PendingOp [action proposition event])

(defn pendingop-action [r] (:action r))

(defn pendingop-proposition [r] (:proposition r))

(defn pendingop-event [r] (:event r))

(defrecord Rotation [space-id version events by-occurrence spo pos osp pending])

(defn rotation-space-id [r] (:space-id r))

(defn rotation-version [r] (:version r))

(defn rotation-events [r] (:events r))

(defn rotation-by-occurrence [r] (:by-occurrence r))

(defn rotation-spo [r] (:spo r))

(defn rotation-pos [r] (:pos r))

(defn rotation-osp [r] (:osp r))

(defn rotation-pending [r] (:pending r))

(def empty-events [])

(def empty-keys [])

(def empty-event-lists [])

(def empty-positions [])

(def empty-slot-rows [])

(def no-pending [])

(def bucket-initial-slots 64)

(def bucket-slot-load 4)

(def pending-fold-cap 512)

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

(defn- occurrence-key [occurrence]
  [occurrence])

(defn occurrence-of [event]
  (t/triple-t1 event))

(defn proposition-of [event]
  (t/triple-t3 event))

(defn ^Boolean assertion-occurrence? [event]
  (and (t/triple? event) (and (= t/asserts (t/triple-t2 event)) (and (t/occurrence-coordinate? (t/triple-t1 event)) (t/triple? (t/triple-t3 event))))))

(defn- ^Boolean slot-matches? [pattern term]
  (or (nil? pattern) (= pattern term)))

(defn- ^Boolean pattern-match? [proposition t1 t2 t3]
  (and (slot-matches? t1 (t/triple-t1 proposition)) (and (slot-matches? t2 (t/triple-t2 proposition)) (slot-matches? t3 (t/triple-t3 proposition)))))

(defn- without-newest-of [events proposition]
  (let [at (loop [position (dec (count events))]
  (if (< position 0) -1 (if (= proposition (proposition-of (nth events position))) position (recur (dec position)))))]
  (if (< at 0) events (loop [built empty-events
   position 0]
  (if (>= position (count events)) built (recur (if (= position at) built (conj built (nth events position))) (inc position)))))))

(defn- merged-matching [base pending t1 t2 t3]
  (if (empty? pending) base (loop [events base
   position 0]
  (if (>= position (count pending)) events (let [op (nth pending position)
   proposition (pendingop-proposition op)]
  (recur (if (= t/assert-action (pendingop-action op)) (if (pattern-match? proposition t1 t2 t3) (conj events (pendingop-event op)) events) (without-newest-of events proposition)) (inc position)))))))

(defn ^String space-id [^Rotation rotation]
  (rotation-space-id rotation))

(defn version [^Rotation rotation]
  (rotation-version rotation))

(defn all-occurrences [^Rotation rotation]
  (merged-matching (rotation-events rotation) (rotation-pending rotation) nil nil nil))

(defn occurrence-count [^Rotation rotation]
  (count (all-occurrences rotation)))

(defn by-t1 [^Rotation rotation t1]
  (merged-matching (bucket-get (rotation-spo rotation) [t1]) (rotation-pending rotation) t1 nil nil))

(defn by-t12 [^Rotation rotation t1 t2]
  (merged-matching (bucket-get (rotation-spo rotation) [t1 t2]) (rotation-pending rotation) t1 t2 nil))

(defn by-t2 [^Rotation rotation t2]
  (merged-matching (bucket-get (rotation-pos rotation) [t2]) (rotation-pending rotation) nil t2 nil))

(defn by-t23 [^Rotation rotation t2 t3]
  (merged-matching (bucket-get (rotation-pos rotation) [t2 t3]) (rotation-pending rotation) nil t2 t3))

(defn by-t3 [^Rotation rotation t3]
  (merged-matching (bucket-get (rotation-osp rotation) [t3]) (rotation-pending rotation) nil nil t3))

(defn by-t13 [^Rotation rotation t1 t3]
  (merged-matching (bucket-get (rotation-osp rotation) [t3 t1]) (rotation-pending rotation) t1 nil t3))

(defn by-proposition [^Rotation rotation proposition]
  (merged-matching (bucket-get (rotation-spo rotation) [(t/triple-t1 proposition) (t/triple-t2 proposition) (t/triple-t3 proposition)]) (rotation-pending rotation) (t/triple-t1 proposition) (t/triple-t2 proposition) (t/triple-t3 proposition)))

(defn matching [^Rotation rotation t1 t2 t3]
  (cond
  (and (some? t1) (and (some? t2) (some? t3))) (by-proposition rotation (t/triple t1 t2 t3))
  (and (some? t1) (some? t2)) (by-t12 rotation t1 t2)
  (and (some? t2) (some? t3)) (by-t23 rotation t2 t3)
  (and (some? t1) (some? t3)) (by-t13 rotation t1 t3)
  (some? t1) (by-t1 rotation t1)
  (some? t2) (by-t2 rotation t2)
  (some? t3) (by-t3 rotation t3)
  :else (all-occurrences rotation)))

(defn event-at [^Rotation rotation occurrence]
  (let [base (bucket-get (rotation-by-occurrence rotation) (occurrence-key occurrence))
   candidate (let [pending (rotation-pending rotation)]
  (loop [position 0
   found (if (empty? base) nil (nth base 0))]
  (if (>= position (count pending)) found (let [op (nth pending position)]
  (recur (inc position) (if (and (= t/assert-action (pendingop-action op)) (= occurrence (occurrence-of (pendingop-event op)))) (pendingop-event op) found))))))]
  (if (nil? candidate) nil (let [present candidate
   live (by-proposition rotation (proposition-of present))
   survives (loop [position 0]
  (if (>= position (count live)) false (if (= occurrence (occurrence-of (nth live position))) true (recur (inc position)))))]
  (if survives present nil)))))

(defn ^Boolean live-occurrence? [^Rotation rotation occurrence]
  (some? (event-at rotation occurrence)))

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

(def empty-event-cells [])

(def empty-position-cells [])

(defn- fresh-position-cells [width]
  (loop [cells empty-position-cells
   position 0]
  (if (>= position width) cells (recur (conj cells (atom empty-positions)) (inc position)))))

(defn- rehashed-position-cells! [keys width]
  (let [cells (fresh-position-cells width)]
  (loop [position 0]
  (if (>= position (count keys)) cells (do
  (swap! (nth cells (bucket-slot (nth keys position) width)) conj position)
  (recur (inc position)))))))

(defn- close-event-cells [cells]
  (loop [lists empty-event-lists
   position 0]
  (if (>= position (count cells)) lists (recur (conj lists (deref (nth cells position))) (inc position)))))

(defn- close-position-cells [cells]
  (loop [rows empty-slot-rows
   position 0]
  (if (>= position (count cells)) rows (recur (conj rows (deref (nth cells position))) (inc position)))))

(defn- checked-assertion [event]
  (if (assertion-occurrence? event) event (throw (ex-info "fram: rotations cover assertion occurrences only" {:type :invalid-rotation-occurrence}))))

(defn- entry-key [entry event]
  (let [proposition (proposition-of event)
   t1 (t/triple-t1 proposition)
   t2 (t/triple-t2 proposition)
   t3 (t/triple-t3 proposition)]
  (cond
  (= entry 0) (occurrence-key (occurrence-of event))
  (= entry 1) [t1]
  (= entry 2) [t1 t2]
  (= entry 3) [t1 t2 t3]
  (= entry 4) [t2]
  (= entry 5) [t2 t3]
  (= entry 6) [t3]
  :else [t3 t1])))

(defn- initial-bucket-width [event-count]
  (loop [width bucket-initial-slots]
  (if (>= (* bucket-slot-load width) event-count) width (recur (* 2 width)))))

(defn- ^Bucket projected-bucket! [events first-entry last-entry ^Boolean replace? initial-width]
  (loop [width initial-width
   keys empty-keys
   cells empty-event-cells
   slot-cells (fresh-position-cells initial-width)
   position 0
   entry first-entry]
  (if (>= position (count events)) (->Bucket width keys (close-event-cells cells) (close-position-cells slot-cells)) (if (> entry last-entry) (recur width keys cells slot-cells (inc position) first-entry) (let [event (checked-assertion (nth events position))
   key (entry-key entry event)
   slot (bucket-slot key width)
   positions (deref (nth slot-cells slot))
   at (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [candidate (nth positions offset)]
  (if (= (nth keys candidate) key) candidate (recur (inc offset))))))]
  (if (>= at 0) (do
  (if replace? (reset! (nth cells at) [event]) (swap! (nth cells at) conj event))
  (recur width keys cells slot-cells position (inc entry))) (let [appended (count keys)
   grown-keys (conj keys key)
   grown-cells (conj cells (atom [event]))]
  (do
  (swap! (nth slot-cells slot) conj appended)
  (if (> (count grown-keys) (* bucket-slot-load width)) (recur (* 2 width) grown-keys grown-cells (rehashed-position-cells! grown-keys (* 2 width)) position (inc entry)) (recur width grown-keys grown-cells slot-cells position (inc entry)))))))))))

(defn- ^Rotation projected! [^String space-id version events]
  (let [total (count events)]
  (->Rotation space-id version events (projected-bucket! events 0 0 true (initial-bucket-width total)) (projected-bucket! events 1 3 false (initial-bucket-width (* 3 total))) (projected-bucket! events 4 5 false (initial-bucket-width (* 2 total))) (projected-bucket! events 6 7 false (initial-bucket-width (* 2 total))) no-pending)))

(defn ^Rotation project! [ctx]
  (projected! (store/space-id ctx) (store/current-sequence ctx) (store/live-occurrences ctx)))

(defn- pending-with-frame [pending ^String space-id frame]
  (let [coordinate (t/transaction-coordinate space-id (t/transactionframe-sequence frame))
   operations (t/transactionframe-operations frame)]
  (loop [built pending
   ordinal 0]
  (if (>= ordinal (count operations)) built (let [operation (nth operations ordinal)
   proposition (t/commitoperation-proposition operation)]
  (recur (conj built (if (= t/assert-action (t/commitoperation-action operation)) (->PendingOp t/assert-action proposition (t/assertion-occurrence (t/occurrence-coordinate coordinate ordinal) proposition)) (->PendingOp t/retract-action proposition proposition))) (inc ordinal)))))))

(defn ^Rotation staged [^Rotation rotation ^String space-id sequence operations]
  (assoc rotation :pending (pending-with-frame (rotation-pending rotation) space-id (t/->TransactionFrame sequence operations))))

(defn ^Rotation refresh! [^Rotation rotation ctx]
  (let [space (store/space-id ctx)
   target (store/current-sequence ctx)
   pinned (rotation-version rotation)]
  (if (not (= space (rotation-space-id rotation))) (throw (ex-info "fram: rotation belongs to a different space" {:type :rotation-space-mismatch})) (if (> pinned target) (throw (ex-info "fram: rotation is ahead of the store it projects" {:type :rotation-ahead-of-store})) (if (= pinned target) rotation (let [frames (store/transaction-frames-between (deref ctx) pinned target)
   pending (loop [built (rotation-pending rotation)
   position 0]
  (if (>= position (count frames)) built (recur (pending-with-frame built space (nth frames position)) (inc position))))
   advanced (assoc (assoc rotation :pending pending) :version target)]
  (if (> (count pending) pending-fold-cap) (projected! space target (all-occurrences advanced)) advanced)))))))

(defn ^Boolean pinned? [^Rotation rotation ctx]
  (and (= (store/space-id ctx) (rotation-space-id rotation)) (= (store/current-sequence ctx) (rotation-version rotation))))
