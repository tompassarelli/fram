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

^{:line 109 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defrecord Verb [ctx view tx SUP KIND Vp srcs capture-only? emit-srcs reject reject2 warn emit extract out-path def-binding typeframe modframe forms-of module-name parse-require capture-refs ultimate BOUND REFERS wrapper-of form-for-victim descendants retire reresolve ents mint register scope-srcs fn-facts FIXED exit])

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

^{:line 117 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- ^Boolean upper-first? [^String s]
  ^{:line 118 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 118 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (> ^{:line 118 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count s) 0) ^{:line 119 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [c ^{:line 119 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (subs s 0 1)]
  ^{:line 119 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 119 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (>= ^{:line 119 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (compare c "A") 0) ^{:line 119 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (<= ^{:line 119 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (compare c "Z") 0)))))

^{:line 121 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-rename! [^Verb v ^String old ^String new ^String target]
  ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   tx ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx v)
   srcs ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:srcs v)
   warn ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   dbind ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding v)
   target-srcs ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/includes? s target)) srcs))
   edits ^{:line 122 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (atom 0)]
  ^{:line 123 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src target-srcs]
  ^{:line 124 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 124 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 124 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 124 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src old)) ^{:line 124 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 124 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src new))) ^{:line 124 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 125 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 125 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `" new "` already names a binding in " src " (rename-doesn't-collide; no facts mutated)."))
  ^{:line 126 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3))))
  ^{:line 127 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src target-srcs]
  ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:typeframe v) src) old)) ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (upper-first? new))) ^{:line 128 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 129 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 129 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `" new "` is not a valid (Capitalized) type name " "(beagle type-name shape; no facts mutated)."))
  ^{:line 130 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3))))
  ^{:line 131 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src target-srcs]
  ^{:line 132 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [B ^{:line 132 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src old)]
  ^{:line 133 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 133 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? B) ^{:line 133 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [crefs ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-refs v)
   fo ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:forms-of v)
   caps ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mapcat ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mapcat ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [f] ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (crefs f ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (list ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:modframe v) s)) B new))) ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 134 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fo s))))) srcs))]
  ^{:line 135 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 135 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (> ^{:line 135 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count caps) 0) ^{:line 135 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 136 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 136 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — renaming `" old "` -> `" new "` would be CAPTURED by a local `" new "` in scope at " ^{:line 136 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count caps) " reference(s) (no-capture; no facts mutated)."))
  ^{:line 137 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 4))))))))
  ^{:line 138 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [mn ^{:line 138 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:module-name v)
   target-mods ^{:line 138 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (set ^{:line 138 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 138 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep ^{:line 138 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 138 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mn s)) target-srcs)))]
  ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (remove ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [t] ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= t s) ^{:line 139 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  t))) target-srcs))) srcs))]
  ^{:line 140 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [preq ^{:line 140 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:parse-require v)
   pr ^{:line 140 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (preq src)
   refer ^{:line 140 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:refer pr)
   rename ^{:line 140 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:rename pr)
   home ^{:line 140 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get refer old)]
  ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? home) ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? target-mods home) ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (or ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src new)) ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get refer new)) ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get rename new)))) ^{:line 141 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 142 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 142 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — renaming `" old "` -> `" new "` would DUPLICATE a binding in consumer " src " (it already binds `" new "`; no-import-collision; no facts mutated)."))
  ^{:line 143 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3))))))
  ^{:line 144 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src target-srcs]
  ^{:line 145 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [B ^{:line 145 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src old)]
  ^{:line 146 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 146 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? B) ^{:line 146 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [oldc ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [cid] ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:Vp v) ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:p ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact-of ctx cid)))) ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/by-l ctx B))))
   nc ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx B ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:Vp v) ^{:line 147 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx new) tx)]
  ^{:line 148 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 148 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? oldc) ^{:line 148 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 148 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx nc ^{:line 148 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:SUP v) oldc tx)))
  ^{:line 149 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (swap! edits ^{:line 149 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [n] ^{:line 149 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ n 1))))))))
  ^{:line 150 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 150 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= 0 ^{:line 150 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (deref edits)) ^{:line 150 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 151 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 151 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — no binding named `" old "` found in \"" target "\" (nothing to rename; no facts mutated)."))
  ^{:line 152 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 5)))
  ^{:line 153 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 153 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 153 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 153 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 154 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ex ^{:line 154 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:extract v)
   op ^{:line 154 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:out-path v)]
  ^{:line 155 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src ^{:line 155 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit-srcs v)]
  ^{:line 155 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ex src))
  ^{:line 156 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn "================ Turtle #5 — O(1) shadow-correct rename ================")
  ^{:line 157 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 157 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "edit: rename def `" old "` -> `" new "` in \"" target "\""))
  ^{:line 158 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 158 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "FACTS EDITED: " ^{:line 158 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (deref edits) "  (just the definition's name; references follow refers_to)"))
  ^{:line 159 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src ^{:line 159 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit-srcs v)]
  ^{:line 160 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 160 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "projected -> " ^{:line 160 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (op src) "   <- " src))))))))

^{:line 175 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- nn [e]
  ^{:line 175 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 175 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? e) -1 e))

^{:line 181 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- ekey [e]
  ^{:line 181 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth ^{:line 181 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec e) 0))

^{:line 183 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- ecid [e]
  ^{:line 183 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth ^{:line 183 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec e) 1))

^{:line 185 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- enode [e]
  ^{:line 185 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth ^{:line 185 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec e) 2))

