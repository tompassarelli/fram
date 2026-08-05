;; writer_lease_test.clj — the exclusive-lease arm (b): real mutual exclusion, fencing.
;;   cd ~/code/fram-lease && bb -cp out tests/writer_lease_test.clj
;; Scratch /tmp logs only; never the live writer authority.
(require '[fram.store :as c] '[fram.schema :as s])
(load-file "database.clj")
(def pass (atom 0)) (def fail (atom 0))
(defn check [nm ok] (if ok (do (swap! pass inc) (println "  [PASS]" nm))
                            (do (swap! fail inc) (println "  [FAIL]" nm))))
(defn log [t] (str "/tmp/lease-" t "-" (System/currentTimeMillis) ".log"))

(println "## (b) exclusive-acquire-arm")

;; 1. a held, non-expired lease rejects a 2nd acquirer (the mutual exclusion base_version lacked)
(let [db (new-database (log "t1"))
      a (acquire-lease! db "A" "R" 10000)
      b (acquire-lease! db "B" "R" 10000)]
  (check "held lease rejects a 2nd acquirer (:held)" (and (:ok a) (= :held (:reject b)) (= "A" (:holder b)))))

;; 2. the refreshed-stomp hole is CLOSED — contrast plain single-valued (lost-update) vs the lease
(let [db (new-database (log "t2"))]
  (register-pred! db "held_by" "single" "literal")
  (let [pa (commit! db "A" "L" "held_by" :assert "A" (current-seq db))
        pb (commit! db "B" "L" "held_by" :assert "B" (current-seq db))      ; plain: B STOMPS A (both :ok)
        la (acquire-lease! db "A" "R2" 10000)
        lb (acquire-lease! db "B" "R2" 10000)]                              ; lease: B is REJECTED
    (check "plain single-valued = lost-update (both writers :ok, 2nd stomps)" (and (:ok pa) (:ok pb)))
    (check "lease CLOSES the stomp (2nd acquirer :held, not a stomp)" (and (:ok la) (= :held (:reject lb))))))

;; 3. a lapsed lease is reacquired by the next acquirer's own commit (no sweeper), epoch bumps
(let [db (new-database (log "t3"))
      a (acquire-lease! db "A" "R" 1)]            ; 1ms ttl
  (Thread/sleep 25)
  (let [b (acquire-lease! db "B" "R" 10000)]
    (check "lapsed lease reacquired by next acquirer; epoch monotonic"
           (and (:ok a) (:ok b) (= "B" (:holder b)) (> (:epoch b) (:epoch a))))))

;; 4a. fencing while held: current holder+epoch ok; stale epoch or wrong holder rejected
(let [db (new-database (log "t4a"))
      a (acquire-lease! db "A" "R" 10000)
      e (:epoch a)]
  (check "fence ok: current holder + current epoch" (fence-ok? db "R" "A" e))
  (check "fence reject: stale epoch (e-1) even while held" (not (fence-ok? db "R" "A" (dec e))))
  (check "fence reject: wrong holder" (not (fence-ok? db "R" "B" e))))

;; 4b. fencing across re-acquire (paused-then-woken holder): A's old token is rejected
(let [db (new-database (log "t4b"))
      a (acquire-lease! db "A" "R" 1)]            ; A holds epoch e1, lapses fast
  (Thread/sleep 25)
  (let [b (acquire-lease! db "B" "R" 10000)]      ; B reacquires -> epoch bumps
    (check "fence REJECTS woken A's stale token after B re-acquired" (not (fence-ok? db "R" "A" (:epoch a))))
    (check "fence ok for B's fresh token" (fence-ok? db "R" "B" (:epoch b)))))

;; 4c. renewal is exact-epoch continuation, not same-holder reacquisition. A
;; successful renewal rotates the fencing token; every prior token is stale.
(let [db (new-database (log "t4c"))
      first (acquire-lease! db "A" "R" 10000)
      wrong-holder (renew-lease! db "B" "R" (:epoch first) 20000)
      wrong-epoch (renew-lease! db "A" "R" (inc (:epoch first)) 20000)
      renewed (renew-lease! db "A" "R" (:epoch first) 20000)
      stale-release (release-lease! db "A" "R" (:epoch first))
      fresh-before-release (fence-ok? db "R" "A" (:epoch renewed))
      fresh-release (release-lease! db "A" "R" (:epoch renewed))]
  (check "renew rejects a wrong holder and wrong expected epoch"
         (and (= :fence-lost (:reject wrong-holder))
              (= :fence-lost (:reject wrong-epoch))))
  (check "renew persists a globally newer coherent epoch and later expiry"
         (and (:ok renewed)
              (= (:ok renewed) (:epoch renewed))
              (> (:epoch renewed) (:epoch first))
              (> (:exp renewed) (:exp first))))
  (check "renew invalidates the old epoch for fencing and stale release"
         (and (not (fence-ok? db "R" "A" (:epoch first)))
              (:noop stale-release)
              fresh-before-release))
  (check "only the renewed epoch releases the lease"
         (and (:ok fresh-release)
              (not (fence-ok? db "R" "A" (:epoch renewed))))))

