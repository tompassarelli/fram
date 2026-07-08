;; tools_test.clj — the CLOSED, O(1) tool catalog + dispatch.
;; Proves: (1) the catalog is exactly TEN tools, never minted per-predicate;
;; (2) tell/retract lower to a coordinator-routable {:write} intent (@-normalized
;; refs), with `untell` accepted as an alias for `retract`; (3) reads (show/validate)
;; return rows off the fold; (4) `ask`/`query` reach fram.query; (5) unknown tool +
;; missing required param -> :error; (6) the five graph-AST edit verbs dispatch to the
;; {:edit} envelope. The vocabulary is DATA (a predicate is an entity), so there are no
;; owner-of/set-owner/<pred>-list tools — `show <pred>` and `ask` reach it instead.
;;   bb -cp out tests/tools_test.clj
(require '[fram.kernel :as k] '[fram.tools :as t])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

(def claims
  [(k/->Claim "@x" "title" "X thread")     ; single, literal
   (k/->Claim "@x" "owner" "personal")     ; single, literal
   (k/->Claim "@x" "depends_on" "@y")      ; multi, ref
   (k/->Claim "@y" "title" "Y thread")])

(def idx (k/build-index claims))
(def cat (t/catalog claims))
(defn has-tool? [nm] (boolean (some #(= (:name %) nm) cat)))
(defn call [tool args] (t/call claims idx cat tool args))

;; (1) CLOSED catalog — EXACTLY these ten names, no more, no fewer, no per-predicate tools.
(def expected-names
  #{"tell" "retract" "show" "ask" "validate"
    "add-def" "set-body" "rename-def" "insert-after" "replace-in-body"})
(chk "catalog is EXACTLY the ten closed tools" (= (set (map :name cat)) expected-names))
(chk "catalog has exactly 10 entries" (= 10 (count cat)))
(chk "no per-predicate tools minted (owner-of/set-owner/depends_on-list absent)"
     (and (not (has-tool? "owner-of")) (not (has-tool? "set-owner"))
          (not (has-tool? "depends_on-list")) (not (has-tool? "depends_on-from"))
          (not (has-tool? "threads")) (not (has-tool? "dependents-of"))))
(chk "catalog has no duplicate tool names" (= (count (map :name cat)) (count (set (map :name cat)))))

;; (2) tell/retract -> coordinator {:write} intent, refs @-normalized by value-driven rule
(chk "tell (literal pred) -> assert intent, value verbatim"
     (= (:write (call "tell" {:subject "x" :predicate "owner" :object "work"}))
        {:op "assert" :l "@x" :p "owner" :r "work"}))
(chk "tell (pure-ref pred) -> assert intent, bare value @-normalized"
     (= (:write (call "tell" {:subject "x" :predicate "depends_on" :object "z"}))
        {:op "assert" :l "@x" :p "depends_on" :r "@z"}))
(chk "retract -> retract intent"
     (= (:write (call "retract" {:subject "x" :predicate "depends_on" :object "@y"}))
        {:op "retract" :l "@x" :p "depends_on" :r "@y"}))
;; the fold-in: `untell` is an accepted ALIAS for `retract` (same intent), mirroring query->ask.
(chk "untell ALIAS -> same retract intent as retract"
     (= (:write (call "untell" {:subject "x" :predicate "depends_on" :object "@y"}))
        {:op "retract" :l "@x" :p "depends_on" :r "@y"}))
;; mixed-ref predicate: a literal write value is stored VERBATIM (no spurious @-prefix).
(let [mc [(k/->Claim "@x" "tag" "@refnode") (k/->Claim "@x" "tag" "plainword")]
      mi (k/build-index mc) mcat (t/catalog mc)]
  (chk "mixed-ref retract keeps literal verbatim (no spurious @)"
       (= (:write (t/call mc mi mcat "retract" {:subject "x" :predicate "tag" :object "plainword"}))
          {:op "retract" :l "@x" :p "tag" :r "plainword"})))

;; (3) reads off the fold
(chk "show @x returns its claims (pred/value rows)"
     (= (set (map (fn [r] [(:pred r) (:value r)]) (:rows (call "show" {:subject "x"}))))
        #{["title" "X thread"] ["owner" "personal"] ["depends_on" "@y"]}))
(chk "show accepts a bare id (auto-@)"
     (= (set (map :pred (:rows (call "show" {:subject "@x"})))) #{"title" "owner" "depends_on"}))
(chk "validate returns rows (no violations here -> empty)"
     (vector? (:rows (call "validate" {}))))

;; (4) ask / query reach fram.query (transitive over the same fold); `query` aliases `ask`
(def reaches-q
  {:find "reaches"
   :rules [{:head {:rel "reaches" :args [{:var "a"} {:var "b"}]}
            :body [{:rel "triple" :args [{:var "a"} "depends_on" {:var "b"}]}]}]})
(chk "ask returns :ok with the edge"    (contains? (set (:ok (call "ask" {:query reaches-q}))) ["@x" "@y"]))
(chk "query ALIAS reaches the same op"  (contains? (set (:ok (call "query" {:query reaches-q}))) ["@x" "@y"]))

;; (5) errors
(chk "unknown tool -> :error"                (contains? (call "nope" {}) :error))
(chk "tell missing object -> :error"         (contains? (call "tell" {:subject "x" :predicate "owner"}) :error))
(chk "retract missing predicate -> :error"   (contains? (call "retract" {:subject "x"}) :error))
(chk "show missing subject -> :error"        (contains? (call "show" {}) :error))
(chk "ask missing :query -> :error"          (contains? (call "ask" {}) :error))

;; (6) GRAPH-AST EDIT verbs: dispatch to the {:edit} envelope (host runs it OUT-OF-BAND).
(chk "structural edit tools present" (and (has-tool? "add-def") (has-tool? "set-body")
                                          (has-tool? "rename-def") (has-tool? "insert-after")
                                          (has-tool? "replace-in-body")))
(chk "add-def -> {:edit upsert-form} envelope (NOT {:write})"
     (let [r (call "add-def" {:module "schema" :form "(defn f [x :- Int] :- Int x)"})]
       (and (nil? (:write r))
            (= (:edit r) {:op "upsert-form" :module "schema" :form "(defn f [x :- Int] :- Int x)"}))))
(chk "set-body -> {:edit set-body} envelope"
     (= (:edit (call "set-body" {:module "schema" :name "cardinality" :body "\"single\""}))
        {:op "set-body" :module "schema" :name "cardinality" :body "\"single\""}))
(chk "rename-def -> {:edit rename} envelope"
     (= (:edit (call "rename-def" {:module "schema" :name "a" :new-name "b"}))
        {:op "rename" :module "schema" :name "a" :new-name "b"}))
(chk "insert-after -> {:edit insert-form} envelope"
     (= (:edit (call "insert-after" {:module "schema" :after "a" :form "(def z 1)"}))
        {:op "insert-form" :module "schema" :after "a" :form "(def z 1)"}))
(chk "replace-in-body -> {:edit replace-in-body} envelope"
     (= (:edit (call "replace-in-body" {:module "schema" :name "f" :old "(a)" :new "(b)"}))
        {:op "replace-in-body" :module "schema" :name "f" :old "(a)" :new "(b)" :within nil}))
;; server-side required-param enforcement on the edit verbs (fail-closed)
(chk "add-def missing form -> :error"        (contains? (call "add-def" {:module "schema"}) :error))
(chk "set-body missing name/body -> :error"  (contains? (call "set-body" {:module "schema"}) :error))
(chk "edit verb missing module -> :error"    (contains? (call "add-def" {:form "(def x 1)"}) :error))
(chk "edit envelope carries no single-triple :write key"
     (nil? (:write (call "set-body" {:module "schema" :name "c" :body "1"}))))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.tools:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.tools:" (count fails) "FAILED") (System/exit 1))))
