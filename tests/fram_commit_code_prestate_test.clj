;; Regression: fram-commit-code must derive PRE from the daemon's module-scoped
;; render, not a whole-corpus triple scan that can exhaust the query budget.
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(binding [*command-line-args* []]
  (load-file "server.clj"))

(def fixture "tests/fixtures/edit-min/schema.code.factlog")
(def tmp-dir
  (io/file (System/getProperty "java.io.tmpdir")
           (str "fram-commit-prestate-test-" (System/nanoTime))))
(.mkdirs tmp-dir)
(def fixture-copy (io/file tmp-dir "schema.code.factlog"))
(io/copy (io/file fixture) fixture-copy)
(boot-flat! (str fixture-copy))

(def all-triples-query
  {:find "out"
   :rules [{:head {:rel "out" :args [{:var "l"} {:var "p"} {:var "r"}]}
            :body [{:rel "triple" :args [{:var "l"} {:var "p"} {:var "r"}]}]}]})

(defn ast-pred? [p]
  (or (#{"kind" "v" "child" "tail" "style" "placement"} p)
      (boolean (re-matches #"(f|seg|comment)\d+" p))))

(defn module-ast [rows]
  (->> rows
       (map #(mapv str %))
       (filter #(str/starts-with? (first %) "@schema#"))
       (filter #(ast-pred? (second %)))
       set))

(defn emitted-module-ast [text]
  (->> (str/split-lines text)
       (keep (fn [line]
               (when (str/starts-with? line "[")
                 (let [[s p o] (edn/read-string line)]
                   [(str "@schema#" s) (str p)
                    (if (integer? o) (str "@schema#" o) (str o))]))))
       (filter #(ast-pred? (second %)))
       set))

(def beagle-home (or (System/getenv "BEAGLE_HOME") (System/getenv "BEAGLE")
                     (str (System/getProperty "user.home") "/code/beagle")))
(def beagle-bin (or (System/getenv "FRAM_BEAGLE")
                    (str beagle-home "/bin/beagle")))
(when-not (.canExecute (io/file beagle-bin))
  (println "SKIP — missing prerequisite: Beagle CLI (" beagle-bin ")")
  (System/exit 0))

(def constrained-scan
  (handle {:op :query :query all-triples-query :scan true
           :query-max-steps 1}))
(def full-scan (handle {:op :query :query all-triples-query :scan true}))
(def module-render (handle {:op :render :module "schema"}))

(def rendered-edn (io/file tmp-dir "schema.edn"))
(def rendered-bclj (io/file tmp-dir "schema.bclj"))

(def checks (atom []))
(defn check! [label ok]
  (swap! checks conj [label (boolean ok)]))

(try
  (spit rendered-edn (:edn module-render))
  (let [rendered (proc/sh {:out :string :err :string}
                          beagle-bin "facts-roundtrip" "--render" (str rendered-edn))]
    (check! "module render converts back to Beagle" (zero? (:exit rendered)))
    (when (zero? (:exit rendered))
      (spit rendered-bclj (:out rendered))
      (let [emitted (proc/sh {:out :string :err :string}
                             beagle-bin "facts-roundtrip" "--emit-edn"
                             (str rendered-bclj))
            scan-ast (module-ast (:ok full-scan))
            render-ast (emitted-module-ast (:out emitted))]
        (check! "module render re-emits successfully" (zero? (:exit emitted)))
        (check! "module render/re-emit is exact PRE AST"
                (= scan-ast render-ast)))))
  (finally
    (.delete rendered-bclj)
    (.delete rendered-edn)
    (.delete (io/file tmp-dir ".fram.rewrite.lock"))
    (.delete fixture-copy)
    (.delete tmp-dir)))

(let [source (slurp "bin/fram-commit-code")]
  (check! "old whole-corpus scan exhausts a bounded query"
          (= :query-work-limit (:code constrained-scan)))
  (check! "commit helper no longer requests a scan"
          (not (str/includes? source ":scan true")))
  (check! "commit helper requests module-scoped render"
          (str/includes? source "{:op :render :module module}")))

(doseq [[label ok] @checks]
  (println (if ok "ok -" "FAIL -") label))
(let [failed (remove second @checks)]
  (if (empty? failed)
    (do (println "fram_commit_code_prestate_test.clj: all assertions passed")
        (System/exit 0))
    (do (println "fram_commit_code_prestate_test.clj: FAILURES ABOVE")
        (System/exit 1))))
