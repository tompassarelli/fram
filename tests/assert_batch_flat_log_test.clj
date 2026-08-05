;; Regression for :assert-batch flat-log records carrying the fact value's
;; shadowed `r` instead of the batch transaction result.
;;
;; Run from the Fram root:
;;   bb -cp out tests/assert_batch_flat_log_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

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

(defn eventually-value [f]
  (loop [remaining 400]
    (let [value (try (f) (catch Throwable _ nil))]
      (cond
        value value
        (zero? remaining) nil
        :else (do (Thread/sleep 25) (recur (dec remaining)))))))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-assert-batch-flat-log"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log "")
      daemon (proc/process
              {:dir root :out :string :err :string}
              "bb" "-cp" "out" "server.clj" "serve-flat"
              (str port) (.getPath log))
      subject "@assert-batch-flat-log"
      shadow-derived-fact-value "shadow-derived-fact-value"
      facts [{:p "alpha" :r "ordinary-fact-value"}
             {:p "beta" :r {:ok shadow-derived-fact-value}}]]
  (try
    (when-not (eventually-value
               #(when (integer? (:version (client port {:op :version}))) true))
      (proc/destroy-tree daemon)
      (let [result (try @daemon (catch Throwable _ nil))]
        (throw (ex-info "daemon did not become ready" {:stderr (:err result)}))))

    (let [response (client port {:op :assert-batch :te subject :facts facts})
          batch-tx (:ok response)
          predicates (set (map :p facts))
          records
          (eventually-value
           #(let [matching
                  (->> (str/split-lines (slurp log))
                       (map edn/read-string)
                       (filter (fn [record]
                                 (and (= "assert" (:op record))
                                      (= subject (:l record))
                                      (contains? predicates (:p record)))))
                       vec)]
              (when (= (count facts) (count matching)) matching)))
          flat-txs (mapv :tx records)
          ok? (and (integer? batch-tx)
                   (:batch response)
                   (= predicates (set (map :p records)))
                   (every? #(= batch-tx %) flat-txs)
                   (every? some? flat-txs)
                   (not-any? #{shadow-derived-fact-value} flat-txs))]
      (println (pr-str {:batch-tx batch-tx
                        :flat-txs flat-txs
                        :records records}))
      (if ok?
        (println "assert-batch flat-log records carry the batch transaction — PASS")
        (throw
         (ex-info "assert-batch flat-log transaction mismatch"
                  {:response response
                   :expected batch-tx
                   :actual flat-txs}))))
    (finally
      (proc/destroy-tree daemon)
      (try @daemon (catch Throwable _ nil)))))
