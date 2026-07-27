;; Original-first directional golden for M5 Cut B.
;;
;; Capture this while coord.clj still owns the OCC decision. The post-port test
;; feeds the recorded snapshot + proposal to the pure Beagle decision function
;; and requires the same conflict envelope.
(require '[fram.store :as c] '[fram.schema :as s])
(load-file "coord.clj")

(let [log "/tmp/coord-commit-decision-golden.log"
      co (new-coord log)
      _ (register-pred! co "status" "single" "literal")
      first-write (commit! co "writer-a" "@work" "status" :assert "open" nil)
      expected-version (:ok first-write)
      _ (commit! co "writer-b" "@work" "status" :assert "done" expected-version)
      st (store co)
      te (s/resolve-name st "@work")
      pid (c/value-id st "status")
      live (live-cids-lp co te pid)
      snapshot {:version (current-seq co)
                :base-version (base-version co te pid)
                :live-values (mapv (fn [cid]
                                     (c/literal st (:r (c/fact-of st cid))))
                                   live)}
      proposal [{:pred "status"
                 :kind :assert
                 :r "stale"
                 :base expected-version}]
      before @st
      decision (commit! co "writer-c" "@work" "status"
                        :assert "stale" expected-version)
      after @st]
  (prn {:scenario :same-group-stale-write
        :snapshot snapshot
        :proposal proposal
        :original-decision (select-keys decision [:reject :version])
        :state-unchanged (= before after)}))
