#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(let [[results-path golden-path] *command-line-args*]
  (when-not (and results-path golden-path)
    (throw (ex-info "usage: check-golden.bb RESULTS.jsonl GOLDEN.edn" {})))
  (let [rows (->> (str/split-lines (slurp results-path))
                  (remove str/blank?)
                  (map #(json/parse-string % true)))
        golden (edn/read-string (slurp golden-path))
        metrics (:metrics golden)
        median (fn [xs]
                 (let [s (vec (sort xs))
                       n (count s)
                       middle (quot n 2)]
                   (if (odd? n)
                     (nth s middle)
                     (/ (+ (nth s (dec middle)) (nth s middle)) 2.0))))
        current (into {}
                      (for [[key rs] (group-by (juxt :adapter :corpus-triples) rows)]
                        [key (into {} (for [metric (keys metrics)]
                                        [metric (median (map metric rs))]))]))
        failures
        (vec
         (for [[key baseline] (:baselines golden)
               :let [observed (get current key)]
               :when observed
               [metric direction] metrics
               :let [base (double (get baseline metric))
                     actual (double (get observed metric))
                     pass? (case direction
                             :lower-is-better (<= actual (* base (:max-latency-regression-ratio golden)))
                             :higher-is-better (>= actual (* base (:min-throughput-retention-ratio golden))))]
               :when (not pass?)]
           {:adapter+size key :metric metric :baseline base :observed actual
            :direction direction}))]
    (doseq [key (keys (:baselines golden))]
      (when-not (contains? current key)
        (throw (ex-info "golden case missing from current run" {:case key}))))
    (if (seq failures)
      (do (doseq [failure failures] (println "GOLDEN FAIL" (pr-str failure)))
          (System/exit 1))
      (println (format "in-class golden: PASS (%d adapter/size cases, %d metrics)"
                       (count (:baselines golden)) (count metrics))))))
