;; coord_fenced_publish_bench.clj — paired burst receipt for the one-wire
;; :managed-agent-publish operation against the legacy 115-request sequence.
;;
;; Run from the Fram root:
;;   bb -cp out tests/coord_fenced_publish_bench.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def nsizes [1 50 100])
(def trials 3)
(def body-size 27)
(def marker-predicate "identity_manifest_sha256")

(defn free-high-port []
  (loop [port (+ 8300 (rand-int 600))]
    (let [available?
          (try
            (with-open [_ (java.net.ServerSocket. port)] true)
            (catch java.net.BindException _ false))]
      (if available? port (recur (inc port))))))

(defn wire-request [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader.
                      (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn checked [response operation]
  (when-not (:ok response)
    (throw (ex-info "benchmark coordinator operation failed"
                    {:operation operation :response response})))
  response)

(defn sha256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value)
                                   java.nio.charset.StandardCharsets/UTF_8))]
    (format "%064x" (java.math.BigInteger. 1 digest))))

(def identity-preds
  (mapv #(format "bench_identity_%02d" %) (range body-size)))
(def check-preds (conj identity-preds marker-predicate))

(defn body [generation]
  (mapv (fn [predicate]
          {:p predicate :r (str generation "-" predicate)})
        identity-preds))

(defn marker [facts]
  (let [by-pred (into {} (map (juxt :p :r)) facts)]
    (sha256
     (apply str
            (for [predicate (sort identity-preds)]
              (str predicate "\u0000" (get by-pred predicate) "\n"))))))

(defn resource [subject]
  (str "managed-agent-write:" (sha256 subject)))

(defn resolved [port subject predicate]
  (set (:values (wire-request port {:op :resolved
                                    :te subject :p predicate}))))

(defn atomic-publish! [port subject generation]
  (let [facts (body generation)
        response
        (wire-request
         port
         {:op :managed-agent-publish
          :te subject
          :holder (str "managed-agent-writer:" generation)
          :ttl-ms 60000
          :facts facts
          :identity-preds identity-preds
          :guard-preds []
          :manifest-sha256 (marker facts)})]
    (checked response :managed-agent-publish)
    {:wire-ops 1}))

(defn legacy-publish! [port subject generation]
  (let [facts (body generation)
        desired-marker (marker facts)
        res (resource subject)
        holder (str "legacy-" generation)
        wire-ops (atom 0)
        request!
        (fn [request]
          (swap! wire-ops inc)
          (wire-request port request))
        lease (checked
               (request! {:op :acquire-lease :res res
                          :holder holder :ttl-ms 60000})
               :acquire-lease)
        fence {:res res :holder holder :epoch (:epoch lease)}]
    (try
      ;; 28 clean-gate reads.
      (doseq [predicate check-preds]
        (when (seq (:values
                    (request! {:op :resolved :te subject :p predicate})))
          (throw (ex-info "legacy benchmark subject was not fresh"
                          {:subject subject :predicate predicate}))))

      ;; 27 fenced body writes.
      (doseq [{:keys [p r]} facts]
        (checked
         (request! (merge {:op :assert-with-fence
                           :te subject :p p :r r}
                          fence))
         [:assert-with-fence p]))

      ;; First 28-request readback pass: body exact, marker still absent.
      (when-not
       (let [desired (into {} (map (juxt :p :r)) facts)]
         (and
          (every?
           (fn [predicate]
             (= #{(get desired predicate)}
                (set (:values
                      (request! {:op :resolved :te subject :p predicate})))))
           identity-preds)
          (empty?
           (:values
            (request! {:op :resolved :te subject
                       :p marker-predicate})))))
        (throw (ex-info "legacy pre-marker readback mismatch"
                        {:subject subject})))

      ;; One marker write.
      (checked
       (request! (merge {:op :assert-with-fence
                         :te subject :p marker-predicate
                         :r desired-marker}
                        fence))
       :assert-marker)

      ;; Second 28-request readback pass: exact committed projection.
      (when-not
       (let [desired (into {} (map (juxt :p :r)) facts)]
         (and
          (every?
           (fn [predicate]
             (= #{(get desired predicate)}
                (set (:values
                      (request! {:op :resolved :te subject :p predicate})))))
           identity-preds)
          (= #{desired-marker}
             (set (:values
                   (request! {:op :resolved :te subject
                              :p marker-predicate}))))))
        (throw (ex-info "legacy final readback mismatch" {:subject subject})))

      ;; One explicit fence check makes the measured legacy sequence exactly
      ;; 115 requests including acquire/release.
      (when-not
       (:fence-ok
        (request! (merge {:op :fence-ok} fence)))
        (throw (ex-info "legacy fence invalid before release"
                        {:subject subject})))
      (finally
        (checked
         (request! {:op :release-lease :res res :holder holder
                    :epoch (:epoch lease)})
         :release-lease)))
    (when-not (= 115 @wire-ops)
      (throw (ex-info "legacy benchmark request count drifted"
                      {:expected 115 :observed @wire-ops})))
    {:wire-ops @wire-ops}))

(defn percentile [values q]
  (let [sorted (vec (sort values))
        index (int (Math/ceil (* q (count sorted))))]
    (nth sorted (max 0 (dec index)))))

(defn burst [port mode n trial]
  (let [gate (promise)
        jobs
        (mapv
         (fn [i]
           (future
             @gate
             (let [generation (format "%s-t%d-n%d-i%d"
                                      (name mode) trial n i)
                   subject (str "@agent:bench-" generation)
                   started (System/nanoTime)]
               (try
                 (let [result
                       (case mode
                         :atomic (atomic-publish! port subject generation)
                         :legacy (legacy-publish! port subject generation))]
                   (assoc result
                          :ok true
                          :ms (/ (- (System/nanoTime) started) 1000000.0)))
                 (catch Throwable error
                   {:ok false :error (.getMessage error)})))))
         (range n))]
    (deliver gate true)
    (let [results (mapv deref jobs)
          successes (filterv :ok results)
          timings (mapv :ms successes)]
      {:n n
       :trial trial
       :mode mode
       :p50-ms (when (seq timings) (percentile timings 0.50))
       :p95-ms (when (seq timings) (percentile timings 0.95))
       :failures (- n (count successes))
       :wire-ops (reduce + 0 (map :wire-ops successes))})))

(defn eventually [f]
  (loop [remaining 400]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 10) (recur (dec remaining))))))

