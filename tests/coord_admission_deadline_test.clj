;; Focused regression for finite socket admission and one accepted-at request deadline.
;; Run: bb -cp out tests/coord_admission_deadline_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root
  (.getCanonicalPath
   (io/file
    (or (System/getenv "FRAM_COORD_TEST_ROOT")
        (System/getProperty "user.dir")))))
(def daemon-file
  (or (System/getenv "FRAM_COORD_DAEMON")
      (str root "/coord_daemon.clj")))
(def checks (atom []))

(defn check! [label value]
  (swap! checks conj [label (boolean value)]))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn envelope [log request]
  {:op :for-log
   :expected-log (.getCanonicalPath (io/file log))
   :request request})

(defn open-request [port log request]
  (let [socket (java.net.Socket.)
        started (System/nanoTime)]
    (.connect socket
              (java.net.InetSocketAddress. "127.0.0.1" (int port))
              1000)
    (.setSoTimeout socket 2500)
    (let [writer (io/writer (.getOutputStream socket))
          reader (java.io.PushbackReader.
                  (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str (envelope log request)) "\n"))
      (.flush writer)
      {:socket socket :reader reader :started started})))

(defn read-response! [{:keys [socket reader started]}]
  (try
    {:response (edn/read reader)
     :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)}
    (finally
      (try (.close reader) (catch Throwable _ nil))
      (try (.close socket) (catch Throwable _ nil)))))

(defn client [port log request]
  (:response (read-response! (open-request port log request))))

