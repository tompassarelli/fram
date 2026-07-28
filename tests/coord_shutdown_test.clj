;; Deterministic regression for bounded TERM shutdown.
;;
;; A finite control request is kept active for one minute.  Before the fix, the
;; daemon closed only subscriptions and then waited forever for this connection,
;; so systemd eventually had to SIGKILL it.  The fixed daemon owns every accepted
;; socket, cancels the worker, crosses the durability barrier, retires the group
;; appender, and exits inside its declared shutdown budget.
;;
;; Run: bb -cp out tests/coord_shutdown_test.clj
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))
(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))
(defn envelope [log request]
  {:op :for-log
   :expected-log (.getCanonicalPath (io/file log))
   :request request})
(defn client [port log request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket
              (java.net.InetSocketAddress. "127.0.0.1" (int port))
              1000)
    (.setSoTimeout socket 5000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader.
                        (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str (envelope log request)) "\n"))
      (.flush writer)
      (edn/read reader))))
(defn eventually [f]
  (loop [remaining 600]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))
(defn stop-process! [^Process process]
  (when (.isAlive process)
    (.destroyForcibly process)
    (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-coord-shutdown"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      output (io/file dir "daemon.log")
      _ (spit log
              (str (pr-str {:tx 1 :op "assert" :l "@seed"
                            :p "title" :r "seed" :ts "t" :by "fixture"})
                   "\n"))
      builder
      (doto
       (ProcessBuilder.
        ^java.util.List
        ["clojure" "-M" "coord_daemon.clj" "serve-flat"
         (str port) (.getPath log)])
        (.directory (io/file root))
        (.redirectErrorStream true)
        (.redirectOutput output))
      env (.environment builder)
      _ (.put env "FRAM_REQUIRE_LOG_FENCE" "1")
      _ (.put env "FRAM_SNAPSHOT_BOOT" "0")
      _ (.put env "FRAM_TEST_CONTROL_DELAY" "1")
      _ (.put env "FRAM_SHUTDOWN_TIMEOUT_MS" "2500")
      _ (.put env "FRAM_SHUTDOWN_CONNECTION_GRACE_MS" "750")
      process (.start builder)
      slow-socket (java.net.Socket.)]
  (try
    (check! "serve-flat daemon starts"
            (eventually
             #(integer? (:version (client port log {:op :status})))))

    ;; Start the appender and prove one write was acknowledged before TERM.
    (let [response
          (client port log
                  {:op :assert
                   :te "@shutdown-durable"
                   :p "title"
                   :r "acknowledged-before-term"})]
      (check! "pre-TERM write is acknowledged" (:ok response)))

    ;; This request deterministically reproduces the old drain hang.  Keep the
    ;; transport open: TERM itself must close it and cancel the request.
    (.connect slow-socket
              (java.net.InetSocketAddress. "127.0.0.1" (int port))
              1000)
    (let [writer (io/writer (.getOutputStream slow-socket))]
      (.write writer
              (str (pr-str
                    (envelope log
                              {:op :status :test-delay-ms 60000}))
                   "\n"))
      (.flush writer))
    (check! "one-minute finite request is active before TERM"
            (eventually
             #(pos? (get-in (client port log {:op :status})
                            [:controls :active] 0))))

    (let [started (System/nanoTime)]
      (.destroy process)                    ; SIGTERM on Unix
      (let [exited? (.waitFor process
                              5 java.util.concurrent.TimeUnit/SECONDS)
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
        (check! (format "SIGTERM exits within 5s (%.1fms)" elapsed-ms)
                exited?)
        (when-not exited? (stop-process! process))))

    (let [daemon-output (slurp output)
          durable-log (slurp log)]
      (check! "shutdown hook reports completion"
              (str/includes?
               daemon-output "[fram] shutdown complete"))
      (check! "accepted finite connections drain"
              (str/includes?
               daemon-output ":connections :drained"))
      (check! "durability barrier completes"
              (str/includes?
               daemon-output ":durability :durable"))
      (check! "group appender is no longer alive"
              (and (str/includes? daemon-output ":alive false")
                   (str/includes? daemon-output ":stopped true")))
      (check! "listener close is treated as normal shutdown"
              (not (str/includes?
                    daemon-output "AsynchronousCloseException")))
      (check! "acknowledged fact remains in the canonical log"
              (str/includes?
               durable-log "@shutdown-durable")))

    (finally
      (try (.close slow-socket) (catch Throwable _ nil))
      (stop-process! process))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do
      (println "\ncoord-shutdown:" (count failures) "FAILED")
      (System/exit 1))
    (println "\ncoord-shutdown:"
             (count @checks) "/" (count @checks) "PASS")))
