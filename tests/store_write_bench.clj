;; ============================================================================
;; store_write_bench.clj — write-path K-sweep: writes/sec + p50/p99 vs K writers.
;;
;;   bb tests/store_write_bench.clj [port] [seed-log] [runtime]
;;     port     — scratch daemon port (default 48995; NEVER 7977/48942)
;;     seed-log — optional flat log to COPY as the corpus (realistic-store run);
;;                omitted => empty log (isolates the pure write path)
;;     runtime  — "clojure" (default; matches the live daemons) | "bb"
;;
;; Boots an ISOLATED serve-flat daemon on a THROWAWAY copy under /tmp, then for
;; K in {1,2,4,8,16} runs K concurrent writer threads x N sequential :assert ops
;; each (fresh socket per op — the fram.rt client shape). Distinct (te,p) per op:
;; no OCC contention, no supersede work — the sweep measures the COMMIT PATH
;; (lock + validate + store-mutate + durable append/fsync), nothing else.
;; Emits an aligned table + one EDN line per K for machine diffing.
;; SAFETY: throwaway log only; kills its own daemon on exit.
;; ============================================================================
(require '[clojure.edn :as edn] '[clojure.java.io :as io])
(import '[java.net Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]
        '[java.util.concurrent CountDownLatch])

(def port (or (some-> (first *command-line-args*) Integer/parseInt) 48995))
(def seed (second *command-line-args*))
(def runtime (or (nth *command-line-args* 2 nil) "clojure"))
(when (#{7977 48942} port) (println "refusing live port" port) (System/exit 2))

(def log (str "/tmp/cnf-write-bench-" port "-" (System/currentTimeMillis) ".log"))
(if (and seed (.exists (io/file seed)))
  (io/copy (io/file seed) (io/file log))
  (spit log ""))

(def fram-root (let [d (io/file "coord_daemon.clj")]
                 (if (.exists d) "." (str (System/getProperty "user.home") "/code/fram"))))

;; ---- daemon lifecycle -------------------------------------------------------
(defn start-daemon! []
  (let [cmd (if (= runtime "bb")
              ["bb" "-cp" "out" "coord_daemon.clj" "serve-flat" (str port) log]
              ["clojure" "-M" "coord_daemon.clj" "serve-flat" (str port) log])
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.directory (io/file fram-root))
             (.redirectErrorStream true)
             (.redirectOutput (java.io.File. (str log ".daemon-out"))))]
    (.start pb)))

(defn req1 [m timeout-ms]
  (with-open [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) (int timeout-ms))
    (.setSoTimeout s 30000)
    (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))
          r (BufferedReader. (InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

(defn wait-ready! [proc]
  (loop [i 0]
    (when (> i 600) (println "daemon never became ready; log tail:")
          (println (slurp (str log ".daemon-out"))) (System/exit 1))
    (or (try (:version (req1 {:op :version} 300)) (catch Exception _ nil))
        (do (Thread/sleep 200) (recur (inc i))))))

;; ---- the sweep --------------------------------------------------------------
(defn pct [sorted-v q]
  (nth sorted-v (min (dec (count sorted-v)) (int (Math/floor (* q (count sorted-v)))))))

(defn run-k [k n-per tag]
  (let [start (CountDownLatch. 1)
        done  (CountDownLatch. k)
        lats  (atom [])                                  ; ms per op, all writers
        errs  (atom 0)
        threads (doall
                 (for [w (range k)]
                   (doto (Thread.
                          (fn []
                            (.await start)
                            (let [mine (double-array n-per)]
                              (dotimes [i n-per]
                                (let [t0 (System/nanoTime)
                                      resp (try (req1 {:op :assert
                                                       :te (str "@" tag "k" k "w" w "x" i)
                                                       :p "title" :r (str "v-" w "-" i) :base nil}
                                                      2000)
                                                (catch Exception e {:err (str e)}))]
                                  (when-not (:ok resp) (swap! errs inc))
                                  (aset mine i (/ (- (System/nanoTime) t0) 1e6))))
                              (swap! lats into (vec mine)))
                            (.countDown done)))
                     (.start))))]
    (doall threads)
    (let [t0 (System/nanoTime)]
      (.countDown start)
      (.await done)
      (let [wall-s (/ (- (System/nanoTime) t0) 1e9)
            ls (vec (sort @lats))
            total (* k n-per)]
        {:k k :ops total :errs @errs
         :throughput (/ total wall-s)
         :p50 (pct ls 0.50) :p99 (pct ls 0.99) :max (last ls)}))))

(println (str "=== fram write-path K-sweep — serve-flat daemon (" runtime ") on :" port
              " log=" log (when seed (str " (seeded from " seed ")")) " ==="))
(def daemon (start-daemon!))
(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (.destroy ^Process daemon))))
(let [v (wait-ready! daemon)]
  (println (str "daemon ready, version " v)))

;; warmup (JIT + interning + first-touch)
(dotimes [i 30] (req1 {:op :assert :te (str "@warm" i) :p "title" :r (str "w" i) :base nil} 2000))

(println)
(println (format "%-4s %8s %12s %10s %10s %10s %6s" "K" "ops" "writes/sec" "p50-ms" "p99-ms" "max-ms" "errs"))
(def results
  (vec (for [k [1 2 4 8 16]]
         (let [r (run-k k 40 (str "b" (System/currentTimeMillis)))]
           (println (format "%-4d %8d %12.1f %10.2f %10.2f %10.2f %6d"
                            (:k r) (:ops r) (double (:throughput r))
                            (double (:p50 r)) (double (:p99 r)) (double (:max r)) (:errs r)))
           r))))
(println)
(doseq [r results] (prn (select-keys r [:k :ops :throughput :p50 :p99 :errs])))

;; scaling verdict: throughput must not ANTI-scale (K=16 below K=1 = convoy)
(let [t1 (:throughput (first results)) t16 (:throughput (last results))]
  (println (format "\nscaling K=1 -> K=16: %.1f -> %.1f writes/sec (%.2fx)" t1 t16 (/ t16 t1))))
(.destroy ^Process daemon)
(io/delete-file log true)
(io/delete-file (str log ".daemon-out") true)
(System/exit 0)
