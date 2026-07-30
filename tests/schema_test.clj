;; schema_test.clj — the store schema layer: cardinality-driven supersession,
;; refs, find-by, identity (name/rename/resolve). Mirrors the oracle's
;; schema.rkt/graph.rkt semantics.
;;   bb -cp out schema_test.clj
(require '[fram.store :as c] '[fram.schema :as s])

(def ctx (c/new-store))
(def tx (c/begin-tx! ctx "test"))
(s/setup! ctx tx)
(s/def-predicate! ctx "title" "single" "literal" tx)
(s/def-predicate! ctx "tag" "multi" "literal" tx)
(s/def-predicate! ctx "depends_on" "multi" "ref" tx)

(def t (c/entity! ctx))
(s/name! ctx t "@t1" tx)
(s/assert! ctx t "title" "First" tx)
(def title1 (s/lookup ctx t "title"))
(s/assert! ctx t "title" "Second" tx)            ; single -> supersede "First"
(def title2 (s/lookup ctx t "title"))
(def title-all (s/lookup-all ctx t "title"))
(s/assert! ctx t "tag" "a" tx)
(s/assert! ctx t "tag" "b" tx)                   ; multi -> keep both
(def tags (s/lookup-all ctx t "tag"))
(def u (c/entity! ctx))
(s/name! ctx u "@u1" tx)
(s/link! ctx t "depends_on" u tx)                ; ref
(def deps (s/lookup-all ctx t "depends_on"))
(def resolved (s/resolve-name ctx "@t1"))
(def nm (s/name-of ctx t))
(s/name! ctx t "@t1-renamed" tx)                 ; rename -> supersede old name
(def nm2 (s/name-of ctx t))
(def resolved2 (s/resolve-name ctx "@t1-renamed"))
(def old-resolves (s/resolve-name ctx "@t1"))

(def open-subj (c/entity! ctx))
(s/assert! ctx open-subj "open_pred" "open-value" tx)
(def open-pid (s/resolve-predicate ctx "open_pred"))

(def legacy-ctx (c/new-store))
(def legacy-tx (c/begin-tx! legacy-ctx "legacy"))
(def legacy-pid (c/value! legacy-ctx "legacy_pred"))
(def legacy-subj (c/entity! legacy-ctx))
(c/fact! legacy-ctx legacy-subj legacy-pid (c/value! legacy-ctx "legacy-value") legacy-tx)
(def legacy-before (s/lookup-all legacy-ctx legacy-subj "legacy_pred"))
(s/assert! legacy-ctx legacy-subj "legacy_pred" "new-value" legacy-tx)

(def status-pid (s/def-predicate! ctx "status" "single" "literal" tx))
(s/alias-predicate! ctx "status" "state" tx)
(def status-subj (c/entity! ctx))
(s/assert! ctx status-subj "status" "draft" tx)
(s/assert! ctx status-subj "state" "ready" tx)
(def renamed-status-pid (s/rename-predicate! ctx "status" "phase" tx))
(s/assert! ctx status-subj "phase" "done" tx)

(def collision-left (s/def-predicate! ctx "collision_left" "multi" "literal" tx))
(def collision-right (s/def-predicate! ctx "collision_right" "multi" "literal" tx))
(def alias-collision-rejected
  (try
    (s/alias-predicate! ctx "collision_left" "collision_right" tx)
    false
    (catch clojure.lang.ExceptionInfo _ true)))
(def canonical-collision-rejected
  (try
    (s/rename-predicate! ctx "collision_left" "collision_right" tx)
    false
    (catch clojure.lang.ExceptionInfo _ true)))

(def atomic-ctx (c/new-store))
(def atomic-tx (c/begin-tx! atomic-ctx "atomic-collision"))
(s/setup! atomic-ctx atomic-tx)
(s/def-predicate! atomic-ctx ":atomic_auto" "multi" "literal" atomic-tx)
(def atomic-before-store @atomic-ctx)
(def atomic-before-dump (pr-str (c/dump-store atomic-ctx)))
(def auto-register-collision-rejected
  (try
    (s/register-predicate! atomic-ctx "atomic_auto" atomic-tx)
    false
    (catch clojure.lang.ExceptionInfo _ true)))
(def auto-register-store-unchanged (= atomic-before-store @atomic-ctx))
(def auto-register-dump-unchanged
  (= atomic-before-dump (pr-str (c/dump-store atomic-ctx))))

(def user-alias-before-store @atomic-ctx)
(def user-alias-before-dump (pr-str (c/dump-store atomic-ctx)))
(def user-alias-collision-rejected
  (try
    (s/alias-predicate! atomic-ctx "fresh_alias_source" ":atomic_auto" atomic-tx)
    false
    (catch clojure.lang.ExceptionInfo _ true)))
(def user-alias-store-unchanged (= user-alias-before-store @atomic-ctx))
(def user-alias-dump-unchanged
  (= user-alias-before-dump (pr-str (c/dump-store atomic-ctx))))

