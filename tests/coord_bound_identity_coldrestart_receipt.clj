;; ============================================================================
;; coord_bound_identity_coldrestart_receipt.clj — #(a) rename + render survive a COLD
;; RESTART by IDENTITY, with no spelling fallback for already-resolved references.
;;   bb -cp out coord_bound_identity_coldrestart_receipt.clj
;;
;; The core hazard (coord_rename_spelling_check): a graph rename edits ONLY the def
;; binding's spelling; reference leaves keep the OLD spelling, so a cold re-derive by
;; SPELLING can't match the renamed def and renders the stale name. bound_to fixes it:
;;   1. boot, ingest migration (persist-bound! locks every ref to its @mod#int).
;;   2. auto-select the most-referenced top-level def D; rename it (display name only).
;;   3. COLD RESTART (re-boot the daemon from the flat log — bound_to is durable).
;;   4. whole re-materialize (refers_to re-derived BY SPELLING from scratch).
;;   5. every reference L that was bound to D now: (refers-target L) == D  [identity],
;;      binding-name(D) == NEW name [renders new], sym-val(L) == OLD name [no spelling
;;      rewrite — pure identity follow, the exact "no spelling fallback" property].
;;
;; SAFE: a /tmp COPY of .fram/code.log + in-process daemon; NO socket, NEVER port 7977.
;; ============================================================================
(require '[clojure.java.io :as io] '[fram.store :as c] '[fram.schema :as s])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))
(def flat (str (System/getProperty "java.io.tmpdir") "/bound-cold-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)

(handle {:op :refers-ensure})                    ; ingest migration: persist bound_to for every ref

;; auto-select the most-referenced TOP-LEVEL def (a rename-able binding) as D.
(defn top-level-nodes []
  (with-resolve-read (:store @co)
    (set (for [src resolve/srcs [_ leaf] (resolve/file-modframe src)] leaf))))
(def tl (top-level-nodes))
(def st0 (:store @co))
(def BND (c/value-id st0 "bound_to"))
;; {target-node -> #{ref leaves}} over durable bound_to, restricted to top-level targets.
(def refs-by-target
  (reduce (fn [m cid] (let [cl (c/fact-of st0 cid) tgt (:r cl)]
                        (if (tl tgt) (update m tgt (fnil conj #{}) (:l cl)) m)))
          {} (if BND (c/by-p st0 BND) [])))
(def D (->> refs-by-target (sort-by (comp count val) >) ffirst))
(when-not D (println "SKIP — no top-level def has durable references in this corpus") (System/exit 0))
(def D-name0 (with-resolve-read st0 (resolve/sym-val D)))
(def D-module (with-resolve-read st0 (resolve/name->module (s/name-of st0 D))))
(def L-set (get refs-by-target D))               ; reference leaves bound to D
(def L-spellings-before (with-resolve-read st0 (into {} (map (fn [L] [L (resolve/sym-val L)]) L-set))))
(def new-name (str D-name0 "-COLDID"))
(println "chosen def D:" (s/name-of st0 D) "name" (pr-str D-name0) "module" D-module
         "refs:" (count L-set))

;; instrumentation: the durable bound_to edges are in the flat log (survive cold restart).
(def st-pre (:store @co))
(def bound-count-pre (count (c/by-p st-pre (c/value-id st-pre "bound_to"))))
(println "bound_to facts (warm, pre-rename):" bound-count-pre)

;; rename D (display name only). Under the same lock persist-bound! re-locks identity.
(def rn (try (do-edit-min {:op "rename" :module D-module :old D-name0 :new new-name})
             (catch Throwable t {:reject (.getMessage t)})))
(println "rename:" (pr-str (select-keys rn [:ok :ops :reject])))

;; ---- COLD RESTART — re-boot the daemon from the durable flat log ----
(boot-flat! flat)
(handle {:op :refers-ensure})                    ; whole re-materialize: refers_to re-derived by SPELLING
(def st1 (:store @co))
(def BND1 (c/value-id st1 "bound_to"))
(def bound-count-post (count (if BND1 (c/by-p st1 BND1) [])))
(println "bound_to facts (warm, post cold-restart fold):" bound-count-post)
;; DIRECT durable-identity check off st1 (independent of resolve/BOUND binding): the raw
;; bound_to object for a leaf.
(defn direct-bound-target [L1] (when (and L1 BND1) (:r (first (map #(c/fact-of st1 %) (c/by-lp st1 L1 BND1))))))
;; resolve D fresh in the rebooted store by its stable @mod#int name.
(def D1 (s/resolve-name st1 (s/name-of st0 D)))
(def D1-name (with-resolve-read st1 (resolve/binding-name D1)))

(def results
  (with-resolve-read st1
    (vec (for [L L-set]
      (let [L1   (s/resolve-name st1 (s/name-of st0 L))
            bt   (direct-bound-target L1)
            tgt  (resolve/ultimate (resolve/refers-target L1))
            spell (resolve/sym-val L1)]
        {:L (s/name-of st0 L)
         :kind (resolve/kind-of L1)
         :bt (when bt (s/name-of st1 bt))
         :tgt (when tgt (s/name-of st1 tgt))
         :identity-ok (= tgt D1)                 ; resolves to the renamed def by IDENTITY
         :renders-new (= (resolve/binding-name tgt) new-name)
         :spelling-unchanged (= spell (get L-spellings-before L))})))))   ; leaf still spells OLD name

(def n (count results))
(def id-ok    (every? :identity-ok results))
(def render-ok(every? :renders-new results))
(def spell-ok (every? :spelling-unchanged results))
(println "\n=== COLD-RESTART RENDER (after re-boot) ===")
(println "  renamed def D renders as:" (pr-str D1-name) " (expected" (pr-str new-name) ")")
(println "  references checked:" n)
(println "  all resolve to D by identity:" id-ok)
(println "  all render the NEW name:" render-ok)
(println "  all leaves keep the OLD spelling (identity follow, not spelling rewrite):" spell-ok)
(println "  --- failing entries ---")
(doseq [r (remove #(and (:identity-ok %) (:renders-new %) (:spelling-unchanged %)) results)]
  (println "   " (pr-str r)))

(println "\n=== VERDICT ===")
(if (and (:ok rn) (pos? n) (= D1-name new-name) id-ok render-ok spell-ok)
  (do (println "PASS — after a cold restart, every already-resolved reference follows its durable"
               "bound_to to the renamed def and renders the NEW name, with NO spelling fallback.")
      (System/exit 0))
  (do (println "FAIL — rename-ok" (boolean (:ok rn)) "refs" n "renamed" (= D1-name new-name)
               "identity" id-ok "render" render-ok "spelling-unchanged" spell-ok)
      (System/exit 1)))
