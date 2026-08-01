(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(load-file "coord.clj")

(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok
    (throw (ex-info label {}))))

(defn u16 [^java.nio.ByteBuffer buf]
  (bit-and 65535 (int (.getShort buf))))

(defn u32 [^java.nio.ByteBuffer buf]
  (Integer/toUnsignedLong (.getInt buf)))

(declare read-term)

(defn read-text [^java.nio.ByteBuffer buf]
  (let [n (u32 buf)
        bytes (byte-array (int n))]
    (.get buf bytes)
    (String. bytes java.nio.charset.StandardCharsets/UTF_8)))

(defn read-term [^java.nio.ByteBuffer buf]
  (case (bit-and 255 (int (.get buf)))
    1 (read-text buf)
    2 (.getLong buf)
    3 (Double/longBitsToDouble (.getLong buf))
    4 false
    5 true
    6 (keyword (read-text buf))
    7 [(read-term buf) (read-term buf) (read-term buf)]
    8 (java.time.Instant/ofEpochSecond (.getLong buf) (u32 buf))
    (throw (ex-info "unknown test term tag" {}))))

(defn decode-log [path]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. (str path))))
        buf (doto (java.nio.ByteBuffer/wrap bytes)
              (.order java.nio.ByteOrder/LITTLE_ENDIAN))
        magic (byte-array 8)]
    (.get buf magic)
    (let [version (u16 buf)
          flags (u16 buf)
          space (read-text buf)
          frames
          (loop [out []]
            (if (zero? (.remaining buf))
              out
              (let [n (u32 buf)
                    payload (byte-array (int n))]
                (.get buf payload)
                (let [stored-crc (u32 buf)
                      crc (doto (java.util.zip.CRC32.) (.update payload))
                      pbuf (doto (java.nio.ByteBuffer/wrap payload)
                             (.order java.nio.ByteOrder/LITTLE_ENDIAN))
                      tx (.getLong pbuf)
                      nops (u32 pbuf)
                      ops (mapv (fn [_]
                                  {:ordinal (u32 pbuf)
                                   :action (bit-and 255 (int (.get pbuf)))
                                   :triple (read-term pbuf)})
                                (range nops))]
                  (check! (str "CRC matches tx " tx) (= stored-crc (.getValue crc)))
                  (check! (str "payload fully consumed tx " tx) (zero? (.remaining pbuf)))
                  (recur (conj out {:tx tx :operations ops}))))))]
      {:magic (String. magic java.nio.charset.StandardCharsets/ISO_8859_1)
       :version version :flags flags :space space :frames frames})))

(defn hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 255)) bytes)))

(defn throwable-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:fram/code (ex-data e)))))

(def tmp-dir
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-triple-migration-test-"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(def legacy-file (java.io.File. tmp-dir "legacy.log"))
(def target-a (java.io.File. tmp-dir "a.framlog"))
(def target-b (java.io.File. tmp-dir "b.framlog"))

(def rows
  [{:tx 5 :op "assert" :l "Alice" :p "email" :r "alice@example.com"
    :ts "2026-08-01T10:31:22.123456789Z" :by "Tom" :frame "merge"}
   {:tx 5 :op "assert" :l "Alice" :p "email" :r "alice@example.com"
    :ts "legacy-local-no-zone" :by "CRM" :frame "merge"}
   {:tx 5 :op "retract" :l "Alice" :p "email" :r "alice@example.com"}
   {:tx 6 :op "retract" :l "Nobody" :p "email" :r "missing@example.com"}])

(spit legacy-file (str (str/join "\n" (map pr-str rows)) "\n"))

(def result-a (migrate-legacy-flat-log! (.getPath legacy-file) "msa-space" (.getPath target-a)))
(def result-b (migrate-legacy-flat-log! (.getPath legacy-file) "msa-space" (.getPath target-b)))

(def bytes-a (java.nio.file.Files/readAllBytes (.toPath target-a)))
(def bytes-b (java.nio.file.Files/readAllBytes (.toPath target-b)))
(def manifest-a (slurp (str (.getPath target-a) ".migration.edn")))
(def manifest-b (slurp (str (.getPath target-b) ".migration.edn")))
(def decoded (decode-log target-a))
(def tx5 (first (:frames decoded)))
(def tx5-ops (:operations tx5))

(check! "double migration is byte-identical" (java.util.Arrays/equals bytes-a bytes-b))
(check! "double migration manifest is byte-identical" (= manifest-a manifest-b))
(check! "sealed migration refuses to overwrite an existing generation"
        (= :migration-target-exists
           (throwable-code
            #(migrate-legacy-flat-log! (.getPath legacy-file)
                                       "msa-space" (.getPath target-a)))))
