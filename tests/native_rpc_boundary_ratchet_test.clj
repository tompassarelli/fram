;; Static ratchet for the public data boundary. EDN remains a local human,
;; migration, and compiler syntax only; it never reaches a live data socket.
(require '[clojure.string :as str])

(def checks (atom []))
(defn check! [label value detail]
  (swap! checks conj [label (boolean value) detail]))
(defn file-source [path] (slurp path))
(defn absent? [text needles] (not-any? #(str/includes? text %) needles))
(defn between [text start end]
  (let [from (str/index-of text start)
        to (when from (str/index-of text end (+ from (count start))))]
    (when (and from to) (subs text from to))))

(let [shim (file-source "deploy/cloudflare/shim.clj")
      worker (file-source "deploy/cloudflare/worker-client.js")
      example (file-source "deploy/cloudflare/worker-example.js")
      docker (file-source "deploy/cloudflare/Dockerfile")
      compose (file-source "deploy/cloudflare/docker-compose.yml")]
  (check! "Cloudflare runtime contains no EDN codec or negotiation"
          (every? #(absent? % ["clojure.edn" "edn/read" "application/edn"
                               "RawEdn" "ednEncode" "ednDecode" ":fmt"
                               "format = 'edn'"])
                  [shim worker example docker compose])
          nil)
  (check! "Cloudflare shim uses the shared FRAMRPC client"
          (and (str/includes? shim "rt/native-request-to!")
               (str/includes? shim "framrpc/rpc-request!")
               (str/includes? shim "strict-duplicate-detection true"))
          nil)
  (check! "Worker has the exact closed Term vocabulary"
          (every? #(str/includes? worker (str "'" % "'"))
                  ["string" "integer" "float64" "boolean" "keyword" "instant" "triple"])
          nil)
  (check! "Worker surface has thirteen named operations and no raw method"
          (and (= 13 (count (re-seq #"(?m)^    [A-Za-z]+: .*send\(" worker)))
               (not (re-find #"(?m)^    raw:" worker)))
          nil))

(let [daemon (file-source "server.clj")]
  (check! "daemon listener has no line/EDN compatibility parser"
          (absent? daemon ["clojure.edn" "edn/read" "readLine" "io/reader"])
          nil)
  (check! "daemon serves only the closed thirteen-op FRAMRPC set"
          (= 13 (count (re-seq #":rpc/[a-z-]+" (between daemon
                                                       "(def native-rpc-operations"
                                                       "(def paged-rpc-operations"))))
          nil))

(let [runtime (file-source "src/fram/rt.clj")
      native (between runtime ";; --- FRAMRPC v1 client"
                      ";; The human syntax is deliberately")]
  (check! "shared binary client section has no legacy socket dependency"
          (and native
               (absent? native ["edn/read" "database-request-for-log"
                                "database-version-for-log" "readLine"]))
          nil)
  (check! "human Term parsing is explicitly local"
          (and (str/includes? runtime "defn parse-human-term!")
               (str/includes? runtime "This parser is a CLI boundary only"))
          nil))

(let [mcp (file-source "tests/fram_mcp.clj")
      catalog (between mcp "(def ^:private closed-catalog"
                       "(defn- input-schema")
      public (between mcp "(defn- term-json" "(def ^:private max-live-queries")
      tool-names (mapv second (re-seq #":name \"([^\"]+)\"" catalog))]
  (check! "MCP tools/list catalog is exactly the five public data verbs"
          (= ["tell" "retract" "show" "ask" "validate"] tool-names)
          tool-names)
  (check! "MCP public data dispatch is FRAMRPC-only"
          (and public
               (str/includes? public "fram.rt/native-call!")
               (absent? public ["database-request-for-log" "database-version-for-log"
                                "database-assert-for-log" "database-retract-for-log"
                                "edn/read"]))
          nil)
  (check! "MCP runtime closure contains no graph-control implementation"
          (absent? mcp ["resolve-core" "babashka.process" "FRAM_GRAPH_EDIT"
                        "route-edit" "add-def" "set-body" "rename-def"
                        "insert-after" "insert-before" "replace-in-body"
                        "edit-transaction"])
          nil))

(let [fast (file-source "bin/fram-fast.clj")
      up (file-source "bin/fram-up")
      selfcheck-runner (file-source "bin/fram-selfcheck")
      selfcheck (file-source "bin/fram-selfcheck-probe.clj")]
  (check! "CLI data client has no legacy server helper"
          (absent? fast ["database-request-for-log" "database-version-for-log"
                         "database-assert-for-log" "database-retract-for-log"])
          nil)
  (check! "CLI EDN use is confined to the local human query parser"
          (= 2 (count (re-seq #"(?:clojure\.edn|edn/read-string)" fast)))
          nil)
  (check! "readiness and deep probes speak native version frames"
          (and (str/includes? up "native-call!")
               (str/includes? selfcheck "native-request-to!")
               (absent? (str up selfcheck) ["database-version-for-log" "edn/read" "readLine"]))
          nil)
  (check! "deep probes bind the exact runtime engine identity"
          (and (str/includes? selfcheck-runner "native) EXPECTED_ENGINE=:rpc/native")
               (str/includes? selfcheck-runner "graal) EXPECTED_ENGINE=:rpc/graal")
               (str/includes? selfcheck-runner
                              "jvm-oracle|jvm-dev) EXPECTED_ENGINE=:rpc/jvm")
               (str/includes? selfcheck-runner
                              "FRAM_SC_EXPECTED_ENGINE=\"$EXPECTED_ENGINE\"")
               (str/includes? selfcheck
                              "FRAM_SC_EXPECTED_ENGINE must be :rpc/native, :rpc/graal, or :rpc/jvm")
               (str/includes? selfcheck "(= expected-engine engine)"))
          nil))

(let [launcher (file-source "bin/fram-server")
      migration (file-source "bin/fram-migrate-triple-log")
      code-on (file-source "bin/fram-code-on")
      ingest (file-source "bin/fram-ingest-code")
      status (file-source "bin/fram-code-status")]
  (check! "FRAMLOG serving, migration, and code ingest retain distinct boundaries"
          (and (str/includes? launcher "FRAM_SERVER_RUNTIME:-native")
               (str/includes? launcher "artifact_dir/READY")
               (str/includes? launcher "FRAM_GRAAL_ARTIFACT")
               (str/includes? launcher "exec \"$graal_artifact\"")
               (str/includes? launcher "jvm-oracle|jvm-dev")
               (str/includes? launcher "exec \"$native_server\"")
               (str/includes? migration "migrate-triple-log")
               (str/includes? code-on "bin/fram-server serve")
               (str/includes? code-on "--space-id \"$SPACE_ID\"")
               (absent? code-on ["edn/read" ":edit-protocol"])
               (every? #(str/includes? ingest %)
                       ["database/create-triple-log!" "database/open-database!"
                        "database/commit!" "replace-atomically!"])
               (str/includes? status "\"$HERE/bin/fram\" status")
               (absent? status ["wc -l"]))
          nil))

(let [failures (remove second @checks)]
  (doseq [[label ok detail] @checks]
    (println (if ok "  [PASS]" "  [FAIL]") label)
    (when (and (not ok) detail) (println "         " detail)))
  (if (seq failures)
    (do (println "\nnative RPC boundary ratchet:" (count failures) "FAILED")
        (System/exit 1))
    (println "\nnative RPC boundary ratchet:" (count @checks) "/" (count @checks) "PASS")))
