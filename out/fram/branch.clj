(ns fram.branch
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.util.zip CRC32]))

(def ^String ref-format "framref/v1")

(def ^String default-branch "default")

(def max-branch-name-length 64)

(def max-chain-length 64)

(defrecord SegmentRecord [sha256 start-sequence byte-count])

(defn segmentrecord-sha256 [r] (:sha256 r))

(defn segmentrecord-start-sequence [r] (:start-sequence r))

(defn segmentrecord-byte-count [r] (:byte-count r))

(defrecord RefDocument [space-id segments])

(defn refdocument-space-id [r] (:space-id r))

(defn refdocument-segments [r] (:segments r))

(defrecord ChainMember [start-sequence end-sequence byte-count continuation space-id torn])

(defn chainmember-start-sequence [r] (:start-sequence r))

(defn chainmember-end-sequence [r] (:end-sequence r))

(defn chainmember-byte-count [r] (:byte-count r))

(defn chainmember-continuation [r] (:continuation r))

(defn chainmember-space-id [r] (:space-id r))

(defn chainmember-torn [r] (:torn r))

(defrecord ForkPlan [document sealed fork-sequence])

(defn forkplan-document [r] (:document r))

(defn forkplan-sealed [r] (:sealed r))

(defn forkplan-fork-sequence [r] (:fork-sequence r))

(defn- fail [^String message code]
  (throw (ex-info message {:type code :fram/code code})))

