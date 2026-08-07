;; Fork seals the parent tail into the shared chain and hands both branches a
;; fresh tail: no triple is copied, both branches see the pre-fork history, and
;; neither branch's later writes are visible to the other.
;; Run from the repository root: bb -cp out tests/framlog_fork_test.clj
(require '[fram.branch :as branch]
         '[fram.store :as store]
         '[fram.types :as t])

(load-file "database.clj")

(def checks (atom []))
(defn check! [label ok]
  (println (str (if ok "  [PASS] " "  [FAIL] ") label))
  (swap! checks conj [label ok]))

(defn error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (or (:fram/code (ex-data error)) (:type (ex-data error))))))

(def scratch
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-framlog-fork-"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- store-path [name] (.getPath (java.io.File. scratch name)))

(defn- read-all ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (java.io.File. (str path)))))

(defn- sha256-hex [^bytes content]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           content))))

(defn- image [db] (store/dump-term-store (database/database-store db)))

(defn- live? [db proposition]
  (boolean (some #{proposition} (database/live-propositions db))))

(def space "framlog-fork-space")

;; ---------------------------------------------------------------- fork at S
(def log (store-path "framlog"))
(database/create-triple-log! log space)
(def origin (database/open-database! log space))
(database/assert! origin (t/triple "Alice" :email "alice@example.com") {})
(database/commit! origin
                  {:actor "batcher"
                   :operations
                   [{:action :assert :proposition (t/triple "A" :count 1)}
                    {:action :assert :proposition (t/triple "B" :count 2)}]})
(database/assert! origin (t/triple "Task" :status "draft") {})
(def pre-fork-image (image origin))
(def pre-fork-bytes (read-all log))
(def pre-fork-sequence
  (:tx-seq (last (:frames (database/read-triple-log! log)))))

;; A derived image beside the parent tail is stale the moment the tail is
;; sealed; the fork must remove it rather than let a later boot install it.
(def stale-image (branch/snapshot-path log))
(java.nio.file.Files/write
 (.toPath (java.io.File. stale-image)) (byte-array 8)
 (into-array java.nio.file.OpenOption
             [java.nio.file.StandardOpenOption/CREATE
              java.nio.file.StandardOpenOption/WRITE]))

(def receipt (database/fork-store! log "lane"))
(def sealed-path (branch/segment-path log (:segment receipt)))

(println "FRAMLOG fork:")
(println (str "  forked at sequence " (:fork-sequence receipt)
              " into " (count (:chain receipt)) " sealed segment(s)"))

(check! "fork reports the parent's last committed sequence"
        (= pre-fork-sequence (:fork-sequence receipt)))
(check! "the sealed segment is the parent tail's exact bytes"
        (java.util.Arrays/equals pre-fork-bytes (read-all sealed-path)))
(check! "the sealed segment is named by the SHA-256 of those bytes"
        (= (sha256-hex pre-fork-bytes)
           (.getName (java.io.File. sealed-path))))
(check! "fork deletes the stale parent image"
        (not (.exists (java.io.File. stale-image))))

(def parent-ref (database/read-branch-ref log branch/default-branch))
(def child-ref (database/read-branch-ref log "lane"))

(check! "parent and child refs name the identical sealed chain"
        (and (= parent-ref child-ref)
             (= [(:segment receipt)]
                (mapv branch/segmentrecord-sha256
                      (branch/refdocument-segments parent-ref)))))
(check! "the sealed record carries the segment's start sequence and size"
        (let [record (first (branch/refdocument-segments parent-ref))]
          (and (= 1 (branch/segmentrecord-start-sequence record))
               (= (alength pre-fork-bytes)
                  (branch/segmentrecord-byte-count record)))))

(def parent-tail (branch/branch-tail-path log branch/default-branch))
(def child-tail (branch/branch-tail-path log "lane"))

(check! "both branches get a fresh tail carrying the continuation flag"
        (every? (fn [path]
                  (let [parsed (database/read-triple-log! path true)]
                    (and (:continuation? parsed)
                         (empty? (:frames parsed))
                         (= space (:space-id parsed)))))
                [parent-tail child-tail]))
(check! "a continuation tail opened without its ref is refused"
        (= [:unsupported-log-version :unsupported-log-version]
           [(error-code #(database/open-database! parent-tail space))
            (error-code #(database/open-database! child-tail space))]))

(def parent (database/open-branch! log branch/default-branch space))
(def child (database/open-branch! log "lane" space))

(check! "both branches fold to the exact pre-fork image"
        (= [pre-fork-image pre-fork-image] [(image parent) (image child)]))

(database/assert! parent (t/triple "parent" :wrote 1) {})
(database/assert! child (t/triple "child" :wrote 1) {})

(check! "each branch's first append lands at the fork sequence plus one"
        (= [(inc pre-fork-sequence) (inc pre-fork-sequence)]
           [(:tx-seq (first (:frames (database/read-triple-log! parent-tail true))))
            (:tx-seq (first (:frames (database/read-triple-log! child-tail true))))]))

(def parent-cold (database/open-branch! log branch/default-branch space))
(def child-cold (database/open-branch! log "lane" space))

(check! "a parent append is invisible to the child"
        (and (live? parent-cold (t/triple "parent" :wrote 1))
             (not (live? child-cold (t/triple "parent" :wrote 1)))))
(check! "a child append is invisible to the parent"
        (and (live? child-cold (t/triple "child" :wrote 1))
             (not (live? parent-cold (t/triple "child" :wrote 1)))))
(check! "both branches still carry the whole pre-fork history"
        (every? (fn [db]
                  (and (live? db (t/triple "Alice" :email "alice@example.com"))
                       (live? db (t/triple "A" :count 1))
                       (live? db (t/triple "Task" :status "draft"))))
                [parent-cold child-cold]))
(check! "the sealed segment is shared, not copied per branch"
        (= 1 (count (.listFiles
                     (java.io.File. (branch/segments-directory log))))))

;; ------------------------------------------------------------- fork of fork
(def grand (database/fork-store! log "lane" "lane-2"))
(check! "a fork of a fork extends the chain by exactly one segment"
        (= 2 (count (:chain grand))))
(check! "the child chain keeps the parent chain as its prefix"
        (= (:segment receipt) (first (:chain grand))))
(def grand-db (database/open-branch! log "lane-2" space))
(check! "the grandchild folds both sealed segments and sees the child's write"
        (and (live? grand-db (t/triple "child" :wrote 1))
             (live? grand-db (t/triple "Alice" :email "alice@example.com"))
             (not (live? grand-db (t/triple "parent" :wrote 1)))))
(check! "the grandchild's base segment stays the only non-continuation member"
        (let [chain (branch/refdocument-segments
                     (database/read-branch-ref log "lane-2"))]
          (= [false true]
             (mapv (fn [record]
                     (boolean
                      (:continuation?
                       (database/read-triple-log!
                        (branch/segment-path
                         log (branch/segmentrecord-sha256 record))
                        true))))
                   chain))))

;; ---------------------------------------------------------- fork of nothing
(def empty-log (store-path "empty.framlog"))
(database/create-triple-log! empty-log space)
(def empty-receipt (database/fork-store! empty-log "lane"))
(check! "forking an empty store reports sequence zero"
        (zero? (:fork-sequence empty-receipt)))
(check! "an empty store's sealed segment records no start sequence"
        (zero? (branch/segmentrecord-start-sequence
                (first (branch/refdocument-segments
                        (database/read-branch-ref empty-log "lane"))))))
(def empty-child (database/open-branch! empty-log "lane" space))
(database/assert! empty-child (t/triple "first" :after "fork") {})
(check! "a branch of an empty store begins at sequence one"
        (= 1 (:tx-seq (first (:frames
                              (database/read-triple-log!
                               (branch/branch-tail-path empty-log "lane")
                               true))))))
(check! "a branch of an empty store cold-replays its first write"
        (live? (database/open-branch! empty-log "lane" space)
               (t/triple "first" :after "fork")))

;; ------------------------------------------------------------ fork refusals
(check! "fork refuses a child branch that already exists"
        (= :branch-exists (error-code #(database/fork-store! log "lane"))))
(check! "fork refuses a parent and child with the same name"
        (= :invalid-branch-name
           (error-code #(database/fork-store! log "lane" "lane"))))
(check! "fork refuses a branch name that cannot address a ref file"
        (= :invalid-branch-name
           (error-code #(database/fork-store! log "../escape"))))
(check! "fork leaves the store untouched when it refuses"
        (= 2 (count (.listFiles
                     (java.io.File. (branch/segments-directory log))))))

(def torn-log (store-path "torn.framlog"))
(database/create-triple-log! torn-log space)
(def torn-db (database/open-database! torn-log space))
(database/assert! torn-db (t/triple "torn" :n 1) {})
(let [content (read-all torn-log)]
  (java.nio.file.Files/write
   (.toPath (java.io.File. torn-log))
   (java.util.Arrays/copyOfRange content 0 (dec (alength content)))
   (into-array java.nio.file.OpenOption
               [java.nio.file.StandardOpenOption/CREATE
                java.nio.file.StandardOpenOption/WRITE
                java.nio.file.StandardOpenOption/TRUNCATE_EXISTING])))
(check! "fork refuses a parent tail with a torn trailing frame"
        (= :torn-tail-repair-required
           (error-code #(database/fork-store! torn-log "lane"))))
(check! "a refused fork never seals the torn tail"
        (not (.exists (java.io.File. (branch/segments-directory torn-log)))))

(let [failures (remove second @checks)]
  (if (empty? failures)
    (do
      (println "\nFRAMLOG fork:" (count @checks) "/" (count @checks) "PASS")
      (shutdown-agents))
    (do
      (println "\nFRAMLOG fork:" (count failures) "FAILED")
      (shutdown-agents)
      (System/exit 1))))
