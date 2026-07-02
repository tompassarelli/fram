;; mcp_test.clj — drives the real bin/fram-mcp process over stdio and asserts the
;; JSON-RPC contract end to end: initialize, tools/list (default = the TELL/ASK KB
;; core; FRAM_MCP_SURFACE=full = the catalog generated from the log's vocabulary),
;; KB and generated-name tools/call reads, and a structured `ask`/`query` call.
;; FRAM_PORT pins a dead port so no live coordinator can leak into the run.
;;   bb mcp_test.clj      (run from the repo root)
(require '[babashka.process :as p] '[cheshire.core :as json] '[clojure.string :as str])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

;; a tiny self-contained log: @a -depends_on-> @b
(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-mcp-test-" (System/nanoTime)))
(.mkdirs (java.io.File. tmp))
(def logpath (str tmp "/claims.log"))
(spit logpath
  (str/join "\n"
    ['{:tx 1 :op "assert" :l "@a" :p "title" :r "A" :frame "test"}
     '{:tx 2 :op "assert" :l "@a" :p "owner" :r "personal" :frame "test"}
     '{:tx 3 :op "assert" :l "@a" :p "depends_on" :r "@b" :frame "test"}
     '{:tx 4 :op "assert" :l "@b" :p "title" :r "B" :frame "test"}]))

;; a port nothing listens on — the warm query path and write path must fail
;; deterministically instead of finding a live daemon serving another corpus.
(def dead-port "59998")
(def env {"FRAM_LOG" logpath "FRAM_THREADS" tmp "FRAM_PORT" dead-port})

(def reaches-query
  {:find "reaches"
   :rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
            :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}]})

(def requests
  (str/join "\n"
    [(json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
     (json/generate-string {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
     (json/generate-string {:jsonrpc "2.0" :id 3 :method "tools/call"
                            :params {:name "owner-of" :arguments {:id "a"}}})
     (json/generate-string {:jsonrpc "2.0" :id 4 :method "tools/call"
                            :params {:name "query" :arguments {:query reaches-query}}})
     (json/generate-string {:jsonrpc "2.0" :id 5 :method "tools/call"
                            :params {:name "show" :arguments {:subject "a"}}})
     (json/generate-string {:jsonrpc "2.0" :id 6 :method "tools/call"
                            :params {:name "ask" :arguments {:query reaches-query}}})
     (json/generate-string {:jsonrpc "2.0" :id 7 :method "tools/call"
                            :params {:name "tell" :arguments {:subject "a" :predicate "note"}}})]))

(def out (:out (p/shell {:in (str requests "\n") :out :string :err :string
                         :extra-env env}
                        "bin/fram-mcp")))

(def by-id
  (reduce (fn [m line]
            (if (str/blank? line) m
              (let [r (try (json/parse-string line true) (catch Exception _ nil))]
                (if (:id r) (assoc m (:id r) r) m))))
          {} (str/split-lines out)))

(let [r1 (get by-id 1)]
  (chk "initialize returns serverInfo fram" (= "fram" (get-in r1 [:result :serverInfo :name])))
  (chk "initialize advertises tools capability" (contains? (get-in r1 [:result :capabilities]) :tools))
  (chk "initialize includes instructions" (seq (get-in r1 [:result :instructions]))))

(let [tools (get-in (get by-id 2) [:result :tools]) names (set (map :name tools))]
  (chk "default tools/list is the TELL/ASK KB core (10 tools)"
       (= names #{"tell" "untell" "show" "ask" "threads" "validate"
                  "add-def" "set-body" "rename-def" "insert-after"}))
  (chk "each tool has an inputSchema object"
       (every? (fn [t] (= "object" (get-in t [:inputSchema :type]))) tools)))

(let [r3 (get by-id 3) txt (get-in r3 [:result :content 0 :text])]
  (chk "generated name still dispatches on the core surface (owner-of -> [\"personal\"])"
       (= ["personal"] (json/parse-string txt))))

(let [r4 (get by-id 4) txt (get-in r4 [:result :content 0 :text])
      pairs (set (map vec (json/parse-string txt)))]
  (chk "query (transitive) returns the @a->@b edge" (contains? pairs ["@a" "@b"])))

(let [r5 (get by-id 5) txt (get-in r5 [:result :content 0 :text])
      preds (set (map #(get % "pred") (json/parse-string txt)))]
  (chk "show by subject returns @a's claims" (every? preds ["title" "owner" "depends_on"])))

(let [r6 (get by-id 6) txt (get-in r6 [:result :content 0 :text])
      pairs (set (map vec (json/parse-string txt)))]
  (chk "ask aliases the structured query op" (contains? pairs ["@a" "@b"])))

(let [r7 (get by-id 7)]
  (chk "tell missing 'object' is rejected server-side"
       (and (get-in r7 [:result :isError])
            (str/includes? (get-in r7 [:result :content 0 :text]) "object"))))

;; the generated per-predicate catalog survives behind FRAM_MCP_SURFACE=full.
(def full-out
  (:out (p/shell {:in (str (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}}) "\n")
                  :out :string :err :string
                  :extra-env (assoc env "FRAM_MCP_SURFACE" "full")}
                 "bin/fram-mcp")))
(let [r (json/parse-string (first (remove str/blank? (str/split-lines full-out))) true)
      names (set (map :name (get-in r [:result :tools])))]
  (chk "FRAM_MCP_SURFACE=full has generated read/write/reverse tools + query"
       (every? names ["owner-of" "set-owner" "depends_on-list" "depends_on-from" "query" "threads"])))

;; conformance (regression guard for the LazySeq batch crash + notification/id rules):
;; notification -> no reply; batch array -> one -32600 and the loop SURVIVES;
;; unknown method (with id) -> -32601; a normal request after the batch is still answered.
(def conf-out
  (:out (p/shell {:in (str (str/join "\n"
                     [(json/generate-string {:jsonrpc "2.0" :method "notifications/initialized"})
                      "[{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}]"
                      (json/generate-string {:jsonrpc "2.0" :id 8 :method "frobnicate"})
                      (json/generate-string {:jsonrpc "2.0" :id 9 :method "tools/list"})]) "\n")
                  :out :string :err :string
                  :extra-env env}
                 "bin/fram-mcp")))
(def conf-parsed (map #(json/parse-string % true) (remove str/blank? (str/split-lines conf-out))))
(chk "notification dropped: 3 id'd inputs -> exactly 3 replies" (= 3 (count conf-parsed)))
(chk "batch array -> -32600 (server survived, didn't crash)"
     (boolean (some #(= -32600 (get-in % [:error :code])) conf-parsed)))
(chk "unknown method -> -32601" (boolean (some #(= -32601 (get-in % [:error :code])) conf-parsed)))
(chk "normal request after the batch still answered"
     (boolean (some #(and (= 9 (:id %)) (:result %)) conf-parsed)))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram-mcp:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram-mcp:" (count fails) "FAILED") (println "--- server stderr/stdout ---") (println out) (System/exit 1))))
