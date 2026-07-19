;; Seeded regression: a coordinator whose boot is deterministically slower
;; than the old fixed 5-second poll window (100 * 50ms) must still be
;; awaited successfully by the bounded observable readiness harness, as
;; long as it comes up inside the harness's deadline. This is the "slow
;; boot" half of the log-split E2E cold-boot-flake fix; see
;; tests/log_split_readiness_dead_child_test.clj for the "dead child" half.
(require '[babashka.process :as p]
         '[fram.rt :as rt])
(load-file "tests/log_split_readiness_lib.clj")

(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-readiness-slow-boot-" (System/nanoTime)))
(.mkdirs (java.io.File. tmp))
(def coord (str tmp "/coordination.log"))
(spit coord "")
(def port (free-port))
(def boot-delay-s 8) ;; > old fixed 5s window; well inside the new bounded deadline

(def child
  (p/process [(str (System/getProperty "user.dir") "/tests/fixtures/slow_daemon_wrapper.sh")
              (str port) coord]
             {:dir (System/getProperty "user.dir")
              :extra-env {"FRAM_TEST_BOOT_DELAY_S" (str boot-delay-s)}
              :out :string :err :string}))

(try
  (let [start (System/currentTimeMillis)
        result (await-ready child port #(not (neg? (rt/coord-version %))))
        elapsed (- (System/currentTimeMillis) start)]
    (assert (= :ready result))
    (assert (>= elapsed (* boot-delay-s 1000))
            (str "expected to have actually waited out the injected " boot-delay-s "s delay, only waited " elapsed "ms"))
    (println (str "log-split readiness slow-boot: awaited " elapsed "ms past the old fixed 5000ms window — PASS")))
  (finally
    (p/destroy-tree child)
    (cleanup-scratch tmp)))
