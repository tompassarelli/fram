(ns fram.worlds-provider
  (:require [fram.provider-host :as host]
            [fram.types :as t]))

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

(defn version-id [world ^String digest]
  (t/triple world :worlds/version digest))

(defn build-id [version ^String digest]
  (t/triple version :worlds/build digest))

(defn receipt-id [build ^String digest]
  (t/triple build :worlds/receipt digest))

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
  (and (= 1 (count (filterv (fn [value] (= version value)) (fact-values live world :worlds/version)))) (= 1 (fact-count live version :worlds/content))))

(defn ^Boolean receipt-valid? [live version receipt]
  (if (not (t/triple? receipt)) false (let [build (t/triple-slot0 receipt)]
  (and (= :worlds/receipt (t/triple-slot1 receipt)) (and (t/triple? build) (and (= version (t/triple-slot0 build)) (and (= :worlds/build (t/triple-slot1 build)) (and (has-fact? live (t/triple build :worlds/receipt receipt)) (has-fact? live (t/triple receipt :worlds/result :worlds/success))))))))))

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
  :else (let [version (version-id world digest)]
  (if (known-version? live world version) (reject-plan :worlds/version-exists version expected-version :worlds/seal) (accept-plan version expected-version [] [(t/triple world :worlds/version version) (t/triple version :worlds/content supplied-content) (t/triple candidate :worlds/operation-count (linked-operation-count live candidate)) (t/triple candidate :worlds/sealed-version version)] :worlds/seal))))))

(defn build-plan [expected-version live version build-spec supplied-build-input ^String build-digest result supplied-receipt-input ^String receipt-digest]
  (let [world (t/triple-slot0 version)
   build (build-id version build-digest)
   receipt (receipt-id build receipt-digest)]
  (cond
  (or (not (digest? build-digest)) (not (digest? receipt-digest))) (reject-plan :worlds/digest-invalid build expected-version :worlds/build)
  (not (and (t/triple? world) (known-version? live world version))) (reject-plan :worlds/version-missing build expected-version :worlds/build)
  (not= supplied-build-input (build-digest-input version build-spec)) (reject-plan :worlds/digest-input-mismatch build expected-version :worlds/build)
  (not= supplied-receipt-input (receipt-digest-input build result)) (reject-plan :worlds/digest-input-mismatch receipt expected-version :worlds/build)
  (pos? (fact-count live build :worlds/receipt)) (reject-plan :worlds/receipt-exists receipt expected-version :worlds/build)
  :else (accept-plan receipt expected-version [] [(t/triple build :worlds/input supplied-build-input) (t/triple build :worlds/receipt receipt) (t/triple receipt :worlds/input supplied-receipt-input) (t/triple receipt :worlds/result result)] :worlds/build))))

(defn promote-plan [expected-version live world candidate expected-head receipt]
  (let [promotion (promotion-id candidate expected-head)
   current (world-head live world)
   sealed (candidate-sealed live candidate)
   declared-head (fact-value live candidate :worlds/expected-head ambiguous)
   declared-count (fact-value live candidate :worlds/operation-count -1)
   actual-count (linked-operation-count live candidate)]
  (cond
  (not (candidate-present? live world candidate)) (reject-plan :worlds/candidate-missing promotion expected-version :worlds/promote)
  (not= expected-head declared-head) (reject-plan :worlds/candidate-head-mismatch promotion expected-version :worlds/promote)
  (not= expected-head current) (reject-plan :worlds/head-conflict promotion expected-version :worlds/promote)
  (not (and (t/triple? sealed) (known-version? live world sealed))) (reject-plan :worlds/candidate-unsealed promotion expected-version :worlds/promote)
  (not (and (= declared-count actual-count) (operations-contiguous? live candidate))) (reject-plan :worlds/candidate-gapped promotion expected-version :worlds/promote)
  (not (receipt-valid? live sealed receipt)) (reject-plan :worlds/receipt-invalid promotion expected-version :worlds/promote)
  (pos? (fact-count live promotion :worlds/result)) (reject-plan :worlds/already-promoted promotion expected-version :worlds/promote)
  :else (accept-plan promotion expected-version (if (= current none) [] [(t/triple world :worlds/head current)]) [(t/triple world :worlds/head sealed) (t/triple promotion :worlds/result sealed)] :worlds/promote))))

(def worlds-identity (host/provider-identity worlds-capability "fram.worlds/v1"))

(def worlds-contract (host/provider-contract worlds-capability 1))

(def worlds-descriptor (host/descriptor worlds-identity worlds-capability worlds-contract [] :provider/ready))
