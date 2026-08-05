(ns fram.worlds-provider
  (:require [fram.provider-host :as host]
            [fram.types :as t]
            [framrpc :as framrpc]))

(def worlds-capability (host/capability "worlds" 1))

(def none :worlds/none)

(def ambiguous :worlds/ambiguous)

(defn world-id [^String space ^String name]
  (if (and (pos? (count space)) (pos? (count name))) (t/triple space :worlds/world name) (throw (ex-info "world identity requires non-empty space and name" {:type :worlds/invalid-world}))))

(defn blob-id [^String space ^String digest]
  (t/triple space :worlds/blob digest))

(defn candidate-id [world ^String nonce]
  (if (pos? (count nonce)) (t/triple world :worlds/candidate nonce) (throw (ex-info "candidate identity requires a non-empty nonce" {:type :worlds/invalid-candidate}))))

(defn operation-id [candidate ordinal]
  (if (>= ordinal 0) (t/triple candidate :worlds/operation ordinal) (throw (ex-info "operation identity requires a non-negative ordinal" {:type :worlds/invalid-operation}))))

(def local-term-codec-writer framrpc/write-term-codec-v1!)

(declare canonical-term-digest promotion-result-marker promotion-recovery)

(defn version-id [world content ^String digest]
  (if (= digest (canonical-term-digest content)) (t/triple world :worlds/version digest) (throw (ex-info "version digest does not match canonical TermCodec content" {:type :worlds/digest-mismatch}))))

(defn build-id [version build-input ^String digest]
  (if (= digest (canonical-term-digest build-input)) (t/triple version :worlds/build digest) (throw (ex-info "build digest does not match canonical TermCodec input" {:type :worlds/digest-mismatch}))))

(defn receipt-id [build receipt-input ^String digest]
  (if (= digest (canonical-term-digest receipt-input)) (t/triple build :worlds/receipt digest) (throw (ex-info "receipt digest does not match canonical TermCodec input" {:type :worlds/digest-mismatch}))))

(defn promotion-id [candidate expected-head]
  (t/triple candidate :worlds/promote expected-head))

(defn term-list [values]
  (reduce (fn [tail value] (if (t/term? value) (t/triple :worlds/list value tail) (throw (ex-info "world list contains a value outside Term" {:type :worlds/invalid-term})))) :worlds/list-end (reverse values)))

(defn put-operation [^String slot ^String mode blob]
  (t/triple slot :worlds/put (t/triple mode :worlds/blob blob)))

(defn delete-operation [^String slot]
  (t/triple slot :worlds/delete none))

(defn inherit-operation [^String slot]
  (t/triple slot :worlds/inherit none))

(defn ^Boolean operation-valid? [value]
  (if (not (t/triple? value)) false (let [slot (t/triple-slot0 value)
   kind (t/triple-slot1 value)
   body (t/triple-slot2 value)]
  (and (string? slot) (and (pos? (count slot)) (cond
  (= kind :worlds/put) (and (t/triple? body) (and (string? (t/triple-slot0 body)) (and (pos? (count (t/triple-slot0 body))) (and (= :worlds/blob (t/triple-slot1 body)) (t/triple? (t/triple-slot2 body))))))
  (or (= kind :worlds/delete) (= kind :worlds/inherit)) (= none body)
  :else false))))))

(defn ^Boolean digest? [value]
  (boolean (and (string? value) (.matches value "sha256:[0-9a-f]{64}"))))

(defn fact-values [live slot0 slot1]
  (mapv (fn [fact] (t/triple-slot2 fact)) (filterv (fn [fact] (and (= slot0 (t/triple-slot0 fact)) (= slot1 (t/triple-slot1 fact)))) live)))

(defn fact-count [live slot0 slot1]
  (count (fact-values live slot0 slot1)))

(defn fact-value [live slot0 slot1 absent]
  (let [values (fact-values live slot0 slot1)]
  (cond
  (empty? values) absent
  (= 1 (count values)) (first values)
  :else ambiguous)))

(defn ^Boolean has-fact? [live proposition]
  (boolean (some (fn [fact] (= proposition fact)) live)))

(defn world-head [live world]
  (fact-value live world :worlds/head none))

(defn ^Boolean candidate-present? [live world candidate]
  (has-fact? live (t/triple world :worlds/candidate candidate)))

