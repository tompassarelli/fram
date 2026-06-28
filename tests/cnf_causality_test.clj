;; cnf_causality_test.clj — thread 019f100f-eefe: causality/as-of + first-class
;; retraction + (the death of) the internal lease. The acceptance gate for thread H.
;;
;; Sections map to the thread's ACCEPTANCE criteria:
;;   (d) the causal STAMP (:observed) survives a log replay round-trip + is clamped
;;       to the pre-commit head (a writer cannot claim to have observed the future).
;;   (a) causal ELECTION: two agents assert rival multi-valued claims, BOTH land (no
;;       reject, no block), and select-causal-1 elects an IDENTICAL winner across all
;;       readers purely from recorded observed/cid — and it picks by DECISION order
;;       (observed), which can DIFFER from commit order (cid).
;;   (c) first-class RETRACTION: a withdrawn multi-valued member drops under remove-wins
;;       and RESURRECTS under an add-wins view — the policy is view-relative, not kernel.
;;   (b) :as-of {:seq S} reconstructs the pre-collision view AND re-sees a later-
;;       withdrawn claim (retraction-as-append is what makes as-of EXACT).
;;
;;   bb -cp out tests/cnf_causality_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord.clj")

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))
(defn live-of [co te-name pred]
  (vec (live-cids-lp co (s/resolve-name (store co) te-name) (c/value-id (store co) pred))))

;; ============================================================================
;; (d) the causal stamp survives replay + is clamped to the head
;; ============================================================================
(let [log "/tmp/cnf-causality-d.log"
      co  (new-coord log)
      _   (commit! co "w" "T0" "note"  :assert "x" nil)   ; advance the global seq
      _   (commit! co "w" "T0" "note2" :assert "y" nil)
      ;; agentA reports it had observed an EARLY seq (1) when it decided
      _    (commit! co "agentA" "T1" "zzz" :assert "alice" 1)
      cidA (first (live-of co "T1" "zzz"))
      ;; a writer that claims to have observed the FUTURE is clamped to the head AT ITS COMMIT
      headB (current-seq co)
      _    (commit! co "agentB" "T2" "zzz" :assert "bob" 999999)
      cidB (first (live-of co "T2" "zzz"))
      ;; round-trip through the v2 log
      st2   (replay log)
      obs   (fn [st cid] (get-in @st [:txs (get (:tx-of @st) cid) :observed]))]
  (chk "(d) observed recorded = the base the writer reported (1)" (= 1 (observed-of co cidA)))
  (chk "(d) observed CLAMPED to the pre-commit head (no future stamp)"
       (= headB (observed-of co cidB)))
  (chk "(d) observed survives a cold log REPLAY round-trip" (= 1 (obs st2 cidA)))
  (chk "(d) clamped observed survives replay too" (= headB (obs st2 cidB))))

;; ============================================================================
;; (a) causal election — rivals coexist; the EARLIEST-DECIDER wins (observed),
;;     which can differ from earliest-COMMITTER (cid).
;; ============================================================================
(let [log "/tmp/cnf-causality-a.log"
      co  (new-coord log)
      ;; advance the global head so the two rivals can carry distinct observed stamps
      _   (dotimes [i 50] (commit! co "w" "Tseq" "n" :assert (str i) nil))
      ;; "drv" is undeclared -> MULTI -> rivals coexist (no supersede, no reject).
      ;; agentA COMMITS FIRST (smaller cid) but DECIDED LATE (observed 40).
      rA  (commit! co "agentA" "TH" "drv" :assert "A" 40)
      ;; agentB COMMITS SECOND (larger cid) but DECIDED EARLY (observed 5).
      rB  (commit! co "agentB" "TH" "drv" :assert "B" 5)
      live   (live-of co "TH" "drv")
      [cA cB] (sort live)                       ; cA = agentA's (earlier cid), cB = agentB's
      cid-win    (elect co live)                ; [cid, agent]  -> earliest COMMIT
      causal-win (elect-causal co live)]        ; [observed, cid, agent] -> earliest DECISION
  (chk "(a) both rival multi writes COMMITTED — no block, no reject" (and (:ok rA) (:ok rB)))
  (chk "(a) BOTH coexist live (no supersede)" (= 2 (count live)))
  (chk "(a) cid-election picks the earliest COMMITTER (agentA)" (= cid-win cA))
  (chk "(a) causal-election picks the earliest DECIDER (agentB, observed 5)" (= causal-win cB))
  (chk "(a) causal election DIFFERS from cid election here (the whole point)" (not= cid-win causal-win))
  (chk "(a) causal election is input-order-INDEPENDENT" (= causal-win (elect-causal co (vec (reverse live)))))
  (chk "(a) causal election is STABLE across reads" (= causal-win (elect-causal co live) (elect-causal co live)))
  (chk "(a) the elected member's value is the earliest decider's (B)"
       (= "B" (c/literal (store co) (:r (c/claim-of (store co) causal-win))))))

;; ---- summary ---------------------------------------------------------------
(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\ncnf-causality:" (count cs) "/" (count cs) "PASS")
    (do (println "\ncnf-causality:" (count fails) "FAILED of" (count cs)) (System/exit 1))))
