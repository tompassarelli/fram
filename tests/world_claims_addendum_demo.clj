;; world_claims_addendum_demo.clj — the INCREMENTAL INGESTION story, executable.
;; Thread 019f9dfb-6da7-79c2-bad3-7197da27247f. Worlds: docs/archive/adr-0001 + world_kernel /
;; world_persistence / world_vertical_slice. Claims: docs/archive/claims-design.md +
;; tests/claims_spec_test.clj.
;;
;;   bb -cp out tests/world_claims_addendum_demo.clj     # from the repo ROOT
;;
;; WHAT THIS IS. Not a third worlds suite and not a second claims spec: a DEMO
;; that the two modules compose into the consumer story they were built for —
;; ingesting a construction plan set, then ingesting the ADDENDUM that revises
;; three lines of it, without re-ingesting or re-reviewing the other 400 sheets.
;; Every bar below is a question a plan-review product actually has to answer.
;;
;; THE STORY, in domain terms.
;;   MARCH. The permit set for a job lands. Ingestion cuts each sheet into
;;   REGIONS — E-101's general notes, E-101's fixture schedule, A-201's finish
;;   schedule — and extracts facts out of each region. A reviewer (jordan) reads
;;   the extractions against the drawing and VERIFIES four of them; one is left
;;   PENDING for want of a reader; one is REJECTED with a reason ("the schedule
;;   row reads 18, not 180").
;;
;;   MAY. Addendum 1 is issued. It reprints ONE region: E-101's general notes,
;;   with note 3 struck through and replaced. Nothing else on the job changed.
;;   The addendum is ingested as a FORK of the sealed permit set: O(1), one head
;;   fact, no blob or manifest copy. The one revised region is overlaid; the
;;   other regions are INHERITED by reference; the result is sealed as a new
;;   head. On the fact side the strikethrough is modelled honestly — the new
;;   reading is ASSERTED and the old fact is SUPERSEDED in the same tx, authored
;;   by "addendum-1". Nothing is deleted; the struck text stays legible forever.
;;
;;   THE PAYOFF. The review queue is a QUERY, not a bookkeeping table:
;;   `needs-reverification A B` returns exactly the verified extractions whose
;;   cited region moved. Pending and rejected extractions are not in it (nobody
;;   verified them, so nothing was invalidated); extractions citing untouched
;;   regions stay verified and are never re-read; the March world still answers
;;   the March question; and the superseded reading is still recoverable.
;;
;;   OPTION A (the semantics this demo pins). Re-verification means RE-EXTRACTING
;;   at the new head: the reviewer re-pends the verdict, the extractor withdraws
;;   the stale citation and cites fresh evidence carrying `evidence.world = B`,
;;   and the reviewer verifies again. Two consequences, both barred here: the A->B
;;   queue DRAINS as claims are re-verified (it is a worklist, not a log), and
;;   when Addendum 2 moves that region again the re-verified claim RE-PENDS on the
;;   B->C transition — because its evidence now lives at B. A claim still citing A
;;   stays owed in the A->B queue and does not appear in B->C: queues are
;;   per-transition, and re-verification is what migrates a claim forward.
;;
;;   INVALIDATION IS REGION-GRANULAR, stated plainly rather than hidden: Addendum
;;   1 touched note 3, but note 4's claim cites the SAME region and re-pends too.
;;   The region is the unit of change; finer regions buy finer queues.
;;
;; THE SEAM between the two modules is the REGION KEY. One key, two encodings:
;;   world SLOT   "regions/E-101/general-notes"  — the unit of content + change
;;   graph SUBJECT "@region:E-101/general-notes" — the unit of extracted facts
;; and `evidence.source` is spelled with the SLOT, which is exactly why the
;; transition rule can join claims to a manifest diff with no bespoke traversal.
;; (`evidence.region` is the finer locator INSIDE the region — a line/col span.)
;;
;; SCOPE, held deliberately. No socket, no daemon, no port; one scratch log under
;; /tmp. Nothing here is a new engine surface: every write uses database.clj verbs
;; that shipped, and every read is fram.claims/fram.world as published. The
;; verification MACHINERY (queues, outboxes, what a human reads) stays app-side,
;; per docs/archive/claims-design.md; this file only proves the substrate answers.
(require '[fram.store :as c] '[fram.schema :as s] '[fram.datalog :as d]
         '[fram.world :as w] '[fram.claims :as cl]
         '[clojure.string :as str] '[clojure.set :as set])
(load-file "database.clj")   ; new-database / commit! / select! / the world verbs (into THIS ns)

;; ---------------------------------------------------------------------------
;; harness — the worlds suites', unchanged: one claim per bar, a failures atom,
;; and a per-bar reason so a broken read names itself instead of taking the
;; whole demo down.
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

;; --- byte-level helpers -----------------------------------------------------
(defn b8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn read-bytes ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (java.io.File. (str path)))))
(defn slurp8 [path] (String. (read-bytes path) "UTF-8"))
(defn flen ^long [path] (.length (java.io.File. (str path))))
(defn sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))
(defn log-sha [path] (sha256-hex (read-bytes path)))