(defn candidate-sealed [live candidate]
  (fact-value live candidate :worlds/sealed-version none))

(defn linked-operation-count [live candidate]
  (fact-count live candidate :worlds/operation))

(defn operation-value [live candidate ordinal]
  (fact-value live (operation-id candidate ordinal) :worlds/value none))

(defn ^Boolean operations-contiguous? [live candidate]
  (let [n (linked-operation-count live candidate)]
  (loop [ordinal 0]
  (if (>= ordinal n) true (let [identity (operation-id candidate ordinal)
   value (operation-value live candidate ordinal)]
  (if (and (= 1 (count (filterv (fn [linked] (= identity linked)) (fact-values live candidate :worlds/operation)))) (and (= 1 (fact-count live identity :worlds/value)) (operation-valid? value))) (recur (inc ordinal)) false))))))

(defn ordered-operation-facts [live candidate]
  (let [n (linked-operation-count live candidate)]
  (loop [ordinal 0
   facts []]
  (if (>= ordinal n) facts (let [identity (operation-id candidate ordinal)]
  (recur (inc ordinal) (conj facts (t/triple identity :worlds/value (operation-value live candidate ordinal)))))))))

(defn seal-digest-input [live candidate]
  (if (operations-contiguous? live candidate) (let [expected (fact-value live candidate :worlds/expected-head ambiguous)
   facts (mapv (fn [fact] fact) (ordered-operation-facts live candidate))]
  (if (= expected ambiguous) (throw (ex-info "candidate has no unique expected head" {:type :worlds/candidate-invalid})) (t/triple candidate :worlds/seal-content (t/triple expected :worlds/operations (term-list facts))))) (throw (ex-info "candidate operation sequence is not contiguous" {:type :worlds/candidate-gapped}))))

(defn build-digest-input [version build-spec]
  (t/triple version :worlds/build-spec build-spec))

(defn receipt-digest-input [build result]
  (t/triple build :worlds/result result))

(defn ^Boolean known-version? [live world version]
  (if (not (t/triple? version)) false (let [contents (fact-values live version :worlds/content)]
  (and (= world (t/triple-slot0 version)) (= :worlds/version (t/triple-slot1 version)) (digest? (t/triple-slot2 version)) (= 1 (count (filterv (fn [value] (= version value)) (fact-values live world :worlds/version)))) (= 1 (count contents)) (t/triple? (first contents)) (= (t/triple-slot2 version) (canonical-term-digest (first contents)))))))

(defn ^Boolean receipt-valid? [live version receipt]
  (if (not (t/triple? receipt)) false (let [build-value (t/triple-slot0 receipt)]
  (if (not (t/triple? build-value)) false (let [build build-value
   build-input-value (fact-value live build :worlds/input none)
   receipt-input-value (fact-value live receipt :worlds/input none)
   result (fact-value live receipt :worlds/result none)]
  (if (or (not (t/triple? build-input-value)) (not (t/triple? receipt-input-value))) false (let [build-input build-input-value
   receipt-input receipt-input-value]
  (and (= :worlds/receipt (t/triple-slot1 receipt)) (digest? (t/triple-slot2 receipt)) (= version (t/triple-slot0 build)) (= :worlds/build (t/triple-slot1 build)) (digest? (t/triple-slot2 build)) (= version (t/triple-slot0 build-input)) (= :worlds/build-spec (t/triple-slot1 build-input)) (= (t/triple-slot2 build) (canonical-term-digest build-input)) (= receipt-input (receipt-digest-input build result)) (= (t/triple-slot2 receipt) (canonical-term-digest receipt-input)) (= 1 (fact-count live build :worlds/input)) (= 1 (count (filterv (fn [value] (= receipt value)) (fact-values live build :worlds/receipt)))) (= 1 (fact-count live receipt :worlds/input)) (= 1 (fact-count live receipt :worlds/result)) (= :worlds/success result)))))))))

(defn one-stage [stage result]
  [(host/provider-stage worlds-capability stage result)])

(defn reject-plan [code result expected-version stage]
  (host/rejected-plan code result expected-version (one-stage stage :worlds/rejected)))

(defn actions-for [retractions assertions]
  (let [after-retractions (reduce (fn [actions proposition] (conj actions (host/provider-action :rpc/retract proposition :rpc/subject-any))) [] retractions)]
  (reduce (fn [actions proposition] (conj actions (host/provider-action :rpc/assert proposition :rpc/subject-any))) after-retractions assertions)))

