;; mcp_test.clj — drives the real bin/fram-mcp process over stdio and asserts the
;; JSON-RPC contract end to end for the CLOSED catalog: initialize, tools/list (exactly
;; eleven tools: tell/retract/show/ask/validate + 6 edit verbs), KB reads (show), a
;; structured ask/query, the `untell`->`retract` alias, and server-side param rejection.
;; FRAM_PORT pins a dead port so no live coordinator can leak into the run.
;;   bb -cp out tests/mcp_test.clj      (run from the repo root)
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.fold :as fold]
         '[fram.rt :as rt]
         '[fram.tools :as tl])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

(defn input-schema [params]
  {:type "object"
   :properties (reduce (fn [m p]
                         (assoc m (keyword (:name p))
                                {:type (:type p) :description (:name p)}))
                       {} params)
   :required (vec (keep (fn [p] (when (:required p) (:name p))) params))})

(def edit-transaction-items-schema
  {:oneOf
   [{:type "object"
     :properties
     {:op {:type "string" :const "set-body"}
      :name {:type "string" :description "Definition whose body will be replaced."}
      :body {:type "string" :description "Replacement body as an EDN datum string."}}
     :required ["op" "name" "body"]}
    {:type "object"
     :properties
     {:op {:type "string" :const "replace-in-body"}
      :name {:type "string" :description "Definition containing the form to replace."}
      :old {:type "string" :description "Existing interior form as an EDN datum string."}
      :new {:type "string" :description "Replacement interior form as an EDN datum string."}
      :within {:type "string" :description "Optional enclosing form used to disambiguate old."}}
     :required ["op" "name" "old" "new"]}
    {:type "object"
     :properties
     {:op {:type "string" :const "upsert-form"}
      :form {:type "string" :description "Whole top-level form as an EDN datum string."}
      :name {:type "string"
             :description "Optional identity assertion; when supplied it must match the identity derived from form."}}
     :required ["op" "form"]}]})

(defn catalog-tool [spec]
  {:name (:name spec)
   :description (:desc spec)
   :inputSchema
   (cond-> (input-schema (:params spec))
     (= "edit-transaction" (:name spec))
     (assoc-in [:properties :edits :items] edit-transaction-items-schema))})

(def expected-tools (mapv catalog-tool (tl/catalog [])))

;; a tiny self-contained log: @a -depends_on-> @b
(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-mcp-test-" (System/nanoTime)))
(.mkdirs (java.io.File. tmp))
(def logpath (str tmp "/coordination.log"))
(def telemetry-path (str tmp "/telemetry.log"))
(spit logpath
  (str/join "\n"
    ['{:tx 1 :op "assert" :l "@a" :p "title" :r "A" :frame "test"}
     '{:tx 2 :op "assert" :l "@a" :p "owner" :r "personal" :frame "test"}
     '{:tx 3 :op "assert" :l "@a" :p "depends_on" :r "@b" :frame "test"}
     '{:tx 4 :op "assert" :l "@b" :p "title" :r "B" :frame "test"}]))
(spit telemetry-path
  (pr-str '{:tx 5 :op "assert" :l "@run-test" :p "kind" :r "run" :frame "test"}))

;; a port nothing listens on — the warm query path and write path must fail
;; deterministically instead of finding a live daemon serving another corpus.
(def dead-port "59998")
;; No FRAM_TELEMETRY_LOG: canonical coordination.log must imply its sibling.
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
                            :params {:name "show" :arguments {:subject "a"}}})
     (json/generate-string {:jsonrpc "2.0" :id 10 :method "tools/call"
                            :params {:name "show" :arguments {:subject "run-test"}}})
     (json/generate-string {:jsonrpc "2.0" :id 4 :method "tools/call"
                            :params {:name "query" :arguments {:query reaches-query}}})
     (json/generate-string {:jsonrpc "2.0" :id 5 :method "tools/call"
                            :params {:name "ask" :arguments {:query reaches-query}}})
     ;; server-side param rejection on tell (missing object)
     (json/generate-string {:jsonrpc "2.0" :id 6 :method "tools/call"
                            :params {:name "tell" :arguments {:subject "a" :predicate "note"}}})
     ;; `untell` is an accepted ALIAS for `retract`: a missing-object untell must hit
     ;; retract's server-side param check ("object"), NOT "unknown tool" — proving the
     ;; alias resolved to the retract op rather than being rejected as unknown.
     (json/generate-string {:jsonrpc "2.0" :id 7 :method "tools/call"
                            :params {:name "untell" :arguments {:subject "a" :predicate "owner"}}})]))

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
  (chk "tools/list is EXACTLY the closed catalog (11 tools, retract not untell, no threads)"
       (= names #{"tell" "retract" "show" "ask" "validate"
                  "add-def" "set-body" "rename-def" "insert-after" "replace-in-body"
                  "edit-transaction"}))
  (chk "tools/list preserves the canonical catalog descriptions, schemas, and order"
       (= expected-tools tools))
  (chk "each tool has an inputSchema object"
       (every? (fn [t] (= "object" (get-in t [:inputSchema :type]))) tools)))

