#!/usr/bin/env bb
;; Project the FRAMLOG the frozen Zig server persisted into the same normalized
;; and per-transaction shapes fram.fri-replay emits, so the two independent
;; replays are diffed as text. Only all-String triples are oracle data: the
;; occurrence-provenance triples the server writes are triples about triples.
(require '[clojure.java.io :as io]
         '[fram.store :as store]
         '[fram.types :as t])

(load-file "database.clj")

(defn data-triple? [value]
  (and (t/triple? value)
       (string? (t/triple-t1 value))
       (string? (t/triple-t2 value))
       (string? (t/triple-t3 value))))

(defn fact-line [value]
  (str "fact\t" (t/triple-t1 value)
       "\t" (t/triple-t2 value)
       "\t" (t/triple-t3 value)))

(let [[log-path output-dir & extra] *command-line-args*]
  (when (or (nil? log-path) (nil? output-dir) (seq extra))
    (binding [*out* *err*]
      (println "usage: bb tests/fri2_replay_zig_state.clj FRAMLOG OUTPUT-DIR"))
    (System/exit 2))
  (.mkdirs (io/file output-dir))
  (let [db (database/open-database! log-path)
        context (:term-store db)
        version (store/current-sequence context)
        facts (filterv data-triple? (database/live-propositions db))
        frames (store/transaction-frames-between @context 0 version)]
    (spit (io/file output-dir "state")
          (str "final-version\t" version "\n"
               (apply str (map #(str (fact-line %) "\n") facts))))
    (spit (io/file output-dir "frames")
          (apply str
                 (for [frame frames
                       operation (t/transactionframe-operations frame)
                       :let [proposition (t/commitoperation-proposition operation)]
                       :when (data-triple? proposition)]
                   (str "tx\t" (t/transactionframe-sequence frame)
                        "\t" (name (t/commitoperation-action operation))
                        "\t" (t/triple-t1 proposition)
                        "\t" (t/triple-t2 proposition)
                        "\t" (t/triple-t3 proposition) "\n"))))
    (println (str "zig-log: version " version ", " (count facts) " live triples"))))