^{:line 187 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- ^String ord-tie [^Verb v]
  ^{:line 187 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 187 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v) "PENDING" "0"))

^{:line 189 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- ^String ord-str* [path ^String tie]
  ^{:line 190 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "f" ^{:line 190 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/join "." path) "~" tie))

^{:line 213 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn datum->canon [d]
  ^{:line 213 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/datum->canon d))

^{:line 225 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- ^Boolean named-def-head? [h]
  ^{:line 226 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 226 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/writable-def-head? ^{:line 226 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str h)) ^{:line 227 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 227 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? ^{:line 227 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (into ^{:line 227 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} #{"defmethod"} rc/EXTEND-FORMS) ^{:line 227 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str h)))))

^{:line 229 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- node-def-name-leaf [^Verb v f]
  ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   view ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v)
   d ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rm/unwrap-def ctx view f)
   children ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/ordered-children ctx d)
   head ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/head-sym ctx view d)
   name-index ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/type-name-index head ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/sym-val ctx view ^{:line 230 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth children 1 nil)))]
  ^{:line 231 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rm/logical-name-leaf ctx view ^{:line 231 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth children name-index nil))))

^{:line 235 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- node-def-name [^Verb v f]
  ^{:line 236 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/sym-val ^{:line 236 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v) ^{:line 236 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v) ^{:line 236 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (node-def-name-leaf v f)))

^{:line 249 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn writable-victim [^Verb v ^String src datum]
  ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   view ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v)
   BOUND ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:BOUND v)
   REFERS ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:REFERS v)
   FIXED ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:FIXED v)
   wrapper ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:wrapper-of v)
   key ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/writable-form-key datum)
   forms ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rest ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/ordered-children ctx ^{:line 250 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wrapper src)))]
  ^{:line 251 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 252 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= :defmethod ^{:line 252 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first key)) ^{:line 253 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [m ^{:line 253 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 253 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second key))
   dv ^{:line 253 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth key 2)]
  ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [f] ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [d ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rm/unwrap-def ctx view f)
   k ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/ordered-children ctx d)]
  ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= "defmethod" ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/head-sym ctx view d)) ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= m ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/sym-val ctx view ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second k))) ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= dv ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/node->canon ctx view BOUND REFERS FIXED ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec k) 2 nil)))) ^{:line 254 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  f)))) forms))
  ^{:line 256 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= :extension ^{:line 256 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first key)) ^{:line 257 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [head ^{:line 257 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 257 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second key))
   tgt ^{:line 257 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth key 2)]
  ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [f] ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [d ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rm/unwrap-def ctx view f)]
  ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= head ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/head-sym ctx view d)) ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= tgt ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/node->canon ctx view BOUND REFERS FIXED ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/ordered-children ctx d))))) ^{:line 258 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  f)))) forms))
  ^{:line 260 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= :named ^{:line 260 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first key)) ^{:line 261 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [nm ^{:line 261 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 261 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second key))]
  ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [f] ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (named-def-head? ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/head-sym ctx view ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rm/unwrap-def ctx view f))) ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= nm ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (node-def-name v f))) ^{:line 262 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  f))) forms))
  :else nil)))

^{:line 270 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn ^String writable-disp-name [datum]
  ^{:line 271 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (or ^{:line 271 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/writable-form-display-name datum) ""))

^{:line 273 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- reuse-retained-binding-datum [^Verb v old-form datum]
  ^{:line 274 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/reuse-retained-bindings datum ^{:line 275 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rm/form-binding-leaves ^{:line 275 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v) ^{:line 275 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v) old-form)))

^{:line 284 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn wrap-forms [^Verb v parent]
  ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   rows ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reduce ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [acc cid] ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [cl ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact-of ctx cid)
   pi ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? cl) nil ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:p cl))
   k ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (int? pi) ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/ord-parse ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/literal ctx pi)) nil)]
  ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? k) acc ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (conj acc ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} [k cid ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:r cl)])))) ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} [] ^{:line 285 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/by-l ctx parent))]
  ^{:line 286 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 286 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (sort-by ^{:line 286 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [row] ^{:line 286 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth row 0)) rc/ord-cmp rows))))

^{:line 298 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- reject-candidate [^Verb v root site others idx]
  ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   view ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v)
   BOUND ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:BOUND v)
   REFERS ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:REFERS v)
   FIXED ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:FIXED v)
   chain ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:chain site)
   breadcrumb ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mapv ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [n] ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/crumb-label ctx view n)) chain)
   parent ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:parent site)
   other-nodes ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (into ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} #{} ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mapcat ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [o] ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:chain o)) others))
   within ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [a] ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? other-nodes a)) ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ac ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/node->canon ctx view BOUND REFERS FIXED a)
   s ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/node->str ctx view BOUND REFERS FIXED a)]
  ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (<= ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count s) 800) ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ac ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (try
  ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (datum->canon ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (edn/read-string s))
  (catch Throwable _
    nil))) ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= 1 ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/anchor-match-sites ctx view BOUND REFERS FIXED root ac)))) ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  s)))))) ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reverse chain))
   ctx-str ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [s ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/node->str ctx view BOUND REFERS FIXED parent)]
  ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (<= ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count s) 200) s ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 299 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (subs s 0 197) "...")))]
  ^{:line 300 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} {:n idx :breadcrumb breadcrumb :within within :context ctx-str}))

