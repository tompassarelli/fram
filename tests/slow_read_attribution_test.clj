;; ============================================================================
;; slow_read_attribution_test.clj — a slow read must NAME which phase was slow.
;;
;; Why this exists: on 2026-07-29 an identical query showed a 24ms median with
;; 23% of samples at 4.7-6.6s, and three of four stall episodes had no
;; corresponding daemon log entry at all. The coordinator was busy with
;; something it never named, which is the whole reason the cause stayed unknown.
;;   bb -cp out tests/slow_read_attribution_test.clj
;; ============================================================================
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))
(require '[clojure.string :as str])

(def failures (atom 0))
(def checks (atom 0))
(defn check! [label pass?]
  (swap! checks inc)
  (if pass? (println "PASS" label)
      (do (swap! failures inc) (println "FAIL" label))))

(def report! #'coord-daemon/report-slow-read!)
(def MS 1000000)

(defn emit
  "Run the reporter over phase durations in ms, returning what it printed."
  [reload-ms lock-ms exec-ms]
  (let [t0 0
        t1 (* reload-ms MS)
        t2 (+ t1 (* lock-ms MS))
        t3 (+ t2 (* exec-ms MS))]
    (with-out-str (report! :query t0 t1 t2 t3))))

;; --- silence is the default -------------------------------------------------
;; This runs on every read, so a healthy coordinator must produce no output at
;; all; a log line per query would be its own performance problem.
(check! "fast read prints nothing" (= "" (emit 1 2 3)))
(check! "read just under the threshold prints nothing"
        (= "" (emit 0 0 (dec coord-daemon/slow-read-ms))))

;; --- past the threshold, attribute ------------------------------------------
(check! "read at the threshold reports"
        (str/includes? (emit 0 0 coord-daemon/slow-read-ms) "slow read"))

(let [out (emit 10 5000 30)]
  (check! "reports the total" (str/includes? out "5040ms"))
  (check! "attributes lock-wait" (str/includes? out "lock-wait 5000ms"))
  (check! "separates reload" (str/includes? out "reload 10ms"))
  (check! "separates execute" (str/includes? out "execute 30ms"))
  (check! "names the op" (str/includes? out ":query")))

;; --- the three phases must be distinguishable, not merged -------------------
;; The whole diagnostic value is telling "blocked behind a writer" apart from
;; "the query itself is slow" — a single total cannot do that.
(let [blocked (emit 0 5000 20)
      slow-query (emit 0 20 5000)]
  (check! "a lock-blocked read is distinguishable from a slow query"
          (and (str/includes? blocked "lock-wait 5000ms")
               (str/includes? blocked "execute 20ms")
               (str/includes? slow-query "lock-wait 20ms")
               (str/includes? slow-query "execute 5000ms"))))

;; Public fram-fast requests are fenced so they cannot accidentally query the
;; wrong corpus. That envelope has its own :fenced-query route; timing only the
;; direct :query route left every public CLI stall invisible.
(let [events (atom [])
      req {:op :for-log
           :expected-log "/tmp/coordination.log"
           :request {:op :query
                     :query {:find "x" :rules []}}}
      response
      (with-redefs-fn
        {#'coord-daemon/maybe-reload!
         (fn [& _] (swap! events conj :reload))
         #'coord-daemon/log-fence-rejection
         (fn [_] nil)
         #'coord-daemon/capture-query-roots!
         (fn [] (swap! events conj :capture) :roots)
         #'coord-daemon/execute-query
         (fn [inner roots]
           (swap! events conj [:execute (:op inner) roots])
           {:ok []})
         #'coord-daemon/report-slow-read!
         (fn [op t0 t1 t2 t3]
           (swap! events conj [:report op (<= t0 t1 t2 t3)]))}
        (fn [] ((deref #'coord-daemon/handle*) req)))]
  (check! "fenced query preserves the query response" (= {:ok []} response))
  (check! "fenced query attributes reload, lock capture, and execution"
          (= [:reload
              :capture
              [:execute :query :roots]
              [:report :fenced-query true]]
             @events)))

(println (format "slow_read_attribution: %d / %d PASS"
                 (- @checks @failures) @checks))
(System/exit (if (zero? @failures) 0 1))
