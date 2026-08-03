#!/usr/bin/env bb
;; Thin I/O shell around fram.fri-replay: the module is pure, so reading the
;; corpus and writing the artifacts happens here and nowhere inside it.
(require '[clojure.java.io :as io]
         '[fram.fold :as fold]
         '[fram.fri-replay :as replay])

(let [[corpus-path space-id output-dir & extra] *command-line-args*]
  (when (or (nil? corpus-path) (nil? space-id) (nil? output-dir) (seq extra))
    (binding [*out* *err*]
      (println "usage: bb tests/fri2_replay_driver.clj CORPUS SPACE-ID OUTPUT-DIR"))
    (System/exit 2))
  (.mkdirs (io/file output-dir))
  (let [result (replay/replay (slurp corpus-path))]
    (when (pos? (count (replay/replayresult-error result)))
      (binding [*out* *err*]
        (println "fri2-replay:" (replay/replayresult-error result)))
      (System/exit 1))
    (let [folded (replay/fold-replay! space-id result)
          facts (replay/store-facts folded)]
      (when-not (replay/store-agrees? result facts)
        (binding [*out* *err*]
          (println "fri2-replay: folded TermStore disagrees with the replay model"))
        (System/exit 1))
      (spit (io/file output-dir "normalized")
            (str (replay/render-outcomes result)
                 "final-version\t" (fold/fold-version folded) "\n"
                 (replay/render-facts facts)))
      (spit (io/file output-dir "frames") (replay/render-frames result))
      (let [summary (replay/summary-line corpus-path result folded facts)]
        (spit (io/file output-dir "summary") (str summary "\n"))
        (println summary)))))
