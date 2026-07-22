;; Real split-daemon proof for physical torn-tail recovery. A logical fold that
;; merely drops an incomplete tail is insufficient: retry must never concatenate
;; onto those bytes. Boot repairs both logs under the exclusive rewrite lock,
;; and live mutation refuses a newly introduced non-LF boundary before changing
;; the in-memory store.
;;
;; Run: bb -cp out tests/coord_torn_tail_repair_test.clj
(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[fram.rt :as rt])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def scratch (str (System/getProperty "java.io.tmpdir")
                  "/fram-torn-tail-repair-" (System/nanoTime)))
(def coordination (str scratch "/coordination.log"))
(def telemetry (str scratch "/telemetry.log"))
(def running (atom nil))
(def checks (atom []))

(defn check! [label value]
  (let [ok? (boolean value)]
    (swap! checks conj [label ok?])
    (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label))
    ok?))

(defn bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))

(defn bytes= [left right]
  (java.util.Arrays/equals ^bytes left ^bytes right))

(defn terminal-lf? [path]
  (let [payload (bytes path)]
    (or (zero? (alength payload))
        (= 10 (bit-and 0xff (aget payload (dec (alength payload))))))))

(defn write-raw! [path content append?]
  (with-open [output (java.io.FileOutputStream. (str path) append?)]
    (.write output (.getBytes ^String content "UTF-8"))
    (.flush output)
    (.force (.getChannel output) true)))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn ready? [port]
  (try
    (integer? (:version (client port {:op :version})))
    (catch Exception _ false)))

