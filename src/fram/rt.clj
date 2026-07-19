(ns fram.rt
  "Host-interop runtime for Fram's Beagle modules — the irreducible Clojure
  layer (file IO, log read/write, string ops) the .bclj `declare-extern`s bind
  to. Beagle owns the typed logic; this owns the host calls.

  Paths default to the current working directory (./threads, ./coordination.log) and
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

;; parse an EDN string from the CLI (a `query`/`call` argument) into data;
;; nil on parse failure so the caller can report it instead of crashing.
(defn parse-edn [s] (try (edn/read-string s) (catch Exception _ nil)))

;; read-log — torn-tail recovery + fail-closed corruption. The live daemon
;; appends WITHOUT fsync, so a reader can catch the file mid-write: the FINAL
;; line may be truncated (its terminating newline not yet flushed). Policy, at
;; the EDN parse boundary:
;;   * parses to a value          -> a FactOp. An EDN-VALID-but-incomplete line
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
;; public: the daemon's incremental tail reader (coord_daemon read-log-tail*)
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
                    (recur next-i (conj! acc (fold/->FactOp (:tx m) (:op m) (:l m) (:p m) (:r m)
                                                            (or (:frame m) (:by m) "legacy")))))
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
        coord (read-log primary)
        telemetry (or (System/getenv "FRAM_TELEMETRY_LOG") inferred)]
    (if telemetry
      (vec (sort-by #(or (:tx %) 0) (into coord (read-log telemetry))))
      coord)))

;; ============================================================================
;; vGUARD — the rollback floor (Reification R0; B2 contract §2/§3/§5).
;;
;; This release is the ROLLBACK FLOOR for the generation-flip protocol the vR
;; rewrite verbs (unify / import --force / compact / split) will ship: any pin
;; revert lands HERE, never on a binary with live unguarded rewrite verbs.
;; Four laws, all in this block:
;;
;;   1. WRITER ADMISSION — every supported append (daemon group batch, cold
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
;;   4. BOOT PARTICIPATION — a daemon acquires the lock BEFORE first serve:
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
;; Replacement-file names are PROTOCOL CONSTANTS shared by the vR flip writer
;; and this doctor: roll-back knows exactly which tmps to sweep, roll-forward
;; which composed telemetry replacement to prefer.
(defn rewrite-coord-tmp-path [log-path]
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
    (boolean (some (fn [op] (and (= "@log:gen" (:l op)) (= "generation" (:p op))))
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
  (let [coord     (str log-path)
        live-ino  (when (.exists (io/file coord)) (file-ino coord))
        old-ino   (get-in intent [:coord :ino])
        new-ino   (get-in intent [:new_coord :ino])
        old-bytes (get-in intent [:coord :bytes])
        old-sha   (get-in intent [:coord :sha])
        new-sha1  (get-in intent [:new_coord :sha1])]
    (cond
      (nil? live-ino)
      (throw (ex-info (str "rewrite intent present but " coord " does not exist — refusing to classify")
                      {:path coord :fram/doctor-refusal true}))
      (and old-ino (= live-ino old-ino)) :roll-back
      (and new-ino (= live-ino new-ino)) :roll-forward
      ;; ino unavailable/recycled: fall back to content shas.
      (and new-sha1 (= new-sha1 (file-line1-sha16 coord))) :roll-forward
      (and old-bytes old-sha (= old-sha (file-prefix-sha16 coord old-bytes))) :roll-back
      :else
      (throw (ex-info (str "rewrite intent does not match the live corpus at " coord
                           " (neither source nor replacement inode/sha) — refusing to classify; "
                           "operator intervention required")
                      {:path coord :intent intent :fram/doctor-refusal true})))))

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
    ;; stale by construction (an old daemon would also invalidate them; sweeping
    ;; makes it unconditional).
    (delete-if-exists! (str log-path ".snap"))
    (delete-tree! (str log-path ".snapshots"))
    ;; leftover coordination tmp (the rename source when the flip died post-move)
    (delete-if-exists! (rewrite-coord-tmp-path log-path))
    ;; (12) delete the intent + directory fsync.
    (delete-if-exists! (rewrite-intent-path log-path))
    (fsync-dir! dir)
    :rolled-forward))

(defn- roll-back! [log-path intent]
  ;; The coordination rename never happened: sweep the composed tmps, restore
  ;; the exact recorded modes on the untouched sources, drop the intent.
  (let [dir   (corpus-dir log-path)
        telem (telem-path-for log-path)]
    (delete-if-exists! (rewrite-coord-tmp-path log-path))
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
  healed — REFUSE LOUD (the caller's ack path delivers the throw; the daemon
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

;; --- daemon boot participation (law 4) --------------------------------------
(defn boot-rewrite-gate!
  "Acquire the rewrite lock BEFORE first serve and RETURN a SHARED lock handle
  the caller holds across its boot fold (close it with close-rewrite-lock!
  before serving — a serving daemon holds the shared lock per append batch,
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
  CLI's first line stays the coordinator health contract, so this line prints
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

(def ^:dynamic *coord-response-byte-limit* nil)

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

(def query-page-response-byte-limit 1048576)

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
        limit (or *coord-response-byte-limit* (coord-response-byte-limit))
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
(defn coord-query-page
  [port q limit after]
  (binding [*coord-response-byte-limit* query-page-response-byte-limit]
    (warm-read port {:op :query-page :query q :limit limit :after after})))
(defn coord-callers  [port te]      (warm-read port {:op :callers :te te}))   ; -> {:callers [...]} | nil
(defn coord-resolved [port te pred] (warm-read port {:op :resolved :te te :p pred})) ; -> {:value :members :ambiguous? :values} | nil — surfaces multiplicity (#3)
(defn coord-query-for-log
  [port log q]
  (warm-read-for-log port log {:op :query :query q}))
(defn coord-query-page-for-log
  [port log q limit after]
  (binding [*coord-response-byte-limit* query-page-response-byte-limit]
    (warm-read-for-log
     port log {:op :query-page :query q :limit limit :after after})))
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
