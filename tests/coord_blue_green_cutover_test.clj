;; coord_blue_green_cutover_test.clj — steady-state authority handoff contract.
(require '[babashka.process :as process]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(load-file "coord_daemon.clj")

(def failures (atom []))

(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok
    (swap! failures conj label)))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn request! [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket
              (java.net.InetSocketAddress. "127.0.0.1" (int port))
              500)
    (.setSoTimeout socket 5000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader.
                        (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer)
      (edn/read reader))))

(defn fenced [log request]
  {:op :for-log
   :expected-log (.getCanonicalPath (io/file log))
   :request request})

(defn eventually [f]
  (loop [attempt 0]
    (let [value (try (f) (catch Throwable _ nil))]
      (cond
        value value
        (>= attempt 240) nil
        :else (do (Thread/sleep 25) (recur (inc attempt)))))))

(defn start-daemon
  ([port log role token]
   (start-daemon port log role token {}))
  ([port log role token extra-env]
   (process/process
    ["bb" "-cp" "out:." "coord_daemon.clj" "serve-flat"
     (str port) (str log)]
    {:out :string
     :err :string
     :extra-env
     (merge
      {"FRAM_COORD_ROLE" role
       "FRAM_CUTOVER_TOKEN" token
       "FRAM_REQUIRE_LOG_FENCE" "1"
       "FRAM_SNAPSHOT_BOOT" "0"
       "FRAM_CUTOVER_DRAIN_TIMEOUT_MS" "2000"}
      extra-env)})))

(defn stop-daemon! [proc]
  (when proc
    (process/destroy-tree proc)
    @proc))

(defn cutover-status [port log token]
  (request! port
            (fenced log {:op :cutover-status :token token})))

(defn cutover-cli! [& args]
  (let [result
        @(process/process
          (into ["bb" "bin/fram-cutover"] args)
          {:out :string :err :string})
        stdout (str/trim (:out result))]
    {:exit (:exit result)
     :stderr (:err result)
     :response (when-not (str/blank? stdout)
                 (edn/read-string stdout))}))

(defn canonical-values [log]
  (with-open [reader (io/reader log)]
    (->> (line-seq reader)
         (keep (fn [line]
                 (try
                   (let [record (edn/read-string line)]
                     (when (and (= "@blue-green-cutover" (:l record))
                                (= "note" (:p record)))
                       (:r record)))
                   (catch Throwable _ nil))))
         vec)))

(defn byte-prefix? [before after]
  (and (<= (alength before) (alength after))
       (java.util.Arrays/equals
        before
        (java.util.Arrays/copyOf after (alength before)))))

