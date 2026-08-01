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
      _ (coord/commit! co0 "test" "@rotation-rebuild" "title" :assert "ready" nil)
      schema-root {}
      old-co @coord-daemon/co
      old-flat @coord-daemon/flat-log
      old-schema @coord-daemon/schema-view
      old-cache @coord-daemon/cache
      old-cold @coord-daemon/cold-image
      old-flight @coord-daemon/query-cache-flight
      published (atom nil)]
  (try
    (reset! coord-daemon/co co0)
    (reset! coord-daemon/flat-log log)
    (reset! coord-daemon/schema-view schema-root)
    (reset! coord-daemon/cache {:index nil :version -1})
    (reset! coord-daemon/cold-image nil)
    (reset! coord-daemon/query-cache-flight nil)
    (let [manifest
          (with-redefs-fn
            {#'coord-daemon/rotation-provenance
             (fn [_] {:fold-fingerprint "test" :log-identity "test"})
             #'rotations/write-set!
             (fn [_ triples metadata]
               (reset! published {:triples triples :metadata metadata})
               {:segments {}})
             #'rotations/gc-segments! (fn [_] nil)}
            #(coord-daemon/compact-rotations! "interval"))
          c @coord-daemon/cache
          store-root @(:store co0)]
      (check! "stale post-invalidation cache is rebuilt and rotation publishes"
              (and manifest
                   (seq (:triples @published))
                   (= (coord/current-seq co0) (:version c))
                   (identical? store-root (:store-root c))
                   (identical? schema-root (:schema-root c)))))
    (finally
      (reset! coord-daemon/co old-co)
      (reset! coord-daemon/flat-log old-flat)
      (reset! coord-daemon/schema-view old-schema)
      (reset! coord-daemon/cache old-cache)
      (reset! coord-daemon/cold-image old-cold)
      (reset! coord-daemon/query-cache-flight old-flight))))

(let [log (scratch ".log")
      co0 (coord/new-coord log)
      _ (coord/commit! co0 "test" "@rotation-race" "title" :assert "ready" nil)
      schema-root {}
      old-co @coord-daemon/co
      old-flat @coord-daemon/flat-log
      old-schema @coord-daemon/schema-view
      old-cache @coord-daemon/cache
      old-cold @coord-daemon/cold-image
      old-flight @coord-daemon/query-cache-flight
      original-build (var-get #'coord-daemon/build-query-cache-for-roots)
      started (promise)
      release (promise)
      published? (atom false)]
  (try
    (reset! coord-daemon/co co0)
    (reset! coord-daemon/flat-log log)
    (reset! coord-daemon/schema-view schema-root)
    (reset! coord-daemon/cache {:index nil :version -1})
    (reset! coord-daemon/cold-image nil)
    (reset! coord-daemon/query-cache-flight nil)
    (let [{:keys [result read-result started-result]}
          (with-redefs-fn
            {#'coord-daemon/build-query-cache-for-roots
             (fn [roots]
               (deliver started true)
               @release
               (original-build roots))
             #'coord-daemon/rotation-provenance
             (fn [_] {:fold-fingerprint "test" :log-identity "test"})
             #'rotations/write-set!
             (fn [& _]
               (reset! published? true)
               {:segments {}})}
            #(let [rotation (future
                              ((var-get #'coord-daemon/compact-rotations-quietly!)
                               "interval"))
                   started-result (deref started 5000 ::timeout)
                   read-result (deref
                                (future
                                  ((var-get #'coord-daemon/capture-status-roots!)))
                                1000 ::timeout)
                   _ (when (= ::timeout read-result)
                       (deliver release true))
                   _ (when-not (= ::timeout read-result)
                       (locking coord-daemon/dlock
                         (reset! coord-daemon/schema-view
                                 {[:synthetic "cardinality"]
                                  (fram.kernel/->Fact
                                   "@synthetic" "cardinality" "single")})))
                   _ (deliver release true)
                   result (deref rotation 5000 ::timeout)]
               {:result result
                :read-result read-result
                :started-result started-result}))]
      (check! "concurrent status read is not fenced by a slow failing cache build"
              (and (not= ::timeout started-result)
                   (not= ::timeout read-result)
                   (false? result)))
      (check! "cache generation race fails with a typed capture code"
              (= :rotation-cache-raced
                 (get-in @coord-daemon/rotation-stats [:last-error :code])))
      (check! "cache generation race publishes neither rotation nor stale cache"
              (and (false? @published?)
                   (= {:index nil :version -1} @coord-daemon/cache))))
    (finally
      (deliver release true)
      (reset! coord-daemon/co old-co)
      (reset! coord-daemon/flat-log old-flat)
      (reset! coord-daemon/schema-view old-schema)
      (reset! coord-daemon/cache old-cache)
      (reset! coord-daemon/cold-image old-cold)
      (reset! coord-daemon/query-cache-flight old-flight))))

(let [log (scratch ".log")
      old-flat @coord-daemon/flat-log
      old-cold @coord-daemon/cold-image
      started (promise)
      release (promise)
      holder (future
               (locking coord-daemon/dlock
                 (deliver started true)
                 @release))]
  (try
    (reset! coord-daemon/flat-log log)
    (reset! coord-daemon/cold-image (Object.))
    @started
    (let [result (deref
                  (future
                    ((var-get #'coord-daemon/compact-rotations-quietly!) "interval"))
                  500 ::timeout)]
      (check! "mmap-cold rotation skips before acquiring the read-fencing lock"
              (and (false? result)
                   (= :rotation-cold-image
                      (get-in @coord-daemon/rotation-stats [:last-error :code]))
                   (= :preflight
                      (get-in @coord-daemon/rotation-stats [:last-error :stage])))))
    (finally
      (deliver release true)
      @holder
      (reset! coord-daemon/flat-log old-flat)
      (reset! coord-daemon/cold-image old-cold))))

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
