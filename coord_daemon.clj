;; coord_daemon.clj — narrow TermStore v2 coordinator daemon.
;;
;; Run long-lived servers with `clojure -M`, never Babashka. This surface stays
;; deliberately small until schema/query/pull/world projections consume TermStore
;; directly; it never reconstructs the removed fact-object APIs.
(ns coord-daemon
  (:require [clojure.java.io :as io]
            [coord-daemon-wire :as wire]
            [fram.datalog :as datalog]
            [fram.kernel :as kernel]
            [fram.query :as query]
            [fram.store :as term-store]
            [fram.types :as t])
  (:import [java.net ServerSocket Socket]
           [java.io ByteArrayOutputStream InputStream OutputStream Writer]
           [java.nio ByteBuffer ByteOrder]
           [java.security MessageDigest]
           [java.time Instant]))

(load-file "coord.clj")
(load-file "coord_writer_authority.clj")

(def coordinator (atom nil))
(def coordinator-role (atom nil))
(def writer-authority (atom nil))
(def listener (atom nil))
(def stopping? (atom false))
(def active-requests (atom {}))
(def published-snapshot (atom nil))
(def daemon-generation (atom 0))
(def ^:private query-page-snapshot-limit 4)
(def ^:private query-page-snapshots (atom {:order [] :by-version {}}))
(def ^:private query-result-version-limit 4)
(def ^:private query-result-per-version-limit 8)
(def ^:private query-result-byte-limit (* 64 1024 1024))

(defn- empty-query-result-cache [generation]
  {:generation generation
   :entries {}
   :lru []
   :version-lru []
   :in-flight {}
   :bytes 0
   :hits 0
   :misses 0
   :evictions 0})

(def query-result-cache (atom (empty-query-result-cache 0)))

;; Request observability: the slow threshold is checked before the quiet gate,
;; so a stalled request still leaves a trace under FRAM_DAEMON_QUIET.

(defn- env-string [name]
  (not-empty (System/getenv name)))

(defn- env-long [name fallback]
  (or (when-let [raw (env-string name)]
        (try (Long/parseLong (.trim ^String raw)) (catch Throwable _ nil)))
      fallback))

(def request-log-path (env-string "FRAM_DAEMON_LOG"))
(def request-log-quiet? (= "1" (System/getenv "FRAM_DAEMON_QUIET")))
(def slow-request-ms (env-long "FRAM_SLOW_MS" 1000))

(def request-stats
  (atom {:requests 0 :errors 0 :slow 0 :ops {} :max-ms 0 :last-ms 0 :last nil}))

(def ^:private request-log-writer (atom nil))

(defn- request-log-sink []
  (when request-log-path
    (or @request-log-writer
        (swap! request-log-writer
               #(or % (try (io/writer (io/file request-log-path) :append true)
                           (catch Throwable _ nil)))))))

(defn- emit-log-line! [line]
  ;; System/err rather than *err*: a daemon thread must not inherit whatever
  ;; dynamic binding happened to be live when the connection thread forked.
  (if-let [^Writer writer (request-log-sink)]
    (locking request-log-writer
      (.write writer ^String line)
      (.write writer "\n")
      (.flush writer))
    (.println System/err line)))

(defn- close-request-log! []
  (locking request-log-writer
    (when-let [^Writer writer @request-log-writer]
      (try (.close writer) (catch Throwable _ nil)))
    (reset! request-log-writer nil)))

(defn record-request!
  "Account one served request and log it. `elapsed-ns` covers daemon-side work
   only — decode to response-written — so client send time never reads as
   coordinator latency."
  [operation elapsed-ns outcome code response-bytes]
  (let [ms (quot (long elapsed-ns) 1000000)
        slow? (>= ms slow-request-ms)]
    (swap! request-stats
           (fn [stats]
             (cond-> stats
               true (update :requests inc)
               (= :error outcome) (update :errors inc)
               slow? (update :slow inc)
               true (update-in [:ops (or operation :unknown)] (fnil inc 0))
               true (update :max-ms max ms)
               true (assoc :last-ms ms
                           :last {:op operation :ms ms
                                  :outcome outcome :code code}))))
    (when (or slow? (not request-log-quiet?))
      (emit-log-line!
       (str "fram-rpc ts=" (Instant/now)
            " op=" (or operation :unknown)
            " ms=" ms
            " outcome=" (name outcome)
            (when code (str " code=" code))
            (when response-bytes (str " bytes=" response-bytes))
            (when slow? " slow=1"))))
    ms))

(defn- response-outcome
  "Errors reach the client as a response field, not a thrown exception, so the
   only place an error code is observable is the encoded response frame."
  [frame]
  (if-let [error (some-> frame t/rpcframev1-response t/rpcresponse-error)]
    [:error (t/rpcerror-code error)]
    [:ok nil]))

(def native-rpc-operations
  #{:rpc/version :rpc/status :rpc/assert :rpc/retract :rpc/batch :rpc/scan
    :rpc/query :rpc/occurrences :rpc/lease-acquire :rpc/lease-renew
    :rpc/lease-release :rpc/lease-check :rpc/validate})

