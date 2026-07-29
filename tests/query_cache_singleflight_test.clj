;; query_cache_singleflight_test.clj — cold query-cache misses must not herd.
;;
;; A split-log reload used to publish the new Store with cache version -1.
;; Concurrent reads then each walked the whole Store independently. This test
;; holds one deterministic build open, joins seven readers to it, moves the live
;; version before publication (the write-race case), and proves all eight still
;; share exactly one immutable product.
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))
(require '[fram.datalog :as d])

(def failures (atom 0))
(def checks (atom 0))
(defn check! [label pass?]
  (swap! checks inc)
  (if pass?
    (println "PASS" label)
    (do (swap! failures inc) (println "FAIL" label))))

(def materialize! #'coord-daemon/materialize-query-snapshot)
(def build-reload! #'coord-daemon/build-reload-candidate)
(def install-reload! #'coord-daemon/install-reload-candidate!)
(def build-warm! #'coord-daemon/build-warm-cache)
(def capture-roots-var #'coord-daemon/capture-query-roots!)
(def lease-mutate-var #'coord-daemon/lease-flat-mutation!)
(def client-view-from-var #'coord-daemon/client-view-facts-from)

(defn await!
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (pred) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/sleep 2) (recur))))))

(let [n 8
      old-store {:next-seq 10}
      new-store {:next-seq 11}
      schema-root (Object.)
      roots {:version 10 :store-root old-store
             :schema-root schema-root :cache nil}
      builds (atom 0)
      build-entered (promise)
      release-build (promise)
      ready (java.util.concurrent.CountDownLatch. n)
      start (java.util.concurrent.CountDownLatch. 1)]
  (reset! coord-daemon/co {:store (atom old-store)})
  (reset! coord-daemon/schema-view schema-root)
  (reset! coord-daemon/cache {:index nil :version -1})
  (reset! coord-daemon/query-cache-flight nil)
  (let [workers
        (with-redefs-fn
          {#'coord-daemon/query-client-view-facts
           (fn [_ _]
             (swap! builds inc)
             (deliver build-entered true)
             @release-build
             [:one :two])
           #'coord-daemon/idx-build
           (fn [facts] {:tuples (set facts)})}
          (fn []
            (let [fs
                  (vec
                   (for [_ (range n)]
                     (future
                       (.countDown ready)
                       (.await start)
                       (materialize! roots))))]
              (check! "all cold readers are ready"
                      (.await ready 2 java.util.concurrent.TimeUnit/SECONDS))
              (.countDown start)
              (check! "one cache owner begins the whole-store build"
                      (true? (deref build-entered 2000 false)))
              (check! "all other readers join the same cache flight"
                      (await!
                       #(when-let [flight @coord-daemon/query-cache-flight]
                          (= (dec n)
                             (.get
                              ^java.util.concurrent.atomic.AtomicInteger
                              (:waiters flight))))
                       2000))
              ;; The canonical head moves while the owner is building. Global
              ;; publication must fail closed, but same-root waiters still share
              ;; the owner's exact immutable snapshot.
              (reset! coord-daemon/co {:store (atom new-store)})
              (deliver release-build true)
              (mapv #(deref % 3000 ::timed-out) fs))))]
    (check! "a concurrent write leaves exactly one full-map build"
            (= 1 @builds))
    (check! "every reader receives the captured version"
            (every? #(= 10 (:version %)) workers))
    (check! "every reader receives the same projected facts"
            (every? #(= #{:one :two} (:facts %)) workers))
    (check! "the stale build is not published over the newer head"
            (= -1 (:version @coord-daemon/cache)))
    (check! "the completed flight is retired"
            (nil? @coord-daemon/query-cache-flight))))

;; Split-log reload construction must carry a ready cache into the atomic
;; install turn. Publishing version -1 here was the production trigger: every
;; telemetry append made the next coordination readers rebuild the corpus.
(let [coord-path "/tmp/coordination.log"
      telemetry-path "/tmp/telemetry.log"
      schema-root (Object.)
      candidate-store {:next-seq 31}
      candidate-co {:store (atom candidate-store)}
      eager-cache {:version 31 :store-root candidate-store
                   :schema-root schema-root
                   :facts #{:ready} :idx {:tuples #{:ready}} :index nil}
      cache-builds (atom 0)
      roots {:path coord-path
             :telemetry-path telemetry-path
             :from-tx 30
             :target-stamp :coord-stamp
             :target-telemetry-stamp :telemetry-stamp}
      candidate
      (with-redefs-fn
        {#'coord-daemon/schema-view-from-flat (fn [_] schema-root)
         #'coord-daemon/migrate-flat->co (fn [_] candidate-co)
         #'coord-daemon/stamp
         (fn [path]
           (case path
             "/tmp/coordination.log" :coord-stamp
             "/tmp/telemetry.log" :telemetry-stamp))
         #'coord-daemon/build-warm-cache
         (fn [co-root candidate-schema]
           (swap! cache-builds inc)
           (when (and (identical? candidate-co co-root)
                      (identical? schema-root candidate-schema))
             eager-cache))}
        (fn [] (build-reload! roots)))]
  (check! "reload candidate eagerly builds exactly one query cache"
          (= 1 @cache-builds))
  (check! "reload candidate carries the exact ready cache"
          (identical? eager-cache (:cache candidate)))
  (let [old-store {:next-seq 30}
        old-co {:store (atom old-store)}
        install-roots
        (assoc roots
               :known-stamp :old-coord-stamp
               :known-telemetry-stamp :old-telemetry-stamp
               :from-byte 100
               :from-telemetry-byte 200
               :co-version 30
               :store-root old-store
               :generation 0
               :target-bytes 101
               :target-telemetry-bytes 201)]
    (reset! coord-daemon/co old-co)
    (reset! coord-daemon/flat-canonical? true)
    (reset! coord-daemon/flat-log coord-path)
    (reset! coord-daemon/telemetry-log telemetry-path)
    (reset! coord-daemon/flat-mtime :old-coord-stamp)
    (reset! coord-daemon/telemetry-mtime :old-telemetry-stamp)
    (reset! coord-daemon/flat-bytes 100)
    (reset! coord-daemon/telemetry-bytes 200)
    (reset! coord-daemon/built-through 30)
    (reset! coord-daemon/reload-generation 0)
    (with-redefs-fn
      {#'coord-daemon/stamp
       (fn [path]
         (case path
           "/tmp/coordination.log" :coord-stamp
           "/tmp/telemetry.log" :telemetry-stamp))
       #'coord-commit/reload-install-decision
       (fn [& _] :installed)}
      (fn []
        (check! "reload installs the candidate"
                (= :installed (install-reload! install-roots candidate)))))
    (check! "reload publishes Store and ready cache together"
            (and (identical? candidate-co @coord-daemon/co)
                 (identical? eager-cache @coord-daemon/cache)))))

;; Waiting is cooperative. A disconnected query must leave a healthy owner's
;; flight without canceling it or waiting for that build to finish.
(let [store-root {:next-seq 20}
      schema-root (Object.)
      roots {:version 20 :store-root store-root
             :schema-root schema-root :cache nil}
      result (promise)
      flight {:version 20 :store-root store-root :schema-root schema-root
              :result result
              :waiters (java.util.concurrent.atomic.AtomicInteger. 0)}
      control ((deref #'coord-daemon/new-query-control)
               {:op :query :query {:find "x" :rules []}})]
  (reset! coord-daemon/cache {:index nil :version -1})
  (reset! coord-daemon/query-cache-flight flight)
  (let [waiter
        (future
          (binding [d/*query-control* control]
            (try
              (materialize! roots)
              ::unexpected-success
              (catch clojure.lang.ExceptionInfo t
                (ex-data t)))))]
    (check! "cancel test joins the in-progress flight"
            (await! #(= 1 (.get
                            ^java.util.concurrent.atomic.AtomicInteger
                            (:waiters flight)))
                    2000))
    (reset! (:cancelled control) :client-disconnected)
    (let [outcome (deref waiter 2000 ::timed-out)]
      (check! "a disconnected waiter stops under its own query control"
              (= :query-cancelled (:code outcome)))
      (check! "waiter cancellation preserves the disconnect reason"
              (= :client-disconnected (:reason outcome))))
    (check! "cancelled waiter releases its flight registration"
            (await! #(zero? (.get
                             ^java.util.concurrent.atomic.AtomicInteger
                             (:waiters flight)))
                    2000))
    (check! "waiter cancellation does not cancel the shared owner"
            (not (realized? result))))
  (reset! coord-daemon/query-cache-flight nil))

;; Lease traffic is continuous even when domain work is idle.  An accepted
;; acquire/renew/release advances the Store version, so failing to patch the
;; cache makes the next query pay one whole-corpus facts+rotations rebuild.  Use
;; the real lease kernel here and prove every established-schema lease mutation
;; leaves the cache byte-for-byte equal to a fresh client projection.
(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-lease-query-cache"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      v2-log (str (java.io.File. dir "coord.v2.log"))
      flat (str (java.io.File. dir "coordination.log"))
      co0 (coord/new-coord v2-log)
      schema-root {}]
  (spit flat "")
  (reset! coord-daemon/co co0)
  (reset! coord-daemon/flat-log flat)
  (reset! coord-daemon/flat-canonical? true)
  (reset! coord-daemon/telemetry-log nil)
  (reset! coord-daemon/schema-view schema-root)
  ;; Establish the lease schema first.  Its first transaction is deliberately
  ;; allowed to invalidate because it also introduces schema facts.
  (with-redefs-fn
    {#'coord-daemon/write-flat-lines! (fn [_] nil)}
    (fn []
      (lease-mutate-var
       {:res "schema-seed" :holder "holder" :ttl-ms 60000}
       #(coord/acquire-lease! co0 "holder" "schema-seed" 60000))))
  (reset! coord-daemon/cache (build-warm! co0 schema-root))
  (let [fresh? #(= (:facts @coord-daemon/cache)
                   (set (client-view-from-var co0 schema-root)))
        no-rebuild?
        (fn []
          (let [builds (atom 0)
                roots (capture-roots-var)]
            (with-redefs-fn
              {#'coord-daemon/query-client-view-facts
               (fn [& _] (swap! builds inc) [])}
              (fn [] (materialize! roots)))
            (zero? @builds)))]
    (with-redefs-fn
      {#'coord-daemon/write-flat-lines! (fn [_] nil)}
      (fn []
        (let [acquired
              (lease-mutate-var
               {:res "hot" :holder "holder" :ttl-ms 60000}
               #(coord/acquire-lease! co0 "holder" "hot" 60000))]
          (check! "lease acquire advances the warm cache with no result-set change"
                  (and (= (coord/current-seq co0)
                          (:version @coord-daemon/cache))
                       (fresh?)))
          (check! "query after lease acquire performs no whole-corpus rebuild"
                  (no-rebuild?))
          (let [renewed
                (lease-mutate-var
                 {:res "hot" :holder "holder" :epoch (:epoch acquired)
                  :ttl-ms 60000}
                 #(coord/renew-lease!
                   co0 "holder" "hot" (:epoch acquired) 60000))]
            (check! "lease renewal advances the warm cache with no result-set change"
                    (and (= (coord/current-seq co0)
                            (:version @coord-daemon/cache))
                         (fresh?)))
            (check! "query after lease renewal performs no whole-corpus rebuild"
                    (no-rebuild?))
            (lease-mutate-var
             {:res "hot" :holder "holder" :epoch (:epoch renewed)}
             #(coord/release-lease!
               co0 "holder" "hot" (:epoch renewed)))
            (check! "lease release advances the warm cache with no result-set change"
                    (and (= (coord/current-seq co0)
                            (:version @coord-daemon/cache))
                         (fresh?)))
            (check! "query after lease release performs no whole-corpus rebuild"
                    (no-rebuild?))))))))

(println (format "query_cache_singleflight: %d / %d PASS"
                 (- @checks @failures) @checks))
(System/exit (if (zero? @failures) 0 1))