(check! "header is FRAMLOG v1 with immutable SpaceId"
        (and (= "FRAMLOG\u0000" (:magic decoded))
             (= 1 (:version decoded)) (= 0 (:flags decoded))
             (= "msa-space" (:space decoded))))
(check! "source operation ordinals stay first and contiguous"
        (= [0 1 2] (mapv :ordinal (take 3 tx5-ops))))
(check! "synthetic operations follow source operations contiguously"
        (= (vec (range (count tx5-ops))) (mapv :ordinal tx5-ops)))
(check! "duplicate assertion keeps its own occurrence and supersedes the prior one"
        (some #(= [[["msa-space" :kernel/tx-sequence 5]
                    :kernel/op-ordinal 1]
                   :kernel/supersedes
                   [["msa-space" :kernel/tx-sequence 5]
                    :kernel/op-ordinal 0]]
                  (:triple %))
              tx5-ops))
(check! "retraction occurrence withdraws the active assertion occurrence"
        (some #(= [[["msa-space" :kernel/tx-sequence 5]
                    :kernel/op-ordinal 2]
                   :kernel/withdraws
                   [["msa-space" :kernel/tx-sequence 5]
                    :kernel/op-ordinal 1]]
                  (:triple %))
              tx5-ops))
(check! "parseable recorded-at is a typed Instant"
        (some #(= (java.time.Instant/parse "2026-08-01T10:31:22.123456789Z")
                  (nth (:triple %) 2 nil))
              tx5-ops))
