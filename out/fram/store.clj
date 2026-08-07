(ns fram.store
  (:require [fram.types :as t]
            [fram.slots :as slots]))

(def empty-ids [])

(def empty-live-cells [])

(def empty-atoms [])

(def empty-triple-rows [])

(def empty-transaction-rows [])

(def empty-operation-rows [])

(def empty-commit-operations [])

(def empty-transaction-frames [])

(def empty-active-buckets [])

(def empty-active-cells [])

(def term-store-dump-version 2)

(def max-transaction-sequence 9223372036854775806)

(defrecord TermStoreLoadResult [ok code message])

(defn termstoreloadresult-ok [r] (:ok r))

(defn termstoreloadresult-code [r] (:code r))

(defn termstoreloadresult-message [r] (:message r))

(defrecord TransactionReplayResult [ok code message])

(defn transactionreplayresult-ok [r] (:ok r))

(defn transactionreplayresult-code [r] (:code r))

(defn transactionreplayresult-message [r] (:message r))

(defrecord TransactionFramesResult [ok frames code message])

(defn transactionframesresult-ok [r] (:ok r))

(defn transactionframesresult-frames [r] (:frames r))

(defn transactionframesresult-code [r] (:code r))

(defn transactionframesresult-message [r] (:message r))

(def initial-slots 64)

(def slot-load 4)

(defn- ^Boolean valid-space-id? [space-id]
  (and (string? space-id) (pos? (count space-id))))

(defn- term-slots-width-for [n]
  (loop [width initial-slots]
  (if (>= (* slot-load width) n) width (recur (* 2 width)))))

(defn- build-atom-term-slots! [atoms width]
  (loop [slots (slots/fresh-slots width)
   position 0]
  (if (>= position (count atoms)) slots (recur (slots/slot-add! slots (slots/slot-of (nth atoms position) width) position) (inc position)))))

(defn- build-triple-term-slots! [rows width]
  (loop [slots (slots/fresh-slots width)
   position 0]
  (if (>= position (count rows)) slots (recur (slots/slot-add! slots (slots/slot-of (nth rows position) width) position) (inc position)))))

(defn- build-active-slots! [buckets width]
  (loop [slots (slots/fresh-slots width)
   position 0]
  (if (>= position (count buckets)) slots (recur (slots/slot-add! slots (slots/slot-of (t/activebucket-triple-handle (nth buckets position)) width) position) (inc position)))))

(defn new-term-store [^String space-id]
  (if (valid-space-id? space-id) (atom (t/->TermStore space-id 1 empty-atoms empty-triple-rows empty-transaction-rows empty-operation-rows empty-ids empty-active-buckets empty-active-cells false (slots/fresh-slots initial-slots) (slots/fresh-slots initial-slots) (slots/fresh-slots initial-slots))) (throw (ex-info "fram: TermStore requires a non-empty SpaceId" {:type :invalid-space-id}))))

(defn ^String space-id [ctx]
  (t/termstore-space-id (deref ctx)))

(defn next-sequence [ctx]
  (t/termstore-next-sequence (deref ctx)))

(defn current-sequence [ctx]
  (dec (next-sequence ctx)))

(defn- atom-row [value]
  (cond
  (string? value) (t/->AtomRow :string value nil nil nil nil nil)
  (integer? value) (t/->AtomRow :int nil value nil nil nil nil)
  (number? value) (t/->AtomRow :float nil nil value nil nil nil)
  (boolean? value) (t/->AtomRow :bool nil nil nil value nil nil)
  (keyword? value) (t/->AtomRow :keyword nil nil nil nil value nil)
  (t/instant? value) (t/->AtomRow :instant nil nil nil nil nil value)
  :else (throw (ex-info "fram: value outside Atom" {:type :invalid-atom}))))

(defn- atom-row-value [row]
  (cond
  (= :string (t/atomrow-kind row)) (t/atomrow-string-value row)
  (= :int (t/atomrow-kind row)) (t/atomrow-int-value row)
  (= :float (t/atomrow-kind row)) (t/atomrow-float-value row)
  (= :bool (t/atomrow-kind row)) (t/atomrow-bool-value row)
  (= :keyword (t/atomrow-kind row)) (t/atomrow-keyword-value row)
  (= :instant (t/atomrow-kind row)) (t/atomrow-instant-value row)
  :else nil))

(defn- ^Boolean valid-atom-row? [row]
  (let [value (atom-row-value row)]
  (and (some? value) (= row (atom-row value)))))

