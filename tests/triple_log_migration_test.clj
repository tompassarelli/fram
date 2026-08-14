(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[fram.types :as t])

(load-file "database.clj")

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

(defn inflate [^bytes bytes]
  (let [out (java.io.ByteArrayOutputStream.)
        buffer (byte-array 8192)]
    (with-open [in (java.util.zip.GZIPInputStream.
                    (java.io.ByteArrayInputStream. bytes))]
      (loop []
        (let [n (.read in buffer)]
          (when (pos? n)
            (.write out buffer 0 n)
            (recur)))))
    (.toByteArray out)))

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
                      decoded-payload (case flags
                                        0 payload
                                        1 (inflate payload)
                                        (throw (ex-info "unknown test FRAMLOG flags"
                                                        {:flags flags})))
                      pbuf (doto (java.nio.ByteBuffer/wrap decoded-payload)
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
(def deflate-target-a (java.io.File. tmp-dir "deflate-a.framlog"))
(def deflate-target-b (java.io.File. tmp-dir "deflate-b.framlog"))

(def rows
  [{:tx 5 :op "assert" :l "Alice" :p "email" :r "alice@example.com"
    :ts "2026-08-01T10:31:22.123456789Z" :by "Tom" :frame "merge"}
   {:tx 5 :op "assert" :l "Alice" :p "email" :r "alice@example.com"
    :ts "legacy-local-no-zone" :by "CRM" :frame "merge"}
   {:tx 5 :op "retract" :l "Alice" :p "email" :r "alice@example.com"}
   {:tx 6 :op "retract" :l "Nobody" :p "email" :r "missing@example.com"}])

(spit legacy-file (str (str/join "\n" (map pr-str rows)) "\n"))

(def result-a (database/migrate-legacy-flat-log! (.getPath legacy-file) "msa-space" (.getPath target-a)))
(def result-b (database/migrate-legacy-flat-log! (.getPath legacy-file) "msa-space" (.getPath target-b)))
(def deflate-result-a
  (database/migrate-legacy-flat-log! (.getPath legacy-file) "msa-space"
                                     (.getPath deflate-target-a) {:deflate? true}))
(def deflate-result-b
  (database/migrate-legacy-flat-log! (.getPath legacy-file) "msa-space"
                                     (.getPath deflate-target-b) {:deflate? true}))

(def bytes-a (java.nio.file.Files/readAllBytes (.toPath target-a)))
(def bytes-b (java.nio.file.Files/readAllBytes (.toPath target-b)))
(def deflate-bytes-a (java.nio.file.Files/readAllBytes (.toPath deflate-target-a)))
(def deflate-bytes-b (java.nio.file.Files/readAllBytes (.toPath deflate-target-b)))
(def manifest-a (slurp (str (.getPath target-a) ".migration.edn")))
(def manifest-b (slurp (str (.getPath target-b) ".migration.edn")))
(def deflate-manifest-a
  (slurp (str (.getPath deflate-target-a) ".migration.edn")))
(def deflate-manifest-b
  (slurp (str (.getPath deflate-target-b) ".migration.edn")))
(def decoded (decode-log target-a))
(def deflate-decoded (decode-log deflate-target-a))
(def runtime-a (database/open-database! (.getPath target-a) "msa-space"))
(def deflate-runtime-a
  (database/open-database! (.getPath deflate-target-a) "msa-space"))
(def tx5 (first (:frames decoded)))
(def tx5-ops (:operations tx5))

(check! "double migration is byte-identical" (java.util.Arrays/equals bytes-a bytes-b))
(check! "double migration manifest is byte-identical" (= manifest-a manifest-b))
(check! "double Deflate migration is byte-identical"
        (java.util.Arrays/equals deflate-bytes-a deflate-bytes-b))
(check! "double Deflate migration manifest is byte-identical"
        (= deflate-manifest-a deflate-manifest-b))
(check! "sealed migration refuses to overwrite an existing generation"
        (= :migration-target-exists
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath legacy-file)
                                             "msa-space" (.getPath target-a)))))
