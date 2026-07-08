(ns fram.types)

(defrecord Store [next-id next-seq supersedes-pred objects values val-intern facts tx-of txs superseded idx-by-l idx-by-p idx-by-r idx-by-lp idx-by-pr])

(defn store-next-id [r] (:next-id r))

(defn store-next-seq [r] (:next-seq r))

(defn store-supersedes-pred [r] (:supersedes-pred r))

(defn store-objects [r] (:objects r))

(defn store-values [r] (:values r))

(defn store-val-intern [r] (:val-intern r))

(defn store-facts [r] (:facts r))

(defn store-tx-of [r] (:tx-of r))

(defn store-txs [r] (:txs r))

(defn store-superseded [r] (:superseded r))

(defn store-idx-by-l [r] (:idx-by-l r))

(defn store-idx-by-p [r] (:idx-by-p r))

(defn store-idx-by-r [r] (:idx-by-r r))

(defn store-idx-by-lp [r] (:idx-by-lp r))

(defn store-idx-by-pr [r] (:idx-by-pr r))
