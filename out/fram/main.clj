(ns fram.main
  (:gen-class))

(defn -main [& _]
  (println "fram usage: validate | tell <subject> <slot> <value> | retract <subject> <slot> <value> (alias: untell) | query <edn> | selfcheck --deep"))