(defn- find-atom-position [atoms slots value]
  (let [positions (deref (nth slots (slots/slot-of value (count slots))))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (and (< position (count atoms)) (= (nth atoms position) value)) position (recur (inc offset))))))))

(defn- find-triple-position [rows slots value]
  (let [positions (deref (nth slots (slots/slot-of value (count slots))))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (and (< position (count rows)) (= (nth rows position) value)) position (recur (inc offset))))))))

(defn- index-atom-term! [ctx value position]
  (let [store (deref ctx)
   slots (slots/slot-add! (t/termstore-atom-slots store) (slots/slot-of value (count (t/termstore-atom-slots store))) position)]
  (if (> (count (t/termstore-atoms store)) (* slot-load (count slots))) (swap! ctx assoc :atom-slots (build-atom-term-slots! (t/termstore-atoms store) (* 2 (count slots)))) store)))

(defn- index-triple-term! [ctx value position]
  (let [store (deref ctx)
   slots (slots/slot-add! (t/termstore-triple-slots store) (slots/slot-of value (count (t/termstore-triple-slots store))) position)]
  (if (> (count (t/termstore-triples store)) (* slot-load (count slots))) (swap! ctx assoc :triple-slots (build-triple-term-slots! (t/termstore-triples store) (* 2 (count slots)))) store)))

(defn- atom-handle [position]
  (* 2 position))

(defn- triple-handle [position]
  (inc (* 2 position)))

(defn- ^Boolean atom-handle? [handle]
  (= 0 (mod handle 2)))

(defn- handle-position [handle]
  (quot handle 2))

(defn- intern-handle! [ctx term]
  (if (not (t/term? term)) (throw (ex-info "fram: cannot intern a value outside Term" {:type :invalid-term})) (if (t/triple? term) (let [t1 (intern-handle! ctx (t/triple-t1 term))
   t2 (intern-handle! ctx (t/triple-t2 term))
   t3 (intern-handle! ctx (t/triple-t3 term))
   store (deref ctx)
   rows (t/termstore-triples store)
   value (t/->TripleRow t1 t2 t3)
   known (find-triple-position rows (t/termstore-triple-slots store) value)]
  (if (>= known 0) (triple-handle known) (let [position (count rows)]
  (swap! ctx update :triples (fn [current] (conj current value)))
  (index-triple-term! ctx value position)
  (triple-handle position)))) (let [value (atom-row term)
   store (deref ctx)
   atoms (t/termstore-atoms store)
   known (find-atom-position atoms (t/termstore-atom-slots store) value)]
  (if (>= known 0) (atom-handle known) (let [position (count atoms)]
  (swap! ctx update :atoms (fn [current] (conj current value)))
  (index-atom-term! ctx value position)
  (atom-handle position)))))))

(defn- ^Boolean valid-handle? [store handle]
  (and (>= handle 0) (if (atom-handle? handle) (< (handle-position handle) (count (t/termstore-atoms store))) (< (handle-position handle) (count (t/termstore-triples store))))))

(defn- resolve-handle [store handle]
  (if (not (valid-handle? store handle)) (throw (ex-info "fram: term handle does not resolve" {:type :invalid-term-handle})) (let [position (handle-position handle)]
  (if (atom-handle? handle) (atom-row-value (nth (t/termstore-atoms store) position)) (let [row (nth (t/termstore-triples store) position)]
  (t/triple (resolve-handle store (t/triplerow-t1 row)) (resolve-handle store (t/triplerow-t2 row)) (resolve-handle store (t/triplerow-t3 row))))))))

(defn- resolve-triple-handle [store handle]
  (let [term (resolve-handle store handle)]
  (if (t/triple? term) term (throw (ex-info "fram: operation handle does not resolve to Triple" {:type :invalid-operation-handle})))))

(defn known-term-handle [store term]
  (if (not (t/term? term)) nil (if (t/triple? term) (let [t1 (known-term-handle store (t/triple-t1 term))
   t2 (known-term-handle store (t/triple-t2 term))
   t3 (known-term-handle store (t/triple-t3 term))
   handles [t1 t2 t3]
   all-known (loop [position 0]
  (if (>= position (count handles)) true (if (some? (nth handles position)) (recur (inc position)) false)))]
  (if all-known (let [position (find-triple-position (t/termstore-triples store) (t/termstore-triple-slots store) (t/->TripleRow (if (some? t1) t1 0) (if (some? t2) t2 0) (if (some? t3) t3 0)))]
  (if (>= position 0) (do
  (triple-handle position)))) nil)) (let [position (find-atom-position (t/termstore-atoms store) (t/termstore-atom-slots store) (atom-row term))]
  (if (>= position 0) (do
  (atom-handle position)))))))