^{:line 305 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn disambig-payload [^Verb v reason ^String name ^String scope root sites]
  ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [total ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count sites)
   shown ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (map-indexed ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [i s] ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject-candidate v root s ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (concat ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (take i sites) ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ i 1) sites)) ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ i 1))) ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (take rc/DISAMBIG-CAP sites)))
   head ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= reason :ambiguous-old) ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "anchor `old` is AMBIGUOUS inside `" name "` in \"" scope "\" (" total " matches; no facts mutated).")
  :else ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "`within` is AMBIGUOUS inside `" name "` in \"" scope "\" (" total " matches; no facts mutated). It must match exactly one enclosing form."))
   lines ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (map ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [c] ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "  [" ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:n c) "] " ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/join " > " ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:breadcrumb c)) ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:within c) ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "\n      within: " ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:within c)))))) shown)
   remedy ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 306 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= reason :ambiguous-old) "Retry with :within set to one candidate's `within` form (it isolates that occurrence), or supply a larger :old."
  :else "Supply a `within` that names exactly one enclosing form (use a larger/more distinctive form).")]
  ^{:line 307 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} {:reason reason :verb "replace-in-body" :name name :scope scope :total total :shown ^{:line 307 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count shown) :candidates shown :remedy remedy :message ^{:line 307 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — " head "\n" ^{:line 307 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/join "\n" lines) "\n  remedy: " remedy)}))

^{:line 335 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-delete! [^Verb v ^String name ^String scope]
  ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   view ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v)
   srcs ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:srcs v)
   warn ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   dbind ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding v)
   ffv ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:form-for-victim v)
   desc ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:descendants v)
   ult ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ultimate v)
   target-srcs ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/includes? s scope)) srcs))
   victims ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind s name)) target-srcs))
   all-forms ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (set ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mapcat ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [b] ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ffv s b)) victims))) srcs)))
   subtree ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reduce ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [acc f] ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (into acc ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (desc f))) ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} #{} ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec all-forms))
   orphans ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mapcat ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [tgt ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/refers-target ctx view ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:BOUND v) ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:REFERS v) e)]
  ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= "symbol" ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/kind-of ctx view e)) ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? tgt) ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? subtree e)) ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? subtree ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ult tgt))))) ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ents v) s ^{:line 336 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} []))))) srcs))]
  ^{:line 337 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 337 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= 0 ^{:line 337 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count victims)) ^{:line 337 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 338 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 338 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — no binding named `" name "` found in \"" scope "\" (nothing to delete; no facts mutated)."))
  ^{:line 339 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 5)))
  ^{:line 340 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 340 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= 0 ^{:line 340 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count all-forms)) ^{:line 340 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 341 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 341 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `" name "` is not an independently-deletable top-level form " "(a defunion variant / nested binding); no facts mutated."))
  ^{:line 342 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 5)))
  ^{:line 343 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 343 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (> ^{:line 343 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count orphans) 0) ^{:line 343 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 344 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn "================ delete + orphaned-reference invariant ================")
  ^{:line 345 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 345 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — " ^{:line 345 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count orphans) " reference(s) would be ORPHANED (no-orphaned-refs; no facts mutated):"))
  ^{:line 346 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [o ^{:line 346 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 346 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (take 5 orphans))]
  ^{:line 347 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 347 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "  orphan: reference node " o " (`" ^{:line 347 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/sym-val ctx view o) "`)")))
  ^{:line 348 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 6)))
  ^{:line 349 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [wof ^{:line 349 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:wrapper-of v)
   retire ^{:line 349 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:retire v)
   emit ^{:line 349 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit v)
   retired ^{:line 349 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (atom 0)]
  ^{:line 350 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src srcs]
  ^{:line 351 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [wrap ^{:line 351 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wof src)]
  ^{:line 352 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 352 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? wrap) ^{:line 352 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 353 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [entry ^{:line 353 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 353 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wrap-forms v ^{:line 353 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap)))]
  ^{:line 354 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 354 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? all-forms ^{:line 354 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode entry)) ^{:line 354 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 355 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (retire ^{:line 355 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ecid entry))
  ^{:line 356 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (swap! retired ^{:line 356 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [n] ^{:line 356 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ n 1))))))))))
  ^{:line 357 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 357 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 357 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 357 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 357 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [rr! ^{:line 357 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve v)]
  ^{:line 357 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr!))))
  ^{:line 358 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (emit "delete" ^{:line 359 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "deleted def `" name "` in \"" scope "\" (" ^{:line 364 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (deref retired) " wrapper form-edge(s) superseded; subtree orphaned + dropped on render; 0 orphaned refs)")))))

