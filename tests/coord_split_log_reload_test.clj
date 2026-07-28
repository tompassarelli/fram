;; coord_split_log_reload_test.clj — independent split-log cursors stay O(delta).
;;
;; Run: bb -cp out tests/coord_split_log_reload_test.clj
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))
(require '[clojure.java.io :as io])

(def checks (atom []))
(defn check! [label value]
  (let [pass? (boolean value)]
    (swap! checks conj [label pass?])
    (println (if pass? "PASS" "FAIL") label)))

(defn append-line! [path row]
  (with-open [writer (java.io.FileWriter. (io/file path) true)]
    (.write writer (str (pr-str row) "\n"))))

(defn write-lines! [path rows]
  (with-open [writer (io/writer path)]
    (doseq [row rows]
      (.write writer (str (pr-str row) "\n")))))

(defn live-version []
  (:version (coord-daemon/handle {:op :version})))

(defn query [find-name head-args body]
  {:find find-name
   :rules [{:head {:rel find-name :args head-args}
            :body body}]})

(def kind-query
  (query "kind-row"
         [{:var "subject"} {:var "kind"}]
         [{:rel "triple"
           :args [{:var "subject"} "kind" {:var "kind"}]}]))

(def phase-query
  (query "phase-row"
         [{:var "phase"}]
         [{:rel "triple"
           :args ["@run:test" "phase" {:var "phase"}]}]))

(def build-reload! #'coord-daemon/build-reload-candidate)
(def capture-reload! #'coord-daemon/capture-reload-roots!)
(def install-reload! #'coord-daemon/install-reload-candidate!)
(def real-apply-tail! (deref #'coord-daemon/apply-tail!))

(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-split-reload"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      coordination (io/file dir "coordination.log")
      telemetry (io/file dir "telemetry.log")
      coordination-rows
      [{:tx 1 :op "assert" :l "@thread:test" :p "kind" :r "thread"
        :ts "t" :by "fixture"}]
      telemetry-rows
      [{:tx 2 :op "assert" :l "@run:test" :p "kind" :r "run"
        :ts "t" :by "fixture"}]]
  (write-lines! coordination coordination-rows)
  (write-lines! telemetry telemetry-rows)
  (reset! coord-daemon/telemetry-log (.getCanonicalPath telemetry))
  (reset! coord-daemon/snapshot-boot-enabled? false)
  (coord-daemon/boot-flat! (.getCanonicalPath coordination))

  (check! "split fixture boots through the shared tx watermark"
          (= 2 (live-version)))
  (check! "boot query merges coordination and telemetry"
          (= #{["@thread:test" "thread"] ["@run:test" "run"]}
             (set (:ok (coord-daemon/handle
                        {:op :query :query kind-query})))))

  ;; The production hot case: only telemetry moved. Neither the whole-log
  ;; migration nor a whole query-cache build is allowed to run.
  (append-line! telemetry
                {:tx 3 :op "assert" :l "@run:test" :p "outcome" :r "done"
                 :ts "t" :by "external"})
  (let [migrations (atom 0)
        cache-builds (atom 0)
        result
        (with-redefs-fn
          {#'coord-daemon/migrate-flat->co
           (fn [& _]
             (swap! migrations inc)
             (throw (ex-info "whole migration reached telemetry append" {})))
           #'coord-daemon/build-warm-cache
           (fn [& _]
             (swap! cache-builds inc)
             (throw (ex-info "whole cache build reached telemetry append" {})))}
          (fn []
            (let [reload (coord-daemon/maybe-reload!)
                  kinds (coord-daemon/handle {:op :query :query kind-query})]
              {:reload reload :kinds kinds})))]
    (check! "telemetry-only append installs one bounded tail"
            (= :installed (:reload result)))
    (check! "telemetry-only append performs no whole migration"
            (zero? @migrations))
    (check! "telemetry-only append performs no whole cache build"
            (zero? @cache-builds))
    (check! "bounded cache delta preserves the merged query view"
            (= #{["@thread:test" "thread"] ["@run:test" "run"]}
               (set (get-in result [:kinds :ok]))))
    (check! "bounded cache delta advances the exact shared version"
            (= 3 (:version @coord-daemon/cache))))

  ;; Deterministic race: append tx4, capture it, then append tx5 while the
  ;; private clone is being built. The post-build stamp fence must reject the
  ;; stale candidate; the normal retry must absorb both records exactly once.
  (append-line! telemetry
                {:tx 4 :op "assert" :l "@run:test" :p "phase" :r "one"
                 :ts "t" :by "external"})
  (let [roots (capture-reload! false)
        injected? (atom false)
        candidate
        (with-redefs-fn
          {#'coord-daemon/apply-tail!
           (fn [candidate-co lines]
             (let [result (real-apply-tail! candidate-co lines)]
               (when (compare-and-set! injected? false true)
                 (append-line! telemetry
                               {:tx 5 :op "assert" :l "@run:test"
                                :p "phase" :r "two"
                                :ts "t" :by "external"}))
               result))}
          (fn [] (build-reload! roots)))]
    (check! "append during candidate construction trips the final stamp fence"
            (= :raced (:mode candidate)))
    (check! "raced candidate asks the caller to retry"
            (= :retry (install-reload! roots candidate)))
    (check! "raced candidate publishes neither partial fact nor version"
            (= 3 (live-version)))
    (check! "retry converges on both independently-cursored telemetry records"
            (= :installed (coord-daemon/maybe-reload!)))
    (check! "retry preserves shared tx order and merged query semantics"
            (= #{["one"] ["two"]}
               (set (:ok (coord-daemon/handle
                          {:op :query :query phase-query})))))
    (check! "retry advances exactly through the raced append"
            (= 5 (live-version))))

  ;; A shorter telemetry file is not an append. It must be classified by the
  ;; whole-corpus watermark oracle and refused without replacing the good Store.
  (write-lines! telemetry telemetry-rows)
  (let [roots (capture-reload! false)
        candidate (build-reload! roots)]
    (check! "telemetry cursor regression is refused"
            (= :refused (:mode candidate)))
    (check! "refused regression cannot replace the last good merged view"
            (= :refused (install-reload! roots candidate)))
    (check! "refused regression preserves the live version"
            (= 5 (live-version)))))

(let [failed (remove second @checks)]
  (println (format "\ncoord_split_log_reload: %d / %d PASS"
                   (- (count @checks) (count failed))
                   (count @checks)))
  (System/exit (if (empty? failed) 0 1)))