(check! "header is FRAMLOG v1 with immutable SpaceId"
        (and (= "FRAMLOG\u0000" (:magic decoded))
             (= 1 (:version decoded)) (= 0 (:flags decoded))
             (= "msa-space" (:space decoded))))
(check! "default migration manifest seals the uncompressed FRAMLOG encoding"
        (= {:encoding :uncompressed :framlog-flags 0 :framlog-version 1}
           (select-keys (:output (edn/read-string manifest-a))
                        [:encoding :framlog-flags :framlog-version])))
(check! "explicit Deflate migration seals FRAMLOG header flag 1"
        (and (= "FRAMLOG\u0000" (:magic deflate-decoded))
             (= 1 (:version deflate-decoded)) (= 1 (:flags deflate-decoded))
             (= "msa-space" (:space deflate-decoded))
             (= {:encoding :deflate :framlog-flags 1 :framlog-version 1}
                (select-keys (:output (edn/read-string deflate-manifest-a))
                             [:encoding :framlog-flags :framlog-version]))))
(check! "sealed migration output boots directly as authoritative TermStore history"
        (and (= (t/transaction-coordinate "msa-space" 6)
                (database/current-transaction runtime-a))
             (not (some #{(t/triple "Alice" "email" "alice@example.com")}
                        (database/live-propositions runtime-a)))
             (every? t/operation-occurrence?
                     (database/occurrences runtime-a))
             (every? t/withdrawal? (database/withdrawals runtime-a))))
(check! "Deflate migration boots with identical database state and history"
        (and (= (database/current-transaction runtime-a)
                (database/current-transaction deflate-runtime-a))
             (= (database/live-propositions runtime-a)
                (database/live-propositions deflate-runtime-a))
             (= (database/occurrences runtime-a)
                (database/occurrences deflate-runtime-a))
             (= (database/withdrawals runtime-a)
                (database/withdrawals deflate-runtime-a))))
(check! "Deflate migration result reports its sealed encoding"
        (= {:encoding :deflate :framlog-flags 1 :framlog-version 1}
           (select-keys (:output deflate-result-a)
                        [:encoding :framlog-flags :framlog-version])))
(check! "migration verifier accepts the exact Deflate output and manifest"
        (= (edn/read-string deflate-manifest-a)
           (database/verify-legacy-flat-log-migration!
            (.getPath deflate-target-a))))

(def cli-deflate-target (java.io.File. tmp-dir "cli-deflate.framlog"))
(def cli-deflate-process
  (.start
   (doto (ProcessBuilder.
          (into-array String ["bin/fram-migrate-triple-log" "--deflate"
                              (.getPath legacy-file) "msa-space"
                              (.getPath cli-deflate-target)]))
     (.directory (java.io.File. (System/getProperty "user.dir")))
     (.redirectErrorStream true))))
(def cli-deflate-output (slurp (.getInputStream cli-deflate-process)))
(def cli-deflate-exit (.waitFor cli-deflate-process))
(check! "--deflate CLI route creates and seals a flag-1 generation"
        (and (zero? cli-deflate-exit)
             (= 1 (:flags (decode-log cli-deflate-target)))
             (= :deflate
                (get-in (edn/read-string
                         (slurp (str (.getPath cli-deflate-target) ".migration.edn")))
                        [:output :encoding]))))

(def mismatched-flags-target (java.io.File. tmp-dir "mismatched-flags.framlog"))
(java.nio.file.Files/copy (.toPath deflate-target-a) (.toPath mismatched-flags-target)
                          (make-array java.nio.file.CopyOption 0))
(spit (str (.getPath mismatched-flags-target) ".migration.edn")
      (str (pr-str (assoc-in (edn/read-string deflate-manifest-a)
                             [:output :framlog-flags] 0)) "\n"))
(check! "migration verifier fails closed on an encoding/flags mismatch"
        (= :migration-seal-invalid
           (throwable-code
            #(database/verify-legacy-flat-log-migration!
              (.getPath mismatched-flags-target)))))

