;; Production-scale proof for lock-free query cache misses and two-phase flat reload.
;; Run: bb -cp out tests/coord_query_cache_reload_stability_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))
(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))
(defn eventually [f]
  (loop [remaining 2400]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))
(defn client [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (.setSoTimeout socket 60000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str request) "\n"))
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
(defn append-line! [log row]
  (with-open [writer (java.io.FileWriter. (io/file log) true)]
    (.write writer (str (pr-str row) "\n"))))
(defn subject-query [subject]
  {:find "subject-fact"
   :rules [{:head {:rel "subject-fact" :args [{:var "p"} {:var "r"}]}
            :body [{:rel "triple" :args [subject {:var "p"} {:var "r"}]}]}]})
(defn invalidate-cache! [port]
  (client port {:op :assert :te "@group" :p "value_kind" :r "literal"}))
(defn query-active? [port]
  (pos? (get-in (client port {:op :status}) [:queries :active] 0)))
(defn reload-state [port] (client port {:op :reload-status}))
(defn exactly-one? [port subject predicate value]
  (let [r (client port {:op :resolved :te subject :p predicate})]
    (and (= 1 (:members r)) (= [value] (:values r)))))

(def watchdog
  (future
    (Thread/sleep 240000)
    (binding [*out* *err*]
      (println "coord-query-cache-reload-stability: hard timeout after 240s"))
    (System/exit 124)))

