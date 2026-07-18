;; Real-socket corpus identity proof.
;;
;; A daemon serving log A must reject every log-aware read/write from a client
;; expecting log B. The expected-log check and mutation share the coordinator
;; lock; the distinct :for-log envelope also makes a new client fail closed
;; against an older daemon that does not know the operation.
;;
;; Run: bb -cp out tests/coord_expected_log_test.clj
(require '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.rt :as rt])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def watchdog
  (future
    (Thread/sleep 60000)
    (binding [*out* *err*]
      (println "coord-expected-log: hard timeout after 60s"))
    (System/exit 124)))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn client [port request]
  (with-open [socket (java.net.Socket.)]
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 3000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader.
                        (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str request) "\n"))
      (.flush writer)
      (edn/read reader))))

(defn for-log [log request]
  {:op :for-log :expected-log log :request request})

(defn eventually [f]
  (loop [remaining 200]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn run-cli [env & args]
  (apply proc/shell
         {:dir root :out :string :err :string :extra-env env}
         "bin/fram"
         args))

(defn run-mcp [env request]
  (let [result (proc/shell
                {:dir root
                 :in (str (json/generate-string request) "\n")
                 :out :string
                 :err :string
                 :extra-env env}
                "bin/fram-mcp")
        line (first (remove str/blank? (str/split-lines (:out result))))]
    (when line (json/parse-string line true))))

(defn run-command [env command & args]
  (apply proc/shell
         {:dir root :out :string :err :string
          :continue true :extra-env env}
         command
         args))

(defn start-fake-old-daemon [seen]
  ;; Bind synchronously on port 0 before publishing the port. The old version
  ;; chose a free port, then bound inside a future: a fast client could lose the
  ;; race, while the future waited forever in accept and the test hung on @server.
  (let [server (java.net.ServerSocket. 0)
        _ (.setSoTimeout server 5000)
        worker
        (future
          (try
            (with-open [socket (.accept server)
                        reader (java.io.PushbackReader.
                                (io/reader (.getInputStream socket)))
                        writer (io/writer (.getOutputStream socket))]
              (.setSoTimeout socket 3000)
              (let [request (edn/read reader)]
                (reset! seen request)
                (.write writer (str (pr-str {:error "unknown op"}) "\n"))
                (.flush writer)
                :done))
            (catch Throwable t
              {:error (or (.getMessage t) (.getSimpleName (class t)))})
            (finally
              (try (.close server) (catch Throwable _ nil)))))]
    {:port (.getLocalPort server) :server server :worker worker}))

(defn stop-process! [process]
  (try (proc/destroy-tree process) (catch Throwable _ nil))
  (let [java-process ^Process (:proc process)]
    (when-not (.waitFor java-process 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly java-process)
      (when-not (.waitFor java-process 5 java.util.concurrent.TimeUnit/SECONDS)
        (throw (ex-info "daemon did not terminate after force-destroy" {}))))))

(defn await-process [process seconds]
  (let [java-process ^Process (:proc process)]
    (when (.waitFor java-process (long seconds)
                    java.util.concurrent.TimeUnit/SECONDS)
      @process)))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-expected-log"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log-a (io/file dir "a.code.log")
      log-b (io/file dir "b.code.log")
      alias-a (io/file dir "alias-a.log")
      _ (spit log-a "")
      _ (spit log-b "")
      _ (java.nio.file.Files/createSymbolicLink
         (.toPath alias-a) (.toPath (.getCanonicalFile log-a))
         (make-array java.nio.file.attribute.FileAttribute 0))
      daemon (proc/process
              {:dir root :out :string :err :string}
              "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
              (str port) (.getPath alias-a))
      checks (atom [])
      check! (fn [label value]
               (swap! checks conj [label (boolean value)]))
      env-b {"FRAM_PORT" (str port)
             "FRAM_LOG" (.getPath log-b)
             "FRAM_THREADS" (.getPath dir)}
      mcp-request
      (fn [id name arguments]
        {:jsonrpc "2.0" :id id :method "tools/call"
         :params {:name name :arguments arguments}})]
  (try
    (check! "real socket daemon starts on log A"
            (eventually #(integer? (:version (client port {:op :version})))))

    (println "phase: mismatched corpus probes")
    (let [before-a (slurp log-a)
          before-b (slurp log-b)
          mismatch-version (client port (for-log (.getPath log-b) {:op :version}))
          mismatch-status (client port (for-log (.getPath log-b) {:op :status}))
          mismatch-assert
          (client port (for-log (.getPath log-b)
                                {:op :assert :te "@wrong" :p "note"
                                 :r "never" :base 0}))
          mismatch-retract
          (client port (for-log (.getPath log-b)
                                {:op :retract :te "@wrong" :p "note"
                                 :r "never" :base 0}))
          mismatch-edit
          (client port (for-log (.getPath log-b)
                                {:op :edit-min
                                 :spec {:op "set-body" :module "missing"
                                        :name "missing" :datum '(+ 1 2)}}))
          mismatch-subscribe
          (client port (for-log (.getPath log-b) {:op :subscribe}))
          nested-envelope
          (client port
                  (for-log
                   (.getPath log-a)
                   (for-log (.getPath log-a)
                            {:op :assert :te "@nested" :p "note"
                             :r "never" :base 0})))
          missing-identity
          (client port {:op :for-log
                        :request {:op :edit-min
                                  :spec {:op "set-body" :module "missing"
                                         :name "missing" :datum '(+ 1 2)}}})
          cli-doctor (run-cli env-b "doctor")
          cli-tell (run-cli env-b "tell" "wrong" "note" "never")
          cli-retract (run-cli env-b "retract" "wrong" "note" "never")
          cli-call (run-cli env-b "call" "tell"
                            "{:subject \"wrong\" :predicate \"note\" :object \"never\"}")
          mcp-write
          (run-mcp env-b
                   (mcp-request 1 "tell"
                                {:subject "wrong" :predicate "note"
                                 :object "never"}))
          mcp-edit
          (run-mcp
           (assoc env-b
                  "FRAM_FLIP" "1"
                  "FRAM_CODE_PORT" (str port)
                  "FRAM_CODE_LOG" (.getPath log-b))
           (mcp-request 2 "set-body"
                        {:module "missing" :name "missing" :body "(+ 1 2)"}))
          body-file (io/file dir "body.edn")
          _ (spit body-file "(+ 1 2)")
          edit-helper
          (run-command
           {} "bb" "-cp" "out" "bin/fram-edit-code"
           "set-body" "missing" "--name" "missing"
           "--body-file" (.getPath body-file)
           "--port" (str port) "--log" (.getPath log-b))
          commit-helper
          (run-command
           {} "bb" "-cp" "out" "bin/fram-commit-code"
           "missing" "src/fram/schema.bclj"
           "--port" (str port) "--log" (.getPath log-b)
           "--dry-run")]
      (check! "mismatched version rejects with canonical identities"
              (and (= :log-mismatch (:code mismatch-version))
                   (= (.getCanonicalPath log-b)
                      (:expected-log mismatch-version))
                   (= (.getCanonicalPath log-a)
                      (:served-log mismatch-version))))
      (check! "mismatched status rejects before reporting UP"
              (= :log-mismatch (:code mismatch-status)))
      (check! "mismatched assert and retract reject"
              (every? #(= :log-mismatch (:code %))
                      [mismatch-assert mismatch-retract]))
      (check! "mismatched edit-min rejects before graph computation/mutation"
              (= :log-mismatch (:code mismatch-edit)))
      (check! "mismatched subscribe rejects instead of registering"
              (= :log-mismatch (:code mismatch-subscribe)))
      (check! "nested envelope rejects without reaching its mutation"
              (= :invalid-log-fence (:code nested-envelope)))
      (check! "missing expected-log cannot bypass edit-min fence"
              (= :invalid-log-fence (:code missing-identity)))
      (check! "runtime version and doctor expose the mismatch"
              (and (= -2 (rt/coord-version-for-log port (.getPath log-b)))
                   (str/includes?
                    (rt/coord-status-for-log port (.getPath log-b))
                    "coordinator WRONG LOG")
                   (str/includes? (:out cli-doctor) "coordinator WRONG LOG")
                   (not (str/includes? (:out cli-doctor) "coordinator UP"))))
      (check! "CLI tell/retract/call writes fail closed"
              (every? #(str/includes? (:out %) "different log")
                      [cli-tell cli-retract cli-call]))
      (check! "regular MCP tell fails closed"
              (and (get-in mcp-write [:result :isError])
                   (str/includes?
                    (get-in mcp-write [:result :content 0 :text])
                    "different log")))
      (check! "MCP direct edit-min path fails closed"
              (and (get-in mcp-edit [:result :isError])
                   (str/includes?
                    (get-in mcp-edit [:result :content 0 :text])
                    "log mismatch")))
      (check! "fram-edit-code minimal authoring path fails closed"
              (and (not (zero? (:exit edit-helper)))
                   (str/includes?
                    (str (:out edit-helper) (:err edit-helper))
                    "log mismatch")))
      (check! "fram-commit-code whole-module path fails closed"
              (and (not (zero? (:exit commit-helper)))
                   (str/includes?
                    (str (:out commit-helper) (:err commit-helper))
                    "log mismatch")))
      (check! "mismatched warm reads do not expose log A"
              (and (nil? (rt/coord-query-for-log
                          port (.getPath log-b)
                          {:find "x" :rules []}))
                   (empty? (rt/coord-live-facts port (.getPath log-b)))))
      (check! "all mismatched CLI/MCP/socket probes leave both corpora byte-identical"
              (and (= before-a (slurp log-a))
                   (= before-b (slurp log-b)))))

    (println "phase: canonical-equivalent corpus probes")
    (let [version (rt/coord-version-for-log port (.getPath alias-a))
          status (rt/coord-status-for-log port (.getPath alias-a))
          asserted (rt/coord-assert-for-log
                    port (.getPath alias-a) "@right" "note" "landed" version)
          live (rt/coord-live-facts port (.getPath alias-a))
          subscribed (client port (for-log (.getPath alias-a)
                                           {:op :subscribe}))
          retracted (rt/coord-retract-for-log
                     port (.getPath alias-a) "@right" "note" "landed"
                     (rt/coord-version-for-log port (.getPath alias-a)))]
      (check! "canonical-equivalent symlink identity is accepted"
              (and (not (neg? version))
                   (re-find
                    (re-pattern
                     (str "^coordinator UP on 127\\.0\\.0\\.1:"
                          port " \\(v[0-9]+\\)$"))
                    status)
                   (str/starts-with? asserted "ok:")
                   (some #(and (= "@right" (:l %))
                               (= "note" (:p %))
                               (= "landed" (:r %)))
                         live)
                   (integer? (:subscribed subscribed))
                   (str/starts-with? retracted "ok:"))))

    (println "phase: boot-time physical identity remains frozen")
    (let [before-a (slurp log-a)
          before-b (slurp log-b)
          _ (java.nio.file.Files/delete (.toPath alias-a))
          _ (java.nio.file.Files/createSymbolicLink
             (.toPath alias-a) (.toPath (.getCanonicalFile log-b))
             (make-array java.nio.file.attribute.FileAttribute 0))
          alias-version (rt/coord-version-for-log port (.getPath alias-a))
          alias-write (rt/coord-assert-for-log
                       port (.getPath alias-a)
                       "@retargeted-alias" "note" "never" 0)
          b-write (rt/coord-assert-for-log
                   port (.getPath log-b)
                   "@retargeted-b" "note" "never" 0)
          mismatch-status (client port
                                  (for-log (.getPath alias-a) {:op :status}))
          after-mismatch-a (slurp log-a)
          after-mismatch-b (slurp log-b)
          a-version (rt/coord-version-for-log port (.getPath log-a))
          a-write (rt/coord-assert-for-log
                   port (.getPath log-a)
                   "@frozen-a" "note" "landed" a-version)]
      (check! "retargeted boot alias resolves to B and is rejected against frozen A"
              (and (= -2 alias-version)
                   (str/starts-with? alias-write "log-mismatch:")
                   (str/starts-with? b-write "log-mismatch:")
                   (= :log-mismatch (:code mismatch-status))
                   (= (.getCanonicalPath log-b)
                      (:expected-log mismatch-status))
                   (= (.getCanonicalPath log-a)
                      (:served-log mismatch-status))))
      (check! "retarget mismatches leave both physical corpora byte-identical"
              (and (= before-a after-mismatch-a)
                   (= before-b after-mismatch-b)))
      (check! "explicit frozen canonical A remains writable and B stays untouched"
              (and (not (neg? a-version))
                   (str/starts-with? a-write "ok:")
                   (not= after-mismatch-a (slurp log-a))
                   (= after-mismatch-b (slurp log-b)))))

    (let [legacy (client port {:op :assert :te "@legacy" :p "note"
                               :r "compatible"})]
      (check! "old-client legacy request remains compatible with new daemon"
              (:ok legacy)))

    (println "phase: old-daemon compatibility probe")
    (let [seen (atom nil)
          {:keys [port server worker]} (start-fake-old-daemon seen)]
      (try
        (let [result (rt/coord-assert-for-log
                      port (.getPath log-b)
                      "@old-daemon" "note" "never" 0)
              joined (deref worker 5000 ::timeout)]
          (check! "new client fails closed against old daemon"
                  (and (= :done joined)
                       (= "protocol-incompatible" result)
                       (= :for-log (:op @seen))
                       (= :assert (get-in @seen [:request :op])))))
        (finally
          (try (.close ^java.net.ServerSocket server)
               (catch Throwable _ nil))
          (future-cancel worker))))

    (println "phase: strict graph-authoring daemon")
    (let [strict-port (free-port)
          strict-a (io/file dir "strict-a.code.log")
          strict-b (io/file dir "strict-b.code.log")
          _ (spit strict-a "")
          _ (spit strict-b "")
          strict-daemon
          (proc/process
           {:dir root :out :string :err :string
            :extra-env {"FRAM_REQUIRE_LOG_FENCE" "1"}}
           "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
           (str strict-port) (.getPath strict-a))
          watch-process (atom nil)]
      (try
        (check! "strict real-socket daemon starts with a fenced version"
                (eventually
                 #(integer?
                   (:version
                    (client strict-port
                            (for-log (.getPath strict-a) {:op :version}))))))

        (let [before-a (slurp strict-a)
              before-b (slurp strict-b)
              raw-responses
              (mapv #(client strict-port %)
                    [{:op :version}
                     {:op :status}
                     {:op :query :query {:find "x" :rules []}}
                     {:op :assert :te "@raw" :p "note" :r "never" :base 0}
                     {:op :subscribe}])]
          (check! "strict mode rejects every representative unwrapped op"
                  (every? #(= :log-fence-required (:code %)) raw-responses))
          (check! "strict unwrapped probes do not mutate either corpus"
                  (and (= before-a (slurp strict-a))
                       (= before-b (slurp strict-b)))))

        (let [version-response
              (client strict-port
                      (for-log (.getPath strict-a) {:op :version}))
              status-response
              (client strict-port
                      (for-log (.getPath strict-a) {:op :status}))
              wrong-response
              (client strict-port
                      (for-log (.getPath strict-b) {:op :version}))
              asserted
              (client strict-port
                      (for-log
                       (.getPath strict-a)
                       {:op :assert :te "@strict" :p "note" :r "landed"
                        :base (:version version-response)}))
              subscribed
              (client strict-port
                      (for-log (.getPath strict-a) {:op :subscribe}))]
          (check! "strict mode accepts correctly fenced reads and writes"
                  (and (integer? (:version version-response))
                       (integer? (:version status-response))
                       (:ok asserted)
                       (integer? (:subscribed subscribed))
                       (= (.getCanonicalPath strict-a)
                          (:log subscribed))))
          (check! "strict mode preserves distinct wrong-log rejection"
                  (= :log-mismatch (:code wrong-response))))

        (let [raw-watch-error
              (try
                (rt/coord-watch strict-port)
                nil
                (catch Throwable t (.getMessage t)))
              wrong-watch-error
              (try
                (rt/coord-watch-for-log strict-port (.getPath strict-b))
                nil
                (catch Throwable t (.getMessage t)))]
          (check! "watch rejects strict/raw and wrong-log handshakes loudly"
                  (and (str/includes? (or raw-watch-error "")
                                      "log-fence-required")
                       (str/includes? (or wrong-watch-error "")
                                      "log-mismatch"))))

        ;; These helpers are graph-authoring clients: strict mode distinguishes a
        ;; properly wrapped request from a legacy raw one. A correct-log missing
        ;; module reaches the render/edit layer; a wrong log is rejected as a
        ;; log mismatch (never merely as the strict envelope requirement).
        (let [body-file (io/file dir "strict-body.edn")
              author-file (io/file dir "strict-author.edn")
              _ (spit body-file "(+ 1 2)")
              _ (spit author-file
                      "[{:op \"set-body\" :module \"missing\" :name \"missing\" :body (+ 1 2)}]")
              render-helper
              (run-command
               {"FRAM_RACKET" "/bin/true"}
               "bb" "-cp" "out" "bin/fram-render-code"
               "missing" "--port" (str strict-port)
               "--log" (.getPath strict-a))
              edit-helper
              (run-command
               {} "bb" "-cp" "out" "bin/fram-edit-code"
               "set-body" "missing" "--name" "missing"
               "--body-file" (.getPath body-file)
               "--port" (str strict-port) "--log" (.getPath strict-b))
              author-helper
              (run-command
               {} "bb" "-cp" "out" "bin/fram-code-author"
               "--script" (.getPath author-file)
               "--port" (str strict-port) "--log" (.getPath strict-a))]
          (check! "warm render crosses strict boundary with its explicit log"
                  (let [output (str (:out render-helper) (:err render-helper))]
                    (and (not (zero? (:exit render-helper)))
                         (str/includes? output "no such module")
                         (not (str/includes? output "log-fence-required")))))
          (check! "edit helper retains wrong-log identity under strict mode"
                  (let [output (str (:out edit-helper) (:err edit-helper))]
                    (and (not (zero? (:exit edit-helper)))
                         (str/includes? output "log mismatch")
                         (not (str/includes? output "log-fence-required")))))
          (check! "author driver uses strict fenced status/version/edit requests"
                  (let [output (str (:out author-helper) (:err author-helper))]
                    (and (not (zero? (:exit author-helper)))
                         (str/includes? output "REJECTED")
                         (not (str/includes? output "log-fence-required"))))))

        ;; Regression for coord-watch-request inheriting coord-socket's 2s request
        ;; timeout. The external watch process must still be alive after >2s idle,
        ;; receive a later commit, and terminate promptly when the daemon closes.
        (let [watch
              (proc/process
               {:dir root :out :string :err :string
                :extra-env {"WATCH_PORT" (str strict-port)
                            "WATCH_LOG" (.getPath strict-a)}}
               "bb" "-cp" "out" "-e"
               "(require '[fram.rt :as rt]) (rt/coord-watch-for-log (Integer/parseInt (System/getenv \"WATCH_PORT\")) (System/getenv \"WATCH_LOG\"))")
              _ (reset! watch-process watch)
              _ (Thread/sleep 2600)
              alive-after-idle? (.isAlive ^Process (:proc watch))
              watch-version
              (rt/coord-version-for-log strict-port (.getPath strict-a))
              watch-write
              (rt/coord-assert-for-log
               strict-port (.getPath strict-a)
               "@watch-survived" "note" "after-idle" watch-version)
              _ (Thread/sleep 500)
              _ (stop-process! strict-daemon)
              watch-result (await-process watch 5)
              _ (when-not watch-result (stop-process! watch))
              output (str (:out watch-result) (:err watch-result))]
          (check! "fenced watch survives more than two idle seconds"
                  (and alive-after-idle?
                       (str/starts-with? watch-write "ok:")
                       watch-result
                       (str/includes? output ":subscribed")
                       (str/includes? output "@watch-survived"))))

        (finally
          (when-let [watch @watch-process]
            (try (stop-process! watch) (catch Throwable _ nil)))
          (try (stop-process! strict-daemon) (catch Throwable _ nil)))))

    (finally
      (future-cancel watchdog)
      (stop-process! daemon)
      (doseq [[label ok?] @checks]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [failed (remove second @checks)]
        (println (format "\n%d/%d passed"
                         (- (count @checks) (count failed))
                         (count @checks)))
        (when (seq failed)
          (System/exit 1))))))
