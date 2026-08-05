;; A terminal group-appender failure must wake queued durability waiters instead
;; of leaving request threads and shutdown parked on promises forever.
;;
;; Run: bb -cp out tests/server_appender_failure_test.clj
(load-file "database.clj")

(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-appender-failure"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (str (java.io.File. dir "facts.log"))
      injected (ex-info "injected appender failure" {:test true})
      outcome
      (with-redefs-fn
        {#'database/plan-group-batch (fn [_] (throw injected))}
        (fn []
          (let [result
                (future
                  (try
                    (database/enqueue-durable! log ["never-written\n"] nil)
                    :unexpected-success
                    (catch Throwable t t)))]
            (deref result 2000 ::timed-out))))]
  (check! "failed appender wakes the enqueue waiter"
          (and (instance? Throwable outcome)
               (not= ::timed-out outcome)))
  (let [barrier-outcome
        (try
          (database/durable-barrier! 100)
          :unexpected-success
          (catch Throwable t t))]
    (check! "later durability barriers fail closed"
            (instance? Throwable barrier-outcome)))
  (let [status (database/stop-group-appender! 500)]
    (check! "failed appender is no longer alive"
            (and (:stopped status)
                 (not (:alive status))
                 (:failure status)))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do
      (println "\nserver-appender-failure:" (count failures) "FAILED")
      (System/exit 1))
    (println "\nserver-appender-failure:"
             (count @checks) "/" (count @checks) "PASS")))
