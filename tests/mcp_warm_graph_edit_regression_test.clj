;; End-to-end regressions for the graph-canonical warm MCP adapter.
;;
;; Proves that JSON form/body strings are parsed as EDN datums before :edit-min,
;; and that a dotted module id renders to its registered nested `file` path rather
;; than to FRAM_SRC/<dotted-module>.bclj. Also proves path confinement rejects
;; before mutation.
;;
;; Run from the Fram root:
;;   bb -cp out tests/mcp_warm_graph_edit_regression_test.clj
(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.rt :as rt])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def home (System/getProperty "user.home"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def racket-bin
  (or (System/getenv "FRAM_RACKET")
      (let [r (try (p/sh {:out :string :err :string}
                         "direnv" "exec" beagle-home "which" "racket")
                   (catch Throwable _ nil))]
        (when (and r (zero? (:exit r))) (str/trim (:out r))))))

(when (or (str/blank? racket-bin)
          (not (.exists (io/file (str beagle-home "/beagle-lib/private/facts-roundtrip.rkt")))))
  (println "SKIP — the flake-pinned Beagle/Racket toolchain is unavailable")
  (System/exit 0))

(def tmp (.getCanonicalPath
          (.toFile (java.nio.file.Files/createTempDirectory
                    "fram-mcp-warm-regression"
                    (make-array java.nio.file.attribute.FileAttribute 0)))))
(def project (str tmp "/project"))
(def nested-dir (str project "/src/plangrep"))
(def source-file (str nested-dir "/model.bclj"))
(def root-level-artifact (str project "/src.plangrep.model.bclj"))
(def code-log (str project "/.fram/code.log"))
(run! #(.mkdirs (io/file %)) [nested-dir (str project "/.fram")])

(spit source-file
      (str "#lang beagle/clj\n"
           ";; @upstream:graph\n"
           "(ns src.plangrep.model)\n"
           "(define-mode strict)\n"
           "(defn base [] :- Int 1)\n"))

(def checks (atom []))
(defn check! [label value]
  (let [ok (boolean value)]
    (swap! checks conj [label ok])
    (println (str "  [" (if ok "PASS" "FAIL") "] " label))
    ok))

(def base-tool-env
  {"BEAGLE_HOME" beagle-home
   "FRAM_RACKET" racket-bin
   "FRAM_OUT" (str root "/out")
   "FRAM_BIN" (str root "/bin")
   "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")
   "FRAM_ROUNDTRIP" (str beagle-home "/beagle-lib/private/facts-roundtrip.rkt")
   "FRAM_CHECK_EMIT" (str beagle-home "/beagle-lib/private/facts-check-emit.rkt")
   "FRAM_BUILD_ALL" (str beagle-home "/bin/beagle-build-all")})

(def ingest
  (p/shell {:dir root :out :string :err :string :continue true
            :extra-env base-tool-env}
           "bin/fram-ingest-code" source-file "--root" project "--out" code-log))
(when-not (zero? (:exit ingest))
  (println "ABORT — fixture ingest failed\n" (:out ingest) (:err ingest))
  (System/exit 1))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))

(def port (free-port))
(def daemon
  (p/process {:dir root
              :out (str tmp "/daemon.log") :err (str tmp "/daemon.log")
              :extra-env {"FRAM_REQUIRE_LOG_FENCE" "1"}}
             "bin/fram-daemon" "serve-flat" (str port) code-log))

