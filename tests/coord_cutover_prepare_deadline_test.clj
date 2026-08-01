;; Bounded PREPARE contract: disconnect/deadline cancellation and exact receipt replay.
(require '[babashka.process :as process]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(load-file "coord_daemon.clj")

(def failures (atom []))

(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok
    (swap! failures conj label)))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn fenced [log request]
  {:op :for-log
   :expected-log (.getCanonicalPath (io/file log))
   :request request})

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

(defn send-and-disconnect! [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket
              (java.net.InetSocketAddress. "127.0.0.1" (int port))
              500)
    (with-open [writer (io/writer (.getOutputStream socket))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer))))

(defn request-outcome [port request]
  (try
    {:response (request! port request)}
    (catch Throwable error
      {:closed (.getName (class error))})))

(defn eventually [f]
  (loop [attempt 0]
    (let [value (try (f) (catch Throwable _ nil))]
      (cond
        value value
        (>= attempt 240) nil
        :else (do (Thread/sleep 25) (recur (inc attempt)))))))

(defn start-daemon [port log token extra-env]
  (process/process
   ["bb" "-cp" "out:." "coord_daemon.clj" "serve-flat"
    (str port) (str log)]
   {:out :string
    :err :string
    :env
    (merge
     (dissoc (into {} (System/getenv)) "FRAM_TELEMETRY_LOG")
     {"FRAM_COORD_ROLE" "standby"
      "FRAM_CUTOVER_TOKEN" token
      "FRAM_REQUIRE_LOG_FENCE" "1"
      "FRAM_SNAPSHOT_BOOT" "0"
      "NORTH_COORD_SINGLE_ORIGIN" "1"}
     extra-env)}))

(defn stop-daemon! [proc]
  (when proc
    (process/destroy-tree proc)
    @proc))

(defn cutover-status [port log token]
  (request! port (fenced log {:op :cutover-status :token token})))

(defn daemon-status [port log]
  (request! port (fenced log {:op :status})))

(defn prepare-request [log token cutover-id]
  (fenced log {:op :cutover-prepare
               :token token
               :cutover-id cutover-id}))

(defn elapsed-request [port request]
  (let [started (System/nanoTime)
        response (request! port request)]
    {:response response
     :elapsed-ms (quot (- (System/nanoTime) started) 1000000)}))

