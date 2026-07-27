;; Original-first lease behavior oracle for M5 Cut C / R3.
;;
;; coord.clj still owns every decision when this is captured. The host clock
;; seam is bound to deterministic instants; no expiry sleeps or output masks.
(load-file "coord.clj")

(let [log "/tmp/coord-lease-decision-golden.log"
      _ (spit log "")
      co (new-coord log)
      now-ms (atom 1000)]
  (binding [coord/*lease-now-ms* (fn [] @now-ms)]
    (let [granted (acquire-lease! co "holder-a" "resource" 100)
          steal-attempt (acquire-lease! co "holder-b" "resource" 100)
          held-fence (fence-ok? co "resource" "holder-a" (:epoch granted))
          _ (reset! now-ms 1050)
          renewed (renew-lease! co "holder-a" "resource" (:epoch granted) 200)
          stale-release (release-lease! co "holder-a" "resource" (:epoch granted))
          _ (reset! now-ms 1250)
          expired-fence (fence-ok? co "resource" "holder-a" (:epoch renewed))
          expired-renew (renew-lease! co "holder-a" "resource" (:epoch renewed) 200)
          successor (acquire-lease! co "holder-b" "resource" 300)
          released (release-lease! co "holder-b" "resource" (:epoch successor))]
      (prn
       {:clock-ms [1000 1050 1250]
        :scenarios
        [{:scenario :grant :result granted}
         {:scenario :steal-attempt :result steal-attempt}
         {:scenario :held-fence :result held-fence}
         {:scenario :renew :result renewed}
         {:scenario :stale-release :result stale-release}
         {:scenario :expired-fence :result expired-fence}
         {:scenario :expired-renew :result expired-renew}
         {:scenario :grant-after-expiry :result successor}
         {:scenario :release :result released}]}))))
