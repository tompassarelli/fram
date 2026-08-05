;; top_level_insert_test.clj — true top-level insertion through the graph/MCP gate.
;; ============================================================================
;; Drives one isolated code log, one throwaway strict-fenced server, and the
;; real MCP server. It proves:
;;
;;   A. `insert-before` is a dedicated MCP tool with a closed string schema.
;;   B. `(declare later!)` becomes an ordered wrapper fact immediately before the
;;      named `setup!` anchor, publishes the tracked projection, and compiles.
;;   C. An unknown anchor, a non-list datum, and a list that fails Beagle's type
;;      gate each reject with byte-identical log/projection and an unmoved version.
;;
;; Parent-red:
;;   FRAM_TEST_ROOT=<repo-root> \
;;     bb -cp <repo-root>/out tests/top_level_insert_test.clj
;; Candidate:
;;   bb -cp out tests/top_level_insert_test.clj
;;
;; The fixture is wholly temporary. No canonical daemon, log, or source tree is
;; read or written.
(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.rt :as rt]
         '[fram.tools :as tools])

(def checks (atom []))
(defn check! [label value]
  (let [ok (boolean value)]
    (swap! checks conj [label ok])
    (println (str "  [" (if ok "PASS" "FAIL") "] " label))
    ok))

(def root
  (.getCanonicalPath
   (io/file (or (System/getenv "FRAM_TEST_ROOT")
                (System/getProperty "user.dir")))))
