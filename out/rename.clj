(ns rename
  (:require [fram.store :as c]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn fact-l [ctx cid]
  (int (:l (c/fact-of ctx cid))))

(defn fact-p [ctx cid]
  (int (:p (c/fact-of ctx cid))))

(defn fact-r [ctx cid]
  (int (:r (c/fact-of ctx cid))))

(defn ent! [ctx local file->ents ^String src lid]
  (let [hit (get (deref local) lid)]
  (if (nil? hit) (let [e (c/entity! ctx)]
  (swap! local assoc lid e)
  (swap! file->ents assoc src (conj (get (deref file->ents) src []) e))
  e) (int hit))))

(defn ^String load-edn! [ctx tx file->ents ^String path]
  (let [lines (str/split-lines (slurp path))
   heads (filterv (fn [l] (str/starts-with? l "@file")) lines)
   src (subs (nth heads 0) 6)
   local (atom {})]
  (doseq [line lines]
  (if (str/starts-with? line "[") (let [trip (edn/read-string line)
   s (nth trip 0)
   p (nth trip 1)
   o (nth trip 2)
   L (ent! ctx local file->ents src s)
   P (c/value! ctx p)
   R (if (integer? o) (ent! ctx local file->ents src o) (c/value! ctx o))]
  (c/fact! ctx L P R tx)
  nil) nil))
  src))

(defn ^Boolean symbol-leaf? [ctx kindp symv e]
  (pos? (count (filterv (fn [cid] (= symv (fact-r ctx cid))) (c/by-lp ctx e kindp)))))

(defn field-child [ctx e ^String fname]
  (let [P (c/value-id ctx fname)]
  (if (nil? P) 0 (let [cids (c/by-lp ctx e (int P))]
  (if (empty? cids) 0 (fact-r ctx (nth cids 0)))))))

(defn sym-val [ctx vp kindp symv e]
  (if (and (> e 0) (symbol-leaf? ctx kindp symv e)) (let [vc (filterv (fn [cid] (= vp (fact-p ctx cid))) (c/by-l ctx e))]
  (if (empty? vc) nil (c/literal ctx (fact-r ctx (nth vc 0))))) nil))

(defn binding-name [ctx vp kindp symv e]
  (let [h (sym-val ctx vp kindp symv (field-child ctx e "f0"))]
  (if (and (not (nil? h)) (contains? #{"defn" "definline" "def-" "defonce" "defn-" "def"} h)) (sym-val ctx vp kindp symv (field-child ctx e "f1")) nil)))

(defn module-bindings [ctx vp kindp symv file->ents ^String src]
  (set (filterv (fn [nm] (not (nil? nm))) (mapv (fn [e] (binding-name ctx vp kindp symv (int e))) (vec (get (deref file->ents) src []))))))

(defn rename-facts! [ctx tx vp kindp symv sup newv target-ents cids]
  (reduce (fn [acc cid] (let [e (fact-l ctx cid)]
  (if (and (contains? target-ents e) (symbol-leaf? ctx kindp symv e)) (let [ncid (c/fact! ctx e vp newv tx)]
  (c/fact! ctx ncid sup cid tx)
  (inc acc)) acc))) 0 cids))

(defn ^String project-line [ctx e cid]
  (let [p (fact-p ctx cid)
   r (fact-r ctx cid)
   ps (c/literal ctx p)]
  (if (= ps "supersedes") "" (if (c/value-object? ctx r) (str "[" e " " (pr-str ps) " " (pr-str (c/literal ctx r)) "]\n") (str "[" e " " (pr-str ps) " " r "]\n")))))

(defn project-file! [ctx file->ents ^String src ^String out-path]
  (spit out-path (str (str "@file " src "\n") (str/join "" (mapv (fn [e] (str/join "" (mapv (fn [cid] (project-line ctx (int e) cid)) (c/by-l ctx (int e))))) (vec (get (deref file->ents) src []))))))
  nil)

(defn ^String out-path [^String src]
  (let [parts (str/split src (re-pattern "/"))]
  (str "/tmp/mutated-" (nth parts (dec (count parts))) ".edn")))

(defn -main [& args]
  (let [argv (vec args)
   old-name (str (nth argv 0))
   new-name (str (nth argv 1))
   target-substr (str (nth argv 2))
   edn-files (vec (drop 3 argv))
   ctx (c/new-store)
   tx (c/begin-tx! ctx "author")
   SUP (c/value! ctx "supersedes")
   _ (c/set-supersedes-pred! ctx SUP)
   file->ents (atom {})
   srcs (mapv (fn [p] (load-edn! ctx tx file->ents (str p))) edn-files)
   Vp (c/value! ctx "v")
   KIND (c/value! ctx "kind")
   SYM (c/value! ctx "symbol")
   OLDv (c/value-id ctx old-name)
   NEWv (c/value! ctx new-name)
   target-modules (filterv (fn [s] (str/includes? s target-substr)) srcs)
   target-ents (set (vec (mapcat (fn [s] (vec (get (deref file->ents) s []))) target-modules)))
   collisions (filterv (fn [m] (contains? (module-bindings ctx Vp KIND SYM file->ents m) new-name)) target-modules)]
  (if (pos? (count collisions)) (let [m (nth collisions 0)]
  (binding [*out* *err*]
  (println (str "REJECTED — `" new-name "` is already a binding in " m ".\n" "  A rename onto an existing binding would shadow/collide; the store refuses the write.\n" "  (Turtle #5 invariant: rename-doesn't-collide. No facts were mutated.)")))
  (System/exit 3)) nil)
  (let [cids (if (nil? OLDv) [] (vec (c/by-pr ctx Vp (int OLDv))))
   renamed (rename-facts! ctx tx Vp KIND SYM SUP NEWv target-ents cids)
   preserved (count (filterv (fn [cid] (let [e (fact-l ctx cid)]
  (and (not (contains? target-ents e)) (symbol-leaf? ctx KIND SYM e)))) cids))
   outs (mapv (fn [s] (out-path s)) srcs)]
  (doseq [i (range (count srcs))]
  (project-file! ctx file->ents (nth srcs i) (nth outs i)))
  (binding [*out* *err*]
  (println "================ TURTLE #4 — graph-native rename ================")
  (println (str "edit: rename symbol `" old-name "` -> `" new-name "` in files matching \"" target-substr "\""))
  (println (str "renamed (target file): " renamed " symbol occurrences"))
  (println (str "preserved (other files, same name, untouched): " preserved " occurrences"))
  (println (str "superseded facts (recoverable, nothing deleted): " renamed))
  (println (str "live facts in store: " (count (c/current-facts ctx))))
  (doseq [i (range (count srcs))]
  (println (str "projected -> " (nth outs i) "   <- " (nth srcs i))))))))
