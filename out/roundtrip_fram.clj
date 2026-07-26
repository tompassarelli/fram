(ns roundtrip-fram
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

(defn dump-fact! [ctx cid]
  (let [cl (c/fact-of ctx cid)
   l (:l cl)
   p (:p cl)
   r (:r cl)
   ps (c/literal ctx p)]
  (if (c/value-object? ctx r) (println (str "[" l " " (pr-str ps) " " (pr-str (c/literal ctx r)) "]")) (println (str "[" l " " (pr-str ps) " " r "]")))))

(defn -main [& args]
  (let [edn-path (str (nth (vec args) 0))
   ctx (c/new-store)
   tx (c/begin-tx! ctx "code")
   cache (atom {})
   lines (str/split-lines (slurp edn-path))]
  (doseq [line lines]
  (if (str/starts-with? line "[") (load-line! ctx tx cache line) nil))
  (binding [*out* *err*]
  (println "loaded" (count (c/current-facts ctx)) "facts into a Fram store"))
  (doseq [cid (c/current-facts ctx)]
  (dump-fact! ctx cid))))
