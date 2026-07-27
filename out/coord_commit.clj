(ns coord-commit
  (:require [fram.types :as t]))

(defn ^Boolean version-conflict? [^Boolean single base-version expected-version]
  (if (nil? expected-version) false (and single (> base-version expected-version))))

(defn ^Boolean expected-value-match? [live-values expected-value]
  (if (nil? expected-value) false (contains? live-values expected-value)))

(defrecord CommitIntent [index pred kind r single base-version expected-version live-values expected-value cycle])

(defn commitintent-index [r] (:index r))

(defn commitintent-pred [r] (:pred r))

(defn commitintent-kind [r] (:kind r))

(defn commitintent-r [r] (:r r))

(defn commitintent-single [r] (:single r))

(defn commitintent-base-version [r] (:base-version r))

(defn commitintent-expected-version [r] (:expected-version r))

(defn commitintent-live-values [r] (:live-values r))

(defn commitintent-expected-value [r] (:expected-value r))

(defn commitintent-cycle [r] (:cycle r))

(defn commit-plan [head-version intents]
  (loop [remaining intents
   writes []
   idempotent []]
  (if (empty? remaining) {:writes writes :idempotent idempotent} (let [intent (first remaining)
   pred (:pred intent)]
  (cond
  (version-conflict? (:single intent) (:base-version intent) (:expected-version intent)) {:reject :conflict :version head-version :at (:index intent) :pred pred}
  (:cycle intent) {:reject [(str pred " cycle")] :version head-version :at (:index intent) :pred pred}
  (and (not (:single intent)) (expected-value-match? (:live-values intent) (:expected-value intent))) (recur (vec (rest remaining)) writes (conj idempotent pred))
  :else (recur (vec (rest remaining)) (conj writes {:pred pred :kind (:kind intent) :r (:r intent)}) idempotent))))))
