;; server_split_log_reload_test.clj — independent split-log cursors stay O(delta).
;;
;; Run: bb -cp out tests/server_split_log_reload_test.clj
(binding [*command-line-args* []] (load-file "server.clj"))
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
  (:version (server/handle {:op :version})))

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

(def build-reload! #'server/build-reload-candidate)
(def capture-reload! #'server/capture-reload-roots!)
(def install-reload! #'server/install-reload-candidate!)
(def real-apply-tail! (deref #'server/apply-tail!))

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
  (reset! server/telemetry-log (.getCanonicalPath telemetry))
  (reset! server/snapshot-boot-enabled? false)
  (server/boot-flat! (.getCanonicalPath coordination))

  (check! "split fixture boots through the shared tx watermark"
          (= 2 (live-version)))
  (check! "boot query merges coordination and telemetry"
          (= #{["@thread:test" "thread"] ["@run:test" "run"]}
             (set (:ok (server/handle
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
          {#'server/migrate-flat->database
           (fn [& _]
             (swap! migrations inc)
             (throw (ex-info "whole migration reached telemetry append" {})))
           #'server/build-warm-cache
           (fn [& _]
             (swap! cache-builds inc)
             (throw (ex-info "whole cache build reached telemetry append" {})))}
          (fn []
            (let [reload (server/maybe-reload!)
                  kinds (server/handle {:op :query :query kind-query})]
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
            (= 3 (:version @server/cache))))

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
          {#'server/apply-tail!
           (fn [candidate-db lines]
             (let [result (real-apply-tail! candidate-db lines)]
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
            (= :installed (server/maybe-reload!)))
    (check! "retry preserves shared tx order and merged query semantics"
            (= #{["one"] ["two"]}
               (set (:ok (server/handle
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

;; Split writers can append a lower tx after the merged Store has advanced.
;; That physical tail must never be filtered away or installed as a version
;; regression. The per-source byte cursor must surface it, while the source LWW
;; index decides whether it changes a key and the shared version remains at the
;; maximum captured/tail tx.
(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-split-interleave"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      coordination (io/file dir "coordination.log")
      telemetry (io/file dir "telemetry.log")
      row (fn [tx l p r]
            {:tx tx :op "assert" :l l :p p :r r
             :ts "t" :by "fixture"})]
  (write-lines! coordination [(row 1 "@thread:base" "kind" "thread")])
  (write-lines! telemetry [(row 2 "@run:base" "kind" "run")])
  (reset! server/telemetry-log (.getCanonicalPath telemetry))
  (reset! server/snapshot-boot-enabled? false)
  (server/boot-flat! (.getCanonicalPath coordination))
  (server/warm!)

  (append-line! telemetry (row 3 "@run:external" "phase" "pending"))
  (append-line! coordination (row 4 "@thread:owned" "title" "owned"))
  (real-apply-tail! @server/db
                    [(row 4 "@thread:owned" "title" "owned")])
  (#'server/note-source-lines!
   [(str (pr-str (row 4 "@thread:owned" "title" "owned")) "\n")])
  ;; Model the owned appender's exact stamp/cursor convergence while telemetry
  ;; remains pending.
  (reset! server/flat-mtime
          (#'server/stamp coordination))
  (reset! server/flat-bytes (.length coordination))
  (reset! server/flat-prefix-fence
          (#'server/safe-prefix-fence-of
           coordination (.length coordination)))
  (server/warm!)
  (let [roots (capture-reload! false)
        candidate (build-reload! roots)]
    (check! "lower split tx reconciles incrementally by source LWW"
            (true? (:incremental? candidate)))
    (check! "lower split tx candidate cannot regress the Store version"
            (= 4 (:through candidate)))
    (check! "lower split tx candidate installs"
            (= :installed (install-reload! roots candidate)))
    (check! "lower split tx preserves the captured owned write"
            (= [["owned"]]
               (:ok (server/handle
                     {:op :query
                      :query (query "owned-title"
                                    [{:var "title"}]
                                    [{:rel "triple"
                                      :args ["@thread:owned" "title"
                                             {:var "title"}]}])}))))
    (check! "lower split tx adds the pending telemetry fact"
            (= [["pending"]]
               (:ok (server/handle
                     {:op :query
                      :query (query "external-phase"
                                    [{:var "phase"}]
                                    [{:rel "triple"
                                      :args ["@run:external" "phase"
                                             {:var "phase"}]}])}))))
    (check! "lower split tx keeps cache and Store at version four"
            (= 4 (live-version) (:version @server/cache))))

  ;; A physically later row can still carry an older/equal tx. Reconcile it
  ;; against the source watermark instead of replaying it blindly: an older
  ;; same-key value loses, while an equal-tx physically later value wins exactly
  ;; as it does in the whole merged fold.
  (append-line! coordination (row 2 "@thread:owned" "title" "stale"))
  (let [roots (capture-reload! false)
        candidate (build-reload! roots)]
    (check! "older same-key tail reconciles incrementally"
            (true? (:incremental? candidate)))
    (check! "older same-key tail is excluded from the applied delta"
            (empty? (:tail-lines candidate)))
    (check! "older same-key tail installs without moving the version"
            (and (= 4 (:through candidate))
                 (= :installed (install-reload! roots candidate))))
    (check! "older same-key tail cannot replace the newer value"
            (= [["owned"]]
               (:ok (server/handle
                     {:op :query
                      :query (query "owned-title-after-stale"
                                    [{:var "title"}]
                                    [{:rel "triple"
                                      :args ["@thread:owned" "title"
                                             {:var "title"}]}])})))))

  (append-line! coordination (row 4 "@thread:owned" "title" "equal-later"))
  (let [roots (capture-reload! false)
        candidate (build-reload! roots)]
    (check! "equal-tx physically later row reconciles incrementally"
            (true? (:incremental? candidate)))
    (check! "equal-tx physically later row installs at the same version"
            (and (= 4 (:through candidate))
                 (= :installed (install-reload! roots candidate))))
    (check! "equal-tx physically later row wins like the whole fold"
            (= [["equal-later"]]
               (:ok (server/handle
                     {:op :query
                      :query (query "owned-title-after-equal"
                                    [{:var "title"}]
                                    [{:rel "triple"
                                      :args ["@thread:owned" "title"
                                             {:var "title"}]}])})))))

  ;; A mixed physical tail must not silently discard tx3 while accepting tx5.
  (write-lines! coordination [(row 1 "@thread:base" "kind" "thread")
                              (row 4 "@thread:owned" "title" "owned")])
  (write-lines! telemetry [(row 2 "@run:base" "kind" "run")])
  (server/boot-flat! (.getCanonicalPath coordination))
  (server/warm!)
  (append-line! telemetry (row 3 "@run:mixed-low" "phase" "low"))
  (append-line! telemetry (row 5 "@run:mixed-high" "phase" "high"))
  (let [roots (capture-reload! false)
        candidate (build-reload! roots)]
    (check! "mixed lower/higher tail reconciles incrementally"
            (true? (:incremental? candidate)))
    (check! "mixed lower/higher tail installs through tx5"
            (and (= 5 (:through candidate))
                 (= :installed (install-reload! roots candidate))))
    (check! "mixed lower/higher tail retains both physical rows"
            (= #{["@run:mixed-low" "low"] ["@run:mixed-high" "high"]}
               (set (:ok
                     (server/handle
                      {:op :query
                       :query (query "mixed-phase"
                                     [{:var "subject"} {:var "phase"}]
                                     [{:rel "triple"
                                       :args [{:var "subject"} "phase"
                                              {:var "phase"}]}])})))))))

;; Truncate-and-regrow can end longer than the saved cursor. Length+mtime alone
;; misclassifies that replacement as append and skips its rewritten prefix.
;; The prefix fence must force the merged whole-fold oracle.
(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-split-regrow"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      coordination (io/file dir "coordination.log")
      telemetry (io/file dir "telemetry.log")
      row (fn [tx l p r]
            {:tx tx :op "assert" :l l :p p :r r
             :ts "t" :by "fixture"})
      replacement [(row 3 "@run:new-1" "phase" "one")
                   (row 4 "@run:new-2" "phase" "two")
                   (row 5 "@run:new-3" "phase" "three")]]
  (write-lines! coordination [(row 1 "@thread:base" "kind" "thread")])
  (write-lines! telemetry [(row 2 "@run:old" "phase" "old")])
  (reset! server/telemetry-log (.getCanonicalPath telemetry))
  (reset! server/snapshot-boot-enabled? false)
  (server/boot-flat! (.getCanonicalPath coordination))
  (server/warm!)
  (let [old-bytes (.length telemetry)]
    (write-lines! telemetry replacement)
    (check! "regrown replacement extends beyond the old byte cursor"
            (> (.length telemetry) old-bytes))
    (check! "regrown replacement installs through the whole-fold oracle"
            (= :installed (server/maybe-reload!)))
    (check! "regrown replacement stays set-equal to an independent whole fold"
            (:ok (server/snapshot-reconcile)))
    (check! "regrown replacement removes the old prefix fact"
            (= []
               (:ok (server/handle
                     {:op :query
                      :query (query "removed-old"
                                    [{:var "phase"}]
                                    [{:rel "triple"
                                      :args ["@run:old" "phase"
                                             {:var "phase"}]}])}))))
    (check! "regrown replacement materializes every new prefix row"
            (= #{["@run:new-1" "one"]
                 ["@run:new-2" "two"]
                 ["@run:new-3" "three"]}
               (set (:ok
                     (server/handle
                      {:op :query
                       :query (query "replacement-rows"
                                     [{:var "subject"} {:var "phase"}]
                                     [{:rel "triple"
                                       :args [{:var "subject"} "phase"
                                              {:var "phase"}]}])})))))))

(let [failed (remove second @checks)]
  (println (format "\nserver_split_log_reload: %d / %d PASS"
                   (- (count @checks) (count failed))
                   (count @checks)))
  (System/exit (if (empty? failed) 0 1)))
