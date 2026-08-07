(ns fram.types
  (:require [fram.slots :as slots]))

(defrecord Instant [epoch-seconds nanos])

(defn instant-epoch-seconds [r] (:epoch-seconds r))

(defn instant-nanos [r] (:nanos r))

(defn ^Boolean instant? [v]
  (instance? Instant v))

(defn ^Instant instant [epoch-seconds nanos]
  (if (and (>= nanos 0) (< nanos 1000000000)) (->Instant epoch-seconds nanos) (throw (ex-info "fram: instant nanoseconds must be in [0, 1000000000)" {:type :invalid-instant}))))

(defn ^Instant instant-shift-seconds [^Instant at seconds]
  (->Instant (+ (instant-epoch-seconds at) seconds) (instant-nanos at)))

(defn instant-seconds-between [^Instant earlier ^Instant later]
  (- (instant-epoch-seconds later) (instant-epoch-seconds earlier)))

(defrecord Triple [t1 t2 t3])

(defn triple-t1 [r] (:t1 r))

(defn triple-t2 [r] (:t2 r))

(defn triple-t3 [r] (:t3 r))

(defrecord RpcPageRequest [limit cursor])

(defn rpcpagerequest-limit [r] (:limit r))

(defn rpcpagerequest-cursor [r] (:cursor r))

(defrecord RpcPageResponse [ordinal next-cursor done])

(defn rpcpageresponse-ordinal [r] (:ordinal r))

(defn rpcpageresponse-next-cursor [r] (:next-cursor r))

(defn rpcpageresponse-done [r] (:done r))

(defrecord RpcError [code retryable message detail])

(defn rpcerror-code [r] (:code r))

(defn rpcerror-retryable [r] (:retryable r))

(defn rpcerror-message [r] (:message r))

(defn rpcerror-detail [r] (:detail r))

(defrecord RpcRequest [space op expected-version page timeout-ms payload])

(defn rpcrequest-space [r] (:space r))

(defn rpcrequest-op [r] (:op r))

(defn rpcrequest-expected-version [r] (:expected-version r))

(defn rpcrequest-page [r] (:page r))

(defn rpcrequest-timeout-ms [r] (:timeout-ms r))

(defn rpcrequest-payload [r] (:payload r))

(defrecord RpcResponse [space op served-version page error payload])

(defn rpcresponse-space [r] (:space r))

(defn rpcresponse-op [r] (:op r))

(defn rpcresponse-served-version [r] (:served-version r))

(defn rpcresponse-page [r] (:page r))

(defn rpcresponse-error [r] (:error r))

(defn rpcresponse-payload [r] (:payload r))

(defrecord RpcFrameV1 [kind flags request-id request response])

(defn rpcframev1-kind [r] (:kind r))

(defn rpcframev1-flags [r] (:flags r))

(defn rpcframev1-request-id [r] (:request-id r))

(defn rpcframev1-request [r] (:request r))

(defn rpcframev1-response [r] (:response r))

(defrecord TermCodecMeasure [bytes nodes])

(defn termcodecmeasure-bytes [r] (:bytes r))

(defn termcodecmeasure-nodes [r] (:nodes r))

(defrecord TermCodecDecoded [value nodes])

(defn termcodecdecoded-value [r] (:value r))

(defn termcodecdecoded-nodes [r] (:nodes r))

(defn ^Boolean triple? [v]
  (instance? Triple v))

(defn ^Boolean rpc-page-request? [v]
  (instance? RpcPageRequest v))

(defn ^Boolean rpc-page-response? [v]
  (instance? RpcPageResponse v))

(defn ^Boolean rpc-error? [v]
  (instance? RpcError v))

(defn ^Boolean rpc-request? [v]
  (instance? RpcRequest v))

(defn ^Boolean rpc-response? [v]
  (instance? RpcResponse v))

(defn ^Boolean rpc-frame-v1? [v]
  (instance? RpcFrameV1 v))

(defn rpc-page-request-cursor-value [^RpcPageRequest v]
  (rpcpagerequest-cursor v))

