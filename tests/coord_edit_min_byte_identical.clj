;; ============================================================================
;; coord_edit_min_byte_identical.clj — CORRECTNESS (non-negotiable):
;; the minimal-op (:edit-min) edit's render(log) must be BYTE-IDENTICAL to the
;; WHOLE-MODULE path's render(log) for the SAME edit. Same outcome, minimal
;; mechanism. Runs a non-trivial set-body (a multi-binding let body) through BOTH
;; paths over two independent disposable copies of the committed fixture. The
;; whole-module candidate is then passed, byte-for-byte, to fram-commit-code and
;; the committed log is rendered again. PASS requires candidate == published view
;; == minimal-op view, so render-only output cannot stand in for publication.
;;   bb -cp out coord_edit_min_byte_identical.clj
;; ============================================================================
(require '[fram.store :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[babashka.process :as proc])
(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (str beagle-home "/beagle-lib/private/facts-roundtrip.rkt"))
;; HERMETIC: the committed single-module fixture (exactly one canonical `schema`),
;; never the worktree's live .fram/code.log. FRAM_BIN pinned to the worktree bin so
;; sub-invocations never reach an ambient live-deployment FRAM_BIN.
(def fixture (str root "/tests/fixtures/edit-min/schema.code.factlog"))
(def source-path (str root "/src/fram/schema.bclj"))
(def child-env
  (merge (into {} (remove (fn [[k _]] (or (str/starts-with? k "FRAM_")
                                            (= k "RESOLVE_OUT")))
                           (System/getenv)))
         {"BEAGLE_HOME" beagle-home
          "FRAM_HOME" root
          "FRAM_OUT" (str root "/out")
          "FRAM_ROUNDTRIP" roundtrip-rkt
          "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")
          "FRAM_BIN" (str root "/bin")}))
(defn- fail-fast! [& xs]
  (binding [*out* *err*] (apply println "FAIL —" xs))
  (System/exit 1))
(doseq [p [fixture source-path roundtrip-rkt]]
  (when-not (.isFile (io/file p)) (fail-fast! "missing required file" p)))