(def mismatched-hash-target (java.io.File. tmp-dir "mismatched-hash.framlog"))
(java.nio.file.Files/copy (.toPath deflate-target-a) (.toPath mismatched-hash-target)
                          (make-array java.nio.file.CopyOption 0))
(spit (str (.getPath mismatched-hash-target) ".migration.edn")
      (str (pr-str (assoc-in (edn/read-string deflate-manifest-a)
                             [:output :sha256] (apply str (repeat 64 "0")))) "\n"))
(check! "migration verifier fails closed on an output hash mismatch"
        (= :migration-seal-invalid
           (throwable-code
            #(database/verify-legacy-flat-log-migration!
              (.getPath mismatched-hash-target)))))
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
(check! "retraction row physically matches the active assertion proposition"
        (= (:triple (nth tx5-ops 1)) (:triple (nth tx5-ops 2))))
(check! "retraction occurrence withdraws the active assertion occurrence"
        (some #(and (= (t/occurrence-coordinate
                        (t/transaction-coordinate "msa-space" 5) 2)
                       (t/operationoccurrence-coordinate
                        (t/withdrawal-retraction %)))
                    (= (t/occurrence-coordinate
                        (t/transaction-coordinate "msa-space" 5) 1)
                       (t/operationoccurrence-coordinate
                        (t/withdrawal-assertion %))))
              (database/withdrawals runtime-a)))
(check! "parseable recorded-at is a typed Instant"
        (some #(= (java.time.Instant/parse "2026-08-01T10:31:22.123456789Z")
                  (nth (:triple %) 2 nil))
              tx5-ops))