(defn coord [req] (rt/coord-request-for-log port code-log req))
(defn eventually [f]
  (loop [remaining 400]
    (cond
      (try (f) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(def mcp-env
  (merge base-tool-env
         {"FRAM_FLIP" "1"
          "FRAM_GRAPH_EDIT" "1"
          "FRAM_CODE_PORT" (str port)
          "FRAM_CODE_LOG" code-log
          "FRAM_LOG" code-log
          "FRAM_THREADS" project
          "FRAM_SRC" project}))

(defn mcp-call [env id tool arguments]
  (let [request (json/generate-string
                 {:jsonrpc "2.0" :id id :method "tools/call"
                  :params {:name tool :arguments arguments}})
        result (p/shell {:dir root :in (str request "\n")
                         :out :string :err :string :continue true :extra-env env}
                        "bin/fram-mcp")]
    (->> (str/split-lines (:out result))
         (keep #(try (json/parse-string % true) (catch Throwable _ nil)))
         (some #(when (= id (:id %)) %)))))

(defn reply-error? [reply] (boolean (get-in reply [:result :isError])))
(defn reply-text [reply] (or (get-in reply [:result :content 0 :text]) ""))
(defn version [] (:version (coord {:op :version})))

(def watchdog
  (future
    (Thread/sleep 120000)
    (binding [*out* *err*] (println "mcp-warm-graph-edit-regression: hard timeout"))
    (try (p/destroy-tree daemon) (catch Throwable _ nil))
    (System/exit 124)))

(try
  (when-not (eventually #(integer? (version)))
    (throw (ex-info "code coordinator did not become ready" {:daemon-log (slurp (str tmp "/daemon.log"))})))

  (let [path-response (coord {:op :module-path :module "src.plangrep.model"})]
    (check! ":module-path resolves the exact registered nested source"
            (and (:ok path-response) (= source-file (:path path-response)))))

  (let [reply (mcp-call mcp-env 10 "add-def"
                        {:module "src.plangrep.model"
                         :form "(defn increment [x :- Int] :- Int (+ x 1))"})
        rendered (slurp source-file)]
    (check! "warm add-def succeeds" (and reply (not (reply-error? reply))))
    (check! "add-def JSON string renders as a real top-level form"
            (and (str/includes? rendered "(defn increment")
                 (str/includes? rendered "(+ x 1)")
                 (not (str/includes? rendered "\"(defn increment"))))
    (check! "add-def updates the registered nested file"
            (str/includes? rendered "defn increment"))
    (check! "add-def creates no root-level dotted-module projection"
            (not (.exists (io/file root-level-artifact)))))

  (let [reply (mcp-call mcp-env 11 "set-body"
                        {:module "src.plangrep.model" :name "base" :body "(+ 40 2)"})
        rendered (slurp source-file)]
    (check! "warm set-body succeeds" (and reply (not (reply-error? reply))))
    (check! "set-body JSON string renders as an expression, not a string literal"
            (and (str/includes? rendered "(+ 40 2)")
                 (not (str/includes? rendered "\"(+ 40 2)\""))))
    (check! "set-body still creates no root-level dotted-module projection"
            (not (.exists (io/file root-level-artifact)))))

  (let [narrow-root (str project "/test")
        _ (.mkdirs (io/file narrow-root))
        confined-env (assoc mcp-env "FRAM_SRC" narrow-root)
        before-version (version)
        before-source (slurp source-file)
        reply (mcp-call confined-env 12 "set-body"
                        {:module "src.plangrep.model" :name "base" :body "99"})]
    (check! "outside-FRAM_SRC registered path is rejected"
            (and (reply-error? reply) (str/includes? (reply-text reply) "outside FRAM_SRC")))
    (check! "path-confinement rejection happens before graph mutation"
            (= before-version (version)))
    (check! "path-confinement rejection leaves the nested view byte-identical"
            (= before-source (slurp source-file))))

  (let [before-version (version)
        reply (mcp-call mcp-env 13 "set-body"
                        {:module "src.plangrep.model" :name "base" :body "(+ 1"})]
    (check! "unreadable set-body EDN is rejected"
            (and (reply-error? reply) (str/includes? (reply-text reply) "not readable EDN")))
    (check! "unreadable EDN rejection happens before graph mutation"
            (= before-version (version))))

  (finally
    (future-cancel watchdog)
    (try (p/destroy-tree daemon) (catch Throwable _ nil))))

(let [failures (remove second @checks)]
  (if (seq failures)
    (do (println "\nFAIL — corpus retained at" tmp) (System/exit 1))
    (do
      (println "\nPASS —" (count @checks) "warm MCP graph-edit regressions")
      (doseq [f (reverse (file-seq (io/file tmp)))]
        (try (io/delete-file f true) (catch Throwable _ nil))))))
