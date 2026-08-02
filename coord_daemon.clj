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
           [java.io ByteArrayOutputStream InputStream OutputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.security MessageDigest]))

(load-file "coord.clj")
(load-file "coord_writer_authority.clj")

(def coordinator (atom nil))
(def coordinator-role (atom nil))
(def writer-authority (atom nil))
(def listener (atom nil))
(def stopping? (atom false))
(def active-requests (atom {}))
(def ^:private query-page-snapshot-limit 4)
(def ^:private query-page-snapshots (atom {:order [] :by-version {}}))

(def native-rpc-operations
  #{:rpc/version :rpc/status :rpc/assert :rpc/retract :rpc/batch :rpc/scan
    :rpc/query :rpc/occurrences :rpc/lease-acquire :rpc/lease-renew
    :rpc/lease-release :rpc/lease-check :rpc/validate})

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

(defn shutdown! []
  (reset! stopping? true)
  (when-let [^ServerSocket server @listener]
    (try (.close server) (catch Throwable _ nil)))
  (doseq [cancellation (vals @active-requests)]
    (reset! (:cancelled cancellation) true)
    (when-let [control @(:query-control cancellation)]
      (datalog/cancel-query! control :daemon-shutdown)))
  (reset! active-requests {})
  (reset! query-page-snapshots {:order [] :by-version {}})
  (coord-writer-authority/release! @writer-authority)
  (reset! writer-authority nil)
  (reset! coordinator nil)
  (reset! coordinator-role nil)
  (reset! listener nil)
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
         (reset! coordinator opened)
         (reset! coordinator-role role)
         (reset! writer-authority authority)
         opened)
       (catch Throwable error
         (coord-writer-authority/release! authority)
         (throw error))))))

(defn- refresh-standby! []
  (when (= :standby @coordinator-role)
    (let [current @coordinator]
      (reset! coordinator
              (coord/open-coordinator! (:log current) (:space-id current))))))

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

(defn- require-expected! [co expected]
  (when (and (some? expected) (not= expected (current-version co)))
    (daemon-fail! :rpc/conflict "expected-version does not match current version" {})))

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

(defn- handle-scan! [request cancellation]
  (let [[slot0-option slot1-option slot2-option]
        (record-fields! (t/rpc-request-payload-value request) :rpc/triple-pattern 3)
        options (mapv option-value! [slot0-option slot1-option slot2-option])
        matches
        (reduce
         (fn [result proposition]
           (cancelled! cancellation)
           (if (every? identity
                       (map-indexed
                        (fn [index [present value]]
                          (or (not present)
                              (= value ((case index
                                          0 t/triple-slot0
                                          1 t/triple-slot1
                                          t/triple-slot2)
                                        proposition))))
                        options))
             (conj result proposition) result))
         [] (coord/live-propositions @coordinator))]
    (wire/rpc-triples! matches)))

(defn- operation-occurrences [co]
  (filterv kernel/operation-occurrence? (coord/history co)))

(defn- handle-occurrences! [payload cancellation]
  (require-unit! payload)
  (let [values
        (reduce (fn [result occurrence]
                  (cancelled! cancellation)
                  (conj result occurrence))
                [] (operation-occurrences @coordinator))]
    (wire/rpc-occurrences! values)))

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

(defn- snapshot-image! [co version]
  (let [head (current-version co)]
    (when (or (neg? version) (> version head))
      (daemon-fail! :query-invalid-snapshot
                    "query snapshot is outside available history" {}))
    (let [context (term-store/new-term-store (coord/coordinator-space co))
          grouped (group-by occurrence-sequence
                            (filterv #(<= (occurrence-sequence %) version)
                                     (operation-occurrences co)))]
      (doseq [sequence (sort (keys grouped))]
        (term-store/replay-transaction!
         context
         (term-store/transaction-frame
          sequence (mapv event-operation (get grouped sequence)))))
      {:version version
       :propositions (term-store/live-propositions context)
       :occurrences (term-store/operation-occurrences context)})))