(defn ^Boolean valid-segment-name? [^String value]
  (some? (re-matches #"[0-9a-f]{64}" value)))

(defn ^Boolean valid-branch-name? [^String value]
  (and (pos? (count value)) (<= (count value) max-branch-name-length) (some? (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" value)) (not (str/includes? value ".."))))

(defn ^String require-branch-name! [^String value]
  (if (valid-branch-name? value) value (fail (str "branch name is not a usable ref file name: " value) :invalid-branch-name)))

(defn ^String segments-directory [^String store-path]
  (str store-path ".segments"))

(defn ^String refs-directory [^String store-path]
  (str store-path ".refs"))

(defn ^String branches-directory [^String store-path]
  (str store-path ".branches"))

(defn ^String segment-path [^String store-path ^String sha256]
  (if (valid-segment-name? sha256) (str (segments-directory store-path) "/" sha256) (fail (str "segment name is not a SHA-256 hex digest: " sha256) :invalid-segment-name)))

(defn ^String ref-path [^String store-path ^String branch]
  (str (refs-directory store-path) "/" (require-branch-name! branch)))

(defn ^String branch-tail-path [^String store-path ^String branch]
  (if (= (require-branch-name! branch) default-branch) store-path (str (branches-directory store-path) "/" branch)))

(defn ^String snapshot-path [^String tail-path]
  (str tail-path ".snapshot"))

(defn- crc32-of [^String text]
  (let [digest (CRC32.)]
  (.update digest (.getBytes text StandardCharsets/UTF_8))
  (long (.getValue digest))))

(defn- ^String segment-line [^SegmentRecord segment]
  (str "segment " (segmentrecord-sha256 segment) " " (segmentrecord-start-sequence segment) " " (segmentrecord-byte-count segment) "\n"))

(defn ^String print-ref [^RefDocument document]
  (let [body (str ref-format "\n" "space " (refdocument-space-id document) "\n" (apply str (mapv (fn [segment] (segment-line segment)) (refdocument-segments document))))]
  (str body "crc " (format "%08x" (crc32-of body)) "\n")))

(defn- parse-count [^String value ^String label]
  (if (some? (re-matches #"(?:0|[1-9][0-9]{0,17})" value)) (Long/parseLong value) (fail (str "branch ref " label " is not a decimal count: " value) :invalid-branch-ref)))

(defn- ^SegmentRecord parse-segment-line [^String line known]
  (let [fields (vec (str/split line #" "))]
  (if (not= 4 (count fields)) (fail (str "branch ref segment line is malformed: " line) :invalid-branch-ref) (let [sha256 (nth fields 1)]
  (cond
  (not (valid-segment-name? sha256)) (fail (str "branch ref segment name is not a SHA-256 hex digest: " sha256) :invalid-branch-ref)
  (contains? known sha256) (fail (str "branch ref lists the same segment twice: " sha256) :invalid-branch-ref)
  :else (->SegmentRecord sha256 (parse-count (nth fields 2) "segment start sequence") (parse-count (nth fields 3) "segment byte count")))))))

(defn ^RefDocument parse-ref [^String text]
  (let [lines (vec (str/split-lines text))]
  (cond
  (< (count lines) 3) (fail "branch ref is missing its format, space, or CRC line" :invalid-branch-ref)
  (not= ref-format (nth lines 0)) (fail (str "branch ref format is unsupported: " (nth lines 0)) :unsupported-branch-ref-version)
  (not (str/starts-with? (nth lines 1) "space ")) (fail "branch ref does not name its SpaceId" :invalid-branch-ref)
  (not (str/starts-with? (nth lines (dec (count lines))) "crc ")) (fail "branch ref does not end with its CRC line" :invalid-branch-ref)
  :else (let [space-id (subs (nth lines 1) 6)
   body (apply str (mapv (fn [line] (str line "\n")) (subvec lines 0 (dec (count lines)))))
   stored (subs (nth lines (dec (count lines))) 4)]
  (cond
  (zero? (count space-id)) (fail "branch ref SpaceId must be nonempty" :invalid-branch-ref)
  (not= stored (format "%08x" (crc32-of body))) (fail "branch ref CRC does not match" :invalid-branch-ref)
  :else (let [segments (loop [index 2
   known #{}
   acc []]
  (if (>= index (dec (count lines))) acc (let [line (nth lines index)]
  (if (not (str/starts-with? line "segment ")) (fail (str "branch ref contains an unknown line: " line) :invalid-branch-ref) (let [segment (parse-segment-line line known)]
  (recur (inc index) (conj known (segmentrecord-sha256 segment)) (conj acc segment)))))))]
  (if (> (count segments) max-chain-length) (fail "branch ref chain exceeds the supported segment count" :invalid-branch-ref) (->RefDocument space-id segments))))))))

(defn ^RefDocument empty-ref [^String space-id]
  (->RefDocument space-id []))

(defn ^ForkPlan fork-plan [^RefDocument parent ^SegmentRecord sealed fork-sequence]
  (let [segments (refdocument-segments parent)]
  (cond
  (not (valid-segment-name? (segmentrecord-sha256 sealed))) (fail "sealed segment name is not a SHA-256 hex digest" :invalid-segment-name)
  (some (fn [segment] (= (segmentrecord-sha256 segment) (segmentrecord-sha256 sealed))) segments) (fail "sealed segment is already named by the parent chain" :segment-already-sealed)
  (>= (count segments) max-chain-length) (fail "branch chain exceeds the supported segment count" :chain-too-long)
  (neg? fork-sequence) (fail "fork sequence must not be negative" :invalid-fork-sequence)
  :else (->ForkPlan (->RefDocument (refdocument-space-id parent) (conj segments sealed)) sealed fork-sequence))))

(defn- member-fault [^RefDocument document index ^ChainMember member expected-next]
  (let [segment (nth (refdocument-segments document) index)]
  (cond
  (not= (refdocument-space-id document) (chainmember-space-id member)) "FRAMLOG segment belongs to a different SpaceId"
  (not= (segmentrecord-byte-count segment) (chainmember-byte-count member)) "FRAMLOG segment size does not match its branch ref record"
  (not= (segmentrecord-start-sequence segment) (chainmember-start-sequence member)) "FRAMLOG segment does not begin at its recorded transaction sequence"
  (chainmember-torn member) "FRAMLOG segment ends inside a transaction frame"
  (and (pos? index) (not (chainmember-continuation member))) "FRAMLOG chain segment after the base segment must carry the continuation flag"
  (and (zero? index) (chainmember-continuation member)) "FRAMLOG base chain segment must not carry the continuation flag"
  (and (pos? (chainmember-start-sequence member)) (not= expected-next (chainmember-start-sequence member))) "FRAMLOG chain segment does not continue the previous transaction sequence"
  :else nil)))

(defn- next-after [^ChainMember member expected-next]
  (if (pos? (chainmember-end-sequence member)) (inc (chainmember-end-sequence member)) expected-next))

(defn chain-fault [^RefDocument document members ^ChainMember tail]
  (if (not= (count (refdocument-segments document)) (count members)) "FRAMLOG branch ref does not name the segments that were read" (loop [index 0
   expected-next 1]
  (if (>= index (count members)) (let [continuation (chainmember-continuation tail)]
  (cond
  (not= (refdocument-space-id document) (chainmember-space-id tail)) "FRAMLOG tail belongs to a different SpaceId"
  (and (pos? (count members)) (not continuation)) "FRAMLOG branch tail must carry the continuation flag"
  (and (zero? (count members)) continuation) "FRAMLOG version or flags are unsupported"
  (and (pos? (chainmember-start-sequence tail)) (not= expected-next (chainmember-start-sequence tail))) "FRAMLOG branch tail does not continue the sealed chain"
  :else nil)) (let [member (nth members index)
   fault (member-fault document index member expected-next)]
  (if (some? fault) fault (recur (inc index) (next-after member expected-next))))))))
