;; ============================================================================
;; cnf_edit_min_core_test.clj — IN-PROCESS green-core proof for the minimal-op
;; authoring path (no socket, no Beagle → no #14 flakiness). Proves do-edit-min
;; commits the verb's exact delta for the SAFE-on-spelling verbs (set-body /
;; upsert-form) and that graph RENAME is IMPLEMENTED via bound_to (cut-3b
;; persist-bound-for-rename!): the def spelling is rewritten and the old-spelling
;; references are BOUND to the renamed def, so the rename is identity-coherent
;; (no dangling ref) rather than a silent def-only rewrite.
;;   bb -cp out cnf_edit_min_core_test.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s] '[clojure.edn :as edn] '[clojure.java.io :as io])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-core-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)
(def st (:store @co))
(def Vp (c/value-id st "v"))
(defn spell-count [s] (count (filter (fn [cid] (= s (c/literal st (:r (c/claim-of st cid))))) (c/by-p st Vp))))
(def fails (atom 0))
(defn chk [name ok] (if ok (println "  [PASS]" name) (do (swap! fails inc) (println "  [FAIL]" name))))

;; edit-min! mirrors the daemon's :edit-min handler arm EXACTLY — (try (do-edit-min ...)
;; (catch Throwable -> {:reject ...})) — so the rename guard's throw becomes the same
;; {:reject ...} a client sees, without paying handle's per-call maybe-reload! re-fold.
(defn edit-min! [spec]
  (try (do-edit-min spec)
       (catch Throwable t {:reject [(str "edit-min: " (.getMessage t))] :version (current-seq @co)})))

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

;; --- rename: IMPLEMENTED via bound_to (cut-3b persist-bound-for-rename!) ---------
;; The resolver-woven port made graph rename real: it rewrites the DEF spelling and
;; installs a bound_to claim from each old-spelling REFERENCE to the renamed def, so
;; the refs resolve (lazy O(1) identity rename) — NOT a dangling def-only rewrite (the
;; gate-v2 hazard). Used to be identity-deferred (rejected); now it commits coherently.
(def before-replace (spell-count "replace!"))
(def rn (edit-min! {:op "rename" :module "schema" :old "replace!" :new "supersede-prior!"}))
(chk "graph rename SUCCEEDS via bound_to (:ok, ops>0)" (and (:ok rn) (pos? (or (:ops rn) 0))))
(chk "rename rewrote the def spelling to the new name" (pos? (spell-count "supersede-prior!")))
(chk "rename rewrote exactly the def site (new-spelling count == old-spelling decrease)"
     (= (spell-count "supersede-prior!") (- before-replace (spell-count "replace!"))))
;; coherence: each node whose spelling stayed "replace!" is a REFERENCE bound_to the
;; renamed def — proving the rename left no dangling ref (the identity invariant).
(def Bp (c/value-id st "bound_to"))
(defn node-spell [n]
  (let [cs (filter (fn [cid] (= (:l (c/claim-of st cid)) n)) (c/by-p st Vp))]
    (when (seq cs) (c/literal st (:r (c/claim-of st (first cs)))))))
(def bound-callers
  (filter (fn [cid] (let [m (c/claim-of st cid)]
                      (and (= "replace!" (node-spell (:l m)))
                           (= "supersede-prior!" (node-spell (:r m))))))
          (if (some? Bp) (c/by-p st Bp) [])))
(chk "rename installed bound_to: old-spelling refs resolve to the renamed def (no dangling)"
     (pos? (count bound-callers)))
(chk "no dangling: every residual replace! spelling is a bound reference, not an unbound site"
     (= (spell-count "replace!") (count bound-callers)))

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