(def paged-rpc-operations #{:rpc/query :rpc/scan :rpc/occurrences})

(def read-only-rpc-operations
  (apply disj native-rpc-operations
         [:rpc/assert :rpc/retract :rpc/batch :rpc/lease-acquire
          :rpc/lease-renew :rpc/lease-release]))

(defn- daemon-fail! [code message data]
  (throw (ex-info message (assoc data :type code :fram/code code :code code))))

(defn- canonical-path [path]
  (.getPath (.getCanonicalFile (io/file (str path)))))

(defn writer-authority-status []
  (when @coordinator
    (let [physical (coord-writer-authority/status
                    @coordinator-role @writer-authority (:log @coordinator))
          lock-held (:write-authorized physical)
          recovery (coord/coordinator-recovery-state @coordinator)]
      (assoc physical
             :lock-held lock-held
             :write-authorized (and lock-held
                                    (coord/mutation-ready? @coordinator))
             :coordinator-recovery recovery))))

(defn- writer-lock-held? []
  (boolean (and (= :active @coordinator-role)
                (coord-writer-authority/held? @writer-authority))))

(defn write-authorized? []
  (boolean (and (writer-lock-held?)
                (coord/mutation-ready? @coordinator))))

(defn- snapshot-of [co]
  (let [root @(coord/coordinator-store co)]
    {:generation @daemon-generation
     :space (coord/coordinator-space co)
     :version (dec (t/termstore-next-sequence root))
     :root root}))

(defn- publish-snapshot! [co]
  (let [snapshot (snapshot-of co)]
    (reset! published-snapshot snapshot)
    snapshot))

(defn- drop-query-caches! []
  (reset! query-page-snapshots {:order [] :by-version {}})
  (reset! query-result-cache
          (empty-query-result-cache @daemon-generation)))

(defn- advance-daemon-generation! []
  (swap! daemon-generation inc)
  (drop-query-caches!))

(defn shutdown! []
  (reset! stopping? true)
  (when-let [^ServerSocket server @listener]
    (try (.close server) (catch Throwable _ nil)))
  (doseq [cancellation (vals @active-requests)]
    (reset! (:cancelled cancellation) true)
    (when-let [control @(:query-control cancellation)]
      (datalog/cancel-query! control :daemon-shutdown)))
  (reset! active-requests {})
  (drop-query-caches!)
  (reset! published-snapshot nil)
  (coord-writer-authority/release! @writer-authority)
  (reset! writer-authority nil)
  (reset! coordinator nil)
  (reset! coordinator-role nil)
  (reset! listener nil)
  (close-request-log!)
  nil)

(defn boot!
  "Install one FRAMLOG generation. Active boot acquires lifetime writer
   authority before creation or torn-tail repair; standby boot stays read-only."
  ([path expected-space]
   (boot! path expected-space (coord-writer-authority/role-from-env)))
  ([path expected-space role]
   (shutdown!)
   (reset! stopping? false)
   (let [canonical (canonical-path path)
         file (io/file canonical)
         role (coord-writer-authority/role-from (name role))
         authority (when (= :active role)
                     (coord-writer-authority/acquire! canonical))]
     (try
       (when-not (.exists file)
         (when-not expected-space
           (daemon-fail! :space-id-required
                         "new FRAMLOG generation requires an explicit SpaceId"
                         {:path canonical}))
         (coord/create-triple-log! canonical expected-space))
       (let [opened (coord/open-coordinator!
                     canonical expected-space {:repair-torn? (= :active role)})]
         (advance-daemon-generation!)
         (reset! coordinator opened)
         (publish-snapshot! opened)
         (reset! coordinator-role role)
         (reset! writer-authority authority)
         opened)
       (catch Throwable error
         (coord-writer-authority/release! authority)
         (throw error))))))

(defn- refresh-standby! []
  (when (= :standby @coordinator-role)
    (locking coordinator
      (let [current @coordinator
            opened (coord/open-coordinator! (:log current) (:space-id current))]
        (advance-daemon-generation!)
        (reset! coordinator opened)
        (publish-snapshot! opened)))))

(defn native-op-disposition [operation]
  (if (contains? native-rpc-operations operation) :supported :unsupported))

(defn- current-version [co]
  (t/triple-slot2 (coord/current-transaction co)))

(defn- require-term! [value label]
  (when-not (t/term? value)
    (daemon-fail! :rpc-invalid-payload (str label " must be a Term") {}))
  value)

(defn- require-triple! [value label]
  (when-not (t/triple? value)
    (daemon-fail! :rpc-invalid-payload (str label " must be a Triple") {}))
  value)

(defn- require-keyword! [value label]
  (when-not (keyword? value)
    (daemon-fail! :rpc-invalid-payload (str label " must be a Keyword") {}))
  value)

(defn- require-string! [value label]
  (when-not (string? value)
    (daemon-fail! :rpc-invalid-payload (str label " must be a String") {}))
  value)

(defn- require-int! [value label]
  (when-not (integer? value)
    (daemon-fail! :rpc-invalid-payload (str label " must be an Int") {}))
  value)

(defn- require-bool! [value label]
  (when-not (boolean? value)
    (daemon-fail! :rpc-invalid-payload (str label " must be a Bool") {}))
  value)

(defn- record-fields! [value tag field-count]
  (wire/rpc-record-fields! value tag field-count))

(defn- list-values! [value]
  (wire/rpc-list-values! value))

(defn- option-value! [value]
  [(wire/rpc-option-present?! value) (wire/rpc-option-value! value)])

(defn- require-unit! [payload]
  (when-not (= wire/rpc-unit payload)
    (daemon-fail! :rpc-invalid-payload "operation payload must be :rpc/unit" {})))

(defn- cancelled! [cancellation]
  (when @(:cancelled cancellation)
    (daemon-fail! :rpc/cancelled "request was cancelled before completion" {})))

(defn- require-writer! []
  (when-not (writer-lock-held?)
    (daemon-fail! :rpc/writer-authority-required
                  "active writer authority is required" {}))
  (coord/require-mutation-ready! @coordinator))

(defn- require-version-expected! [version expected]
  (when (and (some? expected) (not= expected version))
    (daemon-fail! :rpc/conflict "expected-version does not match current version" {})))

(defn- require-expected! [co expected]
  (require-version-expected! (current-version co) expected))

(defn- occurrence-epoch [coordinate]
  (t/triple-slot2 (t/triple-slot0 coordinate)))

(defn- millis->instant [value]
  (let [seconds (quot value 1000)
        millis (mod value 1000)]
    (t/instant seconds (* millis 1000000))))

(defn- parse-fence! [value]
  (let [[resource holder epoch] (record-fields! value :rpc/fence 3)]
    [(require-term! resource "fence resource")
     (require-term! holder "fence holder")
     (require-int! epoch "fence epoch")]))

(defn- current-fence [co resource]
  (when-let [lease (coord/current-lease co resource)]
    [(:holder lease) (occurrence-epoch (:occurrence lease))
     (:occurrence lease) (:expires-ms lease)]))

(defn- valid-fence? [co resource holder epoch now-ms]
  (when-let [[current-holder current-epoch _ expires-ms]
             (current-fence co resource)]
    (and (= holder current-holder) (= epoch current-epoch)
         (> expires-ms now-ms))))

(defn- require-fence! [co fence]
  (when fence
    (let [[resource holder epoch] (parse-fence! fence)]
      (when-not (valid-fence? co resource holder epoch
                              (System/currentTimeMillis))
        (daemon-fail! :rpc/lease-fence-mismatch
                      "lease fence is not current and unexpired" {})))))

(defn- parse-policy! [value]
  (require-keyword! value "subject policy")
  (when-not (or (= value wire/rpc-subject-any)
                (= value wire/rpc-subject-existing))
    (daemon-fail! :rpc-invalid-policy "subject policy is unsupported" {}))
  value)

(defn- parse-action! [value]
  (let [[operation proposition policy] (record-fields! value :rpc/action 3)]
    (require-keyword! operation "action operation")
    (when-not (or (= operation :rpc/assert) (= operation :rpc/retract))
      (daemon-fail! :rpc-invalid-action "action operation is unsupported" {}))
    [operation (require-triple! proposition "action proposition")
     (parse-policy! policy)]))

(defn- remove-last-equal [values target]
  (let [position (last (keep-indexed (fn [index value]
                                       (when (= value target) index))
                                     values))]
    (if (nil? position)
      [values false]
      [(into (subvec values 0 position) (subvec values (inc position))) true])))

(defn- subject-known? [propositions proposition]
  (let [slot0 (t/triple-slot0 proposition)]
    (boolean (some #(= slot0 (t/triple-slot0 %)) propositions))))

(defn- prepare-actions! [propositions actions]
  (loop [remaining actions index 0 simulated propositions
         operations [] decisions []]
    (if (empty? remaining)
      [operations decisions]
      (let [[operation proposition policy] (first remaining)]
        (when (and (= policy wire/rpc-subject-existing)
                   (not (subject-known? simulated proposition)))
          (daemon-fail! :rpc/subject-not-found
                        "subject-existing policy requires a live slot0" {}))
        (if (= operation :rpc/assert)
          (recur (rest remaining) (inc index) (conj simulated proposition)
                 (conj operations {:action :assert :proposition proposition})
                 (conj decisions [index true]))
          (let [[next-propositions changed] (remove-last-equal simulated proposition)]
            (recur (rest remaining) (inc index) next-propositions
                   (if changed
                     (conj operations {:action :retract :proposition proposition})
                     operations)
                   (conj decisions [index changed]))))))))

(defn- mutation-payload! [request actions fence cancellation]
  (let [co @coordinator
        expected (t/rpcrequest-expected-version request)]
    (locking (:lock co)
      (require-writer!)
      (require-expected! co expected)
      (require-fence! co fence)
      (cancelled! cancellation)
      (let [[operations decisions]
            (prepare-actions! (coord/live-propositions co) actions)
            base (when (some? expected)
                   (t/transaction-coordinate (coord/coordinator-space co) expected))
            committed
            (when (seq operations)
              (cancelled! cancellation)
              (coord/commit! co {:base base :operations operations}))]
        (when (:reject committed)
          (daemon-fail! :rpc/conflict "expected-version lost its commit race" {}))
        (loop [remaining decisions events (vec (:occurrences committed)) results []]
          (if (empty? remaining)
            (wire/rpc-mutation-result! results)
            (let [[input-index changed] (first remaining)
                  occurrence (when changed (first events))]
              (recur (rest remaining) (if changed (subvec events 1) events)
                     (conj results
                           (wire/rpc-action-result!
                            input-index changed (if changed [occurrence] [])))))))))))

(defn- handle-write! [request operation cancellation]
  (let [[proposition policy fence-option]
        (record-fields! (t/rpc-request-payload-value request) :rpc/write 3)
        [fence-present fence] (option-value! fence-option)]
    (mutation-payload!
     request [[operation (require-triple! proposition "write proposition")
               (parse-policy! policy)]]
     (when fence-present (require-triple! fence "write fence")) cancellation)))

(defn- handle-batch! [request cancellation]
  (let [[action-list fence-option]
        (record-fields! (t/rpc-request-payload-value request) :rpc/batch 2)
        actions (mapv parse-action! (list-values! action-list))
        [fence-present fence] (option-value! fence-option)]
    (when (empty? actions)
      (daemon-fail! :rpc-invalid-action "batch requires at least one action" {}))
    (mutation-payload! request actions
                       (when fence-present (require-triple! fence "batch fence"))
                       cancellation)))

(defn- scan-match? [options proposition]
  (every? identity
          (map-indexed
           (fn [index [present value]]
             (or (not present)
                 (= value ((case index
                             0 t/triple-slot0
                             1 t/triple-slot1
                             t/triple-slot2)
                           proposition))))
           options)))

(defn- operation-occurrences [co]
  (filterv kernel/operation-occurrence? (coord/history co)))

(defn- query-record-tag [value]
  (when (and (t/triple? value) (= :rpc/record (t/triple-slot2 value)))
    (t/triple-slot0 value)))

(defn- parse-query-term! [value]
  (case (query-record-tag value)
    :query/var
    (let [[name] (record-fields! value :query/var 1)]
      (datalog/variable (require-string! name "query variable")))

    :query/const
    (let [[constant] (record-fields! value :query/const 1)]
      (datalog/constant (require-term! constant "query constant")))

    (daemon-fail! :query-invalid-term
                  "query term must be query/var or query/const" {})))

(defn- parse-query-terms! [value]
  (mapv parse-query-term! (list-values! value)))

(defn- parse-query-head! [value]
  (let [[relation terms] (record-fields! value :query/head 2)]
    [(require-string! relation "query head relation")
     (parse-query-terms! terms)]))

(defn- parse-query-clause! [value]
  (case (query-record-tag value)
    :query/relation
    (let [[relation terms negated] (record-fields! value :query/relation 3)
          relation (require-string! relation "query relation")
          terms (parse-query-terms! terms)
          negated (require-bool! negated "query negation")]
      (if negated
        (datalog/negated-literal relation terms)
        (datalog/relation-literal relation terms)))

    :query/predicate
    (let [[operation left right]
          (record-fields! value :query/predicate 3)
          operation (require-keyword! operation "query predicate")]
      (when-not (contains? datalog/comparison-operators operation)
        (daemon-fail! :query-invalid-predicate
                      "query predicate operation is unsupported" {}))
      (datalog/comparison-literal
       operation [(parse-query-term! left) (parse-query-term! right)]))

    :query/function
    (let [[operation terms binding]
          (record-fields! value :query/function 3)
          operation (require-keyword! operation "query function")]
      (when-not (contains? datalog/builtin-operators operation)
        (daemon-fail! :query-invalid-function
                      "query function operation is unsupported" {}))
      (datalog/builtin-literal operation (parse-query-terms! terms)
                               (require-string! binding "query function binding")))

    (daemon-fail! :query-invalid-clause "query clause record is unsupported" {})))

(defn- parse-query-rule! [value]
  (let [[head clauses] (record-fields! value :query/rule 2)
        [relation terms] (parse-query-head! head)]
    (datalog/rule relation terms (mapv parse-query-clause! (list-values! clauses)))))

(defn- parse-query-stratum! [value]
  (let [[rules] (record-fields! value :query/stratum 1)]
    (mapv parse-query-rule! (list-values! rules))))

(defn- parse-query-aggregate! [value]
  (let [[operation argument-option]
        (record-fields! value :query/aggregate 2)
        operation (require-keyword! operation "query aggregate")
        [argument-present argument] (option-value! argument-option)]
    (when-not (contains? query/aggregate-operators operation)
      (daemon-fail! :query-invalid-aggregate
                    "query aggregate operation is unsupported" {}))
    (query/aggregate-spec operation
                          (when argument-present
                            (require-int! argument "aggregate argument index")))))

(defn- parse-query-having! [value]
  (let [[comparison aggregate-index comparison-value]
        (record-fields! value :query/having 3)
        comparison (require-keyword! comparison "having comparison")]
    (when-not (contains? datalog/comparison-operators comparison)
      (daemon-fail! :query-invalid-having
                    "having comparison operation is unsupported" {}))
    (query/having-clause comparison
                         (require-int! aggregate-index "having aggregate index")
                         (require-term! comparison-value "having value"))))

(defn- parse-query-find! [value]
  (case (query-record-tag value)
    :query/find-relation
    (let [[relation] (record-fields! value :query/find-relation 1)]
      (query/relation-find (require-string! relation "find relation")))

    :query/find-aggregate
    (let [[relation grouping aggregates having]
          (record-fields! value :query/find-aggregate 4)]
      (query/aggregate-find
       (require-string! relation "aggregate find relation")
       (mapv #(require-int! % "aggregate grouping index")
             (list-values! grouping))
       (mapv parse-query-aggregate! (list-values! aggregates))
       (mapv parse-query-having! (list-values! having))))

    (daemon-fail! :query-invalid-find "query find record is unsupported" {})))

(defn- parse-query-plan! [value]
  (let [[find strata] (record-fields! value :query/plan 2)
        plan (query/query-plan
              (parse-query-find! find)
              (mapv parse-query-stratum! (list-values! strata)))
        errors (query/validate-plan plan)]
    (when-let [error (first errors)]
      (daemon-fail! (query/error-code error) (query/error-message error) {}))
    plan))

(defn- occurrence-sequence [event]
  (-> event kernel/occurrence-of t/triple-slot0 t/triple-slot2))

(defn- event-operation [event]
  (if (kernel/assertion-occurrence? event)
    (term-store/assert-operation (kernel/proposition-of event))
    (term-store/retract-operation (kernel/proposition-of event))))

(defn- replayed-store-root! [co version]
  (let [head (current-version co)]
    (when (or (neg? version) (> version head))
      (daemon-fail! :query-invalid-snapshot
                    "query snapshot is outside available history" {}))
    (if (= version head)
      @(coord/coordinator-store co)
      (let [context (term-store/new-term-store (coord/coordinator-space co))
            grouped (group-by occurrence-sequence
                              (filterv #(<= (occurrence-sequence %) version)
                                       (operation-occurrences co)))]
        (doseq [sequence (sort (keys grouped))]
          (term-store/replay-transaction!
           context
           (term-store/transaction-frame
            sequence (mapv event-operation (get grouped sequence)))))
        @context))))

(defn- snapshot-image [version root]
  (let [context (atom root)]
    {:version version
     :store-root root
     :propositions (term-store/live-propositions context)
     :occurrences (term-store/operation-occurrences context)}))

(defn- one-triple-pattern [plan]
  (let [find (query/queryplan-find plan)
        strata (query/queryplan-strata plan)
        rules (when (= 1 (count strata)) (first strata))
        rule (when (= 1 (count rules)) (first rules))
        body (when rule (datalog/rule-body rule))
        literal (when (= 1 (count body)) (first body))
        head-arguments (when rule (datalog/rule-head-arguments rule))
        arguments (when literal (datalog/literal-arguments literal))]
    (when (and (some? rule)
               (some? literal)
               (not (query/aggregate-find? find))
               (= (query/findspec-relation find)
                  (datalog/rule-head-relation rule))
               (= :relation (datalog/literal-kind literal))
               (= datalog/triple-relation (datalog/literal-relation literal))
               (not (datalog/literal-negated literal))
               (= 3 (count arguments)))
      {:arguments arguments :head-arguments head-arguments})))

(defn- cached-query-page-root [version]
  (get-in @query-page-snapshots [:by-version version]))

(defn- retain-query-page-root! [version root]
  (locking query-page-snapshots
    (let [{:keys [order by-version]} @query-page-snapshots
          order (conj (vec (remove #{version} order)) version)
          evict-count (max 0 (- (count order) query-page-snapshot-limit))
          evicted (take evict-count order)
          retained (vec (drop evict-count order))]
      (reset! query-page-snapshots
              {:order retained
               :by-version (reduce dissoc
                                   (assoc by-version version root)
                                   evicted)})
      root)))

(defn- native-term-slot [value width]
  (mod (hash value) width))

(defn- native-index-position [rows slots value]
  (let [positions (t/termbucket-positions
                   (nth slots (native-term-slot value (count slots))))]
    (some (fn [position]
            (when (= value (nth rows position)) position))
          positions)))

(defn- native-atom-row [value]
  (cond
    (string? value) (t/->AtomRow :string value nil nil nil nil nil)
    (integer? value) (t/->AtomRow :int nil value nil nil nil nil)
    (number? value) (t/->AtomRow :float nil nil value nil nil nil)
    (boolean? value) (t/->AtomRow :bool nil nil nil value nil nil)
    (keyword? value) (t/->AtomRow :keyword nil nil nil nil value nil)
    (t/instant? value) (t/->AtomRow :instant nil nil nil nil nil value)))

(declare native-term-handle)

(defn- native-term-handle [root value]
  (if (t/triple? value)
    (let [slot0 (native-term-handle root (t/triple-slot0 value))
          slot1 (native-term-handle root (t/triple-slot1 value))
          slot2 (native-term-handle root (t/triple-slot2 value))]
      (when (every? some? [slot0 slot1 slot2])
        (when-let [position
                   (native-index-position
                    (t/termstore-triples root)
                    (t/termstore-triple-slots root)
                    (t/->TripleRow slot0 slot1 slot2))]
          (inc (* 2 position)))))
    (let [row (native-atom-row value)]
      (when row
        (when-let [position
                   (native-index-position
                    (t/termstore-atoms root)
                    (t/termstore-atom-slots root) row)]
          (* 2 position))))))

(defn- native-atom-value [row]
  (case (t/atomrow-kind row)
    :string (t/atomrow-string-value row)
    :int (t/atomrow-int-value row)
    :float (t/atomrow-float-value row)
    :bool (t/atomrow-bool-value row)
    :keyword (t/atomrow-keyword-value row)
    :instant (t/atomrow-instant-value row)))

(defn- native-resolve-handle [root handle]
  (let [position (quot handle 2)]
    (if (zero? (mod handle 2))
      (native-atom-value (nth (t/termstore-atoms root) position))
      (let [row (nth (t/termstore-triples root) position)]
        (t/triple
         (native-resolve-handle root (t/triplerow-slot0 row))
         (native-resolve-handle root (t/triplerow-slot1 row))
         (native-resolve-handle root (t/triplerow-slot2 row)))))))

(defn- native-active-handle? [root handle]
  (let [slots (t/termstore-active-slots root)
        buckets (t/termstore-active-buckets root)
        positions (t/termbucket-positions
                   (nth slots (native-term-slot handle (count slots))))]
    (boolean
     (some (fn [position]
             (let [bucket (nth buckets position)]
               (and (= handle (t/activebucket-triple-handle bucket))
                    (seq (t/activebucket-positions bucket)))))
           positions))))

(def ^:private native-unbound ::native-unbound)
(def ^:private native-missing ::native-missing)

(defn- native-pattern-handles [root arguments]
  (mapv (fn [argument]
          (if (some? (datalog/queryterm-variable argument))
            native-unbound
            (or (native-term-handle root (datalog/queryterm-value argument))
                native-missing)))
        arguments))

(defn- native-row-matches-handles? [expected row]
  (every? identity
          (map (fn [wanted actual]
                 (or (= native-unbound wanted) (= wanted actual)))
               expected
               [(t/triplerow-slot0 row)
                (t/triplerow-slot1 row)
                (t/triplerow-slot2 row)])))

(defn- native-candidate-handles [root arguments cancellation]
  (let [expected (native-pattern-handles root arguments)]
    (cond
      (some #{native-missing} expected) []
      (not-any? #{native-unbound} expected)
      (let [row (apply t/->TripleRow expected)
            position (native-index-position
                      (t/termstore-triples root)
                      (t/termstore-triple-slots root) row)
            handle (when (some? position) (inc (* 2 position)))]
        (if (and handle (native-active-handle? root handle)) [handle] []))
      :else
      (persistent!
       (reduce (fn [handles bucket]
                 (cancelled! cancellation)
                 (let [handle (t/activebucket-triple-handle bucket)]
                   (if (and (seq (t/activebucket-positions bucket))
                            (native-row-matches-handles?
                             expected
                             (nth (t/termstore-triples root) (quot handle 2))))
                     (conj! handles handle)
                     handles)))
               (transient [])
               (t/termstore-active-buckets root))))))

(defn- match-query-row [arguments row]
  (loop [position 0 bindings {}]
    (if (>= position (count arguments))
      bindings
      (let [term (nth arguments position)
            value (nth row position)
            variable (datalog/queryterm-variable term)]
        (if (some? variable)
          (if (contains? bindings variable)
            (when (= (get bindings variable) value)
              (recur (inc position) bindings))
            (recur (inc position) (assoc bindings variable value)))
          (when (= (datalog/queryterm-value term) value)
            (recur (inc position) bindings)))))))

(defn- ground-query-row [arguments bindings]
  (mapv (fn [term]
          (if-let [variable (datalog/queryterm-variable term)]
            (get bindings variable)
            (datalog/queryterm-value term)))
        arguments))

(defn- one-triple-query-rows [root pattern cancellation]
  (let [rows
        (persistent!
         (reduce (fn [acc handle]
                   (cancelled! cancellation)
                   (let [proposition (native-resolve-handle root handle)
                         row [(t/triple-slot0 proposition)
                              (t/triple-slot1 proposition)
                              (t/triple-slot2 proposition)]]
                     (if-let [bindings (match-query-row (:arguments pattern) row)]
                       (conj! acc (ground-query-row
                                   (:head-arguments pattern) bindings))
                       acc)))
                 (transient #{})
                 (native-candidate-handles
                  root (:arguments pattern) cancellation)))]
    (cancelled! cancellation)
    (vec (sort-by query/row-key rows))))

(defn- term-sha256 [term]
  (let [out (ByteArrayOutputStream.)]
    (wire/write-term-codec-v1! out term wire/rpc-v1-max-string-bytes
                               wire/rpc-v1-max-term-nodes
                               wire/rpc-v1-max-term-depth)
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.toByteArray out))]
      (apply str (map #(format "%02x" (bit-and 255 (int %))) digest)))))

(defn- term-codec-v1-bytes [term]
  (let [out (ByteArrayOutputStream.)]
    (wire/write-term-codec-v1! out term wire/rpc-v1-max-string-bytes
                               wire/rpc-v1-max-term-nodes
                               wire/rpc-v1-max-term-depth)
    (.size out)))

(defn- result-weight [rows shape]
  (+ 32 (* 8 (count rows))
     (reduce (fn [total row]
               (+ total (term-codec-v1-bytes ((:cache-term shape) row))))
             0 rows)))

(defn- result-snapshot-key [snapshot]
  [(:generation snapshot) (:space snapshot) (:version snapshot)])

(defn- touch-result-entry [state key]
  (let [snapshot-key (first key)]
    (-> state
        (update :lru #(conj (vec (remove #{key} %)) key))
        (update :version-lru
                #(conj (vec (remove #{snapshot-key} %)) snapshot-key)))))

(defn- remove-result-entries [state candidate-keys]
  (let [evicted-keys (set (filter #(contains? (:entries state) %)
                                  candidate-keys))
        removed-bytes (reduce + 0
                              (map #(get-in state [:entries % :bytes])
                                   evicted-keys))
        entries (reduce dissoc (:entries state) evicted-keys)
        retained-versions (set (map first (keys entries)))]
    (-> state
        (assoc :entries entries)
        (update :lru #(vec (remove evicted-keys %)))
        (update :version-lru
                #(vec (filter retained-versions %)))
        (update :bytes - removed-bytes)
        (update :evictions + (count evicted-keys)))))

(defn- enforce-result-cache-bounds [state snapshot-key]
  (let [version-keys (filterv #(= snapshot-key (first %)) (:lru state))
        state (remove-result-entries
               state
               (take (max 0 (- (count version-keys)
                               query-result-per-version-limit))
                     version-keys))
        evicted-versions
        (set (take (max 0 (- (count (:version-lru state))
                            query-result-version-limit))
                   (:version-lru state)))
        state (remove-result-entries
               state
               (filterv #(contains? evicted-versions (first %))
                        (:lru state)))]
    (loop [bounded state]
      (if (and (> (:bytes bounded) query-result-byte-limit)
               (seq (:lru bounded)))
        (recur (remove-result-entries bounded [(first (:lru bounded))]))
        bounded))))

(defn- retain-result-entry [state key rows bytes]
  (let [snapshot-key (first key)
        state (-> state
                  (assoc-in [:entries key] {:rows rows :bytes bytes})
                  (update :bytes + bytes)
                  (touch-result-entry key))]
    (enforce-result-cache-bounds state snapshot-key)))

(defn- begin-result-access! [key]
  (locking query-result-cache
    (let [state @query-result-cache]
      (if-let [entry (get-in state [:entries key])]
        (do
          (reset! query-result-cache
                  (-> state (update :hits inc) (touch-result-entry key)))
          {:kind :hit :rows (:rows entry)})
        (if-let [flight (get-in state [:in-flight key])]
          (do
            (reset! query-result-cache (update state :hits inc))
            {:kind :wait :flight flight})
          (let [flight (promise)]
            (reset! query-result-cache
                    (-> state
                        (assoc-in [:in-flight key] flight)
                        (update :misses inc)))
            {:kind :build :flight flight}))))))

(defn- complete-result-flight! [key flight rows bytes]
  (locking query-result-cache
    (let [state @query-result-cache
          generation (ffirst key)
          state (update state :in-flight dissoc key)]
      (reset! query-result-cache
              (if (= generation (:generation state))
                (retain-result-entry state key rows bytes)
                state))))
  (deliver flight {:rows rows}))

(defn- fail-result-flight! [key flight error]
  (locking query-result-cache
    (swap! query-result-cache update :in-flight dissoc key))
  (deliver flight {:error error}))

(def ^:private result-wait-pending (Object.))

(defn- wait-result-flight! [flight cancellation deadline-ns]
  (loop []
    (cancelled! cancellation)
    (let [remaining-ms (when deadline-ns
                         (quot (- deadline-ns (System/nanoTime)) 1000000))]
      (when (and remaining-ms (<= remaining-ms 0))
        (daemon-fail! :query-time-limit "query exceeded its time limit" {}))
      (let [wait-ms (long (if remaining-ms (min 25 remaining-ms) 25))
            outcome (deref flight wait-ms result-wait-pending)]
        (if (identical? result-wait-pending outcome)
          (recur)
          (if-let [error (:error outcome)]
            (throw error)
            (:rows outcome)))))))

(defn- cached-result! [snapshot operation digest shape cancellation deadline-ns build]
  (let [key [(result-snapshot-key snapshot) operation digest]
        {:keys [kind rows flight]} (begin-result-access! key)]
    (case kind
      :hit (do (cancelled! cancellation) rows)
      :wait (wait-result-flight! flight cancellation deadline-ns)
      :build
      (try
        (let [rows (vec (build))
              bytes (result-weight rows shape)]
          (complete-result-flight! key flight rows bytes)
          rows)
        (catch Throwable error
          (fail-result-flight! key flight error)
          (throw error))))))

(defn- parse-query-row! [value]
  (let [[values] (record-fields! value :query/row 1)]
    (mapv #(require-term! % "query row value") (list-values! values))))

(defn- parse-query-cursor! [value]
  (let [[snapshot-version query-sha256 next-page-ordinal after-row]
        (record-fields! value :query/cursor 4)
        snapshot-version (require-int! snapshot-version "cursor snapshot version")
        next-page-ordinal (require-int! next-page-ordinal "cursor page ordinal")]
    (when (or (neg? snapshot-version) (neg? next-page-ordinal))
      (daemon-fail! :query-cursor-mismatch
                    "query cursor coordinates must be non-negative" {}))
    {:snapshot-version snapshot-version
     :query-sha256 (require-string! query-sha256 "cursor query digest")
     :next-page-ordinal next-page-ordinal
     :after-row (parse-query-row! after-row)}))

(defn- requested-snapshot-version! [snapshot cursor head]
  (let [cursor-version (:snapshot-version cursor)
        requested
        (if (= snapshot wire/query-current)
          (or cursor-version head)
          (let [[version] (record-fields! snapshot :query/as-of 1)]
            (require-int! version "query snapshot version")))]
    (when (and cursor-version (not= cursor-version requested))
      (daemon-fail! :query-cursor-mismatch
                    "query cursor belongs to a different snapshot" {}))
    requested))

(defn- result-rows! [result]
  (when-let [error (first (query/result-errors result))]
    (daemon-fail! (query/error-code error) (query/error-message error) {}))
  (query/result-rows result))

(defn- query-cursor-position! [rows after-row]
  (let [position (first (keep-indexed
                         (fn [index row]
                           (when (= row after-row) index))
                         rows))]
    (when (nil? position)
      (daemon-fail! :query-cursor-mismatch
                    "query cursor row is absent from its snapshot" {}))
    position))

;; Scan rows are a multiset — equal propositions may be live twice — so the
;; cursor carries the snapshot position alongside the row it must still hold.
(defn- indexed-cursor-position! [rows after-row]
  (let [[position value] after-row]
    (when-not (and (= 2 (count after-row))
                   (integer? position)
                   (< -1 position (count rows))
                   (= value (nth rows position)))
      (daemon-fail! :query-cursor-mismatch
                    "page cursor row is absent from its snapshot" {}))
    position))

;; A page shape adapts one operation's rows to the shared :query/cursor record:
;; :payload encodes a row vector, :cursor-row builds the cursor row for the last
;; served row, and :locate finds that row again in a re-read snapshot.
(def ^:private query-page-shape
  {:payload (fn [rows] (wire/rpc-query-rows! (mapv wire/rpc-query-row! rows)))
   :cursor-row (fn [_ row] row)
   :locate query-cursor-position!
   :cache-term wire/rpc-query-row!})

(def ^:private triples-page-shape
  {:payload wire/rpc-triples!
   :cursor-row (fn [position row] [position row])
   :locate indexed-cursor-position!
   :cache-term identity})

(def ^:private occurrences-page-shape
  (assoc triples-page-shape :payload wire/rpc-occurrences!))

(defn- paged-result! [rows page digest snapshot-version shape]
  (if (nil? page)
    {:payload ((:payload shape) rows)
     :page nil}
    (let [limit (t/rpcpagerequest-limit page)
          cursor-value (t/rpc-page-request-cursor-value page)
          cursor (when cursor-value (parse-query-cursor! cursor-value))]
      (when (or (< limit 1) (> limit query/max-page-limit))
        (daemon-fail! :query-page-limit "query page limit must be from 1 through 4096" {}))
      (when (and cursor (not= digest (:query-sha256 cursor)))
        (daemon-fail! :query-cursor-mismatch
                      "query cursor belongs to a different query" {}))
      (let [ordinal (or (:next-page-ordinal cursor) 0)
            start (if cursor
                    (inc ((:locate shape) rows (:after-row cursor)))
                    0)
            selected (vec (take limit (drop start rows)))
            next-position (+ start (count selected))
            done (>= next-position (count rows))
            next-cursor
            (when-not done
              (wire/rpc-query-cursor!
               snapshot-version digest (inc ordinal)
               (wire/rpc-query-row!
                ((:cursor-row shape) (dec next-position) (peek selected)))))]
        {:payload ((:payload shape) selected)
         :page (wire/rpc-page-response! ordinal next-cursor done)}))))

;; An RPC list nests one Triple per row, so a response carrying max-term-depth
;; rows can never encode: an unpaged read stops there and still fails typed
;; instead of folding the whole corpus first.
(def ^:private unpaged-row-cutoff wire/rpc-v1-max-term-depth)

(defn- collect-rows [source keep? cutoff cancellation]
  (reduce (fn [result value]
            (cancelled! cancellation)
            (if (keep? value)
              (let [result (conj result value)]
                (if (and cutoff (>= (count result) cutoff))
                  (reduced result)
                  result))
              result))
          [] source))

(defn- page-version [snapshot page]
  (or (some-> page t/rpc-page-request-cursor-value parse-query-cursor!
              :snapshot-version)
      (:version snapshot)))

(defn- handle-scan! [request cancellation snapshot]
  (let [payload (t/rpc-request-payload-value request)
        [slot0-option slot1-option slot2-option]
        (record-fields! payload :rpc/triple-pattern 3)
        options (mapv option-value! [slot0-option slot1-option slot2-option])
        page (t/rpcrequest-page request)
        version (page-version snapshot page)
        cache-snapshot (assoc (select-keys snapshot [:generation :space])
                              :version version)
        build #(let [co (coord/store-view @coordinator (:root snapshot))
                     root (or (when page (cached-query-page-root version))
                              (replayed-store-root! co version))
                     _ (when page (retain-query-page-root! version root))
                     view (coord/store-view co root)]
                 (collect-rows (coord/live-propositions view)
                               (fn [row] (scan-match? options row))
                               (when-not page unpaged-row-cutoff)
                               cancellation))
        digest (term-sha256 payload)
        rows (if page
               (cached-result! cache-snapshot :rpc/scan digest
                               triples-page-shape cancellation nil build)
               (build))]
    (assoc (paged-result! rows page digest version
                          triples-page-shape)
           :served version)))

(defn- handle-occurrences! [request cancellation snapshot]
  (require-unit! (t/rpc-request-payload-value request))
  (let [page (t/rpcrequest-page request)
        version (page-version snapshot page)
        cache-snapshot (assoc (select-keys snapshot [:generation :space])
                              :version version)
        build #(let [co (coord/store-view @coordinator (:root snapshot))
                     root (or (when page (cached-query-page-root version))
                              (replayed-store-root! co version))
                     _ (when page (retain-query-page-root! version root))
                     view (coord/store-view co root)]
                 (collect-rows (coord/history view)
                               kernel/operation-occurrence?
                               (when-not page unpaged-row-cutoff)
                               cancellation))
        digest (term-sha256 wire/rpc-unit)
        rows (if page
               (cached-result! cache-snapshot :rpc/occurrences digest
                               occurrences-page-shape cancellation nil build)
               (build))]
    (assoc (paged-result! rows page digest version
                          occurrences-page-shape)
           :served version)))

(defn- handle-query! [request cancellation published]
  (let [[plan-term requested-snapshot]
        (record-fields! (t/rpc-request-payload-value request) :query/request 2)
        plan (parse-query-plan! plan-term)
        query-digest (term-sha256 plan-term)
        page (t/rpcrequest-page request)
        cursor-value (some-> page t/rpc-page-request-cursor-value)
        cursor (when cursor-value (parse-query-cursor! cursor-value))
        direct-pattern (one-triple-pattern plan)
        direct? (some? direct-pattern)
        co (coord/store-view @coordinator (:root published))
        version (requested-snapshot-version!
                 requested-snapshot cursor (:version published))
        cache-snapshot {:generation (:generation published)
                        :space (:space published)
                        :version version}
        timeout (min 60000 (or (t/rpcrequest-timeout-ms request) 5000))
        deadline-ns (+ (System/nanoTime) (* timeout 1000000))
        control (datalog/query-control 10000000 timeout)
        build
        (if direct?
          #(let [root (or (when page (cached-query-page-root version))
                          (replayed-store-root! co version))]
             (when page (retain-query-page-root! version root))
             (one-triple-query-rows root direct-pattern cancellation))
          #(do
             (reset! (:query-control cancellation) control)
             (when @(:cancelled cancellation)
               (datalog/cancel-query! control :request-cancelled))
             (try
               (let [root (or (when page (cached-query-page-root version))
                              (replayed-store-root! co version))
                     _ (when page (retain-query-page-root! version root))
                     snapshot-data (snapshot-image version root)
                     projection (query/project-with-occurrences
                                 (:propositions snapshot-data)
                                 (:occurrences snapshot-data))
                     result (binding [query/*query-control* control]
                              (query/run-plan-projected! projection plan))]
                 (result-rows! result))
               (finally
                 (reset! (:query-control cancellation) nil)))))
        rows (cached-result! cache-snapshot :rpc/query query-digest
                             query-page-shape cancellation deadline-ns build)
        paged (paged-result! rows page query-digest version
                             query-page-shape)]
    (cancelled! cancellation)
    (assoc paged :served version)))

(defn- lease-mutation-guard! [request cancellation]
  (require-writer!)
  (require-expected! @coordinator (t/rpcrequest-expected-version request))
  (cancelled! cancellation))

(defn- handle-lease-acquire! [request cancellation]
  (let [[resource holder ttl-ms]
        (record-fields! (t/rpc-request-payload-value request) :lease/acquire 3)
        resource (require-term! resource "lease resource")
        holder (require-term! holder "lease holder")
        ttl-ms (require-int! ttl-ms "lease ttl-ms")]
    (when-not (pos? ttl-ms)
      (daemon-fail! :rpc-invalid-payload "lease ttl-ms must be positive" {}))
    (locking (:lock @coordinator)
      (lease-mutation-guard! request cancellation)
      (let [result (coord/acquire-lease! @coordinator resource holder ttl-ms
                                         (System/currentTimeMillis))]
        (when (:reject result)
          (daemon-fail! :rpc/lease-held "lease resource is already held" {}))
        (wire/rpc-lease-grant!
         (wire/rpc-fence! resource holder (occurrence-epoch (:ok result)))
         (millis->instant (:expires-ms result)))))))

(defn- handle-lease-renew! [request cancellation]
  (let [[fence ttl-ms]
        (record-fields! (t/rpc-request-payload-value request) :lease/renew 2)
        [resource holder epoch] (parse-fence! fence)
        ttl-ms (require-int! ttl-ms "lease ttl-ms")]
    (when-not (pos? ttl-ms)
      (daemon-fail! :rpc-invalid-payload "lease ttl-ms must be positive" {}))
    (locking (:lock @coordinator)
      (lease-mutation-guard! request cancellation)
      (let [[current-holder current-epoch occurrence]
            (or (current-fence @coordinator resource) [nil nil nil nil])]
        (when-not (and (= holder current-holder) (= epoch current-epoch))
          (daemon-fail! :rpc/lease-fence-mismatch
                        "lease fence does not name the current lease" {}))
        (let [result (coord/renew-lease!
                      @coordinator resource holder occurrence ttl-ms
                      (System/currentTimeMillis))]
          (when (:reject result)
            (daemon-fail! :rpc/lease-fence-mismatch
                          "lease fence is stale or expired" {}))
          (wire/rpc-lease-grant!
           (wire/rpc-fence! resource holder (occurrence-epoch (:ok result)))
           (millis->instant (:expires-ms result))))))))

(defn- handle-lease-release! [request cancellation]
  (let [[resource holder epoch]
        (parse-fence! (t/rpc-request-payload-value request))]
    (locking (:lock @coordinator)
      (lease-mutation-guard! request cancellation)
      (let [[current-holder current-epoch occurrence]
            (or (current-fence @coordinator resource) [nil nil nil nil])]
        (if-not (and (= holder current-holder) (= epoch current-epoch))
          (wire/rpc-lease-released! false)
          (let [result (coord/release-lease!
                        @coordinator resource holder occurrence)]
            (wire/rpc-lease-released! (boolean (:ok result)))))))))

(defn- handle-lease-check! [payload cancellation snapshot]
  (let [[resource holder epoch] (parse-fence! payload)]
    (cancelled! cancellation)
    (let [view (coord/store-view @coordinator (:root snapshot))
          [current-holder current-epoch _ expires-ms]
          (or (current-fence view resource) [nil nil nil nil])
          matching (and (= holder current-holder) (= epoch current-epoch))
          valid (and matching (> expires-ms (System/currentTimeMillis)))]
      (wire/rpc-lease-check!
       (boolean valid) (when matching (millis->instant expires-ms))))))

(defn- handle-validate! [payload cancellation snapshot]
  (require-unit! payload)
  (cancelled! cancellation)
  (try
    (let [co (coord/store-view @coordinator (:root snapshot))
          dump (term-store/dump-term-store (coord/coordinator-store co))
          space-id (coord/coordinator-space co)
          copy (term-store/new-term-store space-id)
          profile-violations
          (kernel/lint-declared-profile (coord/live-propositions co) space-id)]
      (term-store/load-term-store! copy dump)
      (wire/rpc-validation!
       true
       (mapv #(wire/rpc-violation! :rpc/profile-violation %)
             profile-violations)))
    (catch Throwable error
      (let [code (or (:fram/code (ex-data error))
                     (:type (ex-data error)) :rpc/validation-failed)]
        (wire/rpc-validation!
         false [(wire/rpc-violation!
                 (if (keyword? code) code :rpc/validation-failed)
                 (or (.getMessage error) "validation failed"))])))))

(defn- status-payload [snapshot]
  (let [co (coord/store-view @coordinator (:root snapshot))
        state (:status (coord/coordinator-recovery-state co))
        {:keys [hits misses bytes evictions]} @query-result-cache
        cache (wire/rpc-record! :rpc/result-cache
                                [hits misses bytes evictions])]
    (wire/rpc-status! state (count (coord/live-propositions co))
                      :rpc/jvm cache)))

(defn- dispatch-request! [request cancellation snapshot]
  (let [operation (t/rpcrequest-op request)
        payload (t/rpc-request-payload-value request)]
    (case operation
      :rpc/version (do (require-unit! payload) {:payload wire/rpc-unit})
      :rpc/status (do (require-unit! payload) {:payload (status-payload snapshot)})
      :rpc/assert {:payload (handle-write! request :rpc/assert cancellation)}
      :rpc/retract {:payload (handle-write! request :rpc/retract cancellation)}
      :rpc/batch {:payload (handle-batch! request cancellation)}
      :rpc/scan (handle-scan! request cancellation snapshot)
      :rpc/query (handle-query! request cancellation snapshot)
      :rpc/occurrences (handle-occurrences! request cancellation snapshot)
      :rpc/lease-acquire {:payload (handle-lease-acquire! request cancellation)}
      :rpc/lease-renew {:payload (handle-lease-renew! request cancellation)}
      :rpc/lease-release {:payload (handle-lease-release! request cancellation)}
      :rpc/lease-check {:payload (handle-lease-check! payload cancellation snapshot)}
      :rpc/validate {:payload (handle-validate! payload cancellation snapshot)}
      (daemon-fail! :rpc/unsupported-operation
                    "operation is not part of FRAMRPC v1" {}))))

(def ^:private retryable-error-codes
  #{:rpc/conflict :rpc/cancelled :query-cancelled :query-time-limit
    :query-work-limit :durability-ambiguous})

(defn- response-version []
  (or (:version @published-snapshot) 0))

(defn handle-rpc-request! [request cancellation]
  (let [space (t/rpcrequest-space request)
        operation (t/rpcrequest-op request)
        served-version (volatile! nil)]
    (try
      (when-not @coordinator
        (daemon-fail! :rpc/not-booted "coordinator is not booted" {}))
      (refresh-standby!)
      (when-not (= space (coord/coordinator-space @coordinator))
        (daemon-fail! :rpc/space-mismatch
                      "request SpaceId does not match the served space" {}))
      (when (= :unsupported (native-op-disposition operation))
        (daemon-fail! :rpc/unsupported-operation
                      "operation is not part of FRAMRPC v1" {}))
      (when (and (not (contains? paged-rpc-operations operation))
                 (t/rpcrequest-page request))
        (daemon-fail! :rpc/unexpected-page
                      "paging is supported only by rpc/query, rpc/scan, and rpc/occurrences"
                      {}))
      (when (and (not= operation :rpc/query) (t/rpcrequest-timeout-ms request))
        (daemon-fail! :rpc/unexpected-timeout
                      "timeout-ms is supported only by rpc/query" {}))
      (let [result
            (if (contains? read-only-rpc-operations operation)
              (let [{:keys [version] :as snapshot} @published-snapshot]
                (vreset! served-version version)
                (require-version-expected!
                 version (t/rpcrequest-expected-version request))
                (let [dispatched (dispatch-request! request cancellation snapshot)]
                  (assoc dispatched :served (or (:served dispatched) version))))
              (locking (:lock @coordinator)
                (require-expected! @coordinator
                                   (t/rpcrequest-expected-version request))
                (let [dispatched (dispatch-request! request cancellation nil)
                      snapshot (publish-snapshot! @coordinator)]
                  (vreset! served-version (:version snapshot))
                  (assoc dispatched :served (:version snapshot)))))
            {:keys [payload page served]} result]
        (wire/rpc-response! space operation served
                            page nil payload))
      (catch Throwable error
        (let [data (ex-data error)
              code (or (:fram/code data) (:code data) (:type data)
                       :rpc/internal-error)
              code (if (keyword? code) code :rpc/internal-error)]
          (wire/rpc-response!
           space operation (or @served-version (response-version)) nil
           (wire/rpc-error! code (contains? retryable-error-codes code)
                            (or (.getMessage error) "native RPC request failed") nil)
           nil))))))

(defn- read-exact! [^InputStream input bytes offset length]
  (loop [position offset remaining length]
    (if (zero? remaining)
      true
      (let [read-count (.read input bytes position remaining)]
        (if (neg? read-count)
          false
          (recur (+ position read-count) (- remaining read-count)))))))

(defn- validate-stream-header! [header]
  (dotimes [index 8]
    (when-not (= (bit-and 255 (int (aget header index)))
                 (bit-and 255 (int (aget wire/rpc-v1-magic index))))
      (daemon-fail! :rpc-invalid-magic "FRAMRPC magic does not match" {})))
  (let [buffer (doto (ByteBuffer/wrap header) (.order ByteOrder/LITTLE_ENDIAN))]
    (.position buffer 8)
    (let [major (Short/toUnsignedInt (.getShort buffer))
          minor (Short/toUnsignedInt (.getShort buffer))
          kind (bit-and 255 (int (.get buffer)))
          flags (bit-and 255 (int (.get buffer)))
          body-length (Integer/toUnsignedLong (.getInt buffer))]
      (when-not (and (= major wire/rpc-v1-major)
                     (= minor wire/rpc-v1-minor))
        (daemon-fail! :rpc-unsupported-version
                      "FRAMRPC major/minor version is unsupported" {}))
      (when-not (<= 1 kind 4)
        (daemon-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown" {}))
      (when-not (zero? flags)
        (daemon-fail! :rpc-invalid-flags "FRAMRPC v1 flags must be zero" {}))
      (when (> body-length wire/rpc-v1-max-body-bytes)
        (daemon-fail! :rpc-frame-too-large
                      "FRAMRPC declared body exceeds the byte limit" {}))
      (int body-length))))

(defn read-rpc-frame! [^InputStream input]
  (let [first-byte (.read input)]
    (when-not (neg? first-byte)
      (when-not (= first-byte (bit-and 255 (int (aget wire/rpc-v1-magic 0))))
        (daemon-fail! :rpc-invalid-magic "FRAMRPC magic does not match" {}))
      (let [header (byte-array wire/rpc-v1-header-bytes)]
        (aset-byte header 0 (unchecked-byte first-byte))
        (when-not (read-exact! input header 1 (dec wire/rpc-v1-header-bytes))
          (daemon-fail! :rpc-truncated "FRAMRPC frame ended inside its header" {}))
        (let [body-length (validate-stream-header! header)
              body (byte-array body-length)]
          (when-not (read-exact! input body 0 body-length)
            (daemon-fail! :rpc-truncated "FRAMRPC body is shorter than declared" {}))
          (let [frame (byte-array (+ wire/rpc-v1-header-bytes body-length))]
            (System/arraycopy header 0 frame 0 wire/rpc-v1-header-bytes)
            (System/arraycopy body 0 frame wire/rpc-v1-header-bytes body-length)
            (wire/decode-rpc-frame-v1! frame)))))))

(defn- write-rpc-frame! [^OutputStream output frame]
  (let [bytes
        (try
          (wire/encode-rpc-frame-v1! frame)
          (catch Throwable error
            (let [response (t/rpcframev1-response frame)
                  code (or (:fram/code (ex-data error)) :rpc/internal-error)
                  fallback
                  (wire/rpc-response!
                   (t/rpcresponse-space response) (t/rpcresponse-op response)
                   (t/rpcresponse-served-version response) nil
                   (wire/rpc-error!
                    (if (keyword? code) code :rpc/internal-error) false
                    (or (.getMessage error)
                        "native RPC response is not encodable") nil)
                   nil)]
              (wire/encode-rpc-frame-v1!
               (wire/rpc-response-frame
                (t/rpcframev1-request-id frame) fallback)))))]
    (.write output bytes)
    (.flush output)
    (alength ^bytes bytes)))

(defn- cancellation-state []
  {:cancelled (atom false) :query-control (atom nil)})

(defn- cancel-state! [cancellation reason]
  (reset! (:cancelled cancellation) true)
  (when-let [control @(:query-control cancellation)]
    (datalog/cancel-query! control reason)))

(defn- register-request! [request-id cancellation]
  (locking active-requests
    (when (contains? @active-requests request-id)
      (daemon-fail! :rpc/duplicate-request-id
                    "request id is already active" {}))
    (swap! active-requests assoc request-id cancellation)))

(defn handle-rpc-frame! [frame cancellation]
  (case (t/rpcframev1-kind frame)
    :request
    (wire/rpc-response-frame
     (t/rpcframev1-request-id frame)
     (handle-rpc-request! (t/rpcframev1-request frame) cancellation))
    :cancel
    (do
      (when-let [target (get @active-requests (t/rpcframev1-request-id frame))]
        (cancel-state! target :client-cancelled))
      nil)
    (daemon-fail! :rpc-invalid-kind
                  "listener accepts request and cancel frames only" {})))

(defn serve-connection! [^Socket socket]
  (with-open [socket socket]
    (let [input (.getInputStream socket)
          output (.getOutputStream socket)
          opened (System/nanoTime)]
      (try
        (let [frame (read-rpc-frame! input)
              started (System/nanoTime)]
          (when frame
            (if (= :cancel (t/rpcframev1-kind frame))
              (let [result (handle-rpc-frame! frame (cancellation-state))]
                (record-request! :rpc/cancel (- (System/nanoTime) started)
                                 :ok nil nil)
                result)
              (let [request-id (t/rpcframev1-request-id frame)
                    operation (t/rpcrequest-op (t/rpcframev1-request frame))
                    cancellation (cancellation-state)]
                (register-request! request-id cancellation)
                (future
                  (try
                    (when (neg? (.read input))
                      (cancel-state! cancellation :client-disconnected))
                    (catch Throwable _
                      (cancel-state! cancellation :client-disconnected))))
                (try
                  (let [response (handle-rpc-frame! frame cancellation)
                        response-bytes (write-rpc-frame! output response)
                        [outcome code] (response-outcome response)]
                    (record-request! operation (- (System/nanoTime) started)
                                     outcome code response-bytes))
                  (finally
                    (swap! active-requests dissoc request-id)))))))
        (catch Throwable error
          ;; Frame-level failures never reach handle-rpc-request!, so without
          ;; this arm a malformed or duplicated request is served invisibly.
          (let [data (ex-data error)
                code (or (:fram/code data) (:code data) (:type data)
                         :rpc/internal-error)]
            (record-request! nil (- (System/nanoTime) opened) :error code nil))
          (throw error))))))

(defn serve!
  "Serve FRAMRPC v1 requests. The default bind is loopback; an authenticated
   private gateway may set FRAM_BIND explicitly. The active process holds
   writer authority for the full listener lifetime; a standby refreshes reads."
  [port path expected-space role]
  (boot! path expected-space role)
  (let [bind-host (or (not-empty (System/getenv "FRAM_BIND")) "127.0.0.1")
        server (ServerSocket. (int port) 128
                              (java.net.InetAddress/getByName bind-host))]
    (reset! listener server)
    (println (str "TermStore coordinator listening on " bind-host ":" port
                  " space=" (coord/coordinator-space @coordinator)
                  " role=" (name @coordinator-role)))
    (flush)
    (emit-log-line!
     (str "fram-rpc ts=" (Instant/now) " op=daemon/listen"
          " bind=" bind-host ":" port
          " space=" (coord/coordinator-space @coordinator)
          " role=" (name @coordinator-role)
          " slow-ms=" slow-request-ms
          " quiet=" (if request-log-quiet? 1 0)
          " log=" (or request-log-path "stderr")
          " max-heap-mb=" (quot (.maxMemory (Runtime/getRuntime)) 1048576)))
    (try
      (while (not @stopping?)
        (try
          (let [socket (.accept server)]
            (future
              (try (serve-connection! socket)
                   (catch Throwable _
                     (try (.close socket) (catch Throwable _ nil))))))
          (catch java.net.SocketException error
            (when-not @stopping? (throw error)))))
      (finally (shutdown!)))))

(defn -main [& arguments]
  (let [[command first-arg second-arg third-arg] arguments]
    (case command
      "serve"
      (serve! (Integer/parseInt (or first-arg "7977"))
              (or second-arg
                  (str (System/getProperty "user.dir") "/data/history.framlog"))
              (or third-arg (System/getenv "FRAM_SPACE_ID"))
              (coord-writer-authority/role-from-env))

      "migrate-triple-log"
      (println (pr-str
                (coord/migrate-legacy-flat-log! first-arg second-arg third-arg)))

      "serve-flat"
      (daemon-fail! :migration-required
                    "flat-log runtime boot was removed; run bin/fram-migrate-triple-log"
                    {:source second-arg
                     :migrator "bin/fram-migrate-triple-log"})

      (daemon-fail! :unknown-command
                    "expected serve or migrate-triple-log"
                    {:command command}))))

(when (seq *command-line-args*)
  (apply -main *command-line-args*))