(def home (System/getProperty "user.home"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle/main")))
(def beagle-bin (or (System/getenv "FRAM_BEAGLE") (str beagle-home "/bin/beagle")))
(def build-all (str beagle-home "/bin/beagle-build-all"))
(def check-emit (str beagle-home "/beagle-lib/private/facts-check-emit.rkt"))

;; This first assertion is intentionally self-contained and precedes every
;; fixture/daemon action. Running this candidate test against the exact parent
;; runtime is therefore a deterministic parent-red, not an environmental skip.
(def insert-tool
  (some #(when (= "insert-before" (:name %)) %) (tools/catalog [])))
(check! "A: closed catalog exposes the dedicated insert-before tool" (some? insert-tool))
(when-not insert-tool
  (println "\nFAIL — parent runtime has no insert-before graph operation")
  (System/exit 1))

(let [params (into {} (map (juxt :name identity)) (:params insert-tool))]
  (check! "A: insert-before requires exactly module/before/form"
          (= #{"module" "before" "form"} (set (keys params))))
  (check! "A: insert-before accepts three required strings"
          (every? (fn [n]
                    (let [p (get params n)]
                      (and (= "string" (:type p)) (true? (:required p)))))
                  ["module" "before" "form"])))

(doseq [[path label] [[(str root "/out/fram/tools.clj") "built Fram output"]
                      [beagle-bin "Beagle CLI"]
                      [build-all "Beagle build-all"]
                      [check-emit "facts checker"]]]
  (when-not (.exists (io/file path))
    (println "ABORT — missing" label "at" path)
    (System/exit 1)))

(def racket-bin
  (or (System/getenv "FRAM_RACKET")
      (let [r (try
                (p/sh {:out :string :err :string}
                      "direnv" "exec" beagle-home "which" "racket")
                (catch Throwable _ nil))]
        (when (and r (zero? (:exit r)) (not (str/blank? (:out r))))
          (str/trim (:out r))))))
(when (str/blank? racket-bin)
  (println "ABORT — flake-pinned Racket is unavailable")
  (System/exit 1))

(def tmp
  (.getCanonicalPath
   (.toFile
    (java.nio.file.Files/createTempDirectory
     "fram-top-level-insert-"
     (make-array java.nio.file.attribute.FileAttribute 0)))))
(def project (str tmp "/project"))
(def nested-dir (str project "/src/fram"))
(def source-file (str nested-dir "/topinsert.bclj"))
(def code-log (str project "/.fram/code.log"))
(def daemon-log (str tmp "/daemon.log"))
(run! #(.mkdirs (io/file %)) [nested-dir (str project "/.fram")])

(spit source-file
      (str "#lang beagle/clj\n"
           "(ns src.fram.topinsert)\n"
           "(define-mode strict)\n\n"
           "(defn setup! [] :- Int\n"
           "  1)\n\n"
           "(defn later! [] :- Int\n"
           "  2)\n"))

(def process-env
  (cond-> {"PATH" (System/getenv "PATH")
           "HOME" home
           "BEAGLE_HOME" beagle-home
           "FRAM_RACKET" racket-bin
           "FRAM_BEAGLE" beagle-bin
           "FRAM_CHECK_EMIT" check-emit
           "FRAM_BUILD_ALL" build-all
           "FRAM_EDIT_VERIFIER" (str root "/bin/fram-edit-verifier")
           "FRAM_OUT" (str root "/out")
           "FRAM_BIN" (str root "/bin")
           "FRAM_RESOLVE" (str root "/out/resolve.clj")
           "FRAM_SERVER_READ_TIMEOUT_MS" "180000"}
    (System/getenv "JAVA_HOME") (assoc "JAVA_HOME" (System/getenv "JAVA_HOME"))))

(def ingest
  (p/shell {:dir root :out :string :err :string :continue true
            :env process-env}
           (str root "/bin/fram-ingest-code")
           source-file "--root" project "--out" code-log))
(when-not (zero? (:exit ingest))
  (println "ABORT — fixture ingest failed")
  (println (:out ingest))
  (println (:err ingest))
  (System/exit 1))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))
(def port (free-port))
(def daemon
  (p/process {:dir root
              :out (io/file daemon-log)
              :err (io/file daemon-log)
              :env (assoc process-env "FRAM_REQUIRE_LOG_FENCE" "1")}
             (str root "/bin/fram-server")
             "serve-flat" (str port) code-log))

(defn database [req]
  (rt/database-request-for-log port code-log req))
(defn version []
  (:version (database {:op :version})))
(defn eventually [f]
  (loop [remaining 600]
    (cond
      (try (boolean (f)) (catch Throwable _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(def mcp-env
  (merge process-env
         {"FRAM_MCP_PROFILE" "graph-edit-v1"
          "FRAM_GRAPH_EDIT" "1"
          "FRAM_FLIP" "1"
          "FRAM_SERVER_PORT" (str port)
          "FRAM_CODE_PORT" (str port)
          "FRAM_LOG" code-log
          "FRAM_CODE_LOG" code-log
          "FRAM_THREADS" project
          "FRAM_SRC" project}))

(defn run-mcp [requests]
  (let [input (str (str/join "\n" (map json/generate-string requests)) "\n")
        result (p/shell {:dir root :in input :out :string :err :string
                         :continue true :env mcp-env}
                        (str root "/bin/fram-mcp"))
        replies
        (reduce
         (fn [by-id line]
           (if (str/blank? line)
             by-id
             (let [reply (try
                           (json/parse-string line true)
                           (catch Throwable _ nil))]
               (if (and reply (contains? reply :id))
                 (assoc by-id (:id reply) reply)
                 by-id))))
         {}
         (str/split-lines (or (:out result) "")))]
    {:exit (:exit result) :out (:out result) :err (:err result)
     :replies replies}))

(def init-request
  {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
(def list-request
  {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
(defn call-request [id arguments]
  {:jsonrpc "2.0" :id id :method "tools/call"
   :params {:name "insert-before" :arguments arguments}})
(defn reply-error? [reply]
  (boolean (get-in reply [:result :isError])))
(defn reply-text [reply]
  (or (get-in reply [:result :content 0 :text]) ""))
(defn read-bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
(defn same-bytes? [^bytes left ^bytes right]
  (java.util.Arrays/equals left right))
(defn snapshot []
  {:log (read-bytes code-log)
   :source (read-bytes source-file)
   :version (version)})
(defn unchanged? [before]
  (and (= (:version before) (version))
       (same-bytes? (:log before) (read-bytes code-log))
       (same-bytes? (:source before) (read-bytes source-file))))

(def watchdog
  (future
    (Thread/sleep 240000)
    (binding [*out* *err*]
      (println "top-level-insert: hard timeout"))
    (try (p/destroy-tree daemon) (catch Throwable _ nil))
    (System/exit 124)))

(try
  (when-not (eventually #(integer? (version)))
    (throw
     (ex-info "throwaway server did not become ready"
              {:daemon-log (when (.exists (io/file daemon-log))
                             (slurp daemon-log))})))

  (let [{:keys [exit replies]} (run-mcp [init-request list-request])
        advertised (some #(when (= "insert-before" (:name %)) %)
                         (get-in (get replies 2) [:result :tools]))
        required (set (get-in advertised [:inputSchema :required]))
        props (get-in advertised [:inputSchema :properties])]
    (check! "A: restricted MCP starts cleanly against the isolated strict-fenced daemon"
            (zero? exit))
    (check! "A: tools/list advertises insert-before"
            (some? advertised))
    (check! "A: MCP schema requires module/before/form as strings"
            (and (= #{"module" "before" "form"} required)
                 (every? #(= "string" (get-in props [(keyword %) :type]))
                         required))))

  (let [before-log (read-bytes code-log)
        result (run-mcp
                [init-request
                 (call-request
                  10
                  {:module "src.fram.topinsert"
                   :before "setup!"
                   :form "(declare later!)"})])
        reply (get-in result [:replies 10])
        text (reply-text reply)
        rendered (slurp source-file)
        after-log (read-bytes code-log)
        appended
        (String.
         ^bytes
         (java.util.Arrays/copyOfRange
          ^bytes after-log
          (alength ^bytes before-log)
          (alength ^bytes after-log))
         "UTF-8")]
    (when (or (nil? reply) (reply-error? reply))
      (println "  [DIAG] insert-before success reply:" (pr-str text)))
    (check! "B: MCP insert-before commits through the candidate gate"
            (and reply (not (reply-error? reply))
                 (str/includes? text "committed")))
    (check! "B: tracked projection places declare immediately before setup!"
            (boolean
             (re-find
              #"(?s)\(declare later!\)\s+\(defn setup!"
              rendered)))
    (check! "B: declare is before setup!, and setup! remains before later!"
            (let [d (.indexOf rendered "(declare later!)")
                  s (.indexOf rendered "(defn setup!")
                  l (.indexOf rendered "(defn later!")]
              (and (<= 0 d) (< d s) (< s l))))
    (check! "B: canonical log records an ordered wrapper edge for the new form"
            (boolean
             (re-find
              #":p \"f\d+(?:\.\d+)*~\d+\""
              appended))))

  (let [build-out (str tmp "/compiled")
        result (p/shell {:dir root :out :string :err :string :continue true
                         :env process-env}
                        build-all source-file "--out" build-out)
        output (str (:out result) (:err result))]
    (check! "B: published candidate independently compiles with zero Beagle errors"
            (and (zero? (:exit result))
                 (str/includes? output "0 error"))))

  (let [before (snapshot)
        result (run-mcp
                [init-request
                 (call-request
                  20
                  {:module "src.fram.topinsert"
                   :before "missing-anchor"
                   :form "(declare later!)"})])
        reply (get-in result [:replies 20])]
    (when-not (str/includes? (reply-text reply) "anchor")
      (println "  [DIAG] unknown-anchor reply:" (pr-str (reply-text reply))))
    (check! "C: unknown named anchor rejects"
            (and (reply-error? reply)
                 (str/includes? (reply-text reply) "anchor")))
    (check! "C: unknown-anchor rejection leaves log/projection/version exact"
            (unchanged? before)))

  (let [before (snapshot)
        result (run-mcp
                [init-request
                 (call-request
                  21
                  {:module "src.fram.topinsert"
                   :before "setup!"
                   :form "[:not-a-top-level-list]"})])
        reply (get-in result [:replies 21])]
    (when-not (str/includes? (reply-text reply) "non-empty top-level list")
      (println "  [DIAG] non-list reply:" (pr-str (reply-text reply))))
    (check! "C: non-list top-level datum rejects"
            (and (reply-error? reply)
                 (str/includes? (reply-text reply) "non-empty top-level list")))
    (check! "C: non-list rejection leaves log/projection/version exact"
            (unchanged? before)))

  (let [before (snapshot)
        result (run-mcp
                [init-request
                 (call-request
                  22
                  {:module "src.fram.topinsert"
                   :before "setup!"
                   :form "(defn broken [] :- Int \"not-an-int\")"})])
        reply (get-in result [:replies 22])
        text (reply-text reply)]
    (check! "C: structurally valid but type-invalid top-level form rejects at compile gate"
            (and (reply-error? reply)
                 (or (str/includes? text "TYPE/WORLD check")
                     (str/includes? text "type check")
                     (str/includes? text "does not type-check"))))
    (check! "C: compile-gate rejection leaves log/projection/version exact"
            (unchanged? before)))

  (finally
    (future-cancel watchdog)
    (try (p/destroy-tree daemon) (catch Throwable _ nil))))

(let [failures (remove second @checks)]
  (if (seq failures)
    (do
      (println "\nFAIL — isolated fixture retained at" tmp)
      (System/exit 1))
    (do
      (println "\nPASS —" (count @checks)
               "top-level insert catalog/order/compile/atomicity checks")
      (doseq [file (reverse (file-seq (io/file tmp)))]
        (try (io/delete-file file true) (catch Throwable _ nil))))))