^{:line 377 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-reorder! [^Verb v ^String name ^String scope after-name]
  ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   tx ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx v)
   warn ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   dbind ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding v)
   ffv ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:form-for-victim v)
   wof ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:wrapper-of v)
   target-srcs ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/includes? s scope)) ^{:line 378 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:srcs v)))]
  ^{:line 379 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 379 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not= 1 ^{:line 379 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs)) ^{:line 379 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 380 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 380 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — reorder scope \"" scope "\" matches " ^{:line 380 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs) " files (need 1); no facts mutated."))
  ^{:line 381 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [src ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first target-srcs)
   wrap ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wof src)
   forms ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wrap-forms v ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap)))
   mover-bind ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src name)
   mover-form ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? mover-bind) ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ffv src mover-bind)))
   mover-entry ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? mover-form) ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e) mover-form) ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  e))) forms)))
   front? ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/blank? ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str after-name))
   anchor-bind ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not front?) ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src after-name)))
   anchor-form ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? anchor-bind) ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ffv src anchor-bind)))
   anchor-idx ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? anchor-form) ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep-indexed ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [i e] ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e) anchor-form) ^{:line 382 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  i))) forms)))))]
  ^{:line 383 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 383 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? mover-entry) ^{:line 383 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 384 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 384 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — reorder target `" name "` not found in \"" scope "\"; no facts mutated."))
  ^{:line 385 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 5)))
  ^{:line 386 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 386 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 386 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not front?) ^{:line 386 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? anchor-idx)) ^{:line 386 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 387 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 387 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — reorder anchor `" after-name "` not found in \"" scope "\"; no facts mutated."))
  ^{:line 388 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 389 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 389 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not front?) ^{:line 389 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 390 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ai ^{:line 390 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn anchor-idx)]
  ^{:line 391 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 391 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (>= ai 0) ^{:line 391 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 392 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 392 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 392 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode ^{:line 392 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth forms ai)) mover-form) ^{:line 392 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 393 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 393 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — reorder `" name "` :after itself is a no-op; no facts mutated."))
  ^{:line 394 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3))))))))
  ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [others ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (remove ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e) mover-form)) forms))
   a-pos ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if front? -1 ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep-indexed ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [i e] ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e) anchor-form) ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  i))) others)))))
   lo ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (< a-pos 0) nil ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:path ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth others a-pos))))
   hi ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (< a-pos 0) ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:path ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first others))) ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (< ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ a-pos 1) ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count others)) ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:path ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth others ^{:line 395 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ a-pos 1)))))))]
  ^{:line 396 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [retire ^{:line 396 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:retire v)
   emit ^{:line 396 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit v)]
  ^{:line 397 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (retire ^{:line 397 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ecid mover-entry))
  ^{:line 398 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx ^{:line 399 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap) ^{:line 400 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx ^{:line 400 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-str* ^{:line 400 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/ord-between lo hi) ^{:line 400 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-tie v))) ^{:line 401 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn mover-form) tx)
  ^{:line 403 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 403 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 403 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 403 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 403 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [rr! ^{:line 403 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve v)]
  ^{:line 403 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr!))))
  ^{:line 404 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (emit "reorder" ^{:line 405 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "moved def `" name "` " ^{:line 408 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if front? "to the front" ^{:line 408 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "after `" after-name "`")) " in \"" scope "\" (wrapper order-key re-spelled; SAME subtree, 0 node churn)")))))))

^{:line 419 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-upsert-form! [^Verb v ^String scope datum]
  ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   tx ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx v)
   warn ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   ssrcs ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:scope-srcs v)
   target-srcs ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 420 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ssrcs scope))]
  ^{:line 421 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 421 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not= 1 ^{:line 421 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs)) ^{:line 421 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 422 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 422 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — scope \"" scope "\" matches " ^{:line 422 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs) " source files; upsert-form needs exactly one (no facts mutated)."))
  ^{:line 423 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (seq? datum) ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/writable-def-head? ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first datum))))) ^{:line 424 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 425 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — upsert-form spec head `" ^{:line 425 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first datum) "` is not a writable top-level def (def/defn/deftype/defmulti/defmethod/extend-*); no facts mutated."))
  ^{:line 426 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [wof ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:wrapper-of v)
   mint ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:mint v)
   src ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first target-srcs)
   wrap ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wof src)
   forms ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wrap-forms v ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap)))
   disp-name ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (seq? datum) ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (writable-disp-name datum)))
   victim-form ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (seq? datum) ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (writable-victim v src datum)))
   victim-entry ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? victim-form) ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e) victim-form) ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  e))) forms)))
   new-root ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mint src ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? victim-form) ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [retained ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reuse-retained-binding-datum v ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn victim-form) datum)]
  ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= "js/export" ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/head-sym ctx ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v) victim-form)) ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (list ^{:line 427 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (symbol "js/export") retained) retained)) datum))]
  ^{:line 428 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 428 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? victim-entry) ^{:line 429 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [retire ^{:line 429 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:retire v)]
  ^{:line 430 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (retire ^{:line 430 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ecid victim-entry))
  ^{:line 431 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx ^{:line 432 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap) ^{:line 433 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx ^{:line 433 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-str* ^{:line 433 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:path ^{:line 433 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey victim-entry)) ^{:line 433 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-tie v))) ^{:line 434 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn new-root) tx)) ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [last-path ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (> ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count forms) 0) ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:path ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey ^{:line 436 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (last forms)))))]
  ^{:line 437 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx ^{:line 438 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap) ^{:line 439 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx ^{:line 439 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-str* ^{:line 439 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/ord-append last-path) ^{:line 439 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-tie v))) ^{:line 440 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn new-root) tx)))
  ^{:line 442 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 442 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 442 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 442 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 442 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [rr! ^{:line 442 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve v)]
  ^{:line 442 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr!))))
  ^{:line 443 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [emit ^{:line 443 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit v)]
  ^{:line 444 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (emit "upsert-form" ^{:line 445 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 445 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 445 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? victim-entry) "replaced" "added") " top-level def `" disp-name "` in \"" scope "\" (1 form minted as facts; refs resolved via refers_to)"))))))

