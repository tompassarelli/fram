(ns fram.types)

(defrecord Instant [epoch-seconds nanos])

(defn instant-epoch-seconds [r] (:epoch-seconds r))

(defn instant-nanos [r] (:nanos r))

(defn ^Boolean instant? [v]
  (instance? Instant v))

(defn ^Instant instant [epoch-seconds nanos]
  (if (and (>= nanos 0) (< nanos 1000000000)) (->Instant epoch-seconds nanos) (throw (ex-info "fram: instant nanoseconds must be in [0, 1000000000)" {:type :invalid-instant}))))

(defrecord Triple [slot0 slot1 slot2])

(defn triple-slot0 [r] (:slot0 r))

(defn triple-slot1 [r] (:slot1 r))

(defn triple-slot2 [r] (:slot2 r))

(defn ^Boolean triple? [v]
  (instance? Triple v))

(defrecord AtomRow [kind string-value int-value float-value bool-value keyword-value instant-value])

(defn atomrow-kind [r] (:kind r))

(defn atomrow-string-value [r] (:string-value r))

(defn atomrow-int-value [r] (:int-value r))

(defn atomrow-float-value [r] (:float-value r))

(defn atomrow-bool-value [r] (:bool-value r))

(defn atomrow-keyword-value [r] (:keyword-value r))

(defn atomrow-instant-value [r] (:instant-value r))

(defrecord TripleRow [slot0 slot1 slot2])

(defn triplerow-slot0 [r] (:slot0 r))

(defn triplerow-slot1 [r] (:slot1 r))

(defn triplerow-slot2 [r] (:slot2 r))

(defrecord TermBucket [key positions])

(defn termbucket-key [r] (:key r))

(defn termbucket-positions [r] (:positions r))

(defrecord TermStore [atoms triples atom-slots triple-slots])

(defn termstore-atoms [r] (:atoms r))

(defn termstore-triples [r] (:triples r))

(defn termstore-atom-slots [r] (:atom-slots r))

(defn termstore-triple-slots [r] (:triple-slots r))

(defrecord TermStoreDump [version atoms triples])

(defn termstoredump-version [r] (:version r))

(defn termstoredump-atoms [r] (:atoms r))

(defn termstoredump-triples [r] (:triples r))

(def tx-sequence :kernel/tx-sequence)

(def op-ordinal :kernel/op-ordinal)

(def asserts :kernel/asserts)

(def retracts :kernel/retracts)

(def withdraws :kernel/withdraws)

(def recorded-at-predicate :kernel/recorded-at)

(defn ^Boolean atom? [v]
  (or (string? v) (or (integer? v) (or (and (number? v) (not (integer? v))) (or (boolean? v) (or (keyword? v) (instant? v)))))))

(defn ^Boolean term? [v]
  (if (triple? v) (and (term? (triple-slot0 v)) (and (term? (triple-slot1 v)) (term? (triple-slot2 v)))) (atom? v)))

(defn ^Triple triple [slot0 slot1 slot2]
  (let [value (->Triple slot0 slot1 slot2)]
  (if (term? value) value (throw (ex-info "fram: triple contains a value outside Term" {:type :invalid-term})))))

(defn ^Triple transaction-coordinate [^String space-id sequence]
  (if (and (pos? (count space-id)) (>= sequence 0)) (triple space-id tx-sequence sequence) (throw (ex-info "fram: transaction coordinate requires a non-empty space and non-negative sequence" {:type :invalid-transaction-coordinate}))))

(defn ^Boolean transaction-coordinate? [v]
  (and (triple? v) (and (string? (triple-slot0 v)) (and (pos? (count (triple-slot0 v))) (and (= tx-sequence (triple-slot1 v)) (and (integer? (triple-slot2 v)) (>= (triple-slot2 v) 0)))))))

(defn ^Triple occurrence-coordinate [^Triple tx ordinal]
  (if (and (transaction-coordinate? tx) (>= ordinal 0)) (triple tx op-ordinal ordinal) (throw (ex-info "fram: occurrence coordinate requires a transaction coordinate and non-negative ordinal" {:type :invalid-occurrence-coordinate}))))

(defn ^Boolean occurrence-coordinate? [v]
  (and (triple? v) (and (transaction-coordinate? (triple-slot0 v)) (and (= op-ordinal (triple-slot1 v)) (and (integer? (triple-slot2 v)) (>= (triple-slot2 v) 0))))))

(defn ^Triple assertion-occurrence [^Triple occurrence ^Triple proposition]
  (if (and (occurrence-coordinate? occurrence) (triple? proposition)) (triple occurrence asserts proposition) (throw (ex-info "fram: assertion requires an occurrence coordinate" {:type :invalid-assertion-occurrence}))))

(defn ^Triple retraction-occurrence [^Triple occurrence ^Triple proposition]
  (if (and (occurrence-coordinate? occurrence) (triple? proposition)) (triple occurrence retracts proposition) (throw (ex-info "fram: retraction requires an occurrence coordinate" {:type :invalid-retraction-occurrence}))))