(check! "unparseable recorded-at remains a String"
        (some #(= "legacy-local-no-zone" (nth (:triple %) 2 nil)) tx5-ops))

(def summary (:summary (edn/read-string manifest-a)))
(check! "manifest records source/synthetic/retraction counts and zero flat-log cids"
        (= {:assertions 2 :diagnostic-count 1 :legacy-cid-count 0 :noop-retractions 1
            :retractions 2 :source-operations 4 :synthetic-operations 7
            :targeted-retractions 1 :transactions 2
            :unparseable-recorded-at 1}
           summary))
(check! "manifest names the v2-only cid class without overlaying a cache"
        (= [{:class :cid-addressed-v2-only-data
             :disposition :not-migrated
             :reason "flat sources contain no cid field; v2/FRI caches are rejected as non-authoritative"}]
           (:unresolved-classes (edn/read-string manifest-a))))
(check! "new header reader returns SpaceId"
        (= "msa-space" (database/require-triple-log-header! (.getPath target-a))))
(check! "legacy runtime input is a typed migration requirement"
        (= :migration-required
           (throwable-code #(database/require-triple-log-header! (.getPath legacy-file)))))

;; Same tx numbers in another immutable space resolve to different coordinates.
(def target-other (java.io.File. tmp-dir "other.framlog"))
(database/migrate-legacy-flat-log! (.getPath legacy-file) "telemetry-space" (.getPath target-other))
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
  (database/migrate-legacy-flat-log! (.getPath torn-source) "torn-space" (.getPath torn-target)))
(check! "strictly later unterminated transaction is dropped and reported"
        (and (= {:line 2 :byte-offset (inc (count (.getBytes (pr-str (first rows)) "UTF-8")))
                 :bytes (count (.getBytes "{:tx 9, :op \"assert\", :l \"cut" "UTF-8"))
                 :transaction-sequence 9
                 :dropped-complete-rows 0
                 :reason :torn-later-transaction}
                (:torn-tail torn-result))
             (= [5] (mapv :tx (:frames (decode-log torn-target))))))

;; A completed prefix of the same final transaction is not independently durable.
(def same-tx-source (java.io.File. tmp-dir "same-tx-torn.log"))
(def same-tx-target (java.io.File. tmp-dir "same-tx-torn.framlog"))
(def tx4-row {:tx 4 :op "assert" :l "prior" :p "state" :r "safe"})
(def tx5-row-a {:tx 5 :op "assert" :l "Alice" :p "email" :r "first@example.com"})
(def tx5-row-b {:tx 5 :op "assert" :l "Alice" :p "email" :r "second@example.com"})
(spit same-tx-source
      (str (pr-str tx4-row) "\n"
           (pr-str tx5-row-a) "\n"
           (pr-str tx5-row-b) "\n"
           "{:tx 5, :op \"assert\", :l \"cut"))
(def same-tx-result
  (database/migrate-legacy-flat-log! (.getPath same-tx-source)
                                  "same-tx-space" (.getPath same-tx-target)))
(check! "torn same-transaction prefix drops every completed row of the final tx"
        (and (= [4] (mapv :tx (:frames (decode-log same-tx-target))))
             (= 5 (get-in same-tx-result [:torn-tail :transaction-sequence]))
             (= 2 (get-in same-tx-result [:torn-tail :dropped-complete-rows]))
             (= :torn-same-transaction
                (get-in same-tx-result [:torn-tail :reason]))))
(check! "manifest records the atomic torn-transaction decision"
        (= (:torn-tail same-tx-result)
           (:torn-tail
            (edn/read-string (slurp (str (.getPath same-tx-target)
                                         ".migration.edn"))))))

(def hidden-token-source (java.io.File. tmp-dir "hidden-token-torn.log"))
(def hidden-token-target (java.io.File. tmp-dir "hidden-token-torn.framlog"))
(spit hidden-token-source
      (str (pr-str tx5-row-a) "\n"
           "{:tx 6, :op \"assert\", :l \":tx is data\", :p \"cut"))
(def hidden-token-result
  (database/migrate-legacy-flat-log! (.getPath hidden-token-source)
                                  "hidden-token-space" (.getPath hidden-token-target)))
(check! "transaction-like text inside a torn String is not an ambiguous coordinate"
        (and (= [5] (mapv :tx (:frames (decode-log hidden-token-target))))
             (= :torn-later-transaction
                (get-in hidden-token-result [:torn-tail :reason]))))

(def ambiguous-tail-source (java.io.File. tmp-dir "ambiguous-tail.log"))
(def ambiguous-tail-target (java.io.File. tmp-dir "ambiguous-tail.framlog"))
(spit ambiguous-tail-source
      (str (pr-str tx5-row-a) "\n{:tx 6, :op \"assert\", :tx 7, :l \"cut"))
(check! "multiple coordinates in a torn tail fail typed without guessing"
        (= :migration-torn-transaction-ambiguous
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath ambiguous-tail-source)
                                             "ambiguous-space" (.getPath ambiguous-tail-target)))))

(def missing-tail-source (java.io.File. tmp-dir "missing-tail-tx.log"))
(def missing-tail-target (java.io.File. tmp-dir "missing-tail-tx.framlog"))
(spit missing-tail-source
      (str (pr-str tx5-row-a) "\n{:op \"assert\", :l \"cut"))
(check! "missing leading coordinate in a torn tail fails typed"
        (= :migration-torn-transaction-missing
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath missing-tail-source)
                                             "missing-space" (.getPath missing-tail-target)))))

(def partial-tail-source (java.io.File. tmp-dir "partial-tail-tx.log"))
(def partial-tail-target (java.io.File. tmp-dir "partial-tail-tx.framlog"))
(spit partial-tail-source (str (pr-str tx5-row-a) "\n{:tx 6"))
(check! "a cut transaction number fails typed as ambiguous"
        (= :migration-torn-transaction-ambiguous
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath partial-tail-source)
                                             "partial-space" (.getPath partial-tail-target)))))

(def backward-tail-source (java.io.File. tmp-dir "backward-tail.log"))
(def backward-tail-target (java.io.File. tmp-dir "backward-tail.framlog"))
(spit backward-tail-source
      (str (pr-str tx5-row-a) "\n{:tx 4, :op \"assert\", :l \"cut"))
