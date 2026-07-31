;; Run: bb -cp out tests/coord_telemetry_shed_test.clj
(binding [*command-line-args* []]
  (load-file "coord_daemon.clj"))

(let [decision coord-daemon/telemetry-shed-decision
      rejection (decision 4 4 true)
      checks [(nil? (decision 4 0 true))
              (nil? (decision 3 4 true))
              (nil? (decision 4 4 false))
              (= {:reject ["telemetry ingress shed: durable queue at 4/4; telemetry writes are load-shed while coordination writes continue"]
                  :code :telemetry-backpressure
                  :depth 4
                  :threshold 4}
                 rejection)]]
  (if (every? true? checks)
    (println "coord-telemetry-shed: all checks passed")
    (do
      (binding [*out* *err*]
        (println "coord-telemetry-shed: FAILED" (pr-str checks)))
      (System/exit 1))))
