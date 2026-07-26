(ns supersession-check
  (:require [fram.store :as c]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn ent! [ctx cache lid]
  (let [hit (get (deref cache) lid)]
  (if (nil? hit) (let [e (c/entity! ctx)]
  (swap! cache assoc lid e)
  e) (int hit))))

(defn load-line! [ctx tx cache ^String line]
  (let [trip (edn/read-string line)
   s (nth trip 0)
   p (nth trip 1)
   o (nth trip 2)
   L (ent! ctx cache s)
   P (c/value! ctx p)
   R (if (integer? o) (ent! ctx cache o) (c/value! ctx o))]
  (c/fact! ctx L P R tx)
  nil))

(defn ^Boolean sym? [ctx kindp symv e]
  (pos? (count (filterv (fn [cid] (= symv (:r (c/fact-of ctx cid)))) (c/by-lp ctx e kindp)))))

(defn fact-l [ctx cid]
  (int (:l (c/fact-of ctx cid))))

(defn fact-r [ctx cid]
  (int (:r (c/fact-of ctx cid))))

(defn first-sym-fact [ctx vp kindp symv oldv]
  (let [hits (filterv (fn [cid] (sym? ctx kindp symv (fact-l ctx cid))) (vec (c/by-pr ctx vp oldv)))]
  (if (empty? hits) 0 (nth hits 0))))

(defn -main [& args]
  (let [ctx (c/new-store)
   tx (c/begin-tx! ctx "author")
   SUP (c/value! ctx "supersedes")
   _ (c/set-supersedes-pred! ctx SUP)
   cache (atom {})
   _ (doseq [line (str/split-lines (slurp "/tmp/trap.edn"))]
  (if (str/starts-with? line "[") (load-line! ctx tx cache line) nil))
   Vp (c/value! ctx "v")
   KIND (c/value! ctx "kind")
   SYM (c/value! ctx "symbol")
   OLDv (c/value-id ctx "red")
   NEWv (c/value! ctx "crimson")
   old (first-sym-fact ctx Vp KIND SYM (if (nil? OLDv) 0 (int OLDv)))
   e (fact-l ctx old)
   new (c/fact! ctx e Vp NEWv tx)
   sup (c/fact! ctx new SUP old tx)]
  (println "entity (the symbol node):" e)
  (println)
  (println "OLD value-fact  cid=" old "  ->" (c/fact-of ctx old) "  value=" (pr-str (c/literal ctx (fact-r ctx old))) "  LIVE?=" (c/live? ctx old))
  (println "NEW value-fact  cid=" new "  ->" (c/fact-of ctx new) "  value=" (pr-str (c/literal ctx (fact-r ctx new))) "  LIVE?=" (c/live? ctx new))
  (println "SUPERSEDES fact cid=" sup "  ->" (c/fact-of ctx sup) "  (l=new-fact, p=supersedes, r=old-fact)")
  (println)
  (println "same entity for old & new?   " (and (= (fact-l ctx old) (fact-l ctx new)) (= (fact-l ctx new) e)))
  (println "old still retrievable (history preserved)? " (some? (c/fact-of ctx old)))
  (println "live view of entity's v-facts (by-l is live-only):" (mapv (fn [cid] (pr-str (c/literal ctx (fact-r ctx cid)))) (filterv (fn [cid] (= Vp (:p (c/fact-of ctx cid)))) (c/by-l ctx e))))
  (println "=> old red fact EXISTS, marked not-live; new crimson fact is live; same node. Supersession is real:" (and (some? (c/fact-of ctx old)) (not (c/live? ctx old)) (c/live? ctx new) (and (= e (fact-l ctx old)) (= (fact-l ctx old) (fact-l ctx new)))))))