;; ---------------------------------------------------------------------------
;; THE REGION KEY — one identity, two encodings (the seam, in code)
;; ---------------------------------------------------------------------------
(def rk-notes    "E-101/general-notes")
(def rk-fixtures "E-101/fixture-schedule")
(def rk-finish   "A-201/finish-schedule")
(defn slot-of [rk] (str "regions/" rk))     ; the world slot: content + change
(defn subj-of [rk] (str "@region:" rk))     ; the graph subject: extracted facts

;; --- the sheets, as the ingester cut them ----------------------------------
(def notes-march (b8 (str "E-101 — GENERAL NOTES (PERMIT SET, ISSUED 2026-03-02)\n"
                          "3. All exterior receptacles shall be GFCI protected.\n"
                          "4. All branch circuit conduit shall be EMT.\n")))
(def notes-add1  (b8 (str "E-101 — GENERAL NOTES (ADDENDUM 1, ISSUED 2026-05-14)\n"
                          "3. All exterior receptacles shall be GFCI protected AND"
                          " weather-resistant, with in-use covers.\n"
                          "4. All branch circuit conduit shall be EMT.\n")))
(def notes-add2  (b8 (str "E-101 — GENERAL NOTES (ADDENDUM 2, ISSUED 2026-06-30)\n"
                          "3. All exterior receptacles shall be GFCI protected AND"
                          " weather-resistant, with in-use covers.\n"
                          "4. All branch circuit conduit shall be RMC in wet locations,"
                          " EMT elsewhere.\n")))
(def fixtures-march (b8 (str "E-101 — LIGHTING FIXTURE SCHEDULE\n"
                             "TYPE F1 — 6\" RECESSED LED — QTY 18\n")))
(def finish-march   (b8 (str "A-201 — FINISH SCHEDULE\n"
                             "WALLS — EGGSHELL LATEX OVER GYPSUM BOARD\n")))

;; the extracted readings of note 3 (the strikethrough is between these two)
(def note3-march "All exterior receptacles shall be GFCI protected.")
(def note3-add1  (str "All exterior receptacles shall be GFCI protected AND"
                      " weather-resistant, with in-use covers."))
(def note4-march "All branch circuit conduit shall be EMT.")

(def mode "100644")
(def build-spec
  {:adapter "plan-ingest" :toolchain "sha256:tc" :platform "x86_64-linux"
   :entrypoint "ingest/-main" :purpose "plan-set" :argv []
   :env {} :locale "C" :timezone "UTC" :epoch 0 :random "none" :network "none"})
;; distinct nonces: a CandidateId is (world, expected-head, nonce)-addressed.
(def n-march "0123456789abcdef0123456789abcdef")
(def n-add1  "fedcba9876543210fedcba9876543210")
(def n-add2  "aaaabbbbccccdddd0000111122223333")

;; the review views — a verifier-scoped view under the family root carries the
;; verifier's identity for free (claims_spec_test.clj §4).
(def jordan-v (cl/scoped-view cl/verified-view "jordan"))
(def jordan-r (cl/scoped-view cl/rejected-view "jordan"))
(def sam-v    (cl/scoped-view cl/verified-view "sam"))

(def scratch (str (System/getProperty "java.io.tmpdir") "/fram-addendum-demo-" (System/nanoTime)))
(.mkdirs (java.io.File. scratch))
(def demo-log (str scratch "/plan-set.log"))

;; ---------------------------------------------------------------------------
;; write helpers — EXISTING ops only, exactly as claims_spec_test.clj uses them
;; ---------------------------------------------------------------------------
(defn cid-of
  "The cid of the NEWEST live fact on (subject, predicate). Called immediately
   after a write, so it names that write even when rivals coexist."
  [db subj pred]
  (let [st (store db)]
    (apply max (live-cids-lp db (s/resolve-name st subj) (c/value-id st pred)))))

;; The subject-is-a-cid write is now the real coordinator verb — database.clj
;; `about!` (loaded above). :link takes the target's NAME (about! resolves it);
;; a fresh write returns {:ok seq :subject-cid cid :cid new-fact-cid}.

(defn withdraw!
  "Retire ONE fact by cid with the store's own supersession marker — the same
   marker retract! writes internally. Used for a withdrawn verdict and a stale
   citation: nothing is deleted, the fact simply stops being live."
  [db agent victim]
  (locking (:lock db)
    (let [st    (store db)
          since (:next-id @st)
          tx    (c/begin-tx! st agent)
          sup   (c/value! st "store-supersedes")
          mark  (c/fact! st victim sup victim tx)]
      (append-tx! db (delta-records db since tx))
      mark)))

(defn strike!
  "THE STRIKETHROUGH, atomically: assert the addendum's reading of a region fact
   AND supersede the struck one, in ONE tx authored by the addendum. The
   supersedes edge points FROM the replacement TO the victim (fram.schema's own
   replace! shape), so the log records what replaced what — not merely that
   something died."
  [db agent subj pred new-text victim]
  (locking (:lock db)
    (let [st    (store db)
          since (:next-id @st)
          tx    (c/begin-tx! st agent)
          te    (ent! db tx subj)
          new   (s/assert! st te pred new-text tx)
          sup   (c/value! st "store-supersedes")
          mark  (c/fact! st new sup victim tx)]
      (append-tx! db (delta-records db since tx))
      {:new new :mark mark})))