;; The demotion response is itself the observable query-drain completion proof.
(reset! coord-daemon/active-queries 1)
(reset! coord-daemon/active-reloads 0)
(reset! coord-daemon/active-snapshot-builds 0)
(let [release (future
                (Thread/sleep 80)
                (reset! coord-daemon/active-queries 0))
      started (System/nanoTime)
      drain (with-redefs [coord-daemon/cutover-drain-timeout-ms 1000]
              (coord-daemon/await-cutover-drain!))
      elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
  @release
  (check! "cutover drain waits for an admitted active query"
          (and (:complete drain)
               (zero? (:queries drain))
               (>= elapsed-ms 50))))

(reset! coord-daemon/active-queries 1)
(let [timeout-code
      (with-redefs [coord-daemon/cutover-drain-timeout-ms 20]
        (try
          (coord-daemon/await-cutover-drain!)
          nil
          (catch clojure.lang.ExceptionInfo t
            (:code (ex-data t)))))]
  (reset! coord-daemon/active-queries 0)
  (check! "cutover drain timeout is explicit and fail-closed"
          (= :cutover-drain-timeout timeout-code)))

(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-blue-green-cutover"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      ;; `coordination.log` intentionally activates the production split pair;
      ;; this focused single-origin proof uses a neutral filename.
      log (str (io/file dir "facts.log"))
      token "blue-green-test-token"
      active-port (free-port)
      standby-port (free-port)
      token-file (str (io/file dir "cutover.token"))
      marker-file (str (io/file dir "cutover.marker.edn"))
      active (atom nil)
      standby (atom nil)]
  (spit log "")
  (spit token-file (str token "\n"))
  (java.nio.file.Files/setPosixFilePermissions
   (.toPath (io/file token-file))
   #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
     java.nio.file.attribute.PosixFilePermission/OWNER_WRITE})
  (try
    (reset! active (start-daemon active-port log "active" token))
    (let [active-status
          (eventually
           #(let [status (cutover-status active-port log token)]
              (when (and (:ok status)
                         (= :active (:phase status))
                         (get-in status
                                 [:writer-authority :write-authorized]))
                status)))]
      (check! "active generation owns writer authority"
              (some? active-status))
      (check! "cutover control rejects an invalid token"
              (= :cutover-unauthorized
                 (:code
                  (cutover-status active-port log "wrong-token"))))
      (check! "demotion is fenced to the observed boot identity"
              (let [response
                    (request!
                     active-port
                     (fenced
                      log
                      {:op :cutover-demote
                       :token token
                       :cutover-id "wrong-instance"
                       :expected-instance "boot-not-this-generation"}))]
                (and (= :cutover-instance-mismatch (:code response))
                     (get-in
                      (cutover-status active-port log token)
                      [:writer-authority :write-authorized]))))
      (reset! standby
              (start-daemon
               standby-port log "standby" token
               {"FRAM_TEST_CUTOVER_PREPARE_CACHE_DELAY_MS" "300"}))
      (let [standby-status
            (eventually
             #(let [status (cutover-status standby-port log token)]
                (when (and (:ok status)
                           (= :standby (:phase status))
                           (false?
                            (get-in status
                                    [:writer-authority
                                     :write-authorized])))
                  status)))]
        (check! "standby generation is warm and read-only"
                (some? standby-status))
        (let [written (atom [])
              write! (fn [port value]
                       (let [response
                             (request!
                              port
                              (fenced
                               log
                               {:op :assert
                                :te "@blue-green-cutover"
                                :p "note"
                                :r value}))]
                         (when (:ok response)
                           (swap! written conj value))
                         response))
              before (write! active-port "before-cutover")
              cutover-1 "cutover-1"
              prepare-start (System/nanoTime)
              prepare-task
              (future
                (cutover-cli!
                 "prepare"
                 "--port" (str standby-port)
                 "--log" log
                 "--token-file" token-file
                 "--cutover-id" cutover-1))
              _prepare-delay (Thread/sleep 75)
              during-start (System/nanoTime)
              during-prepare (write! active-port "during-prepare")
              during-ms
              (quot (- (System/nanoTime) during-start) 1000000)
              standby-during
              (write! standby-port "standby-prepare-must-refuse")
              prepare-run @prepare-task
              prepared (:response prepare-run)
              prepare-ms
              (quot (- (System/nanoTime) prepare-start) 1000000)
              prefix-before
              (java.nio.file.Files/readAllBytes (.toPath (io/file log)))
              external-tx
              (inc
               (long
                (max (or (:version prepared) 0)
                     (or (:version
                          (cutover-status active-port log token))
                         0))))
              external-value "external-tail-after-prepare"
              _external-append
              (do
                (spit
                 log
                 (str
                  (pr-str
                   {:tx external-tx
                    :op "assert"
                    :l "@blue-green-cutover"
                    :p "note"
                    :r external-value
                    :ts "external"
                    :by "external"})
                  "\n")
                 :append true)
                (swap! written conj external-value))
              prefix-after
              (java.nio.file.Files/readAllBytes (.toPath (io/file log)))
              stale-active-version
              (:version (cutover-status active-port log token))
              pause-start (System/nanoTime)
              demote-run
              (cutover-cli!
               "demote"
               "--port" (str active-port)
               "--log" log
               "--token-file" token-file
               "--cutover-id" cutover-1
               "--expected-instance" (:instance active-status)
               "--marker-out" marker-file)
              demoted (:response demote-run)
              marker-1 (:marker demoted)
              promote-run
              (cutover-cli!
               "promote"
               "--port" (str standby-port)
               "--log" log
               "--token-file" token-file
               "--cutover-id" cutover-1
               "--marker-file" marker-file)
              promoted (:response promote-run)
              pause-ms
              (quot (- (System/nanoTime) pause-start) 1000000)]
          (check! "pre-cutover write is acknowledged"
                  (:ok before))
          (check! "standby prepare is wired through the operator CLI"
                  (and (zero? (:exit prepare-run))
                       (:ok prepared)
                       (:prepared prepared)
                       (= :standby (:phase prepared))
                       (= cutover-1 (:cutover-id prepared))))
          (check! "prepare absorbs the deliberately delayed cache build before handoff"
                  (and (>= prepare-ms 250)
                       (>= (get-in prepared [:sync :elapsed-ms]) 250)))
          (check! "old active stays writable while standby prepare is slow"
                  (and (:ok during-prepare)
                       (< during-ms 250)))
          (check! "prepare never grants standby writer authority"
                  (and (= :writer-authority-required
                          (:code standby-during))
                       (false?
                        (get-in prepared
                                [:writer-authority
                                 :write-authorized]))))
          (check! "external append preserves the previously installed physical prefix"
                  (byte-prefix? prefix-before prefix-after))
          (check! "external append leaves the active logical version stale before demotion"
                  (< (long stale-active-version) external-tx))
          (check! "demotion returns a drained exact marker after releasing authority"
                  (and (zero? (:exit demote-run))
                       (:ok demoted)
                       (= :demoted (:phase demoted))
                       (true? (get-in demoted [:drain :complete]))
                       (map? marker-1)
                       (= cutover-1 (:cutover-id marker-1))
                       (= external-tx (:version marker-1))
                       (pos? (get-in demoted [:sync :attempts]))
                       (false?
                        (get-in demoted
                                [:writer-authority :write-authorized]))))
          (check! "prepared final-tail demote+promote pause stays within 500ms"
                  (and (zero? (:exit promote-run))
                       (:ok promoted)
                       (<= pause-ms 500)))
          (println
           (str "[INFO] cutover prepare=" prepare-ms
                "ms; old-active write during prepare=" during-ms
                "ms; final demote+promote pause=" pause-ms "ms"))
          (check! "operator CLI persists the exact demotion marker atomically"
                  (= marker-1
                     (edn/read-string (slurp marker-file))))
          (check! "retired predecessor refuses canonical writes"
                  (= :writer-authority-required
                     (:code (write! active-port "retired-must-refuse"))))
          (let [
                promoted-retry
                (request!
                 standby-port
                 (fenced
                  log
                  {:op :cutover-promote
                   :token token
                   :cutover-id cutover-1
                   :marker marker-1}))]
            (check! "standby acquires authority only after exact-tail match"
                    (and (zero? (:exit promote-run))
                         (:ok promoted)
                         (= :active (:phase promoted))
                         (= (:version marker-1) (:version promoted))
                         (get-in promoted
                                 [:writer-authority :write-authorized])))
            (check! "lost promotion acknowledgement is idempotently retryable"
                    (and (:ok promoted-retry)
                         (:idempotent promoted-retry)))
            (check! "promoted generation acknowledges the next write"
                    (:ok (write! standby-port "after-promotion")))
            (check! "retired predecessor cannot create split brain"
                    (= :cutover-authority-held
                       (:code
                        (request!
                         active-port
                         (fenced
                          log
                          {:op :cutover-promote
                           :token token
                           :cutover-id cutover-1
                           :marker marker-1})))))
            (let [cutover-2 "rollback-1"
                  demoted-new
                  (request!
                   standby-port
                   (fenced
                    log
                    {:op :cutover-demote
                     :token token
                     :cutover-id cutover-2
                     :expected-instance (:instance standby-status)}))
                  marker-2 (:marker demoted-new)
                  bad-marker
                  (assoc-in marker-2 [:logs 0 :boundary-sha]
                            (apply str (repeat 64 "0")))
                  bad-promotion
                  (request!
                   active-port
                   (fenced
                    log
                    {:op :cutover-promote
                     :token token
                     :cutover-id cutover-2
                     :marker bad-marker}))
                  bad-authority-released
                  (false?
                   (get-in
                    (cutover-status active-port log token)
                    [:writer-authority :write-authorized]))
                  rolled-back
                  (request!
                   active-port
                   (fenced
                    log
                    {:op :cutover-promote
                     :token token
                     :cutover-id cutover-2
                     :marker marker-2}))]
              (check! "new generation demotes with a fresh rollback marker"
                      (and (:ok demoted-new)
                           (= :demoted (:phase demoted-new))
                           (= cutover-2 (:cutover-id marker-2))))
              (check! "marker mismatch refuses promotion and releases authority"
                      (and (= :cutover-marker-mismatch
                              (:code bad-promotion))
                           bad-authority-released))
              (check! "retired predecessor can be promoted back exactly"
                      (and (:ok rolled-back)
                           (= :active (:phase rolled-back))
                           (get-in rolled-back
                                   [:writer-authority
                                    :write-authorized])))
              (check! "rolled-back generation acknowledges the next write"
                      (:ok (write! active-port "after-rollback")))
              (check! "demoted replacement refuses canonical writes"
                      (= :writer-authority-required
                         (:code
                          (write! standby-port
                                  "demoted-replacement-must-refuse"))))
              (let [values (canonical-values log)
                    expected @written]
                (check! "every acknowledged cutover write is durable exactly once"
                        (and (= (set expected) (set values))
                             (= (count expected) (count values))
                             (= (count values) (count (distinct values)))))))))))
    (finally
      (stop-daemon! @standby)
      (stop-daemon! @active))))

