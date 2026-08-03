#!/usr/bin/env bb
;; Project the FRAMLOG the frozen Zig daemon persisted into the same normalized
;; and per-transaction shapes fram.fri-replay emits, so the two independent
;; replays are diffed as text. Only all-String triples are oracle data: the
;; occurrence-provenance triples the daemon writes are triples about triples.
(require '[clojure.java.io :as io]
         '[fram.store :as store]
         '[fram.types :as t])

(load-file "coord.clj")

(defn data-triple? [value]
  (and (t/triple? value)
       (string? (t/triple-slot0 value))
       (string? (t/triple-slot1 value))
       (string? (t/triple-slot2 value))))

(defn fact-line [value]
  (str "fact\t" (t/triple-slot0 value)
       "\t" (t/triple-slot1 value)
       "\t" (t/triple-slot2 value)))

(let [[log-path output-dir & extra] *command-line-args*]
  (when (or (nil? log-path) (nil? output-dir) (seq extra))
    (binding [*out* *err*]
      (println "usage: bb tests/fri2_replay_zig_state.clj FRAMLOG OUTPUT-DIR"))
    (System/exit 2))
  (.mkdirs (io/file output-dir))
  (let [co (coord/open-coordinator! log-path)
        context (:term-store co)
        version (store/current-sequence context)
        facts (filterv data-triple? (coord/live-propositions co))
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
                        "\t" (t/triple-slot0 proposition)
                        "\t" (t/triple-slot1 proposition)
                        "\t" (t/triple-slot2 proposition) "\n"))))
    (println (str "zig-log: version " version ", " (count facts) " live triples"))))
