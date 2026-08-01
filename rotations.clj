;; Covering slot rotations over live assertion occurrences.
;;
;; FRI2 owns the persisted, source-bound Term/Triple tables and slot indexes.
;; This namespace is a disposable in-memory projection rebuilt from those
;; occurrence triples. It never assigns another identity or owns log history.
(ns rotations
  (:require [fram.kernel :as kernel]
            [fram.types :as t]
            [fri :as fri]))

(defrecord RotationSet [image index])
(defrecord RotationSummary
  [space-id source-fingerprint source-position occurrences])

(def empty-index
  {:events []
   :event-set #{}
   :slot0 {} :slot1 {} :slot2 {}
   :slot01 {} :slot12 {} :slot02 {}
   :novelty []
   :watermark -1})

(defn- assertion! [event]
  (if (kernel/assertion-occurrence? event)
    event
    (throw (ex-info "rotations: expected an assertion occurrence Triple"
                    {:type :invalid-rotation-occurrence}))))

(defn- proposition [event] (kernel/proposition-of (assertion! event)))

(defn- bucket-add [buckets key event]
  (assoc buckets key (conj (get buckets key []) event)))

(defn- remove-event [events target]
  (vec (remove #(= target %) events)))

(defn- bucket-del [buckets key event]
  (let [remaining (remove-event (get buckets key []) event)]
    (if (empty? remaining)
      (dissoc buckets key)
      (assoc buckets key remaining))))

(defn add
  "Add one exact assertion occurrence to all six covering slot buckets."
  [index event]
  (assertion! event)
  (if (contains? (:event-set index) event)
    index
    (let [triple (proposition event)
          slot0 (t/triple-slot0 triple)
          slot1 (t/triple-slot1 triple)
          slot2 (t/triple-slot2 triple)]
      (-> index
          (update :events conj event)
          (update :event-set conj event)
          (update :slot0 bucket-add slot0 event)
          (update :slot1 bucket-add slot1 event)
          (update :slot2 bucket-add slot2 event)
          (update :slot01 bucket-add [slot0 slot1] event)
          (update :slot12 bucket-add [slot1 slot2] event)
          (update :slot02 bucket-add [slot0 slot2] event)))))

(defn del
  "Remove one exact occurrence. Equal propositions at other coordinates remain."
  [index event]
  (assertion! event)
  (if-not (contains? (:event-set index) event)
    index
    (let [triple (proposition event)
          slot0 (t/triple-slot0 triple)
          slot1 (t/triple-slot1 triple)
          slot2 (t/triple-slot2 triple)]
      (-> index
          (update :events remove-event event)
          (update :event-set disj event)
          (update :slot0 bucket-del slot0 event)
          (update :slot1 bucket-del slot1 event)
          (update :slot2 bucket-del slot2 event)
          (update :slot01 bucket-del [slot0 slot1] event)
          (update :slot12 bucket-del [slot1 slot2] event)
          (update :slot02 bucket-del [slot0 slot2] event)))))

(defn build
  "Build a covering index from live assertion occurrence Triples."
  [events]
  (reduce add empty-index events))

(defn build-from-fri [image] (build (fri/live-occurrences image)))
(defn build-from-fri-as-of [image sequence]
  (build (fri/live-occurrences-as-of image sequence)))

(defn occurrence-count [index] (count (:events index)))

;; Every bound subset of slot0/slot1/slot2 has one exact bucket. Results are
;; occurrence events, so equal propositions at distinct coordinates survive.
(defn matching [index [slot0 slot1 slot2]]
  (cond
    (and (some? slot0) (and (some? slot1) (some? slot2)))
    (filterv #(= (t/triple slot0 slot1 slot2) (proposition %)) (:events index))

    (and (some? slot0) (some? slot1)) (get (:slot01 index) [slot0 slot1] [])
    (and (some? slot1) (some? slot2)) (get (:slot12 index) [slot1 slot2] [])
    (and (some? slot0) (some? slot2)) (get (:slot02 index) [slot0 slot2] [])
    (some? slot0) (get (:slot0 index) slot0 [])
    (some? slot1) (get (:slot1 index) slot1 [])
    (some? slot2) (get (:slot2 index) slot2 [])
    :else (:events index)))

(defn matching-propositions [index pattern]
  (mapv proposition (matching index pattern)))

(defn note-add [index event version]
  (assertion! event)
  (-> index
      (update :novelty conj [:add event version])
      (assoc :watermark (max (long (:watermark index)) (long version)))))

(defn note-del [index event version]
  (assertion! event)
  (-> index
      (update :novelty conj [:del event version])
      (assoc :watermark (max (long (:watermark index)) (long version)))))

(defn novelty-count [index] (count (:novelty index)))
(defn drain-novelty [index] (assoc index :novelty []))

;; Rotation persistence is deliberately the same FRI2 artifact. This prevents a
;; second dictionary, watermark, or cache identity from becoming authoritative.
(defn index-path [canonical-log] (str canonical-log ".fri2"))

(defn write-set! [path dump source]
  (fri/write-fri! dump path source))

(defn open-set! [path source]
  (let [image (fri/open-fri! path source)]
    (->RotationSet image (build-from-fri image))))

(defn close-set! [opened]
  (fri/close-fri! (:image opened)))

(defn set-summary [opened]
  (->RotationSummary
   (fri/space-id (:image opened))
   (fri/source-fingerprint (:image opened))
   (fri/source-position (:image opened))
   (occurrence-count (:index opened))))
