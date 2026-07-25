;; ============================================================================
;; coord_edit_min_core_test.clj — IN-PROCESS green-core proof for the minimal-op
;; authoring path (no socket, no Beagle → no #14 flakiness). Proves do-edit-min
;; commits the verb's exact delta for the SAFE-on-spelling verbs (set-body /
;; upsert-form) and that graph RENAME is the O(1) shadow-correct spelling-change
;; verb — it renames the definition's name claim (references follow refers_to),
;; and is GUARDED (a colliding rename REJECTS with no mutation).
;;   bb -cp out coord_edit_min_core_test.clj
;;
;; HERMETIC: boots over the committed single-module fixture
;; tests/fixtures/edit-min/schema.code.factlog (exactly one canonical `schema`
;; module) — never the worktree's live .fram/code.log, so `scope "schema"` is
;; unambiguous, spell-counts are module-exact, and the run is order-independent.
;; ============================================================================
(require '[fram.store :as c] '[fram.schema :as s] '[clojure.edn :as edn] '[clojure.java.io :as io])
(def root (System/getProperty "user.dir"))
(def fixture (str root "/tests/fixtures/edit-min/schema.code.factlog"))
(defn- fail-fast! [& xs]
  (binding [*out* *err*] (apply println "FAIL —" xs))
  (System/exit 1))
(when-not (.isFile (io/file fixture)) (fail-fast! "missing required fixture" fixture))
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
(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-core-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat))
(boot-flat! flat)
(def st (:store @co))
(def Vp (c/value-id st "v"))
(defn spell-count [s] (count (filter (fn [cid] (= s (c/literal st (:r (c/fact-of st cid))))) (c/by-p st Vp))))
(def fails (atom 0))
(defn chk [name ok] (if ok (println "  [PASS]" name) (do (swap! fails inc) (println "  [FAIL]" name))))

;; edit-min! mirrors the daemon's :edit-min handler arm EXACTLY — (try (do-edit-min ...)
;; (catch Throwable -> {:reject ...})) — so the rename guard's throw becomes the same
;; {:reject ...} a client sees, without paying handle's per-call maybe-reload! re-fold.
(defn edit-min! [spec]
  (try (do-edit-min spec)
       (catch Throwable t {:reject [(str "edit-min: " (.getMessage t))]
                           :reject-data (ex-data t)
                           :version (current-seq @co)})))

;; --- set-body: commit a new body carrying a UNIQUE marker token, in-process -----
(def marker "uniqzzqmarker")
(def before-marker (spell-count marker))
(def sb (edit-min! {:op "set-body" :module "schema" :name "cardinality"
                    :datum (edn/read-string (str "(if (some? (c/value-id ctx pname)) (" marker " pname) \"multi\")"))}))
(chk "set-body committed (ok, ops>0)" (and (:ok sb) (pos? (or (:ops sb) 0))))
(chk "set-body's new marker token landed in the store" (and (zero? before-marker) (pos? (spell-count marker))))

;; --- upsert-form: add a NEW top-level def, in-process ---------------------------
(def newfn "uniqzzqnewdef")
(def uf (edit-min! {:op "upsert-form" :module "schema"
                    :datum (edn/read-string (str "(defn " newfn " [x] x)"))}))
(chk "upsert-form committed (ok, ops>0)" (and (:ok uf) (pos? (or (:ops uf) 0))))
(chk "upsert-form's new def name landed in the store" (pos? (spell-count newfn)))

;; --- #25 LOCK: upsert-form is NAME-keyed — a REPLACE preserves the def name (CANNOT rename) ---
;; This is the receipt behind the rename-ONLY guard's sufficiency: the only :edit-min verb that
;; changes a binding's spelling is `rename` (guarded). If a future refactor made upsert node/
;; position-keyed, a (defn cardinality …) -> different-name swap could rename past the guard;
;; this asserts it doesn't — the name survives a body replace.
(def card-before (spell-count "cardinality"))
(def upmark "uniqzzqupsertrepl")
(def uf2 (edit-min! {:op "upsert-form" :module "schema"
                     :datum (edn/read-string (str "(defn cardinality [ctx pname] (" upmark " pname))"))}))
(chk "upsert-form REPLACE of existing def committed" (:ok uf2))
(chk "upsert REPLACE PRESERVED the def name (cardinality still present — not renamed)"
     (and (pos? card-before) (pos? (spell-count "cardinality"))))
(chk "upsert REPLACE landed the new body token" (pos? (spell-count upmark)))

;; --- rename: the O(1) shadow-correct verb — edits ONLY the definition's name claim
;; (asserts the new spelling, supersedes the old); references follow refers_to at render
;; (tests/coord_edit_min_rename.clj is the render+recompile receipt). This is the #25-LOCK
;; spelling-change verb: the ONLY :edit-min verb that moves a binding's name.
(def before-replace (spell-count "replace!"))
(def before-new (spell-count "supersede-prior!"))
(def rn (edit-min! {:op "rename" :module "schema" :old "replace!" :new "supersede-prior!"}))
(chk "graph rename committed (ok)" (:ok rn))
(chk "rename is minimal — 2 ops (assert new name + supersede old)" (= 2 (:ops rn)))
(chk "rename landed the NEW spelling (supersede-prior! now bound)"
     (and (zero? before-new) (pos? (spell-count "supersede-prior!"))))
(chk "rename is identity-preserving — the old name claim was superseded, not duplicated"
     (= (dec before-replace) (spell-count "replace!")))
;; GUARD: renaming ONTO an already-bound name is REJECTED with no facts mutated.
(def guard-before (spell-count "cardinality"))
(def guard-log-before (slurp flat))
(def rn-collide (edit-min! {:op "rename" :module "schema" :old "lookup" :new "cardinality"}))
(chk "rename onto an existing binding REJECTED (guarded, not :ok)" (not (:ok rn-collide)))
(chk "rename collision reached the verb's guarded code-3 rejection"
     (= {:reject :verb :code 3}
        (select-keys (:reject-data rn-collide) [:reject :code])))
(chk "rejected colliding rename mutated NOTHING (exact log bytes unchanged)"
     (and (= guard-before (spell-count "cardinality"))
          (= guard-log-before (slurp flat))))

;; --- #26: unknown verb rejects cleanly (must NOT hard-exit the daemon) ---
;; If the unknown-op path still fell through to run-verb-warm!'s (System/exit 2), this whole
;; test JVM would die here and NONE of the assertions below would run — so reaching them is itself
;; the proof that the daemon does not hard-exit on a malformed :edit-min verb.
(def bogus (edit-min! {:op "bogus-verb-zzz" :module "schema"}))
(chk "unknown :edit-min verb rejected (not :ok)" (not (:ok bogus)))
(chk "unknown-verb reject names it as unknown" (boolean (re-find #"(?i)unknown" (pr-str (:reject bogus)))))
(chk "process continued after unknown-verb (no hard-exit)" true)

(println (str "\n---- edit-min CORE (in-process): " (if (zero? @fails) "ALL PASS" (str @fails " FAIL")) " ----"))
(System/exit (if (zero? @fails) 0 1))