;; A source that cannot be synchronized must fail before writer-authority
;; release. This separately powers the error branch instead of inferring it from
;; a successful handoff.
(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-blue-green-sync-refusal"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (str (io/file dir "facts.log"))
      token "sync-refusal-token"
      token-file (str (io/file dir "cutover.token"))
      marker-file (str (io/file dir "must-not-exist.marker.edn"))
      port (free-port)
      active (atom nil)]
  (spit log "")
  (spit token-file (str token "\n"))
  (java.nio.file.Files/setPosixFilePermissions
   (.toPath (io/file token-file))
   #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
     java.nio.file.attribute.PosixFilePermission/OWNER_WRITE})
  (try
    (reset! active (start-daemon port log "active" token))
    (let [status
          (eventually
           #(let [s (cutover-status port log token)]
              (when (get-in s [:writer-authority :write-authorized]) s)))
          written
          (request!
           port
           (fenced
            log
            {:op :assert
             :te "@sync-refusal"
             :p "note"
             :r "installed"}))
          _truncate (spit log "")
          demote
          (cutover-cli!
           "demote"
           "--port" (str port)
           "--log" log
           "--token-file" token-file
           "--cutover-id" "sync-must-fail"
           "--expected-instance" (:instance status)
           "--marker-out" marker-file)
          after (cutover-status port log token)]
      (check! "sync-refusal fixture first installs a logical version"
              (and (:ok written) (pos? (:version after))))
      (check! "demotion synchronization refusal rolls back before authority release"
              (and (= 3 (:exit demote))
                   (= :cutover-demotion-rolled-back
                      (get-in demote [:response :code]))
                   (= :active (:phase after))
                   (get-in after
                           [:writer-authority :write-authorized])
                   (not (.exists (io/file marker-file))))))
    (finally
      (stop-daemon! @active))))

(if (seq @failures)
  (do
    (println (str "\n" (count @failures)
                  " blue/green cutover checks failed"))
    (System/exit 1))
  (println "\nblue/green cutover: all checks passed"))