(defn revise!
  "One ingestion of a world, end to end and WITHOUT A CHECKOUT: open a candidate
   at the world's current head, append the region ops, seal into a Version,
   lock + build it, then promote. Returns every durable id the bars need."
  [db agent nm nonce ops]
  (let [head (world-head db nm)
        cid  (:ok (world-begin! db agent nm head nonce))
        _    (doseq [op ops] (world-append! db agent cid op))
        v    (:ok (world-seal! db agent cid))
        lock (:ok (world-lock! db v build-spec))
        rcpt (:ok (world-build! db agent lock))]
    {:from head :cid cid :version v :lock lock :receipt rcpt
     :promote (world-promote! db agent nm head cid rcpt)}))

(println "worlds + claims — the addendum demo (incremental ingestion, end to end)")
(println (str "  scratch: " scratch))

;; ===========================================================================
;; THE FIXTURE — one continuous log: a permit set, two addenda, six extracted
;; claims, and the review queue observed AT EACH GENERATION (the queue is a
;; function of the log's state at the moment it is asked, so the March answer
;; must be captured in March, not re-read in June).
;; ===========================================================================
(def job "trenton")
(def job-add1 "trenton-addendum-1")
(def job-add2 "trenton-addendum-2")

(def fx
  (delay
    (let [db   (new-database demo-log)
          root (w/version-id nil [])
          ;; ---- MARCH: ingest the permit set as world "trenton" -------------
          _    (world-create! db "ingest" job root)
          b-notes    (:ok (world-blob-put! db "ingest" notes-march))
          b-fixtures (:ok (world-blob-put! db "ingest" fixtures-march))
          b-finish   (:ok (world-blob-put! db "ingest" finish-march))
          genA (revise! db "ingest" job n-march
                        [(w/put-op (slot-of rk-notes)    mode b-notes)
                         (w/put-op (slot-of rk-fixtures) mode b-fixtures)
                         (w/put-op (slot-of rk-finish)   mode b-finish)])
          vA   (:version genA)
          ;; ---- the FACTS extracted out of each region ----------------------
          ;; region-shaped subjects: the region key IS the identity, so the same
          ;; region carries the same subject across every generation.
          fact! (fn [agent subj pred v]
                  (commit! db agent subj pred :assert v nil)
                  (cid-of db subj pred))
          f-note3 (fact! "extract" (subj-of rk-notes) "note.3" note3-march)
          f-note4 (fact! "extract" (subj-of rk-notes) "note.4" note4-march)
          f-qty   (fact! "extract" (subj-of rk-fixtures) "schedule.f1-qty" "18")
          f-wall  (fact! "extract" (subj-of rk-finish) "schedule.walls"
                         "EGGSHELL LATEX OVER GYPSUM BOARD")
          ;; ---- evidence nodes: source = the region SLOT, world = the version
          ev! (fn [nm rk span fp wld]
                (commit! db "extract" nm cl/source-pred :assert (slot-of rk) nil)
                (commit! db "extract" nm cl/region-pred :assert span nil)
                (commit! db "extract" nm cl/fingerprint-pred :assert fp nil)
                (commit! db "extract" nm cl/world-pred :assert wld nil)
                (s/resolve-name (store db) nm))
          e-gfci    (ev! "@ev:e101-note3@A"   rk-notes    "L2:C1-L2:C53" b-notes vA)
          e-emt     (ev! "@ev:e101-note4@A"   rk-notes    "L3:C1-L3:C41" b-notes vA)
          e-qty     (ev! "@ev:e101-f1qty@A"   rk-fixtures "L2:C31-L2:C33" b-fixtures vA)
          e-paint   (ev! "@ev:a201-walls@A"   rk-finish   "L2:C8-L2:C43" b-finish vA)
          e-panel   (ev! "@ev:e101-panel@A"   rk-notes    "L1:C1-L1:C52" b-notes vA)
          e-misread (ev! "@ev:e101-f1misread@A" rk-fixtures "L2:C31-L2:C34" b-fixtures vA)
          ;; ---- the extracted claims (ordinary facts) -----------------------
          claim! (fn [subj pred v] (commit! db "extract" subj pred :assert v nil)
                   (cid-of db subj pred))
          cite!  (fn [claim node] (about! db "extract" claim cl/evidence-pred :link (s/name-of (store db) node)))
          verdict! (fn [view claim] (select! db view claim) (cid-of db view "selects"))
          k-gfci    (claim! "@takeoff:E-101" "requires" "GFCI protection at every exterior receptacle")
          k-emt     (claim! "@takeoff:E-101" "requires" "EMT for all branch circuit conduit")
          k-qty     (claim! "@takeoff:E-101" "counts"   "18 type-F1 recessed fixtures")
          k-paint   (claim! "@takeoff:A-201" "specifies" "eggshell latex over gypsum board")
          k-panel   (claim! "@takeoff:E-101" "states"   "panel L1 is fed at 225 A")
          k-misread (claim! "@takeoff:E-101" "counts"   "180 type-F1 recessed fixtures")
          _ (cite! k-gfci e-gfci)
          _ (cite! k-emt e-emt)
          _ (cite! k-qty e-qty)
          _ (cite! k-paint e-paint)
          _ (cite! k-panel e-panel)
          _ (cite! k-misread e-misread)
          ;; ---- review: four verified, one left pending, one rejected -------
          s-gfci  (verdict! jordan-v k-gfci)
          _       (verdict! jordan-v k-emt)
          _       (verdict! jordan-v k-qty)
          _       (verdict! sam-v    k-paint)     ; a second reviewer, same family
          ;; k-panel stays PENDING: evidence, nobody has read it.
          s-misread (verdict! jordan-r k-misread)
          _ (about! db "jordan" k-misread cl/reason-pred :assert
                    "the schedule row reads 18, not 180")
          ;; the seq the March world was reviewed at — the as-of probe's anchor
          seq-march (current-seq db)
          ;; ---- MAY: Addendum 1 — fork the sealed head, O(1) ----------------
          len-pre-fork (flen demo-log)
          _         (world-fork! db "addendum-1" job-add1 (world-head db job))
          len-post-fork (flen demo-log)
          fork-tail (subs (slurp8 demo-log) len-pre-fork)
          fork-obs  {:head-job  (world-head db job)
                     :head-add1 (world-head db job-add1)
                     :man-job   (world-manifest db (world-head db job))
                     :man-add1  (world-manifest db (world-head db job-add1))}
          ;; overlay ONLY the reprinted region; the other two are INHERITED.
          b-notes-1 (:ok (world-blob-put! db "addendum-1" notes-add1))
          genB (revise! db "addendum-1" job-add1 n-add1
                        [(w/put-op (slot-of rk-notes) mode b-notes-1)])
          vB   (:version genB)
          ;; the strikethrough, on the fact side: assert + supersede, one tx.
          strike (strike! db "addendum-1" (subj-of rk-notes) "note.3" note3-add1 f-note3)
          ;; ---- THE QUEUE, asked the moment Addendum 1 lands ----------------
          rv-ab-at-b (set (cl/needs-reverification db vA vB))
          rv-ba-at-b (set (cl/needs-reverification db vB vA))
          ;; ---- re-verification, OPTION A: re-extract at the new head -------
          _          (withdraw! db "jordan" s-gfci)          ; the verdict is re-pended
          status-repended (cl/status db k-gfci)
          rv-ab-repended (set (cl/needs-reverification db vA vB))
          stale-cite (first (c/by-lp (store db) k-gfci (c/value-id (store db) cl/evidence-pred)))
          _          (withdraw! db "extract" stale-cite)      ; the A citation is retired
          e-gfci-B   (ev! "@ev:e101-note3@B" rk-notes "L2:C1-L2:C97" b-notes-1 vB)
          _          (cite! k-gfci e-gfci-B)
          s-gfci-B   (verdict! jordan-v k-gfci)              ; re-verified AT B
          rv-ab-final (set (cl/needs-reverification db vA vB))
          ;; ---- JUNE: Addendum 2 — the SAME region moves again --------------
          _         (world-fork! db "addendum-2" job-add2 (world-head db job-add1))
          b-notes-2 (:ok (world-blob-put! db "addendum-2" notes-add2))
          genC (revise! db "addendum-2" job-add2 n-add2
                        [(w/put-op (slot-of rk-notes) mode b-notes-2)])
          vC   (:version genC)
          rv-bc (set (cl/needs-reverification db vB vC))
          rv-ac (set (cl/needs-reverification db vA vC))]
      {:db db :log demo-log :root root :vA vA :vB vB :vC vC
       :genA genA :genB genB :genC genC
       :b-notes b-notes :b-notes-1 b-notes-1 :b-notes-2 b-notes-2
       :b-fixtures b-fixtures :b-finish b-finish
       :f-note3 f-note3 :f-note4 f-note4 :f-qty f-qty :f-wall f-wall
       :e-gfci e-gfci :e-emt e-emt :e-qty e-qty :e-paint e-paint
       :e-panel e-panel :e-misread e-misread :e-gfci-B e-gfci-B
       :k-gfci k-gfci :k-emt k-emt :k-qty k-qty :k-paint k-paint
       :k-panel k-panel :k-misread k-misread
       :s-gfci s-gfci :s-gfci-B s-gfci-B :s-misread s-misread
       :stale-cite stale-cite :strike strike :seq-march seq-march
       :fork-bytes (- len-post-fork len-pre-fork) :fork-tail fork-tail :fork-obs fork-obs
       :status-repended status-repended
       :rv-ab-at-b rv-ab-at-b :rv-ba-at-b rv-ba-at-b
       :rv-ab-repended rv-ab-repended :rv-ab-final rv-ab-final
       :rv-bc rv-bc :rv-ac rv-ac})))

