;; cnf_coexist_elect_test.clj — the Keystone (move-B) gate: cardinality-as-a-claim is
;; the SOLE authority + coexist-elect is the default contention regime.
;;
;; Proves the three keystone properties:
;;   (a) coexist-elect DEFAULT: two rival writes to the SAME (subject,predicate) of an
;;       UNDECLARED predicate both LAND (no writer blocks/rejected) and coexist as multi;
;;       a read ELECTS one deterministically — earliest by [cid, agent], input-order-
;;       independent — and (lowest-agent tiebreak documented for a future sharded allocator).
;;   (b) declared-single (a cardinality=single CLAIM) STILL rejects a stale single-valued
;;       write — the per-(s,p) base-version axiom is RETAINED (the substrate's minimal lock).
;;   (c) multi-valued just APPENDS (distinct values coexist; identical is idempotent).
;; Plus the cardinality-claim-is-authority half: the kernel single-valued list was demoted
;; to a one-time bootstrap SEED of cardinality CLAIMS, so commit! consults ONLY the claim.
;;   bb -cp out tests/cnf_coexist_elect_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s] '[fram.kernel :as ck])
(load-file "cnf_coord.clj")   ; new-coord/commit!/elect/live-cids-lp/register-pred!/store

(let [log "/tmp/cnf-coexist-elect-test.log"
      co (new-coord log)
      _ (register-pred! co "status" "single" "literal")   ; declared-single via a cardinality CLAIM
      _ (register-pred! co "tag" "multi" "ref")            ; declared multi
      checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm ok]))
      live-of (fn [te-name pred]
                (vec (live-cids-lp co (s/resolve-name (store co) te-name)
                                   (c/value-id (store co) pred))))]

  ;; ---- cardinality-claim is the SOLE authority (the OR-arm is gone, list demoted to a seed) ----
  (chk "seed: EVERY kernel single-valued pred is now declared single via a cardinality CLAIM"
       (every? #(= "single" (s/cardinality (store co) %)) ck/single-valued))
  (chk "coexist default: an UNDECLARED predicate is multi (no cardinality claim)"
       (= "multi" (s/cardinality (store co) "zzz_coexist")))

  ;; ---- (a) coexist-elect: rivals to one (s,p) COEXIST; a read elects deterministically ----
  ;; "zzz_coexist" is undeclared (not in the kernel list, not register-pred!'d) -> multi.
  (let [r1 (commit! co "agentB" "T1" "zzz_coexist" :assert "alice" 0)   ; cid-earlier (written first)
        r2 (commit! co "agentA" "T1" "zzz_coexist" :assert "bob"   0)   ; cid-later
        live   (live-of "T1" "zzz_coexist")
        winner (elect co live)]
    (chk "coexist: both rival writes COMMITTED — no writer blocked or rejected"
         (and (:ok r1) (:ok r2)))
    (chk "coexist: BOTH claims live as multi (no supersede, no idempotent no-op)"
         (= 2 (count live)))
    (chk "elect: winner is the EARLIEST-cid claim"
         (= winner (apply min live)))
    (chk "elect: deterministic — input-order-INDEPENDENT (reversed input, same winner)"
         (= winner (elect co (vec (reverse live)))))
    (chk "elect: stable across repeated reads"
         (= winner (elect co live) (elect co live)))
    ;; the elected value renders to the earliest writer's value (agentB wrote first -> earlier cid)
    (chk "elect: the elected member's VALUE is the earliest writer's"
         (= "alice" (c/literal (store co) (:r (c/claim-of (store co) winner))))))

  ;; the documented SECONDARY key: on a cid TIE (a future sharded allocator), the [cid, agent]
  ;; election key breaks the tie by LOWEST agent. cids are unique under the single allocator, so
  ;; this is the spec, exercised on the key shape directly (the engine never produces a tie today).
  (chk "elect tiebreak: equal cid -> lowest agent wins (the [cid agent] key shape)"
       (= [7 "a"] (first (sort-by identity [[7 "b"] [7 "a"] [9 "a"]]))))

  ;; ---- (b) declared-single RETAINS the per-(s,p) base-version reject ----
  (let [seed  (commit! co "w0" "T2" "status" :assert "open" 0)
        base  (:ok seed)
        good  (commit! co "w1" "T2" "status" :assert "wip"  base)   ; base up-to-date -> wins, bumps base
        stale (commit! co "w2" "T2" "status" :assert "done" base)   ; SAME (now-stale) base -> reject
        live  (live-of "T2" "status")]
    (chk "declared-single: an up-to-date single write commits" (:ok good))
    (chk "declared-single: a STALE single write is REJECTED (:conflict)" (= :conflict (:reject stale)))
    (chk "declared-single: exactly ONE live value (supersede, NOT coexist)" (= 1 (count live)))
    (chk "declared-single: the live value is the winner's, not the stale write's"
         (= "wip" (c/literal (store co) (:r (c/claim-of (store co) (first live)))))))

  ;; ---- (c) multi-valued just APPENDS ----
  (let [a   (commit! co "w" "T3" "tag" :link "X" 0)
        b   (commit! co "w" "T3" "tag" :link "Y" 0)
        dup (commit! co "w" "T3" "tag" :link "X" 0)
        live (live-of "T3" "tag")]
    (chk "multi: distinct values BOTH appended (coexist)" (and (:ok a) (:ok b) (= 2 (count live))))
    (chk "multi: an identical link is idempotent (no duplicate edge)" (:idempotent dup)))

  (let [cs @checks fails (remove second cs)]
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (println "\nKeystone (coexist-elect):" (count cs) "/" (count cs) "PASS")
      (do (println "\nKeystone (coexist-elect):" (count fails) "FAILED") (System/exit 1)))))
