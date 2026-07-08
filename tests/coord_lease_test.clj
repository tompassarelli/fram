;; coord_lease_test.clj — the exclusive-lease arm (b): real mutual exclusion, fencing.
;;   cd ~/code/fram-lease && bb -cp out tests/coord_lease_test.clj
;; Scratch /tmp logs only; never the live coordinator.
(require '[fram.store :as c] '[fram.schema :as s])
(load-file "coord.clj")
(def pass (atom 0)) (def fail (atom 0))
(defn check [nm ok] (if ok (do (swap! pass inc) (println "  [PASS]" nm))
                            (do (swap! fail inc) (println "  [FAIL]" nm))))
(defn log [t] (str "/tmp/lease-" t "-" (System/currentTimeMillis) ".log"))

(println "## (b) exclusive-acquire-arm")

;; 1. a held, non-expired lease rejects a 2nd acquirer (the mutual exclusion base_version lacked)
(let [co (new-coord (log "t1"))
      a (acquire-lease! co "A" "R" 10000)
      b (acquire-lease! co "B" "R" 10000)]
  (check "held lease rejects a 2nd acquirer (:held)" (and (:ok a) (= :held (:reject b)) (= "A" (:holder b)))))

;; 2. the refreshed-stomp hole is CLOSED — contrast plain single-valued (lost-update) vs the lease
(let [co (new-coord (log "t2"))]
  (register-pred! co "held_by" "single" "literal")
  (let [pa (commit! co "A" "L" "held_by" :assert "A" (current-seq co))
        pb (commit! co "B" "L" "held_by" :assert "B" (current-seq co))      ; plain: B STOMPS A (both :ok)
        la (acquire-lease! co "A" "R2" 10000)
        lb (acquire-lease! co "B" "R2" 10000)]                              ; lease: B is REJECTED
    (check "plain single-valued = lost-update (both writers :ok, 2nd stomps)" (and (:ok pa) (:ok pb)))
    (check "lease CLOSES the stomp (2nd acquirer :held, not a stomp)" (and (:ok la) (= :held (:reject lb))))))

;; 3. a lapsed lease is reacquired by the next acquirer's own commit (no sweeper), epoch bumps
(let [co (new-coord (log "t3"))
      a (acquire-lease! co "A" "R" 1)]            ; 1ms ttl
  (Thread/sleep 25)
  (let [b (acquire-lease! co "B" "R" 10000)]
    (check "lapsed lease reacquired by next acquirer; epoch monotonic"
           (and (:ok a) (:ok b) (= "B" (:holder b)) (> (:epoch b) (:epoch a))))))

;; 4a. fencing while held: current holder+epoch ok; stale epoch or wrong holder rejected
(let [co (new-coord (log "t4a"))
      a (acquire-lease! co "A" "R" 10000)
      e (:epoch a)]
  (check "fence ok: current holder + current epoch" (fence-ok? co "R" "A" e))
  (check "fence reject: stale epoch (e-1) even while held" (not (fence-ok? co "R" "A" (dec e))))
  (check "fence reject: wrong holder" (not (fence-ok? co "R" "B" e))))

;; 4b. fencing across re-acquire (paused-then-woken holder): A's old token is rejected
(let [co (new-coord (log "t4b"))
      a (acquire-lease! co "A" "R" 1)]            ; A holds epoch e1, lapses fast
  (Thread/sleep 25)
  (let [b (acquire-lease! co "B" "R" 10000)]      ; B reacquires -> epoch bumps
    (check "fence REJECTS woken A's stale token after B re-acquired" (not (fence-ok? co "R" "A" (:epoch a))))
    (check "fence ok for B's fresh token" (fence-ok? co "R" "B" (:epoch b)))))

;; 5. THE race: N acquirers contend for the SAME resource concurrently -> EXACTLY ONE wins
;;    (vs the lost-update load test where ALL "won"). This is the mutex property under real concurrency.
(let [co (new-coord (log "t5"))
      N 32
      rs (mapv deref (mapv (fn [i] (future (acquire-lease! co (str "w" i) "HOT" 60000))) (range N)))
      oks (count (filter :ok rs))
      held (count (filter #(= :held (:reject %)) rs))]
  (check (str "concurrent acquire of one resource: EXACTLY ONE winner (" oks " ok / " held " held / N=" N ")")
         (and (= 1 oks) (= (dec N) held))))

(println (str "\nstore-lease: " @pass " / " (+ @pass @fail) " PASS"))
(when (pos? @fail) (System/exit 1))
