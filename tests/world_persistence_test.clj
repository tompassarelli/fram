;; world_persistence_test.clj — EXECUTABLE SPECIFICATION for worlds durability:
;; cold restart and torn tail. Thread 019f93bb-37b2-7bb6-8ee0-7f0b5e976260,
;; authority design 019f9358-5617-7db6-8662-8ac7556717e8.
;;
;;   bb -cp out tests/world_persistence_test.clj    # from the repo ROOT
;;
;; THIS SUITE IS EXPECTED TO FAIL TODAY, except for section 0. fram.world is a
;; graph-upstream SEED and coord.clj carries no world persistence layer yet, so
;; world-level bars fail by ABSENCE with a named reason. Section 0 exercises the
;; EXISTING 1c523c5 durability seams the world layer is required to reuse rather
;; than reinvent — those bars PASS today and prove the harness itself is sound,
;; so every other FAIL is the missing kernel, not the test.
;;
;; WHAT MUST HOLD. The graph is the source of truth: world/blob/version/candidate/
;; receipt/head-claim facts live in the existing append-only FRAM log and V1 raw
;; blobs are canonical base64 FACTS, so there is no persistent checkout and no
;; blob filesystem. A head is DERIVED by folding append-only create/fork/promote
;; claims — never a stored status. Therefore:
;;   * only COMPLETE, committed, sealed records replay;
;;   * a crash before a complete promotion record leaves the OLD derived head;
;;   * every truncated, gapped, unsealed or tampered candidate is UNPROMOTABLE;
;;   * a WorldLock hash recomputed after a cold restart — in a FRESH PROCESS —
;;     is byte-identical, because every lock input is a durable content-addressed
;;     fact and no wall clock, pid, nonce or process-local cid enters it.
;;
;; NORMATIVE PERSISTENCE SURFACE (coord.clj, load-file'd into the caller's ns —
;; the same idiom tests/coord_occ_verbs_test.clj uses for commit!/register-pred!):
;;   (world-create!   co agent name version-id)            -> {:ok seq} | {:reject kw}
;;   (world-fork!     co agent new-name version-id)         -> {:ok seq} | {:reject kw}
;;   (world-head      co name)                              -> VersionId | nil  (DERIVED)
;;   (world-blob-put! co agent ^bytes raw)                  -> {:ok blob-id} | {:reject kw}
;;   (world-blob      co blob-id)                           -> ^bytes | nil
;;   (world-version   co version-id)                        -> Version record | nil
;;   (world-manifest  co version-id)                        -> resolved manifest vector
;;   (world-begin!    co agent name expected-head nonce)    -> {:ok candidate-id} | {:reject kw}
;;   (world-append!   co agent candidate-id op)             -> {:ok index} | {:reject kw}
;;   (world-seal!     co agent candidate-id)                -> {:ok version-id} | {:reject kw}
;;   (world-candidate co candidate-id)   -> {:ops [..] :sealed <version-id|nil>} | nil
;;   (world-lock!     co version-id build-spec)             -> {:ok lock-id :lock rec}
;;   (world-build!    co agent lock-id)                     -> {:ok receipt} | {:reject kw}
;;   (world-promote!  co agent name expected-head candidate-id receipt)
;;                                                          -> {:ok version-id} | {:reject kw}
;; Rejection keywords this suite pins: :world-candidate-unsealed,
;; :world-candidate-gapped, :world-candidate-truncated,
;; :world-candidate-digest-mismatch. Every rejection happens BEFORE any append.
;;
;; SCOPE. Receipt/expected-head CAS *validation* is specified in
;; tests/world_promotion_test.clj — here a valid promotion is only the vehicle
;; that produces durable state to restart, and rejection bars assert nothing
;; beyond "unpromotable + zero head movement". Pure kernel semantics are in
;; tests/world_kernel_test.clj. No socket, no daemon, no port is touched.
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.process :as proc]
         '[fram.rt :as rt])
(load-file "coord.clj")   ; new-coord / commit! / replay / assert-flat-append-boundary!

;; ---------------------------------------------------------------------------
;; harness (same shape as tests/torn_tail_test.clj, plus a per-bar reason so an
;; ABSENT fn names itself instead of taking the suite down at load time)
;; ---------------------------------------------------------------------------
(def failures (atom 0))
(def total (atom 0))

