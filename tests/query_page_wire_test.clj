;; Real coordinator/client proof for the internal bounded query-page protocol.
;; Run: bb -cp out tests/query_page_wire_test.clj
(require '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[fram.query :as q]
         '[fram.rt :as rt])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(def watchdog
  (future
    (Thread/sleep 60000)
    (binding [*out* *err*]
      (println "query-page-wire: hard timeout after 60s"))
    (System/exit 124)))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn eventually [f]
  (loop [remaining 200]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn stop-process! [process]
  (try (proc/destroy-tree process) (catch Throwable _ nil))
  (let [java-process ^Process (:proc process)]
    (when-not (.waitFor java-process 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly java-process)
      (.waitFor java-process 5 java.util.concurrent.TimeUnit/SECONDS))))

(def page-q
  {:find "page-row"
   :rules [{:head {:rel "page-row" :args [{:var "x"}]}
            :body [{:rel "fact"
                    :args [{:var "x"} "page" {:var "v"}]}]}]})

(defn json-request [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 3000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (io/reader (.getInputStream socket))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer)
      (let [line (.readLine ^java.io.BufferedReader reader)]
        {:line line :response (json/parse-string line true)}))))

(defn start-one-shot-server [response]
  (let [server (java.net.ServerSocket. 0)
        worker
        (future
          (try
            (with-open [socket (.accept server)
                        reader (io/reader (.getInputStream socket))
                        writer (io/writer (.getOutputStream socket))]
              (.readLine ^java.io.BufferedReader reader)
              (.write writer response)
              (.write writer "\n")
              (.flush writer))
            (finally
              (.close server))))]
    {:port (.getLocalPort server) :worker worker}))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-query-page"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log "")
      daemon (proc/process
              {:dir root :out :string :err :string}
              "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
              (str port) (.getPath log))]
  (try
    (check! "query-page wire and client publish the exact same byte limit"
            (= q/max-page-wire-bytes
               rt/query-page-response-byte-limit))
    (check! "real coordinator starts"
            (eventually #(integer?
                          (:version
                           (rt/coord-query-page-for-log
                            port (.getPath log) page-q 1 nil)))))

    (doseq [subject ["@z" "@a" "@m" "@🐢" "@10" "@2"]]
      (let [result
            (rt/coord-assert-for-log
             port (.getPath log) subject "page" "yes"
             (rt/coord-version-for-log port (.getPath log)))]
        (check! (str "assert test row " subject)
                (.startsWith ^String result "ok:"))))

    (let [first-page
          (rt/coord-query-page-for-log
           port (.getPath log) page-q 2 nil)
          second-page
          (rt/coord-query-page-for-log
           port (.getPath log) page-q 2 (:next first-page))
          remaining
          (rt/coord-query-page-for-log
           port (.getPath log) page-q 10 (:next second-page))
          all-rows (vec (concat (:ok first-page)
                                (:ok second-page)
                                (:ok remaining)))
          expected
          (vec (sort-by pr-str
                        [["@z"] ["@a"] ["@m"] ["@🐢"] ["@10"] ["@2"]]))]
      (check! "real coordinator pages drain exact canonical relation"
              (= expected all-rows))
      (check! "query-page is always canonical scan, with snapshot version"
              (every?
               #(and (= "scan" (:engine %))
                     (integer? (:version %)))
               [first-page second-page remaining]))
      (check! "each stamped EDN response remains under the hard wire bound"
              (every?
               #(<= (count (.getBytes (pr-str %) "UTF-8"))
                    q/max-page-wire-bytes)
               [first-page second-page remaining]))
      (check! "terminal stamped page has no continuation"
              (and (not (:more remaining))
                   (nil? (:next remaining)))))

    (let [request
          {:op :for-log
           :expected-log (.getCanonicalPath log)
           :fmt :json
           :request {:op :query-page
                     :query page-q
                     :limit 3
                     :after nil}}
          {:keys [line response]} (json-request port request)]
      (check! "opt-in JSON returns the same bounded query-page contract"
              (and (= "scan" (:engine response))
                   (= 3 (count (:ok response)))
                   (:more response)
                   (string? (:next response))))
      (check! "stamped JSON response remains under the hard wire bound"
              (<= (count (.getBytes ^String line "UTF-8"))
                  q/max-page-wire-bytes)))

    (let [before
          (rt/coord-query-page-for-log
           port (.getPath log) page-q 1 nil)
          boundary (ffirst (:ok before))
          _ (rt/coord-retract-for-log
             port (.getPath log) boundary "page" "yes"
             (rt/coord-version-for-log port (.getPath log)))
          _ (rt/coord-assert-for-log
             port (.getPath log) "@0" "page" "yes"
             (rt/coord-version-for-log port (.getPath log)))
          _ (rt/coord-assert-for-log
             port (.getPath log) "@11" "page" "yes"
             (rt/coord-version-for-log port (.getPath log)))
          after
          (rt/coord-query-page-for-log
           port (.getPath log) page-q 10 (:next before))]
      (check! "real cursor survives deletion of its boundary row"
              (not (some #{[boundary]} (:ok after))))
      (check! "real cursor includes later insertions and excludes earlier ones"
              (and (some #{["@11"]} (:ok after))
                   (not (some #{["@0"]} (:ok after))))))

    (check! "invalid page limit is a bounded protocol error"
            (:error
             (rt/coord-query-page-for-log
              port (.getPath log) page-q 0 nil)))
    (let [other-log (io/file dir "other.log")
          _ (spit other-log "")]
      (check! "log-fenced query page cannot read another corpus"
              (nil?
               (rt/coord-query-page-for-log
                port (.getPath other-log) page-q 2 nil))))

    (finally
      (stop-process! daemon)
      (future-cancel watchdog))))

;; The page client lowers the general 64MiB coordinator response ceiling to the
;; page protocol's 1MiB contract. A hostile/buggy peer cannot make it buffer more.
(let [{:keys [port worker]}
      (start-one-shot-server
       (apply str (repeat (inc q/max-page-wire-bytes) "x")))]
  (check! "query-page client rejects a response one byte over its own bound"
          (nil? (rt/coord-query-page port page-q 1 nil)))
  @worker)

;; Capability handshake remains explicit against a pre-page coordinator.
(let [{:keys [port worker]}
      (start-one-shot-server (pr-str {:error "unknown op"}))]
  (check! "query-page client returns nil against an older coordinator"
          (nil? (rt/coord-query-page port page-q 1 nil)))
  @worker)

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do
      (println "\nquery-page-wire:" (count failures) "FAILED")
      (System/exit 1))
    (println "\nquery-page-wire:" (count @checks) "/" (count @checks) "PASS")))
