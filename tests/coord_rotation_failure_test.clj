;; Focused regression receipt for interval rotation failure containment.
;; Run: env -u FRAM_TELEMETRY_LOG clojure -M tests/coord_rotation_failure_test.clj
(require '[fram.types :as ft]
         '[fram.datalog :as d])
(load-file "coord_daemon.clj")

(def failures (atom []))
(defn check! [label ok]
  (println (str (if ok "PASS " "FAIL ") label))
  (when-not ok (swap! failures conj label)))

(defn scratch [suffix]
  (str (System/getProperty "java.io.tmpdir") "/fram-rotation-failure-"
       (System/nanoTime) suffix))

(let [log (scratch ".log")
      co0 (coord/new-coord log)
      _ (coord/commit! co0 "test" "@rotation-test" "title" :assert "hello" nil)
      store-root @(:store co0)
      schema-root {}
      projected ((var-get #'coord-daemon/query-client-view-facts)
                 store-root schema-root)
      canonical ((var-get #'coord-daemon/client-view-facts-from)
                 co0 schema-root)]
  (check! "query cache projection equals the canonical native-core client view"
          (= (set canonical) (set projected)))
  (check! "query cache projection contains values, never StoredValue records"
          (every? (complement #(instance? fram.types.StoredValue %))
                  (mapcat (juxt :l :p :r) projected)))
  (let [control ((var-get #'coord-daemon/new-query-control)
                 {:op :query :query {:find "x" :rules []}})
        _ (reset! (:cancelled control) :test-cancelled)
        outcome (binding [d/*query-control* control]
                  (try
                    ((var-get #'coord-daemon/query-client-view-facts)
                     store-root schema-root)
                    ::unexpected-success
                    (catch clojure.lang.ExceptionInfo error
                      (:code (ex-data error)))))]
    (check! "historical projection observes cancellation before scanning facts"
            (= :query-cancelled outcome))))

(let [quietly (var-get #'coord-daemon/compact-rotations-quietly!)
      writer (java.io.StringWriter.)
      _ (binding [*err* writer]
          (with-redefs-fn
            {#'coord-daemon/compact-rotations!
             (fn [_] (throw (NullPointerException.)))}
            #(check! "nil-message compaction throwable is contained"
                     (false? (quietly "interval")))))
      output (str writer)
      error (:last-error @coord-daemon/rotation-stats)]
  (check! "nil-message compaction failure receives a stable code and reason"
          (and (= :rotation-compaction-failed (:code error))
               (seq (:reason error))
               (.contains output "code=rotation-compaction-failed")
               (.contains output "reason=java.lang.NullPointerException"))))

(let [log (scratch ".log")
      co0 (coord/new-coord log)
      schema-root {}
      cache0 ((var-get #'coord-daemon/build-warm-cache) co0 schema-root)
      old-co @coord-daemon/co
      old-flat @coord-daemon/flat-log
      old-schema @coord-daemon/schema-view
      old-cache @coord-daemon/cache
      started (promise)
      release (promise)
      holder (future
               (locking coord-daemon/dlock
                 (locking coord-daemon/query-cache-build-lock
                   (deliver started true)
                   @release)))]
  (try
    (reset! coord-daemon/co co0)
    (reset! coord-daemon/flat-log log)
    (reset! coord-daemon/schema-view schema-root)
    (reset! coord-daemon/cache cache0)
    @started
    (let [result
          (with-redefs-fn
            {#'coord-daemon/rotation-provenance
             (fn [_] {:fold-fingerprint "test" :log-identity "test"})
             #'rotations/write-set!
             (fn [& _] (throw (NullPointerException.)))}
            #(deref (future ((var-get #'coord-daemon/compact-rotations-quietly!)
                             "interval"))
                    1000 ::timeout))]
      (check! "failing interval compaction makes progress while read-fencing locks are held"
              (false? result)))
    (finally
      (deliver release true)
      @holder
      (reset! coord-daemon/co old-co)
      (reset! coord-daemon/flat-log old-flat)
      (reset! coord-daemon/schema-view old-schema)
      (reset! coord-daemon/cache old-cache))))

(let [base coord-daemon/rotation-compact-interval-ms
      cap coord-daemon/rotation-compact-max-backoff-ms]
  (check! "persistent failures back off exponentially to a finite cap"
          (= [(min cap (* 2 base))
              (min cap (* 4 base))
              cap]
             [(coord-daemon/rotation-backoff-ms 1)
              (coord-daemon/rotation-backoff-ms 2)
              (coord-daemon/rotation-backoff-ms 100)]))
  (check! "a successful compaction resets the failure count and base delay"
          (let [failures (coord-daemon/rotation-next-failure-count 3 true)]
            (and (zero? failures)
                 (= base (coord-daemon/rotation-backoff-ms failures))))))

(let [flat (scratch ".log")
      root (rotations/index-root flat)
      _ (spit flat "fixture")
      _ (rotations/write-set! root [["@s" "p" "v"]]
                              {:watermark 7 :fold-fingerprint "test"
                               :log-identity "test"})
      original-open rotations/open-set
      opened (atom nil)
      result
      (with-redefs-fn
        {#'coord-daemon/rotation-provenance
         (fn [_] {:fold-fingerprint "test" :log-identity "test"})
         #'rotations/open-set
         (fn [& args]
           (let [candidate (apply original-open args)]
             (reset! opened candidate)
             candidate))
         #'rotations/segment-triples
         (fn [_] (throw (ex-info "synthetic build failure" {})))}
        #(coord-daemon/load-rotations! flat 7))]
  (check! "boot segment build failure closes all opened mmap channels"
          (and (nil? result)
               (seq (:segments @opened))
               (every? (fn [[_ segment]]
                         (not (.isOpen ^java.nio.channels.FileChannel
                                       (:channel segment))))
                       (:segments @opened)))))

(println)
(if (empty? @failures)
  (println "coord_rotation_failure_test: ALL PASS")
  (do
    (println "coord_rotation_failure_test FAILURES:" (pr-str @failures))
    (System/exit 1)))