(defn eventually [f]
  (loop [remaining 1600]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn stop-process! [process]
  (try (proc/destroy-tree process) (catch Throwable _ nil))
  (let [p ^Process (:proc process)]
    (when-not (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly p)
      (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS))))

(def watchdog
  (future
    (Thread/sleep 60000)
    (binding [*out* *err*]
      (println "coord-admission-deadline: hard timeout"))
    (System/exit 124)))

(def outcome-probe-form
  '(do
     (binding [*command-line-args* []]
       (load-file
        (or (System/getenv "FRAM_COORD_DAEMON")
            "coord_daemon.clj")))
     (let [daemon-ns (find-ns 'coord-daemon)
           daemon-var (fn [sym] (var-get (ns-resolve daemon-ns sym)))
           new-control (daemon-var 'new-request-control)
           admit! (daemon-var 'request-admit-durable!)
           request-check! (daemon-var 'request-check!)
           reply! (daemon-var 'try-reply)
           render (fn [control response]
                    (let [sw (java.io.StringWriter.)
                          w (java.io.BufferedWriter. sw)]
                      (reply! w response nil control)
                      (clojure.edn/read-string (str sw))))
           past (fn []
                  (assoc (new-control (System/nanoTime))
                         :request-deadline-ns
                         (dec (System/nanoTime))))
           future (fn []
                    (assoc (new-control (System/nanoTime))
                           :request-deadline-ns
                           (+ (System/nanoTime) 1000000000)))
           success {:ok true :version 7}
           failure {:reject ["fsync failed"] :code :durability-failure}
           expired-durable (assoc (past) :outcome (atom :durable))
           timeout-first (past)
           timeout-check
           (try
             (request-check! timeout-first :probe)
             nil
             (catch Throwable t (:code (ex-data t))))
           timeout-admit
           (try
             (admit! timeout-first)
             nil
             (catch Throwable t (:code (ex-data t))))
           durable-first (future)
           _ (admit! durable-first)
           durable-expired
           (assoc durable-first
                  :request-deadline-ns
                  (dec (System/nanoTime)))
           durable-check
           (try
             (request-check! durable-expired :probe)
             :returned
             (catch Throwable t (:code (ex-data t))))]
       (prn
        {:expired-durable-success (render expired-durable success)
         :expired-durable-failure (render expired-durable failure)
         :timeout-first
         [timeout-check timeout-admit @(:outcome timeout-first)]
         :durable-first
         [durable-check (admit! durable-expired)
          @(:outcome durable-expired)
          (render durable-expired success)]}))))

(let [probe @(proc/process {:dir root :out :string :err :string}
                           "bb" "-cp" "out" "-e"
                           (pr-str outcome-probe-form))
      result (when (zero? (:exit probe))
               (edn/read-string (str/trim (:out probe))))]
  (check! "expired durable success is never rewritten as timeout"
          (= {:ok true :version 7}
             (:expired-durable-success result)))
  (check! "expired durable failure is never rewritten as timeout"
          (= {:reject ["fsync failed"] :code :durability-failure}
             (:expired-durable-failure result)))
  (check! "timeout wins before durable admission in one atomic outcome"
          (= [:request-timeout :request-timeout :timed-out]
             (:timeout-first result)))
  (check! "durable admission wins before timeout in one atomic outcome"
          (= [:returned true :durable {:ok true :version 7}]
             (:durable-first result))))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-admission-deadline"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log
              (str (pr-str {:tx 1 :op "assert" :l "@seed"
                            :p "title" :r "seed" :ts "t" :by "fixture"})
                   "\n"))
      env (assoc (into {} (System/getenv))
                 "FRAM_REQUIRE_LOG_FENCE" "1"
                 "FRAM_SNAPSHOT_BOOT" "0"
                 "FRAM_TEST_CONTROL_DELAY" "1"
                 "FRAM_CONNECTION_WORKERS" "2"
                 "FRAM_CONNECTION_QUEUE" "1"
                 "FRAM_REQUEST_TIMEOUT_MS" "1000")
      daemon (proc/process {:dir root :out :string :err :string :env env}
                           "clojure" "-M" daemon-file "serve-flat"
                           (str port) (.getPath log))]
  (try
    (let [up? (eventually
               #(integer? (:version (client port log {:op :status}))))]
      (check! "bounded daemon starts" up?)
      (when-not up?
        (throw (ex-info "daemon did not become ready within 40 seconds"
                        {:port port}))))

    (let [timed-subscription
          (read-response!
           (open-request port log
                         {:op :subscribe :test-delay-ms 5000}))]
      (check! "subscription handshake stays inside the accepted-at deadline"
              (= :request-timeout
                 (get-in timed-subscription [:response :code]))))

    (let [subscription (open-request port log {:op :subscribe})
          acknowledgement (edn/read (:reader subscription))
          _ (Thread/sleep 1100)
          write-response
          (client port log
                  {:op :assert
                   :te "@subscription-survives-handshake-deadline"
                   :p "title"
                   :r "live"})
          event (edn/read (:reader subscription))]
      (try
        (check! "successful subscription flushes before its deadline disarms"
                (and (integer? (:subscribed acknowledgement))
                     (:ok write-response)
                     (= "@subscription-survives-handshake-deadline"
                        (:l event))))
        (finally
          (try (.close (:reader subscription))
               (catch Throwable _ nil))
          (try (.close (:socket subscription))
               (catch Throwable _ nil)))))

    ;; Two live workers and one queued admission consume the exact configured
    ;; envelope. The fourth accepted socket must be rejected by the accept loop,
    ;; without waiting for any slow handler to retire.
    (let [slow-a (open-request port log {:op :status :test-delay-ms 5000})
          slow-b (open-request port log {:op :status :test-delay-ms 5000})
          _ (Thread/sleep 100)
          pending (open-request port log {:op :status :test-delay-ms 5000})
          _ (Thread/sleep 50)
          overloaded (read-response!
                      (open-request port log {:op :status}))
          busy (:response overloaded)]
      (check! (format "saturation returns typed server-busy within 300ms (%.1fms)"
                      (:elapsed-ms overloaded))
              (and (= :server-busy (:code busy))
                   (true? (:retryable busy))
                   (< (:elapsed-ms overloaded) 300.0)))

      (doseq [[label result]
              [["first live request" (read-response! slow-a)]
               ["second live request" (read-response! slow-b)]
               ["queued request" (read-response! pending)]]]
        (check! (str label " ends at the shared accepted-at deadline")
                (= :request-timeout (get-in result [:response :code])))))

    (let [admission (:admission (client port log {:op :status}))]
      (check! "status exposes the configured admission envelope"
              (and (= 2 (:worker-limit admission))
                   (= 1 (:pending-limit admission))
                   (= 1000 (:request-timeout-ms admission))))
      (check! "observed workers and pending admissions never exceed their caps"
              (and (<= (:largest-workers admission) 2)
                   (<= (:max-active admission) 2)
                   (<= (:max-pending admission) 1)
                   (pos? (:rejected admission)))))

    ;; A timed-out read must consume only its own bounded worker. A disjoint
    ;; mutation uses the other worker, crosses the existing durability barrier,
    ;; and is acknowledged well before the read deadline.
    (let [slow (open-request port log {:op :status :test-delay-ms 5000})
          _ (Thread/sleep 75)
          write-start (System/nanoTime)
          write-response
          (client port log
                  {:op :assert
                   :te "@deadline-does-not-starve-write"
                   :p "title"
                   :r "durable"})
          write-ms (/ (- (System/nanoTime) write-start) 1000000.0)
          slow-result (read-response! slow)]
      (check! (format "unrelated durable write acks within 300ms (%.1fms)"
                      write-ms)
              (and (:ok write-response) (< write-ms 300.0)))
      (check! "slow non-write returns the typed absolute timeout"
              (= :request-timeout
                 (get-in slow-result [:response :code])))
      (check! "acknowledged write is present in the durable log"
              (str/includes? (slurp log)
                             "@deadline-does-not-starve-write")))

    (finally
      (stop-process! daemon)
      (future-cancel watchdog))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do
      (println "\ncoord-admission-deadline:" (count failures) "FAILED")
      (System/exit 1))
    (println "\ncoord-admission-deadline:"
             (count @checks) "/" (count @checks) "PASS")))
