#!/usr/bin/env bb
(require '[babashka.process :as proc]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root
  (.getCanonicalPath
   (io/file (.getParent (io/file (System/getProperty "babashka.file"))) "..")))
(def tmp (.toFile (java.nio.file.Files/createTempDirectory "fram-client-wire-"
                                                            (make-array java.nio.file.attribute.FileAttribute 0))))
(def test-log
  (.getCanonicalPath
   (doto (io/file tmp "facts.log")
     (spit ""))))
(def checks (atom []))

(defn check! [label value]
  (swap! checks conj [label (boolean value)]))

(def child-form
  (str
   "(require '[fram.rt :as rt]) "
   "(let [port (Integer/parseInt (System/getenv \"FRAM_TEST_PORT\")) "
   "log (System/getenv \"FRAM_TEST_LOG\") "
   "mode (System/getenv \"FRAM_TEST_MODE\")] "
   "(case mode "
   "\"edn\" (prn (rt/coord-request-for-log port log {:op :version})) "
   "\"edn-size\" (println (count (:payload "
   "(rt/coord-request-for-log port log {:op :version})))) "
   "\"live-size\" (let [facts (rt/coord-live-facts port log)] "
   "(println (if-let [fact (first facts)] (count (:r fact)) 0))) "
   "\"live-count\" (println (count (rt/coord-live-facts port log))) "
   "\"watch\" (rt/coord-watch-for-log port log) "
   "\"tls\" (with-open [socket (.createSocket "
   "(.getSocketFactory (javax.net.ssl.SSLContext/getDefault)))] "
   "(.connect socket (java.net.InetSocketAddress. \"127.0.0.1\" port) 1000) "
   "(.setSoTimeout socket "
   "(Integer/parseInt (System/getenv \"FRAM_COORD_READ_TIMEOUT_MS\"))) "
   "((deref (var fram.rt/coord-tls-handshake!)) socket)) "
   "\"tls-init-close\" "
   "(let [captured (atom nil) "
   "connect! (deref (var fram.rt/coord-socket)) "
   "context (javax.net.ssl.SSLContext/getDefault)] "
   "(try "
   "(with-redefs-fn "
   "{(var fram.rt/client-ssl-context) (fn [& _] context) "
   "(var fram.rt/coord-tls-handshake!) "
   "(fn [socket] "
   "(reset! captured socket) "
   "(throw (ex-info \"synthetic handshake failure\" {})))} "
   "#(connect! \"127.0.0.1\" port)) "
   "(catch Throwable _ nil)) "
   "(println (boolean (and @captured (.isClosed @captured))))) "
   "\"socket-fd-stability\" "
   "(let [fds (fn [] (count (.listFiles (java.io.File. \"/proc/self/fd\")))) "
   "before (fds) "
   "connect! (deref (var fram.rt/coord-socket))] "
   "(dotimes [_ 100] "
   "(try (connect! \"127.0.0.1\" port) (catch Throwable _ nil))) "
   "(println (- (fds) before))) "
   "\"watchdog-stability\" "
   "(let [sockets (vec (repeatedly 200 #(java.net.Socket.))) "
   "run! (deref (var fram.rt/run-with-coord-watchdog!))] "
   "(doseq [socket sockets] "
   "(run! socket 25 \"expired\" :expired (fn [] :ok))) "
   "(Thread/sleep 50) "
   "(println (count (filter #(.isClosed %) sockets))) "
   "(doseq [socket sockets] (.close socket)))))"))

(defn child
  ([port mode] (child port mode {}))
  ([port mode extra-env]
   (proc/shell
    {:continue true
     :out :string
     :err :string
     :extra-env
     (merge
      {"FRAM_TEST_PORT" (str port)
       "FRAM_TEST_LOG" test-log
       "FRAM_TEST_MODE" mode
       "FRAM_COORD_CONNECT_TIMEOUT_MS" "500"
       "FRAM_COORD_READ_TIMEOUT_MS" "150"}
      extra-env)}
    "bb" "-cp" (str root "/out") "-e" child-form)))

