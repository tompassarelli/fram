;; Real JVM coordinator + Babashka shim proof for negotiated Cloudflare replies.
;; Requests remain EDN; only {:fmt :json} changes the response serializer.
(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.net URI Socket InetSocketAddress ServerSocket]
        '[java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
          HttpResponse$BodyHandlers])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(def watchdog
  (future
    (Thread/sleep 60000)
    (binding [*out* *err*]
      (println "cloudflare-json-response: hard timeout after 60s"))
    (System/exit 124)))

(defn free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn eventually [f]
  (loop [remaining 800]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn stop-process! [process]
  (when process
    (try (proc/destroy-tree process) (catch Throwable _ nil))
    (let [java-process ^Process (:proc process)]
      (when-not (.waitFor java-process 5 java.util.concurrent.TimeUnit/SECONDS)
        (.destroyForcibly java-process)
        (.waitFor java-process 5 java.util.concurrent.TimeUnit/SECONDS)))))

(defn coordinator-request [port request]
  (with-open [socket (Socket.)]
    (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 500)
    (.setSoTimeout socket 5000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (io/reader (.getInputStream socket))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer)
      (some-> (.readLine ^java.io.BufferedReader reader)
              edn/read-string))))

(def http-client (HttpClient/newHttpClient))

(defn http-post [port path token accept body]
  (let [builder
        (doto (HttpRequest/newBuilder
               (URI/create (str "http://127.0.0.1:" port path)))
          (.header "content-type" "application/edn")
          (.POST (HttpRequest$BodyPublishers/ofString body)))
        _ (when token (.header builder "authorization" (str "Bearer " token)))
        _ (when accept (.header builder "accept" accept))
        response (.send http-client (.build builder)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :content-type (.orElse (.firstValue (.headers response) "content-type") "")
     :body (.body response)}))

(defn large-query []
  {:find "out"
   :rules
   [{:head {:rel "out" :args [{:var "l"} {:var "r"}]}
     :body [{:rel "triple"
             :args [{:var "l"} "json-test-payload" {:var "r"}]}]}]})

(let [daemon-port (free-port)
      shim-port (free-port)
      dir (fs/create-temp-dir {:prefix "fram-cloudflare-json-"})
      log (io/file (str dir) "facts.log")
      inherited-env (apply dissoc (into {} (System/getenv))
                           ["FRAM_LOG"
                            "FRAM_TELEMETRY_LOG"
                            "NORTH_PORT"
                            "NORTH_TELEMETRY_PARTITION"
                            "NORTH_TELEMETRY_PORT"])
      token "json-response-test-token"
      bulk-version (atom nil)
      daemon (atom nil)
      shim (atom nil)]
  (try
    (let [result @(proc/process
                   {:dir root :out :string :err :string}
                   "node" "tests/cloudflare_worker_client_test.mjs")
          ok? (zero? (:exit result))]
      (when-not ok?
        (println (:out result))
        (binding [*out* *err*] (println (:err result))))
      (check! "Worker client request and decoder contract" ok?))

    (spit log "")

    (reset!
     daemon
     (proc/process
      {:dir root
       :env (assoc inherited-env "FRAM_SNAPSHOT_BOOT" "0")
       :out :inherit
       :err :inherit}
      "bin/fram-daemon" "serve-flat" (str daemon-port) (.getPath log)))
    (check! "real JVM coordinator starts"
            (eventually #(= 0
                            (:version
                             (coordinator-request daemon-port {:op :version})))))
    (let [response
          (coordinator-request
           daemon-port
           {:op :assert-batch
            :te "@bulk"
            :facts (mapv (fn [i] {:p "json-test-payload"
                                  :r (str "value-" i)})
                         (range 1000))})]
      (reset! bulk-version (:ok response))
      (check! "one real atomic batch creates the large response fixture"
              (and (:batch response)
                   (integer? @bulk-version))))

    (reset!
     shim
     (proc/process
      {:dir root
       :env (assoc inherited-env
                   "FRAM_HOST" "127.0.0.1"
                   "FRAM_PORT" (str daemon-port)
                   "SHIM_PORT" (str shim-port)
                   "SHIM_TOKEN" token)
       :out :inherit
       :err :inherit}
      "bb" "deploy/cloudflare/shim.clj"))
    (check! "shim becomes ready through its authenticated EDN path"
            (eventually
             #(let [response
                    (http-post shim-port "/q" token nil
                               (pr-str {:op :version}))]
                (and (= 200 (:status response))
                     (= @bulk-version
                        (:version (edn/read-string (:body response))))))))

    (let [response (http-post shim-port "/q" token nil
                              (pr-str {:op :version}))]
      (check! "default response remains EDN"
              (and (= 200 (:status response))
                   (= "application/edn" (:content-type response))
                   (= {:version @bulk-version}
                      (edn/read-string (:body response))))))

    (let [response (http-post shim-port "/q" token "application/json"
                              (pr-str {:op :version :fmt :json}))]
      (check! "JSON response is generated by the real coordinator and labeled honestly"
              (and (= 200 (:status response))
                   (= "application/json" (:content-type response))
                   (= {:version @bulk-version}
                      (json/parse-string (:body response) true)))))

    (let [response
          (http-post shim-port "/q" token "application/json"
                     (pr-str {:op :query
                              :query (large-query)
                              :fmt :json}))
          payload (json/parse-string (:body response) true)
          ok? (and (= 200 (:status response))
                   (= "application/json" (:content-type response))
                   (= 1000 (count (:ok payload)))
                   (= "index" (:engine payload))
                   (= @bulk-version (:version payload))
                   (some #{["@bulk" "value-999"]} (:ok payload)))]
      (when-not ok?
        (println "large-query diagnostic"
                 (pr-str {:status (:status response)
                          :content-type (:content-type response)
                          :rows (count (:ok payload))
                          :engine (:engine payload)
                          :version (:version payload)
                          :expected-version @bulk-version
                          :sample (take-last 2 (:ok payload))})))
      (check! "raw query returns all 1000 real rows in JSON mode" ok?))

    (let [response (http-post shim-port "/q" "wrong" "application/json"
                              (pr-str {:op :version :fmt :json}))]
      (check! "pre-parse authentication error follows Accept as JSON"
              (and (= 401 (:status response))
                   (= "application/json" (:content-type response))
                   (= {:error "unauthorized"}
                      (json/parse-string (:body response) true)))))

    (let [response (http-post shim-port "/q" token "application/json"
                              (pr-str {:op :assert :fmt :json}))]
      (check! "parsed shim validation error follows :fmt as JSON"
              (and (= 403 (:status response))
                   (= "application/json" (:content-type response))
                   (str/includes?
                    (:error (json/parse-string (:body response) true))
                    "not allowed"))))

    (let [response (http-post shim-port "/q" token nil
                              (pr-str {:op :assert}))]
      (check! "EDN validation errors remain backward compatible"
              (and (= 403 (:status response))
                   (= "application/edn" (:content-type response))
                   (str/includes? (:error (edn/read-string (:body response)))
                                  "not allowed"))))

    (stop-process! @daemon)
    (reset! daemon nil)
    (let [response (http-post shim-port "/q" token "application/json"
                              (pr-str {:op :version :fmt :json}))]
      (check! "upstream failure remains valid JSON rather than mislabeled EDN"
              (and (= 502 (:status response))
                   (= "application/json" (:content-type response))
                   (str/starts-with?
                    (:error (json/parse-string (:body response) true))
                    "coordinator unreachable:"))))

    (finally
      (stop-process! @shim)
      (stop-process! @daemon)
      (future-cancel watchdog)
      (fs/delete-tree dir))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS]" "  [FAIL]") label))
  (if (seq failures)
    (do
      (println "\ncloudflare JSON response:" (count failures) "FAILED")
      (System/exit 1))
    (println "\ncloudflare JSON response:"
             (count @checks) "/" (count @checks) "PASS")))
