(ns fram.store
  (:require [fram.types :as t]))

(def empty-ids [])

(def empty-bools [])

(def empty-atoms [])

(def empty-triple-rows [])

(def empty-transaction-rows [])

(def empty-operation-rows [])

(def empty-active-buckets [])

(def empty-term-buckets [])

(def term-store-dump-version 2)

(def initial-slots 64)

(def slot-load 4)

(defn- ^Boolean valid-space-id? [space-id]
  (and (string? space-id) (pos? (count space-id))))

(defn- fresh-term-slots [width]
  (loop [slots empty-term-buckets
   position 0]
  (if (>= position width) slots (recur (conj slots (t/->TermBucket position empty-ids)) (inc position)))))

(defn- term-slot [value width]
  (mod (hash value) width))

(defn- term-slot-add [slots slot position]
  (assoc slots slot (t/->TermBucket slot (conj (t/termbucket-positions (nth slots slot)) position))))

(defn- term-slots-width-for [n]
  (loop [width initial-slots]
  (if (>= (* slot-load width) n) width (recur (* 2 width)))))

(defn- build-atom-term-slots [atoms width]
  (loop [slots (fresh-term-slots width)
   position 0]
  (if (>= position (count atoms)) slots (recur (term-slot-add slots (term-slot (nth atoms position) width) position) (inc position)))))

(defn- build-triple-term-slots [rows width]
  (loop [slots (fresh-term-slots width)
   position 0]
  (if (>= position (count rows)) slots (recur (term-slot-add slots (term-slot (nth rows position) width) position) (inc position)))))

(defn- build-active-slots [buckets width]
  (loop [slots (fresh-term-slots width)
   position 0]
  (if (>= position (count buckets)) slots (recur (term-slot-add slots (term-slot (t/activebucket-triple-handle (nth buckets position)) width) position) (inc position)))))

(defn new-term-store [^String space-id]
  (if (valid-space-id? space-id) (atom (t/->TermStore space-id 1 empty-atoms empty-triple-rows empty-transaction-rows empty-operation-rows empty-bools empty-ids empty-active-buckets (fresh-term-slots initial-slots) (fresh-term-slots initial-slots) (fresh-term-slots initial-slots))) (throw (ex-info "fram: TermStore requires a non-empty SpaceId" {:type :invalid-space-id}))))

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
  (let [positions (t/termbucket-positions (nth slots (term-slot value (count slots))))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (= (nth atoms position) value) position (recur (inc offset))))))))

(defn- find-triple-position [rows slots value]
  (let [positions (t/termbucket-positions (nth slots (term-slot value (count slots))))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (= (nth rows position) value) position (recur (inc offset))))))))

(defn- index-atom-term! [ctx value position]
  (let [store (swap! ctx update :atom-slots (fn [slots] (term-slot-add slots (term-slot value (count slots)) position)))]
  (if (> (count (t/termstore-atoms store)) (* slot-load (count (t/termstore-atom-slots store)))) (swap! ctx assoc :atom-slots (build-atom-term-slots (t/termstore-atoms store) (* 2 (count (t/termstore-atom-slots store))))) store)))

(defn- index-triple-term! [ctx value position]
  (let [store (swap! ctx update :triple-slots (fn [slots] (term-slot-add slots (term-slot value (count slots)) position)))]
  (if (> (count (t/termstore-triples store)) (* slot-load (count (t/termstore-triple-slots store)))) (swap! ctx assoc :triple-slots (build-triple-term-slots (t/termstore-triples store) (* 2 (count (t/termstore-triple-slots store))))) store)))

(defn- atom-handle [position]
  (* 2 position))

(defn- triple-handle [position]
  (inc (* 2 position)))

(defn- ^Boolean atom-handle? [handle]
  (= 0 (mod handle 2)))

(defn- handle-position [handle]
  (quot handle 2))

(defn- intern-handle! [ctx term]
  (if (not (t/term? term)) (throw (ex-info "fram: cannot intern a value outside Term" {:type :invalid-term})) (if (t/triple? term) (let [slot0 (intern-handle! ctx (t/triple-slot0 term))
   slot1 (intern-handle! ctx (t/triple-slot1 term))
   slot2 (intern-handle! ctx (t/triple-slot2 term))
   store (deref ctx)
   rows (t/termstore-triples store)
   value (t/->TripleRow slot0 slot1 slot2)
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
  (t/triple (resolve-handle store (t/triplerow-slot0 row)) (resolve-handle store (t/triplerow-slot1 row)) (resolve-handle store (t/triplerow-slot2 row))))))))

