;; Wire-fault regression battery for bin/fram's daemon-first `show` route.
;; Every peer and corpus is disposable: no :7977, no repository coordination log.
(require '[babashka.process :as proc]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def subject "fault-subject")
(def checks (atom []))

(defn check! [label ok?]
  (swap! checks conj [label (boolean ok?)]))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))

(defn eventually [f]
  (loop [n 80]
    (cond (try (f) (catch Throwable _ false)) true
          (zero? n) false
          :else (do (Thread/sleep 25) (recur (dec n))))))

(defn line! [socket]
  (.readLine (java.io.BufferedReader.
              (java.io.InputStreamReader. (.getInputStream socket)
                                          java.nio.charset.StandardCharsets/UTF_8))))

(defn write! [socket text]
  (doto (.getOutputStream socket)
    (.write (.getBytes text java.nio.charset.StandardCharsets/UTF_8))
    (.flush)))

(defn cli! [port log]
  (let [child (proc/process
               {:dir root :out :string :err :string
                :extra-env {"FRAM_PORT" (str port)
                            "FRAM_LOG" log
                            "FRAM_THREADS" (str (.getParent (io/file log)) "/threads")
                            "FRAM_COORD_RETRY_WINDOW_MS" "0"
                            "FRAM_COORD_CONNECT_TIMEOUT_MS" "150"
                            "FRAM_COORD_READ_TIMEOUT_MS" "150"}}
               "bin/fram" "show" subject)
        started (System/nanoTime)
        result (future @child)
        completed (deref result 3000 ::timed-out)
        elapsed (/ (- (System/nanoTime) started) 1e6)]
    (when (= ::timed-out completed)
      (proc/destroy-tree child)
      (future-cancel result))
    {:result completed :elapsed-ms elapsed}))

(defn fallback-ok? [run expected]
  (let [result (:result run)]
    (and (map? result) (zero? (:exit result))
         (= expected (:out result)) (< (:elapsed-ms run) 3000.0))))

(defn one-peer! [handler run]
  (let [server (java.net.ServerSocket. 0)
        worker (future
                 (try (with-open [socket (.accept server)] (handler socket))
                      (catch Throwable _ nil)
                      (finally (.close server))))
        result (run (.getLocalPort server))]
    (future-cancel worker)
    (try (.close server) (catch Throwable _ nil))
    result))

(let [dir (.toFile (java.nio.file.Files/createTempDirectory
                     "fram-wire-fault-"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
      log (.getCanonicalPath (doto (io/file dir "facts.log")
                               (spit (str (pr-str {:tx 1 :op "assert" :l (str "@" subject)
                                                    :p "title" :r "cold truth" :by "fault-test"}) "\n"
                                          (pr-str {:tx 2 :op "assert" :l (str "@" subject)
                                                    :p "owner" :r "scratch" :by "fault-test"}) "\n"))))
      daemon-port (free-port)
      daemon (proc/process {:dir root :out :string :err :string}
                           "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
                           (str daemon-port) log)]
  (try
    (check! "scratch daemon starts on a free non-7977 port"
            (and (not= 7977 daemon-port)
                 (eventually #(integer? (:version
                                          ((requiring-resolve 'fram.rt/coord-request-for-log)
                                           daemon-port log {:op :version}))))))
    (let [closed-port (free-port)
          cold (cli! closed-port log)
          expected (get-in cold [:result :out])
          warm (cli! daemon-port log)]
      (check! "cold-fold control establishes the expected CLI truth"
              (fallback-ok? cold expected))
      (check! "healthy scratch daemon serves the CLI baseline"
              (and (map? (:result warm)) (zero? (get-in warm [:result :exit]))
                   (< (:elapsed-ms warm) 3000.0)))
      (when-not (= expected (get-in warm [:result :out]))
        (println "  OBSERVED DEFECT: warm daemon output is not byte-identical to cold fold"
                 "cold=" (pr-str expected) "warm=" (pr-str (get-in warm [:result :out]))))
      (let [run (cli! closed-port log)]
        (check! "daemon absent falls back to cold fold" (fallback-ok? run expected)))
      (let [run (cli! closed-port log)]
        (check! "connection refused falls back to cold fold" (fallback-ok? run expected)))
      (let [run (one-peer!
                 (fn [socket] (line! socket) (Thread/sleep 600))
                 #(cli! % log))]
        (check! "unresponsive listener completes within the bounded deadline"
                (fallback-ok? run expected)))
      (let [run (one-peer!
                 (fn [socket] (line! socket)
                   (write! socket "{:version 1 :rows [[\"title\" \"partial lie\"]"))
                 #(cli! % log))]
        (check! "torn response falls back without accepting partial data"
                (and (fallback-ok? run expected)
                     (not (.contains (get-in run [:result :out]) "partial lie")))))
      (let [run (one-peer!
                 (fn [socket] (line! socket) (write! socket "not-edn-at-all\n"))
                 #(cli! % log))]
        (check! "garbage response falls back without accepting data"
                (fallback-ok? run expected)))
      (let [proxy (java.net.ServerSocket. 0)
            killer (future
                     (try
                       (with-open [client (.accept proxy)]
                         (let [request (line! client)]
                           (with-open [upstream (java.net.Socket. "127.0.0.1" daemon-port)]
                             (write! upstream (str request "\n"))
                             ;; The real scratch daemon receives a read, then dies before reply.
                             (proc/destroy-tree daemon))))
                       (catch Throwable _ nil)
                       (finally (.close proxy))))
            run (cli! (.getLocalPort proxy) log)]
        (future-cancel killer)
        (try (.close proxy) (catch Throwable _ nil))
        (check! "daemon killed mid-read falls back to cold fold"
                (fallback-ok? run expected))))
    (finally
      (proc/destroy-tree daemon)
      (try @daemon (catch Throwable _ nil))
      (doseq [[label ok?] @checks]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [failed (remove second @checks)]
        (println (format "wire fault daemon-read CLI: %d / %d PASS"
                         (- (count @checks) (count failed)) (count @checks)))
        (System/exit (if (empty? failed) 0 1))))))
