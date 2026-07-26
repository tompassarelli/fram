;; Bar-3: FRAM_SNAPSHOT_BOOT boot-time measurement.
;; Bench.clj's generic boot! always sets FRAM_TELEMETRY_LOG, which forces
;; boot-flat-canonical! to reason "disabled (log-split routing active)" and
;; fold whole-corpus regardless of FRAM_SNAPSHOT_BOOT — so it never exercises
;; the actual snapshot+tail path. This script drives it directly, no telemetry
;; log involved: (1) boot flat-only + fold once (cold, no sidecar yet),
;; (2) issue :snapshot to write the checkpoint, stop, (3) reboot with
;; FRAM_SNAPSHOT_BOOT=1 against the SAME log + a small tail appended after the
;; checkpoint (so boot must fold snapshot + tail, not whole-corpus), measure.
;; Run: bb -cp out /tmp/fram-bench/snapshot-boot.clj <port>
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def port (Integer/parseInt (or (first *command-line-args*) "8935")))
(def home "/tmp/fram-bench/snaphome")
(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
;; NB: a boot path literally named "coordination.log" unconditionally activates
;; log-split routing (activate-split!, coord_daemon.clj:6017) which then forces
;; boot-flat-canonical! to whole-log-merge-fold regardless of FRAM_SNAPSHOT_BOOT
;; (coord_daemon.clj:5317, pre-existing infra, unrelated to this branch's
;; rotations work — the live corpus is split for that reason). Name the boot
;; file "facts.log" instead so reaim-split leaves it in plain single-log mode
;; and the snapshot+tail path in boot-flat-canonical! actually gets exercised.
(def log (str home "/facts.log"))

(defn client
  ([request] (client request 120000))
  ([request timeout-ms]
   (with-open [socket (java.net.Socket.)]
     (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
     (.setSoTimeout socket (int timeout-ms))
     (with-open [w (io/writer (.getOutputStream socket))
                 r (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
       (.write w (str (pr-str request) "\n"))
       (.flush w)
       (edn/read r)))))

(defn ms [f]
  (let [t0 (System/nanoTime) v (f)]
    [(/ (- (System/nanoTime) t0) 1e6) v]))

(defn boot! [extra-env]
  (proc/process (into ["bin/fram-daemon" "serve-flat" (str port) log])
                {:dir root :out :inherit :err :inherit :extra-env extra-env}))

(defn wait-ready! [timeout-ms]
  (let [t0 (System/nanoTime)]
    (loop []
      (let [ok (try (some? (:version (client {:op :version} 5000))) (catch Throwable _ nil))]
        (cond ok (/ (- (System/nanoTime) t0) 1e6)
              (> (/ (- (System/nanoTime) t0) 1e6) timeout-ms) nil
              :else (do (Thread/sleep 100) (recur)))))))

(defn stop! [p]
  (try (proc/destroy-tree p) (catch Throwable _ nil))
  (try (.waitFor ^Process (:proc p) 15 java.util.concurrent.TimeUnit/SECONDS) (catch Throwable _ nil)))

(println (str "load: " (str/trim (:out (proc/sh "cat" "/proc/loadavg"))) "  nproc=" (.availableProcessors (Runtime/getRuntime))))

;; --- fresh corpus copy, no telemetry log (pure flat-log mode) ---------------
(.mkdirs (io/file home))
(io/copy (io/file "/tmp/fram-bench/pristine/coordination.log") (io/file log))
(doseq [f (.listFiles (io/file home))]
  (when-not (= (.getName (io/file log)) (.getName f)) (io/delete-file f true)))

;; --- phase 1: cold boot, no sidecar yet -> whole-log fold, establishes the
;;     no-snapshot-available baseline and gets us a live daemon to checkpoint.
(let [p1 (boot! {})]
  (let [t1 (wait-ready! 600000)]
    (println (format "phase1 cold-fold-boot-ms          %.0f" t1))
    (println (str "phase1 boot-mode  " (pr-str (:boot (client {:op :status} 60000)))))
    ;; write the checkpoint
    (let [[t res] (ms #(client {:op :snapshot} 120000))]
      (println (format "snapshot-write-ms                 %.0f  %s" t (pr-str (select-keys res [:error])))))
    ;; small tail AFTER the checkpoint, so the reboot must fold snapshot+tail
    (dotimes [i 25] (client {:op :assert :l (str "@bench-tail-" (System/currentTimeMillis)) :p "tail_bench" :r (str i)} 60000))
    (stop! p1)))

(Thread/sleep 500)

;; --- phase 2: reboot same log with FRAM_SNAPSHOT_BOOT=1 -> should fold
;;     snapshot + tail only, not the whole 83MB corpus.
(let [p2 (boot! {"FRAM_SNAPSHOT_BOOT" "1"})]
  (let [t2 (wait-ready! 600000)]
    (println (format "phase2 snapshot-boot-ms           %.0f" t2))
    (let [st (client {:op :status} 60000)]
      (println (str "phase2 boot-mode  " (pr-str (:boot st))))
      (println (str "phase2 version    " (:version st))))
    (stop! p2)))
