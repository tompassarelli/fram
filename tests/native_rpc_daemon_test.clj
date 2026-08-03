;; FRAMRPC v1 JVM listener: closed operation set, typed payloads, history,
;; query snapshots, leases, cancellation, malformed input, and restart replay.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[coord-daemon-wire :as wire]
         '[fram.kernel :as kernel]
         '[fram.query :as query]
         '[fram.types :as t])

(load-file "coord_daemon.clj")
(load-file "tests/native_rpc_client.clj")

(def failures (atom []))
(def request-id (atom 0))

(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok (swap! failures conj label)))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))

(defn eventually [f]
  (loop [attempt 0]
    (let [value (try (f) (catch Throwable _ nil))]
      (cond value value
            (>= attempt 200) nil
            :else (do (Thread/sleep 25) (recur (inc attempt)))))))

(defn fields [value tag count-value]
  (wire/rpc-record-fields! value tag count-value))

(defn values-list [value]
  (wire/rpc-list-values! value))

(defn payload [response]
  (t/rpc-response-payload-value response))

(defn error-code [response]
  (some-> response t/rpcresponse-error t/rpcerror-code))

(defn request! [port space operation payload & {:keys [expected page timeout]}]
  (native-rpc-client/request!
   port (swap! request-id inc)
   (wire/rpc-request! space operation expected page timeout payload)))

