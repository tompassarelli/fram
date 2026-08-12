(ns fram.rt
  "Host-interop runtime for Fram's Beagle modules — the irreducible Clojure
  layer (file IO, log read/write, string ops) the .bclj `declare-extern`s bind
  to. Beagle owns the typed logic; this owns the host calls.

  Paths default to the current working directory (./threads, ./coordination.log) and
  are overridable via FRAM_THREADS / FRAM_LOG."
  (:refer-clojure :exclude [slurp])   ; fram.rt/slurp wraps clojure.core/slurp; keep the JVM server's stderr clean
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as cheshire]
            [framrpc :as framrpc]
            [fram.fold :as fold]
            [fram.kernel :as kernel]
            [fram.rt-core :as rtc]
            [fram.types :as terms]))

;; Serialize any value (records serialize as objects keyed by field name; vectors
;; as arrays) to JSON — the engine's structured-output path for the MCP edge.
(defn to-json [x] (cheshire/generate-string x))

;; Canonical query-cursor codec. Encoding is unpadded URL-safe base64 over UTF-8;
;; decoding rejects malformed UTF-8 instead of replacing invalid bytes.
(defn base64url-encode-utf8 [value]
  (.encodeToString
   (.withoutPadding (java.util.Base64/getUrlEncoder))
   (.getBytes ^String value java.nio.charset.StandardCharsets/UTF_8)))

