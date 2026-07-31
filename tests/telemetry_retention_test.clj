;; Run: bb -cp out tests/telemetry_retention_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn row [tx subject value]
  {:tx tx :op "assert" :l subject :p "payload" :r value
   :ts "t" :by "fixture"})

(defn row-line [record]
  (str (pr-str record) "\n"))

(defn write-rows! [path rows]
  (spit path (apply str (map row-line rows))))

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
                       (.setSoTimeout 3000))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader.
                      (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str (envelope log request)) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn eventually [f]
  (loop [remaining 300]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 100) (recur (dec remaining))))))

(defn stop-process! [process]
  (try (proc/destroy-tree process) (catch Throwable _ nil))
  (let [p ^Process (:proc process)]
    (when-not (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly p)
      (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS))))

(let [root (System/getProperty "user.dir")
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-telemetry-retention"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      coordination (io/file dir "coordination.log")
      telemetry (io/file dir "telemetry.log")
      coord-rows [(row 1 "@thread:seed-a" "a")
                  (row 2 "@thread:seed-b" "b")]
      old-rows (mapv (fn [i]
                       (row (+ 3 i) (str "@session:old-" i)
                            (apply str (repeat 180 "o"))))
                     (range 200))
      fresh-base (mapv (fn [i]
                         (row (+ 203 i) (str "@session:fresh-" i)
                              (apply str (repeat 250 "f"))))
                       (range 20))
      fresh-bytes (reduce + (map #(alength (.getBytes ^String (row-line %) "UTF-8"))
                                 fresh-base))
      padding (- 9800 fresh-bytes)
      fresh-rows (update fresh-base (dec (count fresh-base))
                         update :r str (apply str (repeat padding "f")))
      _ (write-rows! coordination coord-rows)
      _ (write-rows! telemetry (into old-rows fresh-rows))
      coordination-bytes (.length coordination)
      port (free-port)
      env (-> (into {} (System/getenv))
              (assoc "FRAM_TELEMETRY_LOG" (.getCanonicalPath telemetry)
                     "FRAM_TELEMETRY_RETENTION_MAX_BYTES" "20000"
                     "FRAM_TELEMETRY_RETENTION_TARGET_BYTES" "10000"
                     "FRAM_TELEMETRY_RETENTION_SWEEP_MS" "1000"
                     "FRAM_REQUIRE_LOG_FENCE" "1"
                     "FRAM_SNAPSHOT_BOOT" "0"))
      daemon (proc/process {:dir root :out :string :err :string :env env}
                           "clojure" "-M" "coord_daemon.clj" "serve-flat"
                           (str port) (.getPath coordination))]
  (try
    (when-not (eventually #(integer? (:version
                                      (client port coordination {:op :status}))))
      (throw (ex-info "daemon did not start" {})))
    (let [before (client port coordination {:op :status})
          swept? (eventually #(<= (.length telemetry) 20000))
          corpus (slurp telemetry)
          subjects (->> (str/split-lines corpus)
                        (map edn/read-string)
                        (map :l)
                        set)
          fresh-subjects (mapv #(str "@session:fresh-" %) (range 20))
          fresh-show (client port coordination
                             {:op :show :te "@session:fresh-19"})
          old-show (client port coordination
                           {:op :show :te "@session:old-0"})
          after (client port coordination {:op :status})
          retention (get-in after [:envelope :telemetry-retention])
          pass? (and swept?
                     (<= (.length telemetry) 20000)
                     (not-any? #(str/starts-with? % "@session:old-") subjects)
                     (every? subjects fresh-subjects)
                     (= coordination-bytes (.length coordination))
                     (<= (:version before) (:version after))
                     (<= 1 (:sweeps retention))
                     (seq (:rows fresh-show))
                     (empty? (:rows old-show)))]
      (if pass?
        (println "telemetry-retention: all checks passed"
                 (pr-str {:bytes (.length telemetry)
                          :sweeps (:sweeps retention)
                          :version-before (:version before)
                          :version-after (:version after)}))
        (do
          (binding [*out* *err*]
            (println "telemetry-retention: FAILED"
                     (pr-str {:swept swept?
                              :telemetry-bytes (.length telemetry)
                              :old-count (count (filter #(str/starts-with?
                                                         % "@session:old-")
                                                       subjects))
                              :fresh-survivors (count (filter subjects fresh-subjects))
                              :coordination-bytes [coordination-bytes
                                                   (.length coordination)]
                              :before before :after after
                              :fresh-show fresh-show :old-show old-show})))
          (System/exit 1))))
    (finally
      (stop-process! daemon))))
