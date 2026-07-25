;; ============================================================================
;; coord_edit_min_correctness.clj — Build A correctness + opcount-scaling.
;; ============================================================================
;; ONE warm daemon over a disposable copy of the committed fixture. Proves:
;;  (A) op count SCALES with the edited body (a literal body => a HANDFUL of ops),
;;  (B) CORRECTNESS: render(log) after the minimal-op edit carries the exact new
;;      body, drops the old body, and the single edited module recompiles 1/0.
;; Byte identity against a truly published whole-module candidate is the separate
;; coord_edit_min_byte_identical receipt.
;;   bb -cp out coord_edit_min_correctness.clj
;; ============================================================================
(require '[fram.store :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[babashka.process :as proc])

(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (str beagle-home "/beagle-lib/private/facts-roundtrip.rkt"))
(def build-all (str beagle-home "/bin/beagle-build-all"))
;; HERMETIC: the committed single-module fixture (exactly one canonical `schema`),
;; never the worktree's live .fram/code.log. FRAM_BIN pinned to the worktree bin so
;; sub-invocations never reach an ambient live-deployment FRAM_BIN.
(def fixture (str root "/tests/fixtures/edit-min/schema.code.factlog"))
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
(doseq [p [fixture roundtrip-rkt build-all]]
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

(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-corr-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) [8130 8131 8132 8133]) 8130))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
(def status (client port {:op :status}))
(when-not (and (= flat (str (:log status))) (pos? (:facts status)))
  (println "ABORT wrong log" (pr-str (:log status))) (shutdown!) (System/exit 1))
(println "daemon up:" (:facts status) "facts, port" port)

(def checks (atom []))
(defn chk [nm ok & [extra]] (swap! checks conj [nm (boolean ok) extra]))

;; (A) opcount scaling: a TRULY trivial body (single string literal) => a TINY op
;; count. set-body of cardinality (schema, returns String) to "multi" — a one-node
;; body that is ALSO type-correct (so it recompiles).
(def tiny (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "cardinality" :datum "multi"}}))
(println "\ntiny set-body (cardinality body = \"multi\"):" (pr-str tiny))
(chk "OPCOUNT scales: trivial body commits a TINY op count (<= 8)"
     (and (:ok tiny) (<= (:ops tiny) 8)) (str (:ops tiny) " ops"))

;; (B) CORRECTNESS: render(log) after the minimal edit, and confirm the body landed +
;; recompiles. Render the edited `schema` module FROM the (now-mutated) flat log.
(def work (str (System/getProperty "java.io.tmpdir") "/edit-min-corr-work-" (System/nanoTime)))
(.mkdirs (io/file work))
(def rendered (str work "/schema.bclj"))
(def render-result
  (proc/shell {:continue true :env child-env :out :string :err :string}
              "bb" "-cp" "out" "bin/fram-render-code"
              "schema" "--log" flat "--out" rendered))
(chk "CORRECTNESS: graph render command exits 0"
     (zero? (:exit render-result))
     (when-not (zero? (:exit render-result))
       (str/trim (str (:out render-result) (:err render-result)))))
(def rendered-txt (when (.exists (io/file rendered)) (slurp rendered)))
;; the cardinality defn line: its body is now the literal "multi" (one node), and the
;; OLD let-body (the `(let [pid ...])` block) is GONE — the edit replaced it.
(def card-line (when rendered-txt
                 (first (filter #(str/includes? % "defn cardinality") (str/split-lines rendered-txt)))))
;; the cardinality defn now renders on ONE line (its body is a single literal), and
;; that line carries "multi" and NOT the old `let` block — proof the body was replaced.
(def body-landed (boolean (and card-line
                               (str/includes? card-line "\"multi\"")
                               (not (str/includes? card-line "let")))))
(chk "CORRECTNESS: render(log) shows cardinality's new body (\"multi\") and old let-body gone" body-landed)
;; recompile the rendered module (drop it in a one-file src tree).
(def src (str work "/src")) (.mkdirs (io/file src))
(when (.exists (io/file rendered)) (io/copy (io/file rendered) (io/file (str src "/schema.bclj"))))
(def out (str work "/out")) (.mkdirs (io/file out))
(def bres (when (.exists (io/file (str src "/schema.bclj")))
            (proc/shell {:continue true :env child-env :out :string :err :string}
                        build-all src "--out" out)))
(def recompiles (boolean (and bres (re-find #"\b1 built, 0 error\(s\)" (str (:out bres) (:err bres))))))
(chk "CORRECTNESS: render(log) of the edited module recompiles (1 built, 0 error)" recompiles)

(shutdown!)
(println "\n=== Build A — minimal-op edit: correctness + scaling ===")
(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok extra] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm (if extra (str "(" extra ")") "")))
  (if (empty? fails)
    (do (println "\nBuild A:" (count cs) "/" (count cs) "PASS") (System/exit 0))
    (do (println "\nBuild A:" (count fails) "FAILED") (System/exit 1))))
