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
                  (wire/request-dispatch {:op :not-real} clean cfg)))))

(println "coord_daemon_wire:" (- 10 @failures) "/ 10 PASS")
(System/exit (if (zero? @failures) 0 1))
