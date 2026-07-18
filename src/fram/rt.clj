(ns fram.rt
  "Host-interop runtime for Fram's Beagle modules — the irreducible Clojure
  layer (file IO, log read/write, string ops) the .bclj `declare-extern`s bind
  to. Beagle owns the typed logic; this owns the host calls.

  Paths default to the current working directory (./threads, ./facts.log) and
  are overridable via FRAM_THREADS / FRAM_LOG."
  (:refer-clojure :exclude [slurp])   ; fram.rt/slurp wraps clojure.core/slurp; keep the JVM daemon's stderr clean
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as cheshire]
            [fram.fold :as fold]
            [fram.kernel :as kernel]))

;; Serialize any value (records serialize as objects keyed by field name; vectors
;; as arrays) to JSON — the engine's structured-output path for the MCP edge.
(defn to-json [x] (cheshire/generate-string x))

;; --- file IO ----------------------------------------------------------------

(defn slurp [path] (clojure.core/slurp path))

(defn list-md
  "Absolute paths of *.md directly under dir, sorted, excluding CLAUDE.md."
  [dir]
  (->> (.listFiles (io/file dir))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % ".md"))
       (remove #(str/ends-with? % "CLAUDE.md"))
       sort
       vec))

(defn spit-file [path content]
  ;; exported .md are a read-only projection of the log: write 0444 so a hand-edit
  ;; fails loud (permission denied) instead of silently stranding the log/file sync.
  ;; setWritable first so re-export can overwrite its own prior read-only output.
  (let [f (io/file path)]
    (when (.exists f) (.setWritable f true))
    (spit path content)
    (.setReadOnly f))
  nil)
