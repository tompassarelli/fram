;; resolve_cli.clj — the hand-Clojure CLI tail appended to the Beagle-emitted
;; resolver (chartroom/src/resolve.bclj) to form chartroom/src/resolve.clj (ns resolve).
;; The bb/CLI -main + the callgraph mode (datalog + cheshire) live here — no Beagle value.
;; Regenerate resolve.clj: bin/build-resolve.sh

;; ---- CLI driver (Clojure: bb/CLI + callgraph's datalog/cheshire edge) ----
(require (quote [clojure.edn :as edn]) (quote [fram.datalog :as d]) (quote [cheshire.core :as json]))
(def mode (first *command-line-args*))

(def MODES #{"resolve" "rename" "delete" "callgraph" "upsert-form" "set-body"})
(defn -main []
  (let [edn-paths (drop (case mode "resolve" 1 "rename" 4 "delete" 3 "callgraph" 1
                                   "upsert-form" 3 "set-body" 4)
                        *command-line-args*)]
    (resolve-edn!
     edn-paths
     (fn []
(case mode
  "resolve"
  (binding [*out* *err*]
    (println "================ Turtle #5 — lexical resolution pass ================")
    (println (str "references resolved (carry refers_to → a binding node): " @n-resolved
                  "  (" @n-xmod " cross-module, " @n-type " type references)"))
    (println (str "unresolved (builtins / native — correctly NO refers_to): " @n-unresolved))
    (println (str "comment identifier mentions resolved (rename-correct doc comments): " @n-comment))
    ;; write the resolved projection so identity can be checked: with NO rename,
    ;; projecting through refers_to must reproduce the original source exactly.
    (doseq [src srcs] (extract-file! src (out-path src)))
    (doseq [src srcs]
      (println (str "  " (-> src (str/split #"/") last) ": "
                    (count (filter #(and (= "symbol" (kind-of %)) (refers-target %)) (@file->ents src)))
                    " references carry refers_to; projected (identity) -> " (out-path src)))))

  "rename"
  (let [[old new target] (drop 1 *command-line-args*)]
    (verb-rename! old new target))

  "delete"
  (let [[name target] (drop 1 *command-line-args*)
        target-srcs (filter #(str/includes? % target) srcs)
        victims (keep #(def-binding % name) target-srcs)   ; value OR type binding occurrences to delete
        ;; the top-level forms to remove + their whole subtrees (incl. each form's own
        ;; doc-comment AND, for a defunion, its variant-constructor name-leaves). Computed
        ;; FIRST so the orphan check can both exclude refs INSIDE a deleted form and flag
        ;; surviving refs to ANY binding the deletion removes — the union name OR a variant.
        all-forms (set (mapcat (fn [src] (keep #(form-for-victim src %) victims)) srcs))
        subtree (reduce into #{} (map descendants all-forms))
        orphans (for [src srcs, e (@file->ents src)
                      :when (and (= "symbol" (kind-of e)) (refers-target e) (not (subtree e))
                                 (subtree (ultimate (refers-target e))))] e)]   ; ref to a deleted binding
    (when (zero? (count victims))
      (binding [*out* *err*]
        (println (str "REJECTED — no binding named `" name "` found in \"" target "\" (nothing to delete).")))
      (System/exit 5))
    ;; matched a binding but no independently-deletable top-level form (e.g. a defunion
    ;; variant lives nested inside its union) — refuse, don't report a no-op as success.
    (when (empty? all-forms)
      (binding [*out* *err*]
        (println (str "REJECTED — `" name "` is not an independently-deletable top-level form "
                      "(a defunion variant / nested binding); no claims mutated.")))
      (System/exit 5))
    ;; INVARIANT (no-orphaned-refs): refuse if any SURVIVING reference points at a victim.
    (when (pos? (count orphans))
      (binding [*out* *err*]
        (println "================ Turtle #5 — delete + orphaned-reference invariant ================")
        (println (str "REJECTED — " (count orphans) " reference(s) would be ORPHANED (no-orphaned-refs):"))
        (doseq [o (take 5 orphans)] (println (str "  orphan: reference node " o " (`" (sym-val o) "`)"))))
      (System/exit 6))
    ;; SAFE: project each src with the victim forms (and their subtrees) omitted, siblings renumbered.
    (binding [*deleted-forms* all-forms *deleted-subtree* subtree]
      (doseq [src srcs] (extract-file! src (out-path src))))
    (binding [*out* *err*]
      (println "================ Turtle #5 — delete (no-orphaned-refs satisfied) ================")
      (println (str "deleted def `" name "` in \"" target "\": " (count all-forms) " form(s); 0 orphaned refs"))
      (doseq [src srcs] (println (str "projected -> " (out-path src) "   <- " src)))))

  ;; ============================================================================
  ;; AUTHORING VERBS — the GAP closed: a claim operation for novel authoring.
  ;; upsert-form : add a NEW top-level def (append a wrapper fN edge) OR replace an
  ;;               existing top-level def by name (supersede its wrapper fN edge to
  ;;               point at a freshly-minted subtree). The form is given as an EDN
  ;;               datum (the structured edit spec), minted into the SAME store.
  ;; Both reuse extract-file! (the rename/delete render machine) and re-run the
  ;; lexical walk over the post-mint corpus, so a reference in the new code resolves
  ;; via refers_to (scope-correct) exactly like hand-written code — then the recompile
  ;; gate (authoring.sh) is the only acceptance criterion. fail-closed before that.
  ;; ============================================================================
  "upsert-form"
  (let [[scope spec-file] (drop 1 *command-line-args*)
        datum (edn/read-string (slurp spec-file))]
    (verb-upsert-form! scope datum))

  ;; set-body : replace a defn's BODY — supersede every post-params fN edge of the
  ;; named defn and re-wire to a freshly-minted body datum.
  "set-body"
  (let [[name scope body-file] (drop 1 *command-line-args*)
        datum (edn/read-string (slurp body-file))]
    (verb-set-body! name scope datum))

  ;; ============================================================================
  ;; callgraph — the scope-correct call graph + transitive blast radius, derived
  ;; from the SAME refers_to edges the rename/delete engine uses. A "call" is a
  ;; reference in list-HEAD position whose binding (followed transitively) is a
  ;; top-level defn; the caller is its enclosing top-level defn. Because refers_to
  ;; is the converged cross-module/multi-arity/collision-correct resolution, this
  ;; call graph is too — unlike a bare-callname index, it does NOT drop qualified
  ;; (a/f, m/f) cross-module calls. Emits the JSON beagle-cascade consumes.
  ;; ============================================================================
  "callgraph"
  (let [dkey      (fn [src leaf] (str src "#" leaf))
        defn-meta (into {} (for [src srcs, [nm leaf] (file-modframe src)]
                             [leaf {:key (dkey src leaf) :file src
                                    :module (or (module-name src)
                                                (-> src (str/split #"/") last (str/replace #"\.[^.]+$" "")))
                                    :name nm}]))
        defn-set  (set (keys defn-meta))
        ;; ALL resolved reference symbols in a subtree (any position — not just list-head).
        ;; For a BLAST RADIUS ("what must change if I change X"), every reference to a defn is
        ;; a dependency: a head call (f x), a value-pass (mapv f xs), a threaded step (-> x f),
        ;; a `:- T` annotation. Head-only silently under-reports (proven on shipped fram/src).
        call-refs (fn call-refs [node]
                    (if (refers-target node) [node]
                      (when (= "list" (kind-of node)) (mapcat call-refs (ordered-children node)))))
        ;; callers = [caller-defn-leaf, body-node] pairs. A top-level value defn is one caller;
        ;; an extend-type/extend-protocol attributes each impl method's body to that protocol
        ;; method (the impl method-name resolves to it via refers_to) — those bodies were skipped.
        callers (mapcat
                 (fn [form]
                   (let [d (unwrap-def form) h (head-sym d)]
                     (cond
                       (VALUE-DEFS h)
                       (let [cl (second (ordered-children d))] (when (defn-meta cl) [[cl d]]))
                       (#{"extend-type" "extend-protocol"} h)
                       (keep (fn [c] (when (= "list" (kind-of c))
                                       (let [mnode (first (ordered-children c))
                                             cl (when (sym-val mnode) (ultimate (refers-target mnode)))]
                                         (when (and cl (defn-meta cl)) [cl c]))))
                             (rest (ordered-children d))))))
                 (mapcat forms-of srcs))
        edges (vec (distinct
                    (for [[caller-leaf body] callers
                          r (call-refs body)
                          :let [callee (ultimate (refers-target r))]  ; follow refers_to to the bound defn
                          :when (and (defn-set callee) (not= callee caller-leaf))]
                      [(:key (defn-meta caller-leaf)) (:key (defn-meta callee))])))
        ;; transitive blast radius via Fram Datalog: blast(D) = {x | x transitively calls D}
        bctx (c/new-store) btx (c/begin-tx! bctx "code") EDGE (c/value! bctx "calls-defn")
        k->e (volatile! {})
        bent (fn [k] (or (get @k->e k) (let [e (c/entity! bctx)] (vswap! k->e assoc k e) e)))
        _ (doseq [[a b] edges] (c/claim! bctx (bent a) EDGE (bent b) btx))
        e->k (into {} (map (fn [[k v]] [v k]) @k->e))
        db (d/run-rules bctx
             [(d/rule "reaches" [(d/v :x) (d/v :y)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])])
              (d/rule "reaches" [(d/v :x) (d/v :z)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])
                                                     (d/lit "reaches" [(d/v :y) (d/v :z)])])])
        reaches (set (d/facts db "reaches"))
        blast (reduce (fn [m [xid yid]] (update m (e->k yid) (fnil conj #{}) (e->k xid))) {} reaches)]
    (binding [*out* *err*]
      (println (format "callgraph: %d defns, %d scope-correct edges, %d transitive reaches-pairs (refers_to + Fram Datalog)"
                       (count defn-meta) (count edges) (count reaches))))
    (println (json/generate-string
              {:defns (vec (vals defn-meta)) :edges edges
               :blast (into {} (map (fn [[k vs]] [k (vec vs)]) blast))}))))))))

;; GUARD: run the pipeline only when invoked as a CLI with a recognized mode.
;; Loaded as a library (no mode arg, or an unrecognized one), this is a no-op —
;; so a daemon can `require`/load this file and call `resolve-edn!` over its own
;; warm store without the old top-level load-edn crashing on mis-sliced args.
(when (MODES mode) (-main))
