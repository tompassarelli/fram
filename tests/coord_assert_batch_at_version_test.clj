;; coord_assert_batch_at_version_test.clj — real-socket receipt for the atomic
;; global-version batch commit seam.
;;
;; Run: bb -cp out tests/coord_assert_batch_at_version_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])
(load-file "tests/log_split_readiness_lib.clj")

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn values-of [port subject predicate]
  (set (:values (client port {:op :resolved :te subject :p predicate}))))

(defn version-ready? [port]
  (try
    (integer? (:version (client port {:op :version})))
    (catch Exception _ false)))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-assert-batch-at-version"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log "")
      daemon
      (proc/process
       {:dir root :out :string :err :string
        :extra-env {"FRAM_SNAPSHOT_BOOT" "0"}}
       "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
       (str port) (.getPath log))
      checks (atom [])
      check! (fn [label value]
               (swap! checks conj [label (boolean value)]))]
  (try
    (check! "real socket daemon starts"
            (= :ready
               (await-ready daemon port version-ready?
                            :deadline-ms 120000 :poll-ms 50)))

    (let [base (:version (client port {:op :version}))
          result
          (client port {:op :assert-batch-at-version
                        :te "@current"
                        :facts [{:p "left" :r "L"}
                                {:p "right" :r "R"}]
                        :base base})]
      (check! "current global base commits the batch"
              (and (integer? (:ok result))
                   (:batch result)))
      (check! "current global base commits every supplied fact"
              (and (= #{"L"} (values-of port "@current" "left"))
                   (= #{"R"} (values-of port "@current" "right")))))

    (let [base (:version (client port {:op :version}))
          intervening
          (client port {:op :assert
                        :te "@other" :p "moved" :r "head"})
          result
          (client port {:op :assert-batch-at-version
                        :te "@stale"
                        :facts [{:p "left" :r "must-not-land"}
                                {:p "right" :r "must-not-land"}]
                        :base base})]
      (check! "intervening write advances the global head" (:ok intervening))
      (check! "stale global base rejects"
              (= :conflict (:reject result)))
      (check! "stale global base leaves the target unchanged"
              (and (empty? (values-of port "@stale" "left"))
                   (empty? (values-of port "@stale" "right")))))

    (let [base (:version (client port {:op :version}))
          result
          (client port {:op :assert-batch-at-version
                        :te "@fact-local-base"
                        :facts [{:p "left" :r "must-not-land" :base base}
                                {:p "right" :r "must-not-land"}]
                        :base base})]
      (check! "fact-local base rejects"
              (= :fact-local-base (:code result)))
      (check! "fact-local base rejection leaves the target unchanged"
              (and (empty? (values-of port "@fact-local-base" "left"))
                   (empty? (values-of port "@fact-local-base" "right")))))

    (finally
      (proc/destroy-tree daemon)
      (try @daemon (catch Exception _ nil))
      (doseq [[label ok?] @checks]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [failed (remove second @checks)]
        (println (format "\n%d/%d passed"
                         (- (count @checks) (count failed))
                         (count @checks)))
        (when (seq failed) (System/exit 1))))))