;; These probes ensure the deep helpers, not merely the outer PREPARE loop,
;; execute the bound cooperative callback.
(let [calls (atom 0)
      cancelled?
      (binding [coord-daemon/*cooperative-work-check*
                (fn [_]
                  (when (>= (swap! calls inc) 3)
                    (throw (ex-info "cancel" {:code :test-cancel}))))]
        (try
          (coord-daemon/checked-set (range 100))
          false
          (catch clojure.lang.ExceptionInfo error
            (= :test-cancel (:code (ex-data error))))))]
  (check! "cache set construction checks cancellation inside its reduction"
          (and cancelled? (= 3 @calls))))

(let [flight {:result (promise)
              :waiters (java.util.concurrent.atomic.AtomicInteger. 0)}
      cancelled?
      (binding [coord-daemon/*cooperative-work-check*
                (fn [_]
                  (throw (ex-info "cancel" {:code :test-cancel})))]
        (try
          (coord-daemon/await-query-cache-flight flight)
          false
          (catch clojure.lang.ExceptionInfo error
            (= :test-cancel (:code (ex-data error))))))]
  (check! "cache-flight wait checks cancellation and releases its waiter"
          (and cancelled?
               (zero? (.get ^java.util.concurrent.atomic.AtomicInteger
                            (:waiters flight))))))

(let [control (coord-daemon/new-request-control (System/nanoTime))
      before {:protocol coord-daemon/cutover-protocol :phase :standby}
      response {:ok true :prepared true}
      prepared (assoc before :phase :prepared :response response)]
  (reset! coord-daemon/cutover-state before)
  (coord-daemon/cancel-query! control :client-disconnected)
  (let [rejected?
        (binding [coord-daemon/*request-control* control]
          (try
            (coord-daemon/publish-cutover-prepare! response prepared)
            false
            (catch clojure.lang.ExceptionInfo error
              (= :cutover-client-disconnected
                 (:code (ex-data error))))))]
    (check! "disconnect wins atomically over prepare publication"
            (and rejected? (= before @coord-daemon/cutover-state)))))

(let [control (coord-daemon/new-request-control (System/nanoTime))
      response {:ok true :prepared true}
      prepared {:protocol coord-daemon/cutover-protocol
                :phase :prepared
                :response response}]
  (reset! coord-daemon/cutover-state
          {:protocol coord-daemon/cutover-protocol :phase :standby})
  (binding [coord-daemon/*request-control* control]
    (coord-daemon/publish-cutover-prepare! response prepared))
  (check! "published prepare wins atomically over later disconnect observation"
          (and (= prepared @coord-daemon/cutover-state)
               (= :durable @(:outcome control))
               (not (coord-daemon/cancel-query!
                     control :client-disconnected)))))

(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-prepare-disconnect"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (str (io/file dir "facts.log"))
      token "prepare-disconnect-token"
      port (free-port)
      daemon (atom nil)
      delay-ms 350]
  (spit log "")
  (try
    (reset! daemon
            (start-daemon
             port log token
             {"FRAM_REQUEST_TIMEOUT_MS" "2000"
              "FRAM_CUTOVER_SYNC_TIMEOUT_MS" "1500"
              "FRAM_TEST_CUTOVER_PREPARE_CACHE_DELAY_MS" (str delay-ms)}))
    (let [ready
          (eventually
           #(let [status (cutover-status port log token)]
              (when (and (:ok status)
                         (= :standby (:phase status)))
                status)))]
      (check! "disconnect fixture starts as a read-only standby"
              (and ready
                   (false?
                    (get-in ready [:writer-authority :write-authorized]))))

      (send-and-disconnect!
       port (prepare-request log token "disconnect-prepare"))
      (let [settled
            (eventually
             #(let [cutover (cutover-status port log token)
                    status (daemon-status port log)]
                (when (and (= :standby (get-in cutover [:cutover :phase]))
                           (zero? (get-in status [:reloads :active]))
                           (<= (get-in status [:controls :monitors]) 1)
                           (<= (get-in status [:admission :accepted]) 1))
                  {:cutover cutover :status status})))]
        ;; Wait beyond the abandoned operation's old uncooperative delay. A late
        ;; worker would publish :prepared during this interval.
        (Thread/sleep (+ delay-ms 100))
        (let [after (cutover-status port log token)]
          (check! "client disconnect leaves no prepare worker or monitor orphan"
                  (some? settled))
          (check! "prepare disconnect is attributed by the control monitor"
                  (pos? (get-in settled
                                [:status :controls :stops
                                 :client-disconnected]
                                0)))
          (check! "client disconnect never publishes a late prepared state"
                  (= :standby (get-in after [:cutover :phase])))))

      (let [first-run
            (elapsed-request
             port (prepare-request log token "receipt-1"))
            first-response (:response first-run)
            stored (cutover-status port log token)
            replay-run
            (elapsed-request
             port (prepare-request log token "receipt-1"))
            second-run
            (elapsed-request
             port (prepare-request log token "receipt-2"))]
        (check! "fresh prepare performs the bounded cache stage"
                (and (:ok first-response)
                     (:prepared first-response)
                     (>= (:elapsed-ms first-run) (- delay-ms 50))))
        (check! "prepare state retains the complete successful response"
                (= first-response
                   (get-in stored [:cutover :response])))
        (check! "same exact prepare id replays the complete response without rebuild"
                (and (= first-response (:response replay-run))
                     (< (:elapsed-ms replay-run) 200)))
        (check! "a different prepare id performs a new bounded synchronization"
                (and (= "receipt-2" (get-in second-run [:response :cutover-id]))
                     (>= (:elapsed-ms second-run) (- delay-ms 50))))
        (check! "prepare and replay preserve standby read-only authority"
                (false?
                 (get-in second-run
                         [:response :writer-authority :write-authorized])))))
    (finally
      (stop-daemon! @daemon))))

(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-prepare-deadline"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (str (io/file dir "facts.log"))
      token "prepare-deadline-token"
      port (free-port)
      daemon (atom nil)
      delay-ms 400]
  (spit log "")
  (try
    (reset! daemon
            (start-daemon
             port log token
             {"FRAM_REQUEST_TIMEOUT_MS" "80"
              "FRAM_CUTOVER_SYNC_TIMEOUT_MS" "1500"
              "FRAM_TEST_CUTOVER_PREPARE_CACHE_DELAY_MS" (str delay-ms)}))
    (let [ready (eventually #(try (cutover-status port log token)
                                  (catch Throwable _ nil)))]
      (check! "deadline fixture starts before the bounded prepare probe"
              (:ok ready))
      (let [outcome
            (request-outcome
             port (prepare-request log token "deadline-prepare"))]
        (Thread/sleep (+ delay-ms 100))
        (let [after (cutover-status port log token)
              status (daemon-status port log)]
          (check! "request deadline interrupts prepare instead of returning success"
                  (not (get-in outcome [:response :prepared])))
          (check! "request deadline never publishes a late prepared state"
                  (= :standby (get-in after [:cutover :phase])))
          (check! "prepare deadline is attributed by request telemetry"
                  (pos? (get-in status
                                [:admission :request-timeouts]
                                0)))
          (check! "request deadline leaves no reload or connection worker orphan"
                  (and (zero? (get-in status [:reloads :active]))
                       (<= (get-in status [:controls :monitors]) 1)
                       (<= (get-in status [:admission :accepted]) 1))))))
    (finally
      (stop-daemon! @daemon))))

(if (seq @failures)
  (do
    (println (str "\n" (count @failures)
                  " cutover prepare deadline checks failed"))
    (System/exit 1))
  (println "\ncutover prepare deadline: all checks passed"))
