;; ============================================================================
;; coord_bound_identity_totality_receipt.clj — #(a) identity-FIRST totality.
;;   bb -cp out coord_bound_identity_totality_receipt.clj
;;
;; Proves the generalized persist-bound! migration is TOTAL and covers FRESH edits:
;;   T1 (ingest totality)      — after the first materialize, EVERY live reference whose
;;                               refers_to lands on a binding NODE carries exactly one
;;                               durable bound_to edge (ref-leaves ⊆ bound-leaves; 1 each).
;;   T2 (fresh-edit totality)  — a set-body that MINTS a new reference to an existing def,
;;                               once reconciled, also carries a bound_to (the new leaf is
;;                               covered — totality is maintained across the edit).
;;   T3 (no read leakage)      — a :query for all triples surfaces ZERO bound_to rows even
;;                               with thousands of real bound_to facts present.
;;
;; SAFE: a /tmp COPY of .fram/code.log + in-process daemon; NO socket, NEVER port 7977.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.edn :as edn]
         '[fram.store :as c] '[fram.schema :as s])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))
(def flat (str (System/getProperty "java.io.tmpdir") "/bound-totality-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)

;; ref-leaves / bound-leaves off the live store; ref-leaves counts ONLY refers_to whose
;; object is a binding NODE (integer :r) — the "resolvable reference -> a binding" set.
(defn ref-leaves []
  (let [st (:store @co) REFp (c/value-id st "refers_to")]
    (->> (c/by-p st REFp) (map #(c/fact-of st %))
         (filter #(integer? (:r %))) (map :l) set)))
(defn bound-by-leaf []
  (let [st (:store @co) BND (c/value-id st "bound_to")]
    (reduce (fn [m cid] (update m (:l (c/fact-of st cid)) (fnil inc 0))) {} (if BND (c/by-p st BND) []))))

;; ---- T1: ingest totality ----------------------------------------------------
(handle {:op :refers-ensure})                    ; first materialize -> persist-bound! migration
(def rl1 (ref-leaves))
(def bl1 (bound-by-leaf))
(def missing1 (clojure.set/difference rl1 (set (keys bl1))))
(def multi1 (filter (fn [[_ n]] (> n 1)) bl1))    ; exactly-one invariant
(def t1 (and (pos? (count rl1)) (empty? missing1) (empty? multi1)))
(println "\n=== T1 — ingest totality ===")
(println "  reference->binding leaves:" (count rl1)
         " bound_to leaves:" (count bl1)
         " missing bound_to:" (count missing1)
         " leaves with >1 bound_to:" (count multi1))

;; ---- T2: fresh-edit totality (set-body mints a new reference) ---------------
;; Replace schema/cardinality's body with one that CALLS the schema def `replace!` — a
;; freshly-minted reference leaf. After reconciliation it too must carry a bound_to.
(def sb (try (do-edit-min {:op "set-body" :module "schema" :name "cardinality"
                           :datum (edn/read-string "(do (replace! pname) \"multi\")")})
             (catch Throwable t {:reject (.getMessage t)})))
(println "\n=== T2 — fresh-edit totality (set-body) ===")
(println "  set-body:" (pr-str (select-keys sb [:ok :ops :reject])))
(handle {:op :refers-ensure})                    ; scoped reconcile -> persist-bound! for the new refs
(def rl2 (ref-leaves))
(def bl2 (bound-by-leaf))
(def missing2 (clojure.set/difference rl2 (set (keys bl2))))
(def new-covered (- (count rl2) (count rl1)))
(println "  reference->binding leaves now:" (count rl2)
         " bound_to leaves now:" (count bl2)
         " missing bound_to:" (count missing2)
         " new durable edges:" (- (count bl2) (count bl1)))
;; totality maintained (no live ref->binding leaf lacks bound_to) AND the set-body's freshly
;; minted references were newly covered (bound_to leaf count grew). Live-ref count may DROP —
;; set-body supersedes the old body, retiring its references — so we assert coverage, not count.
(def t2 (and (:ok sb) (empty? missing2) (> (count bl2) (count bl1))))

;; ---- T3: no read-view leakage with real data --------------------------------
(def ALLQ {:find "out"
           :rules [{:head {:rel "out" :args [{:var "l"} {:var "p"} {:var "r"}]}
                    :body [{:rel "triple" :args [{:var "l"} {:var "p"} {:var "r"}]}]}]})
(def all-rows (:ok (handle {:op :query :query ALLQ})))
(def leaked (filter (fn [[_ p _]] (= "bound_to" p)) all-rows))
(def t3 (and (seq all-rows) (empty? leaked) (pos? (count bl2))))
(println "\n=== T3 — no read-view leakage ===")
(println "  total triple rows:" (count all-rows) " bound_to rows in :query:" (count leaked)
         " durable bound_to facts:" (reduce + (vals bl2)))

(println "\n=== VERDICT ===")
(if (and t1 t2 t3)
  (do (println "PASS — totality at ingest (T1) and after a fresh set-body (T2); bound_to hidden from :query (T3).")
      (System/exit 0))
  (do (println "FAIL — T1" t1 "T2" t2 "T3" t3) (System/exit 1)))