(defn eventually [f]
  (loop [remaining 240]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn start-daemon! []
  (let [port (free-port)
        child (proc/process
               ["bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
                (str port) coordination]
               {:dir root
                :extra-env {"FRAM_TELEMETRY_LOG" telemetry}
                :out :string :err :string})]
    (reset! running {:child child :port port})
    {:child child :port port}))

(defn await-daemon! [port]
  (when-not (eventually #(ready? port))
    (throw (ex-info "split daemon did not become ready" {:port port})))
  port)

(defn stop-daemon! []
  (when-let [{:keys [child]} @running]
    (proc/destroy-tree child)
    (try @child (catch Exception _ nil))
    (reset! running nil)))

(defn values-of [port subject predicate]
  (set (:values (client port {:op :resolved :te subject :p predicate}))))

(defn flat-line [tx subject predicate value]
  (str (pr-str {:tx tx :op "assert" :l subject :p predicate
                :r value :frame "torn-tail-test"}) "\n"))

(fs/create-dirs scratch)
(def coordination-prefix
  (str (flat-line 1 "@lease" "cardinality" "single")
       (flat-line 2 "@lease" "value_kind" "literal")
       (flat-line 3 "@coord-seed" "title" "keep-coordination")))
(def telemetry-prefix
  (str (flat-line 4 "@run-seed" "kind" "run")
       (flat-line 5 "@run-seed" "note" "keep-telemetry")))
(def coordination-torn
  "{:tx 6, :op \"assert\", :l \"@lease:corpus\", :p \"lease\", :r \"owner|999")
(def telemetry-torn
  "{:tx 7, :op \"assert\", :l \"@run-torn\", :p \"note\", :r")
(write-raw! coordination (str coordination-prefix coordination-torn) false)
(write-raw! telemetry (str telemetry-prefix telemetry-torn) false)

(try
  ;; A shared admission holder must block boot: repair is a real exclusive
  ;; rewrite, not an unlocked truncate racing another supported appender.
  (let [coord-before (bytes coordination)
        telemetry-before (bytes telemetry)
        shared-gate (rt/acquire-rewrite-lock! coordination true true)
        {:keys [port]} (start-daemon!)]
    (try
      (Thread/sleep 250)
      (check! "exclusive repair waits behind an existing shared append admission"
              (and (not (ready? port))
                   (bytes= coord-before (bytes coordination))
                   (bytes= telemetry-before (bytes telemetry))))
      (finally (rt/close-rewrite-lock! shared-gate)))
    (await-daemon! port)
    (check! "coordination torn tail truncates to the exact complete prefix"
            (= coordination-prefix (slurp coordination)))
    (check! "telemetry torn tail truncates to the exact complete prefix"
            (= telemetry-prefix (slurp telemetry)))
    (check! "both repaired split logs end at a durable append boundary"
            (and (terminal-lf? coordination) (terminal-lf? telemetry)))
    (check! "all complete pre-crash facts remain queryable"
            (and (= #{"keep-coordination"}
                    (values-of port "@coord-seed" "title"))
                 (= #{"keep-telemetry"}
                    (values-of port "@run-seed" "note"))))

    (let [coord-retry (client port {:op :assert :te "@coord-retry"
                                    :p "note" :r "coordination-retry"})
          telemetry-retry (client port {:op :assert :te "@run-seed"
                                        :p "note" :r "telemetry-retry"})]
      (check! "retry appends succeed after physical repair"
              (and (:ok coord-retry) (:ok telemetry-retry)
                   (terminal-lf? coordination) (terminal-lf? telemetry)
                   (= #{"coordination-retry"}
                      (values-of port "@coord-retry" "note"))
                   (= #{"keep-telemetry" "telemetry-retry"}
                      (values-of port "@run-seed" "note")))))

    ;; A new unsupported/out-of-band torn tail while serving must NACK before
    ;; mutating memory or appending to either half of the split corpus.
    (let [coord-before-refusal (bytes coordination)
          telemetry-clean (bytes telemetry)
          live-torn "{:tx 90, :op \"assert\", :l \"@live-torn\", :p \"note\""
          _ (write-raw! telemetry live-torn true)
          telemetry-with-torn (bytes telemetry)
          refused (client port {:op :assert :te "@must-not-land"
                                :p "note" :r "refused-before-memory"})]
      (check! "live mutation refuses either split log at a non-LF boundary"
              (and (:error refused)
                   (bytes= coord-before-refusal (bytes coordination))
                   (bytes= telemetry-with-torn (bytes telemetry))
                   (empty? (values-of port "@must-not-land" "note"))))
      (stop-daemon!)
      (let [{:keys [port]} (start-daemon!)]
        (await-daemon! port)
        (check! "restart removes the refused telemetry tail without valid-line loss"
                (and (bytes= telemetry-clean (bytes telemetry))
                     (= #{"keep-telemetry" "telemetry-retry"}
                        (values-of port "@run-seed" "note"))))
        (check! "the refused mutation is retryable after restart repair"
                (:ok (client port {:op :assert :te "@must-not-land"
                                   :p "note" :r "landed-after-repair"}))))))

    ;; Syntactically and fold-semantically complete records are not discarded
    ;; just because the final LF was lost: boot adds and forces that LF.
    (stop-daemon!)
    (let [coord-stable (bytes coordination)
          telemetry-stable (bytes telemetry)
          complete-coord (pr-str {:tx 100 :op "assert" :l "@complete-coord"
                                  :p "note" :r "preserve-complete-coord"
                                  :frame "torn-tail-test"})
          complete-telemetry
          (pr-str {:tx 101 :op "assert" :l "@complete-telemetry"
                   :p "kind" :r "run" :frame "torn-tail-test"})
          _ (write-raw! coordination complete-coord true)
          _ (write-raw! telemetry complete-telemetry true)
          {:keys [port]} (start-daemon!)]
      (await-daemon! port)
      (check! "complete unterminated coordination record is preserved"
              (and (= (str (String. coord-stable "UTF-8") complete-coord "\n")
                      (slurp coordination))
                   (= #{"preserve-complete-coord"}
                      (values-of port "@complete-coord" "note"))))
      (check! "complete unterminated telemetry record is preserved"
              (and (= (str (String. telemetry-stable "UTF-8") complete-telemetry "\n")
                      (slurp telemetry))
                   (= #{"run"}
                      (values-of port "@complete-telemetry" "kind"))))

      ;; edn/read-string accepts a valid first form while silently ignoring a
      ;; trailing form. Recovery must be stricter: a complete map concatenated
      ;; with the next torn retry is not one valid flat record, so the entire
      ;; unacknowledged segment is removed at its prior LF boundary.
      (stop-daemon!)
      (let [stable-before-concatenation (bytes coordination)
            concatenated
            (str (pr-str {:tx 102 :op "assert" :l "@must-not-preserve"
                          :p "note" :r "first-form-only"})
                 "{:tx 103, :op \"assert\"")
            _ (write-raw! coordination concatenated true)
            {:keys [port]} (start-daemon!)]
        (await-daemon! port)
        (check! "concatenated EDN forms are truncated, never blessed as one record"
                (and (bytes= stable-before-concatenation (bytes coordination))
                     (empty? (values-of port "@must-not-preserve" "note")))))

      ;; Clean restarts are byte-stable and do not re-run a repair or lose any
      ;; valid line from either corpus.
      (let [stable-coordination (bytes coordination)
            stable-telemetry (bytes telemetry)]
        (dotimes [_ 2]
          (stop-daemon!)
          (let [{:keys [port]} (start-daemon!)]
            (await-daemon! port)
            (check! "repeated clean restart leaves both corpus bytes unchanged"
                    (and (bytes= stable-coordination (bytes coordination))
                         (bytes= stable-telemetry (bytes telemetry))))
            (check! "repeated clean restart retains every repaired/retried fact"
                    (and (= #{"keep-coordination"}
                            (values-of port "@coord-seed" "title"))
                         (= #{"landed-after-repair"}
                            (values-of port "@must-not-land" "note"))
                         (= #{"preserve-complete-coord"}
                            (values-of port "@complete-coord" "note"))
                         (= #{"run"}
                            (values-of port "@complete-telemetry" "kind"))))))))
  (finally
    (stop-daemon!)
    (fs/delete-tree scratch)))

(let [failed (remove second @checks)]
  (println (format "\ncoord torn-tail repair: %d/%d passed"
                   (- (count @checks) (count failed)) (count @checks)))
  (when (seq failed) (System/exit 1)))
