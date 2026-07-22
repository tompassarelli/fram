;; Real split-corpus lease durability: every acknowledged lease mutation must
;; survive process replacement in coordination.log, never telemetry.log.
;;
;; Run: bb -cp out tests/coord_lease_flat_durability_test.clj
(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[fram.rt :as rt])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def scratch (str (System/getProperty "java.io.tmpdir")
                  "/fram-lease-flat-durability-" (System/nanoTime)))
(def coordination (str scratch "/coordination.log"))
(def telemetry (str scratch "/telemetry.log"))
(def running (atom nil))
(def checks (atom []))

(defn check! [label value]
  (let [ok? (boolean value)]
    (swap! checks conj [label ok?])
    (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label))
    ok?))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn eventually [f]
  (loop [remaining 240]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn start-daemon! []
  (let [port (free-port)
        child (proc/process
               ["bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
                (str port) coordination]
               {:dir root
                :extra-env {"FRAM_TELEMETRY_LOG" telemetry}
                :out :string :err :string})]
    (when-not (eventually #(integer? (:version (client port {:op :version}))))
      (proc/destroy-tree child)
      (throw (ex-info "split daemon did not become ready" {:port port})))
    (reset! running {:child child :port port})
    port))

(defn stop-daemon! []
  (when-let [{:keys [child]} @running]
    (proc/destroy-tree child)
    (try @child (catch Exception _ nil))
    (reset! running nil)))

(defn file-bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))

(defn byte-counts []
  [(alength (file-bytes coordination))
   (alength (file-bytes telemetry))])

(defn terminal-lf? [path]
  (let [bs (file-bytes path)]
    (and (pos? (alength bs)) (= 10 (bit-and 0xff (aget bs (dec (alength bs))))))))

(defn lease-request [port op resource holder & [epoch]]
  (client port (cond-> {:op op :res resource :holder holder}
                 (= op :acquire-lease) (assoc :ttl-ms 120000)
                 (= op :renew-lease) (assoc :epoch epoch :ttl-ms 120000)
                 (and (= op :release-lease) (some? epoch)) (assoc :epoch epoch))))

(defn fence? [port resource holder epoch]
  (:fence-ok (client port {:op :fence-ok
                           :res resource :holder holder :epoch epoch})))

(fs/create-dirs scratch)
(spit coordination
      (str (pr-str {:tx 1 :op "assert" :l "@seed" :p "title"
                    :r "coordination" :frame "test"}) "\n"))
(spit telemetry
      (str (pr-str {:tx 2 :op "assert" :l "@run-lease-flat" :p "kind"
                    :r "run" :frame "test"}) "\n"))

