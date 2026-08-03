;; S0 render bootstrap — the instrument the store migration's later stages are
;; measured with. Two invariants, both observed, never asserted from a list:
;;   1. unchanged graph-upstream modules render byte-identical off a native corpus;
;;   2. the render closure loads NONE of the authoring namespaces that stages
;;      S1-S2 are repairing, so a broken authoring stack cannot break the check.
(require '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(def checks (atom []))
(defn check! [label value]
  (println (str (if value "[PASS] " "[FAIL] ") label))
  (swap! checks conj [label (boolean value)]))

(def fram-root (.getCanonicalPath (io/file ".")))
(def beagle
  (or (System/getenv "FRAM_BEAGLE")
      (str (System/getProperty "user.home") "/code/beagle/main/bin/beagle")))

;; Two adopted graph-upstream modules, unchanged in the checkout. Overridable so a
;; later stage can widen the bar without editing the check.
(def sources
  (str/split (or (not-empty (System/getenv "FRAM_S0_SOURCES"))
                 "src/fram/text_index.bclj,src/coord_read.bclj")
             #","))
(def modules
  (str/split (or (not-empty (System/getenv "FRAM_S0_MODULES"))
                 "fram.text_index,coord_read")
             #","))

;; every namespace the render closure must NOT pull in: the removed-API casualties
;; plus the whole authoring layer they belong to.
(def forbidden-namespaces
  #{"fram.schema" "fram.claims" "fram.tools" "codegraph" "pull" "coord-read"
    "rename" "supersession-check" "rep-jurisdiction" "roundtrip-fram" "resolve"
    "resolve-core" "resolve-read" "resolve-binds" "resolve-modules"
    "resolve-render" "resolve-query" "resolve-walk" "resolve-corpus"
    "resolve-mint" "resolve-verbs"})

(def scratch
  (.toFile (java.nio.file.Files/createTempDirectory
            "fram-s0-render-" (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (str (io/file scratch "code.framlog")))
(def space "s0-render-bootstrap-test")

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))] (io/delete-file file true)))

(defn- render-tool [& args]
  (apply shell/sh "env" (str "FRAM_BEAGLE=" beagle) "FRAM_DAEMON_QUIET=1"
         (str (io/file fram-root "bin/fram-render-code-native"))
         "--log" log-path "--space-id" space "--root" fram-root
         (concat args [:dir fram-root])))

(try
  (let [ingest (apply shell/sh "env" (str "FRAM_BEAGLE=" beagle)
                      (str (io/file fram-root "bin/fram-ingest-code"))
                      (concat sources
                              ["--root" "src" "--out" log-path
                               "--space-id" space :dir fram-root]))]
    (check! "ingest builds a native corpus from the unchanged sources"
            (zero? (:exit ingest)))
    (when (zero? (:exit ingest))
      (let [verify (render-tool "--modules" (str/join "," modules) "--verify")
            graph (render-tool "--require-graph")
            loaded (into #{} (remove #(or (str/blank? %) (str/starts-with? % ";")))
                         (str/split-lines (:out graph)))]
        (println (:out verify))
        (check! (str "every requested module renders byte-identical ("
                     (str/join " " modules) ")")
                (zero? (:exit verify)))
        (check! "verify reports one identical row per module"
                (= (count modules)
                   (count (re-seq #"(?m)^IDENTIC " (:out verify)))))
        (check! "render closure loads the reader path"
                (contains? loaded "fram.code-reader"))
        (check! "render closure loads NO authoring namespace"
                (empty? (filter forbidden-namespaces loaded)))
        (when-let [leaked (seq (filter forbidden-namespaces loaded))]
          (println "  leaked:" (str/join " " leaked))))))
  (finally (delete-tree! scratch)))

(let [failures (remove second @checks)]
  (if (seq failures)
    (do (println (str "\ns0 render bootstrap: " (count failures) " FAILED"))
        (System/exit 1))
    (println (str "\ns0 render bootstrap: " (count @checks) "/"
                  (count @checks) " PASS"))))
