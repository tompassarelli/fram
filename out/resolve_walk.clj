(ns resolve-walk
  (:require [clojure.string :as str]
            [fram.types :as t]
            [resolve-core :as rc]
            [resolve-read :as rr]
            [resolve-binds :as rb]
            [resolve-modules :as rm]))

(defrecord Walk [ctx view REFERS BOUND FIXED QUAL CTOR ACC nres nunres nxmod ntype ncomment xres tres ares])

(defn walk-ctx [r] (:ctx r))

(defn walk-view [r] (:view r))

(defn walk-REFERS [r] (:REFERS r))

(defn walk-BOUND [r] (:BOUND r))

(defn walk-FIXED [r] (:FIXED r))

(defn walk-QUAL [r] (:QUAL r))

(defn walk-CTOR [r] (:CTOR r))

(defn walk-ACC [r] (:ACC r))

(defn walk-nres [r] (:nres r))

(defn walk-nunres [r] (:nunres r))

(defn walk-nxmod [r] (:nxmod r))

(defn walk-ntype [r] (:ntype r))

(defn walk-ncomment [r] (:ncomment r))

(defn walk-xres [r] (:xres r))

(defn walk-tres [r] (:tres r))

(defn walk-ares [r] (:ares r))

(defrecord Corpus [srcs modframe typeframe accessors ents])

(defn corpus-srcs [r] (:srcs r))

(defn corpus-modframe [r] (:modframe r))

(defn corpus-typeframe [r] (:typeframe r))

(defn corpus-accessors [r] (:accessors r))

(defn corpus-ents [r] (:ents r))

(defn- nn [e]
  (if (nil? e) (throw (ex-info "resolve: node identity is required" {:type :missing-node-identity})) e))