^{:line 458 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-insert-form! [^Verb v ^String scope ^String after-name datum]
  ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   tx ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx v)
   warn ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   target-srcs ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/includes? s scope)) ^{:line 459 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:srcs v)))]
  ^{:line 460 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 460 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not= 1 ^{:line 460 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs)) ^{:line 460 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 461 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 461 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — insert-form scope \"" scope "\" matches " ^{:line 461 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs) " files (need 1)."))
  ^{:line 462 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (seq? datum) ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? rc/TOPLEVEL-VALUE-DEFS ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first datum))))) ^{:line 463 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 464 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 464 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — insert-form head `" ^{:line 464 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first datum) "` not a value def."))
  ^{:line 465 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [wof ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:wrapper-of v)
   dbind ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding v)
   ffv ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:form-for-victim v)
   src ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first target-srcs)
   wrap ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wof src)
   forms ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (wrap-forms v ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap)))
   anchor-bind ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src after-name)
   anchor-form ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? anchor-bind) ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ffv src anchor-bind)))
   i ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? anchor-form) ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep-indexed ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [n e] ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e) anchor-form) ^{:line 466 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  n))) forms))))))]
  ^{:line 467 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 467 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (< i 0) ^{:line 467 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 468 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 468 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — insert-form anchor `" after-name "` not found in \"" scope "\"."))
  ^{:line 469 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [mint ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:mint v)
   anchor-path ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:path ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth forms i)))
   next-path ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (< ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ i 1) ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count forms)) ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:path ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nth forms ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ i 1))))))
   new-root ^{:line 470 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mint src datum)]
  ^{:line 471 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx ^{:line 472 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn wrap) ^{:line 473 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx ^{:line 474 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-str* ^{:line 474 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rc/ord-between anchor-path next-path) ^{:line 474 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ord-tie v))) ^{:line 475 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn new-root) tx)
  ^{:line 477 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 477 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 477 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 477 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 477 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [rr! ^{:line 477 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve v)]
  ^{:line 477 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr!))))
  ^{:line 478 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [emit ^{:line 478 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit v)]
  ^{:line 479 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (emit "insert-form" ^{:line 480 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "inserted def after `" after-name "` in \"" scope "\" (CRDT mid-insert)")))))))

^{:line 494 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (def COMMENT-RE ^{:line 494 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (re-pattern "comment\\d+"))

^{:line 497 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn- next-comment-idx [^Verb v form]
  ^{:line 498 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 498 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)]
  ^{:line 499 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ 1 ^{:line 500 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reduce ^{:line 500 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [acc n] ^{:line 500 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 500 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (> n acc) n acc)) -1 ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [cid] ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [cl ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact-of ctx cid)
   p ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/literal ctx ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:p cl))]
  ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (string? p) ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (re-matches COMMENT-RE ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str p)))) ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (parse-long ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (subs ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str p) 7)))))) ^{:line 502 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/by-l ctx form)))))))

^{:line 504 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-insert-comment! [^Verb v ^String scope ^String anchor-name ^String text placement]
  ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   tx ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx v)
   warn ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   target-srcs ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [s] ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/includes? s scope)) ^{:line 505 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:srcs v)))]
  ^{:line 506 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 506 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not= 1 ^{:line 506 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs)) ^{:line 506 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 507 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 507 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — insert-comment scope \"" scope "\" matches " ^{:line 507 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs) " files (need 1)."))
  ^{:line 508 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 509 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 509 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/blank? text) ^{:line 509 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 510 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn "REJECTED — insert-comment needs non-empty --text; no facts mutated.")
  ^{:line 511 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [dbind ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding v)
   ffv ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:form-for-victim v)
   src ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first target-srcs)
   plc ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} #{"leading" "trailing"} placement) ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str placement) "leading")
   lex ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/starts-with? ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/triml text) ";") text ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ";; " text))
   anchor-bind ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src anchor-name)
   anchor-form ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? anchor-bind) ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 512 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ffv src anchor-bind)))]
  ^{:line 513 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 513 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? anchor-form) ^{:line 513 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 514 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 514 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — insert-comment anchor `" anchor-name "` not found in \"" scope "\"."))
  ^{:line 515 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [reg ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:register v)
   emit ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit v)
   form ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn anchor-form)
   k ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (next-comment-idx v form)
   cnode ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reg src ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/entity! ctx)))
   seg ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reg src ^{:line 516 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/entity! ctx)))]
  ^{:line 517 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx cnode ^{:line 517 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:KIND v) ^{:line 517 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx "comment") tx)
  ^{:line 518 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx cnode ^{:line 518 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx "style") ^{:line 518 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx "line") tx)
  ^{:line 519 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx cnode ^{:line 519 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx "placement") ^{:line 519 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx plc) tx)
  ^{:line 520 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx seg ^{:line 520 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:KIND v) ^{:line 520 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx "text") tx)
  ^{:line 521 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx seg ^{:line 521 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:Vp v) ^{:line 521 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx lex) tx)
  ^{:line 522 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx cnode ^{:line 522 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx "seg0") seg tx)
  ^{:line 523 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx form ^{:line 523 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx ^{:line 523 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "comment" k)) cnode tx)
  ^{:line 524 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 524 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 524 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 524 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 524 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [rr! ^{:line 524 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve v)]
  ^{:line 524 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr!))))
  ^{:line 525 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (emit "insert-comment" ^{:line 526 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "added " plc " comment on `" anchor-name "` in \"" scope "\" (comment" k "; 1 text seg minted)"))))))

