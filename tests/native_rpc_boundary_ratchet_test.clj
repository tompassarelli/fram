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
                               "format = 'edn'" "serve-flat"])
                  [shim worker example docker compose])
          nil)
  (check! "Cloudflare shim uses the shared FRAMRPC client"
          (and (str/includes? shim "rt/native-request-to!")
               (str/includes? shim "wire/rpc-request!")
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

(let [daemon (file-source "coord_daemon.clj")]
  (check! "daemon listener has no line/EDN compatibility parser"
          (absent? daemon ["clojure.edn" "edn/read" "readLine" "io/reader"])
          nil)
  (check! "daemon serves only the closed thirteen-op FRAMRPC set"
          (= 13 (count (re-seq #":rpc/[a-z-]+" (between daemon
                                                       "(def native-rpc-operations"
                                                       "(defn- daemon-fail!"))))
          nil))

(let [runtime (file-source "src/fram/rt.clj")
      native (between runtime ";; --- FRAMRPC v1 client"
                      ";; The human syntax is deliberately")]
  (check! "shared binary client section has no legacy socket dependency"
          (and native
               (absent? native ["edn/read" "coord-request-for-log"
                                "coord-version-for-log" "readLine"]))
          nil)
  (check! "human Term parsing is explicitly local"
          (and (str/includes? runtime "defn parse-human-term!")
               (str/includes? runtime "This parser is a CLI boundary only"))
          nil))

(let [mcp (file-source "tests/fram_mcp.clj")
      public (between mcp "(defn- term-json" ";; wall-clock budget")]
  (check! "MCP public data dispatch is FRAMRPC-only"
          (and public
               (str/includes? public "fram.rt/native-call!")
               (absent? public ["coord-request-for-log" "coord-version-for-log"
                                "coord-assert-for-log" "coord-retract-for-log"
                                "edn/read"]))
          nil)
  (check! "MCP graph authoring fails into the sealed-control follow-up"
          (str/includes? public
                         "graph authoring is sealed-control work and is not routed through public FRAMRPC")
          nil))

(let [fast (file-source "bin/fram-fast.clj")
      up (file-source "bin/fram-up")
      selfcheck (file-source "bin/fram-selfcheck-probe.clj")]
  (check! "CLI data client has no legacy coordinator helper"
          (absent? fast ["coord-request-for-log" "coord-version-for-log"
                         "coord-assert-for-log" "coord-retract-for-log"])
          nil)
  (check! "CLI EDN use is confined to the local human query parser"
          (= 2 (count (re-seq #"(?:clojure\.edn|edn/read-string)" fast)))
          nil)
  (check! "readiness and deep probes speak native version frames"
          (and (str/includes? up "native-call!")
               (str/includes? selfcheck "native-request-to!")
               (absent? (str up selfcheck) ["coord-version-for-log" "edn/read" "readLine"]))
          nil))

(let [launcher (file-source "bin/fram-daemon")
      migration (file-source "bin/fram-migrate-triple-log")]
  (check! "flat serving is a rejection plus one-shot migration, never a daemon mode"
          (and (str/includes? launcher "serve-flat was removed")
               (str/includes? migration "migrate-triple-log")
               (= 2 (count (re-seq #"serve-flat" launcher))))
          nil))

(let [failures (remove second @checks)]
  (doseq [[label ok detail] @checks]
    (println (if ok "  [PASS]" "  [FAIL]") label)
    (when (and (not ok) detail) (println "         " detail)))
  (if (seq failures)
    (do (println "\nnative RPC boundary ratchet:" (count failures) "FAILED")
        (System/exit 1))
    (println "\nnative RPC boundary ratchet:" (count @checks) "/" (count @checks) "PASS")))
