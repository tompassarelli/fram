(ns resolve-verbs
  (:require [clojure.string :as str]
            [fram.types :as t]
            [fram.store :as c]
            [resolve-core :as rc]
            [resolve-read :as rr]
            [resolve-binds :as rb]
            [resolve-modules :as rm]
            [resolve-render :as rv]
            [clojure.edn :as edn]))

(defrecord Verb [ctx view tx SUP KIND Vp srcs capture-only? emit-srcs reject reject2 warn emit extract out-path def-binding typeframe modframe forms-of module-name parse-require capture-refs ultimate BOUND REFERS wrapper-of form-for-victim descendants retire reresolve ents mint register scope-srcs fn-facts FIXED exit])

(defn verb-ctx [r] (:ctx r))

(defn verb-view [r] (:view r))

(defn verb-tx [r] (:tx r))

(defn verb-SUP [r] (:SUP r))

(defn verb-KIND [r] (:KIND r))

(defn verb-Vp [r] (:Vp r))

(defn verb-srcs [r] (:srcs r))

(defn verb-capture-only? [r] (:capture-only? r))

(defn verb-emit-srcs [r] (:emit-srcs r))

(defn verb-reject [r] (:reject r))

(defn verb-reject2 [r] (:reject2 r))

(defn verb-warn [r] (:warn r))

(defn verb-emit [r] (:emit r))

(defn verb-extract [r] (:extract r))

(defn verb-out-path [r] (:out-path r))

(defn verb-def-binding [r] (:def-binding r))

(defn verb-typeframe [r] (:typeframe r))

(defn verb-modframe [r] (:modframe r))

(defn verb-forms-of [r] (:forms-of r))

(defn verb-module-name [r] (:module-name r))

(defn verb-parse-require [r] (:parse-require r))

(defn verb-capture-refs [r] (:capture-refs r))

(defn verb-ultimate [r] (:ultimate r))

(defn verb-BOUND [r] (:BOUND r))

(defn verb-REFERS [r] (:REFERS r))

(defn verb-wrapper-of [r] (:wrapper-of r))

(defn verb-form-for-victim [r] (:form-for-victim r))

(defn verb-descendants [r] (:descendants r))

(defn verb-retire [r] (:retire r))

(defn verb-reresolve [r] (:reresolve r))

(defn verb-ents [r] (:ents r))

(defn verb-mint [r] (:mint r))

(defn verb-register [r] (:register r))

(defn verb-scope-srcs [r] (:scope-srcs r))

(defn verb-fn-facts [r] (:fn-facts r))

(defn verb-FIXED [r] (:FIXED r))

(defn verb-exit [r] (:exit r))

(defn- ^Boolean upper-first? [^String s]
  (and (> (count s) 0) (let [c (subs s 0 1)]
  (and (>= (compare c "A") 0) (<= (compare c "Z") 0)))))