(defn intern-term! [ctx term]
  (let [handle (intern-handle! ctx term)]
  (resolve-handle (deref ctx) handle)))

(defn replay-terms! [ctx terms]
  (do
  (doseq [term terms]
  (intern-term! ctx term))
  ctx))

(defn atom-term-count [ctx]
  (count (t/termstore-atoms (deref ctx))))

(defn triple-term-count [ctx]
  (count (t/termstore-triples (deref ctx))))

(defn term-count [ctx]
  (+ (atom-term-count ctx) (triple-term-count ctx)))

(defn transaction-count [ctx]
  (count (t/termstore-transactions (deref ctx))))

(defn operation-count [ctx]
  (count (t/termstore-operations (deref ctx))))

(defn assert-operation [proposition]
  (if (and (t/triple? proposition) (t/term? proposition)) (t/->CommitOperation t/assert-action proposition) (throw (ex-info "fram: assertion operation requires a Triple" {:type :invalid-commit-operation}))))

(defn retract-operation [proposition]
  (if (and (t/triple? proposition) (t/term? proposition)) (t/->CommitOperation t/retract-action proposition) (throw (ex-info "fram: retraction operation requires a Triple" {:type :invalid-commit-operation}))))

(defn- ^Boolean valid-commit-operation? [operation]
  (and (or (= t/assert-action (t/commitoperation-action operation)) (= t/retract-action (t/commitoperation-action operation))) (and (t/triple? (t/commitoperation-proposition operation)) (t/term? (t/commitoperation-proposition operation)))))

(defn- ^Boolean valid-operations? [operations]
  (and (pos? (count operations)) (every? (fn [operation] (valid-commit-operation? operation)) operations)))

(defn transaction-frame [sequence operations]
  (if (and (>= sequence 0) (valid-operations? operations)) (t/->TransactionFrame sequence operations) (throw (ex-info "fram: transaction frame requires a non-negative sequence and operations" {:type :invalid-transaction-frame}))))

(defn- find-active-bucket-position [store handle]
  (let [slots (t/termstore-active-slots store)
   buckets (t/termstore-active-buckets store)
   positions (deref (nth slots (slots/slot-of handle (count slots))))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (and (< position (count buckets)) (= handle (t/activebucket-triple-handle (nth buckets position)))) position (recur (inc offset))))))))

(defn- active-positions [store handle]
  (let [position (find-active-bucket-position store handle)]
  (if (>= position 0) (if (t/termstore-fold-open store) (deref (nth (t/termstore-active-cells store) position)) (t/activebucket-positions (nth (t/termstore-active-buckets store) position))) empty-ids)))

(defn- set-active-positions! [store handle positions]
  (let [folding (t/termstore-fold-open store)
   known (find-active-bucket-position store handle)]
  (if (>= known 0) (if folding (do
  (reset! (nth (t/termstore-active-cells store) known) positions)
  store) (assoc store :active-buckets (assoc (t/termstore-active-buckets store) known (t/->ActiveBucket handle positions)))) (let [buckets (conj (t/termstore-active-buckets store) (t/->ActiveBucket handle (if folding empty-ids positions)))
   position (dec (count buckets))
   slots (slots/slot-add! (t/termstore-active-slots store) (slots/slot-of handle (count (t/termstore-active-slots store))) position)
   store-with-bucket (if folding (assoc store :active-buckets buckets :active-cells (conj (t/termstore-active-cells store) (atom positions))) (assoc store :active-buckets buckets))]
  (if (> (count buckets) (* slot-load (count slots))) (assoc store-with-bucket :active-slots (build-active-slots! buckets (* 2 (count slots)))) store-with-bucket)))))

(defn- open-fold-state [store]
  (if (t/termstore-fold-open store) store (let [buckets (t/termstore-active-buckets store)
   opened (loop [built empty-active-buckets
   position 0]
  (if (>= position (count buckets)) built (recur (conj built (t/->ActiveBucket (t/activebucket-triple-handle (nth buckets position)) empty-ids)) (inc position))))
   cells (loop [built empty-active-cells
   position 0]
  (if (>= position (count buckets)) built (recur (conj built (atom (t/activebucket-positions (nth buckets position)))) (inc position))))]
  (assoc store :active-buckets opened :active-cells cells :fold-open true))))

