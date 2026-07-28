;; Deterministic regression for the coordinator's global-monitor convoy.
;; Run: bb -cp out tests/coord_lock_convoy_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

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
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 5000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader.
                        (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str (envelope log request)) "\n"))
      (.flush writer)
      (edn/read reader))))
(defn elapsed-ms [f]
  (let [started (System/nanoTime)
        value (f)]
    [(/ (- (System/nanoTime) started) 1000000.0) value]))
(defn eventually [f]
  (loop [remaining 400]
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
      (println "coord-lock-convoy: hard timeout"))
    (System/exit 124)))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-lock-convoy"
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
                 "FRAM_TEST_SNAPSHOT_BUILD_DELAY_MS" "2000")
      daemon (proc/process {:dir root :out :string :err :string :env env}
                           "clojure" "-M" "coord_daemon.clj" "serve-flat"
                           (str port) (.getPath log))]
  (try
    (check! "strict-fence daemon starts"
            (eventually
             #(integer? (:version (client port log {:op :status})))))

    ;; Keep one strict :for-log status request inside a cooperative two-second
    ;; control delay. The old recursive outer dlock made every writer wait for
    ;; the entire delay.
    (let [slow-socket (java.net.Socket.)
          _ (.connect slow-socket
                      (java.net.InetSocketAddress. "127.0.0.1" (int port))
                      1000)
          slow-writer (io/writer (.getOutputStream slow-socket))]
      (.write slow-writer
              (str (pr-str
                    (envelope log {:op :status :test-delay-ms 2000}))
                   "\n"))
      (.flush slow-writer)
      (check! "two-second strict status is observably active"
              (eventually
               #(pos? (get-in (client port log {:op :status})
                              [:controls :active] 0))))

      (let [writes
            (mapv
             (fn [i]
               (future
                 (elapsed-ms
                  #(client port log
                           {:op :assert
                            :te (str "@disjoint-" i)
                            :p "title"
                            :r (str "value-" i)}))))
             (range 10))
            results (mapv deref writes)
            latencies (mapv first results)
            responses (mapv second results)]
        (check! "all ten disjoint writes commit during slow strict status"
                (every? :ok responses))
        (check! (str "all ten write acknowledgements <=250ms; observed "
                     (pr-str latencies))
                (every? #(<= % 250.0) latencies)))

      ;; A client-side timeout/disconnect must stop the delayed work rather than
      ;; leaving a request worker spinning after the caller is gone.
      (.close slow-socket)
      (check! "disconnect cancels and drains the slow control request"
              (eventually
               #(let [controls (:controls
                                (client port log {:op :status}))]
                  (and (zero? (:active controls))
                       (pos? (get-in controls
                                     [:stops :client-disconnected] 0))))))
      (let [[ms response]
            (elapsed-ms
             #(client port log
                      {:op :assert :te "@after-disconnect"
                       :p "title" :r "landed"}))]
        (check! (format "post-disconnect write remains <=250ms (%.1fms)" ms)
                (and (:ok response) (<= ms 250.0)))))

    ;; Validation builds a whole kernel index and checks every thread. Keep its
    ;; captured store/schema snapshot coherent, but do all O(corpus) work after
    ;; releasing dlock so mutations do not convoy behind it.
    (let [validation
          (future
            (elapsed-ms
             #(client port log {:op :validate :test-delay-ms 2000})))]
      (check! "two-second strict validate is observably active"
              (eventually
               #(pos? (get-in (client port log {:op :status})
                              [:controls :active] 0))))
      (let [writes
            (mapv
             (fn [i]
               (future
                 (elapsed-ms
                  #(client port log
                           {:op :assert
                            :te (str "@during-validate-" i)
                            :p "title"
                            :r (str "validate-value-" i)}))))
             (range 10))
            results (mapv deref writes)
            latencies (mapv first results)
            responses (mapv second results)]
        (check! "all ten disjoint writes commit during slow strict validate"
                (every? :ok responses))
        (check! (str "validate-concurrent acknowledgements <=250ms; observed "
                     (pr-str latencies))
                (every? #(<= % 250.0) latencies)))
      (let [[ms response] @validation]
        (check! (format "validate delay executed outside writer lock (%.1fms)" ms)
                (and (vector? (:violations response)) (>= ms 1900.0)))))

    ;; The periodic checkpoint had a second, independent convoy: dump-log!,
    ;; hashing, FRI generation, and sidecar publication all ran under dlock.
    ;; Hold the captured-root build outside that monitor for two seconds and
    ;; prove the same bounded write latency through the strict fence.
    (let [snapshot
          (future
            (elapsed-ms
             #(client port log {:op :snapshot})))]
      (check! "two-second checkpoint build is observably active"
              (eventually
               #(pos? (get-in (client port log {:op :status})
                              [:snapshots :active] 0))))
      (let [writes
            (mapv
             (fn [i]
               (future
                 (elapsed-ms
                  #(client port log
                           {:op :assert
                            :te (str "@during-snapshot-" i)
                            :p "title"
                            :r (str "snapshot-value-" i)}))))
             (range 10))
            results (mapv deref writes)
            latencies (mapv first results)
            responses (mapv second results)]
        (check! "all ten disjoint writes commit during checkpoint build"
                (every? :ok responses))
        (check! (str "checkpoint-concurrent acknowledgements <=250ms; observed "
                     (pr-str latencies))
                (every? #(<= % 250.0) latencies)))
      (let [[ms response] @snapshot]
        (check! (format "checkpoint delay executed outside writer lock (%.1fms)" ms)
                (and (integer? (:ok response)) (>= ms 1900.0)))))

    (finally
      (stop-process! daemon)
      (future-cancel watchdog))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do (println "\ncoord-lock-convoy:" (count failures) "FAILED")
        (System/exit 1))
    (println "\ncoord-lock-convoy:"
             (count @checks) "/" (count @checks) "PASS")))
