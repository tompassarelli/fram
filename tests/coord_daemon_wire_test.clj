(require '[coord-daemon-wire :as wire])

(def failures (atom 0))

(defn check [label pred]
  (println (if pred "PASS" "FAIL") label)
  (when-not pred (swap! failures inc)))

(let [cfg {:reload-checked? false
           :reload-deferred-ops #{:version}
           :reload-mutation-ops #{:assert}}
      clean {:durability-stop? false}]
  (check "query routes lock-free"
         (= :query (:route (wire/request-dispatch
                            {:op :query :query {}} clean cfg))))
  (check "query inside log fence has fenced-query route"
         (= :fenced-query (:route (wire/request-dispatch
                                   {:op :for-log :expected-log "x"
                                    :request {:op :query :query {}}}
                                   clean cfg))))
  (check "as-of fact lookup stays locked"
         (= :locked (:route (wire/request-dispatch
                             {:op :as-of :seq 1 :te "@a" :p "title"}
                             clean cfg))))
  (check "as-of query routes lock-free"
         (= :query (:route (wire/request-dispatch
                            {:op :as-of :seq 1 :query {}}
                            clean cfg))))
  (check "durability stop overrides every handler"
         (= :durability-stop
            (:route (wire/request-dispatch
                     {:op :version} {:durability-stop? true} cfg))))
  (check "known op maps to handler/validation/response shape"
         (= {:handler :assert :required [:te :p :r] :response :mutation}
            (select-keys
             (wire/request-dispatch
              {:op :assert :te "@a" :p "title" :r "A"} clean cfg)
             [:handler :required :response])))
  (check "required-field validation is per op"
         (= ["p is required" "r is required"]
            (wire/request-validation-errors
             {:op :assert :te "@a"}
             (wire/request-dispatch {:op :assert :te "@a"} clean cfg))))
  (check "target validation accepts te"
         (empty? (wire/request-validation-errors
                  {:op :callers :te "@a"}
                  (wire/request-dispatch {:op :callers :te "@a"} clean cfg))))
  (check "target validation rejects absent target"
         (= ["te or module+name is required"]
            (wire/request-validation-errors
             {:op :blast}
             (wire/request-dispatch {:op :blast} clean cfg))))
  (check "unknown op preserves legacy unknown-op dispatch"
         (empty? (wire/request-validation-errors
                  {:op :not-real}
                  (wire/request-dispatch {:op :not-real} clean cfg))))

  (let [start (wire/connection-start)
        query-state
        (wire/connection-transition
         start {:event :request
                :request {:op :query :query {} :fmt :json}})
        fenced-state
        (wire/connection-transition
         start {:event :request
                :request {:op :for-log :expected-log "x"
                          :request {:op :subscribe :filter {:p "title"}}}})
        rejected
        (wire/connection-transition
         start {:event :request
                :request {:op :version :fmt :json}
                :strict-reject {:code :log-fence-required}})
        handled
        (wire/connection-transition
         query-state {:event :handled :response {:ok [["x"]]}})]
    (check "connection starts at the read mechanism boundary"
           (= :reading (:phase start)))
    (check "finite query enters handle with explicit reader-monitor decision"
           (and (= :handle (:phase query-state))
                (:query query-state)
                (= :json (:format query-state))))
    (check "fenced subscription unwraps into a long-lived subscription phase"
           (and (= :subscribe (:phase fenced-state))
                (:fenced-subscription fenced-state)
                (= :subscribe (get-in fenced-state [:actual :op]))))
    (check "pre-handler rejection enters reply with its selected envelope"
           (and (= :reply (:phase rejected))
                (= :log-fence-required (get-in rejected [:response :code]))))
    (check "handler result advances the same connection state to reply"
           (and (= :reply (:phase handled))
                (= {:ok [["x"]]} (:response handled))))
    (check "reply completion is terminal"
           (= :done
              (:phase
               (wire/connection-transition handled {:event :replied}))))))

(println "coord_daemon_wire:" (- 16 @failures) "/ 16 PASS")
(System/exit (if (zero? @failures) 0 1))