(defn- close-fold-state [store]
  (if (not (t/termstore-fold-open store)) store (let [buckets (t/termstore-active-buckets store)
   cells (t/termstore-active-cells store)
   closed (loop [built empty-active-buckets
   position 0]
  (if (>= position (count buckets)) built (recur (conj built (t/->ActiveBucket (t/activebucket-triple-handle (nth buckets position)) (deref (nth cells position)))) (inc position))))]
  (assoc store :active-buckets closed :active-cells empty-active-cells :fold-open false))))

(defn open-fold! [ctx]
  (do
  (reset! ctx (open-fold-state (deref ctx)))
  ctx))

(defn close-fold! [ctx]
  (do
  (reset! ctx (close-fold-state (deref ctx)))
  ctx))

(defn- apply-operation-state! [store operation-position row]
  (let [handle (t/operationrow-triple-handle row)
   action (t/operationrow-action row)
   active (active-positions store handle)]
  (if (= action t/assert-action) (set-active-positions! (assoc store :withdrawal-targets (conj (t/termstore-withdrawal-targets store) -1)) handle (conj active operation-position)) (if (empty? active) (assoc store :withdrawal-targets (conj (t/termstore-withdrawal-targets store) -1)) (let [target (peek active)]
  (set-active-positions! (assoc store :withdrawal-targets (conj (t/termstore-withdrawal-targets store) target)) handle (pop active)))))))

(defn- operation-handles! [ctx operations]
  (reduce (fn [handles operation] (conj handles (intern-handle! ctx (t/commitoperation-proposition operation)))) empty-ids operations))

(defn- ^TransactionReplayResult transaction-replay-ok []
  (->TransactionReplayResult true nil nil))

(defn- ^TransactionReplayResult transaction-replay-error [code ^String message]
  (->TransactionReplayResult false code message))

(defn- ^TransactionReplayResult transaction-replay-unclassified-error []
  (->TransactionReplayResult false nil nil))

(defn- append-valid-transaction! [ctx sequence operations]
  (let [handles (operation-handles! ctx operations)
   store (deref ctx)
   first-operation (count (t/termstore-operations store))
   transaction-row (t/->TransactionRow sequence first-operation (count operations))
   with-transaction (assoc store :transactions (conj (t/termstore-transactions store) transaction-row))
   appended (loop [current with-transaction
   ordinal 0]
  (if (>= ordinal (count operations)) current (let [operation (nth operations ordinal)
   row (t/->OperationRow sequence ordinal (t/commitoperation-action operation) (nth handles ordinal))
   operation-position (+ first-operation ordinal)
   with-row (assoc current :operations (conj (t/termstore-operations current) row))]
  (recur (apply-operation-state! with-row operation-position row) (inc ordinal)))))
   final-store (assoc appended :next-sequence (inc sequence))]
  (do
  (reset! ctx final-store)
  final-store)))

(defn- ^TransactionReplayResult append-transaction-result! [ctx sequence operations]
  (let [before (deref ctx)]
  (cond
  (not (valid-operations? operations)) (transaction-replay-error :invalid-transaction-frame "fram: transaction requires at least one valid operation")
  (< sequence (t/termstore-next-sequence before)) (transaction-replay-error :nonmonotonic-transaction-sequence "fram: transaction sequence must advance within its space")
  (> sequence max-transaction-sequence) (transaction-replay-unclassified-error)
  :else (do
  (append-valid-transaction! ctx sequence operations)
  (transaction-replay-ok)))))

(defn- append-transaction! [ctx sequence operations]
  (let [before (deref ctx)]
  (if (not (valid-operations? operations)) (throw (ex-info "fram: transaction requires at least one valid operation" {:type :invalid-transaction-frame})) (if (< sequence (t/termstore-next-sequence before)) (throw (ex-info "fram: transaction sequence must advance within its space" {:type :nonmonotonic-transaction-sequence})) (let [final-store (append-valid-transaction! ctx sequence operations)]
  (t/transaction-coordinate (t/termstore-space-id final-store) sequence))))))

(defn commit-transaction! [ctx operations]
  (append-transaction! ctx (t/termstore-next-sequence (deref ctx)) operations))

(defn ^TransactionReplayResult replay-transaction-result! [ctx frame]
  (if (and (t/transaction-frame? frame) (and (>= (t/transactionframe-sequence frame) 0) (valid-operations? (t/transactionframe-operations frame)))) (append-transaction-result! ctx (t/transactionframe-sequence frame) (t/transactionframe-operations frame)) (transaction-replay-error :invalid-transaction-frame "fram: invalid transaction frame")))