(check! "unparseable recorded-at remains a String"
        (some #(= "legacy-local-no-zone" (nth (:triple %) 2 nil)) tx5-ops))

(def summary (:summary (edn/read-string manifest-a)))
(check! "manifest records source/synthetic/retraction counts and zero flat-log cids"
        (= {:assertions 2 :diagnostic-count 1 :legacy-cid-count 0 :noop-retractions 1
            :retractions 2 :source-operations 4 :synthetic-operations 8
            :targeted-retractions 1 :transactions 2
            :unparseable-recorded-at 1}
           summary))
(check! "manifest names the v2-only cid class without overlaying a cache"
        (= [{:class :cid-addressed-v2-only-data
             :disposition :not-migrated
             :reason "flat sources contain no cid field; v2/FRI caches are rejected as non-authoritative"}]
           (:unresolved-classes (edn/read-string manifest-a))))
(check! "new header reader returns SpaceId"
        (= "msa-space" (require-triple-log-header! (.getPath target-a))))
(check! "legacy runtime input is a typed migration requirement"
        (= :migration-required
           (throwable-code #(require-triple-log-header! (.getPath legacy-file)))))

;; Same tx numbers in another immutable space resolve to different coordinates.
(def target-other (java.io.File. tmp-dir "other.framlog"))
(migrate-legacy-flat-log! (.getPath legacy-file) "telemetry-space" (.getPath target-other))
(def other (decode-log target-other))
(check! "overlapping transaction numbers remain distinct across spaces"
        (and (= 5 (:tx (first (:frames decoded))))
             (= 5 (:tx (first (:frames other))))
             (not= (:space decoded) (:space other))))

;; A non-LF final segment is the only repairable tail; a completed bad row fails.
(def torn-source (java.io.File. tmp-dir "torn.log"))
(def torn-target (java.io.File. tmp-dir "torn.framlog"))
(spit torn-source (str (pr-str (first rows)) "\n{:tx 9, :op \"assert\", :l \"cut"))
(def torn-result
  (migrate-legacy-flat-log! (.getPath torn-source) "torn-space" (.getPath torn-target)))
(check! "unterminated final transaction tail is dropped atomically"
        (and (some? (:torn-tail torn-result))
             (= [5] (mapv :tx (:frames (decode-log torn-target))))))

(def corrupt-source (java.io.File. tmp-dir "corrupt.log"))
(def corrupt-target (java.io.File. tmp-dir "corrupt.framlog"))
(spit corrupt-source (str (pr-str (first rows)) "\n{:not \"a fact op\"}\n"))
(check! "completed malformed interior input fails"
        (= :migration-malformed-interior
           (throwable-code
            #(migrate-legacy-flat-log! (.getPath corrupt-source)
                                       "bad-space" (.getPath corrupt-target)))))

(def v2-source (java.io.File. tmp-dir "snapshot.v2log"))
(def v2-target (java.io.File. tmp-dir "snapshot.framlog"))
(spit v2-source (str (pr-str {:k :fact :cid 7 :l 1 :p 2 :r 3 :tx 4}) "\n"))
(check! "lossy v2 cache is rejected as a migration source"
        (= :migration-v2-cache-not-source
           (throwable-code
            #(migrate-legacy-flat-log! (.getPath v2-source)
                                       "bad-space" (.getPath v2-target)))))

(def fri-source (java.io.File. tmp-dir "snapshot.fri"))
(def fri-target (java.io.File. tmp-dir "fri.framlog"))
(with-open [out (java.io.FileOutputStream. fri-source)]
  (.write out (.getBytes "FRAMFRI1cache"
                         java.nio.charset.StandardCharsets/UTF_8)))
(check! "FRI cache is rejected as a migration source"
        (= :migration-v2-cache-not-source
           (throwable-code
            #(migrate-legacy-flat-log! (.getPath fri-source)
                                       "bad-space" (.getPath fri-target)))))

;; Cross-runtime golden owned jointly with src/zig/log.zig.
(def golden-triple
  ((deref #'coord/triple-term) "Alice" :email "alice@example.com"))
(def golden-write
  ((deref #'coord/write-triple-log-temp!)
   (.toPath tmp-dir) "msa-space"
   [{:tx-seq 1842
     :operations [{:ordinal 0 :action 1 :triple golden-triple}]}]))
(def golden-hex
  "4652414d4c4f470001000000090000006d73612d73706163653c0000003207000000000000010000000000000001070105000000416c6963650605000000656d61696c0111000000616c696365406578616d706c652e636f6dd42d3294")
(check! "JVM writer matches the Zig FRAMLOG v1 golden bytes"
        (= golden-hex
           (hex (java.nio.file.Files/readAllBytes (:path golden-write)))))
(java.nio.file.Files/deleteIfExists (:path golden-write))

(def nested-triple
  ((deref #'coord/triple-term)
   ((deref #'coord/triple-term) "left" :edge "one")
   ((deref #'coord/triple-term) "middle" :edge "two")
   ((deref #'coord/triple-term) "right" :edge "three")))
(def nested-write
  ((deref #'coord/write-triple-log-temp!)
   (.toPath tmp-dir) "recursive-space"
   [{:tx-seq 9
     :operations [{:ordinal 0 :action 1 :triple nested-triple}]}]))
(check! "recursive Triple terms round-trip in slot0, slot1, and slot2"
        (= [[["left" :edge "one"]
             ["middle" :edge "two"]
             ["right" :edge "three"]]]
           (mapv :triple
                 (:operations (first (:frames (decode-log (:path nested-write))))))))
(java.nio.file.Files/deleteIfExists (:path nested-write))

(println "triple_log_migration_test: PASS")