(def rename-before-store @atomic-ctx)
(def rename-before-dump (pr-str (c/dump-store atomic-ctx)))
(def rename-preflight-collision-rejected
  (try
    (s/rename-predicate! atomic-ctx "fresh_rename_source" ":atomic_auto" atomic-tx)
    false
    (catch clojure.lang.ExceptionInfo _ true)))
(def rename-store-unchanged (= rename-before-store @atomic-ctx))
(def rename-dump-unchanged
  (= rename-before-dump (pr-str (c/dump-store atomic-ctx))))

(def stable-literal-pid (s/def-predicate! ctx "stable_literal" "multi" "literal" tx))
(s/alias-predicate! ctx "stable_literal" "stable_value" tx)
(def stable-subj (c/entity! ctx))
(def stable-literal-cid (s/assert! ctx stable-subj "stable_value" "needle" tx))
(def stable-ref-pid (s/def-predicate! ctx "stable_ref" "multi" "ref" tx))
(s/alias-predicate! ctx "stable_ref" "points_to" tx)
(def stable-target (c/entity! ctx))
(def stable-ref-cid (s/link! ctx stable-subj "points_to" stable-target tx))

(def checks
  [["assert! then lookup"                  (= "First" title1)]
   ["single-valued update supersedes"      (= "Second" title2)]
   ["lookup-all shows only the live value" (= ["Second"] title-all)]
   ["multi-valued keeps all values (ord)"  (= ["a" "b"] tags)]
   ["link! ref resolves to the entity"     (= [u] deps)]
   ["resolve-name -> the entity"           (= t resolved)]
   ["name-of returns the name"             (= "@t1" nm)]
   ["rename supersedes the old name"       (= "@t1-renamed" nm2)]
   ["rename: id stable, new name resolves" (= t resolved2)]
   ["old name no longer resolves"          (nil? old-resolves)]
   ["cardinality read: single"             (= "single" (s/cardinality ctx "title"))]
   ["cardinality read: multi"              (= "multi" (s/cardinality ctx "tag"))]
   ["unregistered predicate defaults multi" (= "multi" (s/cardinality ctx "nope"))]
   ["unknown write auto-registers canonical predicate"
    (and (= open-pid (c/value-id ctx "open_pred"))
         (= "open_pred" (s/predicate-name ctx open-pid))
         (= [open-pid] (s/find-by ctx "predicate_name" "open_pred")))]
   ["canonical and colon alias resolve one predicate id"
    (= open-pid (s/resolve-predicate ctx ":open_pred"))]
   ["legacy no-registry facts project unchanged"
    (and (= ["legacy-value"] legacy-before)
         (= legacy-pid (s/resolve-predicate legacy-ctx "legacy_pred"))
         (= ["legacy-value" "new-value"]
            (s/lookup-all legacy-ctx legacy-subj "legacy_pred")))]
   ["alias and rename preserve predicate identity"
    (and (= status-pid renamed-status-pid)
         (= status-pid (s/resolve-predicate ctx "status"))
         (= status-pid (s/resolve-predicate ctx "state"))
         (= status-pid (s/resolve-predicate ctx "phase"))
         (= status-pid (s/resolve-predicate ctx ":phase")))]
   ["rename records the old canonical spelling as an alias"
    (some #{status-pid} (s/find-by ctx "predicate_alias" "status"))]
   ["aliases and rename share one logical value group"
    (and (= ["done"] (s/lookup-all ctx status-subj "status"))
         (= ["done"] (s/lookup-all ctx status-subj "state"))
         (= ["done"] (s/lookup-all ctx status-subj "phase"))
         (= 1 (count (c/by-lp ctx status-subj status-pid))))]
   ["alias collision with another canonical rejects loudly"
    alias-collision-rejected]
   ["rename collision with another canonical rejects loudly"
    canonical-collision-rejected]
   ["auto-register default-alias collision rejects before mutation"
    (and auto-register-collision-rejected
         auto-register-store-unchanged
         auto-register-dump-unchanged
         (nil? (c/value-id atomic-ctx "atomic_auto")))]
   ["user alias collision rejects before registering its source"
    (and user-alias-collision-rejected
         user-alias-store-unchanged
         user-alias-dump-unchanged
         (nil? (c/value-id atomic-ctx "fresh_alias_source")))]
   ["rename collision rejects before registering its source"
    (and rename-preflight-collision-rejected
         rename-store-unchanged
         rename-dump-unchanged
         (nil? (c/value-id atomic-ctx "fresh_rename_source")))]
   ["assert and find-by resolve aliases through the stable id"
    (and (= stable-literal-pid (:p (c/fact-of ctx stable-literal-cid)))
         (= ["needle"] (s/lookup-all ctx stable-subj "stable_literal"))
         (= [stable-subj] (s/find-by ctx "stable_value" "needle")))]
   ["link and lookup resolve aliases through the stable id"
    (and (= stable-ref-pid (:p (c/fact-of ctx stable-ref-cid)))
         (= [stable-target] (s/lookup-all ctx stable-subj "stable_ref")))]])

(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nstore schema:" (count checks) "/" (count checks) "PASS")
    (do (println "\nstore schema:" (count fails) "FAILED") (System/exit 1))))
