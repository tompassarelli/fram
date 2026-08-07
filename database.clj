;; database.clj — authoritative TermStore v2 database.
;;
;; This file deliberately depends only on the recursive-Term kernel. Schema,
;; query, pull, and codegraph remain downstream projections; none may
;; restore the removed fact-object store beneath this boundary.
(ns database
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [framrpc :as framrpc]
            [fram.branch :as branch]
            [fram.kernel :as kernel]
            [fram.store :as term-store]
            [fram.types :as t]))

;; Fork renames a live log, so it takes the same lifetime lock the server does.
;; Callers reach this file from their own working directory, so the sibling is
;; located from this file rather than from the process's.
(load-file
 (.getPath (java.io.File.
            (.getParentFile (.getCanonicalFile (java.io.File. (str *file*))))
            "writer_authority.clj")))

(def ^:private ^"[B" triple-log-magic
  (.getBytes "FRAMLOG\u0000" java.nio.charset.StandardCharsets/UTF_8))
(def ^:private legacy-fri-magic
  (.getBytes "FRAMFRI1" java.nio.charset.StandardCharsets/UTF_8))
(def ^:private legacy-v2-edn-prefix
  (.getBytes "{:k " java.nio.charset.StandardCharsets/UTF_8))
(def ^:private triple-log-version 1)
(def ^:private triple-log-flags 0)
;; Header flag bit 0: every frame payload in this generation is
;; Deflate-compressed; the CRC still covers the stored (compressed) bytes.
(def ^:private deflate-flag 1)
;; Header flag bit 1: this generation continues a sealed segment chain and is
;; not a whole store on its own, so every single-file open must refuse it.
(def ^:private continuation-flag 2)
(def ^:private triple-log-manifest-version
  "fram-triple-log-migration-manifest/v1")
(def ^:private max-term-depth 256)

(defn- fail! [code message data]
  (throw (ex-info message (assoc data :type code :fram/code code))))

(defn- migration-fail! [code message data]
  (fail! code message data))

(defn- require-u32! [n label]
  (when-not (and (integer? n) (<= 0 n 4294967295))
    (fail! :invalid-integer
           (str label " is outside unsigned 32-bit range")
           {:label label :value n}))
  (long n))

(defn- require-i64! [n label]
  (when-not (and (integer? n) (<= Long/MIN_VALUE n Long/MAX_VALUE))
    (fail! :invalid-integer
           (str label " is outside signed 64-bit range")
           {:label label :value n}))
  (long n))

(defn- write-u8! [^java.io.OutputStream out n]
  (.write out (int (bit-and 255 (long n)))))

(defn- write-u16-le! [^java.io.OutputStream out n]
  (let [v (long n)]
    (dotimes [i 2]
      (write-u8! out (unsigned-bit-shift-right v (* i 8))))))

(defn- write-u32-le! [^java.io.OutputStream out n]
  (let [v (require-u32! n "u32")]
    (dotimes [i 4]
      (write-u8! out (unsigned-bit-shift-right v (* i 8))))))

(defn- write-i64-le! [^java.io.OutputStream out n]
  (let [v (require-i64! n "i64")]
    (dotimes [i 8]
      (write-u8! out (unsigned-bit-shift-right v (* i 8))))))

