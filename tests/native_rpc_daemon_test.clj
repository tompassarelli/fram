;; FRAMRPC v1 JVM listener: closed operation set, typed payloads, history,
;; query snapshots, leases, cancellation, malformed input, and restart replay.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[coord-daemon-wire :as wire]
         '[fram.kernel :as kernel]
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
        [state live-count engine] (fields (payload response) :rpc/status 3)]
    (check! "rpc/status is a typed record"
            (and (= :ready state) (= 0 live-count) (= :rpc/jvm engine))))

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

  (let [nested-subject (t/triple "source-file" :plangrep/page 1)
        proposition (t/triple nested-subject :plangrep/title "Door Schedule")
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

    (let [replacement (t/triple nested-subject :plangrep/title "Revised Schedule")
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
                        (wire/rpc-query-request! plan (wire/rpc-query-as-of! 1)))]
        (check! "query cursor pins snapshot version across later commits"
                (and (= 2 (t/rpcresponse-served-version first-page))
                     (= 3 (t/rpcresponse-served-version asserted-later))
                     (= 2 (t/rpcresponse-served-version second-page))
                     (not-any? #(= ["later" :value 3] %) pinned-rows)))
        (check! "query as-of rebuilds the requested logical snapshot"
                (and (= 1 (t/rpcresponse-served-version historical))
                     (some #(= [(t/triple "source-file" :plangrep/page 1)
                                :plangrep/title "Door Schedule"] %)
                           (query-rows historical))))))

      (let [stale (request!
                   port space :rpc/assert
                   (wire/rpc-write! (t/triple "stale" :value 2)
                                    wire/rpc-subject-any nil)
                   :expected 2)
            head (request! port space :rpc/version wire/rpc-unit)]
        (check! "stale expected-version fails OCC without version movement"
                (and (= :rpc/conflict (error-code stale))
                     (= 3 (t/rpcresponse-served-version head)))))

    (let [acquired (request! port space :rpc/lease-acquire
                             (wire/rpc-lease-acquire! :resource "holder" 60000))
          [fence _] (fields (payload acquired) :lease/grant 2)
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
          [valid _] (fields (payload checked) :lease/check 2)
          [old-valid _] (fields (payload old-check) :lease/check 2)
          [released?] (fields (payload released) :lease/released 1)]
      (check! "lease acquire/check/renew/release use typed Fence records"
              (and valid (not old-valid) released? (not= fence next-fence)))
      (check! "write fencing accepts the current epoch and rejects the stale one"
              (and (nil? (error-code fenced-write))
                   (= :rpc/lease-fence-mismatch (error-code stale-fence)))))

    (let [validated (request! port space :rpc/validate wire/rpc-unit)
          [valid violations] (fields (payload validated) :rpc/validation 2)]
      (check! "rpc/validate round-trips the portable store dump"
              (and valid (empty? (values-list violations)))))

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
                         (wire/rpc-triple-pattern! "later" :value 3))]
      (check! "restart replays native RPC mutations from FRAMLOG"
              (and (pos? (t/rpcresponse-served-version version))
                   (= [(t/triple "later" :value 3)]
                      (triples-result scan :rpc/triples)))))
    (finally
      (coord-daemon/shutdown!)
      (deref restarted 3000 nil))))

(shutdown-agents)

(if (seq @failures)
  (do
    (println (str "\n" (count @failures) " native RPC daemon checks failed"))
    (System/exit 1))
  (println "\nFRAMRPC v1 JVM daemon: all checks passed"))
