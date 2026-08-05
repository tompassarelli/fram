;; claims_daemon_test.clj — fram.claims is usable through the durable daemon
;; boundary, including cold replay of cid-subject evidence and cid-object verdicts.
(require '[clojure.java.io :as io])
(load-file "server.clj")

(def failures (atom 0))
(def total (atom 0))
(defn check [label ok?]
  (swap! total inc)
  (println (str "  [" (if ok? "PASS" "FAIL") "] " label))
  (when-not ok? (swap! failures inc)))

(def scratch (str "/tmp/claims-daemon-" (System/nanoTime)))
(.mkdirs (io/file scratch))
(def log-path (str scratch "/facts.v2.log"))
(spit log-path "")

(boot! log-path)
(check "ordinary claim fact writes through the daemon"
       (:ok (handle {:op :assert
                     :te "@claim:door"
                     :p "review.assertion"
                     :r "{\"text\":\"Door 101 is 900 mm wide.\"}"})))
(check "evidence node writes through the ordinary batch surface"
       (:ok (handle {:op :assert-batch
                     :te "@evidence:door"
                     :facts [{:p "evidence.source" :r "sheet:A101"}
                             {:p "evidence.region" :r "detail:3"}
                             {:p "evidence.fingerprint" :r "sha256:abc"}]})))
(check "claim-cite rejects a missing agent (provenance is attributable)"
       (= :invalid-claim-citation
          (:code (handle {:op :claim-cite
                          :te "@claim:door"
                          :p "review.assertion"
                          :evidence "@evidence:door"}))))
(check "claim-cite links evidence to the fact cid"
       (:ok (handle {:op :claim-cite
                     :agent "extractor"
                     :te "@claim:door"
                     :p "review.assertion"
                     :evidence "@evidence:door"})))

(def pending (handle {:op :claim-read
                      :te "@claim:door"
                      :p "review.assertion"}))
(check "claim-read derives pending from native fram.claims"
       (= :pending (:status pending)))
(check "claim-read returns native provenance"
       (= [{:node (get-in pending [:provenance 0 :node])
            :source "sheet:A101"
            :region "detail:3"
            :fingerprint "sha256:abc"
            :world nil
            :name "@evidence:door"}]
          (:provenance pending)))

(check "claim-decision selects the cid into a verifier-scoped view"
       (:ok (handle {:op :claim-decision
                     :agent "alice"
                     :decision :verified
                     :te "@claim:door"
                     :p "review.assertion"})))
(check "claim-decision is idempotent"
       (:idempotent (handle {:op :claim-decision
                             :agent "alice"
                             :decision :verified
                             :te "@claim:door"
                             :p "review.assertion"})))

(def verified (handle {:op :claim-read
                       :te "@claim:door"
                       :p "review.assertion"}))
(check "claim-read derives verified and verifier provenance"
       (and (= :verified (:status verified))
            (= "@view:claim.verified:alice" (get-in verified [:verdict :view]))))
(check "claims-read batches native claim read models"
       (= :verified
          (get-in (handle {:op :claims-read
                           :claims [{:te "@claim:door"
                                     :p "review.assertion"}]})
                  [:ok 0 :status])))

;; Cold replay is the production bar: flat logs cannot preserve cid identity,
;; while the canonical v2 log must reconstruct the same claim status/provenance.
(boot! log-path)
(def replayed (handle {:op :claim-read
                       :te "@claim:door"
                       :p "review.assertion"}))
(check "cold replay preserves claim status"
       (= :verified (:status replayed)))
(check "cold replay preserves evidence provenance"
       (= "@evidence:door" (get-in replayed [:provenance 0 :name])))

;; Un-verify closes the lifecycle loop: withdraw the verdict selection(s) so
;; status derives back to pending — claim + evidence untouched, the withdrawn
;; verdict still in the log — and a fresh decision can then flip the SAME
;; claim the other way. The full pending -> verified -> pending -> rejected
;; arc through one wire surface, on replayed state.
(check "claim-unverify requires an agent"
       (= :invalid-claim-unverify
          (:code (handle {:op :claim-unverify
                          :te "@claim:door" :p "review.assertion"}))))
(def unverified (handle {:op :claim-unverify :agent "alice"
                         :te "@claim:door" :p "review.assertion"}))
(check "claim-unverify withdraws the verdict back to pending"
       (and (:ok unverified)
            (= :pending (:status unverified))
            (= 1 (count (:withdrawn unverified)))))
(check "claim-unverify is idempotent"
       (:idempotent (handle {:op :claim-unverify :agent "alice"
                             :te "@claim:door" :p "review.assertion"})))
(check "an un-verified claim can be re-decided the other way"
       (= :rejected
          (:status (handle {:op :claim-decision :agent "bob"
                            :decision :rejected
                            :reason "the schedule row reads 900, not 950"
                            :te "@claim:door" :p "review.assertion"}))))
(check "claim-read shows the rejection with its reason"
       (= "the schedule row reads 900, not 950"
          (get-in (handle {:op :claim-read
                           :te "@claim:door" :p "review.assertion"})
                  [:rejection :reason])))

;; Rival facts coexist on one (te,pred) — exactly what views exist for — and
;; the locator alone then dead-ends. The :cid every claim op returns is the
;; disambiguator; a stale/mismatched cid+locator pairing is rejected, never
;; guessed.
(handle {:op :assert :te "@claim:window" :p "review.assertion"
         :r "{\"text\":\"Window W1 is 600 mm wide.\"}"})
(handle {:op :assert :te "@claim:window" :p "review.assertion"
         :r "{\"text\":\"Window W1 is 650 mm wide.\"}"})
(def rivals
  (let [st (:store @co)]
    (vec (sort (live-cids-lp @co (s/resolve-name st "@claim:window")
                             (c/value-id st "review.assertion"))))))
(check "locator alone is ambiguous across live rivals"
       (= :claim-ambiguous
          (:code (handle {:op :claim-read
                          :te "@claim:window" :p "review.assertion"}))))
(check "an explicit :cid disambiguates the read"
       (= "{\"text\":\"Window W1 is 650 mm wide.\"}"
          (:claim (handle {:op :claim-read :cid (second rivals)}))))
(check "an explicit :cid disambiguates a citation"
       (= (second rivals)
          (:claim-cid (handle {:op :claim-cite :agent "extractor"
                               :cid (second rivals)
                               :evidence "@evidence:door"}))))
(check "an explicit :cid disambiguates a decision"
       (= :verified
          (:status (handle {:op :claim-decision :agent "carol"
                            :decision :verified :cid (second rivals)}))))
(check "a mismatched locator + :cid pairing is rejected"
       (= :claim-locator-mismatch
          (:code (handle {:op :claim-read :te "@claim:door"
                          :p "review.assertion" :cid (second rivals)}))))

;; The transition rule on the wire: bare cids, composable with :claim-read
;; {:cid ...}. Worlds-optional: this store never called a world verb, so the
;; answer is empty, not a false alarm.
(check "claims-needing-reverification validates its inputs"
       (= :invalid-reverification-read
          (:code (handle {:op :claims-needing-reverification :from "vA"}))))
(check "claims-needing-reverification is worlds-optional (empty, no false alarm)"
       (= [] (:ok (handle {:op :claims-needing-reverification
                           :from "vA" :to "vB"}))))

(def flat-path (str scratch "/facts.flat.log"))
(spit flat-path "")
(boot-flat! flat-path)
(handle {:op :assert
         :te "@claim:flat"
         :p "review.assertion"
         :r "{\"text\":\"flat\"}"})
(handle {:op :assert-batch
         :te "@evidence:flat"
         :facts [{:p "evidence.source" :r "flat"}]})
(check "serve-flat fails closed for cid-dependent claim writes"
       (= :claims-require-v2-log
          (:code (handle {:op :claim-cite
                          :agent "extractor"
                          :te "@claim:flat"
                          :p "review.assertion"
                          :evidence "@evidence:flat"}))))
(check "serve-flat fails closed for claim-unverify"
       (= :claims-require-v2-log
          (:code (handle {:op :claim-unverify :agent "alice"
                          :te "@claim:flat" :p "review.assertion"}))))

(println (str "\nclaims-daemon: " (- @total @failures) "/" @total " passed"))
(when (pos? @failures)
  (System/exit 1))