(let [tools (get-in (get by-id 2) [:result :tools])
      transaction (some #(when (= "edit-transaction" (:name %)) %) tools)
      items (get-in transaction [:inputSchema :properties :edits :items])
      arms (into {}
                 (map (fn [arm]
                        [(get-in arm [:properties :op :const]) arm]))
                 (:oneOf items))]
  (chk "edit-transaction edits.items is discriminated by exactly three op constants"
       (= #{"set-body" "replace-in-body" "upsert-form"} (set (keys arms))))
  (chk "set-body transaction items require op, name, and body"
       (= ["op" "name" "body"] (:required (get arms "set-body"))))
  (chk "replace-in-body transaction items require op/name/old/new and expose optional within"
       (let [arm (get arms "replace-in-body")]
         (and (= ["op" "name" "old" "new"] (:required arm))
              (= "string" (get-in arm [:properties :within :type])))))
  (chk "upsert-form transaction items require form while name is an optional identity assertion"
       (let [arm (get arms "upsert-form")]
         (and (= ["op" "form"] (:required arm))
              (= "string" (get-in arm [:properties :name :type]))
              (not (some #{"name"} (:required arm)))
              (str/includes? (get-in arm [:properties :name :description])
                             "identity assertion")))))

;; Cold large-corpus regression. The fixture models 10k graph entities / 30k
;; kind-v-f0 facts, then appends a newline-terminated corruption sentinel. A
;; state fold must reject that sentinel, so tools/list returning the exact catalog
;; is a structural proof that discovery did not read/fold the corpus; no flaky
;; wall-time threshold is involved. Restoring the valid corpus and issuing show in
;; a fresh offline server proves its initial state snapshot still has a cold fallback.
(def large-dir (str tmp "/large-corpus"))
(.mkdirs (java.io.File. large-dir))
(def large-logpath (str large-dir "/coordination.log"))
(def large-entity-count 10000)
(def large-corpus
  (let [sb (StringBuilder.)]
    (doseq [n (range large-entity-count)
            [offset pred object] [[1 "kind" "ast"]
                                  [2 "v" (str "node-" n)]
                                  [3 "f0" (str "@node-" (mod (inc n) large-entity-count))]]]
      (.append sb (pr-str {:tx (+ (* n 3) offset)
                           :op "assert"
                           :l (str "@node-" n)
                           :p pred
                           :r object
                           :frame "mcp-large-corpus-test"}))
      (.append sb "\n"))
    (str sb)))
(def large-env {"FRAM_LOG" large-logpath
                "FRAM_THREADS" large-dir
                "FRAM_PORT" dead-port})

(spit large-logpath (str large-corpus "{\n"))
(def large-corpus-fold-rejected?
  (try
    (fold/fold (rt/read-log large-logpath))
    false
    (catch clojure.lang.ExceptionInfo error
      (true? (:fram/corrupt-log (ex-data error))))))
(chk "large-corpus oracle is rejected by the actual read-log/fold path"
     large-corpus-fold-rejected?)
(def large-catalog-out
  (:out (p/shell {:in (str (str/join "\n"
                              [(json/generate-string {:jsonrpc "2.0" :id 30 :method "initialize" :params {}})
                               (json/generate-string {:jsonrpc "2.0" :id 31 :method "tools/list" :params {}})])
                          "\n")
                  :out :string :err :string :extra-env large-env}
                 "bin/fram-mcp")))
(def large-catalog-responses
  (keep #(try (json/parse-string % true) (catch Exception _ nil))
        (remove str/blank? (str/split-lines large-catalog-out))))
(def large-catalog-response (some #(when (= 31 (:id %)) %) large-catalog-responses))
(chk "tools/list serves the exact closed catalog without folding a cold 30k-fact corpus"
     (= expected-tools (get-in large-catalog-response [:result :tools])))

(spit large-logpath large-corpus)
(def large-show-out
  (:out (p/shell {:in (str (json/generate-string
                             {:jsonrpc "2.0" :id 32 :method "tools/call"
                              :params {:name "show" :arguments {:subject "node-9999"}}})
                         "\n")
                  :out :string :err :string :extra-env large-env}
                 "bin/fram-mcp")))
(def large-show-response
  (some #(when (= 32 (:id %)) %)
        (keep #(try (json/parse-string % true) (catch Exception _ nil))
              (remove str/blank? (str/split-lines large-show-out)))))
(def large-show-rows
  (some-> large-show-response (get-in [:result :content 0 :text]) json/parse-string))
(chk "an offline server's initial state snapshot cold-folds and reads the 30k-fact corpus"
     (= #{"kind" "v" "f0"} (set (map #(get % "pred") large-show-rows))))

;; Process-lifetime regression: after the first offline show builds its snapshot,
;; append a completed corrupt row that any second whole-log fold MUST reject. The
;; repeat show must still return from the cached snapshot. This is structural proof
;; of fold lifetime, independent of wall-clock thresholds.
(def cache-pb
  (doto (ProcessBuilder. (into-array String ["bin/fram-mcp"]))
    (.directory (io/file (System/getProperty "user.dir")))))
(doseq [[k v] large-env]
  (.put (.environment cache-pb) k v))
(def cache-process (.start cache-pb))
(try
  (with-open [writer (io/writer (.getOutputStream cache-process))
              reader (io/reader (.getInputStream cache-process))]
    (let [call! (fn [request]
                  (.write writer (str (json/generate-string request) "\n"))
                  (.flush writer)
                  (json/parse-string (.readLine reader) true))
          initialized (call! {:jsonrpc "2.0" :id 40 :method "initialize" :params {}})
          first-show (call! {:jsonrpc "2.0" :id 41 :method "tools/call"
                             :params {:name "show" :arguments {:subject "node-9999"}}})
          _ (spit large-logpath "{\n" :append true)
          repeat-show (call! {:jsonrpc "2.0" :id 42 :method "tools/call"
                              :params {:name "show" :arguments {:subject "node-9999"}}})
          first-text (get-in first-show [:result :content 0 :text])
          repeat-text (get-in repeat-show [:result :content 0 :text])]
      (chk "cache regression process initializes"
           (= "fram" (get-in initialized [:result :serverInfo :name])))
      (chk "repeat show reuses the process snapshot instead of folding the grown log"
           (and (not (get-in repeat-show [:result :isError]))
                (= first-text repeat-text)))))
  (finally
    (.destroyForcibly cache-process)
    (spit large-logpath large-corpus)))

(let [r3 (get by-id 3) txt (get-in r3 [:result :content 0 :text])
      preds (set (map #(get % "pred") (json/parse-string txt)))]
  (chk "show by subject returns @a's facts" (every? preds ["title" "owner" "depends_on"])))

(let [r10 (get by-id 10) txt (get-in r10 [:result :content 0 :text])
      preds (set (map #(get % "pred") (json/parse-string txt)))]
  (chk "show reads the split telemetry half" (contains? preds "kind")))

(let [r4 (get by-id 4) txt (get-in r4 [:result :content 0 :text])
      pairs (set (map vec (json/parse-string txt)))]
  (chk "query (transitive) returns the @a->@b edge" (contains? pairs ["@a" "@b"])))

(let [r5 (get by-id 5) txt (get-in r5 [:result :content 0 :text])
      pairs (set (map vec (json/parse-string txt)))]
  (chk "ask aliases the structured query op" (contains? pairs ["@a" "@b"])))

(let [r6 (get by-id 6)]
  (chk "tell missing 'object' is rejected server-side"
       (and (get-in r6 [:result :isError])
            (str/includes? (get-in r6 [:result :content 0 :text]) "object"))))

(let [r7 (get by-id 7) txt (get-in r7 [:result :content 0 :text])]
  (chk "untell ALIAS resolves to retract (param error mentions 'object', not 'unknown tool')"
       (and (get-in r7 [:result :isError])
            (str/includes? txt "object")
            (not (str/includes? txt "unknown tool")))))

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

;; Canonical inference is deliberately name-scoped: an unrelated primary log
;; beside telemetry.log remains a single physical corpus unless env opts in.
(def alternate-log (str tmp "/alternate.log"))
(spit alternate-log (slurp logpath))
(def alternate-out
  (:out (p/shell {:in (str (json/generate-string
                              {:jsonrpc "2.0" :id 20 :method "tools/call"
                               :params {:name "show" :arguments {:subject "run-test"}}}) "\n")
                  :out :string :err :string
                  :extra-env {"FRAM_LOG" alternate-log "FRAM_THREADS" tmp "FRAM_PORT" dead-port}}
                 "bin/fram-mcp")))
(def alternate-response (json/parse-string (first (remove str/blank? (str/split-lines alternate-out))) true))
(chk "an unrelated primary log does not infer telemetry.log"
     (= [] (json/parse-string (get-in alternate-response [:result :content 0 :text]))))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (do (println "\nfram-mcp:" (count cs) "/" (count cs) "PASS")
        (fs/delete-tree tmp))
    (do (println "\nfram-mcp:" (count fails) "FAILED") (println "--- server stderr/stdout ---") (println out) (System/exit 1))))
