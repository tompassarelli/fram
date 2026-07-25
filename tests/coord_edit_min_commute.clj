;; ============================================================================
;; coord_edit_min_commute.clj — GATE 2 (THE MAKE-OR-BREAK): two edits to DIFFERENT
;; defns in the SAME module COMMUTE through ONE warm coordinator.
;; ============================================================================
;; The thesis in miniature: disjoint same-module edits must BOTH survive — the exact
;; thing the whole-module path (git's weakness) breaks (second clobbers first /
;; false-conflict). We set-body fnA (`cardinality`) AND set-body fnB (`lookup`) in
;; `schema` through the SAME warm :edit-min daemon. PASS = BOTH new bodies present in
;; render(log) AND schema recompiles 1/0 AND neither got :conflict. Then we run the
;; WHOLE-MODULE path on the SAME scenario and show it does NOT commute (the contrast).
;;   bb -cp out coord_edit_min_commute.clj
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

(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(defn render-schema [flat tag]
  (let [out (str (System/getProperty "java.io.tmpdir") "/commute-" tag "-" (System/nanoTime) ".bclj")]
    (let [r (proc/shell {:continue true :env child-env :out :string :err :string}
                        "bb" "-cp" "out" "bin/fram-render-code"
                        "schema" "--log" flat "--out" out)]
      (when-not (zero? (:exit r))
        (binding [*out* *err*]
          (println "render failed for" tag (str/trim (str (:out r) (:err r))))))
      (when (and (zero? (:exit r)) (.isFile (io/file out))) (slurp out)))))
(defn recompiles? [render-txt]
  (let [work (str (System/getProperty "java.io.tmpdir") "/commute-rc-" (System/nanoTime))
        src (str work "/src") out (str work "/out")]
    (.mkdirs (io/file src)) (.mkdirs (io/file out))
    (spit (str src "/schema.bclj") render-txt)
    (let [r (proc/shell {:continue true :env child-env :out :string :err :string}
                        build-all src "--out" out)]
      (boolean (re-find #"\b1 built, 0 error\(s\)" (str (:out r) (:err r)))))))

;; two DISJOINT, type-correct bodies. cardinality :- String ; lookup :- Any.
(def body-card (edn/read-string "(if (some? (c/value-id ctx pname)) \"single-or-multi\" \"multi\")"))
(def body-lookup (edn/read-string "(first (lookup-all ctx subj pname))"))
;; markers proving each NEW body landed (and the OLD one is gone).
(def card-marker "single-or-multi")
(def lookup-marker "(first (lookup-all ctx subj pname))")

;; ============================================================================
;; PATH 1 — :edit-min through ONE warm daemon (the fact path).
;; ============================================================================
(def flat (str (System/getProperty "java.io.tmpdir") "/commute-min-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat))
(def port (or (some #(when (port-free? %) %) [8170 8171 8172 8173]) 8170))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))

;; both edits go through the SAME warm coordinator. Each :edit-min clones the live
;; store and commits ONLY its own (te,p) groups — cardinality's body fN vs lookup's
;; body fN are DISJOINT groups, so neither OCC-conflicts the other.
(def edit-a (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "cardinality" :datum body-card}}))
(def edit-b (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "lookup" :datum body-lookup}}))
(println "edit A (cardinality):" (pr-str edit-a))
(println "edit B (lookup)     :" (pr-str edit-b))
(def no-conflict (and (:ok edit-a) (:ok edit-b)
                      (not (and (:reject edit-a) (str/includes? (str (:reject edit-a)) "conflict")))
                      (not (and (:reject edit-b) (str/includes? (str (:reject edit-b)) "conflict")))))
(def render-min (render-schema flat "min"))
(def both-present (boolean (and render-min
                                (str/includes? render-min card-marker)
                                (str/includes? render-min lookup-marker))))
(def min-recompiles (and render-min (recompiles? render-min)))
(shutdown!)

;; ============================================================================
;; PATH 2 — WHOLE-MODULE on the SAME scenario (the contrast), applied for real.
;; Two stale whole-module candidates are rendered from byte-identical copies of ONE
;; pristine base. Candidate A and candidate B therefore cannot have observed each
;; other. Both are then accepted, in order, by one coordinator over another pristine
;; copy. The post-A render must carry A; the post-B render must carry B and have LOST A.
;; That observed state transition is the clobber proof — candidate inspection alone is
;; not evidence that the legacy commit mechanism actually behaves this way.
;; ============================================================================
(def bf-card (str (System/getProperty "java.io.tmpdir") "/commute-bc-" (System/nanoTime) ".edn"))
(def bf-lookup (str (System/getProperty "java.io.tmpdir") "/commute-bl-" (System/nanoTime) ".edn"))
(spit bf-card (pr-str body-card)) (spit bf-lookup (pr-str body-lookup))
(def pristine-a (str (System/getProperty "java.io.tmpdir") "/commute-pa-" (System/nanoTime) ".code.log"))
(def pristine-b (str (System/getProperty "java.io.tmpdir") "/commute-pb-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file pristine-a))
(io/copy (io/file fixture) (io/file pristine-b))
(def same-base? (and (= (slurp fixture) (slurp pristine-a))
                     (= (slurp fixture) (slurp pristine-b))))
(def render-a (str (System/getProperty "java.io.tmpdir") "/commute-ra-" (System/nanoTime) ".bclj"))
(def render-b (str (System/getProperty "java.io.tmpdir") "/commute-rb-" (System/nanoTime) ".bclj"))
(def candidate-a
  (proc/shell {:continue true :env child-env :out :string :err :string}
              "bb" "-cp" "out" "bin/fram-edit-code" "set-body" "schema"
              "--name" "cardinality" "--body-file" bf-card
              "--whole-module" "--no-commit" "--out" render-a "--log" pristine-a))
(def candidate-b
  (proc/shell {:continue true :env child-env :out :string :err :string}
              "bb" "-cp" "out" "bin/fram-edit-code" "set-body" "schema"
              "--name" "lookup" "--body-file" bf-lookup
              "--whole-module" "--no-commit" "--out" render-b "--log" pristine-b))
(def txt-a (when (.exists (io/file render-a)) (slurp render-a)))
(def txt-b (when (.exists (io/file render-b)) (slurp render-b)))
(def candidates-stale?
  (boolean (and same-base?
                (zero? (:exit candidate-a)) (zero? (:exit candidate-b))
                txt-a txt-b
                (str/includes? txt-a card-marker)
                (not (str/includes? txt-a lookup-marker))
                (str/includes? txt-b lookup-marker)
                (not (str/includes? txt-b card-marker)))))

(def flat-w (str (System/getProperty "java.io.tmpdir") "/commute-whole-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat-w))
(def portw (or (some #(when (port-free? %) %) [8180 8181 8182 8183]) 8180))
(boot-flat! flat-w)
(def serverw (future (serve portw)))
(Thread/sleep 500)
(defn- shutdownw! [] (try (future-cancel serverw) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdownw!))
(def whole-status (client portw {:op :status}))
(def commit-a
  (proc/shell {:continue true :env child-env :out :string :err :string}
              "bb" "-cp" "out" "bin/fram-commit-code"
              "schema" render-a "--port" (str portw) "--log" flat-w))
(def after-a (render-schema flat-w "whole-after-a"))
(def commit-b
  (proc/shell {:continue true :env child-env :out :string :err :string}
              "bb" "-cp" "out" "bin/fram-commit-code"
              "schema" render-b "--port" (str portw) "--log" flat-w))
(def after-b (render-schema flat-w "whole-after-b"))
(shutdownw!)

(def a-published?
  (boolean (and after-a
                (str/includes? after-a card-marker)
                (not (str/includes? after-a lookup-marker)))))
(def b-clobbered-a?
  (boolean (and after-b
                (str/includes? after-b lookup-marker)
                (not (str/includes? after-b card-marker)))))
(def whole-applied?
  (and (= flat-w (str (:log whole-status)))
       (zero? (:exit commit-a))
       (zero? (:exit commit-b))
       a-published?
       b-clobbered-a?))

;; ============================================================================
(println "\n=== GATE 2 — COMMUTATION (two disjoint same-module edits) ===")
(println (format "  [fact/:edit-min] edit A ok=%s  edit B ok=%s  no-conflict=%s"
                 (boolean (:ok edit-a)) (boolean (:ok edit-b)) no-conflict))
(println (format "  [fact/:edit-min] BOTH bodies present in render(log): %s" both-present))
(println (format "  [fact/:edit-min] render(log) recompiles 1/0:        %s" min-recompiles))
(println (format "  [whole-module]    candidates share exact pristine base: %s" same-base?))
(println (format "  [whole-module]    stale candidates each carry own edit: %s" candidates-stale?))
(println (format "  [whole-module]    commit A accepted and published A:    %s" a-published?))
(println (format "  [whole-module]    commit B accepted, B present, A LOST: %s" b-clobbered-a?))
(when-not (zero? (:exit commit-a))
  (println "  whole commit A output:" (str/trim (str (:out commit-a) (:err commit-a)))))
(when-not (zero? (:exit commit-b))
  (println "  whole commit B output:" (str/trim (str (:out commit-b) (:err commit-b)))))

(def pass (and no-conflict both-present min-recompiles candidates-stale? whole-applied?))
(if pass
  (do (println "\nGATE 2 PASS — minimal graph edits preserved both disjoint changes; "
               "the same-base stale whole-module sequence accepted both commits and the second demonstrably clobbered the first.")
      (System/exit 0))
  (do (println "\nGATE 2 FAIL — the crown jewel is dead: disjoint same-module edits did NOT both survive.")
      (System/exit 1)))