^{:line 547 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-set-body! [^Verb v ^String name ^String scope datum]
  ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   view ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v)
   tx ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx v)
   warn ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   ssrcs ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:scope-srcs v)
   target-srcs ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 548 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ssrcs scope))]
  ^{:line 549 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 549 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not= 1 ^{:line 549 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs)) ^{:line 549 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 550 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 550 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — scope \"" scope "\" matches " ^{:line 550 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs) " source files; set-body needs exactly one (no facts mutated)."))
  ^{:line 551 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [dbind ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding v)
   ffv ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:form-for-victim v)
   src ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first target-srcs)
   B ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src name)
   form ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? B) ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ffv src B)))
   d ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? form) ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rm/unwrap-def ctx view ^{:line 552 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn form))))]
  ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (or ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? form) ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? rc/VALUE-DEFS ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/head-sym ctx view d))))) ^{:line 553 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 554 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 554 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `" name "` is not a def/defn with a body in \"" scope "\" (set-body needs a value binding; no facts mutated)."))
  ^{:line 555 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 5)))
  ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [fnf ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:fn-facts v)
   mint ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:mint v)
   kids ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fnf d))
   param? ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? rc/PARAM-FORMS ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/head-sym ctx view d)))
   anchor-n ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if param? ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rb/brackets? ctx view ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e))) ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey e)))) kids) ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= name ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/sym-val ctx view ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/unwrap-meta ctx view ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e))))) ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey e)))) kids)))
   ret? ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey e) ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ anchor-n 1)) ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (contains? rc/TYPE-COLON ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr/sym-val ctx view ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (enode e)))))) ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  true))) kids)
   body-start ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ anchor-n ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ret?) 3 1))
   body-slots ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [e] ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (>= ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ekey e)) body-start)) kids))
   new-root ^{:line 556 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mint src datum)]
  ^{:line 557 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 557 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= 0 ^{:line 557 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count body-slots)) ^{:line 557 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 558 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 558 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `" name "` has no body fN edges to replace; no facts mutated."))
  ^{:line 559 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 5)))
  ^{:line 560 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [retire ^{:line 560 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:retire v)
   emit ^{:line 560 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit v)]
  ^{:line 561 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [e body-slots]
  ^{:line 561 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (retire ^{:line 561 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ecid e)))
  ^{:line 562 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx ^{:line 563 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn d) ^{:line 564 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx ^{:line 564 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "f" body-start)) ^{:line 565 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn new-root) tx)
  ^{:line 567 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 567 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 567 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 567 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 567 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [rr! ^{:line 567 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve v)]
  ^{:line 567 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr!))))
  ^{:line 568 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (emit "set-body" ^{:line 569 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "replaced body of `" name "` in \"" scope "\" (" ^{:line 574 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count body-slots) " body slot(s) superseded; new body minted as facts)")))))))

^{:line 596 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn verb-replace-in-body! [^Verb v ^String name ^String scope old-datum new-datum within-datum]
  ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [ctx ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx v)
   view ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view v)
   tx ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx v)
   warn ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   reject ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject v)
   reject2 ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject2 v)
   target-srcs ^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (^{:line 597 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:scope-srcs v) scope)]
  ^{:line 598 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 598 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not= 1 ^{:line 598 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs)) ^{:line 598 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 599 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 599 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — scope \"" scope "\" matches " ^{:line 599 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count target-srcs) " source files; replace-in-body needs exactly one (no facts mutated)."))
  ^{:line 600 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject 3)))
  ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [dbind ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding v)
   ffv ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:form-for-victim v)
   src ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first target-srcs)
   B ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dbind src name)
   form ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? B) ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 601 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (ffv src B)))]
  ^{:line 602 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 602 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? form) ^{:line 602 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 603 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 603 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — no def named `" name "` found in \"" scope "\" (nothing to edit; no facts mutated)."))
  ^{:line 604 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject2 5 ^{:line 605 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} {:reason :no-def :verb "replace-in-body" :name name :scope scope :message ^{:line 605 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — no def named `" name "` found in \"" scope "\".")})))
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [search-root ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? within-datum) form ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [wcanon ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (datum->canon within-datum)
   wsites ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/anchor-match-sites ctx view ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:BOUND v) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:REFERS v) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:FIXED v) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn form) wcanon)]
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= 0 ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count wsites)) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `within` form not found inside `" name "` in \"" scope "\" (0 matches; no facts mutated)."))
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject2 5 ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} {:reason :no-within :verb "replace-in-body" :name name :scope scope :message ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `within` form not found inside `" name "` in \"" scope "\" (0 matches).")}))
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (> ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count wsites) 1) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — `within` is AMBIGUOUS inside `" name "` in \"" scope "\" (" ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count wsites) " matches; no facts mutated)."))
  ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject2 5 ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (disambig-payload v :ambiguous-within name scope ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn form) wsites)))
  :else ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:child ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first wsites)))))
   target-canon ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (datum->canon old-datum)
   matches ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rv/anchor-match-sites ctx view ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:BOUND v) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:REFERS v) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:FIXED v) ^{:line 615 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn search-root) target-canon)]
  ^{:line 616 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 617 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= 0 ^{:line 617 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count matches)) ^{:line 618 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 619 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 619 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — anchor `old` not found inside " ^{:line 619 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 619 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? within-datum) "the `within` form" ^{:line 619 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "`" name "`")) " in \"" scope "\" (0 matches; no facts mutated). The old form must match " "an interior form structurally (head + spelling + child shape)."))
  ^{:line 620 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject2 5 ^{:line 621 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} {:reason :no-old :verb "replace-in-body" :name name :scope scope :within ^{:line 621 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? within-datum) :message ^{:line 621 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — anchor `old` not found inside " ^{:line 621 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 621 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? within-datum) "the `within` form" ^{:line 621 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "`" name "`")) " in \"" scope "\" (0 matches).")}))
  ^{:line 639 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (> ^{:line 639 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count matches) 1) ^{:line 640 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 641 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 641 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "REJECTED — anchor `old` is AMBIGUOUS inside " ^{:line 641 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 641 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? within-datum) "the `within` form" ^{:line 641 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "`" name "`")) " in \"" scope "\" (" ^{:line 641 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count matches) " matches; no facts mutated)."))
  ^{:line 642 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject2 5 ^{:line 643 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (disambig-payload v :ambiguous-old name scope ^{:line 647 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn search-root) matches)))
  :else ^{:line 650 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [mint ^{:line 650 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:mint v)
   retire ^{:line 650 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:retire v)
   emit ^{:line 650 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit v)
   site ^{:line 650 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first matches)
   new-root ^{:line 650 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mint src new-datum)]
  ^{:line 651 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (retire ^{:line 651 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:cid site))
  ^{:line 652 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/fact! ctx ^{:line 653 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn ^{:line 653 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:parent site)) ^{:line 654 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (c/value! ctx ^{:line 654 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:pos site)) ^{:line 655 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nn new-root) tx)
  ^{:line 657 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 657 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not ^{:line 657 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? v)) ^{:line 657 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 657 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [rr! ^{:line 657 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve v)]
  ^{:line 657 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rr!))))
  ^{:line 658 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (emit "replace-in-body" ^{:line 659 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "replaced 1 interior form inside `" name "` in \"" scope ^{:line 663 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 663 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? within-datum) ^{:line 663 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  "\" (scoped by :within)")) "\" (1 fN edge superseded + re-pointed at a freshly-minted form; " "def NOT re-emitted — siblings + comments preserved; refs via refers_to)"))))))))