(defn verb-rename! [^Verb v ^String old ^String new ^String target]
  (let [ctx (:ctx v)
   tx (:tx v)
   srcs (:srcs v)
   warn (:warn v)
   reject (:reject v)
   dbind (:def-binding v)
   target-srcs (vec (filter (fn [s] (str/includes? s target)) srcs))
   edits (atom 0)]
  (doseq [src target-srcs]
  (if (and (some? (dbind src old)) (some? (dbind src new))) (do
  (warn (str "REJECTED — `" new "` already names a binding in " src " (rename-doesn't-collide; no facts mutated)."))
  (reject 3))))
  (doseq [src target-srcs]
  (if (and (some? (get (get (:typeframe v) src) old)) (not (upper-first? new))) (do
  (warn (str "REJECTED — `" new "` is not a valid (Capitalized) type name " "(beagle type-name shape; no facts mutated)."))
  (reject 3))))
  (doseq [src target-srcs]
  (let [B (dbind src old)]
  (if (some? B) (do
  (let [crefs (:capture-refs v)
   fo (:forms-of v)
   caps (vec (mapcat (fn [s] (vec (mapcat (fn [f] (vec (crefs f (list (get (:modframe v) s)) B new))) (vec (fo s))))) srcs))]
  (if (> (count caps) 0) (do
  (warn (str "REJECTED — renaming `" old "` -> `" new "` would be CAPTURED by a local `" new "` in scope at " (count caps) " reference(s) (no-capture; no facts mutated)."))
  (reject 4))))))))
  (let [mn (:module-name v)
   target-mods (set (vec (keep (fn [s] (mn s)) target-srcs)))]
  (doseq [src (vec (remove (fn [s] (some? (some (fn [t] (if (= t s) (do
  t))) target-srcs))) srcs))]
  (let [preq (:parse-require v)
   pr (preq src)
   refer (:refer pr)
   rename (:rename pr)
   home (get refer old)]
  (if (and (some? home) (contains? target-mods home) (or (some? (dbind src new)) (some? (get refer new)) (some? (get rename new)))) (do
  (warn (str "REJECTED — renaming `" old "` -> `" new "` would DUPLICATE a binding in consumer " src " (it already binds `" new "`; no-import-collision; no facts mutated)."))
  (reject 3))))))
  (doseq [src target-srcs]
  (let [B (dbind src old)]
  (if (some? B) (do
  (let [oldc (first (vec (filter (fn [cid] (= (:Vp v) (:p (c/fact-of ctx cid)))) (c/by-l ctx B))))
   nc (c/fact! ctx B (:Vp v) (c/value! ctx new) tx)]
  (if (some? oldc) (do
  (c/fact! ctx nc (:SUP v) oldc tx)))
  (swap! edits (fn [n] (+ n 1))))))))
  (if (= 0 (deref edits)) (do
  (warn (str "REJECTED — no binding named `" old "` found in \"" target "\" (nothing to rename; no facts mutated)."))
  (reject 5)))
  (if (not (:capture-only? v)) (do
  (let [ex (:extract v)
   op (:out-path v)]
  (doseq [src (:emit-srcs v)]
  (ex src))
  (warn "================ Turtle #5 — O(1) shadow-correct rename ================")
  (warn (str "edit: rename def `" old "` -> `" new "` in \"" target "\""))
  (warn (str "FACTS EDITED: " (deref edits) "  (just the definition's name; references follow refers_to)"))
  (doseq [src (:emit-srcs v)]
  (warn (str "projected -> " (op src) "   <- " src))))))))

(defn- nn [e]
  (if (nil? e) -1 e))

(defn- ekey [e]
  (nth (vec e) 0))

(defn- ecid [e]
  (nth (vec e) 1))

(defn- enode [e]
  (nth (vec e) 2))

(defn- ^String ord-tie [^Verb v]
  (if (:capture-only? v) "PENDING" "0"))

(defn- ^String ord-str* [path ^String tie]
  (str "f" (str/join "." path) "~" tie))

(defn datum->canon [d]
  (cond
  (nil? d) [:leaf "symbol" "nil"]
  (symbol? d) [:leaf "symbol" (str d)]
  (keyword? d) [:leaf "symbol" (str d)]
  (boolean? d) [:leaf "symbol" (if d "true" "false")]
  (string? d) [:leaf "string" d]
  (char? d) [:leaf "char" (str d)]
  (number? d) [:leaf "number" (str d)]
  (vector? d) (into [:list [:leaf "symbol" "#%brackets"]] (mapv datum->canon d))
  (map? d) (into [:list [:leaf "symbol" "#%map"]] (mapv datum->canon (apply concat (seq d))))
  (instance? java.util.regex.Pattern d) [:list [:leaf "symbol" "#%regex"] [:leaf "string" (.pattern d)]]
  (set? d) (into [:list [:leaf "symbol" "#%set"]] (mapv datum->canon d))
  (or (list? d) (seq? d)) (into [:list] (mapv datum->canon d))
  :else [:leaf "other" (pr-str d)]))

