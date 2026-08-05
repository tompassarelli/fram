(ns fram.query
  (:require [fram.datalog :as d]
            [fram.types :as t]
            [clojure.string :as str]))

(defrecord AggregateSpec [operator argument])

(defn aggregatespec-operator [r] (:operator r))

(defn aggregatespec-argument [r] (:argument r))

(defrecord HavingClause [operator aggregate-index value])

(defn havingclause-operator [r] (:operator r))

(defn havingclause-aggregate-index [r] (:aggregate-index r))

(defn havingclause-value [r] (:value r))

(defrecord FindSpec [relation grouping aggregates having])

(defn findspec-relation [r] (:relation r))

(defn findspec-grouping [r] (:grouping r))

(defn findspec-aggregates [r] (:aggregates r))

(defn findspec-having [r] (:having r))

(defrecord QueryPlan [find strata])

(defn queryplan-find [r] (:find r))

(defn queryplan-strata [r] (:strata r))

(defrecord Projection [edb candidates])

(defn projection-edb [r] (:edb r))

(defn projection-candidates [r] (:candidates r))

(defrecord QueryError [code message])

(defn queryerror-code [r] (:code r))

(defn queryerror-message [r] (:message r))

(defrecord CompileResult [plan errors])

(defn compileresult-plan [r] (:plan r))

(defn compileresult-errors [r] (:errors r))

(defrecord QueryResult [rows errors over-limit maximum])

(defn queryresult-rows [r] (:rows r))

(defn queryresult-errors [r] (:errors r))

(defn queryresult-over-limit [r] (:over-limit r))

(defn queryresult-maximum [r] (:maximum r))

(defrecord QueryPage [rows next more errors max-bytes])

(defn querypage-rows [r] (:rows r))

(defn querypage-next [r] (:next r))

(defn querypage-more [r] (:more r))

(defn querypage-errors [r] (:errors r))

(defn querypage-max-bytes [r] (:max-bytes r))

(defrecord CursorResult [key error])

(defn cursorresult-key [r] (:key r))

(defn cursorresult-error [r] (:error r))

(def ^:dynamic *query-control* nil)

(def base-rel-arities {d/triple-relation 3 d/occurrence-relation 3 d/text-match-relation 3 d/text-phrase-relation 3 d/text-substring-relation 3 d/text-stem-relation 3 d/text-search-relation 4})

(def base-relations d/base-relations)

