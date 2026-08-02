;; W19a slice 1: turtles-native declaration, advisory relational lint, and P3.
;;   env -u FRAM_TELEMETRY_LOG bb -cp out tests/profile_lint_test.clj
;;   FRAM_PROFILE_CORPUS=docs/private/w19a-corpus/coordination.log ...
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[fram.kernel :as kernel]
         '[fram.types :as t])

(def space-id "north-corpus")
(def profile-id "relational-v1")
(def declaration (kernel/relational-profile-declaration space-id profile-id))
(def rule-triples (mapv #(kernel/profile-rule profile-id %)
                        kernel/relational-profile-rules))
(def profile-triples (into [declaration] rule-triples))

(defn violation-rules [violations]
  (mapv t/triple-slot2 violations))

(defn lint-one [proposition]
  (violation-rules
   (kernel/lint-declared-profile
    (conj profile-triples proposition) space-id)))

(def negative-corpus
  [(t/triple (t/triple "nested" "subject" 1) "predicate" "value")
   (t/triple "" "predicate" "value")
   (t/triple "subject" 7 "value")
   (t/triple "subject" "predicate" (t/triple "nested" "value" 1))
   (t/->Triple "subject" "predicate" nil)
   (t/->Triple "subject" "predicate" [])
   ;; A namespace-shaped spelling has no bootstrap privilege after the amendment.
   (t/triple "north-corpus" (keyword "space" "profile")
             (t/triple "legacy" "relational" "observe"))])

(defn p3-agrees? [proposition]
  (= (sort (kernel/relational-admission-errors proposition))
     (sort (kernel/relational-lint-errors proposition))))

(def checks
  [["profile declaration uses the one primitive anchoring predicate"
    (= kernel/profile-anchor (t/triple-slot1 declaration))]
   ["profile header and R1-R4 are ordinary reachable triples"
    (kernel/declared-relational-profile? profile-triples space-id)]
   ["missing one declared rule leaves the profile unbound"
    (not (kernel/declared-relational-profile?
          (vec (butlast profile-triples)) space-id))]
   ["undeclared spaces preserve freeform behavior"
    (empty? (kernel/lint-declared-profile negative-corpus space-id))]
   ["primitive anchor alone receives the bootstrap exemption"
    (= ["R1" "R4"] (lint-one (last negative-corpus)))]
   ["relational R1-R4 accept north's string-predicate write shape"
    (empty? (lint-one (t/triple "@thread" "progress" "")))]
   ["P3 admission and lint verdicts agree on the differential corpus"
    (every? p3-agrees? negative-corpus)]
   ["negative corpus exercises every relational rule"
    (= #{"R1" "R2" "R3" "R4"}
       (set (mapcat kernel/relational-lint-errors negative-corpus)))]])

(defn read-corpus [path]
  (with-open [reader (io/reader path)]
    (doall (map edn/read-string (line-seq reader)))))

(defn row-triple [row]
  (t/triple (:l row) (:p row) (:r row)))

(defn apply-row [live row]
  (let [proposition (row-triple row)]
    (if (= "retract" (:op row))
      (disj live proposition)
      (conj live proposition))))

(defn predicate-label [proposition]
  (pr-str (t/triple-slot1 proposition)))

(defn corpus-census! [path]
  (let [rows (read-corpus path)
        asserted (mapv row-triple (filter #(= "assert" (:op %)) rows))
        live (vec (reduce apply-row #{} rows))
        lint-input (into profile-triples live)
        violations (kernel/lint-declared-profile lint-input space-id)
        by-predicate (frequencies
                      (map #(predicate-label (t/triple-slot0 %)) violations))
        p3-mismatches (count (remove p3-agrees? asserted))]
    (println "CORPUS" path)
    (println "ROWS" (count rows))
    (println "ASSERT_WRITES" (count asserted))
    (println "LIVE_PROPOSITIONS" (count live))
    (println "PROFILE_VIOLATIONS" (count violations))
    (println "P3_MISMATCHES" p3-mismatches)
    (println "VIOLATIONS_BY_PREDICATE")
    (if (empty? by-predicate)
      (println "<none>\t0")
      (doseq [[predicate count] (sort-by key by-predicate)]
        (println (str predicate "\t" count))))))

(let [failures (remove second checks)]
  (doseq [[label ok] checks]
    (println (if ok "  [PASS]" "  [FAIL]") label))
  (if (empty? failures)
    (println "\nprofile lint:" (count checks) "/" (count checks) "PASS")
    (do
      (println "\nprofile lint:" (count failures) "FAILED")
      (System/exit 1))))

(when-let [path (System/getenv "FRAM_PROFILE_CORPUS")]
  (corpus-census! path))
