;; Run: bb -cp out tests/coord_notify_bound_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn envelope [log request]
  {:op :for-log
   :expected-log (.getCanonicalPath (io/file log))
   :request request})

(defn client [port log request]
  (with-open [socket (doto (java.net.Socket.)
                       (.connect (java.net.InetSocketAddress.
                                  "127.0.0.1" (int port)) 1000)
                       (.setSoTimeout 2000))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader.
                      (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str (envelope log request)) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn eventually [f]
  (loop [remaining 400]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 50) (recur (dec remaining))))))

(defn stop-process! [process]
  (try (proc/destroy-tree process) (catch Throwable _ nil))
  (let [p ^Process (:proc process)]
    (when-not (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly p)
      (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS))))

(let [root (System/getProperty "user.dir")
      port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-notify-bound"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "coordination.log")
      _ (spit log
              (str (pr-str {:tx 1 :op "assert" :l "@seed"
                            :p "title" :r "seed" :ts "t" :by "fixture"})
                   "\n"))
      env (-> (into {} (System/getenv))
              (dissoc "FRAM_TELEMETRY_LOG"
                      "FRAM_TELEMETRY_RETENTION_MAX_BYTES"
                      "FRAM_TELEMETRY_RETENTION_TARGET_BYTES")
              (assoc "FRAM_NOTIFY_QUEUE" "1"
                     "FRAM_REQUIRE_LOG_FENCE" "1"
                     "FRAM_SNAPSHOT_BOOT" "0"))
      daemon (proc/process {:dir root :out :string :err :string :env env}
                           "clojure" "-M" "coord_daemon.clj" "serve-flat"
                           (str port) (.getPath log))]
  (try
    (when-not (eventually #(integer? (:version (client port log {:op :status}))))
      (throw (ex-info "daemon did not start" {})))
    (let [subscriber (doto (java.net.Socket.)
                       (.setReceiveBufferSize 4096)
                       (.connect (java.net.InetSocketAddress.
                                  "127.0.0.1" (int port)) 1000)
                       (.setSoTimeout 2000))
          writer (io/writer (.getOutputStream subscriber))
          reader (java.io.PushbackReader.
                  (io/reader (.getInputStream subscriber)))]
      (try
        (.write writer
                (str (pr-str (envelope log {:op :subscribe})) "\n"))
        (.flush writer)
        (let [ack (edn/read reader)
              payload (apply str (repeat 4096 "x"))
              writes
              (mapv (fn [i]
                      (client port log
                              {:op :assert
                               :te (str "@notify-bound:" i)
                               :p "payload"
                               :r payload}))
                    (range 2000))
              started (System/nanoTime)
              status (client port log {:op :status})
              status-ms (/ (- (System/nanoTime) started) 1000000.0)
              envelope (:envelope status)
              pass? (and (integer? (:subscribed ack))
                         (every? :ok writes)
                         (< status-ms 2000.0)
                         (pos? (get-in envelope [:notify :drops] 0))
                         (= 1 (get-in envelope [:notify :capacity]))
                         (= 4096 (get-in envelope [:durable-queue :capacity]))
                         (= 4 (get-in envelope [:query-page-snapshots :capacity]))
                         (zero? (get-in envelope [:query-page-snapshots :retained]))
                         (= #{:durable-queue :notify :telemetry-shed
                              :telemetry-retention :query-page-snapshots}
                            (set (keys envelope))))]
          (if pass?
            (println "coord-notify-bound: all checks passed"
                     (pr-str {:status-ms status-ms
                              :drops (get-in envelope [:notify :drops])
                              :envelope-keys (set (keys envelope))}))
            (do
              (binding [*out* *err*]
                (println "coord-notify-bound: FAILED"
                         (pr-str {:ack ack :status-ms status-ms
                                  :envelope envelope
                                  :write-failures (count (remove :ok writes))})))
              (System/exit 1))))
        (finally
          (try (.close reader) (catch Throwable _ nil))
          (try (.close subscriber) (catch Throwable _ nil)))))
    (finally
      (stop-process! daemon))))