^{:line 679 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn dispatch-verb! [^Verb v spec]
  ^{:line 680 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [op ^{:line 680 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:op spec)
   module ^{:line 680 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:module spec)]
  ^{:line 681 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 682 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "rename") ^{:line 683 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-rename! v ^{:line 683 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:old spec) ^{:line 683 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:new spec) module)
  ^{:line 684 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "upsert-form") ^{:line 685 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-upsert-form! v module ^{:line 685 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:datum spec))
  ^{:line 686 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "insert-form") ^{:line 687 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-insert-form! v module ^{:line 687 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:after spec) ^{:line 687 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:datum spec))
  ^{:line 688 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "insert-comment") ^{:line 689 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-insert-comment! v module ^{:line 691 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:after spec) ^{:line 692 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:text spec) ^{:line 693 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:placement spec))
  ^{:line 694 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "set-body") ^{:line 695 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-set-body! v ^{:line 695 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:name spec) module ^{:line 695 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:datum spec))
  ^{:line 696 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "replace-in-body") ^{:line 697 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-replace-in-body! v ^{:line 698 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:name spec) module ^{:line 700 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:old spec) ^{:line 701 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:new spec) ^{:line 702 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:within spec))
  ^{:line 703 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "delete") ^{:line 704 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-delete! v ^{:line 704 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:name spec) module)
  ^{:line 705 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= op "reorder") ^{:line 706 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (verb-reorder! v ^{:line 706 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:name spec) module ^{:line 706 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:after spec))
  :else ^{:line 708 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [warn ^{:line 708 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:warn v)
   exit ^{:line 708 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:exit v)]
  ^{:line 709 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 709 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "run-verb-warm!: unknown op " ^{:line 709 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:op spec)))
  ^{:line 710 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (exit 2)))))