(def aggregate-operators #{:count :count-distinct :sum :avg :min :max})

(def aggregate-argument-operators #{:count-distinct :sum :avg :min :max})

(defn ^QueryError query-error [code ^String message]
  (->QueryError code message))

(defn ^FindSpec relation-find [^String relation]
  (->FindSpec relation [] [] []))

(defn ^AggregateSpec aggregate-spec [operator argument]
  (->AggregateSpec operator argument))

(defn ^HavingClause having-clause [operator aggregate-index value]
  (->HavingClause operator aggregate-index value))

(defn ^FindSpec aggregate-find [^String relation grouping aggregates having]
  (->FindSpec relation grouping aggregates having))

(defn ^QueryPlan query-plan [^FindSpec find strata]
  (->QueryPlan find strata))

(defn ^Boolean query-plan? [value]
  (instance? QueryPlan value))

(defn ^Boolean projection? [value]
  (instance? Projection value))

(defn ^Boolean aggregate-find? [^FindSpec find]
  (not (empty? (findspec-aggregates find))))

(defn ^Boolean compile-ok? [^CompileResult result]
  (empty? (compileresult-errors result)))

(defn compiled-plan [^CompileResult result]
  (compileresult-plan result))

(defn compile-errors [^CompileResult result]
  (compileresult-errors result))

(defn ^Boolean result-ok? [^QueryResult result]
  (empty? (queryresult-errors result)))

(defn result-rows [^QueryResult result]
  (queryresult-rows result))

(defn result-errors [^QueryResult result]
  (queryresult-errors result))

(defn result-over-limit [^QueryResult result]
  (queryresult-over-limit result))

(defn result-maximum [^QueryResult result]
  (queryresult-maximum result))

(defn ^Boolean page-ok? [^QueryPage page]
  (empty? (querypage-errors page)))

(defn page-rows [^QueryPage page]
  (querypage-rows page))

(defn page-next [^QueryPage page]
  (querypage-next page))

(defn ^Boolean page-more? [^QueryPage page]
  (querypage-more page))

(defn page-errors [^QueryPage page]
  (querypage-errors page))

(defn error-code [^QueryError error-value]
  (queryerror-code error-value))

(defn ^String error-message [^QueryError error-value]
  (queryerror-message error-value))

(defn- ^QueryResult success-result [rows]
  (->QueryResult rows [] nil nil))

(defn- ^QueryResult failure-result [errors]
  (->QueryResult [] errors nil nil))

(defn- ^QueryResult limited-result [^QueryError error-value count-value maximum]
  (->QueryResult [] [error-value] count-value maximum))

(defn- ^QueryPage success-page [rows next ^Boolean more]
  (->QueryPage rows next more [] nil))

(defn- ^QueryPage failure-page [errors]
  (->QueryPage [] nil false errors nil))

(defn- ^QueryPage wire-failure-page [^QueryError error-value maximum]
  (->QueryPage [] nil false [error-value] maximum))

(defn ^Projection project-with-candidates [propositions candidates]
  (->Projection (d/edb propositions) candidates))

(defn ^Projection project-with-source [propositions ^String relation source]
  (project-with-candidates propositions {relation source}))

(defn ^Projection project [propositions]
  (project-with-candidates propositions (d/build-text-candidates propositions)))

(defn ^Projection project-with-occurrences [propositions occurrences]
  (->Projection (d/edb-with-occurrences propositions occurrences) (d/build-text-candidates propositions)))

(defn- set-union [left right]
  (reduce (fn [acc value] (conj acc value)) left right))

(defn- term-vars [terms]
  (reduce (fn [acc term] (let [name (d/queryterm-variable term)]
  (if (some? name) (conj acc name) acc))) #{} terms))

(defn- term-errors [terms ^String context]
  (reduce (fn [acc term] (if (d/query-term? term) acc (conj acc (query-error :query-invalid-term (str context " contains an invalid QueryTerm"))))) [] terms))

(defn- unbound-errors [terms bound ^String context]
  (reduce (fn [acc name] (if (contains? bound name) acc (conj acc (query-error :query-unbound-variable (str context " variable '" name "' is not bound"))))) [] (term-vars terms)))

(defn- head-arities [rules]
  (reduce (fn [acc rule] (let [relation (d/rule-head-relation rule)]
  (if (contains? acc relation) acc (assoc acc relation (count (d/rule-head-arguments rule)))))) {} rules))

(defn- derived-relations [rules]
  (reduce (fn [acc rule] (conj acc (d/rule-head-relation rule))) #{} rules))

(defn- relation-arity [^String relation arities]
  (if (contains? base-rel-arities relation) (get base-rel-arities relation) (get arities relation)))

(defn- literal-errors [literal known arities bound]
  (let [kind (d/literal-kind literal)
   arguments (d/literal-arguments literal)
   base-errors (term-errors arguments "literal")]
  (cond
  (= kind :relation) (let [relation (d/literal-relation literal)
   arity (relation-arity relation arities)
   relation-errors (cond
  (not (contains? known relation)) [(query-error :query-unknown-relation (str "unknown relation '" relation "'"))]
  (and (some? arity) (not (= arity (count arguments)))) [(query-error :query-arity (str "relation '" relation "' takes " arity " arguments"))]
  :else [])
   range-errors (if (d/literal-negated literal) (unbound-errors arguments bound "negated literal") [])]
  (vec (concat base-errors (concat relation-errors range-errors))))
  (= kind :comparison) (let [operator-errors (if (contains? d/comparison-operators (d/literal-operator literal)) [] [(query-error :query-invalid-comparison "comparison operator is not supported")])
   arity-errors (if (= 2 (count arguments)) [] [(query-error :query-arity "comparison literal takes two arguments")])]
  (vec (concat base-errors (concat operator-errors (concat arity-errors (unbound-errors arguments bound "comparison"))))))
  (= kind :builtin) (let [operator-errors (if (contains? d/builtin-operators (d/literal-operator literal)) [] [(query-error :query-invalid-builtin "builtin operator is not supported")])
   arity-errors (if (= 2 (count arguments)) [] [(query-error :query-arity "builtin literal takes two arguments")])
   binding (d/literal-binding literal)
   binding-errors (cond
  (not (pos? (count binding))) [(query-error :query-invalid-builtin "builtin binding must be non-empty")]
  (contains? bound binding) [(query-error :query-unbound-variable (str "builtin binding '" binding "' is already bound"))]
  :else [])]
  (vec (concat base-errors (concat operator-errors (concat arity-errors (concat binding-errors (unbound-errors arguments bound "builtin")))))))
  :else [(query-error :query-invalid-literal "literal kind is not supported")])))

(defn- literal-bindings [literal]
  (cond
  (and (= :relation (d/literal-kind literal)) (not (d/literal-negated literal))) (term-vars (d/literal-arguments literal))
  (= :builtin (d/literal-kind literal)) #{(d/literal-binding literal)}
  :else #{}))

(defn- text-literal-errors [literal bound]
  (if (and (= :relation (d/literal-kind literal)) (contains? d/text-relations (d/literal-relation literal))) (let [relation (d/literal-relation literal)
   arguments (d/literal-arguments literal)]
  (cond
  (d/literal-negated literal) [(query-error :query-text-negative (str relation " is available only as a positive relation"))]
  (not (= (get base-rel-arities relation) (count arguments))) []
  :else (let [needle (nth arguments 2)
   variable (d/queryterm-variable needle)]
  (cond
  (some? variable) (if (contains? bound variable) [] [(query-error :query-text-unbound-needle (str relation " query must be constant or already bound"))])
  (d/text-relation-needle-valid? relation (d/queryterm-value needle)) []
  :else [(query-error :query-text-invalid-needle (str relation " query is empty or invalid"))])))) []))

(defn- rule-errors [rule known arities]
  (let [head-relation (d/rule-head-relation rule)
   head-arguments (d/rule-head-arguments rule)
   head-errors (vec (concat (if (pos? (count head-relation)) [] [(query-error :query-invalid-rule "rule head relation is empty")]) (concat (if (contains? base-relations head-relation) [(query-error :query-base-shadow (str "rule head cannot redefine base relation '" head-relation "'"))] []) (term-errors head-arguments "rule head"))))]
  (loop [remaining (d/rule-body rule)
   bound #{}
   errors head-errors]
  (if (empty? remaining) (vec (concat errors (unbound-errors head-arguments bound "rule head"))) (let [literal (first remaining)]
  (recur (rest remaining) (set-union bound (literal-bindings literal)) (vec (concat errors (concat (literal-errors literal known arities bound) (text-literal-errors literal bound))))))))))

(defn- arity-errors [rules]
  (let [arities (head-arities rules)]
  (reduce (fn [acc rule] (let [relation (d/rule-head-relation rule)
   expected (get arities relation)
   actual (count (d/rule-head-arguments rule))]
  (if (= expected actual) acc (conj acc (query-error :query-arity (str "relation '" relation "' has inconsistent arity")))))) [] rules)))

(defn- positive-relations [rule]
  (reduce (fn [acc literal] (if (and (= :relation (d/literal-kind literal)) (not (d/literal-negated literal))) (conj acc (d/literal-relation literal)) acc)) #{} (d/rule-body rule)))

(defn- dependency-edges [rules]
  (reduce (fn [acc rule] (let [head (d/rule-head-relation rule)]
  (update acc head (fn [current] (set-union (or current #{}) (positive-relations rule)))))) {} rules))

(defn- ^Boolean reaches? [^String start ^String target edges]
  (loop [frontier (vec (get edges start #{}))
   seen #{}]
  (if (empty? frontier) false (let [current (first frontier)]
  (cond
  (= current target) true
  (contains? seen current) (recur (rest frontier) seen)
  :else (recur (vec (concat (rest frontier) (vec (get edges current #{})))) (conj seen current)))))))

(defn- recursive-relations [rules]
  (let [edges (dependency-edges rules)]
  (reduce (fn [acc rule] (let [relation (d/rule-head-relation rule)]
  (if (reaches? relation relation edges) (conj acc relation) acc))) #{} rules)))

(defn- recursive-builtin-errors [rules]
  (let [recursive (recursive-relations rules)]
  (reduce (fn [acc rule] (if (and (contains? recursive (d/rule-head-relation rule)) (some (fn [literal] (= :builtin (d/literal-kind literal))) (d/rule-body rule))) (conj acc (query-error :query-recursive-builtin (str "recursive relation '" (d/rule-head-relation rule) "' cannot contain a builtin binding"))) acc)) [] rules)))

(defn- forward-errors [strata all-derived]
  (loop [remaining strata
   lower base-relations
   errors []]
  (if (empty? remaining) errors (let [stratum (first remaining)
   current (derived-relations stratum)
   available (set-union lower current)
   errors2 (reduce (fn [acc rule] (reduce (fn [inner relation] (if (and (contains? all-derived relation) (not (contains? available relation))) (conj inner (query-error :query-forward-reference (str "relation '" relation "' is defined only in a later stratum"))) inner)) acc (positive-relations rule))) errors stratum)]
  (recur (rest remaining) (set-union lower current) errors2)))))

(defn- find-errors [^FindSpec find derived arities]
  (let [relation (findspec-relation find)
   arity (get arities relation)
   relation-errors (cond
  (contains? base-relations relation) [(query-error :query-invalid-find "find cannot name a base relation")]
  (not (contains? derived relation)) [(query-error :query-invalid-find (str "find relation '" relation "' is not derived"))]
  :else [])
   aggregate-errors (if (aggregate-find? find) (let [group-errors (reduce (fn [acc position] (if (and (>= position 0) (and (some? arity) (< position arity))) acc (conj acc (query-error :query-invalid-aggregate "aggregate group position is out of range")))) [] (findspec-grouping find))
   spec-errors (reduce (fn [acc spec] (let [operator (aggregatespec-operator spec)
   argument (aggregatespec-argument spec)]
  (cond
  (not (contains? aggregate-operators operator)) (conj acc (query-error :query-invalid-aggregate "aggregate operator is not supported"))
  (and (contains? aggregate-argument-operators operator) (nil? argument)) (conj acc (query-error :query-invalid-aggregate "aggregate operator requires an argument position"))
  (and (some? argument) (not (and (>= argument 0) (and (some? arity) (< argument arity))))) (conj acc (query-error :query-invalid-aggregate "aggregate argument position is out of range"))
  :else acc))) [] (findspec-aggregates find))
   having-errors (reduce (fn [acc clause] (cond
  (not (contains? d/comparison-operators (havingclause-operator clause))) (conj acc (query-error :query-invalid-having "having operator is not supported"))
  (not (and (>= (havingclause-aggregate-index clause) 0) (< (havingclause-aggregate-index clause) (count (findspec-aggregates find))))) (conj acc (query-error :query-invalid-having "having aggregate index is out of range"))
  (not (number? (havingclause-value clause))) (conj acc (query-error :query-invalid-having "having comparison value must be numeric"))
  :else acc)) [] (findspec-having find))]
  (vec (concat group-errors (concat spec-errors having-errors)))) (if (or (not (empty? (findspec-grouping find))) (not (empty? (findspec-having find)))) [(query-error :query-invalid-find "plain find cannot contain grouping or having clauses")] []))]
  (vec (concat relation-errors aggregate-errors))))

(defn validate-plan [^QueryPlan plan]
  (let [strata (queryplan-strata plan)
   rules (reduce (fn [acc stratum] (vec (concat acc stratum))) [] strata)
   derived (derived-relations rules)
   known (set-union base-relations derived)
   arities (head-arities rules)
   empty-errors (if (empty? rules) [(query-error :query-invalid-plan "query plan must contain at least one rule")] [])
   rules-errors (reduce (fn [acc rule] (vec (concat acc (rule-errors rule known arities)))) [] rules)
   strata-errors (mapv (fn [message] (query-error :query-stratification message)) (d/strata-violations strata))]
  (vec (concat empty-errors (concat rules-errors (concat (arity-errors rules) (concat (recursive-builtin-errors rules) (concat (forward-errors strata derived) (concat strata-errors (find-errors (queryplan-find plan) derived arities))))))))))

(defn- ^Boolean variable-form? [value]
  (and (map? value) (and (= 1 (count value)) (and (contains? value :var) (and (string? (:var value)) (pos? (count (:var value))))))))

(defn- ^Boolean query-term-form? [value]
  (or (variable-form? value) (t/term? value)))

(defn- ^Boolean all-vectors? [values]
  (loop [remaining values]
  (if (empty? remaining) true (if (vector? (first remaining)) (recur (rest remaining)) false))))

(defn- raw-strata [form]
  (cond
  (not (map? form)) nil
  (and (contains? form :rules) (not (contains? form :strata))) (if (vector? (:rules form)) [(:rules form)] nil)
  (and (contains? form :strata) (not (contains? form :rules))) (if (and (vector? (:strata form)) (all-vectors? (:strata form))) (:strata form) nil)
  :else nil))

(defn- syntax-term-errors [values ^String context]
  (if (not (vector? values)) [(query-error :query-invalid-syntax (str context " arguments must be a vector"))] (reduce (fn [acc value] (if (query-term-form? value) acc (conj acc (query-error :query-invalid-syntax (str context " contains an invalid term"))))) [] values)))

(defn- syntax-literal-errors [literal]
  (if (not (map? literal)) [(query-error :query-invalid-syntax "literal must be a map")] (cond
  (contains? literal :pred) (vec (concat (if (keyword? (:pred literal)) [] [(query-error :query-invalid-syntax "comparison operator must be a keyword")]) (syntax-term-errors (:args literal) "comparison")))
  (contains? literal :fn) (vec (concat (if (and (keyword? (:fn literal)) (string? (:bind literal))) [] [(query-error :query-invalid-syntax "builtin requires keyword :fn and string :bind")]) (syntax-term-errors (:args literal) "builtin")))
  :else (vec (concat (if (string? (:rel literal)) [] [(query-error :query-invalid-syntax "relation literal requires string :rel")]) (concat (if (or (nil? (:neg literal)) (or (= true (:neg literal)) (= false (:neg literal)))) [] [(query-error :query-invalid-syntax "literal :neg must be boolean")]) (syntax-term-errors (:args literal) "literal")))))))

(defn- syntax-rule-errors [rule]
  (if (not (map? rule)) [(query-error :query-invalid-syntax "rule must be a map")] (let [head (:head rule)
   body (:body rule)
   head-errors (if (and (map? head) (and (string? (:rel head)) (vector? (:args head)))) (syntax-term-errors (:args head) "rule head") [(query-error :query-invalid-syntax "rule head must contain string :rel and vector :args")])
   body-errors (if (vector? body) (reduce (fn [acc literal] (vec (concat acc (syntax-literal-errors literal)))) [] body) [(query-error :query-invalid-syntax "rule body must be a vector")])]
  (vec (concat head-errors body-errors)))))

(defn- syntax-find-errors [find]
  (cond
  (string? find) []
  (not (map? find)) [(query-error :query-invalid-syntax "find must be a relation string or aggregate map")]
  :else (let [base-errors (vec (concat (if (string? (:rel find)) [] [(query-error :query-invalid-syntax "aggregate find requires string :rel")]) (concat (if (and (vector? (:group find)) (every? integer? (:group find))) [] [(query-error :query-invalid-syntax "aggregate :group must contain integer positions")]) (if (and (vector? (:agg find)) (not (empty? (:agg find)))) [] [(query-error :query-invalid-syntax "aggregate :agg must be a non-empty vector")]))))
   spec-errors (if (vector? (:agg find)) (reduce (fn [acc spec] (if (and (map? spec) (and (keyword? (:op spec)) (or (nil? (:arg spec)) (integer? (:arg spec))))) acc (conj acc (query-error :query-invalid-syntax "aggregate spec requires keyword :op and optional integer :arg")))) [] (:agg find)) [])
   having-errors (if (or (nil? (:having find)) (vector? (:having find))) (reduce (fn [acc clause] (if (and (map? clause) (and (keyword? (:op clause)) (and (integer? (:agg clause)) (number? (:val clause))))) acc (conj acc (query-error :query-invalid-syntax "having clause requires :op, integer :agg, and numeric :val")))) [] (or (:having find) [])) [(query-error :query-invalid-syntax "aggregate :having must be a vector")])]
  (vec (concat base-errors (concat spec-errors having-errors))))))

(defn- syntax-errors [form]
  (cond
  (not (map? form)) [(query-error :query-invalid-syntax "query must be a map")]
  (= (contains? form :rules) (contains? form :strata)) [(query-error :query-invalid-syntax "query must provide exactly one of :rules or :strata")]
  (nil? (raw-strata form)) [(query-error :query-invalid-syntax "rules or strata have an invalid shape")]
  :else (let [strata (raw-strata form)
   rules (reduce (fn [acc stratum] (vec (concat acc stratum))) [] strata)
   rule-errors (reduce (fn [acc rule] (vec (concat acc (syntax-rule-errors rule)))) [] rules)]
  (vec (concat (syntax-find-errors (:find form)) rule-errors)))))

(defn- compile-term-form [value]
  (if (variable-form? value) (d/variable (:var value)) (d/constant value)))

(defn- compile-term-forms [values]
  (mapv (fn [value] (compile-term-form value)) values))

(defn- compile-literal-form [literal]
  (cond
  (contains? literal :pred) (d/comparison-literal (:pred literal) (compile-term-forms (:args literal)))
  (contains? literal :fn) (d/builtin-literal (:fn literal) (compile-term-forms (:args literal)) (:bind literal))
  (= true (:neg literal)) (d/negated-literal (:rel literal) (compile-term-forms (:args literal)))
  :else (d/relation-literal (:rel literal) (compile-term-forms (:args literal)))))

(defn- compile-rule-form [rule]
  (d/rule (:rel (:head rule)) (compile-term-forms (:args (:head rule))) (mapv (fn [literal] (compile-literal-form literal)) (:body rule))))

(defn- ^FindSpec compile-find-form [find]
  (if (string? find) (relation-find find) (aggregate-find (:rel find) (:group find) (mapv (fn [spec] (aggregate-spec (:op spec) (:arg spec))) (:agg find)) (mapv (fn [clause] (having-clause (:op clause) (:agg clause) (:val clause))) (or (:having find) [])))))

(defn ^CompileResult compile-query [form]
  (if (query-plan? form) (let [errors (validate-plan form)]
  (if (empty? errors) (->CompileResult form []) (->CompileResult nil errors))) (let [errors (syntax-errors form)]
  (if (not (empty? errors)) (->CompileResult nil errors) (let [strata (mapv (fn [stratum] (mapv (fn [rule] (compile-rule-form rule)) stratum)) (raw-strata form))
   plan (query-plan (compile-find-form (:find form)) strata)
   validation-errors (validate-plan plan)]
  (if (empty? validation-errors) (->CompileResult plan []) (->CompileResult nil validation-errors)))))))

(defn- ^String length-key [^String tag ^String value]
  (str tag (count value) ":" value))

(defn ^String term-key [value]
  (cond
  (string? value) (length-key "s" value)
  (integer? value) (str "i" value ";")
  (number? value) (str "f" value ";")
  (boolean? value) (if value "b1;" "b0;")
  (keyword? value) (length-key "k" (str value))
  (t/instant? value) (str "m" (t/instant-epoch-seconds value) ":" (t/instant-nanos value) ";")
  (t/triple? value) (let [slot0 (term-key (t/triple-slot0 value))
   slot1 (term-key (t/triple-slot1 value))
   slot2 (term-key (t/triple-slot2 value))]
  (str "t" (count slot0) ":" slot0 (count slot1) ":" slot1 (count slot2) ":" slot2))
  :else (length-key "x" (pr-str value))))

(defn ^String row-key [row]
  (reduce (fn [acc value] (let [key (term-key value)]
  (str acc (count key) ":" key))) "r" row))

(defn- ordered-rows [rows]
  (vec (sort-by row-key (vec rows))))

(def max-results (let [raw (System/getenv "FRAM_MAX_RESULTS")
   parsed (if (and (some? raw) (not (= raw ""))) (parse-long raw) nil)]
  (if (and (some? parsed) (> parsed 0)) parsed 100000)))

(defn- evaluate-plan-controlled! [^Projection projection ^QueryPlan plan control]
  (d/run-strata-db-with-candidates-controlled! (projection-edb projection) (queryplan-strata plan) (projection-candidates projection) control))

(defn- evaluate-plan! [^Projection projection ^QueryPlan plan]
  (evaluate-plan-controlled! projection plan *query-control*))

(defn- ^QueryResult abort-result [problem]
  (let [data (ex-data problem)]
  (if (= :fram-query-abort (:type data)) (failure-result [(query-error (:code data) (.getMessage problem))]) (throw problem))))

(def numeric-aggregate-operators #{:sum :avg :min :max})

(defn- number-atom [value]
  (cond
  (integer? value) value
  (number? value) (double value)
  (string? value) (let [integer-result (parse-long value)]
  (if (some? integer-result) integer-result (parse-double value)))
  :else nil))

(defn- numeric-column-error [rows ^FindSpec find]
  (loop [specs (findspec-aggregates find)]
  (if (empty? specs) nil (let [spec (first specs)]
  (if (contains? numeric-aggregate-operators (aggregatespec-operator spec)) (let [position (aggregatespec-argument spec)
   bad (some (fn [row] (nil? (number-atom (nth row position)))) rows)]
  (if bad (query-error :query-nonnumeric-aggregate (str "aggregate position " position " contains a non-numeric Term")) (recur (rest specs)))) (recur (rest specs)))))))

(defn- group-rows [rows grouping]
  (reduce (fn [acc row] (let [key (mapv (fn [position] (nth row position)) grouping)]
  (update acc key (fn [current] (conj (or current []) row))))) {} rows))

(defn- ^Boolean all-integers? [rows position]
  (every? (fn [row] (integer? (number-atom (nth row position)))) rows))

(defn- sum-values [rows position]
  (if (all-integers? rows position) (reduce (fn [acc row] (+ acc (number-atom (nth row position)))) 0 rows) (reduce (fn [acc row] (+ acc (double (number-atom (nth row position))))) 0.0 rows)))

(defn- extreme-value [rows position ^Boolean maximum?]
  (reduce (fn [best row] (let [candidate (number-atom (nth row position))]
  (if (nil? best) candidate (if (if maximum? (> (double candidate) (double best)) (< (double candidate) (double best))) candidate best)))) nil rows))

(defn- aggregate-value [rows ^AggregateSpec spec]
  (let [operator (aggregatespec-operator spec)
   position (or (aggregatespec-argument spec) 0)]
  (cond
  (= operator :count) (count rows)
  (= operator :count-distinct) (count (set (mapv (fn [row] (nth row position)) rows)))
  (= operator :sum) (sum-values rows position)
  (= operator :avg) (/ (double (sum-values rows position)) (double (count rows)))
  (= operator :min) (extreme-value rows position false)
  (= operator :max) (extreme-value rows position true)
  :else nil)))

(defn- ^Boolean comparison-number [operator left right]
  (let [left-number (double left)
   right-number (double right)]
  (cond
  (= operator :eq) (= left-number right-number)
  (= operator :ne) (not (= left-number right-number))
  (= operator :lt) (< left-number right-number)
  (= operator :le) (<= left-number right-number)
  (= operator :gt) (> left-number right-number)
  (= operator :ge) (>= left-number right-number)
  :else false)))

(defn- ^Boolean having-passes? [row grouping-count clauses]
  (every? (fn [clause] (comparison-number (havingclause-operator clause) (nth row (+ grouping-count (havingclause-aggregate-index clause))) (havingclause-value clause))) clauses))

(defn- ^QueryResult aggregate-result [db ^FindSpec find]
  (let [rows (vec (get db (findspec-relation find) #{}))]
  (if (empty? rows) (success-result []) (let [numeric-error (numeric-column-error rows find)]
  (if (some? numeric-error) (failure-result [numeric-error]) (let [groups (group-rows rows (findspec-grouping find))
   aggregated (reduce (fn [acc entry] (let [key (nth entry 0)
   members (nth entry 1)
   values (mapv (fn [spec] (aggregate-value members spec)) (findspec-aggregates find))]
  (conj acc (vec (concat key values))))) [] groups)
   survivors (filterv (fn [row] (having-passes? row (count (findspec-grouping find)) (findspec-having find))) aggregated)
   count-value (count survivors)]
  (if (> count-value max-results) (limited-result (query-error :query-result-limit (str "aggregate result has " count-value " groups, over limit " max-results)) count-value max-results) (success-result (vec (sort-by row-key survivors))))))))))

(defn ^QueryResult run-plan-projected-controlled! [^Projection projection ^QueryPlan plan control]
  (let [errors (validate-plan plan)]
  (if (not (empty? errors)) (failure-result errors) (try
  (let [db (evaluate-plan-controlled! projection plan control)
   find (queryplan-find plan)]
  (if (aggregate-find? find) (aggregate-result db find) (let [rows (get db (findspec-relation find) #{})
   count-value (count rows)]
  (if (> count-value max-results) (limited-result (query-error :query-result-limit (str "result has " count-value " rows, over limit " max-results)) count-value max-results) (success-result (ordered-rows rows))))))
  (catch Exception problem
    (abort-result problem))))))

(defn ^QueryResult run-plan-projected! [^Projection projection ^QueryPlan plan]
  (run-plan-projected-controlled! projection plan *query-control*))

(defn ^QueryResult run-projected! [^Projection projection ^QueryPlan plan]
  (run-plan-projected! projection plan))

(defn ^QueryResult run! [propositions ^QueryPlan plan]
  (run-plan-projected! (project propositions) plan))

(defn ^QueryResult run-syntax! [propositions form]
  (let [compiled (compile-query form)
   plan (compileresult-plan compiled)]
  (if (some? plan) (run! propositions plan) (failure-result (compileresult-errors compiled)))))

(def max-page-limit 4096)

(def max-page-wire-bytes 1048576)

(def max-page-payload-bytes (- max-page-wire-bytes 512))

(def max-page-cursor-bytes 524288)

(def ^String page-cursor-prefix "fram-query-term-page-v1.")

(defn- utf8-size [^String value]
  (count (.getBytes value "UTF-8")))

(defn ^String page-cursor [row]
  (str page-cursor-prefix (row-key row)))

(defn ^CursorResult decode-page-cursor [cursor]
  (if (not (string? cursor)) (->CursorResult nil (query-error :query-page-cursor "page cursor must be a string")) (if (or (> (utf8-size cursor) max-page-cursor-bytes) (or (not (str/starts-with? cursor page-cursor-prefix)) (= cursor page-cursor-prefix))) (->CursorResult nil (query-error :query-page-cursor "page cursor is not canonical")) (->CursorResult (subs cursor (count page-cursor-prefix)) nil))))

(defn- ^QueryPage page-envelope [window count-value]
  (let [rows (subvec window 0 count-value)
   more (> (count window) count-value)
   next (if (and more (> count-value 0)) (page-cursor (nth rows (- count-value 1))) nil)]
  (success-page rows next more)))

(defn- envelope-size [rows-size next ^Boolean more]
  (+ (utf8-size "rows:") rows-size (utf8-size "|next:") (utf8-size (pr-str next)) (utf8-size "|more:") (utf8-size (pr-str more))))

(defn- fitting-prefix [window wanted]
  (loop [index 0
   rows-size 2
   best 0]
  (if (>= index wanted) best (let [row (nth window index)
   count-value (+ index 1)
   rows-size2 (+ rows-size (if (= index 0) 0 1) (utf8-size (pr-str row)))
   more (> (count window) count-value)
   next (if more (page-cursor row) nil)
   fits (and (or (nil? next) (<= (utf8-size next) max-page-cursor-bytes)) (<= (envelope-size rows-size2 next more) max-page-payload-bytes))]
  (recur count-value rows-size2 (if fits count-value best))))))

(defn- ^QueryPage abort-page [problem]
  (let [data (ex-data problem)]
  (if (= :fram-query-abort (:type data)) (failure-page [(query-error (:code data) (.getMessage problem))]) (throw problem))))

(defn ^QueryPage run-page-plan-projected! [^Projection projection ^QueryPlan plan limit after]
  (let [validation-errors (validate-plan plan)]
  (cond
  (not (empty? validation-errors)) (failure-page validation-errors)
  (aggregate-find? (queryplan-find plan)) (failure-page [(query-error :query-aggregate-not-pageable "aggregate results are not pageable")])
  (or (not (integer? limit)) (or (< limit 1) (> limit max-page-limit))) (failure-page [(query-error :query-page-limit (str "page limit must be from 1 through " max-page-limit))])
  (and (some? after) (not (string? after))) (failure-page [(query-error :query-page-cursor "page cursor must be a string or nil")])
  :else (let [decoded (if (some? after) (decode-page-cursor after) (->CursorResult nil nil))
   cursor-error (cursorresult-error decoded)]
  (if (some? cursor-error) (failure-page [cursor-error]) (try
  (let [db (evaluate-plan! projection plan)
   relation (get db (findspec-relation (queryplan-find plan)) #{})
   ordered (ordered-rows relation)
   after-key (cursorresult-key decoded)
   eligible (if (some? after-key) (filterv (fn [row] (pos? (compare (row-key row) after-key))) ordered) ordered)
   window (vec (take (+ limit 1) eligible))
   wanted (min limit (count window))]
  (if (= wanted 0) (page-envelope window 0) (let [count-value (fitting-prefix window wanted)]
  (if (= count-value 0) (wire-failure-page (query-error :query-page-row-too-large "page contains a row too large for the bounded response") max-page-wire-bytes) (page-envelope window count-value)))))
  (catch Exception problem
    (abort-page problem))))))))

(defn ^QueryPage run-page-projected! [^Projection projection ^QueryPlan plan limit after]
  (run-page-plan-projected! projection plan limit after))

(defn ^QueryPage run-page! [propositions ^QueryPlan plan limit after]
  (run-page-plan-projected! (project propositions) plan limit after))