;; ===========================================================================
(println "\n-- 1. MARCH: the permit set, ingested region by region --")
;; ===========================================================================
(bar "ingest: the job world sealed a content-addressed Version and promoted it"
     (let [f @fx]
       (and (re-matches #"[0-9a-f]{64}" (str (:vA f)))
            (= (:vA f) (world-head (:db f) job)))))
(bar "ingest: generation A holds exactly the three regions that were cut"
     (let [f @fx]
       (= [(slot-of rk-finish) (slot-of rk-fixtures) (slot-of rk-notes)]
          (sort (mapv :slot (world-manifest (:db f) (:vA f)))))))
(bar "ingest: each region resolves to the EXACT bytes the ingester put"
     (let [f @fx
           at (fn [rk] (:blob-id (first (filter #(= (slot-of rk) (:slot %))
                                                (world-manifest (:db f) (:vA f))))))]
       (and (= (:b-notes f) (at rk-notes))
            (java.util.Arrays/equals ^bytes notes-march
                                     ^bytes (world-blob (:db f) (at rk-notes))))))
(bar "regions: the extracted facts hang off REGION-shaped subjects (region_key identity)"
     (let [f  @fx
           st (store (:db f))]
       (= (s/resolve-name st (subj-of rk-notes)) (:l (c/fact-of st (:f-note3 f))))))
(bar "regions: the region key spells BOTH encodings — world slot and graph subject"
     (let [f  @fx
           st (store (:db f))]
       (and (some? (s/resolve-name st (subj-of rk-notes)))
            (some? (first (filter #(= (slot-of rk-notes) (:slot %))
                                  (world-manifest (:db f) (:vA f))))))))
(bar "regions: note 3 and note 4 are separate facts INSIDE one region subject"
     (let [f  @fx
           st (store (:db f))
           n3 (c/fact-of st (:f-note3 f))
           n4 (c/fact-of st (:f-note4 f))]
       (and (= (:l n3) (:l n4))                     ; one region
            (not= (:p n3) (:p n4))                  ; two notes
            (= [note3-march note4-march] [(c/literal st (:r n3)) (c/literal st (:r n4))]))))

;; ===========================================================================
(println "\n-- 2. the review at A: verified / pending / rejected --")
;; ===========================================================================
(bar "claims: every extraction cites evidence whose SOURCE is the region slot"
     (let [f @fx]
       (= [(slot-of rk-notes)] (mapv :source (cl/provenance (:db f) (:k-gfci f))))))
(bar "claims: the evidence records the generation it was extracted against"
     (let [f @fx] (= (:vA f) (:world (cl/evidence (:db f) (:e-gfci f))))))
(bar "claims: ... and the fingerprint of the region content it read"
     (let [f @fx] (= (:b-notes f) (:fingerprint (cl/evidence (:db f) (:e-gfci f))))))
(bar "claims: four extractions were VERIFIED by a reviewer-scoped view selection"
     (let [f @fx]
       (= [:verified :verified :verified :verified]
          (mapv #(cl/status (:db f) %) [(:k-gfci f) (:k-emt f) (:k-qty f) (:k-paint f)]))))
(bar "claims: the verifier is the selecting view's writing agent — no extra schema"
     (let [f @fx] (= jordan-v (cl/verifier (:db f) (:k-emt f)))))
(bar "claims: a second reviewer verifies in the SAME family under her own name"
     (let [f @fx] (= sam-v (cl/verifier (:db f) (:k-paint f)))))
(bar "claims: the unread extraction is :pending — evidence, no verdict"
     (let [f @fx] (= :pending (cl/status (:db f) (:k-panel f)))))
(bar "claims: the misread is :rejected, with the reviewer's reason on the record"
     (let [f @fx]
       (and (= :rejected (cl/status (:db f) (:k-misread f)))
            (= "the schedule row reads 18, not 180"
               (:reason (cl/rejection (:db f) (:k-misread f))))
            (= (:s-misread f) (:cid (cl/rejection (:db f) (:k-misread f)))))))

;; ===========================================================================
(println "\n-- 3. MAY: Addendum 1 forks the head in O(1) and overlays ONE region --")
;; ===========================================================================
(bar "fork: the addendum world starts AT the sealed permit set — two names, ONE Version"
     (let [f @fx] (= (:vA f) (get-in f [:fork-obs :head-add1]))))
(bar "fork: forking did NOT move the permit set's head"
     (let [f @fx] (= (:vA f) (get-in f [:fork-obs :head-job]))))
(bar "fork: NO REGION WAS COPIED — the fork's bytes name no blob, slot or Version"
     (let [f @fx
           tail (:fork-tail f)]
       (and (not (str/includes? tail (:b-notes f)))
            (not (str/includes? tail (slot-of rk-notes)))
            (not (str/includes? tail "world.version:"))
            (not (str/includes? tail "world.blob:")))))
(bar "fork: it cost UNDER 512 B — ingesting the addendum is O(1) in the plan set's size"
     (let [f @fx] (< 0 (:fork-bytes f) 512)))
(bar "fork: at fork time both names read ONE identical three-region manifest"
     (let [f @fx]
       (and (= 3 (count (get-in f [:fork-obs :man-job])))
            (= (get-in f [:fork-obs :man-job]) (get-in f [:fork-obs :man-add1])))))
(bar "overlay: generation B is SPARSE — one op over an inherited base"
     (let [f @fx
           r (world-version (:db f) (:vB f))]
       (and (= 1 (count (:overlay r)))
            (= [(slot-of rk-notes)] (mapv :slot (:overlay r)))
            (= (:vA f) (:base r)))))
(bar "overlay: the revised region resolves to the ADDENDUM's bytes at B"
     (let [f @fx
           row (first (filter #(= (slot-of rk-notes) (:slot %))
                              (world-manifest (:db f) (:vB f))))]
       (and (= (:b-notes-1 f) (:blob-id row))
            (java.util.Arrays/equals ^bytes notes-add1
                                     ^bytes (world-blob (:db f) (:blob-id row))))))
(bar "inherit: the untouched regions resolve to the SAME blobs at B, ORIGIN A"
     (let [f @fx
           rows (filter #(not= (slot-of rk-notes) (:slot %)) (world-manifest (:db f) (:vB f)))]
       (and (= 2 (count rows))
            (= #{(:b-fixtures f) (:b-finish f)} (set (map :blob-id rows)))
            (every? #(= (:vA f) (:origin %)) rows))))
(bar "seal: B is a new head on the addendum world, and a DIFFERENT Version than A"
     (let [f @fx]
       (and (= (:vB f) (world-head (:db f) job-add1))
            (not= (:vA f) (:vB f)))))

;; ===========================================================================
(println "\n-- 4. the strikethrough, on the fact side (authored by addendum-1) --")
;; ===========================================================================
(bar "strike: the addendum's reading of note 3 is now the live one"
     (let [f @fx]
       (= note3-add1 (s/lookup (store (:db f))
                               (s/resolve-name (store (:db f)) (subj-of rk-notes))
                               "note.3"))))
(bar "strike: the struck fact is NOT live — superseded, not deleted"
     (let [f @fx]
       (and (not (c/live? (store (:db f)) (:f-note3 f)))
            (map? (c/fact-of (store (:db f)) (:f-note3 f))))))
(bar "strike: the ADDENDUM authored both halves — assertion and supersedes edge"
     (let [f @fx]
       (= ["addendum-1" "addendum-1"]
          [(agent-of (:db f) (get-in f [:strike :new]))
           (agent-of (:db f) (get-in f [:strike :mark]))])))
(bar "strike: both halves landed in ONE tx — a strikethrough is atomic"
     (let [f @fx]
       (= (c/fact-tx (store (:db f)) (get-in f [:strike :new]))
          (c/fact-tx (store (:db f)) (get-in f [:strike :mark])))))
(bar "strike: the supersedes edge names WHAT replaced the struck reading"
     (let [f  @fx
           st (store (:db f))
           m  (c/fact-of st (get-in f [:strike :mark]))]
       (and (= (get-in f [:strike :new]) (:l m)) (= (:f-note3 f) (:r m)))))
(bar "strike: note 4 was NOT touched — the addendum reprinted one line, not a region"
     (let [f @fx]
       (and (c/live? (store (:db f)) (:f-note4 f))
            (= note4-march (s/lookup (store (:db f))
                                     (s/resolve-name (store (:db f)) (subj-of rk-notes))
                                     "note.4")))))
(bar "strike: the region SUBJECT is unchanged across generations (region_key identity)"
     (let [f  @fx
           st (store (:db f))]
       (= (:l (c/fact-of st (:f-note3 f))) (:l (c/fact-of st (get-in f [:strike :new]))))))

;; ===========================================================================
(println "\n-- 5. PAYOFF (a): the review queue is a QUERY over A -> B --")
;; ===========================================================================
(bar "queue: EXACTLY the verified extractions citing the reprinted region"
     (let [f @fx] (= #{(:k-gfci f) (:k-emt f)} (:rv-ab-at-b f))))
(bar "queue: ... which is region-granular — note 4's claim re-pends though note 4 held"
     (let [f @fx] (contains? (:rv-ab-at-b f) (:k-emt f))))
(bar "queue: the PENDING extraction is not in it — nothing verified, nothing invalidated"
     (let [f @fx] (not (contains? (:rv-ab-at-b f) (:k-panel f)))))
(bar "queue: the REJECTED extraction is not in it either"
     (let [f @fx] (not (contains? (:rv-ab-at-b f) (:k-misread f)))))
(bar "queue: the untouched regions' extractions are not in it"
     (let [f @fx]
       (and (not (contains? (:rv-ab-at-b f) (:k-qty f)))
            (not (contains? (:rv-ab-at-b f) (:k-paint f))))))
(bar "queue: the identity transition A -> A is EMPTY (no addendum, no work)"
     (let [f @fx] (empty? (cl/needs-reverification (:db f) (:vA f) (:vA f)))))
;; DIRECTIONAL, not chronological: the query asks "which claims were extracted at
;; FROM and cite a region that reads differently at TO". Rolling the addendum BACK
;; is that same question with the ends swapped — and at the end of this fixture
;; only the re-verified claim was extracted at B, so only it is flagged.
(bar "queue: it is a TRANSITION, not an arrow of time — rolling B -> A flags the B-cut claim"
     (let [f @fx] (= #{(:k-gfci f)} (set (cl/needs-reverification (:db f) (:vB f) (:vA f))))))
(bar "queue: ... and B -> A was EMPTY before anything had been extracted at B"
     (let [f @fx] (empty? (:rv-ba-at-b f))))
(bar "queue: it is a pure READ — asking twice is identical, and the log is unmoved"
     (let [f      @fx
           before (log-sha (:log f))
           a      (set (cl/needs-reverification (:db f) (:vA f) (:vB f)))
           b      (set (cl/needs-reverification (:db f) (:vA f) (:vB f)))]
       (and (= a b) (= before (log-sha (:log f))))))
(bar "queue: the rule is DATA — a stratified program the shipped engine runs"
     (let [f @fx
           p (cl/reverification-rules (:db f) (:vA f) (:vB f))]
       (and (vector? p) (empty? (d/strata-violations p))
            (= (:rv-ab-final f)
               (set (map first (d/facts (d/run-strata (store (:db f)) p)
                                        cl/reverification-relation)))))))

;; ===========================================================================
(println "\n-- 6. PAYOFF (a'): OPTION A — re-verify at B, and generation C re-pends --")
;; ===========================================================================
(bar "re-verify: withdrawing the stale verdict returns the claim to :pending"
     (let [f @fx] (= :pending (:status-repended f))))
(bar "re-verify: ... and a re-pended claim leaves the queue while it is being worked"
     (let [f @fx] (not (contains? (:rv-ab-repended f) (:k-gfci f)))))
(bar "re-verify: the claim FACT survived the flip — only the verdict moved"
     (let [f @fx] (c/live? (store (:db f)) (:k-gfci f))))
(bar "re-verify: re-extraction cites the ADDENDUM's content at generation B"
     (let [f @fx
           e (cl/evidence (:db f) (:e-gfci-B f))]
       (and (= (:vB f) (:world e)) (= (:b-notes-1 f) (:fingerprint e))
            (= (slot-of rk-notes) (:source e)))))
(bar "re-verify: the stale A-citation is withdrawn — the live chain is B evidence only"
     (let [f @fx] (= [(:e-gfci-B f)] (vec (cl/evidence-nodes (:db f) (:k-gfci f))))))
(bar "re-verify: ... but the withdrawn citation is STILL IN THE LOG (auditable)"
     (let [f @fx]
       (and (map? (c/fact-of (store (:db f)) (:stale-cite f)))
            (not (c/live? (store (:db f)) (:stale-cite f))))))
(bar "re-verify: the claim is :verified again, on the NEW selection"
     (let [f @fx]
       (and (= :verified (cl/status (:db f) (:k-gfci f)))
            (= (:s-gfci-B f) (:cid (cl/verdict (:db f) (:k-gfci f)))))))
(bar "queue DRAINS: A -> B now owes only the claim nobody re-verified"
     (let [f @fx] (= #{(:k-emt f)} (:rv-ab-final f))))
(bar "option A: Addendum 2 moves that region again — B -> C RE-PENDS the re-verified claim"
     (let [f @fx] (= #{(:k-gfci f)} (:rv-bc f))))
(bar "option A: work owed at A -> B stays owed THERE — B -> C does not inherit it"
     (let [f @fx] (not (contains? (:rv-bc f) (:k-emt f)))))
(bar "option A: a claim still citing A is caught by the A -> C transition"
     (let [f @fx] (= #{(:k-emt f)} (:rv-ac f))))
(bar "option A: generation C is sparse over B, and B's own history is intact"
     (let [f @fx
           r (world-version (:db f) (:vC f))]
       (and (= (:vB f) (:base r)) (= 1 (count (:overlay r)))
            (= (:vB f) (world-head (:db f) job-add1)))))

;; ===========================================================================
(println "\n-- 7. PAYOFF (b): untouched regions were never re-read --")
;; ===========================================================================
(bar "untouched: the fixture-schedule and finish-schedule claims are STILL verified"
     (let [f @fx]
       (= [:verified :verified]
          [(cl/status (:db f) (:k-qty f)) (cl/status (:db f) (:k-paint f))])))
(bar "untouched: their verdicts are the ORIGINAL March selections — nobody re-reviewed"
     (let [f @fx]
       (< (:cid (cl/verdict (:db f) (:k-qty f))) (:s-gfci-B f))))
(bar "untouched: their evidence still cites generation A, and still resolves"
     (let [f @fx
           e (cl/evidence (:db f) (:e-qty f))]
       (and (= (:vA f) (:world e))
            (= (:b-fixtures f) (:fingerprint e))
            (java.util.Arrays/equals
              ^bytes fixtures-march
              ^bytes (world-blob (:db f) (:fingerprint e))))))
(bar "untouched: their regions resolve to the same blob at A, B and C"
     (let [f @fx
           at (fn [v rk] (:blob-id (first (filter #(= (slot-of rk) (:slot %))
                                                  (world-manifest (:db f) v)))))]
       (= #{(:b-fixtures f)}
          (set (map #(at % rk-fixtures) [(:vA f) (:vB f) (:vC f)])))))
(bar "untouched: they never appeared in ANY transition queue"
     (let [f    @fx
           seen (apply set/union
                       [(:rv-ab-at-b f) (:rv-ab-final f) (:rv-bc f) (:rv-ac f)])]
       (not-any? seen [(:k-qty f) (:k-paint f)])))

;; ===========================================================================
(println "\n-- 8. PAYOFF (c): the March world still answers the March question --")
;; ===========================================================================
(bar "history: the permit-set world's head is STILL generation A after two addenda"
     (let [f @fx] (= (:vA f) (world-head (:db f) job))))
(bar "history: A's manifest still resolves the notes region to the MARCH bytes"
     (let [f @fx
           row (first (filter #(= (slot-of rk-notes) (:slot %))
                              (world-manifest (:db f) (:vA f))))]
       (and (= (:b-notes f) (:blob-id row))
            (java.util.Arrays/equals ^bytes notes-march
                                     ^bytes (world-blob (:db f) (:blob-id row))))))
(bar "history: the struck sentence is still READABLE at A — 'GFCI protected.' unqualified"
     (let [f @fx]
       (str/includes? (String. ^bytes (world-blob (:db f) (:b-notes f)) "UTF-8")
                      "3. All exterior receptacles shall be GFCI protected.\n")))
(bar "history: all three generations of the ONE region are recoverable side by side"
     (let [f @fx]
       (= 3 (count (set (map #(vec (world-blob (:db f) %))
                             [(:b-notes f) (:b-notes-1 f) (:b-notes-2 f)]))))))
(bar "history: AS OF the March review, the struck fact was the live reading"
     (let [f  @fx
           st (store (:db f))]
       (= [(:f-note3 f)]
          (vec (live-as-of-lp (:db f) (:seq-march f)
                              (s/resolve-name st (subj-of rk-notes))
                              (c/value-id st "note.3"))))))
(bar "history: ... and the addendum's reading did not exist yet at that seq"
     (let [f @fx]
       (not (contains? (live-as-of (:db f) (:seq-march f)) (get-in f [:strike :new])))))
(bar "history: the March evidence node is IMMUTABLE — same source, world, fingerprint"
     (let [f @fx
           e (cl/evidence (:db f) (:e-gfci f))]
       (and (= (:vA f) (:world e)) (= (:b-notes f) (:fingerprint e))
            (= "L2:C1-L2:C53" (:region e)))))

;; ===========================================================================
(println "\n-- 9. PAYOFF (d): the supersession is recoverable, not a hole --")
;; ===========================================================================
(bar "recover: the struck fact's text reads back out of the log verbatim"
     (let [f  @fx
           st (store (:db f))
           fc (c/fact-of st (:f-note3 f))]
       (= note3-march (c/literal st (:r fc)))))
(bar "recover: it is marked NOT LIVE at B — retired, present, attributable"
     (let [f @fx]
       (and (not (c/live? (store (:db f)) (:f-note3 f)))
            (= "extract" (agent-of (:db f) (:f-note3 f)))
            (= "addendum-1" (agent-of (:db f) (get-in f [:strike :mark]))))))
(bar "recover: the strikethrough is a DIFF — both readings, in one region subject"
     (let [f  @fx
           st (store (:db f))]
       (= [note3-march note3-add1]
          [(c/literal st (:r (c/fact-of st (:f-note3 f))))
           (c/literal st (:r (c/fact-of st (get-in f [:strike :new]))))])))
(bar "recover: the withdrawn March VERDICT is likewise recoverable, not erased"
     (let [f @fx]
       (and (map? (c/fact-of (store (:db f)) (:s-gfci f)))
            (not (c/live? (store (:db f)) (:s-gfci f)))
            (contains? (live-as-of (:db f) (:seq-march f)) (:s-gfci f)))))
(bar "recover: nothing in this demo DELETED a fact — the log only ever grew"
     (let [f  @fx
           st (store (:db f))]
       (every? #(map? (c/fact-of st %))
               [(:f-note3 f) (:s-gfci f) (:stale-cite f) (:k-gfci f)
                (get-in f [:strike :mark])])))

;; ===========================================================================
(println "\n-- 10. the whole story replays from the log bytes alone --")
;; ===========================================================================
(bar "replay: a cold restart derives the SAME three heads"
     (let [f  @fx
           db {:store (replay (:log f)) :log (:log f) :lock (Object.)}]
       (= [(:vA f) (:vB f) (:vC f)]
          (mapv #(world-head db %) [job job-add1 job-add2]))))
(bar "replay: ... and recomputes the SAME A -> B review queue"
     (let [f  @fx
           db {:store (replay (:log f)) :log (:log f) :lock (Object.)}]
       (= (:rv-ab-final f) (set (cl/needs-reverification db (:vA f) (:vB f))))))
(bar "replay: ... and the SAME claim statuses, verdicts and reasons"
     (let [f  @fx
           db {:store (replay (:log f)) :log (:log f) :lock (Object.)}]
       (and (= :verified (cl/status db (:k-gfci f)))
            (= :pending (cl/status db (:k-panel f)))
            (= "the schedule row reads 18, not 180" (:reason (cl/rejection db (:k-misread f)))))))

;; ---------------------------------------------------------------------------
(let [pass (- @total @failures)]
  (println (str "\naddendum-demo: " pass "/" @total " PASS"))
  (if (zero? @failures)
    (println "addendum-demo: ALL BARS PASS")
    (do (println (str "addendum-demo: " @failures " FAILED — these bars are the"
                      " incremental-ingestion story: fork, sparse overlay, the"
                      " transition query, and option-A re-verification."))
        (System/exit 1))))
