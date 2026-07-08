;; roundtrip_test.clj — facts<->files idempotence guard.
;;
;; Proves the cutover keystone stays true: import -> export -> import yields the
;; SAME fact set. If this ever fails, the export projection has lost (or gained)
;; information and files can no longer be trusted as a view of the fact graph.
;;
;;   bb -cp out roundtrip_test.clj      (run from the repo root; uses threads/)
(require '[fram.kernel :as k]
         '[fram.fold :as fold]
         '[fram.import :as imp]
         '[fram.export :as exp]
         '[fram.rt]
         '[clojure.java.io :as io])

(defn fact-set [assertions]
  (set (map (juxt :l :p :r) (:facts (fold/fold assertions)))))

(let [src "threads"
      a-asserts (imp/load-corpus src)
      a (fact-set a-asserts)
      idx (k/build-index (:facts (fold/fold a-asserts)))
      out (str (System/getProperty "java.io.tmpdir") "/cheln-rt-"
               (System/currentTimeMillis))]
  (.mkdirs (io/file out))
  (let [facts (:facts (fold/fold a-asserts))]
    (doseq [te (k/thread-ids-i idx)]
      (let [title (k/one-i idx te "title")
            fname (str (subs te 1) "-" (fram.rt/slugify (if title title "untitled")) ".md")]
        (spit (str out "/" fname) (exp/thread-md facts te)))))
  (let [b (fact-set (imp/load-corpus out))
        only-a (clojure.set/difference a b)
        only-b (clojure.set/difference b a)]
    (println "round-trip:" (count a) "facts in," (count b) "facts back ("
             (count (k/thread-ids-i idx)) "threads )")
    (when (seq only-a) (println "  LOST (in source, not round-trip):")
          (doseq [x (take 10 only-a)] (println "   " (pr-str x))))
    (when (seq only-b) (println "  GAINED (in round-trip, not source):")
          (doseq [x (take 10 only-b)] (println "   " (pr-str x))))
    (if (and (empty? only-a) (empty? only-b))
      (println "  [PASS] import->export->import is fact-identical")
      (do (println "  [FAIL] round-trip is lossy") (System/exit 1)))))