(defn replay-transaction! [ctx frame]
  (if (and (t/transaction-frame? frame) (and (>= (t/transactionframe-sequence frame) 0) (valid-operations? (t/transactionframe-operations frame)))) (append-transaction! ctx (t/transactionframe-sequence frame) (t/transactionframe-operations frame)) (throw (ex-info "fram: invalid transaction frame" {:type :invalid-transaction-frame}))))

(defn- occurrence-at [store operation-position]
  (let [row (nth (t/termstore-operations store) operation-position)]
  (t/occurrence-coordinate (t/transaction-coordinate (t/termstore-space-id store) (t/operationrow-tx-sequence row)) (t/operationrow-ordinal row))))

(defn- event-at [store operation-position]
  (let [row (nth (t/termstore-operations store) operation-position)
   occurrence (occurrence-at store operation-position)
   proposition (resolve-triple-handle store (t/operationrow-triple-handle row))]
  (if (= t/assert-action (t/operationrow-action row)) (t/assertion-occurrence occurrence proposition) (t/retraction-occurrence occurrence proposition))))

(defn- first-transaction-after [transactions sequence]
  (loop [low 0
   high (count transactions)]
  (if (>= low high) low (let [middle (quot (+ low high) 2)
   candidate (t/transactionrow-sequence (nth transactions middle))]
  (if (<= candidate sequence) (recur (inc middle) high) (recur low middle))))))

(defn operation-range-bounds [store lower-exclusive upper-inclusive]
  (let [transactions (t/termstore-transactions store)
   operations (t/termstore-operations store)
   start-transaction (first-transaction-after transactions lower-exclusive)
   end-transaction (first-transaction-after transactions upper-inclusive)
   start (if (>= start-transaction (count transactions)) (count operations) (t/transactionrow-first-operation (nth transactions start-transaction)))
   end (if (>= end-transaction (count transactions)) (count operations) (t/transactionrow-first-operation (nth transactions end-transaction)))]
  [start end]))

(defn transaction-frames-between [store lower-exclusive upper-inclusive]
  (let [transactions (t/termstore-transactions store)
   first (first-transaction-after transactions lower-exclusive)
   end (first-transaction-after transactions upper-inclusive)]
  (mapv (fn [row] (let [start (t/transactionrow-first-operation row)
   stop (+ start (t/transactionrow-operation-count row))]
  (t/->TransactionFrame (t/transactionrow-sequence row) (mapv (fn [operation] (t/->CommitOperation (t/operationrow-action operation) (resolve-triple-handle store (t/operationrow-triple-handle operation)))) (subvec (t/termstore-operations store) start stop))))) (subvec transactions first end))))

(defn operation-postings [store]
  (loop [position 0
   postings {}]
  (if (>= position (count (t/termstore-operations store))) postings (let [handle (t/operationrow-triple-handle (nth (t/termstore-operations store) position))]
  (recur (inc position) (update postings handle (fn [known] (if (nil? known) [position] (conj known position)))))))))

(defn- lower-bound-position [positions target]
  (loop [low 0
   high (count positions)]
  (if (>= low high) low (let [middle (quot (+ low high) 2)]
  (if (< (nth positions middle) target) (recur (inc middle) high) (recur low middle))))))

(defn- exact-occurrence-position [store coordinate]
  (if (not (t/occurrence-coordinate? coordinate)) -1 (let [transaction (t/triple-t1 coordinate)
   space (t/triple-t1 transaction)
   sequence (t/triple-t3 transaction)
   ordinal (t/triple-t3 coordinate)
   transactions (t/termstore-transactions store)
   position (first-transaction-after transactions (dec sequence))]
  (if (or (not (= space (t/termstore-space-id store))) (>= position (count transactions))) -1 (let [row (nth transactions position)]
  (if (and (= sequence (t/transactionrow-sequence row)) (< ordinal (t/transactionrow-operation-count row))) (+ (t/transactionrow-first-operation row) ordinal) -1))))))

(defn operation-candidate-positions [store lower-exclusive upper-inclusive coordinate proposition postings]
  (let [bounds (operation-range-bounds store lower-exclusive upper-inclusive)
   start (nth bounds 0)
   end (nth bounds 1)
   exact (if (some? coordinate) (do
  (exact-occurrence-position store coordinate)))
   proposition-handle (if (some? proposition) (do
  (known-term-handle store proposition)))
   posted (if (some? proposition-handle) (get postings proposition-handle []) [])
   from (lower-bound-position posted start)
   until (lower-bound-position posted end)
   candidates (cond
  (some? exact) (if (and (>= exact start) (< exact end)) [exact] [])
  (some? proposition) (if (some? proposition-handle) (subvec posted from until) [])
  :else (vec (range start end)))]
  (if (and (some? exact) (some? proposition)) (filterv (fn [position] (= proposition-handle (t/operationrow-triple-handle (nth (t/termstore-operations store) position)))) candidates) candidates)))