(def fixture-roots
  (with-open [reader (io/reader fixture)]
    (reduce (fn [roots line]
              (let [{:keys [op l p r]} (edn/read-string line)
                    root [l r]]
                (if (= "file" p)
                  (case op
                    "assert" (conj roots root)
                    "retract" (disj roots root)
                    roots)
                  roots)))
            #{} (line-seq reader))))
(when-not (= #{["@schema#root" "src/fram/schema.bclj"]} fixture-roots)
  (fail-fast! "fixture must contain exactly the canonical schema root; found" (pr-str fixture-roots)))
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))

(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(defn- read-bytes [path]
  (when (.isFile (io/file path))
    (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))
(defn- bytes= [a b]
  (boolean (and a b (java.util.Arrays/equals ^bytes a ^bytes b))))
(defn render-schema! [flat tag]
  (let [work (str (System/getProperty "java.io.tmpdir") "/byte-id-" (System/nanoTime))
        out (str work "/schema-" tag ".bclj")]
    (.mkdirs (io/file work))
    (let [result (proc/shell {:continue true :env child-env :out :string :err :string}
                             "bb" "-cp" "out" "bin/fram-render-code"
                             "schema" "--log" flat "--out" out)]
      (when-not (zero? (:exit result))
        (binding [*out* *err*]
          (println "render failed for" tag (str/trim (str (:out result) (:err result))))))
      {:result result :path out :bytes (when (zero? (:exit result)) (read-bytes out))})))

;; Fixture provenance is executable, not prose-only: its exact canonical module
;; must render byte-identically to the graph-projected source it was generated from.
(def fixture-render (render-schema! fixture "fixture"))
(def fixture-provenance?
  (and (zero? (:exit (:result fixture-render)))
       (bytes= (:bytes fixture-render) (read-bytes source-path))))

(def new-body
  (edn/read-string
    (str "(let [p (c/value-id ctx pname) cp (c/value-id ctx \"cardinality\") "
         "cs (if (and (some? p) (some? cp)) (c/by-lp ctx p cp) [])] "
         "(if (empty? cs) \"multi\" (c/literal ctx (:r (c/fact-of ctx (first cs))))))")))

;; ---- PATH 1: minimal-op (:edit-min) over a fresh daemon --------------------
(def flat-min (str (System/getProperty "java.io.tmpdir") "/byte-id-min-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat-min))
(def port (or (some #(when (port-free? %) %) [8150 8151 8152 8153]) 8150))
(boot-flat! flat-min)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
(def resp-min (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "cardinality" :datum new-body}}))
(println "minimal-op:" (pr-str resp-min))
(def render-min (render-schema! flat-min "minimal"))
(shutdown!)

;; ---- PATH 2: whole-module candidate, actual commit, post-commit render --------
;; --no-commit produces the whole-module candidate. That exact file is then the
;; positional input to fram-commit-code against a disposable coordinator. Rendering
;; the resulting log proves whether the candidate was actually consumed/published.
(def flat-whole (str (System/getProperty "java.io.tmpdir") "/byte-id-whole-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat-whole))
(def body-file (str (System/getProperty "java.io.tmpdir") "/byte-id-body-" (System/nanoTime) ".edn"))
(spit body-file (pr-str new-body))
(def whole-out (str (System/getProperty "java.io.tmpdir") "/byte-id-whole-render-" (System/nanoTime) ".bclj"))
(def whole (proc/shell {:continue true :env child-env :out :string :err :string}
                       "bb" "-cp" "out" "bin/fram-edit-code" "set-body" "schema"
                       "--name" "cardinality" "--body-file" body-file
                       "--whole-module" "--no-commit" "--out" whole-out "--log" flat-whole))
(println "whole-module (--no-commit) exit:" (:exit whole))
(when-not (zero? (:exit whole)) (println (str/trim (str (:out whole) (:err whole)))))
(def candidate-before (read-bytes whole-out))

(def port2 (or (some #(when (port-free? %) %) [8160 8161 8162 8163]) 8160))
(boot-flat! flat-whole)
(def server2 (future (serve port2)))
(Thread/sleep 500)
(defn- shutdown2! [] (try (future-cancel server2) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown2!))
(def whole-status (client port2 {:op :status}))
(def committed
  (proc/shell {:continue true :env child-env :out :string :err :string}
              "bb" "-cp" "out" "bin/fram-commit-code"
              "schema" whole-out "--port" (str port2) "--log" flat-whole))
(println "whole-module commit exit:" (:exit committed))
(when-not (zero? (:exit committed))
  (println (str/trim (str (:out committed) (:err committed)))))
(def candidate-after (read-bytes whole-out))
(def render-published (render-schema! flat-whole "published"))
(shutdown2!)

;; ---- compare ----------------------------------------------------------------
(println "\n=== CORRECTNESS — minimal render == exact committed whole-module candidate ===")
(def candidate-unchanged? (bytes= candidate-before candidate-after))
(def candidate-published? (bytes= candidate-before (:bytes render-published)))
(def identical? (bytes= (:bytes render-min) (:bytes render-published)))
(def pass (and fixture-provenance?
               (:ok resp-min)
               (zero? (:exit (:result render-min)))
               (zero? (:exit whole))
               (= flat-whole (str (:log whole-status)))
               (zero? (:exit committed))
               (zero? (:exit (:result render-published)))
               candidate-unchanged?
               candidate-published?
               identical?))
(println "  fixture render == source bytes:" fixture-provenance?)
(println "  minimal render bytes:" (some-> (:bytes render-min) alength))
(println "  candidate bytes:     " (some-> candidate-before alength))
(println "  published bytes:     " (some-> (:bytes render-published) alength))
(println "  candidate unchanged while consumed:" candidate-unchanged?)
(println "  candidate == published render:      " candidate-published?)
(println "  minimal == published render:        " identical?)
(when (and (:bytes render-min) (:bytes render-published) (not identical?))
  (let [lm (str/split-lines (String. ^bytes (:bytes render-min) "UTF-8"))
        lw (str/split-lines (String. ^bytes (:bytes render-published) "UTF-8"))]
    (println "  first differing line:")
    (let [d (first (keep-indexed (fn [i [a b]] (when (not= a b) [i a b])) (map vector lm lw)))]
      (when d (println "   line" (first d) "\n   min:   " (pr-str (nth d 1)) "\n   whole: " (pr-str (nth d 2)))))))
(if pass
  (do (println "\nGATE (correctness) PASS — exact whole-module candidate was committed and published byte-identically to the minimal result.") (System/exit 0))
  (do (println "\nGATE (correctness) FAIL") (System/exit 1)))
