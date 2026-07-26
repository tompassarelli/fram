;; Index-architecture benchmark. Boots an ISOLATED scratch daemon on a high port
;; against a copy of the 83MB pre-clean-slate corpus and measures:
;;   A. cold query latency (first query after boot -> whole-corpus projection today)
;;   B. steady-state read latency interleaved with writes (the invalidation defect)
;;   C. write throughput under concurrent read load
;;   D. boot time (fold vs snapshot)
;; Run: bb -cp out /tmp/fram-bench/bench.clj <label> [<port>]
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def label (or (first *command-line-args*) "run"))
(def port (Integer/parseInt (or (second *command-line-args*) "8931")))
(def home "/tmp/fram-bench/home")
(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

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

(defn pctl [xs p]
  (let [s (vec (sort xs)) n (count s)]
    (if (zero? n) nil (nth s (min (dec n) (int (* p n)))))))

;; --- workload queries (shapes north actually issues) -------------------------
;; 1. predicate scan: every thread title (bound predicate, unbound s/o) -> POS
(def q-titles
  {:find "t"
   :rules [{:head {:rel "t" :args [{:var "s"} {:var "r"}]}
            :body [{:rel "triple" :args [{:var "s"} "title" {:var "r"}]}]}]})
;; 2. object scan: everything pointing at one value (bound object) -> OSP
(def q-by-object
  {:find "o"
   :rules [{:head {:rel "o" :args [{:var "s"} {:var "p"}]}
            :body [{:rel "triple" :args [{:var "s"} {:var "p"} "@tom_passarelli"]}]}]})
;; 3. two-literal JOIN (the catalog/board projection shape) -> needs 2 rotations
(def q-join
  {:find "j"
   :rules [{:head {:rel "j" :args [{:var "s"} {:var "ti"}]}
            :body [{:rel "triple" :args [{:var "s"} "kind" "thread"]}
                   {:rel "triple" :args [{:var "s"} "title" {:var "ti"}]}]}]})
;; 4. subject pull: one thread's facts -> SPO
(def q-subject
  {:find "s1"
   :rules [{:head {:rel "s1" :args [{:var "p"} {:var "r"}]}
            :body [{:rel "triple" :args ["@019f9e66-dd56-7326-8a6c-57a27a163956"
                                         {:var "p"} {:var "r"}]}]}]})
;; 5. NON-simple (2 rules) -> forced through q/run-projected, i.e. the full
;;    Datalog projection: this is the query the invalidate-and-refold cache kills.
(def q-scan
  {:find "lead-thread"
   :rules [{:head {:rel "leadp" :args [{:var "s"}]}
            :body [{:rel "triple" :args [{:var "s"} "lead" {:var "l"}]}]}
           {:head {:rel "lead-thread" :args [{:var "s"} {:var "ti"}]}
            :body [{:rel "leadp" :args [{:var "s"}]}
                   {:rel "triple" :args [{:var "s"} "title" {:var "ti"}]}]}]})

(def workload [[:titles q-titles] [:by-object q-by-object] [:join q-join]
               [:subject q-subject] [:scan-2rule q-scan]])

(defn run-query [q] (client {:op :query :query q :query-timeout-ms 60000
                             :query-max-rows 1000000
                             :query-max-response-bytes 200000000}))

(defn result-info [res]
  (cond (:error res) (str "ERR " (pr-str (take 1 (:error res))) " code=" (:code res))
        :else (str (count (:ok res)) " rows engine=" (:engine res))))

;; --- daemon lifecycle --------------------------------------------------------
;; Every run starts from the pristine 83MB corpus copy, so BEFORE and AFTER see
;; byte-identical input (the bench itself writes into the log).
(defn reset-corpus! []
  (doseq [f ["coordination.log" "telemetry.log"]]
    (io/copy (io/file "/tmp/fram-bench/pristine" f) (io/file home f)))
  (doseq [^java.io.File f (.listFiles (io/file home))]
    (when-not (#{"coordination.log" "telemetry.log"} (.getName f))
      (if (.isDirectory f)
        (doseq [^java.io.File g (file-seq f)] (.delete g))
        (.delete f)))))

(defn boot! [extra-env]
  (let [p (proc/process (into ["bin/fram-daemon" "serve-flat" (str port)
                               (str home "/coordination.log")])
                        {:dir root :out :inherit :err :inherit
                         :extra-env (merge {"FRAM_TELEMETRY_LOG" (str home "/telemetry.log")}
                                           extra-env)})]
    p))

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

(def out (atom []))
(defn emit! [k v] (swap! out conj [k v]) (println (format "%-34s %s" (str k) v)))

(defn -main []
  (println (str "=== fram index bench :: " label " :: port " port " ==="))
  (println (str "load: " (str/trim (:out (proc/sh "cat" "/proc/loadavg"))) "  nproc=" (.availableProcessors (Runtime/getRuntime))))
  (when-not (= "1" (System/getenv "BENCH_KEEP_CORPUS")) (reset-corpus!))
  (let [snap? (= "1" (System/getenv "BENCH_SNAPSHOT_BOOT"))
        p (boot! (if snap? {"FRAM_SNAPSHOT_BOOT" "1"} {}))]
    (try
      (let [boot-ms (wait-ready! 600000)]
        (when-not boot-ms (throw (ex-info "daemon never became ready" {})))
        (emit! :boot-ready-ms (format "%.0f" boot-ms))
        (let [st (client {:op :status} 60000)]
          (emit! :version (:version st))
          (emit! :boot-mode (pr-str (:boot st))))

        ;; --- A. COLD query: first touch of each shape after boot -------------
        (doseq [[k q] workload]
          (let [[t res] (ms #(run-query q))]
            (emit! (keyword (str "cold-" (name k) "-ms")) (format "%.0f  (%s)" t (result-info res)))))

        ;; --- B. WARM query (cache hot, no writes between) --------------------
        (doseq [[k q] workload]
          (let [ts (vec (for [_ (range 3)] (first (ms #(run-query q)))))]
            (emit! (keyword (str "warm-" (name k) "-ms")) (format "%.0f" (apply min ts)))))

        ;; --- C. READ-UNDER-WRITE: one write between each read ----------------
        ;; This is the defect: today every write invalidates the projection, so
        ;; each read pays the whole-corpus rebuild again.
        (let [n 8
              wid (str "@bench-" (System/currentTimeMillis))]
          (doseq [[k q] workload]
            (let [ts (vec (for [i (range n)]
                            (do (client {:op :assert :l wid :p "bench_tick"
                                         :r (str (name k) "-" i)} 60000)
                                (first (ms #(run-query q))))))]
              (emit! (keyword (str "under-write-" (name k) "-ms"))
                     (format "min %.0f  p50 %.0f  max %.0f" (apply min ts) (pctl ts 0.5) (apply max ts))))))

        ;; --- D. WRITE throughput (serial, and with a concurrent reader) ------
        (let [wid (str "@bench-w-" (System/currentTimeMillis))
              n 200
              [t _] (ms #(dotimes [i n] (client {:op :assert :l wid :p "w" :r (str i)} 60000)))]
          (emit! :write-serial-per-min (format "%.0f  (%d writes in %.0f ms)" (* 60000.0 (/ n t)) n t)))
        (let [wid (str "@bench-wc-" (System/currentTimeMillis))
              n 200
              stop (atom false)
              reader (future (loop [c 0] (if @stop c
                                           (do (try (run-query q-join) (catch Throwable _ nil))
                                               (recur (inc c))))))
              [t _] (ms #(dotimes [i n] (client {:op :assert :l wid :p "w" :r (str i)} 60000)))]
          (reset! stop true)
          (emit! :write-under-read-per-min (format "%.0f  (%d writes in %.0f ms)" (* 60000.0 (/ n t)) n t))
          (emit! :concurrent-reads-completed (str @reader)))

        ;; --- E. query stop counters (aborts) ---------------------------------
        (let [st (client {:op :status} 60000)]
          (emit! :query-stops (pr-str (:queries st)))
          (emit! :index-state (pr-str (:index st))))

        (spit (str "/tmp/fram-bench/result-" label ".edn") (pr-str (into {} @out)))
        (println (str "wrote /tmp/fram-bench/result-" label ".edn")))
      (finally (stop! p)))))

(-main)