(defn request-line! [socket]
  (.readLine
   (java.io.BufferedReader.
    (java.io.InputStreamReader.
     (.getInputStream socket)
     java.nio.charset.StandardCharsets/UTF_8))))

(defn write-bytes! [socket bytes]
  (let [output (.getOutputStream socket)]
    (.write output bytes)
    (.flush output)))

(defn write-text! [socket text]
  (write-bytes!
   socket
   (.getBytes text java.nio.charset.StandardCharsets/UTF_8)))

(defn run-peer
  ([mode handler] (run-peer mode {} handler))
  ([mode extra-env handler]
   (let [server (java.net.ServerSocket. 0)
         worker
         (future
           (try
             (with-open [socket (.accept server)]
               (handler socket))
             (catch Throwable _ nil)))
         started (System/nanoTime)
         result (child (.getLocalPort server) mode extra-env)
         elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
     (.close server)
     (future-cancel worker)
     (try (deref worker 1000 nil) (catch Throwable _ nil))
     {:result result :elapsed-ms elapsed-ms})))

(defn failed-with? [result fragment]
  (and (not (zero? (:exit result)))
       (str/includes? (:err result) fragment)))

(let [payload-size (* 512 1024)
      payload (apply str (repeat payload-size "x"))
      {:keys [result elapsed-ms]}
      (run-peer
       "edn-size"
       {"FRAM_COORD_READ_TIMEOUT_MS" "3000"
        "FRAM_COORD_MAX_RESPONSE_BYTES" "1048576"}
       (fn [socket]
         (request-line! socket)
         (write-text! socket (str "{:payload " (pr-str payload) "}\n"))))]
  (println
   (format "  [METRIC] 512 KiB terminal EDN response parsed in %.1fms"
           elapsed-ms))
  (check! "chunked terminal reader handles a normal 512 KiB EDN response"
          (and (zero? (:exit result))
               (= (str payload-size) (str/trim (:out result)))
               (< elapsed-ms 3000.0))))

;; The production North corpus was already 7.77 MB when this boundary landed.
;; Prove the default has growth headroom by crossing 8 MiB through the real JSON
;; live-facts path, rather than locking the current corpus against its ceiling.
(let [payload-size (* 9 1024 1024)
      payload (apply str (repeat payload-size "x"))
      response
      (str "{\"log\":" (pr-str test-log)
           ",\"facts\":[[\"@wire\",\"note\"," (pr-str payload) "]]}\n")
      {:keys [result elapsed-ms]}
      (run-peer
       "live-size"
       {"FRAM_COORD_FACTS_TIMEOUT_MS" "10000"
        "FRAM_COORD_READ_TIMEOUT_MS" "invalid"}
       (fn [socket]
         (request-line! socket)
         (write-text! socket response)))]
  (println
   (format "  [METRIC] 9 MiB terminal JSON response parsed in %.1fms"
           elapsed-ms))
  (check! "default response cap leaves measured corpus growth headroom"
          (and (zero? (:exit result))
               (= (str payload-size) (str/trim (:out result)))
               (< elapsed-ms 10000.0)))
  (check! "facts timeout is independent of the small-response timeout"
          (and (zero? (:exit result))
               (= (str payload-size) (str/trim (:out result))))))

(let [{:keys [result elapsed-ms]}
      (run-peer
       "live-count"
       {"FRAM_COORD_READ_TIMEOUT_MS" "5000"
        "FRAM_COORD_FACTS_TIMEOUT_MS" "150"}
       (fn [socket]
         (request-line! socket)
         (let [output (.getOutputStream socket)]
           (try
             (dotimes [_ 20]
               (.write output
                       (.getBytes " "
                                  java.nio.charset.StandardCharsets/UTF_8))
               (.flush output)
               (Thread/sleep 40))
             (catch Throwable _ nil)))))]
  (check! "facts drip cannot extend its separate absolute deadline"
          (and (zero? (:exit result))
               (= "0" (str/trim (:out result)))
               (>= elapsed-ms 100.0)
               (< elapsed-ms 2000.0))))

(let [result
      (child
       1
       "live-count"
       {"FRAM_COORD_FACTS_TIMEOUT_MS" "0"})]
  (check! "facts deadline configuration is range-validated"
          (failed-with?
           result
           "FRAM_COORD_FACTS_TIMEOUT_MS must be an integer from 1 through 999999")))

(let [server (java.net.ServerSocket. 0)
      accepted (promise)
      worker
      (future
        (with-open [socket (.accept server)]
          (deliver accepted true)
          (Thread/sleep 5000)))
      started (System/nanoTime)
      result (child (.getLocalPort server) "edn")
      elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
  (.close server)
  (future-cancel worker)
  (check! "silent accepted peer reaches the absolute response deadline"
          (and (true? (deref accepted 1000 false))
               (failed-with? result "coordinator response deadline exceeded")
               (>= elapsed-ms 100.0)
               (< elapsed-ms 2000.0))))

(let [{:keys [result elapsed-ms]}
      (run-peer
       "edn"
       (fn [socket]
         (request-line! socket)
         (let [output (.getOutputStream socket)]
           (try
             (dotimes [_ 20]
               (.write output
                       (.getBytes " "
                                  java.nio.charset.StandardCharsets/UTF_8))
               (.flush output)
               (Thread/sleep 40))
             (catch Throwable _ nil)))))]
  (check! "drip peer cannot extend the absolute response deadline"
          (and (failed-with? result "coordinator response deadline exceeded")
               (>= elapsed-ms 100.0)
               (< elapsed-ms 2000.0))))

(let [{:keys [result]}
      (run-peer
       "edn"
       {"FRAM_COORD_MAX_RESPONSE_BYTES" "64"}
       (fn [socket]
         (request-line! socket)
         (write-text! socket (str (apply str (repeat 65 "x")) "\n"))))]
  (check! "oversized response is rejected before parsing"
          (failed-with? result "coordinator response line exceeds 64 bytes")))

(let [{:keys [result]}
      (run-peer
       "edn"
       (fn [socket]
         (request-line! socket)
         (write-bytes!
          socket
          (byte-array [(unchecked-byte 0xC3)
                       (unchecked-byte 0x28)
                       (unchecked-byte 0x0A)]))))]
  (check! "malformed UTF-8 response is rejected deterministically"
          (failed-with? result "coordinator response line is not valid UTF-8")))

(doseq [[label response expected]
        [["malformed EDN response is rejected"
          "{:version\n"
          "coordinator response line is not exactly one valid EDN form"]
         ["two EDN values in one line are rejected"
          "{:version 1} {:version 2}\n"
          "coordinator response line is not exactly one valid EDN form"]
         ["two terminal response lines are rejected"
          "{:version 1}\n{:version 2}\n"
          "coordinator sent more than one terminal response frame"]]]
  (let [{:keys [result]}
        (run-peer
         "edn"
         (fn [socket]
           (request-line! socket)
           (write-text! socket response)))]
    (check! label (failed-with? result expected))))

(let [{:keys [result elapsed-ms]}
      (run-peer
       "edn"
       (fn [socket]
         (request-line! socket)
         (write-text! socket "{:version 1}\n")
         (Thread/sleep 1000)))]
  (check! "terminal response requires peer EOF inside the same deadline"
          (and (failed-with? result "coordinator response deadline exceeded")
               (>= elapsed-ms 100.0)
               (< elapsed-ms 2000.0))))

(let [valid
      (str "{\"log\":" (pr-str test-log)
           ",\"facts\":[[\"@wire\",\"note\",\"must-not-be-accepted\"]]} {} \n")
      {:keys [result]}
      (run-peer
       "live-count"
       (fn [socket]
         (request-line! socket)
         (write-text! socket valid)))]
  (check! "JSON response contains exactly one root value"
          (and (zero? (:exit result))
               (= "0" (str/trim (:out result))))))

;; Send handshake + first event in one kernel write. A reader recreated after the
;; handshake would discard the already-buffered event and this child would print
;; only the acknowledgement.
(let [handshake (pr-str {:subscribed 7 :log test-log})
      event (pr-str {:event :commit :seq 8})
      {:keys [result]}
      (run-peer
       "watch"
       (fn [socket]
         (request-line! socket)
         (write-text! socket (str handshake "\n" event "\n"))))]
  (check! "subscription reader preserves surplus bytes after the handshake"
          (and (zero? (:exit result))
               (= [handshake event] (str/split-lines (:out result))))))

(let [handshake (pr-str {:subscribed 7 :log test-log})
      {:keys [result]}
      (run-peer
       "watch"
       {"FRAM_COORD_MAX_RESPONSE_BYTES" "64"}
       (fn [socket]
         (request-line! socket)
         (write-text! socket
                      (str handshake "\n" (apply str (repeat 65 "x"))))))]
  (check! "post-handshake event stream remains byte-capped"
          (failed-with? result "coordinator response line exceeds 64 bytes")))

;; Feed a syntactically plausible TLS record header declaring a 16 KiB record,
;; then drip its body. The small-response timeout is deliberately shorter than
;; the drip interval; only the handshake's own timeout may govern this phase.
(let [{:keys [result elapsed-ms]}
      (run-peer
       "tls"
       {"FRAM_COORD_HANDSHAKE_TIMEOUT_MS" "150"
        "FRAM_COORD_READ_TIMEOUT_MS" "25"}
       (fn [socket]
         (let [input (.getInputStream socket)
               hello (byte-array 4096)]
           (.read input hello)
           (let [output (.getOutputStream socket)]
             (.write output
                     (byte-array [(unchecked-byte 0x16)
                                  (unchecked-byte 0x03)
                                  (unchecked-byte 0x03)
                                  (unchecked-byte 0x40)
                                  (unchecked-byte 0x00)]))
             (.flush output)
             (try
               (dotimes [_ 20]
                 (.write output (byte-array [(unchecked-byte 0x00)]))
                 (.flush output)
                 (Thread/sleep 40))
               (catch Throwable _ nil))))))]
  (check! "TLS handshake drip uses its own absolute timeout"
          (and (failed-with? result "coordinator TLS handshake deadline exceeded")
               (>= elapsed-ms 100.0)
               (< elapsed-ms 2000.0))))

(let [result (child 1 "watchdog-stability")]
  (check! "completed handshakes cannot be closed by a losing watchdog race"
          (and (zero? (:exit result))
               (= "0" (str/trim (:out result))))))

(let [{:keys [result]}
      (run-peer
       "tls-init-close"
       {"FRAM_TLS_KEYSTORE" "synthetic-keystore"
        "FRAM_TLS_TRUSTSTORE" "synthetic-truststore"
        "FRAM_TLS_PASS" "synthetic-password"}
       (fn [_socket]
         (Thread/sleep 500)))]
  (check! "ordinary TLS initialization failure closes the connected socket"
          (and (zero? (:exit result))
               (= "true" (str/trim (:out result))))))

(let [probe (java.net.ServerSocket. 0)
      unused-port (.getLocalPort probe)
      _ (.close probe)
      result (child unused-port "socket-fd-stability")]
  (check! "failed TCP connects do not accumulate descriptors"
          (and (zero? (:exit result))
               (<= (Long/parseLong (str/trim (:out result))) 2))))

(doseq [[label ok?] @checks]
  (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
(let [failed (remove second @checks)]
  (println
   (format "\ncoordinator client wire boundary: %d / %d PASS"
           (- (count @checks) (count failed))
           (count @checks)))
  (.delete (io/file test-log))
  (.delete tmp)
  (System/exit (if (empty? failed) 0 1)))
