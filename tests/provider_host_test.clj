(require '[coord-daemon-wire :as wire]
         '[fram.provider-host :as host]
         '[fram.types :as t])

(def failures (atom []))
(defn check! [label value]
  (println (if value "  [PASS]" "  [FAIL]") label)
  (when-not value (swap! failures conj label)))
(defn throws? [f]
  (try (f) false (catch Throwable _ true)))

(def worlds (host/capability "worlds" 1))
(def codegraph (host/capability "codegraph" 1))
(def worlds-id (host/provider-identity worlds "fram.worlds/v1"))
(def codegraph-id (host/provider-identity codegraph "fram.codegraph/v1"))
(def worlds-provider
  (host/descriptor worlds-id worlds (host/provider-contract worlds 1)
                   [] :provider/ready))
(def codegraph-provider
  (host/descriptor codegraph-id codegraph (host/provider-contract codegraph 1)
                   [worlds] :provider/ready))

(println "provider host — boot selection and typed FRAMRPC seam")

(let [boot (host/boot-providers [worlds-provider codegraph-provider]
                                [worlds codegraph])]
  (check! "one ready implementation per enabled capability boots"
          (and (host/boot-ok? boot)
               (= [worlds-provider codegraph-provider]
                  (host/providerboot-providers boot))))
  (check! "provider lookup returns the boot-selected implementation"
          (= codegraph-provider (host/provider-for boot codegraph)))
  (check! "boot emits only the stable provider/boot stage hook"
          (= [:provider/boot :provider/boot]
             (mapv host/providerstage-stage
                   (host/providerboot-stages boot)))))

(let [duplicate
      (host/descriptor
       (host/provider-identity worlds "fake-second")
       worlds (host/provider-contract worlds 1) [] :provider/ready)
      boot (host/boot-providers [worlds-provider duplicate] [worlds])]
  (check! "duplicate capability implementations fail boot closed"
          (and (not (host/boot-ok? boot))
               (empty? (host/providerboot-providers boot))
               (some #(= :provider/capability-cardinality
                         (host/providerviolation-code %))
                     (host/providerboot-violations boot)))))

(let [duplicate-id
      (host/descriptor worlds-id codegraph (host/provider-contract codegraph 1)
                       [worlds] :provider/ready)
      boot (host/boot-providers [worlds-provider duplicate-id]
                                [worlds codegraph])]
  (check! "one provider identity cannot own two selected capabilities"
          (and (not (host/boot-ok? boot))
               (some #(= :provider/duplicate-identity
                         (host/providerviolation-code %))
                     (host/providerboot-violations boot)))))

(let [missing-import
      (host/descriptor codegraph-id codegraph (host/provider-contract codegraph 1)
                       [worlds] :provider/ready)
      boot (host/boot-providers [missing-import] [codegraph])]
  (check! "an unavailable declared import fails boot validation"
          (and (not (host/boot-ok? boot))
               (some #(= :provider/import-unavailable
                         (host/providerviolation-code %))
                     (host/providerboot-violations boot)))))

(let [rejected
      (host/descriptor worlds-id worlds (host/provider-contract worlds 1)
                       [] :provider/rejected)
      boot (host/boot-providers [rejected] [worlds])]
  (check! "provider self-check rejection prevents host boot"
          (and (not (host/boot-ok? boot))
               (some #(= :provider/boot-rejected
                         (host/providerviolation-code %))
                     (host/providerboot-violations boot)))))

(let [proposition (t/triple (t/triple "space" :example/entity "one")
                            :example/value 7)
      action (host/provider-action :rpc/assert proposition :rpc/subject-any)
      batch (host/provider-batch "space" 41 [action])
      request (host/batch-request batch)
      expected-payload
      (wire/rpc-batch!
       [(wire/rpc-action! :rpc/assert proposition wire/rpc-subject-any)] nil)]
  (check! "provider batch lowers to the exact canonical rpc/batch Term"
          (= expected-payload (t/rpc-request-payload-value request)))
  (check! "expected-version is mandatory data on the one-shot batch request"
          (and (= :rpc/batch (t/rpcrequest-op request))
               (= "space" (t/rpcrequest-space request))
               (= 41 (t/rpcrequest-expected-version request))))
  (check! "the seam refuses operations outside assert/retract"
          (throws? #(host/provider-action :rpc/worlds proposition
                                          :rpc/subject-any))))

(let [result (t/triple worlds :provider/test :provider/rejected)
      plan (host/rejected-plan :provider/test-rejection result 2
                               [(host/provider-stage worlds :provider/test
                                                     :provider/rejected)])]
  (check! "a rejected plan has no batch and cannot reach FRAMRPC"
          (and (nil? (host/plan-batch "space" plan))
               (nil? (host/plan-request "space" plan))
               (empty? (host/providerplan-actions plan)))))

(if (seq @failures)
  (do (println "\nprovider host:" (count @failures) "FAILED")
      (System/exit 1))
  (println "\nprovider host: all checks passed"))
