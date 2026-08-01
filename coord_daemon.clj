;; coord_daemon.clj — narrow TermStore v2 coordinator daemon.
;;
;; Run long-lived servers with `clojure -M`, never Babashka. This surface stays
;; deliberately small until schema/query/pull/world projections consume TermStore
;; directly; it never reconstructs the removed fact-object APIs.
(ns coord-daemon
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fram.kernel :as kernel]
            [fram.store :as term-store]
            [fram.types :as t])
  (:import [java.net ServerSocket Socket]
           [java.io BufferedReader InputStreamReader OutputStreamWriter
            BufferedWriter]))

(load-file "coord.clj")
(load-file "coord_writer_authority.clj")

(def coordinator (atom nil))
(def coordinator-role (atom nil))
(def writer-authority (atom nil))
(def listener (atom nil))
(def stopping? (atom false))

(def ^:private unavailable-projections
  [:schema :pull :query :datalog :resolver :fri-cache :worlds :codegraph])
(def ^:private unavailable-runtime-surfaces
  [:legacy-cli-wire :snapshot-reload :blue-green-cutover :tls-listener])

(defn- daemon-fail! [code message data]
  (throw (ex-info message (assoc data :type code :fram/code code :code code))))

(defn- wire->term [value]
  (cond
    (t/term? value) value
    (and (map? value) (= #{:instant} (set (keys value))))
    (let [parts (:instant value)]
      (when-not (and (vector? parts) (= 2 (count parts))
                     (every? integer? parts))
        (daemon-fail! :invalid-wire-term
                      "Instant wire value must be {:instant [seconds nanos]}"
                      {:value value}))
      (t/instant (first parts) (second parts)))
    (vector? value)
    (do
      (when-not (= 3 (count value))
        (daemon-fail! :invalid-wire-term
                      "Triple wire value must contain exactly three slots"
                      {:value value}))
      (t/triple (wire->term (nth value 0))
                (wire->term (nth value 1))
                (wire->term (nth value 2))))
    :else
    (daemon-fail! :invalid-wire-term "wire value is outside Term"
                  {:value value})))

(defn- term->wire [value]
  (cond
    (t/triple? value)
    [(term->wire (t/triple-slot0 value))
     (term->wire (t/triple-slot1 value))
     (term->wire (t/triple-slot2 value))]
    (t/instant? value)
    {:instant [(t/instant-epoch-seconds value) (t/instant-nanos value)]}
    :else value))

(defn- response->wire [value]
  (cond
    (t/term? value) (term->wire value)
    (map? value) (into {} (map (fn [[key item]] [key (response->wire item)])) value)
    (vector? value) (mapv response->wire value)
    (set? value) (mapv response->wire value)
    (seq? value) (mapv response->wire value)
    :else value))

(defn- canonical-path [path]
  (.getPath (.getCanonicalFile (io/file (str path)))))

(defn writer-authority-status []
  (when @coordinator
    (coord-writer-authority/status
     @coordinator-role @writer-authority (:log @coordinator))))

(defn write-authorized? []
  (boolean (and (= :active @coordinator-role)
                (coord-writer-authority/held? @writer-authority))))

(defn shutdown! []
  (reset! stopping? true)
  (when-let [^ServerSocket server @listener]
    (try (.close server) (catch Throwable _ nil)))
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

(defn- mutation? [operation]
  (contains? #{:commit :assert :retract :withdraw :supersede
               :view-select :view-deselect
               :acquire-lease :renew-lease :release-lease}
             operation))

(defn- request-options [request]
  (cond-> {}
    (contains? request :base) (assoc :base (wire->term (:base request)))
    (contains? request :actor) (assoc :actor (wire->term (:actor request)))
    (contains? request :recorded-at)
    (assoc :recorded-at (wire->term (:recorded-at request)))
    (contains? request :source-frame)
    (assoc :source-frame (wire->term (:source-frame request)))))

(defn- request-operation [operation]
  (cond-> {:action (:action operation)
           :proposition (wire->term (:triple operation))}
    (contains? operation :recorded-at)
    (assoc :recorded-at (wire->term (:recorded-at operation)))
    (contains? operation :asserted-by)
    (assoc :asserted-by (wire->term (:asserted-by operation)))
    (contains? operation :source-frame)
    (assoc :source-frame (wire->term (:source-frame operation)))
    (contains? operation :withdraws)
    (assoc :withdraws (wire->term (:withdraws operation)))
    (contains? operation :supersedes)
    (assoc :supersedes (wire->term (:supersedes operation)))))

(defn- status-response []
  (let [co @coordinator
        context (coord/coordinator-store co)]
    {:format :termstore-v2/framlog
     :space-id (coord/coordinator-space co)
     :version (coord/current-transaction co)
     :transactions (term-store/transaction-count context)
     :operations (term-store/operation-count context)
     :terms (term-store/term-count context)
     :writer-authority (writer-authority-status)
     :torn-tail (:torn-tail co)
     :recovered-tail (:recovered-tail co)
     :surface :occurrence-native/narrow
     :unavailable unavailable-projections
     :unavailable-runtime unavailable-runtime-surfaces
     :unavailable-reason :downstream-termstore-migration-required}))

(defn- handle-native [request]
  (let [co @coordinator]
    (case (:op request)
      :status (status-response)
      :version {:version (coord/current-transaction co)}
      :history {:history (coord/history co)}
      :live {:occurrences (coord/live-occurrences co)
             :propositions (coord/live-propositions co)}
      :occurrence
      {:occurrence (coord/occurrence co (wire->term (:occurrence request)))}

      :commit
      (coord/commit!
       co (merge (request-options request)
                 {:operations (mapv request-operation (:operations request))}))

      :assert
      (coord/assert! co (wire->term (:triple request)) (request-options request))

      :retract
      (coord/retract! co (wire->term (:triple request)) (request-options request))

      :withdraw
      (coord/withdraw-occurrence! co (wire->term (:occurrence request))
                                  (request-options request))

      :supersede
      (coord/supersede! co (wire->term (:occurrence request))
                        (wire->term (:triple request)) (request-options request))

      :view-select
      (coord/view-select! co (wire->term (:view request))
                          (wire->term (:occurrence request))
                          (request-options request))

      :view-deselect
      (coord/view-deselect! co (wire->term (:view request))
                            (wire->term (:occurrence request))
                            (request-options request))

      :view
      {:occurrences (coord/view-occurrences co (wire->term (:view request)))}

      :acquire-lease
      (coord/acquire-lease! co (wire->term (:resource request))
                            (wire->term (:holder request)) (:ttl-ms request)
                            (or (:now-ms request) (System/currentTimeMillis)))

      :renew-lease
      (coord/renew-lease! co (wire->term (:resource request))
                          (wire->term (:holder request))
                          (wire->term (:epoch request)) (:ttl-ms request)
                          (or (:now-ms request) (System/currentTimeMillis)))

      :release-lease
      (coord/release-lease! co (wire->term (:resource request))
                            (wire->term (:holder request))
                            (wire->term (:epoch request)))

      :lease-valid
      {:valid (coord/lease-fence-valid? co (wire->term (:resource request))
                                        (wire->term (:holder request))
                                        (wire->term (:epoch request))
                                        (or (:now-ms request)
                                            (System/currentTimeMillis)))}

      (daemon-fail! :unsupported-termstore-operation
                    "operation is unavailable on the occurrence-native daemon"
                    {:operation (:op request)
                     :unavailable unavailable-projections}))))

(defn handle
  "Handle one wire request. :for-log fences the corpus before authority checks."
  [request]
  (try
    (when-not @coordinator
      (daemon-fail! :coordinator-not-booted "coordinator has not booted" {}))
    (refresh-standby!)
    (let [response
          (if (= :for-log (:op request))
            (let [expected (canonical-path (:expected-log request))
                  actual (:log @coordinator)]
              (if-not (= expected actual)
                {:code :log-mismatch :expected expected :actual actual}
                (handle (:request request))))
            (if (and (mutation? (:op request)) (not (write-authorized?)))
              {:code :writer-authority-required
               :writer-authority (writer-authority-status)}
              (handle-native request)))]
      (response->wire response))
    (catch clojure.lang.ExceptionInfo error
      (let [data (ex-data error)
            code (or (:fram/code data) (:type data) (:code data) :request-failed)]
        (response->wire
         {:code code :error (.getMessage error)
          :data (dissoc data :fram/code :type :code)})))
    (catch Throwable error
      {:code :request-failed :error (.getMessage error)})))

(defn- serve-connection! [^Socket socket]
  (with-open [socket socket
              reader (BufferedReader.
                      (InputStreamReader. (.getInputStream socket)
                                          java.nio.charset.StandardCharsets/UTF_8))
              writer (BufferedWriter.
                      (OutputStreamWriter. (.getOutputStream socket)
                                           java.nio.charset.StandardCharsets/UTF_8))]
    (when-let [line (.readLine reader)]
      (let [request (edn/read-string line)
            response (handle request)]
        (.write writer (str (pr-str response) "\n"))
        (.flush writer)))))

(defn serve!
  "Serve one-line EDN requests over loopback. The active process holds writer
   authority for the full listener lifetime; a standby refreshes before reads."
  [port path expected-space role]
  (boot! path expected-space role)
  (let [server (ServerSocket. (int port) 128
                              (java.net.InetAddress/getByName "127.0.0.1"))]
    (reset! listener server)
    (println (str "TermStore coordinator listening on 127.0.0.1:" port
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