(defn occurrence-tuple-at [store position]
  (let [event (event-at store position)]
  [(t/triple-t1 event) (t/triple-t2 event) (t/triple-t3 event)]))

(defn operation-occurrences [ctx]
  (let [store (deref ctx)]
  (loop [position 0
   events []]
  (if (>= position (count (t/termstore-operations store))) events (recur (inc position) (conj events (event-at store position)))))))

(defn withdrawal-triples [ctx]
  (let [store (deref ctx)]
  (loop [position 0
   withdrawals []]
  (if (>= position (count (t/termstore-operations store))) withdrawals (let [target (nth (t/termstore-withdrawal-targets store) position)]
  (if (>= target 0) (recur (inc position) (conj withdrawals (t/withdrawal (occurrence-at store position) (occurrence-at store target)))) (recur (inc position) withdrawals)))))))

(defn semantic-history [ctx]
  (let [store (deref ctx)]
  (loop [position 0
   history []]
  (if (>= position (count (t/termstore-operations store))) history (let [with-event (conj history (event-at store position))
   target (nth (t/termstore-withdrawal-targets store) position)]
  (if (>= target 0) (recur (inc position) (conj with-event (t/withdrawal (occurrence-at store position) (occurrence-at store target)))) (recur (inc position) with-event)))))))

(defn- operation-live-cells [store]
  (let [operations (t/termstore-operations store)
   targets (t/termstore-withdrawal-targets store)
   cells (loop [built empty-live-cells
   position 0]
  (if (>= position (count operations)) built (recur (conj built (atom (= t/assert-action (t/operationrow-action (nth operations position))))) (inc position))))]
  (loop [position 0]
  (if (>= position (count targets)) cells (let [target (nth targets position)]
  (if (>= target 0) (do
  (reset! (nth cells target) false)
  (recur (inc position))) (recur (inc position))))))))

(defn live-occurrences [ctx]
  (let [store (deref ctx)
   cells (operation-live-cells store)]
  (loop [position 0
   live []]
  (if (>= position (count (t/termstore-operations store))) live (if (deref (nth cells position)) (recur (inc position) (conj live (event-at store position))) (recur (inc position) live))))))

(defn live-propositions [ctx]
  (let [store (deref ctx)
   cells (operation-live-cells store)]
  (loop [position 0
   live []]
  (if (>= position (count (t/termstore-operations store))) live (let [row (nth (t/termstore-operations store) position)]
  (if (deref (nth cells position)) (recur (inc position) (conj live (resolve-triple-handle store (t/operationrow-triple-handle row)))) (recur (inc position) live)))))))

(defn dump-term-store [ctx]
  (let [store (deref ctx)]
  (t/->TermStoreDump term-store-dump-version (t/termstore-space-id store) (t/termstore-next-sequence store) (t/termstore-atoms store) (t/termstore-triples store) (t/termstore-transactions store) (t/termstore-operations store))))

(defn- ^Boolean valid-prior-handle? [atom-count triple-position handle]
  (and (>= handle 0) (if (atom-handle? handle) (< (handle-position handle) atom-count) (< (handle-position handle) triple-position))))

(defn- ^Boolean valid-triple-rows? [atom-count rows]
  (loop [position 0]
  (if (>= position (count rows)) true (let [row (nth rows position)]
  (if (and (valid-prior-handle? atom-count position (t/triplerow-t1 row)) (and (valid-prior-handle? atom-count position (t/triplerow-t2 row)) (valid-prior-handle? atom-count position (t/triplerow-t3 row)))) (recur (inc position)) false)))))

(defn- ^Boolean valid-dump-operation? [triple-count sequence ordinal row]
  (let [handle (t/operationrow-triple-handle row)]
  (and (= sequence (t/operationrow-tx-sequence row)) (and (= ordinal (t/operationrow-ordinal row)) (and (or (= t/assert-action (t/operationrow-action row)) (= t/retract-action (t/operationrow-action row))) (and (>= handle 0) (and (not (atom-handle? handle)) (< (handle-position handle) triple-count))))))))