(defn accept-plan [result expected-version retractions assertions stage]
  (host/accepted-plan result expected-version (actions-for retractions assertions) (one-stage stage :worlds/accepted)))

(defn begin-plan [^String space expected-version live ^String name expected-head ^String nonce]
  (let [world (world-id space name)
   candidate (candidate-id world nonce)
   current (world-head live world)]
  (cond
  (= current ambiguous) (reject-plan :worlds/head-ambiguous candidate expected-version :worlds/begin)
  (not= expected-head current) (reject-plan :worlds/head-conflict candidate expected-version :worlds/begin)
  (candidate-present? live world candidate) (reject-plan :worlds/candidate-exists candidate expected-version :worlds/begin)
  :else (accept-plan candidate expected-version [] [(t/triple world :worlds/candidate candidate) (t/triple candidate :worlds/expected-head expected-head)] :worlds/begin))))

(defn append-plan [expected-version live candidate ordinal operation]
  (let [world (t/triple-slot0 candidate)
   identity (operation-id candidate ordinal)
   next-ordinal (linked-operation-count live candidate)]
  (cond
  (not (and (t/triple? world) (candidate-present? live world candidate))) (reject-plan :worlds/candidate-missing identity expected-version :worlds/append)
  (not= none (candidate-sealed live candidate)) (reject-plan :worlds/candidate-sealed identity expected-version :worlds/append)
  (not= ordinal next-ordinal) (reject-plan :worlds/operation-gap identity expected-version :worlds/append)
  (not (operation-valid? operation)) (reject-plan :worlds/operation-invalid identity expected-version :worlds/append)
  :else (accept-plan identity expected-version [] [(t/triple candidate :worlds/operation identity) (t/triple identity :worlds/value operation)] :worlds/append))))

(defn seal-plan [expected-version live candidate supplied-content ^String digest]
  (let [world (t/triple-slot0 candidate)
   result (t/triple candidate :worlds/seal digest)]
  (cond
  (not (digest? digest)) (reject-plan :worlds/digest-invalid result expected-version :worlds/seal)
  (not (and (t/triple? world) (candidate-present? live world candidate))) (reject-plan :worlds/candidate-missing result expected-version :worlds/seal)
  (not= none (candidate-sealed live candidate)) (reject-plan :worlds/candidate-sealed result expected-version :worlds/seal)
  (not (operations-contiguous? live candidate)) (reject-plan :worlds/candidate-gapped result expected-version :worlds/seal)
  (not= supplied-content (seal-digest-input live candidate)) (reject-plan :worlds/digest-input-mismatch result expected-version :worlds/seal)
  (not= digest (canonical-term-digest supplied-content)) (reject-plan :worlds/digest-mismatch result expected-version :worlds/seal)
  :else (let [version (version-id world supplied-content digest)]
  (if (known-version? live world version) (reject-plan :worlds/version-exists version expected-version :worlds/seal) (accept-plan version expected-version [] [(t/triple world :worlds/version version) (t/triple version :worlds/content supplied-content) (t/triple candidate :worlds/operation-count (linked-operation-count live candidate)) (t/triple candidate :worlds/sealed-version version)] :worlds/seal))))))

(defn build-plan [expected-version live version build-spec supplied-build-input ^String build-digest result supplied-receipt-input ^String receipt-digest]
  (let [world (t/triple-slot0 version)
   raw-build (t/triple version :worlds/build build-digest)]
  (cond
  (or (not (digest? build-digest)) (not (digest? receipt-digest))) (reject-plan :worlds/digest-invalid raw-build expected-version :worlds/build)
  (not (and (t/triple? world) (known-version? live world version))) (reject-plan :worlds/version-missing raw-build expected-version :worlds/build)
  (not= supplied-build-input (build-digest-input version build-spec)) (reject-plan :worlds/digest-input-mismatch raw-build expected-version :worlds/build)
  (not= build-digest (canonical-term-digest supplied-build-input)) (reject-plan :worlds/digest-mismatch raw-build expected-version :worlds/build)
  :else (let [build (build-id version supplied-build-input build-digest)
   raw-receipt (t/triple build :worlds/receipt receipt-digest)]
  (cond
  (not= supplied-receipt-input (receipt-digest-input build result)) (reject-plan :worlds/digest-input-mismatch raw-receipt expected-version :worlds/build)
  (not= receipt-digest (canonical-term-digest supplied-receipt-input)) (reject-plan :worlds/digest-mismatch raw-receipt expected-version :worlds/build)
  (pos? (fact-count live build :worlds/receipt)) (reject-plan :worlds/receipt-exists raw-receipt expected-version :worlds/build)
  :else (let [receipt (receipt-id build supplied-receipt-input receipt-digest)]
  (accept-plan receipt expected-version [] [(t/triple build :worlds/input supplied-build-input) (t/triple build :worlds/receipt receipt) (t/triple receipt :worlds/input supplied-receipt-input) (t/triple receipt :worlds/result result)] :worlds/build)))))))