(defn ensure-dir [dir] (.mkdirs (io/file dir)) nil)
(defn file-slug
  "Slug portion of a thread filename: '<id>-<slug>.md' -> '<slug>'."
  [path]
  (let [base (str/replace (.getName (io/file path)) #"\.md$" "")
        dash (str/index-of base "-")]
    (if dash (subs base (inc dash)) base)))

;; --- string ops the parser needs -------------------------------------------

(defn split-on [s sep]
  (vec (str/split s (re-pattern (java.util.regex.Pattern/quote sep)) -1)))
(defn str-index-of [s sub] (str/index-of s sub))
(defn split-comma [s]
  (->> (str/split s #",") (map str/trim) (remove str/blank?) vec))
(defn today-iso [] (str (java.time.LocalDate/now)))
(defn str-lt? [a b] (neg? (compare a b)))

;; split a triple line "<predicate><ws><object...>" into [pred obj]; obj may
;; contain spaces (it's the rest of the line). Blank/garbage -> [line ""].
(defn split-kv [line]
  (let [t (str/trim line)
        m (re-find #"^(\S+)\s+(.*)$" t)]
    (if m [(nth m 1) (nth m 2)] [t ""])))

;; --- fact-native triple-file value (de)serialization -----------------------
;; A fact in a triple file is either a ref (@id, handled by the caller)
;; or a literal. Literals are quoted/unquoted via EDN — bulletproof escaping
;; (the same pr-str/read-string pair the log uses), so no hand-rolled quoter can
;; ever emit something a real parser rejects.
(defn edn-quote [s] (pr-str s))
(defn edn-unquote [s] (edn/read-string s))

;; --- thread id: human-grouped, fixed-width, opaque key ----------------------
;; 2026-06-15-150040 (yyyy-MM-dd-HHmmss). Dashes for glance-readability; fixed
;; width so id<->slug splits by position; sorts chronologically as a plain string.
(defn- fmt-id [n]
  (let [s (str n)]
    (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8) "-" (subs s 8 14))))

(defn now-id []
  (fmt-id (.format (java.time.LocalDateTime/now)
                   (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))))

;; Advance a dashed id by one second (same fixed-width format). Used to mint a
;; collision-free session id against the fact graph (sessions live in the log,
;; not as files, so they can't use the file-based reserve-id).
(defn bump-id [id]
  (let [fmt (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
        dt (java.time.LocalDateTime/parse (str/replace id "-" "") fmt)]
    (fmt-id (.format (.plusSeconds dt 1) fmt))))

;; The thread id, not the filename, is the entity key — two captures in the same
;; second produce distinct filenames (slugs differ) but would COLLIDE on id.
(defn- id-taken? [dir id]
  (let [f (io/file dir)]
    (boolean
     (when (.isDirectory f)
       (some (fn [n] (or (str/starts-with? n (str id "-")) (= n (str id ".md"))))
             (map #(.getName ^java.io.File %) (.listFiles f)))))))

;; Atomically reserve a free id ACROSS concurrent capture processes: bump past
;; any id already asserted by a file (id-taken?) AND any in-flight reservation —
;; the latter via an exclusive CREATE_NEW of a per-id lock dotfile, which two
;; racers in the same second cannot both win. Caller writes <id>-<slug>.md then
;; release-id. (A scan-then-write alone has a TOCTOU window two distinct-slug
;; captures slip through, silently folding into one entity on import.)
(defn- lock-path [dir id] (str dir "/." id ".lock"))
(defn reserve-id [dir]
  (loop [n (Long/parseLong (.format (java.time.LocalDateTime/now)
                                    (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))]
    (let [id (fmt-id n)
          ;; try returns the id on a clean exclusive create, nil if the id is
          ;; taken or a racer won the lock — recur OUTSIDE the try (recur cannot
          ;; cross a try/catch boundary).
          got (when-not (id-taken? dir id)
                (try (java.nio.file.Files/createFile
                      (.toPath (io/file (lock-path dir id)))
                      (make-array java.nio.file.attribute.FileAttribute 0))
                     id
                     (catch java.nio.file.FileAlreadyExistsException _ nil)))]
      (if got got (recur (inc n))))))
(defn release-id [dir id] (.delete (io/file (lock-path dir id))) nil)

(defn slugify [title]
  (let [base (-> (str/lower-case (str title))
                 (str/replace #"[^a-z0-9]+" "_")
                 (str/replace #"^_+" "")
                 (str/replace #"_+$" ""))
        capped (if (> (count base) 60) (subs base 0 60) base)
        clean (str/replace capped #"_+$" "")]
    (if (str/blank? clean) "untitled" clean)))

;; --- portable defaults ------------------------------------------------------

(defn threads-dir []
  (or (System/getenv "FRAM_THREADS")
      (str (System/getProperty "user.dir") "/threads")))
(defn log-path []
  (or (System/getenv "FRAM_LOG")
      (str (System/getProperty "user.dir") "/facts.log")))
(defn time-dir []
  (or (System/getenv "FRAM_TIME_DIR")
      (str (System/getProperty "user.dir") "/time")))

;; capture provenance: generic fallbacks here; a consumer (e.g. the life-os
;; wrapper) exports its own conventions via these env vars.
(defn getenv-or [k fallback] (or (System/getenv k) fallback))

;; --- the fact-op log ------------------------------------------------------
;; one EDN map per line: {:tx Int :op "assert"|"retract" :l :p :r :frame :ts}.
;; :ts is the wall-clock commit instant — PROVENANCE ONLY, never a sort key
;; (:tx is the sole total order; wall-clock is non-monotonic under NTP/skew).
;; Absent on pre-cutover lines; read-log ignores it, so old logs read unchanged.

(defn now-ts [] (str (java.time.Instant/now)))

;; parse an EDN string from the CLI (a `query`/`call` argument) into data;
;; nil on parse failure so the caller can report it instead of crashing.
(defn parse-edn [s] (try (edn/read-string s) (catch Exception _ nil)))

(defn read-log [path]
  (if (.exists (io/file path))
    (->> (str/split-lines (clojure.core/slurp path))
         (remove str/blank?)
         (keep (fn [line]
                 (try (let [m (edn/read-string line)]
                        (fold/->FactOp (:tx m) (:op m) (:l m) (:p m) (:r m) (or (:frame m) (:by m) "legacy")))
                      (catch Exception _ nil))))
         vec)
    []))

;; Read the configured split corpus as one transaction-ordered history. Callers
;; that need a complete logical store (MCP/query projections) use this; write
;; paths continue to name their single physical destination explicitly.
(defn read-configured-logs []
  (let [primary (log-path)
        primary-file (.getAbsoluteFile (io/file primary))
        inferred (when (= "coordination.log" (.getName primary-file))
                   (str (io/file (.getParentFile primary-file) "telemetry.log")))
        coord (read-log primary)
        telemetry (or (System/getenv "FRAM_TELEMETRY_LOG") inferred)]
    (if telemetry
      (vec (sort-by #(or (:tx %) 0) (into coord (read-log telemetry))))
      coord)))

(defn write-log [path fact-ops]
  (let [ts (now-ts)                                  ; one batch instant (this import/rewrite)
        lines (map (fn [a]
                     (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a) :ts ts}))
                   fact-ops)]
    (spit path (str (str/join "\n" lines) "\n"))))

(defn append-fact-op [path a]
  (spit path (str (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a) :ts (now-ts)}) "\n")
        :append true))

;; --- entity history: the time-travel read (a log scan, not a fold) ----------
;; Every assert/retract touching one entity, in tx order, with its commit
;; instant. The CHEAP half of time-travel — O(log lines), no re-fold — and the
;; query people actually reach for ("how did this get to its current state?").
;; (General "state as-of tx N" is the expensive re-fold; deferred until needed.)
(defn history [path id]
  (if (str/blank? id)
    (println "usage: history <id>")
    (let [te (if (str/starts-with? id "@") id (str "@" id))
          entries (if (.exists (io/file path))
                    (->> (str/split-lines (clojure.core/slurp path))
                         (remove str/blank?)
                         (keep (fn [line] (try (edn/read-string line) (catch Exception _ nil))))
                         (filter (fn [m] (= (:l m) te)))
                         ;; :tx is the sole order; coerce so a corrupt non-numeric :tx
                         ;; can't crash the comparator (Long-vs-String) — bad line floats to 0.
                         (sort-by (fn [m] (let [t (:tx m)] (if (number? t) t 0))))
                         vec)
                    [])]
      (if (empty? entries)
        (println (str "no history for " te))
        (do
          (println (str "history of " te " — " (count entries) " event(s)   (when · tx · who · what)"))
          (doseq [m entries]
            (let [raw (:ts m)
                  ;; real ISO instant, else "—" (covers missing :ts and the legacy "t" placeholder)
                  ts (if (and (string? raw) (str/includes? raw "T")) raw "—")
                  who (or (:frame m) (:by m) "?")
                  txn (if (number? (:tx m)) (str "tx" (:tx m)) "tx?")
                  op (if (= (:op m) "retract") "retract" "assert ")
                  flat (str/replace (str (:r m)) #"\s+" " ")
                  rv (if (> (count flat) 72) (str (subs flat 0 71) "…") flat)]
              (println (str "  " (format "%-30s" ts) "  " (format "%-5s" txn) "  "
                            op "  " (format "%-8s" who) "  " (:p m) " = " rv)))))))))

;; --- coordinator client: write THROUGH the daemon (safe concurrent path) -----
;; One request/response over the local socket. The daemon serializes writes
;; (optimistic base_version + obligation rules), so this is the safe multi-agent
;; write path — unlike append-fact-op, which writes the log directly.

;; client-side mutual TLS: present FRAM_TLS_KEYSTORE, verify the coordinator against
;; FRAM_TLS_TRUSTSTORE. Works on babashka (client SSL classes are present; only the
;; SERVER-side SSLServerSocket is absent, which is why the daemon runs on the JVM).
(defn- client-ssl-context [ks ts pass]
  (let [pw (.toCharArray ^String pass)
        load (fn [p] (with-open [in (io/input-stream p)]
                       (doto (java.security.KeyStore/getInstance "PKCS12") (.load in pw))))
        kmf (doto (javax.net.ssl.KeyManagerFactory/getInstance (javax.net.ssl.KeyManagerFactory/getDefaultAlgorithm))
              (.init (load ks) pw))
        tmf (doto (javax.net.ssl.TrustManagerFactory/getInstance (javax.net.ssl.TrustManagerFactory/getDefaultAlgorithm))
              (.init (load ts)))]
    (doto (javax.net.ssl.SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil))))

;; connect to the coordinator: FRAM_CONNECT host (default 127.0.0.1); mutual TLS when
;; FRAM_TLS_* is set, else plaintext (the unchanged loopback default).
(defn- connect-host []
  (let [h (System/getenv "FRAM_CONNECT")] (if (str/blank? h) "127.0.0.1" h)))

(defn- coord-timeout-ms [name default]
  (let [raw (or (System/getenv name) (str default))]
    (when-not (re-matches #"[1-9][0-9]{0,5}" raw)
      (throw
       (ex-info
        (str name " must be an integer from 1 through 999999 milliseconds")
        {:type :invalid-coordinator-timeout :name name :value raw})))
    (Integer/parseInt raw)))

(defn- coord-response-byte-limit []
  (let [raw (or (System/getenv "FRAM_COORD_MAX_RESPONSE_BYTES") "67108864")
        value (when (re-matches #"[1-9][0-9]{0,8}" raw)
                (Long/parseLong raw))]
    (when-not (and value (<= value 67108864))
      (throw
       (ex-info
        "FRAM_COORD_MAX_RESPONSE_BYTES must be an integer from 1 through 67108864"
        {:type :invalid-coordinator-response-limit :value raw})))
    (int value)))

(defn- coord-response-timeout! [timeout cause]
  (throw
   (ex-info "coordinator response deadline exceeded"
            {:type :coordinator-response-timeout
             :timeout-ms timeout}
            cause)))

(defn- decode-coord-utf8! [bytes]
  (try
    (let [decoder
          (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
            (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
            (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
      (str (.decode decoder (java.nio.ByteBuffer/wrap bytes))))
    (catch java.nio.charset.CharacterCodingException error
      (throw
       (ex-info "coordinator response line is not valid UTF-8"
                {:type :malformed-coordinator-utf8}
                error)))))

(defrecord CoordinatorReader [socket input buffer bounds])

(defn coordinator-reader [socket]
  (->CoordinatorReader
   socket
   (.getInputStream socket)
   (byte-array 65536)
   (int-array 2)))

(defn- as-coordinator-reader [source]
  (if (instance? CoordinatorReader source)
    source
    (coordinator-reader source)))

(defn- coord-newline-offset [buffer start end]
  (.indexOf
   (String.
    buffer
    start
    (- end start)
    java.nio.charset.StandardCharsets/ISO_8859_1)
   "\n"))

(defn- finish-coord-line! [output]
  (let [line (decode-coord-utf8! (.toByteArray output))]
    (if (str/ends-with? line "\r")
      (subs line 0 (dec (count line)))
      line)))

(defn- arm-coord-deadline! [socket deadline timeout]
  (let [remaining-ns (- deadline (System/nanoTime))]
    (when-not (pos? remaining-ns)
      (coord-response-timeout! timeout nil))
    (.setSoTimeout
     socket
     (int (max 1 (quot (+ remaining-ns 999999) 1000000))))))

(defn- read-coord-line! [source deadline timeout eof-ok?]
  (let [{:keys [socket input buffer bounds]} (as-coordinator-reader source)
        buffer-size (alength buffer)
        limit (coord-response-byte-limit)
        output (java.io.ByteArrayOutputStream.)]
    (loop []
      (when (and deadline (not (pos? (- deadline (System/nanoTime)))))
        (coord-response-timeout! timeout nil))
      (let [start (aget bounds 0)
            end (aget bounds 1)]
        (if (< start end)
          (let [available (- end start)
                newline-offset (coord-newline-offset buffer start end)
                take-bytes (if (neg? newline-offset) available newline-offset)
                total (+ (.size output) take-bytes)]
            (when (> total limit)
              (throw
               (ex-info
                (str "coordinator response line exceeds " limit " bytes")
                {:type :coordinator-response-too-large
                 :max-bytes limit})))
            (.write output buffer start take-bytes)
            (if (neg? newline-offset)
              (do
                (aset-int bounds 0 end)
                (recur))
              (do
                (aset-int bounds 0 (+ start newline-offset 1))
                (finish-coord-line! output))))
          (do
            (when deadline
              (arm-coord-deadline! socket deadline timeout))
            (let [read-count
                  (try
                    (.read input buffer 0 buffer-size)
                    (catch java.net.SocketTimeoutException error
                      (coord-response-timeout! timeout error)))]
              (cond
                (= -1 read-count)
                (if (and eof-ok? (zero? (.size output)))
                  nil
                  (throw
                   (ex-info
                    (if (zero? (.size output))
                      "coordinator closed before sending a response line"
                      "coordinator closed during a response line")
                    {:type (if (zero? (.size output))
                             :coordinator-response-closed
                             :coordinator-response-truncated)
                     :bytes (.size output)})))

                (zero? read-count)
                (recur)

                :else
                (do
                  (aset-int bounds 0 0)
                  (aset-int bounds 1 read-count)
                  (recur))))))))))

(defn read-coord-response-line!
  "Read one bounded UTF-8 response line under an absolute total deadline."
  [source]
  (let [timeout (coord-timeout-ms "FRAM_COORD_READ_TIMEOUT_MS" 2000)]
    (read-coord-line!
     source
     (+ (System/nanoTime) (* 1000000 (long timeout)))
     timeout
     false)))

(defn read-coord-stream-line!
  "Read one bounded UTF-8 event line without an idle deadline.
   A persistent reader retains bytes following the newline for the next event."
  [source]
  (let [reader (as-coordinator-reader source)]
    (.setSoTimeout (:socket reader) 0)
    (read-coord-line! reader nil nil true)))

(defn- ensure-coord-terminal-eof! [reader deadline timeout]
  (let [{:keys [socket input buffer bounds]} reader]
    (loop []
      (let [start (aget bounds 0)
            end (aget bounds 1)]
        (when (< start end)
          (throw
           (ex-info "coordinator sent more than one terminal response frame"
                    {:type :multiple-coordinator-response-frames
                     :surplus-bytes (- end start)})))
        (arm-coord-deadline! socket deadline timeout)
        (let [read-count
              (try
                (.read input buffer 0 (alength buffer))
                (catch java.net.SocketTimeoutException error
                  (coord-response-timeout! timeout error)))]
          (cond
            (= -1 read-count) nil
            (zero? read-count) (recur)
            :else
            (throw
             (ex-info "coordinator sent more than one terminal response frame"
                      {:type :multiple-coordinator-response-frames
                       :surplus-bytes read-count}))))))))

(defn- read-coord-terminal-line-with-timeout! [reader timeout]
  (let [deadline (+ (System/nanoTime) (* 1000000 (long timeout)))
        line (read-coord-line! reader deadline timeout false)]
    (ensure-coord-terminal-eof! reader deadline timeout)
    line))

(defn- read-coord-terminal-line! [reader]
  (read-coord-terminal-line-with-timeout!
   reader
   (coord-timeout-ms "FRAM_COORD_READ_TIMEOUT_MS" 2000)))

(defn- malformed-coord-line! [message line error]
  (throw
   (ex-info
    message
    {:type :malformed-coordinator-response
     :line-bytes
     (count (.getBytes
             (str line)
             java.nio.charset.StandardCharsets/UTF_8))}
    error)))

(defn parse-coord-edn-line! [line]
  (try
    (with-open [reader (java.io.PushbackReader. (java.io.StringReader. line))]
      (let [eof (Object.)
            value (edn/read {:eof eof} reader)
            trailing (edn/read {:eof eof} reader)]
        (when (or (identical? eof value)
                  (not (identical? eof trailing)))
          (throw (ex-info "not exactly one EDN form" {})))
        value))
    ;; Hostile bounded input can still overflow a recursive parser. Normalize that
    ;; one Error alongside ordinary parse Exceptions, but let VM-fatal Errors pass.
    (catch StackOverflowError error
      (malformed-coord-line!
       "coordinator response line is not exactly one valid EDN form"
       line
       error))
    (catch Exception error
      (malformed-coord-line!
       "coordinator response line is not exactly one valid EDN form"
       line
       error))))

(defn- parse-coord-json-line! [line]
  (try
    (with-open [reader (java.io.StringReader. line)]
      (let [values (vec (take 2 (cheshire/parsed-seq reader)))]
        (when-not (= 1 (count values))
          (throw (ex-info "not exactly one JSON value" {})))
        (first values)))
    (catch StackOverflowError error
      (malformed-coord-line!
       "coordinator response line is not exactly one valid JSON value"
       line
       error))
    (catch Exception error
      (malformed-coord-line!
       "coordinator response line is not exactly one valid JSON value"
       line
       error))))

(defn- run-with-coord-watchdog!
  [closeable timeout timeout-message timeout-type operation]
  (let [state (atom :armed)
        watchdog
        (future
          (try
            (Thread/sleep timeout)
            (when (compare-and-set! state :armed :expired)
              (.close closeable))
            (catch InterruptedException _ nil)
            (catch Throwable _ nil)))]
    (try
      (let [result (operation)]
        (when-not (compare-and-set! state :armed :complete)
          (throw
           (ex-info timeout-message
                    {:type timeout-type
                     :timeout-ms timeout})))
        result)
      (catch Throwable error
        (if (= :expired @state)
          (throw
           (ex-info timeout-message
                    {:type timeout-type
                     :timeout-ms timeout}
                    error))
          (do
            (compare-and-set! state :armed :complete)
            (throw error))))
      (finally
        (future-cancel watchdog)))))

(defn- coord-tls-handshake! [socket]
  (let [timeout (coord-timeout-ms "FRAM_COORD_HANDSHAKE_TIMEOUT_MS" 2000)]
    ;; One timeout owns the handshake: SO_TIMEOUT bounds an individual SSL read
    ;; and the watchdog bounds the whole exchange. Request/facts readers replace
    ;; it with their own absolute deadline after the handshake succeeds.
    (.setSoTimeout socket timeout)
    (run-with-coord-watchdog!
     socket
     timeout
     "coordinator TLS handshake deadline exceeded"
     :coordinator-handshake-timeout
     (fn []
       (.startHandshake socket)
       nil))))

(defn- coord-socket [host port]
  (let [ks (System/getenv "FRAM_TLS_KEYSTORE") ts (System/getenv "FRAM_TLS_TRUSTSTORE")
        pass (or (System/getenv "FRAM_TLS_PASS")
                 (when-let [f (System/getenv "FRAM_TLS_PASS_FILE")] (str/trim (slurp f))))]
    ;; fail CLOSED on a partial config — a typo'd/missing var must NOT silently
    ;; downgrade a "secure" link to plaintext.
    (when (and (or ks ts pass) (not (and ks ts pass)))
      (binding [*out* *err*]
        (println "FATAL: FRAM_TLS_* partially set — need ALL of FRAM_TLS_KEYSTORE / FRAM_TLS_TRUSTSTORE / FRAM_TLS_PASS (refusing to connect in plaintext)"))
      (System/exit 2))
    (if (and ks ts pass)
      (let [s (.createSocket (.getSocketFactory (client-ssl-context ks ts pass)))]
        (try
          (.connect s
                    (java.net.InetSocketAddress. ^String host (int port))
                    (coord-timeout-ms "FRAM_COORD_CONNECT_TIMEOUT_MS" 2000))
          (coord-tls-handshake! s)
          s
          (catch Throwable error
            (try (.close s) (catch Throwable _ nil))
            (throw error))))
      (let [s (java.net.Socket.)]
        (try
          (.connect s
                    (java.net.InetSocketAddress. ^String host (int port))
                    (coord-timeout-ms "FRAM_COORD_CONNECT_TIMEOUT_MS" 2000))
          s
          (catch Throwable error
            (try (.close s) (catch Throwable _ nil))
            (throw error)))))))

(defn- coord-rt [port req]
  (with-open [s (coord-socket (connect-host) port)]
    (let [w (.getOutputStream s)
          reader (coordinator-reader s)]
      (.write w
              (.getBytes (str (pr-str req) "\n")
                         java.nio.charset.StandardCharsets/UTF_8))
      (.flush w)
      (parse-coord-edn-line! (read-coord-terminal-line! reader)))))

;; Protocol-level corpus identity. The distinct :for-log operation is deliberate:
;; an older daemon rejects it as unknown instead of ignoring an optional field and
;; mutating the wrong corpus. Low-level legacy functions below remain available for
;; compatibility; CLI/MCP entry points use the explicit *-for-log variants.
(defn canonical-log-path [path]
  (.getCanonicalPath (io/file path)))

(defn- log-envelope [log req]
  (cond-> {:op :for-log
           :expected-log (canonical-log-path log)
           :request req}
    (contains? req :fmt) (assoc :fmt (:fmt req))))

(defn coord-request-for-log [port log req]
  (coord-rt port (log-envelope log req)))

(defn coord-version [port]
  (try (let [resp (coord-rt port {:op :version})] (or (:version resp) -1))
       (catch Exception _ -1)))

(defn coord-version-for-log [port log]
  (try
    (let [resp (coord-request-for-log port log {:op :version})]
      (cond
        (integer? (:version resp)) (:version resp)
        (= :log-mismatch (:code resp)) -2
        :else -3))
    (catch Exception _ -1)))

(defn- reject-message [rejection]
  (if (sequential? rejection)
    (str/join "; " (map str rejection))
    (str rejection)))

(defn- coord-write-response [resp]
  (cond
    (:ok resp) (str "ok:" (:ok resp))
    (= (:reject resp) :conflict) "conflict"
    (= (:code resp) :log-mismatch)
    (str "log-mismatch: expected " (:expected-log resp)
         "; daemon serves " (:served-log resp))
    (= "unknown op" (:error resp)) "protocol-incompatible"
    (:reject resp) (str "reject:" (reject-message (:reject resp)))
    :else (str "error:" (pr-str resp))))

(defn- coord-write [op port te pred value base]
  (try
    (coord-write-response
     (coord-rt port {:op op :te te :p pred :r value :base base :frame "agent"}))
    (catch Exception _ "error:nodaemon")))

(defn- coord-write-for-log [op port log te pred value base]
  (try
    (coord-write-response
     (coord-request-for-log
      port log {:op op :te te :p pred :r value :base base :frame "agent"}))
    (catch Exception _ "error:nodaemon")))

(defn coord-assert  [port te pred value base] (coord-write :assert  port te pred value base))
(defn coord-retract [port te pred value base] (coord-write :retract port te pred value base))
(defn coord-assert-for-log
  [port log te pred value base]
  (coord-write-for-log :assert port log te pred value base))
(defn coord-retract-for-log
  [port log te pred value base]
  (coord-write-for-log :retract port log te pred value base))

(defn coord-port [] (if-let [p (System/getenv "FRAM_PORT")] (Integer/parseInt p) 7977))

(defn coord-status [port]
  (try (let [r (coord-rt port {:op :status})]
         (str "up|" (:version r) "|" (:facts r) "|" (:log r)))
       (catch Exception _ "down")))

(defn coord-status-for-log [port log]
  (try
    (let [r (coord-request-for-log port log {:op :status})]
      (cond
        (integer? (:version r))
        ;; Keep this exact first-line contract: North's lifecycle probe accepts
        ;; only coordinator UP + an integer version.
        (str "coordinator UP on 127.0.0.1:" port " (v" (:version r) ")")

        (= :log-mismatch (:code r))
        (str "coordinator WRONG LOG on 127.0.0.1:" port
             " — expected " (:expected-log r)
             "; daemon serves " (:served-log r)
             "; refusing fenced reads and writes")

        (= "unknown op" (:error r))
        (str "coordinator INCOMPATIBLE on 127.0.0.1:" port
             " — daemon lacks required log-fence protocol; restart it with current Fram")

        :else
        (str "coordinator UNUSABLE on 127.0.0.1:" port
             " — " (pr-str r))))
    (catch Exception _
      (str "coordinator DOWN on 127.0.0.1:" port
           " — start it with bin/fram-up"))))

;; warm READ ops — served off the daemon's in-memory warm store / index, avoiding the
;; COLD full-log fold the MCP/CLI read path pays per request (interface investigation
;; #1: ~60x tax — cold load-state ~450ms vs warm ~7ms on the canonical log). warm-read
;; returns the parsed resp, or NIL if the daemon is down OR doesn't support the op
;; ({:error "unknown op"} — an older serve-flat daemon predating the warm-op commits):
;; the caller falls back to the cold path on nil. This IS the capability handshake.
;; Keyed on (l,p,r) / Datalog strings — REP-STABLE across the fractional/CRDT ordering
;; rewrite (no fN ordering touched).
(defn warm-read [port req]
  (try (let [r (coord-rt port req)]
         (when-not (and (map? r) (= "unknown op" (:error r))) r))
       (catch Exception _ nil)))
(defn warm-read-for-log [port log req]
  (try
    (let [r (coord-request-for-log port log req)]
      (when-not (or (= "unknown op" (:error r))
                    (contains? r :reject))
        r))
    (catch Exception _ nil)))
(defn coord-query    [port q]       (warm-read port {:op :query :query q}))   ; -> q/run envelope | nil
(defn coord-callers  [port te]      (warm-read port {:op :callers :te te}))   ; -> {:callers [...]} | nil
(defn coord-resolved [port te pred] (warm-read port {:op :resolved :te te :p pred})) ; -> {:value :members :ambiguous? :values} | nil — surfaces multiplicity (#3)
(defn coord-query-for-log
  [port log q]
  (warm-read-for-log port log {:op :query :query q}))
(defn coord-callers-for-log
  [port log te]
  (warm-read-for-log port log {:op :callers :te te}))
(defn coord-resolved-for-log
  [port log te pred]
  (warm-read-for-log port log {:op :resolved :te te :p pred}))

;; :facts — the daemon's WHOLE live view as [l p r] triples: the daemon-first read
;; path (thread 019f2190). The CLI rebuilds its kernel index from this instead of
;; paying the per-process cold fold (read-log EDN parse + fold ≈ 700ms on the 11k-line
;; north log). The daemon serves the triples IN FOLD EMISSION ORDER (its contract —
;; fram.fold/refold-order, cached per version), so the records returned here feed
;; build-index directly and every listing stays byte-identical to the cold fold's.
;; Asked with {:fmt :json} DELIBERATELY: this is a multi-megabyte whole-corpus
;; payload, and bb parses JSON (cheshire, native) substantially faster than EDN.
;; Returns a (Vec kernel/Fact), or [] when the warm path is unavailable — daemon
;; down, an older daemon without the op (its {"error" ...} reply has no "facts"),
;; or a daemon serving a DIFFERENT log than the caller's (the :log echo mismatches —
;; never silently read another store). [] is safe as the sentinel: a real log always
;; folds to a non-empty view (an empty log has no daemon serving it worth trusting).
;; Callers fall back to the cold fold on [].
(defn coord-live-facts [port log]
  (let [facts-timeout
        (coord-timeout-ms "FRAM_COORD_FACTS_TIMEOUT_MS" 30000)]
    (try
      (with-open [s (coord-socket (connect-host) port)]
        (let [w (.getOutputStream s)
              reader (coordinator-reader s)]
          (.write w
                  (.getBytes
                   (str (pr-str (log-envelope log {:op :facts :fmt :json})) "\n")
                   java.nio.charset.StandardCharsets/UTF_8))
          (.flush w)
          (let [resp (parse-coord-json-line!
                      (read-coord-terminal-line-with-timeout!
                       reader
                       facts-timeout))]
            (if (and (map? resp)
                     (= (canonical-log-path log)
                        (canonical-log-path (get resp "log")))
                     (vector? (get resp "facts")))
              (mapv
               (fn [t] (kernel/->Fact (nth t 0) (nth t 1) (nth t 2)))
               (get resp "facts"))
              []))))
      (catch Exception _ []))))

;; subscribe + stream commit events (one EDN line each) until disconnect.
;; TLS setup has its own absolute handshake deadline. After the request write, the
;; subscription acknowledgement arms the small-response absolute deadline; only a
;; validated subscription earns an unbounded idle read.
(defn- coord-watch-request [port request]
  (with-open [s (coord-socket (connect-host) port)]   ; honors FRAM_CONNECT + mTLS like coord-rt
    (let [w (.getOutputStream s)
          reader (coordinator-reader s)
          fenced? (= :for-log (:op request))
          expected-log (:expected-log request)]
      (.write w
              (.getBytes (str (pr-str request) "\n")
                         java.nio.charset.StandardCharsets/UTF_8))
      (.flush w)
      (let [line (read-coord-response-line! reader)
            response (parse-coord-edn-line! line)]
        (cond
          (:reject response)
          (throw (ex-info
                  (str "coordinator rejected watch subscription"
                       (when-let [code (:code response)] (str " (" (name code) ")"))
                       ": " (reject-message (:reject response)))
                  response))

          (not (integer? (:subscribed response)))
          (throw (ex-info "invalid watch subscription handshake"
                          {:port port :response response}))

          (and fenced?
               (not= (canonical-log-path expected-log)
                     (some-> (:log response) canonical-log-path)))
          (throw (ex-info "watch subscription acknowledged for the wrong log"
                          {:port port
                           :expected-log (canonical-log-path expected-log)
                           :served-log (:log response)})))

        ;; The server has accepted this exact subscription. An idle watch is normal,
        ;; so remove the request deadline now (and only now); disconnect/EOF still
        ;; terminates the loop and closes the socket.
        (.setSoTimeout s 0)
        (println line)
        (loop []
          (when-let [event (read-coord-stream-line! reader)]
            (println event)
            (recur))))))
  nil)
(defn coord-watch [port]
  (coord-watch-request port {:op :subscribe}))
(defn coord-watch-for-log [port log]
  (coord-watch-request port (log-envelope log {:op :subscribe})))

;; --- time module runtime (ported from los.rt for `north clock`) -----------

(defn error-exit [msg]
  (binding [*out* *err*] (println (str "error: " msg)))
  (System/exit 1))

(defn now-iso []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")))

;; Canonical timestamps are zone-less local ISO (now-iso above), but facts also
;; arrive hand-written with a Z/±hh:mm offset (e.g. reconstructed clock
;; sessions). Honor an explicit offset when present; anything zone-less stays
;; interpreted in the system zone as before.
(defn iso-to-seconds [s]
  (let [normalized (if (= 16 (count s)) (str s ":00") s)]
    (if (re-find #"(Z|[+-]\d\d:?\d\d)$" normalized)
      (.toEpochSecond (java.time.OffsetDateTime/parse normalized))
      (.toEpochSecond (.atZone (java.time.LocalDateTime/parse normalized)
                               (java.time.ZoneId/systemDefault))))))

;; tolerant int parse for fact literals (estimate_hours etc.); 0 on garbage.
(defn parse-int [s]
  (try (Integer/parseInt (str/trim s)) (catch Exception _ 0)))

(defn this-week-dates []
  (let [today (java.time.LocalDate/now)
        dow (.getValue (.getDayOfWeek today))]
    (mapv (fn [i] (.toString (.plusDays today (- i (dec dow))))) (vec (range 0 7)))))

(defn file-exists [p] (.exists (io/file p)))
(defn create-dirs [p] (.mkdirs (io/file p)) nil)
(defn delete-file [p] (when (.exists (io/file p)) (.delete (io/file p))) nil)
(defn spit-append [p content] (spit p content :append true) nil)
(defn getenv [nm] (System/getenv nm))
(defn filter-digits [s] (str/replace s #"[^0-9]" ""))
(defn is-iso-datetime-19 [s]
  (boolean (and (= 19 (count s)) (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" s))))
(defn is-iso-datetime-16 [s]
  (boolean (and (= 16 (count s)) (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" s))))
(defn repeat-str [s n] (apply str (repeat (max 0 (long n)) s)))

;; Clockify HTTP — lazy-resolve babashka.http-client so the AOT/native path
;; never references it at compile time (network/out-of-scope there).
(defn http-get [url api-key]
  (or (:body ((requiring-resolve 'babashka.http-client/get)
              url {:headers {"X-Api-Key" api-key}})) ""))
(defn http-post [url api-key body]
  (or (:body ((requiring-resolve 'babashka.http-client/post)
              url {:headers {"X-Api-Key" api-key "Content-Type" "application/json"}
                   :body body})) ""))
