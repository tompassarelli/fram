;; Writer authority and narrow TermStore daemon integration.
;; Daemon subprocesses always use JVM Clojure.
(require '[babashka.process :as process]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(load-file "coord_writer_authority.clj")
(load-file "coord_daemon.clj")

(when (= "hold" (first *command-line-args*))
  (let [[_ log ready] *command-line-args*
        handle (coord-writer-authority/acquire! log)]
    (try
      (spit ready "ready\n")
      (Thread/sleep 30000)
      (finally (coord-writer-authority/release! handle))))
  (System/exit 0))

(def failures (atom []))
(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok (swap! failures conj label)))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))

(defn request! [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket
              (java.net.InetSocketAddress. "127.0.0.1" (int port)) 500)
    (.setSoTimeout socket 3000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader.
                        (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer)
      (edn/read reader))))

(defn eventually [f]
  (loop [attempt 0]
    (let [value (try (f) (catch Throwable _ nil))]
      (cond value value
            (>= attempt 240) nil
            :else (do (Thread/sleep 25) (recur (inc attempt)))))))

(def scratch
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-term-authority-"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(let [log (str (io/file scratch "lock-only.framlog"))]
  (spit log "")
  (check! "nil role preserves active default"
          (= :active (coord-writer-authority/role-from nil)))
  (check! "standby role is explicit"
          (= :standby (coord-writer-authority/role-from "standby")))
  (check! "unknown role fails closed"
          (= :invalid-coordinator-role
             (try (coord-writer-authority/role-from "writer-ish") nil
                  (catch clojure.lang.ExceptionInfo error
                    (:code (ex-data error))))))
  (let [first (coord-writer-authority/acquire! log)]
    (try
      (check! "first generation holds canonical-log authority"
              (coord-writer-authority/held? first))
      (check! "overlapping generation cannot acquire the same log"
              (nil? (coord-writer-authority/try-acquire! log)))
      (finally (coord-writer-authority/release! first))))
  (let [successor (coord-writer-authority/acquire! log)]
    (check! "successor acquires after predecessor release"
            (coord-writer-authority/held? successor))
    (coord-writer-authority/release! successor)))

;; Host-level narrow daemon: authority gates occurrence-native writes before
;; any transaction frame is appended.
(let [log (str (io/file scratch "direct.framlog"))]
  (coord-daemon/boot! log "direct-space" :active)
  (try
    (check! "active narrow daemon advertises TermStore v2 authority"
            (let [status (coord-daemon/handle {:op :status})]
              (and (= :termstore-v2/framlog (:format status))
                   (true? (get-in status [:writer-authority :write-authorized]))
                   (= :occurrence-native/narrow (:surface status)))))
    (let [result (coord-daemon/handle
                  {:op :assert :triple ["A" :email "a@example.com"]
                   :actor "tester"
                   :recorded-at {:instant [100 7]}})]
      (check! "active write returns transaction and occurrence coordinates"
              (and (= ["direct-space" :kernel/tx-sequence 1] (:ok result))
                   (= [[[["direct-space" :kernel/tx-sequence 1]
                          :kernel/op-ordinal 0]
                         :kernel/asserts
                         ["A" :email "a@example.com"]]]
                      (:occurrences result))))
      (check! "wire response contains no physical row handle"
              (not-any? #(and (map? %) (contains? % :cid))
                        (tree-seq coll? seq result))))
    (check! "removed schema/query operation fails explicitly"
            (= :unsupported-termstore-operation
               (:code (coord-daemon/handle {:op :query :query []}))))
    (finally (coord-daemon/shutdown!))))

;; Real overlapping generations: one active JVM writer, one JVM standby, and a
;; refused second active process over the same canonical FRAMLOG.
(let [log (str (io/file scratch "shared.framlog"))
      active-port (free-port)
      duplicate-port (free-port)
      standby-port (free-port)
      active
      (process/process
       ["clojure" "-M" "coord_daemon.clj" "serve"
        (str active-port) log "shared-space"]
       {:out :string :err :string
        :extra-env {"FRAM_COORD_ROLE" "active"
                    "FRAM_TELEMETRY_LOG" nil}})]
  (try
    (check! "active JVM generation starts with writer authority"
            (some? (eventually
                    #(let [status (request! active-port {:op :status})]
                       (when (get-in status [:writer-authority :write-authorized])
                         status)))))
    (let [duplicate
          (process/process
           ["clojure" "-M" "coord_daemon.clj" "serve"
            (str duplicate-port) log "shared-space"]
           {:out :string :err :string
            :extra-env {"FRAM_COORD_ROLE" "active"
                        "FRAM_TELEMETRY_LOG" nil}})
          exited? (.waitFor ^Process (:proc duplicate)
                            6 java.util.concurrent.TimeUnit/SECONDS)
          result (when exited? @duplicate)]
      (when-not exited? (process/destroy-tree duplicate))
      (check! "second active JVM generation fails before serving"
              (and exited? (not (zero? (:exit result)))
                   (str/includes? (str (:out result) (:err result))
                                  "holds writer authority"))))
    (let [standby
          (process/process
           ["clojure" "-M" "coord_daemon.clj" "serve"
            (str standby-port) log "shared-space"]
           {:out :string :err :string
            :extra-env {"FRAM_COORD_ROLE" "standby"
                        "FRAM_TELEMETRY_LOG" nil}})]
      (try
        (check! "standby JVM generation serves read-only"
                (some? (eventually
                        #(let [status (request! standby-port {:op :status})]
                           (when (and (= :standby
                                         (get-in status [:writer-authority :role]))
                                      (false? (get-in status
                                                      [:writer-authority
                                                       :write-authorized])))
                             status)))))
        (let [asserted
              (request! active-port
                        {:op :assert
                         :triple ["blue-green" :status "visible"]})]
          (check! "active JVM generation appends a whole transaction frame"
                  (vector? (:ok asserted))))
        (check! "standby rejects the same occurrence-native mutation"
                (= :writer-authority-required
                   (:code
                    (request! standby-port
                              {:op :assert
                               :triple ["blue-green" :status "forbidden"]}))))
        (check! "standby refresh observes the active generation append"
                (some? (eventually
                        #(let [live (:propositions
                                    (request! standby-port {:op :live}))]
                           (when (some #{["blue-green" :status "visible"]}
                                       live)
                             live)))))
        (check! "for-log mismatch is rejected before authority routing"
                (= :log-mismatch
                   (:code
                    (request! standby-port
                              {:op :for-log
                               :expected-log (str (io/file scratch "other.framlog"))
                               :request {:op :assert
                                         :triple ["A" :p "B"]}}))))
        (finally
          (process/destroy-tree standby)
          @standby)))
    (finally
      (process/destroy-tree active)
      @active)))

(if (seq @failures)
  (do
    (println (str "\n" (count @failures) " writer-authority checks failed"))
    (System/exit 1))
  (println "\nTermStore writer authority: all checks passed"))
