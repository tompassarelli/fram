;; A durable lease append must never advance the daemon's physical freshness
;; marker over an external prefix it has not folded. Prove the ordering against
;; both halves of a split corpus and through a real process restart.
;;
;; Run: bb -cp out tests/coord_lease_external_freshness_test.clj
(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def scratch (str (System/getProperty "java.io.tmpdir")
                  "/fram-lease-external-freshness-" (System/nanoTime)))
(def coordination (str scratch "/coordination.log"))
(def telemetry (str scratch "/telemetry.log"))
(def running (atom nil))
(def checks (atom []))

(defn check! [label value]
  (let [ok? (boolean value)]
    (swap! checks conj [label ok?])
    (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label))
    ok?))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn eventually [f]
  (loop [remaining 240]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn start-daemon! []
  (let [port (free-port)
        child (proc/process
               ["bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
                (str port) coordination]
               {:dir root
                :extra-env {"FRAM_TELEMETRY_LOG" telemetry}
                :out :string :err :string})]
    (reset! running {:child child :port port})
    (when-not (eventually #(integer? (:version (client port {:op :version}))))
      (throw (ex-info "split daemon did not become ready" {:port port})))
    port))

(defn stop-daemon! []
  (when-let [{:keys [child]} @running]
    (proc/destroy-tree child)
    (try @child (catch Exception _ nil))
    (reset! running nil)))

(defn flat-line [tx subject predicate value]
  (str (pr-str {:tx tx :op "assert" :l subject :p predicate :r value
                :frame "lease-external-freshness-test"}) "\n"))

(defn append-line! [path tx subject predicate value]
  (with-open [output (java.io.FileOutputStream. (str path) true)]
    (.write output (.getBytes ^String (flat-line tx subject predicate value) "UTF-8"))
    (.flush output)
    (.force (.getChannel output) true)))

(defn values-of [port subject predicate]
  (set (:values (client port {:op :resolved :te subject :p predicate}))))

(defn generation [port]
  (:generation (client port {:op :reload-status})))

(defn acquire! [port resource]
  (client port {:op :acquire-lease :res resource :holder "freshness-test"
                :ttl-ms 600000}))

(defn held? [port resource lease]
  (true? (:fence-ok (client port {:op :fence-ok :res resource
                                  :holder "freshness-test"
                                  :epoch (:epoch lease)}))))

(fs/create-dirs scratch)
(spit coordination
      (str (flat-line 1 "@lease" "cardinality" "single")
           (flat-line 2 "@lease" "value_kind" "literal")
           (flat-line 3 "@coord-seed" "note" "coordination-seed")))
(spit telemetry
      (str (flat-line 4 "@run-seed" "kind" "run")
           (flat-line 5 "@run-seed" "note" "telemetry-seed")))

(try
  (let [port (start-daemon!)
        coord-tx (+ 1000 (:version (client port {:op :version})))
        generation-before (generation port)
        _ (append-line! coordination coord-tx "@coord-external" "note" "coord-external")
        lease-coord (acquire! port "after-coordination-external")]
    (check! "lease after an external coordination append is durably accepted"
            (:ok lease-coord))
    (check! "deferred lease does not client-pay the pending coordination reload"
            (= generation-before (generation port)))
    (check! "next query absorbs the external coordination prefix"
            (= #{"coord-external"} (values-of port "@coord-external" "note")))
    (check! "coordination prefix produces exactly one reload generation"
            (= 1 (- (generation port) generation-before)))
    (check! "lease survives the coordination-prefix reload"
            (held? port "after-coordination-external" lease-coord))
    (check! "reload advances the unified version through the external tx"
            (>= (:version (client port {:op :version})) coord-tx))

    (stop-daemon!)
    (let [port (start-daemon!)]
      (check! "restart retains both the external coordination fact and lease"
              (and (= #{"coord-external"}
                      (values-of port "@coord-external" "note"))
                   (held? port "after-coordination-external" lease-coord)))

      (let [telemetry-tx (+ 1000 (:version (client port {:op :version})))
            generation-before (generation port)
            _ (append-line! telemetry telemetry-tx "@run-external" "note" "telemetry-external")
            lease-telemetry (acquire! port "after-telemetry-external")]
        (check! "lease after an external telemetry append is durably accepted"
                (:ok lease-telemetry))
        (check! "deferred lease does not client-pay the pending telemetry reload"
                (= generation-before (generation port)))
        (check! "next query absorbs the external telemetry prefix"
                (= #{"telemetry-external"}
                   (values-of port "@run-external" "note")))
        (check! "telemetry prefix produces exactly one unified reload generation"
                (= 1 (- (generation port) generation-before)))
        (check! "both leases survive the telemetry-prefix reload"
                (and (held? port "after-coordination-external" lease-coord)
                     (held? port "after-telemetry-external" lease-telemetry)))
        (check! "telemetry reload advances the unified version through its tx"
                (>= (:version (client port {:op :version})) telemetry-tx))

        (stop-daemon!)
        (let [port (start-daemon!)]
          (check! "second restart retains both external facts without masking"
                  (and (= #{"coord-external"}
                          (values-of port "@coord-external" "note"))
                       (= #{"telemetry-external"}
                          (values-of port "@run-external" "note"))))
          (check! "second restart retains both durable lease fences"
                  (and (held? port "after-coordination-external" lease-coord)
                       (held? port "after-telemetry-external" lease-telemetry)))))))
  (finally
    (stop-daemon!)
    (fs/delete-tree scratch)))

(let [failed (remove second @checks)]
  (println (format "\nlease external freshness: %d/%d passed"
                   (- (count @checks) (count failed)) (count @checks)))
  (when (seq failed) (System/exit 1)))
