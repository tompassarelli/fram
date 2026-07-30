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
         '[clojure.java.io :as io]
         '[babashka.process :as proc])

(defn fact-set [assertions]
  (set (map (juxt :l :p :r) (:facts (fold/fold assertions)))))

(defn require-pass [label ok]
  (println (str "  [" (if ok "PASS" "FAIL") "] " label))
  (when-not ok (System/exit 1)))

;; Predicate identity metadata is imported before dependent facts, aliases
;; canonicalize, and value_kind—not the object's @ sigil—governs both directions.
(let [root (str (System/getProperty "java.io.tmpdir") "/fram-predicate-rt-"
                (System/currentTimeMillis))
      src (str root "/src")
      out (str root "/out")
      log (str root "/facts.log")]
  (.mkdirs (io/file src))
  (.mkdirs (io/file out))
  (spit (str src "/01-friend.md")
        "@friend\npredicate_name  friend\npredicate_alias  :friend\ncardinality  multi\nvalue_kind  ref\n---\n")
  (spit (str src "/02-note.md")
        "@note\npredicate_name  note\npredicate_alias  :note\ncardinality  multi\nvalue_kind  literal\n---\n")
  (spit (str src "/03-alice.md")
        "@alice\ntitle  Alice\n:friend  bob\nnote  \"@bob\"\n---\n")
  (let [ops (imp/load-corpus src)
        facts (:facts (fold/fold ops))
        sigs (set (map (juxt :l :p :r) facts))
        identity? #(contains? #{"predicate_name" "predicate_alias"} (:p %))
        identity-count (count (filter identity? ops))
        prefix-count (count (take-while identity? ops))
        first-domain (first (drop-while identity? ops))]
    (require-pass "identity metadata precedes dependent import facts"
                  (and first-domain
                       (pos? identity-count)
                       (= identity-count prefix-count)))
    (require-pass "alias import resolves to canonical ref predicate"
                  (contains? sigs ["@alice" "friend" "@bob"]))
    (require-pass "explicit literal preserves an @-prefixed value"
                  (contains? sigs ["@alice" "note" "@bob"]))
    (require-pass "identity metadata remains ordinary facts"
                  (and (contains? sigs ["@friend" "predicate_name" "friend"])
                       (contains? sigs ["@friend" "predicate_alias" ":friend"])))
    (fram.rt/write-log log ops)
    (let [run (proc/shell {:continue true
                           :out :string
                           :err :string
                           :extra-env {"FRAM_THREADS" src
                                       "FRAM_LOG" log}}
                          "./bin/fram" "export" out "--force")]
      (require-pass "real CLI export succeeds"
                    (= 0 (:exit run)))
      (require-pass "real CLI export includes predicate metadata subjects"
                    (= sigs (fact-set (imp/load-corpus out)))))
    (let [rendered (exp/thread-md facts "@alice")]
      (require-pass "declared ref exports bare"
                    (clojure.string/includes? rendered "friend  @bob"))
      (require-pass "declared literal exports quoted"
                    (clojure.string/includes? rendered "note  \"@bob\"")))))

(let [legacy [(k/->Fact "@a" "title" "A")
              (k/->Fact "@a" "depends_on" "@b")
              (k/->Fact "@a" "note" "@literal")
              (k/->Fact "@b" "title" "B")]
      rendered (exp/thread-md legacy "@a")]
  (require-pass "legacy ref fallback renders unchanged"
                (clojure.string/includes? rendered "depends_on  @b"))
  (require-pass "legacy literal @ value remains quoted"
                (clojure.string/includes? rendered "note  \"@literal\"")))

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