(defn- resolve-triple-handle [store handle]
  (let [term (resolve-handle store handle)]
  (if (t/triple? term) term (throw (ex-info "fram: operation handle does not resolve to Triple" {:type :invalid-operation-handle})))))

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
   positions (t/termbucket-positions (nth slots (term-slot handle (count slots))))]
  (loop [offset 0]
  (if (>= offset (count positions)) -1 (let [position (nth positions offset)]
  (if (= handle (t/activebucket-triple-handle (nth buckets position))) position (recur (inc offset))))))))

(defn- active-positions [store handle]
  (let [position (find-active-bucket-position store handle)]
  (if (>= position 0) (t/activebucket-positions (nth (t/termstore-active-buckets store) position)) empty-ids)))

(defn- set-active-positions [store handle positions]
  (let [known (find-active-bucket-position store handle)]
  (if (>= known 0) (assoc store :active-buckets (assoc (t/termstore-active-buckets store) known (t/->ActiveBucket handle positions))) (let [buckets (conj (t/termstore-active-buckets store) (t/->ActiveBucket handle positions))
   position (dec (count buckets))
   slots (t/termstore-active-slots store)
   store-with-bucket (assoc store :active-buckets buckets :active-slots (term-slot-add slots (term-slot handle (count slots)) position))]
  (if (> (count buckets) (* slot-load (count slots))) (assoc store-with-bucket :active-slots (build-active-slots buckets (* 2 (count slots)))) store-with-bucket)))))

(defn- apply-operation-state [store operation-position row]
  (let [handle (t/operationrow-triple-handle row)
   action (t/operationrow-action row)
   active (active-positions store handle)]
  (if (= action t/assert-action) (set-active-positions (assoc store :operation-live (conj (t/termstore-operation-live store) true) :withdrawal-targets (conj (t/termstore-withdrawal-targets store) -1)) handle (conj active operation-position)) (if (empty? active) (assoc store :operation-live (conj (t/termstore-operation-live store) false) :withdrawal-targets (conj (t/termstore-withdrawal-targets store) -1)) (let [target (peek active)
   live (t/termstore-operation-live store)]
  (set-active-positions (assoc store :operation-live (conj (assoc live target false) false) :withdrawal-targets (conj (t/termstore-withdrawal-targets store) target)) handle (pop active)))))))

(defn- operation-handles! [ctx operations]
  (reduce (fn [handles operation] (conj handles (intern-handle! ctx (t/commitoperation-proposition operation)))) empty-ids operations))

(defn- append-transaction! [ctx sequence operations]
  (let [before (deref ctx)]
  (if (not (valid-operations? operations)) (throw (ex-info "fram: transaction requires at least one valid operation" {:type :invalid-transaction-frame})) (if (< sequence (t/termstore-next-sequence before)) (throw (ex-info "fram: transaction sequence must advance within its space" {:type :nonmonotonic-transaction-sequence})) (let [handles (operation-handles! ctx operations)
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
  (recur (apply-operation-state with-row operation-position row) (inc ordinal)))))
   final-store (assoc appended :next-sequence (inc sequence))]
  (reset! ctx final-store)
  (t/transaction-coordinate (t/termstore-space-id final-store) sequence))))))

(defn commit-transaction! [ctx operations]
  (append-transaction! ctx (t/termstore-next-sequence (deref ctx)) operations))

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
  (recur (inc position) (update postings handle (fn [known] (conj (or known []) position))))))))

(defn- lower-bound-position [positions target]
  (loop [low 0
   high (count positions)]
  (if (>= low high) low (let [middle (quot (+ low high) 2)]
  (if (< (nth positions middle) target) (recur (inc middle) high) (recur low middle))))))

(defn- exact-occurrence-position [store coordinate]
  (if (not (t/occurrence-coordinate? coordinate)) -1 (let [transaction (t/triple-slot0 coordinate)
   space (t/triple-slot0 transaction)
   sequence (t/triple-slot2 transaction)
   ordinal (t/triple-slot2 coordinate)
   transactions (t/termstore-transactions store)
   position (first-transaction-after transactions (dec sequence))]
  (if (or (not (= space (t/termstore-space-id store))) (>= position (count transactions))) -1 (let [row (nth transactions position)]
  (if (and (= sequence (t/transactionrow-sequence row)) (< ordinal (t/transactionrow-operation-count row))) (+ (t/transactionrow-first-operation row) ordinal) -1))))))

