(ns fram.graal-coordinator
  (:require [coord-daemon :as daemon])
  (:gen-class))

(defn -main [& arguments]
  (reset! daemon/runtime-engine :rpc/graal)
  (apply daemon/-main arguments))
