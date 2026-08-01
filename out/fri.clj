(ns fri
  (:require [fri-port :as fp]))

(def ^String MAGIC fp/MAGIC)

(def FMT fp/FMT)

(defn source-binding [^String space-id ^String fingerprint valid-bytes]
  (fp/source-binding space-id fingerprint valid-bytes))

(defn write-fri! [dump ^String path source]
  (fp/write-fri! dump path source))

(defn open-fri! [^String path source]
  (fp/open-fri! path source))

(defn close-fri! [image]
  (fp/close-fri! image))

(defn restore-store! [image target]
  (fp/restore-store! image target))

(defn ^String space-id [image]
  (fp/space-id image))

(defn ^String source-fingerprint [image]
  (fp/source-fingerprint image))

(defn source-position [image]
  (fp/source-position image))

(defn transaction-count [image]
  (fp/transaction-count image))

(defn operation-count [image]
  (fp/operation-count image))

(defn semantic-history [image]
  (fp/semantic-history image))

(defn operation-occurrences [image]
  (fp/operation-occurrences image))

(defn live-occurrences [image]
  (fp/live-occurrences image))

(defn live-propositions [image]
  (fp/live-propositions image))

(defn by-slot0 [image term]
  (fp/by-slot0 image term))

(defn by-slot1 [image term]
  (fp/by-slot1 image term))

(defn by-slot2 [image term]
  (fp/by-slot2 image term))

(defn by-slot01 [image slot0 slot1]
  (fp/by-slot01 image slot0 slot1))

(defn by-slot12 [image slot1 slot2]
  (fp/by-slot12 image slot1 slot2))

(defn by-slot02 [image slot0 slot2]
  (fp/by-slot02 image slot0 slot2))

(defn live-occurrences-as-of [image sequence]
  (fp/live-occurrences-as-of image sequence))

(defn live-propositions-as-of [image sequence]
  (fp/live-propositions-as-of image sequence))
