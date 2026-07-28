(ns fram-fast
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- env-timeout-ms [name default]
  (let [raw (or (System/getenv name) (str default))]
    (when-not (re-matches #"[1-9][0-9]{0,5}" raw)
      (throw
       (ex-info
        (str name " must be an integer from 1 through 999999 milliseconds")
        {:type :invalid-coordinator-timeout :name name :value raw})))
    (Integer/parseInt raw)))

(defn- response-byte-limit []
  (let [raw (or (System/getenv "FRAM_COORD_MAX_RESPONSE_BYTES") "67108864")
        value (when (re-matches #"[1-9][0-9]{0,8}" raw)
                (Long/parseLong raw))]
    (when-not (and value (<= value 67108864))
      (throw
       (ex-info
        "FRAM_COORD_MAX_RESPONSE_BYTES must be an integer from 1 through 67108864"
        {:type :invalid-coordinator-response-limit :value raw})))
    (int value)))

(defn- parse-one-edn! [line]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. line))]
    (let [eof (Object.)
          value (edn/read {:eof eof} reader)
          trailing (edn/read {:eof eof} reader)]
      (when (or (identical? eof value)
                (not (identical? eof trailing)))
        (throw (ex-info "coordinator response is not exactly one EDN form" {})))
      value)))

(defn- read-response-line! [^java.net.Socket socket]
  (.setSoTimeout socket (env-timeout-ms "FRAM_COORD_READ_TIMEOUT_MS" 2000))
  (let [limit (response-byte-limit)
        input (java.io.BufferedInputStream. (.getInputStream socket))
        output (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [byte (.read input)]
        (cond
          (= -1 byte)
          (throw (ex-info "coordinator closed before its response newline" {}))

          (= 10 byte)
          (let [bytes (.toByteArray output)
                decoder
                (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                  (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                  (.onUnmappableCharacter
                   java.nio.charset.CodingErrorAction/REPORT))]
            (parse-one-edn!
             (str (.decode decoder (java.nio.ByteBuffer/wrap bytes)))))

          (>= (.size output) limit)
          (throw
           (ex-info
            (str "coordinator response line exceeds " limit " bytes")
            {:type :coordinator-response-too-large :max-bytes limit}))

          :else
          (do
            (.write output byte)
            (recur)))))))

(defn- local-plaintext? []
  (let [host (System/getenv "FRAM_CONNECT")]
    (and (or (str/blank? host)
             (= host "127.0.0.1")
             (= host "localhost"))
         (not-any?
          #(not (str/blank? (System/getenv %)))
          ["FRAM_TLS_KEYSTORE"
           "FRAM_TLS_TRUSTSTORE"
           "FRAM_TLS_PASS"
           "FRAM_TLS_PASS_FILE"]))))

(defn- local-request-for-log [port log request]
  (with-open [socket (java.net.Socket.)]
    (.connect
     socket
     (java.net.InetSocketAddress.
      (or (not-empty (System/getenv "FRAM_CONNECT")) "127.0.0.1")
      (int port))
     (env-timeout-ms "FRAM_COORD_CONNECT_TIMEOUT_MS" 2000))
    (let [output (.getOutputStream socket)
          envelope
          {:op :for-log
           :expected-log (.getCanonicalPath (io/file log))
           :request request}]
      (.write
       output
       (.getBytes
        (str (pr-str envelope) "\n")
        java.nio.charset.StandardCharsets/UTF_8))
      (.flush output)
      (read-response-line! socket))))

(defn- coord-request-for-log [port log request]
  (if (local-plaintext?)
    (local-request-for-log port log request)
    ((requiring-resolve 'fram.rt/coord-request-for-log)
     port log request)))

(defn- coord-port []
  (if-let [port (System/getenv "FRAM_PORT")]
    (Integer/parseInt port)
    7977))

(defn- log-path []
  (or (not-empty (System/getenv "FRAM_LOG"))
      "coordination.log"))

(defn- coord-show-for-log [port log subject]
  (try
    (let [response
          (coord-request-for-log port log {:op :show :te subject})]
      (when-not (or (= "unknown op" (:error response))
                    (contains? response :reject))
        response))
    (catch Exception _ nil)))

(defn- coord-query-for-log [port log query]
  (try
    (let [response
          (coord-request-for-log port log {:op :query :query query})]
      (when-not (or (= "unknown op" (:error response))
                    (contains? response :reject))
        response))
    (catch Exception _ nil)))

(defn- coord-version-for-log [port log]
  (try
    (let [response
          (coord-request-for-log port log {:op :version})]
      (cond
        (integer? (:version response)) (:version response)
        (= :log-mismatch (:code response)) -2
        :else -3))
    (catch Exception _ -1)))

(defn- rejection-message [rejection]
  (if (sequential? rejection)
    (str/join "; " (map str rejection))
    (str rejection)))

(defn- coord-write-response [response]
  (cond
    (:ok response)
    (str "ok:" (:ok response))

    (= :missing-subject (:code response))
    "missing-subject"

    (= (:reject response) :conflict)
    "conflict"

    (= (:code response) :log-mismatch)
    (str "log-mismatch: expected "
         (:expected-log response)
         "; daemon serves "
         (:served-log response))

    (= "unknown op" (:error response))
    "protocol-incompatible"

    (:reject response)
    (str "reject:" (rejection-message (:reject response)))

    :else
    (str "error:" (pr-str response))))

(defn- coord-write-for-log
  [operation port log subject predicate value base]
  (try
    (coord-write-response
     (coord-request-for-log
      port log
      {:op operation
       :te subject
       :p predicate
       :r value
       :base base
       :frame "agent"}))
    (catch Exception _ "error:nodaemon")))

(defn- coord-assert-for-log
  [port log subject predicate value base]
  (coord-write-for-log
   :assert port log subject predicate value base))

(defn- coord-retract-for-log
  [port log subject predicate value base]
  (coord-write-for-log
   :retract port log subject predicate value base))

(defn- coord-write-existing-for-log
  [operation port log subject predicate value]
  (try
    (coord-write-response
     (coord-request-for-log
      port log
      {:op (if (= operation :assert)
             :assert-existing
             :retract-existing)
       :te subject
       :p predicate
       :r value
       :frame "agent"}))
    (catch Exception _ "error:nodaemon")))

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
  (let [response (coord-show-for-log port log subject)]
    (when (valid-show-response? response) response)))

(defn- coordinator-show
  "One strict daemon-read choke point. A reachable incompatible or malformed
  daemon falls back immediately. An unreachable daemon may use an explicitly
  configured bounded retry window before the same cold fallback."
  [port log subject]
  (or
   (show-once port log subject)
   (when (= -1 (coord-version-for-log port log))
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
        ((requiring-resolve 'fram.fold/->FactOp)
         (:tx op) (:op op) (:l op) (:p op) (:r op)
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

(defn- needs-full-renderer? [_rows provenance?]
  ;; Exact `show` is the everyday read path.  Keep it O(subject), even for
  ;; progress/outcome/bar_evidence: those predicates previously selected the
  ;; provenance renderer implicitly, which scanned both complete logs on every
  ;; invocation.  Provenance remains available through the explicit
  ;; `show <id> --provenance` surface.
  provenance?)

(defn- cold-main! [args]
  (apply (requiring-resolve 'fram.main/-main) args))

(defn- subject-candidate [id]
  (when-not (str/blank? id)
    (if (str/starts-with? id "@") id (str "@" id))))

(defn- exact-spelling? [id]
  (or
   ;; `@` is the exact-reference marker for every Fram subject. Subject kind is
   ;; data, so an explicit telemetry/lease/schema ref gets the same indexed
   ;; coordinator read as an explicit thread ref.
   (str/starts-with? id "@")

   ;; Preserve the convenient bare exact-UUID spelling. Other missing bare
   ;; tokens may be thread prefixes and still belong to the cold resolver.
   (boolean
    (re-matches
     #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
     id))))

(defn fast-query!
  "Serve `query` from the coordinator instead of folding the whole log.

  fram.main/cmd-query does `(fold/fold (read-log log))` before running the
  query, so EVERY query paid a full-corpus fold — measured 13.1s even for a
  predicate matching ZERO rows, because the cost is the fold, not the result.
  The daemon already answers :query over its live snapshot (routing simple
  queries to the index and the rest to the same q engine cold uses), so the
  fold buys nothing an exact read needs.

  Returns false whenever the cold CLI must own the outcome — unreachable or
  incompatible daemon, unparseable EDN, or a query the daemon reports errors
  for — so every diagnostic message stays byte-identical to the cold path."
  [log edn-string]
  (let [parsed (try (edn/read-string edn-string) (catch Exception _ ::unparseable))]
    (if (= ::unparseable parsed)
      false
      (let [response (coord-query-for-log (coord-port) log parsed)
            rows (:ok response)]
        (cond
          (nil? response) false
          ;; Cold prints one "  error: <e>" line per error; rather than
          ;; reproduce that shape here, hand the whole query back so the
          ;; existing renderer owns it.
          (contains? response :error) false
          (not (vector? rows)) false
          :else
          (do
            (if (empty? rows)
              (println "  (no results)")
              (doseq [row rows] (println (str "  " row))))
            true))))))

(defn fast-show!
  "Serve an exact subject from the coordinator's narrow :show projection.
  Returns false when the existing CLI must own fallback or prefix resolution."
  [log id provenance?]
  (if-let [subject (subject-candidate id)]
    (let [bare (str/replace-first subject #"^@" "")
          response (coordinator-show (coord-port) log subject)
          rows (:rows response)]
      (cond
        (nil? response)
        false

        (and (empty? rows) (not (exact-spelling? id)))
        false

        (empty? rows)
        (do
          (println (str "no facts for " subject))
          true)

        :else
        (do
          (if (needs-full-renderer? rows provenance?)
            (let [ops (relevant-ops log subject)
                  run-facts
                  (filterv #(= "run_bar_evidence" (:p %))
                           (:facts
                            ((requiring-resolve 'fram.fold/fold) ops)))
                  facts
                  (into (mapv (fn [[predicate value]]
                                ((requiring-resolve 'fram.kernel/->Fact)
                                 subject predicate value))
                              rows)
                        run-facts)]
              ;; Keep explicit provenance joins byte-identical, but never charge
              ;; their engine and full-log scan to ordinary exact reads.
              (with-redefs-fn
                {(requiring-resolve 'fram.rt/coord-live-facts)
                 (fn [& _] facts)
                 (requiring-resolve 'fram.rt/read-log)
                 (fn [_] ops)}
                #((requiring-resolve 'fram.main/cmd-show)
                  log bare provenance?)))
            (doseq [[predicate value] rows]
              (println (str "  " predicate "  " value))))
          true)))
    false))

(defn- safe-fast-value? [value]
  (or (str/starts-with? value "@")
      (boolean (re-find #"\s" value))))

(defn- write-once [port log operation subject predicate value]
  (let [version (coord-version-for-log port log)]
    (cond
      (= version -1) "nodaemon"
      (= version -2) "log-mismatch"
      (= version -3) "protocol-incompatible"
      (= operation "assert")
      (coord-assert-for-log port log subject predicate value version)
      :else
      (coord-retract-for-log port log subject predicate value version))))

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
    (let [port (coord-port)
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

(defn- write-existing-retry
  [port log operation subject predicate value tries]
  (let [response
        (coord-write-existing-for-log
         (if (= operation "assert") :assert :retract)
         port log subject predicate value)]
    (cond
      (= response "missing-subject") :missing
      (or (= response "error:nodaemon")
          (= response "protocol-incompatible")
          (str/starts-with? response "log-mismatch:"))
      :unavailable
      (and (= response "conflict") (pos? tries))
      (recur port log operation subject predicate value (dec tries))
      :else response)))

(defn fast-write-existing!
  "Write only when the exact subject already exists in the serving projection.
  The coordinator normalizes ambiguous bare values, checks existence, and
  commits atomically, so every exact-ID path needs one request and never
  renders/scans the thread."
  [log operation id predicate value]
  (let [port (coord-port)
        subject (str "@" id)
        response (write-existing-retry
                  port log operation subject predicate value 5)]
    (cond
      (= response :missing)
      :missing

      (= response :unavailable)
      :unavailable

      :else
      (do
        (cond
          (= response "conflict")
          (println
           "rejected: write conflict after retries (another agent is racing this id+pred)")

          (str/starts-with? response "ok:")
          (println
           (str "committed via coordinator (v" (subs response 3) "): "
                id " " predicate " = " value))

          :else
          (println (str "REJECTED by coordinator: " response)))
        :handled))))

(defn -main [& args]
  (let [command (first args)
        log (log-path)
        existing-command?
        (contains?
         #{"tell-existing" "retract-existing" "untell-existing"}
         command)
        existing-operation
        (if (= command "tell-existing") "assert" "retract")
        existing-result
        (when (and existing-command? (>= (count args) 4))
          (fast-write-existing!
           log
           existing-operation
           (nth args 1)
           (nth args 2)
           (nth args 3)))
        handled?
        (cond
          (= existing-result :missing)
          (do
            (println (str "no facts for @" (nth args 1)))
            (System/exit 3))

          (= existing-result :unavailable)
          (do
            (binding [*out* *err*]
              (println "coordinator unavailable for exact existence check"))
            (System/exit 4))

          (= existing-result :handled)
          true

          (and (= command "show") (>= (count args) 2))
          (fast-show! log
                      (nth args 1)
                      (and (>= (count args) 3)
                           (= "--provenance" (nth args 2))))

          (and (= command "query") (>= (count args) 2))
          (fast-query! log (nth args 1))

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