(defn- ^Boolean named-def-head? [h]
  (and (rc/writable-def-head? (str h)) (not (contains? (into #{"defmethod"} rc/EXTEND-FORMS) (str h)))))

(defn- node-def-name [^Verb v f]
  (let [ctx (:ctx v)
   view (:view v)
   nl0 (rr/unwrap-meta ctx view (second (rr/ordered-children ctx (rm/unwrap-def ctx view f))))
   nl (if (= "list" (rr/kind-of ctx view nl0)) (first (rr/ordered-children ctx nl0)) nl0)]
  (rr/sym-val ctx view nl)))

(defn writable-victim [^Verb v ^String src datum]
  (let [ctx (:ctx v)
   view (:view v)
   BOUND (:BOUND v)
   REFERS (:REFERS v)
   FIXED (:FIXED v)
   wrapper (:wrapper-of v)
   head (str (first datum))
   forms (rest (rr/ordered-children ctx (wrapper src)))]
  (cond
  (= head "defmethod") (let [m (str (second datum))
   dv (datum->canon (nth (vec datum) 2 nil))]
  (some (fn [f] (let [d (rm/unwrap-def ctx view f)
   k (rr/ordered-children ctx d)]
  (if (and (= "defmethod" (rr/head-sym ctx view d)) (= m (rr/sym-val ctx view (second k))) (= dv (rv/node->canon ctx view BOUND REFERS FIXED (nth (vec k) 2 nil)))) (do
  f)))) forms))
  (contains? rc/EXTEND-FORMS head) (let [tgt (datum->canon (second datum))]
  (some (fn [f] (let [d (rm/unwrap-def ctx view f)]
  (if (and (= head (rr/head-sym ctx view d)) (= tgt (rv/node->canon ctx view BOUND REFERS FIXED (second (rr/ordered-children ctx d))))) (do
  f)))) forms))
  :else (let [nm (str (second datum))]
  (some (fn [f] (if (and (named-def-head? (rr/head-sym ctx view (rm/unwrap-def ctx view f))) (= nm (node-def-name v f))) (do
  f))) forms)))))

(defn ^String writable-disp-name [datum]
  (let [head (str (first datum))]
  (cond
  (= head "defmethod") (str (second datum) ":" (pr-str (nth (vec datum) 2 nil)))
  (contains? rc/EXTEND-FORMS head) (str head " " (pr-str (second datum)))
  :else (str (second datum)))))

(defn wrap-forms [^Verb v parent]
  (let [ctx (:ctx v)
   rows (reduce (fn [acc cid] (let [cl (c/fact-of ctx cid)
   pi (if (nil? cl) nil (:p cl))
   k (if (int? pi) (rc/ord-parse (c/literal ctx pi)) nil)]
  (if (nil? k) acc (conj acc [k cid (:r cl)])))) [] (c/by-l ctx parent))]
  (vec (sort-by (fn [row] (nth row 0)) rc/ord-cmp rows))))

(defn- reject-candidate [^Verb v root site others idx]
  (let [ctx (:ctx v)
   view (:view v)
   BOUND (:BOUND v)
   REFERS (:REFERS v)
   FIXED (:FIXED v)
   chain (:chain site)
   breadcrumb (mapv (fn [n] (rv/crumb-label ctx view n)) chain)
   parent (:parent site)
   other-nodes (into #{} (mapcat (fn [o] (:chain o)) others))
   within (some (fn [a] (if (not (contains? other-nodes a)) (do
  (let [ac (rv/node->canon ctx view BOUND REFERS FIXED a)
   s (rv/node->str ctx view BOUND REFERS FIXED a)]
  (if (and (<= (count s) 800) (= ac (try
  (datum->canon (edn/read-string s))
  (catch Throwable _
    nil))) (= 1 (count (rv/anchor-match-sites ctx view BOUND REFERS FIXED root ac)))) (do
  s)))))) (reverse chain))
   ctx-str (let [s (rv/node->str ctx view BOUND REFERS FIXED parent)]
  (if (<= (count s) 200) s (str (subs s 0 197) "...")))]
  {:n idx :breadcrumb breadcrumb :within within :context ctx-str}))

(defn disambig-payload [^Verb v reason ^String name ^String scope root sites]
  (let [total (count sites)
   shown (vec (map-indexed (fn [i s] (reject-candidate v root s (concat (take i sites) (drop (+ i 1) sites)) (+ i 1))) (take rc/DISAMBIG-CAP sites)))
   head (cond
  (= reason :ambiguous-old) (str "anchor `old` is AMBIGUOUS inside `" name "` in \"" scope "\" (" total " matches; no facts mutated).")
  :else (str "`within` is AMBIGUOUS inside `" name "` in \"" scope "\" (" total " matches; no facts mutated). It must match exactly one enclosing form."))
   lines (map (fn [c] (str "  [" (:n c) "] " (str/join " > " (:breadcrumb c)) (if (:within c) (do
  (str "\n      within: " (:within c)))))) shown)
   remedy (cond
  (= reason :ambiguous-old) "Retry with :within set to one candidate's `within` form (it isolates that occurrence), or supply a larger :old."
  :else "Supply a `within` that names exactly one enclosing form (use a larger/more distinctive form).")]
  {:reason reason :verb "replace-in-body" :name name :scope scope :total total :shown (count shown) :candidates shown :remedy remedy :message (str "REJECTED — " head "\n" (str/join "\n" lines) "\n  remedy: " remedy)}))

(defn verb-delete! [^Verb v ^String name ^String scope]
  (let [ctx (:ctx v)
   view (:view v)
   srcs (:srcs v)
   warn (:warn v)
   reject (:reject v)
   dbind (:def-binding v)
   ffv (:form-for-victim v)
   desc (:descendants v)
   ult (:ultimate v)
   target-srcs (vec (filter (fn [s] (str/includes? s scope)) srcs))
   victims (vec (keep (fn [s] (dbind s name)) target-srcs))
   all-forms (set (vec (mapcat (fn [s] (vec (keep (fn [b] (ffv s b)) victims))) srcs)))
   subtree (reduce (fn [acc f] (into acc (desc f))) #{} (vec all-forms))
   orphans (vec (mapcat (fn [s] (vec (filter (fn [e] (let [tgt (rr/refers-target ctx view (:BOUND v) (:REFERS v) e)]
  (and (= "symbol" (rr/kind-of ctx view e)) (some? tgt) (not (contains? subtree e)) (contains? subtree (ult tgt))))) (vec (get (:ents v) s []))))) srcs))]
  (if (= 0 (count victims)) (do
  (warn (str "REJECTED — no binding named `" name "` found in \"" scope "\" (nothing to delete; no facts mutated)."))
  (reject 5)))
  (if (= 0 (count all-forms)) (do
  (warn (str "REJECTED — `" name "` is not an independently-deletable top-level form " "(a defunion variant / nested binding); no facts mutated."))
  (reject 5)))
  (if (> (count orphans) 0) (do
  (warn "================ delete + orphaned-reference invariant ================")
  (warn (str "REJECTED — " (count orphans) " reference(s) would be ORPHANED (no-orphaned-refs; no facts mutated):"))
  (doseq [o (vec (take 5 orphans))]
  (warn (str "  orphan: reference node " o " (`" (rr/sym-val ctx view o) "`)")))
  (reject 6)))
  (let [wof (:wrapper-of v)
   retire (:retire v)
   emit (:emit v)
   retired (atom 0)]
  (doseq [src srcs]
  (let [wrap (wof src)]
  (if (some? wrap) (do
  (doseq [entry (vec (wrap-forms v (nn wrap)))]
  (if (contains? all-forms (enode entry)) (do
  (retire (ecid entry))
  (swap! retired (fn [n] (+ n 1))))))))))
  (if (not (:capture-only? v)) (do
  (let [rr! (:reresolve v)]
  (rr!))))
  (emit "delete" (str "deleted def `" name "` in \"" scope "\" (" (deref retired) " wrapper form-edge(s) superseded; subtree orphaned + dropped on render; 0 orphaned refs)")))))

(defn verb-reorder! [^Verb v ^String name ^String scope after-name]
  (let [ctx (:ctx v)
   tx (:tx v)
   warn (:warn v)
   reject (:reject v)
   dbind (:def-binding v)
   ffv (:form-for-victim v)
   wof (:wrapper-of v)
   target-srcs (vec (filter (fn [s] (str/includes? s scope)) (:srcs v)))]
  (if (not= 1 (count target-srcs)) (do
  (warn (str "REJECTED — reorder scope \"" scope "\" matches " (count target-srcs) " files (need 1); no facts mutated."))
  (reject 3)))
  (let [src (first target-srcs)
   wrap (wof src)
   forms (vec (wrap-forms v (nn wrap)))
   mover-bind (dbind src name)
   mover-form (if (some? mover-bind) (do
  (ffv src mover-bind)))
   mover-entry (if (some? mover-form) (do
  (some (fn [e] (if (= (enode e) mover-form) (do
  e))) forms)))
   front? (str/blank? (str after-name))
   anchor-bind (if (not front?) (do
  (dbind src after-name)))
   anchor-form (if (some? anchor-bind) (do
  (ffv src anchor-bind)))
   anchor-idx (if (some? anchor-form) (do
  (first (vec (keep-indexed (fn [i e] (if (= (enode e) anchor-form) (do
  i))) forms)))))]
  (if (nil? mover-entry) (do
  (warn (str "REJECTED — reorder target `" name "` not found in \"" scope "\"; no facts mutated."))
  (reject 5)))
  (if (and (not front?) (nil? anchor-idx)) (do
  (warn (str "REJECTED — reorder anchor `" after-name "` not found in \"" scope "\"; no facts mutated."))
  (reject 3)))
  (if (not front?) (do
  (let [ai (nn anchor-idx)]
  (if (>= ai 0) (do
  (if (= (enode (nth forms ai)) mover-form) (do
  (warn (str "REJECTED — reorder `" name "` :after itself is a no-op; no facts mutated."))
  (reject 3))))))))
  (let [others (vec (remove (fn [e] (= (enode e) mover-form)) forms))
   a-pos (if front? -1 (nn (first (vec (keep-indexed (fn [i e] (if (= (enode e) anchor-form) (do
  i))) others)))))
   lo (if (< a-pos 0) nil (:path (ekey (nth others a-pos))))
   hi (if (< a-pos 0) (:path (ekey (first others))) (if (< (+ a-pos 1) (count others)) (do
  (:path (ekey (nth others (+ a-pos 1)))))))]
  (let [retire (:retire v)
   emit (:emit v)]
  (retire (ecid mover-entry))
  (c/fact! ctx (nn wrap) (c/value! ctx (ord-str* (rc/ord-between lo hi) (ord-tie v))) (nn mover-form) tx)
  (if (not (:capture-only? v)) (do
  (let [rr! (:reresolve v)]
  (rr!))))
  (emit "reorder" (str "moved def `" name "` " (if front? "to the front" (str "after `" after-name "`")) " in \"" scope "\" (wrapper order-key re-spelled; SAME subtree, 0 node churn)")))))))

(defn verb-upsert-form! [^Verb v ^String scope datum]
  (let [ctx (:ctx v)
   tx (:tx v)
   warn (:warn v)
   reject (:reject v)
   ssrcs (:scope-srcs v)
   target-srcs (vec (ssrcs scope))]
  (if (not= 1 (count target-srcs)) (do
  (warn (str "REJECTED — scope \"" scope "\" matches " (count target-srcs) " source files; upsert-form needs exactly one (no facts mutated)."))
  (reject 3)))
  (if (and (seq? datum) (not (rc/writable-def-head? (str (first datum))))) (do
  (warn (str "REJECTED — upsert-form spec head `" (first datum) "` is not a writable top-level def (def/defn/deftype/defmulti/defmethod/extend-*); no facts mutated."))
  (reject 3)))
  (let [wof (:wrapper-of v)
   mint (:mint v)
   src (first target-srcs)
   wrap (wof src)
   forms (vec (wrap-forms v (nn wrap)))
   disp-name (if (seq? datum) (do
  (writable-disp-name datum)))
   victim-form (if (seq? datum) (do
  (writable-victim v src datum)))
   victim-entry (if (some? victim-form) (do
  (some (fn [e] (if (= (enode e) victim-form) (do
  e))) forms)))
   new-root (mint src datum)]
  (if (some? victim-entry) (let [retire (:retire v)]
  (retire (ecid victim-entry))
  (c/fact! ctx (nn wrap) (c/value! ctx (ord-str* (:path (ekey victim-entry)) (ord-tie v))) (nn new-root) tx)) (let [last-path (if (> (count forms) 0) (do
  (:path (ekey (last forms)))))]
  (c/fact! ctx (nn wrap) (c/value! ctx (ord-str* (rc/ord-append last-path) (ord-tie v))) (nn new-root) tx)))
  (if (not (:capture-only? v)) (do
  (let [rr! (:reresolve v)]
  (rr!))))
  (let [emit (:emit v)]
  (emit "upsert-form" (str (if (some? victim-entry) "replaced" "added") " top-level def `" disp-name "` in \"" scope "\" (1 form minted as facts; refs resolved via refers_to)"))))))

(defn verb-insert-form! [^Verb v ^String scope ^String after-name datum]
  (let [ctx (:ctx v)
   tx (:tx v)
   warn (:warn v)
   reject (:reject v)
   target-srcs (vec (filter (fn [s] (str/includes? s scope)) (:srcs v)))]
  (if (not= 1 (count target-srcs)) (do
  (warn (str "REJECTED — insert-form scope \"" scope "\" matches " (count target-srcs) " files (need 1)."))
  (reject 3)))
  (if (and (seq? datum) (not (contains? rc/VALUE-DEFS (str (first datum))))) (do
  (warn (str "REJECTED — insert-form head `" (first datum) "` not a value def."))
  (reject 3)))
  (let [wof (:wrapper-of v)
   dbind (:def-binding v)
   ffv (:form-for-victim v)
   src (first target-srcs)
   wrap (wof src)
   forms (vec (wrap-forms v (nn wrap)))
   anchor-bind (dbind src after-name)
   anchor-form (if (some? anchor-bind) (do
  (ffv src anchor-bind)))
   i (nn (if (some? anchor-form) (do
  (first (vec (keep-indexed (fn [n e] (if (= (enode e) anchor-form) (do
  n))) forms))))))]
  (if (< i 0) (do
  (warn (str "REJECTED — insert-form anchor `" after-name "` not found in \"" scope "\"."))
  (reject 3)))
  (let [mint (:mint v)
   anchor-path (:path (ekey (nth forms i)))
   next-path (if (< (+ i 1) (count forms)) (do
  (:path (ekey (nth forms (+ i 1))))))
   new-root (mint src datum)]
  (c/fact! ctx (nn wrap) (c/value! ctx (ord-str* (rc/ord-between anchor-path next-path) (ord-tie v))) (nn new-root) tx)
  (if (not (:capture-only? v)) (do
  (let [rr! (:reresolve v)]
  (rr!))))
  (let [emit (:emit v)]
  (emit "insert-form" (str "inserted def after `" after-name "` in \"" scope "\" (CRDT mid-insert)")))))))

(def COMMENT-RE (re-pattern "comment\\d+"))

(defn- next-comment-idx [^Verb v form]
  (let [ctx (:ctx v)]
  (+ 1 (reduce (fn [acc n] (if (> n acc) n acc)) -1 (vec (keep (fn [cid] (let [cl (c/fact-of ctx cid)
   p (c/literal ctx (:p cl))]
  (if (and (string? p) (some? (re-matches COMMENT-RE (str p)))) (do
  (parse-long (subs (str p) 7)))))) (c/by-l ctx form)))))))

(defn verb-insert-comment! [^Verb v ^String scope ^String anchor-name ^String text placement]
  (let [ctx (:ctx v)
   tx (:tx v)
   warn (:warn v)
   reject (:reject v)
   target-srcs (vec (filter (fn [s] (str/includes? s scope)) (:srcs v)))]
  (if (not= 1 (count target-srcs)) (do
  (warn (str "REJECTED — insert-comment scope \"" scope "\" matches " (count target-srcs) " files (need 1)."))
  (reject 3)))
  (if (str/blank? text) (do
  (warn "REJECTED — insert-comment needs non-empty --text; no facts mutated.")
  (reject 3)))
  (let [dbind (:def-binding v)
   ffv (:form-for-victim v)
   src (first target-srcs)
   plc (if (contains? #{"leading" "trailing"} placement) (str placement) "leading")
   lex (if (str/starts-with? (str/triml text) ";") text (str ";; " text))
   anchor-bind (dbind src anchor-name)
   anchor-form (if (some? anchor-bind) (do
  (ffv src anchor-bind)))]
  (if (nil? anchor-form) (do
  (warn (str "REJECTED — insert-comment anchor `" anchor-name "` not found in \"" scope "\"."))
  (reject 3)))
  (let [reg (:register v)
   emit (:emit v)
   form (nn anchor-form)
   k (next-comment-idx v form)
   cnode (nn (reg src (c/entity! ctx)))
   seg (nn (reg src (c/entity! ctx)))]
  (c/fact! ctx cnode (:KIND v) (c/value! ctx "comment") tx)
  (c/fact! ctx cnode (c/value! ctx "style") (c/value! ctx "line") tx)
  (c/fact! ctx cnode (c/value! ctx "placement") (c/value! ctx plc) tx)
  (c/fact! ctx seg (:KIND v) (c/value! ctx "text") tx)
  (c/fact! ctx seg (:Vp v) (c/value! ctx lex) tx)
  (c/fact! ctx cnode (c/value! ctx "seg0") seg tx)
  (c/fact! ctx form (c/value! ctx (str "comment" k)) cnode tx)
  (if (not (:capture-only? v)) (do
  (let [rr! (:reresolve v)]
  (rr!))))
  (emit "insert-comment" (str "added " plc " comment on `" anchor-name "` in \"" scope "\" (comment" k "; 1 text seg minted)"))))))

(defn verb-set-body! [^Verb v ^String name ^String scope datum]
  (let [ctx (:ctx v)
   view (:view v)
   tx (:tx v)
   warn (:warn v)
   reject (:reject v)
   ssrcs (:scope-srcs v)
   target-srcs (vec (ssrcs scope))]
  (if (not= 1 (count target-srcs)) (do
  (warn (str "REJECTED — scope \"" scope "\" matches " (count target-srcs) " source files; set-body needs exactly one (no facts mutated)."))
  (reject 3)))
  (let [dbind (:def-binding v)
   ffv (:form-for-victim v)
   src (first target-srcs)
   B (dbind src name)
   form (if (some? B) (do
  (ffv src B)))
   d (if (some? form) (do
  (rm/unwrap-def ctx view (nn form))))]
  (if (or (nil? form) (not (contains? rc/VALUE-DEFS (str (rr/head-sym ctx view d))))) (do
  (warn (str "REJECTED — `" name "` is not a def/defn with a body in \"" scope "\" (set-body needs a value binding; no facts mutated)."))
  (reject 5)))
  (let [fnf (:fn-facts v)
   mint (:mint v)
   kids (vec (fnf d))
   param? (contains? rc/PARAM-FORMS (str (rr/head-sym ctx view d)))
   anchor-n (nn (if param? (some (fn [e] (if (rb/brackets? ctx view (nn (enode e))) (do
  (ekey e)))) kids) (some (fn [e] (if (= name (rr/sym-val ctx view (rr/unwrap-meta ctx view (nn (enode e))))) (do
  (ekey e)))) kids)))
   ret? (some (fn [e] (if (and (= (ekey e) (+ anchor-n 1)) (contains? rc/TYPE-COLON (str (rr/sym-val ctx view (nn (enode e)))))) (do
  true))) kids)
   body-start (+ anchor-n (if (some? ret?) 3 1))
   body-slots (vec (filter (fn [e] (>= (nn (ekey e)) body-start)) kids))
   new-root (mint src datum)]
  (if (= 0 (count body-slots)) (do
  (warn (str "REJECTED — `" name "` has no body fN edges to replace; no facts mutated."))
  (reject 5)))
  (let [retire (:retire v)
   emit (:emit v)]
  (doseq [e body-slots]
  (retire (ecid e)))
  (c/fact! ctx (nn d) (c/value! ctx (str "f" body-start)) (nn new-root) tx)
  (if (not (:capture-only? v)) (do
  (let [rr! (:reresolve v)]
  (rr!))))
  (emit "set-body" (str "replaced body of `" name "` in \"" scope "\" (" (count body-slots) " body slot(s) superseded; new body minted as facts)")))))))

(defn verb-replace-in-body! [^Verb v ^String name ^String scope old-datum new-datum within-datum]
  (let [ctx (:ctx v)
   view (:view v)
   tx (:tx v)
   warn (:warn v)
   reject (:reject v)
   reject2 (:reject2 v)
   target-srcs (vec (filter (fn [s] (str/includes? s scope)) (:srcs v)))]
  (if (not= 1 (count target-srcs)) (do
  (warn (str "REJECTED — scope \"" scope "\" matches " (count target-srcs) " source files; replace-in-body needs exactly one (no facts mutated)."))
  (reject 3)))
  (let [dbind (:def-binding v)
   ffv (:form-for-victim v)
   src (first target-srcs)
   B (dbind src name)
   form (if (some? B) (do
  (ffv src B)))]
  (if (nil? form) (do
  (warn (str "REJECTED — no def named `" name "` found in \"" scope "\" (nothing to edit; no facts mutated)."))
  (reject2 5 {:reason :no-def :verb "replace-in-body" :name name :scope scope :message (str "REJECTED — no def named `" name "` found in \"" scope "\".")})))
  (let [search-root (if (nil? within-datum) form (let [wcanon (datum->canon within-datum)
   wsites (rv/anchor-match-sites ctx view (:BOUND v) (:REFERS v) (:FIXED v) (nn form) wcanon)]
  (cond
  (= 0 (count wsites)) (do
  (warn (str "REJECTED — `within` form not found inside `" name "` in \"" scope "\" (0 matches; no facts mutated)."))
  (reject2 5 {:reason :no-within :verb "replace-in-body" :name name :scope scope :message (str "REJECTED — `within` form not found inside `" name "` in \"" scope "\" (0 matches).")}))
  (> (count wsites) 1) (do
  (warn (str "REJECTED — `within` is AMBIGUOUS inside `" name "` in \"" scope "\" (" (count wsites) " matches; no facts mutated)."))
  (reject2 5 (disambig-payload v :ambiguous-within name scope (nn form) wsites)))
  :else (:child (first wsites)))))
   target-canon (datum->canon old-datum)
   matches (rv/anchor-match-sites ctx view (:BOUND v) (:REFERS v) (:FIXED v) (nn search-root) target-canon)]
  (cond
  (= 0 (count matches)) (do
  (warn (str "REJECTED — anchor `old` not found inside " (if (some? within-datum) "the `within` form" (str "`" name "`")) " in \"" scope "\" (0 matches; no facts mutated). The old form must match " "an interior form structurally (head + spelling + child shape)."))
  (reject2 5 {:reason :no-old :verb "replace-in-body" :name name :scope scope :within (some? within-datum) :message (str "REJECTED — anchor `old` not found inside " (if (some? within-datum) "the `within` form" (str "`" name "`")) " in \"" scope "\" (0 matches).")}))
  (> (count matches) 1) (do
  (warn (str "REJECTED — anchor `old` is AMBIGUOUS inside " (if (some? within-datum) "the `within` form" (str "`" name "`")) " in \"" scope "\" (" (count matches) " matches; no facts mutated)."))
  (reject2 5 (disambig-payload v :ambiguous-old name scope (nn search-root) matches)))
  :else (let [mint (:mint v)
   retire (:retire v)
   emit (:emit v)
   site (first matches)
   new-root (mint src new-datum)]
  (retire (:cid site))
  (c/fact! ctx (nn (:parent site)) (c/value! ctx (:pos site)) (nn new-root) tx)
  (if (not (:capture-only? v)) (do
  (let [rr! (:reresolve v)]
  (rr!))))
  (emit "replace-in-body" (str "replaced 1 interior form inside `" name "` in \"" scope (if (some? within-datum) (do
  "\" (scoped by :within)")) "\" (1 fN edge superseded + re-pointed at a freshly-minted form; " "def NOT re-emitted — siblings + comments preserved; refs via refers_to)"))))))))

(defn dispatch-verb! [^Verb v spec]
  (let [op (:op spec)
   module (:module spec)]
  (cond
  (= op "rename") (verb-rename! v (:old spec) (:new spec) module)
  (= op "upsert-form") (verb-upsert-form! v module (:datum spec))
  (= op "insert-form") (verb-insert-form! v module (:after spec) (:datum spec))
  (= op "insert-comment") (verb-insert-comment! v module (:after spec) (:text spec) (:placement spec))
  (= op "set-body") (verb-set-body! v (:name spec) module (:datum spec))
  (= op "replace-in-body") (verb-replace-in-body! v (:name spec) module (:old spec) (:new spec) (:within spec))
  (= op "delete") (verb-delete! v (:name spec) module)
  (= op "reorder") (verb-reorder! v (:name spec) module (:after spec))
  :else (let [warn (:warn v)
   exit (:exit v)]
  (warn (str "run-verb-warm!: unknown op " (:op spec)))
  (exit 2)))))

(defn ^Verb make-verb! [env]
  (let [reject! (:reject! env)
   extract-file (:extract-file env)
   out-path (:out-path env)]
  (->Verb (:ctx env) (:view env) (:tx env) (:SUP env) (:KIND env) (:Vp env) (vec (:srcs env)) (:capture-only? env) (vec (:emit-srcs env)) (fn [code] (reject! code)) (fn [code detail] (reject! code detail)) (fn [line] (binding [*out* *err*]
  (println line))) (:author-emit env) (fn [src] (extract-file src (out-path src))) out-path (:def-binding env) (:typeframe env) (:modframe env) (:forms-of env) (:module-name env) (:parse-require env) (:capture-refs env) (:ultimate env) (:BOUND env) (:REFERS env) (:wrapper-of env) (:form-for-victim env) (:descendants env) (:retire env) (:reresolve env) (:ents env) (:mint env) (:register env) (:scope-srcs env) (:fn-facts env) (:FIXED env) (fn [code] (System/exit code)))))