(check! "backward torn transaction fails typed"
        (= :migration-nonmonotonic-torn-transaction
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath backward-tail-source)
                                             "backward-space" (.getPath backward-tail-target)))))

(def backward-complete-source (java.io.File. tmp-dir "backward-complete.log"))
(def backward-complete-target (java.io.File. tmp-dir "backward-complete.framlog"))
(spit backward-complete-source
      (str (pr-str tx5-row-a) "\n" (pr-str tx4-row) "\n"))
(check! "completed transaction rows must be contiguous and nondecreasing"
        (= :migration-nonmonotonic-transaction
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath backward-complete-source)
                                             "backward-complete-space"
                                             (.getPath backward-complete-target)))))

(def corrupt-source (java.io.File. tmp-dir "corrupt.log"))
(def corrupt-target (java.io.File. tmp-dir "corrupt.framlog"))
(spit corrupt-source (str (pr-str (first rows)) "\n{:not \"a fact op\"}\n"))
(check! "completed malformed interior input fails"
        (= :migration-malformed-interior
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath corrupt-source)
                                             "bad-space" (.getPath corrupt-target)))))

(def v2-source (java.io.File. tmp-dir "snapshot.v2log"))
(def v2-target (java.io.File. tmp-dir "snapshot.framlog"))
(spit v2-source (str (pr-str {:k :fact :cid 7 :l 1 :p 2 :r 3 :tx 4}) "\n"))
(check! "lossy v2 cache is rejected as a migration source"
        (= :migration-v2-cache-not-source
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath v2-source)
                                             "bad-space" (.getPath v2-target)))))

(def fri-source (java.io.File. tmp-dir "snapshot.fri"))
(def fri-target (java.io.File. tmp-dir "fri.framlog"))
(with-open [out (java.io.FileOutputStream. fri-source)]
  (.write out (.getBytes "FRAMFRI1cache"
                         java.nio.charset.StandardCharsets/UTF_8)))
(check! "FRI cache is rejected as a migration source"
        (= :migration-v2-cache-not-source
           (throwable-code
            #(database/migrate-legacy-flat-log! (.getPath fri-source)
                                             "bad-space" (.getPath fri-target)))))

;; Frozen FRAMLOG v1 on-disk golden; a writer change that moves these bytes is a
;; format break, not a refactor.
(def golden-triple (t/triple "Alice" :email "alice@example.com"))
(def golden-write
  ((deref #'database/write-triple-log-temp!)
   (.toPath tmp-dir) "msa-space"
   [{:tx-seq 1842
     :operations [{:ordinal 0 :action 1 :triple golden-triple}]}]))
(def golden-hex
  "4652414d4c4f470001000000090000006d73612d73706163653c0000003207000000000000010000000000000001070105000000416c6963650605000000656d61696c0111000000616c696365406578616d706c652e636f6dd42d3294")
(check! "JVM writer matches the FRAMLOG v1 golden bytes"
        (= golden-hex
           (hex (java.nio.file.Files/readAllBytes (:path golden-write)))))
(java.nio.file.Files/deleteIfExists (:path golden-write))

(def nested-triple
  (t/triple (t/triple "left" :edge "one")
            (t/triple "middle" :edge "two")
            (t/triple "right" :edge "three")))
(def nested-write
  ((deref #'database/write-triple-log-temp!)
   (.toPath tmp-dir) "recursive-space"
   [{:tx-seq 9
     :operations [{:ordinal 0 :action 1 :triple nested-triple}]}]))
(check! "recursive Triple terms round-trip in t1, t2, and t3"
        (= [[["left" :edge "one"]
             ["middle" :edge "two"]
             ["right" :edge "three"]]]
           (mapv :triple
                 (:operations (first (:frames (decode-log (:path nested-write))))))))
(java.nio.file.Files/deleteIfExists (:path nested-write))

(println "triple_log_migration_test: PASS")
