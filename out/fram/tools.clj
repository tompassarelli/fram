(ns fram.tools
  (:require [fram.kernel :as k]
            [fram.query :as q]
            [clojure.string :as str]))

(def Op-values #{::tell ::retract ::show ::query ::validate ::upsert-form ::set-body ::rename-def ::insert-after ::replace-in-body})

(defrecord PredVal [pred value])

(defn predval-pred [r] (:pred r))

(defn predval-value [r] (:value r))

(defrecord ThreadViolation [thread violation])

(defn threadviolation-thread [r] (:thread r))

(defn threadviolation-violation [r] (:violation r))

(defrecord Param [name type required])

(defn param-name [r] (:name r))

(defn param-type [r] (:type r))

(defn param-required [r] (:required r))

(defrecord ToolSpec [name desc params op pred])

(defn toolspec-name [r] (:name r))

(defn toolspec-desc [r] (:desc r))

(defn toolspec-params [r] (:params r))

(defn toolspec-op [r] (:op r))

(defn toolspec-pred [r] (:pred r))

(defn- at [id]
  (if (string? id) (if (str/starts-with? id "@") id (str "@" id)) id))

(defn- ^Boolean all-ref? [claims ^String pred]
  (loop [cs claims
   seen false]
  (if (empty? cs) seen (let [c (first cs)]
  (if (= (:p c) pred) (if (and (string? (:r c)) (str/starts-with? (:r c) "@")) (recur (rest cs) true) false) (recur (rest cs) seen))))))

(defn ref-value [claims ^String pred value]
  (if (all-ref? claims pred) (at value) value))

(defn catalog [claims]
  (let [subj-param [(->Param "subject" "string" true)]
   spo-params [(->Param "subject" "string" true) (->Param "predicate" "string" true) (->Param "object" "string" true)]]
  [(->ToolSpec "tell" (str "Assert the fact (subject, predicate, object). A single-valued " "predicate replaces its current value; a multi-valued one accumulates " "across repeated tells. A bare id for a reference predicate is auto-@-" "prefixed. Asserts ANY predicate, including one not yet in the vocabulary " "(predicate cardinality itself is a fact: `tell <pred> cardinality " "single|multi`).") spo-params :tell "") (->ToolSpec "retract" "Retract the exact fact (subject, predicate, object). The verb `untell` is an accepted alias." spo-params :retract "") (->ToolSpec "show" (str "All facts about <subject>. Predicates are entities too: " "`show <pred>` reveals its cardinality/value_kind/acyclic facts.") subj-param :show "") (->ToolSpec "ask" (str "Ad-hoc recursive query for multi-hop questions and vocabulary " "enumeration. Pass a structured Datalog-shaped object: " "{:find <rel> :rules [{:head {:rel R :args [terms]} :body [{:rel r :args [terms] :neg <bool>}]}]}. " "A term is {:var \"x\"} or a constant; base relations are fact(l,p,r) and fact-id(cid,l,p,r) " "(the pre-rename names triple/claim are accepted as aliases). " "Recursion and stratified negation are supported; the query is validated before it runs.") [(->Param "query" "object" true)] :query "") (->ToolSpec "validate" "Structural integrity violations (cycles, dangling refs) across all threads." [] :validate "") (->ToolSpec "add-def" (str "Author a graph-owned (claim-canonical) Beagle module: add a NEW top-level def, or " "REPLACE an existing one by name (upsert by the def name). `form` is the " "whole top-level form as an EDN datum string, e.g. " "\"(defn add-two [x :- Int] :- Int (base (+ x 2)))\". Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "form" "string" true)] :upsert-form "") (->ToolSpec "set-body" (str "Author a graph-owned (claim-canonical) Beagle module: replace the BODY of an existing " "defn named <name>. `body` is the new body as an EDN datum string, e.g. " "\"(* x 10)\" (params + return type are preserved). Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "name" "string" true) (->Param "body" "string" true)] :set-body "") (->ToolSpec "rename-def" (str "Author a graph-owned (claim-canonical) Beagle module: rename a top-level def from <name> " "to <new-name> (O(1), scope-correct via refers_to, shadow-safe; references " "follow by identity). Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "name" "string" true) (->Param "new-name" "string" true)] :rename-def "") (->ToolSpec "insert-after" (str "Author a graph-owned (claim-canonical) Beagle module: insert a NEW top-level def AFTER an " "anchor def named <after>, at a CRDT (path,tie) order-key strictly between the " "anchor and its next sibling. `form` is the whole top-level form as an EDN datum " "string, e.g. \"(defn add-two [x :- Int] :- Int (base (+ x 2)))\". Two concurrent " "inserts after the same anchor COMMUTE (both land at distinct ties). " "Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "after" "string" true) (->Param "form" "string" true)] :insert-after "") (->ToolSpec "replace-in-body" (str "Author a graph-owned (claim-canonical) Beagle module: replace ONE interior form inside def " "<name>, WITHOUT re-emitting the whole def (the sub-def surgical edit). `old` is " "the existing interior form as an EDN datum string (the anchor — matched " "STRUCTURALLY, like an Edit old_string on the AST; must match exactly one interior " "form, else rejected). `new` is its replacement as an EDN datum string. e.g. old " "\"(when done (finish))\" new \"(when done (cleanup) (finish))\". If `old` is " "AMBIGUOUS (>1 match), the rejection lists candidates with breadcrumbs + a " "copy-pastable enclosing form for each; pass one as the OPTIONAL `within` (an " "enclosing-form EDN datum string) to narrow the search — `old` must then match " "exactly one form INSIDE `within` (structural, survives concurrent edits; prefer " "it to an occurrence index). Preserves all sibling forms + comments. " "Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "name" "string" true) (->Param "old" "string" true) (->Param "new" "string" true) (->Param "within" "string" false)] :replace-in-body "")]))

(defn- spec-by-name [cat ^String name]
  (loop [cs cat]
  (if (empty? cs) nil (if (= (:name (first cs)) name) (first cs) (recur (rest cs))))))

(defn- missing-req [op args]
  (let [need-spo (or (= op :tell) (= op :retract))
   need-module (or (= op :upsert-form) (or (= op :set-body) (or (= op :rename-def) (or (= op :insert-after) (= op :replace-in-body)))))
   e1 (if (and need-spo (or (nil? (:subject args)) (or (nil? (:predicate args)) (nil? (:object args))))) ["missing required param 'subject' and/or 'predicate' and/or 'object'"] [])
   e2 (if (and (= op :show) (nil? (:subject args))) ["missing required param 'subject'"] [])
   e3 (if (and (= op :query) (nil? (:query args))) ["missing required param 'query'"] [])
   e4 (if (and need-module (nil? (:module args))) ["missing required param 'module'"] [])
   e5 (if (and (= op :upsert-form) (nil? (:form args))) ["missing required param 'form'"] [])
   e6 (if (and (= op :set-body) (or (nil? (:name args)) (nil? (:body args)))) ["missing required param 'name' and/or 'body'"] [])
   e7 (if (and (= op :rename-def) (or (nil? (:name args)) (nil? (:new-name args)))) ["missing required param 'name' and/or 'new-name'"] [])
   e8 (if (and (= op :insert-after) (or (nil? (:after args)) (nil? (:form args)))) ["missing required param 'after' and/or 'form'"] [])
   e9 (if (and (= op :replace-in-body) (or (nil? (:name args)) (or (nil? (:old args)) (nil? (:new args))))) ["missing required param 'name' and/or 'old' and/or 'new'"] [])]
  (vec (concat e1 (concat e2 (concat e3 (concat e4 (concat e5 (concat e6 (concat e7 (concat e8 e9)))))))))))

(defn call [claims idx cat ^String tool args]
  (let [tool (if (= tool "query") "ask" (if (= tool "untell") "retract" tool))
   spec (spec-by-name cat tool)]
  (if (nil? spec) {:error [(str "unknown tool '" tool "' — call `tools` for the catalog")]} (let [op (:op spec)
   miss (missing-req op args)]
  (if (not (empty? miss)) {:error miss} (let [subj (:subject args)
   pred (str (:predicate args))
   te (at subj)]
  (cond
  (= op :tell) {:write {:op "assert" :l te :p pred :r (ref-value claims pred (:object args))}}
  (= op :retract) {:write {:op "retract" :l te :p pred :r (ref-value claims pred (:object args))}}
  (= op :show) {:rows (mapv (fn [c] (->PredVal (:p c) (:r c))) (k/q-by-l claims te))}
  (= op :query) (q/run claims (:query args))
  (= op :validate) {:rows (reduce (fn [acc t] (vec (concat acc (mapv (fn [v] (->ThreadViolation t v)) (k/violations-i idx t))))) [] (k/thread-ids-i idx))}
  (= op :upsert-form) {:edit {:op "upsert-form" :module (:module args) :form (:form args)}}
  (= op :set-body) {:edit {:op "set-body" :module (:module args) :name (:name args) :body (:body args)}}
  (= op :rename-def) {:edit {:op "rename" :module (:module args) :name (:name args) :new-name (:new-name args)}}
  (= op :insert-after) {:edit {:op "insert-form" :module (:module args) :after (:after args) :form (:form args)}}
  (= op :replace-in-body) {:edit {:op "replace-in-body" :module (:module args) :name (:name args) :old (:old args) :new (:new args) :within (:within args)}}
  :else {:error [(str "unhandled op for tool '" tool "'")]})))))))
