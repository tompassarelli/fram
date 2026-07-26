(ns resolve-query
  (:require [clojure.string :as str]
            [fram.types :as t]
            [fram.store :as c]
            [fram.datalog :as d]
            [resolve-core :as rc]
            [resolve-read :as rr]
            [resolve-modules :as rm]
            [resolve-render :as rv]))

(def SLASH-RE (re-pattern "/"))

(def EXT-RE (re-pattern "\\.[^.]+$"))

(defn- call-refs [ctx view BOUND REFERS node]
  (if (some? (rr/refers-target ctx view BOUND REFERS node)) (if (nil? node) [] [node]) (if (= "list" (rr/kind-of ctx view node)) (reduce (fn [acc ch] (into acc (call-refs ctx view BOUND REFERS ch))) [] (rr/ordered-children ctx node)) [])))

(defn- src-label [ctx view ^String src ents]
  (let [mn (rm/module-name ctx view ents)]
  (if (some? mn) mn (str/replace (str (last (str/split src SLASH-RE))) EXT-RE ""))))

(defn- defn-meta-of [ctx view srcs file-modframe ents-of]
  (reduce (fn [acc src] (let [frame (get file-modframe src {})
   label (src-label ctx view src (get ents-of src []))]
  (reduce (fn [a nm] (let [leaf (get frame nm)]
  (assoc a leaf {:key (str src "#" (str leaf)) :file src :module label :name nm}))) acc (vec (keys frame))))) {} srcs))

(defn- callers-of [ctx view BOUND REFERS srcs ents-of defn-meta]
  (reduce (fn [acc src] (reduce (fn [a form] (let [d (rm/unwrap-def ctx view form)
   h (rr/head-sym ctx view d)]
  (cond
  (contains? rc/VALUE-DEFS (str h)) (let [cl (nth (rr/ordered-children ctx d) 1 nil)]
  (if (contains? defn-meta cl) (conj a [cl d]) a))
  (contains? #{"extend-protocol" "extend-type"} (str h)) (reduce (fn [b cc] (if (= "list" (rr/kind-of ctx view cc)) (let [mnode (nth (rr/ordered-children ctx cc) 0 nil)
   cl (if (some? (rr/sym-val ctx view mnode)) (rv/ultimate ctx view BOUND REFERS (rr/refers-target ctx view BOUND REFERS mnode)) nil)]
  (if (and (some? cl) (contains? defn-meta cl)) (conj b [cl cc]) b)) b)) a (vec (rest (rr/ordered-children ctx d))))
  :else a))) acc (rm/forms-of ctx view (get ents-of src [])))) [] srcs))

(defn call-edges [ctx view BOUND REFERS srcs file-modframe ents-of]
  (let [defn-meta (defn-meta-of ctx view srcs file-modframe ents-of)
   defn-set (set (keys defn-meta))
   callers (callers-of ctx view BOUND REFERS srcs ents-of defn-meta)
   edges (reduce (fn [acc pair] (let [caller-leaf (nth pair 0)
   body (nth pair 1)]
  (reduce (fn [a r] (let [callee (rv/ultimate ctx view BOUND REFERS (rr/refers-target ctx view BOUND REFERS r))]
  (if (and (contains? defn-set callee) (not= callee caller-leaf)) (conj a [caller-leaf callee]) a))) acc (call-refs ctx view BOUND REFERS body)))) [] callers)]
  {:defn-meta defn-meta :edges (vec (distinct edges)) :defn-set defn-set}))

(defn binding-privacy [ctx view srcs ents-of]
  (reduce (fn [acc src] (reduce (fn [a f] (let [d (rm/unwrap-def ctx view f)
   h (rr/head-sym ctx view d)]
  (if (contains? rc/VALUE-DEFS (str h)) (let [nl (rr/unwrap-meta ctx view (nth (rr/ordered-children ctx d) 1 nil))]
  (if (some? (rr/sym-val ctx view nl)) (assoc a nl (if (contains? #{"def-" "defn-"} (str h)) :private :public)) a)) a))) acc (rm/forms-of ctx view (get ents-of src [])))) {} srcs))

(defn- ent! [ctx k->id k]
  (let [cur (get (deref k->id) k)]
  (if (int? cur) cur (let [e (c/entity! ctx)]
  (do
  (swap! k->id assoc k e)
  e)))))

(defn- invert [k->id]
  (reduce (fn [m k] (let [v (get (deref k->id) k)]
  (if (int? v) (assoc m v k) m))) {} (vec (keys (deref k->id)))))

(defn blast-closure! [edges]
  (let [ctx (c/new-store)
   tx (c/begin-tx! ctx "code")
   EDGE (c/value! ctx "calls-defn")
   k->id (atom {})]
  (do
  (doseq [e edges]
  (c/fact! ctx (ent! ctx k->id (nth e 0)) EDGE (ent! ctx k->id (nth e 1)) tx))
  (let [id->k (invert k->id)
   db (d/run-rules ctx [(d/rule "reaches" [(d/v :x) (d/v :y)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])]) (d/rule "reaches" [(d/v :x) (d/v :z)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)]) (d/lit "reaches" [(d/v :y) (d/v :z)])])])
   reaches (set (mapv (fn [row] [(get id->k (nth row 0)) (get id->k (nth row 1))]) (vec (d/facts db "reaches"))))
   blast (reduce (fn [m row] (let [x (nth row 0)
   y (nth row 1)]
  (assoc m y (conj (get m y #{}) x)))) {} (vec reaches))]
  {:reaches reaches :blast blast}))))

(defn dead-private-bindings! [cg privacy]
  (let [defn-meta (:defn-meta cg {})
   edges (:edges cg [])
   ctx (c/new-store)
   tx (c/begin-tx! ctx "dead")
   CALLS (c/value! ctx "calls-defn")
   ISROOT (c/value! ctx "is-root")
   ISPRIV (c/value! ctx "is-priv")
   k->id (atom {})]
  (do
  (doseq [e edges]
  (c/fact! ctx (ent! ctx k->id (nth e 0)) CALLS (ent! ctx k->id (nth e 1)) tx))
  (doseq [leaf (vec (keys defn-meta))]
  (let [e (ent! ctx k->id leaf)]
  (if (= :private (get privacy leaf)) (c/fact! ctx e ISPRIV e tx) (c/fact! ctx e ISROOT e tx))))
  (let [id->k (invert k->id)
   db (d/run-strata ctx [[(d/rule "live" [(d/v :x)] [(d/lit "triple" [(d/v :x) ISROOT (d/v :x)])]) (d/rule "live" [(d/v :y)] [(d/lit "triple" [(d/v :x) CALLS (d/v :y)]) (d/lit "live" [(d/v :x)])])] [(d/rule "dead" [(d/v :p)] [(d/lit "triple" [(d/v :p) ISPRIV (d/v :p)]) (d/nlit "live" [(d/v :p)])])]])]
  (set (vec (keep (fn [row] (get id->k (nth row 0))) (vec (d/facts db "dead")))))))))
