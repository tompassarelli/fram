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
;; NAMESPACE. This file used to have no `ns` form at all, so every one of its
;; ~90 defs landed in whatever namespace load-file'd it — in practice `user`,
;; shared with coord_daemon.clj and 88 loading scripts/tests. It now owns the
;; real namespace `coord`; the compat bridge at the BOTTOM of the file re-exports
;; those vars into `user` so the existing load-file callers keep working
;; unchanged. See the bridge comment for why load-file (not require) remains.
(ns coord
  (:require [fram.store :as c] [fram.schema :as s] [fram.kernel :as ck]
            [fram.rt :as rt]     ; vGUARD writer admission (shared rewrite flock)
            [fram.world :as w]   ; the PURE world kernel (graph-upstream); durability lives below
            [coord-read :as cr]
            [coord-commit :as cc]
            [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]))

(defn- store [co] (:store co))
(defn- version-conflict? [single bv base] (cc/version-conflict? single bv base))
(defn- expected-value-match? [live-values expected] (cc/expected-value-match? live-values expected))
(defn- plan-commits [head intents] (cc/commit-plan head intents))

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
(def ^:private group-appender-thread (atom nil))
(def ^:private group-appender-failure (atom nil))
(def ^:private group-appender-stopping? (atom false))
(def ^:private group-appender-lifecycle-lock (Object.))

