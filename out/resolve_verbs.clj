(ns resolve-verbs
  (:require [clojure.string :as str]
            [fram.types :as t]
            [fram.store :as c]))

(defrecord Verb [ctx view tx SUP KIND Vp srcs capture-only? emit-srcs reject reject2 warn emit extract out-path def-binding typeframe modframe forms-of module-name parse-require capture-refs])

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
