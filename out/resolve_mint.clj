(ns resolve-mint
  (:require [fram.types :as t]
            [fram.store :as c]
            [resolve-core :as rc]
            [resolve-read :as rr]
            [resolve-binds :as rb]
            [resolve-render :as rv]))

(def FN-RE (re-pattern "f\\d+"))

(defrecord Mint [ctx tx SUP KIND Vp ents view BOUND REFERS FIXED])

(defn mint-ctx [r] (:ctx r))

(defn mint-tx [r] (:tx r))

(defn mint-SUP [r] (:SUP r))

(defn mint-KIND [r] (:KIND r))

(defn mint-Vp [r] (:Vp r))

(defn mint-ents [r] (:ents r))

(defn mint-view [r] (:view r))

(defn mint-BOUND [r] (:BOUND r))

(defn mint-REFERS [r] (:REFERS r))

(defn mint-FIXED [r] (:FIXED r))

(defrecord FnEdge [idx cid child])

(defn fnedge-idx [r] (:idx r))

(defn fnedge-cid [r] (:cid r))

(defn fnedge-child [r] (:child r))

(defn register! [^Mint m ^String src e]
  (let [ents (:ents m)]
  (do
  (swap! ents (fn [tbl] (assoc tbl src (conj (get tbl src []) e))))
  e)))

(defn mint-leaf! [^Mint m ^String src kind v]
  (let [ctx (:ctx m)
   tx (:tx m)
   e (register! m src (c/entity! ctx))]
  (do
  (c/fact! ctx e (:KIND m) (c/value! ctx kind) tx)
  (c/fact! ctx e (:Vp m) (c/value! ctx v) tx)
  e)))

(defn- clj-meta->beagle-meta [mt]
  (cond
  (and (= 1 (count mt)) (contains? mt :tag) (symbol? (:tag mt))) (:tag mt)
  (and (= 1 (count mt)) (true? (val (first mt)))) (key (first mt))
  :else mt))

(defn- reader-meta [d]
  (if (instance? clojure.lang.IObj d) (not-empty (apply dissoc (meta d) [:line :column :end-line :end-column :file])) nil))

(defn mint-datum! [^Mint m ^String src d]
  (let [mt (reader-meta d)]
  (if (some? mt) (mint-datum! m src (list (symbol "#%meta") (clj-meta->beagle-meta mt) (with-meta d nil))) (cond
  (nil? d) (mint-leaf! m src "symbol" "nil")
  (symbol? d) (mint-leaf! m src "symbol" (str d))
  (keyword? d) (mint-leaf! m src "symbol" (str d))
  (string? d) (mint-leaf! m src "string" d)
  (boolean? d) (mint-leaf! m src "symbol" (if d "true" "false"))
  (char? d) (mint-leaf! m src "char" (str d))
  (number? d) (mint-leaf! m src "number" (str d))
  (or (list? d) (seq? d) (vector? d) (map? d)) (let [ctx (:ctx m)
   tx (:tx m)
   head (cond
  (vector? d) [(symbol "#%brackets")]
  (map? d) [(symbol "#%map")]
  :else [])
   elems (vec (concat head (if (map? d) (apply concat (seq d)) (seq d))))
   e (register! m src (c/entity! ctx))]
  (do
  (c/fact! ctx e (:KIND m) (c/value! ctx "list") tx)
  (doseq [i (range (count elems))]
  (c/fact! ctx e (c/value! ctx (str "f" i)) (mint-datum! m src (nth elems i)) tx))
  e))
  (instance? java.util.regex.Pattern d) (mint-datum! m src (list (symbol "#%regex") (.pattern d)))
  (set? d) (mint-datum! m src (apply list (cons (symbol "#%set") (seq d))))
  :else (mint-leaf! m src "other" (pr-str d))))))

(defn fN-facts [^Mint m parent]
  (let [ctx (:ctx m)
   rows (reduce (fn [acc cid] (let [f (c/fact-of ctx cid)
   pi (if (nil? f) nil (:p f))
   p (if (int? pi) (c/literal ctx pi) nil)
   r (if (nil? f) nil (:r f))]
  (if (and (string? p) (some? (re-matches FN-RE (str p)))) (let [n (parse-long (subs (str p) 1))]
  (if (nil? n) acc (conj acc (->FnEdge n cid r)))) acc))) [] (c/by-l ctx parent))]
  (mapv (fn [e] [(:idx e) (:cid e) (:child e)]) (sort-by (fn [e] (:idx e)) rows))))