(defn- ^Boolean valid-operation-slice? [operations triple-count sequence first-operation operation-count]
  (loop [ordinal 0]
  (if (>= ordinal operation-count) true (if (valid-dump-operation? triple-count sequence ordinal (nth operations (+ first-operation ordinal))) (recur (inc ordinal)) false))))

(defn- ^Boolean valid-history-rows? [transactions operations triple-count next-sequence-value]
  (loop [transaction-position 0
   operation-position 0
   previous-sequence -1]
  (if (>= transaction-position (count transactions)) (and (= operation-position (count operations)) (if (< previous-sequence 0) (= next-sequence-value 1) (and (> next-sequence-value 0) (= (dec next-sequence-value) previous-sequence)))) (let [row (nth transactions transaction-position)
   sequence (t/transactionrow-sequence row)
   first-operation (t/transactionrow-first-operation row)
   operation-count-value (t/transactionrow-operation-count row)]
  (if (and (> sequence previous-sequence) (and (= first-operation operation-position) (and (pos? operation-count-value) (and (<= operation-count-value (- (count operations) first-operation)) (valid-operation-slice? operations triple-count sequence first-operation operation-count-value))))) (recur (inc transaction-position) (+ operation-position operation-count-value) sequence) false)))))

(defn- ^TransactionFramesResult transaction-frames-ok [frames]
  (->TransactionFramesResult true frames nil nil))

(defn- ^TransactionFramesResult transaction-frames-error [code ^String message]
  (->TransactionFramesResult false empty-transaction-frames code message))

(defn- operation-handle-error [store]
  (let [atoms (t/termstore-atoms store)
   triples (t/termstore-triples store)
   operations (t/termstore-operations store)]
  (loop [position 0]
  (if (>= position (count operations)) nil (let [handle (t/operationrow-triple-handle (nth operations position))
   handle-position-value (handle-position handle)]
  (cond
  (< handle 0) (transaction-frames-error :invalid-term-handle "fram: term handle does not resolve")
  (atom-handle? handle) (if (< handle-position-value (count atoms)) (transaction-frames-error :invalid-operation-handle "fram: operation handle does not resolve to Triple") (transaction-frames-error :invalid-term-handle "fram: term handle does not resolve"))
  (>= handle-position-value (count triples)) (transaction-frames-error :invalid-term-handle "fram: term handle does not resolve")
  :else (recur (inc position))))))))

(defn- ^Boolean valid-atom-rows? [rows]
  (loop [position 0]
  (if (>= position (count rows)) true (if (valid-atom-row? (nth rows position)) (recur (inc position)) false))))

(defn- history-sequence-error [transactions]
  (loop [position 0
   previous-sequence 0]
  (if (>= position (count transactions)) nil (let [sequence (t/transactionrow-sequence (nth transactions position))]
  (cond
  (< sequence 0) (transaction-frames-error :invalid-transaction-frame "fram: invalid transaction frame")
  (<= sequence previous-sequence) (transaction-frames-error :nonmonotonic-transaction-sequence "fram: transaction sequence must advance within its space")
  :else (recur (inc position) sequence))))))

(defn- resolve-valid-handle [store handle]
  (let [position (handle-position handle)]
  (if (atom-handle? handle) (atom-row-value (nth (t/termstore-atoms store) position)) (let [row (nth (t/termstore-triples store) position)]
  (t/->Triple (resolve-valid-handle store (t/triplerow-t1 row)) (resolve-valid-handle store (t/triplerow-t2 row)) (resolve-valid-handle store (t/triplerow-t3 row)))))))

(defn- resolve-valid-triple-handle [store handle]
  (let [row (nth (t/termstore-triples store) (handle-position handle))]
  (t/->Triple (resolve-valid-handle store (t/triplerow-t1 row)) (resolve-valid-handle store (t/triplerow-t2 row)) (resolve-valid-handle store (t/triplerow-t3 row)))))

(defn- valid-transaction-frame-at [store transaction-position]
  (let [row (nth (t/termstore-transactions store) transaction-position)
   start (t/transactionrow-first-operation row)
   stop (+ start (t/transactionrow-operation-count row))
   operations (loop [position start
   current empty-commit-operations]
  (if (>= position stop) current (let [operation (nth (t/termstore-operations store) position)]
  (recur (inc position) (conj current (t/->CommitOperation (t/operationrow-action operation) (resolve-valid-triple-handle store (t/operationrow-triple-handle operation))))))))]
  (t/->TransactionFrame (t/transactionrow-sequence row) operations)))

