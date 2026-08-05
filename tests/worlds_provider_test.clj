(require '[framrpc :as wire]
         '[fram.provider-host :as host]
         '[fram.rt :as rt]
         '[fram.store :as store]
         '[fram.types :as t]
         '[fram.worlds-provider :as worlds])

(def failures (atom []))
(defn check! [label value]
  (println (if value "  [PASS]" "  [FAIL]") label)
  (when-not value (swap! failures conj label)))

(defn sha256-term [term]
  (let [out (java.io.ByteArrayOutputStream.)]
    (wire/write-term-codec-v1!
     out term wire/rpc-v1-max-string-bytes wire/rpc-v1-max-term-nodes
     wire/rpc-v1-max-term-depth)
    (str "sha256:"
         (apply str
                (map #(format "%02x" (bit-and 255 %))
                     (.digest (java.security.MessageDigest/getInstance "SHA-256")
                              (.toByteArray out)))))))

(defn commit-plan! [term-store plan]
  (when-not (host/providerplan-accepted plan)
    (throw (ex-info "test attempted to commit a rejected plan"
                    {:type :test/rejected-plan})))
  (when-not (= (store/current-sequence term-store)
               (host/providerplan-expected-version plan))
    (throw (ex-info "expected-version conflict"
                    {:type :rpc/conflict})))
  (store/commit-transaction!
   term-store
   (mapv
    (fn [action]
      (case (host/provideraction-operation action)
        :rpc/assert
        (store/assert-operation (host/provideraction-proposition action))
        :rpc/retract
        (store/retract-operation (host/provideraction-proposition action))))
    (host/providerplan-actions plan))))

(defn rejected-code [plan]
  (when-not (host/providerplan-accepted plan)
    (host/providerplan-code plan)))

(defn throws? [f]
  (try (f) false (catch Throwable _ true)))

(println "worlds/v1 provider — recursive Terms, OCC batches, and restart replay")

(let [boot (host/boot-providers [worlds/worlds-descriptor]
                                [worlds/worlds-capability])]
  (check! "worlds/v1 is selected through the private provider host"
          (and (host/boot-ok? boot)
               (= worlds/worlds-descriptor
                  (host/provider-for boot worlds/worlds-capability)))))

(def space "worlds-test")
(def term-store (store/new-term-store space))
(def world (worlds/world-id space "main"))
(def blob (worlds/blob-id space
                          "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
(def put (worlds/put-operation "src/main.bclj" "100644" blob))

(def begin
  (worlds/begin-plan space 0 (store/live-propositions term-store)
                     "main" worlds/none "candidate-a"))
(def candidate (host/providerplan-result begin))

(check! "begin identity is a recursive world/candidate Triple"
        (and (t/triple? candidate)
             (= world (t/triple-slot0 candidate))
             (= :worlds/candidate (t/triple-slot1 candidate))))
(check! "begin emits one expected-version batch, not a special daemon verb"
        (let [request (host/plan-request space begin)]
          (and (= :rpc/batch (t/rpcrequest-op request))
               (= 0 (t/rpcrequest-expected-version request)))))
(let [calls (atom [])
      response (t/->RpcResponse space :rpc/batch 1 nil nil :rpc/ok)
      invocation
      (with-redefs [rt/native-request-to!
                    (fn [address port request]
                      (swap! calls conj [address port request])
                      response)]
        (worlds/invoke-plan-to! "127.0.0.1" 17771 space begin))]
  (check! "worlds non-test entrypoint boots its private registry and reaches local FRAMRPC"
          (and (= worlds/worlds-descriptor
                  (host/providerinvocation-descriptor invocation))
               (= response (host/providerinvocation-response invocation))
               (= [["127.0.0.1" 17771
                    (host/providerinvocation-request invocation)]]
                  @calls))))
(commit-plan! term-store begin)

(def append
  (worlds/append-plan 1 (store/live-propositions term-store)
                      candidate 0 put))
(def operation (host/providerplan-result append))
(check! "append identity nests candidate and ordinal"
        (= operation (worlds/operation-id candidate 0)))
(commit-plan! term-store append)

(def seal-input
  (worlds/seal-digest-input (store/live-propositions term-store) candidate))
(def seal-digest (sha256-term seal-input))
(check! "provider canonical digest is exactly TermCodecV1 SHA-256"
        (= seal-digest (worlds/canonical-term-digest seal-input)))
(def seal
  (worlds/seal-plan 2 (store/live-propositions term-store)
                    candidate seal-input seal-digest))
(def version (host/providerplan-result seal))

(check! "version identity nests world and canonical TermCodec digest"
        (and (= version (worlds/version-id world seal-input seal-digest))
             (worlds/digest? (t/triple-slot2 version))))
(check! "TermCodec digest is stable and structure-sensitive"
        (and (= seal-digest (sha256-term seal-input))
             (not= seal-digest
                   (sha256-term
                    (t/triple candidate :worlds/seal-content
                              :worlds/different)))))
(let [bad-content (t/triple candidate :worlds/seal-content :worlds/forged)
      rejected (worlds/seal-plan 2 (store/live-propositions term-store)
                                 candidate bad-content (sha256-term bad-content))]
  (check! "seal rejects a digest over any term except its canonical input"
          (= :worlds/digest-input-mismatch (rejected-code rejected))))
(let [before (store/dump-term-store term-store)
      forged-digest "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      rejected (worlds/seal-plan 2 (store/live-propositions term-store)
                                 candidate seal-input forged-digest)]
  (check! "seal rejects a forged caller digest before any batch or mutation"
          (and (= :worlds/digest-mismatch (rejected-code rejected))
               (empty? (host/providerplan-actions rejected))
               (nil? (host/plan-request space rejected))
               (= before (store/dump-term-store term-store)))))
(check! "version identity cannot be minted with a forged digest"
        (throws? #(worlds/version-id
                   world seal-input
                   "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
(commit-plan! term-store seal)

(def build-spec
  (t/triple (t/triple :tool "beagle" "zig")
            :target "x86_64-linux"))
(def build-input (worlds/build-digest-input version build-spec))
(def build-digest (sha256-term build-input))
(def build (worlds/build-id version build-input build-digest))
(def receipt-input (worlds/receipt-digest-input build :worlds/success))
(def receipt-digest (sha256-term receipt-input))
(def build-plan
  (worlds/build-plan 3 (store/live-propositions term-store)
                     version build-spec build-input build-digest
                     :worlds/success receipt-input receipt-digest))
(def receipt (host/providerplan-result build-plan))

(check! "build and receipt identities recursively bind version and digests"
        (= receipt (worlds/receipt-id
                    (worlds/build-id version build-input build-digest)
                    receipt-input receipt-digest)))
(let [before (store/dump-term-store term-store)
      forged-build-digest "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      forged-build (t/triple version :worlds/build forged-build-digest)
      forged-receipt-input
      (worlds/receipt-digest-input forged-build :worlds/success)
      rejected
      (worlds/build-plan
       3 (store/live-propositions term-store)
       version build-spec build-input forged-build-digest :worlds/success
       forged-receipt-input (sha256-term forged-receipt-input))]
  (check! "build rejects a forged build digest before any batch or mutation"
          (and (= :worlds/digest-mismatch (rejected-code rejected))
               (empty? (host/providerplan-actions rejected))
               (nil? (host/plan-request space rejected))
               (= before (store/dump-term-store term-store)))))
(let [before (store/dump-term-store term-store)
      forged-receipt-digest
      "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      rejected
      (worlds/build-plan
       3 (store/live-propositions term-store)
       version build-spec build-input build-digest :worlds/success
       receipt-input forged-receipt-digest)]
  (check! "receipt rejects a forged caller digest before any batch or mutation"
          (and (= :worlds/digest-mismatch (rejected-code rejected))
               (empty? (host/providerplan-actions rejected))
               (nil? (host/plan-request space rejected))
               (= before (store/dump-term-store term-store)))))
(check! "build and receipt identities cannot be minted with forged digests"
        (and
         (throws? #(worlds/build-id
                    version build-input
                    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
         (throws? #(worlds/receipt-id
                    build receipt-input
                    "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))))
(commit-plan! term-store build-plan)

(let [before (store/live-propositions term-store)
      forged (t/triple build :worlds/receipt
                       "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
      rejected (worlds/promote-plan 4 before world candidate worlds/none forged)]
  (check! "promotion rejects a receipt that is not a live successful build result"
          (= :worlds/receipt-invalid (rejected-code rejected)))
  (check! "rejected promotion has no actions or request and leaves state unchanged"
          (and (empty? (host/providerplan-actions rejected))
               (nil? (host/plan-request space rejected))
               (= before (store/live-propositions term-store)))))

(def promote
  (worlds/promote-plan 4 (store/live-propositions term-store)
                       world candidate worlds/none receipt))
(def promotion (host/providerplan-result promote))
(def promotion-marker (worlds/promotion-result-marker version receipt))
(check! "promotion identity is a recursive candidate/head coordinate"
        (= promotion (worlds/promotion-id candidate worlds/none)))
(check! "one promotion batch binds its resulting head to the exact authorizing receipt"
        (some #(= (t/triple promotion :worlds/result promotion-marker)
                  (host/provideraction-proposition %))
              (host/providerplan-actions promote)))
(commit-plan! term-store promote)

(check! "promotion atomically advances the one live world head"
        (and (= version (worlds/world-head (store/live-propositions term-store)
                                          world))
             (= 5 (store/current-sequence term-store))))
(check! "lost-response recovery proves the exact receipt and resulting head"
        (= promotion-marker
           (worlds/promotion-recovery
            (store/live-propositions term-store)
            world candidate worlds/none receipt)))
(let [wrong-receipt
      (t/triple build :worlds/receipt
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")]
  (check! "lost-response recovery refuses a different receipt"
          (nil? (worlds/promotion-recovery
                 (store/live-propositions term-store)
                 world candidate worlds/none wrong-receipt))))
(let [before (store/dump-term-store term-store)
      replay (worlds/promote-plan
              5 (store/live-propositions term-store)
              world candidate worlds/none receipt)]
  (check! "promotion replay is recognized without a second batch or mutation"
          (and (= :worlds/already-promoted (rejected-code replay))
               (empty? (host/providerplan-actions replay))
               (nil? (host/plan-request space replay))
               (= before (store/dump-term-store term-store)))))

(let [dump (store/dump-term-store term-store)
      restarted (store/new-term-store space)
      _ (store/load-term-store! restarted dump)]
  (check! "restart replay reproduces every live Worlds proposition"
          (= (store/live-propositions term-store)
             (store/live-propositions restarted)))
  (check! "restart replay preserves recursive head and occurrence history"
          (and (= version
                  (worlds/world-head (store/live-propositions restarted) world))
               (= (store/semantic-history term-store)
                  (store/semantic-history restarted))))
  (check! "restart recovery preserves exact receipt-bound promotion proof"
          (= promotion-marker
             (worlds/promotion-recovery
              (store/live-propositions restarted)
              world candidate worlds/none receipt)))
  (let [next-plan
        (worlds/begin-plan space 5 (store/live-propositions restarted)
                           "main" version "candidate-b")]
    (check! "a new candidate can continue from the replayed head"
            (host/providerplan-accepted next-plan)))
  (let [stale
        (worlds/begin-plan space 4 (store/live-propositions restarted)
                           "main" version "candidate-stale")
        before (store/dump-term-store restarted)
        conflict
        (try (commit-plan! restarted stale) nil
             (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))]
    (check! "stale expected-version is rejected before any batch mutation"
            (and (= :rpc/conflict conflict)
                 (= before (store/dump-term-store restarted))))))

(check! "all durable Worlds state is ordinary recursive Triple propositions"
        (every? #(and (t/triple? %) (t/term? %))
                (store/live-propositions term-store)))
(check! "stage hooks expose only stable stage/result labels"
        (= [:worlds/begin :worlds/append :worlds/seal :worlds/build :worlds/promote]
           (mapv #(host/providerstage-stage
                   (first (host/providerplan-stages %)))
                 [begin append seal build-plan promote])))

(if (seq @failures)
  (do (println "\nworlds/v1 provider:" (count @failures) "FAILED")
      (System/exit 1))
  (println "\nworlds/v1 provider: all checks passed"))
