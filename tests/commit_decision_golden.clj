;; Original-first directional golden for M5 Cut B.
;;
;; Capture this while database.clj still owns the OCC decision. The post-port test
;; feeds the recorded snapshot + proposal to the pure Beagle decision function
;; and requires the same conflict envelope.
(require '[fram.store :as c] '[fram.schema :as s])
(load-file "database.clj")

(let [log "/tmp/commit-plan-decision-golden.log"
      db (new-database log)
      _ (register-pred! db "status" "single" "literal")
      first-write (commit! db "writer-a" "@work" "status" :assert "open" nil)
      expected-version (:ok first-write)
      _ (commit! db "writer-b" "@work" "status" :assert "done" expected-version)
      st (store db)
      te (s/resolve-name st "@work")
      pid (c/value-id st "status")
      live (live-cids-lp db te pid)
      snapshot {:version (current-seq db)
                :base-version (base-version db te pid)
                :live-values (mapv (fn [cid]
                                     (c/literal st (:r (c/fact-of st cid))))
                                   live)}
      proposal [{:pred "status"
                 :kind :assert
                 :r "stale"
                 :base expected-version}]
      before @st
      decision (commit! db "writer-c" "@work" "status"
                        :assert "stale" expected-version)
      after @st]
  (prn {:scenario :same-group-stale-write
        :snapshot snapshot
        :proposal proposal
        :original-decision (select-keys decision [:reject :version])
        :state-unchanged (= before after)}))