(try
  (let [resource "corpus:transaction"
        holder "lease-flat-owner"
        port-1 (start-daemon!)
        before-acquire (byte-counts)
        acquired (lease-request port-1 :acquire-lease resource holder)
        after-acquire (byte-counts)
        held (lease-request port-1 :acquire-lease resource "other-owner")
        invalid-renew (lease-request port-1 :renew-lease resource holder 0)
        after-rejects (byte-counts)]
    (check! "accepted acquire appends coordination only before acknowledgement"
            (and (:ok acquired)
                 (> (first after-acquire) (first before-acquire))
                 (= (second after-acquire) (second before-acquire))))
    (check! "both split files retain a terminal LF"
            (and (terminal-lf? coordination) (terminal-lf? telemetry)))
    (check! "held and invalid lease requests append zero bytes"
            (and (= :held (:reject held))
                 (= :invalid-lease-request (:reject invalid-renew))
                 (= after-acquire after-rejects)))

    (stop-daemon!)
    (let [port-2 (start-daemon!)
          acquired-epoch (:epoch acquired)
          restored (fence? port-2 resource holder acquired-epoch)
          before-renew (byte-counts)
          renewed (lease-request port-2 :renew-lease resource holder acquired-epoch)
          after-renew (byte-counts)]
      (check! "kill/restart restores the exact acquired holder and epoch"
              restored)
      (check! "accepted renewal rotates the epoch and appends coordination only"
              (and (:ok renewed)
                   (> (:epoch renewed) acquired-epoch)
                   (> (first after-renew) (first before-renew))
                   (= (second after-renew) (second before-renew))))

      (stop-daemon!)
      (let [port-3 (start-daemon!)
            renewed-epoch (:epoch renewed)
            old-fence-after-restart (fence? port-3 resource holder acquired-epoch)
            renewed-fence-after-restart (fence? port-3 resource holder renewed-epoch)
            before-stale-release (byte-counts)
            stale-release (lease-request port-3 :release-lease resource holder acquired-epoch)
            after-stale-release (byte-counts)
            renewed-fence-after-stale-release
            (fence? port-3 resource holder renewed-epoch)
            before-release after-stale-release
            released (lease-request port-3 :release-lease resource holder renewed-epoch)
            after-release (byte-counts)]
        (check! "restart restores only the renewed fence"
                (and (not old-fence-after-restart)
                     renewed-fence-after-restart))
        (check! "stale epoch release is a byte-stable no-op"
                (and (:noop stale-release)
                     (= before-stale-release after-stale-release)
                     renewed-fence-after-stale-release))
        (check! "exact release appends coordination only"
                (and (:ok released) (not (:noop released))
                     (> (first after-release) (first before-release))
                     (= (second after-release) (second before-release))))

        (stop-daemon!)
        (let [port-4 (start-daemon!)
              before-successor (byte-counts)
              successor (lease-request port-4 :acquire-lease resource holder)
              after-successor (byte-counts)
              stale-successor-release
              (lease-request port-4 :release-lease resource holder renewed-epoch)
              coordination-before-ordinary (first (byte-counts))
              coord-write (client port-4 {:op :assert :te "@seed" :p "note"
                                          :r "still-normal"})
              coordination-after-ordinary (first (byte-counts))
              telemetry-before-ordinary (second (byte-counts))
              telemetry-write
              (client port-4 {:op :assert :te "@run-lease-flat" :p "note"
                              :r "still-routed"})
              telemetry-after-ordinary (second (byte-counts))]
          (check! "released lease stays absent across restart"
                  (not (fence? port-4 resource holder renewed-epoch)))
          (check! "same-holder successor has a fresh durable epoch"
                  (and (:ok successor)
                       (> (:epoch successor) renewed-epoch)
                       (> (first after-successor) (first before-successor))
                       (= (second after-successor) (second before-successor))
                       (:noop stale-successor-release)
                       (fence? port-4 resource holder (:epoch successor))))
          (check! "ordinary split-corpus write routing remains unchanged"
                  (and (:ok coord-write) (:ok telemetry-write)
                       (> coordination-after-ordinary coordination-before-ordinary)
                       (> telemetry-after-ordinary telemetry-before-ordinary)))

          (lease-request port-4 :release-lease resource holder (:epoch successor))
          (stop-daemon!)
          (let [port-5 (start-daemon!)
                ops (rt/read-log coordination)
                schema-cardinality
                (filter #(and (= "@lease" (:l %))
                              (= "cardinality" (:p %))
                              (= "assert" (:op %))) ops)]
            (check! "final exact release remains absent after a second restart"
                    (not (fence? port-5 resource holder (:epoch successor))))
            (check! "lease schema is projected once and remains single-valued"
                    (= 1 (count schema-cardinality)))
            (check! "all durable lease facts stayed out of telemetry"
                    (not-any? #(= "lease" (:p %)) (rt/read-log telemetry)))
            (check! "ordinary writes survive the same process replacements"
                    (and (= #{"still-normal"}
                            (set (:values (client port-5 {:op :resolved
                                                         :te "@seed" :p "note"}))))
                         (= #{"still-routed"}
                            (set (:values (client port-5 {:op :resolved
                                                         :te "@run-lease-flat" :p "note"})))))))))))
  (finally
    (stop-daemon!)
    (fs/delete-tree scratch)))

(let [failed (remove second @checks)]
  (println (format "\ncoord lease flat durability: %d/%d passed"
                   (- (count @checks) (count failed)) (count @checks)))
  (when (seq failed) (System/exit 1)))
