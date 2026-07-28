(ns fram-fast
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fram.fold :as fold]
            [fram.kernel :as k]
            [fram.rt :as rt]))

(defn- retry-window-ms []
  (let [raw (or (System/getenv "FRAM_COORD_RETRY_WINDOW_MS") "0")]
    (when-not (re-matches #"(0|[1-9][0-9]{0,4})" raw)
      (throw
       (ex-info
        "FRAM_COORD_RETRY_WINDOW_MS must be an integer from 0 through 99999 milliseconds"
        {:type :invalid-coordinator-retry-window :value raw})))
    (parse-long raw)))

(defn- retry-delays []
  (loop [remaining (retry-window-ms)
         next-delay 100
         delays []]
    (if (zero? remaining)
      delays
      (let [delay (min remaining next-delay)]
        (recur (- remaining delay)
               (min 6000 (* 2 next-delay))
               (conj delays delay))))))

(def ^:dynamic *sleep!* #(Thread/sleep %))

(defn- valid-show-response? [response]
  (and (map? response)
       (integer? (:version response))
       (vector? (:rows response))
       (every?
        (fn [row]
          (and (vector? row)
               (= 2 (count row))
               (string? (nth row 0))
               (string? (nth row 1))))
        (:rows response))))

(defn- show-once [port log subject]
  (let [response (rt/coord-show-for-log port log subject)]
    (when (valid-show-response? response) response)))

(defn- coordinator-show
  "One strict daemon-read choke point. A reachable incompatible or malformed
  daemon falls back immediately. An unreachable daemon may use an explicitly
  configured bounded retry window before the same cold fallback."
  [port log subject]
  (or
   (show-once port log subject)
   (when (= -1 (rt/coord-version-for-log port log))
     (loop [delays (retry-delays)]
       (when-let [delay (first delays)]
         (*sleep!* delay)
         (or (show-once port log subject)
             (recur (rest delays))))))))

(defn- parse-op [line]
  (try
    (let [op (edn/read-string line)]
      (when (and (map? op)
                 (string? (:l op))
                 (string? (:p op))
                 (contains? op :r))
        (fold/->FactOp (:tx op) (:op op) (:l op) (:p op) (:r op)
                       (or (:frame op) (:by op) "legacy"))))
    (catch Exception _ nil)))

(defn- matching-ops [path needle accept?]
  (let [file (io/file path)]
    (if-not (.isFile file)
      []
      (with-open [reader (io/reader file)]
        (persistent!
         (reduce
          (fn [ops line]
            (if (str/includes? line needle)
              (let [op (parse-op line)]
                (if (and op (accept? op))
                  (conj! ops op)
                  ops))
              ops))
          (transient [])
          (line-seq reader)))))))

(defn- run-record? [subject op]
  (and (= "run_bar_evidence" (:p op))
       (string? (:r op))
       (str/includes? (:r op) subject)))

(defn- relevant-ops [log subject]
  (let [primary
        (matching-ops
         log subject #(or (= subject (:l %)) (run-record? subject %)))
        telemetry (not-empty (System/getenv "FRAM_TELEMETRY_LOG"))]
    (if telemetry
      (into primary
            (matching-ops telemetry subject #(run-record? subject %)))
      primary)))

(def evidence-preds #{"bar_evidence" "progress" "outcome"})

(defn- needs-full-renderer? [rows provenance?]
  (or provenance?
      (some #(contains? evidence-preds (first %)) rows)))

(defn- cold-main! [args]
  (apply (requiring-resolve 'fram.main/-main) args))

(defn fast-show!
  "Serve an exact subject from the coordinator's narrow :show projection.
  Returns false when the existing CLI must own fallback or prefix resolution."
  [log id provenance?]
  (let [subject (str "@" id)
        rows (:rows (coordinator-show (rt/coord-port) log subject))]
    (if-not (seq rows)
      false
      (do
        (if (needs-full-renderer? rows provenance?)
          (let [ops (relevant-ops log subject)
                run-facts
                (filterv #(= "run_bar_evidence" (:p %))
                         (:facts (fold/fold ops)))
                facts
                (into (mapv (fn [[predicate value]]
                              (k/->Fact subject predicate value))
                            rows)
                      run-facts)]
            ;; Keep full provenance joins byte-identical, but pay their engine
            ;; and log-scan cost only when the output can contain a marker.
            (with-redefs [rt/coord-live-facts (fn [& _] facts)
                          rt/read-log (fn [_] ops)]
              ((requiring-resolve 'fram.main/cmd-show)
               log id provenance?)))
          (doseq [[predicate value] rows]
            (println (str "  " predicate "  " value))))
        true))))

(defn- safe-fast-value? [value]
  (or (str/starts-with? value "@")
      (boolean (re-find #"\s" value))))

(defn- write-once [port log operation subject predicate value]
  (let [version (rt/coord-version-for-log port log)]
    (cond
      (= version -1) "nodaemon"
      (= version -2) "log-mismatch"
      (= version -3) "protocol-incompatible"
      (= operation "assert")
      (rt/coord-assert-for-log port log subject predicate value version)
      :else
      (rt/coord-retract-for-log port log subject predicate value version))))

(defn- write-retry [port log operation subject predicate value tries]
  (let [response (write-once port log operation subject predicate value)]
    (if (and (= response "conflict") (pos? tries))
      (recur port log operation subject predicate value (dec tries))
      response)))

(defn fast-write!
  "Skip whole-corpus value inference only when the value cannot be mistaken for
  a bare entity id. Returns false to preserve the existing normalization path."
  [log operation id predicate value]
  (if-not (safe-fast-value? value)
    false
    (let [port (rt/coord-port)
          subject (str "@" id)
          response (write-retry port log operation subject predicate value 5)]
      (if (contains? #{"nodaemon" "log-mismatch" "protocol-incompatible"} response)
        false
        (do
          (cond
            (= response "conflict")
            (println "rejected: write conflict after retries (another agent is racing this id+pred)")

            (str/starts-with? response "ok:")
            (println
             (str "committed via coordinator (v" (subs response 3) "): "
                  id " " predicate " = " value))

            :else
            (println (str "REJECTED by coordinator: " response)))
          true)))))

(defn -main [& args]
  (let [command (first args)
        log (rt/log-path)
        handled?
        (cond
          (and (= command "show") (>= (count args) 2))
          (fast-show! log
                      (nth args 1)
                      (and (>= (count args) 3)
                           (= "--provenance" (nth args 2))))

          (and (contains? #{"tell" "retract" "untell"} command)
               (>= (count args) 4))
          (fast-write! log
                       (if (= command "tell") "assert" "retract")
                       (nth args 1)
                       (nth args 2)
                       (nth args 3))

          :else false)]
    (when-not handled?
      (cold-main! args))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