(defn retire-fact! [^Mint m oldc]
  (let [ctx (:ctx m)]
  (c/fact! ctx (c/entity! ctx) (:SUP m) oldc (:tx m))))

(defn- nn [e]
  (if (nil? e) -1 e))

(def COLON3 #{":-" ":" ":raises"})

(defn- push [frame scope]
  (into [frame] scope))

(defn- ^Boolean renders-as-tracked-name? [^Mint m node]
  (and (empty? (c/by-lp (:ctx m) (nn node) (:FIXED m))) (nil? (rr/pred-val (:ctx m) (:view m) node "qualifier"))))

(defn capture-refs [^Mint m node scope B newnm]
  (let [ctx (:ctx m)
   view (:view m)
   BOUND (:BOUND m)
   REFERS (:REFERS m)
   k (rr/kind-of ctx view node)]
  (cond
  (= "symbol" k) (let [tgt (rr/refers-target ctx view BOUND REFERS node)]
  (if (and (some? tgt) (= B (rv/ultimate ctx view BOUND REFERS tgt)) (renders-as-tracked-name? m node) (some? (some (fn [fr] (get fr newnm)) scope))) [node] []))
  (= "list" k) (let [kids (rr/ordered-children ctx node)
   h (str (rr/head-sym ctx view node))
   brk? (fn [e] (rb/brackets? ctx view e))
   cap-arity (fn [forms] (let [bi (loop [i 0]
  (if (>= i (count forms)) nil (if (brk? (nth forms i)) i (recur (inc i)))))
   pv (if (nil? bi) nil (nth forms bi))
   frame (rb/frame-of ctx view (if (nil? pv) [] (rb/param-binds ctx view pv)))
   or-vals (if (nil? pv) [] (reduce (fn [acc kd] (into acc (rb/collect-or-vals ctx view kd))) [] (vec (rest (rr/ordered-children ctx pv)))))
   body (loop [xs (if (nil? bi) [] (vec (drop (+ bi 1) forms)))]
  (if (contains? COLON3 (str (rr/sym-val ctx view (nth xs 0 nil)))) (recur (vec (drop 2 xs))) xs))]
  (into (reduce (fn [acc o] (into acc (capture-refs m o scope B newnm))) [] or-vals) (reduce (fn [acc b] (into acc (capture-refs m b (push frame scope) B newnm))) [] body))))]
  (cond
  (contains? rc/PARAM-FORMS h) (let [after-name (if (contains? #{"defn" "defn-" "defmacro"} h) (vec (drop 2 kids)) (vec (rest kids)))]
  (if (some? (some (fn [f] (if (brk? f) true nil)) after-name)) (cap-arity after-name) (reduce (fn [acc a] (if (and (= "list" (rr/kind-of ctx view a)) (brk? (nth (rr/ordered-children ctx a) 0 nil))) (into acc (cap-arity (rr/ordered-children ctx a))) acc)) [] after-name)))
  (contains? rc/LET-FORMS h) (let [bracket (nth kids 1 nil)
   pairs (if (and (some? bracket) (brk? bracket)) (rb/let-bind-pairs ctx view bracket) [])
   acc0 [scope []]
   st (reduce (fn [a p] (let [sc (nth a 0)
   caps (nth a 1)
   bsyms (nth p 0)
   vnode (nth p 1)
   orvals (nth p 2)
   c1 (reduce (fn [x o] (into x (capture-refs m o sc B newnm))) caps orvals)
   c2 (if (some? vnode) (into c1 (capture-refs m vnode sc B newnm)) c1)]
  [(push (rb/frame-of ctx view bsyms) sc) c2])) acc0 pairs)
   final (nth st 0)
   vcaps (nth st 1)]
  (reduce (fn [acc b] (into acc (capture-refs m b final B newnm))) vcaps (vec (drop 2 kids))))
  (contains? rc/FOR-FORMS h) (let [bracket (nth kids 1 nil)
   entries (if (and (some? bracket) (brk? bracket)) (rb/for-bind-pairs ctx view bracket) [])
   acc0 [scope []]
   st (reduce (fn [a e] (let [sc (nth a 0)
   caps (nth a 1)]
  (if (= :expr (nth e 0)) [sc (into caps (capture-refs m (nth e 1) sc B newnm))] (let [bsyms (nth e 1)
   vnode (nth e 2)
   orvals (nth e 3)
   c1 (reduce (fn [x o] (into x (capture-refs m o sc B newnm))) caps orvals)
   c2 (if (some? vnode) (into c1 (capture-refs m vnode sc B newnm)) c1)]
  [(push (rb/frame-of ctx view bsyms) sc) c2])))) acc0 entries)
   final (nth st 0)
   vcaps (nth st 1)]
  (reduce (fn [acc b] (into acc (capture-refs m b final B newnm))) vcaps (vec (drop 2 kids))))
  (contains? rc/MATCH-FORMS h) (reduce (fn [acc clause] (if (brk? clause) (let [cc (vec (rest (rr/ordered-children ctx clause)))
   pat (nth cc 0 nil)
   body (vec (rest cc))
   frame (rb/frame-of ctx view (rb/match-pat-binds ctx view pat))]
  (into (into acc (capture-refs m pat scope B newnm)) (reduce (fn [x b] (into x (capture-refs m b (push frame scope) B newnm))) [] body))) acc)) (capture-refs m (nth kids 1 nil) scope B newnm) (vec (drop 2 kids)))
  (= "letfn" h) (let [bracket (nth kids 1 nil)
   fnlists (if (and (some? bracket) (brk? bracket)) (vec (filter (fn [f] (= "list" (rr/kind-of ctx view f))) (vec (rest (rr/ordered-children ctx bracket))))) [])
   frame (rb/frame-of ctx view (vec (keep (fn [f] (nth (rr/ordered-children ctx f) 0 nil)) fnlists)))
   bodyscope (push frame scope)
   cap-fn (fn [forms] (let [bi (loop [i 0]
  (if (>= i (count forms)) nil (if (brk? (nth forms i)) i (recur (inc i)))))
   pv (if (nil? bi) nil (nth forms bi))
   pframe (rb/frame-of ctx view (if (nil? pv) [] (rb/param-binds ctx view pv)))
   fbody (loop [xs (if (nil? bi) [] (vec (drop (+ bi 1) forms)))]
  (if (contains? COLON3 (str (rr/sym-val ctx view (nth xs 0 nil)))) (recur (vec (drop 2 xs))) xs))]
  (reduce (fn [x b] (into x (capture-refs m b (push pframe bodyscope) B newnm))) [] fbody)))]
  (into (reduce (fn [acc fl] (into acc (cap-fn (vec (rest (rr/ordered-children ctx fl)))))) [] fnlists) (reduce (fn [acc b] (into acc (capture-refs m b bodyscope B newnm))) [] (vec (drop 2 kids)))))
  (contains? #{"extend-type" "extend-protocol"} h) (reduce (fn [acc ch] (if (= "list" (rr/kind-of ctx view ch)) (let [ic (rr/ordered-children ctx ch)
   rest-ic (vec (rest ic))
   bi (loop [i 0]
  (if (>= i (count rest-ic)) nil (if (brk? (nth rest-ic i)) i (recur (inc i)))))
   pv (if (nil? bi) nil (nth rest-ic bi))
   pframe (rb/frame-of ctx view (if (nil? pv) [] (rb/param-binds ctx view pv)))
   fbody (loop [xs (if (nil? bi) [] (vec (drop (+ bi 1) rest-ic)))]
  (if (contains? COLON3 (str (rr/sym-val ctx view (nth xs 0 nil)))) (recur (vec (drop 2 xs))) xs))]
  (into (into acc (capture-refs m (nth ic 0 nil) scope B newnm)) (reduce (fn [x b] (into x (capture-refs m b (push pframe scope) B newnm))) [] fbody))) (into acc (capture-refs m ch scope B newnm)))) [] (vec (rest kids)))
  (= "as->" h) (let [init (nth kids 1 nil)
   nmn (nth kids 2 nil)
   frame (rb/frame-of ctx view (if (some? (rr/sym-val ctx view nmn)) [(nn nmn)] []))
   head (if (some? init) (capture-refs m init scope B newnm) [])]
  (reduce (fn [acc b] (into acc (capture-refs m b (push frame scope) B newnm))) head (vec (drop 3 kids))))
  :else (reduce (fn [acc b] (into acc (capture-refs m b scope B newnm))) [] kids)))
  :else [])))