(defn- strict-utf8-bytes [s label]
  (when-not (string? s)
    (fail! :invalid-text (str label " must be a String")
           {:label label :value s}))
  (try
    (let [encoder (doto (.newEncoder java.nio.charset.StandardCharsets/UTF_8)
                    (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                    (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))
          buffer (.encode encoder (java.nio.CharBuffer/wrap ^String s))
          bytes (byte-array (.remaining buffer))]
      (.get buffer bytes)
      bytes)
    (catch java.nio.charset.CharacterCodingException e
      (fail! :invalid-utf8 (str label " is not valid UTF-8 text")
             {:label label :cause (.getMessage e)}))))

(defn- strict-utf8-string [^bytes bytes label]
  (try
    (let [decoder (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                    (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                    (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
      (str (.decode decoder (java.nio.ByteBuffer/wrap bytes))))
    (catch java.nio.charset.CharacterCodingException e
      (fail! :invalid-utf8 (str label " is not valid UTF-8")
             {:label label :cause (.getMessage e)}))))

(defn- write-triple! [^java.io.OutputStream out value depth]
  (when-not (t/triple? value)
    (fail! :invalid-triple "recursive encoder requires a Triple" {:value value}))
  (when-not (zero? depth)
    (fail! :invalid-term-depth "FRAMLOG Term encoding must begin at depth zero"
           {:depth depth}))
  (try
    (framrpc/write-term-codec-v1!
     out value Integer/MAX_VALUE Integer/MAX_VALUE max-term-depth)
    (catch clojure.lang.ExceptionInfo e
      (let [code (:fram/code (ex-data e))]
        (case code
          :term-depth-exceeded (throw e)
          :term-codec-invalid-utf8
          (fail! :invalid-utf8 "FRAMLOG Term contains invalid UTF-8" {:cause code})
          :term-codec-invalid-keyword
          (fail! :invalid-keyword "FRAMLOG Keyword atom is empty" {:cause code})
          :term-codec-integer-range
          (fail! :invalid-integer "FRAMLOG Term integer is out of range" {:cause code})
          :term-codec-unsupported-term
          (fail! :unsupported-term "FRAMLOG encountered a value outside Term"
                 {:value value :class (some-> value class str)})
          (throw e))))))

(defn- ensure-remaining! [^java.nio.ByteBuffer buffer n context]
  (when (< (.remaining buffer) n)
    (fail! :corrupt-triple-log "FRAMLOG payload ended inside a value"
           {:context context :needed n :remaining (.remaining buffer)})))

(defn- read-u32 [^java.nio.ByteBuffer buffer context]
  (ensure-remaining! buffer 4 context)
  (Integer/toUnsignedLong (.getInt buffer)))

(defn- read-term [^java.nio.ByteBuffer buffer depth]
  (when-not (zero? depth)
    (fail! :corrupt-triple-log "FRAMLOG Term decoding must begin at depth zero"
           {:depth depth}))
  (try
    (t/termcodecdecoded-value
     (framrpc/decode-term-codec-v1!
      buffer Integer/MAX_VALUE Integer/MAX_VALUE max-term-depth))
    (catch clojure.lang.ExceptionInfo e
      (if (= :term-depth-exceeded (:fram/code (ex-data e)))
        (throw e)
        (fail! :corrupt-triple-log "FRAMLOG contains a malformed Term"
               {:cause (:fram/code (ex-data e))})))))

(defn- wire-action [action]
  (case action :assert 1 :retract 2
        (fail! :invalid-commit-operation "operation action must be :assert or :retract"
               {:action action})))

(defn- store-action [action]
  (case action
    1 :assert
    2 :retract
    (fail! :corrupt-triple-log "FRAMLOG contains an unknown operation action"
           {:action action})))

(defn- operation-map [ordinal operation]
  {:ordinal ordinal
   :action (wire-action (t/commitoperation-action operation))
   :triple (t/commitoperation-proposition operation)})

(defn- deflate-bytes ^bytes [^bytes bytes]
  (let [out (java.io.ByteArrayOutputStream. (alength bytes))]
    (with-open [gz (java.util.zip.GZIPOutputStream. out)]
      (.write gz bytes))
    (.toByteArray out)))

(defn- inflate-bytes ^bytes [^bytes bytes path offset]
  (try
    (let [out (java.io.ByteArrayOutputStream. (* 4 (alength bytes)))
          buffer (byte-array 8192)]
      (with-open [gz (java.util.zip.GZIPInputStream.
                      (java.io.ByteArrayInputStream. bytes))]
        (loop []
          (let [n (.read gz buffer)]
            (when (pos? n)
              (.write out buffer 0 n)
              (recur)))))
      (.toByteArray out))
    (catch java.io.IOException error
      (fail! :corrupt-triple-log "FRAMLOG deflate frame is invalid"
             {:path path :offset offset :cause (.getMessage error)}))))

(defn- write-transaction-frame! [^java.io.OutputStream out tx deflate?]
  (let [payload (java.io.ByteArrayOutputStream.)
        operations (:operations tx)]
    (write-i64-le! payload (:tx-seq tx))
    (write-u32-le! payload (count operations))
    (doseq [[expected operation] (map-indexed vector operations)]
      (when-not (= expected (:ordinal operation))
        (fail! :noncontiguous-ordinal
               "transaction operation ordinals must be contiguous"
               {:tx-seq (:tx-seq tx) :expected expected
                :actual (:ordinal operation)}))
      (write-u32-le! payload (:ordinal operation))
      (write-u8! payload (:action operation))
      (write-triple! payload (:triple operation) 0))
    (let [bytes (let [^bytes raw (.toByteArray payload)]
                  (if deflate? (deflate-bytes raw) raw))
          crc (doto (java.util.zip.CRC32.) (.update ^bytes bytes))]
      (write-u32-le! out (alength ^bytes bytes))
      (.write out ^bytes bytes)
      (write-u32-le! out (.getValue crc)))))

(defn- decode-transaction-payload [^bytes payload frame-offset]
  (let [buffer (doto (java.nio.ByteBuffer/wrap payload)
                 (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (ensure-remaining! buffer 12 "transaction header")
    (let [sequence (.getLong buffer)
          operation-count (read-u32 buffer "operation count")]
      (when (neg? sequence)
        (fail! :corrupt-triple-log "FRAMLOG transaction sequence is negative"
               {:offset frame-offset :sequence sequence}))
      (when (or (zero? operation-count) (> operation-count Integer/MAX_VALUE))
        (fail! :corrupt-triple-log "FRAMLOG transaction operation count is invalid"
               {:offset frame-offset :operation-count operation-count}))
      (let [operations
            (mapv
             (fn [expected]
               (let [ordinal (read-u32 buffer "operation ordinal")]
                 (when-not (= expected ordinal)
                   (fail! :noncontiguous-ordinal
                          "FRAMLOG operation ordinals are not contiguous"
                          {:offset frame-offset :sequence sequence
                           :expected expected :actual ordinal}))
                 (ensure-remaining! buffer 1 "operation action")
                 (let [action (bit-and 255 (int (.get buffer)))
                       proposition (read-term buffer 0)]
                   (when-not (t/triple? proposition)
                     (fail! :corrupt-triple-log
                            "FRAMLOG operation proposition is not a Triple"
                            {:offset frame-offset :sequence sequence
                             :ordinal ordinal}))
                   {:ordinal ordinal :action action
                    :store-action (store-action action)
                    :triple proposition})))
             (range (int operation-count)))]
        (when-not (zero? (.remaining buffer))
          (fail! :corrupt-triple-log "FRAMLOG transaction has trailing payload bytes"
                 {:offset frame-offset :sequence sequence
                  :remaining (.remaining buffer)}))
        {:tx-seq sequence :operations operations}))))

(defn- bytes-prefix? [^bytes bytes ^bytes prefix]
  (and (>= (alength bytes) (alength prefix))
       (java.util.Arrays/equals
        prefix (java.util.Arrays/copyOfRange bytes 0 (alength prefix)))))

(defn- parse-triple-log-bytes
  ([^bytes bytes path] (parse-triple-log-bytes bytes path false))
  ([^bytes bytes path allow-continuation?]
  (when (or (bytes-prefix? bytes legacy-fri-magic)
            (bytes-prefix? bytes legacy-v2-edn-prefix))
    (fail! :migration-v2-cache-not-source
           "FRI cache is not authoritative; migrate its canonical flat log with bin/fram-migrate-triple-log"
           {:path path :migrator "bin/fram-migrate-triple-log"}))
  (when-not (bytes-prefix? bytes triple-log-magic)
    (fail! :migration-required
           "legacy log requires bin/fram-migrate-triple-log before runtime boot"
           {:path path :migrator "bin/fram-migrate-triple-log"}))
  (let [^java.nio.ByteBuffer buffer
        (doto (java.nio.ByteBuffer/wrap bytes)
          (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (.position buffer (alength triple-log-magic))
    (try
      (ensure-remaining! buffer 8 "FRAMLOG header")
      (let [version (bit-and 65535 (int (.getShort buffer)))
            flags (bit-and 65535 (int (.getShort buffer)))
            space-length (read-u32 buffer "SpaceId length")]
        (when-not (and (= triple-log-version version)
                       (contains? (if allow-continuation?
                                    #{triple-log-flags deflate-flag
                                      continuation-flag
                                      (bit-or deflate-flag continuation-flag)}
                                    #{triple-log-flags deflate-flag})
                                  flags))
          (fail! :unsupported-log-version
                 "FRAMLOG version or flags are unsupported"
                 {:path path :version version :flags flags}))
        (when (or (zero? space-length) (> space-length Integer/MAX_VALUE))
          (fail! :corrupt-triple-log "FRAMLOG SpaceId length is invalid"
                 {:path path :length space-length}))
        (ensure-remaining! buffer (int space-length) "SpaceId")
        (let [space-bytes (byte-array (int space-length))
              _ (.get buffer space-bytes)
              space-id (strict-utf8-string space-bytes "SpaceId")
              deflate? (pos? (bit-and deflate-flag flags))
              continuation? (pos? (bit-and continuation-flag flags))
              header-bytes (.position buffer)]
          (loop [frames [] valid-bytes header-bytes prefix-ends {}]
            (let [offset (.position buffer)
                  remaining (.remaining buffer)]
              (cond
                (zero? remaining)
                {:space-id space-id :deflate? deflate? :frames frames
                 :continuation? continuation?
                 :valid-bytes valid-bytes
                 :header-bytes header-bytes :prefix-ends prefix-ends
                 :torn-tail nil}

                (< remaining 4)
                {:space-id space-id :deflate? deflate? :frames frames
                 :continuation? continuation?
                 :valid-bytes valid-bytes
                 :header-bytes header-bytes :prefix-ends prefix-ends
                 :torn-tail {:offset offset :bytes remaining
                             :reason :torn-frame-length}}

                :else
                (let [payload-length (read-u32 buffer "frame payload length")]
                  (when (> payload-length Integer/MAX_VALUE)
                    (fail! :corrupt-triple-log "FRAMLOG frame exceeds JVM bounds"
                           {:path path :offset offset :length payload-length}))
                  (if (< (.remaining buffer) (+ payload-length 4))
                    {:space-id space-id :frames frames
                     :continuation? continuation?
                     :valid-bytes valid-bytes
                     :header-bytes header-bytes :prefix-ends prefix-ends
                     :torn-tail {:offset offset :bytes (- (alength bytes) offset)
                                 :reason :torn-transaction-frame}}
                    (let [payload (byte-array (int payload-length))
                          _ (.get buffer payload)
                          stored-crc (read-u32 buffer "frame CRC")
                          actual-crc (.getValue
                                      (doto (java.util.zip.CRC32.)
                                        (.update payload)))]
                      (when-not (= stored-crc actual-crc)
                        (fail! :corrupt-triple-log "FRAMLOG frame CRC does not match"
                               {:path path :offset offset
                                :stored stored-crc :actual actual-crc}))
                      (let [frame (decode-transaction-payload
                                   (if deflate?
                                     (inflate-bytes payload path offset)
                                     payload)
                                   offset)
                            end (.position buffer)]
                        (recur (conj frames frame) end
                               (assoc prefix-ends (:tx-seq frame) end)))))))))))
      (catch Throwable error
        (if (instance? clojure.lang.ExceptionInfo error)
          (throw error)
          (fail! :corrupt-triple-log "FRAMLOG header is truncated"
                 {:path path :cause (.getMessage error)})))))))

(defn read-triple-log!
  "Read and validate a FRAMLOG generation without accepting any legacy shape."
  ([path] (read-triple-log! path false))
  ([path allow-continuation?]
   (let [file (.getCanonicalFile (java.io.File. (str path)))]
     (when-not (.isFile file)
       (fail! :triple-log-missing "FRAMLOG source is missing"
              {:path (.getPath file)}))
     (parse-triple-log-bytes
      (java.nio.file.Files/readAllBytes (.toPath file)) (.getPath file)
      allow-continuation?))))

(defn require-triple-log-header!
  "Return the immutable SpaceId of a validated FRAMLOG generation."
  [path]
  (:space-id (read-triple-log! path)))

(defn triple-log-prefix-source!
  "Bind an inclusive transaction-sequence prefix to its exact canonical bytes."
  [path upper-inclusive]
  (let [file (.getCanonicalFile (java.io.File. (str path)))
        parsed (read-triple-log! (.getPath file))
        sequence (reduce (fn [known frame]
                           (let [candidate (:tx-seq frame)]
                             (if (<= candidate upper-inclusive) candidate known)))
                         nil (:frames parsed))
        valid-bytes (if (some? sequence)
                      (get (:prefix-ends parsed) sequence)
                      (:header-bytes parsed))
        bytes (java.nio.file.Files/readAllBytes (.toPath file))
        ^bytes prefix (java.util.Arrays/copyOfRange bytes 0 (int valid-bytes))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256") prefix)
        fingerprint (apply str (map #(format "%02x" (bit-and % 255)) digest))]
    {:space-id (:space-id parsed)
     :sequence (or sequence 0)
     :valid-bytes valid-bytes
     :fingerprint fingerprint}))

(defn- write-header!
  ([out space-id] (write-header! out space-id triple-log-flags))
  ([^java.io.OutputStream out space-id flags]
   (let [space-bytes (strict-utf8-bytes space-id "SpaceId")]
     (when (zero? (alength ^bytes space-bytes))
       (fail! :space-id-required "SpaceId must be nonempty" {}))
     (.write out triple-log-magic)
     (write-u16-le! out triple-log-version)
     (write-u16-le! out flags)
     (write-u32-le! out (alength ^bytes space-bytes))
     (.write out ^bytes space-bytes))))

(defn create-triple-log!
  "Atomically create a header-only FRAMLOG generation for SPACE-ID.
   {:deflate? true} creates a generation whose frames are Deflate-compressed."
  ([path space-id] (create-triple-log! path space-id {}))
  ([path space-id {:keys [deflate? continuation?]
                   :or {deflate? false continuation? false}}]
  (let [target (.getCanonicalFile (java.io.File. (str path)))
        parent (.getParentFile target)]
    (when-not (and (.isAbsolute target) parent (.isDirectory parent))
      (fail! :triple-log-target-invalid
             "FRAMLOG target must be an absolute path in an existing directory"
             {:path (.getPath target)}))
    (when (.exists target)
      (fail! :triple-log-exists "FRAMLOG target already exists"
             {:path (.getPath target)}))
    (let [tmp (java.nio.file.Files/createTempFile
               (.toPath parent) ".framlog-header-" ".tmp"
               (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (with-open [file-out (java.io.FileOutputStream. (.toFile tmp))
                    out (java.io.BufferedOutputStream. file-out)]
          (write-header! out space-id
                         (bit-or (if deflate? deflate-flag triple-log-flags)
                                 (if continuation?
                                   continuation-flag triple-log-flags)))
          (.flush out)
          (.force (.getChannel file-out) true))
        (java.nio.file.Files/move
         tmp (.toPath target)
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
        (.getPath target)
        (finally (java.nio.file.Files/deleteIfExists tmp)))))))

(defn- append-frame-durable! [path frame deflate?]
  (with-open [file-out (java.io.FileOutputStream. (str path) true)
              out (java.io.BufferedOutputStream. file-out)]
    (write-transaction-frame! out frame deflate?)
    (.flush out)
    (.force (.getChannel file-out) true)))

(defn- append-frame-cohort-durable! [path frames deflate?]
  (with-open [file-out (java.io.FileOutputStream. (str path) true)
              out (java.io.BufferedOutputStream. file-out)]
    (doseq [frame frames]
      (write-transaction-frame! out frame deflate?))
    (.flush out)
    (.force (.getChannel file-out) true)))

(def ^:dynamic *deferred-frames* nil)

(defn- frame->store-frame [frame]
  (term-store/transaction-frame
   (:tx-seq frame)
   (mapv (fn [{:keys [store-action triple]}]
           (case store-action
             :assert (term-store/assert-operation triple)
             :retract (term-store/retract-operation triple)))
         (:operations frame))))

(defn- replay-frames! [context frames]
  (doseq [frame frames]
    (term-store/replay-transaction! context (frame->store-frame frame)))
  context)

(defn- truncate-log! [path length]
  (with-open [file (java.io.RandomAccessFile. (str path) "rw")]
    (.setLength file length)
    (.force (.getChannel file) true)))

(def ^:private fork-marker-suffix ".fork")
(def ^:private fork-pending-suffix ".fork-new")

(defn- fork-marker-path [store] (str store fork-marker-suffix))
(defn- fork-pending-path [path] (str path fork-pending-suffix))

(defn- read-fork-marker [store]
  (let [file (java.io.File. (str (fork-marker-path store)))]
    (when (.isFile file)
      (branch/parse-fork-marker
       (strict-utf8-string (java.nio.file.Files/readAllBytes (.toPath file))
                           "fork marker")))))

(defn- require-no-pending-fork! [store]
  (when (.exists (java.io.File. (str (fork-marker-path store))))
    (fail! :fork-incomplete
           "a fork of this store was interrupted and has not been completed"
           {:path (str store) :marker (fork-marker-path store)})))

(defn open-database!
  "Open a FRAMLOG-backed TermStore. A passive reader reports a torn trailing
   frame and refuses later writes. An authority-holding caller may pass
   {:repair-torn? true}; only the last incomplete frame is truncated."
  ([path] (open-database! path nil {}))
  ([path expected-space] (open-database! path expected-space {}))
  ([path expected-space {:keys [repair-torn?] :or {repair-torn? false}}]
   (let [canonical (.getPath (.getCanonicalFile (java.io.File. (str path))))
         _ (require-no-pending-fork! canonical)
         parsed (read-triple-log! canonical)
         space-id (:space-id parsed)]
     (when (and expected-space (not= expected-space space-id))
       (fail! :space-mismatch "FRAMLOG belongs to a different SpaceId"
              {:expected expected-space :actual space-id :path canonical}))
     (let [context (term-store/new-term-store space-id)]
       (replay-frames! context (:frames parsed))
       (when (and (:torn-tail parsed) repair-torn?)
         (truncate-log! canonical (:valid-bytes parsed)))
       {:term-store context
        :space-id space-id
        :deflate? (:deflate? parsed)
        :log canonical
        :lock (Object.)
        :mutation-state (atom {:status :ready})
        :torn-tail (when-not repair-torn? (:torn-tail parsed))
        :recovered-tail (when repair-torn? (:torn-tail parsed))}))))

(defn- sha256-hex [^bytes content]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           content))))

(defn- ensure-directory! [path]
  (java.nio.file.Files/createDirectories
   (.toPath (java.io.File. (str path)))
   (make-array java.nio.file.attribute.FileAttribute 0))
  (str path))

(defn- move-atomically! [source target]
  (java.nio.file.Files/move
   (.toPath (java.io.File. (str source))) (.toPath (java.io.File. (str target)))
   (into-array java.nio.file.CopyOption
               [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
  (str target))

(defn- write-text-durable! [path text]
  (let [^bytes content (strict-utf8-bytes text "branch ref")
        temporary (str path ".tmp")]
    (with-open [file-out (java.io.FileOutputStream. (str temporary))
                out (java.io.BufferedOutputStream. file-out)]
      (.write out content)
      (.flush out)
      (.force (.getChannel file-out) true))
    (move-atomically! temporary path)))

(defn read-branch-ref
  "Read a branch ref, or nil when the branch has no sealed chain on disk."
  [store-path branch]
  (let [file (java.io.File. (str (branch/ref-path (str store-path) branch)))]
    (when (.isFile file)
      (branch/parse-ref
       (strict-utf8-string (java.nio.file.Files/readAllBytes (.toPath file))
                           "branch ref")))))

(defn- chain-member [parsed byte-count]
  (branch/->ChainMember
   (long (or (:tx-seq (first (:frames parsed))) 0))
   (long (or (:tx-seq (last (:frames parsed))) 0))
   (long byte-count)
   (boolean (:continuation? parsed))
   (:space-id parsed)
   (some? (:torn-tail parsed))))

(defn- read-chain-member! [path]
  (let [parsed (read-triple-log! path true)]
    [parsed (chain-member parsed (.length (java.io.File. (str path))))]))

(defn open-branch!
  "Open one branch of a store: fold its sealed segment chain in ref order, then
   its tail. A store with no ref for the default branch boots exactly as an
   unforked FRAMLOG does."
  ([store-path branch] (open-branch! store-path branch nil {}))
  ([store-path branch expected-space]
   (open-branch! store-path branch expected-space {}))
  ([store-path branch expected-space
    {:keys [repair-torn?] :or {repair-torn? false}}]
   (let [store (.getPath (.getCanonicalFile (java.io.File. (str store-path))))
         _ (require-no-pending-fork! store)
         document (read-branch-ref store branch)]
     (cond
       (and (nil? document) (= branch branch/default-branch))
       (open-database! store expected-space {:repair-torn? repair-torn?})

       (nil? document)
       (fail! :branch-missing "branch has no ref"
              {:branch branch :path (branch/ref-path store branch)})

       :else
       (let [tail-path (branch/branch-tail-path store branch)
             space-id (branch/refdocument-space-id document)
             sealed (mapv (fn [segment]
                            (read-chain-member!
                             (branch/segment-path
                              store (branch/segmentrecord-sha256 segment))))
                          (branch/refdocument-segments document))
             [tail-parsed tail-member] (read-chain-member! tail-path)
             fault (branch/chain-fault document (mapv second sealed) tail-member)]
         (when (and expected-space (not= expected-space space-id))
           (fail! :space-mismatch "FRAMLOG belongs to a different SpaceId"
                  {:expected expected-space :actual space-id :branch branch}))
         (when fault
           (fail! :invalid-branch-chain fault
                  {:branch branch :path tail-path
                   :ref (branch/ref-path store branch)}))
         (let [context (term-store/new-term-store space-id)]
           (doseq [[parsed _] sealed]
             (replay-frames! context (:frames parsed)))
           (replay-frames! context (:frames tail-parsed))
           (when (and (:torn-tail tail-parsed) repair-torn?)
             (truncate-log! tail-path (:valid-bytes tail-parsed)))
           {:term-store context
            :space-id space-id
            :deflate? (:deflate? tail-parsed)
            :log tail-path
            :branch branch
            :segments (mapv branch/segmentrecord-sha256
                            (branch/refdocument-segments document))
            :lock (Object.)
            :mutation-state (atom {:status :ready})
            :torn-tail (when-not repair-torn? (:torn-tail tail-parsed))
            :recovered-tail (when repair-torn? (:torn-tail tail-parsed))}))))))

(defn- delete-file! [path]
  (java.nio.file.Files/deleteIfExists (.toPath (java.io.File. (str path)))))

(defn- install-pending! [pending target]
  (when (.exists (java.io.File. (str pending)))
    (move-atomically! pending target)))

;; Every file a fork installs is prepared before its marker is written, so a
;; fork interrupted at any point finishes by replaying the renames below in
;; this order; each one is skipped when its prepared file is already consumed.
(defn- complete-fork! [store marker]
  (let [parent (branch/forkmarker-parent marker)
        child (branch/forkmarker-child marker)
        parent-tail (branch/branch-tail-path store parent)
        child-tail (branch/branch-tail-path store child)
        parent-ref (branch/ref-path store parent)
        child-ref (branch/ref-path store child)
        sealed (branch/segment-path store (branch/forkmarker-segment marker))]
    (when-not (.exists (java.io.File. (str sealed)))
      (move-atomically! parent-tail sealed))
    (install-pending! (fork-pending-path parent-tail) parent-tail)
    (install-pending! (fork-pending-path parent-ref) parent-ref)
    (install-pending! (fork-pending-path child-ref) child-ref)
    (install-pending! (fork-pending-path child-tail) child-tail)
    ;; The parent's image is derived state whose watermark no longer names the
    ;; tail it was built beside.
    (delete-file! (branch/snapshot-path parent-tail))
    (delete-file! (fork-marker-path store))
    nil))

(defn- acquire-fork-authority! [paths]
  (reduce
   (fn [held path]
     (if-let [handle (writer-authority/try-acquire! path)]
       (conj held handle)
       (do
         (doseq [previous held] (writer-authority/release! previous))
         (fail! :writer-authority-held
                "a writer holds this store; fork runs offline only"
                {:path path :lock (writer-authority/authority-path path)}))))
   [] paths))

(defn fork-store!
  "Seal the parent branch's tail into the shared segment chain and give parent
   and child fresh continuation tails that both begin at the next sequence.
   Offline: fork holds writer authority over the store and both tails for its
   whole run, and refuses rather than rename a log a writer still holds."
  ([store-path child-branch]
   (fork-store! store-path branch/default-branch child-branch))
  ([store-path parent-branch child-branch]
   (let [store (.getPath (.getCanonicalFile (java.io.File. (str store-path))))
         parent (branch/require-branch-name! parent-branch)
         child (branch/require-branch-name! child-branch)
         parent-tail (branch/branch-tail-path store parent)
         child-tail (branch/branch-tail-path store child)]
     (when (= parent child)
       (fail! :invalid-branch-name "fork requires two different branch names"
              {:branch parent}))
     (let [held (acquire-fork-authority!
                 (distinct [store parent-tail child-tail]))]
       (try
         (when-let [pending (read-fork-marker store)]
           (complete-fork! store pending))
         (doseq [path [child-tail (branch/ref-path store child)]]
           (when (.exists (java.io.File. (str path)))
             (fail! :branch-exists "fork child branch already exists"
                    {:branch child :path path})))
         (let [document (read-branch-ref store parent)]
           (when (and (nil? document) (not= parent branch/default-branch))
             (fail! :branch-missing "branch has no ref"
                    {:branch parent :path (branch/ref-path store parent)}))
           (let [parsed (read-triple-log! parent-tail true)
                 space-id (:space-id parsed)
                 frames (:frames parsed)
                 base (or document (branch/empty-ref space-id))
                 chained? (pos? (count (branch/refdocument-segments base)))]
             (when (:torn-tail parsed)
               (fail! :torn-tail-repair-required
                      "fork requires a parent tail with no torn trailing frame"
                      {:branch parent :path parent-tail}))
             (when (not= space-id (branch/refdocument-space-id base))
               (fail! :space-mismatch "FRAMLOG belongs to a different SpaceId"
                      {:expected (branch/refdocument-space-id base)
                       :actual space-id :branch parent}))
             (when (not= chained? (boolean (:continuation? parsed)))
               (fail! :invalid-branch-chain
                      (if chained?
                        "FRAMLOG branch tail must carry the continuation flag"
                        "FRAMLOG base chain segment must not carry the continuation flag")
                      {:branch parent :path parent-tail}))
             (let [^bytes content (java.nio.file.Files/readAllBytes
                                   (.toPath (java.io.File. (str parent-tail))))
                   record (branch/->SegmentRecord
                           (sha256-hex content)
                           (long (or (:tx-seq (first frames)) 0))
                           (long (or (:tx-seq (last frames)) 0))
                           (long (alength content)))
                   ;; The tail's own last frame, else the chain's recorded end:
                   ;; a fork reads no sealed segment to learn where it forked.
                   plan (branch/fork-plan
                         base record
                         (long (or (:tx-seq (last frames))
                                   (branch/chain-end-sequence base))))
                   chain (branch/forkplan-document plan)
                   text (branch/print-ref chain)
                   marker (branch/->ForkMarker
                           parent child (branch/segmentrecord-sha256 record))
                   parent-ref (branch/ref-path store parent)
                   child-ref (branch/ref-path store child)]
               (ensure-directory! (branch/segments-directory store))
               (ensure-directory! (branch/refs-directory store))
               (when (not= child-tail store)
                 (ensure-directory! (branch/branches-directory store)))
               (doseq [path [parent-tail child-tail parent-ref child-ref]]
                 (delete-file! (fork-pending-path path)))
               (doseq [tail [parent-tail child-tail]]
                 (create-triple-log! (fork-pending-path tail) space-id
                                     {:deflate? (:deflate? parsed)
                                      :continuation? true}))
               (doseq [ref [parent-ref child-ref]]
                 (write-text-durable! (fork-pending-path ref) text))
               (write-text-durable! (fork-marker-path store)
                                    (branch/print-fork-marker marker))
               (complete-fork! store marker)
               {:space-id space-id
                :fork-sequence (branch/forkplan-fork-sequence plan)
                :segment (branch/segmentrecord-sha256 record)
                :chain (mapv branch/segmentrecord-sha256
                             (branch/refdocument-segments chain))
                :parent {:branch parent :tail parent-tail :ref parent-ref}
                :child {:branch child :tail child-tail :ref child-ref}})))
         (finally
           (doseq [handle held] (writer-authority/release! handle))))))))

(defn new-database
  "Create an in-memory authoritative database for one immutable SpaceId."
  [space-id]
  {:term-store (term-store/new-term-store space-id)
   :space-id space-id :log nil :lock (Object.)
   :mutation-state (atom {:status :ready})
   :torn-tail nil :recovered-tail nil})

(defn database-recovery-state [db]
  @(:mutation-state db))

(defn mutation-ready? [db]
  (= :ready (:status (database-recovery-state db))))

(defn require-mutation-ready! [db]
  (let [{:keys [status] :as state} (database-recovery-state db)]
    (case status
      :ready true
      :recovery-required
      (fail! :recovery-required
             "database is fenced after a durability-ambiguous commit"
             {:recovery state})
      :corrupt
      (fail! :database-corrupt
             "database is permanently fenced because durable history is corrupt"
             {:recovery state})
      (fail! :database-state-invalid "database mutation state is invalid"
             {:recovery state}))))

(defn- require-readable! [db]
  (let [{:keys [status reconciled?] :as state}
        (database-recovery-state db)]
    (case status
      :ready true
      :recovery-required
      (if reconciled?
        true
        (fail! :recovery-required
               "durable history reconciliation has not completed"
               {:recovery state}))
      :corrupt
      (fail! :database-corrupt
             "durable history could not be reconciled"
             {:recovery state})
      (fail! :database-state-invalid "database mutation state is invalid"
             {:recovery state}))))

(defn database-store [db]
  (require-readable! db)
  (:term-store db))

(defn database-space [db] (:space-id db))

(defn store-view
  "Read-only database over an immutable TermStore root: every read accessor
   below works against the pinned root instead of the live store."
  [db root]
  (assoc db :term-store (atom root)))

(defn current-transaction [db]
  (t/transaction-coordinate
   (database-space db)
   (term-store/current-sequence (database-store db))))

(defn database-status [db]
  (locking (:lock db)
    (let [{:keys [status reconciled?] :as recovery}
          (database-recovery-state db)
          readable? (or (= :ready status)
                        (and (= :recovery-required status) reconciled?))
          context (:term-store db)]
      {:space-id (database-space db)
       :version (when readable?
                  (t/transaction-coordinate
                   (database-space db)
                   (term-store/current-sequence context)))
       :transactions (when readable? (term-store/transaction-count context))
       :operations (when readable? (term-store/operation-count context))
       :terms (when readable? (term-store/term-count context))
       :readable readable?
       :mutation-ready (= :ready status)
       :recovery recovery})))

(defn instant-now []
  (let [now (java.time.Instant/now)]
    (t/instant (.getEpochSecond now) (.getNano now))))

(defn- occurrence-events [db]
  (term-store/operation-occurrences (database-store db)))

;; Ranged: the whole-history operation-occurrences scan costs O(all operations)
;; per commit, making a corpus fold O(n^2) in propositions.
(defn- occurrence-events-range [db from to]
  (let [store @(database-store db)]
    (mapv (fn [position]
            (let [slots (term-store/occurrence-tuple-at store position)]
              (t/triple (nth slots 0) (nth slots 1) (nth slots 2))))
          (range from to))))

(defn history [db]
  (term-store/semantic-history (database-store db)))

(defn occurrence [db coordinate]
  (some #(when (= coordinate (kernel/occurrence-of %)) %) (occurrence-events db)))

(defn- relation-proposition? [predicate value]
  (and (t/triple? value)
       (t/occurrence-coordinate? (t/triple-t1 value))
       (= predicate (t/triple-t2 value))
       (t/occurrence-coordinate? (t/triple-t3 value))))

(defn supersession-triples [db]
  (filterv #(relation-proposition? :kernel/supersedes %)
           (term-store/live-propositions (database-store db))))

(defn withdrawal-triples [db]
  (vec
   (distinct
    (concat
     (term-store/withdrawal-triples (database-store db))
     (filter #(relation-proposition? :kernel/withdraws %)
             (term-store/live-propositions (database-store db)))))))

;; Only the frame just appended can name the occurrence coordinates it minted,
;; so withdrawal-triples' store-wide live-proposition scan cannot match here.
(defn- frame-withdrawal-triples [db frame-operations]
  (vec
   (distinct
    (concat
     (term-store/withdrawal-triples (database-store db))
     (->> frame-operations
          (filter #(= t/assert-action (t/commitoperation-action %)))
          (map t/commitoperation-proposition)
          (filter #(relation-proposition? :kernel/withdraws %)))))))

(defn- suppressed-occurrences [db]
  (into #{}
        (map t/triple-t3)
        (concat (supersession-triples db)
                (filter #(relation-proposition? :kernel/withdraws %)
                        (term-store/live-propositions
                         (database-store db))))))

(defn live-occurrences [db]
  (let [suppressed (suppressed-occurrences db)]
    (filterv #(not (contains? suppressed (kernel/occurrence-of %)))
             (term-store/live-occurrences (database-store db)))))

(defn live-propositions [db]
  (mapv kernel/proposition-of (live-occurrences db)))

(defn- validate-base [db base]
  (when base
    (when-not (t/transaction-coordinate? base)
      (fail! :invalid-base "OCC base must be a transaction-coordinate Triple"
             {:base base}))
    (when-not (= (database-space db) (t/triple-t1 base))
      (fail! :space-mismatch "OCC base belongs to a different SpaceId"
             {:base base :space-id (database-space db)})))
  base)

(def ^:private occurrence-metadata-order
  [:kernel/recorded-at :kernel/asserted-by :kernel/source-frame
   :kernel/withdraws :kernel/supersedes])

(defn- canonical-term! [value]
  (cond
    (t/triple? value)
    (t/triple (canonical-term! (t/triple-t1 value))
              (canonical-term! (t/triple-t2 value))
              (canonical-term! (t/triple-t3 value)))
    (integer? value) (long (require-i64! value "Int atom"))
    (and (number? value) (not (integer? value))) (double value)
    (t/instant? value)
    (t/instant (require-i64! (t/instant-epoch-seconds value)
                             "Instant epoch seconds")
               (t/instant-nanos value))
    (t/atom? value) value
    :else (fail! :invalid-term "value is outside Term" {:value value})))

(defn- commit-operation! [{:keys [action proposition] :as operation}]
  (when-not (t/triple? proposition)
    (fail! :invalid-commit-operation "operation proposition must be a Triple"
           {:operation operation}))
  (let [canonical (canonical-term! proposition)]
    (case action
    :assert (term-store/assert-operation canonical)
    :retract (term-store/retract-operation canonical)
    (fail! :invalid-commit-operation "operation action must be :assert or :retract"
           {:operation operation}))))

(defn- validate-occurrence-reference! [db coordinate field]
  (when coordinate
    (when-not (t/occurrence-coordinate? coordinate)
      (fail! :invalid-occurrence-coordinate
             (str field " must be an occurrence-coordinate Triple")
             {field coordinate}))
    (let [tx (t/triple-t1 coordinate)]
      (when-not (= (database-space db) (t/triple-t1 tx))
        (fail! :space-mismatch "occurrence coordinate belongs to another SpaceId"
               {field coordinate :space-id (database-space db)})))
    (when-not (occurrence db coordinate)
      (fail! :unknown-occurrence "occurrence coordinate does not resolve"
             {field coordinate})))
  coordinate)

(defn- metadata-operations [db tx-coordinate source-operations request]
  (let [source-count (count source-operations)
        per-source
        (mapcat
         (fn [[ordinal operation]]
           (let [source (t/occurrence-coordinate tx-coordinate ordinal)
                 values {:kernel/recorded-at (some-> (:recorded-at operation)
                                                     canonical-term!)
                         :kernel/asserted-by (some-> (:asserted-by operation)
                                                    canonical-term!)
                         :kernel/source-frame (some-> (:source-frame operation)
                                                     canonical-term!)
                         :kernel/withdraws (:withdraws operation)
                         :kernel/supersedes (:supersedes operation)}]
             (validate-occurrence-reference! db (:withdraws operation) :withdraws)
             (validate-occurrence-reference! db (:supersedes operation) :supersedes)
             (when (and (:recorded-at operation)
                        (not (t/instant? (:recorded-at operation))))
               (fail! :invalid-instant
                      "operation recorded-at must be a typed Instant"
                      {:recorded-at (:recorded-at operation)}))
             (mapv (fn [predicate]
                     (term-store/assert-operation
                      (t/triple source predicate (get values predicate))))
                   (filter #(some? (get values %)) occurrence-metadata-order))))
         (map-indexed vector source-operations))
        tx-metadata
        (cond-> []
          (:recorded-at request)
          (conj (term-store/assert-operation
                 (t/triple tx-coordinate :kernel/recorded-at
                           (canonical-term! (:recorded-at request)))))
          (:actor request)
          (conj (term-store/assert-operation
                 (t/triple tx-coordinate :kernel/asserted-by
                           (canonical-term! (:actor request))))))]
    (when (and (:recorded-at request)
               (not (t/instant? (:recorded-at request))))
      (fail! :invalid-instant "recorded-at must be a typed Instant"
             {:recorded-at (:recorded-at request)}))
    (when (and (:actor request) (not (t/term? (:actor request))))
      (fail! :invalid-term "actor must be a Term" {:actor (:actor request)}))
    (vec (concat per-source tx-metadata))))

(defn- append-and-replay! [db sequence operations]
  (let [frame (term-store/transaction-frame sequence operations)
        serializable {:tx-seq sequence
                      :operations (mapv operation-map (range) operations)}]
    (if *deferred-frames*
      (swap! *deferred-frames* conj serializable)
      (when-let [path (:log db)]
        (append-frame-durable! path serializable (:deflate? db))))
    (term-store/replay-transaction! (database-store db) frame)))

(defn- throwable-code [error]
  (let [data (ex-data error)]
    (or (:fram/code data) (:type data) (:code data)
        (keyword (.getSimpleName (class error))))))

(defn- fence-and-reconcile! [db before-store ^Throwable error]
  ;; No caller may observe the pre-append version as writable while the log is
  ;; being resolved after a write whose durable outcome is unknown.
  (let [cause {:code (throwable-code error) :message (.getMessage error)}]
    (reset! (:mutation-state db)
            {:status :recovery-required :reconciled? false :cause cause})
    (try
      (let [{:keys [context torn-tail valid-bytes source]}
            (if-let [path (:log db)]
              (let [parsed (read-triple-log! path)
                    space-id (:space-id parsed)
                    context (term-store/new-term-store space-id)]
                (when-not (= (database-space db) space-id)
                  (fail! :space-mismatch
                         "durable history changed SpaceId during reconciliation"
                         {:expected (database-space db) :actual space-id}))
                (replay-frames! context (:frames parsed))
                {:context context :torn-tail (:torn-tail parsed)
                 :valid-bytes (:valid-bytes parsed) :source :durable-prefix})
              (let [context (term-store/new-term-store (database-space db))]
                (reset! context before-store)
                {:context context :torn-tail nil :valid-bytes nil
                 :source :memory-snapshot}))
            sequence (term-store/current-sequence context)
            recovery {:status :recovery-required
                      :reconciled? true
                      :source source
                      :cause cause
                      :version (t/transaction-coordinate
                                (database-space db) sequence)
                      :torn-tail torn-tail
                      :valid-bytes valid-bytes}]
        (reset! (:term-store db) @context)
        (reset! (:mutation-state db) recovery)
        recovery)
      (catch Throwable reconciliation-error
        (let [corruption {:status :corrupt
                          :reconciled? false
                          :cause cause
                          :corruption
                          {:code (throwable-code reconciliation-error)
                           :message (.getMessage reconciliation-error)}}]
          (reset! (:mutation-state db) corruption)
          corruption)))))

(defn- propagate-ambiguous-commit! [recovery error]
  (if (= :corrupt (:status recovery))
    (throw
     (ex-info "durable history is corrupt after a commit failure"
              {:type :database-corrupt :fram/code :database-corrupt
               :recovery recovery}
              error))
    (throw
     (ex-info "commit outcome is durability-ambiguous; restart is required"
              {:type :durability-ambiguous :fram/code :durability-ambiguous
               :recovery recovery}
              error))))

(defn commit!
  "Commit one ordered transaction. REQUEST contains :operations and may contain
   :base, :actor, and typed :recorded-at. The response exposes transaction and
   occurrence coordinates; no physical row handle is public."
  [db {:keys [operations base] :as request}]
  (locking (:lock db)
    (require-mutation-ready! db)
    (validate-base db base)
    (let [current (current-transaction db)]
      (if (and base (not= base current))
        {:reject :conflict :expected base :current current}
        (do
          (when-not (and (vector? operations) (seq operations))
            (fail! :invalid-transaction-frame
                   "transaction requires a nonempty operation vector" {}))
          (when (:torn-tail db)
            (fail! :torn-tail-repair-required
                   "FRAMLOG has a torn trailing frame; writer authority must repair it"
                   {:path (:log db) :torn-tail (:torn-tail db)}))
          (let [context (database-store db)
                sequence (term-store/next-sequence context)
                tx-coordinate (t/transaction-coordinate
                               (database-space db) sequence)
                source-operations (mapv commit-operation! operations)
                metadata (metadata-operations db tx-coordinate operations request)
                all-operations (into source-operations metadata)
                before (term-store/operation-count context)
                before-store @context]
            (try
              (let [committed (append-and-replay! db sequence all-operations)
                    events (occurrence-events-range
                            db before (+ before (count source-operations)))
                    event-coordinates (into #{} (map kernel/occurrence-of) events)
                    withdrawals (filterv #(contains? event-coordinates
                                                      (t/triple-t1 %))
                                         (frame-withdrawal-triples
                                          db all-operations))]
                {:ok committed
                 :occurrences events
                 :withdrawals withdrawals
                 :operation-count (count all-operations)})
              (catch Throwable error
                (propagate-ambiguous-commit!
                 (fence-and-reconcile! db before-store error)
                 error)))))))))

(defn commit-cohort!
  "Run mutation functions in FIFO order against a private store root, append
   every resulting FRAMLOG frame under one durability barrier, and publish the
   root atomically. Individual pre-append failures are returned without
   aborting later functions; a barrier failure fences the whole database."
  [db mutation-functions]
  (locking (:lock db)
    (require-mutation-ready! db)
    (let [context (database-store db)
          before-store @context
          scratch (assoc db
                         :term-store (atom before-store)
                         :mutation-state (atom @(:mutation-state db)))
          frames (atom [])
          results
          (binding [*deferred-frames* frames]
            (mapv (fn [mutation]
                    (try
                      (let [value (mutation scratch)]
                        {:value value
                         :version (term-store/current-sequence
                                   (database-store scratch))})
                      (catch Throwable error
                        {:error error
                         :version (term-store/current-sequence
                                   (database-store scratch))})))
                  mutation-functions))]
      (if (empty? @frames)
        {:results results :frame-count 0 :root before-store
         :version (term-store/current-sequence context)}
        (try
          (when-let [path (:log db)]
            (append-frame-cohort-durable! path @frames (:deflate? db)))
          (let [root @(database-store scratch)]
            (reset! context root)
            {:results results :frame-count (count @frames) :root root
             :version (term-store/current-sequence context)})
          (catch Throwable error
            (propagate-ambiguous-commit!
             (fence-and-reconcile! db before-store error)
             error)))))))

(defn assert!
  ([db proposition] (assert! db proposition {}))
  ([db proposition options]
   (commit! db (assoc options :operations
                      [{:action :assert :proposition proposition
                        :supersedes (:supersedes options)
                        :source-frame (:source-frame options)}]))))

(defn retract!
  ([db proposition] (retract! db proposition {}))
  ([db proposition options]
   (commit! db (assoc options :operations
                      [{:action :retract :proposition proposition
                        :withdraws (:withdraws options)
                        :source-frame (:source-frame options)}]))))

(defn withdraw-occurrence!
  "Withdraw one exact currently-effective occurrence. TermStore's physical
   retraction targets the most recent equal live proposition; rejecting any
   other coordinate keeps the public target exact."
  [db target options]
  (locking (:lock db)
    (let [event (occurrence db target)
          effective (into #{} (map kernel/occurrence-of) (live-occurrences db))]
      (cond
        (nil? event) {:reject :unknown-occurrence :occurrence target}
        (not (kernel/assertion-occurrence? event))
        {:reject :not-assertion-occurrence :occurrence target}
        (not (contains? effective target))
        {:reject :occurrence-not-live :occurrence target}
        :else
        (let [proposition (kernel/proposition-of event)
              matching (filterv #(= proposition (kernel/proposition-of %))
                                (term-store/live-occurrences
                                 (database-store db)))
              current (some-> matching peek kernel/occurrence-of)]
          (if (not= target current)
            {:reject :withdrawal-target-not-current
             :occurrence target :current current}
            (retract! db proposition (assoc options :withdraws target))))))))

(defn supersede!
  "Assert REPLACEMENT while relating its new occurrence to exact TARGET."
  [db target replacement options]
  (locking (:lock db)
    (if-not (some #{target} (map kernel/occurrence-of (live-occurrences db)))
      {:reject :occurrence-not-live :occurrence target}
      (assert! db replacement (assoc options :supersedes target)))))

(defn view-select! [db view target options]
  (locking (:lock db)
    (validate-occurrence-reference! db target :target)
    (let [selection (t/triple view :kernel/selects target)]
      (if (some #{selection} (live-propositions db))
        {:idempotent true :selection selection}
        (assert! db selection options)))))

(defn view-deselect! [db view target options]
  (retract! db (t/triple view :kernel/selects target) options))

(defn view-occurrences [db view]
  (let [effective (live-occurrences db)
        by-coordinate (into {} (map (juxt kernel/occurrence-of identity)) effective)
        selected (for [event effective
                       :let [proposition (kernel/proposition-of event)]
                       :when (and (= view (t/triple-t1 proposition))
                                  (= :kernel/selects (t/triple-t2 proposition))
                                  (t/occurrence-coordinate?
                                   (t/triple-t3 proposition)))]
                   (t/triple-t3 proposition))]
    (into [] (keep by-coordinate) selected)))

(defn- lease-value [holder expires-ms]
  (t/triple holder :kernel/expires-at expires-ms))

(defn- lease-record [event]
  (let [proposition (kernel/proposition-of event)
        value (t/triple-t3 proposition)]
    (when (and (= :kernel/lease (t/triple-t2 proposition))
               (t/triple? value)
               (= :kernel/expires-at (t/triple-t2 value))
               (integer? (t/triple-t3 value)))
      {:resource (t/triple-t1 proposition)
       :holder (t/triple-t1 value)
       :expires-ms (t/triple-t3 value)
       :occurrence (kernel/occurrence-of event)
       :proposition proposition})))

(defn current-lease [db resource]
  (some->> (live-occurrences db)
           (keep lease-record)
           (filter #(= resource (:resource %)))
           last))

(defn acquire-lease! [db resource holder ttl-ms now-ms]
  (locking (:lock db)
    (when-not (and (t/term? resource) (t/term? holder)
                   (integer? ttl-ms) (pos? ttl-ms)
                   (integer? now-ms))
      (fail! :invalid-lease-request "lease requires Term resource/holder and positive ttl"
             {:resource resource :holder holder :ttl-ms ttl-ms :now-ms now-ms}))
    (let [prior (current-lease db resource)]
      (if (and prior (> (:expires-ms prior) now-ms))
        {:reject :lease-held :holder (:holder prior)
         :epoch (:occurrence prior) :expires-ms (:expires-ms prior)}
        (let [result (assert! db
                              (t/triple resource :kernel/lease
                                        (lease-value holder (+ now-ms ttl-ms)))
                              (cond-> {:actor holder}
                                prior (assoc :supersedes (:occurrence prior))))
              epoch (some-> result :occurrences first kernel/occurrence-of)]
          {:ok epoch :expires-ms (+ now-ms ttl-ms)
           :transaction (:ok result)})))))

(defn renew-lease! [db resource holder epoch ttl-ms now-ms]
  (locking (:lock db)
    (let [prior (current-lease db resource)]
      (if-not (and prior (= holder (:holder prior))
                   (= epoch (:occurrence prior))
                   (> (:expires-ms prior) now-ms))
        {:reject :lease-fence-mismatch :current prior}
        (let [result (assert! db
                              (t/triple resource :kernel/lease
                                        (lease-value holder (+ now-ms ttl-ms)))
                              {:actor holder :supersedes epoch})
              next-epoch (some-> result :occurrences first kernel/occurrence-of)]
          {:ok next-epoch :expires-ms (+ now-ms ttl-ms)
           :transaction (:ok result)})))))

(defn release-lease! [db resource holder epoch]
  (locking (:lock db)
    (let [prior (current-lease db resource)]
      (if-not (and prior (= holder (:holder prior)) (= epoch (:occurrence prior)))
        {:reject :lease-fence-mismatch :current prior}
        (let [result (withdraw-occurrence! db epoch {:actor holder})]
          (if (:ok result)
            {:ok true :transaction (:ok result) :withdrawals (:withdrawals result)}
            result))))))

(defn lease-fence-valid? [db resource holder epoch now-ms]
  (let [lease (current-lease db resource)]
    (boolean (and lease (= holder (:holder lease))
                  (= epoch (:occurrence lease))
                  (> (:expires-ms lease) now-ms)))))

;; ---------------------------------------------------------------------------
;; Sealed one-shot legacy flat-log migration
;; ---------------------------------------------------------------------------
;; "Turtles all the way down" is the architectural prior: semantic identity,
;; history, and metadata return as ordinary recursive Triple values. Turtle is
;; not a record, identifier, or log format; the physical frame fields below are
;; only the finite representation of TermStore transaction order.

(defn- sha256-bytes [^bytes bytes]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest bytes)
    (apply str (map #(format "%02x" (bit-and (int %) 255)) (.digest digest)))))

(defn- sha256-file [path]
  (with-open [in (java.io.BufferedInputStream.
                  (java.io.FileInputStream. (str path)))]
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")
          buffer (byte-array 65536)]
      (loop []
        (let [n (.read in buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur))))
      (apply str (map #(format "%02x" (bit-and (int %) 255))
                      (.digest digest))))))

(defn- file-stamp [^java.io.File file]
  (let [^"[Ljava.nio.file.LinkOption;" options
        (make-array java.nio.file.LinkOption 0)
        ^java.nio.file.attribute.BasicFileAttributes attributes
        (java.nio.file.Files/readAttributes
         (.toPath file) java.nio.file.attribute.BasicFileAttributes
         options)]
    {:file-key (str (.fileKey attributes))
     :bytes (.size attributes)
     :modified-ms (.toMillis (.lastModifiedTime attributes))}))

(defn- frozen-source! [source]
  (let [input (java.io.File. (str source))
        canonical (.getCanonicalFile input)]
    (when-not (and (.isAbsolute input)
                   (= (.getPath input) (.getPath canonical))
                   (.isFile canonical))
      (migration-fail! :migration-source-invalid
                       "migration source must be an absolute canonical regular file"
                       {:source (str source) :canonical (.getPath canonical)}))
    (let [before (file-stamp canonical)
          bytes (java.nio.file.Files/readAllBytes (.toPath canonical))
          after (file-stamp canonical)]
      (when-not (and (= before after) (= (:bytes after) (alength ^bytes bytes)))
        (migration-fail! :migration-source-changed
                         "migration source changed while it was being frozen"
                         {:source (.getPath canonical) :before before :after after
                          :read-bytes (alength ^bytes bytes)}))
      {:path (.getPath canonical)
       :file-key (:file-key after)
       :bytes bytes
       :byte-count (alength ^bytes bytes)
       :sha256 (sha256-bytes bytes)})))

(defn- legacy-line! [line-number byte-offset text]
  (let [row (try
              (edn/read-string text)
              (catch Exception error
                (migration-fail! :migration-malformed-interior
                                 "legacy flat log contains malformed completed EDN"
                                 {:line line-number :byte-offset byte-offset
                                  :cause (.getMessage error)})))]
    (when (or (not (map? row))
              (not (integer? (:tx row)))
              (neg? (:tx row))
              (not (contains? #{"assert" "retract"} (:op row)))
              (not (string? (:l row)))
              (not (string? (:p row)))
              (not (string? (:r row))))
      (migration-fail! (if (and (map? row) (contains? row :k))
                         :migration-v2-cache-not-source
                         :migration-malformed-interior)
                       "legacy migration requires completed flat operation rows"
                       {:line line-number :byte-offset byte-offset :row row}))
    (when (> (:tx row) Long/MAX_VALUE)
      (migration-fail! :migration-invalid-integer
                       "legacy transaction sequence exceeds signed 64-bit range"
                       {:line line-number :tx (:tx row)}))
    (assoc row :source-line line-number :source-byte-offset byte-offset)))

(def ^:private torn-leading-tx-pattern
  #"(?s)^\s*\{\s*:tx\s+(-?\d+)\s*,")
(def ^:private torn-tx-token-pattern
  #"(?<![A-Za-z0-9_./-]):tx(?=\s|,|\}|$)")

(defn- outside-string-view [text]
  (let [out (StringBuilder. (.length ^String text))]
    (loop [index 0 in-string? false escaped? false]
      (if (= index (.length ^String text))
        (.toString out)
        (let [character (.charAt ^String text index)]
          (if in-string?
            (do
              (.append out \space)
              (cond
                escaped? (recur (inc index) true false)
                (= character \\) (recur (inc index) true true)
                (= character \") (recur (inc index) false false)
                :else (recur (inc index) true false)))
            (do
              (.append out (if (= character \") \space character))
              (recur (inc index) (= character \") false))))))))

(defn- torn-transaction-sequence! [tail line-number byte-offset]
  (let [outside (outside-string-view tail)
        token-count (count (re-seq torn-tx-token-pattern outside))
        leading (re-find torn-leading-tx-pattern outside)
        location {:line line-number :byte-offset byte-offset}]
    (when (> token-count 1)
      (migration-fail! :migration-torn-transaction-ambiguous
                       "torn legacy tail contains more than one transaction coordinate"
                       (assoc location :transaction-token-count token-count)))
    (when-not leading
      (migration-fail!
       (if (and (= 1 token-count)
                (re-find #"(?s)^\s*\{\s*:tx(?:\s|,|\}|$)" outside))
         :migration-torn-transaction-ambiguous
         :migration-torn-transaction-missing)
       "torn legacy tail has no complete canonical leading transaction coordinate"
       (assoc location :transaction-token-count token-count)))
    (let [value (bigint (second leading))]
      (when (or (neg? value) (> value Long/MAX_VALUE))
        (migration-fail! :migration-invalid-integer
                         "torn legacy transaction sequence is outside signed 64-bit range"
                         (assoc location :tx value)))
      (long value))))

(defn- validate-legacy-transaction-order! [rows]
  (loop [remaining (seq rows) previous nil]
    (when-let [row (first remaining)]
      (when (and (some? previous) (< (:tx row) previous))
        (migration-fail! :migration-nonmonotonic-transaction
                         "legacy flat log transaction sequence moved backward"
                         {:previous previous :current (:tx row)
                          :line (:source-line row)
                          :byte-offset (:source-byte-offset row)}))
      (recur (next remaining) (:tx row))))
  rows)

(defn- reconcile-torn-transaction! [rows tail line-number byte-offset]
  (let [tail-tx (torn-transaction-sequence! tail line-number byte-offset)
        last-tx (some-> rows peek :tx)
        report {:line line-number :byte-offset byte-offset
                :bytes (alength ^bytes (strict-utf8-bytes tail "torn tail"))
                :transaction-sequence tail-tx}]
    (cond
      (nil? last-tx)
      {:rows rows :torn-tail (assoc report :dropped-complete-rows 0
                                    :reason :torn-only-transaction)}

      (< tail-tx last-tx)
      (migration-fail! :migration-nonmonotonic-torn-transaction
                       "torn legacy transaction sequence moved backward"
                       (assoc report :previous last-tx))

      (= tail-tx last-tx)
      (let [dropped (count (take-while #(= tail-tx (:tx %)) (rseq rows)))]
        {:rows (subvec rows 0 (- (count rows) dropped))
         :torn-tail (assoc report :dropped-complete-rows dropped
                           :reason :torn-same-transaction)})

      :else
      {:rows rows :torn-tail (assoc report :dropped-complete-rows 0
                                    :reason :torn-later-transaction)})))

(defn- parse-legacy-flat [^bytes bytes]
  (when (bytes-prefix? bytes legacy-fri-magic)
    (migration-fail! :migration-v2-cache-not-source
                     "FRI cache is not an authoritative migration source" {}))
  (when (bytes-prefix? bytes triple-log-magic)
    (migration-fail! :migration-already-complete
                     "source already has the FRAMLOG header" {}))
  (let [text (strict-utf8-string bytes "legacy flat log")
        terminal-lf? (or (zero? (alength bytes))
                         (= 10 (bit-and 255 (aget bytes (dec (alength bytes))))))
        pieces (str/split text #"\n" -1)
        complete (butlast pieces)
        tail (when-not terminal-lf? (last pieces))]
    (loop [remaining (seq complete) line-number 1 byte-offset 0 rows []]
      (if (nil? remaining)
        (let [ordered (validate-legacy-transaction-order! rows)]
          (if tail
            (reconcile-torn-transaction! ordered tail line-number byte-offset)
            {:rows ordered :torn-tail nil}))
        (let [line (first remaining)
              line-bytes (alength ^bytes (strict-utf8-bytes line "legacy line"))]
          (when (str/blank? line)
            (migration-fail! :migration-malformed-interior
                             "legacy flat log contains a blank completed row"
                             {:line line-number :byte-offset byte-offset}))
          (recur (next remaining) (inc line-number)
                 (+ byte-offset line-bytes 1)
                 (conj rows (legacy-line! line-number byte-offset line))))))))

;; These defaults are frozen migration semantics. Runtime predicate policy is a
;; projection and cannot influence deterministic conversion bytes.
(def ^:private legacy-single-valued
  #{"title" "owner" "lead" "driver" "source" "part_of" "do_on"
    "valid_until" "estimate_hours" "created_at" "updated_at" "name"
    "body" "created_by" "committed" "outcome" "abandoned"
    "superseded_by" "merged_into" "session_of" "start_time" "end_time"
    "clockify_id"})

(defn- legacy-registry-key [row]
  (if (= "predicate_name" (:p row))
    [(:l row) (:p row)]
    [(:l row) (:p row) (:r row)]))

(defn- legacy-predicate-registry [rows]
  (let [latest
        (reduce (fn [known row]
                  (if (contains? #{"predicate_name" "predicate_alias"} (:p row))
                    (assoc known (legacy-registry-key row) row)
                    known))
                {} rows)
        facts (filter #(= "assert" (:op %)) (vals latest))
        canonical (into {} (for [row facts :when (= "predicate_name" (:p row))]
                             [(:l row) (:r row)]))
        by-name
        (reduce
         (fn [known row]
           (let [spelling (:r row) identity (:l row)]
             (when-let [prior (get known spelling)]
               (when-not (= prior identity)
                 (migration-fail! :migration-predicate-spelling-collision
                                  "legacy predicate spelling resolves to two identities"
                                  {:spelling spelling :left prior :right identity})))
             (assoc known spelling identity)))
         {} facts)]
    {:canonical canonical :by-name by-name}))

(defn- legacy-predicate-id [registry spelling]
  (if (str/starts-with? spelling "@")
    spelling
    (or (get (:by-name registry) spelling) (str "@" spelling))))

(defn- legacy-predicate-name [registry spelling]
  (let [identity (legacy-predicate-id registry spelling)]
    (or (get (:canonical registry) identity)
        (if (str/starts-with? identity "@") (subs identity 1) identity))))

(defn- legacy-cardinality [registry rows]
  (let [cardinality-id (legacy-predicate-id registry "cardinality")
        latest
        (reduce
         (fn [known row]
           (if (= cardinality-id (legacy-predicate-id registry (:p row)))
             (assoc known (legacy-predicate-id registry (:l row)) row)
             known))
         {} rows)]
    (into {}
          (for [[identity row] latest :when (= "assert" (:op row))]
            [identity (= "single" (:r row))]))))

(defn- final-cardinality [rows]
  (let [registry (legacy-predicate-registry rows)]
    {:registry registry :cardinality (legacy-cardinality registry rows)}))

(defn- legacy-single? [{:keys [registry cardinality]} predicate]
  (let [identity (legacy-predicate-id registry predicate)
        explicit (get cardinality identity ::missing)
        canonical (legacy-predicate-name registry predicate)]
    (if-not (= ::missing explicit)
      explicit
      (or (contains? legacy-single-valued canonical)
          (str/starts-with? canonical "emoji_")))))

(defn- active-key [{:keys [registry] :as classification} row]
  (let [identity (legacy-predicate-id registry (:p row))]
    (if (legacy-single? classification (:p row))
      [(:l row) identity]
      [(:l row) identity (:r row)])))

(defn- parsed-recorded-at [row diagnostics]
  (if-not (contains? row :ts)
    [nil diagnostics]
    (let [value (:ts row)]
      (if-not (string? value)
        [value diagnostics]
        (try
          (let [instant (java.time.Instant/parse value)]
            [(t/instant (.getEpochSecond instant) (.getNano instant)) diagnostics])
          (catch java.time.format.DateTimeParseException _
            [value (conj diagnostics {:code :unparseable-recorded-at
                                      :line (:source-line row)
                                      :value value})]))))))

(defn- migration-source-operation [ordinal row relation-target]
  {:ordinal ordinal
   :action (if (= "assert" (:op row)) 1 2)
   :triple (t/triple (:l row) (:p row) (:r row))
   :source-line (:source-line row)
   :source-byte-offset (:source-byte-offset row)
   :relation-target relation-target
   :source row})

(defn- migration-transaction-plan
  [space-id tx-sequence rows active classification diagnostics]
  (let [[sources active-after]
        (loop [remaining rows ordinal 0 operations [] active-now active]
          (if (empty? remaining)
            [operations active-now]
            (let [row (first remaining)
                  key (active-key classification row)
                  prior (get active-now key)
                  occurrence (t/occurrence-coordinate
                              (t/transaction-coordinate space-id tx-sequence)
                              ordinal)
                  assertion? (= "assert" (:op row))
                  relation (when prior
                             [(if assertion? :kernel/supersedes :kernel/withdraws)
                              prior])
                  next-active (if assertion?
                                (assoc active-now key occurrence)
                                (dissoc active-now key))]
              (recur (rest remaining) (inc ordinal)
                     (conj operations
                           (migration-source-operation ordinal row relation))
                     next-active))))
        source-count (count sources)
        [synthetic final-diagnostics]
        (loop [remaining sources ordinal source-count operations []
               current-diagnostics diagnostics]
          (if (empty? remaining)
            [operations current-diagnostics]
            (let [source (first remaining)
                  row (:source source)
                  source-coordinate
                  (t/occurrence-coordinate
                   (t/transaction-coordinate space-id tx-sequence)
                   (:ordinal source))
                  [recorded-at next-diagnostics]
                  (parsed-recorded-at row current-diagnostics)
                  relation (:relation-target source)
                  values {:kernel/recorded-at recorded-at
                          :kernel/asserted-by (when (contains? row :by) (:by row))
                          :kernel/source-frame (when (contains? row :frame) (:frame row))
                          :kernel/withdraws (when (= :kernel/withdraws (first relation))
                                              (second relation))
                          :kernel/supersedes (when (= :kernel/supersedes (first relation))
                                               (second relation))}
                  additions
                  (reduce
                   (fn [result predicate]
                     (if-some [value (get values predicate)]
                       (conj result
                             {:ordinal (+ ordinal (count result)) :action 1
                              :triple (t/triple source-coordinate predicate value)
                              :synthetic-for (:ordinal source)
                              :predicate predicate})
                       result))
                   [] occurrence-metadata-order)]
              (recur (rest remaining) (+ ordinal (count additions))
                     (into operations additions) next-diagnostics))))]
    [{:tx-seq tx-sequence :source-count source-count
      :operations (into sources synthetic)}
     active-after final-diagnostics]))

(defn- write-triple-log-temp! [parent space-id transactions]
  (let [tmp (java.nio.file.Files/createTempFile
             parent ".fram-triple-log-" ".tmp"
             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [file-out (java.io.FileOutputStream. (.toFile tmp))
                  out (java.io.BufferedOutputStream. file-out)]
        (write-header! out space-id)
        (doseq [transaction transactions]
          (write-transaction-frame! out transaction false))
        (.flush out)
        (.force (.getChannel file-out) true))
      {:path tmp :bytes (java.nio.file.Files/size tmp)
       :sha256 (sha256-file tmp)}
      (catch Throwable error
        (java.nio.file.Files/deleteIfExists tmp)
        (throw error)))))

(defn- write-bytes-temp! [parent prefix ^bytes bytes]
  (let [tmp (java.nio.file.Files/createTempFile
             parent prefix ".tmp"
             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [file-out (java.io.FileOutputStream. (.toFile tmp))]
        (.write file-out bytes)
        (.force (.getChannel file-out) true))
      tmp
      (catch Throwable error
        (java.nio.file.Files/deleteIfExists tmp)
        (throw error)))))

(defn- atomic-install! [tmp destination]
  (java.nio.file.Files/move
   tmp destination
   (into-array java.nio.file.CopyOption
               [java.nio.file.StandardCopyOption/ATOMIC_MOVE])))

(defn- migration-counts [transactions diagnostics]
  (let [operations (mapcat :operations transactions)
        sources (filter :source operations)
        synthetic (remove :source operations)]
    (sorted-map
     :assertions (count (filter #(= 1 (:action %)) sources))
     :diagnostic-count (count diagnostics)
     :legacy-cid-count 0
     :noop-retractions (count (filter #(and (= 2 (:action %))
                                            (nil? (:relation-target %))) sources))
     :retractions (count (filter #(= 2 (:action %)) sources))
     :source-operations (count sources)
     :synthetic-operations (count synthetic)
     :targeted-retractions
     (count (filter #(and (= 2 (:action %))
                          (= :kernel/withdraws (first (:relation-target %)))) sources))
     :transactions (count transactions)
     :unparseable-recorded-at
     (count (filter #(= :unparseable-recorded-at (:code %)) diagnostics)))))

(defn- migration-output-format [deflate?]
  (sorted-map
   :encoding (if deflate? :deflate :uncompressed)
   :framlog-flags (if deflate? deflate-flag triple-log-flags)
   :framlog-version triple-log-version))

(defn- write-migration-temp! [parent space-id rows deflate?]
  (let [classification (final-cardinality rows)
        zero-counts (migration-counts [] [])
        tmp (java.nio.file.Files/createTempFile
             parent ".fram-triple-log-" ".tmp"
             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [result
            (with-open [file-out (java.io.FileOutputStream. (.toFile tmp))
                        out (java.io.BufferedOutputStream. file-out)]
              (write-header! out space-id
                             (if deflate? deflate-flag triple-log-flags))
              (let [migration
                    (loop [remaining (seq rows) previous-tx nil first-tx nil
                           active {} counts zero-counts diagnostics []]
                      (if (nil? remaining)
                        {:diagnostics diagnostics :summary counts
                         :transaction-range (when first-tx [first-tx previous-tx])}
                        (let [tx-sequence (:tx (first remaining))
                              [same later] (split-with #(= tx-sequence (:tx %)) remaining)
                              [transaction next-active tx-diagnostics]
                              (migration-transaction-plan
                               space-id tx-sequence (vec same) active classification [])
                              tx-counts (migration-counts [transaction] tx-diagnostics)
                              sample-room (- 32 (count diagnostics))]
                          (write-transaction-frame! out transaction deflate?)
                          (recur (seq later) tx-sequence (or first-tx tx-sequence)
                                 next-active (merge-with + counts tx-counts)
                                 (if (pos? sample-room)
                                   (into diagnostics (take sample-room tx-diagnostics))
                                   diagnostics)))))]
                (.flush out)
                (.force (.getChannel file-out) true)
                migration))]
        (merge result {:path tmp :bytes (java.nio.file.Files/size tmp)
                       :sha256 (sha256-file tmp)}))
      (catch Throwable error
        (java.nio.file.Files/deleteIfExists tmp)
        (throw error)))))

(defn- migration-manifest! [manifest-file]
  (try
    (edn/read-string (slurp manifest-file))
    (catch Throwable error
      (migration-fail! :migration-manifest-invalid
                       "migration manifest is not valid EDN"
                       {:manifest (.getPath ^java.io.File manifest-file)
                        :cause (.getMessage error)}))))

(defn verify-legacy-flat-log-migration!
  "Verify a migrated FRAMLOG generation against its adjacent sealed manifest."
  [target]
  (let [input (java.io.File. (str target))
        canonical (.getCanonicalFile input)
        manifest-file (java.io.File. (str (.getPath canonical) ".migration.edn"))]
    (when-not (and (.isAbsolute input)
                   (= (.getPath input) (.getPath canonical))
                   (.isFile canonical)
                   (.isFile manifest-file))
      (migration-fail! :migration-seal-missing
                       "migration target and adjacent manifest must both exist"
                       {:target (str target) :manifest (.getPath manifest-file)}))
    (let [manifest (migration-manifest! manifest-file)
          output (:output manifest)
          encoding (:encoding output)
          expected-flags (case encoding
                           :uncompressed triple-log-flags
                           :deflate deflate-flag
                           nil)
          bytes (java.nio.file.Files/size (.toPath canonical))
          sha256 (sha256-file (.toPath canonical))
          parsed (read-triple-log! (.getPath canonical))]
      (when-not (and (= triple-log-manifest-version (:format manifest))
                     (= triple-log-version (:framlog-version output))
                     (some? expected-flags)
                     (= expected-flags (:framlog-flags output))
                     (= (= deflate-flag expected-flags) (:deflate? parsed))
                     (= (:space-id manifest) (:space-id parsed))
                     (= bytes (:bytes output))
                     (= sha256 (:sha256 output)))
        (migration-fail! :migration-seal-invalid
                         "migration target does not match its sealed manifest"
                         {:target (.getPath canonical)
                          :manifest (.getPath manifest-file)}))
      manifest)))

(defn- migration-options! [options]
  (when-not (and (map? options)
                 (every? #{:deflate?} (keys options))
                 (or (not (contains? options :deflate?))
                     (instance? Boolean (:deflate? options))))
    (migration-fail! :migration-options-invalid
                     "migration options accept only a Boolean :deflate? value"
                     {:options options}))
  (boolean (:deflate? options)))

(defn migrate-legacy-flat-log!
  "Seal one frozen canonical flat log into FRAMLOG. The converter is the only
   legacy reader; runtime boot never dual-accepts the source bytes."
  ([source space-id target]
   (migrate-legacy-flat-log! source space-id target {}))
  ([source space-id target options]
   (let [deflate? (migration-options! options)
         space-bytes (strict-utf8-bytes space-id "SpaceId")]
     (when (zero? (alength ^bytes space-bytes))
       (migration-fail! :migration-space-id-required
                        "SpaceId must be a nonempty UTF-8 String" {}))
     (let [frozen (frozen-source! source)
           parsed (parse-legacy-flat (:bytes frozen))
           target-file (java.io.File. (str target))
           canonical-target (.getCanonicalFile target-file)
           manifest-file (java.io.File. (str (.getPath canonical-target)
                                             ".migration.edn"))
           parent (.toPath (.getParentFile canonical-target))]
       (when-not (and (.isAbsolute target-file)
                      (= (.getPath target-file) (.getPath canonical-target))
                      (.isDirectory (.getParentFile canonical-target))
                      (not= (:path frozen) (.getPath canonical-target)))
         (migration-fail! :migration-target-invalid
                          "migration target must be a distinct absolute canonical path"
                          {:target (str target) :canonical (.getPath canonical-target)}))
       (when (or (.exists canonical-target) (.exists manifest-file))
         (migration-fail! :migration-target-exists
                          "sealed migration refuses to overwrite a target or manifest"
                          {:target (.getPath canonical-target)
                           :manifest (.getPath manifest-file)}))
       (let [written (write-migration-temp! parent space-id (:rows parsed) deflate?)
             counts (:summary written)
             output (merge (migration-output-format deflate?)
                           (sorted-map :bytes (:bytes written)
                                       :sha256 (:sha256 written)))
             manifest
             (sorted-map
              :diagnostics (:diagnostics written)
              :format triple-log-manifest-version
              :output (into (sorted-map) output)
              :source (sorted-map :bytes (:byte-count frozen)
                                  :file-key (:file-key frozen)
                                  :path (:path frozen) :sha256 (:sha256 frozen))
              :space-id space-id
              :summary counts
              :torn-tail (:torn-tail parsed)
              :transaction-range (:transaction-range written)
              :unresolved-classes
              [{:class :cid-addressed-v2-only-data
                :disposition :not-migrated
                :reason "flat sources contain no cid field; v2/FRI caches are rejected as non-authoritative"}])
             manifest-bytes (.getBytes (str (pr-str manifest) "\n")
                                       java.nio.charset.StandardCharsets/UTF_8)
             manifest-tmp (write-bytes-temp! parent ".fram-migration-manifest-"
                                             manifest-bytes)
             target-path (.toPath canonical-target)
             manifest-path (.toPath manifest-file)]
         (try
           (atomic-install! (:path written) target-path)
           (try
             (atomic-install! manifest-tmp manifest-path)
             (catch Throwable error
               (java.nio.file.Files/deleteIfExists target-path)
               (throw error)))
           (try
             (when-not (= manifest
                          (verify-legacy-flat-log-migration!
                           (.getPath canonical-target)))
               (migration-fail! :migration-seal-invalid
                                "installed migration manifest changed during publication"
                                {:target (.getPath canonical-target)
                                 :manifest (.getPath manifest-file)}))
             (catch Throwable error
               (java.nio.file.Files/deleteIfExists target-path)
               (java.nio.file.Files/deleteIfExists manifest-path)
               (throw error)))
           {:target (.getPath canonical-target)
            :manifest (.getPath manifest-file)
            :output (:output manifest)
            :summary counts :sha256 (:sha256 written)
            :torn-tail (:torn-tail parsed)}
           (finally
             (java.nio.file.Files/deleteIfExists (:path written))
             (java.nio.file.Files/deleteIfExists manifest-tmp))))))))