^{:line 712 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn ^Verb make-verb! [env]
  ^{:line 713 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [reject! ^{:line 713 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reject! env)
   extract-file ^{:line 713 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:extract-file env)
   out-path ^{:line 713 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:out-path env)]
  ^{:line 714 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (->Verb ^{:line 714 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ctx env) ^{:line 715 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:view env) ^{:line 716 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:tx env) ^{:line 717 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:SUP env) ^{:line 718 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:KIND env) ^{:line 719 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:Vp env) ^{:line 720 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 720 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:srcs env)) ^{:line 721 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-only? env) ^{:line 722 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 722 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:emit-srcs env)) ^{:line 723 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [code] ^{:line 723 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject! code)) ^{:line 724 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [code detail] ^{:line 724 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reject! code detail)) ^{:line 725 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [line] ^{:line 725 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (binding [*out* *err*]
  ^{:line 725 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (println line))) ^{:line 726 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:author-emit env) ^{:line 727 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [src] ^{:line 727 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (extract-file src ^{:line 727 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (out-path src))) out-path ^{:line 729 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:def-binding env) ^{:line 730 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:typeframe env) ^{:line 731 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:modframe env) ^{:line 732 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:forms-of env) ^{:line 733 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:module-name env) ^{:line 734 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:parse-require env) ^{:line 735 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:capture-refs env) ^{:line 736 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ultimate env) ^{:line 737 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:BOUND env) ^{:line 738 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:REFERS env) ^{:line 739 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:wrapper-of env) ^{:line 740 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:form-for-victim env) ^{:line 741 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:descendants env) ^{:line 742 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:retire env) ^{:line 743 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reresolve env) ^{:line 744 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:ents env) ^{:line 745 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:mint env) ^{:line 746 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:register env) ^{:line 747 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:scope-srcs env) ^{:line 748 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:fn-facts env) ^{:line 749 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:FIXED env) ^{:line 750 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [code] ^{:line 750 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (System/exit code)))))

^{:line 752 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (defn run-cli! [env args]
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [mode ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first args)
   fi ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (keep-indexed ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [i arg] ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= arg "--within-file") i nil)) args)))
   stripped ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (nil? fi) args ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (concat ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (take fi args) ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (+ fi 2) args))))
   skip ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "resolve") 1
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "rename") 4
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "delete") 3
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "reorder") 4
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "callgraph") 1
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "upsert-form") 3
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "set-body") 4
  ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "replace-in-body") 5
  :else 1)
   edn-paths ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop skip stripped))
   resolve-edn ^{:line 753 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:resolve-edn env)]
  ^{:line 754 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (resolve-edn edn-paths ^{:line 755 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [] ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [warn ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [line] ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (binding [*out* *err*]
  ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (println line)))
   srcs-fn ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:srcs env)
   counter ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:counter env)
   extract ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:extract env)
   out-path ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:out-path env)
   basename ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [src] ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (last ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str/split src ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (re-pattern "/"))))
   file-ents ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:file-ents env)
   kind-of ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:kind-of env)
   refers-target ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:refers-target env)
   rename! ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:rename! env)
   delete! ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:delete! env)
   reorder! ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reorder! env)
   upsert! ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:upsert! env)
   set-body! ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:set-body! env)
   replace-in-body! ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:replace-in-body! env)
   read-edn ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [path] ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (edn/read-string ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (slurp path)))
   call-edges ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:call-edges env)
   blast-closure ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:blast-closure env)
   binding-privacy ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:binding-privacy env)
   dead-private-bindings ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:dead-private-bindings env)
   json-generate ^{:line 756 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (requiring-resolve 'cheshire.core/generate-string)]
  ^{:line 757 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (cond
  ^{:line 758 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "resolve") ^{:line 759 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 760 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn "================ Turtle #5 — lexical resolution pass ================")
  ^{:line 761 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 761 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "references resolved (carry refers_to → a binding node): " ^{:line 761 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (counter :resolved) "  (" ^{:line 761 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (counter :xmod) " cross-module, " ^{:line 761 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (counter :type) " type references)"))
  ^{:line 762 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 762 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "unresolved (builtins / native — correctly NO refers_to): " ^{:line 762 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (counter :unresolved)))
  ^{:line 763 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 763 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "comment identifier mentions resolved (rename-correct doc comments): " ^{:line 763 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (counter :comment)))
  ^{:line 764 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src ^{:line 764 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 764 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (srcs-fn))]
  ^{:line 764 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (extract src))
  ^{:line 765 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (doseq [src ^{:line 765 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 765 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (srcs-fn))]
  ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (str "  " ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (basename src) ": " ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (filter ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [node] ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (and ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= "symbol" ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (kind-of node)) ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (refers-target node)))) ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (file-ents src)))) " references carry refers_to; projected (identity) -> " ^{:line 766 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (out-path src)))))
  ^{:line 767 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "rename") ^{:line 768 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [[old new target] ^{:line 768 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 768 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop 1 args))]
  ^{:line 769 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (rename! old new target))
  ^{:line 770 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "delete") ^{:line 771 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [[name target] ^{:line 771 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 771 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop 1 args))]
  ^{:line 771 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (delete! name target))
  ^{:line 772 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "reorder") ^{:line 773 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [[name target after] ^{:line 773 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 773 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop 1 args))]
  ^{:line 774 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (reorder! name target after))
  ^{:line 775 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "upsert-form") ^{:line 776 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [[scope spec-file] ^{:line 776 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 776 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop 1 args))]
  ^{:line 777 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (upsert! scope ^{:line 777 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (read-edn spec-file)))
  ^{:line 778 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "set-body") ^{:line 779 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [[name scope body-file] ^{:line 779 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 779 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop 1 args))]
  ^{:line 780 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (set-body! name scope ^{:line 780 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (read-edn body-file)))
  ^{:line 781 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "replace-in-body") ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [[name scope old-file new-file] ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop 1 args))
   within-path ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (drop-while ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [arg] ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (not= "--within-file" arg)) args))
   within-datum ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (if ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (some? within-path) ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (do
  ^{:line 782 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (read-edn within-path)))]
  ^{:line 783 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (replace-in-body! name scope ^{:line 785 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (read-edn old-file) ^{:line 786 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (read-edn new-file) within-datum))
  ^{:line 788 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (= mode "callgraph") ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (let [cg ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (call-edges)
   defn-meta ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:defn-meta cg)
   edges ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:edges cg)
   key->s ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [leaf] ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:key ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (get defn-meta leaf)))
   edges-s ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (mapv ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [edge] ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} [^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (key->s ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first edge)) ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (key->s ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second edge))]) edges)
   closure ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (blast-closure edges-s)
   reaches ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:reaches closure)
   blast ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (:blast closure)
   dead-priv ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (dead-private-bindings cg ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (binding-privacy))
   dead-s ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (sort ^{:line 789 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (map key->s dead-priv)))]
  ^{:line 790 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (warn ^{:line 790 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (format "callgraph: %d defns, %d scope-correct edges, %d transitive reaches-pairs, %d dead private (refers_to + Fram Datalog)" ^{:line 790 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count defn-meta) ^{:line 790 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count edges-s) ^{:line 790 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count reaches) ^{:line 790 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (count dead-s)))
  ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (println ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (json-generate ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} {:defns ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vals defn-meta)) :edges edges-s :blast ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (into ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} {} ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (map ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (fn [entry] ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} [^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (first entry) ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (vec ^{:line 791 :file "/home/tom/code/fram/src/resolve_verbs.bclj"} (second entry))]) blast)) :dead-private dead-s})))
  :else nil))))))