(defn ^Triple withdrawal [^Triple retraction ^Triple target]
  (if (and (occurrence-coordinate? retraction) (occurrence-coordinate? target)) (triple retraction withdraws target) (throw (ex-info "fram: withdrawal requires retraction and target occurrence coordinates" {:type :invalid-withdrawal}))))

(defn ^Triple recorded-at [^Triple source ^Instant at]
  (if (and (triple? source) (instant? at)) (triple source recorded-at-predicate at) (throw (ex-info "fram: recorded-at requires a triple source and Instant" {:type :invalid-recorded-at}))))

(defn ^Boolean occurrence-before? [^Triple left ^Triple right]
  (if (and (occurrence-coordinate? left) (occurrence-coordinate? right)) (let [left-tx (triple-slot0 left)
   right-tx (triple-slot0 right)
   left-space (triple-slot0 left-tx)
   right-space (triple-slot0 right-tx)
   left-seq (triple-slot2 left-tx)
   right-seq (triple-slot2 right-tx)
   left-ordinal (triple-slot2 left)
   right-ordinal (triple-slot2 right)]
  (if (= left-space right-space) (or (< left-seq right-seq) (and (= left-seq right-seq) (< left-ordinal right-ordinal))) (throw (ex-info "fram: occurrences from different spaces have no shared order" {:type :incomparable-occurrence-spaces})))) (throw (ex-info "fram: occurrence ordering requires occurrence coordinates" {:type :invalid-occurrence-order}))))

(defrecord StoredValue [id value])

(defn storedvalue-id [r] (:id r))

(defn storedvalue-value [r] (:value r))

(defrecord StoredFact [id l p r])

(defn storedfact-id [r] (:id r))

(defn storedfact-l [r] (:l r))

(defn storedfact-p [r] (:p r))

(defn storedfact-r [r] (:r r))

(defrecord FactView [l p r])

(defn factview-l [r] (:l r))

(defn factview-p [r] (:p r))

(defn factview-r [r] (:r r))

(defrecord StoredTxOf [cid tx])

(defn storedtxof-cid [r] (:cid r))

(defn storedtxof-tx [r] (:tx r))

(defrecord StoredTx [id seq agent observed ts])

(defn storedtx-id [r] (:id r))

(defn storedtx-seq [r] (:seq r))

(defn storedtx-agent [r] (:agent r))

(defn storedtx-observed [r] (:observed r))

(defn storedtx-ts [r] (:ts r))

(defrecord IdBucket [key ids])

(defn idbucket-key [r] (:key r))

(defn idbucket-ids [r] (:ids r))

(defrecord PairBucket [left right ids])

(defn pairbucket-left [r] (:left r))

(defn pairbucket-right [r] (:right r))

(defn pairbucket-ids [r] (:ids r))

(defrecord StoreDump [version next-id next-seq supersedes-pred objects values facts tx-of txs superseded])

(defn storedump-version [r] (:version r))

(defn storedump-next-id [r] (:next-id r))

(defn storedump-next-seq [r] (:next-seq r))

(defn storedump-supersedes-pred [r] (:supersedes-pred r))

(defn storedump-objects [r] (:objects r))

(defn storedump-values [r] (:values r))

(defn storedump-facts [r] (:facts r))

(defn storedump-tx-of [r] (:tx-of r))

(defn storedump-txs [r] (:txs r))

(defn storedump-superseded [r] (:superseded r))

(defrecord Store [next-id next-seq supersedes-pred objects values facts tx-of txs superseded idx-by-l idx-by-p idx-by-r idx-by-lp idx-by-pr value-slots l-slots p-slots r-slots lp-slots pr-slots fact-slots tx-slots])

(defn store-next-id [r] (:next-id r))

(defn store-next-seq [r] (:next-seq r))

(defn store-supersedes-pred [r] (:supersedes-pred r))

(defn store-objects [r] (:objects r))

(defn store-values [r] (:values r))

(defn store-facts [r] (:facts r))

(defn store-tx-of [r] (:tx-of r))

(defn store-txs [r] (:txs r))

(defn store-superseded [r] (:superseded r))

(defn store-idx-by-l [r] (:idx-by-l r))

(defn store-idx-by-p [r] (:idx-by-p r))

(defn store-idx-by-r [r] (:idx-by-r r))

(defn store-idx-by-lp [r] (:idx-by-lp r))

(defn store-idx-by-pr [r] (:idx-by-pr r))

(defn store-value-slots [r] (:value-slots r))

(defn store-l-slots [r] (:l-slots r))

(defn store-p-slots [r] (:p-slots r))

(defn store-r-slots [r] (:r-slots r))

(defn store-lp-slots [r] (:lp-slots r))

(defn store-pr-slots [r] (:pr-slots r))

(defn store-fact-slots [r] (:fact-slots r))

(defn store-tx-slots [r] (:tx-slots r))