(defn rpc-page-response-cursor-value [^RpcPageResponse v]
  (rpcpageresponse-next-cursor v))

(defn rpc-error-detail-value [^RpcError v]
  (rpcerror-detail v))

(defn rpc-request-payload-value [^RpcRequest v]
  (rpcrequest-payload v))

(defn rpc-response-payload-value [^RpcResponse v]
  (rpcresponse-payload v))

(defrecord AtomRow [kind string-value int-value float-value bool-value keyword-value instant-value])

(defn atomrow-kind [r] (:kind r))

(defn atomrow-string-value [r] (:string-value r))

(defn atomrow-int-value [r] (:int-value r))

(defn atomrow-float-value [r] (:float-value r))

(defn atomrow-bool-value [r] (:bool-value r))

(defn atomrow-keyword-value [r] (:keyword-value r))

(defn atomrow-instant-value [r] (:instant-value r))

(defrecord TripleRow [t1 t2 t3])

(defn triplerow-t1 [r] (:t1 r))

(defn triplerow-t2 [r] (:t2 r))

(defn triplerow-t3 [r] (:t3 r))

(defrecord TransactionRow [sequence first-operation operation-count])

(defn transactionrow-sequence [r] (:sequence r))

(defn transactionrow-first-operation [r] (:first-operation r))

(defn transactionrow-operation-count [r] (:operation-count r))

(defrecord OperationRow [tx-sequence ordinal action triple-handle])

(defn operationrow-tx-sequence [r] (:tx-sequence r))

(defn operationrow-ordinal [r] (:ordinal r))

(defn operationrow-action [r] (:action r))

(defn operationrow-triple-handle [r] (:triple-handle r))

(defrecord ActiveBucket [triple-handle positions])

(defn activebucket-triple-handle [r] (:triple-handle r))

(defn activebucket-positions [r] (:positions r))

(defrecord CommitOperation [action proposition])

(defn commitoperation-action [r] (:action r))

(defn commitoperation-proposition [r] (:proposition r))

(defrecord TransactionFrame [sequence operations])

(defn transactionframe-sequence [r] (:sequence r))

(defn transactionframe-operations [r] (:operations r))

(defrecord TermStore [space-id next-sequence atoms triples transactions operations withdrawal-targets active-buckets active-cells fold-open atom-slots triple-slots active-slots])

(defn termstore-space-id [r] (:space-id r))

(defn termstore-next-sequence [r] (:next-sequence r))

(defn termstore-atoms [r] (:atoms r))

(defn termstore-triples [r] (:triples r))

(defn termstore-transactions [r] (:transactions r))

(defn termstore-operations [r] (:operations r))

(defn termstore-withdrawal-targets [r] (:withdrawal-targets r))

(defn termstore-active-buckets [r] (:active-buckets r))

(defn termstore-active-cells [r] (:active-cells r))

(defn termstore-fold-open [r] (:fold-open r))

(defn termstore-atom-slots [r] (:atom-slots r))

(defn termstore-triple-slots [r] (:triple-slots r))

(defn termstore-active-slots [r] (:active-slots r))

(defrecord TermStoreDump [version space-id next-sequence atoms triples transactions operations])

(defn termstoredump-version [r] (:version r))

(defn termstoredump-space-id [r] (:space-id r))

(defn termstoredump-next-sequence [r] (:next-sequence r))

(defn termstoredump-atoms [r] (:atoms r))

(defn termstoredump-triples [r] (:triples r))

(defn termstoredump-transactions [r] (:transactions r))

(defn termstoredump-operations [r] (:operations r))

(defn ^Boolean commit-operation? [v]
  (instance? CommitOperation v))

(defn ^Boolean transaction-frame? [v]
  (instance? TransactionFrame v))

(defn ^Boolean term-store-dump? [v]
  (instance? TermStoreDump v))

(def tx-sequence :kernel/tx-sequence)

(def op-ordinal :kernel/op-ordinal)

(def asserts :kernel/asserts)

(def retracts :kernel/retracts)

(def withdraws :kernel/withdraws)

