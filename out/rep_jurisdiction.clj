(ns rep-jurisdiction
  (:require [fram.store :as c]
            [fram.datalog :as d]
            [callgraph :as cg]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn parse-rep-blocks [^String path]
  (let [lines (str/split-lines (slurp path))
   acc (reduce (fn [st l] (if (str/starts-with? l "@file ") {:cur {:file (subs l 6) :triples []} :out (if (empty? (:cur st)) (:out st) (conj (vec (:out st)) (:cur st)))} (if (str/starts-with? l "[") (assoc st :cur (assoc (:cur st) :triples (conj (vec (:triples (:cur st))) (edn/read-string l)))) st))) {:cur {} :out []} lines)]
  (if (empty? (:cur acc)) (vec (:out acc)) (conj (vec (:out acc)) (:cur acc)))))

(defn block->defs [block]
  (let [ts (vec (:triples block))
   by-subj (group-by (fn [t] (nth t 0)) ts)
   ms (mapv (fn [s] (into {} (mapv (fn [t] [(nth t 1) (nth t 2)]) (vec (get by-subj s))))) (vec (keys by-subj)))]
  (mapv (fn [m] {:name (get m "name") :regime (get m "rep-regime") :native (get m "native-sites") :hamt (get m "hamt-sites") :module (get m "module")}) (filterv (fn [m] (= "rep-def" (get m "form-kind"))) ms))))

(defn ent! [ctx cache nm]
  (let [hit (get (deref cache) nm)]
  (if (nil? hit) (let [e (c/entity! ctx)]
  (swap! cache assoc nm e)
  e) (int hit))))

(defn reverse-cache [cache]
  (into {} (mapv (fn [kv] [(nth kv 1) (nth kv 0)]) (vec (deref cache)))))

(defn defs-with-regime [ctx regime ent->n ^String reg]
  (let [R (c/value! ctx reg)]
  (vec (sort (mapv (fn [cid] (get ent->n (int (:l (c/fact-of ctx cid))))) (vec (c/by-pr ctx regime R)))))))

(defn q4! [rep-defs ^String cg-path]
  (let [cg-blocks (cg/parse-corpus cg-path)
   graph (cg/build-graph cg-blocks)
   defns (vec (:defns graph))
   edges (vec (:edges graph))
   key->name (into {} (mapv (fn [dd] [(:key dd) (:name dd)]) defns))
   name-edges (vec (distinct (filterv (fn [x] (not (nil? x))) (mapv (fn [e] (let [an (get key->name (nth e 0))
   bn (get key->name (nth e 1))]
  (if (and (not (nil? an)) (not (nil? bn))) [an bn] nil))) edges))))
   hamt-names (set (mapv (fn [dd] (:name dd)) (filterv (fn [dd] (contains? #{"mixed" "hamt"} (:regime dd))) rep-defs)))
   gctx (c/new-store)
   gtx (c/begin-tx! gctx "cg")
   CALLS (c/value! gctx "calls")
   HAMT (c/value! gctx "ships-hamt")
   MARK (c/value! gctx "yes")
   cache (atom {})
   _seed (doseq [dd defns]
  (if (nil? (:name dd)) nil (let [e (ent! gctx cache (:name dd))]
  nil)))
   _edges (doseq [e name-edges]
  (c/fact! gctx (ent! gctx cache (nth e 0)) CALLS (ent! gctx cache (nth e 1)) gtx))
   _marks (doseq [nm (vec hamt-names)]
  (c/fact! gctx (ent! gctx cache nm) HAMT MARK gtx))
   ent->name (reverse-cache cache)
   db (d/run-rules gctx [(d/rule "forces" [(d/v :x)] [(d/lit "triple" [(d/v :x) CALLS (d/v :y)]) (d/lit "triple" [(d/v :y) HAMT MARK])]) (d/rule "forces" [(d/v :x)] [(d/lit "triple" [(d/v :x) CALLS (d/v :y)]) (d/lit "forces" [(d/v :y)])])])
   forced (vec (distinct (vec (sort (filterv (fn [x] (not (nil? x))) (mapv (fn [row] (get ent->name (nth row 0))) (d/facts db "forces")))))))]
  (if (pos? (count forced)) (doseq [nm forced]
  (println "  forces-HAMT" nm)) (println "  (no caller transitively reaches a HAMT def in this corpus)"))
  (println (format "\nHAMT-shipping defs: %d ; defs that FORCE a HAMT downstream: %d" (count hamt-names) (count forced)))
  (println "  ^ THIS is the query grep-the-comment cannot answer: the comment is")
  (println "    per-module and disconnected from the call graph; the fact is a")
  (println "    graph node you JOIN against scope-correct edges — blast radius of a")
  (println "    rep decision, across module boundaries, in one fixpoint.")))

(defn -main [& args]
  (let [argv (vec args)
   rep-path (str (nth argv 0))
   cg-path (if (> (count argv) 1) (str (nth argv 1)) nil)
   blocks (parse-rep-blocks rep-path)
   rep-defs (vec (mapcat (fn [b] (block->defs b)) blocks))]
  (println "================ REP JURISDICTION — compiler judgment as facts =================")
  (println "rep corpus:" rep-path)
  (println "rep-def facts:" (count rep-defs) " across" (count (distinct (mapv (fn [dd] (:module dd)) rep-defs))) "module(s)")
  (let [ctx (c/new-store)
   tx (c/begin-tx! ctx "rep")
   REGIME (c/value! ctx "rep-regime")
   cache (atom {})
   _load (doseq [dd rep-defs]
  (c/fact! ctx (ent! ctx cache (:name dd)) REGIME (c/value! ctx (:regime dd)) tx))
   ent->n (reverse-cache cache)]
  (println "\n---- Q1. defs that SHIP THE HAMT (pull persistent runtime) ----")
  (doseq [nm (defs-with-regime ctx REGIME ent->n "hamt")]
  (println "  HAMT  " nm))
  (println "\n---- Q2. defs that are 100% NATIVE (zero persistent runtime) ----")
  (doseq [nm (defs-with-regime ctx REGIME ent->n "native")]
  (println "  native" nm))
  (println "\n---- Q3. defs that are MIXED (a rep boundary lives inside) ----")
  (doseq [nm (defs-with-regime ctx REGIME ent->n "mixed")]
  (println "  mixed " nm)))
  (if (nil? cg-path) nil (let [_h1 (println "\n---- Q4. transitive HAMT blast: defs DOWNSTREAM of any HAMT/mixed def ----")
   _h2 (println "(scope-correct call graph; a caller \"forces\" a HAMT if it reaches one)")]
  (q4! rep-defs (str cg-path))))))
