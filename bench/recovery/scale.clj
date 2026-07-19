;; Recovery scale receipt: compare snapshot+tail boot with a fresh whole-log fold
;; as retained append history and tail size grow. Runs only on scratch files and
;; one caller-selected loopback port >=8400.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(load-file "coord_daemon.clj")

(defn env-longs [name fallback]
  (mapv #(Long/parseLong %)
        (str/split (or (System/getenv name) fallback) #",")))

(def histories (env-longs "RECOVERY_HISTORIES" "1000,10000,50000"))
(def tails (env-longs "RECOVERY_TAILS" "0,100,1000"))
(def port (Integer/parseInt (or (System/getenv "RECOVERY_PORT") "8497")))
(def in-process? (= "1" (System/getenv "RECOVERY_IN_PROCESS")))
(when-not (and (<= 8400 port 65535)
               (every? pos? histories)
               (every? #(<= 0 %) tails))
  (binding [*out* *err*]
    (println "RECOVERY_PORT must be in [8400,65535], histories positive, tails non-negative"))
  (System/exit 2))

(def scratch
  (doto (io/file (str (System/getProperty "java.io.tmpdir")
                      "/fram-recovery-scale-" (System/nanoTime)))
    (.mkdirs)))

(defn line [tx subject value]
  (pr-str {:tx tx :op "assert" :l subject :p "title" :r value
           :ts "bench" :by "bench/recovery/scale"}))

(defn write-range! [path start count]
  (with-open [w (io/writer path :append (pos? start))]
    (doseq [tx (range (inc start) (+ start count 1))]
      (.write w (line tx (str "@bench-" (mod tx 256)) (str "v-" tx)))
      (.write w "\n"))))

(defn ms [start] (/ (double (- (System/nanoTime) start)) 1e6))
(def server (atom nil))
(defn request [m] (if in-process? (handle m) (client port m)))
(defn wait-for-port! []
  (loop [attempt 0]
    (let [r (try (request {:op :status}) (catch Throwable _ nil))]
      (cond r r
            (< attempt 200) (do (Thread/sleep 10) (recur (inc attempt)))
            :else (throw (ex-info "scratch coordinator did not become ready"
                                  {:port port}))))))
(defn ensure-server! []
  (when-not (or in-process? @server)
    (reset! server (future (serve port))))
  (wait-for-port!))

(defn run-case! [history tail]
  (let [path (str (.getPath scratch) "/h" history "-t" tail ".log")]
    (write-range! path 0 history)
    (reset! snapshot-boot-enabled? true)
    (boot-flat! path)
    (ensure-server!)
    (write-snapshot! @co path)
    (let [append-seq (current-seq @co)]
      (write-range! path append-seq tail)
      (let [snapshot-start (System/nanoTime)
            _ (boot-flat! path)
            snapshot-ms (ms snapshot-start)
            snapshot-mode (:mode @last-boot)
            snapshot-tail (:tail-lines @last-boot)
            observed-version (:version (request {:op :status}))
            reconcile (snapshot-reconcile @co path)
            snapshot-state (live-name-triples @co)
            _ (reset! snapshot-boot-enabled? false)
            fold-start (System/nanoTime)
            _ (boot-flat! path)
            fold-ms (ms fold-start)
            fold-state (live-name-triples @co)
            actual-lines (with-open [r (io/reader path)]
                           (count (line-seq r)))
            row {:history-lines history
                 :tail-lines tail
                 :retained-lines actual-lines
                 :snapshot-tail-lines snapshot-tail
                 :snapshot-boot-ms snapshot-ms
                 :whole-fold-ms fold-ms
                 :snapshot-mode snapshot-mode
                 :scratch-port port
                 :transport (if in-process? :in-process :loopback)
                 :observed-version observed-version
                 :reconcile-ok (:ok reconcile)
                 :incremental-equals-fresh (= snapshot-state fold-state)}]
        (println (pr-str row))
        row))))

(println (pr-str {:benchmark "fram recovery scale"
                  :pinned-processors (.availableProcessors (Runtime/getRuntime))
                  :port port
                  :transport (if in-process? :in-process :loopback)
                  :histories histories
                  :tails tails}))
(def rows (vec (for [history histories tail tails] (run-case! history tail))))
(def passed?
  (every? #(and (= :snapshot (:snapshot-mode %))
                (:reconcile-ok %)
                (:incremental-equals-fresh %)
                (integer? (:observed-version %))
                (= port (:scratch-port %)))
          rows))
(println (pr-str {:cases (count rows) :pass passed?}))
(System/exit (if passed? 0 1))
