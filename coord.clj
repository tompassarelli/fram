;; coord.clj — Stage 6: the coordinator on the REIFIED kernel.
;; ============================================================================
;; Sole writer, serialized (one lock). The flat coord.clj's proven skeleton
;; (locking write-lock, optimistic base_version, rule-check, append+notify),
;; rebuilt over the reified store (fram.store + fram.schema). Full Clojure
;; — direct, mutable access to the store map, no Beagle typing friction.
;;
;; The six concurrency/durability holes the analysis flagged, closed here:
;;   1. base_version  = max tx-seq over the LIVE facts on (l,p); a stale
;;      single-valued write is rejected (lost-update protection).
;;   2. validate-without-mutating — obligations (acyclicity) are a PURE pre-check
;;      over resolved ids, run BEFORE any tx/entity is minted, so a rejected
;;      write leaves ZERO unlogged state (else the live store would diverge from
;;      a replay of the log).
;;   3. single-subject obligations (depends_on/part_of acyclicity) for v1.
;;   4. multi-valued idempotency — reified fact! mints a fresh cid every call,
;;      so without this two identical link!s make duplicate live edges; we no-op
;;      when the live (l,p,r) already exists.
;;   5. atomic v2 log — each committed tx appends its records + a :commit marker,
;;      fsync'd; a torn tx (records without :commit, always trailing under a
;;      single appender) is DROPPED on replay. Durability the bb coord lacked.
;;   6. file-import optimistic concurrency — every write goes through the one
;;      lock; the base_version contract (C5) decides who wins.
;;
;;   bb -cp out coord.clj test
;; ============================================================================
(require '[fram.store :as c] '[fram.schema :as s] '[fram.kernel :as ck]
         '[fram.rt :as rt]     ; vGUARD writer admission (shared rewrite flock)
         '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(defn- store [co] (:store co))

;; --- GROUP COMMIT: the durable-append engine (fsync OUT of the write lock) ---
;; The convoy this kills: every commit used to hold the coordinator lock (and, in
;; the daemon, the global dlock) across its OWN open+write+fsync, so K concurrent
;; writers serialized on the disk flush — measured BEFORE: p50 grew ~7x from K=1
;; to K=16 and throughput plateaued ~700-800 writes/s. Now a commit ENQUEUES its
;; log lines (still inside the lock, so queue order == commit order == log order)
;; and durability is awaited via a TICKET (promise):
;;   * *durable-tickets* UNBOUND (library callers, tests, scripts): the enqueue
;;     awaits its ticket inline — byte-identical semantics to the old direct
;;     write+fsync (durable before the fn returns), just via the appender thread.
;;   * *durable-tickets* BOUND (the daemon binds it per request): the ticket is
;;     collected and awaited AFTER the lock is released — so the lock is held
;;     only for in-memory work, and concurrent writers' appends coalesce.
;; ONE appender thread drains the queue, appends every pending item's lines in
;; enqueue order, fsyncs ONCE per file per batch, then delivers every ticket.
;; Durability contract UNCHANGED: an ack (ticket delivery / fn return) happens
;; only after the fact's bytes are fsynced; an append/fsync failure is delivered
;; as the Throwable and rethrown on the awaiting thread (fail closed). A crash
;; before the fsync loses only UN-acked commits, exactly as before; v2 torn-tx
;; replay and the flat fold's keyed-latest already tolerate a torn tail.
(def ^:dynamic *durable-tickets* nil)   ; nil => inline await; atom => deferred collect
(def group-io-lock (Object.))           ; batch write+fsync+callbacks vs external stat checks
(def ^:private group-q (java.util.concurrent.LinkedBlockingQueue.))
(def ^:private group-appender-started (atom false))

(defn- deliver-all! [items v] (doseq [{:keys [ticket]} items] (deliver ticket v)))

(defn- group-appender-loop []
  (loop []
    (let [fst (.take group-q)
          buf (java.util.ArrayList.)]
      (.add buf fst)
      (.drainTo group-q buf)                     ; the batch = everything pending now
      (let [items (vec buf)]
        ;; group-io-lock makes (write+fsync+on-flushed) atomic w.r.t. the daemon's
        ;; maybe-reload! stamp check, so our own async append is never mistaken
        ;; for an external edit (stamp and file move together).
        (locking group-io-lock
          (doseq [[path pitems] (group-by :path items)]
            (let [real (filter #(seq (:lines %)) pitems)]
              (try
                (when (and path (seq real))
                  ;; vGUARD writer admission (B2 §2): the batch holds the SHARED
                  ;; rewrite lock across open→write→fsync→close, so a generation
                  ;; flip's EXCLUSIVE lock excludes it kernel-arbitrated (no scan,
                  ;; no TOCTOU). A live flip DELAYS the batch — the ack (ticket
                  ;; delivery below) still happens only after the fsync, so no
                  ;; acked write can ever sit outside a flip's read set.
                  (rt/with-append-admission (str path)
                    (fn []
                      (with-open [os (java.io.FileOutputStream. (str path) true)]
                        (doseq [{:keys [lines]} real, ^String ln lines]
                          (.write os (.getBytes ln "UTF-8")))
                        (.flush os)
                        (.force (.getChannel os) true)))))   ; ONE fsync covers the whole batch
                (doseq [{:keys [on-flushed]} pitems :when on-flushed] (on-flushed))
                (deliver-all! pitems :ok)
                (catch Throwable t (deliver-all! pitems t)))))))
      (recur))))

(defn- ensure-group-appender! []
  (when (compare-and-set! group-appender-started false true)
    (doto (Thread. ^Runnable group-appender-loop)
      (.setName "fram-group-appender") (.setDaemon true) (.start))))

(defn await-durable! [ticket]
  (let [r (deref ticket)]
    (when (instance? Throwable r) (throw r))
    r))

;; enqueue `lines` for durable append to `path`. Returns the ticket when deferred
;; (collected into *durable-tickets*); awaits it inline otherwise. on-flushed (may
;; be nil) runs on the appender thread after the batch's fsync, before delivery.
(defn enqueue-durable! [path lines on-flushed]
  (ensure-group-appender!)
  (let [t (promise)]
    (.put group-q {:path path :lines lines :ticket t :on-flushed on-flushed})
    (if *durable-tickets*
      (do (swap! *durable-tickets* conj t) t)
      (await-durable! t))))

;; barrier: returns once every enqueue that happened-before it is on disk (FIFO
;; queue + in-order batches). No-op if nothing was ever enqueued.
(defn durable-barrier! []
  (when @group-appender-started
    (let [t (promise)]
      (.put group-q {:path nil :lines [] :ticket t})
      (await-durable! t))))

;; --- atomic v2 log: a tx's records + :commit, fsync'd (via group commit) -----
(defn- append-tx! [co records]
  (when (:log co)                               ; nil :log = drop-in mode: the flat log is
    ;; the mapv REALIZES the lazy delta-records — keep it INSIDE the (when (:log co))
    ;; guard: in drop-in mode the delta seq must stay unrealized (it walks the store).
    ;; One enqueued item = one tx's records => the tx stays CONTIGUOUS in the log,
    ;; so torn-tx replay semantics (records without :commit are dropped) still hold.
    (enqueue-durable! (str (:log co))
                      (mapv (fn [r] (str (pr-str r) "\n")) records)
                      nil)))

;; the records minted in `store` since id `since` (new values/entities/facts),
;; plus this tx's provenance and the terminating :commit marker.
(defn- delta-records [co since txid]
  (let [m @(store co)]
    (concat
     (for [[id v] (:values m) :when (>= id since)] {:k :value :id id :v v})
     (for [id (keys (:objects m))
           :when (and (>= id since)
                      (not (contains? (:values m) id))
                      (not (contains? (:facts m) id)))]
       {:k :entity :id id})
     (for [[cid mm] (:facts m) :when (>= cid since)]
       {:k :fact :cid cid :l (:l mm) :p (:p mm) :r (:r mm) :tx (get (:tx-of m) cid)})
     [{:k :tx :tx txid :seq (get-in m [:txs txid :seq]) :agent (get-in m [:txs txid :agent])
       :observed (get-in m [:txs txid :observed])          ; causality (thread H): the global seq the writer had SEEN when it decided
       :ts (get-in m [:txs txid :ts])}                     ; wall-clock (display-only): the SAME instant stamped into the store map, so replay recovers the identical :ts pull rendered live. nil for internal txs that never stamped (bootstrap/schema/lease/bump) -> pull omits :ts.
      {:k :commit :tx txid}])))

;; --- reads over the reified store -------------------------------------------
(defn- live-cids-lp [co te pid]
  (let [m @(store co)]
    (remove #(contains? (:superseded m) %) (get (:idx-by-lp m) [te pid]))))
(defn- seq-of [co cid]
  (let [m @(store co)] (get-in m [:txs (get (:tx-of m) cid) :seq] 0)))
(defn base-version [co te pid]
  (reduce max 0 (map #(seq-of co %) (live-cids-lp co te pid))))
(defn current-seq [co]
  ;; O(1): :next-seq is the monotonic seq counter — begin-tx! does (update :next-seq inc) and
  ;; assigns the result as the tx's :seq, so the LAST-assigned seq == the MAX seq == :next-seq.
  ;; The old (reduce max ... (:txs m)) was O(total-commits) and, called ~37x per authoring op,
  ;; was the residual O(total) authoring cost (per-op grew 14->61ms over a 500-def build).
  (or (:next-seq @(store co)) 0))

;; --- coexist-elect: the default read-time election (move-B keystone) ---------
;; Under coexist-elect a live (l,p) group MAY hold >1 coexisting fact: rival writes
;; both LAND (no writer blocks, none is rejected). Choosing the main one is a READ-time
;; decision every reader computes IDENTICALLY with zero coordination — the winner is
;; the EARLIEST fact by the total key [cid, writing-agent]. cids are monotonic under
;; the single allocator, so earliest-cid IS the winner today; `agent` is the documented
;; secondary key that keeps the order total IF cid allocation is ever sharded (the
;; moment that happens, earliest-cid alone stops being a total order — coexist-elect is
;; sound iff exactly one cid allocator). For a cid-ascending live group (the default)
;; this is BYTE-IDENTICAL to (first cids); it diverges only to make the pick total and
;; input-order-independent. The loser sees itself lose on its NEXT read and yields.
;; nil on an empty group. (`view` attaches here when first-class views land — thread E.)
(defn agent-of [co cid]
  (let [m @(store co)] (get-in m [:txs (get (:tx-of m) cid) :agent])))

;; --- causality / as-of (thread H, Part A): the causal stamp ------------------
;; Every coordination write already reports :base = "the version I had observed when
;; I decided" (the daemon/CLI path passes the GLOBAL :version it round-tripped; commit!/
;; retract! used it ONLY for the single-valued staleness reject, then dropped it). We now
;; THREAD that base into the tx record as :observed — one int per tx, recovered through
;; replay exactly like :seq/:agent. This turns happens-before into a recorded fact:
;; "did peer B's fact exist in the view A read before A acted?" == (<= seq(B) observed(A)).
;; observed-of reads it; nil for legacy/non-causal writes -> callers fall back to seq-of
;; (commit order), so the causal election degrades to cid-order, never throws.
;; RISK GUARD: the writer cannot fact to have observed the FUTURE — observed is clamped
;; to the pre-commit current-seq at the write site (a backdated stamp only LOSES elections).
(defn observed-of [co cid]
  (let [m @(store co)] (get-in m [:txs (get (:tx-of m) cid) :observed])))
;; ts-of — the WALL-CLOCK stamp of a fact's asserting tx (thread H, display metadata).
;; Mirrors agent-of/observed-of: reads the tx record's :ts (an ISO-8601 instant string,
;; same format the flat log records, minted once per tx at commit via rt/now-ts). PURELY
;; DISPLAY — pull surfaces it as :ts; it NEVER participates in as-of / live election, which
;; stay seq-addressed. nil for pre-existing v2 txs whose record predates the :ts field (pull
;; omits the key then), so OLD logs replay unchanged — the stamp is strictly additive.
(defn ts-of [co cid]
  (let [m @(store co)] (get-in m [:txs (get (:tx-of m) cid) :ts])))
;; the causal key of a live fact: [observed-or-seq, cid, agent]. observed orders by
;; DECISION time (who saw the empty group first), cid/agent keep it a total order. A LATER
;; commit (higher cid) that DECIDED earlier (lower observed) wins — this is the whole point:
;; election by causal view, not by commit order. Pure fn of recorded facts -> every reader
;; computes it identically with zero coordination.
(defn causal-key [co cid]
  [(or (observed-of co cid) (seq-of co cid)) cid (str (agent-of co cid))])

;; --- as-of: the history fold (thread H, Part B) ------------------------------
;; "What was live AS OF seq S?" A fact is live-as-of-S iff it was BORN at a seq <= S
;; AND no store-supersedes marker for it was committed at a seq <= S. Because retraction is
;; append-only (a marker, never a delete), this is EXACT: a fact later superseded/withdrawn
;; is naturally RE-SEEN at an earlier S — its tombstone hadn't been written yet (acceptance
;; b). Folds the in-store tail, so it is bounded by thread D's snapshot floor (history
;; compacted below a snapshot is gone: as-of before the floor is unavailable, not wrong) —
;; never O(total history) (acceptance f).
(defn- tx-seq-of [m cid] (get-in m [:txs (get (:tx-of m) cid) :seq] 0))
(defn superseded-as-of [co s]
  (let [m @(store co) sup (:supersedes-pred m)]
    (set (for [[cid cl] (:facts m)
               :when (and (= (:p cl) sup) (<= (tx-seq-of m cid) s))]
           (:r cl)))))
(defn live-as-of [co s]
  (let [m @(store co) sup (:supersedes-pred m) gone (superseded-as-of co s)]
    (set (for [[cid cl] (:facts m)
               :when (and (<= (tx-seq-of m cid) s)
                          (not= (:p cl) sup)              ; the markers are bookkeeping, not data
                          (not (contains? gone cid)))]
           cid))))
;; the live cids of ONE (te,pid) group as of S — the as-of twin of live-cids-lp.
(defn live-as-of-lp [co s te pid]
  (let [m @(store co) all (live-as-of co s)]
    (filterv (fn [cid] (let [cl (get (:facts m) cid)] (and (= (:l cl) te) (= (:p cl) pid))))
             all)))

;; --- first-class retraction readers + the add-wins/remove-wins view selector ---
;; withdrawal-of reads the attribution surface OFF a victim cid (the queryable who/when/
;; why retract! stamped). nil when the cid carries no live withdrawn_by tombstone.
(defn- live-r-on [co cid pid]   ; live object-value of one (cid,pid) marker group
  (let [m @(store co)]
    (some (fn [c] (when-not (contains? (:superseded m) c) (:r (get (:facts m) c))))
          (get (:idx-by-lp m) [cid pid]))))
(defn withdrawal-of [co cid]
  (let [m  @(store co)
        wb (c/value-id (store co) "withdrawn_by")]
    (when (and wb (live-r-on co cid wb))
      {:by     (get (:values m) (live-r-on co cid wb))
       :at     (when-let [a (live-r-on co cid (c/value-id (store co) "withdrawn_at"))]     (get (:values m) a))
       :reason (when-let [r (live-r-on co cid (c/value-id (store co) "withdrawn_reason"))] (get (:values m) r))})))
(defn withdrawn? [co cid] (boolean (withdrawal-of co cid)))

;; live-members — the multi-valued live group on (te,pid) UNDER A WITHDRAWAL POLICY. The
;; policy is a VIEW choice (thread H, Part D), not a kernel hardcode:
;;   :remove-wins (DEFAULT) — withdrawn members drop. Byte-identical to live-cids-lp (the
;;     store-supersedes marker already excludes them); this is what every existing reader sees.
;;   :add-wins — a member superseded ONLY by a WITHDRAWAL (carries a withdrawn_by tombstone)
;;     RESURRECTS; a genuine OVERWRITE (superseded with no withdrawal tag) still wins. So an
;;     add-wins view re-sees a cancellation while remove-wins hides it — same log, two views.
;; The discriminator is `withdrawn?` (overwrite victims have no tombstone), so the two
;; policies are pure read-time derivations over the one append-only log.
(defn live-members
  ([co te pid] (live-members co te pid :remove-wins))
  ([co te pid policy]
   (let [m    @(store co)
         live (live-cids-lp co te pid)]
     (if (= policy :add-wins)
       (let [resurrected (filter (fn [cid] (and (contains? (:superseded m) cid) (withdrawn? co cid)))
                                 (get (:idx-by-lp m) [te pid]))]
         (vec (distinct (concat live resurrected))))
       (vec live)))))

;; --- views-as-facts (thread E): per-branch isolation over the same log ------
;; A VIEW is a first-class subject; (view selects @cid) facts are its OVERLAY —
;; the cids it treats as facts. The object IS a fact id: cids live in the same
;; flat content-interned id-space, so a fact is itself addressable (the most
;; store-native of VIEWS_AND_BRANCHES §8's three encodings). view-selects returns
;; the live overlay; nil when the view subject or `selects` predicate was never
;; minted (an unknown view selects nothing -> it inherits main).
(defn view-selects [co view]
  (let [m   @(store co)
        ve  (s/resolve-name (store co) view)
        sel (c/value-id (store co) "selects")]
    (when (and ve sel)
      (set (keep #(:r (get (:facts m) %))
                 (remove #(contains? (:superseded m) %)
                         (get (:idx-by-lp m) [ve sel])))))))

;; elect — the read-time election, now VIEW-RELATIVE (thread E generalizes move-B's
;; default-main `elect` to `elect(view, cids)`; `(first cids)`'s descendant gains a view):
;;   * 2-arity / view=nil / "main": the privileged DEFAULT view — elect over the WHOLE
;;     live group by [cid, agent]. BYTE-IDENTICAL to move-B (branch overlays never touch
;;     the bare group, so main is isolated from every branch's writes).
;;   * 3-arity named view V: PER-BRANCH ISOLATION — restrict the group to the cids V
;;     `selects`, then elect among those. A branch sees ONLY its own selected rival on a
;;     contended (s,p); sibling branches' (and main's bare) rivals are invisible to it.
;;   * inherit-the-base: where V selects NONE of THIS group (silent on this (s,p)), V
;;     falls back to the default election over the whole group — "one head + named
;;     overlays" (VIEWS §8): a view is main plus only the facts it overrides.
(defn elect
  ([co cids] (elect co nil cids))
  ([co view cids]
   (when (seq cids)
     (let [sel     (when view (view-selects co view))
           in-view (when (seq sel) (filterv sel cids))
           pool    (if (seq in-view) in-view cids)]
       (first (sort-by (fn [cid] [cid (str (agent-of co cid))]) pool))))))

;; elect-causal — the CAUSAL election policy (thread H, Part C): same view-relative
;; pool as `elect`, but ordered by the CAUSAL key [observed, cid, agent] instead of
;; [cid, agent]. So of a contended live (l,p) group, the winner is the member whose
;; writer DECIDED earliest (saw the empty/oldest group), tie-broken by commit order
;; then agent. This is what lets rival drivers/roles COEXIST and resolve by "who had
;; the earlier causal view" rather than "who happened to commit first" — both readers
;; agree, nothing blocks. Degrades to `elect` when no :observed stamps exist (legacy
;; facts fall back to seq-of via causal-key), so it is a strict refinement, never a
;; regression. nil on an empty group.
(defn elect-causal
  ([co cids] (elect-causal co nil cids))
  ([co view cids]
   (when (seq cids)
     (let [sel     (when view (view-selects co view))
           in-view (when (seq sel) (filterv sel cids))
           pool    (if (seq in-view) in-view cids)]
       (first (sort-by (fn [cid] (causal-key co cid)) pool))))))

(defn- ent! [co tx nm]
  (or (s/resolve-name (store co) nm)
      (let [e (c/entity! (store co))] (s/name! (store co) e nm tx) e)))

;; Bootstrap SEED (move-B keystone): the kernel single-valued LIST, read ONCE at
;; coord creation and turned into per-predicate `cardinality` FACTS. After this the
;; FACT is the SOLE runtime authority for single-ness — commit!/retract! consult
;; only (s/cardinality …), never ck/single? (the old per-write ensure-single pin +
;; the L128/L167 OR-arm are gone). This is the replacement for finding #12's
;; "infer-single-on-first-write": seeding the WHOLE list up front (even predicates
;; never yet written) means there is no "first runtime write of an unseeded single
;; predicate" case — strictly stronger than the old per-write pin. An unseeded
;; predicate defaults to "multi" == coexist-elect, which is now the intended default.
;; Idempotent: only seeds a predicate the store doesn't already record as single
;; (so setup!'s name=single and any prior def-predicate! ref-kind are untouched).
(defn- seed-kernel-cardinality! [st tx]
  (doseq [p ck/single-valued :when (not= "single" (s/cardinality st p))]
    (s/def-predicate! st p "single" "literal" tx)))

;; --- obligation: depends_on/part_of acyclicity (pure, over resolved ids) ----
(defn- succ [co pid x]
  (let [m @(store co)]
    (map #(:r (get (:facts m) %))
         (remove #(contains? (:superseded m) %) (get (:idx-by-lp m) [x pid])))))
;; reachability over the reified store — routed through the kernel's ONE verified
;; traversal (ck/reachable-from?) instead of a second hand-rolled DFS. The store
;; supplies `succ`; the algorithm (and its correctness) lives once, in Beagle.
(defn- reaches? [co pid from to]
  (ck/reachable-from? (fn [x] (succ co pid x)) [from] to))

;; --- bootstrap a coordinator (multi-store: one engine, many coordinators) ---
(defn new-coord [log-path]
  (spit log-path "")
  (let [st (c/new-store)
        tx0 (c/begin-tx! st "bootstrap")
        co {:store st :log log-path :lock (Object.)}]
    (s/setup! st tx0)
    (seed-kernel-cardinality! st tx0)            ; demote ck/single-valued to one-time cardinality FACTS
    (append-tx! co (delta-records co 0 tx0))     ; the bootstrap is the first committed tx
    co))

;; register a domain predicate's metadata (its own committed tx)
(defn register-pred! [co pname card kind]
  (locking (:lock co)
    (let [since (:next-id @(store co))
          tx (c/begin-tx! (store co) "schema")]
      (s/def-predicate! (store co) pname card kind tx)
      (append-tx! co (delta-records co since tx))
      pname)))

;; --- the sole writer --------------------------------------------------------
;; kind = :assert (literal value) | :link (ref to an entity by name).  Retract is
;; a separate entry point (retract!) since it removes rather than supersedes-by-add.
(defn commit! [co agent te-name pred kind r-spec base]
  (locking (:lock co)
    (let [pid    (c/value-id (store co) pred)
          te0    (s/resolve-name (store co) te-name)
          tgt0   (when (= kind :link) (s/resolve-name (store co) r-spec))
          vid    (when (= kind :assert) (c/value-id (store co) r-spec))
          ;; single-ness from the cardinality FACT ALONE (move-B keystone): the
          ;; ck/single? kernel-list OR-arm is gone — the fact, seeded once at boot,
          ;; is the sole runtime authority. No fact => "multi" => coexist-elect.
          single (= "single" (s/cardinality (store co) pred))
          bv     (if (and te0 pid) (base-version co te0 pid) 0)
          live   (if (and te0 pid) (live-cids-lp co te0 pid) [])
          facts (:facts @(store co))]
      (cond
        ;; (1)(6) base_version: reject a stale single-valued write — ONLY when a base
        ;; was supplied (move-C: :base is OPTIONAL). The cardinality-typed verbs split
        ;; here: append!/put! pass NO base (nil) and are NEVER staleness-rejected
        ;; (multi coexists; single is last-writer-wins); only swap! passes a base and
        ;; opts into compare-and-swap. `and` short-circuits on nil base, so `(> bv base)`
        ;; is never reached with a nil base (no NPE). base 0 is a REAL base (fresh
        ;; subject, bv=0), still checked — only a MISSING base means LWW. The
        ;; id-collision / reserved-predicate rejections are base-independent (below) and
        ;; untouched.
        (and single base (> bv base))
        {:reject :conflict :version (current-seq co)}

        ;; (2)(3) obligation: acyclicity — pure pre-check, before any mutation
        (and (= kind :link) (contains? #{"depends_on" "part_of"} pred)
             (or (= te-name r-spec) (and te0 tgt0 (reaches? co pid tgt0 te0))))
        {:reject [(str pred " cycle")] :version (current-seq co)}

        ;; (4) multi-valued idempotency: no-op if the live (l,p,r) already exists
        (and (not single) (= kind :link) tgt0 (some #(= tgt0 (:r (get facts %))) live))
        {:ok (current-seq co) :idempotent true}
        (and (not single) (= kind :assert) vid (some #(= vid (:r (get facts %))) live))
        {:ok (current-seq co) :idempotent true}

        :else
        (let [since    (:next-id @(store co))
              observed (let [pre (current-seq co)] (min (or base pre) pre))  ; causal stamp, clamped to head (no future)
              tx (c/begin-tx! (store co) agent)
              _  (swap! (store co) assoc-in [:txs tx :observed] observed)
              _  (swap! (store co) assoc-in [:txs tx :ts] (rt/now-ts))  ; wall-clock stamp for pull provenance (display-only; ONE clock read per tx, mirrored into the v2 log via delta-records so live + replay agree)
              te (ent! co tx te-name)]
          (case kind
            :link   (s/link! (store co) te pred (ent! co tx r-spec) tx)
            :assert (s/assert! (store co) te pred r-spec tx))
          (append-tx! co (delta-records co since tx))   ; (5) atomic + fsync
          {:ok (get-in @(store co) [:txs tx :seq])})))))

;; --- views-as-facts writers (thread E) -------------------------------------
;; select! asserts (view selects @cid): `view` now treats fact `cid` as a fact. Multi
;; (a view selects many facts); idempotent when it already selects cid. This ONE write
;; is the whole branch-membership surface — per-branch isolation is otherwise pure
;; read-time election (elect above), no writer ever blocked.
(defn select! [co view cid]
  (locking (:lock co)
    (let [selp    (c/value-id (store co) "selects")
          ve0     (s/resolve-name (store co) view)
          already (when (and selp ve0)
                    (some #(= cid (:r (get (:facts @(store co)) %)))
                          (live-cids-lp co ve0 selp)))]
      (if already
        {:ok (current-seq co) :idempotent true :cid cid}
        (let [since (:next-id @(store co))
              tx    (c/begin-tx! (store co) view)
              _     (swap! (store co) assoc-in [:txs tx :ts] (rt/now-ts))  ; wall-clock stamp (display-only) — view-select facts are pullable too
              ve    (ent! co tx view)
              sp    (c/value! (store co) "selects")]
          (c/fact! (store co) ve sp cid tx)            ; object IS the selected fact's cid
          (append-tx! co (delta-records co since tx))
          {:ok (get-in @(store co) [:txs tx :seq]) :cid cid})))))

;; commit-on-view! — write a rival fact AND select it into `view` in one breath: the
;; "write on a branch" verb. Always coexists (no base -> never staleness-rejected); the
;; new rival is the highest live cid on (te,pred), so THAT cid is selected into the branch.
;; Reentrant lock (commit!/select! re-enter — JVM monitors are reentrant, as release-lease!
;; already relies on). Returns the new fact's cid. The lock spans both writes so a
;; concurrent reader never sees the rival un-selected (committed but not yet on its branch).
(defn commit-on-view! [co view agent te-name pred kind r-spec]
  (locking (:lock co)
    (let [r (commit! co agent te-name pred kind r-spec nil)]
      (if-not (:ok r)
        r
        (let [te  (s/resolve-name (store co) te-name)
              pid (c/value-id (store co) pred)
              cid (apply max (live-cids-lp co te pid))]   ; the just-written rival = newest live cid
          (select! co view cid)
          {:ok (:ok r) :cid cid})))))

;; retract: single-valued clears (te,pred); multi-valued removes the (te,pred,r)
;; edge. Same lock + base_version contract as commit! — clearing a driver out
;; from under an active thread races safely.
;;
;; FIRST-CLASS RETRACTION (thread H, Part D): cancellation is now an ATTRIBUTABLE,
;; QUERYABLE fact-ABOUT-the-victim-cid — (@cid withdrawn_by <agent>), (@cid
;; withdrawn_at <seq>), (@cid withdrawn_reason "<why>") — emitted ALONGSIDE (not
;; instead of) the anonymous store-supersedes marker. The supersedes marker stays the
;; internal live-fold mechanism (it drives live-cids-lp == remove-wins, the default);
;; the withdrawn_* facts are the cancellation SURFACE: who/when/why, queryable, and
;; the discriminator that lets an ADD-WINS view resurrect a withdrawal (live-members)
;; while a genuine overwrite still wins. `reason` is optional (older 6-arg callers
;; keep working). cids are first-class subjects (same flat id-space — VIEWS §8), so a
;; fact-about-a-cid is just a fact.
(defn retract!
  ([co agent te-name pred r-spec base] (retract! co agent te-name pred r-spec base nil))
  ([co agent te-name pred r-spec base reason]
  (locking (:lock co)
    (let [pid    (c/value-id (store co) pred)
          te0    (s/resolve-name (store co) te-name)
          single (= "single" (s/cardinality (store co) pred))]   ; fact is sole authority (move-B)
      (if (or (nil? te0) (nil? pid))
        {:ok (current-seq co)}                              ; nothing to retract
        (let [bv (base-version co te0 pid)]
          (if (and single base (> bv base))   ; move-C: :base optional here too (symmetric, nil-safe)
            {:reject :conflict :version (current-seq co)}
            (let [tgt (if (and r-spec (str/starts-with? (str r-spec) "@"))
                        (s/resolve-name (store co) r-spec)
                        (c/value-id (store co) r-spec))
                  facts (:facts @(store co))
                  victims (if single
                            (live-cids-lp co te0 pid)
                            (filter #(= tgt (:r (get facts %))) (live-cids-lp co te0 pid)))]
              (if (empty? victims)
                {:ok (current-seq co)}
                (let [since (:next-id @(store co))
                      observed (let [pre (current-seq co)] (min (or base pre) pre))  ; causal stamp on the retract tx
                      tx  (c/begin-tx! (store co) agent)
                      _   (swap! (store co) assoc-in [:txs tx :observed] observed)
                      _   (swap! (store co) assoc-in [:txs tx :ts] (rt/now-ts))  ; wall-clock stamp for the retract tx (display-only; distinct from :withdrawn_at, which holds the retract SEQ)
                      sup (c/value! (store co) "store-supersedes")
                      wbp (c/value! (store co) "withdrawn_by")
                      wap (c/value! (store co) "withdrawn_at")
                      wrp (c/value! (store co) "withdrawn_reason")
                      ag  (c/value! (store co) (str agent))
                      atv (c/value! (store co) (str (get-in @(store co) [:txs tx :seq])))
                      rsv (when reason (c/value! (store co) (str reason)))]
                  (doseq [old victims]
                    (c/fact! (store co) old sup old tx)             ; internal live-fold mechanism (remove-wins)
                    (c/fact! (store co) old wbp ag tx)              ; cancellation SURFACE: who
                    (c/fact! (store co) old wap atv tx)             ;   when (the retract tx seq)
                    (when rsv (c/fact! (store co) old wrp rsv tx))) ;   why (optional)
                  (append-tx! co (delta-records co since tx))
                  {:ok (get-in @(store co) [:txs tx :seq])}))))))))))

;; --- exclusive lease (mutual exclusion + fencing) — ADDITIVE -----------------
;; Closes the lost-update-vs-mutex gap: commit!'s base_version rejects a STALE
;; overwrite, NOT two acquirers that each read a FRESH base. acquire reads holder
;; LIVENESS fresh IN-lock. One single-valued cell on @lease:<R> co-encodes
;; holder|expiry-ms|epoch; held-ness is DERIVED (cell present AND expiry > clock).
;; A lapsed lease is reacquired by the next acquirer's own commit (no sweeper).
;; Ported into canonical's hand-written idiom from the typed spec-of-record
;; (fram-lease/src/fram/coord.bclj); coord_lease_test is the gate.
(def lease-pred "lease")
(defn- lease-subj [res] (str "@lease:" res))
(defn- encode-lease [h exp epoch] (str h "|" exp "|" epoch))
(defn- decode-lease [v]
  (when (string? v)
    (let [parts (str/split v #"\|")]
      (when (= 3 (count parts))
        {:holder (nth parts 0) :exp (parse-long (nth parts 1)) :epoch (parse-long (nth parts 2))}))))
(defn- read-lease [co res]
  (let [st (store co)
        te (s/resolve-name st (lease-subj res))
        pid (c/value-id st lease-pred)]
    (when (and te pid)
      (let [cid (first (live-cids-lp co te pid))]
        (when cid (decode-lease (get (:values @st) (:r (get (:facts @st) cid)))))))))

(defn- valid-lease-request? [holder res ttl-ms now]
  (and (string? holder) (not (str/blank? holder)) (not (str/includes? holder "|"))
       (string? res) (not (str/blank? res))
       (integer? ttl-ms) (pos? ttl-ms)
       (<= ttl-ms (- Long/MAX_VALUE now))))

(defn- valid-lease-epoch? [epoch]
  (and (integer? epoch) (pos? epoch) (<= epoch Long/MAX_VALUE)))

(defn- persist-lease!
  "Persist one fresh lease cell. Caller owns (:lock co); the new transaction's
  global sequence is both the durable write version and the fencing epoch."
  [co holder res ttl-ms now]
  (let [exp   (+ now ttl-ms)
        since (:next-id @(store co))
        tx    (c/begin-tx! (store co) holder)
        epoch (get-in @(store co) [:txs tx :seq])
        te    (ent! co tx (lease-subj res))]
    (when (not= "single" (s/cardinality (store co) lease-pred))
      (s/def-predicate! (store co) lease-pred "single" "literal" tx))
    (s/assert! (store co) te lease-pred (encode-lease holder exp epoch) tx)
    (append-tx! co (delta-records co since tx))
    {:ok epoch :holder holder :exp exp :epoch epoch}))

(defn acquire-lease! [co holder res ttl-ms]
  (locking (:lock co)
    (let [now (System/currentTimeMillis)
          cur (read-lease co res)]
      (cond
        (not (valid-lease-request? holder res ttl-ms now))
        {:reject :invalid-lease-request :version (current-seq co)}

        (and cur (> (:exp cur) now) (not= (:holder cur) holder))
        {:reject :held :holder (:holder cur) :exp (:exp cur) :version (current-seq co)}

        :else
        ;; The transaction sequence is global and durable. Deriving the fence
        ;; token from the lease cell itself lets release erase the cell without
        ;; erasing epoch history, closing same-holder ABA after reacquisition.
        (persist-lease! co holder res ttl-ms now)))))

(defn renew-lease!
  "Extend only the exact current, unexpired lease and rotate its fencing token.
  A lapse or takeover is terminal for the caller: renewal never reacquires."
  [co holder res expected-epoch ttl-ms]
  (locking (:lock co)
    (let [now (System/currentTimeMillis)
          cur (read-lease co res)]
      (cond
        (or (not (valid-lease-request? holder res ttl-ms now))
            (not (valid-lease-epoch? expected-epoch)))
        {:reject :invalid-lease-request :version (current-seq co)}

        (and cur
             (= holder (:holder cur))
             (= expected-epoch (:epoch cur))
             (> (:exp cur) now))
        (persist-lease! co holder res ttl-ms now)

        :else
        {:reject :fence-lost :version (current-seq co)}))))

;; release-lease! re-enters (:lock co) via retract! — JVM monitors are REENTRANT,
;; so this is safe; do NOT "fix" the nesting into a separate lock (would deadlock).
(defn release-lease!
  ;; The three-argument form is the legacy holder-only contract. Keep it for
  ;; callers that predate fencing. New callers supply their acquisition epoch:
  ;; an old finally block from the same holder must not release a newer lease.
  ([co holder res]
   (locking (:lock co)
     (let [cur (read-lease co res)]
       (if (and cur (= (:holder cur) holder))
         (retract! co holder (lease-subj res) lease-pred nil (current-seq co))
         {:ok (current-seq co) :noop true}))))
  ([co holder res epoch]
   (locking (:lock co)
     (let [cur (read-lease co res)]
       (if (and cur
                (= (:holder cur) holder)
                (= (:epoch cur) epoch))
         (retract! co holder (lease-subj res) lease-pred nil (current-seq co))
         {:ok (current-seq co) :noop true})))))

(defn fence-ok? [co res holder epoch]
  (locking (:lock co)
    (let [cur (read-lease co res)]
      (boolean (and cur (= (:holder cur) holder) (= epoch (:epoch cur))
                    (> (:exp cur) (System/currentTimeMillis)))))))

(defn with-fence!
  "Execute ACTION only while RES is held by HOLDER at EPOCH. Fence validation
  and ACTION share the coordinator's one writer lock. ACTION may re-enter that
  JVM monitor through commit!/retract!, so an expiry/takeover cannot land
  between the check and its fact mutation."
  [co res holder epoch action]
  (locking (:lock co)
    (if (and (string? res) (not (str/blank? res))
             (string? holder) (not (str/blank? holder))
             (integer? epoch) (not (neg? epoch))
             (fence-ok? co res holder epoch))
      (action)
      {:reject :fence-lost :version (current-seq co)})))

;; --- atomic counter (the swarm token budget) -------------------------------
;; bump-counter! adds delta to a numeric single-valued predicate under the SAME
;; lock the lease uses, so concurrent charges from N executors serialize and can't
;; lose updates (the read-then-assert-via-tells race the budget would otherwise hit).
;; Single-valued is load-bearing: an undeclared predicate is multi-valued, so asserts
;; ACCUMULATE and a later read picks an arbitrary cid — silent lost updates.
(defn- read-counter [co te-name p]
  (let [st (store co)
        te (s/resolve-name st te-name)
        pid (c/value-id st p)]
    (when (and te pid)
      (when-let [cid (first (live-cids-lp co te pid))]
        (parse-long (str (get (:values @st) (:r (get (:facts @st) cid)))))))))

(defn bump-counter! [co te-name p delta]
  (locking (:lock co)
    (let [newn  (+ (or (read-counter co te-name p) 0) (long delta))
          since (:next-id @(store co))
          tx    (c/begin-tx! (store co) "bump")
          te    (ent! co tx te-name)]
      (when (not= "single" (s/cardinality (store co) p))
        (s/def-predicate! (store co) p "single" "literal" tx))
      (s/assert! (store co) te p (str newn) tx)
      (append-tx! co (delta-records co since tx))
      {:ok (get-in @(store co) [:txs tx :seq]) :value newn})))

;; --- replay: rebuild the store from the v2 log (drops torn/uncommitted txs) --
(defn- read-records [path]
  (with-open [r (io/reader path)]
    (doall (keep (fn [ln] (try (edn/read-string ln) (catch Exception _ nil)))   ; tolerate a torn last line
                 (line-seq r)))))

(defn- committed-records [recs]
  ;; group into per-tx buffers; a buffer terminated by :commit is kept, a
  ;; trailing buffer with no :commit (a torn tx) is dropped.
  (loop [rs recs buf [] out []]
    (if (empty? rs)
      out
      (let [r (first rs)]
        (if (= (:k r) :commit)
          (recur (rest rs) [] (into out buf))
          (recur (rest rs) (conj buf r) out))))))

(defn- assemble-dump [recs]
  (let [vals   (vec (for [r recs :when (= (:k r) :value)]  [(:id r) (:v r)]))
        ents   (vec (for [r recs :when (= (:k r) :entity)] (:id r)))
        facts (vec (for [r recs :when (= (:k r) :fact)]  [(:cid r) {:l (:l r) :p (:p r) :r (:r r)}]))
        tx-of  (vec (for [r recs :when (= (:k r) :fact)]  [(:cid r) (:tx r)]))
        txs    (vec (for [r recs :when (= (:k r) :tx)]     [(:tx r) {:seq (:seq r) :agent (:agent r) :observed (:observed r) :ts (:ts r)}]))   ; recover the causal stamp AND wall-clock :ts through replay (acceptance d). OLD v2 records predate :ts -> (:ts r) is nil -> pull omits the key, exactly as for a never-stamped tx. Backward-compatible: old logs replay unchanged.
        sup    (some (fn [[id v]] (when (= v "store-supersedes") id)) vals)
        superd (vec (for [[_ m] facts :when (= (:p m) sup)] (:r m)))
        all-id (concat (map first vals) ents (map first facts) (map first txs))
        all-sq (map (fn [[_ m]] (:seq m)) txs)]
    ;; the kernel's counters hold the LAST-used id/seq (fresh-id!/begin-tx! return
    ;; the post-increment value), so recover them as max — NOT max+1 — else the
    ;; next mint would skip an id/seq (a gap) instead of continuing contiguously.
    {:next-id (reduce max 0 all-id) :next-seq (reduce max 0 all-sq)
     :supersedes-pred sup
     :objects (vec (concat (map first vals) ents (map first facts)))
     :values vals :facts facts :tx-of tx-of :txs txs :superseded superd}))

(defn replay [path]
  (let [st (c/new-store)]
    (c/load-store! st (assemble-dump (committed-records (read-records path))))
    st))

;; write a whole reified store as a v2 log (all records + one trailing :commit)
;; that `replay` consumes — the migration target. After migration the live
;; coordinator boots via (replay path); next-id/next-seq are recovered from the
;; logged ids, so its appends continue cleanly from where migration left off.
(defn dump-log! [st path]
  (spit path "")
  (let [m @st]
    (with-open [os (java.io.FileOutputStream. (str path) true)]
      (let [emit (fn [r] (.write os (.getBytes (str (pr-str r) "\n") "UTF-8")))]
        (doseq [[id v] (:values m)] (emit {:k :value :id id :v v}))
        (doseq [id (keys (:objects m))
                :when (and (not (contains? (:values m) id)) (not (contains? (:facts m) id)))]
          (emit {:k :entity :id id}))
        (doseq [[cid cl] (:facts m)]
          (emit {:k :fact :cid cid :l (:l cl) :p (:p cl) :r (:r cl) :tx (get (:tx-of m) cid)}))
        (doseq [[tx t] (:txs m)] (emit {:k :tx :tx tx :seq (:seq t) :agent (:agent t) :observed (:observed t) :ts (:ts t)}))
        (emit {:k :commit :tx :migration}))
      (.force (.getChannel os) true))))

;; live (l,p,r) id-triples of a reified store (substrate identity for tests/diff)
(defn live-triples [st]
  (let [m @st]
    (set (for [cid (keys (:facts m)) :when (not (contains? (:superseded m) cid))]
           (let [cl (get (:facts m) cid)] [(:l cl) (:p cl) (:r cl)])))))