(defn operation-candidate-positions [store lower-exclusive upper-inclusive coordinate proposition postings]
  (let [[start end] (operation-range-bounds store lower-exclusive upper-inclusive)
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
  [(t/triple-slot0 event) (t/triple-slot1 event) (t/triple-slot2 event)]))

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

(defn live-occurrences [ctx]
  (let [store (deref ctx)]
  (loop [position 0
   live []]
  (if (>= position (count (t/termstore-operations store))) live (if (nth (t/termstore-operation-live store) position) (recur (inc position) (conj live (event-at store position))) (recur (inc position) live))))))

(defn live-propositions [ctx]
  (let [store (deref ctx)]
  (loop [position 0
   live []]
  (if (>= position (count (t/termstore-operations store))) live (let [row (nth (t/termstore-operations store) position)]
  (if (nth (t/termstore-operation-live store) position) (recur (inc position) (conj live (resolve-triple-handle store (t/operationrow-triple-handle row)))) (recur (inc position) live)))))))

(defn dump-term-store [ctx]
  (let [store (deref ctx)]
  (t/->TermStoreDump term-store-dump-version (t/termstore-space-id store) (t/termstore-next-sequence store) (t/termstore-atoms store) (t/termstore-triples store) (t/termstore-transactions store) (t/termstore-operations store))))

(defn- ^Boolean valid-prior-handle? [atom-count triple-position handle]
  (and (>= handle 0) (if (atom-handle? handle) (< (handle-position handle) atom-count) (< (handle-position handle) triple-position))))

(defn- ^Boolean valid-triple-rows? [atom-count rows]
  (loop [position 0]
  (if (>= position (count rows)) true (let [row (nth rows position)]
  (if (and (valid-prior-handle? atom-count position (t/triplerow-slot0 row)) (and (valid-prior-handle? atom-count position (t/triplerow-slot1 row)) (valid-prior-handle? atom-count position (t/triplerow-slot2 row)))) (recur (inc position)) false)))))

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
  (if (>= transaction-position (count transactions)) (and (= operation-position (count operations)) (= next-sequence-value (if (< previous-sequence 0) 1 (inc previous-sequence)))) (let [row (nth transactions transaction-position)
   sequence (t/transactionrow-sequence row)
   first-operation (t/transactionrow-first-operation row)
   operation-count-value (t/transactionrow-operation-count row)]
  (if (and (> sequence previous-sequence) (and (= first-operation operation-position) (and (pos? operation-count-value) (and (<= (+ first-operation operation-count-value) (count operations)) (valid-operation-slice? operations triple-count sequence first-operation operation-count-value))))) (recur (inc transaction-position) (+ operation-position operation-count-value) sequence) false)))))

(defn- rebuild-operation-state [store]
  (let [base (assoc store :operation-live empty-bools :withdrawal-targets empty-ids :active-buckets empty-active-buckets :active-slots (fresh-term-slots initial-slots))]
  (loop [current base
   position 0]
  (if (>= position (count (t/termstore-operations current))) current (recur (apply-operation-state current position (nth (t/termstore-operations current) position)) (inc position))))))

(defn load-term-store! [ctx data]
  (if (not (t/term-store-dump? data)) (throw (ex-info "fram: legacy store dump requires one-shot migration" {:type :migration-required})) (if (not (= term-store-dump-version (t/termstoredump-version data))) (throw (ex-info "fram: legacy TermStore dump requires one-shot migration" {:type :migration-required})) (let [atoms (t/termstoredump-atoms data)
   rows (t/termstoredump-triples data)
   transactions (t/termstoredump-transactions data)
   operations (t/termstoredump-operations data)
   dump-space (t/termstoredump-space-id data)
   next-sequence-value (t/termstoredump-next-sequence data)]
  (if (not (and (valid-space-id? dump-space) (and (>= next-sequence-value 1) (and (every? (fn [row] (valid-atom-row? row)) atoms) (and (valid-triple-rows? (count atoms) rows) (valid-history-rows? transactions operations (count rows) next-sequence-value)))))) (throw (ex-info "fram: invalid TermStore dump" {:type :invalid-term-store-dump})) (if (not (= (space-id ctx) dump-space)) (throw (ex-info "fram: TermStore dump belongs to a different space" {:type :space-mismatch})) (let [loaded (t/->TermStore dump-space next-sequence-value atoms rows transactions operations empty-bools empty-ids empty-active-buckets (build-atom-term-slots atoms (term-slots-width-for (count atoms))) (build-triple-term-slots rows (term-slots-width-for (count rows))) (fresh-term-slots initial-slots))]
  (reset! ctx (rebuild-operation-state loaded))
  ctx)))))))
