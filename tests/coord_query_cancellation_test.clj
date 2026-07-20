;; Forced-failure proof for coordinator query isolation and cancellation.
;; Run: bb -cp out tests/coord_query_cancellation_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))
(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))
(defn eventually [f]
  (loop [remaining 200]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))
(defn client [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 5000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer)
      (edn/read reader))))
(defn pipelined-client [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 5000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
      ;; One transport write makes the forbidden byte available for the server's
      ;; BufferedReader read-ahead together with the request line.
      (.write writer (str (pr-str request) "\nX"))
      (.flush writer)
      (edn/read reader))))
(defn elapsed-ms [f]
  (let [started (System/nanoTime) value (f)]
    [(/ (- (System/nanoTime) started) 1000000.0) value]))
(defn stop-process! [process]
  (try (proc/destroy-tree process) (catch Throwable _ nil))
  (let [p ^Process (:proc process)]
    (when-not (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly p)
      (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS))))

(def all-pairs-q
  {:find "pair"
   :rules [{:head {:rel "pair" :args [{:var "x"} {:var "y"}]}
            :body [{:rel "triple" :args [{:var "x"} "group" "g"]}
                   {:rel "triple" :args [{:var "y"} "group" "g"]}]}]})
(def subject-q
  {:find "subject-fact"
   :rules [{:head {:rel "subject-fact" :args [{:var "p"} {:var "r"}]}
            :body [{:rel "triple" :args ["@n42" {:var "p"} {:var "r"}]}]}]})
(def stable-snapshot-q
  {:find "member"
   :rules [{:head {:rel "member" :args [{:var "x"}]}
            :body (vec (repeat 100
                               {:rel "triple"
                                :args [{:var "x"} "group" "g"]}))}]})

(def watchdog
  (future
    (Thread/sleep 60000)
    (binding [*out* *err*] (println "coord-query-cancellation: hard timeout"))
    (System/exit 124)))

