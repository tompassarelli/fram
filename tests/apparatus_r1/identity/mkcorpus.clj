;; Generates the canonical raw-byte identity corpus into a scratch dir.
;; Layout mirrors a mid-residual generation state (S1 counterexample):
;;   telemetry.log = [3 shadowed prefix facts] ++ [1 append byte-identical to a
;;                    retained coordination line, landing BEYOND the boundary].
;;   coordination.log = [gen control record] ++ [3 retained facts, byte-verbatim].
;; Usage: <bb|clojure> mkcorpus.clj <scratch-dir>
(require '[r1.model :as m])
(require '[clojure.java.io :as io])

(let [[dir] *command-line-args*
      _ (.mkdirs (io/file dir))
      f1 "{:tx 1 :op \"assert\" :l \"@a\" :p \"note\" :r \"one\" :by \"agent:x\" :ts \"2026-07-19T00:00:01Z\"}"
      f2 "{:tx 2 :op \"assert\" :l \"@b\" :p \"note\" :r \"two\" :by \"agent:x\" :ts \"2026-07-19T00:00:02Z\"}"
      f3 "{:tx 3 :op \"assert\" :l \"@c\" :p \"note\" :r \"three\" :by \"agent:x\" :ts \"2026-07-19T00:00:03Z\"}"
      prefix (str f1 "\n" f2 "\n" f3 "\n")
      gen (m/bytes->str (m/gen-record-bytes {:tx 4 :gen-n 1 :telem-prefix prefix}))
      ;; coordination.log: control record then retained facts, byte-verbatim.
      coord (str gen "\n" prefix)
      ;; telemetry.log: the consumed prefix (shadowed) + a legitimate later
      ;; append that byte-equals retained line f2 but lands beyond the boundary.
      telem (str prefix f2 "\n")]
  (spit (io/file dir "coordination.log") coord)
  (spit (io/file dir "telemetry.log") telem)
  (println "corpus:" dir "coord=" (count (m/str->bytes coord)) "B telem=" (count (m/str->bytes telem)) "B"))
