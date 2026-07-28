(require '[coord-daemon-wire :as wire])

(def failures (atom 0))

(defn check [label pred]
  (println (if pred "PASS" "FAIL") label)
  (when-not pred (swap! failures inc)))

(let [cfg {:reload-checked? false
           :reload-deferred-ops #{:version}
           :reload-mutation-ops #{:assert :assert-existing
                                  :retract-existing}}
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
  (check "existing-subject ops are locked mutation handlers"
         (every?
          (fn [[op handler]]
            (= {:route :locked
                :handler handler
                :required [:te :p :r]
                :response :mutation
                :reload-policy :mutation}
               (select-keys
                (wire/request-dispatch
                 {:op op :te "@a" :p "title" :r "A"} clean cfg)
                [:route :handler :required :response :reload-policy])))
          [[:assert-existing :assert-existing]
           [:retract-existing :retract-existing]]))
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
               (wire/connection-transition handled {:event :replied})))))

  (let [ceilings {:timeout-ms 5000
                  :max-steps 10000000
                  :max-rows 1000
                  :max-response-bytes 65536}
        lowered (wire/query-limit-plan
                 {:query-timeout-ms 50
                  :query-max-steps 200
                  :query-max-rows 10
                  :query-max-response-bytes 1024}
                 ceilings 1000000)
        raised (wire/query-limit-plan
                {:query-timeout-ms 6000
                 :query-max-steps 20000000
                 :query-max-rows 2000
                 :query-max-response-bytes 999999}
                ceilings 1000000)
        invalid (wire/query-limit-plan
                 {:query-timeout-ms 0
                  :query-max-steps "many"}
                 ceilings 1000000)]
    (check "query requests lower every server backpressure ceiling"
           (= [50 200 10 1024]
              (mapv (fn [key] (get lowered key))
                    [:timeout-ms :max-steps :max-rows :max-response-bytes])))
    (check "query requests cannot raise server backpressure ceilings"
           (= [5000 10000000 1000 65536]
              (mapv (fn [key] (get raised key))
                    [:timeout-ms :max-steps :max-rows :max-response-bytes])))
    (check "limit plan uses explicit clock and rejects invalid overrides"
           (and (= 5001000000 (:deadline-ns invalid))
                (= 5000 (:timeout-ms invalid))
                (= 10000000 (:max-steps invalid)))))

  (let [bad (wire/connection-error-selection
             :request :throwable {:type :edn-too-deep} "Exception")
        timeout (wire/connection-error-selection
                 :request :socket-timeout nil "SocketTimeoutException")
        setup (wire/connection-error-selection
               :connection :throwable nil "IOException")]
    (check "request failure selects the legacy bad-request envelope"
           (= {:action :reply
               :response {:error "bad request: :edn-too-deep"}}
              bad))
    (check "slow request selects close without a reply"
           (= {:action :close} timeout))
    (check "pre-writer connection failure always selects close"
           (= {:action :close} setup))))

(println "coord_daemon_wire:" (- 22 @failures) "/ 22 PASS")
(System/exit (if (zero? @failures) 0 1))
