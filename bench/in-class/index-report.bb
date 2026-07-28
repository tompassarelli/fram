#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[clojure.set :as set]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(let [[results-path contract-path expected-runs-arg] *command-line-args*]
  (when-not (and results-path contract-path expected-runs-arg)
    (throw
     (ex-info
      "usage: index-report.bb RESULTS.jsonl scenario-contract.edn EXPECTED_RUNS"
      {})))
  (let [rows (->> (str/split-lines (slurp results-path))
                  (remove str/blank?)
                  (mapv #(json/parse-string % true)))
        contract (-> contract-path slurp edn/read-string :index-architecture)
        scenarios (into {} (map (juxt (comp name :id) identity)
                                (:scenarios contract)))
        required (:required-result-keys contract)
        expected-runs (Long/parseLong expected-runs-arg)
        applies? (fn [scenario size]
                   (let [sizes (:corpus-sizes scenario)]
                     (if (= sizes :default)
                       (contains? (set (:default-corpus-sizes contract)) size)
                       (contains? (set sizes) size))))
        expected
        (set
         (for [engine (:engines contract)
               run (range 1 (inc expected-runs))
               scenario (:scenarios contract)
               size (concat (:default-corpus-sizes contract)
                            [(:rotation-corpus-size contract)])
               :when (applies? scenario size)]
           [engine run size (name (:id scenario))]))
        observed (set (map (juxt :engine :run :corpus-triples :scenario) rows))]
    (when (empty? rows)
      (throw (ex-info "no index benchmark rows" {:path results-path})))
    (doseq [row rows]
      (let [missing (remove #(contains? row %) required)
            scenario (get scenarios (:scenario row))]
        (when (seq missing)
          (throw (ex-info "index row violates scenario contract"
                          {:missing missing :row row})))
        (when-not scenario
          (throw (ex-info "unknown scenario row" {:row row})))
        (when-not (= (:decision-section scenario) (:decision-section row))
          (throw (ex-info "decision citation drift" {:row row})))
        (when-not (= (:query-class scenario) (keyword (:query-class row)))
          (throw (ex-info "query class drift" {:row row})))
        (doseq [[key value] (:receipt-fields scenario)]
          (when-not (= value (get row key))
            (throw (ex-info "scenario provenance drift"
                            {:key key :expected value :row row}))))
        (when-not (and (zero? (:errors row))
                       (= (:expected-count row) (:result-count row)))
          (throw (ex-info "index scenario result mismatch" {:row row})))))
    (when-not (= expected observed)
      (throw
       (ex-info "index benchmark matrix incomplete"
                {:missing (sort (set/difference expected observed))
                 :unexpected (sort (set/difference observed expected))})))
    (when-not (= (count expected) (count rows))
      (throw
       (ex-info "index benchmark matrix contains duplicate rows"
                {:expected (count expected) :observed (count rows)})))
    (println
     "| engine | live triples | scenario | runs | mean query ms | retained RSS KiB |")
    (println "| --- | ---: | --- | ---: | ---: | ---: |")
    (doseq [[[engine size scenario] rs]
            (sort-by key (group-by (juxt :engine :corpus-triples :scenario) rows))]
      (println
       (format "| %s | %d | %s | %d | %.3f | %.0f |"
               engine size scenario (count rs)
               (/ (reduce + (map :query-ms rs)) (double (count rs)))
               (/ (reduce + (map :rss-retained-kb rs)) (double (count rs))))))
    (println
     (format "index-architecture contract: PASS (%d rows, %d matrix cases)"
             (count rows) (count expected)))))
