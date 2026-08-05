;; :facts-for-subjects must be O(the requested slice), not O(the corpus), and
;; must agree exactly with the whole-wire projection restricted to those
;; subjects.  Run:
;;   bb -cp out tests/database_scoped_facts_test.clj
;;
;; The op exists because north's board verbs downloaded all 353,442 facts
;; (59.4 MB) to render the 4,131 subjects carrying a title (55,893 facts,
;; 10.4 MB), and re-indexed the whole corpus in a fresh process every
;; invocation — 8.5-12.5 s per verb against 200 ms for exact `show`. A slice
;; The saving is the CLIENT's: transfer, decode and index build all shrink to
;; the slice. Server-side this deliberately REUSES the per-version wire cache
;; rather than projecting each subject from the by-l index — measured on the
;; real corpus, filtering the cache is 48 ms against 778 ms per-subject, and it
;; keeps rows in global fold order so a slice equals the whole response
;; filtered. The ratchet below pins that it never pays the O(corpus)
;; RE-projection, which is the regression that would silently restore the cost.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(binding [*command-line-args* []] (load-file "server.clj"))
(reset! telemetry-log nil)

(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(def tmp-dir
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-scoped-facts"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (.getCanonicalPath (io/file tmp-dir "facts.log")))

(def a "@019fa4d4-93aa-7447-aae5-0a5bcfca6849")
(def b "@019fa4d4-93aa-7447-aae5-0a5bcfca684a")

(defn line [tx l p r]
  (pr-str {:tx tx :op "assert" :l l :p p :r r :ts "t" :by "fixture"}))

;; A nontrivial corpus exercises canonical ordering across several subjects.
(spit log-path
      (str
       (str/join
        "\n"
        (concat
         [(line 1 a "title" "first subject")
          (line 2 a "progress" "ready")
          (line 3 a "owner" "personal")
          (line 4 a "depends_on" "@other")
          (line 5 b "title" "second subject")
          (line 6 b "progress" "blocked")]
         (for [i (range 1 40)]
           (line (+ 10 i) (str "@other-" i) "title" (str "value-" i)))
         [(line 100 "@title" "cardinality" "single")]))
       "\n"))

(reset! snapshot-boot-enabled? false)
(boot-flat! log-path)

(check! "fixture exercises a nontrivial multi-subject corpus"
        (> (count (:triples (facts-wire-snapshot))) 8))

(defn wire-slice
  "The whole-wire projection restricted to SUBJECTS — the oracle."
  [subjects]
  (let [wanted (set subjects)]
    (filterv (fn [[l _ _]] (contains? wanted l))
             (:triples (facts-wire-snapshot)))))

;; --- agreement with the oracle ----------------------------------------------
(let [scoped (:facts (scoped-facts-snapshot [a]))]
  (check! "one subject's slice is set-equal to the wire projection"
          (= (set (wire-slice [a])) (set scoped)))
  (check! "one subject's slice preserves the wire row order"
          (= (wire-slice [a]) scoped))
  (check! "slice rows are full [l p r] triples, not [p r] pairs"
          (every? #(and (vector? %) (= 3 (count %))) scoped)))

(let [scoped (:facts (scoped-facts-snapshot [a b]))]
  (check! "a multi-subject slice is set-equal to the wire projection"
          (= (set (wire-slice [a b])) (set scoped)))
  (check! "every requested subject contributes its facts"
          (= #{a b} (set (map first scoped)))))

;; Request order cannot override the canonical whole-corpus order.
(let [scoped (:facts (scoped-facts-snapshot [b a]))]
  (check! "facts remain in canonical subject order when the request is reversed"
          (= [a b] (vec (distinct (map first scoped)))))
  (check! "reordering the request does not change the fact SET"
          (= (set (:facts (scoped-facts-snapshot [a b]))) (set scoped))))

(check! "log-authoritative schema facts are included"
        (= (set (wire-slice ["@title"]))
           (set (:facts (scoped-facts-snapshot ["@title"])))))

;; --- absence and duplication ------------------------------------------------
(check! "an unknown subject yields no facts rather than an error"
        (= [] (:facts (scoped-facts-snapshot ["@missing"]))))
(check! "an empty request yields an empty slice"
        (= [] (:facts (scoped-facts-snapshot []))))

;; A repeated subject must not duplicate its facts: a duplicate triple corrupts
;; every by-predicate read downstream, because kernel/build-index accumulates
;; multi-valued predicates with (conj (get m kk []) r).
(check! "a repeated subject does not duplicate its facts"
        (= (:facts (scoped-facts-snapshot [a]))
           (:facts (scoped-facts-response {:subjects [a a a]}))))

;; --- the response carries its identity --------------------------------------
(let [r (scoped-facts-snapshot [a])]
  (check! "the slice reports the captured version" (integer? (:version r)))
  (check! "the slice reports the log it was served from"
          (= log-path (:log r))))

;; --- input validation -------------------------------------------------------
(let [r (scoped-facts-response {:subjects "not-a-sequence"})]
  (check! "a non-sequential :subjects is a typed rejection"
          (= :invalid-scoped-subjects (:code r))))
(let [r (scoped-facts-response {:subjects [a 42]})]
  (check! "a non-string subject is a typed rejection"
          (= :invalid-scoped-subjects (:code r))))
(let [r (scoped-facts-response {:subjects (vec (repeat (inc max-scoped-subjects) a))})]
  (check! "an oversized request is refused rather than served"
          (= :too-many-scoped-subjects (:code r))))
(check! "a missing :subjects key is rejected, not treated as empty"
        (= :invalid-scoped-subjects (:code (scoped-facts-response {}))))

;; --- THE RATCHET ------------------------------------------------------------
;; A slice that re-projects the whole corpus still returns correct answers, so
;; correctness assertions alone cannot detect the regression this op exists to
;; prevent. client-view-facts-from IS the O(corpus) projection; make it fatal
;; and prove a warm slice never reaches it.
(facts-wire-snapshot)                       ; ensure the per-version cache is warm

(with-redefs [server/client-view-facts-from
              (fn [& _] (throw (ex-info "whole client projection selected" {})))]
  (let [scoped (handle {:op :facts-for-subjects :subjects [a b]})]
    (check! "a warm slice is served without re-projecting the corpus"
            (= #{a b} (set (map first (:facts scoped)))))
    (check! "the ratcheted slice still carries its version"
            (integer? (:version scoped)))))

;; The point of the op: the client receives its slice, not the corpus.
(let [corpus (count (:triples (facts-wire-snapshot)))
      slice (count (:facts (handle {:op :facts-for-subjects :subjects [a]})))]
  (check! "the slice is strictly smaller than the corpus"
          (< 0 slice corpus))
  (check! "the slice carries no unrequested subject"
          (every? #(= a (first %))
                  (:facts (handle {:op :facts-for-subjects :subjects [a]})))))

;; Rows stay in GLOBAL fold order, so a slice is the whole response filtered —
;; callers inherit ordering rather than having to re-sort.
(check! "a slice equals the whole wire projection filtered"
        (= (wire-slice [a b])
           (:facts (handle {:op :facts-for-subjects :subjects [a b]}))))

;; And it must stay correct after an unrelated write moves the global version,
;; which invalidates the cache the fast path depends on.
(let [before (set (wire-slice [a]))
      write (handle {:op :assert :te "@unrelated" :p "title" :r "new"})
      scoped (handle {:op :facts-for-subjects :subjects [a]})]
  (check! "the unrelated write advanced the global version" (:ok write))
  (check! "a post-write slice is still correct after cache invalidation"
          (= before (set (:facts scoped))))
  (check! "a post-write slice reports the new version"
          (= (:ok write) (:version scoped))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do (println "\ndatabase-scoped-facts:" (count failures) "FAILED")
        (System/exit 1))
    (println "\ndatabase-scoped-facts:" (count @checks) "/" (count @checks) "PASS")))