(let [port (free-port)
      dir (.toFile (java.nio.file.Files/createTempDirectory
                    "fram-query-cache-reload"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      rows 141000
      _ (with-open [writer (io/writer log)]
          (dotimes [i rows]
            (.write writer
                    (str (pr-str {:tx (inc i) :op "assert"
                                  :l (str "@n" i) :p "group" :r "g"
                                  :ts "t" :by "fixture"})
                         "\n"))))
      env (assoc (into {} (System/getenv))
                 "FRAM_QUERY_TIMEOUT_MS" "30000"
                 "FRAM_QUERY_MAX_STEPS" "100000000"
                 "FRAM_QUERY_MAX_ROWS" "1000000")
      daemon (proc/process {:dir root :out :string :err :string :env env}
                           "clojure" "-M" "coord_daemon.clj" "serve-flat"
                           (str port) (.getPath log))]
  (try
    (check! "141k daemon starts"
            (eventually #(integer? (:version (client port {:op :status})))))

    ;; A wire schema mutation invalidates the whole warm query cache.  The cold
    ;; projection/index build must be observable as active but own no writer lock.
    (check! "schema wire write invalidates the 141k cache" (:ok (invalidate-cache! port)))
    (let [answer (future (client port {:op :query :query (subject-query "@n42")}))]
      (check! "cold 141k cache rebuild becomes active" (eventually #(query-active? port)))
      (let [[lease-ms lease]
            (elapsed-ms #(client port {:op :acquire-lease
                                       :res "cold-cache" :holder "stability-test"
                                       :ttl-ms 10000}))]
        (check! (format "lease stays below 500ms during cold cache build (observed %.1fms)"
                        lease-ms)
                (and (:ok lease) (< lease-ms 500.0)))
        (when (:ok lease)
          (client port {:op :release-lease :res "cold-cache"
                        :holder "stability-test" :epoch (:epoch lease)})))
      (check! "cold query returns the captured subject row"
              (= [["group" "g"]] (:ok @answer)))
      (check! "query-built cache matches a fresh whole 141k projection/index"
              (true? (:consistent (client port {:op :warm-check})))))

    ;; Timeout must interrupt projection/index construction itself, not only the
    ;; evaluator that follows it.  A partial build must never become current.
    (invalidate-cache! port)
    (let [timed (client port {:op :query :query (subject-query "@n42")
                              :query-timeout-ms 1})
          hot-check (client port {:op :query :query (subject-query "@n42")
                                  :query-max-steps 10})]
      (check! "1ms timeout stops the cold cache rebuild cooperatively"
              (= :query-time-limit (:code timed)))
      (check! "timed-out partial cache is not published"
              (= :query-work-limit (:code hot-check))))

    ;; Disconnect after the rebuild is active.  The monitor cancellation must be
    ;; polled by projection/index and drain the worker without a response write.
    (invalidate-cache! port)
    (let [before (get-in (client port {:op :status})
                         [:queries :stops :query-cancelled] 0)
          socket (java.net.Socket. "127.0.0.1" (int port))
          writer (io/writer (.getOutputStream socket))]
      (.write writer (str (pr-str {:op :query :query (subject-query "@n42")}) "\n"))
      (.flush writer)
      (check! "disconnect test enters the cold rebuild" (eventually #(query-active? port)))
      (.close socket)
      (check! "disconnect during rebuild cancels and drains"
              (eventually
               #(let [q (:queries (client port {:op :status}))]
                  (and (zero? (:active q))
                       (> (get-in q [:stops :query-cancelled] 0) before))))))

    ;; Rewarm before the reload races so cache invalidation/publish is separately
    ;; attributable to the external install.
    (check! "cache can rebuild cleanly after cancellation"
            (= [["group" "g"]]
               (:ok (client port {:op :query :query (subject-query "@n42")}))))

    ;; Pure lease/control operations never client-pay a pending corpus reload. A
    ;; subsequent freshness query owns the reload and must converge both roots.
    (let [head (:version (client port {:op :version}))
          ext-tx (+ head 1000)
          generation-before (:generation (reload-state port))]
      (append-line! log {:tx ext-tx :op "assert" :l "@lease-first-external"
                         :p "marker" :r "external" :ts "t" :by "external"})
      (let [[lease-ms lease]
            (elapsed-ms #(client port {:op :acquire-lease
                                       :res "first-after-stamp" :holder "stability-test"
                                       :ttl-ms 10000}))]
        (check! (format "lease as first op after external stamp stays below 500ms (observed %.1fms)"
                        lease-ms)
                (and (:ok lease) (< lease-ms 500.0)))
        (check! "first-op lease leaves the external reload pending"
                (= generation-before (:generation (reload-state port))))
        (check! "subsequent query absorbs the pending external fact"
                (= [["marker" "external"]]
                   (:ok (client port {:op :query
                                      :query (subject-query "@lease-first-external")}))))
        (check! "lease mutation survives the subsequent reload OCC install"
                (true? (:fence-ok (client port {:op :fence-ok
                                                :res "first-after-stamp"
                                                :holder "stability-test"
                                                :epoch (:epoch lease)}))))
        (check! "pending external stamp produces one install generation"
                (= 1 (- (:generation (reload-state port)) generation-before)))
        (when (:ok lease)
          (client port {:op :release-lease :res "first-after-stamp"
                        :holder "stability-test" :epoch (:epoch lease)}))))

    ;; External tail + two simultaneous reloaders + a normal coordinator append.
    ;; Both external and own edits must survive exactly once.  One physical target
    ;; produces one install generation; the losing reloader converges/supersedes.
    (let [head (:version (client port {:op :version}))
          ext-tx (+ head 1000)
          generation-before (:generation (reload-state port))]
      (append-line! log {:tx ext-tx :op "assert" :l "@external-race"
                         :p "marker" :r "external" :ts "t" :by "external"})
      (let [reload-a (future (client port {:op :query
                                           :query (subject-query "@external-race")}))]
        (check! "first external reload build becomes active"
                (eventually #(pos? (:active (reload-state port)))))
        (let [reload-b (future (client port {:op :query
                                             :query (subject-query "@external-race")}))]
          (check! "two reloaders overlap outside the writer lock"
                  (eventually #(>= (:active (reload-state port)) 2)))
          (let [[lease-ms lease]
                (elapsed-ms #(client port {:op :acquire-lease
                                           :res "external-reload" :holder "stability-test"
                                           :ttl-ms 10000}))
                own (client port {:op :assert :te "@own-race"
                                  :p "marker" :r "own"})]
            (check! (format "lease stays below 500ms during external reload (observed %.1fms)"
                            lease-ms)
                    (and (:ok lease) (< lease-ms 500.0)))
            (check! "normal append succeeds during external reload" (:ok own))
            (when (:ok lease)
              (client port {:op :release-lease :res "external-reload"
                            :holder "stability-test" :epoch (:epoch lease)})))
          (check! "both freshness queries converge on the external row"
                  (and (= [["marker" "external"]] (:ok @reload-a))
                       (= [["marker" "external"]] (:ok @reload-b))))))
      (let [generation-after (:generation (reload-state port))]
        (check! "simultaneous reloaders converge to one install generation"
                (= 1 (- generation-after generation-before))))
      (check! "external tail survives exactly once"
              (exactly-one? port "@external-race" "marker" "external"))
      (check! "concurrent own append survives exactly once"
              (exactly-one? port "@own-race" "marker" "own")))

    ;; A schema-only external tail changes no domain row, but its schema identity
    ;; must still invalidate the cache.  First query publishes the new identity;
    ;; the ten-step second query proves it reused that published index.
    (let [head (:version (client port {:op :version}))
          schema-tx (+ head 1000)
          generation-before (:generation (reload-state port))]
      (append-line! log {:tx schema-tx :op "assert" :l "@external_schema"
                         :p "value_kind" :r "literal" :ts "t" :by "external"})
      (let [cold (client port {:op :query :query (subject-query "@external_schema")})
            hot (client port {:op :query :query (subject-query "@external_schema")
                              :query-max-steps 10})]
        (check! "schema-only tail uses one reload install"
                (= 1 (- (:generation (reload-state port)) generation-before)))
        (check! "schema-only identity appears in the cold query"
                (= [["value_kind" "literal"]] (:ok cold)))
        (check! "schema identity is published and reused under ten steps"
                (= [["value_kind" "literal"]] (:ok hot)))))

    ;; Mixed valid+torn tail: apply the valid row, do not apply the incomplete row,
    ;; but retain the incomplete row's max tx exactly as the cold fold does.  A
    ;; second request sees the remembered stamp and performs no second install.
    (let [head (:version (client port {:op :version}))
          valid-tx (inc head)
          torn-tx (+ head 1000)
          generation-before (:generation (reload-state port))]
      (append-line! log {:tx valid-tx :op "assert" :l "@valid-before-torn"
                         :p "marker" :r "valid" :ts "t" :by "external"})
      (append-line! log {:tx torn-tx :op "assert" :l "@incomplete-torn"
                         :p "marker"})
      (client port {:op :query :query (subject-query "@valid-before-torn")})
      (let [generation-after (:generation (reload-state port))
            second (client port {:op :status})]
        (check! "mixed tail advances version and built-through to torn max tx"
                (and (= torn-tx (:version second))
                     (= torn-tx (:built-through
                                 (client port {:op :built-through})))))
        (check! "valid sibling before torn row is applied exactly once"
                (exactly-one? port "@valid-before-torn" "marker" "valid"))
        (check! "incomplete torn row is not applied"
                (zero? (:members (client port {:op :resolved
                                               :te "@incomplete-torn"
                                               :p "marker"}))))
        (check! "second reload after torn tail is unchanged"
                (and (= 1 (- generation-after generation-before))
                     (= generation-after (:generation (reload-state port)))))))

    (finally
      (stop-process! daemon)
      (future-cancel watchdog))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do (println "\ncoord-query-cache-reload-stability:" (count failures) "FAILED")
        (System/exit 1))
    (println "\ncoord-query-cache-reload-stability:"
             (count @checks) "/" (count @checks) "PASS")))
