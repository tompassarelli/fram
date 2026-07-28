;; coord_writer_authority_test.clj — blue/green writer-authority prerequisite.
(require '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.process :as process])
(load-file "coord_writer_authority.clj")

(when (= "hold" (first *command-line-args*))
  (let [[_ log ready] *command-line-args*
        handle (coord-writer-authority/acquire! log)]
    (try
      (spit ready "ready\n")
      (Thread/sleep 30000)
      (finally
        (coord-writer-authority/release! handle))))
  (System/exit 0))

(def failures (atom []))
(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok (swap! failures conj label)))

(let [dir (.toFile (java.nio.file.Files/createTempDirectory
                    "fram-writer-authority"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
      log (str (io/file dir "coordination.log"))]
  (spit log "")
  (check! "nil role preserves active compatibility"
          (= :active (coord-writer-authority/role-from nil)))
  (check! "standby role is explicit"
          (= :standby (coord-writer-authority/role-from "standby")))
  (check! "unknown role fails closed"
          (= :invalid-coordinator-role
             (try
               (coord-writer-authority/role-from "writer-ish")
               nil
               (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
  (let [first (coord-writer-authority/acquire! log)]
    (try
      (check! "first generation holds writer authority"
              (coord-writer-authority/held? first))
      (check! "overlapping generation cannot acquire the same log"
              (nil? (coord-writer-authority/try-acquire! log)))
      (check! "authority is named by canonical log, not deployment"
              (= (str (.getCanonicalPath (io/file log))
                      ".writer-authority.lock")
                 (:path first)))
      (finally
        (coord-writer-authority/release! first))))
  (let [successor (coord-writer-authority/acquire! log)]
    (try
      (check! "successor acquires only after predecessor releases"
              (coord-writer-authority/held? successor))
      (finally
        (coord-writer-authority/release! successor))))
  (check! "released authority is no longer valid"
          (let [h (coord-writer-authority/acquire! log)]
            (coord-writer-authority/release! h)
            (not (coord-writer-authority/held? h))))

  (let [cross-log (str (io/file dir "telemetry.log"))
        ready (str (io/file dir "child.ready"))
        child (process/process
               ["bb" "-cp" "out:."
                "tests/coord_writer_authority_test.clj"
                "hold" cross-log ready]
               {:out :inherit :err :inherit})]
    (spit cross-log "")
    (loop [n 0]
      (cond
        (.exists (io/file ready)) nil
        (>= n 100) (throw (ex-info "child lock holder did not become ready" {}))
        :else (do (Thread/sleep 20) (recur (inc n)))))
    (try
      (check! "kernel lock excludes a separate coordinator process"
              (nil? (coord-writer-authority/try-acquire! cross-log)))
      (finally
        (process/destroy-tree child)
        @child))
    (let [successor (coord-writer-authority/acquire! cross-log)]
      (try
        (check! "kernel releases authority when predecessor process exits"
                (coord-writer-authority/held? successor))
        (finally
          (coord-writer-authority/release! successor))))))

;; Host integration: a standby can serve reads but every canonical mutation
;; class is rejected before it reaches a log or checkpoint writer.
(load-file "coord_daemon.clj")
(let [dir (.toFile (java.nio.file.Files/createTempDirectory
                    "fram-standby"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
      log (str (io/file dir "coordination.log"))]
  (spit log "")
  (coord-daemon/boot-flat! log)
  (with-redefs [coord-daemon/coordinator-role :standby]
    (check! "standby serves warm reads"
            (integer? (:version (coord-daemon/handle {:op :version}))))
    (check! "standby status advertises read-only authority"
            (let [a (:writer-authority
                     (coord-daemon/handle {:op :status}))]
              (and (= :standby (:role a))
                   (false? (:write-authorized a)))))
    (doseq [[label req]
            [["fact mutation" {:op :assert :te "@T" :p "title" :r "x"}]
             ["lease mutation" {:op :acquire-lease :res "r" :holder "h"
                                :ttl-ms 1000}]
             ["text bridge mutation" {:op :write-def :spec {}}]
             ["graph edit mutation" {:op :edit-min :spec {}}]
             ["candidate commit mutation" {:op :edit-commit :candidate {}}]
             ["checkpoint mutation" {:op :snapshot}]]]
      (check! (str "standby rejects " label)
              (= :writer-authority-required
                 (:code (coord-daemon/handle req)))))
    (check! "fenced standby mutation validates corpus before authority"
            (= :log-mismatch
               (:code
                (coord-daemon/handle
                 {:op :for-log
                  :expected-log (str (io/file dir "other.log"))
                  :request {:op :assert :te "@T" :p "title" :r "x"}}))))
    (check! "correctly-fenced standby mutation remains read-only"
            (= :writer-authority-required
               (:code
                (coord-daemon/handle
                 {:op :for-log
                  :expected-log log
                  :request {:op :assert :te "@T" :p "title" :r "x"}}))))))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn request! [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket
              (java.net.InetSocketAddress. "127.0.0.1" (int port))
              500)
    (.setSoTimeout socket 3000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader.
                        (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer)
      (edn/read reader))))

(defn eventually [f]
  (loop [n 0]
    (let [value (try (f) (catch Throwable _ nil))]
      (cond
        value value
        (>= n 200) nil
        :else (do (Thread/sleep 25) (recur (inc n)))))))

;; Real overlapping processes: one active writer, one read-only warm standby,
;; and a refused second active generation over the same canonical log.
(let [dir (.toFile (java.nio.file.Files/createTempDirectory
                    "fram-dual-generation"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
      log (str (io/file dir "coordination.log"))
      active-port (free-port)
      duplicate-port (free-port)
      standby-port (free-port)
      base-env {"FRAM_SNAPSHOT_BOOT" "0"}
      active
      (process/process
       ["bb" "-cp" "out:." "coord_daemon.clj" "serve-flat"
        (str active-port) log]
       {:out :string :err :string
        :extra-env (assoc base-env "FRAM_COORD_ROLE" "active")})]
  (spit log "")
  (try
    (check! "active generation starts with writer authority"
            (some?
             (eventually
              #(let [a (:writer-authority (request! active-port {:op :status}))]
                 (when (:write-authorized a) a)))))
    (let [duplicate
          (process/process
           ["bb" "-cp" "out:." "coord_daemon.clj" "serve-flat"
            (str duplicate-port) log]
           {:out :string :err :string
            :extra-env (assoc base-env "FRAM_COORD_ROLE" "active")})
          exited? (.waitFor ^Process (:proc duplicate)
                            5 java.util.concurrent.TimeUnit/SECONDS)
          result (when exited? @duplicate)]
      (when-not exited? (process/destroy-tree duplicate))
      (check! "second active generation fails closed before serving"
              (and exited?
                   (not (zero? (:exit result)))
                   (str/includes? (str (:out result) (:err result))
                                  "holds writer authority"))))
    (let [standby
          (process/process
           ["bb" "-cp" "out:." "coord_daemon.clj" "serve-flat"
            (str standby-port) log]
           {:out :string :err :string
            :extra-env (assoc base-env "FRAM_COORD_ROLE" "standby")})]
      (try
        (check! "standby overlaps active on a private endpoint"
                (some?
                 (eventually
                  #(let [a (:writer-authority
                            (request! standby-port {:op :status}))]
                     (when (and (= :standby (:role a))
                                (false? (:write-authorized a)))
                       a)))))
        (check! "active remains writable while standby warms"
                (:ok (request! active-port
                               {:op :assert :te "@blue-green"
                                :p "title" :r "visible"})))
        (check! "standby endpoint refuses the same write"
                (= :writer-authority-required
                   (:code
                    (request! standby-port
                              {:op :assert :te "@blue-green"
                               :p "title" :r "forbidden"}))))
        (check! "standby catches up to the active append"
                (some?
                 (eventually
                  #(let [rows (:rows
                               (request! standby-port
                                         {:op :show :te "@blue-green"}))]
                     (when (some #{["title" "visible"]} rows) rows)))))
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
  (println "\nwriter-authority: all checks passed"))
