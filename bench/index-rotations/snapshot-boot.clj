;; Bar-3: FRAM_SNAPSHOT_BOOT boot-time measurement.
;; Exercises the production split-log layout: (1) force one full merged fold,
;; (2) issue :snapshot at one unified-store watermark, (3) append coordination
;; AND telemetry tails, (4) reboot with the default-on snapshot path, then
;; (5) reboot in FRAM_SNAPSHOT_VERIFY mode to diff it against a fresh full fold.
;; Run: bb -cp out /tmp/fram-bench/snapshot-boot.clj <port>
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def port (Integer/parseInt (or (first *command-line-args*) "8935")))
(def home "/tmp/fram-bench/snaphome")
(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def log (str home "/coordination.log"))
(def telemetry (str home "/telemetry.log"))

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
  (proc/process (into ["bin/fram-server" "serve-flat" (str port) log])
                {:dir root :out :inherit :err :inherit
                 :extra-env (merge {"FRAM_TELEMETRY_LOG" telemetry} extra-env)}))

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

;; --- fresh split-corpus copy ------------------------------------------------
(.mkdirs (io/file home))
(doseq [f (.listFiles (io/file home))]
  (io/delete-file f true))
(io/copy (io/file "/tmp/fram-bench/pristine/coordination.log") (io/file log))
(io/copy (io/file "/tmp/fram-bench/pristine/telemetry.log") (io/file telemetry))

;; --- phase 1: cold boot, no sidecar yet -> whole-log fold, establishes the
;;     no-snapshot-available baseline and gets us a live daemon to checkpoint.
(let [p1 (boot! {"FRAM_SNAPSHOT_BOOT" "0"})]
  (let [t1 (wait-ready! 600000)]
    (println (format "phase1 cold-fold-boot-ms          %.0f" t1))
    (println (str "phase1 boot-mode  " (pr-str (:boot (client {:op :status} 60000)))))
    ;; write the checkpoint
    (let [[t res] (ms #(client {:op :snapshot} 120000))]
      (println (format "snapshot-write-ms                 %.0f  %s" t (pr-str (select-keys res [:error])))))
    ;; Small tails in BOTH logs after the checkpoint.
    (dotimes [i 12]
      (client {:op :assert :l (str "@bench-thread-" i) :p "title" :r (str "tail-" i)} 60000)
      (client {:op :assert :l (str "@run:bench-" i) :p "kind" :r "run"} 60000)
      (client {:op :assert :l (str "@run:bench-" i) :p "sample" :r (str i)} 60000))
    (stop! p1)))

(Thread/sleep 500)

;; --- phase 2: unset means ON; replay checkpoint + both bounded tails ----------
(let [p2 (boot! {})]
  (let [t2 (wait-ready! 600000)]
    (println (format "phase2 snapshot-boot-ms           %.0f" t2))
    (let [st (client {:op :status} 60000)]
      (println (str "phase2 boot-mode  " (pr-str (:boot st))))
      (println (str "phase2 version    " (:version st))))
    (stop! p2)))

(Thread/sleep 500)

;; --- phase 3: golden mode independently full-folds and requires empty diff ---
(let [p3 (boot! {"FRAM_SNAPSHOT_VERIFY" "1"})]
  (wait-ready! 600000)
  (let [st (client {:op :status} 60000)]
    (println (str "phase3 boot-both-ways " (pr-str (:boot st))))
    (stop! p3)))