(defn base64url-decode-utf8 [value]
  (let [bytes (.decode (java.util.Base64/getUrlDecoder) ^String value)
        decoder
        (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
          (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
          (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
    (str (.decode decoder (java.nio.ByteBuffer/wrap bytes)))))

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
(defn str-index-of [s sub] (rtc/str-index-of s sub))
(defn split-comma [s] (rtc/split-comma s))
(defn today-iso [] (str (java.time.LocalDate/now)))
(defn str-lt? [a b] (rtc/str-lt? a b))

;; split a triple line "<predicate><ws><object...>" into [pred obj]; obj may
;; contain spaces (it's the rest of the line). Blank/garbage -> [line ""].
(defn split-kv [line] (rtc/split-kv line))

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
(defn- fmt-id [n] (rtc/fmt-id n))

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

(defn slugify [title] (rtc/slugify title))

;; Keep downstream source locations stable: the rt golden compares host
;; exception stderr without masks. The graph-authored delegates above replace
;; multi-line bodies, so this spacer preserves the original stack-trace lines.
;;
;;
;;
;;
;;
;;
;;
;;

;; --- portable defaults ------------------------------------------------------

(defn threads-dir []
  (or (System/getenv "FRAM_THREADS")
      (str (System/getProperty "user.dir") "/threads")))
(defn log-path []
  (or (System/getenv "FRAM_LOG")
      (str (System/getProperty "user.dir") "/coordination.log")))
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

;; graph-edit-candidate-v1 durable receipt envelope.  This is deliberately a
;; distinct, closed log-record schema rather than metadata on an ordinary fact:
;; cold receipt recovery needs one unambiguous seal immediately followed by its
;; exact fact rows, while legacy facts carrying similarly named inert fields must
;; continue to fold as facts.  Any record that claims the discriminator but does
;; not satisfy the complete v1 schema is corruption, not an ignorable partial
;; fact.  The envelope itself has no :tx; operation fact rows remain the sole
;; version clock.
(def edit-batch-envelope-version rtc/EDIT-BATCH-ENVELOPE-VERSION)
(def edit-batch-envelope-keys rtc/EDIT-BATCH-ENVELOPE-KEYS)
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
(def ^:private edit-batch-envelope-seal-fields rtc/EDIT-BATCH-ENVELOPE-SEAL-FIELDS)
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;

(defn sha256-text [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn edit-batch-envelope-marker? [record] (rtc/edit-batch-envelope-marker? record))
;;

(defn edit-batch-envelope-seal [record]
  (sha256-text (pr-str (mapv #(get record %) edit-batch-envelope-seal-fields))))

(defn valid-edit-batch-envelope? [record]
  (rtc/valid-edit-batch-envelope? record (edit-batch-envelope-seal record)))
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;

(defn validate-edit-batch-envelope! [path byte-offset record]
  (when-not (valid-edit-batch-envelope? record)
    (throw
     (ex-info (str "fram: malformed graph-edit receipt envelope in " path
                   " at byte " byte-offset " — refusing to fold")
              {:path path
               :byte-offset byte-offset
               :fram/corrupt-log true
               :fram/malformed-edit-envelope true})))
  record)

;; parse an EDN string from the CLI (a `query`/`call` argument) into data;
;; nil on parse failure so the caller can report it instead of crashing.
(defn parse-edn [s] (try (edn/read-string s) (catch Exception _ nil)))

;; read-log — torn-tail recovery + fail-closed corruption. The live server
;; appends WITHOUT fsync, so a reader can catch the file mid-write: the FINAL
;; line may be truncated (its terminating newline not yet flushed). Policy, at
;; the EDN parse boundary:
;;   * parses to an exact graph-edit envelope -> validate the closed schema and
;;     skip it (operation facts carry the version and fold normally).
;;   * parses to any other value -> a FactOp. An EDN-VALID-but-incomplete line
;;     (e.g. one missing :r) is NOT corrupt: it still becomes a FactOp (fold
;;     filters it out, but max-tx counts its :tx — preserving migrate-flat->co's
;;     torn-tail-counts-toward-version invariant).
;;   * unparseable AND the final UNTERMINATED segment -> torn tail: recover every
;;     prior fact and emit ONE warning naming the exact BYTE offset where it
;;     starts. The writer will retry; the tail is dropped, never folded.
;;   * unparseable otherwise (newline-terminated, or a non-final line) -> FAIL
;;     CLOSED: throw naming file + byte offset. Never a silent skip, never a
;;     partial fold — a completed corrupt write is a real defect, not a race.
;; str/split-lines discards byte positions (and readLine's charset handling is
;; lossy on the offset for multi-byte values), so we split the RAW UTF-8 bytes on
;; 0x0A — never a UTF-8 continuation byte — and carry each segment's byte offset.
;; public: the server's incremental tail reader (server read-log-tail*)
;; emits the SAME warning + fail-closed shapes when it catches a torn/corrupt tail.
(defn warn-torn-tail! [path off n]
  (binding [*out* *err*]
    (println (str "fram: WARN torn-tail: " path ": torn final log line at byte "
                  off " — recovered " n " prior fact(s), incomplete tail dropped"))))

(defn corrupt-log-ex [path off]
  (ex-info (str "fram: corrupt log line in " path " at byte " off
                " — unparseable and newline-terminated (not a torn tail); refusing to fold")
           {:path path :byte-offset off :fram/corrupt-log true}))

(defn read-log [path]
  (if-not (.exists (io/file path))
    []
    (let [bs  (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
          len (alength bs)]
      (loop [i 0 acc (transient [])]
        (if (>= i len)
          (persistent! acc)
          (let [nl (loop [j i] (cond (>= j len) -1
                                     (== (aget bs j) 10) j
                                     :else (recur (inc j))))
                terminated? (>= nl 0)
                end (if terminated? nl len)
                seg (String. bs i (- end i) java.nio.charset.StandardCharsets/UTF_8)
                next-i (if terminated? (inc nl) len)]
            (if (str/blank? seg)
              (recur next-i acc)
              (let [parsed (try {:m (edn/read-string seg)} (catch Exception _ nil))]
                (cond
                  parsed
                  (let [m (:m parsed)]
                    (if (edit-batch-envelope-marker? m)
                      (do (validate-edit-batch-envelope! path i m)
                          (recur next-i acc))
                      (recur next-i
                             (conj! acc
                                    ((requiring-resolve 'fram.fold/->FactOp)
                                     (:tx m) (:op m) (:l m) (:p m) (:r m)
                                     (or (:frame m) (:by m) "legacy"))))))
                  ;; unparseable + no terminating newline: torn tail — recover, warn once.
                  (not terminated?)
                  (let [recovered (persistent! acc)]
                    (warn-torn-tail! path i (count recovered))
                    recovered)
                  ;; unparseable + newline-terminated (completed write) or non-final: fail closed.
                  :else
                  (throw (corrupt-log-ex path i)))))))))))

;; Read the configured split corpus as one transaction-ordered history. Callers
;; that need a complete logical store (MCP/query projections) use this; write
;; paths continue to name their single physical destination explicitly.
(defn read-configured-logs []
  (let [primary (log-path)
        primary-file (.getAbsoluteFile (io/file primary))
        inferred (when (= "coordination.log" (.getName primary-file))
                   (str (io/file (.getParentFile primary-file) "telemetry.log")))
        primary-history (read-log primary)
        telemetry (or (System/getenv "FRAM_TELEMETRY_LOG") inferred)]
    (if telemetry
      (vec (sort-by #(or (:tx %) 0) (into primary-history (read-log telemetry))))
      primary-history)))

;; ============================================================================
;; vGUARD — the rollback floor (Reification R0; B2 contract §2/§3/§5).
;;
;; This release is the ROLLBACK FLOOR for the generation-flip protocol the vR
;; rewrite verbs (unify / import --force / compact / split) will ship: any pin
;; revert lands HERE, never on a binary with live unguarded rewrite verbs.
;; Four laws, all in this block:
;;
;;   1. WRITER ADMISSION — every supported append (server group batch, cold
;;      `fram set`, merge/import whole-file writes) holds the SHARED
;;      FileChannel lock on <dir>/.fram.rewrite.lock across
;;      open→write→fsync→close. A generation flip holds the EXCLUSIVE lock, so
;;      exclusion is kernel-arbitrated — no scan, no TOCTOU. An in-progress
;;      flip DELAYS an append (the shared acquire blocks); it can never lose an
;;      acked write (ack ⇒ fsync under shared lock ⇒ happens-before the
;;      exclusive grant ⇒ inside the flip's read set).
;;   2. GENERATION-MANAGED REFUSAL — this binary's wholesale rewrite verbs
;;      (import / merge) FAIL CLOSED on a corpus a vR flip has ever managed
;;      (any @log:gen generation line). Rewriting such a corpus with pre-flip
;;      semantics could resurrect dead values; refusal is unconditional
;;      (--force does not override the floor).
;;   3. INTENT-AWARE EXACT-MODE DOCTOR — a crashed vR flip leaves
;;      <dir>/.fram.rewrite.intent (single-line EDN, fsynced before any
;;      mutation) recording exact st_ino / st_mode&07777 / source byte+sha
;;      boundaries. Under the EXCLUSIVE lock the doctor classifies the actual
;;      state from recorded inos/shas vs live stat — NEVER from the advisory
;;      :phase — rolls the flip forward (coordination already renamed) or back
;;      (not renamed), and always restores the EXACT recorded modes
;;      (0600/0660 stay 0600/0660 — never a constant).
;;   4. BOOT PARTICIPATION — a server acquires the lock BEFORE first serve:
;;      exclusive-if-free (heal any crashed flip), else it BLOCKS on a shared
;;      acquire until the live flip releases.
;;
;; bb law: a FileLock is released ONLY by closing its channel (sci restriction
;; — FileLock methods like .release/.isShared are not invocable); never call
;; anything on the lock object itself.
;; ============================================================================

(def rollback-floor
  "The rollback-floor release id. Releases below this floor are OUT of
  rollback support (restore-from-backup territory); `@fram admission_floor`
  facts and North pin sequencing reference this exact token."
  "vGUARD")
(defn rollback-floor-id [] rollback-floor)

(defn- corpus-dir ^java.io.File [log-path]
  (or (.getParentFile (.getAbsoluteFile (io/file (str log-path))))
      (io/file "/")))
(defn rewrite-lock-path [log-path]
  (str (io/file (corpus-dir log-path) ".fram.rewrite.lock")))
(defn rewrite-intent-path [log-path]
  (str (io/file (corpus-dir log-path) ".fram.rewrite.intent")))
;; v0.3 recovery fixes these legacy keys and filename spellings: the intent is
;; hashed/persisted state, so changing one would require a protocol migration.
;; Replacement-file names are PROTOCOL CONSTANTS shared by the vR flip writer
;; and this doctor: roll-back knows exactly which tmps to sweep, roll-forward
;; which composed telemetry replacement to prefer.
(defn rewrite-server-tmp-path [log-path]
  (str (io/file (corpus-dir log-path) ".fram.rewrite.coord.tmp")))
(defn rewrite-telem-tmp-path [log-path]
  (str (io/file (corpus-dir log-path) ".fram.rewrite.telem.tmp")))

;; --- lock primitives --------------------------------------------------------
;; A handle is {:channel ch}; close the channel to release (bb law above).
(defn acquire-rewrite-lock!
  "Acquire the corpus rewrite lock. shared? true = append class, false =
  flip/doctor class. blocking? false uses tryLock and returns nil when the
  lock is unavailable (incl. an overlapping lock held by this same JVM)."
  [log-path shared? blocking?]
  (let [raf (java.io.RandomAccessFile. (rewrite-lock-path log-path) "rw")
        ch  (.getChannel raf)]
    (try
      (let [lk (if blocking?
                 (.lock ch 0 Long/MAX_VALUE (boolean shared?))
                 (.tryLock ch 0 Long/MAX_VALUE (boolean shared?)))]
        (if lk
          {:channel ch}
          (do (.close ch) nil)))
      ;; OverlappingFileLockException (same-JVM overlap) is not in bb's class
      ;; allowlist — catch its supertype and discriminate by name. Only a
      ;; NON-blocking try maps it to nil (unavailable); a blocking acquire must
      ;; THROW — silently proceeding without the lock would break admission.
      (catch IllegalStateException e
        (.close ch)
        (if (and (not blocking?)
                 (= "OverlappingFileLockException" (.getSimpleName (class e))))
          nil
          (throw e)))
      (catch Throwable t
        (.close ch) (throw t)))))
(defn close-rewrite-lock! [h]
  (when-let [ch (:channel h)] (.close ^java.nio.channels.FileChannel ch))
  nil)

;; --- generation-managed detection (refusal law 2) ---------------------------
(defn generation-managed?
  "True when the primary log carries any @log:gen generation line (a vR flip's
  control record sits at physical line 1; ANY occurrence counts — deliberately
  fail-closed, no liveness fold: refusing too much is safe, resurrecting a
  dead value is not)."
  [log-path]
  (if-not (.exists (io/file (str log-path)))
    false
    (boolean (some (fn [op] (rtc/generation-record? op))
                   (read-log (str log-path))))))

(def generation-managed-refusal
  "corpus is generation-managed; use fram >= vR or `fram split` first")

;; --- exact-mode stat/restore helpers ----------------------------------------
(defn- unix-attr [path attr]
  (java.nio.file.Files/getAttribute (.toPath (io/file (str path)))
                                    (str "unix:" attr)
                                    (make-array java.nio.file.LinkOption 0)))
(defn file-ino [path] (long (unix-attr path "ino")))
(defn file-mode
  "st_mode & 07777 as an int — the exact value the intent records."
  [path]
  (bit-and (int (unix-attr path "mode")) 07777))
(def ^:private posix-perm-bits
  [[0400 java.nio.file.attribute.PosixFilePermission/OWNER_READ]
   [0200 java.nio.file.attribute.PosixFilePermission/OWNER_WRITE]
   [0100 java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE]
   [0040 java.nio.file.attribute.PosixFilePermission/GROUP_READ]
   [0020 java.nio.file.attribute.PosixFilePermission/GROUP_WRITE]
   [0010 java.nio.file.attribute.PosixFilePermission/GROUP_EXECUTE]
   [0004 java.nio.file.attribute.PosixFilePermission/OTHERS_READ]
   [0002 java.nio.file.attribute.PosixFilePermission/OTHERS_WRITE]
   [0001 java.nio.file.attribute.PosixFilePermission/OTHERS_EXECUTE]])
(defn set-file-mode!
  "Restore the EXACT recorded mode (never a constant). Special bits
  (setuid/setgid/sticky) cannot be expressed through the Java perm API — a log
  never legitimately carries them, so refuse loud rather than restore wrong."
  [path mode]
  (when (pos? (bit-and (long mode) 07000))
    (throw (ex-info (str "refusing to restore special mode bits on " path
                         " — recorded mode " mode " carries setuid/setgid/sticky")
                    {:path (str path) :mode mode :fram/doctor-refusal true})))
  (java.nio.file.Files/setPosixFilePermissions
   (.toPath (io/file (str path)))
   (java.util.HashSet.
    ^java.util.Collection
    (vec (keep (fn [[bit perm]] (when (pos? (bit-and (long mode) (long bit))) perm))
               posix-perm-bits))))
  nil)

(defn- fsync-dir!
  "Directory fsync — makes a rename/delete in this directory durable (Linux)."
  [dir]
  (with-open [ch (java.nio.channels.FileChannel/open
                  (.toPath (io/file (str dir)))
                  (into-array java.nio.file.OpenOption
                              [java.nio.file.StandardOpenOption/READ]))]
    (.force ch true))
  nil)

(defn- sha256-16hex [^bytes bs]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)]
    (subs (apply str (map #(format "%02x" %) d)) 0 16)))
(defn- file-prefix-sha16
  "sha256-16hex of exactly the first n bytes of path; nil when the file is
  shorter than n (the recorded boundary cannot match)."
  [path n]
  (let [f (io/file (str path))]
    (when (and (.exists f) (>= (.length f) (long n)))
      (let [bs (byte-array (long n))]
        (with-open [in (java.io.FileInputStream. f)]
          (loop [off 0]
            (when (< off (long n))
              (let [k (.read in bs off (- (long n) off))]
                (when (pos? k) (recur (+ off k)))))))
        (sha256-16hex bs)))))
(defn- file-line1-sha16
  "sha256-16hex of physical line 1 (bytes up to and excluding the first LF)."
  [path]
  (let [f (io/file (str path))]
    (when (.exists f)
      (let [bs (java.nio.file.Files/readAllBytes (.toPath f))
            n  (alength bs)
            nl (loop [i 0] (cond (>= i n) n (== (aget bs i) 10) i :else (recur (inc i))))]
        (sha256-16hex (java.util.Arrays/copyOfRange bs 0 (int nl)))))))

;; --- the rewrite intent (doctor law 3) --------------------------------------
(def rewrite-intent-version 1)
(defn read-rewrite-intent
  "Parse <dir>/.fram.rewrite.intent. nil when absent; throws (loud, naming the
  required version) on an unknown :v — a NEWER flip protocol wrote it and this
  binary must not guess at its recovery semantics."
  [log-path]
  (let [f (io/file (rewrite-intent-path log-path))]
    (when (.exists f)
      (let [m (try (edn/read-string (clojure.core/slurp f))
                   (catch Exception e
                     (throw (ex-info (str "unparseable rewrite intent " (.getPath f)
                                          " — refusing to classify; operator intervention required")
                                     {:path (.getPath f) :fram/doctor-refusal true} e))))]
        (when-not (= rewrite-intent-version (:v m))
          (throw (ex-info (str "rewrite intent " (.getPath f) " has version " (:v m)
                               " — this binary understands only :v " rewrite-intent-version
                               "; run the fram release that wrote it")
                          {:path (.getPath f) :v (:v m)
                           :required rewrite-intent-version :fram/doctor-refusal true})))
        m))))

(defn- telem-path-for [log-path]
  (str (io/file (corpus-dir log-path) "telemetry.log")))
(defn- delete-if-exists! [path]
  (let [f (io/file (str path))] (when (.exists f) (io/delete-file f true))) nil)
(defn- delete-tree! [path]
  (let [f (io/file (str path))]
    (when (.exists f)
      (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))
  nil)

(defn- classify-rewrite-crash
  "Which side of the coordination ATOMIC_MOVE did the flip die on? From
  recorded inos/shas vs live stat ONLY (the advisory :phase carries zero
  correctness weight). :rolled-back = coordination.log is still the source
  inode/bytes; :rolled-forward = it is the composed replacement. Anything
  unrecognizable refuses loud — never guess about a corpus."
  [log-path intent]
  (let [primary-log (str log-path)
        live-ino  (when (.exists (io/file primary-log)) (file-ino primary-log))
        old-ino   (get-in intent [:coord :ino])
        new-ino   (get-in intent [:new_coord :ino])
        old-bytes (get-in intent [:coord :bytes])
        old-sha   (get-in intent [:coord :sha])
        new-sha1  (get-in intent [:new_coord :sha1])
        line1-sha (when new-sha1 (file-line1-sha16 primary-log))
        prefix-sha (when (and old-bytes old-sha) (file-prefix-sha16 primary-log old-bytes))]
    (rtc/classify-rewrite-crash
     primary-log live-ino old-ino new-ino old-bytes old-sha new-sha1 line1-sha prefix-sha)))
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;

(defn- compose-empty-telem-tmp!
  "Step-6 twin for roll-forward: an EMPTY 0444 replacement for telemetry.log,
  fsynced, in the corpus directory."
  [log-path]
  (let [tmp (rewrite-telem-tmp-path log-path)]
    (delete-if-exists! tmp)
    (with-open [os (java.io.FileOutputStream. tmp)]
      (.force (.getChannel os) true))
    (set-file-mode! tmp 0444)
    tmp))

(defn- roll-forward! [log-path intent]
  ;; Complete flip steps 8–12. Every step is idempotent, so a crash inside the
  ;; doctor itself re-runs cleanly.
  (let [dir   (corpus-dir log-path)
        telem (telem-path-for log-path)
        telem-recorded? (some? (:telem intent))
        telem-old-ino   (get-in intent [:telem :ino])
        telem-live-ino  (when (.exists (io/file telem)) (file-ino telem))]
    ;; (8) rename the composed empty telemetry replacement over telemetry.log —
    ;; the path is EMPTIED, never deleted (North's bin trigger watches existence).
    (when (and telem-recorded? telem-live-ino (= telem-live-ino telem-old-ino))
      (let [tmp (let [t (io/file (rewrite-telem-tmp-path log-path))]
                  (if (.exists t) (.getPath t) (compose-empty-telem-tmp! log-path)))]
        (java.nio.file.Files/move (.toPath (io/file (str tmp)))
                                  (.toPath (io/file telem))
                                  (into-array java.nio.file.CopyOption
                                              [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))))
    ;; (9) directory fsync — both renames durable before any mode restores.
    (fsync-dir! dir)
    ;; (10) restore the EXACT recorded modes.
    (when (.exists (io/file (str log-path)))
      (set-file-mode! (str log-path) (get-in intent [:coord :mode])))
    (when (and telem-recorded? (.exists (io/file telem)))
      (set-file-mode! telem (get-in intent [:telem :mode])))
    ;; (11) sweep sidecar + snapshots — the log identity flipped, so they are
    ;; stale by construction (an old server would also invalidate them; sweeping
    ;; makes it unconditional).
    (delete-if-exists! (str log-path ".snap"))
    (delete-tree! (str log-path ".snapshots"))
    ;; leftover coordination tmp (the rename source when the flip died post-move)
    (delete-if-exists! (rewrite-server-tmp-path log-path))
    ;; (12) delete the intent + directory fsync.
    (delete-if-exists! (rewrite-intent-path log-path))
    (fsync-dir! dir)
    :rolled-forward))

(defn- roll-back! [log-path intent]
  ;; The coordination rename never happened: sweep the composed tmps, restore
  ;; the exact recorded modes on the untouched sources, drop the intent.
  (let [dir   (corpus-dir log-path)
        telem (telem-path-for log-path)]
    (delete-if-exists! (rewrite-server-tmp-path log-path))
    (delete-if-exists! (rewrite-telem-tmp-path log-path))
    (when (.exists (io/file (str log-path)))
      (set-file-mode! (str log-path) (get-in intent [:coord :mode])))
    (when (and (some? (:telem intent)) (.exists (io/file telem)))
      (set-file-mode! telem (get-in intent [:telem :mode])))
    (delete-if-exists! (rewrite-intent-path log-path))
    (fsync-dir! dir)
    :rolled-back))

(defn doctor-rewrite-intent!
  "Heal a crashed flip. CALLER MUST HOLD THE EXCLUSIVE LOCK. Returns
  {:state :clean | :rolled-forward | :rolled-back}; :clean touches NOTHING
  (A7: with no intent, store modes/bytes stay byte-for-byte untouched).
  Throws (:fram/doctor-refusal) on unknown intent version / unclassifiable
  state / special mode bits."
  [log-path]
  (if-let [intent (read-rewrite-intent log-path)]
    (case (classify-rewrite-crash log-path intent)
      :roll-forward {:state (roll-forward! log-path intent) :intent intent}
      :roll-back    {:state (roll-back! log-path intent) :intent intent})
    {:state :clean}))

;; --- write admission (law 1) ------------------------------------------------
(defn with-append-admission
  "Run write-fn while holding the SHARED rewrite lock (blocking: a live flip
  DELAYS the append until its exclusive lock releases). If a rewrite intent
  still exists once the shared lock is granted, the flip crashed without being
  healed — REFUSE LOUD (the caller's ack path delivers the throw; the server
  NACKs, a CLI prints), never append into a half-flipped corpus."
  [log-path write-fn]
  (let [h (acquire-rewrite-lock! log-path true true)]
    (try
      (when (.exists (io/file (rewrite-intent-path log-path)))
        (throw (ex-info (str "rewrite in progress/crashed on " log-path
                             " — run `fram doctor` first")
                        {:path (str log-path) :fram/rewrite-in-progress true})))
      (write-fn)
      (finally (close-rewrite-lock! h)))))

(defn- heal-if-crashed!
  "Cold-verb auto-heal (deterministic, logged): when an intent exists and the
  exclusive lock is FREE, the crashed flip is healed before the write. A LIVE
  flip (lock held) falls through — the shared acquire in with-append-admission
  blocks until it completes."
  [log-path]
  (when (.exists (io/file (rewrite-intent-path log-path)))
    (when-let [h (acquire-rewrite-lock! log-path false false)]
      (try
        (let [r (doctor-rewrite-intent! log-path)]
          (when-not (= :clean (:state r))
            (binding [*out* *err*]
              (println (str "fram: healed crashed rewrite on " log-path
                            " (" (name (:state r)) ")")))))
        (finally (close-rewrite-lock! h))))))

(defn with-cold-write-admission
  "The cold single-process write seam (set / merge / import): auto-heal a
  crashed flip if the lock is free, then append under the shared lock."
  [log-path write-fn]
  (heal-if-crashed! log-path)
  (with-append-admission log-path write-fn))

;; --- server boot participation (law 4) --------------------------------------
(defn boot-rewrite-gate!
  "Acquire the rewrite lock BEFORE first serve and RETURN a SHARED lock handle
  the caller holds across its boot fold (close it with close-rewrite-lock!
  before serving — a serving server holds the shared lock per append batch,
  never continuously). While an intent exists: exclusive-if-free heals the
  crashed flip; exclusive unobtainable = a LIVE flip (or a peer healing) —
  BLOCK on a shared acquire until it releases, then re-check. The exclusive
  path runs ONLY while an intent exists, so concurrent shared traffic (peer
  boots, appends) can never livelock this loop; the returned handle is
  re-checked against a fresh intent so a flip crashing between heal and
  acquire is never served."
  [log-path]
  (let [intent? #(.exists (io/file (rewrite-intent-path log-path)))]
    (loop [n 0]
      (if (intent?)
        (do
          (if-let [h (acquire-rewrite-lock! log-path false false)]
            (try
              (let [r (doctor-rewrite-intent! log-path)]
                (when-not (= :clean (:state r))
                  (println (str "[fram] boot: healed crashed rewrite on " log-path
                                " (" (name (:state r)) ")"))))
              (finally (close-rewrite-lock! h)))
            (do
              (when (zero? n)
                (println (str "[fram] boot: rewrite in progress on " log-path
                              " — waiting for the flip to release its lock")))
              (close-rewrite-lock! (acquire-rewrite-lock! log-path true true))))
          (recur (inc n)))
        ;; no intent: take the participation lock, then re-check — a flip that
        ;; started and crashed in the gap left an intent we must heal, never serve.
        (let [h (acquire-rewrite-lock! log-path true true)]
          (if (intent?)
            (do (close-rewrite-lock! h) (recur (inc n)))
            h))))))

;; --- operator doctor (the `fram doctor` face of law 3) ----------------------
(defn doctor-rewrite!
  "Intent doctor for the doctor CLI: loud, exact, never guesses. Heals (or
  no-ops) and RETURNS the one-line report for the caller to print — the doctor
  CLI's first line stays the server health contract, so this line prints
  after it. Exit 2 when a live flip holds the lock (nothing to heal yet —
  retry after it completes); exit 1 on a refusal state (unknown intent
  version / unclassifiable corpus)."
  [log-path]
  (if-let [h (acquire-rewrite-lock! log-path false false)]
    (try
      (let [r (try (doctor-rewrite-intent! log-path)
                   (catch clojure.lang.ExceptionInfo e
                     (if (:fram/doctor-refusal (ex-data e))
                       (do (binding [*out* *err*]
                             (println (str "fram doctor: REFUSED — " (.getMessage e))))
                           (System/exit 1))
                       (throw e))))
            modes (fn [] (str (get-in r [:intent :coord :mode])
                              (when-let [tm (get-in r [:intent :telem :mode])] (str "/" tm))))]
        (case (:state r)
          :clean "rewrite-intent: none (clean)"
          :rolled-forward
          (str "rewrite-intent: HEALED — rolled the crashed flip FORWARD"
               " (modes restored to recorded " (modes) ")")
          :rolled-back
          (str "rewrite-intent: HEALED — rolled the crashed flip BACK"
               " (modes restored to recorded " (modes) ")")))
      (finally (close-rewrite-lock! h)))
    (do (binding [*out* *err*]
          (println (str "fram doctor: rewrite in progress on " log-path
                        " — another process holds the rewrite lock; retry after the flip completes")))
        (System/exit 2))))

;; write-log / append-fact-op — the cold write seams, now vGUARD supported
;; writers: SHARED rewrite lock across open→write→fsync→close, and the fn
;; returns (the caller's ack) only after the bytes are fsynced. A live
;; generation flip's exclusive lock DELAYS these writes; it can never lose one.
(defn write-log [path fact-ops]
  (let [ts (now-ts)                                  ; one batch instant (this import/rewrite)
        lines (map (fn [a]
                     (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a) :ts ts}))
                   fact-ops)
        payload (.getBytes (str (str/join "\n" lines) "\n") "UTF-8")]
    (with-cold-write-admission path
      (fn []
        (with-open [os (java.io.FileOutputStream. (str path))]   ; truncate+rewrite (import/merge)
          (.write os ^bytes payload)
          (.flush os)
          (.force (.getChannel os) true))))))

(defn append-fact-op [path a]
  (let [payload (.getBytes (str (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a) :ts (now-ts)}) "\n")
                           "UTF-8")]
    (with-cold-write-admission path
      (fn []
        (with-open [os (java.io.FileOutputStream. (str path) true)]
          (.write os ^bytes payload)
          (.flush os)
          (.force (.getChannel os) true))))))

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

;; --- server client: write THROUGH the server (safe concurrent path) -----
;; One request/response over the local socket. The server serializes writes
;; (optimistic base_version + obligation rules), so this is the safe multi-agent
;; write path — unlike append-fact-op, which writes the log directly.

;; client-side mutual TLS: present FRAM_SERVER_TLS_KEYSTORE, verify the server against
;; FRAM_SERVER_TLS_TRUSTSTORE. Works on babashka (client SSL classes are present; only the
;; SERVER-side SSLServerSocket is absent, which is why the server runs on the JVM).
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

;; connect to the server: FRAM_SERVER_CONNECT host (default 127.0.0.1); mutual TLS when
;; FRAM_SERVER_TLS_* is set, else plaintext (the unchanged loopback default).
(defn- connect-host []
  (let [h (System/getenv "FRAM_SERVER_CONNECT")] (if (str/blank? h) "127.0.0.1" h)))

(defn- server-timeout-ms [name default]
  (let [raw (or (System/getenv name) (str default))]
    (when-not (re-matches #"[1-9][0-9]{0,5}" raw)
      (throw
       (ex-info
        (str name " must be an integer from 1 through 999999 milliseconds")
        {:type :invalid-server-timeout :name name :value raw})))
    (Integer/parseInt raw)))

(def ^:dynamic *server-response-byte-limit* nil)

(defn- server-response-byte-limit []
  (let [raw (or (System/getenv "FRAM_SERVER_MAX_RESPONSE_BYTES") "67108864")
        value (when (re-matches #"[1-9][0-9]{0,8}" raw)
                (Long/parseLong raw))]
    (when-not (and value (<= value 67108864))
      (throw
       (ex-info
        "FRAM_SERVER_MAX_RESPONSE_BYTES must be an integer from 1 through 67108864"
        {:type :invalid-server-response-limit :value raw})))
    (int value)))

(def query-page-response-byte-limit 1048576)

(defn- server-response-timeout! [timeout cause]
  (throw
   (ex-info "server response deadline exceeded"
            {:type :server-response-timeout
             :timeout-ms timeout}
            cause)))

(defn- decode-server-utf8! [bytes]
  (try
    (let [decoder
          (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
            (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
            (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
      (str (.decode decoder (java.nio.ByteBuffer/wrap bytes))))
    (catch java.nio.charset.CharacterCodingException error
      (throw
       (ex-info "server response line is not valid UTF-8"
                {:type :malformed-server-utf8}
                error)))))

(defrecord ServerReader [socket input buffer bounds])

(defn server-reader [socket]
  (->ServerReader
   socket
   (.getInputStream socket)
   (byte-array 65536)
   (int-array 2)))

(defn- as-server-reader [source]
  (if (instance? ServerReader source)
    source
    (server-reader source)))

(defn- server-newline-offset [buffer start end]
  (.indexOf
   (String.
    buffer
    start
    (- end start)
    java.nio.charset.StandardCharsets/ISO_8859_1)
   "\n"))

(defn- finish-server-line! [output]
  (let [line (decode-server-utf8! (.toByteArray output))]
    (if (str/ends-with? line "\r")
      (subs line 0 (dec (count line)))
      line)))

(defn- arm-server-deadline! [socket deadline timeout]
  (let [remaining-ns (- deadline (System/nanoTime))]
    (when-not (pos? remaining-ns)
      (server-response-timeout! timeout nil))
    (.setSoTimeout
     socket
     (int (max 1 (quot (+ remaining-ns 999999) 1000000))))))

(defn- read-server-line! [source deadline timeout eof-ok?]
  (let [{:keys [socket input buffer bounds]} (as-server-reader source)
        buffer-size (alength buffer)
        limit (or *server-response-byte-limit* (server-response-byte-limit))
        output (java.io.ByteArrayOutputStream.)]
    (loop []
      (when (and deadline (not (pos? (- deadline (System/nanoTime)))))
        (server-response-timeout! timeout nil))
      (let [start (aget bounds 0)
            end (aget bounds 1)]
        (if (< start end)
          (let [available (- end start)
                newline-offset (server-newline-offset buffer start end)
                take-bytes (if (neg? newline-offset) available newline-offset)
                total (+ (.size output) take-bytes)]
            (when (> total limit)
              (throw
               (ex-info
                (str "server response line exceeds " limit " bytes")
                {:type :server-response-too-large
                 :max-bytes limit})))
            (.write output buffer start take-bytes)
            (if (neg? newline-offset)
              (do
                (aset-int bounds 0 end)
                (recur))
              (do
                (aset-int bounds 0 (+ start newline-offset 1))
                (finish-server-line! output))))
          (do
            (when deadline
              (arm-server-deadline! socket deadline timeout))
            (let [read-count
                  (try
                    (.read input buffer 0 buffer-size)
                    (catch java.net.SocketTimeoutException error
                      (server-response-timeout! timeout error)))]
              (cond
                (= -1 read-count)
                (if (and eof-ok? (zero? (.size output)))
                  nil
                  (throw
                   (ex-info
                    (if (zero? (.size output))
                      "server closed before sending a response line"
                      "server closed during a response line")
                    {:type (if (zero? (.size output))
                             :server-response-closed
                             :server-response-truncated)
                     :bytes (.size output)})))

                (zero? read-count)
                (recur)

                :else
                (do
                  (aset-int bounds 0 0)
                  (aset-int bounds 1 read-count)
                  (recur))))))))))

(defn read-server-response-line!
  "Read one bounded UTF-8 response line under an absolute total deadline."
  [source]
  (let [timeout (server-timeout-ms "FRAM_SERVER_READ_TIMEOUT_MS" 2000)]
    (read-server-line!
     source
     (+ (System/nanoTime) (* 1000000 (long timeout)))
     timeout
     false)))

(defn- ensure-server-terminal-eof! [reader deadline timeout]
  (let [{:keys [socket input buffer bounds]} reader]
    (loop []
      (let [start (aget bounds 0)
            end (aget bounds 1)]
        (when (< start end)
          (throw
           (ex-info "server sent more than one terminal response frame"
                    {:type :multiple-server-response-frames
                     :surplus-bytes (- end start)})))
        (arm-server-deadline! socket deadline timeout)
        (let [read-count
              (try
                (.read input buffer 0 (alength buffer))
                (catch java.net.SocketTimeoutException error
                  (server-response-timeout! timeout error)))]
          (cond
            (= -1 read-count) nil
            (zero? read-count) (recur)
            :else
            (throw
             (ex-info "server sent more than one terminal response frame"
                      {:type :multiple-server-response-frames
                       :surplus-bytes read-count}))))))))

(defn- read-server-terminal-line-with-timeout! [reader timeout]
  (let [deadline (+ (System/nanoTime) (* 1000000 (long timeout)))
        line (read-server-line! reader deadline timeout false)]
    (ensure-server-terminal-eof! reader deadline timeout)
    line))

(defn- read-server-terminal-line! [reader]
  (read-server-terminal-line-with-timeout!
   reader
   (server-timeout-ms "FRAM_SERVER_READ_TIMEOUT_MS" 2000)))

(defn- malformed-server-line! [message line error]
  (throw
   (ex-info
    message
    {:type :malformed-server-response
     :line-bytes
     (count (.getBytes
             (str line)
             java.nio.charset.StandardCharsets/UTF_8))}
    error)))

(defn parse-server-edn-line! [line]
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
      (malformed-server-line!
       "server response line is not exactly one valid EDN form"
       line
       error))
    (catch Exception error
      (malformed-server-line!
       "server response line is not exactly one valid EDN form"
       line
       error))))

(defn- parse-server-json-line! [line]
  (try
    (with-open [reader (java.io.StringReader. line)]
      (let [values (vec (take 2 (cheshire/parsed-seq reader)))]
        (when-not (= 1 (count values))
          (throw (ex-info "not exactly one JSON value" {})))
        (first values)))
    (catch StackOverflowError error
      (malformed-server-line!
       "server response line is not exactly one valid JSON value"
       line
       error))
    (catch Exception error
      (malformed-server-line!
       "server response line is not exactly one valid JSON value"
       line
       error))))

(defn- run-with-server-watchdog!
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

(defn- server-tls-handshake! [socket]
  (let [timeout (server-timeout-ms "FRAM_SERVER_HANDSHAKE_TIMEOUT_MS" 2000)]
    ;; One timeout owns the handshake: SO_TIMEOUT bounds an individual SSL read
    ;; and the watchdog bounds the whole exchange. Request/facts readers replace
    ;; it with their own absolute deadline after the handshake succeeds.
    (.setSoTimeout socket timeout)
    (run-with-server-watchdog!
     socket
     timeout
     "server TLS handshake deadline exceeded"
     :server-handshake-timeout
     (fn []
       (.startHandshake socket)
       nil))))

(defn- server-socket [host port]
  (let [ks (System/getenv "FRAM_SERVER_TLS_KEYSTORE") ts (System/getenv "FRAM_SERVER_TLS_TRUSTSTORE")
        pass (or (System/getenv "FRAM_SERVER_TLS_PASS")
                 (when-let [f (System/getenv "FRAM_SERVER_TLS_PASS_FILE")] (str/trim (slurp f))))]
    ;; fail CLOSED on a partial config — a typo'd/missing var must NOT silently
    ;; downgrade a "secure" link to plaintext.
    (when (and (or ks ts pass) (not (and ks ts pass)))
      (binding [*out* *err*]
        (println "FATAL: FRAM_SERVER_TLS_* partially set — need ALL of FRAM_SERVER_TLS_KEYSTORE / FRAM_SERVER_TLS_TRUSTSTORE / FRAM_SERVER_TLS_PASS (refusing to connect in plaintext)"))
      (System/exit 2))
    (if (and ks ts pass)
      (let [s (.createSocket (.getSocketFactory (client-ssl-context ks ts pass)))]
        (try
          (.connect s
                    (java.net.InetSocketAddress. ^String host (int port))
                    (server-timeout-ms "FRAM_SERVER_CONNECT_TIMEOUT_MS" 2000))
          (server-tls-handshake! s)
          s
          (catch Throwable error
            (try (.close s) (catch Throwable _ nil))
            (throw error))))
      (let [s (java.net.Socket.)]
        (try
          (.connect s
                    (java.net.InetSocketAddress. ^String host (int port))
                    (server-timeout-ms "FRAM_SERVER_CONNECT_TIMEOUT_MS" 2000))
          s
          (catch Throwable error
            (try (.close s) (catch Throwable _ nil))
            (throw error)))))))

;; --- FRAMRPC v1 client -------------------------------------------------------
;; Data clients share this one bounded binary implementation. Human-facing
;; commands may parse EDN before this boundary, but only recursive Terms and
;; closed RpcRequest records reach the socket.

(def ^:private rpc-request-sequence (java.util.concurrent.atomic.AtomicLong. 0))

(defn rpc-space-id []
  (let [space (System/getenv "FRAM_SPACE_ID")]
    (when (str/blank? space)
      (throw (ex-info "FRAM_SPACE_ID is required for FRAMRPC data requests"
                      {:type :rpc-space-id-required})))
    space))

(defn- next-rpc-request-id []
  (let [value (.incrementAndGet rpc-request-sequence)]
    (if (pos? value)
      value
      (do
        (.set rpc-request-sequence 1)
        1))))

(defn- read-rpc-exact! [input bytes offset length]
  (loop [position offset remaining length]
    (if (zero? remaining)
      true
      (let [read-count (.read input bytes position remaining)]
        (if (neg? read-count)
          false
          (recur (+ position read-count) (- remaining read-count)))))))

(defn- rpc-stream-body-length! [header]
  (dotimes [index 8]
    (when-not (= (bit-and 255 (int (aget header index)))
                 (bit-and 255 (int (aget framrpc/rpc-v1-magic index))))
      (throw (ex-info "FRAMRPC response magic does not match"
                      {:type :rpc-invalid-magic}))))
  (let [buffer (doto (java.nio.ByteBuffer/wrap header)
                 (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (.position buffer 8)
    (let [major (Short/toUnsignedInt (.getShort buffer))
          minor (Short/toUnsignedInt (.getShort buffer))
          kind (bit-and 255 (int (.get buffer)))
          flags (bit-and 255 (int (.get buffer)))
          body-length (Integer/toUnsignedLong (.getInt buffer))]
      (when-not (and (= major framrpc/rpc-v1-major)
                     (= minor framrpc/rpc-v1-minor))
        (throw (ex-info "FRAMRPC response version is unsupported"
                        {:type :rpc-unsupported-version
                         :major major :minor minor})))
      (when-not (contains? #{2 4} kind)
        (throw (ex-info "FRAMRPC client expected a response or event frame"
                        {:type :rpc-invalid-kind :kind kind})))
      (when-not (zero? flags)
        (throw (ex-info "FRAMRPC v1 response flags must be zero"
                        {:type :rpc-invalid-flags :flags flags})))
      (when (> body-length framrpc/rpc-v1-max-body-bytes)
        (throw (ex-info "FRAMRPC response body exceeds the 1 MiB limit"
                        {:type :rpc-frame-too-large
                         :body-length body-length})))
      (int body-length))))

(defn read-rpc-frame! [input]
  (let [header (byte-array framrpc/rpc-v1-header-bytes)]
    (when-not (read-rpc-exact! input header 0 framrpc/rpc-v1-header-bytes)
      (throw (ex-info "FRAMRPC response ended inside its header"
                      {:type :rpc-truncated})))
    (let [body-length (rpc-stream-body-length! header)
          body (byte-array body-length)
          frame (byte-array (+ framrpc/rpc-v1-header-bytes body-length))]
      (when-not (read-rpc-exact! input body 0 body-length)
        (throw (ex-info "FRAMRPC response ended inside its body"
                        {:type :rpc-truncated})))
      (System/arraycopy header 0 frame 0 framrpc/rpc-v1-header-bytes)
      (System/arraycopy body 0 frame framrpc/rpc-v1-header-bytes body-length)
      (framrpc/decode-rpc-frame-v1! frame))))

(defn native-request-to!
  "Send one closed FRAMRPC request to host/port and return its RpcResponse.
   The response id, space, and operation must match the request exactly."
  [host port request]
  (let [request-id (next-rpc-request-id)]
    (with-open [socket (server-socket host port)]
      (let [timeout (max (server-timeout-ms "FRAM_SERVER_READ_TIMEOUT_MS" 15000)
                         (+ 1000 (or (terms/rpcrequest-timeout-ms request) 0)))
            output (.getOutputStream socket)]
        (.setSoTimeout socket timeout)
        (.write output
                (framrpc/encode-rpc-frame-v1!
                 (framrpc/rpc-request-frame request-id request)))
        (.flush output)
        (let [frame (read-rpc-frame! (.getInputStream socket))
              response (terms/rpcframev1-response frame)]
          (when-not (= :response (terms/rpcframev1-kind frame))
            (throw (ex-info "FRAMRPC request received a non-response frame"
                            {:type :rpc-invalid-kind})))
          (when-not (= request-id (terms/rpcframev1-request-id frame))
            (throw (ex-info "FRAMRPC response request-id does not match"
                            {:type :rpc-request-id-mismatch})))
          (when-not (and (= (terms/rpcrequest-space request)
                            (terms/rpcresponse-space response))
                         (= (terms/rpcrequest-op request)
                            (terms/rpcresponse-op response)))
            (throw (ex-info "FRAMRPC response identity does not match its request"
                            {:type :rpc-response-mismatch})))
          response)))))

(defn native-request! [port request]
  (native-request-to! (connect-host) port request))

(defn native-call!
  ([port operation payload]
   (native-call! port (rpc-space-id) operation payload nil nil nil))
  ([port space operation payload expected-version page timeout-ms]
   (native-request!
    port
    (framrpc/rpc-request! space operation expected-version page timeout-ms
                           payload))))

(defn native-error [response] (terms/rpcresponse-error response))
(defn native-error-code [response]
  (some-> response native-error terms/rpcerror-code))
(defn native-payload [response]
  (terms/rpc-response-payload-value response))

(defn require-native-success! [response]
  (if-let [error (native-error response)]
    (throw (ex-info (terms/rpcerror-message error)
                    {:type (terms/rpcerror-code error)
                     :code (terms/rpcerror-code error)
                     :retryable (terms/rpcerror-retryable error)
                     :served-version (terms/rpcresponse-served-version response)
                     :detail (terms/rpc-error-detail-value error)}))
    response))

(defn rpc-record-fields! [value tag field-count]
  (framrpc/rpc-record-fields! value tag field-count))

(defn rpc-list-values! [value]
  (framrpc/rpc-list-values! value))

;; The human syntax is deliberately just a local lowering convenience. A
;; three-element vector is a Triple; {:instant [seconds nanos]} is an Instant;
;; symbols become String atoms. Maps and arbitrary sequences never cross the
;; data socket.
(declare lower-term!)

(defn lower-term! [value]
  (cond
    (terms/term? value) value
    (symbol? value) (str value)
    (and (vector? value) (= 3 (count value)))
    (terms/triple (lower-term! (nth value 0))
                  (lower-term! (nth value 1))
                  (lower-term! (nth value 2)))
    (and (map? value)
         (= #{:instant} (set (keys value)))
         (vector? (:instant value))
         (= 2 (count (:instant value))))
    (terms/instant (long (nth (:instant value) 0))
                   (long (nth (:instant value) 1)))
    :else
    (throw (ex-info "value cannot be lowered to Term"
                    {:type :invalid-term-input :value value}))))

(defn parse-human-term!
  "Parse one local EDN datum, falling back to the original text as a String.
   This parser is a CLI boundary only; its result is immediately lowered."
  [text]
  (let [parsed
        (try
          (with-open [reader (java.io.PushbackReader.
                              (java.io.StringReader. text))]
            (let [eof (Object.)
                  value (edn/read {:eof eof} reader)
                  trailing (edn/read {:eof eof} reader)]
              (when (or (identical? eof value)
                        (not (identical? eof trailing)))
                (throw (ex-info "expected exactly one EDN datum" {})))
              value))
          (catch Throwable _ text))]
    (try
      (lower-term! parsed)
      (catch Throwable _
        (if (string? parsed) parsed text)))))

(defn- query-field [value key]
  (if (contains? value key)
    (get value key)
    (get value (name key))))

(defn- query-has? [value key]
  (or (contains? value key) (contains? value (name key))))

(defn- require-query-field! [value key]
  (if (query-has? value key)
    (query-field value key)
    (throw (ex-info (str "query field " (name key) " is required")
                    {:type :query-invalid-syntax :field key}))))

(defn- query-name! [value label]
  (cond
    (string? value) value
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    :else (throw (ex-info (str label " must be a name")
                          {:type :query-invalid-syntax :value value}))))

(defn- query-operation! [value label]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    (symbol? value) (keyword (str value))
    :else (throw (ex-info (str label " must be a keyword spelling")
                          {:type :query-invalid-syntax :value value}))))

(defn- lower-query-term! [value]
  (if (and (map? value)
           (= #{(if (contains? value :var) :var "var")}
              (set (keys value))))
    (framrpc/rpc-query-variable!
     (query-name! (query-field value :var) "query variable"))
    (framrpc/rpc-query-constant! (lower-term! value))))

(defn- lower-query-head! [value]
  (framrpc/rpc-query-head!
   (query-name! (require-query-field! value :rel) "query relation")
   (mapv lower-query-term! (require-query-field! value :args))))

(defn- lower-query-clause! [value]
  (cond
    (query-has? value :rel)
    (framrpc/rpc-query-relation!
     (query-name! (query-field value :rel) "query relation")
     (mapv lower-query-term! (require-query-field! value :args))
     (boolean (or (query-field value :neg)
                  (query-field value :not)
                  (query-field value :negated))))

    (query-has? value :pred)
    (let [arguments (vec (require-query-field! value :args))]
      (when-not (= 2 (count arguments))
        (throw (ex-info "query predicate requires exactly two arguments"
                        {:type :query-invalid-syntax})))
      (framrpc/rpc-query-predicate!
       (query-operation! (query-field value :pred) "query predicate")
       (lower-query-term! (nth arguments 0))
       (lower-query-term! (nth arguments 1))))

    (query-has? value :fn)
    (framrpc/rpc-query-function!
     (query-operation! (query-field value :fn) "query function")
     (mapv lower-query-term! (require-query-field! value :args))
     (query-name! (require-query-field! value :bind) "query binding"))

    :else
    (throw (ex-info "query clause must be relation, predicate, or function"
                    {:type :query-invalid-syntax :value value}))))

(defn- lower-query-rule! [value]
  (framrpc/rpc-query-rule!
   (lower-query-head! (require-query-field! value :head))
   (mapv lower-query-clause! (require-query-field! value :body))))

(defn- lower-query-find! [value]
  (if (map? value)
    (framrpc/rpc-query-find-aggregate!
     (query-name! (require-query-field! value :rel) "aggregate relation")
     (mapv long (or (query-field value :group) []))
     (mapv
      (fn [aggregate]
        (framrpc/rpc-query-aggregate!
         (query-operation! (require-query-field! aggregate :op)
                           "aggregate operation")
         (when (query-has? aggregate :arg)
           (long (query-field aggregate :arg)))))
      (require-query-field! value :agg))
     (mapv
      (fn [having]
        (framrpc/rpc-query-having!
         (query-operation! (require-query-field! having :op)
                           "having comparison")
         (long (require-query-field! having :agg))
         (lower-term! (require-query-field! having :val))))
      (or (query-field value :having) [])))
    (framrpc/rpc-query-find-relation!
     (query-name! value "find relation"))))

(defn lower-query-plan!
  "Lower the public structured query syntax into the closed recursive-Term IR."
  [value]
  (when-not (map? value)
    (throw (ex-info "query must be a map"
                    {:type :query-invalid-syntax})))
  (let [rules? (query-has? value :rules)
        strata? (query-has? value :strata)]
    (when (= rules? strata?)
      (throw (ex-info "query requires exactly one of rules or strata"
                      {:type :query-invalid-syntax})))
    (framrpc/rpc-ordered-query-plan!
     (lower-query-find! (require-query-field! value :find))
     (mapv (fn [rules]
             (framrpc/rpc-query-stratum!
              (mapv lower-query-rule! rules)))
           (if rules?
             [(require-query-field! value :rules)]
             (require-query-field! value :strata)))
     (mapv
      (fn [clause]
        (framrpc/rpc-query-order!
         (long (require-query-field! clause :column))
         (query-operation!
          (require-query-field! clause :direction)
          "query order direction")))
      (or (query-field value :order-by) []))
     (when (query-has? value :limit)
       (long (query-field value :limit))))))

(defn native-query-payload!
  ([query] (native-query-payload! query nil))
  ([query as-of]
   (framrpc/rpc-query-request!
    (lower-query-plan! query)
    (if (nil? as-of)
      framrpc/query-current
      (framrpc/rpc-query-as-of! (long as-of))))))

(defn- server-rt [port req]
  (with-open [s (server-socket (connect-host) port)]
    (let [w (.getOutputStream s)
          reader (server-reader s)]
      (.write w
              (.getBytes (str (pr-str req) "\n")
                         java.nio.charset.StandardCharsets/UTF_8))
      (.flush w)
      (parse-server-edn-line! (read-server-terminal-line! reader)))))

;; Protocol-level corpus identity. The distinct :for-log operation is deliberate:
;; an older server rejects it as unknown instead of ignoring an optional field and
;; mutating the wrong corpus. Low-level legacy functions below remain available for
;; compatibility; CLI/MCP entry points use the explicit *-for-log variants.
(defn canonical-log-path [path]
  (.getCanonicalPath (io/file path)))

(defn- log-envelope [log req]
  (rtc/log-envelope (canonical-log-path log) req))
;;
;;
;;

(defn server-request-for-log [port log req]
  (server-rt port (log-envelope log req)))

(defn server-version [port]
  (try (let [resp (server-rt port {:op :version})] (rtc/server-version-response resp))
       (catch Exception _ -1)))

(defn server-version-for-log [port log]
  (try
    (let [resp (server-request-for-log port log {:op :version})]
      (rtc/server-version-for-log-response resp))
;;
;;
;;
    (catch Exception _ -1)))

(defn- reject-message [rejection]
  (rtc/reject-message rejection))
;;
;;

(defn- server-write-response [resp]
  (rtc/server-write-response resp))
;;
;;
;;
;;
;;
;;
;;
;;

(defn- server-write [op port te pred value base]
  (try
    (server-write-response
     (server-rt port {:op op :te te :p pred :r value :base base :frame "agent"}))
    (catch Exception _ "error:noserver")))

(defn- server-write-for-log [op port log te pred value base]
  (try
    (server-write-response
     (server-request-for-log
      port log {:op op :te te :p pred :r value :base base :frame "agent"}))
    (catch Exception _ "error:noserver")))

(defn server-assert  [port te pred value base] (server-write :assert  port te pred value base))
(defn server-retract [port te pred value base] (server-write :retract port te pred value base))
(defn server-assert-for-log
  [port log te pred value base]
  (server-write-for-log :assert port log te pred value base))
(defn server-retract-for-log
  [port log te pred value base]
  (server-write-for-log :retract port log te pred value base))

(defn server-port [] (if-let [p (System/getenv "FRAM_SERVER_PORT")] (Integer/parseInt p) 7977))

(defn server-status [port]
  (try (let [r (server-rt port {:op :status})]
         (str "up|" (:version r) "|" (:facts r) "|" (:log r)))
       (catch Exception _ "down")))

(defn server-status-for-log [port log]
  (try
    (let [r (server-request-for-log port log {:op :status})]
      (rtc/server-status-response port r))
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
;;
    (catch Exception _
      (rtc/server-status-down port))))
;;
;;
;;

;; warm READ ops — served off the server's in-memory warm store / index, avoiding the
;; COLD full-log fold the MCP/CLI read path pays per request (interface investigation
;; #1: ~60x tax — cold load-state ~450ms vs warm ~7ms on the canonical log). warm-read
;; returns the parsed resp, or NIL if the server is down OR doesn't support the op
;; ({:error "unknown op"} from an older server predating the warm-op commits):
;; the caller falls back to the cold path on nil. This IS the capability handshake.
;; Keyed on (l,p,r) / Datalog strings — REP-STABLE across the fractional/CRDT ordering
;; rewrite (no fN ordering touched).
(defn warm-read [port req]
  (try (let [r (server-rt port req)]
         (rtc/warm-read-response r))
       (catch Exception _ nil)))
(defn warm-read-for-log [port log req]
  (try
    (let [r (server-request-for-log port log req)]
      (rtc/warm-read-for-log-response r))
;;
;;
    (catch Exception _ nil)))
(defn server-query    [port q]       (warm-read port {:op :query :query q}))   ; -> q/run envelope | nil
(defn server-query-page
  [port q limit after]
  (binding [*server-response-byte-limit* query-page-response-byte-limit]
    (warm-read port {:op :query-page :query q :limit limit :after after})))
(defn server-callers  [port te]      (warm-read port {:op :callers :te te}))   ; -> {:callers [...]} | nil
(defn server-resolved [port te pred] (warm-read port {:op :resolved :te te :p pred})) ; -> {:value :members :ambiguous? :values} | nil — surfaces multiplicity (#3)
(defn server-query-for-log
  [port log q]
  (warm-read-for-log port log {:op :query :query q}))
(defn server-query-page-for-log
  [port log q limit after]
  (binding [*server-response-byte-limit* query-page-response-byte-limit]
    (warm-read-for-log
     port log {:op :query-page :query q :limit limit :after after})))
(defn server-callers-for-log
  [port log te]
  (warm-read-for-log port log {:op :callers :te te}))
(defn server-resolved-for-log
  [port log te pred]
  (warm-read-for-log port log {:op :resolved :te te :p pred}))
(defn server-show-for-log
  [port log te]
  (warm-read-for-log port log {:op :show :te te}))

;; :facts — the server's WHOLE live view as [l p r] triples: the server-first read
;; path (thread 019f2190). The CLI rebuilds its kernel index from this instead of
;; paying the per-process cold fold (read-log EDN parse + fold ≈ 700ms on the 11k-line
;; north log). The server serves the triples IN FOLD EMISSION ORDER (its contract —
;; fram.fold/refold-order, cached per version), so the records returned here feed
;; build-index directly and every listing stays byte-identical to the cold fold's.
;; Asked with {:fmt :json} DELIBERATELY: this is a multi-megabyte whole-corpus
;; payload, and bb parses JSON (cheshire, native) substantially faster than EDN.
;; server-live-state retains the response version beside the facts so long-lived
;; clients can cache one built projection and refresh only after the server
;; version moves. nil is the capability sentinel: server down, old server, malformed
;; response, or a server serving another log. server-live-facts preserves the older
;; Vec-only interface for Beagle/CLI callers.
(defn server-live-state [port log]
  (let [facts-timeout
        (server-timeout-ms "FRAM_SERVER_FACTS_TIMEOUT_MS" 30000)]
    (try
      (with-open [s (server-socket (connect-host) port)]
        (let [w (.getOutputStream s)
              reader (server-reader s)]
          (.write w
                  (.getBytes
                   (str (pr-str (log-envelope log {:op :facts :fmt :json})) "\n")
                   java.nio.charset.StandardCharsets/UTF_8))
          (.flush w)
          (let [resp (parse-server-json-line!
                      (read-server-terminal-line-with-timeout!
                       reader
                       facts-timeout))]
            (when (and (map? resp)
                       (number? (get resp "version"))
                       (= (canonical-log-path log)
                          (canonical-log-path (get resp "log")))
                       (vector? (get resp "facts")))
              {:version (long (get resp "version"))
               :facts (mapv
                       (fn [t]
                         ((requiring-resolve 'fram.kernel/->Fact)
                          (nth t 0) (nth t 1) (nth t 2)))
                       (get resp "facts"))}))))
      (catch Exception _ nil))))

(defn server-live-facts [port log]
  (or (:facts (server-live-state port log)) []))

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
(defn filter-digits [s] (rtc/filter-digits s))
(defn is-iso-datetime-19 [s] (rtc/is-iso-datetime-19 s))
(defn is-iso-datetime-16 [s] (rtc/is-iso-datetime-16 s))
(defn repeat-str [s n] (rtc/repeat-str s n))

;; Clockify HTTP — lazy-resolve babashka.http-client so the AOT/native path
;; never references it at compile time (network/out-of-scope there).
(defn http-get [url api-key]
  (or (:body ((requiring-resolve 'babashka.http-client/get)
              url {:headers {"X-Api-Key" api-key}})) ""))
(defn http-post [url api-key body]
  (or (:body ((requiring-resolve 'babashka.http-client/post)
              url {:headers {"X-Api-Key" api-key "Content-Type" "application/json"}
                   :body body})) ""))