(def recorded-at-predicate :kernel/recorded-at)

(def assert-action :assert)

(def retract-action :retract)

(defn ^Boolean atom? [v]
  (or (string? v) (or (integer? v) (or (and (number? v) (not (integer? v))) (or (boolean? v) (or (keyword? v) (instant? v)))))))

(defn ^Boolean term? [v]
  (if (triple? v) (and (term? (triple-t1 v)) (and (term? (triple-t2 v)) (term? (triple-t3 v)))) (atom? v)))

(defn ^Triple triple [t1 t2 t3]
  (let [value (->Triple t1 t2 t3)]
  (if (term? value) value (throw (ex-info "fram: triple contains a value outside Term" {:type :invalid-term})))))

(defn ^Triple transaction-coordinate [^String space-id sequence]
  (if (and (pos? (count space-id)) (>= sequence 0)) (triple space-id tx-sequence sequence) (throw (ex-info "fram: transaction coordinate requires a non-empty space and non-negative sequence" {:type :invalid-transaction-coordinate}))))

(defn ^Boolean transaction-coordinate? [v]
  (and (triple? v) (and (string? (triple-t1 v)) (and (pos? (count (triple-t1 v))) (and (= tx-sequence (triple-t2 v)) (and (integer? (triple-t3 v)) (>= (triple-t3 v) 0)))))))

(defn ^Triple occurrence-coordinate [^Triple tx ordinal]
  (if (and (transaction-coordinate? tx) (>= ordinal 0)) (triple tx op-ordinal ordinal) (throw (ex-info "fram: occurrence coordinate requires a transaction coordinate and non-negative ordinal" {:type :invalid-occurrence-coordinate}))))

(defn ^Boolean occurrence-coordinate? [v]
  (and (triple? v) (and (transaction-coordinate? (triple-t1 v)) (and (= op-ordinal (triple-t2 v)) (and (integer? (triple-t3 v)) (>= (triple-t3 v) 0))))))

(defn ^Triple assertion-occurrence [^Triple occurrence ^Triple proposition]
  (if (and (occurrence-coordinate? occurrence) (triple? proposition)) (triple occurrence asserts proposition) (throw (ex-info "fram: assertion requires an occurrence coordinate" {:type :invalid-assertion-occurrence}))))

(defn ^Triple retraction-occurrence [^Triple occurrence ^Triple proposition]
  (if (and (occurrence-coordinate? occurrence) (triple? proposition)) (triple occurrence retracts proposition) (throw (ex-info "fram: retraction requires an occurrence coordinate" {:type :invalid-retraction-occurrence}))))

(defn ^Triple withdrawal [^Triple retraction ^Triple target]
  (if (and (occurrence-coordinate? retraction) (occurrence-coordinate? target)) (triple retraction withdraws target) (throw (ex-info "fram: withdrawal requires retraction and target occurrence coordinates" {:type :invalid-withdrawal}))))

(defn ^Triple recorded-at [^Triple source ^Instant at]
  (if (and (triple? source) (instant? at)) (triple source recorded-at-predicate at) (throw (ex-info "fram: recorded-at requires a triple source and Instant" {:type :invalid-recorded-at}))))

(defn ^Boolean occurrence-before? [^Triple left ^Triple right]
  (if (and (occurrence-coordinate? left) (occurrence-coordinate? right)) (let [left-tx (triple-t1 left)
   right-tx (triple-t1 right)
   left-space (triple-t1 left-tx)
   right-space (triple-t1 right-tx)
   left-seq (triple-t3 left-tx)
   right-seq (triple-t3 right-tx)
   left-ordinal (triple-t3 left)
   right-ordinal (triple-t3 right)]
  (if (= left-space right-space) (or (< left-seq right-seq) (and (= left-seq right-seq) (< left-ordinal right-ordinal))) (throw (ex-info "fram: occurrences from different spaces have no shared order" {:type :incomparable-occurrence-spaces})))) (throw (ex-info "fram: occurrence ordering requires occurrence coordinates" {:type :invalid-occurrence-order}))))
