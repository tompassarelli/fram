;; Historical queries capture a persistent Store value, not the production store
;; atom. Force capture-v0 -> commit-v1 -> execute for normal and fenced requests.
;; Run: bb -cp out tests/coord_query_snapshot_isolation_test.clj
(require '[clojure.java.io :as io])
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))

(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(def tmp-dir (.toFile (java.nio.file.Files/createTempDirectory
                       "fram-query-snapshot"
                       (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (.getCanonicalPath (io/file tmp-dir "facts.log")))
(spit log-path "")
(boot-flat! log-path)

(defn member-q [predicate]
  {:find "member"
   :rules [{:head {:rel "member" :args [{:var "x"}]}
            :body [{:rel "triple" :args [{:var "x"} predicate "yes"]}]}]})

(defn run-capture-race! [label fenced?]
  (let [predicate (str "snapshot-" label)
        early (str "@early-" label)
        late (str "@late-" label)
        initial (handle {:op :assert :te early :p predicate :r "yes"})
        v0 (:ok initial)
        inner {:op :as-of :seq Long/MAX_VALUE :query (member-q predicate)}
        request (if fenced?
                  {:op :for-log :expected-log log-path :request inner}
                  inner)
        captured (promise)
        proceed (promise)
        real-execute execute-query]
    (with-redefs [execute-query
                  (fn [req snapshot]
                    (deliver captured snapshot)
                    @proceed
                    (real-execute req snapshot))]
      (let [response-future (future (handle request))
            snapshot (deref captured 5000 ::timeout)
            committed (handle {:op :assert :te late :p predicate :r "yes"})
            v1 (:ok committed)
            _ (deliver proceed true)
            response (deref response-future 5000 ::timeout)
            rows (set (:ok response))]
        (check! (str label " captured before concurrent commit")
                (and (map? snapshot) (= v0 (:version snapshot)) (> v1 v0)))
        (check! (str label " historical result excludes v1 row")
                (and (contains? rows [early])
                     (not (contains? rows [late]))))
        (check! (str label " response stays stamped at captured v0")
                (= v0 (:version response)))))))

(run-capture-race! "normal" false)
(run-capture-race! "fenced" true)

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do (println "\ncoord-query-snapshot-isolation:" (count failures) "FAILED")
        (System/exit 1))
    (println "\ncoord-query-snapshot-isolation:" (count @checks) "/" (count @checks) "PASS")))