(defn action-results [response]
  (let [[results] (fields (payload response) :rpc/mutation-result 1)]
    (mapv #(fields % :rpc/action-result 3) (values-list results))))

(defn triples-result [response tag]
  (let [[values] (fields (payload response) tag 1)]
    (values-list values)))

(defn query-rows [response]
  (mapv (fn [row]
          (let [[values] (fields row :query/row 1)] (values-list values)))
        (triples-result response :query/rows)))

(defn paged-query-rows [port space query-payload]
  (loop [cursor nil rows []]
    (let [response (request! port space :rpc/query query-payload
                             :page (wire/rpc-page-request! 1 cursor))
          page (t/rpcresponse-page response)
          next-cursor (when page (t/rpc-page-response-cursor-value page))]
      (if (or (error-code response) (nil? page))
        {:error (error-code response) :rows rows}
        (let [all-rows (into rows (query-rows response))]
          (if (t/rpcpageresponse-done page)
            {:error nil :rows all-rows}
            (recur next-cursor all-rows)))))))

(defn paged-read
  "Drain one paged read into its rows and the versions each page served."
  ([port space operation payload limit tag]
   (paged-read port space operation payload limit tag nil))
  ([port space operation payload limit tag start-cursor]
   (loop [cursor start-cursor rows [] versions []]
     (let [response (request! port space operation payload
                              :page (wire/rpc-page-request! limit cursor))
           page (t/rpcresponse-page response)]
       (if (or (error-code response) (nil? page))
         {:error (or (error-code response) :missing-page)
          :rows rows :versions versions}
         (let [all-rows (into rows (triples-result response tag))
               all-versions (conj versions
                                  (t/rpcresponse-served-version response))]
           (if (t/rpcpageresponse-done page)
             {:error nil :rows all-rows :versions all-versions}
             (recur (t/rpc-page-response-cursor-value page)
                    all-rows all-versions))))))))

(defn one-triple-plan [relation head-terms body-terms]
  (wire/rpc-query-plan!
   (wire/rpc-query-find-relation! relation)
   [(wire/rpc-query-stratum!
     [(wire/rpc-query-rule!
       (wire/rpc-query-head! relation head-terms)
       [(wire/rpc-query-relation! "triple" body-terms false)])])]))

(defn all-triples-plan []
  (let [slot0 (wire/rpc-query-variable! "slot0")
        slot1 (wire/rpc-query-variable! "slot1")
        slot2 (wire/rpc-query-variable! "slot2")]
    (wire/rpc-query-plan!
     (wire/rpc-query-find-relation! "all")
     [(wire/rpc-query-stratum!
       [(wire/rpc-query-rule!
         (wire/rpc-query-head! "all" [slot0 slot1 slot2])
         [(wire/rpc-query-relation! "triple" [slot0 slot1 slot2] false)])])])))

(defn all-occurrences-plan []
  (let [coordinate (wire/rpc-query-variable! "coordinate")
        action (wire/rpc-query-variable! "action")
        proposition (wire/rpc-query-variable! "proposition")]
    (wire/rpc-query-plan!
     (wire/rpc-query-find-relation! "occurrences")
     [(wire/rpc-query-stratum!
       [(wire/rpc-query-rule!
         (wire/rpc-query-head! "occurrences" [coordinate action proposition])
         [(wire/rpc-query-relation!
           "occurrence" [coordinate action proposition] false)])])])))

(def scratch
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-native-rpc-"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (str (io/file scratch "history.framlog")))
(def space "native-rpc-test")
(def port (free-port))
(def server (future (coord-daemon/serve! port log-path space :active)))

(try
  (check! "listener starts on FRAMRPC v1"
          (some? (eventually #(request! port space :rpc/version wire/rpc-unit))))

  (check! "operation disposition is exhaustive for the thirteen v1 operations"
          (and (= 13 (count coord-daemon/native-rpc-operations))
               (every? #(= :supported (coord-daemon/native-op-disposition %))
                       coord-daemon/native-rpc-operations)
               (every? #(= :unsupported (coord-daemon/native-op-disposition %))
                       [:status :facts :query :for-log :rpc/not-an-operation])))

  (let [source (slurp "coord_daemon.clj")]
    (check! "listener source has no EDN/line-reader compatibility path"
            (not-any? #(str/includes? source %)
                      ["clojure.edn" "edn/read" "readLine" "io/reader"])))

  (let [response (request! port space :rpc/version wire/rpc-unit)]
    (check! "rpc/version reports the outer logical version"
            (and (nil? (error-code response))
                 (= 0 (t/rpcresponse-served-version response))
                 (= wire/rpc-unit (payload response)))))

  (let [response (request! port space :rpc/status wire/rpc-unit)
        [state live-count engine cache] (fields (payload response) :rpc/status 4)
        [hits misses bytes evictions] (fields cache :rpc/result-cache 4)]
    (check! "rpc/status is a typed record"
            (and (= :ready state) (= 0 live-count) (= :rpc/jvm engine)
                 (= [0 0 0 0] [hits misses bytes evictions]))))

  (let [bad (request! port space :status wire/rpc-unit)]
    (check! "legacy and unknown operations fail as typed unsupported requests"
            (= :rpc/unsupported-operation (error-code bad))))

  (with-open [socket (java.net.Socket.)]
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 2000)
    (let [output (.getOutputStream socket)]
      (.write output (.getBytes "{:op :status}\n" "UTF-8"))
      (.flush output)
      (check! "EDN is not accepted on the native listener"
              (= -1 (.read (.getInputStream socket))))))

  (let [cancel-bytes (wire/encode-rpc-frame-v1! (wire/rpc-cancel-frame 44))]
    (aset-byte cancel-bytes 14 (unchecked-byte 255))
    (aset-byte cancel-bytes 15 (unchecked-byte 255))
    (aset-byte cancel-bytes 16 (unchecked-byte 255))
    (aset-byte cancel-bytes 17 (unchecked-byte 127))
    (check! "oversized frames are rejected from the header before body allocation"
            (= :rpc-frame-too-large
               (try
                 (coord-daemon/read-rpc-frame!
                  (java.io.ByteArrayInputStream. cancel-bytes))
                 nil
                 (catch clojure.lang.ExceptionInfo error
                   (:fram/code (ex-data error)))))))

  (let [nested-subject (t/triple "source-file" :page 1)
        proposition (t/triple nested-subject :title "Door Schedule")
        response (request! port space :rpc/assert
                           (wire/rpc-write! proposition wire/rpc-subject-any nil))
        [[input-index changed occurrences]] (action-results response)
        events (values-list occurrences)]
    (check! "recursive Triple assertion returns its direct occurrence"
            (and (= 0 input-index) changed (= 1 (count events))
                 (kernel/assertion-occurrence? (first events))
                 (= proposition (kernel/proposition-of (first events)))
                 (= 1 (t/rpcresponse-served-version response))))
    (let [scan (request! port space :rpc/scan
                         (wire/rpc-triple-pattern! nested-subject nil nil))]
      (check! "slot-addressed scan returns the recursive proposition"
              (= [proposition] (triples-result scan :rpc/triples))))

    (let [replacement (t/triple nested-subject :title "Revised Schedule")
          batch (request!
                 port space :rpc/batch
                 (wire/rpc-batch!
                  [(wire/rpc-action! :rpc/assert replacement wire/rpc-subject-existing)
                   (wire/rpc-action! :rpc/assert (t/triple "other" :value 2)
                                     wire/rpc-subject-any)
                   (wire/rpc-action! :rpc/retract proposition wire/rpc-subject-existing)]
                  nil))
          results (action-results batch)]
      (check! "batch applies ordered assert/retract actions in one transaction"
              (and (= [0 1 2] (mapv first results))
                   (= [true true true] (mapv second results))
                   (= 2 (t/rpcresponse-served-version batch))))

      (let [no-op (request! port space :rpc/retract
                            (wire/rpc-write! proposition wire/rpc-subject-any nil))
            [[_ changed occurrences]] (action-results no-op)]
        (check! "missing retract is an explicit no-op with no version movement"
                (and (false? changed) (empty? (values-list occurrences))
                     (= 2 (t/rpcresponse-served-version no-op)))))

      (let [history (request! port space :rpc/occurrences wire/rpc-unit)
            events (triples-result history :rpc/occurrences)]
        (check! "occurrences are direct assertion/retraction Triples"
                (and (seq events) (every? kernel/operation-occurrence? events))))

      (let [plan (all-triples-plan)
            query-payload (wire/rpc-query-request! plan wire/query-current)
            first-page (request! port space :rpc/query query-payload
                                 :page (wire/rpc-page-request! 1 nil))
            cursor (t/rpc-page-response-cursor-value
                    (t/rpcresponse-page first-page))
            later (t/triple "later" :value 3)
            asserted-later (request! port space :rpc/assert
                                     (wire/rpc-write! later wire/rpc-subject-any nil))
            second-page (request! port space :rpc/query query-payload
                                  :page (wire/rpc-page-request! 4096 cursor))
            pinned-rows (into (query-rows first-page) (query-rows second-page))
            historical (request!
                        port space :rpc/query
                        (wire/rpc-query-request! plan (wire/rpc-query-as-of! 1)))
            current-after-ack
            (request! port space :rpc/scan
                      (wire/rpc-triple-pattern! "later" :value 3))]
        (check! "query cursor pins snapshot version across later commits"
                (and (= 2 (t/rpcresponse-served-version first-page))
                     (= 3 (t/rpcresponse-served-version asserted-later))
                     (= 2 (t/rpcresponse-served-version second-page))
                     (not-any? #(= ["later" :value 3] %) pinned-rows)))
        (check! "query as-of rebuilds the requested logical snapshot"
                (and (= 1 (t/rpcresponse-served-version historical))
                     (some #(= [(t/triple "source-file" :page 1)
                                :title "Door Schedule"] %)
                           (query-rows historical))))
        (let [occurrence-plan (all-occurrences-plan)
              at-one (request!
                      port space :rpc/query
                      (wire/rpc-query-request!
                       occurrence-plan (wire/rpc-query-as-of! 1)))
              at-two (request!
                      port space :rpc/query
                      (wire/rpc-query-request!
                       occurrence-plan (wire/rpc-query-as-of! 2)))
              since-one (request!
                         port space :rpc/query
                         (wire/rpc-query-request!
                          occurrence-plan
                          (wire/rpc-query-since!
                           1 (wire/rpc-query-as-of! 2))))
              current-since (request!
                             port space :rpc/query
                             (wire/rpc-query-request!
                              occurrence-plan
                              (wire/rpc-query-since! 1 wire/query-current)))
              row-sequence (fn [row]
                             (-> row first t/triple-slot0 t/triple-slot2))]
          (check! "query since composes deterministic (L,U] occurrence history"
                  (and (= 2 (t/rpcresponse-served-version at-two))
                       (= (set (query-rows at-two))
                          (into (set (query-rows at-one))
                                (query-rows since-one)))
                       (every? #(= 2 (row-sequence %))
                               (query-rows since-one))
                       (every? #(< 1 (row-sequence %) 4)
                               (query-rows current-since))))
          (let [first-page
                (request!
                 port space :rpc/query
                 (wire/rpc-query-request!
                  occurrence-plan
                  (wire/rpc-query-since! 1 wire/query-current))
                 :page (wire/rpc-page-request! 1 nil))
                cursor (t/rpc-page-response-cursor-value
                        (t/rpcresponse-page first-page))
                mismatched
                (request!
                 port space :rpc/query
                 (wire/rpc-query-request!
                  occurrence-plan
                  (wire/rpc-query-since! 0 wire/query-current))
                 :page (wire/rpc-page-request! 1 cursor))]
            (check! "query cursor binds the resolved since lower bound"
                    (= :query-cursor-mismatch (error-code mismatched))))
          (with-redefs [coord-daemon/query-checkpoint-interval 1]
            (#'coord-daemon/drop-query-caches!)
            (let [checkpoint (io/file
                              (str log-path ".query-checkpoints")
                              "snapshot-1.fri")
                  built (request!
                         port space :rpc/query
                         (wire/rpc-query-request!
                          occurrence-plan (wire/rpc-query-as-of! 1)))
                  _ (spit checkpoint "corrupt-derived-cache")
                  _ (#'coord-daemon/drop-query-caches!)
                  rebuilt (request!
                           port space :rpc/query
                           (wire/rpc-query-request!
                            occurrence-plan (wire/rpc-query-as-of! 1)))]
              (check! "as-of uses prefix-bound FRI2 checkpoints and corrupt cache falls back"
                      (and (.isFile checkpoint)
                           (nil? (error-code built))
                           (nil? (error-code rebuilt))
                           (= (query-rows built) (query-rows rebuilt))
                           (> (.length checkpoint) 32)))))
          (let [before (request!
                        port space :rpc/query
                        (wire/rpc-query-request!
                         occurrence-plan (wire/rpc-query-as-of! 1)))
                entry (coord-daemon/seal-query-epoch! 1)
                after (request!
                       port space :rpc/query
                       (wire/rpc-query-request!
                        occurrence-plan (wire/rpc-query-as-of! 1)))
                _ (.delete (io/file (:path entry)))
                _ (reset! (var-get #'coord-daemon/query-archive-coordinators) {})
                _ (#'coord-daemon/drop-query-caches!)
                unavailable (request!
                             port space :rpc/query
                             (wire/rpc-query-request!
                              occurrence-plan (wire/rpc-query-as-of! 1)))
                _ (coord-daemon/seal-query-epoch! 1)
                _ (coord-daemon/expire-query-epoch! 1)
                expired (request!
                         port space :rpc/query
                         (wire/rpc-query-request!
                          occurrence-plan (wire/rpc-query-as-of! 1)))
                _ (coord-daemon/seal-query-epoch! 1)]
            (check! "sealed epoch preserves rows and distinguishes unavailable from expired"
                    (and (= (query-rows before) (query-rows after))
                         (= :query/archive-unavailable
                            (error-code unavailable))
                         (true? (some-> unavailable t/rpcresponse-error
                                        t/rpcerror-retryable))
                         (= :query/snapshot-expired (error-code expired))
                         (false? (some-> expired t/rpcresponse-error
                                         t/rpcerror-retryable))))))
        (check! "a read after an acknowledged write sees at least its version"
                (and (<= (t/rpcresponse-served-version asserted-later)
                         (t/rpcresponse-served-version current-after-ack))
                     (= [later]
                        (triples-result current-after-ack :rpc/triples))))
        (let [work-limited
              (with-redefs-fn
                {#'coord-daemon/cached-result!
                 (fn [& _]
                   (throw (ex-info "query evaluation stopped: query-work-limit"
                                   {:fram/code :query-work-limit})))}
                #(request!
                  port space :rpc/query
                  (wire/rpc-query-request! plan (wire/rpc-query-as-of! 1))))]
          (check! "an as-of work-limit error reports the snapshot it served"
                  (and (= :query-work-limit (error-code work-limited))
                       (= 1 (t/rpcresponse-served-version work-limited)))))))

      (let [stale (request!
                   port space :rpc/assert
                   (wire/rpc-write! (t/triple "stale" :value 2)
                                    wire/rpc-subject-any nil)
                   :expected 2)
            head (request! port space :rpc/version wire/rpc-unit)]
        (check! "stale expected-version fails OCC without version movement"
                (and (= :rpc/conflict (error-code stale))
                     (= 3 (t/rpcresponse-served-version head)))))

      (let [same (t/triple "same" :same "same")
            asserted-same (request! port space :rpc/assert
                                    (wire/rpc-write! same wire/rpc-subject-any nil))
            patterns
            [["constant predicate and reordered distinct bindings"
              (let [subject (wire/rpc-query-variable! "subject")
                    value (wire/rpc-query-variable! "value")]
                (one-triple-plan
                 "values" [value subject value]
                 [subject (wire/rpc-query-constant! :value) value]))]
             ["repeated body variable unifies equal slots"
              (let [value (wire/rpc-query-variable! "value")]
                (one-triple-plan
                 "self" [value]
                 [value (wire/rpc-query-constant! :same) value]))]
             ["constant subject projects one binding"
              (let [value (wire/rpc-query-variable! "value")]
                (one-triple-plan
                 "one-value" [value]
                 [(wire/rpc-query-constant! "other")
                  (wire/rpc-query-constant! :value)
                  value]))]
             ["constant-only match grounds a constant head"
              (one-triple-plan
               "constant-match" [(wire/rpc-query-constant! :matched)]
               [(wire/rpc-query-constant! "same")
                (wire/rpc-query-constant! :same)
                (wire/rpc-query-constant! "same")])]
             ["repeated body variable rejects unequal slots"
              (let [value (wire/rpc-query-variable! "value")]
                (one-triple-plan
                 "not-self" [value]
                 [value (wire/rpc-query-constant! :value) value]))]]]
        (check! "one-triple fixture assertion advances one version"
                (= 4 (t/rpcresponse-served-version asserted-same)))
        (doseq [[label plan] patterns]
          (let [query-payload (wire/rpc-query-request! plan wire/query-current)
                reference (request! port space :rpc/query query-payload)
                paged (paged-query-rows port space query-payload)]
            (check! (str "paged one-triple path matches Datalog: " label)
                    (and (nil? (error-code reference))
                         (nil? (:error paged))
                         (= (query-rows reference) (:rows paged)))))))

      (let [subject (wire/rpc-query-variable! "subject")
            value (wire/rpc-query-variable! "value")
            plan (one-triple-plan
                  "pinned-values" [subject value]
                  [subject (wire/rpc-query-constant! :value) value])
            query-payload (wire/rpc-query-request! plan wire/query-current)
            reference (request! port space :rpc/query query-payload)
            first-page (request! port space :rpc/query query-payload
                                 :page (wire/rpc-page-request! 1 nil))
            snapshot-version (t/rpcresponse-served-version first-page)
            cursor (t/rpc-page-response-cursor-value
                    (t/rpcresponse-page first-page))
            asserted-new (request!
                          port space :rpc/assert
                          (wire/rpc-write! (t/triple "new-value" :value 4)
                                           wire/rpc-subject-any nil))
            second-page (request! port space :rpc/query query-payload
                                  :page (wire/rpc-page-request! 4096 cursor))
            pinned-rows (into (query-rows first-page) (query-rows second-page))]
        (check! "selective one-triple cursor retains its immutable snapshot"
                (and (= snapshot-version
                        (t/rpcresponse-served-version second-page))
                     (= (inc snapshot-version)
                        (t/rpcresponse-served-version asserted-new))
                     (= (query-rows reference) pinned-rows)
                     (not-any? #(= ["new-value" 4] %) pinned-rows))))

    (let [subject (wire/rpc-query-variable! "subject")
          value (wire/rpc-query-variable! "value")
          values-plan (one-triple-plan
                       "values-after-writes" [subject value]
                       [subject (wire/rpc-query-constant! :value) value])
          values-payload (wire/rpc-query-request! values-plan wire/query-current)
          before-lease (request! port space :rpc/query values-payload)
          acquired (request! port space :rpc/lease-acquire
                             (wire/rpc-lease-acquire! :resource "holder" 60000))
          [fence _] (fields (payload acquired) :lease/grant 2)
          after-acquire (request! port space :rpc/query values-payload)
          checked (request! port space :rpc/lease-check fence)
          fenced-write (request!
                        port space :rpc/assert
                        (wire/rpc-write! (t/triple "fenced" :value true)
                                         wire/rpc-subject-any fence))
          renewed (request! port space :rpc/lease-renew
                            (wire/rpc-lease-renew! fence 60000))
          [next-fence _] (fields (payload renewed) :lease/grant 2)
          stale-fence (request!
                       port space :rpc/assert
                       (wire/rpc-write! (t/triple "fenced" :value false)
                                        wire/rpc-subject-any fence))
          old-check (request! port space :rpc/lease-check fence)
          released (request! port space :rpc/lease-release next-fence)
          projection-used (atom false)
          direct-reads
          (with-redefs [query/project-with-occurrences
                        (fn [& _]
                          (reset! projection-used true)
                          (throw (ex-info "whole-corpus projection used" {})))]
            {:query (request! port space :rpc/query values-payload)
             :paged (paged-query-rows port space values-payload)})
          [valid _] (fields (payload checked) :lease/check 2)
          [old-valid _] (fields (payload old-check) :lease/check 2)
          [released?] (fields (payload released) :lease/released 1)]
      (check! "lease acquire/check/renew/release use typed Fence records"
              (and valid (not old-valid) released? (not= fence next-fence)))
      (check! "write fencing accepts the current epoch and rejects the stale one"
              (and (nil? (error-code fenced-write))
                   (= :rpc/lease-fence-mismatch (error-code stale-fence))))
      (check! "one-triple query reads through lease acquisition"
              (and (nil? (error-code before-lease))
                   (nil? (error-code after-acquire))
                   (= (query-rows before-lease) (query-rows after-acquire))))
      (check! "one-triple query and page see the ordinary write after lease writes"
              (let [direct-rows (query-rows (:query direct-reads))]
                (and (nil? (error-code (:query direct-reads)))
                     (nil? (get-in direct-reads [:paged :error]))
                     (some #{["fenced" true]} direct-rows)
                     (= direct-rows (get-in direct-reads [:paged :rows])))))
      (check! "one-triple reads bypass the whole-corpus query projection"
              (false? @projection-used)))

    (let [subject (wire/rpc-query-variable! "cache-subject")
          value (wire/rpc-query-variable! "cache-value")
          arguments [subject (wire/rpc-query-constant! :value) value]
          plan (wire/rpc-query-plan!
                (wire/rpc-query-find-relation! "single-flight-values")
                [(wire/rpc-query-stratum!
                  [(wire/rpc-query-rule!
                    (wire/rpc-query-head!
                     "single-flight-values" [subject value])
                    [(wire/rpc-query-relation! "triple" arguments false)
                     (wire/rpc-query-relation! "triple" arguments false)])])])
          query-payload (wire/rpc-query-request! plan wire/query-current)
          row-builder (ns-resolve 'fram.query 'run-plan-projected!)
          original-builder (var-get row-builder)
          builds (atom 0)
          [first-read second-read advanced current-read historical-read]
          (with-redefs-fn
            {row-builder
             (fn [& args]
               (swap! builds inc)
               (Thread/sleep 100)
               (apply original-builder args))}
            #(let [a (future (request! port space :rpc/query query-payload))
                   b (future (request! port space :rpc/query query-payload))
                   first-read @a
                   second-read @b
                   pinned-version (t/rpcresponse-served-version first-read)
                   advanced
                   (request! port space :rpc/assert
                             (wire/rpc-write!
                              (t/triple "cache-version" :value 99)
                              wire/rpc-subject-any nil))
                   current-read (request! port space :rpc/query query-payload)
                   historical-read
                   (request!
                    port space :rpc/query
                    (wire/rpc-query-request!
                     plan (wire/rpc-query-as-of! pinned-version)))]
               [first-read second-read advanced current-read historical-read]))
          _ (doseq [index (range 9)]
              (request!
               port space :rpc/query
               (wire/rpc-query-request!
                (one-triple-plan
                 (str "cache-bound-" index) [subject value]
                 [subject (wire/rpc-query-constant! :value) value])
                wire/query-current)))
          status (request! port space :rpc/status wire/rpc-unit)
          [_ _ _ cache] (fields (payload status) :rpc/status 4)
          [hits misses bytes evictions] (fields cache :rpc/result-cache 4)
          cache-state @coord-daemon/query-result-cache
          version-counts (frequencies (map first (keys (:entries cache-state))))
          validated (request! port space :rpc/validate wire/rpc-unit)
          [valid violations] (fields (payload validated) :rpc/validation 2)]
      (check! "rpc/validate round-trips the store and result cache stays bounded"
              (and valid (empty? (values-list violations))
                   (= 2 @builds)
                   (= (query-rows first-read) (query-rows second-read))
                   (= (inc (t/rpcresponse-served-version first-read))
                      (t/rpcresponse-served-version advanced))
                   (not= (query-rows first-read) (query-rows current-read))
                   (= (query-rows first-read) (query-rows historical-read))
                   (pos? hits) (pos? misses) (pos? bytes) (pos? evictions)
                   (= bytes (:bytes cache-state))
                   (<= (count version-counts) 4)
                   (every? #(<= % 8) (vals version-counts))
                   (<= bytes (* 64 1024 1024)))))

    (let [profile-id "relational-v1"
          declaration (kernel/relational-profile-declaration space profile-id)
          profile-facts (into [declaration]
                              (mapv #(kernel/profile-rule profile-id %)
                                    kernel/relational-profile-rules))
          _ (doseq [proposition profile-facts]
              (request! port space :rpc/assert
                        (wire/rpc-write! proposition wire/rpc-subject-any nil)))
          violating (t/triple "profile-observe"
                              "nested-value"
                              (t/triple "still" "commits" true))
          write-response (request! port space :rpc/assert
                                   (wire/rpc-write!
                                    violating wire/rpc-subject-any nil))
          validated (request! port space :rpc/validate wire/rpc-unit)
          [valid violations] (fields (payload validated) :rpc/validation 2)
          advisories (values-list violations)]
      (check! "observe profile commits a violating write and validate reports it"
              (and valid
                   (nil? (error-code write-response))
                   (some (fn [advisory]
                           (let [[code detail]
                                 (fields advisory :rpc/violation 2)]
                             (and (= :rpc/profile-violation code)
                                  (= violating (t/triple-slot0 detail)))))
                         advisories)
                   (not-any? (fn [advisory]
                               (let [[_ detail]
                                     (fields advisory :rpc/violation 2)]
                                 (= declaration (t/triple-slot0 detail))))
                             advisories))))

    (let [predicate :page-fixture
          fixture-count 400
          scan-payload (wire/rpc-triple-pattern! nil predicate nil)
          scan-reference (fn []
                           (filterv #(= predicate (t/triple-slot1 %))
                                    (coord/live-propositions
                                     @coord-daemon/coordinator)))
          occurrence-reference (fn []
                                 (filterv kernel/operation-occurrence?
                                          (coord/history
                                           @coord-daemon/coordinator)))]
      (doseq [batch (partition-all 100 (range fixture-count))]
        (request! port space :rpc/batch
                  (wire/rpc-batch!
                   (mapv (fn [index]
                           (wire/rpc-action!
                            :rpc/assert
                            (t/triple (str "fixture-" index) predicate index)
                            wire/rpc-subject-any))
                         batch)
                   nil)))

      (let [scan-rows (scan-reference)
            occurrences (occurrence-reference)
            paged-scan (paged-read port space :rpc/scan scan-payload
                                   100 :rpc/triples)
            paged-occurrences (paged-read port space :rpc/occurrences
                                          wire/rpc-unit 100 :rpc/occurrences)
            unpaged-scan (request! port space :rpc/scan scan-payload)
            unpaged-occurrences (request! port space :rpc/occurrences
                                          wire/rpc-unit)]
        (check! "paged scan reassembles the corpus the unpaged reply cannot carry"
                (and (= fixture-count (count scan-rows))
                     (nil? (:error paged-scan))
                     (= scan-rows (:rows paged-scan))))
        (check! "paged occurrences reassemble the history past 251 events"
                (and (< 251 (count occurrences))
                     (nil? (:error paged-occurrences))
                     (= occurrences (:rows paged-occurrences))))
        (check! "unpaged scan and occurrences past the depth bound still fail typed"
                (and (= :term-depth-exceeded (error-code unpaged-scan))
                     (= :term-depth-exceeded (error-code unpaged-occurrences)))))

      (let [visited (atom 0)
            live coord/live-propositions
            bounded (with-redefs [coord/live-propositions
                                  (fn [co]
                                    (map (fn [proposition]
                                           (swap! visited inc)
                                           proposition)
                                         (live co)))]
                      (request! port space :rpc/scan scan-payload))]
        (check! "unpaged scan stops folding once the depth bound is unreachable"
                (and (= :term-depth-exceeded (error-code bounded))
                     (< @visited (count (scan-reference))))))

      (let [reference (scan-reference)
            first-page (request! port space :rpc/scan scan-payload
                                 :page (wire/rpc-page-request! 1 nil))
            pinned-version (t/rpcresponse-served-version first-page)
            cursor (t/rpc-page-response-cursor-value
                    (t/rpcresponse-page first-page))
            later (request! port space :rpc/assert
                            (wire/rpc-write!
                             (t/triple "fixture-later" predicate 4000)
                             wire/rpc-subject-any nil))
            rest-pages (paged-read port space :rpc/scan scan-payload
                                   100 :rpc/triples cursor)
            pinned (into (triples-result first-page :rpc/triples)
                         (:rows rest-pages))]
        (check! "scan cursor pins its snapshot across an intervening commit"
                (and (nil? (:error rest-pages))
                     (= (inc pinned-version)
                        (t/rpcresponse-served-version later))
                     (every? #(= pinned-version %) (:versions rest-pages))
                     (= reference pinned))))

      (let [reference (occurrence-reference)
            first-page (request! port space :rpc/occurrences wire/rpc-unit
                                 :page (wire/rpc-page-request! 1 nil))
            pinned-version (t/rpcresponse-served-version first-page)
            cursor (t/rpc-page-response-cursor-value
                    (t/rpcresponse-page first-page))
            later (request! port space :rpc/assert
                            (wire/rpc-write!
                             (t/triple "occurrence-later" predicate 4001)
                             wire/rpc-subject-any nil))
            rest-pages (paged-read port space :rpc/occurrences wire/rpc-unit
                                   100 :rpc/occurrences cursor)
            pinned (into (triples-result first-page :rpc/occurrences)
                         (:rows rest-pages))]
        (check! "occurrences cursor pins its snapshot across an intervening commit"
                (and (nil? (:error rest-pages))
                     (= (inc pinned-version)
                        (t/rpcresponse-served-version later))
                     (every? #(= pinned-version %) (:versions rest-pages))
                     (= reference pinned))))

      (let [duplicated (t/triple "duplicated" :page-dup 0)
            dup-payload (wire/rpc-triple-pattern! nil :page-dup nil)]
        (doseq [proposition [duplicated
                             (t/triple "between" :page-dup 1)
                             duplicated
                             (t/triple "tail" :page-dup 2)]]
          (request! port space :rpc/assert
                    (wire/rpc-write! proposition wire/rpc-subject-any nil)))
        (let [reference (filterv #(= :page-dup (t/triple-slot1 %))
                                 (coord/live-propositions
                                  @coord-daemon/coordinator))
              paged (paged-read port space :rpc/scan dup-payload
                                3 :rpc/triples)]
          (check! "scan pages resume by position, not by row value"
                  (and (= 4 (count reference))
                       (= 2 (count (filter #{duplicated} reference)))
                       (nil? (:error paged))
                       (= reference (:rows paged))))))

      (let [fixture-cursor (t/rpc-page-response-cursor-value
                            (t/rpcresponse-page
                             (request! port space :rpc/scan scan-payload
                                       :page (wire/rpc-page-request! 1 nil))))
            mismatched (request! port space :rpc/scan
                                 (wire/rpc-triple-pattern! nil :value nil)
                                 :page (wire/rpc-page-request! 1 fixture-cursor))
            over-limit (request! port space :rpc/scan scan-payload
                                 :page (wire/rpc-page-request!
                                        (inc query/max-page-limit) nil))
            unpaged-op (request! port space :rpc/status wire/rpc-unit
                                 :page (wire/rpc-page-request! 1 nil))]
        (check! "scan cursors stay bound to their pattern, limit, and operation"
                (and (= :query-cursor-mismatch (error-code mismatched))
                     (= :query-page-limit (error-code over-limit))
                     (= :rpc/unexpected-page (error-code unpaged-op))))))

    (let [before (t/rpcresponse-served-version
                  (request! port space :rpc/version wire/rpc-unit))
          cancellation {:cancelled (atom true) :query-control (atom nil)}
          refused (coord-daemon/handle-rpc-request!
                   (wire/rpc-request!
                    space :rpc/assert nil nil nil
                    (wire/rpc-write! (t/triple "cancelled" :value 1)
                                     wire/rpc-subject-any nil))
                   cancellation)
          after (t/rpcresponse-served-version
                 (request! port space :rpc/version wire/rpc-unit))]
      (check! "pre-durability cancellation cannot append a transaction"
              (and (= :rpc/cancelled (error-code refused)) (= before after))))

    (let [cancellation {:cancelled (atom false) :query-control (atom nil)}]
      (swap! coord-daemon/active-requests assoc 777 cancellation)
      (coord-daemon/handle-rpc-frame! (wire/rpc-cancel-frame 777)
                                      {:cancelled (atom false)
                                       :query-control (atom nil)})
      (swap! coord-daemon/active-requests dissoc 777)
      (check! "cancel frames target the matching active request id"
              @(:cancelled cancellation))))

  (finally
    (coord-daemon/shutdown!)
    (deref server 3000 nil)))

(let [restart-port (free-port)
      restarted (future (coord-daemon/serve! restart-port log-path space :active))]
  (try
    (let [version (eventually #(request! restart-port space :rpc/version wire/rpc-unit))
          scan (request! restart-port space :rpc/scan
                         (wire/rpc-triple-pattern! "later" :value 3))
          status (request! restart-port space :rpc/status wire/rpc-unit)
          [_ _ _ cache] (fields (payload status) :rpc/status 4)
          cache-stats (fields cache :rpc/result-cache 4)]
      (check! "restart replays native RPC mutations from FRAMLOG"
              (and (pos? (t/rpcresponse-served-version version))
                   (= [(t/triple "later" :value 3)]
                      (triples-result scan :rpc/triples))
                   (= [0 0 0 0] cache-stats))))
    (finally
      (coord-daemon/shutdown!)
      (deref restarted 3000 nil))))

(shutdown-agents)

(if (seq @failures)
  (do
    (println (str "\n" (count @failures) " native RPC daemon checks failed"))
    (System/exit 1))
  (println "\nFRAMRPC v1 JVM daemon: all checks passed"))
