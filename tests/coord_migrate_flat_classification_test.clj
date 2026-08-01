;; Sealed legacy classification stays local to migration and never restores a
;; runtime schema/store compatibility layer.
(require '[clojure.string :as str]
         '[fram.types :as t])
(load-file "coord.clj")

(def failures (atom []))
(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok (swap! failures conj label)))
(defn error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error
         (:fram/code (ex-data error)))))

(def scratch
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-migration-classification-"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def legacy-source (java.io.File. scratch "legacy.log"))
(def migration-target (java.io.File. scratch "history.framlog"))

(def rows
  [{:tx 1 :op "assert" :l "@contact" :p "predicate_name" :r "email"}
   {:tx 1 :op "assert" :l "@contact" :p "predicate_alias" :r "mail"}
   {:tx 1 :op "assert" :l "@tag" :p "predicate_name" :r "tag"}
   {:tx 2 :op "assert" :l "@contact" :p "cardinality" :r "single"}
   {:tx 2 :op "assert" :l "@tag" :p "cardinality" :r "multi"}
   {:tx 3 :op "assert" :l "Alice" :p "email" :r "first@example.com"}
   {:tx 4 :op "assert" :l "Alice" :p "mail" :r "second@example.com"}
   {:tx 4 :op "assert" :l "Alice" :p "tag" :r "red"}
   {:tx 5 :op "assert" :l "Alice" :p "tag" :r "blue"}
   ;; A single-valued retract may carry a different spelling/value. Its explicit
   ;; occurrence relation, not proposition equality, preserves the legacy fold.
   {:tx 6 :op "retract" :l "Alice" :p "email" :r "ignored-value"}])

(spit legacy-source (str (str/join "\n" (map pr-str rows)) "\n"))
(coord/migrate-legacy-flat-log! (.getPath legacy-source) "classification-space"
                                (.getPath migration-target))
(def runtime (coord/open-coordinator! (.getPath migration-target)
                                      "classification-space"))
(def live (set (coord/live-propositions runtime)))

(check! "canonical and alias spellings share one single-valued identity"
        (and (not (contains? live
                             (t/triple "Alice" "email" "first@example.com")))
             (not (contains? live
                             (t/triple "Alice" "mail" "second@example.com")))))
(check! "single-valued replacement is recorded as ordinary supersession"
        (= 1
           (count
            (filter #(= :kernel/supersedes (t/triple-slot1 %))
                    (coord/supersession-triples runtime)))))
(check! "alias-spelled retract is recorded as an exact withdrawal relation"
        (some #(= :kernel/withdraws (t/triple-slot1 %))
              (coord/withdrawal-triples runtime)))
(check! "explicit multi cardinality preserves distinct proposition values"
        (and (contains? live (t/triple "Alice" "tag" "red"))
             (contains? live (t/triple "Alice" "tag" "blue"))))
(check! "classification metadata itself remains ordinary Triple history"
        (and (contains? live (t/triple "@contact" "predicate_name" "email"))
             (contains? live (t/triple "@contact" "predicate_alias" "mail"))
             (every? t/triple? (coord/history runtime))))

(def collision-source (java.io.File. scratch "collision.log"))
(def collision-target (java.io.File. scratch "collision.framlog"))
(spit collision-source
      (str (pr-str {:tx 1 :op "assert" :l "@left"
                    :p "predicate_name" :r "same"}) "\n"
           (pr-str {:tx 1 :op "assert" :l "@right"
                    :p "predicate_alias" :r "same"}) "\n"))
(check! "predicate spelling collisions fail typed before output installation"
        (and (= :migration-predicate-spelling-collision
                (error-code
                 #(coord/migrate-legacy-flat-log!
                   (.getPath collision-source) "collision-space"
                   (.getPath collision-target))))
             (not (.exists collision-target))))

(if (seq @failures)
  (do (println (str (count @failures) " migration classification failures"))
      (System/exit 1))
  (println "sealed migration classification: PASS"))