(let [port (free-high-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-fenced-publish-bench"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log "")
      singles (str/join " " check-preds)
      daemon
      (proc/process
       {:dir root :out :string :err :string
        :extra-env {"FRAM_SINGLE_VALUED" singles}}
       "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
       (str port) (.getPath log))]
  (try
    (when (or (< port 7978) (= port 7977))
      (throw (ex-info "benchmark refused unsafe coordinator port"
                      {:port port})))
    (when-not
     (eventually
      #(integer? (:version (wire-request port {:op :version}))))
      (throw (ex-info "scratch coordinator did not start"
                      {:port port :log (.getPath log)})))
    (println (format "scratch_port=%d scratch_log=%s trials=%d"
                     port (.getPath log) trials))
    (println "mode\tN\ttrial\tp50_ms\tp95_ms\tfailures\twire_ops")
    (let [rows
          (vec
           (mapcat
            (fn [trial]
              (mapcat
               (fn [n]
                 ;; Alternate pair order by trial to reduce systematic warm/order
                 ;; bias while keeping both modes on the same daemon.
                 (let [modes (if (odd? trial)
                               [:atomic :legacy]
                               [:legacy :atomic])]
                   (mapv #(burst port % n trial) modes)))
               nsizes))
            (range 1 (inc trials))))]
      (doseq [{:keys [mode n trial p50-ms p95-ms failures wire-ops]} rows]
        (println
         (format "%s\t%d\t%d\t%.3f\t%.3f\t%d\t%d"
                 (name mode) n trial
                 (double p50-ms) (double p95-ms)
                 failures wire-ops)))
      (println "summary\tN\tatomic_p50_ms\tlegacy_p50_ms\tspeedup")
      (doseq [n nsizes
              :let [atomic
                    (percentile
                     (mapv :p50-ms
                           (filterv #(and (= :atomic (:mode %))
                                          (= n (:n %)))
                                    rows))
                     0.50)
                    legacy
                    (percentile
                     (mapv :p50-ms
                           (filterv #(and (= :legacy (:mode %))
                                          (= n (:n %)))
                                    rows))
                     0.50)]]
        (println
         (format "summary\t%d\t%.3f\t%.3f\t%.2fx"
                 n (double atomic) (double legacy)
                 (/ (double legacy) (double atomic)))))
      (when (some #(pos? (:failures %)) rows)
        (throw (ex-info "benchmark observed failed admissions"
                        {:failures (reduce + (map :failures rows))}))))
    (finally
      (proc/destroy-tree daemon)
      (try @daemon (catch Exception _ nil)))))