(defn- plan-group-batch [items]
  (mapv (fn [batch] [(:path batch) (mapv #(nth items %) (:indices batch))])
        (cc/group-batch-plan (mapv #(cc/->GroupBatchItem (:path %)) items))))
(defn- group-flush-policy [pending-count] (cc/group-flush-policy pending-count))
(defn- group-flush-ready? [policy batch-count] (cc/group-flush-ready? policy batch-count))
(defn- queue-admission-decision [deferred] (cc/queue-admission-decision deferred))
(defn- group-lock-order [] (cc/group-lock-order))

(defn- with-batch-lock! [lock-id f]
  (case lock-id
    :group-io (locking group-io-lock (f))
    (throw (ex-info "unknown group batch lock policy" {:lock lock-id}))))

(defn- with-path-lock! [lock-id path f]
  (case lock-id
    :append-admission (rt/with-append-admission path f)
    (throw (ex-info "unknown group path lock policy" {:lock lock-id}))))

(defn- deliver-all! [items v] (doseq [{:keys [ticket]} items] (deliver ticket v)))

(defn assert-flat-append-boundary!
  "Refuse to append after a non-empty unterminated flat-log tail. Boot recovery
  repairs a crash tail under the exclusive rewrite lock; reaching this shared
  append seam without a terminal LF means repair did not own a stable corpus."
  [path]
  (let [f (java.io.File. (str path))]
    (when (and (.exists f) (pos? (.length f)))
      (with-open [raf (java.io.RandomAccessFile. f "r")]
        (.seek raf (dec (.length raf)))
        (when-not (= 10 (.read raf))
          (throw (ex-info (str "refusing append to unterminated flat log: " path)
                          {:path (str path)
                           :bytes (.length raf)
                           :fram/unterminated-flat-tail true})))))))

(defn- flat-file-stamp [path]
  (let [f (java.io.File. (str path))]
    (str (.lastModified f) ":" (.length f))))

(defn- utf8-byte-count [items]
  (reduce + 0
          (for [{:keys [lines]} items, ^String line lines]
            (alength (.getBytes line java.nio.charset.StandardCharsets/UTF_8)))))

(defn- group-appender-loop []
  (loop []
    (let [fst (.take group-q)
          buf (java.util.ArrayList.)
          policy (group-flush-policy (.size group-q))]
      (.add buf fst)
      (.drainTo group-q buf (:drain-limit policy))
      (let [items (vec buf)]
        (try
          (let [[batch-lock path-lock] (group-lock-order)]
            (when-not (group-flush-ready? policy (count items))
              (throw (ex-info "group batch below flush threshold"
                              {:count (count items) :policy policy})))
            ;; group-io-lock makes (write+fsync+on-flushed) atomic w.r.t. the
            ;; daemon's maybe-reload! stamp check, so our own async append is
            ;; never mistaken for an external edit (stamp and file move together).
            (with-batch-lock!
              batch-lock
              (fn []
                (doseq [[path pitems] (plan-group-batch items)]
                  (let [real (vec (filter #(seq (:lines %)) pitems))
                        written-bytes (when (seq real) (utf8-byte-count real))
                        flush-context (volatile! nil)]
                    (try
                      (when (and path (seq real))
                        ;; vGUARD writer admission (B2 §2): the batch holds the
                        ;; SHARED rewrite lock across open→write→fsync→close, so a
                        ;; generation flip's EXCLUSIVE lock excludes it
                        ;; kernel-arbitrated (no scan, no TOCTOU). A live flip
                        ;; DELAYS the batch — the ack (ticket delivery below) still
                        ;; happens only after the fsync, so no acked write can ever
                        ;; sit outside a flip's read set.
                        (with-path-lock!
                          path-lock
                          (str path)
                          (fn []
                            (assert-flat-append-boundary! path)
                            (let [before-stamp (flat-file-stamp path)
                                  before-bytes
                                  (.length (java.io.File. (str path)))]
                              (with-open
                               [os (java.io.FileOutputStream. (str path) true)]
                                (doseq [{:keys [lines]} real, ^String ln lines]
                                  (.write os (.getBytes ln "UTF-8")))
                                (.flush os)
                                ;; ONE fsync covers the whole batch
                                (.force (.getChannel os) true))
                              ;; Capture the owned-byte proof before releasing
                              ;; shared rewrite admission; a generation flip
                              ;; cannot hide in the before/after window.
                              (let [after-stamp (flat-file-stamp path)
                                    after-bytes
                                    (.length (java.io.File. (str path)))]
                                (vreset! flush-context
                                         {:path (str path)
                                          :before-stamp before-stamp
                                          :after-stamp after-stamp
                                          :after-bytes after-bytes
                                          :owned-append-exact?
                                          (= (long after-bytes)
                                             (+ (long before-bytes)
                                                (long written-bytes)))}))))))
                      (doseq [{:keys [on-flushed]} pitems :when on-flushed]
                        (on-flushed @flush-context))
                      (deliver-all! pitems :ok)
                      (catch Throwable t (deliver-all! pitems t))))))))
          (catch Throwable t
            ;; Fail the batch already removed from the queue as well as the
            ;; pending queue drained by run-group-appender!.  Without this, a
            ;; terminal planner/allocator failure strands these tickets forever.
            (deliver-all! items t)
            (throw t)))
        ;; A stop marker is admitted only after the daemon has stopped accepting
        ;; requests and drained its connection workers.  It shares the normal
        ;; FIFO batch so every earlier append/barrier is delivered before the
        ;; appender retires; no second writer starts in the same lifecycle.
        (when-not (some :stop items)
          (recur))))))

(defn- fail-pending-group-items! [t]
  (loop []
    (when-let [item (.poll group-q)]
      (when-let [ticket (:ticket item)]
        (deliver ticket t))
      (recur))))

(defn- run-group-appender! []
  (try
    (group-appender-loop)
    (catch Throwable t
      ;; A dead appender used to leave every queued request and the shutdown
      ;; durability barrier parked on promises forever.  Publish the terminal
      ;; failure and wake all queued waiters; writer admission remains fail-closed
      ;; for the rest of this process.
      (reset! group-appender-failure t)
      (fail-pending-group-items! t)
      (throw t))
    (finally
      (reset! group-appender-thread nil)
      (reset! group-appender-started false))))

(defn- ensure-group-appender! []
  (when-let [failure @group-appender-failure]
    (throw (ex-info "durable appender is unavailable"
                    {:type :durable-appender-failed} failure)))
  (when @group-appender-stopping?
    (throw (ex-info "durable appender is stopping"
                    {:type :durable-appender-stopping})))
  (when (compare-and-set! group-appender-started false true)
    (let [thread (doto (Thread. ^Runnable run-group-appender!)
                   (.setName "fram-group-appender")
                   (.setDaemon true))]
      (reset! group-appender-thread thread)
      (.start thread))))

(defn await-durable! [ticket]
  (let [r (deref ticket)]
    (when (instance? Throwable r) (throw r))
    r))

(defn await-durable-bounded!
  "Await a durability ticket for at most `timeout-ms`.  Normal request
   acknowledgements retain the unbounded await above; this bounded form exists
   for process shutdown, whose outer service manager must never have to SIGKILL
   a wedged or failed appender."
  [ticket timeout-ms]
  (let [timeout-ms (max 0 (long timeout-ms))
        timed-out (Object.)
        r (deref ticket timeout-ms timed-out)]
    (when (identical? timed-out r)
      (throw (ex-info "durable appender timed out"
                      {:type :durable-appender-timeout
                       :timeout-ms timeout-ms})))
    (when (instance? Throwable r) (throw r))
    r))

;; enqueue `lines` for durable append to `path`. Returns the ticket when deferred
;; (collected into *durable-tickets*); awaits it inline otherwise. on-flushed (may
;; be nil) runs on the appender thread after the batch's fsync, before delivery,
;; with the batch's before/after stamps and an exact-owned-byte verdict.
(defn enqueue-durable! [path lines on-flushed]
  (let [t (promise)]
    (locking group-appender-lifecycle-lock
      (ensure-group-appender!)
      (.put group-q {:path path :lines lines :ticket t :on-flushed on-flushed}))
    ;; Close the narrow failure-before-put race: if the appender exited after
    ;; ensure but before this item became visible to its failure drain, wake this
    ;; waiter with the same terminal error instead of parking forever.
    (when-let [failure @group-appender-failure]
      (deliver t failure))
    (case (queue-admission-decision (boolean *durable-tickets*))
      :defer (do (swap! *durable-tickets* conj t) t)
      :await (await-durable! t))))

;; barrier: returns once every enqueue that happened-before it is on disk (FIFO
;; queue + in-order batches). No-op if nothing was ever enqueued.
(defn durable-barrier!
  ([]
   (when-let [failure @group-appender-failure]
     (throw (ex-info "durable appender failed before barrier"
                     {:type :durable-appender-failed} failure)))
   (when @group-appender-started
     (let [t (promise)]
       (locking group-appender-lifecycle-lock
         (when @group-appender-stopping?
           (throw (ex-info "durable appender is stopping"
                           {:type :durable-appender-stopping})))
         (.put group-q {:path nil :lines [] :ticket t}))
       (when-let [failure @group-appender-failure]
         (deliver t failure))
       (await-durable! t))))
  ([timeout-ms]
   (when-let [failure @group-appender-failure]
     (throw (ex-info "durable appender failed before barrier"
                     {:type :durable-appender-failed} failure)))
   (when @group-appender-started
     (let [t (promise)]
       (locking group-appender-lifecycle-lock
         (when @group-appender-stopping?
           (throw (ex-info "durable appender is stopping"
                           {:type :durable-appender-stopping})))
         (.put group-q {:path nil :lines [] :ticket t}))
       (when-let [failure @group-appender-failure]
         (deliver t failure))
       (await-durable-bounded! t timeout-ms)))))

(defn group-appender-status []
  (let [^Thread thread @group-appender-thread]
    {:started @group-appender-started
     :stopping @group-appender-stopping?
     :alive (boolean (and thread (.isAlive thread)))
     :failure (some-> @group-appender-failure class .getName)}))

(defn stop-group-appender!
  "Retire the one durable appender after its FIFO has drained.  Returns a status
   map instead of waiting past `timeout-ms`; callers may then let process exit
   kill the daemon thread, while every acknowledged write is already protected
   by the preceding durability barrier."
  [timeout-ms]
  (let [timeout-ms (max 0 (long timeout-ms))
        deadline-ns (+ (System/nanoTime) (* timeout-ms 1000000))
        remaining-ms (fn []
                       (max 0
                            (long
                             (quot (+ (max 0 (- deadline-ns (System/nanoTime)))
                                      999999)
                                   1000000))))
        ticket (promise)
        thread
        (locking group-appender-lifecycle-lock
          (reset! group-appender-stopping? true)
          (let [^Thread thread @group-appender-thread]
            (when (and thread (.isAlive thread))
              (.put group-q {:path nil :lines [] :ticket ticket :stop true}))
            thread))]
    (when (and thread (.isAlive ^Thread thread))
      (deref ticket (remaining-ms) ::timeout)
      ;; Thread.join(0) means "wait forever", the opposite of our expired
      ;; deadline.  Skip the cooperative join once the budget reaches zero.
      (let [join-ms (remaining-ms)]
        (when (pos? join-ms)
          (.join ^Thread thread join-ms)))
      (when (.isAlive ^Thread thread)
        (.interrupt ^Thread thread)
        (.join ^Thread thread 100)))
    (assoc (group-appender-status)
           :stopped (not (boolean (and thread (.isAlive ^Thread thread)))))))

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
(defn- live-cids-lp [co te pid] (cr/live-cids-lp (store co) te pid))
(defn- seq-of [co cid] (cr/seq-of (store co) cid))
(defn base-version [co te pid] (cr/base-version (store co) te pid))
(defn current-seq [co] (cr/current-seq (store co)))

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
(defn agent-of [co cid] (cr/agent-of (store co) cid))

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
(defn observed-of [co cid] (cr/observed-of (store co) cid))
;; ts-of — the WALL-CLOCK stamp of a fact's asserting tx (thread H, display metadata).
;; Mirrors agent-of/observed-of: reads the tx record's :ts (an ISO-8601 instant string,
;; same format the flat log records, minted once per tx at commit via rt/now-ts). PURELY
;; DISPLAY — pull surfaces it as :ts; it NEVER participates in as-of / live election, which
;; stay seq-addressed. nil for pre-existing v2 txs whose record predates the :ts field (pull
;; omits the key then), so OLD logs replay unchanged — the stamp is strictly additive.
(defn ts-of [co cid] (cr/ts-of (store co) cid))
;; the causal key of a live fact: [observed-or-seq, cid, agent]. observed orders by
;; DECISION time (who saw the empty group first), cid/agent keep it a total order. A LATER
;; commit (higher cid) that DECIDED earlier (lower observed) wins — this is the whole point:
;; election by causal view, not by commit order. Pure fn of recorded facts -> every reader
;; computes it identically with zero coordination.
(defn causal-key [co cid] (cr/causal-key (store co) cid))

;; --- as-of: the history fold (thread H, Part B) ------------------------------
;; "What was live AS OF seq S?" A fact is live-as-of-S iff it was BORN at a seq <= S
;; AND no store-supersedes marker for it was committed at a seq <= S. Because retraction is
;; append-only (a marker, never a delete), this is EXACT: a fact later superseded/withdrawn
;; is naturally RE-SEEN at an earlier S — its tombstone hadn't been written yet (acceptance
;; b). Folds the in-store tail, so it is bounded by thread D's snapshot floor (history
;; compacted below a snapshot is gone: as-of before the floor is unavailable, not wrong) —
;; never O(total history) (acceptance f).
(defn superseded-as-of [co s] (cr/superseded-as-of (store co) s))
(defn live-as-of [co s] (cr/live-as-of (store co) s))
;; the live cids of ONE (te,pid) group as of S — the as-of twin of live-cids-lp.
(defn live-as-of-lp [co s te pid] (cr/live-as-of-lp (store co) s te pid))

;; --- first-class retraction readers + the add-wins/remove-wins view selector ---
;; withdrawal-of reads the attribution surface OFF a victim cid (the queryable who/when/
;; why retract! stamped). nil when the cid carries no live withdrawn_by tombstone.
(defn- live-r-on [co cid pid] (cr/live-r-on (store co) cid pid))
(defn withdrawal-of [co cid] (cr/withdrawal-of (store co) cid))
(defn withdrawn? [co cid] (cr/withdrawn? (store co) cid))

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
  ([co te pid] (cr/live-members (store co) te pid :remove-wins))
  ([co te pid policy] (cr/live-members (store co) te pid policy)))

;; --- views-as-facts (thread E): per-branch isolation over the same log ------
;; A VIEW is a first-class subject; (view selects @cid) facts are its OVERLAY —
;; the cids it treats as facts. The object IS a fact id: cids live in the same
;; flat content-interned id-space, so a fact is itself addressable (the most
;; store-native of VIEWS_AND_BRANCHES §8's three encodings). view-selects returns
;; the live overlay; nil when the view subject or `selects` predicate was never
;; minted (an unknown view selects nothing -> it inherits main).
(defn view-selects [co view] (cr/view-selects (store co) view))

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
  ([co cids] (cr/elect (store co) nil cids))
  ([co view cids] (cr/elect (store co) view cids)))

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
  ([co cids] (cr/elect-causal (store co) nil cids))
  ([co view cids] (cr/elect-causal (store co) view cids)))

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
        (version-conflict? single bv base)
        {:reject :conflict :version (current-seq co)}

        ;; (2)(3) obligation: acyclicity — pure pre-check, before any mutation
        (and (= kind :link) (contains? #{"depends_on" "part_of"} pred)
             (or (= te-name r-spec) (and te0 tgt0 (reaches? co pid tgt0 te0))))
        {:reject [(str pred " cycle")] :version (current-seq co)}

        ;; (4) multi-valued idempotency: no-op if the live (l,p,r) already exists
        (and (not single)
             (expected-value-match? (set (keep #(get-in facts [% :r]) live))
                                    (if (= kind :link) tgt0 vid)))
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

;; commit-batch! — ATOMIC multi-fact publication for ONE subject (thread 019f9063,
;; incident 019f8958). The all-or-none primitive the torn-mail bug demands: a send
;; used to publish from/subject/body/sent_at/to as SEPARATE single-fact commit! txs,
;; so a crash/disconnect mid-send left a from-only orphan (torn subject). This admits
;; N facts ABOUT te-name as one unit: EVERY fact is validated FIRST (base_version OCC,
;; acyclicity, multi idempotency) exactly as commit! does — and if ANY rejects, ZERO
;; state is minted (finding #2's validate-without-mutating, extended over the set).
;; Accepted writes then land in ONE tx with ONE append-tx! (one fsync, ONE :commit
;; marker), so delta-records emits them contiguously — v2 torn-tx replay drops the
;; whole batch or none, never a partial. Single-fact commit!/`:assert` is UNTOUCHED
;; (byte-identical); this is an additive second entry point, callers opt in.
;;   facts = [{:pred p :kind :assert|:link :r r :base <optional>}]
;; Returns {:ok seq :written [{:pred :kind :r}..] :idempotent [pred..]}
;;      or {:reject <reason> :version v :at <fact-index> :pred <pred>}  (nothing minted).
(defn commit-batch! [co agent te-name facts]
  (locking (:lock co)
    (let [st  (store co)
          te0 (s/resolve-name st te-name)
          ;; PHASE 1 — validate/classify EVERY fact against the pre-batch snapshot,
          ;; before minting anything. `reduced` on the first reject aborts with no state.
          intents
          (mapv
           (fn [[i {:keys [pred kind r base]}]]
                  (let [pid    (c/value-id st pred)
                        tgt0   (when (= kind :link) (s/resolve-name st r))
                        vid    (when (= kind :assert) (c/value-id st r))
                        single (= "single" (s/cardinality st pred))
                        bv     (if (and te0 pid) (base-version co te0 pid) 0)
                        live   (if (and te0 pid) (live-cids-lp co te0 pid) [])
                        fm     (:facts @st)]
                    (cc/->CommitIntent
                     i pred kind r single bv base
                     (set (keep #(get-in fm [% :r]) live))
                     (if (= kind :link) tgt0 vid)
                     (and (= kind :link) (contains? #{"depends_on" "part_of"} pred)
                          (or (= te-name r) (and te0 tgt0 (reaches? co pid tgt0 te0)))))))
           (map-indexed vector facts))
          plan (plan-commits (current-seq co) intents)]
      (cond
        (:reject plan) plan
        ;; whole batch was idempotent/empty: no version movement, nothing durable
        (empty? (:writes plan))
        {:ok (current-seq co) :written [] :idempotent (:idempotent plan)}
        :else
        ;; PHASE 2 — one tx, all writes, one append-tx!. All-or-none at the durable seam.
        (let [since    (:next-id @st)
              observed (current-seq co)                 ; the head the batch was decided at
              tx       (c/begin-tx! st agent)
              _        (swap! st assoc-in [:txs tx :observed] observed)
              _        (swap! st assoc-in [:txs tx :ts] (rt/now-ts))]
          (doseq [{:keys [pred kind r]} (:writes plan)]
            (let [te (ent! co tx te-name)]
              (case kind
                :link   (s/link! st te pred (ent! co tx r) tx)
                :assert (s/assert! st te pred r tx))))
          (append-tx! co (delta-records co since tx))
          {:ok (get-in @st [:txs tx :seq])
           :written (:writes plan) :idempotent (:idempotent plan)})))))

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

;; about! writes one fact whose SUBJECT is an existing fact cid. This is the
;; public coordinator seam for modules such as fram.claims that model
;; participation with facts-about-facts. The v2 log preserves cid identity, so
;; the write replays byte-for-byte like select! and retract!'s withdrawal facts.
;; kind = :assert (literal) | :link (named entity). Exact live duplicates are
;; idempotent. The caller owns any higher-level policy around the target fact.
;; Returns the NEW fact's :cid on a fresh write (commit!/select! parity) — a
;; caller that must later name the citation itself (supersede it, decorate it)
;; needs no read-back query. The idempotent arm carries no :cid: nothing new.
(defn about! [co agent cid pred kind r-spec]
  (locking (:lock co)
    (let [st      (store co)
          victim (c/fact-of st cid)
          pid     (c/value-id st pred)
          target  (when (= kind :link) (s/resolve-name st r-spec))
          value   (when (= kind :assert) (c/value-id st r-spec))
          live    (if pid (live-cids-lp co cid pid) [])
          facts   (:facts @st)
          wanted  (if (= kind :link) target value)]
      (cond
        (or (nil? victim) (not (c/live? st cid)))
        {:reject :fact-not-live :version (current-seq co)}

        (and (= kind :link) (nil? target))
        {:reject :target-not-found :version (current-seq co)}

        (some #(= wanted (:r (get facts %))) live)
        {:ok (current-seq co) :idempotent true :subject-cid cid}

        :else
        (let [since (:next-id @st)
              tx    (c/begin-tx! st agent)
              _     (swap! st assoc-in [:txs tx :ts] (rt/now-ts))
              new   (case kind
                      :link   (s/link! st cid pred target tx)
                      :assert (s/assert! st cid pred r-spec tx))]
          (append-tx! co (delta-records co since tx))
          {:ok (get-in @st [:txs tx :seq]) :subject-cid cid :cid new})))))

;; supersede-cid! retires ONE fact by cid with the store's own supersession
;; marker — the exact write retract! performs internally, reachable by cid
;; instead of by (subject, predicate, value). retract!'s name-oriented
;; signature cannot NAME a selection fact, so un-verifying a claim (supersede
;; the verdict SELECTION, leave claim + evidence untouched, the withdrawn
;; verdict still in the log — docs/claims-design.md) needed this seam.
;; Idempotent on an already-superseded cid: nothing new to retire.
(defn supersede-cid! [co agent cid]
  (locking (:lock co)
    (let [st (store co)]
      (cond
        (nil? (c/fact-of st cid))
        {:reject :fact-not-found :version (current-seq co)}

        (not (c/live? st cid))
        {:ok (current-seq co) :idempotent true :cid cid}

        :else
        (let [since (:next-id @st)
              tx    (c/begin-tx! st agent)
              _     (swap! st assoc-in [:txs tx :ts] (rt/now-ts))
              sup   (c/value! st "store-supersedes")]
          (c/fact! st cid sup cid tx)
          (append-tx! co (delta-records co since tx))
          {:ok (get-in @st [:txs tx :seq]) :cid cid})))))

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
          (if (version-conflict? single bv base)   ; move-C: :base optional here too (symmetric, nil-safe)
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
;; Pure decisions live in graph-upstream coord-commit. This host retains the
;; clock, lock, persistence, retract execution, and durable fencing epochs.
(def lease-pred "lease")
(def ^:dynamic *lease-now-ms*
  "Host clock seam for lease adapters. Production uses the JVM wall clock;
  deterministic decision goldens bind an explicit clock."
  (fn [] (System/currentTimeMillis)))
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

(defn- lease-snapshot [lease] (when lease (cc/->LeaseSnapshot (:holder lease) (:exp lease) (:epoch lease))))
(defn- lease-grant-decision [lease holder res ttl-ms now version] (cc/lease-grant-decision (lease-snapshot lease) holder res ttl-ms now Long/MAX_VALUE version))
(defn- lease-renew-decision [lease holder res epoch ttl-ms now version] (cc/lease-renew-decision (lease-snapshot lease) holder res epoch ttl-ms now Long/MAX_VALUE Long/MAX_VALUE version))
(defn- lease-release-decision [lease holder epoch require-epoch version] (cc/lease-release-decision (lease-snapshot lease) holder epoch require-epoch version))
(defn- lease-fence-ok-decision [lease holder epoch now] (cc/lease-fence-ok? (lease-snapshot lease) holder epoch now))

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
    (let [now (*lease-now-ms*)
          cur (read-lease co res)
          decision (lease-grant-decision cur holder res ttl-ms now (current-seq co))]
      (if (:persist decision)
        ;; The transaction sequence is global and durable. Deriving the fence
        ;; token from the lease cell itself lets release erase the cell without
        ;; erasing epoch history, closing same-holder ABA after reacquisition.
        (persist-lease! co holder res ttl-ms now)
        decision))))

(defn renew-lease!
  "Extend only the exact current, unexpired lease and rotate its fencing token.
  A lapse or takeover is terminal for the caller: renewal never reacquires."
  [co holder res expected-epoch ttl-ms]
  (locking (:lock co)
    (let [now (*lease-now-ms*)
          cur (read-lease co res)
          decision (lease-renew-decision cur holder res expected-epoch ttl-ms now (current-seq co))]
      (if (:persist decision)
        (persist-lease! co holder res ttl-ms now)
        decision))))

;; release-lease! re-enters (:lock co) via retract! — JVM monitors are REENTRANT,
;; so this is safe; do NOT "fix" the nesting into a separate lock (would deadlock).
(defn release-lease!
  ;; The three-argument form is the legacy holder-only contract. Keep it for
  ;; callers that predate fencing. New callers supply their acquisition epoch:
  ;; an old finally block from the same holder must not release a newer lease.
  ([co holder res]
   (locking (:lock co)
     (let [cur (read-lease co res)
           decision (lease-release-decision cur holder nil false (current-seq co))]
       (if (:retract decision)
         (retract! co holder (lease-subj res) lease-pred nil (current-seq co))
         decision))))
  ([co holder res epoch]
   (locking (:lock co)
     (let [cur (read-lease co res)
           decision (lease-release-decision cur holder epoch true (current-seq co))]
       (if (:retract decision)
         (retract! co holder (lease-subj res) lease-pred nil (current-seq co))
         decision)))))

(defn fence-ok? [co res holder epoch]
  (locking (:lock co)
    (let [cur (read-lease co res)]
      (lease-fence-ok-decision cur holder epoch (*lease-now-ms*)))))

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

;; ============================================================================
;; WORLDS — the durability layer (thread 019f93bb / authority design 019f9358)
;; ============================================================================
;; The world kernel (fram.world, graph-upstream) is PURE: content-addressed ids,
;; overlay precedence, deterministic composition. Nothing in it touches a log.
;; This section is the other half: how a world's blobs, candidates, versions,
;; locks, receipts and head facts BECOME DURABLE — and it does so by REUSING
;; the coordinator's existing append-only seams rather than inventing a second
;; durable format:
;;
;;   * every world record is ORDINARY FACTS on a content-addressed subject, so
;;     one world verb == one tx block == records + a terminating :commit marker.
;;     A crash mid-verb leaves a trailing buffer with no :commit, which `replay`
;;     already DROPS. There is no world-specific torn-record logic to get wrong.
;;   * the head is DERIVED — folded from append-only `world.head` facts on
;;     the world subject, latest cid wins. No stored "canonical"/"current" marker
;;     exists to survive an incomplete promotion and win a later fold.
;;   * V1 raw blobs are canonical base64 LITERALS in the log. There is no blob
;;     filesystem and no persistent checkout: the graph is the source of truth.
;;   * every candidate begin/op record NAMES its candidate id IN ITS SUBJECT, so
;;     op contiguity is checkable after byte surgery (a dropped interior record
;;     leaves a hole that `world.ops` — the count sealed with the candidate —
;;     exposes). This is the assumption tests/world_persistence_test.clj makes
;;     explicit and falsifiable.
;;   * a candidate's sealed VersionId is its digest: promotion RE-DERIVES it from
;;     the replayed ops and refuses on mismatch, so tampering with op bytes in
;;     the log cannot be promoted even at equal byte length.
;;
;; Every rejection happens BEFORE any append: the reject paths below are pure
;; reads (value-id lookups never intern), so a refused promotion moves neither
;; the derived head nor a single log byte.
;;
;; SCOPE: receipt/expected-head CAS *validation* is specified separately
;; (tests/world_promotion_test.clj) and `world-build!` here attests a lock rather
;; than executing a build adapter — the build/projection slice owns that.

(defn- world-subject [nm] (str "world:" nm))
(defn- world-cand-subject [cid] (str "world.cand:" cid))
(defn- world-op-subject [cid i] (str "world.cand:" cid ":op:" i))

;; one durable read: the literal of a write-once (subject, predicate) world fact.
;; PURE — resolve-name/value-id never intern, so no read can mutate the store.
(defn- world-fact [co subj pred]
  (when-let [e (s/resolve-name (store co) subj)]
    (s/lookup (store co) e pred)))

(defn- world-record [co subj pred]
  (when-let [t (world-fact co subj pred)] (edn/read-string (str t))))

;; one tx, many (subject predicate literal) asserts. World subjects are fresh and
;; content-addressed and written once, so none of commit!'s guards apply (no
;; base_version CAS, no acyclicity, no coexist-elect rivalry); what matters is
;; that the whole verb lands as ONE contiguous tx block terminated by :commit.
(defn- world-commit! [co agent triples]
  (locking (:lock co)
    (let [st    (store co)
          since (:next-id @st)
          tx    (c/begin-tx! st agent)]
      (swap! st assoc-in [:txs tx :observed] (current-seq co))
      (swap! st assoc-in [:txs tx :ts] (rt/now-ts))
      (doseq [[subj pred v] triples]
        (s/assert! st (ent! co tx subj) pred v tx))
      (append-tx! co (delta-records co since tx))
      {:ok (get-in @st [:txs tx :seq])})))

;; --- heads are DERIVED, never stored ----------------------------------------
(defn world-head
  "The derived head of world `nm`: the latest live world.head, by cid.
  nil for an unknown world. Never reads a stored status."
  [co nm]
  (let [st  (store co)
        e   (s/resolve-name st (world-subject nm))
        pid (c/value-id st "world.head")]
    (when (and e pid)
      (let [m @(store co)]
        (->> (get (:idx-by-lp m) [e pid])
             (remove #(contains? (:superseded m) %))
             sort
             last
             (#(when % (get (:values m) (:r (get (:facts m) %))))))))))

(defn world-create! [co agent nm version-id]
  (or (w/validate-world-name nm)
      (if (world-head co nm)
        {:reject :world-exists}
        (world-commit! co agent [[(world-subject nm) "world.head" version-id]]))))

(defn world-fork!
  "O(1): one head fact naming the base VersionId. No blob, manifest or version
  record is copied — the forked name simply starts deriving from the same node."
  [co agent new-name version-id]
  (or (w/validate-world-name new-name)
      (cond
        (world-head co new-name) {:reject :world-exists}
        (nil? version-id)        {:reject :world-version-unknown}
        :else (world-commit! co agent
                             [[(world-subject new-name) "world.head" version-id]]))))

;; --- blobs: canonical base64 FACTS (no blob filesystem) ---------------------
(defn world-blob-put! [co agent ^bytes raw]
  (or (w/validate-blob raw)
      (let [id   (w/blob-id raw)
            subj (str "world.blob:" id)]
        (when-not (world-fact co subj "world.b64")
          (world-commit! co agent [[subj "world.b64" (w/blob-b64 raw)]]))
        {:ok id})))

(defn world-blob ^bytes [co blob-id]
  (when-let [b64 (world-fact co (str "world.blob:" blob-id) "world.b64")]
    (.decode (java.util.Base64/getDecoder) (str b64))))

;; --- versions and manifests -------------------------------------------------
(defn world-version [co version-id]
  (when version-id (world-record co (str "world.version:" version-id) "world.record")))

(defn- world-chain
  "The versions map the kernel's resolver needs, walked lazily from `vid` down
  its :base chain. Absent ancestors simply end the walk (resolve-slot is total)."
  [co vid]
  (loop [v vid acc {}]
    (if (or (nil? v) (contains? acc v))
      acc
      (let [r (world-version co v)]
        (if (nil? r) acc (recur (:base r) (assoc acc v r)))))))

(defn world-manifest [co version-id]
  (w/manifest (world-chain co version-id) version-id))

(defn world-compose
  "Mixed composition over DURABLE versions: gather every participating version's
  chain out of the log, then hand the PURE kernel one versions map. `selections`
  is [[slot source-version-id] ...] — each named slot is taken from its source,
  every other slot inherits the base.

  A READ, deliberately: it appends nothing. The result is an ORDINARY Version
  record whose :overlay is exactly the op list a candidate opened on
  `base-version-id` must append to become that version, so composition needs no
  second promotion path — it reuses begin/append/seal/promote unchanged."
  [co base-version-id selections]
  (let [versions (reduce (fn [acc v] (merge acc (world-chain co v)))
                         (world-chain co base-version-id)
                         (map second selections))]
    (w/compose versions base-version-id selections)))

;; --- candidates: begin / append / seal --------------------------------------
(defn- world-candidate-ops
  "The CONTIGUOUS op prefix of a candidate, read back from the log. Contiguity is
  the point: a dropped interior op record stops the walk, and the count sealed
  with the candidate then exposes the hole."
  [co cid]
  (loop [i 0 acc []]
    (if-let [r (world-record co (world-op-subject cid i) "world.record")]
      (recur (inc i) (conj acc r))
      acc)))

(defn world-candidate [co cid]
  (let [subj (world-cand-subject cid)]
    (when-let [nm (world-fact co subj "world.for")]
      {:ops      (world-candidate-ops co cid)
       :sealed   (world-fact co subj "world.sealed")
       :world    nm
       :base     (world-fact co subj "world.base")
       :declared (some-> (world-fact co subj "world.ops") str parse-long)})))

(defn world-begin! [co agent nm expected-head nonce]
  (cond
    (not (w/nonce-hex? nonce))              {:reject :world-nonce-inadmissible}
    (not= expected-head (world-head co nm)) {:reject :world-head-stale}
    :else
    (let [cid  (w/candidate-id nm expected-head nonce)
          subj (world-cand-subject cid)]
      (if (world-fact co subj "world.for")
        {:reject :world-candidate-exists}
        (do (world-commit! co agent
                           (cond-> [[subj "world.for" nm] [subj "world.nonce" nonce]]
                             expected-head (conj [subj "world.base" expected-head])))
            {:ok cid})))))

(defn world-append! [co agent cid op]
  (let [subj (world-cand-subject cid)]
    (cond
      (nil? (world-fact co subj "world.for")) {:reject :world-candidate-unknown}
      (world-fact co subj "world.sealed")     {:reject :world-candidate-sealed}
      :else
      (or (w/validate-slot (:slot op))
          (when (= :put (:op op)) (w/validate-mode (:mode op)))
          (let [rendered (w/render-record op)]
            (or (w/validate-record op)
                (let [i (count (world-candidate-ops co cid))]
                  (world-commit! co agent [[(world-op-subject cid i) "world.record" rendered]])
                  {:ok i})))))))

(defn world-seal!
  "Freeze a candidate into a content-addressed Version. The sealed VersionId is
  also the candidate's DIGEST — promotion re-derives it from the replayed ops."
  [co agent cid]
  (let [subj (world-cand-subject cid)]
    (cond
      (nil? (world-fact co subj "world.for")) {:reject :world-candidate-unknown}
      (world-fact co subj "world.sealed")     {:ok (world-fact co subj "world.sealed")}
      :else
      (let [base (world-fact co subj "world.base")
            ops  (world-candidate-ops co cid)
            vid  (w/version-id base ops)
            rec  (w/version-record base ops)]
        (or (w/validate-record rec)
            (do (world-commit! co agent
                               [[(str "world.version:" vid) "world.record" (w/render-record rec)]
                                [subj "world.ops" (str (count ops))]
                                [subj "world.sealed" vid]])
                {:ok vid}))))))

;; --- locks and receipts -----------------------------------------------------
(defn world-lock!
  "A WorldLock is a PURE function of durable, content-addressed inputs: the
  VersionId and the build spec. No wall clock, pid, nonce or process-local cid
  enters it, so a fresh process replaying the same bytes recomputes the same id."
  [co version-id build-spec]
  (let [rec (w/lock-record version-id build-spec)
        id  (w/world-lock-id version-id build-spec)]
    (when-not (world-fact co (str "world.lock:" id) "world.record")
      (world-commit! co "world" [[(str "world.lock:" id) "world.record" (w/render-record rec)]]))
    {:ok id :lock rec}))

(defn world-build!
  "Attest a lock. SCOPE: the build adapter itself (beagle toolchain, git
  projection) is a separate slice; this records the durable receipt that
  promotion gates on."
  [co agent lock-id]
  (if-let [lock (world-record co (str "world.lock:" lock-id) "world.record")]
    (let [rec {:kind :world/receipt :lock lock-id :version (:version lock)}
          rid (w/hash-text w/receipt-tag (w/render-record rec))]
      (world-commit! co agent [[(str "world.receipt:" rid) "world.record" (w/render-record rec)]])
      {:ok (assoc rec :receipt rid)})
    {:reject :world-lock-unknown}))

;; --- promotion: every rejection is a PURE read, before any append -----------
(defn world-promote! [co agent nm expected-head cid receipt]
  (let [subj    (world-cand-subject cid)
        known   (world-fact co subj "world.for")
        sealed  (world-fact co subj "world.sealed")
        declared (some-> (world-fact co subj "world.ops") str parse-long)
        ops     (when sealed (world-candidate-ops co cid))]
    (cond
      (nil? known)   {:reject :world-candidate-unknown}
      ;; a candidate whose seal record never landed (or was cut away) is not a
      ;; Version and can never become one.
      (nil? sealed)  {:reject :world-candidate-unsealed}
      ;; the seal recorded HOW MANY ops it froze; fewer replay => an interior
      ;; record is missing (log surgery / a hole), so the candidate is not whole.
      (not= declared (count ops)) {:reject :world-candidate-gapped}
      ;; the sealed VersionId IS the digest of (base, ops): re-derive it from the
      ;; bytes that actually replayed. Equal-length tampering fails here.
      (not= sealed (w/version-id (world-fact co subj "world.base") ops))
      {:reject :world-candidate-digest-mismatch}
      (not= expected-head (world-head co nm)) {:reject :world-head-stale}
      (not (and (map? receipt) (= (:version receipt) sealed)))
      {:reject :world-receipt-invalid}
      :else
      (do (world-commit! co agent [[(world-subject nm) "world.head" sealed]])
          {:ok sealed}))))

;; ============================================================================
;; COMPAT BRIDGE — `coord` re-exported into `user`.
;; ============================================================================
;; 88 callers (coord_daemon.clj, the bench harnesses, bin/fram-selfcheck-probe,
;; ~80 tests) reach this file with `(load-file "coord.clj")` from a bare bb
;; script, i.e. from `user`, and then call new-coord/commit!/select!/… with NO
;; qualifier. Before this file had an `ns`, that worked because the defs landed
;; in `user` directly. It keeps working because we refer every var of `coord`
;; — publics AND privates, which several callers legitimately use (live-cids-lp,
;; append-tx!, delta-records, read-lease, ent!) because privacy was a no-op in
;; the shared-`user` world — plus this namespace's require ALIASES (c/, s/, ck/,
;; rt/, w/, edn/, io/, str/), which callers also inherited from the old bare
;; `(require ...)`. `refer` installs the REAL vars (not copies), so dynamic
;; rebinding of `*durable-tickets*` from `user` still hits this namespace's var.
;;
;; WHY load-file AND NOT require. coord.clj sits at the repo ROOT, and every
;; entry point runs with `-cp out` (the compiled Beagle output) — the root is
;; not on the classpath, so `(require 'coord)` cannot find it. Moving the file
;; under a classpath root would rewrite the nix package layout, build.sh, the
;; bench harnesses and all 88 call sites at once; that is a separate change.
;; Until then load-file is the loader and this bridge is the seam.
;; `refer` exports only PUBLICS on the JVM (SCI is laxer), but the shared-`user`
;; world had no privacy at all — so the bridge LIFTS the (inert) :private marker
;; before referring, keeping the callable surface byte-for-byte what it was. The
;; definition-site `defn-` is retained as the authorial intent marker for the
;; eventual real decoupling; only the exported surface is widened, here, once.
(let [target (create-ns 'user)]
  (doseq [[_ v] (ns-interns 'coord)] (alter-meta! v dissoc :private))
  (binding [*ns* target]
    (refer 'coord)
    (doseq [[a n] (ns-aliases 'coord)] (alias a (ns-name n)))))
