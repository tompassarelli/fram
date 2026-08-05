;; Atomic exact-ID existence + mutation regression.
;; Run: bb -cp out tests/commit_existing_write_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn client [port log request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader.
                      (io/reader (.getInputStream socket)))]
    (.write writer
            (str
             (pr-str
              {:op :for-log
               :expected-log (.getCanonicalPath (io/file log))
               :request request})
             "\n"))
    (.flush writer)
    (edn/read reader)))

(defn eventually [f]
  (loop [remaining 200]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-existing-write"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log
              (str
               (pr-str
                {:tx 1 :op "assert" :l "@existing"
                 :p "title" :r "existing" :by "fixture"})
               "\n"
               (pr-str
                {:tx 2 :op "assert" :l "@schema-only"
                 :p "cardinality" :r "single" :by "fixture"})
               "\n"
               (pr-str
                {:tx 3 :op "assert" :l "@target"
                 :p "title" :r "target" :by "fixture"})
               "\n"
               (pr-str
                {:tx 4 :op "assert" :l "@target-2"
                 :p "title" :r "target 2" :by "fixture"})
               "\n"
               (pr-str
                {:tx 5 :op "assert" :l "@existing"
                 :p "depends_on" :r "@target" :by "fixture"})
               "\n"))
      daemon
      (proc/process
       {:dir root :out :string :err :string}
       "bb" "-cp" "out" "server.clj" "serve-flat"
       (str port) (.getPath log))
      checks (atom [])
      check! (fn [label value]
               (swap! checks conj [label (boolean value)]))]
  (try
    (check! "real socket daemon starts"
            (eventually #(integer? (:version
                                   (client port log {:op :version})))))

    (let [before (:version (client port log {:op :version}))
          accepted
          (client port log
                  {:op :assert-existing
                   :te "@existing" :p "progress" :r "landed"})
          after (:version (client port log {:op :version}))]
      (check! "existing-subject assert commits"
              (and (:ok accepted) (> after before))))

    (let [before (:version (client port log {:op :version}))
          accepted
          (client port log
                  {:op :retract-existing
                   :te "@existing" :p "progress" :r "landed"})
          after (:version (client port log {:op :version}))]
      (check! "existing-subject retract commits"
              (and (:ok accepted) (> after before))))

    (let [accepted
          (client port log
                  {:op :assert-existing
                   :te "@schema-only" :p "note" :r "visible"})]
      (check! "schema-only show subject counts as existing"
              (:ok accepted)))

    (let [accepted
          (client port log
                  {:op :assert-existing
                   :te "@existing"
                   :p "depends_on"
                   :r "target-2"})
          rows (:rows (client port log {:op :show :te "@existing"}))]
      (check! "bare reference assert normalizes inside the atomic write"
              (and (:ok accepted)
                   (some #(= ["depends_on" "@target-2"] %) rows)
                   (not (some #(= ["depends_on" "target-2"] %) rows)))))

    (let [accepted
          (client port log
                  {:op :retract-existing
                   :te "@existing"
                   :p "depends_on"
                   :r "target-2"})
          rows (:rows (client port log {:op :show :te "@existing"}))]
      (check! "bare reference retract normalizes inside the atomic write"
              (and (:ok accepted)
                   (not (some #(= ["depends_on" "@target-2"] %) rows)))))

    (let [accepted
          (client port log
                  {:op :assert-existing
                   :te "@existing"
                   :p "depends_on"
                   :r "target with space"})
          rows (:rows (client port log {:op :show :te "@existing"}))]
      (check! "whitespace value stays literal on the exact-write path"
              (and (:ok accepted)
                   (some #(= ["depends_on" "target with space"] %) rows)
                   (not
                    (some #(= ["depends_on" "@target with space"] %) rows)))))

    (let [accepted
          (client port log
                  {:op :retract-existing
                   :te "@existing"
                   :p "depends_on"
                   :r "target with space"})
          rows (:rows (client port log {:op :show :te "@existing"}))]
      (check! "literal whitespace value retracts symmetrically"
              (and (:ok accepted)
                   (not
                    (some #(= ["depends_on" "target with space"] %) rows)))))

    (let [before (:version (client port log {:op :version}))
          before-bytes (slurp log)
          rejected
          (client port log
                  {:op :assert-existing
                   :te "@missing" :p "progress" :r "never"})
          after (:version (client port log {:op :version}))]
      (check! "missing-subject assert fails closed without version movement"
              (and (= :missing-subject (:code rejected))
                   (= :missing-subject (:reject rejected))
                   (= before after)
                   (= before-bytes (slurp log)))))

    (let [before (:version (client port log {:op :version}))
          rejected
          (client port log
                  {:op :retract-existing
                   :te "@missing" :p "progress" :r "never"})
          after (:version (client port log {:op :version}))]
      (check! "missing-subject retract fails closed without version movement"
              (and (= :missing-subject (:code rejected))
                   (= before after))))

    ;; Both requests carry ambiguous bare values. If the last public fact is
    ;; retracted concurrently with a guarded reference write, one dlock turn
    ;; admits only the two serialized outcomes: both commit when the guarded
    ;; write wins first, or the later guarded write rejects. Normalization can
    ;; never open a check/write gap that recreates the subject.
    (doseq [index (range 10)]
      (let [subject (str "@race-" index)
            title (str "race-" index)
            _ (client port log
                      {:op :assert :te subject :p "title" :r title})
            retract
            (future
              (client port log
                      {:op :retract-existing
                       :te subject :p "title" :r title}))
            guarded
            (future
              (client port log
                      {:op :assert-existing
                       :te subject :p "depends_on" :r "target"}))
            retract-result @retract
            guarded-result @guarded
            rows (:rows (client port log {:op :show :te subject}))
            ref-live? (some #(= ["depends_on" "@target"] %) rows)]
        (check!
         (str "bare-token assert/retract race " index
              " has no normalization/check/write gap")
         (and (:ok retract-result)
              (or (and (:ok guarded-result) ref-live?)
                  (and (= :missing-subject (:code guarded-result))
                       (not ref-live?)))))))

    (let [legacy
          (client port log
                  {:op :assert
                   :te "@legacy-new" :p "title" :r "still allowed"})]
      (check! "ordinary assert keeps create-new-subject semantics"
              (:ok legacy)))

    (finally
      (proc/destroy-tree daemon)
      (try @daemon (catch Exception _ nil))
      (doseq [[label ok?] @checks]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [failed (remove second @checks)]
        (println
         (format "\n%d/%d passed"
                 (- (count @checks) (count failed))
                 (count @checks)))
        (when (seq failed) (System/exit 1))))))