(defn- valid-transaction-frames-between [store lower-exclusive upper-inclusive]
  (let [transactions (t/termstore-transactions store)
   first (first-transaction-after transactions lower-exclusive)
   end (first-transaction-after transactions upper-inclusive)]
  (loop [position first
   frames empty-transaction-frames]
  (if (>= position end) frames (recur (inc position) (conj frames (valid-transaction-frame-at store position)))))))

(defn ^TransactionFramesResult transaction-frames-between-result [store lower-exclusive upper-inclusive]
  (let [atoms (t/termstore-atoms store)
   triples (t/termstore-triples store)
   transactions (t/termstore-transactions store)
   operations (t/termstore-operations store)]
  (cond
  (> lower-exclusive upper-inclusive) (transaction-frames-error :invalid-transaction-frame "fram: transaction frame range is invalid")
  (not (valid-space-id? (t/termstore-space-id store))) (transaction-frames-error :invalid-space-id "fram: TermStore requires a non-empty SpaceId")
  (< (t/termstore-next-sequence store) 1) (transaction-frames-error :invalid-transaction-frame "fram: invalid transaction frame")
  :else (let [handle-error (operation-handle-error store)]
  (if (some? handle-error) handle-error (if (not (valid-atom-rows? atoms)) (transaction-frames-error :invalid-term "fram: triple contains a value outside Term") (if (not (valid-triple-rows? (count atoms) triples)) (transaction-frames-error :invalid-term-handle "fram: term handle does not resolve") (let [sequence-error (history-sequence-error transactions)]
  (if (some? sequence-error) sequence-error (if (not (valid-history-rows? transactions operations (count triples) (t/termstore-next-sequence store))) (transaction-frames-error :invalid-transaction-frame "fram: invalid transaction frame") (transaction-frames-ok (valid-transaction-frames-between store lower-exclusive upper-inclusive))))))))))))

(defn- rebuild-operation-state! [store]
  (let [base (open-fold-state (assoc store :withdrawal-targets empty-ids :active-buckets empty-active-buckets :active-cells empty-active-cells :fold-open false :active-slots (slots/fresh-slots initial-slots)))]
  (close-fold-state (loop [current base
   position 0]
  (if (>= position (count (t/termstore-operations current))) current (recur (apply-operation-state! current position (nth (t/termstore-operations current) position)) (inc position)))))))

(defn- ^TermStoreLoadResult term-store-load-ok []
  (->TermStoreLoadResult true nil nil))

(defn- ^TermStoreLoadResult term-store-load-error [code ^String message]
  (->TermStoreLoadResult false code message))

(defn ^TermStoreLoadResult load-term-store-result! [ctx data]
  (if (not (t/term-store-dump? data)) (term-store-load-error :migration-required "fram: legacy store dump requires one-shot migration") (if (not (= term-store-dump-version (t/termstoredump-version data))) (term-store-load-error :migration-required "fram: legacy TermStore dump requires one-shot migration") (let [atoms (t/termstoredump-atoms data)
   rows (t/termstoredump-triples data)
   transactions (t/termstoredump-transactions data)
   operations (t/termstoredump-operations data)
   dump-space (t/termstoredump-space-id data)
   next-sequence-value (t/termstoredump-next-sequence data)]
  (if (not (and (valid-space-id? dump-space) (and (>= next-sequence-value 1) (and (every? (fn [row] (valid-atom-row? row)) atoms) (and (valid-triple-rows? (count atoms) rows) (valid-history-rows? transactions operations (count rows) next-sequence-value)))))) (term-store-load-error :invalid-term-store-dump "fram: invalid TermStore dump") (if (not (= (space-id ctx) dump-space)) (term-store-load-error :space-mismatch "fram: TermStore dump belongs to a different space") (let [loaded (t/->TermStore dump-space next-sequence-value atoms rows transactions operations empty-ids empty-active-buckets empty-active-cells false (build-atom-term-slots! atoms (term-slots-width-for (count atoms))) (build-triple-term-slots! rows (term-slots-width-for (count rows))) (slots/fresh-slots initial-slots))]
  (reset! ctx (rebuild-operation-state! loaded))
  (term-store-load-ok))))))))

(defn load-term-store! [ctx data]
  (let [result (load-term-store-result! ctx data)]
  (if (termstoreloadresult-ok result) ctx (let [code (termstoreloadresult-code result)
   message (termstoreloadresult-message result)]
  (throw (ex-info (if message message "fram: TermStore load failed") {:type (if code code :invalid-term-store-dump)}))))))
