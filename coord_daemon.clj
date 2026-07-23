;; coord_daemon.clj — Stage 7: the reified coordinator as a socket daemon.
;; ============================================================================
;; Speaks the SAME wire protocol as coord.clj (:version/:assert/:retract/:ready/
;; :blocked/:leverage/:validate/:status/:subscribe), so fram.rt's socket
;; client + the CLI + the MCP work UNCHANGED after the cutover. Internally it
;; commits through the reified kernel (coord) over the v2 log, and serves
;; reads by projecting the reified live view into the EXISTING, proven projections
;; (fram.projections) — the read side of the cutover. The reified live view
;; is set-equal to the flat fold (store_domain_test/store_lifecycle_test), so those
;; projections return identical results.
;;
;;   bb -cp out coord_daemon.clj serve [port] [v2-log]
;;   bb -cp out coord_daemon.clj test  [port]
;; ============================================================================
(require '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.set]
         '[fram.store :as c] '[fram.schema :as s]
         '[fram.kernel :as ck]
         '[fram.fold :as fold] '[fram.query :as q] '[fram.datalog :as d] '[fram.rt])
(import '[java.net ServerSocket Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter FileInputStream]
        '[javax.net.ssl SSLContext KeyManagerFactory TrustManagerFactory]
        '[java.security KeyStore])
(load-file "coord.clj")          ; the reified coordinator library
(load-file "fri.clj")            ; FRAM_MMAP_IMAGE V1: the .fri columnar mmap image (ns fri)
(load-file "pull.clj")           ; the PULL API (ns pull) — MUST load after coord.clj:
                                 ; pull references coord.clj's readers as user/… and SCI
                                 ; resolves those qualified symbols at analysis time.
;; resolve.clj — the store-parameterized lexical resolver (S3.1/S3.2), loaded as a
;; LIBRARY: its -main is guarded behind a recognized MODES arg, and the daemon's
;; *command-line-args* ("serve-flat" ...) is not one, so load-file runs NOTHING — it
;; only defines `resolve/resolve-warm-store!` + the read accessors. Loaded here, at
;; daemon-namespace-load time, so the `resolve/...`-qualified symbols in callers-of /
;; with-resolve-read resolve at compile time (load-file is run-once by nature).
(load-file (str (System/getProperty "user.dir") "/chartroom/src/resolve.clj"))

;; ---- state: one reified coordinator + a cached read index ------------------
(def co (atom nil))                  ; {:store :log :lock} — reified canonical (v2 log)
(def flat-log (atom nil))            ; the flat log, now a PROJECTION the cold CLI folds
(def cache (atom {:index nil :version -1}))
(def facts-wire-cache (atom {:version -1 :triples nil}))  ; :facts op — fold-ordered [l p r] wire triples, per version
;; Datalog PROJECTION cache (the EDB + base index a scan :query / :query-page runs
;; over). Building it is O(arity x corpus); without this cache every page and every
;; concurrent reader rebuilt it, so paginating a large derived relation was
;; O(corpus x pages) and N concurrent cold readers each allocated their own
;; full-corpus EDB+index — the memory-multiplication behind the load-stress OOM
;; and the pending-message-page query-time-limit. Keyed on the IDENTITY of the
;; version-guarded facts set (materialize-query-snapshot reuses one immutable set
;; object per version), so a hit needs no version arithmetic. `projection-build-lock`
;; single-flights the cold build: exactly one full-corpus projection is materialized
;; at a time, and concurrent readers share the one immutable result. {:facts <set> :projection <proj>}
(def projection-cache (atom nil))
(def projection-build-lock (Object.))
(def subscribers (atom []))
(def dlock (Object.))                ; serializes reload + writes + reads (drop-in mode)
(def flat-mtime (atom nil))          ; last-seen flat-log stamp (to detect external edits)
(def telemetry-mtime (atom nil))     ; same freshness fence for the split telemetry half
(def flat-canonical? (atom false))   ; drop-in mode: flat log is canonical, reload absorbs edits
(def require-log-fence?
  (= "1" (System/getenv "FRAM_REQUIRE_LOG_FENCE")))

;; Canonical corpus identity for the log-fenced wire envelope. Clients that know
;; which physical log they intend to use send:
;;
;;   {:op :for-log :expected-log "/path/to/facts.log" :request {<legacy request>}}
;;
;; This is a DISTINCT top-level op rather than an optional field on legacy ops:
;; an older daemon rejects :for-log as unknown and therefore cannot accidentally
;; execute the nested mutation. New daemons continue to accept legacy low-level
;; requests, preserving old-client -> new-daemon compatibility.
(defn- canonical-path [path]
  (.getCanonicalPath (java.io.File. (str path))))

(defn- served-log-path []
  (when-let [path (or @flat-log (:log @co))]
    ;; boot!/boot-flat! freeze the physical identity once. Never resolve this
    ;; stored path again: a symlink or parent-directory retarget after boot must
    ;; not move the daemon's identity (or its writes) to a different corpus.
    (str path)))

(defn- log-fence-rejection [expected]
  (try
    (let [expected* (when (and (string? expected) (not (str/blank? expected)))
                      (canonical-path expected))
          served* (served-log-path)]
      (cond
        (nil? expected*)
        {:reject ["log fence requires a non-blank :expected-log path"]
         :code :invalid-log-fence}

        (nil? served*)
        {:reject ["coordinator has no served log identity"]
         :code :log-identity-unavailable
         :expected-log expected*}

        (not= expected* served*)
        {:reject [(str "log mismatch: client expects " expected*
                       " but coordinator serves " served*)]
         :code :log-mismatch
         :expected-log expected*
         :served-log served*}

        :else nil))
    (catch Throwable t
      {:reject [(str "invalid expected log path: "
                     (or (.getMessage t) (.getSimpleName (class t))))]
       :code :invalid-log-fence
       :expected-log (str expected)})))
;; ---- the log split: kind-routed per-log persistence (A1: unified store, split disk) ----
;; FRAM_TELEMETRY_LOG nil => single-log, BYTE-IDENTICAL to pre-split (write path,
;; boot, snapshot all unchanged). Set => the coordinator partitions each written flat
;; line to a per-log file by log-for(subject); boot merge-replays both by :tx into
;; the ONE unified store, so every warm read/query/subscribe still sees all facts.
(def telemetry-log (atom (System/getenv "FRAM_TELEMETRY_LOG")))
;; telemetry allow-list of KIND names — an allow-list, never a deny-list: everything
;; NOT here (thread/concern/@agent/@lease/@cmd/@swarm/un-kinded legacy/unknown) is
;; coordination. Hardcoded fallback; overridable as data via @log-routing config facts.
(def default-telemetry-kinds #{"run" "session" "mine" "guard_denial"})
(def telemetry-kinds (atom default-telemetry-kinds))
;; ENGINE predicates — never ordinary domain data. Split by WRITE POLICY (F3):
;;   hard-reserved   — identity/bookkeeping; a domain tell/retract is REJECTED (as always).
;;   schema-writable — cardinality/value_kind: VALIDATED domain writes (the schema-write
;;                     gate at do-assert/do-retract), routed through the schema layer and
;;                     appended VERBATIM so the cold CLI fold's card-map still sees them.
;; schema-preds stays their UNION, so every downstream filter (materialization skips at
;; migrate/apply-tail!, fact->triple/lp-live-triples read-view hiding) is byte-identical.
(def hard-reserved #{"name" "store-supersedes"})
(def schema-writable #{"cardinality" "value_kind"})
(def schema-preds (clojure.set/union hard-reserved schema-writable))

;; ---- FRAM_MMAP_IMAGE (thread 019f82d9): the beyond-RAM mmap-cold slice ---------
;; Flag UNSET/off => every byte of today's behavior (checkpoint stays v2log, boot
;; unchanged, bar 1). Flag on => the checkpoint writer ALSO emits a .fri columnar
;; image; boot mmaps it READ-ONLY (OS page cache holds the cold corpus, NOT the JVM
;; heap) and serves the by-l/by-lp/fact/value primitives from it, deferring the heap
;; fold until a whole-corpus op arrives (ensure-materialized!). An atom (not a bare
;; env read) so in-process tests/bench flip it without an env round-trip. Declared
;; early: the :status handler (hybrid-fact-count) and maybe-reload! reference these.
(def mmap-image-enabled?
  (atom (contains? #{"1" "true" "on"} (str/lower-case (str (System/getenv "FRAM_MMAP_IMAGE"))))))
(def cold-image (atom nil))     ; the open fri.Image while booted mmap-cold + unmaterialized
(declare ensure-materialized! mmap-boot write-fri-snapshot! hybrid-fact-count
         cold-served? cold-by-l cold-by-lp cold-render mmap-reconcile)

;; ---- warm scope-correct code-intelligence (refers_to materialized over `co`) -
;; resolve.clj's lexical resolver (loadable as a library) writes refers_to + render-
;; marker facts into a store. We run it OVER the warm `co` store, version-cached.
;; These predicates are DERIVED / in-memory: they must (a) never reach the flat log,
;; (b) never leak into the S1 :query warm cache (which keys on current-seq), and
;; (c) never bump current-seq. fact->triple filters them out of every read projection
;; (so :query/:warm-check/the read view never see them); the materialize step rolls
;; back the seq-space the resolver's tx consumed.
(def resolve-preds #{"refers_to" "keep_spelling" "qualifier" "ctor_prefix" "accessor_field" "supersedes"})
;; #(a) identity: bound_to is DURABLE (persisted to the flat log — an authored identity edge,
;; reference -> binding's stable @mod#int) but for option-1 scope is kept OUT of read projections
;; (:query/datalog/warm-cache/tripwire) — render+resolve read it off the store directly. Filtered
;; HERE (read view) WITHOUT being a resolve-pred (which would strip it from the store + roll back seq).
;; withdrawn_* are the first-class retraction SURFACE (thread H): facts-about-a-cid
;; recording who/when/why a fact was cancelled. Like store-supersedes they are bookkeeping
;; ON cid-subjects, not domain data — filter them from the warm read view / :query / index
;; (the resolve layer reads them off the store directly via coord/withdrawal-of).
(def read-hidden-preds #{"bound_to" "withdrawn_by" "withdrawn_at" "withdrawn_reason"})
(def refers-version (atom -1))       ; the co version refers_to was last materialized at

;; ---- S3.3: scoped re-resolve state -----------------------------------------
;; Whole-corpus re-resolve on every code edit is O(corpus) per commit — the swarm
;; write-ceiling. S3.3 makes re-resolve MODULE-GRANULAR: track which modules an
;; edit touched (dirty), classify by EXPORT-SET delta (not syntactic site), and
;; re-resolve ONLY the affected module set (dirty ∪ export-changed consumers).
;; dirty-modules  : modules with an asserted/retracted AST fact since last materialize.
;; export-snapshot: {module -> #{exported/top-level name}} captured at the last
;;                  materialize — the classifier diffs the live set against it.
;; materialized?  : has refers_to been fully materialized at least once (cold gate —
;;                  the FIRST materialize is necessarily whole-corpus). maybe-reload!
;;                  (external flat rebuild) resets it, forcing a fresh whole pass.
;; last-materialize: instrumentation for the gate — {:mode :scoped|:whole :walked #{mods}
;;                  :stripped <n> :forms-walked <n>}. Read via the :refers-debug op.
(def dirty-modules (atom #{}))
(def export-snapshot (atom {}))
(def materialized? (atom false))
(def last-materialize (atom nil))
;; module of an AST node name "@kernel#127" -> "kernel" (same parse resolve.clj uses).
(defn- module-of-name [nm]
  (when (string? nm)
    (when-let [[_ m] (re-matches #"@([^#]+)#\d+" nm)] m)))
;; record that a commit touched te's module (te is "@mod#int"); skip non-AST te.
(defn- mark-dirty! [te]
  (when-let [m (module-of-name te)] (swap! dirty-modules conj m)))
;; The incremental corpus cache (ensure-corpus-groups! below) — declared here so the reload-reset
;; can invalidate it; the fns that use it are defined after with-resolve-read.
(def corpus-groups (atom nil))    ; {module-src -> [entity-ids]} | nil (cold/invalidated)
;; calls_defn closure cache (thread 019f1010-2705) — declared here so reset-refers-state!
;; can invalidate it; ensure-calls! + the fns that read it are defined after ensure-refers!.
(def calls-version (atom -1))     ; the refers-version calls_defn was last derived at
(def calls-cache   (atom nil))    ; {:edges [[caller callee]] :blast {callee -> #{callers}} :defns {name -> meta}}

;; reset all S3.3 derived state — called when `co` is REBUILT wholesale (boot /
;; external flat reload): the in-memory refers_to + export snapshot belong to the OLD
;; store, so the next materialize must be a fresh cold whole-corpus pass.
(defn- reset-refers-state! []
  (reset! dirty-modules #{})
  (reset! export-snapshot {})
  (reset! materialized? false)
  (reset! refers-version -1)
  (reset! last-materialize nil)
  (reset! corpus-groups nil)
  (reset! calls-version -1)            ; calls_defn closure belongs to the OLD store
  (reset! calls-cache nil))

;; ---- DoS hardening knobs (findings #2/#5/#19/#20) --------------------------
;; Read timeout on every accepted socket — mirrors the CLIENT side (fram.rt
;; coord-socket, 2000ms), but a touch longer so a legitimately-slow gateway
;; round-trip isn't cut off. A slow-loris / never-sends-newline client now
;; trips SocketTimeoutException instead of pinning a thread+socket forever.
(def ^:const sock-read-timeout-ms 5000)
;; Per-connection line cap — the wire protocol is one EDN line per request, so a
;; line larger than this is malicious/buggy. Bounds BufferedReader memory growth
;; (a no-newline client can't balloon the heap) and caps edn/read-string input.
(def ^:const max-line-bytes (* 1024 1024))        ; 1 MiB
;; EDN nesting bound — clojure.edn/read-string is recursive-descent and throws
;; StackOverflowError on deep nesting (overflows ~16k deep). Reject obviously
;; over-nested input cheaply (a count of opening delimiters) BEFORE handing it to
;; the reader, so a deep-nest payload returns a clean {:error} instead of an Error.
(def ^:const max-edn-depth 200)

;; Every daemon query has hard server-side limits.  Request fields may LOWER a
;; limit for a caller/test, never raise the daemon's ceiling.
(defn- positive-env-long [name fallback]
  (let [n (some-> (System/getenv name) parse-long)]
    (if (and n (pos? n)) n fallback)))
(def query-timeout-ms (positive-env-long "FRAM_QUERY_TIMEOUT_MS" 5000))
(def query-max-steps (positive-env-long "FRAM_QUERY_MAX_STEPS" 10000000))
(def query-max-rows (min q/max-results (positive-env-long "FRAM_QUERY_MAX_ROWS" q/max-results)))
(def query-max-response-bytes
  (positive-env-long "FRAM_QUERY_MAX_RESPONSE_BYTES" (* 64 1024 1024)))
(def active-queries (atom 0))
(def active-query-monitors (atom 0))
(def active-reloads (atom 0))
(def reload-retries (atom 0))
(def reload-generation (atom 0))
(def query-stops (atom {:query-cancelled 0 :query-work-limit 0
                        :query-time-limit 0 :query-row-limit 0}))

(defn- complete-flat-record? [record]
  (and (map? record)
       (int? (:tx record))
       (#{"assert" "retract"} (:op record))
       (some? (:l record))
       (some? (:p record))
       (some? (:r record))))

(defn- read-exact-flat-record [^bytes tail]
  (try
    (with-open [reader (java.io.PushbackReader.
                        (java.io.StringReader.
                         (String. tail java.nio.charset.StandardCharsets/UTF_8)))]
      (let [eof (Object.)
            record (edn/read {:eof eof} reader)
            trailing (edn/read {:eof eof} reader)]
        (when (and (not (identical? eof record))
                   (identical? eof trailing))
          record)))
    (catch Exception _ nil)))

(defn- repair-flat-tail!
  "Repair one unterminated final flat-log segment while the caller holds the
  corpus EXCLUSIVE rewrite lock. A complete fold-visible record is preserved by
  durably adding its missing LF; an unparseable/incomplete segment is durably
  truncated back to the last complete LF boundary. Mid-log corruption remains
  untouched and is refused by the normal fold."
  [label path]
  (let [f (java.io.File. (str path))]
    (when (and (.exists f) (pos? (.length f)))
      (when-not (and (.isFile f) (not (java.nio.file.Files/isSymbolicLink (.toPath f))))
        (throw (ex-info (str label " flat log is not a real regular file: " path)
                        {:path (str path) :fram/flat-tail-repair-refused true})))
      (with-open [raf (java.io.RandomAccessFile. f "rw")]
        (let [length (.length raf)]
          (.seek raf (dec length))
          (when-not (= 10 (.read raf))
            (let [window-size (int (min length (inc (long max-line-bytes))))
                  window-start (- length window-size)
                  window (byte-array window-size)
                  _ (do (.seek raf window-start) (.readFully raf window))
                  last-lf (loop [i (dec window-size)]
                            (cond
                              (neg? i) nil
                              (= 10 (bit-and 0xff (aget window i))) i
                              :else (recur (dec i))))
                  boundary (if last-lf (+ window-start last-lf 1) 0)
                  tail-bytes (- length boundary)]
              (when (> tail-bytes max-line-bytes)
                (throw (ex-info (str label " unterminated flat-log tail exceeds the repair bound: "
                                     tail-bytes " bytes")
                                {:path (str path) :bytes tail-bytes
                                 :max-bytes max-line-bytes
                                 :fram/flat-tail-repair-refused true})))
              (let [tail (byte-array (int tail-bytes))
                    _ (do (.seek raf boundary) (.readFully raf tail))
                    parsed (read-exact-flat-record tail)
                    preserve? (complete-flat-record? parsed)
                    expected-length (if preserve? (inc length) boundary)]
                (if preserve?
                  (do (.seek raf length) (.write raf 10))
                  (.setLength raf boundary))
                (.force (.getChannel raf) true)
                (when-not (= expected-length (.length raf))
                  (throw (ex-info (str label " flat-log tail repair did not reach its exact boundary")
                                  {:path (str path) :expected expected-length
                                   :actual (.length raf)
                                   :fram/flat-tail-repair-refused true})))
                (when (pos? expected-length)
                  (.seek raf (dec expected-length))
                  (when-not (= 10 (.read raf))
                    (throw (ex-info (str label " flat-log tail repair did not leave a terminal LF")
                                    {:path (str path)
                                     :fram/flat-tail-repair-refused true}))))
                (binding [*out* *err*]
                  (println (str "[fram] repaired " label " flat-log tail at byte " boundary
                                (if preserve?
                                  " — complete record preserved and LF forced"
                                  (str " — truncated " tail-bytes " incomplete byte(s) and forced")))))))))))))

(defn- repair-flat-corpus-tails! [flat]
  (doseq [[label path]
          (distinct
           (remove (comp nil? second)
                   [["coordination" (canonical-path flat)]
                    ["telemetry" (some-> @telemetry-log canonical-path)]]))]
    (repair-flat-tail! label path)))

(defn- assert-flat-corpus-append-boundaries! []
  (locking group-io-lock
    (when @flat-log
      (assert-flat-append-boundary! @flat-log)
      (when-let [path @telemetry-log]
        (assert-flat-append-boundary! path)))))

(defn- stamp [f] (let [fi (java.io.File. (str f))] (str (.lastModified fi) ":" (.length fi))))

;; bounded readLine: read at most `cap` chars, stopping at newline. Returns the
;; line (sans newline), nil at clean EOF, or throws ex-info {:type :line-too-long}
;; if the cap is hit with no newline — so a client streaming bytes forever can
;; neither pin the thread (setSoTimeout already bounds idle) nor exhaust the heap.
(defn- read-line-bounded ^String [^BufferedReader r ^long cap]
  (let [sb (StringBuilder.)]
    (loop []
      (let [ch (.read r)]
        (cond
          (= ch -1)  (when (pos? (.length sb)) (.toString sb))   ; EOF: partial -> return, empty -> nil
          (= ch 10)  (.toString sb)                              ; \n terminates the line
          (= ch 13)  (recur)                                     ; ignore \r (CRLF)
          :else      (do (when (>= (.length sb) cap)
                           (throw (ex-info "line too long" {:type :line-too-long})))
                         (.append sb (char ch))
                         (recur)))))))

;; cheap pre-parse depth guard: the deepest run of unmatched opening delimiters.
;; Rejecting here avoids the JVM recursive reader StackOverflowError (#5).
(defn- edn-too-deep? [^String s]
  (loop [i 0 depth 0 mx 0 in-str false esc false]
    (if (>= i (.length s))
      (> mx max-edn-depth)
      (let [c (.charAt s i)]
        (cond
          esc          (recur (inc i) depth mx in-str false)
          (and in-str (= c \\)) (recur (inc i) depth mx in-str true)
          in-str       (recur (inc i) depth mx (not (= c \")) false)
          (= c \")     (recur (inc i) depth mx true false)
          (or (= c \() (= c \[) (= c \{))
                       (let [d (inc depth)] (recur (inc i) d (max mx d) in-str false))
          (or (= c \)) (= c \]) (= c \}))
                       (recur (inc i) (max 0 (dec depth)) mx in-str false)
          :else        (recur (inc i) depth mx in-str false))))))

;; parse a request line defensively: bound depth, keep clojure.edn (never
;; read-string — no #= eval), and surface a parse failure as data, not a throw.
(defn- parse-req [^String line]
  (when (edn-too-deep? line)
    (throw (ex-info "edn too deep" {:type :edn-too-deep})))
  (edn/read-string line))

;; flat-log projection: each reified commit also appends the flat {:op :l :p :r}
;; line the CLI's cold fold reads — so "files are pure projections of the reified
;; store" (Stage 7) and existing reads keep working UNCHANGED across the cutover.
;; Refreshes flat-mtime so our OWN write isn't mistaken for an external edit.
;; flat-log batching: a multi-fact commit collects its lines and writes+fsyncs ONCE (1 fsync per
;; commit, NOT per fact — the per-fact fsync was the dominant authoring cost, ~13 fsyncs/def).
;; *flat-batch* (an atom of line-strings) is bound around a commit; nil => write immediately.
(def ^:dynamic *flat-batch* nil)
(defn- flat-line [op te p r seq]
  (str (pr-str {:tx seq :op op :l te :p p :r r :ts (fram.rt/now-ts) :by "coord"}) "\n"))

(defn- advance-owned-append-stamp!
  "Advance a known corpus stamp only when the appender proved that the bytes
  between its before/after observations are exactly its own batch. If an
  external prefix was already pending—or raced the append—the old stamp stays
  visible and the next freshness-sensitive request must reload it."
  [known-stamp {:keys [before-stamp after-stamp owned-append-exact?]}]
  (when (and owned-append-exact? (= before-stamp @known-stamp))
    (reset! known-stamp after-stamp)))
;; DURABILITY (finding #13) is preserved through GROUP COMMIT (coord/enqueue-durable!):
;; the lines are enqueued (in commit order — callers hold dlock) and the {:ok} ack only
;; happens after the appender thread has fsynced them (handle awaits the tickets after
;; releasing dlock; library callers await inline). In drop-in (serve-flat) mode the flat
;; log is the ONLY durable record; the batch fsync makes the acked write survive a crash
;; exactly as the old in-lock fsync did — but K concurrent writers now share ONE fsync
;; instead of convoying on K of them. flat-mtime is refreshed on the appender thread,
;; atomically with the append (group-io-lock), so our OWN write is never mistaken for an
;; external edit by maybe-reload!.
;; ---- log-for: which log does a subject's line belong to? -------------------
;; PRIMARY = the subject's stored `kind` fact (warm store, O(1)). FALLBACK (kind-less
;; subject, e.g. @session which carries NO kind fact yet is the biggest telemetry
;; mass) = the structural @<token> prefix. A subject is promoted to :telemetry ONLY
;; when its kind — or, kind-less, its token — is in the telemetry allow-list; every-
;; thing else defaults :coordination (threads @2026-*, concern-*, @agent/@lease/@cmd
;; tokens not in the list, un-kinded legacy). Misroute is hygiene-only, never a read
;; bug: the store is unified (A1), so a line in the "wrong" file still folds correctly.
(defn- subject-token [subject]
  (when (and (string? subject) (> (count subject) 1) (= \@ (.charAt ^String subject 0)))
    (let [c (.indexOf ^String subject ":")]
      (when (pos? c) (subs subject 1 c)))))
(defn- kind-of-subject [st subject]
  (when (and st subject)
    (when-let [sid (s/resolve-name st subject)]
      (s/lookup st sid "kind"))))
(defn- log-for [st subject]
  (let [k (or (kind-of-subject st subject) (subject-token subject))]
    (if (contains? @telemetry-kinds k) :telemetry :coordination)))
;; a flat line is one EDN map per string; recover its subject (:l) to route it.
;; A malformed/unreadable line routes :coordination (safe default).
(defn- line-subject [ln]
  (try (:l (edn/read-string ln)) (catch Exception _ nil)))

;; the kind→log map is DATA, not code: `@log-routing telemetry_kind <k>` facts (multi-
;; valued) override the hardcoded allow-list. Absent => default holds. Read once at boot.
(defn- load-log-routing! [st]
  (when st
    ;; @worlds was the original public name. Prefer the vocabulary-neutral name,
    ;; but keep old persisted routing policy effective during migration.
    (let [canonical (when-let [wid (s/resolve-name st "@log-routing")]
                      (seq (s/lookup-all st wid "telemetry_kind")))
          legacy    (when-let [wid (s/resolve-name st "@worlds")]
                      (seq (s/lookup-all st wid "telemetry_kind")))]
      (when-let [ks (or canonical legacy)]
        (reset! telemetry-kinds (set (map str ks)))))))

(defn- write-flat-lines! [lines]
  (when (and @flat-log (seq lines))
    (if-let [tlog @telemetry-log]
      ;; ROUTED: partition by log; the group-commit appender already keys on :path,
      ;; so two enqueues to two paths fan out with one fsync per file. Coordination keeps
      ;; the EXACT legacy path + flat-mtime callback (so the daemon's own coordination
      ;; append is never misread as an external edit); telemetry needs no mtime tracking
      ;; (maybe-reload! watches only the coordination log).
      (let [st (some-> @co :store)
            g  (group-by #(log-for st (line-subject %)) lines)]
        (when-let [coord (seq (:coordination g))]
          (enqueue-durable! (str @flat-log) (vec coord)
                            (fn [flush]
                              (advance-owned-append-stamp! flat-mtime flush))))
        (when-let [telem (seq (:telemetry g))]
          (enqueue-durable! (str tlog) (vec telem)
                            (fn [flush]
                              (advance-owned-append-stamp! telemetry-mtime flush)))))
      ;; LEGACY single-log — BYTE-IDENTICAL to pre-split.
      (enqueue-durable! (str @flat-log) (vec lines)
                        (fn [flush]
                          (advance-owned-append-stamp! flat-mtime flush))))))
(defn- append-flat! [op te p r seq]
  (if *flat-batch*
    (swap! *flat-batch* conj (flat-line op te p r seq))    ; defer — flushed once at commit end
    (write-flat-lines! [(flat-line op te p r seq)])))       ; immediate (single-op / non-batched callers)

;; coord.clj persists leases through its v2 transaction log. In serve-flat mode
;; `co` deliberately has no v2 log, so accepted lease mutations need the same
;; flat projection bridge used by ordinary fact writes. Keep the mutation and
;; projection in this one dlock turn; handle awaits the resulting durable ticket
;; before acknowledging the request. Rejected/no-op calls append nothing.
(defn- lease-flat-mutation! [req action]
  (assert-flat-corpus-append-boundaries!)
  (let [st (:store @co)
        resource (:res req)
        before (read-lease @co resource)
        schema-single? (= "single" (s/cardinality st lease-pred))
        result (action)]
    (when @flat-log
      (let [seq (:ok result)
            acquired? (and (integer? seq)
                           (= seq (:epoch result)))
            released? (and (integer? seq)
                           before
                           (not (:noop result))
                           (nil? (:epoch result)))
            schema-lines (when (and acquired? (not schema-single?))
                           [(flat-line "assert" "@lease" "cardinality" "single" seq)
                            (flat-line "assert" "@lease" "value_kind" "literal" seq)])
            lease-line (cond
                         acquired?
                         (flat-line "assert" (lease-subj resource) lease-pred
                                    (encode-lease (:holder result) (:exp result) (:epoch result))
                                    seq)

                         released?
                         (flat-line "retract" (lease-subj resource) lease-pred
                                    (encode-lease (:holder before) (:exp before) (:epoch before))
                                    seq))]
        (write-flat-lines! (cond-> (vec schema-lines) lease-line (conj lease-line)))))
    result))
(defn- flush-flat-batch! []
  (when *flat-batch*
    (let [t0 (System/nanoTime) lines @*flat-batch*]
      (write-flat-lines! lines)
      (reset! *flat-batch* [])
      (when (= "1" (System/getenv "FRAM_PROF"))
        (binding [*out* *err*] (println (format "  flush(enqueue) n=%d %.1fms" (count lines) (/ (- (System/nanoTime) t0) 1e6))))))))

;; render one live fact cid into the SAME (l-name p-str r-rendered) shape build-index
;; wants: subject -> name, predicate -> literal, object -> literal (value) | name (ref).
;; Returns nil for a schema-pred OR resolve-pred fact (both excluded from the read view).
;; The single filter point: reified->facts, lp-live-triples, AND the warm cache all
;; funnel through here, so filtering BOTH sets here is what keeps the DERIVED refers_to
;; + render markers (materialized over `co` for :callers) invisible to :query, the
;; :warm-check tripwire, and the read view — the corpus :query sees is exactly the AST
;; facts the flat log ingested, identical whether or not refers_to has been materialized.
(declare query-check)
(defn- fact->triple [st cid]
  (let [cl (c/fact-of st cid) pstr (c/literal st (:p cl))]
    (when-not (or (schema-preds pstr) (resolve-preds pstr) (read-hidden-preds pstr))
      [(s/name-of st (:l cl)) pstr
       (if (c/value-object? st (:r cl)) (c/literal st (:r cl)) (s/name-of st (:r cl)))])))

;; read-bridge: reified live view -> the flat (l p r) Fact vec build-index wants.
;; PURE over its store: DOMAIN facts only (fact->triple hides every schema-pred). This is
;; the STORE-MATERIALIZATION view — the snapshot-reconcile gate compares two stores of the
;; same log through it, and it must stay byte-identical to pre-F4 (analyst invariant 3:
;; schema facts are NOT store-materialized as domain triples). The client READ view adds
;; the log-sourced schema facts on top — see client-view-facts below.
(defn reified->facts [c0]
  (let [st (:store c0)]
    (->> (c/current-facts st)
         (keep (fn [cid] (when-let [t (fact->triple st cid)] (ck/->Fact (nth t 0) (nth t 1) (nth t 2)))))
         vec)))

;; ---- schema-as-facts READ view (F4) ---------------------------------------
;; cardinality/value_kind are USER-DECLARABLE schema facts (F3 opened the write path), and
;; the "predicates are entities" promise says `show <pred>` must reveal them. But the STORE
;; cannot host their read view: migrate's def-predicate! seeds a cardinality + value_kind
;; fact for EVERY predicate (subject = the pred-name's value-object, so name-of => nil), so
;; the reified store holds ~1 seed pair per pred that the cold fold (log-resident lines only)
;; never emits. Projecting the store would leak ALL those seeds and shatter warm<->cold
;; parity — which is why the pre-F3 blanket filter hid every schema-pred. The AUTHORITY for
;; which schema facts are LIVE is the FLAT LOG (exactly what the cold fold reads): schema-view
;; mirrors the log's live schema-writable facts as [l p] -> ck/Fact in raw @-prefixed log
;; form. It is refreshed only at the RARE log-mutation points (boot / schema-write / external
;; reload), so the warm read path pays nothing per version.
(def schema-view (atom {}))   ; [l p] -> ck/Fact — live user-declared schema-writable facts

;; recompute schema-view from a flat log's schema-writable lines (keyed-latest via the SAME
;; fold the cold path uses, so the facts are byte-identical to the cold projection). Called
;; at boot + on external reload, when the log file is quiescent (safe to re-read).
(defn- schema-view-from-flat [flat]
  (->> (fram.rt/read-log flat)
       (filter #(schema-writable (:p %)))
       vec fold/fold :facts
       (reduce (fn [m cl] (assoc m [(:l cl) (:p cl)] cl)) {})))

(defn- seed-schema-view! [flat]
  (reset! schema-view (schema-view-from-flat flat)))

;; the CLIENT read view: DOMAIN facts (store) + user-declared SCHEMA facts (log). This is
;; what the daemon serves to CLI reads (:facts op, warm cache, :query/show). It is NOT the
;; reconcile/materialization view (that stays domain-pure, reified->facts). Every schema
;; fact here is hidden from the store projection, so there is no double-count.
(defn- client-view-facts-from [c0 schema-root]
  (into (reified->facts c0) (vals schema-root)))

(defn client-view-facts [c0]
  (client-view-facts-from c0 @schema-view))

;; Query cache misses project from the immutable Store value captured under dlock,
;; never from the live store atom.  Keep this projection cooperative: unlike
;; c/current-facts, the direct persistent-map walk can poll timeout/disconnect/work
;; control at every historical fact, including superseded facts.  Name resolution is
;; the pure equivalent of schema/name-of over that same Store root.
(defn- store-root-name-of [store-root subj]
  (let [name-pid (get (:val-intern store-root) "name")
        cids (when name-pid (get (:idx-by-lp store-root) [subj name-pid] []))]
    (some (fn [cid]
            (when-not (contains? (:superseded store-root) cid)
              (let [rid (get-in store-root [:facts cid :r])]
                (get (:values store-root) rid))))
          cids)))

(defn- store-root-fact->triple [store-root cl]
  (let [pstr (get (:values store-root) (:p cl))]
    (when-not (or (schema-preds pstr) (resolve-preds pstr) (read-hidden-preds pstr))
      [(store-root-name-of store-root (:l cl)) pstr
       (if (contains? (:values store-root) (:r cl))
         (get (:values store-root) (:r cl))
         (store-root-name-of store-root (:r cl)))])))

(defn- query-client-view-facts [store-root schema-root]
  (let [domain
        (persistent!
         (reduce-kv
          (fn [acc cid cl]
            (query-check)
            (if (contains? (:superseded store-root) cid)
              acc
              (if-let [t (store-root-fact->triple store-root cl)]
                (conj! acc (ck/->Fact (nth t 0) (nth t 1) (nth t 2)))
                acc)))
          (transient []) (:facts store-root)))]
    (reduce (fn [facts cl] (query-check) (conj facts cl)) domain (vals schema-root))))

;; The live (l p r) triples on ONE (te-name, p-str) group, projected exactly as
;; reified->facts would — the authoritative post-commit state of just that group.
;; Empty when te/p don't resolve or p is a schema-pred. Bounded by the group's
;; cardinality (1 for a single-valued pred), so reconciling against it is cheap.
(defn- lp-live-triples [c0 te p]
  (let [st (:store c0) lid (s/resolve-name st te) pid (c/value-id st p)]
    (if (and lid pid (not (schema-preds p)))
      (set (keep #(fact->triple st %) (c/by-lp st lid pid)))
      #{})))

;; ---- index-accelerated read path (warm) ------------------------------------
;; The scan path (fram.query/run) pulls the WHOLE "triple" relation per literal
;; (datalog match-lit). For the common shape — ONE non-recursive rule whose body is
;; "triple" literals with bound predicate+object — we instead probe a by-[p r] index.
;; The index is STRING-KEYED and built from the SAME facts the scan sees, so it is
;; provably a regrouping of the scan's own tuples: NO int<->string translation, hence
;; no silent-mistranslation hazard. q/run stays the untouched ORACLE; anything not of
;; the simple shape (recursion, negation, derived rels, unbound p/r) falls back to it.
;; Positional by-l/by-p/by-r indexes plus by-[l p]/by-[p r] compound indexes let
;; every grounded literal probe its narrowest bucket. by-[l p] also scopes a write
;; delta, so a superseding assert drops its victim without scanning the corpus.
(defn- idx-build [facts]
  (reduce (fn [acc c]
            (query-check)
            (let [t [(:l c) (:p c) (:r c)]]
              (-> acc (update :triples conj t)
                  (update-in [:by-l (:l c)] (fnil conj #{}) t)
                  (update-in [:by-p (:p c)] (fnil conj #{}) t)
                  (update-in [:by-r (:r c)] (fnil conj #{}) t)
                  (update-in [:by-pr [(:p c) (:r c)]] (fnil conj #{}) t)
                  (update-in [:by-lp [(:l c) (:p c)]] (fnil conj #{}) t))))
          {:triples #{} :by-l {} :by-p {} :by-r {} :by-pr {} :by-lp {}} facts))
;; Drop a key whose bucket emptied (DON'T leave it mapped to #{}) — idx-build never
;; emits an empty-set entry, it just omits the key, so the incremental index must do
;; the same or its REPRESENTATION drifts from a fresh fold (warm-check :by-pr-eq
;; false: equal triple-set, dangling empty bucket — queries stay correct since
;; lit-candidates treats #{} and an absent key identically, but the tripwire fires).
(defn- bucket-update [m k v]
  (let [nb (disj (get m k #{}) v)] (if (empty? nb) (dissoc m k) (assoc m k nb))))
;; O(1) delta maintenance per bucket (sets => add/remove + dedup).
(defn- idx-add [idx t]
  (-> idx (update :triples conj t)
      (update-in [:by-l (nth t 0)] (fnil conj #{}) t)
      (update-in [:by-p (nth t 1)] (fnil conj #{}) t)
      (update-in [:by-r (nth t 2)] (fnil conj #{}) t)
      (update-in [:by-pr [(nth t 1) (nth t 2)]] (fnil conj #{}) t)
      (update-in [:by-lp [(nth t 0) (nth t 1)]] (fnil conj #{}) t)))
(defn- idx-del [idx t]
  (-> idx (update :triples disj t)
      (update :by-l (fn [m] (bucket-update m (nth t 0) t)))
      (update :by-p (fn [m] (bucket-update m (nth t 1) t)))
      (update :by-r (fn [m] (bucket-update m (nth t 2) t)))
      (update :by-pr (fn [m] (bucket-update m [(nth t 1) (nth t 2)] t)))
      (update :by-lp (fn [m] (bucket-update m [(nth t 0) (nth t 1)] t)))))

(defn- var-term? [t] (and (map? t) (contains? t :var)))
(declare query-check)
(defn- unify1 [arg val s]
  (if (var-term? arg)
    (let [k (:var arg) b (get s k ::none)]
      (if (= b ::none) (assoc s k val) (if (= b val) s nil)))
    (if (= arg val) s nil)))
(defn- unify-tuple [args tup s]
  (query-check)
  (if (not= (count args) (count tup)) nil
    (loop [a args t tup acc s]
      (cond (nil? acc) nil (empty? a) acc
            :else (recur (rest a) (rest t) (unify1 (first a) (first t) acc))))))
(defn- resolve-arg [arg s] (if (var-term? arg) (get s (:var arg) ::unbound) arg))
;; Choose the narrowest exact bucket made available by the currently-ground
;; positions.  A subject-ground lookup is therefore O(subject facts), not a full
;; corpus scan; joins acquire by-lp/by-pr selectivity as substitutions accumulate.
(defn- lit-candidates [idx litt s]
  (let [args (:args litt)
        l (resolve-arg (nth args 0) s)
        p (resolve-arg (nth args 1) s)
        r (resolve-arg (nth args 2) s)
        bound? #(not= % ::unbound)
        buckets (cond-> []
                  (and (bound? l) (bound? p)) (conj (get (:by-lp idx) [l p] #{}))
                  (and (bound? p) (bound? r)) (conj (get (:by-pr idx) [p r] #{}))
                  (bound? l) (conj (get (:by-l idx) l #{}))
                  (bound? p) (conj (get (:by-p idx) p #{}))
                  (bound? r) (conj (get (:by-r idx) r #{})))]
    (if (seq buckets) (apply min-key count buckets) (:triples idx))))
(defn- conj-query-row [acc row]
  (if (nil? row)
    acc
    (let [max-rows (or (:max-rows d/*query-control*) query-max-rows)]
      (when (>= (count acc) max-rows)
        (throw (ex-info "query evaluation stopped: query-row-limit"
                        {:type :fram-query-abort :code :query-row-limit
                         :rows (count acc) :max-rows max-rows})))
      (conj acc row))))
(defn- query-check []
  (let [control d/*query-control*]
    (when control
      (let [steps (.incrementAndGet (:steps control))
            now (System/nanoTime)
            cancelled @(:cancelled control)
            code (cond
                   cancelled :query-cancelled
                   (> steps (:max-steps control)) :query-work-limit
                   (>= now (:deadline-ns control)) :query-time-limit)]
        (when code
          (throw (ex-info (str "query evaluation stopped: " (name code))
                          {:type :fram-query-abort :code code :reason cancelled
                           :steps steps :max-steps (:max-steps control)
                           :timeout-ms (:timeout-ms control)})))))))
(defn- eval-body-idx [idx body]
  (reduce (fn [substs litt]
            (reduce (fn [acc s]
                      (reduce (fn [a tup] (conj-query-row a (unify-tuple (:args litt) tup s)))
                              acc (lit-candidates idx litt s)))
                    [] substs))
          [{}] body))
(defn- ground-head [args s] (mapv (fn [t] (if (var-term? t) (get s (:var t)) t)) args))
;; the simple shape the index serves; everything else -> q/run (the oracle).
(defn- simple-query? [q]
  (and (map? q) (not (contains? q :strata)) (vector? (:rules q)) (= 1 (count (:rules q)))
       (let [rule (first (:rules q)) body (:body rule)]
         (and (map? rule) (vector? body) (seq body)
              (not= (:rel (:head rule)) "triple")
              (every? (fn [l] (and (map? l) (= "triple" (:rel l)) (not (:neg l))
                                   (vector? (:args l)) (= 3 (count (:args l))))) body)))))
;; index-accelerated run — SAME validation boundary as q/run, SAME head-tuple set.
(defn- idx-run [idx q]
  (let [errs (q/validate q)]
    (if (seq errs) {:error errs}
      (let [rule (first (:rules q))
            substs (eval-body-idx idx (:body rule))
            tuples (reduce (fn [acc s] (conj acc (ground-head (:args (:head rule)) s))) #{} substs)]
        {:ok (vec tuples)}))))

;; warm read cache kept CONSISTENT with the coordinator under writes (the live write
;; path): whole-rebuild on a cold/divergent version, then O(1) incremental delta-apply
;; on each in-lockstep commit — so a write no longer forces an O(corpus) reprojection
;; (the swarm write-ceiling). :facts is a SET of Facts (O(1) add/remove); :idx is the
;; triple-set + positional/compound buckets; :index (kernel, for :validate) is
;; lazy/whole, off the hot path.
(defn warm! []
  (let [co-root @co
        store-root @(:store co-root)
        schema-root @schema-view
        v (current-seq co-root)
        c @cache]
    (when (or (not= v (:version c))
              (not (identical? store-root (:store-root c)))
              (not (identical? schema-root (:schema-root c))))
      (let [facts (client-view-facts-from co-root schema-root)] ; F4: domain + schema facts
        (reset! cache {:facts (set facts) :idx (idx-build facts) :index nil :version v
                       :store-root store-root :schema-root schema-root})))
    @cache))
(defn index! []
  (let [c (warm!)]
    (or (:index c) (let [ix (ck/build-index (vec (:facts c)))] (swap! cache assoc :index ix) ix))))
(defn warm-facts [] (vec (:facts (warm!))))
(defn warm-idx [] (:idx (warm!)))

(def ^:dynamic *request-query-control* nil)

(defn- query-request? [req]
  (and (map? req)
       (or (#{:query :query-page :pull} (:op req))
           (and (= :as-of (:op req)) (:query req)))))

(defn- lower-query-limit [req key ceiling]
  (let [n (get req key)]
    (if (and (integer? n) (pos? n)) (min n ceiling) ceiling)))

(defn- new-query-control [req]
  (let [timeout (lower-query-limit req :query-timeout-ms query-timeout-ms)]
    {:cancelled (atom nil)
     :done (atom false)
     :steps (java.util.concurrent.atomic.AtomicLong. 0)
     :timeout-ms timeout
     :deadline-ns (+ (System/nanoTime) (* 1000000 timeout))
     :max-steps (lower-query-limit req :query-max-steps query-max-steps)
     :max-rows (lower-query-limit req :query-max-rows query-max-rows)
     :max-response-bytes (lower-query-limit req :query-max-response-bytes
                                            query-max-response-bytes)}))

(defn- cancel-query! [control reason]
  (compare-and-set! (:cancelled control) nil reason))

;; Capture ONLY immutable roots while reload/write exclusion is held.  A hot cache
;; is already immutable and can be reused directly.  A miss is merely described
;; here; projection/index construction happens cooperatively outside dlock.
(defn- capture-query-roots! []
  (let [co-root @co
        store-root @(:store co-root)
        schema-root @schema-view
        version (current-seq co-root)
        c @cache
        current-cache? (and (= version (:version c))
                            (identical? store-root (:store-root c))
                            (identical? schema-root (:schema-root c))
                            (:facts c) (:idx c))]
    {:store-root store-root :schema-root schema-root :version version
     :cache (when current-cache? c)}))

(defn- publish-query-cache! [roots built]
  ;; Publishing is an optimization, never the query's consistency boundary.  Recheck
  ;; all captured identities under dlock; if a writer/reload moved any root while the
  ;; miss was being built, this query still uses its private snapshot and simply skips
  ;; publication.
  (locking dlock
    (let [co-root @co]
      (when (and (= (:version roots) (current-seq co-root))
                 (identical? (:store-root roots) @(:store co-root))
                 (identical? (:schema-root roots) @schema-view))
        (reset! cache built)
        true))))

(defn- materialize-query-snapshot [roots]
  (let [history-store (atom (:store-root roots))
        c (or (:cache roots)
              (let [facts (query-client-view-facts (:store-root roots) (:schema-root roots))
                    built {:facts (set facts) :idx (idx-build facts) :index nil
                           :version (:version roots)
                           :store-root (:store-root roots)
                           :schema-root (:schema-root roots)}]
                (publish-query-cache! roots built)
                built))]
    {:facts (:facts c) :idx (:idx c) :version (:version roots)
     :history-co {:store history-store}
     :history-store history-store}))

;; The shared Datalog projection (EDB + base index) for one immutable facts set.
;; A hit is lock-free (identity check against the last-published set). A miss
;; single-flights under `projection-build-lock` with a double-check, so exactly one
;; full-corpus projection is built per cold version and all concurrent scan/page
;; readers at that version share the one immutable value — bounding peak build
;; memory to a single corpus regardless of query concurrency. The build runs under
;; the caller's *query-control* binding, so a cold build is itself deadline/step
;; bounded (it aborts like any query rather than running unbounded); a partial build
;; is never published because `reset!` happens only after `q/project` returns.
(defn- snapshot-projection [facts-set]
  (let [pc @projection-cache]
    (if (and pc (identical? (:facts pc) facts-set))
      (:projection pc)
      (locking projection-build-lock
        (let [pc2 @projection-cache]
          (if (and pc2 (identical? (:facts pc2) facts-set))
            (:projection pc2)
            (let [proj (q/project (vec facts-set))]
              (reset! projection-cache {:facts facts-set :projection proj})
              proj)))))))

(defn- query-abort-response [t version engine]
  (let [data (ex-data t)
        code (:code data)]
    (swap! query-stops update code (fnil inc 0))
    (merge {:error [(or (.getMessage t) (name code))]
            :code code :version version :engine engine}
           (select-keys data [:reason :steps :max-steps :timeout-ms :rows :max-rows]))))

(defn- enforce-result-row-limit [res control]
  (let [rows (:ok res) max-rows (:max-rows control)]
    (if (and (vector? rows) (> (count rows) max-rows))
      (throw (ex-info "query result exceeded row limit"
                      {:type :fram-query-abort :code :query-row-limit
                       :rows (count rows) :max-rows max-rows}))
      res)))

(defn- execute-query [req roots]
  (let [control (or *request-query-control* (new-query-control req))
        use-idx (and (= :query (:op req))
                     (not (:scan req))
                     ;; SEAM (aggregates lane): a map-shaped :find is an AGGREGATE query
                     ;; (landing concurrently in fram.query); it must NEVER route to
                     ;; idx-run — only a string :find (a plain head-rel name) may.
                     (string? (:find (:query req)))
                     (simple-query? (:query req)))
        engine (if use-idx "index" "scan")
        version (:version roots)]
    (swap! active-queries inc)
    (try
      (binding [d/*query-control* control
                q/*query-control* control]
        ;; Cancellation is a protocol decision, not merely an evaluator-loop
        ;; check. Poll before validation so a fast malformed request cannot return
        ;; a normal validation envelope after its connection already pipelined
        ;; forbidden extra input.
        (query-check)
        (let [snapshot (materialize-query-snapshot roots)
              _ (query-check)
              res (case (:op req)
                    :query (if use-idx
                             (idx-run (:idx snapshot) (:query req))
                             (q/run-projected (snapshot-projection (:facts snapshot)) (:query req)))
                    :query-page (q/run-page-projected (snapshot-projection (:facts snapshot))
                                                      (:query req) (:limit req) (:after req))
                    :pull (let [errs (pull/validate (:root req) (:pattern req) req)]
                            (if (seq errs)
                              {:error errs}
                              (pull/run (:history-store snapshot) (:root req) (:pattern req) req)))
                    :as-of (let [s (:seq req)]
                             (if (nil? s)
                               {:error [":as-of needs :seq"]}
                               (let [co0 (:history-co snapshot)
                                     st (:history-store snapshot)
                                     cids (live-as-of co0 s)
                                     facts (vec (keep (fn [cid]
                                                        (query-check)
                                                        (when-let [t (fact->triple st cid)]
                                                          (ck/->Fact (nth t 0) (nth t 1) (nth t 2))))
                                                      cids))]
                                 (assoc (q/run facts (:query req)) :as-of s)))))
              bounded (enforce-result-row-limit res control)
              stamped (assoc bounded :version version :engine engine)
              utf8-size (fn [s] (count (.getBytes ^String s "UTF-8")))
              edn-bytes (utf8-size (pr-str stamped))
              json-bytes (utf8-size (fram.rt/to-json stamped))
              max-bytes (if (= :query-page (:op req))
                          q/max-page-wire-bytes
                          (:max-response-bytes control))]
          (if (and (<= edn-bytes max-bytes)
                   (<= json-bytes max-bytes))
            stamped
            (if (= :query-page (:op req))
              {:error ["query page response exceeded its final wire bound"]
               :code :query-page-response-too-large
               :max-bytes max-bytes
               :version version :engine engine}
              {:error ["query response exceeded its final wire bound"]
               :code :query-response-too-large
               :max-bytes max-bytes
               :edn-bytes edn-bytes :json-bytes json-bytes
               :version version :engine engine}))))
      (catch clojure.lang.ExceptionInfo t
        (if (= :fram-query-abort (:type (ex-data t)))
          (query-abort-response t version engine)
          (throw t)))
      (finally
        (reset! (:done control) true)
        (swap! active-queries dec)))))
;; apply a just-committed (te p) edit to the warm cache IFF the cache was current as of
;; the pre-commit seq; else invalidate so the next warm! whole-rebuilds (correctness
;; floor — incremental only when provably in lockstep). We reconcile the whole (te,p)
;; GROUP against the store's authoritative post-commit live set rather than applying
;; the wire tuple alone: a single-valued ASSERT also SUPERSEDES the prior value, so
;; applying only the new tuple left the superseded victim live in the cache (warm !=
;; cold — a genuine cache bug the gate's supersede step caught). Group-reconcile drops
;; the victim AND adds the new tuple in one correct step, op-agnostically (assert /
;; retract / supersede / ref all flow through it). Cost is O(group cardinality) — the
;; cache's by-[l p] gives the old group, the store gives the new — not O(corpus).
(defn apply-commit-delta! [pre te p]
  (let [post (current-seq @co)]
    (when (> post pre)
      (swap! cache
        (fn [c]
          (if (= (:version c) pre)
            (let [old (get-in c [:idx :by-lp] {})
                  old-g (get old [te p] #{})                ; cache's tuples on (te,p)
                  new-g (lp-live-triples @co te p)          ; store's live tuples on (te,p)
                  to-del (clojure.set/difference old-g new-g)
                  to-add (clojure.set/difference new-g old-g)
                  idx' (as-> (:idx c) ix
                         (reduce idx-del ix to-del)
                         (reduce idx-add ix to-add))
                  facts' (as-> (:facts c) cs
                            (reduce (fn [s t] (disj s (ck/->Fact (nth t 0) (nth t 1) (nth t 2)))) cs to-del)
                            (reduce (fn [s t] (conj s (ck/->Fact (nth t 0) (nth t 1) (nth t 2)))) cs to-add))]
              {:facts facts' :idx idx' :index nil :version post
               :store-root @(:store @co) :schema-root (:schema-root c)})
            (assoc c :version -1)))))))

;; Subscriber delivery is BEST-EFFORT and OFF the write path (finding #3): a slow
;; or stuck subscriber (TCP send buffer full) must NOT stall commits, which run
;; under dlock. We hand the event to a single-threaded executor so the committing
;; thread returns immediately; delivery happens later and a wedged subscriber only
;; backs up the (unbounded, but commit-independent) notify queue, never dlock.
;; A subscriber whose .write/.flush throws (or whose socket SO_TIMEOUT trips) is
;; dropped. Single thread = events stay ordered.
(def ^:private notify-exec
  (java.util.concurrent.Executors/newSingleThreadExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r] (doto (Thread. r "store-notify") (.setDaemon true))))))

;; P5 — scoped subscribe. A subscriber MAY register a filter so the daemon pushes only
;; relevant commits instead of the firehose (efficiency at scale). Backward-compatible:
;; flt=nil => firehose (every commit), identical to pre-P5. A filter is
;; {:addrs #{..} :watch #{..} :node "@agent:<uuid>"}; an event passes if it's a message
;; to one of my addrs, a commit on a thread I watch, or a change to my own node (so the
;; client re-scopes live on role/watch updates).
(defn- sub-match? [flt {:keys [l p r]}]
  (or (nil? flt)
      ;; a MESSAGE (send -> p"to") OR a peer-COMMAND envelope (send-cmd -> p"target")
      ;; addressed to one of my addrs. "target" lands last in the envelope, so matching
      ;; it is the trigger; the reactor then reads the full @cmd:<id>. (v1 debt 019f1184-e244)
      (and (#{"to" "target"} p) (contains? (:addrs flt) r))
      (contains? (:watch flt) l)
      (= l (:node flt))))

(defn- notify-subs! [event]
  (let [line (str (pr-str event) "\n")]
    (.execute notify-exec
      (fn []
        (reset! subscribers
                (vec (filter (fn [{:keys [^BufferedWriter w flt]}]
                               (if (sub-match? flt event)
                                 (try (.write w line) (.flush w) true   ; push; drop on disconnect
                                      (catch Throwable _ false))
                                 true))                                 ; no match -> keep subscriber, don't push
                             @subscribers)))))))

;; ref-shape? — is a STRING value a node reference (a link), vs a literal that
;; merely starts with '@'? The convention is "@-prefixed => ref", but a bare "@"
;; or "@ " is a legitimate LITERAL (e.g. a comment lexeme discussing the `@id`
;; syntax — tools.bclj/import.bclj/main.bclj all carry one). A real reference —
;; a thread id (@2026-06-15-150040) or a code node-name (@mod#123) — is "@" + at
;; least one char AND contains NO whitespace (ids/node-names are whitespace-free by
;; construction). So: starts with '@', length > 1, no whitespace. This closes a
;; render-from-log fidelity hole where "@" was mis-stored as a link to a phantom
;; node, and the renderer then got an entity-id where it expected the string "@".
(defn- ref-shape? [^String s]
  (and (> (.length s) 1)
       (= \@ (.charAt s 0))
       (not (re-find #"\s" s))))

;; VALUE-PREDS — predicates whose object is ALWAYS a literal leaf value in the CODE
;; graph, NEVER a node link, even when the value is a whitespace-free @-string. A Clojure
;; DEREF `@atom` (malli error.cljc's `@!likely-misspelling-of`, core.cljc's `@cache`, …) is
;; a symbol SPELLING stored under "v"; without this guard ref-shape? links it to a phantom
;; node named "@atom", and warm-render then emits that entity-id where racket expects a
;; string ("string->symbol: contract violation, given: <node-id>"). Structural references
;; (fN/child/span/col/line/pos/refers_to/…) carry real @<module>#<int> node-names under
;; OTHER predicates, so link detection there is unaffected. (Comment lexemes are also "v"
;; segs — a comment mentioning `@id` is likewise a literal, not a link.)
(def ^:private VALUE-PREDS #{"v"})
;; kind from the value: a ref-shaped @-string under a NON-value predicate => ref (link),
;; else literal (assert) — exactly the convention the migration loader uses, so daemon
;; writes stay consistent with the migrated store.
(defn- link-value? [p r] (and (string? r) (not (VALUE-PREDS p)) (ref-shape? r)))
(defn- kind-of [p r] (if (link-value? p r) :link :assert))

;; forward ref: the-model §9 cascade is defined after do-retract (it calls both
;; do-retract + do-assert), but do-assert calls it — declare so SCI resolves it.
(declare terminal-cascade!)

;; --- F3: validated schema-write gate ----------------------------------------
;; cardinality + value_kind are META-predicates ON a predicate-name (subject "@<pred>").
;; They are NOT domain data, but unlike name/store-supersedes they are USER-DECLARABLE: a
;; `tell @<pred> cardinality single` is the authoring surface for schema-as-facts. A
;; schema-writable predicate routes through here: validate the subject shape + value,
;; apply the multi->single DATA-LOSS guard, then write through the schema layer (s/assert!
;; on the STRIPPED pred-name — never a raw @<pred> entity node) and append the fact
;; VERBATIM to the flat log so the cold CLI fold's card-map sees it (daemon⇄CLI parity).
(defn- pred-name [s] (if (and (string? s) (str/starts-with? s "@")) (subs s 1) s))
(def ^:private schema-allowed {"cardinality" #{"single" "multi"} "value_kind" #{"ref" "literal"}})
(def ^:private schema-allowed-msg {"cardinality" "single|multi" "value_kind" "ref|literal"})

;; live (subject,pred) groups on `pname` holding >1 live value — the groups a
;; cardinality multi->single flip would silently collapse to latest-tx (the schema-as-
;; facts data-loss vector). O(pname's own facts) via the by-p index, NOT O(store).
;; Returns {:count n :subjects [<=3 subject-names]} for a pointed reject.
(defn- multivalued-groups [st pname]
  (let [pid (c/value-id st pname)]
    (if (nil? pid)
      {:count 0 :subjects []}
      (let [offending (->> (c/by-p st pid)
                           (keep #(c/fact-of st %))
                           (group-by :l)
                           (filterv (fn [g] (> (count (second g)) 1))))]
        {:count (count offending)
         :subjects (mapv (fn [g] (or (s/name-of st (first g)) (str (first g)))) (take 3 offending))}))))

;; write one schema meta-fact into the store + flat log in ONE coordinator tx. ASSERT:
;; s/assert! on the pred-name's value-object supersedes the prior declaration (cardinality
;; + value_kind are single-valued per setup!); only cardinality is written, so a value_kind
;; declared earlier is preserved (invariant). RETRACT: fall back to the env/fallback
;; classification — a kernel-single pred re-seeds "single" (so the store still matches the
;; cold CLI fold, which falls back via ck/single?), else the declaration is cleared
;; (default multi). The flat line records the user's op VERBATIM so the CLI fold agrees.
(defn- schema-store-write! [op te pname p r]
  (locking (:lock @co)
    (let [st (:store @co)
          tx (c/begin-tx! st "schema")
          pid (c/value! st pname)
          clear! (fn [pp] (doseq [old (c/by-lp st pid (c/value! st pp))]
                            (c/fact! st old (c/value! st "store-supersedes") old tx)))]
      (cond
        (= op "assert")     (s/assert! st pid p r tx)
        (= p "cardinality") (if (ck/single? pname)
                              (s/assert! st pid "cardinality" "single" tx)   ; fallback single: re-seed
                              (clear! "cardinality"))                        ; fallback multi: clear -> default
        :else               (clear! "value_kind"))
      (let [seq (get-in @st [:txs tx :seq])]
        (append-flat! op te p r seq)
        ;; mirror the log fact into the READ view directly from the op (append-flat! is
        ;; async, so re-reading the file here would race): assert installs the fact,
        ;; retract drops it — exactly how the cold fold's keyed-latest treats the line.
        (if (= op "assert")
          (swap! schema-view assoc [te p] (ck/->Fact te p r))
          (swap! schema-view dissoc [te p]))
        ;; force the warm cache to rebuild (schema writes skip apply-commit-delta!): the
        ;; version bump alone leaves a race window where a rebuild could tag the new
        ;; version with the pre-swap schema-view. Invalidate so the next warm! re-projects.
        (swap! cache assoc :version -1)
        (reset! facts-wire-cache {:version -1 :triples nil})
        (notify-subs! {:event :commit :version seq :op op :l te :p p :r r})
        {:ok seq}))))

(defn- do-schema-assert [te p r]
  (let [st (:store @co)]
    (cond
      (not (ref-shape? te))
      {:reject [(str "schema predicate '" p "' requires an @-prefixed predicate-name subject (got '" te "')")] :version (current-seq @co)}
      (not (contains? (schema-allowed p) r))
      {:reject [(str "invalid " p " value '" r "' — allowed: " (schema-allowed-msg p))] :version (current-seq @co)}
      :else
      (let [pname (pred-name te)]
        (if (and (= p "cardinality") (= r "single") (not= "single" (s/cardinality st pname)))
          (let [{:keys [count subjects]} (multivalued-groups st pname)]
            (if (pos? count)
              {:reject [(str "cardinality multi->single on '" pname "' would collapse " count
                             " live multi-valued group(s) to latest-tx (data loss); first: "
                             (str/join ", " subjects) " — retract extra values first")]
               :version (current-seq @co)}
              (schema-store-write! "assert" te pname p r)))
          (schema-store-write! "assert" te pname p r))))))

(defn- do-schema-retract [te p r]
  (let [st (:store @co)]
    (if (not (ref-shape? te))
      {:reject [(str "schema predicate '" p "' requires an @-prefixed predicate-name subject (got '" te "')")] :version (current-seq @co)}
      (let [pname (pred-name te)]
        (if (and (= p "cardinality")
                 (= "multi" (s/cardinality st pname))   ; currently declared multi
                 (ck/single? pname))                    ; fallback would flip to single
          (let [{:keys [count subjects]} (multivalued-groups st pname)]
            (if (pos? count)
              {:reject [(str "retracting 'cardinality' on '" pname "' falls back to single but " count
                             " live multi-valued group(s) would collapse to latest-tx (data loss); first: "
                             (str/join ", " subjects) " — re-declare multi or retract extra values first")]
               :version (current-seq @co)}
              (schema-store-write! "retract" te pname p r)))
          (schema-store-write! "retract" te pname p r))))))

;; reserved engine predicates (identity + metadata) — a DOMAIN write to name/store-supersedes
;; would collide with the reified schema layer and silently corrupt; reject at the boundary.
;; cardinality/value_kind are validated schema writes (do-schema-assert/retract, F3).
(defn- do-assert [te p r base]
  (assert-flat-corpus-append-boundaries!)
  (cond
    (hard-reserved p)
    {:reject [(str "reserved predicate '" p "' (engine-internal; use a domain predicate)")] :version (current-seq @co)}
    (schema-writable p)
    (do-schema-assert te p r)
    :else
    (let [pre (current-seq @co)
          res (commit! @co "coord" te p (kind-of p r) r base)]
      (if (:ok res)
        (do (when-not (:idempotent res)
              (append-flat! "assert" te p r (:ok res))
              (apply-commit-delta! pre te p)
              ;; #(a) a read-hidden durable pred (bound_to/withdrawn_*) is NOT AST and never
              ;; changes resolution, so it must NOT invalidate the scoped refers_to cache —
              ;; else persisting bound_to would re-dirty its module and force a wasted re-resolve.
              (when-not (read-hidden-preds p) (mark-dirty! te)))   ; S3.3: this module's refers_to are stale
            (notify-subs! {:event :commit :version (:ok res) :op "assert" :l te :p p :r r})
            ;; the-model §9: a successful, non-idempotent terminal transition
            ;; (outcome|abandoned) cascades in the SAME serialized turn — retract
            ;; any live driver and close any running clock session. Routes through
            ;; do-retract/do-assert so each cascade write inherits append-flat! +
            ;; notify-subs! + OCC. Idempotent on replay (a 2nd terminal assert is
            ;; :idempotent => skipped; a driver-free, clock-free thread no-ops).
            (when (and (not (:idempotent res)) (ck/vec-contains? ck/terminal-preds p))
              (terminal-cascade! te))
            {:ok (:ok res)})
        {:reject (:reject res) :version (:version res)}))))

(defn- do-retract [te p r base]
  (assert-flat-corpus-append-boundaries!)
  (cond
    (hard-reserved p)
    {:reject [(str "reserved predicate '" p "'")] :version (current-seq @co)}
    (schema-writable p)
    (do-schema-retract te p r)
    :else
    (let [pre (current-seq @co)
          res (retract! @co "coord" te p r base)]
      (if (:ok res)
        (do (append-flat! "retract" te p r (:ok res))
            (apply-commit-delta! pre te p)
            (when-not (read-hidden-preds p) (mark-dirty! te))   ; S3.3: this module's refers_to are stale (read-hidden preds are not AST)
            (notify-subs! {:event :commit :version (:ok res) :op "retract" :l te :p p :r r})
            {:ok (:ok res)})
        {:reject (:reject res) :version (:version res)}))))

(defn- with-current-fence
  "Run one fact mutation only while RES is held by HOLDER at EPOCH. The lease
  check and ACTION execute under the coordinator store's one writer lock, so an
  expiry/takeover cannot land between validation and mutation. ACTION may
  re-enter the same JVM monitor through do-assert/do-retract."
  [res holder epoch action]
  (with-fence! @co res holder epoch action))

(defn- assert-at-version [te p r base]
  (let [head (current-seq @co)]
    (cond
      (not (and (integer? base) (not (neg? base))))
      {:reject :invalid-base :version head}

      (not= base head)
      {:reject :conflict :version head}

      :else
      (do-assert te p r base))))

;; the-model §9 — atomic terminal-transition cascade. Called by do-assert AFTER a
;; successful, non-idempotent terminal assert (outcome|abandoned). Runs INSIDE the
;; same serialized coordinator turn (do-assert holds no extra lock here; the
;; cascade writes re-acquire the reentrant (:lock co) via do-retract/do-assert):
;;   (1) DRIVER — a thread that has resolved is no longer being driven; retract any
;;       live driver cell on te.
;;   (2) CLOCK — any running session ON te (session_of -> te, with a live start_time
;;       and NO live end_time) is closed by asserting end_time now.
;; Both route through the EXISTING do-retract/do-assert so they inherit append-flat!
;; + notify-subs! + OCC. Idempotent on replay: a 2nd terminal assert is :idempotent
;; (cascade skipped), and a driver-free / clock-free thread no-ops here.
;; a single live LITERAL value on (sid-name, p), or nil — the post-commit state of
;; one single-valued group, read straight off the reified store (no fold).
(defn- one-live-literal [sid p]
  (let [st  (:store @co)
        lid (s/resolve-name st sid)
        pid (c/value-id st p)]
    (when (and lid pid)
      (let [cids (live-cids-lp @co lid pid)]
        (when (seq cids)
          (let [cl (c/fact-of st (elect @co cids))]    ; coexist-elect: the elected member
            (when cl (c/literal st (:r cl)))))))))

(defn- terminal-cascade! [te]
  (let [st (:store @co)]
    ;; (1) driver — a resolved thread is no longer being driven. Read the live
    ;; driver value and pass it as `r`: retract! clears the whole single-valued
    ;; group regardless of r, but a flat-log retract line MUST carry a non-nil r
    ;; (fold drops :r nil as a torn line — see fram.fold), or the clear would not
    ;; survive a cold re-fold.
    (let [eid (s/resolve-name st te)
          did (c/value-id st "driver")
          live-driver (let [cids (when (and eid did) (live-cids-lp @co eid did))]
                        (when (seq cids)
                          (let [cl (c/fact-of st (elect @co cids))]   ; coexist-elect
                            (when cl
                              (if (c/value-object? st (:r cl))
                                (c/literal st (:r cl))
                                (s/name-of st (:r cl)))))))]
      (when (some? live-driver)
        (do-retract te "driver" live-driver (current-seq @co))))
    ;; (2) running clock sessions on te (session_of -> te, live start_time, no end_time).
    (let [te-eid (s/resolve-name st te)
          sof    (c/value-id st "session_of")]
      (when (and te-eid sof)
        (doseq [scid (vec (c/by-pr st sof te-eid))]
          (let [cl (c/fact-of st scid)
                sid (when cl (s/name-of st (:l cl)))]
            (when (and sid
                       (some? (one-live-literal sid "start_time"))
                       (nil? (one-live-literal sid "end_time")))
              (do-assert sid "end_time" (fram.rt/now-iso) (current-seq @co)))))))))

;; §1.2: ready/blocked/leverage are DOMAIN projections — the engine no longer
;; serves them. The CLI/MCP fold the log locally (main/cmd-ready, cmd-json), so
;; these daemon ops were vestigial wire-protocol surface. Dropped along with the
;; fram.projections require → the daemon depends on no domain code. (:validate
;; stays: it's kernel-level structural integrity, not lifecycle.)
(defn- all-violations [idx]
  (->> (ck/thread-ids-i idx)
       (mapcat (fn [te] (map #(str (subs te 1) ": " %) (ck/violations-i idx te))))
       vec))

;; ============================================================================
;; :callers — warm scope-correct callers of a binding, from refers_to over `co`.
;; ============================================================================
;; clean slate: surgically drop EVERY live resolve-pred fact (refers_to + render
;; markers) from the store STORE's facts + all five indexes (idx-by-l/p/r/lp/pr) + the
;; superseded set. (Independent of the S1-fix cache's :by-lp index — this is the store,
;; not the warm cache.) The resolver assumes a clean slate, else a re-resolve over an
;; existing edge set doubles the refers_to edges. These facts are derived/in-memory
;; only, so dropping them from the map (rather than appending a supersede) is exactly
;; right — nothing durable references them, and they were never written to the flat log.
;; subj-keep? : an optional predicate on the SUBJECT node-id (:l of the resolve-pred
;; fact). nil => strip every resolve-pred fact (whole-corpus, the S3.2 behavior).
;; A set/fn => strip only those whose subject is in scope (S3.3 scoped strip: clear
;; just the affected modules' derived edges before re-walking them). Correctness:
;; a resolve-pred fact's subject IS the referencing/binding leaf, and every leaf
;; belongs to exactly one module (its @<mod># name), so scoping by subject scopes by
;; module exactly — the untouched modules' edges are left intact.
(defn- strip-resolve-facts!
  ([st] (strip-resolve-facts! st nil))
  ([st subj-keep?]
   (let [m @st
         rp-ids (set (keep (fn [[vid v]] (when (resolve-preds v) vid)) (:values m)))
         victims (set (keep (fn [[cid cl]]
                              (when (and (rp-ids (:p cl))
                                         (or (nil? subj-keep?) (subj-keep? (:l cl))))
                                cid))
                            (:facts m)))]
     (when (seq victims)
       (let [drop-from (fn [idx] (reduce-kv (fn [acc k cids]
                                              (let [kept (vec (remove victims cids))]
                                                (if (seq kept) (assoc acc k kept) acc)))
                                            {} idx))]
         (swap! st (fn [s]
                     (-> s
                         (update :facts #(reduce dissoc % victims))
                         (update :tx-of #(reduce dissoc % victims))
                         (update :objects #(reduce dissoc % victims))
                         (update :superseded #(reduce dissoc % victims))
                         (update :idx-by-l drop-from)
                         (update :idx-by-p drop-from)
                         (update :idx-by-r drop-from)
                         (update :idx-by-lp drop-from)
                         (update :idx-by-pr drop-from))))))
     (count victims))))

;; node-ids whose @<mod># name-prefix is in `mods` — the subject scope for a scoped
;; strip AND the membership test the resolver's module-set walk mirrors. Computed
;; straight off the store's `name` facts (the same source corpus-from-store! groups by).
(defn- module-node-ids [st mods]
  (let [NAME (c/value-id st "name")]
    (when NAME
      (set (keep (fn [cid]
                   (let [cl (c/fact-of st cid)
                         nm (c/literal st (:r cl))]
                     (when (contains? mods (module-of-name nm)) (:l cl))))
                 (c/by-p st NAME))))))

;; restore-seq-space! — the resolver opens a tx (begin-tx! bumps :next-seq + records a
;; :txs entry); that would bump current-seq, make refers-version chase a moving target,
;; AND poison the S1 :query cache key. So we SNAPSHOT the seq-space + supersedes-pred
;; before, and restore them after: the freshly-minted refers_to facts/values/ids are
;; KEPT (next-id stays advanced so a later real commit mints past them), but :txs /
;; :next-seq are rolled back (current-seq unchanged) and :supersedes-pred is restored to
;; the migrate store's store-supersedes (the resolver re-points it at "supersedes"; daemon
;; writes need store-supersedes).
(defn- restore-seq-space! [st before]
  (swap! st assoc
         :next-seq        (:next-seq before)
         :supersedes-pred (:supersedes-pred before)
         :txs             (:txs before)))

;; Materialize refers_to WHOLE-CORPUS over the warm store: clear ALL stale refers_to,
;; re-walk every module, restore the seq-space. The cold/first-cut path and the
;; macro-touch fallback (a defmacro edit's blast radius isn't bounded by imports).
;; The result is GROUND TRUTH — set-equal to the EDN path (S3.2). Returns the module
;; set walked (every module) for instrumentation.
(defn materialize-refers-whole! []
  (let [st (:store @co)
        before @st
        stripped (strip-resolve-facts! st)
        walk-info (atom nil)]
    (resolve/resolve-warm-store! st             ; side-effect: writes refers_to for EVERY module
      (fn [] (reset! walk-info {:forms-walked @resolve/n-forms-walked
                                :modules-walked @resolve/walked-modules})))
    (restore-seq-space! st before)
    {:stripped stripped
     :forms-walked (:forms-walked @walk-info)
     :modules-walked (:modules-walked @walk-info)}))

;; READ-ONLY binding of resolve.clj's accessors over a store: bind ctx + the marker
;; value-ids (recomputed against THIS store — store-local ids must match their store)
;; and the corpus tables, WITHOUT running run-resolution! (which would write more
;; refers_to and double the edges). refers_to is already materialized over `co`; this
;; just lets ultimate/binding-name/pred-val/name->module read it. corpus-from-store!
;; (re)derives the def-binding tables from the store so def-binding works for the
;; (module name) target lookup — it writes NO facts, only sets dynamic tables.
(defmacro with-resolve-read [store & body]
  `(let [st# ~store]
     (binding [resolve/ctx st#
               resolve/Vp     (c/value-id st# "v")
               resolve/KIND   (c/value-id st# "kind")
               resolve/REFERS (c/value-id st# "refers_to")
               resolve/FIXED  (c/value-id st# "keep_spelling")
               resolve/QUAL   (c/value-id st# "qualifier")
               resolve/CTOR   (c/value-id st# "ctor_prefix")
               resolve/ACC    (c/value-id st# "accessor_field")
               resolve/file->ents (atom {})
               resolve/srcs [] resolve/file-modframe {} resolve/file-typeframe {}
               resolve/file-accessors {} resolve/global-exports {}
               resolve/global-type-exports {} resolve/global-accessor-exports {}]
       (resolve/corpus-from-store!)            ; derive def-binding tables (writes no facts)
       ~@body)))

;; ============================================================================
;; S3.3 — the classifier + SCOPED materialize.
;; ============================================================================
;; classify-affected: given the set of dirty modules, return
;;   {:affected #{modules to re-walk}  :macro? bool  :export-changed #{mods}}
;; The rule (binding-SET delta, NOT syntactic site): for each dirty module M, diff M's
;; live export-set against the snapshot taken at the last materialize. If M's export-set
;; is UNCHANGED, only M re-resolves (an internal body edit can't change how a consumer
;; binds M's name — and re-walking ALL of M covers any internal frame-move/shadowing).
;; If it CHANGED (or M is new / deleted), M PLUS its consumers (modules importing M)
;; re-resolve, because a consumer's :refer/:as of M now binds a different surface.
;; Reads the corpus tables (call under with-resolve-read).
(defn- classify-affected [dirty snapshot]
  (let [ig          (resolve/import-graph)             ; {module -> #{imports}}
        consumers   (fn [m] (->> ig (keep (fn [[s imps]] (when (contains? imps m) s))) set))
        export-now  (fn [m] (resolve/module-export-set m))
        macro?      (boolean (some resolve/module-has-macro? dirty))   ; whole-corpus fallback
        changed     (set (filter (fn [m]
                                   (not= (export-now m)
                                         (get snapshot m ::absent)))   ; new module => ::absent != set
                                 dirty))
        affected    (reduce (fn [acc m] (into acc (consumers m))) (set dirty) changed)]
    {:affected affected :macro? macro? :export-changed changed}))

;; snapshot EVERY module's current export-set — taken right after a materialize so the
;; next edit's classifier diffs against a coherent baseline. (Whole-corpus snapshot is
;; cheap: it's table reads, no walk.)
(defn- snapshot-exports! [st]
  (reset! export-snapshot
          (with-resolve-read st
            (into {} (map (fn [m] [m (resolve/module-export-set m)])
                          (filter some? resolve/srcs))))))

;; materialize-refers-scoped! — re-resolve ONLY the affected module set:
;;   1. classify dirty -> affected (∪ export-changed consumers); macro-touch => whole.
;;   2. strip refers_to for ONLY the affected modules' node subjects.
;;   3. resolve/resolve-modules! walks ONLY those modules (full tables, partial walk).
;;   4. restore the seq-space; re-snapshot export-sets; clear dirty.
;; The untouched modules' refers_to are left exactly as the previous materialize wrote
;; them — sound because nothing they bind changed. Returns instrumentation.
(defn materialize-refers-scoped! []
  (let [st       (:store @co)
        before   @st
        dirty    @dirty-modules
        {:keys [affected macro? export-changed]}
        (with-resolve-read st (classify-affected dirty @export-snapshot))]
    (if macro?
      ;; defmacro touched — blast radius unbounded by imports; whole-corpus (sound).
      (let [{:keys [stripped]} (materialize-refers-whole!)]
        (snapshot-exports! st)
        (reset! dirty-modules #{})
        {:mode :whole-macro-fallback :walked :all :stripped stripped :export-changed export-changed})
      (let [keep-ids (module-node-ids st affected)
            stripped (strip-resolve-facts! st (or keep-ids #{}))
            walk-info (atom nil)]
        (resolve/resolve-modules! st affected
          (fn [] (reset! walk-info {:forms-walked @resolve/n-forms-walked
                                    :modules-walked @resolve/walked-modules})))
        (restore-seq-space! st before)
        (snapshot-exports! st)
        (reset! dirty-modules #{})
        {:mode :scoped :walked affected :stripped stripped :export-changed export-changed
         :forms-walked (:forms-walked @walk-info)
         :modules-walked (:modules-walked @walk-info)}))))

;; ensure-refers! — keep refers_to current for the next :callers read. COLD (never
;; materialized, or an external flat reload reset us): whole-corpus + snapshot. WARM
;; (some module dirty since last materialize): scoped re-resolve of just the affected
;; set. CLEAN (no dirty modules): nothing to do. refers-version still tracks the seq
;; the materialized edges reflect, for the gate's reasoning + status. The version is
;; NOT the trigger any more (scoped tracks dirty modules, which is finer than seq).
;; persist-bound! (defined below, near do-edit-min*) is the #(a) identity migration: it
;; do-asserts a durable bound_to edge for every reference the current refers_to resolved.
;; ensure-refers! is the ingest / edit-reconciliation seam, so the migration rides its two
;; materialize branches (cold whole = ingest; scoped = post-edit reconcile). Persisting under
;; dlock (reentrant — rename already holds it) keeps the appends serialized; bound_to is NOT
;; AST, so it never re-dirties a module (do-assert guard) and never loops the maintenance step.
(declare persist-bound!)
(defn ensure-refers! []
  (cond
    (not @materialized?)
    (let [r (materialize-refers-whole!)]
      (snapshot-exports! (:store @co))
      (reset! dirty-modules #{})
      (reset! materialized? true)
      (reset! refers-version (current-seq @co))
      (reset! last-materialize (assoc r :mode :whole-cold :walked :all))
      (locking dlock (persist-bound!)))              ; #(a) code ingest — every ref gets a durable identity edge
    (seq @dirty-modules)
    (let [r (materialize-refers-scoped!)]
      (reset! refers-version (current-seq @co))
      (reset! last-materialize r)
      (locking dlock (persist-bound!)))              ; #(a) edit reconciliation — freshly minted refs get theirs
    :else nil))

;; ============================================================================
;; :blast / :concern-overlap — the WARM scope-correct call graph (thread 019f1010-2705).
;; ============================================================================
;; calls_defn is a defn->defn edge derived by lifting the materialized refers_to up to
;; the ENCLOSING defn (resolve/call-edges) — scope-correct (same-named fns in different
;; modules are distinct @mod#int nodes, never merged) and rename-stable (keyed on node
;; identity, not spelling). The edge set + its transitive blast closure are VERSION-
;; CACHED on refers-version, so they are re-derived only when CODE actually changed —
;; never on a footprint declare (a @concern->@node fact leaves the call graph alone) nor
;; per overlap query. blast(D) = D's transitive callers (who breaks if D changes), shared
;; with who-calls via resolve/blast-closure. (v1: call-edges re-derives whole-corpus on a
;; code edit; an O(delta) calls_defn — thread D's discipline — is the scale follow-up.)
;; (calls-version / calls-cache atoms are declared up near corpus-groups so reset-refers-state! can invalidate them.)

(defn ensure-calls! []
  (ensure-refers!)                                     ; calls_defn lifts the warm refers_to
  (when (not= @calls-version @refers-version)
    (let [st (:store @co)
          {:keys [defn-meta edges]} (with-resolve-read st (resolve/call-edges))
          nm   (fn [leaf] (s/name-of st leaf))         ; @mod#int identity — footprint joins on these names
          named-edges (mapv (fn [[a b]] [(nm a) (nm b)]) edges)
          {:keys [blast]} (resolve/blast-closure named-edges)
          defns (into {} (map (fn [[leaf m]] [(nm leaf) m]) defn-meta))]
      (reset! calls-cache {:edges named-edges :blast blast :defns defns})
      (reset! calls-version @refers-version)))
  @calls-cache)

;; the @mod#int node-name set in a concern's blast CLOSURE: its footprint nodes plus
;; every node that transitively CALLS one of them. Caller-direction on BOTH sides makes
;; the closure intersection symmetric — A-changes-callee-of-B and B-changes-caller-of-A
;; are both caught (a callee's caller pulls the other concern's node into the overlap).
(defn- closure-of [blast nodes]
  (reduce (fn [acc n] (into (conj acc n) (get blast n))) #{} nodes))

;; {concern-name -> #{footprint node-name}} read LIVE from the warm store — sees a peer's
;; committed-but-unrendered footprint fact with no render and no merge.
(defn- footprint-by-concern [st]
  (when-let [FP (c/value-id st "footprint")]
    (reduce (fn [m cid]
              (let [cl (c/fact-of st cid)
                    c  (s/name-of st (:l cl))
                    r  (:r cl)
                    n  (if (c/value-object? st r) (c/literal st r) (s/name-of st r))]
                (update m c (fnil conj #{}) n)))
            {} (c/by-p st FP))))

;; ============================================================================
;; INCREMENTAL CORPUS CACHE (per-verb O(edited-module), not O(total-app))
;; ============================================================================
;; corpus-from-store! otherwise reduces over EVERY name fact in the whole store on EVERY commit
;; (O(total-app)) — the dominant per-op authoring cost for medium/large apps. But module-defs /
;; forms-of / wrapper-of only read each module's STABLE `beagle-file` wrapper eid (which never
;; changes across commits) plus that wrapper's children FROM THE STORE (always current). So the
;; module->entity-ids map can be seeded ONCE and reused: edits into existing modules need no
;; update (new defs are read off the wrapper's store children), and the verb skips the O(total)
;; reduce. Re-seed only when the target module is absent (a brand-new module) or after a reload.
;; (corpus-groups atom is declared above, near reset-refers-state!, so reload can invalidate it.)
(defn ensure-corpus-groups! [module]
  (when (or (nil? @corpus-groups) (not (contains? @corpus-groups module)))
    (with-resolve-read (:store @co)        ; cold corpus-from-store! (the full reduce) -> file->ents
      (reset! corpus-groups @resolve/file->ents)))
  @corpus-groups)
(defn invalidate-corpus-groups! [] (reset! corpus-groups nil))

;; resolve a target binding spec to its node entity-id in `co`. Accepts a direct node
;; name "@mod#id", OR a (module name) pair resolved via the def-binding tables (value
;; OR type defs) the resolver builds from the warm corpus — the SAME resolution the
;; EDN path uses, so the daemon and EDN agree on which node a binding name denotes.
;;
;; For the :te path we follow `ultimate` so that a node which is itself a REFERENCE
;; (a leaf carrying refers_to, e.g. the `k/->Fact` reference @import#398) resolves to
;; the BINDING it denotes (here the kernel defrecord Fact @kernel#298), chasing
;; re-export/alias chains. This is idempotent for binding nodes — `ultimate` returns a
;; binding unchanged (a binding has no refers_to) — so callers may key :callers on a
;; reference site and have the daemon perform reference->ultimate->reverse-lookup in
;; ONE round-trip, exactly the resolution a text agent must do by hand. A {:module
;; :name} target is already a binding, so it is NOT re-ultimated.
(defn- target-node [req]
  (let [st (:store @co)]
    (cond
      (:te req)                              ; "@mod#id" -> entity-id, then ultimate->binding
      (when-let [n (s/resolve-name st (:te req))]
        (with-resolve-read st (resolve/ultimate n)))
      (and (:module req) (:name req))
      (with-resolve-read st (resolve/def-binding (:module req) (:name req)))
      :else nil)))

;; callers-of(B): the set of [module, rendered-name] for every referencing leaf whose
;; refers_to (followed transitively through re-export chains via `ultimate`) lands on
;; B. Pure over the warm store — refers_to is already materialized; with-resolve-read
;; only binds the read accessors. The rendered-name is computed with resolve.clj's OWN
;; render logic (binding-name + the ctor/qualifier/accessor/keep_spelling markers) so
;; it is byte-identical to what extract-file! emits for that leaf — hence identical to
;; the EDN-path callers-of (which runs this exact code over its EDN-resolved store).
(defn callers-of-in-store [st B]
  (when B
    (with-resolve-read st
      (->> (c/by-p resolve/ctx resolve/REFERS)          ; every live refers_to fact
           (map #(c/fact-of resolve/ctx %))
           (keep (fn [cl]
                   (let [L (:l cl)]
                     (when (= B (resolve/ultimate (:r cl)))   ; ultimate target is B (chains)
                       (let [nm     (resolve/binding-name (resolve/refers-target L))
                             cpfx   (resolve/pred-val L "ctor_prefix")
                             afield (resolve/pred-val L "accessor_field")
                             qual   (resolve/pred-val L "qualifier")
                             fixed? (seq (c/by-lp resolve/ctx L resolve/FIXED))
                             rendered (cond fixed? (resolve/sym-val L)   ; :rename keeps own spelling
                                            cpfx   (str cpfx nm)
                                            afield (str (str/lower-case nm) "-" afield)
                                            qual   (str qual "/" nm)
                                            :else  nm)]
                         [(resolve/name->module (s/name-of resolve/ctx L)) rendered])))))
           set))))

;; ============================================================================
;; S3.3 gate — the STABLE, id-free keyset of materialized refers_to edges.
;; ============================================================================
;; structural-path: a node's slot-path from its module's beagle-file root, using the
;; AST's parent->child slot edges (fN / childN / segN / commentN / tail). It is id-free
;; (slot STRINGS) and stable across re-resolve (re-resolve writes refers_to, never the
;; structural fN edges), so two distinct references with the same rendered name are
;; distinguished by WHERE they sit — the set can't silently undercount a real diff.
(defn- parent-slot-index [st]
  ;; {child-node -> [parent-node "slot"]}. `child` edges duplicate fN; skip them so a
  ;; node has ONE structural parent edge. A node may appear under multiple slots only
  ;; via fN (the canonical), so we key on the fN/segN/commentN/tail slot.
  (let [m @st]
    (reduce (fn [acc [cid cl]]
              (let [pstr (c/literal st (:p cl)) r (:r cl)]
                (if (and (integer? r) (string? pstr)
                         (or (resolve/ord-pos? pstr) (re-matches #"seg\d+" pstr)   ; #36: ord-pos? = old f<int> OR new CRDT key
                             (re-matches #"comment\d+" pstr) (= pstr "tail")))
                  (assoc acc r [(:l cl) pstr])
                  acc)))
            {} (:facts m))))
(defn- node-path [psi node]
  (loop [n node acc []]
    (if-let [[p slot] (get psi n)]
      (recur p (conj acc slot))
      (vec (reverse acc)))))            ; root-down slot path

;; refers-keyset: over a store whose refers_to is materialized, the SET of stable keys
;; — one per live refers_to edge. Each key: [referencing-module ref-rendered-name
;; structural-path target-module target-ultimate-name]. Rendered name + render markers
;; reuse the exact extract-file!/callers-of render logic, so the key tracks the SAME
;; spelling the projection emits. Pure read — no mutation.
(defn refers-keyset [st]
  (with-resolve-read st
    (let [psi (parent-slot-index st)]
      (->> (c/by-p resolve/ctx resolve/REFERS)
           (map #(c/fact-of resolve/ctx %))
           (keep (fn [cl]
                   (let [L (:l cl) D (resolve/ultimate (:r cl))
                         nm     (resolve/binding-name (resolve/refers-target L))
                         cpfx   (resolve/pred-val L "ctor_prefix")
                         afield (resolve/pred-val L "accessor_field")
                         qual   (resolve/pred-val L "qualifier")
                         fixed? (seq (c/by-lp resolve/ctx L resolve/FIXED))
                         rendered (cond fixed? (resolve/sym-val L)
                                        cpfx   (str cpfx nm)
                                        afield (str (str/lower-case nm) "-" afield)
                                        qual   (str qual "/" nm)
                                        :else  nm)]
                     [(resolve/name->module (s/name-of resolve/ctx L))
                      rendered
                      (node-path psi L)
                      (resolve/name->module (s/name-of resolve/ctx D))
                      (resolve/binding-name D)])))
           set))))

;; the gate compares the SCOPED-maintained keyset (read off `co`) against a fresh
;; WHOLE-CORPUS rebuild (over a CLONE so `co`'s scoped state is untouched). A clone is
;; (atom @st): the store value is persistent/immutable, so swap!s on the clone never
;; reach the original. We strip ALL refers_to on the clone, resolve-warm-store! (whole
;; corpus = ground truth), and read its keyset. Symmetric-difference 0 ⇔ scoped==truth.
(defn refers-keyset-resp []
  (let [st     (:store @co)
        scoped (refers-keyset st)
        clone  (atom @st)
        _      (strip-resolve-facts! clone)              ; clear ALL derived edges on the clone
        _      (resolve/resolve-warm-store! clone)        ; whole-corpus GROUND TRUTH
        ground (refers-keyset clone)
        symdiff (clojure.set/union (clojure.set/difference scoped ground)
                                   (clojure.set/difference ground scoped))]
    {:scoped-size (count scoped)
     :ground-size (count ground)
     :symdiff-size (count symdiff)
     :symdiff (vec (take 40 symdiff))
     :version (current-seq @co)}))

;; ============================================================================
;; :edit-min — THE MINIMAL-OP AUTHORING EDIT (Build A: edits as minimal,
;; commutable transactions through the coordinator wire).
;; ============================================================================
;; The whole-module path (bin/fram-commit-code) renders the edited module, emit-edn's
;; it into a FRESH numbering, and commits the WHOLE-module delta vs the coordinator —
;; ~7800 ops for a 1-line set-body, because emit-edn RENUMBERS every node after the
;; edit point. That false-conflicts two agents editing DIFFERENT defns in the SAME
;; module (each rewrites the module), which kills disjoint-edit commutation — the
;; entire thesis.
;;
;; :edit-min instead commits exactly the verb's OWN mint/supersede ops. The authoring
;; verb (resolve.clj verb-set-body!/verb-rename!/verb-upsert-form!) already writes a
;; SMALL fact delta: it mints a new body subtree (kind/v/fN facts on fresh nodes),
;; points the parent's body fN edge at the new root, and SUPERSEDES the old body fN
;; edges. We:
;;   1. CLONE the warm store ((atom @st) — persistent map, O(1), swap!s don't touch
;;      `co`), record `since` = the clone's :next-id.
;;   2. run the verb over the CLONE via resolve/run-verb-warm! (NO text, NO emit-edn,
;;      NO whole-module render). The verb mints/supersedes against LOG-RESIDENT node
;;      identity, exactly as the text path would.
;;   3. HARVEST the delta from the clone:
;;        - NEW entities (objects >= since, not values/facts): the minted AST nodes.
;;          Each needs STABLE coordinator identity — assign @<mod>#<int> at the next
;;          free int for the module (the same @<mod>#<int> shape migrate-flat->co /
;;          s/name! use), memoized clone-eid -> wire-name.
;;        - NEW AST facts (cid >= since, p in the emit-edn vocabulary): translate
;;          (l p r) to wire NAMES (subject + ref object via the name map; literal
;;          object verbatim) -> :assert ops.
;;        - NEW supersede markers (cid >= since, p == supersedes-pred): each victim
;;          (the marker's :r) is an OLD AST fact; translate its (l p r) to names ->
;;          :retract op. (Derived resolve-pred facts the verb's re-resolve! wrote on
;;          the clone are NOT committed — the log carries 0 derived lines.)
;;   4. apply the harvested asserts + retracts to the REAL `co` THROUGH do-assert/
;;      do-retract — the SAME single-(te,p,r) OCC wire every commit uses. So the edit
;;      gets per-(te,p)-group base-version OCC, flat-log persistence + fsync, dirty-
;;      marking (scoped re-resolve), and notify, all for free. Two disjoint same-module
;;      edits touch DISJOINT (te,p) groups => they NEVER conflict (commute by
;;      construction). Order: retract old fN edges FIRST, then assert leaves (kind/v)
;;      before parent fN re-points (so a referenced child exists before its parent
;;      points at it).
;;
;; Returns {:ok true :module M :asserts <n> :retracts <n> :ops <n> :new-nodes <n>}
;; or {:reject ...}. The minimal-op result is BYTE-IDENTICAL (render(log)) to the
;; whole-module path for the same edit — same outcome, minimal mechanism.
;; ============================================================================
;; the emit-edn AST vocabulary — the predicates a code subtree is made of. A code
;; delta NEVER touches a non-AST pred (name/cardinality are schema; refers_to et al.
;; are derived). Mirrors bin/fram-commit-code ast-pred? exactly.
(defn- ast-pred-str? [p]
  (or (#{"kind" "v" "child" "tail" "style" "placement"} p)
      (boolean (and (string? p)
                    (or (re-matches #"f\d+(?:\.\d+)*~(?:\d+|PENDING)" p)   ; #36: new CRDT position key (incl PENDING tie)
                        (re-matches #"(?:f|seg|comment)\d+" p))))))         ; old f<int> + seg/comment (dual)

;; next free @<mod>#<int> for a module, from the store's existing `name` facts. New
;; minted nodes are numbered ABOVE every existing int so they never collide with an
;; ingested node id (or another concurrent edit's nodes, once that edit is committed —
;; each fresh mint reads the current max, and commits serialize under dlock).
(defn- next-module-int [st module]
  (let [NAME (c/value-id st "name")
        pfx  (str "@" module "#")
        mx   (if NAME
               (reduce (fn [acc cid]
                         (let [nm (c/literal st (:r (c/fact-of st cid)))]
                           (if (and (string? nm) (str/starts-with? nm pfx))
                             (if-let [[_ d] (re-matches #"@[^#]+#(\d+)" nm)]
                               (max acc (parse-long d)) acc)
                             acc)))
                       0 (c/by-p st NAME))
               0)]
    (inc mx)))

;; SERIALIZED node-name allocation (Build A). Clone-side next-module-int RACES: two
;; same-base edits read the same local max and mint identical @mod#int names -> collision
;; under true concurrency (proven). This atomic counter, seeded above the GLOBAL max at
;; boot, hands DISJOINT name-int ranges to concurrent edits by construction (swap! atomic),
;; so minting needs no global write-lock — the per-(te,p) OCC carries the rest.
(def node-name-seq (atom 0))
(defn- global-max-name-int [st]
  (let [NAME (c/value-id st "name")]
    (if NAME
      (reduce (fn [acc cid]
                (let [nm (c/literal st (:r (c/fact-of st cid)))]
                  (if-let [[_ d] (and (string? nm) (re-matches #"@[^#]+#(\d+)" nm))]
                    (max acc (parse-long d)) acc)))
              0 (c/by-p st NAME))
      0)))
(defn- seed-name-seq! [st] (reset! node-name-seq (global-max-name-int st)))
(defn- reserve-name-ints! [n]                 ; atomically reserve n consecutive name-ints
  (let [hi (swap! node-name-seq + n)] (vec (range (inc (- hi n)) (inc hi)))))

;; CRDT (commute, #36): the verb computes the ORDER PATH on the lock-free clone
;; (ord-append / ord-between) and mints the position predicate "f<path>~PENDING". Here
;; we set the TIE to the new node's ATOMIC name-int (the :r-node's @mod#<int>, already
;; allocated by reserve-name-ints!) — the positional analog of name allocation. Two
;; concurrent same-gap inserts share the path but get DISTINCT ties -> distinct keys ->
;; BOTH land, ordered by tie (commute). This generalizes D (append) to insert-anywhere.
(defn- allocate-positions [asserts]
  (mapv (fn [[te p r :as op]]
          (if (and (string? p) (str/ends-with? p "~PENDING"))
            (let [tie (or (some-> (re-matches #"@[^#]+#(\d+)" (str r)) second) "0")]
              [te (str (subs p 0 (- (count p) (count "PENDING"))) tie) r])
            op))
        asserts))

;; #(a) IDENTITY-FIRST migration — persist a durable bound_to edge for EVERY resolvable
;; reference. Generalizes the former target-scoped persist-bound-for-rename! (which resolved
;; ONE def's refs via target-node): for every live reference leaf whose warm refers_to lands
;; on a binding NODE and that lacks a bound_to, do-assert `bound_to` (leaf -> the binding's
;; stable @mod#int). References then resolve by IDENTITY, so a display-name rename or a cold
;; re-render follows the edge to the target's CURRENT name instead of re-deriving by spelling.
;;
;; Called AT: (a) code ingest — the first cold whole materialize (ensure-refers!); (b) edit
;; reconciliation — after a scoped re-resolve materializes fresh refers_to for a set-body /
;; replace-in-body / upsert-form's freshly minted references; (c) atomically before a rename
;; (do-edit-min*), so old-spelling refs are locked to identity BEFORE the display name moves.
;;
;; PERSISTED AT THE LOG-AUTHORITY BOUNDARY: do-assert appends to the flat log (durable) +
;; commits to the warm store — the resolver never writes durable facts itself. IDEMPOTENT and
;; COLD-RESTART SAFE: bound_to is multi-valued, so commit! never staleness-rejects and drops
;; an exact-duplicate (leaf,bound_to,target) as :idempotent; `already` skips any leaf that
;; already carries a bound_to so the invariant stays "exactly one durable edge per reference".
;; bound_to survives strip-resolve-facts! (not a resolve-pred) and is filtered from read
;; projections (read-hidden-preds), so it never reaches :query/Datalog. Caller holds dlock.
;; Returns the number of edges newly persisted.
(defn- persist-bound! []
  (let [st   (:store @co)
        REFp (c/value-id st "refers_to")]
    (if-not REFp
      0
      (let [BND     (or (c/value-id st "bound_to") (c/value! st "bound_to"))
            v0      (current-seq @co)
            already (set (map #(:l (c/fact-of st %)) (c/by-p st BND)))
            edges   (->> (c/by-p st REFp)
                         (map #(c/fact-of st %))
                         (filter (fn [cl] (and (integer? (:r cl))        ; refers_to -> a binding NODE (not a value literal)
                                               (not (already (:l cl))))))
                         (map (fn [cl] [(:l cl) (:r cl)]))
                         distinct)]
        (when (seq edges)
          ;; BATCH the durable appends into ONE fsync (the migration can touch every ref).
          (binding [*flat-batch* (atom [])]
            (doseq [[leaf B] edges]
              (do-assert (s/name-of st leaf) "bound_to" (s/name-of st B) v0))
            (flush-flat-batch!)))
        (count edges)))))

;; do-edit-min: run the verb over a CLONE, harvest its minimal delta as wire ops, and
;; commit those through do-assert/do-retract on the REAL `co`. te-naming for new nodes
;; is assigned here (the verb mints nameless local entities on the clone).
(defn- do-edit-min* [spec expected-log fenced?]
  (let [module (:module spec)]
    (when (str/blank? module) (throw (ex-info "edit-min: :module required" {})))
    ;; (#26) reject UNKNOWN verbs early — before the expensive clone/corpus build and before
    ;; they can fall through to run-verb-warm!'s `(System/exit 2)` default, which would HARD-EXIT
    ;; the daemon on a malformed client request. Known verbs: set-body, upsert-form, rename.
    (when-not (#{"set-body" "upsert-form" "insert-form" "insert-comment" "rename" "delete" "reorder" "replace-in-body"} (:op spec))
      (throw (ex-info (str "edit-min: unknown verb '" (:op spec) "' (known: set-body, upsert-form, insert-form, insert-comment, rename, delete, reorder, replace-in-body)")
                      {:reject :unknown-verb})))
    ;; #(a) GRAPH RENAME IS NOW O(1) — references carry DURABLE identity. verb-rename! rewrites
    ;; the DEF binding's spelling only; references follow `bound_to` (the binding's stable @mod#int),
    ;; persisted HERE before the rename so a cold re-render resolves them by IDENTITY, not by spelling
    ;; (the old failure: coord_rename_spelling_check.clj — old-spelled refs re-derived to nothing and
    ;; rendered the OLD name). persist-bound-for-rename! is idempotent + appends bound_to to the flat
    ;; log (durable). Content-hashes (increment (b)) are explicitly OUT of scope: identity is the
    ;; existing sequential @mod#int. (The text-path CLI rename still works as a same-process projection.)
    ;; #(a) rename-identity is persisted INSIDE the commit dlock below (ONE acquisition) —
    ;; NOT in a separate dlock here. A separate lock would leave a lock-free window (the verb
    ;; compute) in which a concurrent agent could commit a NEW reference AFTER the snapshot but
    ;; BEFORE the rename commits, landing it with no identity edge (the red-team's snapshot-window
    ;; race). Folded into the commit lock so persist + rename are atomic.
    (let [real   (:store @co)
          clone  (atom @real)                       ; O(1) structural clone; verb writes here only
          since  (:next-id @clone)
          ;; SCOPE the verb's frame build to the edited module(s) for the single-module
          ;; verbs (set-body/upsert-form read ONLY their own module's def-binding/frame).
          ;; rename is cross-module (consumer-collision + capture across all srcs read
          ;; the whole corpus' require/frame tables) — leave it whole (nil scope).
          ;; delete is ALSO whole-corpus: its no-orphaned-refs invariant reads refers_to
          ;; across every module (a surviving cross-module ref to a victim must REFUSE),
          ;; so it must NOT be scoped. reorder is a pure wrapper order-key re-spell (reads
          ;; only its own module's wrapper) — single-module, scope it like the inserts.
          scope? (#{"set-body" "upsert-form" "insert-form" "reorder" "replace-in-body"} (:op spec))
          scope  (when scope? (fn [s] (str/includes? s module)))
          ;; the supersedes pred the migrate store uses (set by s/setup!); the verb's
          ;; resolve-warm-store! re-points :supersedes-pred at "supersedes", but the
          ;; clone-local marker FACTS it writes carry whatever pred-id it used. We
          ;; harvest by re-reading the clone's :supersedes-pred AFTER the run.
          ;; A REJECTED edit must NOT kill the daemon: bind *reject!* to THROW (the CLI
          ;; default exits the process). The throw unwinds the verb + is caught by the
          ;; :edit-min handler arm, returning {:reject ...} to the client.
          _      (let [tv0 (System/nanoTime)
                       res (binding [resolve/*reject!*
                                     ;; carry the verb's structured disambiguation detail (replace-in-body's
                                     ;; :candidates + :within remedy) into the ex-info so `handle` surfaces
                                     ;; it to the model — not just the bare match count.
                                     (fn [code & [detail]]
                                       (throw (ex-info (or (:message detail) (str "verb rejected the edit (code " code ")"))
                                                       (cond-> {:reject :verb :code code}
                                                         detail (assoc :disambiguation detail)))))
                                     resolve/*capture-only?* true   ; mint/supersede only — no re-resolve, no EDN projection
                                     resolve/*resolve-walk?* false  ; Build B: skip the whole-corpus walk
                                     resolve/*corpus-scope* scope    ; Build B: scope frames to the edited module
                                     resolve/*corpus-cache* (when scope? (ensure-corpus-groups! module))]  ; skip O(total) name reduce
                             (resolve/run-verb-warm! clone spec))]
                   (when (= "1" (System/getenv "FRAM_PROF"))
                     (binding [*out* *err*] (println (format "PROF run-verb-warm!=%.1fms" (/ (- (System/nanoTime) tv0) 1e6)))))
                   res)
          t-hv   (System/nanoTime)
          m      @clone
          sup-pid (:supersedes-pred m)
          ;; Build B — O(delta), not O(corpus): a clone shares ONE monotonic :next-id
          ;; for entities AND facts, and fresh-id! returns the POST-increment value —
          ;; so `since` (the pre-verb :next-id) is the LAST OLD id, and every object the
          ;; verb minted has id in (since, (:next-id m)] (inclusive of the FINAL mint,
          ;; e.g. set-body's parent body-fN re-point). We iterate that RANGE and `get`
          ;; each from :objects/:facts — O(verb delta), ~130 lookups — instead of
          ;; scanning the whole ~78k-entry :facts map three times (old O(corpus) cost).
          since-ids (range (inc since) (inc (:next-id m)))
          ;; clone-local entity-id -> wire NAME. Existing nodes already carry a `name`
          ;; fact (inherited from the real store); new nodes (eid >= since with no
          ;; name) get a fresh @<mod>#<int>, numbered above every existing int.
          name-of* (fn [eid] (s/name-of clone eid))
          new-eids (->> since-ids
                        (filter (fn [id] (and (contains? (:objects m) id)
                                              (not (contains? (:values m) id))
                                              (not (contains? (:facts m) id))
                                              (nil? (name-of* id)))))
                        vec)
          ;; assign names to the new entities (sequential, above the current max int).
          name-ints (reserve-name-ints! (count new-eids))   ; SERIALIZED atomic alloc — concurrent-safe; replaces clone-side next-module-int (which raced)
          eid->name (into {} (map (fn [eid i] [eid (str "@" module "#" i)]) new-eids name-ints))
          wire-name (fn [eid] (or (eid->name eid) (name-of* eid)))
          ;; render a fact's (l p r) into wire (te p r-spec): subject -> name; object
          ;; -> name if it's an entity (a ref/link), else the literal value verbatim.
          ->wire (fn [cl]
                   (let [l (:l cl) p (c/literal clone (:p cl)) r (:r cl)
                         te (wire-name l)
                         rs (if (c/value-object? clone r) (c/literal clone r) (wire-name r))]
                     (when (and te (some? rs)) [te p rs])))
          ;; the verb's NEW facts = the fact ids in [since, next-id) (O(delta)).
          new-cid-facts (keep (fn [cid] (when-let [cl (get (:facts m) cid)] [cid cl])) since-ids)
          ;; ASSERTS: every NEW AST fact (kind/v/fN/... ; skip derived + schema preds).
          new-facts (->> new-cid-facts
                          (map second)
                          (filter (fn [cl] (let [p (c/literal clone (:p cl))]
                                             (ast-pred-str? p)))))
          asserts (vec (keep ->wire new-facts))
          ;; RETRACTS: every NEW supersede marker's VICTIM, as its (l p r) by name.
          ;; A marker is a fact (cid >= since) whose pred == the supersedes pred; its
          ;; :r is the OLD (superseded) fact id. We retract that old fact's AST edge.
          victim-cids (->> new-cid-facts
                           (filter (fn [[_ cl]] (= (:p cl) sup-pid)))
                           (map (fn [[_ cl]] (:r cl))))
          retracts (vec (keep (fn [vcid]
                                (when-let [vcl (get (:facts m) vcid)]
                                  (when (ast-pred-str? (c/literal clone (:p vcl)))
                                    (->wire vcl))))
                              victim-cids))
          t-cm (System/nanoTime)]
      ;; commit through the REAL coordinator wire. Retract old edges first, then
      ;; assert leaves (kind/v) before parent fN re-points. Each op is OCC-checked at
      ;; the current version of its OWN (te,p) group (commit! base-version), so two
      ;; disjoint same-module edits never false-conflict.
      ;; (B) LOCK BOUNDARY — serialize ONLY the commit. Everything ABOVE (clone, verb,
      ;; harvest, atomic name reservation) ran LOCK-FREE => concurrent. INSIDE this lock:
      ;; the do-retract/do-assert ops AND, reached through them, apply-commit-delta!
      ;; (warm-cache) + append-flat! (flat log) — the two that would corrupt if left
      ;; outside. So: compute concurrent, commit serial, cache+log safe.
      (locking dlock
       ;; The identity check and the first possible mutation share this exact
       ;; commit lock. Computation above stays lock-free; a mismatched request
       ;; may consume only transient name reservations, never corpus state.
       (if-let [fence-reject (when fenced?
                               (log-fence-rejection expected-log))]
         fence-reject
         (do
           ;; #(a) persist identity UNDER THE SAME lock as the commit (no lock-free window).
           ;; ensure-refers! re-derives fresh refers_to (so a reference a concurrent agent committed
           ;; BEFORE this lock is captured; one arriving AFTER sees the renamed def — old spelling
           ;; correctly resolves to nothing, a stale ref, not a silent mis-bind), then persist-bound!
           ;; locks every ref to its @mod#int identity BEFORE the display name moves. Both are
           ;; idempotent, so the ingest migration having already run makes this a cheap re-scan.
           ;; v0 is read AFTER, so the rename asserts' OCC base reflects the bound_to commits.
           (when (= "rename" (:op spec)) (ensure-refers!) (persist-bound!))
           (let [v0 (current-seq @co)
                 asserts (allocate-positions asserts)  ; CRDT (#36): set PENDING ties to new nodes' atomic name-ints -> concurrent same-gap inserts get distinct keys, both land (commute)
                 rej (atom nil)]
            ;; BATCH the flat-log appends: collect every fact's line, write+fsync ONCE at the end
            ;; (1 fsync per commit, not per fact). do-assert/do-retract still update the warm store
            ;; per op; only the durable-log fsync is batched — same durable-before-ack guarantee.
            (binding [*flat-batch* (atom [])]
             (doseq [[te p r] retracts :while (nil? @rej)]
               (let [res (do-retract te p r v0)] (when (:reject res) (reset! rej {:op :retract :te te :p p :r r :res res}))))
             ;; r is already a wire value: a name STRING (ref, for fN/child/tail/...) or a
             ;; literal STRING (kind/v). do-assert's kind-of picks :link vs :assert by the
             ;; ref-shape of the string — exactly the migrate-flat->co convention.
             (let [leaf? (fn [[_ p _]] (#{"kind" "v"} p))
                   ordered (concat (filter leaf? asserts) (remove leaf? asserts))]
               (doseq [[te p r] ordered :while (nil? @rej)]
                 (let [res (do-assert te p r v0)]
                   (when (:reject res) (reset! rej {:op :assert :te te :p p :r r :res res})))))
             (flush-flat-batch!))                          ; ONE fsync for the whole commit's appends
            (when (= "1" (System/getenv "FRAM_PROF"))
              (binding [*out* *err*] (println (format "PROF harvest=%.1fms commit=%.1fms" (/ (- t-cm t-hv) 1e6) (/ (- (System/nanoTime) t-cm) 1e6)))))
            (if @rej
              {:reject (:res @rej) :failed-op @rej :module module}
              {:ok true :module module
               :asserts (count asserts) :retracts (count retracts)
               :ops (+ (count asserts) (count retracts))
               :new-nodes (count new-eids) :name-ints name-ints
               :version (current-seq @co)}))))))))

(declare maybe-reload!)
(def ^:dynamic *reload-checked* false)
(def ^:private reload-deferred-ops
  ;; These operations are meaningful against the currently-installed root and
  ;; must never make their client pay for an unrelated external corpus import.
  ;; The pending physical stamp remains visible to the next freshness-sensitive
  ;; read or fact mutation.
  #{:version :status :built-through
    :acquire-lease :renew-lease :release-lease :fence-ok})
(def ^:private reload-mutation-ops
  ;; A first fact mutation after an external edit must absorb/validate that edit
  ;; before committing.  If another request already owns the rebuild, however,
  ;; mutate the old root and make that owner's identity check retry over both.
  #{:assert :assert-with-fence :assert-at-version :assert-at-version-with-fence
    :retract :retract-with-fence :bump})

(defn- prepare-request-reload! [req]
  (let [op (:op req)]
    (cond
      (reload-deferred-ops op) :deferred
      (reload-mutation-ops op) (maybe-reload! true)
      :else (maybe-reload! false))))
;; thread 019f100f-7fff: snapshot/tail-fold/as-of/incremental-aggregate surface,
;; defined below migrate-flat->co (so they can call it) but referenced in handle.
(declare write-snapshot! snapshot-reconcile materialize-as-of register-agg! agg-report sweep-snapshots! built-through last-boot)

;; ============================================================================
;; S-PROFILE TEXT-BRIDGE VERB LAYER (thread A1) — adapter v2 §"S-profile tool
;; contract". Gives sonnet the interface it already knows: TEXT in, repair-grade
;; errors out. write-def (text->forms->graph), read-def / index (graph->text), all
;; over the SAME warm store + the SAME edit-min commit path, never a second parser.
;;
;; Closed under mistake (spec doctrine 3): every call, on ANY malformed input,
;; returns the structured ERROR shape below — never a stack trace, never the
;; t-r1 message-less IOOBE from (nth form 4). The reader is the HOST LispReader
;; (NOT clojure.edn — real code has #(...) / '/ :: ), with *read-eval* false so a
;; `#=(...)` injection is refused at read time.
;; ============================================================================

;; ---- the ERROR shape (build to this — the A2/A3 coordination seam) ----------
;; {:ok false :stage :parse|:canon|:type|:gate|:lookup :at {:module :def :line?}
;;  :message "..." :expected? :got? :suggestion "imperative fix" :nearest [..]}
(defn- s-err [stage & {:keys [at message expected got suggestion nearest]}]
  (cond-> {:ok false :stage stage}
    at             (assoc :at at)
    message        (assoc :message message)
    expected       (assoc :expected expected)
    got            (assoc :got got)
    suggestion     (assoc :suggestion suggestion)
    (seq nearest)  (assoc :nearest (vec nearest))))

(defn- pr-short [x] (let [s (pr-str x)] (if (<= (count s) 60) s (str (subs s 0 57) "..."))))

;; ---- nearest-miss (Levenshtein) ---------------------------------------------
(defn- levenshtein [a b]
  (let [a (str a) b (str b) m (count a) n (count b)]
    (cond (zero? m) n (zero? n) m
      :else
      (loop [i 1 prev (vec (range (inc n)))]
        (if (> i m) (peek prev)
          (recur (inc i)
                 (loop [j 1 row (transient [i])]
                   (if (> j n) (persistent! row)
                     (let [cost (if (= (.charAt a (dec i)) (.charAt b (dec j))) 0 1)]
                       (recur (inc j) (conj! row (min (inc (nth prev j))
                                                      (inc (nth row (dec j)))
                                                      (+ (nth prev (dec j)) cost)))))))))))))
;; NB: `distinct` is broken on sets in the daemon runtime (a loaded ns clobbered
;; clojure.core/distinct — it nth's the coll, which PersistentHashSet refuses). The
;; candidate colls here are already unique (sets / map-keys), so no dedup is needed.
(defn- nearest-names [target candidates & {:keys [n max-dist] :or {n 3 max-dist 3}}]
  (->> candidates
       (map (fn [c] [(str c) (levenshtein target c)]))
       (filter (fn [[_ d]] (<= d max-dist)))
       (sort-by second) (take n) (mapv first)))

;; Beagle builtin type names (primitives + parametric ctors — from beagle-lib/
;; private/types*.rkt). `Vec` is real; `Vector` is NOT (the t-r1 fumble → suggest Vec).
(def ^:private beagle-types
  #{"Any" "Int" "Integer" "Float" "Double" "Long" "Number" "Num" "String" "Bool"
    "Boolean" "Nil" "Char" "Byte" "Keyword" "Symbol" "Void" "Unit"
    "Vec" "List" "Set" "Map" "Promise" "NixType" "Arr" "Ptr" "Atom" "HVec" "Result" "Fn" "U"})

;; ---- the reader: host LispReader, sentinel ns so `::kw` is detectable --------
;; ::kw silently resolves to (ns-name *ns*)/kw — mint-datum! would corrupt it into a
;; symbol leaf ":<ns>/kw". Binding *ns* to a sentinel makes ns==sentinel ⇔ the source
;; wrote `::`, which canon-1 rejects with a pointed fix.
(def ^:private canon-sentinel (create-ns 'fram.autocanon.sentinel))
(def ^:private canon-sentinel-name (name (ns-name canon-sentinel)))

;; strip a leading ```lang ... ``` markdown fence (a real t-r1 fumble: EDN wrapped
;; in a code fence). Idempotent on unfenced text.
(defn- strip-md-fences [s]
  (let [t (str/trim s)]
    (if (str/starts-with? t "```")
      (let [after (subs t 3)
            after (if-let [nl (str/index-of after "\n")] (subs after (inc nl)) "")
            close (str/last-index-of after "```")]
        (str/trim (if close (subs after 0 close) after)))
      s)))

(defn- read-code-forms [source]
  (let [src (strip-md-fences source)
        rdr (java.io.PushbackReader. (java.io.StringReader. src))
        eof (Object.)]
    (try
      (binding [*read-eval* false *ns* canon-sentinel]
        (loop [forms []]
          (let [f (read {:eof eof :read-cond :allow} rdr)]
            (if (identical? f eof) {:forms forms} (recur (conj forms f))))))
      (catch Throwable t
        (let [m (str (.getMessage t))]
          {:error (s-err :parse
                         :message (str "cannot read Beagle source: " (if (str/blank? m) (.getSimpleName (class t)) m))
                         :got (let [p (str/trim src)] (subs p 0 (min 60 (count p))))
                         :suggestion (cond
                                       (re-find #"EvalReader" m)      "remove the `#=(...)` read-eval form (refused)"
                                       (re-find #"(?i)Invalid token: ::" m) "replace `::alias/kw` with a plain `:kw`"
                                       (re-find #"(?i)EOF|unmatched|Unexpected" m) "balance the parens/brackets; send complete forms only"
                                       :else "send only well-formed Beagle forms (no prose, no markdown)"))})))))

;; ---- canonicalization: #(...)→(fn ..), reject ::kw --------------------------
(defn- anon-param? [s]                      ; #() reader params: p1__NN# / rest__NN#
  (and (symbol? s) (re-matches #"(?:p\d+|rest)__\d+#?" (name s))))
(defn- subst-syms [form m]
  (cond
    (symbol? form) (get m form form)
    (seq? form)    (map #(subst-syms % m) form)
    (vector? form) (mapv #(subst-syms % m) form)
    (map? form)    (into {} (map (fn [[k v]] [(subst-syms k m) (subst-syms v m)]) form))
    :else form))
(defn- rename-anon-params [params]          ; [p1__# p2__# & rest__#] -> [arg1 arg2 & varargs] + subst map
  (loop [ps (seq params) i 1 out [] m {}]
    (if (nil? ps) [out m]
      (let [p (first ps)]
        (cond
          (= '& p)        (recur (next ps) i (conj out '&) m)
          (anon-param? p) (let [nm (if (str/starts-with? (name p) "rest") 'varargs (symbol (str "arg" i)))]
                            (recur (next ps) (inc i) (conj out nm) (assoc m p nm)))
          :else           (recur (next ps) (inc i) (conj out p) m))))))
(declare canon-1)
(defn- canon-fn* [form]                     ; (fn* [params] body...) -> (fn [renamed] body') ; #() is single-arity
  (let [tail (rest form)]
    (if (and (seq tail) (vector? (first tail)))
      (let [[params m] (rename-anon-params (first tail))]
        (cons 'fn (cons params (doall (map #(subst-syms (canon-1 %) m) (rest tail))))))
      (cons 'fn (doall (map canon-1 tail))))))
(defn- canon-1 [form]
  (cond
    (keyword? form) (if (= (namespace form) canon-sentinel-name)
                      (throw (ex-info "auto-namespaced keyword"
                                      {:s-canon true :got (str "::" (name form))
                                       :suggestion (str "use `:" (name form) "` (single colon — Beagle has no `::`)")}))
                      form)
    ;; doall: realize EAGERLY so a nested `::kw` throw fires INSIDE canon-form's
    ;; try/catch (a lazy `map` would defer it to mint-time, escaping as a :gate error).
    ;; doall keeps seq-ness (mint-datum! distinguishes seq? from vector?), unlike mapv.
    (seq? form)     (if (= 'fn* (first form)) (canon-fn* form) (doall (map canon-1 form)))
    (vector? form)  (mapv canon-1 form)
    (map? form)     (into {} (map (fn [[k v]] [(canon-1 k) (canon-1 v)]) form))
    ;; sets recurse too — else a nested `::kw` / `#(…)` inside `#{…}` escapes the gate
    ;; and reaches mint-time uncanonicalized. mint-datum! re-encodes the set as (#%set …).
    (set? form)     (set (map canon-1 form))
    :else form))
(defn- canon-form [form]
  (try {:form (canon-1 form)}
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:s-canon d)
             {:error (s-err :canon :message (str "`" (:got d) "` cannot be canonicalized")
                            :got (:got d) :suggestion (:suggestion d))}
             (throw e))))))

;; ---- static type-name lint (pre-mint, no racket) ----------------------------
;; Collects uppercase type-name symbols in `:- <T>` / `:raises <T>` positions and, for
;; any unknown one that is CLOSE to a builtin, returns the repair-grade error. Novel
;; types with no close builtin are left for the real def-level check (avoids false block).
(defn- collect-type-names [form]
  (let [acc (atom [])]
    (letfn [(type-walk [t]
              (cond
                (symbol? t) (let [nm (name t)]
                              (when (and (pos? (count nm)) (Character/isUpperCase ^char (first nm)))
                                (swap! acc conj nm)))
                (or (seq? t) (vector? t)) (doseq [x t :when (not= '-> x)] (type-walk x))
                :else nil))
            (go [f]
              (when (sequential? f)
                (loop [xs (seq f)]
                  (when xs
                    (let [x (first xs)]
                      (when (and (keyword? x) (#{"-" "raises"} (name x)) (next xs)) (type-walk (fnext xs)))
                      (go x)
                      (recur (next xs)))))))]
      (go form)
      (distinct @acc))))
(defn- static-type-check [module form corpus-types]
  (let [known (into beagle-types corpus-types)]
    (some (fn [tn]
            (when-not (contains? known tn)
              (let [near (nearest-names tn beagle-types :n 3 :max-dist 3)]
                (when (seq near)
                  (s-err :type :at {:module module}
                         :message (str "no such type `" tn "`")
                         :got tn :expected (str "a Beagle type (e.g. " (first near) ")")
                         :suggestion (str "use `" (first near) "`")
                         :nearest near)))))
          (collect-type-names form))))

;; ---- def-level check seam (deliverable 5) -----------------------------------
;; ONE swappable fn: (fn [module name] -> nil | error-map). nil = clean. A2 (@cc-fram
;; -9d5855ed) owns the FAST warm primitive; when it lands green they `reset!` it here.
;; Default is a no-op (deep check DEFERRED — surfaced as :deep-check :deferred in the
;; result so it is never SILENT). The static-type lint above already catches unknown
;; type NAMES pre-mint; arity/deep type errors need the real gate (A2 / racket).
(defn- default-def-check [_module _name] nil)
(def ^:private def-check-hook (atom default-def-check))
;; the whole-tree gate (A2's whole-tree-check) behind the S-profile `check {}` verb —
;; nil until wired (FRAM_DEFCHECK=1); a fn [] -> nil (clean) | {:ok false :stage :gate ..}.
(def ^:private whole-tree-hook (atom nil))

;; ---- signature derivation (best-effort, from the surface form) --------------
(defn- types-in-bracket [bracket]
  (loop [xs (seq bracket) acc []]
    (if (nil? xs) acc
      (if (and (keyword? (first xs)) (= "-" (name (first xs))) (next xs))
        (recur (nnext xs) (conj acc (fnext xs)))
        (recur (next xs) acc)))))
(defn- sig-of-form [form]
  (when (and (seq? form) (>= (count form) 2))
    (let [h (str (first form))]
      (cond
        (resolve/DEF-FORMS h)                 ; (def name :- T v) -> "T"
        (let [t (loop [xs (seq (drop 2 form))]
                  (when xs (if (and (keyword? (first xs)) (= "-" (name (first xs))) (next xs)) (fnext xs) (recur (next xs)))))]
          (when t (pr-str t)))
        (resolve/PARAM-FORMS h)               ; (defn name [a :- A] :- R ..) -> "(A -> R)"
        (let [bracket (first (filter vector? form))
              after   (when bracket (next (drop-while #(not (identical? % bracket)) form)))
              ret     (when (and after (keyword? (first after)) (= "-" (name (first after)))) (fnext after))
              ptypes  (when bracket (types-in-bracket bracket))]
          (when bracket (str "(" (str/join " " (map pr-str ptypes)) " -> " (if ret (pr-str ret) "?") ")")))
        :else nil))))

;; ---- module / def resolution over the warm store (call under with-resolve-read)
(defn- module-srcs [module] (filter #(str/includes? % module) resolve/srcs))
(defn- corpus-type-names [] (set (mapcat keys (vals resolve/global-type-exports))))
(defn- def-node-name [fnode]
  (resolve/sym-val (second (resolve/ordered-children (resolve/unwrap-def fnode)))))

;; ---- exception / reject -> ERROR shape (NEVER a bare throw) ------------------
(defn- ex->s-err [module nm t]
  (let [d (ex-data t)
        msg (or (not-empty (str (.getMessage t))) (:message d) (str "internal error: " (.getSimpleName (class t))))]
    (s-err :gate :at {:module module :def nm}
           :message msg
           :got (.getSimpleName (class t))
           :suggestion (or (:suggestion d) "simplify the form; ensure every referenced helper/type exists"))))
(defn- reject->s-err [module nm er]
  (let [msg (if (vector? (:reject er)) (str/join "; " (:reject er)) (str (:reject er)))]
    (cond-> (s-err :canon :at {:module module :def nm}
                   :message (str "verb rejected: " msg)
                   :suggestion "send exactly one named value def per form; narrow ambiguous edits")
      (:disambiguation er) (assoc :disambiguation (:disambiguation er)))))

;; ---- write-def --------------------------------------------------------------
;; A t-r1 fumble: the agent sends the WIRE CHANGESET ([{:op "upsert-form" :form (defn
;; ..)}] or a bare {:op ..}) instead of source. Detect it + point at the :form/:body so
;; the repair is exact ("send the def itself"), not the generic "not a form".
(defn- changeset-forms [form]
  (let [ops (cond (map? form)                                   [form]
                  (and (vector? form) (seq form) (every? map? form)) form
                  :else nil)]
    (when (and ops (every? #(contains? % :op) ops))
      (vec (keep #(or (:form %) (:body %)) ops)))))
(defn- write-one-form [module raw corpus-types]
  (let [cr (canon-form raw)]
    (if (:error cr)
      (update (:error cr) :at #(merge {:module module} %))
      (let [form (:form cr)
            ext-lint (when (seq? form) (resolve/extend-target-lint form))]
        (cond
          (changeset-forms form)
          (let [cf (changeset-forms form)]
            (s-err :canon :at {:module module}
                   :message "this is a wire changeset ({:op ...} maps), not Beagle source"
                   :got (pr-short form)
                   :suggestion (if (seq cf)
                                 (str "send the def source directly: " (pr-short (first cf)))
                                 "send `(def ...)`/`(defn ...)` source, not the {:op ...} wire form")))
          (not (seq? form))
          (s-err :canon :at {:module module} :message (str "top-level item is not a form: " (pr-short form))
                 :got (pr-short form) :suggestion "send a `(def ...)`/`(defn ...)` form — not a bare value or prose")
          (not (resolve/writable-def-head? (str (first form))))
          (s-err :canon :at {:module module}
                 :message (str "top-level head `" (first form) "` is not a writable top-level form")
                 :got (str (first form))
                 :expected "def / defn / defonce / def- / defmulti / defmethod / defrecord / deftype / defprotocol / extend-type / extend-protocol / extend"
                 ;; A multimethod method / protocol extension is a top-level EFFECT — never
                 ;; wrap it as (def …); send the form itself.
                 :suggestion "send the top-level form directly (e.g. `(defmethod m dispatch [..] ..)`, `(extend-type T P (m [..] ..))`, or `(defn f [..] ..)`)"
                 :nearest (nearest-names (str (first form)) resolve/WRITABLE-DEFS :n 2 :max-dist 3))
          (< (count form) 2)
          (s-err :canon :at {:module module} :message "def has no name" :suggestion "name it: `(def <name> ...)`")
          ;; REPAIR-GRADE lint: a LIST in extend-protocol/extend-type target position is the
          ;; classic `(class (byte-array 0))` footgun — it silently mis-partitions and the
          ;; oracle stays red with no error (EXP-025 p2g ring-01). Reject + teach the `extend`
          ;; idiom. Plain `extend` with expression targets is LEGAL (extend-target-lint skips it).
          ext-lint
          (s-err :canon :at {:module module}
                 :message (:message ext-lint) :got (:got ext-lint)
                 :suggestion (:suggestion ext-lint) :nearest (:nearest ext-lint))
          :else
          (let [nm (resolve/writable-disp-name form)]
            (or (static-type-check module form corpus-types)
                (let [er (try (do-edit-min* {:op "upsert-form" :module module :datum form} nil false)
                              (catch Throwable t {:ex t}))]
                  (cond
                    (:ex er)     (ex->s-err module nm (:ex er))
                    (:reject er) (reject->s-err module nm er)
                    ;; ADVISORY def-level check (post-commit). A2's primitive reads the LIVE
                    ;; store given [module name], so the def is committed FIRST, then checked.
                    ;; A :type reject is INNER-LOOP FEEDBACK, not a transaction abort: the warm
                    ;; store is a scratchpad that tolerates in-progress type errors BY DESIGN
                    ;; (the whole-tree gate at promotion is authoritative — defcheck_gate.clj
                    ;; "authority split"). So we surface the error (agent fixes + re-upserts,
                    ;; replacing by name — idempotent) rather than rolling back, which would cost
                    ;; a full corpus read per write. The commit mechanics stay atomic (OCC).
                    (:ok er)     (let [chk (@def-check-hook module nm)]
                                   (if (nil? chk)
                                     {:ok true :name nm :module module :ops (:ops er) :version (:version er)}
                                     (update chk :at #(merge {:module module :def nm} %))))
                    :else (s-err :gate :at {:module module :def nm}
                                 :message (str "upsert-form: unexpected result " (pr-short er))
                                 :suggestion "retry; if it persists inspect the daemon state"))))))))))
(defn- do-write-def [spec]
  (let [module (:module spec) source (:source spec)]
    (cond
      (str/blank? module) (s-err :parse :message "write-def: :module is required" :suggestion "call {:module \"<name>\" :source \"...\"}")
      (str/blank? source) (s-err :parse :message "write-def: :source is required (raw Beagle text)" :suggestion "call {:module M :source \"(def x 1)\"}")
      :else
      (let [rc (read-code-forms source)]
        (if (:error rc)
          (update (:error rc) :at #(merge {:module module} %))
          (let [forms (:forms rc)]
            (if (empty? forms)
              (s-err :parse :at {:module module} :message "no top-level forms in :source" :suggestion "send at least one `(def ...)`/`(defn ...)` form")
              (let [pre (with-resolve-read (:store @co)
                          (let [ss (module-srcs module)]
                            (if (= 1 (count ss))
                              {:ok true :corpus-types (corpus-type-names)}
                              {:ok false :nearest (nearest-names module resolve/srcs :n 3 :max-dist 5) :count (count ss)})))]
                (if-not (:ok pre)
                  (s-err :lookup :at {:module module}
                         :message (if (> (:count pre) 1) (str "module `" module "` is ambiguous (" (:count pre) " sources match)")
                                      (str "no such module `" module "`"))
                         :nearest (:nearest pre)
                         :suggestion (if (seq (:nearest pre)) (str "did you mean `" (first (:nearest pre)) "`?") "run `index` to list modules, or ingest it first"))
                  (loop [fs forms results []]
                    (if (empty? fs)
                      {:ok true :module module :written (count results) :results results
                       :deep-check (if (identical? @def-check-hook default-def-check) :deferred :ran)
                       :version (current-seq @co)}
                      (let [res (try (write-one-form module (first fs) (:corpus-types pre))
                                     (catch Throwable t
                                       (ex->s-err module (try (str (second (first fs))) (catch Throwable _ nil)) t)))]
                        (if (:ok res)
                          (recur (rest fs) (conj results res))
                          {:ok false :module module :written (count results) :results results :failed res
                           :version (current-seq @co)})))))))))))))

;; ---- addressable-forms: the ONE surface `index` emits and `read-def` resolves ----
;; ACCEPTANCE INVARIANT: every :name here round-trips through read-def. The old
;; index filtered top-level forms to VALUE-DEFS heads AND named them with a
;; meta-blind `(second children)`, so on a REAL Clojure module every non-value
;; form (defprotocol, extend / extend-type / extend-protocol, defmulti/defmethod)
;; AND every ^:meta / ^hint-named def (defn- ^Writer f, def ^:private x) was
;; INVISIBLE — ring.core.protocols indexed to `defs=[]` and the model flew blind
;; (EXP-025 p1c ring-01: 10 dead read-def lookups). This makes every top-level
;; form addressable and each :name resolvable back to its enclosing form's text.
(defn- addr-name-leaf [fnode]                 ; def/type/multi NAME leaf, ^meta peeled
  (let [nl0 (resolve/unwrap-meta (second (resolve/ordered-children (resolve/unwrap-def fnode))))]
    (if (= "list" (resolve/kind-of nl0)) (first (resolve/ordered-children nl0)) nl0)))
(defn- target-str [node]                      ; a type/protocol target as a stable string
  (or (resolve/sym-val node) (resolve/node->str node)))
(defn- proto-methods [fnode]                  ; defprotocol/definterface member names
  (keep (fn [m] (when (= "list" (resolve/kind-of m))
                  (resolve/sym-val (first (resolve/ordered-children m)))))
        (drop 2 (resolve/ordered-children fnode))))
(defn- impl-list? [node]                       ; a `(method [params] body)` impl — NOT a target
  (and (= "list" (resolve/kind-of node))
       (let [k (resolve/ordered-children node)]
         (and (>= (count k) 2) (resolve/brackets? (second k))))))
(defn- addressable-forms [src]
  (let [wrapper (resolve/wrapper-of src)
        forms   (when wrapper (map (fn [[_ _ fnode]] fnode) (resolve/wrap-forms wrapper)))
        entries
        (mapcat
         (fn [fnode]
           (when (and fnode (= "list" (resolve/kind-of fnode)))
             (let [h   (str (resolve/head-sym fnode))
                   sig #(try (sig-of-form (edn/read-string (resolve/node->str fnode))) (catch Throwable _ nil))]
               (cond
                 (resolve/VALUE-DEFS h)
                 (when-let [nm (resolve/sym-val (addr-name-leaf fnode))]
                   [{:name nm :head h :fnode fnode :sig (sig)}])
                 ;; defprotocol/definterface: the protocol name AND each member var
                 (#{"defprotocol" "definterface"} h)
                 (let [pn (resolve/sym-val (addr-name-leaf fnode))]
                   (concat (when pn [{:name pn :head h :fnode fnode :sig nil}])
                           (map (fn [mn] {:name mn :head (str h "-member") :fnode fnode :sig nil})
                                (proto-methods fnode))))
                 (resolve/TYPE-DEFS h)          ; defrecord/deftype/defunion (protocols handled above)
                 (when-let [tn (resolve/sym-val (addr-name-leaf fnode))]
                   [{:name tn :head h :fnode fnode :sig nil}])
                 ;; (extend-protocol P T1 impl.. T2 impl.. ...) -> one entry per target block
                 (= h "extend-protocol")
                 (let [kids  (resolve/ordered-children fnode)
                       proto (target-str (second kids))]
                   (map (fn [t] {:name (str proto "@" (target-str t)) :head h :fnode fnode :sig nil})
                        (remove impl-list? (drop 2 kids))))
                 ;; (extend-type T P1 impl.. P2 impl.. ...) -> one entry per protocol block
                 (= h "extend-type")
                 (let [kids (resolve/ordered-children fnode)
                       tn   (target-str (second kids))]
                   (map (fn [p] {:name (str tn "@" (target-str p)) :head h :fnode fnode :sig nil})
                        (remove impl-list? (drop 2 kids))))
                 ;; (extend T P1 {..} P2 {..} ...) -> one entry per protocol symbol
                 (= h "extend")
                 (let [kids (resolve/ordered-children fnode)
                       tn   (target-str (second kids))]
                   (map (fn [p] {:name (str tn "@" (target-str p)) :head h :fnode fnode :sig nil})
                        (filter #(= "symbol" (resolve/kind-of %)) (drop 2 kids))))
                 (= h "defmulti")
                 (when-let [nm (resolve/sym-val (addr-name-leaf fnode))]
                   [{:name nm :head h :fnode fnode :sig nil}])
                 (= h "defmethod")               ; (defmethod M dispatch [params] body) -> `M:dispatch`
                 (let [kids (resolve/ordered-children fnode)
                       mn   (resolve/sym-val (second kids))
                       dv   (when (nth kids 2 nil) (resolve/node->str (nth kids 2 nil)))]
                   (when mn [{:name (str mn (when dv (str ":" dv))) :head h :fnode fnode :sig nil}]))
                 :else nil))))
         forms)]
    (filterv #(not (str/blank? (str (:name %)))) entries)))

;; nearest addressable names: edit-distance candidates UNIONed with same-prefix
;; ones (so `write-body` surfaces `write-body-to-stream`, which edit distance alone
;; — dist 9 — would miss). Capped at n.
(defn- nearest-defs [target candidates & {:keys [n] :or {n 5}}]
  (let [t (str target)
        by-dist (nearest-names t candidates :n n :max-dist 5)
        pfx (when (pos? (count t)) (subs t 0 (min 4 (count t))))
        by-prefix (when pfx (filter #(str/starts-with? (str %) pfx) candidates))
        seen (set by-dist)]
    (vec (take n (concat by-dist (remove seen by-prefix))))))

;; ---- read-def ---------------------------------------------------------------
(defn- do-read-def [spec]
  (let [module (:module spec) nm (:name spec)]
    (cond
      (str/blank? module) (s-err :parse :message "read-def: :module is required" :suggestion "call {:module M :name N}")
      (str/blank? nm)     (s-err :parse :message "read-def: :name is required" :suggestion "call {:module M :name N}")
      :else
      (with-resolve-read (:store @co)
        (let [ss (module-srcs module)]
          (if (not= 1 (count ss))
            (s-err :lookup :at {:module module} :message (str "no such module `" module "`")
                   :nearest (nearest-names module resolve/srcs :n 3 :max-dist 5)
                   :suggestion "run `index` to list modules")
            (let [src   (first ss)
                  addr  (addressable-forms src)
                  ;; primary: value/type/protocol-method binding -> its top-level form.
                  ;; fallback: any addressable name (extend blocks, defmulti/method, or a
                  ;; protocol MEMBER whose binding has no top-level form of its own) ->
                  ;; the enclosing form's text. Every name `index` lists resolves here.
                  fnode (or (when-let [bnode (resolve/def-binding src nm)]
                              (resolve/form-for-victim src bnode))
                            (:fnode (some #(when (= nm (:name %)) %) addr)))]
              (if (nil? fnode)
                (let [near (nearest-defs nm (mapv :name addr) :n 5)]
                  (s-err :lookup :at {:module module :def nm}
                         :message (str "no such def `" nm "` in `" module "`")
                         :nearest near
                         :suggestion (if (seq near)
                                       (str "did you mean `" (first near) "`? — or run `index` on this module")
                                       "run `index` on this module to see its addressable names")))
                (let [src-text (resolve/node->str fnode)]
                  {:ok true :module module :name nm
                   :source src-text
                   :sig (try (sig-of-form (edn/read-string src-text)) (catch Throwable _ nil))
                   :version (current-seq @co)})))))))))

;; ---- index ------------------------------------------------------------------
(defn- do-index [spec]
  (with-resolve-read (:store @co)
    (let [all-mods (vec (sort (into #{} resolve/srcs)))   ; into #{} — `distinct` is clobbered on sets here
          want (:module spec)]
      (if (str/blank? want)
        {:ok true :modules all-mods :count (count all-mods) :version (current-seq @co)}
        (let [ss (module-srcs want)]
          (if (not= 1 (count ss))
            (s-err :lookup :at {:module want} :message (str "no such module `" want "`")
                   :nearest (nearest-names want resolve/srcs :n 3 :max-dist 5)
                   :suggestion "call `index` with no :module to list all modules")
            (let [src  (first ss)
                  defs (mapv #(select-keys % [:name :head :sig]) (addressable-forms src))]
              {:ok true :module want :defs defs :count (count defs) :version (current-seq @co)})))))))

;; ---- phase 2: wire A2's WARM def-level check (thread A2, defcheck_gate.clj) ---
;; Opt-in via FRAM_DEFCHECK=1 so LIVE task coordinators (7977/48942/48950 — no code,
;; no sidecar) are untouched; the EXP-026 code daemon sets it. Graceful degrade: any
;; load/resolve failure leaves the advisory default in place (never breaks the daemon).
;; A2's check-def reads fram.defcheck/*coord-port* — we bind it to THIS daemon's serving
;; port per call (with-bindings; no root mutation). *autostart?* boots the 49060 sidecar.
(defn- maybe-wire-def-check! [port]
  (when (= "1" (System/getenv "FRAM_DEFCHECK"))
    (try
      (load-file (str (System/getProperty "user.dir") "/defcheck_gate.clj"))
      (let [cp  (resolve 'fram.defcheck/*coord-port*)
            cd  (some-> (resolve 'fram.defcheck/check-def) var-get)
            wtc (some-> (resolve 'fram.defcheck/whole-tree-check) var-get)]
        (if (and cp cd)
          (do
            (reset! def-check-hook (fn [module name] (with-bindings {cp port} (cd module name))))
            (when wtc (reset! whole-tree-hook (fn [] (with-bindings {cp port} (wtc)))))
            (println (str "def-check: WARM primitive wired — fram.defcheck/check-def @ coord-port " port
                          (when wtc " (+ whole-tree-check -> :check verb)"))))
          (binding [*out* *err*]
            (println "def-check: defcheck_gate.clj loaded but check-def/*coord-port* not found; staying advisory"))))
      (catch Throwable t
        (binding [*out* *err*]
          (println (str "def-check: warm primitive unavailable (" (.getMessage t) "); staying advisory-deferred")))))))

(defn- do-edit-min
  "Legacy in-process edit seam. Socket fencing is applied by do-edit-min*."
  [spec]
  (do-edit-min* spec nil false))

(defn- edit-min-response [req expected-log fenced?]
  (try (do-edit-min* (:spec req) expected-log fenced?)
       (catch Throwable t
         ;; Surface a verb's structured disambiguation payload (replace-in-body
         ;; candidates + :within suggestions) alongside the human :reject message.
         (let [d   (ex-data t)
               msg (or (not-empty (str (.getMessage t))) (:message d)
                       (str "internal error: " (.getSimpleName (class t))))]
           (cond-> {:reject [(str "edit-min: " msg)]
                    :error (ex->s-err (:module (:spec req)) (:name (:spec req)) t)
                    :version (current-seq @co)}
             (:disambiguation d) (assoc :disambiguation (:disambiguation d)))))))

;; Internal adapter read: resolve a module's canonical downstream view from the
;; graph's @<module>#root `file` fact. The MCP validates path confinement before
;; sending :edit-min; returning anything but exactly one live path fails closed.
(defn- module-path-response [module]
  (let [st (:store @co)
        root-id (when-not (str/blank? (str module))
                  (s/resolve-name st (str "@" module "#root")))
        file-pred (c/value-id st "file")
        paths (if (and root-id file-pred)
                (->> (c/by-lp st root-id file-pred)
                     (map #(c/literal st (:r (c/fact-of st %))))
                     (filter string?) distinct vec)
                [])]
    (cond
      (str/blank? (str module))
      {:reject ["module-path: :module required"] :code :invalid-module
       :version (current-seq @co)}

      (nil? root-id)
      {:reject [(str "module-path: no registered root for module " (pr-str module))]
       :code :missing-module :version (current-seq @co)}

      (not= 1 (count paths))
      {:reject [(str "module-path: expected exactly one live file fact for " module
                     ", found " (count paths))]
       :code :ambiguous-module-path :version (current-seq @co)}

      :else
      {:ok true :module module :path (first paths) :version (current-seq @co)})))

(defn- handle* [req]
  ;; (#14 socket EXPOSURE) :edit-min runs OUTSIDE the outer dlock. do-edit-min's compute
  ;; (clone/verb/harvest) is lock-free and its COMMIT phase already takes dlock itself (the (B)
  ;; boundary), so wrapping the whole op in the outer dlock re-serializes the lock-free compute
  ;; and HIDES the concurrency the logic layer + the 150-pair commute already proved. maybe-reload!
  ;; is a no-op in v2-log mode (the code daemon, where :edit-min lives), so skipping it here is
  ;; safe; the commit still serializes under dlock and is OCC-checked per (te,p) at commit time.
  (cond
    ;; Query fencing covers only reload/fence validation and capture of one
    ;; immutable cache root. Evaluation happens after releasing dlock, so an
    ;; expensive or abandoned read can never block writes, leases, or status.
    (and (= :for-log (:op req)) (query-request? (:request req)))
    (let [_ (maybe-reload!)
          inner (:request req)
          captured (locking dlock
                     (if-let [reject (log-fence-rejection (:expected-log req))]
                       {:reject reject}
                       {:roots (capture-query-roots!)}))]
      (if-let [reject (:reject captured)]
        reject
        (execute-query inner (:roots captured))))

    (query-request? req)
    (let [_ (maybe-reload!)
          roots (locking dlock (capture-query-roots!))]
      (execute-query req roots))

    ;; Protocol-level corpus fencing. Normal requests validate and execute while
    ;; this outer dlock remains held; the recursive legacy handler may re-enter
    ;; the JVM monitor, but cannot release the fence between check and mutation.
    ;; :edit-min is different by design: its expensive compute stays lock-free
    ;; and do-edit-min validates expected-log inside its existing commit lock.
    (= :for-log (:op req))
    (let [inner (:request req)
          expected (:expected-log req)]
      (cond
        (not (map? inner))
        {:reject ["log fence requires a nested request map"]
         :code :invalid-log-fence}

        (= :for-log (:op inner))
        {:reject ["nested log-fence envelopes are not supported"]
         :code :invalid-log-fence}

        (= :edit-min (:op inner))
        ;; Cheap preflight avoids running the lock-free compiler/harvest work
        ;; for an already-proven mismatch. do-edit-min* repeats this check at
        ;; the commit boundary, under the same dlock as its first mutation.
        (if-let [fence-reject (locking dlock
                                (log-fence-rejection expected))]
          fence-reject
          (edit-min-response inner expected true))

        :else
        (do
          (prepare-request-reload! inner)
          (locking dlock
          (if-let [fence-reject (log-fence-rejection expected)]
            fence-reject
            (binding [*reload-checked* true]
              (handle* inner)))))))

    ;; LOCK-FREE read: deref the @co immutable snapshot, NO dlock. Reads don't need the
    ;; writer lock (the atom swap on commit is atomic), so a reader never serializes behind
    ;; concurrent writers. Used to measure true propagation (commit -> reader sees) without
    ;; the read-coupled-to-writer-lock artifact the dlock-wrapped :version/:status have.
    (= :version-free (:op req)) {:version (current-seq @co)}
    ;; LOCK-FREE CONTENT check: is value string (:v req) interned in the warm @co snapshot
    ;; yet? Names are unique per writer, so interned <=> that writer's def reached the store.
    ;; This is the propagation visibility signal (commit -> reader sees THIS def), off the dlock.
    (= :seen (:op req)) {:seen (boolean (c/value-id (:store @co) (:v req)))}
    ;; Lock-free reload telemetry: supervisors/tests can observe the two-phase build
    ;; without triggering another maybe-reload! request themselves.
    (= :reload-status (:op req))
    {:active @active-reloads :retries @reload-retries
     :generation @reload-generation}
    ;; S-PROFILE text-bridge verbs (thread A1). LOCK-FREE: write-def commits through
    ;; do-edit-min (which takes dlock itself); read-def/index are @co snapshot reads.
    ;; All return the structured ERROR shape on any malformed input — never a bare throw.
    (= :write-def (:op req)) (try (do-write-def (:spec req))
                                  (catch Throwable t (ex->s-err (:module (:spec req)) nil t)))
    (= :read-def  (:op req)) (try (do-read-def (:spec req))
                                  (catch Throwable t (ex->s-err (:module (:spec req)) (:name (:spec req)) t)))
    (= :index     (:op req)) (try (do-index (:spec req))
                                  (catch Throwable t (ex->s-err (:module (:spec req)) nil t)))
    ;; :check {} — the whole-tree gate the agent calls before declaring done (spec
    ;; S-profile contract). Delegates to A2's whole-tree-check (:stage :gate) when wired;
    ;; else reports :deferred (advisory phase). nil from the gate = the tree is clean.
    (= :check (:op req))
    (if-let [wtc @whole-tree-hook]
      (let [res (try (wtc) (catch Throwable t (ex->s-err nil nil t)))]
        (if (nil? res)
          {:ok true :checked :whole-tree :version (current-seq @co)}
          (assoc res :version (current-seq @co))))
      {:ok true :checked :deferred
       :message "whole-tree :check not wired (advisory phase; set FRAM_DEFCHECK=1 + defcheck_gate.clj)"
       :version (current-seq @co)})
    (= :edit-min (:op req)) (edit-min-response req nil false)
  :else
  (do
    ;; maybe-reload! performs its own two-phase capture/build/install.  It must run
    ;; before this request takes dlock; *reload-checked* prevents a log-fenced
    ;; recursive dispatch from repeating it while the outer fence holds the monitor.
    (when-not *reload-checked* (prepare-request-reload! req))
    (locking dlock                     ; serialize writes + short immutable captures
     (case (:op req)
      :version  {:version (current-seq @co)}
      :assert   (do-assert (:te req) (:p req) (:r req) (:base req))
      ;; Lease-fenced fact writes. The fence and mutation share the coordinator
      ;; store lock; a stale holder cannot write after expiry/takeover.
      :assert-with-fence
      (with-current-fence
       (:res req) (:holder req) (:epoch req)
       #(do-assert (:te req) (:p req) (:r req) (:base req)))
      ;; Read-set commit seam for callers that validated facts across multiple
      ;; subjects/predicates. Ordinary :assert intentionally interprets :base
      ;; only as per-(subject,predicate) OCC for declared-single predicates;
      ;; changing that would break multi-value coexistence and no-base LWW.
      ;; This opt-in verb instead compares the caller's GLOBAL snapshot version
      ;; and performs the existing assert in this same dlock turn. Therefore an
      ;; intervening write anywhere in the graph rejects before mutation, while
      ;; accepted writes retain do-assert's rules, durability, notifications,
      ;; cascades, and identical-triple idempotency.
      :assert-at-version
      (assert-at-version (:te req) (:p req) (:r req) (:base req))
      :assert-at-version-with-fence
      (with-current-fence
       (:res req) (:holder req) (:epoch req)
       #(assert-at-version (:te req) (:p req) (:r req) (:base req)))
      :retract  (do-retract (:te req) (:p req) (:r req) (:base req))
      :retract-with-fence
      (with-current-fence
       (:res req) (:holder req) (:epoch req)
       #(do-retract (:te req) (:p req) (:r req) (:base req)))
      ;; :bump — ATOMIC add to a numeric counter (read-add-write under the lease lock, so
      ;; concurrent charges from N executors can't lose updates). Declares the predicate
      ;; single-valued (else asserts accumulate -> arbitrary reads). The swarm token budget
      ;; (@swarm budget_spent) uses it. -> {:ok seq :value <new>}. (:n may be negative.)
      :bump     (do (assert-flat-corpus-append-boundaries!)
                    (bump-counter! @co (:te req) (:p req) (:n req)))
      ;; --- exclusive-lease wire verbs (agents lease @lease:<res> over the socket) ---
      ;; The lease fn enforces mutual exclusion in its OWN (:lock co); the outer dlock just
      ;; serializes with other daemon ops. A bare :assert @lease:<res> is the UNSAFE lost-update
      ;; path the lease arm exists to close — agents MUST use these. No notify-subs! (lease
      ;; changes are not broadcast), matching the fram-lease fork. Impl: coord.clj (load-file'd).
      :acquire-lease
      (lease-flat-mutation!
       req #(acquire-lease! @co (:holder req) (:res req) (:ttl-ms req)))
      ;; Renewal is not reacquisition: exact holder + current epoch + unexpired
      ;; state are checked, then the new expiry and globally fresh epoch are
      ;; persisted in the same coordinator turn.
      :renew-lease
      (lease-flat-mutation!
       req #(renew-lease! @co (:holder req) (:res req) (:epoch req) (:ttl-ms req)))
      ;; Epoch is optional only for wire compatibility with legacy callers.
      ;; Modern fenced callers include it so a delayed release from the same
      ;; holder cannot delete a successor acquisition (ABA).
      :release-lease
      (lease-flat-mutation!
       req #(if (contains? req :epoch)
              (release-lease! @co (:holder req) (:res req) (:epoch req))
              (release-lease! @co (:holder req) (:res req))))
      :fence-ok      {:fence-ok (fence-ok? @co (:res req) (:holder req) (:epoch req))}
      ;; :edit-min is handled ABOVE, outside the outer dlock (socket exposure) — see top of handle.
      :validate {:violations (all-violations (index!))}
      ;; gate: is the incrementally-maintained warm cache == a fresh whole rebuild?
      :warm-check (let [inc (warm!) fresh (client-view-facts @co) fidx (idx-build fresh)]
                    {:consistent (and (= (:triples (:idx inc)) (:triples fidx))
                                      (= (:by-l (:idx inc)) (:by-l fidx))
                                      (= (:by-p (:idx inc)) (:by-p fidx))
                                      (= (:by-r (:idx inc)) (:by-r fidx))
                                      (= (:by-pr (:idx inc)) (:by-pr fidx))
                                      (= (:by-lp (:idx inc)) (:by-lp fidx))
                                      (= (:facts inc) (set fresh)))
                     :inc-triples (count (:triples (:idx inc))) :fresh-triples (count (:triples fidx))
                     :version (current-seq @co)})
      ;; :boot echoes HOW this process booted ({:mode :snapshot|:fold :ms .. :reason ..})
      ;; — the post-bounce verification surface for snapshot boot (thread 019f2190).
      ;; :rollback_floor — the queryable floor law (B2 §5/R0): releases below
      ;; this id are out of rollback support for generation-managed corpora.
      :status   {:version (current-seq @co) :facts (hybrid-fact-count)
                 :log (or @flat-log (:log @co)) :boot @last-boot
                 :queries {:active @active-queries :monitors @active-query-monitors
                           :stops @query-stops}
                 :reloads {:active @active-reloads :retries @reload-retries
                           :generation @reload-generation}
                 :rollback_floor fram.rt/rollback-floor}
      ;; :facts — the WHOLE live view as flat [l p r] triples IN FOLD EMISSION ORDER
      ;; (fram.fold/refold-order), for daemon-first CLI reads (thread 019f2190): the
      ;; client feeds these straight into its kernel index instead of paying the
      ;; per-process cold fold. Ordering here is part of the op's CONTRACT — the
      ;; client renders byte-identical output without re-ordering, and bb shares
      ;; clojure.lang.PersistentHashMap with the JVM so the hash order agrees.
      ;; Computed AFTER maybe-reload! above (exactly as fresh as the flat log at
      ;; request time) and cached per version — a read storm re-serializes, never
      ;; re-orders. :log echoes which log this daemon serves so a client can refuse
      ;; a mismatched daemon (fram.rt/coord-live-facts checks it). Clients ask with
      ;; {:fmt :json} — bb decodes the ~2MB payload ~12x faster as JSON than as EDN.
      :facts   (let [v (current-seq @co)
                      cc @facts-wire-cache
                      triples (if (= v (:version cc))
                                (:triples cc)
                                (let [ts (mapv (fn [c] [(:l c) (:p c) (:r c)])
                                               (fold/refold-order (client-view-facts @co)))]
                                  (reset! facts-wire-cache {:version v :triples ts})
                                  ts))]
                  {:version v
                   :log (or @flat-log (:log @co))
                   :facts triples})
      ;; thread 019f100f-7fff — snapshot/compaction surface:
      ;; :snapshot writes a checkpoint (dump-log! image + @snapshot:<seq> facts);
      ;; :snapshot-reconcile is the gate (live store == from-scratch whole migrate).
      :snapshot           (cond
                            (not @flat-log) {:error "snapshot needs flat-log (drop-in) mode"}
                            ;; refuse under log-split routing: write-snapshot!'s byte_offset indexes
                            ;; ONLY the coordination log, so a later FRAM_TELEMETRY_LOG-unset reboot
                            ;; would incremental-boot off this sidecar and silently drop telemetry
                            ;; facts committed after it. Mirrors the periodic-writer guard.
                            @telemetry-log {:error "snapshot disabled under log-split routing (FRAM_TELEMETRY_LOG set)"}
                            :else (write-snapshot! @co @flat-log))
      :snapshot-reconcile (snapshot-reconcile)
      :built-through      {:built-through @built-through :version (current-seq @co)}
      ;; warm scope-correct callers of a binding, served from refers_to materialized
      ;; over `co` (version-cached). ensure-refers! whole-corpus re-resolves only when
      ;; the code version moved (the correct first cut); the reverse lookup is then a
      ;; by-pr/ultimate scan returning the set of [module, rendered-name] referencing
      ;; leaves. Target is {:te "@mod#id"} OR {:module .. :name ..}.
      ;; :render — WARM render (TRACK B opt). The cold CLI path (fram-render-code) pays
      ;; migrate-flat->co (log fold) + resolve-warm-store! (whole-corpus refers_to walk)
      ;; per invocation (~1.8s). Served off `co` it skips BOTH: ensure-refers! keeps
      ;; refers_to current (scoped), then project the module via extract-file! over the
      ;; warm store and return the resolved EDN. The client racket --renders the EDN.
      :module-path (module-path-response (:module req))
      :render   (do (ensure-refers!)
                    (let [st (:store @co) module (:module req)
                          tmp (str (System/getProperty "java.io.tmpdir") "/fram-warmrender-" (System/nanoTime))
                          _   (.mkdirs (java.io.File. ^String tmp))
                          edn-out (str tmp "/resolved-" module ".bclj.edn")]
                      (binding [resolve/ctx st
                                resolve/tx (c/begin-tx! st "warm-render-read")
                                resolve/Vp     (c/value-id st "v")
                                resolve/KIND   (c/value-id st "kind")
                                resolve/REFERS (c/value-id st "refers_to")
                                resolve/FIXED  (c/value-id st "keep_spelling")
                                resolve/QUAL   (c/value-id st "qualifier")
                                resolve/CTOR   (c/value-id st "ctor_prefix")
                                resolve/ACC    (c/value-id st "accessor_field")
                                resolve/file->ents (atom {})
                                resolve/srcs [] resolve/file-modframe {} resolve/file-typeframe {}
                                resolve/file-accessors {} resolve/global-exports {}
                                resolve/global-type-exports {} resolve/global-accessor-exports {}]
                        (resolve/corpus-from-store!)
                        (let [the-src (some #(when (= module %) %) resolve/srcs)]
                          (if the-src
                            (do (resolve/extract-file! the-src edn-out)
                                {:edn (slurp edn-out) :module module :version (current-seq @co)})
                            {:error "no such module in warm corpus" :module module
                             :srcs (vec resolve/srcs) :version (current-seq @co)})))))
      :callers  (do (ensure-refers!)
                    (let [B (target-node req)]
                      (if B
                        {:callers (vec (callers-of-in-store (:store @co) B))
                         :target  (s/name-of (:store @co) B)
                         :version (current-seq @co)}
                        {:error "no such binding" :te (:te req) :module (:module req) :name (:name req)
                         :version (current-seq @co)})))
      ;; :blast — transitive callers of a binding over the WARM calls_defn closure (who
      ;; breaks if it changes). Target is {:te "@mod#id"} OR {:module .. :name ..}; the
      ;; node-id is resolved via ultimate so a reference site resolves to its binding.
      :blast    (let [{:keys [blast]} (ensure-calls!)
                      B (target-node req)
                      bname (when B (s/name-of (:store @co) B))
                      callers (get blast bname #{})]
                  (if bname
                    {:node bname :blast (vec callers) :count (count callers)
                     :version (current-seq @co)}
                    {:error "no such binding" :te (:te req) :module (:module req) :name (:name req)
                     :version (current-seq @co)}))
      ;; :concern-overlap — for {:te "@concern:id"}, the peer concerns whose blast CLOSURE
      ;; intersects mine, derived over the warm store: footprint read LIVE (sees peers'
      ;; committed-but-unrendered @concern->@node facts), closure via the recursive
      ;; calls_defn reaches. Footprint is multi-valued and declaring never blocks — overlap
      ;; is the SIGNAL surfaced, never a conflict. -> {:overlaps [{:concern :shared :footprint}]}.
      :concern-overlap
      (let [{:keys [blast]} (ensure-calls!)
            st (:store @co)
            fp (or (footprint-by-concern st) {})
            me (:te req)
            my-nodes (get fp me #{})
            my-clo (closure-of blast my-nodes)
            overlaps (->> (dissoc fp me)
                          (keep (fn [[c nodes]]
                                  (let [shared (clojure.set/intersection my-clo (closure-of blast nodes))]
                                    (when (seq shared)
                                      {:concern c :shared (vec shared) :footprint (vec nodes)}))))
                          vec)]
        {:concern me :footprint (vec my-nodes) :overlaps overlaps
         :version (current-seq @co)})
      ;; ---- S3.3 gate surface (test-only reads; no mutation) ------------------
      ;; :refers-ensure — force the maintenance step (scoped or cold) for the current
      ;; dirty set, then report what it did: mode, modules walked, edges stripped, and
      ;; the export-changed set. This is the (d) skipped-work evidence — a scoped run
      ;; reports :walked = exactly the affected modules, never the corpus.
      :refers-ensure (do (ensure-refers!)
                         {:last-materialize @last-materialize
                          :dirty (vec @dirty-modules)
                          :version (current-seq @co)})
      ;; :refers-keyset — the materialized refers_to edge set as STABLE, id-free keys
      ;; (referencing module + rendered name + structural fN-path + target module +
      ;; target ultimate name). The gate compares this against a fresh whole-corpus
      ;; rebuild's keyset: equality (sym-diff 0) is the scoped==whole-corpus proof.
      ;; ensure-refers! first so the scoped-maintained set is current. :scoped key
      ;; reads off `co`; :ground recomputes whole-corpus over a CLONE (never disturbs
      ;; `co`'s scoped state), so both keysets come from the same store snapshot.
      :refers-keyset (do (ensure-refers!) (refers-keyset-resp))
      ;; :resolved — surface the MULTIPLICITY of a (te,pred) group instead of hiding it
      ;; behind first-live. P-of/select-main-1 return (first) — a SELECTION, not a
      ;; uniqueness proof (#19) — so a contested single-valued field reads as a silently
      ;; arbitrary pick. This returns {:value <first> :members <n> :ambiguous? (> n 1)
      ;; :values [...]} so an agent gets a CHECKABLE answer. Pure read over the live
      ;; (l,p) group — rep-stable (no fN). (interface investigation #3)
      :resolved (let [st (:store @co)
                      e (s/resolve-name st (:te req))
                      pid (c/value-id st (:p req))
                      live (if (and e pid) (live-cids-lp @co e pid) [])
                      render (fn [cid] (let [r (:r (c/fact-of st cid))]
                                         (if (c/value-object? st r) (c/literal st r) (s/name-of st r))))
                      vals (mapv render live)]
                  ;; :value is the coexist-ELECTED member (not a blind first-live); :members/
                  ;; :ambiguous?/:values still surface the full multiplicity so a contested
                  ;; (te,pred) reads as a CHECKABLE answer, not a silently arbitrary pick.
                  {:value (when (seq live) (render (elect @co live)))
                   :members (count vals) :ambiguous? (> (count vals) 1)
                   :values vals :version (current-seq @co)})
      ;; :as-of — time-travel READ (thread H, Part B). Reconstruct the view as of seq S
      ;; (coord/live-as-of: born <= S, no supersede/withdraw marker <= S) and either
      ;; run a Datalog :query over that historical EDB (SAME q/run oracle, just fed an
      ;; as-of facts vec — the engine is untouched) or resolve one (:te,:p) group as-of.
      ;; Retraction-as-append makes this exact: a later-withdrawn fact is RE-SEEN at an
      ;; earlier S. Bounded by the snapshot floor (live-as-of folds the in-store tail).
      :as-of (let [s (:seq req) st (:store @co)]
               (cond
                 (nil? s) {:error ":as-of needs :seq"}
                 (:query req)
                 (let [cids   (live-as-of @co s)
                       facts (vec (keep (fn [cid] (when-let [t (fact->triple st cid)]
                                                     (ck/->Fact (nth t 0) (nth t 1) (nth t 2))))
                                         cids))
                       res    (q/run facts (:query req))]
                   (assoc res :as-of s :version (current-seq @co)))
                 (:te req)
                 (let [e   (s/resolve-name st (:te req))
                       pid (c/value-id st (:p req))
                       live (if (and e pid) (live-as-of-lp @co s e pid) [])
                       render (fn [cid] (let [r (:r (c/fact-of st cid))]
                                          (if (c/value-object? st r) (c/literal st r) (s/name-of st r))))
                       vals (mapv render live)]
                   {:value (when (seq live) (render (elect @co (vec live))))
                    :members (count vals) :ambiguous? (> (count vals) 1)
                    :values vals :as-of s :version (current-seq @co)})
                 :else {:error ":as-of needs :query or :te/:p"}))
      {:error "unknown op"})))))

;; GROUP COMMIT boundary: collect this request's durability tickets while the
;; work (and dlock) runs, then await them AFTER the lock is released — so the
;; critical section covers only in-memory commit work (validate/OCC/rule-check/
;; store-mutate/warm-cache), never the fsync. The reply still happens only after
;; every one of this request's appends is fsynced (durability-before-ack holds);
;; a failed append/fsync rethrows here and surfaces as the same {:error} the old
;; in-lock fsync failure did. Reads collect no tickets and pass straight through.
(defn handle
  ([req] (handle req nil))
  ([req query-control]
   (binding [*durable-tickets* (atom [])
             *request-query-control* query-control]
     (let [resp (handle* req)]
       (doseq [t @*durable-tickets*] (await-durable! t))
       resp))))

;; ---- socket server (verbatim shape from the proven coord.clj) ---------------
;; Hardened (findings #2/#5/#19/#20): every accepted socket gets a read timeout
;; (no slow-loris), the request line is read with a hard byte cap (no heap
;; balloon), and the WHOLE handler is wrapped to catch Throwable — including
;; StackOverflowError from a deep-nested EDN payload — so a malformed/hostile
;; request returns a clean {:error} line (or just drops the conn) instead of
;; killing the per-connection thread. A reply is best-effort: if writing it also
;; throws (socket already gone), we still hit the finally and close.
;; Response wire format is EDN by DEFAULT (every existing client — byte-identical to
;; before). A request carrying {:fmt :json} (or "json") OPTS IN to cheshire JSON
;; instead (fram.rt/to-json = cheshire/generate-string): the Elixir client decodes
;; JSON ~90x faster than EDN (eden), so an opt-in JSON :query is the first-load/refresh
;; win. Gated purely on :fmt — no :fmt => pr-str, exactly as today.
(defn- serialize-resp [fmt resp]
  (if (or (= fmt :json) (= fmt "json"))
    (fram.rt/to-json resp)
    (pr-str resp)))

(defn- try-reply
  ([^BufferedWriter w resp] (try-reply w resp nil))
  ([^BufferedWriter w resp fmt]
   (try (.write w (serialize-resp fmt resp)) (.newLine w) (.flush w) (catch Throwable _ nil))))

(defn- monitor-query-disconnect [^Socket s ^BufferedReader r control]
  ;; serve-conn has completely parsed the protocol's single request line before
  ;; starting this future. From this point until close, this monitor is the sole
  ;; input-stream owner; the request handler never reads from the socket.
  (let [inspected (java.util.concurrent.CountDownLatch. 1)
        registered? (atom true)
        release-monitor! (fn []
                           (when (compare-and-set! registered? true false)
                             (swap! active-query-monitors dec)))]
    (swap! active-query-monitors inc)
    (try
      (let [worker
            (future
              (try
                (.setSoTimeout s 100)
                ;; read-line-bounded has consumed exactly the first protocol line,
                ;; but BufferedReader may already hold read-ahead bytes. Inspect
                ;; that SAME reader before allowing evaluation to begin. ready is
                ;; nonblocking; if true, one byte is conclusive because the wire
                ;; contract permits no second frame or trailing data.
                (when (.ready r)
                  (let [ch (.read r)]
                    (if (= ch -1)
                      (cancel-query! control :client-disconnected)
                      (cancel-query! control :unexpected-client-input))))
                (.countDown inspected)
                (when (nil? @(:cancelled control))
                  (loop []
                    (when-not @(:done control)
                      (let [again? (try
                                     (let [ch (.read r)]
                                       (if (= ch -1)
                                         (cancel-query! control :client-disconnected)
                                         (cancel-query! control :unexpected-client-input))
                                       false)
                                     (catch java.net.SocketTimeoutException _ true))]
                        (when again? (recur))))))
                (catch Throwable _
                  (cancel-query! control :client-disconnected))
                (finally
                  ;; Also releases serve-conn if initial inspection itself failed.
                  (.countDown inspected)
                  (release-monitor!))))]
        ;; Synchronization point: evaluation/response ordering begins only after
        ;; the monitor's initial nonblocking inspection has a definitive result.
        (.await inspected)
        worker)
      (catch Throwable t
        ;; Submission/await failure races safely with the worker's finally: the
        ;; registration is released exactly once, never driven negative.
        (release-monitor!)
        (throw t)))))

(defn serve-conn [^Socket s]
  (try
    (.setSoTimeout s sock-read-timeout-ms)         ; bound idle/slow-loris reads
    (let [r (BufferedReader. (InputStreamReader. (.getInputStream s)))
          w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))]
      (try
        (when-let [line (read-line-bounded r max-line-bytes)]
          (let [req (parse-req line)
                inner (:request req)
                strict-reject
                (when (and require-log-fence?
                           (not= :for-log (:op req)))
                  {:reject ["this coordinator requires a :for-log envelope"]
                   :code :log-fence-required
                   :served-log (served-log-path)})
                fenced-subscribe? (and (= :for-log (:op req))
                                       (map? inner)
                                       (= :subscribe (:op inner)))
                subscribe-req (if fenced-subscribe? inner req)
                fence-reject (when fenced-subscribe?
                               (locking dlock
                                 (log-fence-rejection (:expected-log req))))]
            (cond
              strict-reject
              (try-reply w strict-reject (:fmt req))

              fence-reject
              (try-reply w fence-reject (:fmt req))

              (or fenced-subscribe? (= (:op req) :subscribe))
              (do (swap! subscribers conj {:w w :flt (:filter subscribe-req)})   ; P5: opt-in scoped filter (nil = firehose)
                  ;; A subscriber is long-lived: it RECEIVES pushed events and sends
                  ;; nothing, so the request-path read timeout (5s) must NOT apply or
                  ;; it would drop every idle subscriber. Disable it for this socket;
                  ;; the loop now blocks on read purely to detect disconnect (EOF).
                  ;; The 1 MiB line cap still guards against a flooding subscriber.
                  (.setSoTimeout s 0)
                  (.write w (pr-str (cond-> {:subscribed (current-seq @co)}
                                      fenced-subscribe?
                                      (assoc :log (served-log-path)))))
                  (.newLine w)
                  (.flush w)
                  (loop [] (when (read-line-bounded r max-line-bytes) (recur))))

              :else
              (let [actual (if (= :for-log (:op req)) inner req)
                    query? (query-request? actual)
                    control (when query? (new-query-control actual))
                    _ (when query? (monitor-query-disconnect s r control))]
                (try
                  (let [resp (if control (handle req control) (handle req))]
                    (try-reply w resp (:fmt req)))
                  (finally
                    ;; The monitor owns input after request parse and has a 100ms
                    ;; read timeout. Marking done lets it retire by itself; do not
                    ;; future-cancel it (cancelling before scheduling could skip
                    ;; its finally and leak the monitor count).
                    (when control (reset! (:done control) true))))))))
        ;; StackOverflowError is an Error (not Exception); catching Throwable here
        ;; keeps a deep-nest / malformed line from taking down the conn thread.
        (catch java.net.SocketTimeoutException _ nil)   ; slow client: just close
        (catch Throwable t
          (try-reply w {:error (str "bad request: "
                                    (or (:type (ex-data t)) (.. t getClass getSimpleName)))}))))
    (catch Throwable _ nil)
    (finally (try (.close s) (catch Throwable _ nil)))))

;; bind address: loopback by default (no existing single-machine user is silently
;; exposed); honor FRAM_BIND for gateway-fronted / cross-host deployment. The wire
;; protocol is UNAUTHENTICATED by design (auth is the gateway's job), so a
;; non-loopback bind is only safe behind a network boundary where the ONLY thing
;; that can reach the port is the authenticating gateway / a firewall.
;; Recommended cross-host value: FRAM_BIND=0.0.0.0 — binds ALL interfaces including
;; loopback, so the local CLI + `fram-up` doctor (which connect to 127.0.0.1) keep
;; working, and isolation is enforced by the network rather than by binding one IP.
;; loopback-ness is decided from FRAM_BIND itself (not by introspecting the
;; resolved InetAddress — that reflective call isn't reliable on babashka).
(defn- bind-cfg []
  (let [b (System/getenv "FRAM_BIND")
        loopback? (or (nil? b) (boolean (#{"" "loopback" "127.0.0.1"} b)))]
    {:addr (if loopback?
             (java.net.InetAddress/getLoopbackAddress)
             (java.net.InetAddress/getByName b))
     :loopback? loopback?
     :label (if loopback? "127.0.0.1" b)}))

;; engine-terminated mTLS (JVM-only — this daemon runs on the JVM). When
;; FRAM_TLS_KEYSTORE / FRAM_TLS_TRUSTSTORE / FRAM_TLS_PASS are all set, the listener
;; is an SSLServerSocket that REQUIRES + verifies a client cert (mutual TLS), so a
;; non-loopback link is safe over an untrusted network. Unset => plaintext (default,
;; unchanged). The EDN wire protocol is identical inside the TLS session.
;; password from FRAM_TLS_PASS, or read from FRAM_TLS_PASS_FILE (Docker/k8s secrets
;; mount as files — keeps the secret out of the process environ for multi-tenant hosts).
(defn- tls-pass []
  (or (System/getenv "FRAM_TLS_PASS")
      (when-let [f (System/getenv "FRAM_TLS_PASS_FILE")] (str/trim (slurp f)))))

(defn- tls-cfg []
  (let [ks (System/getenv "FRAM_TLS_KEYSTORE")
        ts (System/getenv "FRAM_TLS_TRUSTSTORE")
        pass (tls-pass)]
    ;; fail CLOSED: a partial config (typo / missing var / secrets-manager glitch)
    ;; must NOT silently serve plaintext where mTLS was intended.
    (when (and (or ks ts pass) (not (and ks ts pass)))
      (binding [*out* *err*]
        (println "FATAL: FRAM_TLS_* partially set — need ALL of FRAM_TLS_KEYSTORE / FRAM_TLS_TRUSTSTORE / FRAM_TLS_PASS (refusing to serve plaintext)"))
      (System/exit 2))
    (when (and ks ts pass) {:ks ks :ts ts :pass pass})))

(defn- load-keystore [path pw]
  (with-open [in (FileInputStream. (str path))]
    (doto (KeyStore/getInstance "PKCS12") (.load in pw))))

(defn- tls-context [{:keys [ks ts pass]}]
  (let [pw (.toCharArray (str pass))
        kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init (load-keystore ks pw) pw))
        tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init (load-keystore ts pw)))]
    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil))))

(defn- listen-socket [addr port tls]
  (if tls
    (doto (.createServerSocket (.getServerSocketFactory (tls-context tls)))
      (.setNeedClientAuth true)                                  ; mutual TLS: require + verify client cert
      (.setEnabledProtocols (into-array String ["TLSv1.3" "TLSv1.2"]))
      (.setReuseAddress true)
      (.bind (InetSocketAddress. addr (int port))))
    (doto (ServerSocket.) (.setReuseAddress true)
          (.bind (InetSocketAddress. addr (int port))))))

(defn serve [port]
  (let [cfg (bind-cfg)
        addr (:addr cfg) loopback? (:loopback? cfg) label (:label cfg)
        tls (tls-cfg)
        ss (listen-socket addr port tls)]
    (when (and (not loopback?) (not tls))
      (binding [*out* *err*]
        (println (str "WARNING: coordinator bound to " label ":" port
                      " (non-loopback, NO TLS). The wire protocol is UNAUTHENTICATED — it MUST sit "
                      "behind the gateway / a firewall, or set FRAM_TLS_* for mutual TLS; never publish this port."))))
    (println (str "reified coordinator listening on " label ":" port
                  (cond tls " (sole writer, mTLS)"
                        loopback? " (sole writer, loopback-only)"
                        :else " (sole writer, behind-gateway)")))
    (maybe-wire-def-check! port)          ; phase 2: wire A2's warm def-level check (FRAM_DEFCHECK=1)
    (loop [] (let [s (.accept ss)] (future (serve-conn s)) (recur)))))

(defn client [port m]
  (with-open [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))
          r (BufferedReader. (InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

;; ---- boot: replay the v2 log (or bootstrap a fresh one) --------------------
(defn boot!
  ([log] (boot! log nil))
  ([log flat]
   (let [log (canonical-path log)
         flat (when flat (canonical-path flat))]
    (when-let [tlog @telemetry-log]
      (reset! telemetry-log (canonical-path tlog)))
    (reset! flat-log flat)
    (let [f (java.io.File. log)]
     (reset! co (if (and (.exists f) (pos? (.length f)))
                  {:store (replay log) :log log :lock (Object.)}
                  (new-coord log))))
    (reset-refers-state!)                 ; S3.3: fresh store -> next materialize is cold
    (index!)
    @co)))

(defn serve-daemon [port log flat]
  (boot! log flat)
  (println (str "reified coordinator: " (count (c/current-facts (:store @co)))
                " live facts from " (:log @co)
                (when @flat-log (str "; flat projection -> " @flat-log))))
  (serve port))

;; ===========================================================================
;; DROP-IN cutover (design B): the flat log stays canonical (no format change);
;; the daemon is a reified-engine FRONT-END over it. Boots by migrating the flat
;; log into the reified store; commits go through the reified coordinator AND
;; append the flat line; external edits (capture/import/set append out-of-band)
;; are absorbed by re-migrating on mtime change. Cardinality is SCHEMA-AS-FACTS:
;; a log-resident `@<pred> cardinality single|multi` fact is authoritative, falling
;; back to fram.kernel (env FRAM_SINGLE_VALUED > transitional list) only for preds the
;; log doesn't declare — the SAME precedence (fact > env > fallback) the CLI fold uses
;; (fram.fold/card-map + fram.kernel/single-eff?), so the daemon and a cold CLI fold of
;; the same log classify a predicate's cardinality IDENTICALLY (finding #23). ref-ness
;; follows the @-prefix convention. A true reversible drop-in for coord.clj: same log,
;; same protocol, reified underneath.
;; ===========================================================================
;; ref-str? — a value is a node LINK iff it's a ref-shaped @-string (see ref-shape?:
;; "@" + ≥1 char + no whitespace). A bare "@" / "@ " literal (a comment lexeme about
;; the `@id` syntax) is an ASSERT, NOT a link — else migration mints a phantom "@"
;; node and render-from-log breaks (string-append on an entity-id). Mirrors kind-of.
(defn- ref-str? [x] (and (string? x) (ref-shape? x)))

;; --- schema-as-facts cardinality: daemon⇄CLI parity (finding #23) -----------
;; The flat log may carry `@<pred> cardinality single|multi` facts. The CLI fold
;; classifies cardinality from them (fram.fold/card-map builds predname->is-single —
;; leading @ stripped, latest-:tx-wins, meta-preds seeded, a retracted declaration
;; falls back — and fram.kernel/single-eff? applies fact > env > fallback). The daemon
;; MUST classify IDENTICALLY or its warm live view diverges from the cold fold. We REUSE
;; those exact CLI functions here (no re-derivation → no drift): `log-card-map` builds
;; the same map from a set of flat lines; classification is ck/single-eff?. `pred-name`
;; strips the leading @ from a `cardinality` fact's subject to recover the pred name.
;; (pred-name is defined up at the F3 schema-write gate — the write path needs it too.)
(defn- log-card-map [lines]
  (fold/card-map (filterv #(and (:l %) (:p %) (:r %) (int? (:tx %))) (vec lines))))
;; predicates the given lines LIVE-declare a cardinality for (subjects of `cardinality`
;; asserts that survive as a live declaration in cmap) — the ones to also materialize as
;; a store cardinality fact if they carry no domain facts, so s/cardinality (the write-
;; path authority) agrees with the CLI map even for a not-yet-used predicate.
(defn- card-only-preds [cmap lines]
  (into #{} (comp (filter #(= "cardinality" (:p %)))
                  (map #(pred-name (:l %)))
                  (filter #(contains? cmap %)))
        lines))

;; boot merge-replay: the whole-log fold reads the UNION of split logs, stable-sorted
;; by :tx (the sole total order). Correctness (constraint 2, byte-identical to a single-
;; log fold of the same records): fold/keyed-latest keeps the MAX-:tx line PER KEY
;; (order-independent), and facts from different logs never share a key ((l,p)[,r]) —
;; different subjects route to different logs — so any interleaving folds identically. The
;; stable sort makes the merge deterministic. telemetry-log nil => returns the coordination
;; log verbatim (byte-identical to pre-split boot).
(defn- read-logs-merged [flat]
  (let [coord (fram.rt/read-log flat)]
    (if-let [tlog @telemetry-log]
      (vec (sort-by #(or (:tx %) 0) (into coord (fram.rt/read-log tlog))))
      coord)))

(defn migrate-flat->co [flat]
  (let [;; drop torn/partial lines BEFORE folding: the live flat log is appended
        ;; without fsync, so a copy/read caught mid-write can yield an assertion
        ;; missing a field — and fold itself calls single? on :p, so the incomplete
        ;; line must be dropped pre-fold. A torn line is an incomplete write that
        ;; must NOT apply (the writer retries).
        raw (read-logs-merged flat)
        ;; max :tx over ALL parsed lines — same set fold/max-tx (doctor's log-v)
        ;; counts, INCLUDING a torn tail (EDN-valid but missing :r). Seeding over
        ;; only the filtered asserts would lag by one when the tail is torn and make
        ;; doctor report STALE; matching fold keeps doctor FRESH.
        flat-max-tx (reduce max 0 (map #(or (:tx %) 0) raw))
        asserts (filter #(and (:l %) (:p %) (:r %)) raw)
        ;; schema-as-facts: predname->is-single from the log's own cardinality facts
        ;; (CLI-parity map). Authoritative over ck/single? at every classification below.
        cmap (log-card-map asserts)
        facts (:facts (fold/fold (vec asserts)))
        by-pred (group-by :p facts)
        st (c/new-store)
        tx (c/begin-tx! st "migrate")]
    (s/setup! st tx)
    (doseq [p (keys by-pred) :when (not (schema-preds p))]   ; skip reserved engine preds (defensive)
      (s/def-predicate! st p (if (ck/single-eff? cmap p) "single" "multi")
                            (if (some #(link-value? p %) (map :r (get by-pred p))) "ref" "literal") tx))
    ;; cardinality-only preds: declared in the log via `@<pred> cardinality X` but with
    ;; NO domain facts (absent from by-pred). Materialize the fact so s/cardinality (the
    ;; write-path authority) matches the CLI map; no value facts ⇒ value_kind "literal".
    (doseq [p (card-only-preds cmap asserts) :when (and (not (schema-preds p)) (not (contains? by-pred p)))]
      (s/def-predicate! st p (if (ck/single-eff? cmap p) "single" "multi") "literal" tx))
    ;; complete the bootstrap SEED (move-B keystone): kernel single-valued preds NOT
    ;; present in the flat log AND not classified by a cardinality fact still get their
    ;; cardinality FACT, so a first runtime write supersedes (not accumulates) — the
    ;; replacement for finding #12's per-write ensure-single pin, now a one-time seed.
    ;; ck/single-valued is read ONCE here; at runtime commit! consults only the fact.
    ;; The `cmap` guard means a log `@p cardinality multi` on a kernel-single pred is NOT
    ;; force-seeded back to single (the fact wins). The loops above already seeded in-log
    ;; + cardinality-only preds, so the guard skips them and preserves their ref/literal kind.
    (doseq [p ck/single-valued :when (and (not (schema-preds p)) (not (contains? cmap p)) (not= "single" (s/cardinality st p)))]
      (s/def-predicate! st p "single" "literal" tx))
    (let [memo (atom {})
          ent! (fn [sid] (or (get @memo sid)
                             (let [id (c/entity! st)] (swap! memo assoc sid id) (s/name! st id sid tx) id)))]
      (doseq [cl facts :when (not (schema-preds (:p cl)))]
        (let [su (ent! (:l cl)) p (:p cl) r (:r cl)]
          (if (link-value? p r) (s/link! st su p (ent! r) tx) (s/assert! st su p r tx)))))
    ;; Seed the seq-space to the flat log's max :tx so (a) :version == the flat
    ;; fold's version (doctor reports FRESH, not STALE), (b) base_version stays
    ;; coherent, and (c) projected flat :tx CONTINUE the flat space (no collision;
    ;; coord.clj can still fold the log on rollback).
    (swap! st assoc :next-seq flat-max-tx)
    (swap! st update :txs assoc tx {:seq flat-max-tx :agent "migrate"})
    ;; :log nil — DROP-IN: the flat log is canonical and is written ONLY by the
    ;; daemon's append-flat!; the reified store must NOT dump v2 :k-records into it.
    {:store st :log nil :lock (Object.)}))

;; ===========================================================================
;; SNAPSHOT / TAIL-FOLD / AS-OF / INCREMENTAL AGGREGATES (thread 019f100f-7fff)
;; ---------------------------------------------------------------------------
;; The flat fold (fram.fold) is KEYED-LATEST-BY-:tx: a single-valued predicate keys
;; on (l,p) [an LWW cell]; a multi on (l,p,r) [the edge]; per key the MAX-:tx line
;; wins and a `retract` whose :tx dominates drops the key. So for ANY key first
;; touched in a tail T past a snapshot at seq N, every tail line has :tx > N >= every
;; snapshot-era line for that key — the tail's max-:tx line for the key dominates the
;; whole history. THEREFORE: per-key keyed-latest over T, applied onto the snapshot
;; store (whose keys T is silent on are left untouched), reconstructs EXACTLY a full
;; fold of the whole log. That is the whole correctness argument for incremental boot
;; + tail-fold reload + as-of; it is GATED at runtime by snapshot-reconcile (diff the
;; incremental store's live name-triples against a from-scratch whole-migrate).
;; This is the fix for the mislabeled "single-writer" bottleneck: the writer's one
;; job (id allocation) STAYS single + serialized; what was O(history) — boot re-fold
;; and the whole-log re-migrate on EVERY out-of-band append — becomes O(delta).
;; ===========================================================================

;; the highest flat :tx the live store reflects, and the flat-log byte length at that
;; point. flat-bytes is a SEEK HINT for the tail read; correctness is the :tx filter
;; (risk guard: "anchor boot on :tx (monotonic), not byte_offset").
(def built-through (atom 0))
(def flat-bytes    (atom 0))
(def telemetry-bytes (atom 0))

(defn- snap-dir [flat] (str flat ".snapshots"))
(defn- snap-image [flat seq] (str (snap-dir flat) "/snap-" seq ".v2log"))
(defn- sidecar-path [flat] (str flat ".snap"))      ; latest-snapshot pointer (O(1) boot hint)

(defn- sha256-file [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [is (java.io.FileInputStream. (str path))]
      (loop [] (let [n (.read is buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn- read-sidecar [flat]
  (let [f (java.io.File. (sidecar-path flat))]
    (when (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))
;; temp-file + ATOMIC_MOVE rename — a concurrent reader/booter sees the OLD file or
;; the NEW one, never a torn write (same-dir rename is atomic on POSIX).
(defn- rename-atomic! [tmp dst]
  (java.nio.file.Files/move
   (.toPath (java.io.File. (str tmp))) (.toPath (java.io.File. (str dst)))
   (into-array java.nio.file.CopyOption
               [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
(defn- write-sidecar! [flat m]
  (let [tmp (str (sidecar-path flat) ".tmp")]
    (spit tmp (pr-str m))
    (rename-atomic! tmp (sidecar-path flat))))

;; ---- snapshot-boot activation + stamps (thread 019f2190, plan b) -----------
;; FRAM_SNAPSHOT_BOOT gates BOTH boot-time consumption of a checkpoint AND the
;; periodic checkpoint writer — default OFF, so landing this on main changes
;; nothing until an owned :7977 bounce exports the flag. An atom (not a bare env
;; read) so in-process tests can flip it without an env round-trip.
(def snapshot-boot-enabled?
  (atom (contains? #{"1" "true" "on"} (str/lower-case (str (System/getenv "FRAM_SNAPSHOT_BOOT"))))))
(def snapshot-interval-ms
  (max 1000 (or (some-> (System/getenv "FRAM_SNAPSHOT_INTERVAL_MS") parse-long) 900000)))  ; 15 min; 1s floor
(def last-boot (atom nil))  ; {:mode :snapshot|:fold :ms n :reason <why fold>} — ops (:status) + tests

;; fold-version fingerprint: sha256 over the sources that DEFINE the folded state —
;; the emitted runtime modules this process actually loads (classpath `out`: fold =
;; keyed-latest semantics, kernel = cardinality vocab, schema/store = store ops, rt =
;; log parsing), plus coord.clj (dump-log!/replay — the image format) and THIS
;; file (migrate-flat->co / apply-tail! / read-log-tail). A checkpoint written by
;; older fold logic self-invalidates. Over-invalidation (any daemon edit) is the
;; safe direction: one whole-log fold on the first post-deploy boot, then the
;; writer re-stamps. nil when a source is unreadable -> never stamped, never
;; validated (fail closed).
(def ^:private fold-fingerprint-files
  ["out/fram/fold.clj" "out/fram/kernel.clj" "out/fram/schema.clj" "out/fram/store.clj"
   "out/fram/rt.clj" "chartroom/src/resolve.clj" "coord.clj" "coord_daemon.clj"])
(defn fold-fingerprint []
  (try
    (let [root (System/getProperty "user.dir")
          hs (mapv (fn [rel]
                     (let [f (java.io.File. ^String root ^String rel)]
                       (if (.exists f) (sha256-file f) (throw (ex-info (str "missing " rel) {})))))
                   fold-fingerprint-files)
          md (java.security.MessageDigest/getInstance "SHA-256")]
      (.update md (.getBytes ^String (str/join "\n" hs) "UTF-8"))
      (apply str (map #(format "%02x" %) (.digest md))))
    (catch Exception _ nil)))

;; log identity: sha256 of the log's FIRST line — survives copies (unlike an inode),
;; changes on a rotated/reset log so a checkpoint of the OLD history can't apply to
;; a NEW one. A compaction that drops the head legitimately changes it: the
;; compactor must re-stamp the sidecar (coord_snapshot_test step E does).
(defn log-identity-of [flat]
  (try
    (with-open [rdr (java.io.BufferedReader.
                     (java.io.InputStreamReader. (java.io.FileInputStream. (str flat)) "UTF-8"))]
      (when-let [l (.readLine rdr)]
        (let [md (java.security.MessageDigest/getInstance "SHA-256")]
          (.update md (.getBytes ^String l "UTF-8"))
          (apply str (map #(format "%02x" %) (.digest md))))))
    (catch Exception _ nil)))

(defn- skip-fully! [^java.io.InputStream is ^long n]
  (loop [left n] (when (pos? left) (let [s (.skip is left)] (if (pos? s) (recur (- left s)) nil)))))

;; flat-log lines (raw maps) with :tx > from-tx, read from byte `from-byte` forward.
;; UTF-8 decode (fact values carry unicode — so NOT RandomAccessFile.readLine, which
;; is ISO-8859-1 and corrupts multibyte). from-byte is a seek hint: too-low only costs
;; extra parsing, a torn first line is dropped, and the :tx filter is the real boundary.
;; Returns {:lines [...] :max-tx n} — :max-tx counts EVERY EDN-parsed line carrying an
;; int :tx > from-tx, INCLUDING a torn tail line (EDN-valid but missing :l/:p/:r, the
;; realistic append-no-fsync condition): fold/max-tx and migrate-flat->co's flat-max-tx
;; seed count such lines, so the incremental boot's version must too or a torn tail
;; makes a snapshot boot report STALE vs the whole-log fold.
;;
;; CORRUPTION POLICY — parity with fram.rt/read-log (thread 019f791c): we split the
;; RAW UTF-8 bytes on 0x0A (never a UTF-8 continuation byte) so byte offsets survive.
;;   * unparseable, no terminating newline (final segment) -> torn tail: recover the
;;     accumulated lines, warn once with the exact byte offset, stop.
;;   * unparseable, newline-terminated -> FAIL CLOSED (path + byte offset), EXCEPT the
;;     ONE tolerated case: the FIRST segment when from-byte > 0, which the seek hint may
;;     split mid-line (a straddle fragment) — that partial is dropped, as before.
;;   * parses -> unchanged :tx filter (an EDN-valid-incomplete torn tail still counts
;;     toward :max-tx but is not applied).
(defn- read-log-tail* [path from-byte from-tx]
  (let [f (java.io.File. (str path))]
    (if-not (and (.exists f) (pos? (.length f)))
      {:lines [] :max-tx (long from-tx)}
      (let [len (.length f) start (long (max 0 (min (long (or from-byte 0)) len)))]
        (with-open [is (java.io.FileInputStream. f)]
          (skip-fully! is start)
          (let [bs (.readAllBytes is) blen (alength bs)]
            (loop [i 0 first? true acc (transient []) mx (long from-tx)]
              (if (>= i blen)
                {:lines (persistent! acc) :max-tx mx}
                (let [nl (loop [j i] (cond (>= j blen) -1
                                           (== (aget bs j) 10) j
                                           :else (recur (inc j))))
                      terminated? (>= nl 0)
                      end (if terminated? nl blen)
                      seg (String. bs i (- end i) java.nio.charset.StandardCharsets/UTF_8)
                      next-i (if terminated? (inc nl) blen)
                      abs (+ start i)]
                  (if (str/blank? seg)
                    (recur next-i false acc mx)
                    (let [parsed (try {:x (edn/read-string seg)} (catch Exception _ nil))]
                      (cond
                        parsed
                        (let [x  (:x parsed)
                              tx (when (and (map? x) (int? (:tx x))) (long (:tx x)))
                              past? (and tx (> tx (long from-tx)))
                              m  (when (and past? (:l x) (:p x) (:r x)) x)]
                          (recur next-i false
                                 (if m (conj! acc m) acc)
                                 (if (and past? (> tx mx)) tx mx)))
                        ;; unparseable torn tail (no terminating newline): recover + warn once.
                        (not terminated?)
                        (let [recovered (persistent! acc)]
                          (fram.rt/warn-torn-tail! path abs (count recovered))
                          {:lines recovered :max-tx mx})
                        ;; seek-straddle first fragment (from-byte landed mid-line): drop it.
                        (and first? (pos? start))
                        (recur next-i false acc mx)
                        ;; unparseable, newline-terminated, real position: fail closed.
                        :else
                        (throw (fram.rt/corrupt-log-ex path abs))))))))))))))
(defn- read-log-tail [path from-byte from-tx]
  (:lines (read-log-tail* path from-byte from-tx)))

(defn- ref-str?* [x] (and (string? x) (ref-shape? x)))

;; keyed-latest over flat lines, mirroring fram.fold/key-of (single -> (l,p); multi ->
;; (l,p,r)); the latest by :tx wins and its :op is carried so a dominating retract
;; removes the key. Re-derived here because fram.fold/keyed-latest is private AND drops
;; retracts (we need them to supersede a snapshot-era fact). `cmap` is the effective
;; cardinality map (schema-as-facts: store facts overlaid with THIS tail's cardinality
;; declarations); ck/single-eff? applies fact > env > fallback exactly as the CLI fold's
;; key-of does, so a pred keys IDENTICALLY warm and cold (finding #23).
(defn- tail-keyed-latest [cmap lines]
  (reduce (fn [m a]
            (let [k (if (ck/single-eff? cmap (:p a)) [(:l a) (:p a)] [(:l a) (:p a) (:r a)])
                  prev (get m k)]
              (if (and prev (>= (long (:tx prev)) (long (:tx a)))) m (assoc m k a))))
          {} lines))

;; apply a flat-log TAIL (lines past from-tx) onto co's store as per-key group-
;; reconciled deltas — the O(delta) materialization step shared by boot / reload /
;; as-of. New predicates are declared (existing cardinality untouched). Then per
;; keyed-latest key: assert/link (single supersedes the cell via s/assert!/s/link!'s
;; replace!; multi adds the edge IFF not already live — idempotent), or retract
;; (mark the matching live fact store-superseded). One migrate-style tx; the seq space
;; is advanced to the tail's max :tx so :version stays == the flat fold version.
;; Mutates (:store co) in place — callers that need atomicity vs lock-free readers
;; clone the store first (see maybe-reload!). Returns co.
(defn- apply-tail! [co lines]
  (let [st (:store co)
        valid (filterv #(and (:l %) (:p %) (:r %) (int? (:tx %)) (not (schema-preds (:p %)))) lines)]
    (when (seq valid)
      (let [tx (c/begin-tx! st "tail")
            by-pred (group-by :p valid)
            ;; EFFECTIVE cardinality at tail time (schema-as-facts): the store's current
            ;; cardinality facts (from the snapshot / whole-migrate) OVERLAID with THIS
            ;; tail's own `@<pred> cardinality X` declarations, which have the higher :tx
            ;; and therefore dominate (invariant: snapshot⇄tail cmap consistency). Built
            ;; from the PRE-tail store, so it is a stable classifier for both the keying
            ;; below and the declaration loop; ck/single-eff? applies fact > env > fallback.
            tcmap (log-card-map lines)                       ; tail-declared cardinality
            card-pid (c/value-id st "cardinality")
            base (reduce (fn [m p]
                           (if (and card-pid (seq (c/by-lp st (c/value! st p) card-pid)))
                             (assoc m p (= "single" (s/cardinality st p))) m))
                         {} (keys by-pred))
            ecmap (merge base tcmap)                          ; tail declarations win
            memo (atom {})
            sub! (fn [sid] (or (get @memo sid)
                               (let [id (or (s/resolve-name st sid)
                                            (let [e (c/entity! st)] (s/name! st e sid tx) e))]
                                 (swap! memo assoc sid id) id)))]
        ;; declare preds new to the store; sync cardinality from the tail's own facts.
        (doseq [p (keys by-pred)]
          (let [pid (c/value! st p)
                want (if (ck/single-eff? ecmap p) "single" "multi")]
            (cond
              ;; new pred: full declaration (cardinality effective, value_kind from data)
              (empty? (c/by-lp st pid card-pid))
              (s/def-predicate! st p want
                                (if (some #(link-value? p %) (map :r (get by-pred p))) "ref" "literal") tx)
              ;; existing pred the tail RE-DECLARES to a different cardinality: update the
              ;; cardinality fact ONLY (assert! supersedes; value_kind untouched — invariant).
              (not= want (s/cardinality st p))
              (s/assert! st pid "cardinality" want tx))))
        ;; cardinality-only tail preds (declared, no domain facts): materialize the fact
        ;; so s/cardinality (write-path authority) agrees; value_kind "literal" (no values).
        (doseq [p (card-only-preds tcmap lines) :when (and (not (schema-preds p)) (not (contains? by-pred p)))]
          (let [want (if (ck/single-eff? ecmap p) "single" "multi")]
            (when (not= want (s/cardinality st p))
              (s/def-predicate! st p want "literal" tx))))
        (doseq [[_ a] (tail-keyed-latest ecmap valid)]
          (let [p (:p a) r (:r a) single? (ck/single-eff? ecmap p)
                su (sub! (:l a)) pid (c/value! st p)
                live (c/by-lp st su pid)]      ; already live-only
            (if (= "retract" (:op a))
              (let [sup (c/value! st "store-supersedes")
                    link? (link-value? p r)
                    rid (when link? (s/resolve-name st r))
                    victims (if single? live
                                (filter #(let [cr (:r (c/fact-of st %))]
                                           (if link? (= rid cr) (= (c/value-id st r) cr))) live))]
                (doseq [old victims] (c/fact! st old sup old tx)))
              (let [link? (link-value? p r)
                    exists? (some #(let [cr (:r (c/fact-of st %))]
                                     (if link? (= (s/resolve-name st r) cr) (= (c/value-id st r) cr))) live)]
                (when-not (and (not single?) exists?)
                  (if link? (s/link! st su p (sub! r) tx) (s/assert! st su p r tx)))))))
        (let [tmax (reduce max 0 (map :tx valid))]
          (swap! st assoc :next-seq tmax)
          (swap! st update :txs assoc tx {:seq tmax :agent "tail"}))))
    co))

;; live name-triples (store-independent: names + literals, not entity ids) — the
;; substrate for the reconcile gate, which compares an incrementally-built store to a
;; from-scratch whole-migrate of the same flat log (they MUST be set-equal).
(defn- live-name-triples [co] (set (reified->facts co)))
(defn snapshot-reconcile
  "Gate: does the live (incrementally-materialized) store equal a from-scratch whole
   migrate of the flat log? {:ok bool :inc n :fresh n}. Hot-path-free (test/admin)."
  ([] (snapshot-reconcile @co @flat-log))
  ([co flat]
   (let [fresh (migrate-flat->co flat)]
     {:ok (= (live-name-triples co) (live-name-triples fresh))
      :inc (count (reified->facts co)) :fresh (count (reified->facts fresh))})))

;; replay the nearest snapshot image, then tail-apply the flat lines past it — the
;; incremental boot. nil if no usable snapshot (caller falls back to whole migrate).
(defn- incremental-boot [snap flat]
  (let [img (java.io.File. (str (:image snap)))]
    (when (and (.exists img) (pos? (.length img))
               ;; hash gate: a torn/edited image is rejected (fall back to whole migrate)
               (or (nil? (:hash snap)) (= (:hash snap) (try (sha256-file (:image snap)) (catch Exception _ nil)))))
      (let [base {:store (replay (:image snap)) :log nil :lock (Object.)}
            {:keys [lines max-tx]} (read-log-tail* flat (:byte_offset snap) (:seq snap))
            through (max (long (:seq snap)) (long max-tx))]
        (apply-tail! base lines)
        ;; a torn tail line is dropped from APPLY but its :tx still counts toward the
        ;; version (see read-log-tail*) — advance :next-seq so current-seq == what a
        ;; whole-log fold of the same bytes reports (doctor FRESH, :facts version equal).
        (swap! (:store base) update :next-seq #(max (long (or % 0)) through))
        {:co base :through through :tail-lines (count lines)}))))

;; checkpoint validation gate — nil when usable, else WHY not (the boot log line +
;; last-boot carry the reason). Every failure falls back to the whole-log fold: a bad
;; checkpoint may cost a slower boot, never wrong state.
(defn- validate-sidecar [snap flat]
  (let [fp (fold-fingerprint)]
    (cond
      (not (map? snap))                          "no checkpoint sidecar (missing/torn/non-EDN)"
      (or (not (int? (:seq snap)))
          (not (int? (:byte_offset snap))))      "sidecar malformed (:seq/:byte_offset not ints)"
      (nil? (:fold_version snap))                "sidecar unstamped (pre-fingerprint format)"
      (nil? fp)                                  "live fold fingerprint uncomputable (fold source unreadable)"
      (not= fp (:fold_version snap))             (str "fold-version mismatch (checkpoint "
                                                      (subs (str (:fold_version snap)) 0 (min 12 (count (str (:fold_version snap)))))
                                                      "… vs live " (subs fp 0 12) "…) — fold logic changed since checkpoint")
      (not= (:log_identity snap)
            (log-identity-of flat))              "log identity mismatch (rotated/reset log)"
      (> (long (:byte_offset snap))
         (.length (java.io.File. (str flat))))   "byte offset past EOF (log truncated below checkpoint)"
      :else nil)))

(defn- boot-flat-canonical! [flat]
  (reset! flat-canonical? true)
  (let [t0   (System/nanoTime)
        snap (read-sidecar flat)
        why  (cond
               (not @snapshot-boot-enabled?) "disabled (FRAM_SNAPSHOT_BOOT unset)"
               ;; a snapshot image covers the unified store but its tail-fold reads ONLY
               ;; the coordination log's byte offset — it cannot see telemetry-log lines
               ;; past the checkpoint. Under log-split routing, force the whole-log MERGE boot
               ;; (both logs). A1 costs no boot speedup anyway (plan §GO); correctness > latency.
               @telemetry-log "disabled (log-split routing active — whole-log merge boot)"
               :else (validate-sidecar snap flat))
        ;; FRAM_MMAP_IMAGE: when the sidecar advertises an :fri image and the flag is
        ;; on, prefer the mmap-cold boot (mmap-boot); it returns :cold when it kept the
        ;; corpus mmap'd (empty tail) or falls into a byte-identical heap fold+tail.
        ;; A missing/torn .fri returns nil -> we retry the v2log incremental-boot, then
        ;; the whole-log fold. Every miss costs a slower boot, never wrong state.
        [ib why] (if why
                   [nil why]
                   (try (if-let [r (or (when (and @mmap-image-enabled? (= :fri (:image_format snap)))
                                         (mmap-boot snap flat))
                                       (incremental-boot snap flat))]
                          [r nil]
                          [nil "snapshot image missing/torn (hash gate)"])
                        (catch Throwable t
                          [nil (str "snapshot replay failed: " (.getMessage t))])))]
    (reset! cold-image nil)                ; drop any prior boot's mmap handle
    (if ib
      (do (reset! co (:co ib)) (reset! built-through (:through ib))
          (when (:cold ib) (reset! cold-image (:cold ib))))
      ;; cold path: no/invalid checkpoint -> the proven whole-log migrate
      (let [c0 (migrate-flat->co flat)]
        (reset! co c0)
        (reset! built-through (or (:next-seq @(:store c0)) 0))))
    (seed-name-seq! (:store @co))          ; Build A: seed the serialized name allocator above the global max
    (reset! flat-log flat)
    (load-log-routing! (:store @co))       ; log split: data-driven telemetry allow-list (@log-routing facts > default)
    (seed-schema-view! flat)               ; F4: log-resident schema-writable facts for the read view
    (reset! flat-mtime (stamp flat))
    (reset! flat-bytes (.length (java.io.File. (str flat))))
    (if-let [tlog @telemetry-log]
      (do (reset! telemetry-mtime (stamp tlog))
          (reset! telemetry-bytes (.length (java.io.File. (str tlog)))))
      (do (reset! telemetry-mtime nil)
          (reset! telemetry-bytes 0)))
    (reset! cache {:index nil :version -1})
    (reset-refers-state!)                  ; S3.3: derived refers_to belong to the OLD store
    ;; mmap-cold + unmaterialized: SKIP the eager warm-cache build (index!) — that
    ;; 5-way String projection is the dominant RSS multiplier this slice defers. The
    ;; first whole-corpus op materializes and warms (ensure-materialized!).
    (when-not @cold-image (index!))
    (let [ms (quot (- (System/nanoTime) t0) 1000000)]
      (reset! last-boot (if ib
                          {:mode :snapshot :ms ms :image (or (:fri_image snap) (:image snap)) :covers (:seq snap)
                           :cold (boolean @cold-image) :tail-lines (:tail-lines ib)}
                          {:mode :fold :ms ms :reason why}))
      (println (str "[fram] boot(flat): "
                    (if ib
                      (str "checkpoint " (:image snap) " (covers seq " (:seq snap) ") + tail of "
                           (:tail-lines ib) " lines")
                      (str "whole-log fold — " why))
                    " in " ms " ms")))
    @co))

(defn boot-flat! [flat]
  (when-let [tlog @telemetry-log]
    (reset! telemetry-log (canonical-path tlog)))
  (boot-flat-canonical! (canonical-path flat)))

;; External flat edits are a two-phase OCC reload.  The writer lock protects only
;; capture/install of immutable identities.  Tail I/O, clone mutation, whole-log
;; fallback, and schema projection all run outside it, so an import or compaction
;; cannot convoy leases/writes/status.  A concurrent coordinator append invalidates
;; the captured Store/stamp and forces a fresh read; once an external change has been
;; observed, retries remain forced even if the appender's mtime callback moved the
;; public stamp, so that callback can never swallow the external tail.
(def ^:private reload-max-attempts 8)

(defn- capture-reload-roots! [force?]
  (locking dlock
    (locking group-io-lock
      (when (and @flat-canonical? @flat-log)
        (let [path @flat-log
              telemetry-path @telemetry-log
              target-stamp (stamp path)
              target-telemetry-stamp (some-> telemetry-path stamp)]
          (when (or force?
                    (not= target-stamp @flat-mtime)
                    (not= target-telemetry-stamp @telemetry-mtime))
            {:path path
             :known-stamp @flat-mtime
             :target-stamp target-stamp
             :target-bytes (.length (java.io.File. (str path)))
             :telemetry-path telemetry-path
             :known-telemetry-stamp @telemetry-mtime
             :target-telemetry-stamp target-telemetry-stamp
             :target-telemetry-bytes
             (when telemetry-path (.length (java.io.File. (str telemetry-path))))
             :from-byte @flat-bytes
             :from-telemetry-byte @telemetry-bytes
             :from-tx @built-through
             :co-version (current-seq @co)
             :store-root @(:store @co)
             :generation @reload-generation}))))))

(defn- build-reload-candidate [roots]
  (swap! active-reloads inc)
  (try
    (let [path (:path roots)
          telemetry-path (:telemetry-path roots)
          schema-root (schema-view-from-flat path)
          candidate
          (if telemetry-path
            ;; The two logs share one tx space but have independent byte offsets.
            ;; An out-of-band edit to either half therefore rebuilds from their
            ;; stable tx-ordered union. Runtime split edits are rare; correctness
            ;; here is worth avoiding two partially-coupled incremental cursors.
            (let [c0 (migrate-flat->co path)
                  through (or (:next-seq @(:store c0)) 0)]
              (if (< (long through) (long (:from-tx roots)))
                {:mode :refused :logmax through}
                {:mode :install :co c0 :schema-root schema-root
                 :through through}))
            (let [tail-result (read-log-tail* path (:from-byte roots) (:from-tx roots))
                  tail (:lines tail-result)
                  tail-max (:max-tx tail-result)]
              (if (> (long tail-max) (long (:from-tx roots)))
                (let [clone {:store (atom (:store-root roots)) :log nil :lock (Object.)}]
                  (apply-tail! clone tail)
                  ;; read-log-tail* deliberately excludes an EDN-valid incomplete row
                  ;; from :lines while retaining its :tx in :max-tx.  The cold fold does
                  ;; the same: no fact applies, but version advances past the torn write.
                  ;; Advancing the private clone also keeps schema-only tails in cheap
                  ;; tail mode instead of forcing a whole-log migration.
                  (swap! (:store clone) assoc :next-seq tail-max)
                  {:mode :install :co clone :schema-root schema-root
                   :through tail-max})
                (let [whole (read-log-tail* path 0 -1)
                      logmax (:max-tx whole)]
                  (if (< logmax (long (:from-tx roots)))
                    {:mode :refused :logmax logmax}
                    (let [c0 (migrate-flat->co path)]
                      {:mode :install :co c0 :schema-root schema-root
                       :through (or (:next-seq @(:store c0)) 0)}))))))]
      ;; External writers do not participate in group-io-lock.  A final stamp check
      ;; is therefore mandatory after every byte of candidate construction.
      (if (and (= (:target-stamp roots) (stamp path))
               (= (:target-telemetry-stamp roots)
                  (some-> telemetry-path stamp)))
        candidate
        {:mode :raced}))
    (finally
      (swap! active-reloads dec))))

(defn- install-reload-candidate! [roots candidate]
  (locking dlock
    (locking group-io-lock
      (let [same-target? (and (= (:path roots) @flat-log)
                              (= (:target-stamp roots) (stamp (:path roots)))
                              (= (:telemetry-path roots) @telemetry-log)
                              (= (:target-telemetry-stamp roots)
                                 (some-> (:telemetry-path roots) stamp)))
            exact-base? (and @flat-canonical?
                             (= (:known-stamp roots) @flat-mtime)
                             (= (:known-telemetry-stamp roots) @telemetry-mtime)
                             (= (:from-byte roots) @flat-bytes)
                             (= (:from-telemetry-byte roots) @telemetry-bytes)
                             (= (:from-tx roots) @built-through)
                             (= (:co-version roots) (current-seq @co))
                             (identical? (:store-root roots) @(:store @co))
                             (= (:generation roots) @reload-generation))]
        (cond
          (= :raced (:mode candidate)) :retry

          (and same-target? exact-base? (= :refused (:mode candidate)))
          (do
            ;; Remember the refused physical state so every later request does not
            ;; rescan it.  The in-memory Store/schema/cache stay on the last good root.
            (reset! flat-mtime (:target-stamp roots))
            (reset! flat-bytes (:target-bytes roots))
            (reset! telemetry-mtime (:target-telemetry-stamp roots))
            (reset! telemetry-bytes (or (:target-telemetry-bytes roots) 0))
            (binding [*out* *err*]
              (println (str "[fram] REFUSED reload: facts.log regressed (max-tx "
                            (:logmax candidate) " < live built-through " (:from-tx roots) ") — a"
                            " revert/truncation, NOT an append. Kept the in-memory"
                            " state; the log file is STALE — restore before restart.")))
            :refused)

          (and same-target? exact-base? (= :install (:mode candidate)))
          (do
            (reset! co (:co candidate))
            (reset! built-through (:through candidate))
            (reset! flat-mtime (:target-stamp roots))
            (reset! flat-bytes (:target-bytes roots))
            (reset! telemetry-mtime (:target-telemetry-stamp roots))
            (reset! telemetry-bytes (or (:target-telemetry-bytes roots) 0))
            (reset! schema-view (:schema-root candidate))
            (reset! cache {:index nil :version -1})
            (reset! facts-wire-cache {:version -1 :triples nil})
            (reset-refers-state!)
            (swap! reload-generation inc)
            :installed)

          ;; Another reloader installed this exact physical target.  Its generation
          ;; is proof of convergence; do not whole-migrate the same corpus again.
          (and same-target?
               (> @reload-generation (:generation roots))
               (= @flat-mtime (:target-stamp roots))
               (= @telemetry-mtime (:target-telemetry-stamp roots)))
          :superseded

          :else :retry)))))

(defn maybe-reload!
  ([] (maybe-reload! false))
  ([nonblocking-if-active?]
   ;; FRAM_MMAP_IMAGE: any corpus-touching op (every non-deferred request routes here
   ;; via prepare-request-reload!) forces the lazy heap fold FIRST — reload cloning,
   ;; tail apply, and whole-log fallback all assume a materialized heap Store. Deferred
   ;; ops (:status/:version/lease) never reach here, so they stay mmap-cold.
   (ensure-materialized!)
   ;; A fact mutation arriving after another request has already
   ;; observed and started rebuilding an external tail must not duplicate that
   ;; O(corpus) work before it can acquire a lease or commit.  It may safely mutate
   ;; the old immutable root: the active reloader's Store/stamp OCC check will fail
   ;; and its forced retry will clone the new root plus the external tail.  Reads
   ;; that require reload freshness pass false and continue to participate.
   (if (and nonblocking-if-active? (pos? @active-reloads))
     :in-progress
     (loop [attempt 0 force? false]
       (if-let [roots (capture-reload-roots! force?)]
         (let [candidate (build-reload-candidate roots)
               result (install-reload-candidate! roots candidate)]
           (if (= :retry result)
             (if (< attempt (dec reload-max-attempts))
               (do (swap! reload-retries inc)
                   (recur (inc attempt) true))
               (throw (ex-info "external log kept changing during reload"
                               {:type :reload-raced :attempts reload-max-attempts
                                :path (:path roots)})))
             result))
         :unchanged)))))

;; ---- snapshot WRITER: a thin wrapper over dump-log! + @snapshot:<seq> facts ------
;; dump-log! writes the live store as a v2 image the EXISTING replay consumes (reuse,
;; not new fold code). covers_through = the live seq at dump time; byte_offset = the
;; flat-log length at dump time (= tail start). The metadata becomes FACTS so "latest
;; snapshot" / "which covers seq N" / "GC candidates" are queries; a tiny sidecar
;; mirrors the latest pointer for O(1) boot discovery (facts are the source of truth).
;; Snapshot is view-relative: of_view @view:main. The @snapshot:<seq> facts land in
;; the tail (tx > covers_through) and are harmlessly re-applied on the next boot.
(defn write-snapshot! [co flat]
  (locking dlock
    (let [st (:store co)
          sq (current-seq co)
          _ (.mkdirs (java.io.File. (snap-dir flat)))
          image (snap-image flat sq)
          tmp (str image ".tmp")
          byteoff (.length (java.io.File. (str flat)))
          _ (dump-log! st tmp)
          h (sha256-file tmp)
          _ (rename-atomic! tmp image)     ; the image appears WHOLE or not at all
          ccount (count (c/current-facts st))
          subj (str "@snapshot:" sq)]
      (doseq [[p v] [["covers_through" (str sq)] ["byte_offset" (str byteoff)]
                     ["fact_count" (str ccount)] ["image_path" image]
                     ["snapshot_hash" h] ["of_view" "@view:main"]]]
        (do-assert subj p v nil))
      ;; log identity read AFTER the @snapshot:* appends: on a previously-EMPTY log the
      ;; first line only exists now, and boot validates against the log as it will read it.
      ;; GROUP COMMIT: the appends above may still be queued (deferred tickets when this
      ;; runs under a socket request); the barrier makes them durable BEFORE the identity
      ;; read, or log-identity-of could see an empty/stale file and stamp a nil identity
      ;; (boot would then fail closed to a whole fold — safe, but silently slower).
      (durable-barrier!)
      ;; FRAM_MMAP_IMAGE: emit the columnar .fri alongside the v2log image (never on
      ;; the commit/ack path — this is the checkpoint writer). The sidecar gains
      ;; :image_format :fri + per-segment sha256 so boot's hash gate rejects a torn
      ;; segment -> full-log fold. The v2log image + its :hash stay for the flag-off
      ;; boot path (unchanged) and as the fallback when a segment hash fails.
      ;; The .fri is dumped HERE — after the @snapshot:* appends + barrier — so it
      ;; COVERS its own metadata and boot sees an EMPTY tail (the mmap-cold path).
      ;; :fri_covers/:fri_byte_offset (post-append) drive mmap-boot's tail read; the
      ;; v2log image keeps the pre-append :seq/:byte_offset for the flag-off path.
      (let [fri-meta (when @mmap-image-enabled? (write-fri-snapshot! st flat (current-seq co)))
            fri-byteoff (when fri-meta (.length (java.io.File. (str flat))))]
        (write-sidecar! flat (cond-> {:seq sq :image image :byte_offset byteoff :fact_count ccount :hash h
                                      :fold_version (fold-fingerprint) :log_identity (log-identity-of flat)
                                      :written_at (fram.rt/now-iso)}
                               fri-meta (assoc :image_format :fri
                                               :fri_image (:image fri-meta)
                                               :fri_segments (:segments fri-meta)
                                               :fri_covers (:covers_seq fri-meta)
                                               :fri_byte_offset fri-byteoff))))
      ;; the snapshot's own @snapshot:<seq> facts were appended inline (do-assert),
      ;; so the LIVE store already reflects them: advance built-through past them and
      ;; re-stamp, so a following reload tail-reads only genuinely-new appends.
      (reset! built-through (current-seq co))
      (reset! flat-mtime (stamp flat))
      (reset! flat-bytes (.length (java.io.File. (str flat))))
      {:ok sq :image image :byte_offset byteoff :fact_count ccount :hash h})))

;; ============================================================================
;; FRAM_MMAP_IMAGE (thread 019f82d9): checkpoint .fri emit, mmap-cold boot, the
;; lazy heap-materialize seam, and the mmap-cold read primitives / parity gate.
;; ============================================================================
(defn- fri-image-path [flat sq] (str (snap-dir flat) "/snap-" sq ".fri"))

;; checkpoint-side .fri writer. Serializes the live Store VALUE into the columnar
;; image (fri/write-fri! does temp+fsync+ATOMIC_MOVE). Off the commit/ack path.
(defn write-fri-snapshot! [st flat sq]
  (let [path (fri-image-path flat sq)
        r (fri/write-fri! @st path :fold-fingerprint (fold-fingerprint))]
    (assoc r :image path)))

;; boot-side: mmap the .fri, gate every segment's sha, then EITHER keep the corpus
;; mmap'd (empty post-checkpoint tail -> the RSS-win path, heap holds only a seeded
;; tail Store) OR — when a tail exists — fold cold into heap and apply the tail via
;; the existing seam, byte-identical to the v2log incremental-boot. Returns the
;; boot-flat shape {:co :through :tail-lines :cold img|nil} or nil to fall through.
(defn- seeded-tail-store [img]
  (let [st (c/new-store)]
    (swap! st assoc :next-id (fri/next-id img) :next-seq (fri/covers-seq img)
           :supersedes-pred (fri/supersedes-pred img))
    st))

(defn mmap-boot [snap flat]
  (let [fri-path (:fri_image snap)
        f (and fri-path (java.io.File. (str fri-path)))]
    (when (and f (.exists f) (pos? (.length f)))
      (let [img (fri/open-fri fri-path)]
        (if-not (fri/verify-segments? img (:fri_segments snap))
          (do (fri/close-fri! img) nil)     ; torn segment -> caller falls back
          ;; the .fri covers POST-@snapshot-metadata state, so tail-read from the
          ;; fri-specific covers/offset (empty right after a checkpoint = cold path).
          (let [fcov (or (:fri_covers snap) (:seq snap))
                foff (or (:fri_byte_offset snap) (:byte_offset snap))
                {:keys [lines max-tx]} (read-log-tail* flat foff fcov)
                through (max (long fcov) (long max-tx))
                real-tail (filterv #(and (:l %) (:p %) (:r %) (int? (:tx %))) lines)]
            (if (seq real-tail)
              ;; NON-EMPTY tail: heap-fold cold + apply tail (== v2log incremental boot).
              (let [st (c/new-store)]
                (c/load-store! st (fri/cold->dump img))
                (let [base {:store st :log nil :lock (Object.)}]
                  (apply-tail! base lines)
                  (swap! (:store base) update :next-seq #(max (long (or % 0)) through))
                  (fri/close-fri! img)
                  {:co base :through through :tail-lines (count lines) :cold nil}))
              ;; EMPTY tail: keep cold mmap'd; heap = seeded (empty) tail Store.
              {:co {:store (seeded-tail-store img) :log nil :lock (Object.)}
               :through through :tail-lines 0 :cold img})))))))

;; the lazy heap fold — first whole-corpus op (any non-deferred request; see
;; maybe-reload!) reconstructs the exact heap Store cold->dump == a v2log replay would,
;; then warms/re-seeds as a normal boot tail. Idempotent + no-op when already
;; materialized (cold-image nil) or flag-off. Under dlock: readers/writers capture
;; roots under the same lock, so the atomic :store swap is a clean generation flip.
(defn ensure-materialized! []
  (when @cold-image
    (locking dlock
      (when-let [img @cold-image]
        (let [st (c/new-store)]
          (c/load-store! st (fri/cold->dump img))
          (reset! co (assoc @co :store st))
          (seed-name-seq! st)
          (reset! cache {:index nil :version -1})
          (reset! facts-wire-cache {:version -1 :triples nil})
          (reset-refers-state!)
          (reset! cold-image nil)
          (fri/close-fri! img)            ; frees the fd; buffers evict on GC (unmap caveat)
          (reset! last-boot (assoc @last-boot :materialized true :cold false)))))))

;; :status live-fact count WITHOUT materializing: cold live = nfacts - superseded
;; (both counted in the footer; every superseded cid is a fact cid) + the (empty)
;; tail store's live facts. Falls to the plain store count once materialized/flag-off.
(defn hybrid-fact-count []
  (if-let [img @cold-image]
    (+ (- (fri/nfacts img) (long (get-in img [:footer :counts :superseded])))
       (count (c/current-facts (:store @co))))
    (count (c/current-facts (:store @co)))))

;; mmap-served LOCAL read primitives (thread bar 3 latency scenario). Resolve a
;; wire-shaped (subject-name, predicate-name) to cold ids and probe the mmap postings
;; — the by-l / by-lp path served WITHOUT a heap fold. nil when not booted mmap-cold.
(defn cold-served? [] (some? @cold-image))
(defn cold-by-lp [subj-name pred-name]      ; -> live cids on (subject,pred), or nil
  (when-let [img @cold-image]
    (let [lid (fri/resolve-name img subj-name) pid (fri/pred-id img pred-name)]
      (when (and lid pid) (fri/by-lp img lid pid)))))
(defn cold-by-l [subj-name]                  ; -> all live cids on subject, or nil
  (when-let [img @cold-image]
    (when-let [lid (fri/resolve-name img subj-name)] (fri/by-l img lid))))
(defn cold-render [cid]                       ; cid -> [subj-name pred-str r-rendered]
  (when-let [img @cold-image] (fri/render img cid)))
;; the mmap-served GROUP read: (subject,pred) -> rendered triples, direct from ordinals
;; (no ord->cid->ord round-trip) — the fast local-read path.
(defn cold-lp-render [subj-name pred-name]
  (when-let [img @cold-image] (fri/render-lp img subj-name pred-name)))

;; parity gate (bar 2): the mmap projection == a from-scratch whole-log fold. Reads
;; the cold columns straight (no materialize) into the reified->facts name-triple shape
;; (schema/resolve/read-hidden preds hidden identically) and set-compares to a fresh
;; migrate. Meaningful while booted mmap-cold (empty tail); once a tail forces a heap
;; fold, snapshot-reconcile is the (byte-identical) authority.
(defn mmap-reconcile
  ([] (mmap-reconcile @flat-log))
  ([flat]
   (if-let [img @cold-image]
     (let [pred-hidden? (fn [p] (or (schema-preds p) (resolve-preds p) (read-hidden-preds p)))
           cold (fri/cold-name-triples img pred-hidden? (constantly false))
           fresh (set (reified->facts (migrate-flat->co flat)))
           fresh* (set (map (fn [f] [(:l f) (:p f) (:r f)]) fresh))]
       {:ok (= cold fresh*) :cold (count cold) :fresh (count fresh*)})
     {:ok true :cold 0 :fresh 0 :note "not booted mmap-cold (nothing to project)"})))

;; ---- as-of: read the graph AS IT STOOD at flat seq N ----------------------------
;; nearest snapshot with covers_through <= N, then tail-apply the lines in
;; (covers_through, N] — exactly a full fold truncated at N (same keyed-latest-by-:tx
;; domination argument, with an UPPER :tx bound). Bounded below by the snapshot floor:
;; an N below the oldest retained snapshot still works via the whole-migrate fallback
;; as long as the flat lines survive (compaction must not drop a line below a live
;; as-of horizon — see the retention sweeper). Admin/debug read, off the hot path.
(defn- fresh-co []
  (let [s (c/new-store) tx (c/begin-tx! s "asof-setup")] (s/setup! s tx) {:store s :log nil :lock (Object.)}))

;; @snapshot:<seq> metadata, read off the live store (post-boot the facts ARE the
;; registry — "which snapshot covers seq N" is a query, decision: snapshots are facts).
(defn- snapshot-entries [co]
  (let [st (:store co) cp (c/value-id st "covers_through")]
    (if-not cp []
      (vec (for [cid (c/by-p st cp)
                 :let [subj (:l (c/fact-of st cid))
                       g (fn [p] (let [v (s/lookup st subj p)] v))]]
             {:subject (s/name-of st subj)
              :covers_through (try (parse-long (str (g "covers_through"))) (catch Exception _ nil))
              :byte_offset    (try (parse-long (str (g "byte_offset"))) (catch Exception _ nil))
              :image          (g "image_path")
              :hash           (g "snapshot_hash")})))))

(defn materialize-as-of [flat n]
  ;; FAIL-CLOSED under log-split routing: this reads ONLY the coordination `flat` tail
  ;; (read-log-tail flat …) and telemetry has no snapshots, so an as-of over a routed
  ;; store would silently omit telemetry-log facts. Dead today (no callers); guard now
  ;; so it can't be wired into a wrong answer. To support as-of under routing, teach it to
  ;; merge-read the telemetry tail (read-logs-merged-style) truncated at :tx <= n.
  (when @telemetry-log
    (throw (ex-info "materialize-as-of is not log-split-aware under FRAM_TELEMETRY_LOG routing" {:flat flat :n n})))
  (let [n (long n)
        cand (->> (snapshot-entries @co)
                  (filter #(and (:covers_through %) (<= (long (:covers_through %)) n)
                                (:image %) (.exists (java.io.File. (str (:image %))))))
                  (sort-by :covers_through))
        best (last cand)
        usable? (and best (or (nil? (:hash best))
                              (= (:hash best) (try (sha256-file (:image best)) (catch Exception _ nil)))))]
    (if usable?
      (let [base {:store (replay (:image best)) :log nil :lock (Object.)}
            tail (filterv #(<= (long (:tx %)) n)
                          (read-log-tail flat (:byte_offset best) (:covers_through best)))]
        (apply-tail! base tail))
      ;; no usable snapshot at/below N -> fold the flat log truncated at :tx <= N
      (apply-tail! (fresh-co) (filterv #(<= (long (:tx %)) n) (read-log-tail flat 0 -1))))))

;; ---- periodic checkpoint writer (flag-gated with boot; thread 019f2190 plan b) ----
;; Every snapshot-interval-ms, if commits advanced past the last checkpoint, write one
;; (write-snapshot! serializes under dlock). Also fires once right after boot on its
;; own thread — after a fold boot that restamps a fresh checkpoint without delaying
;; time-to-serve — and once from a clean-shutdown hook, so the next boot's tail is
;; as small as the shutdown was clean. Failures log and never take the daemon down.
(def ^:private last-snapshot-seq (atom -1))
(defn- snapshot-if-dirty! [why]
  (try
    (when (and @flat-log (> (long (current-seq @co)) (long @last-snapshot-seq)))
      (let [r (write-snapshot! @co @flat-log)]
        ;; dirtiness is measured PAST the checkpoint's own @snapshot:* metadata
        ;; appends (current-seq, not the dump seq :ok) — else every checkpoint
        ;; re-dirties the log with its own bookkeeping and every boot/interval
        ;; rewrites a whole image to cover 6 metadata facts.
        (reset! last-snapshot-seq (long (current-seq @co)))
        (println (str "[fram] checkpoint (" why "): seq " (:ok r) " -> " (:image r)))))
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "[fram] checkpoint (" why ") FAILED: " (.getMessage t)))))))
(defn start-snapshot-writer! []
  ;; nil? @telemetry-log: don't write checkpoints under log-split routing — boot-flat!
  ;; forces the whole-log merge (snapshot fast-path disabled), so images would only
  ;; accumulate unread. Per-log snapshots are a later enhancement (plan Lane 1 note).
  (when (and @snapshot-boot-enabled? @flat-log (nil? @telemetry-log))
    ;; booted FROM a checkpoint -> the state through the current seq is exactly
    ;; what the next boot reconstructs from image+tail; only NEW commits warrant
    ;; the next checkpoint. (A fold boot leaves the seed at -1 so the post-boot
    ;; pass bootstraps the first checkpoint immediately — the activation path.)
    (when (= :snapshot (:mode @last-boot))
      (reset! last-snapshot-seq (long (current-seq @co))))
    (doto (Thread. (fn []
                     (snapshot-if-dirty! "post-boot")
                     (loop []
                       (Thread/sleep (long snapshot-interval-ms))
                       (snapshot-if-dirty! "interval")
                       (recur))))
      (.setName "fram-snapshot-writer") (.setDaemon true) (.start))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (snapshot-if-dirty! "shutdown"))))))

(defn serve-flat-daemon [port flat]
  ;; Boot owns the corpus EXCLUSIVELY across intent healing, torn-tail repair,
  ;; and the fold. This is stronger than steady-state shared append admission:
  ;; no peer append or generation flip can race between truncating/finalizing an
  ;; unacknowledged tail and observing the repaired bytes. Once serving begins,
  ;; every append re-takes the shared rewrite lock and refuses a non-LF boundary.
  (let [gate (fram.rt/acquire-rewrite-lock! (str flat) false true)]
    (try
      (let [healed (fram.rt/doctor-rewrite-intent! (str flat))]
        (when-not (= :clean (:state healed))
          (println (str "[fram] boot: healed crashed rewrite on " flat
                        " (" (name (:state healed)) ")"))))
      (repair-flat-corpus-tails! flat)
      (boot-flat! flat)
         (finally (fram.rt/close-rewrite-lock! gate))))
  (start-snapshot-writer!)
  (println (str "reified coordinator (drop-in over flat log): "
                (count (c/current-facts (:store @co))) " live facts, canonical=" flat
                (when @snapshot-boot-enabled? " [snapshot-boot ON]")))
  (serve port))

;; ---- adversarial socket test (mirrors coord.clj's run-test) ----------------
(defn run-test [port]
  (spit "/tmp/store-coord-daemon-test.log" "")     ; start clean (boot! replays a non-empty log)
  (boot! "/tmp/store-coord-daemon-test.log")
  (register-pred! @co "owner" "single" "literal")
  (register-pred! @co "title" "single" "literal")
  (register-pred! @co "part_of" "single" "ref")
  (let [server (future (serve port))
        _ (Thread/sleep 400)
        ;; seed thread @T via the socket
        _ (client port {:op :assert :te "@T" :p "title" :r "Race target" :base 0})
        n-clients 10 attempts 5
        racers (doall (for [i (range n-clients)]
                        (future
                          (loop [k 0 commits 0 rejects 0]
                            (if (= k attempts) [commits rejects]
                                (let [v (:version (client port {:op :version}))
                                      resp (client port {:op :assert :te "@T" :p "owner"
                                                         :r (str "owner-c" i "-" k) :base v})]
                                  (recur (inc k) (+ commits (if (:ok resp) 1 0))
                                         (+ rejects (if (:reject resp) 1 0)))))))))
        illegal (future (loop [k 0 ok 0]
                          (if (= k 20) ok
                              (let [r (client port {:op :assert :te "@T" :p "part_of" :r "@T" :base 0})]
                                (recur (inc k) (+ ok (if (:ok r) 1 0)))))))
        rc (map deref racers)
        illegal-ok @illegal
        total-commits (reduce + (map first rc))
        total-rejects (reduce + (map second rc))
        owner-live (count (live-cids-lp @co (s/resolve-name (:store @co) "@T") (c/value-id (:store @co) "owner")))
        status (client port {:op :status})
        rp (replay (:log @co))]
    (future-cancel server)
    (println "\n=== reified coordinator concurrency proof (over the socket) ===")
    (println (format "clients=%d attempts=%d -> commits=%d rejects=%d (contention fired: %s)"
                     n-clients attempts total-commits total-rejects (if (pos? total-rejects) "yes" "no")))
    (let [checks [["illegal (part_of-self) writes that slipped through = 0" (zero? illegal-ok)]
                  ["owner on @T is single-valued -> exactly 1 live" (= 1 owner-live)]
                  ["contention actually fired (some rejects)" (pos? total-rejects)]
                  [":status reports live facts" (pos? (:facts status))]
                  ["v2 log replays to the live view (durable)" (= (live-triples (:store @co)) (live-triples rp))]]
          fails (remove second checks)]
      (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
      (if (empty? fails)
        (do (println "\nStage 7 (daemon): reified coordinator over the socket —" (count checks) "/" (count checks) "PASS")
            (System/exit 0))   ; exit cleanly so the test frees the listener port (don't leak it)
        (do (println "\nStage 7 (daemon):" (count fails) "FAILED") (System/exit 1))))))

;; Boot re-aim: the log split's artifact IS the switch (mirrors bin/north).
;; A caller holding a stale pre-split path — e.g. a systemd unit pinning
;; facts.log in ExecStart — must not fork the lineage: if coordination.log
;; exists beside that exact legacy alias, serve the split pair instead. Rollback
;; (delete coordination.log) disables this automatically. 2026-07-16 incident:
;; north-coord.service respawned onto frozen facts.log and diverged live writes.

(defn- activate-split! [coord]
  (let [coord-file (.getAbsoluteFile (java.io.File. (str coord)))
        expected   (canonical-path (java.io.File. (.getParentFile coord-file) "telemetry.log"))]
    (if-let [configured @telemetry-log]
      (when-not (= expected (canonical-path configured))
        (throw (ex-info "FRAM_TELEMETRY_LOG must be telemetry.log beside coordination.log"
                        {:coordination (canonical-path coord-file)
                         :telemetry (canonical-path configured)
                         :expected expected})))
      (reset! telemetry-log expected))))

(defn- reaim-split [path]
  (let [f  (.getAbsoluteFile (java.io.File. (str path)))
        cl (java.io.File. (.getParentFile f) "coordination.log")]
    (cond
      ;; Starting on the canonical coordination half must still activate the pair.
      (= (.getName f) "coordination.log")
      (do (activate-split! f) (canonical-path f))

      ;; Only the frozen pre-split alias is eligible for automatic re-aiming.
      (and (= (.getName f) "facts.log") (.isFile cl))
      (do (activate-split! cl)
          (println (str "[fram] boot re-aim: " path " -> " (.getPath cl)
                        " (log split active; telemetry: " @telemetry-log ")"))
          (canonical-path cl))

      :else (canonical-path path))))

(let [[cmd p log flat] *command-line-args*]
  (case cmd
    ;; v2-log canonical + optional flat projection (design A)
    "serve"      (serve-daemon (Integer/parseInt (or p "7977"))
                               (or log (str (System/getProperty "user.dir") "/data/facts-v2.log"))
                               flat)
    ;; DROP-IN: flat log canonical, reified engine over it (design B) — the safe
    ;; reversible swap for coord.clj: `serve-flat 7977 <facts.log>`
    "serve-flat" (serve-flat-daemon (Integer/parseInt (or p "7977"))
                                    (reaim-split (or log (str (System/getProperty "user.dir") "/data/facts.log"))))
    "test"       (run-test (Integer/parseInt (or p "7988")))
    nil))