;; 4d. an expired lease cannot be resurrected by renew, before or after a
;; competitor takes over. Invalid numeric/string inputs fail without a write.
(let [db (new-database (log "t4d"))
      base (current-seq db)
      invalid-acquire (acquire-lease! db "bad|holder" "R" 10000)
      invalid-ttl (acquire-lease! db "A" "R" 0)
      overflow-ttl (acquire-lease! db "A" "R" Long/MAX_VALUE)
      after-invalid (current-seq db)
      stable (acquire-lease! db "A" "stable" 10000)
      before-invalid-renew (current-seq db)
      invalid-epoch (renew-lease! db "A" "stable" 0 10000)
      invalid-renew-ttl (renew-lease! db "A" "stable" (:epoch stable) 0)
      after-invalid-renew (current-seq db)
      first (acquire-lease! db "A" "expiring" 1)]
  (Thread/sleep 25)
  (let [expired-renew (renew-lease! db "A" "expiring" (:epoch first) 10000)
        successor (acquire-lease! db "B" "expiring" 10000)
        post-takeover (renew-lease! db "A" "expiring" (:epoch first) 10000)]
    (check "hostile acquire inputs reject without advancing the graph"
           (and (= :invalid-lease-request (:reject invalid-acquire))
                (= :invalid-lease-request (:reject invalid-ttl))
                (= :invalid-lease-request (:reject overflow-ttl))
                (= base after-invalid)))
    (check "hostile renew epoch/TTL reject without replacing the lease"
           (and (= :invalid-lease-request (:reject invalid-epoch))
                (= :invalid-lease-request (:reject invalid-renew-ttl))
                (= before-invalid-renew after-invalid-renew)
                (fence-ok? db "stable" "A" (:epoch stable))))
    (check "expired and post-takeover renewals never reacquire"
           (and (= :fence-lost (:reject expired-renew))
                (:ok successor)
                (= :fence-lost (:reject post-takeover))
                (fence-ok? db "expiring" "B" (:epoch successor))))))

;; 5. THE race: N acquirers contend for the SAME resource concurrently -> EXACTLY ONE wins
;;    (vs the lost-update load test where ALL "won"). This is the mutex property under real concurrency.
(let [db (new-database (log "t5"))
      N 32
      rs (mapv deref (mapv (fn [i] (future (acquire-lease! db (str "w" i) "HOT" 60000))) (range N)))
      oks (count (filter :ok rs))
      held (count (filter #(= :held (:reject %)) rs))]
  (check (str "concurrent acquire of one resource: EXACTLY ONE winner (" oks " ok / " held " held / N=" N ")")
         (and (= 1 oks) (= (dec N) held))))

;; 6. release erases the lease cell, but must never erase epoch history. The
;; globally assigned transaction sequence makes a same-holder successor fresh,
;; and an epoch-aware stale release cannot delete it.
(let [db (new-database (log "t6"))
      first (acquire-lease! db "A" "R" 10000)
      released (release-lease! db "A" "R" (:epoch first))
      second (acquire-lease! db "A" "R" 10000)
      stale-release (release-lease! db "A" "R" (:epoch first))]
  (check "release then same-holder reacquire receives a globally newer epoch"
         (and (:ok first) (:ok released) (:ok second)
              (> (:epoch second) (:epoch first))))
  (check "stale epoch-aware release cannot remove same-holder successor"
         (and (:noop stale-release)
              (fence-ok? db "R" "A" (:epoch second)))))

;; 7. The epoch source survives process replacement because transaction
;; sequences are recovered from the durable log, not from the live lease cell.
(let [path (log "t7")
      first-db (new-database path)
      first (acquire-lease! first-db "A" "R" 10000)
      released (release-lease! first-db "A" "R" (:epoch first))
      replayed-db {:store (replay path) :log path :lock (Object.)}
      second (acquire-lease! replayed-db "A" "R" 10000)]
  (check "release then replay then same-holder reacquire preserves epoch monotonicity"
         (and (:ok first) (:ok released) (:ok second)
              (> (:epoch second) (:epoch first))
              (not (fence-ok? replayed-db "R" "A" (:epoch first)))
              (fence-ok? replayed-db "R" "A" (:epoch second)))))

;; 8. Renewal is a normal durable lease transaction: replay restores the exact
;; renewed epoch/expiry, never the pre-renew token.
(let [path (log "t8")
      first-db (new-database path)
      acquired (acquire-lease! first-db "A" "R" 10000)
      renewed (renew-lease! first-db "A" "R" (:epoch acquired) 20000)
      replayed-db {:store (replay path) :log path :lock (Object.)}
      replayed (read-lease replayed-db "R")]
  (check "cold replay preserves the renewed epoch and expiry exactly"
         (and (= (:holder renewed) (:holder replayed))
              (= (:epoch renewed) (:epoch replayed))
              (= (:exp renewed) (:exp replayed))))
  (check "cold replay keeps the old epoch stale and the renewed epoch live"
         (and (not (fence-ok? replayed-db "R" "A" (:epoch acquired)))
              (fence-ok? replayed-db "R" "A" (:epoch renewed)))))

(println (str "\nstore-lease: " @pass " / " (+ @pass @fail) " PASS"))
(when (pos? @fail) (System/exit 1))
