;; Start the real daemon over a split pair, commit through its socket, and prove
;; both routing and merged warm reads survive the process boundary.
(require '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[fram.rt :as rt])
(load-file "tests/log_split_readiness_lib.clj")

(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-log-split-e2e-" (System/nanoTime)))
(.mkdirs (java.io.File. tmp))
(def coord (str tmp "/coordination.log"))
(def telemetry (str tmp "/telemetry.log"))
(def port (free-port))

;; The subject is already kinded at boot, so its subsequent payload routes to
;; telemetry. A coordination-only subject proves the other half remains active.
(spit coord (str (pr-str {:tx 1 :op "assert" :l "@run-e2e" :p "kind" :r "run" :frame "test"}) "\n"
                 (pr-str {:tx 2 :op "assert" :l "@thread-e2e" :p "title" :r "Thread" :frame "test"}) "\n"
                 (pr-str {:tx 3 :op "assert" :l "@worlds" :p "telemetry_kind" :r "audit" :frame "test"}) "\n"
                 ;; Canonical subject exists but has no routing values: legacy
                 ;; telemetry_kind values must remain effective during migration.
                 (pr-str {:tx 4 :op "assert" :l "@log-routing" :p "note" :r "reserved" :frame "test"}) "\n"
                 (pr-str {:tx 5 :op "assert" :l "@audit-e2e" :p "kind" :r "audit" :frame "test"}) "\n"))
(spit telemetry (str (pr-str {:tx 6 :op "assert" :l "@run-e2e" :p "seed" :r "telemetry" :frame "test"}) "\n"))

(def child
  (p/process [(str (System/getProperty "user.dir") "/bin/fram-daemon")
              (str port) coord]
             {:dir (System/getProperty "user.dir")
              :extra-env {"FRAM_TELEMETRY_LOG" telemetry}
              :out :string :err :string}))

(try
  (await-ready child port #(not (neg? (rt/coord-version %))))
  (let [v (rt/coord-version port)
        telemetry-resp (rt/coord-assert port "@run-e2e" "note" "routed" v)
        v2 (rt/coord-version port)
        coordination-resp (rt/coord-assert port "@thread-e2e" "owner" "personal" v2)
        v3 (rt/coord-version port)
        legacy-routing-resp (rt/coord-assert port "@audit-e2e" "note" "legacy-policy" v3)
        warm (rt/coord-live-facts port coord)]
    (when-not (str/starts-with? telemetry-resp "ok:")
      (throw (ex-info "telemetry commit failed" {:response telemetry-resp})))
    (when-not (str/starts-with? coordination-resp "ok:")
      (throw (ex-info "coordination commit failed" {:response coordination-resp})))
    (when-not (str/starts-with? legacy-routing-resp "ok:")
      (throw (ex-info "legacy routing commit failed" {:response legacy-routing-resp})))
    (assert (str/includes? (slurp telemetry) ":p \"note\""))
    (assert (str/includes? (slurp telemetry) ":r \"legacy-policy\""))
    (assert (str/includes? (slurp coord) ":p \"owner\""))
    (assert (some #(= ["@run-e2e" "seed" "telemetry"] [(:l %) (:p %) (:r %)]) warm))
    (println "log-split e2e: routed both halves and merged warm read — PASS"))
  (finally
    (p/destroy-tree child)
    (cleanup-scratch tmp)))