(let [port (free-port)
      dir (.toFile (java.nio.file.Files/createTempDirectory
                    "fram-query-cancel"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      rows 4000
      _ (spit log
              (apply str
                     (for [i (range rows)]
                       (str (pr-str {:tx (inc i) :op "assert"
                                    :l (str "@n" i) :p "group" :r "g"}) "\n"))))
      env (assoc (into {} (System/getenv))
                 "FRAM_MAX_RESULTS" "10000000"
                 "FRAM_QUERY_MAX_ROWS" "10000000"
                 "FRAM_QUERY_MAX_STEPS" "100000000"
                 "FRAM_QUERY_TIMEOUT_MS" "15000")
      daemon (proc/process {:dir root :out :string :err :string :env env}
                           "clojure" "-M" "coord_daemon.clj" "serve-flat"
                           (str port) (.getPath log))]
  (try
    (check! "daemon starts with fixture"
            (eventually #(integer? (:version (client port {:op :status})))))
    (let [boot-ms (get-in (client port {:op :status}) [:boot :ms])]
      (check! (str "boot builds the warm index before listen within 15 seconds (observed "
                   boot-ms "ms)")
              (and (integer? boot-ms) (< boot-ms 15000))))

    ;; A ground subject must use by-l. Ten work units are enough for its one row;
    ;; the old full-corpus scan deterministically exceeded this bound.
    (let [res (client port {:op :query :query subject-q :query-max-steps 10})]
      (check! "subject-ground query is index-local under a ten-step budget"
              (= [["group" "g"]] (:ok res))))
    (check! "normal completed request retires its EOF monitor"
            (eventually #(zero? (get-in (client port {:op :status})
                                        [:queries :monitors]))))
    (let [samples (vec (repeatedly 100
                                   #(elapsed-ms
                                     (fn [] (client port {:op :query
                                                          :query subject-q})))))
          max-ms (apply max (map first samples))]
      (check! (format "normal no-extra fast queries remain below one second (max %.1fms)" max-ms)
              (and (every? #(= [["group" "g"]] (:ok (second %))) samples)
                   (< max-ms 1000.0))))

    (let [work (client port {:op :query :scan true :query all-pairs-q
                             :query-max-steps 10})]
      (check! "canonical scan work ceiling is explicit"
              (= :query-work-limit (:code work))))

    (let [timed (client port {:op :query :scan true :query all-pairs-q
                              :query-timeout-ms 1})]
      (check! "canonical scan deadline is explicit"
              (= :query-time-limit (:code timed))))

    (let [rows-res (client port {:op :query :query all-pairs-q
                                 :query-max-rows 10})]
      (check! "indexed intermediate row ceiling is explicit"
              (= :query-row-limit (:code rows-res))))

    (let [wire (client port {:op :query :query subject-q
                             :query-max-response-bytes 32})]
      (check! "final response byte ceiling is explicit"
              (= :query-response-too-large (:code wire))))

    ;; active is incremented only after the immutable snapshot was captured.
    ;; A write committed after that point must not leak into the answer, and the
    ;; response version must remain the captured version rather than the newer
    ;; coordinator version.
    (let [answer (future (client port {:op :query :query stable-snapshot-q}))]
      (check! "snapshot query becomes active"
              (eventually #(pos? (get-in (client port {:op :status})
                                         [:queries :active]))))
      (let [write (client port {:op :assert :te "@late" :p "group" :r "g"})
            result @answer]
        (check! "concurrent write does not enter captured result/version"
                (and (:ok write)
                     (< (:version result) (:ok write))
                     (not (some #{["@late"]} (:ok result)))))))

    ;; Keep the connection open until the query is observably running. While it
    ;; burns through a 16M-row join, a lease must still complete quickly because
    ;; query evaluation owns no writer lock. Closing the client must then cancel
    ;; and drain that exact worker.
    (let [socket (java.net.Socket. "127.0.0.1" (int port))
          writer (io/writer (.getOutputStream socket))
          req {:op :query :query all-pairs-q
               :query-timeout-ms 15000
               :query-max-steps 100000000
               :query-max-rows 10000000}]
      (.write writer (str (pr-str req) "\n"))
      (.flush writer)
      (check! "pathological query becomes observably active"
              (eventually #(pos? (get-in (client port {:op :status}) [:queries :active]))))
      (let [[ms lease] (elapsed-ms #(client port {:op :acquire-lease
                                                  :res "query-isolation"
                                                  :holder "test"
                                                  :ttl-ms 5000}))]
        (check! (format "lease stays below one second during active query (observed %.1fms)" ms)
                (and (:ok lease) (< ms 1000.0)))
        (client port {:op :release-lease :res "query-isolation"
                      :holder "test" :epoch (:epoch lease)}))
      (.close socket)
      (check! "disconnect drains query worker and records cancellation"
              (eventually
               #(let [q (:queries (client port {:op :status}))]
                  (and (zero? (:active q))
                       (pos? (get-in q [:stops :query-cancelled])))))))

    ;; Fast malformed validation used to beat the asynchronously-scheduled
    ;; monitor. Exercise both that path and a one-row valid query 1000 times each;
    ;; every already-pipelined byte must win before evaluation/response.
    (let [failures (atom [])]
      (dotimes [i 1000]
        (doseq [[kind request]
                [[:malformed {:op :query :query nil}]
                 [:valid {:op :query :query subject-q}]]]
          (let [response (pipelined-client port request)]
            (when-not (and (= :query-cancelled (:code response))
                           (= :unexpected-client-input (:reason response)))
              (swap! failures conj [i kind response])))))
      (check! "1000 iterations each of fast malformed and valid pipelined requests cancel"
              (empty? @failures)))
    (check! "all query monitors retire after completion/cancellation"
            (eventually #(zero? (get-in (client port {:op :status})
                                        [:queries :monitors]))))

    ;; Historical queries go through the same lock-free controlled executor.
    (let [as-of (client port {:op :as-of :seq rows :query all-pairs-q
                              :query-max-steps 10})]
      (check! "historical query is bounded by the shared evaluator"
              (= :query-work-limit (:code as-of))))

    (finally
      (stop-process! daemon)
      (future-cancel watchdog))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do (println "\ncoord-query-cancellation:" (count failures) "FAILED")
        (System/exit 1))
    (println "\ncoord-query-cancellation:" (count @checks) "/" (count @checks) "PASS")))
