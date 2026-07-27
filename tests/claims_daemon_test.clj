;; claims_daemon_test.clj — fram.claims is usable through the durable daemon
;; boundary, including cold replay of cid-subject evidence and cid-object verdicts.
(require '[clojure.java.io :as io])
(load-file "coord_daemon.clj")

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

(println (str "\nclaims-daemon: " (- @total @failures) "/" @total " passed"))
(when (pos? @failures)
  (System/exit 1))
