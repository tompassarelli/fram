(ns resolve-verbs
  (:require [clojure.string :as str]
            [fram.types :as t]
            [fram.store :as c]
            [resolve-core :as rc]
            [resolve-read :as rr]))

(defrecord Verb [ctx view tx SUP KIND Vp srcs capture-only? emit-srcs reject reject2 warn emit extract out-path def-binding typeframe modframe forms-of module-name parse-require capture-refs ultimate BOUND REFERS wrapper-of wrap-forms form-for-victim descendants retire reresolve ents])

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

(defn verb-wrap-forms [r] (:wrap-forms r))

(defn verb-form-for-victim [r] (:form-for-victim r))

(defn verb-descendants [r] (:descendants r))

(defn verb-retire [r] (:retire r))

(defn verb-reresolve [r] (:reresolve r))

(defn verb-ents [r] (:ents r))

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
   wf (:wrap-forms v)
   retire (:retire v)
   emit (:emit v)
   retired (atom 0)]
  (doseq [src srcs]
  (let [wrap (wof src)]
  (if (some? wrap) (do
  (doseq [entry (vec (wf wrap))]
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
   wf (:wrap-forms v)
   target-srcs (vec (filter (fn [s] (str/includes? s scope)) (:srcs v)))]
  (if (not= 1 (count target-srcs)) (do
  (warn (str "REJECTED — reorder scope \"" scope "\" matches " (count target-srcs) " files (need 1); no facts mutated."))
  (reject 3)))
  (let [src (first target-srcs)
   wrap (wof src)
   forms (vec (wf wrap))
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
