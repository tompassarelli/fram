(ns resolve
  (:require [fram.types :as t]
            [fram.cnf :as c]
            [clojure.string :as str]
            [fram.rt :as rt]))

(def ORD-STEP 65536)

(def PARAM-FORMS #{"defn" "defn-" "fn" "defmacro" "fn*"})

(def DEF-FORMS #{"def" "def-" "defonce"})

(def VALUE-DEFS (into PARAM-FORMS DEF-FORMS))

(def TYPE-DEFS #{"defrecord" "deftype" "defprotocol" "definterface" "defunion"})

(def TYPE-COLON #{":-" ":"})

(def LET-FORMS #{"let" "loop" "when-let" "if-let" "when-some" "if-some" "binding" "with-open" "with-local-vars" "dotimes" "with-redefs" "if-let*" "when-let*"})

(def FOR-FORMS #{"doseq" "for"})

(def MATCH-FORMS #{"match"})

(def ^:dynamic ctx nil)

(def ^:dynamic tx nil)

(def ^:dynamic SUP nil)

(def ^:dynamic *reject!* (fn [code] (rt/exit! code)))

(def ^:dynamic *resolve-walk?* true)

(def ^:dynamic *corpus-scope* nil)

(def ^:dynamic *corpus-cache* nil)

(def ^:dynamic *deleted-forms* #{})

(def ^:dynamic *deleted-subtree* #{})

(def ^:dynamic *resolve-out* nil)

(def ^:dynamic *project-srcs* nil)

(def ^:dynamic *capture-only?* false)

(def ^:dynamic file->ents (atom {}))

(def ^:dynamic Vp nil)

(def ^:dynamic KIND nil)

(def ^:dynamic REFERS nil)

(def ^:dynamic BOUND nil)

(def ^:dynamic FIXED nil)

(def ^:dynamic QUAL nil)

(def ^:dynamic CTOR nil)

(def ^:dynamic ACC nil)

(def ^:dynamic n-resolved (atom 0))

(def ^:dynamic n-unresolved (atom 0))

(def ^:dynamic n-xmod (atom 0))

(def ^:dynamic n-type (atom 0))

(def ^:dynamic n-comment (atom 0))

(def ^:dynamic n-forms-walked (atom 0))

(def ^:dynamic walked-modules (atom #{}))

(def ^:dynamic *xresolve* (fn [nm] nil))

(def ^:dynamic *tresolve* (fn [nm] nil))

(def ^:dynamic *aresolve* (fn [nm] nil))

(def ^:dynamic srcs [])

(def ^:dynamic file-modframe {})

(def ^:dynamic file-typeframe {})

(def ^:dynamic file-accessors {})

(def ^:dynamic global-exports {})

(def ^:dynamic global-type-exports {})

(def ^:dynamic global-accessor-exports {})

(defn ^String load-edn! [^String path]
  (let [lines (str/split-lines (rt/slurp path))
   src (subs (let [l (first (filter (fn [l] (str/starts-with? l "@file")) lines))]
  l) 6)
   local (atom {})
   ent (fn [lid] (let [r (or (get (deref local) lid) (let [e (c/entity! ctx)]
  (swap! local (fn [m] (assoc m lid e)))
  (swap! file->ents (fn [m] (update m src (fn [o] (conj (or o []) e)))))
  e))]
  r))]
  (doseq [line lines]
  (if (str/starts-with? line "[") (do
  (let [[s p o] (rt/parse-edn line)]
  (c/claim! ctx (ent s) (c/value! ctx p) (if (integer? o) (ent o) (c/value! ctx o)) tx)))))
  src))

(defn select-main-1 [cids]
  (first cids))

(defn pred-val [e ^String pname]
  (let [P (c/value-id ctx pname)]
  (if P (do
  (let [cs (c/by-lp ctx e P)]
  (let [cid (select-main-1 cs)]
  (if cid (do
  (c/literal ctx (let [r (or (c/claim-r ctx cid) 0)]
  r))))))))))

(defn kind-of [e]
  (pred-val e "kind"))

(defn sym-val [e]
  (if (= "symbol" (kind-of e)) (do
  (pred-val e "v"))))

(defn ord-parse [p]
  (if (string? p) (do
  (let [m (re-matches #"f(\d+(?:\.\d+)*)~(\d+)" p)]
  (if (some? m) (let [ps (nth m 1)
   ts (nth m 2)]
  {:path (mapv (fn [x] (or (parse-long x) 0)) (str/split ps #"\.")) :tie (or (parse-long ts) 0)}) (let [m2 (re-matches #"f(\d+)" p)]
  (if (some? m2) {:path [(* (inc (or (parse-long (nth m2 1)) 0)) ORD-STEP)] :tie 0} nil)))))))

(defn ^Boolean ord-pos? [p]
  (boolean (ord-parse p)))

(defn ^String ord-str [path tie]
  (str "f" (str/join "." path) "~" tie))

(defn ord-veccmp [a b]
  (loop [a (seq a)
   b (seq b)]
  (cond
  (and (nil? a) (nil? b)) 0
  (nil? a) -1
  (nil? b) 1
  :else (let [c (compare (first a) (first b))]
  (if (zero? c) (recur (next a) (next b)) c)))))

(defn ord-cmp [x y]
  (let [c (ord-veccmp (:path x) (:path y))]
  (if (zero? c) (compare (:tie x) (:tie y)) c)))

(defn ord-append [last-path]
  (if (empty? last-path) [ORD-STEP] (conj (vec (butlast last-path)) (let [n (+ (let [l (or (last last-path) 0)]
  l) ORD-STEP)]
  n))))

(defn ord-between [lo hi]
  (cond
  (and (nil? lo) (nil? hi)) [ORD-STEP]
  (nil? hi) (ord-append (let [l (or lo [])]
  l))
  :else (let [lo (or lo [0])]
  (loop [i 0
   acc []]
  (let [a (get lo i 0)
   b (get hi i (+ a (* 2 ORD-STEP)))]
  (if (> (let [d (- b a)]
  d) 1) (conj acc (quot (let [s (+ a b)]
  s) 2)) (recur (inc i) (conj acc a))))))))

(defn ordered-children [e]
  (->> (c/by-l ctx e) (keep (fn [cid] (let [k (ord-parse (c/literal ctx (let [p (or (c/claim-p ctx cid) 0)]
  p)))]
  (if k (do
  [k (let [r (or (c/claim-r ctx cid) 0)]
  r)]))))) (sort-by first ord-cmp) (mapv second)))

(defn ordered-segs [e]
  (->> (c/by-l ctx e) (keep (fn [cid] (let [p (c/literal ctx (let [pp (or (c/claim-p ctx cid) 0)]
  pp))]
  (if (and (string? p) (re-matches #"seg\d+" p)) (do
  [(parse-long (subs p 3)) (let [r (or (c/claim-r ctx cid) 0)]
  r)]))))) (sort-by first) (mapv second)))

(defn head-sym [e]
  (if (= "list" (kind-of e)) (do
  (let [cs (ordered-children e)]
  (if (not (empty? cs)) (do
  (sym-val (let [c (first cs)]
  c))))))))

(defn bound-target
  "The DURABLE identity target of reference `L` — the bound_to edge points at the binding's\n   stable @mod#int node-id, not its spelling. nil if L carries no durable edge (legacy/unedged\n   refs fall back to the spelling-derived refers_to)." [L]
  (if BOUND (do
  (let [cs (c/by-lp ctx L BOUND)]
  (let [cid (select-main-1 cs)]
  (if cid (do
  (c/claim-r ctx cid))))))))

(defn refers-target
  "The binding node reference `L` resolves to (default-main view). Prefers the DURABLE bound_to\n   identity edge; falls back to the derived refers_to (spelling-walk) for unedged/legacy refs.\n   Not a uniqueness proof (select-main-1)." [L]
  (or (bound-target L) (let [cs (c/by-lp ctx L REFERS)]
  (let [cid (select-main-1 cs)]
  (if cid (do
  (c/claim-r ctx cid)))))))

(defn live-node? [e]
  (seq (c/by-lp ctx e KIND)))

(defn ^Boolean brackets? [e]
  (= "#%brackets" (head-sym e)))

(defn ^Boolean map-node? [e]
  (= "#%map" (head-sym e)))

(defn collect-bind-syms [node]
  (cond
  (sym-val node) (let [v (sym-val node)]
  (if (contains? #{"&" "_"} v) [] [node]))
  (brackets? node) (mapcat collect-bind-syms (rest (ordered-children node)))
  (map-node? node) (loop [ks (rest (ordered-children node))
   acc []]
  (if (empty? ks) acc (let [k (first ks)
   kv (sym-val k)
   v (second ks)]
  (cond
  (contains? #{":keys" ":strs" ":syms"} kv) (recur (drop 2 ks) (into acc (if (and v (brackets? v)) (do
  (filterv sym-val (rest (ordered-children v)))))))
  (= ":as" kv) (recur (drop 2 ks) (into acc (collect-bind-syms v)))
  (= ":or" kv) (recur (drop 2 ks) acc)
  (sym-val k) (recur (drop 2 ks) (conj acc k))
  :else (recur (drop 2 ks) (into acc (collect-bind-syms k)))))))
  (= "list" (kind-of node)) (mapcat collect-bind-syms (take-while (fn [x] (not (contains? TYPE-COLON (sym-val x)))) (ordered-children node)))
  :else []))

(defn collect-or-vals [node]
  (cond
  (map-node? node) (loop [ks (rest (ordered-children node))
   acc []]
  (if (empty? ks) acc (let [k (first ks)
   kv (sym-val k)
   v (second ks)]
  (cond
  (= ":or" kv) (recur (drop 2 ks) (into acc (if (and v (map-node? v)) (do
  (keep-indexed (fn [i c] (if (odd? i) (do
  c))) (rest (ordered-children v)))))))
  (contains? #{":keys" ":strs" ":syms" ":as"} kv) (recur (drop 2 ks) acc)
  (sym-val k) (recur (drop 2 ks) acc)
  :else (recur (drop 2 ks) (into acc (collect-or-vals k)))))))
  (brackets? node) (mapcat collect-or-vals (rest (ordered-children node)))
  :else []))

(defn param-binds [bracket]
  (loop [ks (rest (ordered-children bracket))
   binds []
   skip false]
  (if (empty? ks) binds (let [k (first ks)
   v (sym-val k)]
  (cond
  skip (recur (rest ks) binds false)
  (contains? TYPE-COLON v) (recur (rest ks) binds true)
  :else (recur (rest ks) (into binds (collect-bind-syms k)) false))))))

(defn let-bind-pairs [bracket]
  (loop [ks (rest (ordered-children bracket))
   acc []]
  (if (empty? ks) acc (let [pat (first ks)
   after (if (contains? TYPE-COLON (sym-val (second ks))) (drop 3 ks) (rest ks))
   val (first after)]
  (recur (rest after) (conj acc [(collect-bind-syms pat) val (collect-or-vals pat)]))))))

(defn for-bind-pairs [bracket]
  (loop [ks (rest (ordered-children bracket))
   acc []]
  (if (empty? ks) acc (let [k (first ks)
   kv (sym-val k)
   v (second ks)]
  (cond
  (contains? #{":when" ":while"} kv) (recur (drop 2 ks) (conj acc [:expr v]))
  (= ":let" kv) (recur (drop 2 ks) (into acc (if (and v (brackets? v)) (do
  (mapv (fn [p] (let [s (first p)
   vn (second p)
   ov (nth p 2)]
  [:bind s vn ov])) (let-bind-pairs v))))))
  (contains? TYPE-COLON (sym-val v)) (recur (drop 4 ks) (conj acc [:bind (collect-bind-syms k) (nth ks 3 nil) (collect-or-vals k)]))
  :else (recur (drop 2 ks) (conj acc [:bind (collect-bind-syms k) v (collect-or-vals k)])))))))

(defn frame-of [bsyms]
  (into {} (mapv (fn [b] [(sym-val b) b]) bsyms)))

(defn match-pat-binds [pat]
  (cond
  (sym-val pat) (let [v (sym-val pat)]
  (if (contains? #{"_"} v) [] [pat]))
  (= "list" (kind-of pat)) (mapcat match-pat-binds (rest (ordered-children pat)))
  (brackets? pat) (mapcat match-pat-binds (rest (ordered-children pat)))
  :else []))

(declare walk! walk-type! walk-all! walk-fn-arity! walk-pat-heads! walk-quasi! walk-quasi-seq!)

(defn bind! [L target]
  (c/claim! ctx L REFERS target tx)
  (swap! n-resolved (fn [x] (+ x 1))))

(defn bind-xmod! [node x]
  (if (and x (:target x)) (do
  (bind! node (:target x))
  (cond
  (= (:mode x) :fixed) (c/claim! ctx node FIXED (c/value! ctx "1") tx)
  (= (:mode x) :qual) (c/claim! ctx node QUAL (c/value! ctx (:alias x)) tx)
  :else nil)
  (if (:accessor x) (do
  (c/claim! ctx node ACC (c/value! ctx (:accessor x)) tx)))
  (swap! n-xmod (fn [n] (+ n 1)))
  true)))

(defn walk-type! [node]
  (cond
  (sym-val node) (let [nm (sym-val node)]
  (or (let [b (*tresolve* nm)]
  (if b (do
  (bind! node b)
  (swap! n-type (fn [n] (+ n 1)))
  true))) (bind-xmod! node (*xresolve* nm))))
  (= "list" (kind-of node)) (doseq [c (ordered-children node)]
  (walk-type! c))
  (brackets? node) (doseq [c (rest (ordered-children node))]
  (walk-type! c))
  :else nil))

(defn resolve-type-after-colon! [nodes]
  (loop [xs nodes]
  (if (seq xs) (do
  (if (contains? TYPE-COLON (sym-val (first xs))) (if (second xs) (do
  (walk-type! (second xs)))) (recur (rest xs)))))))

(defn resolve-types-in-bracket! [bracket]
  (loop [ks (rest (ordered-children bracket))]
  (if (seq ks) (do
  (let [k (first ks)]
  (cond
  (contains? TYPE-COLON (sym-val k)) (do
  (if (second ks) (do
  (walk-type! (second ks))))
  (recur (drop 2 ks)))
  (= "list" (kind-of k)) (do
  (resolve-type-after-colon! (ordered-children k))
  (recur (rest ks)))
  :else (recur (rest ks))))))))

(defn walk-all! [nodes scope]
  (doseq [n nodes]
  (walk! n scope)))

(defn walk-fn-arity! [forms scope]
  (let [pv (first (filter (fn [f] (brackets? f)) forms))
   binds (if pv (param-binds pv) [])
   _ (if pv (do
  (resolve-types-in-bracket! pv)))
   or-vals (if pv (mapcat collect-or-vals (rest (ordered-children pv))) [])
   frame (frame-of binds)
   body (loop [xs (rest (drop-while (fn [f] (not (brackets? f))) forms))]
  (if (contains? #{":-" ":" ":raises"} (sym-val (first xs))) (do
  (if (second xs) (do
  (walk-type! (second xs))))
  (recur (drop 2 xs))) xs))]
  (walk-all! or-vals scope)
  (walk-all! body (cons frame scope))))

(defn walk-pat-heads! [pat scope]
  (if (= "list" (kind-of pat)) (do
  (walk! (first (ordered-children pat)) scope)
  (doseq [c (rest (ordered-children pat))]
  (walk-pat-heads! c scope)))))

(defn walk! [node scope]
  (cond
  (= "symbol" (kind-of node)) (let [nm (sym-val node)
   bt (bound-target node)
   local (some (fn [m] (get m nm)) scope)]
  (cond
  bt (bind! node bt)
  local (bind! node local)
  (bind-xmod! node (*xresolve* nm)) nil
  (let [b (*tresolve* nm)]
  (if b (do
  (bind! node b)
  (swap! n-type (fn [n] (+ n 1)))
  true))) nil
  (let [pfx (cond
  (or (str/starts-with? (or nm "") "map->") (str/includes? (or nm "") "/map->")) "map->"
  (or (str/starts-with? (or nm "") "->") (str/includes? (or nm "") "/->")) "->"
  :else nil)]
  (if pfx (do
  (let [stripped (str/replace (or nm "") pfx "")]
  (or (let [b (*tresolve* stripped)]
  (if b (do
  (bind! node b)
  (c/claim! ctx node CTOR (c/value! ctx pfx) tx)
  (swap! n-type (fn [n] (+ n 1)))
  true))) (if (bind-xmod! node (*xresolve* stripped)) (do
  (c/claim! ctx node CTOR (c/value! ctx pfx) tx)
  true))))))) nil
  (let [a (*aresolve* nm)]
  (if a (do
  (bind! node (first a))
  (c/claim! ctx node ACC (c/value! ctx (second a)) tx)
  (swap! n-type (fn [n] (+ n 1)))
  true))) nil
  :else (swap! n-unresolved (fn [n] (+ n 1)))))
  (= "list" (kind-of node)) (let [kids (ordered-children node)
   h (head-sym node)]
  (cond
  (contains? #{"quote"} h) nil
  (contains? #{"quasiquote"} h) (walk-quasi! node scope false)
  (contains? TYPE-DEFS h) (doseq [c (drop 2 kids)]
  (cond
  (brackets? c) (resolve-types-in-bracket! c)
  (= "list" (kind-of c)) (do
  (doseq [b (filter (fn [x] (brackets? x)) (ordered-children c))]
  (resolve-types-in-bracket! b))
  (resolve-type-after-colon! (rest (drop-while (fn [x] (not (brackets? x))) (ordered-children c)))))
  (sym-val c) (let [b (*tresolve* (sym-val c))]
  (if (and b (not= b c)) (do
  (bind! c b)
  (swap! n-type (fn [n] (+ n 1))))))
  :else nil))
  (contains? DEF-FORMS h) (let [after-name (drop 2 kids)]
  (if (= ":-" (sym-val (first after-name))) (do
  (if (second after-name) (do
  (walk-type! (second after-name))))
  (walk-all! (drop 2 after-name) scope)) (walk-all! after-name scope)))
  (contains? PARAM-FORMS h) (let [after-name (if (contains? #{"defn" "defn-" "defmacro"} h) (drop 2 kids) (rest kids))]
  (if (some (fn [a] (brackets? a)) after-name) (walk-fn-arity! after-name scope) (doseq [a after-name]
  (if (and (= "list" (kind-of a)) (brackets? (first (ordered-children a)))) (do
  (walk-fn-arity! (ordered-children a) scope))))))
  (contains? LET-FORMS h) (let [bracket (second kids)
   _ (if (and bracket (brackets? bracket)) (do
  (resolve-types-in-bracket! bracket)))
   pairs (if (and bracket (brackets? bracket)) (let-bind-pairs bracket) [])
   final (reduce (fn [sc pr] (let [[bsyms vnode orvals] pr]
  (walk-all! orvals sc)
  (if vnode (do
  (walk! vnode sc)))
  (cons (frame-of bsyms) sc))) scope pairs)]
  (walk-all! (drop 2 kids) final))
  (contains? FOR-FORMS h) (let [bracket (second kids)
   _ (if (and bracket (brackets? bracket)) (do
  (resolve-types-in-bracket! bracket)))
   entries (if (and bracket (brackets? bracket)) (for-bind-pairs bracket) [])
   final (reduce (fn [sc e] (if (= :expr (first e)) (do
  (walk! (second e) sc)
  sc) (let [[_ bsyms vnode orvals] e]
  (walk-all! orvals sc)
  (if vnode (do
  (walk! vnode sc)))
  (cons (frame-of bsyms) sc)))) scope entries)]
  (walk-all! (drop 2 kids) final))
  (contains? MATCH-FORMS h) (let [kids (ordered-children node)]
  (walk! (second kids) scope)
  (doseq [clause (drop 2 kids)]
  (if (brackets? clause) (do
  (let [cc (rest (ordered-children clause))
   pat (first cc)
   body (rest cc)]
  (walk-pat-heads! pat scope)
  (walk-all! body (cons (frame-of (match-pat-binds pat)) scope)))))))
  (= h "letfn") (let [bracket (second kids)
   fnlists (if (and bracket (brackets? bracket)) (filter (fn [x] (= "list" (kind-of x))) (rest (ordered-children bracket))) [])
   frame (frame-of (keep (fn [fl] (first (ordered-children fl))) fnlists))
   bodyscope (cons frame scope)]
  (doseq [fl fnlists]
  (walk-fn-arity! (rest (ordered-children fl)) bodyscope))
  (walk-all! (drop 2 kids) bodyscope))
  (contains? #{"extend-type" "extend-protocol"} h) (doseq [c (rest kids)]
  (cond
  (sym-val c) (walk! c scope)
  (= "list" (kind-of c)) (let [ic (ordered-children c)]
  (walk! (first ic) scope)
  (walk-fn-arity! (rest ic) scope))
  :else nil))
  (= h "as->") (let [init (if (> (count kids) 1) (second kids) nil)
   nm (if (> (count kids) 2) (nth kids 2) nil)
   frame (frame-of (if (and nm (sym-val nm)) [nm] []))]
  (if init (do
  (walk! init scope)))
  (walk-all! (drop 3 kids) (cons frame scope)))
  :else (walk-all! kids scope)))
  :else nil))

(defn walk-quasi! [node scope ^Boolean quoted?]
  (cond
  (sym-val node) (if (not quoted?) (do
  (let [nm (sym-val node)]
  (cond
  (some (fn [m] (get m nm)) (butlast scope)) nil
  (get (last scope) nm) (bind! node (get (last scope) nm))
  (bind-xmod! node (*xresolve* nm)) nil
  :else nil))))
  (= "list" (kind-of node)) (let [h (head-sym node)]
  (cond
  (contains? #{"unquote" "unquote-splicing"} h) (walk-all! (rest (ordered-children node)) scope)
  (contains? #{"quote"} h) (walk-quasi-seq! (ordered-children node) scope true)
  :else (walk-quasi-seq! (ordered-children node) scope quoted?)))
  :else nil))

(defn walk-quasi-seq! [children scope ^Boolean quoted?]
  (loop [cs children]
  (if (seq cs) (do
  (if (contains? #{"~" "," "~@" ",@"} (sym-val (first cs))) (do
  (if (second cs) (do
  (walk! (second cs) scope)))
  (recur (drop 2 cs))) (do
  (walk-quasi! (first cs) scope quoted?)
  (recur (rest cs))))))))

(defn unwrap-def [form]
  (if (= "js/export" (head-sym form)) (second (ordered-children form)) form))

(defn module-defs [^String src]
  (let [wrapper (some (fn [e] (if (= "beagle-file" (head-sym e)) (do
  e))) (let [fe (deref file->ents)]
  (get fe src)))
   forms (rest (ordered-children wrapper))]
  (into {} (mapcat (fn [f] (let [d (unwrap-def f)]
  (cond
  (contains? VALUE-DEFS (head-sym d)) (let [nl (second (ordered-children d))]
  (if (sym-val nl) (do
  [[(sym-val nl) nl]])))
  (contains? #{"defprotocol" "definterface"} (head-sym d)) (keep (fn [m] (if (= "list" (kind-of m)) (do
  (let [nl (first (ordered-children m))]
  (if (sym-val nl) (do
  [(sym-val nl) nl])))))) (drop 2 (ordered-children d)))
  :else nil))) forms))))

(defn forms-of [^String src]
  (rest (ordered-children (some (fn [e] (if (= "beagle-file" (head-sym e)) (do
  e))) (let [fe (deref file->ents)]
  (get fe src))))))

(defn ns-form [^String src]
  (some (fn [f] (if (= "ns" (head-sym f)) (do
  f))) (forms-of src)))

(defn module-name [^String src]
  (let [nf (ns-form src)]
  (if nf (do
  (sym-val (second (ordered-children nf)))))))

(defn merge-import-opts [acc modn kids]
  (let [idx (fn [kw] (first (keep-indexed (fn [i k] (if (= kw (sym-val k)) (do
  i))) kids)))
   ri (idx ":refer")
   ai (idx ":as")
   rri (idx ":rename")
   nb (if ri (do
  (nth kids (inc ri) nil)))
   refers (if (and nb (brackets? nb)) (do
  (keep sym-val (rest (ordered-children nb)))))
   alias (if ai (do
  (sym-val (nth kids (inc ai) nil))))
   rmap (if rri (do
  (let [mb (nth kids (inc rri) nil)]
  (if (and mb (map-node? mb)) (do
  (loop [cs (rest (ordered-children mb))
   m {}]
  (if (< (count cs) 2) m (recur (drop 2 cs) (assoc m (sym-val (first cs)) (sym-val (second cs)))))))))))
   acc1 (if (seq refers) (update acc :refer (fn [m] (into m (mapv (fn [n] [n modn]) refers)))) acc)
   acc2 (if alias (update acc1 :as (fn [m] (assoc m alias modn))) acc1)
   acc3 (if (seq rmap) (update acc2 :rename (fn [m] (into m (mapv (fn [pr] (let [sn (first pr)
   ln (nth pr 1)]
  [ln [modn sn]])) (vec rmap))))) acc2)]
  acc3))

(defn parse-require [^String src]
  (let [empty {:refer {} :as {} :rename {}}
   bare (reduce (fn [acc f] (if (= "require" (head-sym f)) (let [kids (ordered-children f)]
  (merge-import-opts acc (sym-val (nth kids 1 nil)) (drop 2 kids))) acc)) empty (forms-of src))]
  (let [nf (ns-form src)]
  (if nf (let [reqs (some (fn [c] (if (and (= "list" (kind-of c)) (= ":require" (sym-val (first (ordered-children c))))) (do
  c))) (ordered-children nf))]
  (if reqs (reduce (fn [acc spec] (if (not (brackets? spec)) acc (let [kids (rest (ordered-children spec))]
  (merge-import-opts acc (sym-val (first kids)) (rest kids))))) bare (rest (ordered-children reqs))) bare)) bare))))

(defn module-exports [^String src]
  (into {} (keep (fn [f] (if (= "js/export" (head-sym f)) (do
  (let [d (second (ordered-children f))]
  (cond
  (contains? VALUE-DEFS (head-sym d)) (let [nl (second (ordered-children d))]
  [(sym-val nl) nl])
  (sym-val d) [(sym-val d) d]
  :else nil))))) (forms-of src))))

(defn type-name-leaf [d]
  (let [nl0 (second (ordered-children d))]
  (if (= "list" (kind-of nl0)) (first (ordered-children nl0)) nl0)))

(defn module-types [^String src]
  (let [defs (filterv (fn [f] (contains? TYPE-DEFS (head-sym (unwrap-def f)))) (forms-of src))
   names (into {} (keep (fn [f] (let [nl (type-name-leaf (unwrap-def f))]
  (if (sym-val nl) (do
  [(sym-val nl) nl])))) defs))
   variants (into {} (mapcat (fn [f] (let [d (unwrap-def f)]
  (if (= "defunion" (head-sym d)) (do
  (keep (fn [v] (cond
  (= "list" (kind-of v)) (let [vn (first (ordered-children v))]
  (if (sym-val vn) (do
  [(sym-val vn) vn])))
  (sym-val v) [(sym-val v) v]
  :else nil)) (drop 2 (ordered-children d))))))) defs))]
  (merge variants names)))

(defn module-accessors [^String src]
  (into {} (mapcat (fn [f] (let [d (unwrap-def f)]
  (if (contains? #{"defrecord" "deftype"} (head-sym d)) (do
  (let [nl (type-name-leaf d)
   fb (first (filterv (fn [c] (brackets? c)) (drop 2 (ordered-children d))))]
  (if (and (sym-val nl) fb) (do
  (let [pfx (str/lower-case (sym-val nl))]
  (mapv (fn [fld] [(str pfx "-" fld) [nl fld]]) (keep sym-val (param-binds fb))))))))))) (forms-of src))))

(defn def-binding [^String src ^String nm]
  (or (get (file-modframe src) nm) (get (file-typeframe src) nm)))

(defn make-xresolve [^String src]
  (let [parsed (parse-require src)
   refer (:refer parsed)
   as (:as parsed)
   rename (:rename parsed)
   xport (fn [m n] (or (get-in global-exports [m n]) (get-in global-type-exports [m n])))
   xacc (fn [m n] (get-in global-accessor-exports [m n]))]
  (fn [nm] (cond
  (get refer nm) (let [m (get refer nm)]
  (let [t (xport m nm)]
  (if t {:target t :mode :tracking} (let [a (xacc m nm)]
  (if a (do
  {:target (first a) :mode :tracking :accessor (nth a 1)}))))))
  (get rename nm) (let [rr (get rename nm)
   m (first rr)
   sn (nth rr 1)]
  {:target (xport m sn) :mode :fixed})
  (str/includes? nm "/") (let [parts (str/split nm #"/")
   al (first parts)
   pn (str/join "/" (rest parts))
   m (or (get as al) (if (some (fn [tab] (contains? tab al)) [global-exports global-type-exports global-accessor-exports]) (do
  al)))]
  (if m (do
  (let [t (xport m pn)]
  (if t {:target t :mode :qual :alias al} (let [a (xacc m pn)]
  (if a (do
  {:target (first a) :mode :qual :alias al :accessor (nth a 1)}))))))))
  :else nil))))

(defn cbind! [L target]
  (c/claim! ctx L REFERS target tx)
  (swap! n-comment (fn [x] (+ x 1))))

(defn resolve-comment! [e ^String src]
  (doseq [seg (ordered-segs e)
   :when (= "symbol" (kind-of seg))]
  (let [nm (sym-val seg)]
  (if (some? nm) (do
  (let [b (or (def-binding src nm) (:target (*xresolve* nm)))]
  (if b (do
  (cbind! seg b)))))))))

(defn walk-comments! [^String src]
  (let [es (deref file->ents)]
  (doseq [e (get es src)
   :when (= "comment" (kind-of e))]
  (resolve-comment! e src))))

(defn run-resolution-over! [walk-srcs]
  (doseq [src walk-srcs]
  (binding [*xresolve* (make-xresolve src)
   *tresolve* (fn [nm] (let [tf file-typeframe]
  (get (get tf src) nm)))
   *aresolve* (fn [nm] (let [af file-accessors]
  (get (get af src) nm)))]
  (let [forms (forms-of src)
   fmf file-modframe]
  (swap! walked-modules (fn [s] (conj s src)))
  (swap! n-forms-walked (fn [x] (+ x (count forms))))
  (walk-all! forms [(get fmf src)]))
  (walk-comments! src))))

(defn run-resolution! []
  (let [s srcs]
  (run-resolution-over! s)))

(defn resolve-edn!
  ([edn-paths]
    (resolve-edn! edn-paths (fn [] nil)))
  ([edn-paths body]
    (let [store (c/new-store)
   t (c/begin-tx! store "resolve")
   sup (c/value! store "supersedes")]
  (c/set-supersedes-pred! store sup)
  (binding [ctx store
   tx t
   SUP sup
   file->ents (atom {})
   Vp (c/value! store "v")
   KIND (c/value! store "kind")
   REFERS (c/value! store "refers_to")
   BOUND (c/value! store "bound_to")
   FIXED (c/value! store "keep_spelling")
   QUAL (c/value! store "qualifier")
   CTOR (c/value! store "ctor_prefix")
   ACC (c/value! store "accessor_field")
   n-resolved (atom 0)
   n-unresolved (atom 0)
   n-xmod (atom 0)
   n-type (atom 0)
   n-comment (atom 0)
   n-forms-walked (atom 0)
   walked-modules (atom #{})
   srcs []
   file-modframe {}
   file-typeframe {}
   file-accessors {}
   global-exports {}
   global-type-exports {}
   global-accessor-exports {}]
  (set! srcs (mapv (fn [p] (load-edn! p)) edn-paths))
  (set! file-modframe (into {} (map (fn [s] [s (module-defs s)]) srcs)))
  (set! file-typeframe (into {} (map (fn [s] [s (module-types s)]) srcs)))
  (set! file-accessors (into {} (map (fn [s] [s (module-accessors s)]) srcs)))
  (set! global-exports (into {} (map (fn [s] [(module-name s) (let [e (module-exports s)]
  (if (seq e) e (module-defs s)))]) (filter (fn [s] (module-name s)) srcs))))
  (set! global-type-exports (into {} (map (fn [s] [(module-name s) (module-types s)]) (filter (fn [s] (module-name s)) srcs))))
  (set! global-accessor-exports (into {} (map (fn [s] [(module-name s) (module-accessors s)]) (filter (fn [s] (module-name s)) srcs))))
  (run-resolution!)
  (body)))))

(defn name->module [nm]
  (if (string? nm) (do
  (let [g (re-matches #"@([^#]+)#\d+" nm)]
  (if g (do
  (nth g 1)))))))

(defn corpus-from-store! []
  (let [t0 (rt/nano-time)
   NAME (c/value-id ctx "name")
   groups (cond
  (some? *corpus-cache*) *corpus-cache*
  (some? NAME) (reduce (fn [acc cid] (let [nm (c/literal ctx (:r (c/claim-of ctx cid)))
   m (name->module nm)]
  (if (some? m) (update acc m (fn [o] (conj (or o []) (:l (c/claim-of ctx cid))))) acc))) {} (c/by-p ctx NAME))
  :else {})
   t-groups (rt/nano-time)]
  (reset! file->ents groups)
  (set! srcs (vec (keys groups)))
  (let [frame-srcs (if (some? *corpus-scope*) (filterv (fn [s] (boolean (*corpus-scope* s))) srcs) srcs)]
  (set! file-modframe (into {} (map (fn [s] [s (module-defs s)]) frame-srcs)))
  (set! file-typeframe (into {} (map (fn [s] [s (module-types s)]) frame-srcs)))
  (set! file-accessors (into {} (map (fn [s] [s (module-accessors s)]) frame-srcs))))
  (if (not (some? *corpus-scope*)) (do
  (set! global-exports (into {} (map (fn [s] [(module-name s) (let [e (module-exports s)]
  (if (seq e) e (module-defs s)))]) (filterv (fn [s] (some? (module-name s))) srcs))))
  (set! global-type-exports (into {} (map (fn [s] [(module-name s) (module-types s)]) (filterv (fn [s] (some? (module-name s))) srcs))))
  (set! global-accessor-exports (into {} (map (fn [s] [(module-name s) (module-accessors s)]) (filterv (fn [s] (some? (module-name s))) srcs))))))
  (if (= "1" (rt/getenv "FRAM_PROF")) (do
  (let [s srcs]
  (rt/println-err! (rt/format-str "  corpus-from-store!: groups=%.1fms frames+exports=%.1fms cached=%s nsrcs=%d scoped=%s" [(/ (- t-groups t0) 1000000.0) (/ (- (rt/nano-time) t-groups) 1000000.0) (some? *corpus-cache*) (count s) (boolean *corpus-scope*)])))))))

(defn module-export-set [^String src]
  (let [v (module-exports src)
   vexp (if (seq v) v (module-defs src))]
  (into #{} (concat (keys vexp) (keys (module-types src)) (keys (module-accessors src))))))

(defn module-imports [^String src]
  (let [pr (parse-require src)
   refer (:refer pr)
   as (:as pr)
   rename (:rename pr)]
  (into #{} (concat (vals refer) (vals as) (map (fn [x] (first x)) (vals rename))))))

(defn import-graph []
  (into {} (map (fn [s] [(module-name s) (module-imports s)]) (filterv (fn [s] (some? (module-name s))) srcs))))

(defn ^Boolean module-has-macro? [^String src]
  (boolean (some (fn [f] (= "defmacro" (head-sym (unwrap-def f)))) (forms-of src))))

(defn resolve-warm-store!
  ([store]
    (resolve-warm-store! store (fn [] nil)))
  ([store body]
    (let [t (c/begin-tx! store "resolve-warm")
   sup (or (c/value-id store "supersedes") (c/value! store "supersedes"))]
  (c/set-supersedes-pred! store sup)
  (binding [ctx store
   tx t
   SUP sup
   file->ents (atom {})
   Vp (c/value! store "v")
   KIND (c/value! store "kind")
   REFERS (c/value! store "refers_to")
   BOUND (c/value! store "bound_to")
   FIXED (c/value! store "keep_spelling")
   QUAL (c/value! store "qualifier")
   CTOR (c/value! store "ctor_prefix")
   ACC (c/value! store "accessor_field")
   n-resolved (atom 0)
   n-unresolved (atom 0)
   n-xmod (atom 0)
   n-type (atom 0)
   n-comment (atom 0)
   n-forms-walked (atom 0)
   walked-modules (atom #{})
   srcs []
   file-modframe {}
   file-typeframe {}
   file-accessors {}
   global-exports {}
   global-type-exports {}
   global-accessor-exports {}]
  (corpus-from-store!)
  (if *resolve-walk?* (do
  (run-resolution!)))
  (body)))))

(defn resolve-modules!
  ([store module-set]
    (resolve-modules! store module-set (fn [] nil)))
  ([store module-set body]
    (let [t (c/begin-tx! store "resolve-scoped")
   sup (or (c/value-id store "supersedes") (c/value! store "supersedes"))]
  (c/set-supersedes-pred! store sup)
  (binding [ctx store
   tx t
   SUP sup
   file->ents (atom {})
   Vp (c/value! store "v")
   KIND (c/value! store "kind")
   REFERS (c/value! store "refers_to")
   BOUND (c/value! store "bound_to")
   FIXED (c/value! store "keep_spelling")
   QUAL (c/value! store "qualifier")
   CTOR (c/value! store "ctor_prefix")
   ACC (c/value! store "accessor_field")
   n-resolved (atom 0)
   n-unresolved (atom 0)
   n-xmod (atom 0)
   n-type (atom 0)
   n-comment (atom 0)
   n-forms-walked (atom 0)
   walked-modules (atom #{})
   srcs []
   file-modframe {}
   file-typeframe {}
   file-accessors {}
   global-exports {}
   global-type-exports {}
   global-accessor-exports {}]
  (corpus-from-store!)
  (run-resolution-over! (filterv (fn [s] (contains? module-set s)) srcs))
  (body)))))

(defn ultimate [B]
  (loop [b B
   n 0]
  (let [t (refers-target b)]
  (if (and (some? t) (< n 64)) (recur t (+ n 1)) b))))

(defn binding-name [B]
  (sym-val (ultimate B)))

(defn register! [^String src e]
  (swap! file->ents update src (fn [o] (conj (or o []) e)))
  e)

(defn mint-leaf! [^String src ^String kind ^String v]
  (let [e (register! src (c/entity! ctx))]
  (c/claim! ctx e KIND (c/value! ctx kind) tx)
  (c/claim! ctx e Vp (c/value! ctx v) tx)
  e))

(defn mint-datum! [^String src d]
  (cond
  (nil? d) (let [e (register! src (c/entity! ctx))]
  (c/claim! ctx e KIND (c/value! ctx "nil") tx)
  e)
  (symbol? d) (mint-leaf! src "symbol" (str d))
  (keyword? d) (mint-leaf! src "keyword" (subs (str d) 1))
  (string? d) (mint-leaf! src "string" d)
  (boolean? d) (mint-leaf! src "bool" (if d "true" "false"))
  (char? d) (mint-leaf! src "char" (str d))
  (number? d) (mint-leaf! src "number" (str d))
  (or (list? d) (seq? d) (vector? d) (map? d)) (let [head (cond
  (vector? d) [(symbol "#%brackets")]
  (map? d) [(symbol "#%map")]
  :else [])
   elems (concat head (if (map? d) (apply concat (seq d)) (seq d)))
   e (register! src (c/entity! ctx))]
  (c/claim! ctx e KIND (c/value! ctx "list") tx)
  (doseq [[i x] (map-indexed vector elems)]
  (c/claim! ctx e (c/value! ctx (str "f" i)) (mint-datum! src x) tx))
  e)
  :else (mint-leaf! src "other" (pr-str d))))

(defn fN-claims [parent]
  (->> (c/by-l ctx parent) (keep (fn [cid] (let [p (c/literal ctx (c/claim-p ctx cid))]
  (if (and (string? p) (some? (re-matches #"f\d+" p))) (do
  [(parse-long (subs p 1)) cid (c/claim-r ctx cid)]))))) (sort-by first)))

(defn retire-claim! [oldc]
  (c/claim! ctx (c/entity! ctx) SUP oldc tx))

(defn wrapper-of [^String src]
  (let [m (deref file->ents)]
  (some (fn [e] (if (= "beagle-file" (head-sym e)) (do
  e))) (get m src))))

(defn structural-kids [n]
  (->> (c/by-l ctx n) (keep (fn [cid] (let [p (c/literal ctx (c/claim-p ctx cid))
   r (c/claim-r ctx cid)]
  (if (and (integer? r) (string? p) (or (ord-pos? p) (some? (re-matches #"seg\d+" p)) (some? (re-matches #"comment\d+" p)) (= p "tail"))) (do
  r)))))))

(defn descendants [root]
  (loop [seen #{}
   stack [root]]
  (if (empty? stack) seen (let [n (peek stack)]
  (if (contains? seen n) (recur seen (vec (pop stack))) (recur (conj seen n) (into (vec (pop stack)) (structural-kids n))))))))

(defn form-for-victim [^String src victim]
  (let [w (wrapper-of src)]
  (if (some? w) (some (fn [f] (let [nl0 (second (ordered-children (unwrap-def f)))]
  (if (some? nl0) (let [nl (if (= "list" (kind-of nl0)) (let [g0 (first (ordered-children nl0))]
  (if (some? g0) g0 nl0)) nl0)]
  (if (= victim nl) f nil)) nil))) (rest (ordered-children w))) nil)))

(defn extract-file! [^String src ^String out-path]
  (let [ents (or (get (deref file->ents) src) [])
   ds *deleted-subtree*
   wrap (wrapper-of src)
   root (if (empty? *deleted-forms*) (do
  (wrapper-of src)))
   live (if root (do
  (descendants (or root 0))))
   keep? (fn [e] (or (nil? live) (contains? (or live #{}) e)))
   emit-line (fn [e cid] (let [p (or (c/claim-p ctx cid) 0)
   r (or (c/claim-r ctx cid) 0)
   ps (c/literal ctx p)]
  (cond
  (and (= e (or wrap 0)) (string? ps) (ord-pos? ps) (not= ps "f0")) nil
  (contains? #{"supersedes" "refers_to" "keep_spelling" "qualifier" "ctor_prefix" "accessor_field"} ps) nil
  (and (= ps "v") (refers-target e)) (let [D (or (refers-target e) 0)
   fixed? (seq (c/by-lp ctx e FIXED))
   qual (pred-val e "qualifier")
   cpfx (pred-val e "ctor_prefix")
   afield (pred-val e "accessor_field")
   nm0 (binding-name D)
   nm (cond
  cpfx (str cpfx nm0)
  afield (str (str/lower-case (str nm0)) "-" afield)
  :else (str nm0))]
  (str "[" e " \"v\" " (pr-str (cond
  fixed? (c/literal ctx r)
  qual (str qual "/" nm)
  :else nm)) "]"))
  (c/value-object? ctx r) (str "[" e " " (pr-str ps) " " (pr-str (c/literal ctx r)) "]")
  :else (str "[" e " " (pr-str ps) " " r "]"))))
   node-lines (reduce (fn [acc e] (if (and (not (contains? ds e)) (keep? e)) (reduce (fn [a cid] (let [ln (emit-line e cid)]
  (if ln (conj a ln) a))) acc (c/by-l ctx e)) acc)) [] ents)
   form-lines (if wrap (vec (keep-indexed (fn [i f] (str "[" (or wrap 0) " \"f" (+ i 1) "\" " f "]")) (filterv (fn [f] (not (contains? *deleted-forms* f))) (rest (ordered-children (or wrap 0)))))) [])
   all-lines (vec (concat (cons (str "@file " src) node-lines) form-lines))]
  (rt/spit-file out-path (str (str/join "\n" all-lines) "\n"))))

(defn ^String out-path [^String src]
  (str (or *resolve-out* (rt/getenv "RESOLVE_OUT") "/tmp") "/resolved-" (last (str/split src #"/")) ".edn"))

(defn ^Boolean renders-as-tracked-name? [node]
  (and (not (seq (c/by-lp ctx node FIXED))) (not (pred-val node "qualifier"))))

(defn capture-refs [node scope B ^String newnm]
  (cond
  (= (kind-of node) "symbol") (let [t (refers-target node)]
  (if (and t (= B (ultimate (or t 0))) (renders-as-tracked-name? node) (some (fn [m] (get m newnm)) scope)) [node] []))
  (= (kind-of node) "list") (let [kids (ordered-children node)
   h (head-sym node)]
  (cond
  (contains? PARAM-FORMS (or h "")) (let [after-name (if (contains? #{"defn" "defn-" "defmacro"} (or h "")) (vec (drop 2 kids)) (vec (rest kids)))
   cap-arity (fn [forms] (let [pv (first (filterv (fn [x] (brackets? x)) forms))
   frame (frame-of (if pv (param-binds (or pv 0)) []))
   or-vals (if pv (vec (mapcat (fn [x] (collect-or-vals x)) (rest (ordered-children (or pv 0))))) [])
   body (loop [xs (vec (rest (drop-while (fn [x] (not (brackets? x))) forms)))]
  (if (contains? #{":-" ":" ":raises"} (or (sym-val (first xs)) "")) (recur (vec (drop 2 xs))) xs))]
  (vec (concat (mapcat (fn [x] (capture-refs x scope B newnm)) or-vals) (mapcat (fn [x] (capture-refs x (cons frame scope) B newnm)) body)))))]
  (if (some (fn [x] (brackets? x)) after-name) (cap-arity after-name) (vec (mapcat (fn [a] (if (and (= "list" (kind-of a)) (brackets? (or (first (ordered-children a)) 0))) (cap-arity (ordered-children a)) [])) after-name))))
  (contains? LET-FORMS (or h "")) (let [bracket (second kids)
   pairs (if (and bracket (brackets? (or bracket 0))) (let-bind-pairs (or bracket 0)) [])
   acc (reduce (fn [a pr] (let [sc (first a)
   caps (or (second a) [])
   bsyms (or (first pr) [])
   vnode (second pr)
   orvals (or (nth pr 2 nil) [])]
  [(cons (frame-of bsyms) sc) (into caps (vec (concat (mapcat (fn [x] (capture-refs x sc B newnm)) orvals) (if vnode (capture-refs (or vnode 0) sc B newnm) []))))])) [scope []] pairs)
   final (first acc)
   vcaps (or (second acc) [])]
  (vec (concat vcaps (mapcat (fn [x] (capture-refs x final B newnm)) (drop 2 kids)))))
  (contains? FOR-FORMS (or h "")) (let [bracket (second kids)
   entries (if (and bracket (brackets? (or bracket 0))) (for-bind-pairs (or bracket 0)) [])
   acc (reduce (fn [a e] (let [sc (first a)
   caps (or (second a) [])]
  (if (= :expr (first e)) [sc (into caps (capture-refs (or (second e) 0) sc B newnm))] (let [bsyms (or (nth e 1 nil) [])
   vnode (nth e 2 nil)
   orvals (or (nth e 3 nil) [])]
  [(cons (frame-of bsyms) sc) (into caps (vec (concat (mapcat (fn [x] (capture-refs x sc B newnm)) orvals) (if vnode (capture-refs (or vnode 0) sc B newnm) []))))])))) [scope []] entries)
   final (first acc)
   vcaps (or (second acc) [])]
  (vec (concat vcaps (mapcat (fn [x] (capture-refs x final B newnm)) (drop 2 kids)))))
  (contains? MATCH-FORMS (or h "")) (let [kids (ordered-children node)]
  (vec (concat (capture-refs (or (second kids) 0) scope B newnm) (mapcat (fn [clause] (if (brackets? clause) (let [cc (vec (rest (ordered-children clause)))
   pat (or (first cc) 0)
   body (vec (rest cc))
   frame (frame-of (match-pat-binds pat))]
  (vec (concat (capture-refs pat scope B newnm) (mapcat (fn [x] (capture-refs x (cons frame scope) B newnm)) body)))) [])) (drop 2 kids)))))
  (= h "letfn") (let [bracket (second kids)
   fnlists (if (and bracket (brackets? (or bracket 0))) (filterv (fn [x] (= "list" (kind-of x))) (rest (ordered-children (or bracket 0)))) [])
   frame (frame-of (vec (keep (fn [x] (first (ordered-children x))) fnlists)))
   bodyscope (cons frame scope)
   cap-arity (fn [forms] (let [pv (first (filterv (fn [x] (brackets? x)) forms))
   pframe (frame-of (if pv (param-binds (or pv 0)) []))
   fbody (loop [xs (vec (rest (drop-while (fn [x] (not (brackets? x))) forms)))]
  (if (contains? #{":-" ":" ":raises"} (or (sym-val (first xs)) "")) (recur (vec (drop 2 xs))) xs))]
  (vec (mapcat (fn [x] (capture-refs x (cons pframe bodyscope) B newnm)) fbody))))]
  (vec (concat (mapcat (fn [fl] (cap-arity (vec (rest (ordered-children fl))))) fnlists) (mapcat (fn [x] (capture-refs x bodyscope B newnm)) (drop 2 kids)))))
  (contains? #{"extend-type" "extend-protocol"} (or h "")) (vec (mapcat (fn [c] (if (= "list" (kind-of c)) (let [ic (ordered-children c)
   pv (first (filterv (fn [x] (brackets? x)) (rest ic)))
   pframe (frame-of (if pv (param-binds (or pv 0)) []))
   fbody (loop [xs (vec (rest (drop-while (fn [x] (not (brackets? x))) (rest ic))))]
  (if (contains? #{":-" ":" ":raises"} (or (sym-val (first xs)) "")) (recur (vec (drop 2 xs))) xs))]
  (vec (concat (capture-refs (or (first ic) 0) scope B newnm) (mapcat (fn [x] (capture-refs x (cons pframe scope) B newnm)) fbody)))) (capture-refs c scope B newnm))) (rest kids)))
  (= h "as->") (let [init (nth kids 1 nil)
   nm (nth kids 2 nil)
   frame (frame-of (if (sym-val (or nm 0)) [(or nm 0)] []))]
  (vec (concat (if init (capture-refs (or init 0) scope B newnm) []) (mapcat (fn [x] (capture-refs x (cons frame scope) B newnm)) (drop 3 kids)))))
  :else (vec (mapcat (fn [x] (capture-refs x scope B newnm)) kids))))
  :else []))

(defn re-resolve! []
  (let [s srcs
   modframe (into {} (map (fn [src] [src (module-defs src)]) s))
   typeframe (into {} (map (fn [src] [src (module-types src)]) s))
   accessors (into {} (map (fn [src] [src (module-accessors src)]) s))]
  (doseq [src s]
  (binding [*xresolve* (make-xresolve src)
   *tresolve* (fn [nm] (get (get typeframe src) nm))
   *aresolve* (fn [nm] (get (get accessors src) nm))]
  (walk-all! (forms-of src) (list (get modframe src)))
  (walk-comments! src)))))

(defn author-emit! [^String op ^String detail]
  (let [s srcs]
  (doseq [src s]
  (extract-file! src (out-path src)))
  (rt/println-err! (str "================ authoring: " op " ================"))
  (rt/println-err! detail)
  (doseq [src s]
  (rt/println-err! (str "projected -> " (out-path src) "   <- " src)))))

(defn- emit-srcs []
  (or *project-srcs* srcs))

(defn author-emit-scoped! [^String op ^String detail]
  (if (not *capture-only?*) (do
  (doseq [src (emit-srcs)]
  (extract-file! src (out-path src)))
  (rt/println-err! (str "================ authoring: " op " ================"))
  (rt/println-err! detail)
  (doseq [src (emit-srcs)]
  (rt/println-err! (str "projected -> " (out-path src) "   <- " src))))))

(defn verb-rename! [^String old ^String new ^String target]
  (let [target-srcs (filterv (fn [s] (str/includes? s target)) srcs)
   edits (atom 0)]
  (doseq [src target-srcs]
  (if (and (def-binding src old) (def-binding src new)) (do
  (rt/println-err! (str "REJECTED — `" new "` already names a binding in " src " (rename-doesn't-collide; no claims mutated)."))
  (*reject!* 3))))
  (doseq [src target-srcs]
  (if (and (some? (get (file-typeframe src) old)) (nil? (re-find #"^[A-Z]" new))) (do
  (rt/println-err! (str "REJECTED — `" new "` is not a valid (Capitalized) type name " "(beagle type-name shape; no claims mutated)."))
  (*reject!* 3))))
  (doseq [src target-srcs]
  (let [B (def-binding src old)]
  (if (some? B) (do
  (let [caps (vec (mapcat (fn [s] (mapcat (fn [f] (capture-refs f (list (get file-modframe s)) B new)) (forms-of s))) srcs))]
  (if (seq caps) (do
  (rt/println-err! (str "REJECTED — renaming `" old "` -> `" new "` would be CAPTURED by a local `" new "` in scope at " (count caps) " reference(s) (no-capture; no claims mutated)."))
  (*reject!* 4))))))))
  (let [target-mods (into #{} (keep (fn [src] (module-name src)) target-srcs))]
  (doseq [src srcs
   :when (not (some (fn [s] (= s src)) target-srcs))]
  (let [pr (parse-require src)
   refer (:refer pr)
   rename (:rename pr)]
  (if (and (contains? target-mods (get refer old)) (or (def-binding src new) (get refer new) (get rename new))) (do
  (rt/println-err! (str "REJECTED — renaming `" old "` -> `" new "` would DUPLICATE a binding in consumer " src " (it already binds `" new "`; no-import-collision; no claims mutated)."))
  (*reject!* 3))))))
  (doseq [src target-srcs]
  (let [B (def-binding src old)]
  (if (some? B) (do
  (let [oldc (first (filterv (fn [cid] (= Vp (c/claim-p ctx cid))) (c/by-l ctx B)))
   nc (c/claim! ctx B Vp (c/value! ctx new) tx)]
  (c/claim! ctx nc SUP oldc tx)
  (swap! edits (fn [x] (+ x 1))))))))
  (if (zero? (let [e (deref edits)]
  e)) (do
  (rt/println-err! (str "REJECTED — no binding named `" old "` found in \"" target "\" (nothing to rename; no claims mutated)."))
  (*reject!* 5)))
  (if (not *capture-only?*) (do
  (doseq [src (emit-srcs)]
  (extract-file! src (out-path src)))
  (rt/println-err! "================ Turtle #5 — O(1) shadow-correct rename ================")
  (rt/println-err! (str "edit: rename def `" old "` -> `" new "` in \"" target "\""))
  (rt/println-err! (str "CLAIMS EDITED: " (let [e (deref edits)]
  e) "  (just the definition's name; references follow refers_to)"))
  (doseq [src (emit-srcs)]
  (rt/println-err! (str "projected -> " (out-path src) "   <- " src)))))))

(defn wrap-forms [parent]
  (vec (sort-by (fn [e] (first e)) ord-cmp (keep (fn [cid] (let [p (c/claim-p ctx cid)
   k (ord-parse (c/literal ctx p))]
  (if k (do
  [k cid (c/claim-r ctx cid)])))) (c/by-l ctx parent)))))

(defn- ^String ord-tie []
  (if *capture-only?* "PENDING" "0"))

(defn verb-upsert-form! [^String scope datum]
  (let [target-srcs (filterv (fn [s] (str/includes? s scope)) srcs)]
  (if (not= 1 (count target-srcs)) (do
  (rt/println-err! (str "REJECTED — scope \"" scope "\" matches " (count target-srcs) " source files; upsert-form needs exactly one (no claims mutated)."))
  (*reject!* 3)))
  (if (and (seq? datum) (not (contains? VALUE-DEFS (str (first datum))))) (do
  (rt/println-err! (str "REJECTED — upsert-form spec head `" (first datum) "` is not a value def (def/defn/...); no claims mutated."))
  (*reject!* 3)))
  (let [src (first target-srcs)
   wrap (wrapper-of src)
   forms (wrap-forms wrap)
   new-name (if (and (seq? datum) (contains? VALUE-DEFS (str (first datum)))) (do
  (str (second datum))))
   existing (if new-name (do
  (def-binding src new-name)))
   victim-form (if existing (do
  (form-for-victim src existing)))
   victim-entry (if victim-form (do
  (some (fn [e] (let [r (nth e 2)]
  (if (= r victim-form) (do
  e)))) forms)))
   new-root (mint-datum! src datum)]
  (if victim-entry (let [k (nth victim-entry 0)
   cid (nth victim-entry 1)]
  (retire-claim! cid)
  (c/claim! ctx wrap (c/value! ctx (ord-str (:path k) (ord-tie))) new-root tx)) (let [last-path (if (seq forms) (do
  (let [pk (first (peek forms))]
  (:path pk))))]
  (c/claim! ctx wrap (c/value! ctx (ord-str (ord-append (or last-path [])) (ord-tie))) new-root tx)))
  (if (not *capture-only?*) (do
  (re-resolve!)))
  (author-emit-scoped! "upsert-form" (str (if victim-entry "replaced" "added") " top-level def `" new-name "` in \"" scope "\" (1 form minted as claims; refs resolved via refers_to)")))))

(defn verb-insert-form! [^String scope ^String after-name datum]
  (let [target-srcs (filterv (fn [s] (str/includes? s scope)) srcs)]
  (if (not= 1 (count target-srcs)) (do
  (rt/println-err! (str "REJECTED — insert-form scope \"" scope "\" matches " (count target-srcs) " files (need 1)."))
  (*reject!* 3)))
  (if (and (seq? datum) (not (contains? VALUE-DEFS (str (first datum))))) (do
  (rt/println-err! (str "REJECTED — insert-form head `" (first datum) "` not a value def."))
  (*reject!* 3)))
  (let [src (first target-srcs)
   wrap (wrapper-of src)
   forms (wrap-forms wrap)
   anchor-bind (def-binding src after-name)
   anchor-form (if anchor-bind (do
  (form-for-victim src anchor-bind)))
   idx (if anchor-form (do
  (first (keep-indexed (fn [i e] (let [r (nth e 2)]
  (if (= r anchor-form) (do
  i)))) forms))))]
  (if (nil? idx) (do
  (rt/println-err! (str "REJECTED — insert-form anchor `" after-name "` not found in \"" scope "\"."))
  (*reject!* 3)))
  (if (some? idx) (do
  (let [i idx
   anchor-path (let [k (first (nth forms i))]
  (:path k))
   next-path (if (< (+ i 1) (count forms)) (do
  (let [k (first (nth forms (+ i 1)))]
  (:path k))))
   new-root (mint-datum! src datum)]
  (c/claim! ctx wrap (c/value! ctx (ord-str (ord-between anchor-path next-path) (ord-tie))) new-root tx)
  (if (not *capture-only?*) (do
  (re-resolve!)))
  (author-emit-scoped! "insert-form" (str "inserted def after `" after-name "` in \"" scope "\" (CRDT mid-insert)"))))))))

(defn- next-comment-idx [form]
  (+ 1 (reduce (fn [acc n] (max acc n)) -1 (keep (fn [cid] (let [p (c/literal ctx (c/claim-p ctx cid))]
  (if (and (string? p) (re-matches #"comment\d+" p)) (do
  (parse-long (subs p 7)))))) (c/by-l ctx form)))))

(defn verb-insert-comment! [^String scope ^String anchor-name ^String text ^String placement]
  (let [target-srcs (filterv (fn [s] (str/includes? s scope)) srcs)]
  (if (not= 1 (count target-srcs)) (do
  (rt/println-err! (str "REJECTED — insert-comment scope \"" scope "\" matches " (count target-srcs) " files (need 1)."))
  (*reject!* 3)))
  (if (str/blank? text) (do
  (rt/println-err! "REJECTED — insert-comment needs non-empty --text; no claims mutated.")
  (*reject!* 3)))
  (let [src (first target-srcs)
   plc (if (contains? #{"leading" "trailing"} placement) placement "leading")
   lex (if (str/starts-with? (str/triml text) ";") text (str ";; " text))
   anchor-bind (def-binding src anchor-name)
   anchor-form (if anchor-bind (do
  (form-for-victim src anchor-bind)))]
  (if (nil? anchor-form) (do
  (rt/println-err! (str "REJECTED — insert-comment anchor `" anchor-name "` not found in \"" scope "\"."))
  (*reject!* 3)))
  (if (some? anchor-form) (do
  (let [af anchor-form
   k (next-comment-idx af)
   cnode (register! src (c/entity! ctx))
   seg (register! src (c/entity! ctx))]
  (c/claim! ctx cnode KIND (c/value! ctx "comment") tx)
  (c/claim! ctx cnode (c/value! ctx "style") (c/value! ctx "line") tx)
  (c/claim! ctx cnode (c/value! ctx "placement") (c/value! ctx plc) tx)
  (c/claim! ctx seg KIND (c/value! ctx "text") tx)
  (c/claim! ctx seg Vp (c/value! ctx lex) tx)
  (c/claim! ctx cnode (c/value! ctx "seg0") seg tx)
  (c/claim! ctx af (c/value! ctx (str "comment" k)) cnode tx)
  (if (not *capture-only?*) (do
  (re-resolve!)))
  (author-emit-scoped! "insert-comment" (str "added " plc " comment on `" anchor-name "` in \"" scope "\" (comment" k "; 1 text seg minted)"))))))))

(defn verb-set-body! [^String name ^String scope datum]
  (let [target-srcs (filterv (fn [s] (str/includes? s scope)) srcs)]
  (if (not= 1 (count target-srcs)) (do
  (rt/println-err! (str "REJECTED — scope \"" scope "\" matches " (count target-srcs) " source files; set-body needs exactly one (no claims mutated)."))
  (*reject!* 3)))
  (let [src (first target-srcs)
   B (def-binding src name)
   form (if (some? B) (do
  (form-for-victim src B)))
   d (if (some? form) (do
  (unwrap-def form)))
   dhead (if (some? d) (do
  (head-sym d)))]
  (if (or (nil? form) (not (contains? PARAM-FORMS dhead))) (do
  (rt/println-err! (str "REJECTED — `" name "` is not a defn with a body in \"" scope "\" (set-body needs a defn; no claims mutated)."))
  (*reject!* 5)))
  (if (some? d) (do
  (let [dd d
   kids (fN-claims dd)
   bracket-n (some (fn [e] (let [n (nth e 0)
   r (nth e 2)]
  (if (brackets? r) (do
  n)))) kids)
   bn (or bracket-n 0)
   ret? (some (fn [e] (let [n (nth e 0)
   r (nth e 2)]
  (if (and (= n (+ bn 1)) (contains? TYPE-COLON (sym-val r))) (do
  n)))) kids)
   body-start (+ bn (if ret? 3 1))
   body-slots (filterv (fn [e] (let [n (nth e 0)]
  (>= n body-start))) kids)
   new-root (mint-datum! src datum)]
  (if (empty? body-slots) (do
  (rt/println-err! (str "REJECTED — `" name "` has no body fN edges to replace; no claims mutated."))
  (*reject!* 5)))
  (doseq [e body-slots]
  (let [cid (nth e 1)]
  (retire-claim! cid)))
  (c/claim! ctx dd (c/value! ctx (str "f" body-start)) new-root tx)
  (if (not *capture-only?*) (do
  (re-resolve!)))
  (author-emit-scoped! "set-body" (str "replaced body of defn `" name "` in \"" scope "\" (" (count body-slots) " body slot(s) superseded; new body minted as claims)"))))))))

(defn run-verb-warm! [store spec]
  (let [module (:module spec)]
  (binding [*resolve-out* (:resolve-out spec)]
  (resolve-warm-store! store (fn [] (binding [*project-srcs* (if module (do
  (let [s srcs
   m module]
  (filter (fn [x] (str/includes? x m)) s))))]
  (cond
  (= (:op spec) "rename") (verb-rename! (:old spec) (:new spec) module)
  (= (:op spec) "upsert-form") (verb-upsert-form! module (:datum spec))
  (= (:op spec) "insert-form") (verb-insert-form! module (:after spec) (:datum spec))
  (= (:op spec) "insert-comment") (verb-insert-comment! module (:after spec) (:text spec) (:placement spec))
  (= (:op spec) "set-body") (verb-set-body! (:name spec) module (:datum spec))
  :else (do
  (rt/println-err! (str "run-verb-warm!: unknown op " (:op spec)))
  (rt/exit! 2)))))))
  module))
;; resolve_cli.clj — the hand-Clojure CLI tail appended to the Beagle-emitted
;; resolver (chartroom/src/resolve.bclj) to form chartroom/src/resolve.clj (ns resolve).
;; The bb/CLI -main + the callgraph mode (datalog + cheshire) live here — no Beagle value.
;; Regenerate resolve.clj: bin/build-resolve.sh

;; ---- CLI driver (Clojure: bb/CLI + callgraph's datalog/cheshire edge) ----
(require (quote [clojure.edn :as edn]) (quote [fram.datalog :as d]) (quote [cheshire.core :as json]))
(def mode (first *command-line-args*))

(def MODES #{"resolve" "rename" "delete" "callgraph" "upsert-form" "set-body"})
(defn -main []
  (let [edn-paths (drop (case mode "resolve" 1 "rename" 4 "delete" 3 "callgraph" 1
                                   "upsert-form" 3 "set-body" 4)
                        *command-line-args*)]
    (resolve-edn!
     edn-paths
     (fn []
(case mode
  "resolve"
  (binding [*out* *err*]
    (println "================ Turtle #5 — lexical resolution pass ================")
    (println (str "references resolved (carry refers_to → a binding node): " @n-resolved
                  "  (" @n-xmod " cross-module, " @n-type " type references)"))
    (println (str "unresolved (builtins / native — correctly NO refers_to): " @n-unresolved))
    (println (str "comment identifier mentions resolved (rename-correct doc comments): " @n-comment))
    ;; write the resolved projection so identity can be checked: with NO rename,
    ;; projecting through refers_to must reproduce the original source exactly.
    (doseq [src srcs] (extract-file! src (out-path src)))
    (doseq [src srcs]
      (println (str "  " (-> src (str/split #"/") last) ": "
                    (count (filter #(and (= "symbol" (kind-of %)) (refers-target %)) (@file->ents src)))
                    " references carry refers_to; projected (identity) -> " (out-path src)))))

  "rename"
  (let [[old new target] (drop 1 *command-line-args*)]
    (verb-rename! old new target))

  "delete"
  (let [[name target] (drop 1 *command-line-args*)
        target-srcs (filter #(str/includes? % target) srcs)
        victims (keep #(def-binding % name) target-srcs)   ; value OR type binding occurrences to delete
        ;; the top-level forms to remove + their whole subtrees (incl. each form's own
        ;; doc-comment AND, for a defunion, its variant-constructor name-leaves). Computed
        ;; FIRST so the orphan check can both exclude refs INSIDE a deleted form and flag
        ;; surviving refs to ANY binding the deletion removes — the union name OR a variant.
        all-forms (set (mapcat (fn [src] (keep #(form-for-victim src %) victims)) srcs))
        subtree (reduce into #{} (map descendants all-forms))
        orphans (for [src srcs, e (@file->ents src)
                      :when (and (= "symbol" (kind-of e)) (refers-target e) (not (subtree e))
                                 (subtree (ultimate (refers-target e))))] e)]   ; ref to a deleted binding
    (when (zero? (count victims))
      (binding [*out* *err*]
        (println (str "REJECTED — no binding named `" name "` found in \"" target "\" (nothing to delete).")))
      (System/exit 5))
    ;; matched a binding but no independently-deletable top-level form (e.g. a defunion
    ;; variant lives nested inside its union) — refuse, don't report a no-op as success.
    (when (empty? all-forms)
      (binding [*out* *err*]
        (println (str "REJECTED — `" name "` is not an independently-deletable top-level form "
                      "(a defunion variant / nested binding); no claims mutated.")))
      (System/exit 5))
    ;; INVARIANT (no-orphaned-refs): refuse if any SURVIVING reference points at a victim.
    (when (pos? (count orphans))
      (binding [*out* *err*]
        (println "================ Turtle #5 — delete + orphaned-reference invariant ================")
        (println (str "REJECTED — " (count orphans) " reference(s) would be ORPHANED (no-orphaned-refs):"))
        (doseq [o (take 5 orphans)] (println (str "  orphan: reference node " o " (`" (sym-val o) "`)"))))
      (System/exit 6))
    ;; SAFE: project each src with the victim forms (and their subtrees) omitted, siblings renumbered.
    (binding [*deleted-forms* all-forms *deleted-subtree* subtree]
      (doseq [src srcs] (extract-file! src (out-path src))))
    (binding [*out* *err*]
      (println "================ Turtle #5 — delete (no-orphaned-refs satisfied) ================")
      (println (str "deleted def `" name "` in \"" target "\": " (count all-forms) " form(s); 0 orphaned refs"))
      (doseq [src srcs] (println (str "projected -> " (out-path src) "   <- " src)))))

  ;; ============================================================================
  ;; AUTHORING VERBS — the GAP closed: a claim operation for novel authoring.
  ;; upsert-form : add a NEW top-level def (append a wrapper fN edge) OR replace an
  ;;               existing top-level def by name (supersede its wrapper fN edge to
  ;;               point at a freshly-minted subtree). The form is given as an EDN
  ;;               datum (the structured edit spec), minted into the SAME store.
  ;; Both reuse extract-file! (the rename/delete render machine) and re-run the
  ;; lexical walk over the post-mint corpus, so a reference in the new code resolves
  ;; via refers_to (scope-correct) exactly like hand-written code — then the recompile
  ;; gate (authoring.sh) is the only acceptance criterion. fail-closed before that.
  ;; ============================================================================
  "upsert-form"
  (let [[scope spec-file] (drop 1 *command-line-args*)
        datum (edn/read-string (slurp spec-file))]
    (verb-upsert-form! scope datum))

  ;; set-body : replace a defn's BODY — supersede every post-params fN edge of the
  ;; named defn and re-wire to a freshly-minted body datum.
  "set-body"
  (let [[name scope body-file] (drop 1 *command-line-args*)
        datum (edn/read-string (slurp body-file))]
    (verb-set-body! name scope datum))

  ;; ============================================================================
  ;; callgraph — the scope-correct call graph + transitive blast radius, derived
  ;; from the SAME refers_to edges the rename/delete engine uses. A "call" is a
  ;; reference in list-HEAD position whose binding (followed transitively) is a
  ;; top-level defn; the caller is its enclosing top-level defn. Because refers_to
  ;; is the converged cross-module/multi-arity/collision-correct resolution, this
  ;; call graph is too — unlike a bare-callname index, it does NOT drop qualified
  ;; (a/f, m/f) cross-module calls. Emits the JSON beagle-cascade consumes.
  ;; ============================================================================
  "callgraph"
  (let [dkey      (fn [src leaf] (str src "#" leaf))
        defn-meta (into {} (for [src srcs, [nm leaf] (file-modframe src)]
                             [leaf {:key (dkey src leaf) :file src
                                    :module (or (module-name src)
                                                (-> src (str/split #"/") last (str/replace #"\.[^.]+$" "")))
                                    :name nm}]))
        defn-set  (set (keys defn-meta))
        ;; ALL resolved reference symbols in a subtree (any position — not just list-head).
        ;; For a BLAST RADIUS ("what must change if I change X"), every reference to a defn is
        ;; a dependency: a head call (f x), a value-pass (mapv f xs), a threaded step (-> x f),
        ;; a `:- T` annotation. Head-only silently under-reports (proven on shipped fram/src).
        call-refs (fn call-refs [node]
                    (if (refers-target node) [node]
                      (when (= "list" (kind-of node)) (mapcat call-refs (ordered-children node)))))
        ;; callers = [caller-defn-leaf, body-node] pairs. A top-level value defn is one caller;
        ;; an extend-type/extend-protocol attributes each impl method's body to that protocol
        ;; method (the impl method-name resolves to it via refers_to) — those bodies were skipped.
        callers (mapcat
                 (fn [form]
                   (let [d (unwrap-def form) h (head-sym d)]
                     (cond
                       (VALUE-DEFS h)
                       (let [cl (second (ordered-children d))] (when (defn-meta cl) [[cl d]]))
                       (#{"extend-type" "extend-protocol"} h)
                       (keep (fn [c] (when (= "list" (kind-of c))
                                       (let [mnode (first (ordered-children c))
                                             cl (when (sym-val mnode) (ultimate (refers-target mnode)))]
                                         (when (and cl (defn-meta cl)) [cl c]))))
                             (rest (ordered-children d))))))
                 (mapcat forms-of srcs))
        edges (vec (distinct
                    (for [[caller-leaf body] callers
                          r (call-refs body)
                          :let [callee (ultimate (refers-target r))]  ; follow refers_to to the bound defn
                          :when (and (defn-set callee) (not= callee caller-leaf))]
                      [(:key (defn-meta caller-leaf)) (:key (defn-meta callee))])))
        ;; transitive blast radius via Fram Datalog: blast(D) = {x | x transitively calls D}
        bctx (c/new-store) btx (c/begin-tx! bctx "code") EDGE (c/value! bctx "calls-defn")
        k->e (volatile! {})
        bent (fn [k] (or (get @k->e k) (let [e (c/entity! bctx)] (vswap! k->e assoc k e) e)))
        _ (doseq [[a b] edges] (c/claim! bctx (bent a) EDGE (bent b) btx))
        e->k (into {} (map (fn [[k v]] [v k]) @k->e))
        db (d/run-rules bctx
             [(d/rule "reaches" [(d/v :x) (d/v :y)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])])
              (d/rule "reaches" [(d/v :x) (d/v :z)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])
                                                     (d/lit "reaches" [(d/v :y) (d/v :z)])])])
        reaches (set (d/facts db "reaches"))
        blast (reduce (fn [m [xid yid]] (update m (e->k yid) (fnil conj #{}) (e->k xid))) {} reaches)]
    (binding [*out* *err*]
      (println (format "callgraph: %d defns, %d scope-correct edges, %d transitive reaches-pairs (refers_to + Fram Datalog)"
                       (count defn-meta) (count edges) (count reaches))))
    (println (json/generate-string
              {:defns (vec (vals defn-meta)) :edges edges
               :blast (into {} (map (fn [[k vs]] [k (vec vs)]) blast))}))))))))

;; GUARD: run the pipeline only when invoked as a CLI with a recognized mode.
;; Loaded as a library (no mode arg, or an unrecognized one), this is a no-op —
;; so a daemon can `require`/load this file and call `resolve-edn!` over its own
;; warm store without the old top-level load-edn crashing on mis-sliced args.
(when (MODES mode) (-main))
