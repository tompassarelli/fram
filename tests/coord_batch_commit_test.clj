;; coord_batch_commit_test.clj — in-process receipt for commit-batch!, the atomic
;; multi-fact publication primitive (thread 019f9063 / incident 019f8958).
;;
;; No socket, no daemon boot: this drives coord.clj's coordinator directly, so it is
;; fast and deterministic (the socket-level boundary proofs — disconnect/timeout/
;; concurrent writers — live in coord_assert_batch_atomicity_test.clj). The bar here
;; is the primitive itself: EVERY fact validates before the FIRST mutation, accepted
;; writes land in ONE tx with ONE :commit marker (all-or-none at the durable seam),
;; and single-fact commit! is untouched.
;;
;; Run: bb -cp out tests/coord_batch_commit_test.clj
(load-file "coord.clj")

(def checks (atom []))
(defn ck! [l v] (swap! checks conj [l (boolean v)]))
(defn fresh [] (new-coord (str "/tmp/cb-" (System/nanoTime) "-" (rand-int 1e6) ".log")))

;; live values on (te,pred), resolved back to strings
(defn vals-of [co te p]
  (let [st (:store co)
        pid (fram.store/value-id st p)
        teid (fram.schema/resolve-name st te)]
    (if (and pid teid)
      (set (map #(let [r (:r (fram.store/fact-of st %))]
                   (or (fram.schema/name-of st r) (fram.store/literal st r)))
                (#'user/live-cids-lp co teid pid)))
      #{})))

;; (1) happy path — three multi facts land as one unit
(let [co (fresh)
      r (commit-batch! co "coord" "@msg:1"
                       [{:pred "from" :kind :assert :r "@a"}
                        {:pred "subject" :kind :assert :r "hi"}
                        {:pred "to" :kind :assert :r "@b"}])]
  (ck! "batch ok" (integer? (:ok r)))
  (ck! "three written" (= 3 (count (:written r))))
  (ck! "all facts live" (and (= #{"@a"} (vals-of co "@msg:1" "from"))
                             (= #{"hi"} (vals-of co "@msg:1" "subject"))
                             (= #{"@b"} (vals-of co "@msg:1" "to")))))

;; (2) all-or-none — a cyclic link mid-batch rejects; the good fact AHEAD of it in
;; the same batch must not land either, and the version must not move.
(let [co (fresh)
      v0 (current-seq co)
      r (commit-batch! co "coord" "@msg:2"
                       [{:pred "subject" :kind :assert :r "ahead"}
                        {:pred "depends_on" :kind :link :r "@msg:2"}])]
  (ck! "cycle batch rejected at the cyclic fact" (and (:reject r) (= 1 (:at r))))
  (ck! "zero facts landed" (and (empty? (vals-of co "@msg:2" "subject"))
                                (empty? (vals-of co "@msg:2" "depends_on"))))
  (ck! "version unmoved after reject" (= v0 (current-seq co))))

;; (3) base_version OCC — a stale :base on a single-valued pred rejects the whole
;; batch; the earlier fact does not leak and the prior single value is untouched.
(let [co (fresh)
      _ (commit! co "coord" "@msg:3" "body" :assert "first" nil)  ; body is single-valued
      r (commit-batch! co "coord" "@msg:3"
                       [{:pred "from" :kind :assert :r "@a"}
                        {:pred "body" :kind :assert :r "second" :base 0}])] ; base 0 < head
  (ck! "stale-base batch rejected" (= :conflict (:reject r)))
  (ck! "no partial: from absent, body unchanged"
       (and (empty? (vals-of co "@msg:3" "from"))
            (= #{"first"} (vals-of co "@msg:3" "body")))))

;; (4) idempotency — re-publishing identical multi facts writes nothing (once, not twice)
(let [co (fresh)
      _ (commit-batch! co "coord" "@msg:4" [{:pred "from" :kind :assert :r "@x"}])
      r (commit-batch! co "coord" "@msg:4" [{:pred "from" :kind :assert :r "@x"}])]
  (ck! "re-batch writes nothing" (empty? (:written r)))
  (ck! "re-batch marks idempotent" (= ["from"] (:idempotent r)))
  (ck! "single live value, no dup" (= #{"@x"} (vals-of co "@msg:4" "from"))))

;; (5) DURABLE ATOMICITY — the batch's facts all share ONE store tx. delta-records
;; emits one tx's records + ONE terminating :commit marker as a single contiguous
;; append (append-tx!), so v2 torn-tx replay drops the whole batch or none. Proving
;; the single tx-of is the reader-independent witness of that contiguity.
(let [co (new-coord (str "/tmp/cb-durable-" (System/nanoTime) ".log"))
      before (:next-seq @(:store co))
      _ (commit-batch! co "coord" "@msg:5"
                       [{:pred "from" :kind :assert :r "@a"}
                        {:pred "subject" :kind :assert :r "s"}
                        {:pred "to" :kind :assert :r "@b"}])
      st (:store co)
      cids-of (fn [te p]
                (let [pid (fram.store/value-id st p)
                      teid (fram.schema/resolve-name st te)]
                  (#'user/live-cids-lp co teid pid)))
      batch-cids (mapcat #(cids-of "@msg:5" %) ["from" "subject" "to"])
      txs (set (map #(get (:tx-of @st) %) batch-cids))
      after (:next-seq @(:store co))]
  (ck! "all batch facts share exactly one store tx" (= 1 (count txs)))
  (ck! "the batch advanced the seq by exactly one tx" (= 1 (- after before))))

;; (6) mixed idempotent+new — only the genuinely new facts are written
(let [co (fresh)
      _ (commit-batch! co "coord" "@msg:6" [{:pred "from" :kind :assert :r "@a"}])
      r (commit-batch! co "coord" "@msg:6"
                       [{:pred "from" :kind :assert :r "@a"}          ; already live
                        {:pred "subject" :kind :assert :r "new"}])]   ; new
  (ck! "mixed batch writes only the new fact" (= ["subject"] (mapv :pred (:written r))))
  (ck! "mixed batch reports the idempotent one" (= ["from"] (:idempotent r)))
  (ck! "both values live" (and (= #{"@a"} (vals-of co "@msg:6" "from"))
                               (= #{"new"} (vals-of co "@msg:6" "subject")))))

(doseq [[l ok?] @checks] (println (format "  [%s] %s" (if ok? "PASS" "FAIL") l)))
(let [failed (remove second @checks)]
  (println (format "\n%d/%d passed" (- (count @checks) (count failed)) (count @checks)))
  (when (seq failed) (System/exit 1)))
