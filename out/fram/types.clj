(ns fram.types)

^{:line 11 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord Instant [epoch-seconds nanos])

(defn instant-epoch-seconds [r] (:epoch-seconds r))

(defn instant-nanos [r] (:nanos r))

^{:line 12 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean instant? [v]
  ^{:line 12 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? Instant v))

^{:line 13 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Instant instant [epoch-seconds nanos]
  ^{:line 14 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 14 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 14 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (>= nanos 0) ^{:line 14 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (< nanos 1000000000)) ^{:line 15 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (->Instant epoch-seconds nanos) ^{:line 16 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 16 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: instant nanoseconds must be in [0, 1000000000)" ^{:line 17 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-instant}))))

^{:line 22 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord Triple [slot0 slot1 slot2])

(defn triple-slot0 [r] (:slot0 r))

(defn triple-slot1 [r] (:slot1 r))

(defn triple-slot2 [r] (:slot2 r))

^{:line 27 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord RpcPageRequest [limit cursor])

(defn rpcpagerequest-limit [r] (:limit r))

(defn rpcpagerequest-cursor [r] (:cursor r))

^{:line 28 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord RpcPageResponse [ordinal next-cursor done])

(defn rpcpageresponse-ordinal [r] (:ordinal r))

(defn rpcpageresponse-next-cursor [r] (:next-cursor r))

(defn rpcpageresponse-done [r] (:done r))

^{:line 29 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord RpcError [code retryable message detail])

(defn rpcerror-code [r] (:code r))

(defn rpcerror-retryable [r] (:retryable r))

(defn rpcerror-message [r] (:message r))

(defn rpcerror-detail [r] (:detail r))

^{:line 31 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord RpcRequest [space op expected-version page timeout-ms payload])

(defn rpcrequest-space [r] (:space r))

(defn rpcrequest-op [r] (:op r))

(defn rpcrequest-expected-version [r] (:expected-version r))

(defn rpcrequest-page [r] (:page r))

(defn rpcrequest-timeout-ms [r] (:timeout-ms r))

(defn rpcrequest-payload [r] (:payload r))

^{:line 34 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord RpcResponse [space op served-version page error payload])

(defn rpcresponse-space [r] (:space r))

(defn rpcresponse-op [r] (:op r))

(defn rpcresponse-served-version [r] (:served-version r))

(defn rpcresponse-page [r] (:page r))

(defn rpcresponse-error [r] (:error r))

(defn rpcresponse-payload [r] (:payload r))

^{:line 37 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord RpcFrameV1 [kind flags request-id request response])

(defn rpcframev1-kind [r] (:kind r))

(defn rpcframev1-flags [r] (:flags r))

(defn rpcframev1-request-id [r] (:request-id r))

(defn rpcframev1-request [r] (:request r))

(defn rpcframev1-response [r] (:response r))

^{:line 40 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TermCodecMeasure [bytes nodes])

(defn termcodecmeasure-bytes [r] (:bytes r))

(defn termcodecmeasure-nodes [r] (:nodes r))

^{:line 43 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TermCodecDecoded [value nodes])

(defn termcodecdecoded-value [r] (:value r))

(defn termcodecdecoded-nodes [r] (:nodes r))

^{:line 45 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean triple? [v]
  ^{:line 45 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? Triple v))

^{:line 46 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean rpc-page-request? [v]
  ^{:line 46 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? RpcPageRequest v))

^{:line 47 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean rpc-page-response? [v]
  ^{:line 47 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? RpcPageResponse v))

^{:line 48 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean rpc-error? [v]
  ^{:line 48 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? RpcError v))

^{:line 49 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean rpc-request? [v]
  ^{:line 49 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? RpcRequest v))

^{:line 50 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean rpc-response? [v]
  ^{:line 50 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? RpcResponse v))

^{:line 51 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean rpc-frame-v1? [v]
  ^{:line 51 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? RpcFrameV1 v))

^{:line 55 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn rpc-page-request-cursor-value [^RpcPageRequest v]
  ^{:line 56 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (rpcpagerequest-cursor v))

^{:line 57 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn rpc-page-response-cursor-value [^RpcPageResponse v]
  ^{:line 58 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (rpcpageresponse-next-cursor v))

^{:line 59 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn rpc-error-detail-value [^RpcError v]
  ^{:line 59 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (rpcerror-detail v))

^{:line 60 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn rpc-request-payload-value [^RpcRequest v]
  ^{:line 60 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (rpcrequest-payload v))

^{:line 61 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn rpc-response-payload-value [^RpcResponse v]
  ^{:line 61 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (rpcresponse-payload v))

^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord AtomRow [kind string-value int-value float-value bool-value keyword-value instant-value])

(defn atomrow-kind [r] (:kind r))

(defn atomrow-string-value [r] (:string-value r))

(defn atomrow-int-value [r] (:int-value r))

(defn atomrow-float-value [r] (:float-value r))

(defn atomrow-bool-value [r] (:bool-value r))

(defn atomrow-keyword-value [r] (:keyword-value r))

(defn atomrow-instant-value [r] (:instant-value r))

^{:line 68 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TripleRow [slot0 slot1 slot2])

(defn triplerow-slot0 [r] (:slot0 r))

(defn triplerow-slot1 [r] (:slot1 r))

(defn triplerow-slot2 [r] (:slot2 r))

^{:line 69 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TermBucket [key positions])

(defn termbucket-key [r] (:key r))

(defn termbucket-positions [r] (:positions r))

^{:line 74 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TransactionRow [sequence first-operation operation-count])

(defn transactionrow-sequence [r] (:sequence r))

(defn transactionrow-first-operation [r] (:first-operation r))

(defn transactionrow-operation-count [r] (:operation-count r))

^{:line 76 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord OperationRow [tx-sequence ordinal action triple-handle])

(defn operationrow-tx-sequence [r] (:tx-sequence r))

(defn operationrow-ordinal [r] (:ordinal r))

(defn operationrow-action [r] (:action r))

(defn operationrow-triple-handle [r] (:triple-handle r))

^{:line 78 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord ActiveBucket [triple-handle positions])

(defn activebucket-triple-handle [r] (:triple-handle r))

(defn activebucket-positions [r] (:positions r))

^{:line 79 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord CommitOperation [action proposition])

(defn commitoperation-action [r] (:action r))

(defn commitoperation-proposition [r] (:proposition r))

^{:line 80 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TransactionFrame [sequence operations])

(defn transactionframe-sequence [r] (:sequence r))

(defn transactionframe-operations [r] (:operations r))

^{:line 81 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TermStore [space-id next-sequence atoms triples transactions operations operation-live withdrawal-targets active-buckets atom-slots triple-slots active-slots])

(defn termstore-space-id [r] (:space-id r))

(defn termstore-next-sequence [r] (:next-sequence r))

(defn termstore-atoms [r] (:atoms r))

(defn termstore-triples [r] (:triples r))

(defn termstore-transactions [r] (:transactions r))

(defn termstore-operations [r] (:operations r))

(defn termstore-operation-live [r] (:operation-live r))

(defn termstore-withdrawal-targets [r] (:withdrawal-targets r))

(defn termstore-active-buckets [r] (:active-buckets r))

(defn termstore-atom-slots [r] (:atom-slots r))

(defn termstore-triple-slots [r] (:triple-slots r))

(defn termstore-active-slots [r] (:active-slots r))

^{:line 89 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defrecord TermStoreDump [version space-id next-sequence atoms triples transactions operations])

(defn termstoredump-version [r] (:version r))

(defn termstoredump-space-id [r] (:space-id r))

(defn termstoredump-next-sequence [r] (:next-sequence r))

(defn termstoredump-atoms [r] (:atoms r))

(defn termstoredump-triples [r] (:triples r))

(defn termstoredump-transactions [r] (:transactions r))

(defn termstoredump-operations [r] (:operations r))

^{:line 94 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean commit-operation? [v]
  ^{:line 94 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? CommitOperation v))

^{:line 95 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean transaction-frame? [v]
  ^{:line 95 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? TransactionFrame v))

^{:line 96 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean term-store-dump? [v]
  ^{:line 96 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instance? TermStoreDump v))

^{:line 98 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def tx-sequence :kernel/tx-sequence)

^{:line 99 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def op-ordinal :kernel/op-ordinal)

^{:line 100 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def asserts :kernel/asserts)

^{:line 101 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def retracts :kernel/retracts)

^{:line 102 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def withdraws :kernel/withdraws)

^{:line 103 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def recorded-at-predicate :kernel/recorded-at)

^{:line 104 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def assert-action :assert)

^{:line 105 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (def retract-action :retract)

^{:line 107 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean atom? [v]
  ^{:line 108 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (or ^{:line 108 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (string? v) ^{:line 109 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (or ^{:line 109 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (integer? v) ^{:line 110 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (or ^{:line 110 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 110 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (number? v) ^{:line 110 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (not ^{:line 110 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (integer? v))) ^{:line 111 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (or ^{:line 111 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (boolean? v) ^{:line 111 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (or ^{:line 111 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (keyword? v) ^{:line 111 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instant? v)))))))

^{:line 113 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean term? [v]
  ^{:line 114 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 114 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple? v) ^{:line 115 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 115 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (term? ^{:line 115 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 v)) ^{:line 116 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 116 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (term? ^{:line 116 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot1 v)) ^{:line 117 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (term? ^{:line 117 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 v)))) ^{:line 118 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (atom? v)))

^{:line 120 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Triple triple [slot0 slot1 slot2]
  ^{:line 121 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (let [value ^{:line 121 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (->Triple slot0 slot1 slot2)]
  ^{:line 122 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 122 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (term? value) value ^{:line 124 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 124 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: triple contains a value outside Term" ^{:line 124 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-term})))))

^{:line 126 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Triple transaction-coordinate [^String space-id sequence]
  ^{:line 127 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 127 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 127 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (pos? ^{:line 127 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (count space-id)) ^{:line 127 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (>= sequence 0)) ^{:line 128 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple space-id tx-sequence sequence) ^{:line 129 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 129 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: transaction coordinate requires a non-empty space and non-negative sequence" ^{:line 130 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-transaction-coordinate}))))

^{:line 132 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean transaction-coordinate? [v]
  ^{:line 133 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 133 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple? v) ^{:line 134 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 134 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (string? ^{:line 134 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 v)) ^{:line 135 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 135 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (pos? ^{:line 135 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (count ^{:line 135 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 v))) ^{:line 136 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 136 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (= tx-sequence ^{:line 136 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot1 v)) ^{:line 137 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 137 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (integer? ^{:line 137 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 v)) ^{:line 138 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (>= ^{:line 138 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 v) 0)))))))

^{:line 140 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Triple occurrence-coordinate [^Triple tx ordinal]
  ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (transaction-coordinate? tx) ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (>= ordinal 0)) ^{:line 142 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple tx op-ordinal ordinal) ^{:line 143 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 143 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: occurrence coordinate requires a transaction coordinate and non-negative ordinal" ^{:line 144 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-occurrence-coordinate}))))

^{:line 146 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean occurrence-coordinate? [v]
  ^{:line 147 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 147 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple? v) ^{:line 148 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 148 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (transaction-coordinate? ^{:line 148 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 v)) ^{:line 149 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 149 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (= op-ordinal ^{:line 149 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot1 v)) ^{:line 150 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 150 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (integer? ^{:line 150 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 v)) ^{:line 151 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (>= ^{:line 151 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 v) 0))))))

^{:line 153 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Triple assertion-occurrence [^Triple occurrence ^Triple proposition]
  ^{:line 154 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 154 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 154 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (occurrence-coordinate? occurrence) ^{:line 154 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple? proposition)) ^{:line 155 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple occurrence asserts proposition) ^{:line 156 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 156 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: assertion requires an occurrence coordinate" ^{:line 157 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-assertion-occurrence}))))

^{:line 159 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Triple retraction-occurrence [^Triple occurrence ^Triple proposition]
  ^{:line 160 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 160 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 160 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (occurrence-coordinate? occurrence) ^{:line 160 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple? proposition)) ^{:line 161 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple occurrence retracts proposition) ^{:line 162 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 162 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: retraction requires an occurrence coordinate" ^{:line 163 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-retraction-occurrence}))))

^{:line 165 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Triple withdrawal [^Triple retraction ^Triple target]
  ^{:line 166 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 166 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 166 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (occurrence-coordinate? retraction) ^{:line 166 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (occurrence-coordinate? target)) ^{:line 167 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple retraction withdraws target) ^{:line 168 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 168 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: withdrawal requires retraction and target occurrence coordinates" ^{:line 169 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-withdrawal}))))

^{:line 171 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Triple recorded-at [^Triple source ^Instant at]
  ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple? source) ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (instant? at)) ^{:line 173 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple source recorded-at-predicate at) ^{:line 174 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 174 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: recorded-at requires a triple source and Instant" ^{:line 175 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-recorded-at}))))

^{:line 177 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (defn ^Boolean occurrence-before? [^Triple left ^Triple right]
  ^{:line 178 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 178 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 178 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (occurrence-coordinate? left) ^{:line 178 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (occurrence-coordinate? right)) ^{:line 179 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (let [left-tx ^{:line 179 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 left)
   right-tx ^{:line 180 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 right)
   left-space ^{:line 181 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 left-tx)
   right-space ^{:line 182 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot0 right-tx)
   left-seq ^{:line 183 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 left-tx)
   right-seq ^{:line 184 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 right-tx)
   left-ordinal ^{:line 185 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 left)
   right-ordinal ^{:line 186 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (triple-slot2 right)]
  ^{:line 187 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (if ^{:line 187 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (= left-space right-space) ^{:line 188 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (or ^{:line 188 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (< left-seq right-seq) ^{:line 189 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (and ^{:line 189 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (= left-seq right-seq) ^{:line 189 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (< left-ordinal right-ordinal))) ^{:line 190 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 190 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: occurrences from different spaces have no shared order" ^{:line 191 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :incomparable-occurrence-spaces})))) ^{:line 192 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (throw ^{:line 192 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} (ex-info "fram: occurrence ordering requires occurrence coordinates" ^{:line 193 :file "/home/tom/code/fram/wt-fram-rpc/src/fram/types.bclj"} {:type :invalid-occurrence-order}))))