(def RET-COLON #{":-" ":" ":raises"})

(def UNQUOTE-TOKENS #{"~" "," "~@" ",@"})

(defn- sv [^Walk w e]
  (rr/sym-val (:ctx w) (:view w) e))

(defn- kd [^Walk w e]
  (rr/kind-of (:ctx w) (:view w) e))

(defn- hd [^Walk w e]
  (rr/head-sym (:ctx w) (:view w) e))

(defn- kids [^Walk w e]
  (rr/ordered-children (:ctx w) e))

(defn- ^Boolean brk? [^Walk w e]
  (rb/brackets? (:ctx w) (:view w) e))

(defn- ^Boolean list? [^Walk w e]
  (= "list" (kd w e)))

(defn- xr [^Walk w nm]
  (let [f (:xres w)]
  (f nm)))

(defn- tr [^Walk w nm]
  (let [f (:tres w)]
  (f nm)))

(defn- ar [^Walk w nm]
  (let [f (:ares w)]
  (f nm)))

(defn- scope-lookup [scope nm]
  (loop [i 0]
  (if (>= i (count scope)) nil (let [hit (get (nth scope i) nm)]
  (if (nil? hit) (recur (inc i)) hit)))))

(defn- push [frame scope]
  (into [frame] scope))

(defn bind! [^Walk w L target]
  (do
  (rr/update-single! (:ctx w) (nn L) (:REFERS w) (nn target))
  (swap! (:nres w) (fn [n] (inc n)))))

(defn bind-xmod! [^Walk w node x]
  (if (or (nil? x) (nil? (:target x))) nil (let [mode (:mode x)
   acc (:accessor x)]
  (do
  (bind! w node (:target x))
  (cond
  (= :fixed mode) (rr/update-single! (:ctx w) (nn node) (:FIXED w) "1")
  (= :qual mode) (rr/update-single! (:ctx w) (nn node) (:QUAL w) (:alias x))
  :else nil)
  (if (some? acc) (do
  (rr/update-single! (:ctx w) (nn node) (:ACC w) acc)))
  (swap! (:nxmod w) (fn [n] (inc n)))
  true))))

(defn bound-render! [^Walk w node nm bt]
  (do
  (bind! w node bt)
  (let [x (xr w nm)
   pfx (rc/ctor-prefix (if (string? nm) nm nil))
   acc (ar w nm)
   stripped (if (nil? pfx) nil (str/replace (str nm) pfx ""))]
  (cond
  (and (some? x) (some? (:target x))) (let [mode (:mode x)
   xacc (:accessor x)]
  (do
  (cond
  (= :fixed mode) (rr/update-single! (:ctx w) (nn node) (:FIXED w) "1")
  (= :qual mode) (rr/update-single! (:ctx w) (nn node) (:QUAL w) (:alias x))
  :else nil)
  (if (some? xacc) (do
  (rr/update-single! (:ctx w) (nn node) (:ACC w) xacc)))))
  (and (some? pfx) (or (some? (tr w stripped)) (some? (:target (xr w stripped))))) (rr/update-single! (:ctx w) (nn node) (:CTOR w) pfx)
  (some? acc) (rr/update-single! (:ctx w) (nn node) (:ACC w) (nth acc 1))
  :else nil))))

(defn walk-type! [^Walk w node]
  (cond
  (some? (sv w node)) (let [nm (sv w node)
   b (tr w nm)]
  (if (some? b) (do
  (bind! w node b)
  (swap! (:ntype w) (fn [n] (inc n)))
  true) (bind-xmod! w node (xr w nm))))
  (= "list" (kd w node)) (doseq [ch (kids w node)]
  (walk-type! w ch))
  (brk? w node) (doseq [ch (vec (rest (kids w node)))]
  (walk-type! w ch))
  :else nil))

(defn- ^Boolean colon? [^Walk w e]
  (let [v (sv w e)]
  (and (some? v) (contains? rc/TYPE-COLON (str v)))))

(defn resolve-type-after-colon! [^Walk w nodes]
  (loop [xs nodes]
  (if (empty? xs) nil (if (colon? w (nth xs 0 nil)) (let [nxt (nth xs 1 nil)]
  (if (some? nxt) (do
  (walk-type! w nxt)))) (recur (vec (rest xs)))))))

(defn resolve-types-in-bracket! [^Walk w bracket]
  (loop [ks (vec (rest (kids w bracket)))]
  (if (empty? ks) nil (let [k (nth ks 0)]
  (cond
  (colon? w k) (do
  (if (some? (nth ks 1 nil)) (do
  (walk-type! w (nth ks 1 nil))))
  (recur (vec (drop 2 ks))))
  (= "list" (kd w k)) (do
  (resolve-type-after-colon! w (kids w k))
  (recur (vec (rest ks))))
  :else (recur (vec (rest ks))))))))

(defn walk-all! [^Walk w nodes scope wf]
  (doseq [n nodes]
  (wf w n scope)))

(defn walk-pat-heads! [^Walk w pat scope wf]
  (if (= "list" (kd w pat)) (do
  (let [cs (kids w pat)]
  (do
  (wf w (nth cs 0 nil) scope)
  (doseq [ch (vec (rest cs))]
  (walk-pat-heads! w ch scope wf)))))))

(defn walk-fn-arity! [^Walk w forms scope wf]
  (let [bi (loop [i 0]
  (if (>= i (count forms)) nil (if (brk? w (nth forms i)) i (recur (inc i)))))
   pv (if (nil? bi) nil (nth forms bi))
   binds (if (nil? pv) [] (rb/param-binds (:ctx w) (:view w) pv))
   _ (if (some? pv) (do
  (resolve-types-in-bracket! w pv)))
   or-vals (if (nil? pv) [] (reduce (fn [acc k] (into acc (rb/collect-or-vals (:ctx w) (:view w) k))) [] (vec (rest (kids w pv)))))
   frame (rb/frame-of (:ctx w) (:view w) binds)
   body (loop [xs (if (nil? bi) [] (vec (drop (inc bi) forms)))]
  (let [v (sv w (nth xs 0 nil))]
  (if (and (some? v) (contains? RET-COLON (str v))) (do
  (if (some? (nth xs 1 nil)) (do
  (walk-type! w (nth xs 1 nil))))
  (recur (vec (drop 2 xs)))) xs)))]
  (do
  (walk-all! w or-vals scope wf)
  (walk-all! w body (push frame scope) wf))))

(defn walk-quasi! [^Walk w node scope ^Boolean quoted? wf qsf]
  (cond
  (some? (sv w node)) (if (not quoted?) (do
  (let [nm (sv w node)
   outer (if (empty? scope) [] (vec (butlast scope)))
   modframe (if (empty? scope) nil (last scope))
   inner (scope-lookup outer nm)
   mod-hit (get modframe nm)]
  (cond
  (some? inner) nil
  (some? mod-hit) (bind! w node mod-hit)
  (some? (bind-xmod! w node (xr w nm))) nil
  :else nil))))
  (= "list" (kd w node)) (let [h (hd w node)]
  (cond
  (contains? #{"unquote" "unquote-splicing"} (str h)) (walk-all! w (vec (rest (kids w node))) scope wf)
  (= "quote" (str h)) (qsf w (kids w node) scope true wf)
  :else (qsf w (kids w node) scope quoted? wf)))
  :else nil))

(defn walk-quasi-seq! [^Walk w children scope ^Boolean quoted? wf]
  (loop [cs children]
  (if (empty? cs) nil (let [v (sv w (nth cs 0 nil))]
  (if (and (some? v) (contains? UNQUOTE-TOKENS (str v))) (do
  (if (some? (nth cs 1 nil)) (do
  (wf w (nth cs 1 nil) scope)))
  (recur (vec (drop 2 cs)))) (do
  (walk-quasi! w (nth cs 0 nil) scope quoted? wf walk-quasi-seq!)
  (recur (vec (rest cs)))))))))

(defn- ^Boolean try-type! [^Walk w node nm]
  (let [b (tr w nm)]
  (if (nil? b) false (do
  (bind! w node b)
  (swap! (:ntype w) (fn [n] (inc n)))
  true))))

(defn- ^Boolean try-ctor! [^Walk w node nm]
  (let [pfx (rc/ctor-prefix (if (string? nm) nm nil))]
  (if (nil? pfx) false (let [stripped (str/replace (str nm) pfx "")
   b (tr w stripped)]
  (if (some? b) (do
  (bind! w node b)
  (rr/update-single! (:ctx w) (nn node) (:CTOR w) pfx)
  (swap! (:ntype w) (fn [n] (inc n)))
  true) (if (some? (bind-xmod! w node (xr w stripped))) (do
  (rr/update-single! (:ctx w) (nn node) (:CTOR w) pfx)
  true) false))))))

(defn- ^Boolean try-accessor! [^Walk w node nm]
  (let [a (ar w nm)]
  (if (nil? a) false (do
  (bind! w node (nth a 0))
  (rr/update-single! (:ctx w) (nn node) (:ACC w) (nth a 1))
  (swap! (:ntype w) (fn [n] (inc n)))
  true))))

(defn- walk-type-def! [^Walk w ks]
  (doseq [ch (vec (drop (inc (rc/type-name-index (sv w (nth ks 0 nil)) (sv w (nth ks 1 nil)))) ks))]
  (cond
  (brk? w ch) (resolve-types-in-bracket! w ch)
  (= "list" (kd w ch)) (let [cc (kids w ch)]
  (do
  (doseq [b (vec (filter (fn [k] (brk? w k)) cc))]
  (resolve-types-in-bracket! w b))
  (let [bi (loop [i 0]
  (if (>= i (count cc)) nil (if (brk? w (nth cc i)) i (recur (inc i)))))]
  (resolve-type-after-colon! w (if (nil? bi) [] (vec (drop (inc bi) cc)))))))
  (some? (sv w ch)) (let [b (tr w (sv w ch))]
  (if (and (some? b) (not= b ch)) (do
  (do
  (bind! w ch b)
  (swap! (:ntype w) (fn [n] (inc n)))))))
  :else nil)))

(defn walk! [^Walk w node scope]
  (let [k (kd w node)]
  (cond
  (= "symbol" k) (let [nm (sv w node)
   local (scope-lookup scope nm)
   bt (rr/bound-target (:ctx w) (:view w) (:BOUND w) node)]
  (cond
  (some? bt) (bound-render! w node nm bt)
  (some? local) (bind! w node local)
  (some? (bind-xmod! w node (xr w nm))) nil
  (try-type! w node nm) nil
  (try-ctor! w node nm) nil
  (try-accessor! w node nm) nil
  :else (swap! (:nunres w) (fn [n] (inc n)))))
  (= "list" k) (let [ks (kids w node)
   h (hd w node)
   hs (str h)]
  (cond
  (= "quote" hs) nil
  (= "quasiquote" hs) (walk-quasi! w node scope false walk! walk-quasi-seq!)
  (contains? rc/TYPE-DEFS hs) (walk-type-def! w ks)
  (contains? rc/DEF-FORMS hs) (let [after-name (vec (drop 2 ks))]
  (if (= ":-" (str (sv w (nth after-name 0 nil)))) (do
  (if (some? (nth after-name 1 nil)) (do
  (walk-type! w (nth after-name 1 nil))))
  (walk-all! w (vec (drop 2 after-name)) scope walk!)) (walk-all! w after-name scope walk!)))
  (contains? rc/PARAM-FORMS hs) (let [after-name (if (contains? #{"defn" "defn-" "defmacro"} hs) (vec (drop 2 ks)) (vec (rest ks)))]
  (if (some? (some (fn [f] (if (brk? w f) true nil)) after-name)) (walk-fn-arity! w after-name scope walk!) (doseq [a (vec (filter (fn [f] (and (= "list" (kd w f)) (brk? w (nth (kids w f) 0 nil)))) after-name))]
  (walk-fn-arity! w (kids w a) scope walk!))))
  (contains? rc/LET-FORMS hs) (let [bracket (nth ks 1 nil)
   ok (and (some? bracket) (brk? w bracket))
   _ (if ok (do
  (resolve-types-in-bracket! w bracket)))
   pairs (if ok (rb/let-bind-pairs (:ctx w) (:view w) bracket) [])
   final (reduce (fn [sc p] (let [bsyms (nth p 0)
   vnode (nth p 1)
   orvals (nth p 2)]
  (do
  (walk-all! w orvals sc walk!)
  (if (some? vnode) (do
  (walk! w vnode sc)))
  (push (rb/frame-of (:ctx w) (:view w) bsyms) sc)))) scope pairs)]
  (walk-all! w (vec (drop 2 ks)) final walk!))
  (contains? rc/FOR-FORMS hs) (let [bracket (nth ks 1 nil)
   ok (and (some? bracket) (brk? w bracket))
   _ (if ok (do
  (resolve-types-in-bracket! w bracket)))
   entries (if ok (rb/for-bind-pairs (:ctx w) (:view w) bracket) [])
   final (reduce (fn [sc e] (if (= :expr (nth e 0)) (do
  (walk! w (nth e 1) sc)
  sc) (let [bsyms (nth e 1)
   vnode (nth e 2)
   orvals (nth e 3)]
  (do
  (walk-all! w orvals sc walk!)
  (if (some? vnode) (do
  (walk! w vnode sc)))
  (push (rb/frame-of (:ctx w) (:view w) bsyms) sc))))) scope entries)]
  (walk-all! w (vec (drop 2 ks)) final walk!))
  (contains? rc/MATCH-FORMS hs) (do
  (walk! w (nth ks 1 nil) scope)
  (doseq [clause (vec (filter (fn [cl] (brk? w cl)) (vec (drop 2 ks))))]
  (let [cc (vec (rest (kids w clause)))
   pat (nth cc 0 nil)
   body (vec (rest cc))]
  (do
  (walk-pat-heads! w pat scope walk!)
  (walk-all! w body (push (rb/frame-of (:ctx w) (:view w) (rb/match-pat-binds (:ctx w) (:view w) pat)) scope) walk!)))))
  (= "letfn" hs) (let [bracket (nth ks 1 nil)
   fnlists (if (and (some? bracket) (brk? w bracket)) (vec (filter (fn [f] (= "list" (kd w f))) (vec (rest (kids w bracket))))) [])
   frame (rb/frame-of (:ctx w) (:view w) (vec (keep (fn [f] (nth (kids w f) 0 nil)) fnlists)))
   bodyscope (push frame scope)]
  (do
  (doseq [fl fnlists]
  (walk-fn-arity! w (vec (rest (kids w fl))) bodyscope walk!))
  (walk-all! w (vec (drop 2 ks)) bodyscope walk!)))
  (contains? #{"extend-type" "extend-protocol"} hs) (doseq [ch (vec (rest ks))]
  (cond
  (some? (sv w ch)) (walk! w ch scope)
  (= "list" (kd w ch)) (let [ic (kids w ch)]
  (do
  (walk! w (nth ic 0 nil) scope)
  (walk-fn-arity! w (vec (rest ic)) scope walk!)))
  :else nil))
  (= "as->" hs) (let [init (nth ks 1 nil)
   name (nth ks 2 nil)
   frame (rb/frame-of (:ctx w) (:view w) (if (some? (sv w name)) [name] []))]
  (do
  (if (some? init) (do
  (walk! w init scope)))
  (walk-all! w (vec (drop 3 ks)) (push frame scope) walk!)))
  :else (walk-all! w ks scope walk!)))
  :else nil)))

(defn cbind! [^Walk w L target]
  (do
  (rr/update-single! (:ctx w) (nn L) (:REFERS w) (nn target))
  (swap! (:ncomment w) (fn [n] (inc n)))))

(defn- def-binding [^Corpus cp src nm]
  (let [v (get (get (:modframe cp) src) nm)]
  (if (some? v) v (get (get (:typeframe cp) src) nm))))

(defn resolve-comment! [^Walk w ^Corpus cp e src]
  (doseq [seg (vec (filter (fn [s] (= "symbol" (kd w s))) (rr/ordered-segs (:ctx w) e)))]
  (let [nm (sv w seg)
   local (def-binding cp src nm)
   b (if (some? local) local (:target (xr w nm)))]
  (if (some? b) (do
  (cbind! w seg b))))))

(defn walk-comments! [^Walk w ^Corpus cp src]
  (doseq [e (vec (filter (fn [x] (= "comment" (kd w x))) (vec (get (:ents cp) src []))))]
  (resolve-comment! w cp e src)))

(defn- ^Walk for-src [^Walk w ^Corpus cp src xres-for]
  (->Walk (:ctx w) (:view w) (:REFERS w) (:BOUND w) (:FIXED w) (:QUAL w) (:CTOR w) (:ACC w) (:nres w) (:nunres w) (:nxmod w) (:ntype w) (:ncomment w) (xres-for src) (fn [nm] (get (get (:typeframe cp) src) nm)) (fn [nm] (get (get (:accessors cp) src) nm))))

(defn run-resolution-over! [^Walk w ^Corpus cp walk-srcs xres-for n-forms walked]
  (doseq [src walk-srcs]
  (let [w2 (for-src w cp src xres-for)
   ents (vec (get (:ents cp) src []))
   forms (rm/forms-of (:ctx w) (:view w) ents)]
  (do
  (swap! walked conj src)
  (swap! n-forms + (count forms))
  (walk-all! w2 forms [(get (:modframe cp) src)] walk!)
  (walk-comments! w2 cp src)))))

(defn run-resolution! [^Walk w ^Corpus cp xres-for n-forms walked]
  (run-resolution-over! w cp (:srcs cp) xres-for n-forms walked))

(defn corpus-tables [ctx view srcs ents-of]
  (let [per (fn [f] (reduce (fn [m s] (assoc m s (f (vec (get ents-of s []))))) {} srcs))
   named (vec (filter (fn [s] (some? (rm/module-name ctx view (vec (get ents-of s []))))) srcs))
   by-mod (fn [f] (reduce (fn [m s] (let [ents (vec (get ents-of s []))]
  (assoc m (rm/module-name ctx view ents) (f ents)))) {} named))]
  {:modframe (per (fn [ents] (rm/module-defs ctx view ents))) :typeframe (per (fn [ents] (rm/module-types ctx view ents))) :accessors (per (fn [ents] (rm/module-accessors ctx view ents))) :exports (by-mod (fn [ents] (let [e (rm/module-exports ctx view ents)]
  (if (empty? e) (rm/module-defs ctx view ents) e)))) :type-exports (by-mod (fn [ents] (rm/module-types ctx view ents))) :accessor-exports (by-mod (fn [ents] (rm/module-accessors ctx view ents)))}))

(defn warm-groups [ctx cache name->module]
  (if (some? cache) cache (reduce (fn [groups event] (let [node-name (rr/event-value event)
   module (name->module node-name)]
  (if (some? module) (update groups module (fnil conj []) (rr/event-subject event)) groups))) {} (rr/events-by-predicate ctx "name"))))

(defn scoped-corpus-tables [ctx view groups scope]
  (let [srcs (vec (keys groups))
   frame-srcs (if (some? scope) (vec (filter scope srcs)) srcs)
   per-frame (fn [f] (reduce (fn [table src] (assoc table src (f (vec (get groups src []))))) {} frame-srcs))
   named (vec (filter (fn [src] (some? (rm/module-name ctx view (vec (get groups src []))))) srcs))
   by-module (fn [f] (if (some? scope) {} (reduce (fn [table src] (let [ents (vec (get groups src []))]
  (assoc table (rm/module-name ctx view ents) (f ents)))) {} named)))]
  {:srcs srcs :modframe (per-frame (fn [ents] (rm/module-defs ctx view ents))) :typeframe (per-frame (fn [ents] (rm/module-types ctx view ents))) :accessors (per-frame (fn [ents] (rm/module-accessors ctx view ents))) :exports (by-module (fn [ents] (let [exports (rm/module-exports ctx view ents)]
  (if (seq exports) exports (rm/module-defs ctx view ents))))) :type-exports (by-module (fn [ents] (rm/module-types ctx view ents))) :accessor-exports (by-module (fn [ents] (rm/module-accessors ctx view ents)))}))