(defn promote-plan [expected-version live world candidate expected-head receipt]
  (let [promotion (promotion-id candidate expected-head)
   current (world-head live world)
   sealed (candidate-sealed live candidate)
   recovered (promotion-recovery live world candidate expected-head receipt)
   declared-head (fact-value live candidate :worlds/expected-head ambiguous)
   declared-count (fact-value live candidate :worlds/operation-count -1)
   actual-count (linked-operation-count live candidate)]
  (cond
  (some? recovered) (reject-plan :worlds/already-promoted promotion expected-version :worlds/promote)
  (not (candidate-present? live world candidate)) (reject-plan :worlds/candidate-missing promotion expected-version :worlds/promote)
  (not= expected-head declared-head) (reject-plan :worlds/candidate-head-mismatch promotion expected-version :worlds/promote)
  (not= expected-head current) (reject-plan :worlds/head-conflict promotion expected-version :worlds/promote)
  (not (and (t/triple? sealed) (known-version? live world sealed))) (reject-plan :worlds/candidate-unsealed promotion expected-version :worlds/promote)
  (not (and (= declared-count actual-count) (operations-contiguous? live candidate))) (reject-plan :worlds/candidate-gapped promotion expected-version :worlds/promote)
  (not (receipt-valid? live sealed receipt)) (reject-plan :worlds/receipt-invalid promotion expected-version :worlds/promote)
  (pos? (fact-count live promotion :worlds/result)) (reject-plan :worlds/promotion-conflict promotion expected-version :worlds/promote)
  :else (let [marker (promotion-result-marker sealed receipt)]
  (accept-plan promotion expected-version (if (= current none) [] [(t/triple world :worlds/head current)]) [(t/triple world :worlds/head sealed) (t/triple promotion :worlds/result marker)] :worlds/promote)))))

(def worlds-identity (host/provider-identity worlds-capability "fram.worlds/v1"))

(def worlds-contract (host/provider-contract worlds-capability 1))

(def worlds-descriptor (host/descriptor worlds-identity worlds-capability worlds-contract [] :provider/ready))

(defn ^String canonical-term-digest [term]
  (if (t/term? term) (let [out (java.io.ByteArrayOutputStream.)]
  (do
  (local-term-codec-writer out term framrpc/rpc-v1-max-string-bytes framrpc/rpc-v1-max-term-nodes framrpc/rpc-v1-max-term-depth)
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.toByteArray out))]
  (str "sha256:" (apply str (mapv (fn [value] (format "%02x" (bit-and 255 value))) (vec digest))))))) (throw (ex-info "world digest input lies outside TermCodecV1" {:type :worlds/invalid-term}))))

(defn promotion-result-marker [version receipt]
  (t/triple version :worlds/authorized-by receipt))

(defn promotion-recovery [live world candidate expected-head receipt]
  (let [promotion (promotion-id candidate expected-head)
   sealed (candidate-sealed live candidate)]
  (if (and (t/triple? sealed) (and (receipt-valid? live sealed receipt) (and (= sealed (world-head live world)) (let [marker (promotion-result-marker sealed receipt)]
  (and (= 1 (fact-count live promotion :worlds/result)) (has-fact? live (t/triple promotion :worlds/result marker))))))) (promotion-result-marker sealed receipt) nil)))

(def worlds-registry [worlds-descriptor])

(defn invoke-plan-to! [^String address port ^String space plan]
  (host/invoke-plan-to! worlds-registry [worlds-capability] worlds-capability address port space plan))