(defn- all-triples-page-plan? [plan]
  (let [find (query/queryplan-find plan)
        strata (query/queryplan-strata plan)
        rules (when (= 1 (count strata)) (first strata))
        rule (when (= 1 (count rules)) (first rules))
        body (when rule (datalog/rule-body rule))
        literal (when (= 1 (count body)) (first body))
        head-arguments (when rule (datalog/rule-head-arguments rule))
        arguments (when literal (datalog/literal-arguments literal))
        variables (when arguments
                    (mapv datalog/queryterm-variable arguments))]
    (and (some? rule)
         (some? literal)
         (not (query/aggregate-find? find))
         (= (query/findspec-relation find)
            (datalog/rule-head-relation rule))
         (= :relation (datalog/literal-kind literal))
         (= datalog/triple-relation (datalog/literal-relation literal))
         (not (datalog/literal-negated literal))
         (= 3 (count arguments))
         (= head-arguments arguments)
         (every? some? variables)
         (= 3 (count (set variables))))))

(defn- cached-query-page-rows [version]
  (get-in @query-page-snapshots [:by-version version]))

(defn- retain-query-page-rows! [version rows]
  (let [{:keys [order by-version]} @query-page-snapshots
        order (conj (vec (remove #{version} order)) version)
        evict-count (max 0 (- (count order) query-page-snapshot-limit))
        evicted (take evict-count order)
        retained (vec (drop evict-count order))]
    (reset! query-page-snapshots
            {:order retained
             :by-version (reduce dissoc
                                 (assoc by-version version rows)
                                 evicted)})
    rows))

(defn- build-ordered-triple-rows [propositions cancellation]
  (let [rows
        (persistent!
         (reduce (fn [acc proposition]
                   (cancelled! cancellation)
                   (conj! acc [(t/triple-slot0 proposition)
                               (t/triple-slot1 proposition)
                               (t/triple-slot2 proposition)]))
                 (transient #{})
                 propositions))
        keyed (mapv (fn [row] [(query/row-key row) row]) rows)]
    (cancelled! cancellation)
    (mapv second (sort-by first keyed))))

(defn- ordered-query-page-rows! [version propositions cancellation]
  (or (cached-query-page-rows version)
      (locking query-page-snapshots
        (or (cached-query-page-rows version)
            (retain-query-page-rows!
             version (build-ordered-triple-rows propositions cancellation))))))

(defn- snapshot-propositions! [co version]
  (if (= version (current-version co))
    (term-store/live-propositions (coord/coordinator-store co))
    (:propositions (snapshot-image! co version))))

(defn- term-sha256 [term]
  (let [out (ByteArrayOutputStream.)]
    (wire/write-term-codec-v1! out term wire/rpc-v1-max-string-bytes
                               wire/rpc-v1-max-term-nodes
                               wire/rpc-v1-max-term-depth)
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.toByteArray out))]
      (apply str (map #(format "%02x" (bit-and 255 (int %))) digest)))))

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

(defn- paged-query-result! [rows page query-digest snapshot-version]
  (if (nil? page)
    {:payload (wire/rpc-query-rows! (mapv wire/rpc-query-row! rows))
     :page nil}
    (let [limit (t/rpcpagerequest-limit page)
          cursor-value (t/rpc-page-request-cursor-value page)
          cursor (when cursor-value (parse-query-cursor! cursor-value))]
      (when (or (< limit 1) (> limit query/max-page-limit))
        (daemon-fail! :query-page-limit "query page limit must be from 1 through 4096" {}))
      (when (and cursor (not= query-digest (:query-sha256 cursor)))
        (daemon-fail! :query-cursor-mismatch
                      "query cursor belongs to a different query" {}))
      (let [ordinal (or (:next-page-ordinal cursor) 0)
            start
            (if cursor
              (let [position (first (keep-indexed
                                     (fn [index row]
                                       (when (= row (:after-row cursor)) index))
                                     rows))]
                (when (nil? position)
                  (daemon-fail! :query-cursor-mismatch
                                "query cursor row is absent from its snapshot" {}))
                (inc position))
              0)
            selected (vec (take limit (drop start rows)))
            next-position (+ start (count selected))
            done (>= next-position (count rows))
            next-cursor
            (when-not done
              (wire/rpc-query-cursor!
               snapshot-version query-digest (inc ordinal)
               (wire/rpc-query-row! (peek selected))))]
        {:payload (wire/rpc-query-rows! (mapv wire/rpc-query-row! selected))
         :page (wire/rpc-page-response! ordinal next-cursor done)}))))

(defn- handle-query! [request cancellation]
  (let [[plan-term snapshot]
        (record-fields! (t/rpc-request-payload-value request) :query/request 2)
        plan (parse-query-plan! plan-term)
        query-digest (term-sha256 plan-term)
        page (t/rpcrequest-page request)
        cursor-value (some-> page t/rpc-page-request-cursor-value)
        cursor (when cursor-value (parse-query-cursor! cursor-value))
        direct-page? (and page (all-triples-page-plan? plan))
        snapshot-data
        (locking (:lock @coordinator)
          (require-expected! @coordinator
                             (t/rpcrequest-expected-version request))
          (let [version (requested-snapshot-version!
                         snapshot cursor (current-version @coordinator))]
            (if direct-page?
              {:version version
               :rows (cached-query-page-rows version)
               :propositions (when-not (cached-query-page-rows version)
                               (snapshot-propositions! @coordinator version))}
              (snapshot-image! @coordinator version))))
        timeout (min 60000 (or (t/rpcrequest-timeout-ms request) 5000))
        control (datalog/query-control 10000000 timeout)]
    (if direct-page?
      (let [rows (or (:rows snapshot-data)
                     (ordered-query-page-rows!
                      (:version snapshot-data)
                      (:propositions snapshot-data)
                      cancellation))
            paged (paged-query-result!
                   rows page query-digest (:version snapshot-data))]
        (cancelled! cancellation)
        (assoc paged :served (:version snapshot-data)))
      (do
        (reset! (:query-control cancellation) control)
        (when @(:cancelled cancellation)
          (datalog/cancel-query! control :request-cancelled))
        (try
          (let [projection (query/project-with-occurrences
                            (:propositions snapshot-data)
                            (:occurrences snapshot-data))
                result (binding [query/*query-control* control]
                         (query/run-plan-projected! projection plan))
                rows (result-rows! result)
                paged (paged-query-result!
                       rows page query-digest (:version snapshot-data))]
            (cancelled! cancellation)
            (assoc paged :served (:version snapshot-data)))
          (finally
            (reset! (:query-control cancellation) nil)))))))

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

(defn- handle-lease-check! [payload cancellation]
  (let [[resource holder epoch] (parse-fence! payload)]
    (cancelled! cancellation)
    (locking (:lock @coordinator)
      (let [[current-holder current-epoch _ expires-ms]
            (or (current-fence @coordinator resource) [nil nil nil nil])
            matching (and (= holder current-holder) (= epoch current-epoch))
            valid (and matching (> expires-ms (System/currentTimeMillis)))]
        (wire/rpc-lease-check!
         (boolean valid) (when matching (millis->instant expires-ms)))))))

(defn- handle-validate! [payload cancellation]
  (require-unit! payload)
  (cancelled! cancellation)
  (try
    (let [co @coordinator
          dump (term-store/dump-term-store (coord/coordinator-store co))
          copy (term-store/new-term-store (coord/coordinator-space co))]
      (term-store/load-term-store! copy dump)
      (wire/rpc-validation! true []))
    (catch Throwable error
      (let [code (or (:fram/code (ex-data error))
                     (:type (ex-data error)) :rpc/validation-failed)]
        (wire/rpc-validation!
         false [(wire/rpc-violation!
                 (if (keyword? code) code :rpc/validation-failed)
                 (or (.getMessage error) "validation failed"))])))))

(defn- status-payload []
  (let [co @coordinator
        state (:status (coord/coordinator-recovery-state co))]
    (wire/rpc-status! state (count (coord/live-propositions co)) :rpc/jvm)))

(defn- dispatch-request! [request cancellation]
  (let [operation (t/rpcrequest-op request)
        payload (t/rpc-request-payload-value request)]
    (case operation
      :rpc/version (do (require-unit! payload) {:payload wire/rpc-unit})
      :rpc/status (do (require-unit! payload) {:payload (status-payload)})
      :rpc/assert {:payload (handle-write! request :rpc/assert cancellation)}
      :rpc/retract {:payload (handle-write! request :rpc/retract cancellation)}
      :rpc/batch {:payload (handle-batch! request cancellation)}
      :rpc/scan {:payload (handle-scan! request cancellation)}
      :rpc/query (handle-query! request cancellation)
      :rpc/occurrences {:payload (handle-occurrences! payload cancellation)}
      :rpc/lease-acquire {:payload (handle-lease-acquire! request cancellation)}
      :rpc/lease-renew {:payload (handle-lease-renew! request cancellation)}
      :rpc/lease-release {:payload (handle-lease-release! request cancellation)}
      :rpc/lease-check {:payload (handle-lease-check! payload cancellation)}
      :rpc/validate {:payload (handle-validate! payload cancellation)}
      (daemon-fail! :rpc/unsupported-operation
                    "operation is not part of FRAMRPC v1" {}))))

(def ^:private retryable-error-codes
  #{:rpc/conflict :rpc/cancelled :query-cancelled :query-time-limit
    :query-work-limit :durability-ambiguous})

(defn- response-version []
  (if-let [co @coordinator] (current-version co) 0))

(defn handle-rpc-request! [request cancellation]
  (let [space (t/rpcrequest-space request)
        operation (t/rpcrequest-op request)]
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
      (when (and (not= operation :rpc/query) (t/rpcrequest-page request))
        (daemon-fail! :rpc/unexpected-page
                      "paging is supported only by rpc/query" {}))
      (when (and (not= operation :rpc/query) (t/rpcrequest-timeout-ms request))
        (daemon-fail! :rpc/unexpected-timeout
                      "timeout-ms is supported only by rpc/query" {}))
      (let [result
            (if (= operation :rpc/query)
              (dispatch-request! request cancellation)
              (locking (:lock @coordinator)
                (require-expected! @coordinator
                                   (t/rpcrequest-expected-version request))
                (let [dispatched (dispatch-request! request cancellation)]
                  (assoc dispatched :served
                         (or (:served dispatched)
                             (current-version @coordinator))))))
            {:keys [payload page served]} result]
        (wire/rpc-response! space operation (or served (response-version))
                            page nil payload))
      (catch Throwable error
        (let [data (ex-data error)
              code (or (:fram/code data) (:code data) (:type data)
                       :rpc/internal-error)
              code (if (keyword? code) code :rpc/internal-error)]
          (wire/rpc-response!
           space operation (response-version) nil
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
    (.write output bytes))
  (.flush output))

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
          frame (read-rpc-frame! input)]
      (when frame
        (if (= :cancel (t/rpcframev1-kind frame))
          (handle-rpc-frame! frame (cancellation-state))
          (let [request-id (t/rpcframev1-request-id frame)
                cancellation (cancellation-state)]
            (register-request! request-id cancellation)
            (future
              (try
                (when (neg? (.read input))
                  (cancel-state! cancellation :client-disconnected))
                (catch Throwable _
                  (cancel-state! cancellation :client-disconnected))))
            (try
              (write-rpc-frame! output (handle-rpc-frame! frame cancellation))
              (finally
                (swap! active-requests dissoc request-id)))))))))

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