(defn check
  ([nm ok?] (check nm ok? nil))
  ([nm ok? why]
   (swap! total inc)
   (println (str "  [" (if ok? "PASS" "FAIL") "] " nm
                 (when (and (not ok?) why) (str "  <- " why))))
   (when-not ok? (swap! failures inc))))

(defmacro bar [label & body]
  `(let [r# (try {:ok (boolean (do ~@body))}
                 (catch Throwable e# {:why (or (ex-message e#) (str e#))}))]
     (check ~label (:ok r#) (:why r#))))

(def kernel
  (try (require 'fram.world) {:ok true}
       (catch Throwable _ {:ok false :why "fram.world is not on the classpath (seed only)"})))
(defn kv [nm]
  (or (when (:ok kernel) (try (ns-resolve 'fram.world (symbol nm)) (catch Throwable _ nil)))
      (throw (ex-info (str "fram.world/" nm " ABSENT"
                           (when-not (:ok kernel) (str " — " (:why kernel)))) {:missing nm}))))
(defn k [nm & args] (apply (kv nm) args))

(defn uv
  "The Var for a world persistence fn defined by coord.clj, or a catchable absence."
  [nm]
  (or (resolve (symbol nm))
      (throw (ex-info (str nm " ABSENT — coord.clj has no world persistence layer yet")
                      {:missing nm}))))
(defn u [nm & args] (apply (uv nm) args))

;; --- byte-level helpers (torn-tail work is BYTES, never chars) --------------
(defn b8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))
(defn read-bytes ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file (str path)))))
(defn flen ^long [path] (.length (io/file (str path))))
(defn log-sha [path] (sha256-hex (read-bytes path)))
(defn slurp8 [path] (String. (read-bytes path) "UTF-8"))
(defn write-raw! [path ^String content append?]
  (with-open [os (java.io.FileOutputStream. (str path) (boolean append?))]
    (.write os (b8 content))
    (.flush os)
    (.force (.getChannel os) true)))
(defn truncate-to! [path n]
  (with-open [raf (java.io.RandomAccessFile. (io/file (str path)) "rw")]
    (.setLength raf (long n))))

(def scratch (str (System/getProperty "java.io.tmpdir")
                  "/fram-world-persistence-" (System/nanoTime)))
(.mkdirs (io/file scratch))
(def copies (atom 0))
(defn copy-log
  "A private copy of a log so byte surgery never disturbs the live one."
  [src tag]
  (let [dst (str scratch "/" tag "-" (swap! copies inc) ".log")]
    (io/copy (io/file (str src)) (io/file dst))
    dst))
(defn reopen
  "Cold restart, exactly the daemon's own idiom (coord_daemon.clj:4530):
   rebuild the store by REPLAYING the log; nothing in-memory carries over."
  [log]
  {:store (replay log) :log log :lock (Object.)})

;; --- fixtures ---------------------------------------------------------------
(def mode "100644")
(def slot-a "src/app/core.bclj")
(def slot-b "src/app/util.bclj")
(def raw-a (b8 "(ns app.core)\n"))
(def raw-b (b8 "(ns app.util)\n"))
(def n1 "0123456789abcdef0123456789abcdef")
(def n2 "fedcba9876543210fedcba9876543210")
(def build-spec
  {:adapter "beagle" :toolchain "sha256:tc" :platform "x86_64-linux"
   :entrypoint "app.core/-main" :purpose "test" :argv []
   :env {} :locale "C" :timezone "UTC" :epoch 0 :random "none" :network "none"})

(println "worlds persistence — executable specification (cold restart + torn tail)")
(println (str "  scratch: " scratch))
(when-not (:ok kernel)
  (println (str "  NOTE: " (:why kernel) "; coord.clj world verbs are also absent"
                " — world bars below FAIL by absence, as expected of a seed.")))

;; ===========================================================================
(println "\n-- 0. the EXISTING durability seams the world layer must reuse (PASS today) --")
;; ===========================================================================
;; Design: \"Reuse the complete-record/torn-tail, candidate seal, fsync
;; acknowledgement, boot replay and OCC machinery already evidenced at 1c523c5.\"
;; These bars pin that machinery's live behaviour, so a later world FAIL cannot
;; be blamed on the harness or on the log format.
(def seam-log (str scratch "/seam.log"))
(def seam
  (delay
    (let [co (new-coord seam-log)]
      (register-pred! co "status" "single" "literal")
      (commit! co "w1" "S1" "status" :assert "open" nil)
      {:co co :len (flen seam-log) :sha (log-sha seam-log)})))

(bar "seam: a committed tx replays after a cold restart"
     (let [_  @seam
           st (replay seam-log)]
       (some? st)))
(bar "seam: replay DROPS a trailing tx with no :commit marker (torn tx)"
     (let [{:keys [len]} @seam
           torn (copy-log seam-log "seam-nocommit")]
       ;; a complete, newline-terminated fact record whose tx never committed
       (write-raw! torn (str (pr-str {:k :fact :cid 999999 :l 1 :p 2 :r 3 :tx 999999}) "\n") true)
       (and (> (flen torn) len)
            (= (live-triples (replay seam-log)) (live-triples (replay torn))))))
(bar "seam: an UNTERMINATED tail is refused as an append boundary before mutation"
     (let [torn (copy-log seam-log "seam-unterminated")]
       (write-raw! torn "{:k :fact :cid 1234 :l 1 :p 2" true)   ; no LF
       (try (assert-flat-append-boundary! torn) false
            (catch clojure.lang.ExceptionInfo e
              (true? (:fram/unterminated-flat-tail (ex-data e)))))))
(bar "seam: an LF-terminated log is an ACCEPTABLE append boundary"
     (do @seam (nil? (assert-flat-append-boundary! seam-log))))
;; the surgical tool the whole \"old head survives\" section rests on: truncating a
;; log back to a byte length recorded BEFORE a write restores that earlier state
;; exactly, which is what a crash mid-append physically looks like.
(bar "seam: truncating to a recorded byte length restores the exact pre-write state"
     (let [{:keys [co len]} @seam
           before (live-triples (replay seam-log))
           _      (commit! co "w1" "S2" "status" :assert "second" nil)
           after  (live-triples (replay seam-log))
           cut    (copy-log seam-log "seam-cut")]
       (truncate-to! cut len)
       (and (< (count before) (count after))
            (= before (live-triples (replay cut))))))
(bar "seam: rt/read-log recovers every prior fact from a torn final line"
     (let [torn (copy-log seam-log "seam-readlog")
           _    (write-raw! torn "{:k :fact :cid 4321 :l 1 :p" true)
           sw   (java.io.StringWriter.)
           v    (binding [*err* sw] (rt/read-log torn))]
       (and (seq v) (str/includes? (str sw) "torn"))))

;; ===========================================================================
(println "\n-- 1. the durable world fixture (create A, blob, candidate, seal, promote) --")
;; ===========================================================================
;; Every later bar restarts or mutilates a COPY of this one log. The fixture also
;; records the exact log length BEFORE the promotion append, which is what makes
;; \"a crash before a complete promotion record leaves the old head\" testable
;; without knowing the record schema.
(def world-log (str scratch "/world.log"))
(def fx
  (delay
    (let [co   (new-coord world-log)
          root (k "version-id" nil [])
          _    (u "world-create!" co "w" "A" root)
          head0 (u "world-head" co "A")
          bid  (:ok (u "world-blob-put!" co "w" raw-a))
          cid  (:ok (u "world-begin!" co "w" "A" head0 n1))
          _    (u "world-append!" co "w" cid (k "put-op" slot-a mode bid))
          vA   (:ok (u "world-seal!" co "w" cid))
          lock (u "world-lock!" co vA build-spec)
          rcpt (:ok (u "world-build!" co "w" (:ok lock)))
          len0 (flen world-log)
          sha0 (log-sha world-log)
          prom (u "world-promote!" co "w" "A" head0 cid rcpt)]
      {:co co :log world-log :root root :head0 head0 :bid bid :cid cid :vA vA
       :lock-id (:ok lock) :receipt rcpt
       :len-before-promote len0 :sha-before-promote sha0
       :head1 (:ok prom)})))

(bar "fixture: create A puts its derived head at the empty root Version"
     (let [f @fx] (= (:root f) (:head0 f))))
(bar "fixture: seal produced a content-addressed VersionId"
     (let [f @fx] (boolean (re-matches #"[0-9a-f]{64}" (str (:vA f))))))
(bar "fixture: the accepted promotion moved the derived head to the sealed Version"
     (let [f @fx] (= (:vA f) (:head1 f) (u "world-head" (:co f) "A"))))
(bar "fixture: the promotion appended bytes (there IS a durable record to tear)"
     (let [f @fx] (> (flen (:log f)) (:len-before-promote f))))

;; ===========================================================================
(println "\n-- 2. cold restart: only complete sealed records replay --")
;; ===========================================================================
(bar "restart: the derived head survives a cold restart (replay, nothing in memory)"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart"))]
       (= (:vA f) (u "world-head" co "A"))))
(bar "restart: restart is idempotent — two independent replays agree"
     (let [f @fx]
       (= (u "world-head" (reopen (copy-log (:log f) "restart-a")) "A")
          (u "world-head" (reopen (copy-log (:log f) "restart-b")) "A"))))
(bar "restart: blob bytes replay byte-identically (base64 facts, not files)"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart-blob"))]
       (java.util.Arrays/equals ^bytes raw-a ^bytes (u "world-blob" co (:bid f)))))
(bar "restart: the sealed Version record replays intact"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart-version"))]
       (= (u "world-version" (:co f) (:vA f)) (u "world-version" co (:vA f)))))
(bar "restart: the resolved manifest is identical across restart"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart-manifest"))]
       (and (= 1 (count (u "world-manifest" co (:vA f))))
            (= (u "world-manifest" (:co f) (:vA f)) (u "world-manifest" co (:vA f))))))
(bar "restart: a sealed candidate is still SEALED to the same Version after restart"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart-candidate"))]
       (= (:vA f) (:sealed (u "world-candidate" co (:cid f))))))
(bar "restart: NO PERSISTENT CHECKOUT — the only files created are log files"
     (let [f     @fx
           names (->> (.listFiles (io/file scratch)) (map #(.getName %)) set)]
       (every? #(str/ends-with? % ".log") names)))
(bar "restart: raw blob bytes are IN THE LOG as canonical base64 (graph is the truth)"
     (let [f @fx]
       (str/includes? (slurp8 (:log f))
                      (.encodeToString (java.util.Base64/getEncoder) ^bytes raw-a))))

;; ===========================================================================
(println "\n-- 3. the lock hash is restart-stable (recomputed in a FRESH PROCESS) --")
;; ===========================================================================
;; \"Restart-stable\" means a different JVM/babashka process, replaying the same
;; bytes, recomputes the identical WorldLockId — the only test that actually
;; excludes wall clock, pid, hashCode and process-local cid from lock inputs.
(def probe-path (str scratch "/lock_probe.clj"))
(def probe-src
  (str "(load-file \"coord.clj\")\n"
       "(let [[log nm spec] *command-line-args*\n"
       "      co {:store (replay log) :log nil :lock (Object.)}\n"
       "      head (world-head co nm)]\n"
       "  (println (:ok (world-lock! co head (clojure.edn/read-string spec)))))\n"))
(spit probe-path probe-src)

(bar "lock: WorldLockId is stable across an in-process cold restart"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart-lock"))]
       (= (:lock-id f) (:ok (u "world-lock!" co (u "world-head" co "A") build-spec)))))
(bar "lock: WorldLockId recomputed in a FRESH bb PROCESS is byte-identical"
     (let [f   @fx
           log (copy-log (:log f) "coldproc")
           r   (proc/shell {:out :string :err :string :continue true
                            :dir (System/getProperty "user.dir")}
                           "bb" "-cp" "out" probe-path log "A" (pr-str build-spec))]
       (and (zero? (:exit r)) (= (:lock-id f) (str/trim (:out r))))))
(bar "lock: the rendered WorldLock contains no timestamp, pid, nonce or cid"
     (let [f   @fx
           txt (k "render-record" (:lock (u "world-lock!" (:co f) (:vA f) build-spec)))]
       (and (not (str/includes? txt n1))
            (not (str/includes? txt (:cid f)))
            (nil? (re-find #"(?i)\bcid\b|:ts\b|\bpid\b|\d{4}-\d{2}-\d{2}T" txt)))))

;; ===========================================================================
(println "\n-- 4. old derived heads survive incomplete writes --")
;; ===========================================================================
(bar "torn: a log truncated to just BEFORE the promotion record replays the OLD head"
     (let [f   @fx
           log (copy-log (:log f) "pre-promote")]
       (truncate-to! log (:len-before-promote f))
       (= (:head0 f) (u "world-head" (reopen log) "A"))))
(bar "torn: that truncation is byte-exact — the bytes equal the pre-promote log"
     (let [f   @fx
           log (copy-log (:log f) "pre-promote-sha")]
       (truncate-to! log (:len-before-promote f))
       (= (:sha-before-promote f) (log-sha log))))
(bar "torn: an UNTERMINATED partial promotion tail leaves the head where it was"
     (let [f   @fx
           log (copy-log (:log f) "partial-promote")]
       (truncate-to! log (:len-before-promote f))
       (write-raw! log (str "{:k :world/head-claim :world \"A\" :version \"" (:vA f)) true)
       (= (:head0 f) (u "world-head" (reopen log) "A"))))
(bar "torn: an unterminated tail also blocks any further append (no concatenation)"
     (let [f   @fx
           log (copy-log (:log f) "partial-append-guard")]
       (truncate-to! log (:len-before-promote f))
       (write-raw! log "{:k :world/head-claim :world \"A\"" true)
       (try (assert-flat-append-boundary! log) false
            (catch clojure.lang.ExceptionInfo e
              (true? (:fram/unterminated-flat-tail (ex-data e)))))))
(bar "torn: dropping the last committed byte-run cannot RESURRECT a newer head"
     (let [f   @fx
           log (copy-log (:log f) "no-resurrect")]
       (truncate-to! log (:len-before-promote f))
       (not= (:vA f) (u "world-head" (reopen log) "A"))))
(bar "torn: replay is READ-ONLY — reopening a torn log does not rewrite its bytes"
     (let [f   @fx
           log (copy-log (:log f) "readonly")
           _   (truncate-to! log (:len-before-promote f))
           before (log-sha log)]
       (u "world-head" (reopen log) "A")
       (= before (log-sha log))))
(bar "torn: an incomplete promotion leaves NO Version claiming to be canonical"
     ;; the head is DERIVED from claims, so a missing promote claim must not leave
     ;; any stored \"canonical\"/\"current\" marker behind that could win a fold
     (let [f   @fx
           log (copy-log (:log f) "no-marker")]
       (truncate-to! log (:len-before-promote f))
       (nil? (re-find #"(?i):world/canonical|:is-head|:current-head" (slurp8 log)))))

;; ===========================================================================
(println "\n-- 5. truncated / gapped / unsealed / tampered candidates are UNPROMOTABLE --")
;; ===========================================================================
;; Each bar asserts BOTH halves of the contract: the promotion is rejected, and
;; the durable head plus the log bytes are UNCHANGED (rejection before append).
(defn unpromotable
  "Reject-and-zero-movement probe. Returns {:reject kw :head-moved? b :bytes-moved? b}."
  [co log name expected-head cid receipt]
  (let [head0 (u "world-head" co name)
        sha0  (log-sha log)
        len0  (flen log)
        r     (u "world-promote!" co "w" name expected-head cid receipt)]
    {:reject (:reject r)
     :ok (:ok r)
     :head-moved? (not= head0 (u "world-head" co name))
     :bytes-moved? (or (not= sha0 (log-sha log)) (not= len0 (flen log)))}))

(def unsealed-fx
  (delay
    (let [f    @fx
          log  (copy-log (:log f) "unsealed")
          co   (reopen log)
          head (u "world-head" co "A")
          bid  (:ok (u "world-blob-put!" co "w" raw-b))
          cid  (:ok (u "world-begin!" co "w" "A" head n2))]
      (u "world-append!" co "w" cid (k "put-op" slot-b mode bid))
      {:co co :log log :cid cid :head head})))

(bar "unsealed: promoting an UNSEALED candidate is rejected :world-candidate-unsealed"
     (let [f @fx
           {:keys [co log cid head]} @unsealed-fx
           r (unpromotable co log "A" head cid (:receipt f))]
       (= :world-candidate-unsealed (:reject r))))
(bar "unsealed: the rejection moved NO head and appended NO bytes"
     (let [f @fx
           {:keys [co log cid head]} @unsealed-fx
           r (unpromotable co log "A" head cid (:receipt f))]
       (and (nil? (:ok r)) (false? (:head-moved? r)) (false? (:bytes-moved? r)))))
(bar "unsealed: only a SEALED candidate can become a Version"
     (let [{:keys [co cid]} @unsealed-fx]
       (nil? (:sealed (u "world-candidate" co cid)))))

(bar "gapped: a candidate missing an interior op record is UNPROMOTABLE"
     ;; Byte surgery: drop the MIDDLE log line naming this candidate, so its op
     ;; indices stop being contiguous. LOAD-BEARING ASSUMPTION, asserted below
     ;; rather than assumed silently — every candidate begin/op/seal record must
     ;; NAME its candidate id, exactly as the existing graph-edit envelope does
     ;; (src/fram/rt.clj: every edit-batch fact row carries :fram-edit-batch).
     ;; Contiguity and hash-checking are impossible without that.
     (let [f    @fx
           log  (copy-log (:log f) "gapped")
           co0  (reopen log)
           head (u "world-head" co0 "A")
           bid  (:ok (u "world-blob-put!" co0 "w" raw-b))
           cid  (:ok (u "world-begin!" co0 "w" "A" head n2))
           _    (u "world-append!" co0 "w" cid (k "put-op" slot-b mode bid))
           _    (u "world-append!" co0 "w" cid (k "put-op" "src/app/x.bclj" mode bid))
           _    (u "world-append!" co0 "w" cid (k "delete-op" slot-b))
           _    (u "world-seal!" co0 "w" cid)
           lines (str/split (slurp8 log) #"\n" -1)
           hits  (vec (keep-indexed (fn [i l] (when (str/includes? l cid) i)) lines))
           _     (when (< (count hits) 3)
                   (throw (ex-info (str "only " (count hits) " log records name this"
                                        " candidate; begin/op/seal records MUST each"
                                        " name their candidate id for contiguity and"
                                        " hash-checking to be possible") {})))
           drop-i (nth hits (quot (count hits) 2))
           kept   (keep-indexed (fn [i l] (when (not= i drop-i) l)) lines)]
       (write-raw! log (str/join "\n" kept) false)
       (let [co (reopen log)
             r  (unpromotable co log "A" head cid (:receipt f))]
         (and (= :world-candidate-gapped (:reject r))
              (false? (:head-moved? r))
              (false? (:bytes-moved? r))))))

(bar "truncated: a candidate whose tail was cut mid-record is UNPROMOTABLE"
     (let [f    @fx
           log  (copy-log (:log f) "truncated")
           co0  (reopen log)
           head (u "world-head" co0 "A")
           bid  (:ok (u "world-blob-put!" co0 "w" raw-b))
           cid  (:ok (u "world-begin!" co0 "w" "A" head n2))
           len0 (flen log)
           _    (u "world-append!" co0 "w" cid (k "put-op" slot-b mode bid))
           _    (u "world-seal!" co0 "w" cid)]
       ;; cut back into the op/seal byte range: the begin record survives, the
       ;; rest does not — exactly a crash mid-candidate.
       (truncate-to! log (+ len0 (quot (- (flen log) len0) 2)))
       (let [co (reopen log)
             r  (unpromotable co log "A" head cid (:receipt f))]
         (and (contains? #{:world-candidate-truncated :world-candidate-unsealed}
                         (:reject r))
              (nil? (:sealed (u "world-candidate" co cid)))
              (false? (:head-moved? r))))))

(bar "tampered: mutating a sealed candidate's op bytes breaks its hash check"
     (let [f    @fx
           log  (copy-log (:log f) "tampered")
           co0  (reopen log)
           head (u "world-head" co0 "A")
           bid  (:ok (u "world-blob-put!" co0 "w" raw-b))
           cid  (:ok (u "world-begin!" co0 "w" "A" head n2))
           _    (u "world-append!" co0 "w" cid (k "put-op" slot-b mode bid))
           _    (u "world-seal!" co0 "w" cid)
           txt  (slurp8 log)]
       ;; same byte length, different content: only a real digest check catches it
       (write-raw! log (str/replace txt slot-b "src/app/uti1.bclj") false)
       (let [co (reopen log)
             r  (unpromotable co log "A" head cid (:receipt f))]
         (and (= :world-candidate-digest-mismatch (:reject r))
              (false? (:head-moved? r))
              (false? (:bytes-moved? r))))))

(bar "unpromotable: after every rejection the head still replays as the ORIGINAL"
     (let [f @fx]
       (= (:vA f) (u "world-head" (reopen (copy-log (:log f) "final-head")) "A"))))
(bar "unpromotable: a candidate that never sealed yields NO Version record"
     (let [{:keys [co cid]} @unsealed-fx]
       (nil? (u "world-version" co (:sealed (u "world-candidate" co cid))))))

;; ---------------------------------------------------------------------------
(let [pass (- @total @failures)]
  (println (str "\nworld-persistence: " pass "/" @total " PASS"))
  (if (zero? @failures)
    (println "world-persistence: ALL BARS PASS")
    (do (println (str "world-persistence: " @failures " FAILED — section 0 pins the"
                      " existing 1c523c5 seams; the rest DEFINE the world durability"
                      " contract that coord.clj + fram.world must still meet."))
        (System/exit 1))))
