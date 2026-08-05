;; Exact snapshot reader :show must remain O(subject), including immediately after
;; an unrelated write changes the global version.  Run:
;;   bb -cp out tests/snapshot_show_indexed_test.clj
(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(binding [*command-line-args* []] (load-file "server.clj"))

(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(def tmp-dir
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-indexed-show"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (.getCanonicalPath (io/file tmp-dir "facts.log")))
(def target "@019fa4d4-93aa-7447-aae5-0a5bcfca6849")

(defn line [tx l p r]
  (pr-str {:tx tx :op "assert" :l l :p p :r r :ts "t" :by "fixture"}))

;; More than eight fold keys forces the production hash-map ordering regime.
(spit log-path
      (str
       (str/join
        "\n"
        (concat
         [(line 1 target "title" "indexed show")
          (line 2 target "progress" "ready")
          (line 3 target "owner" "personal")
          (line 4 target "depends_on" "@other")]
         (for [i (range 1 40)]
           (line (+ 10 i) (str "@other-" i) "title" (str "value-" i)))
         [(line 100 "@title" "cardinality" "single")]))
       "\n"))

(reset! snapshot-boot-enabled? false)
(boot-flat! log-path)

(let [full (:triples (facts-wire-snapshot))
      expected (reduce (fn [rows [l p r]]
                         (if (= l target) (conj rows [p r]) rows))
                       [] full)
      indexed (:rows (subject-wire-snapshot target))]
  (check! "indexed subject projection is set-equal to full wire projection"
          (= (set expected) (set indexed)))
  (check! "indexed subject projection preserves full wire row order"
          (= expected indexed)))

(let [schema-full (:triples (facts-wire-snapshot))
      schema-expected
      (reduce (fn [rows [l p r]]
                (if (= l "@title") (conj rows [p r]) rows))
              [] schema-full)
      schema-indexed (:rows (subject-wire-snapshot "@title"))]
  (check! "indexed show includes log-authoritative schema facts"
          (= schema-expected schema-indexed)))

;; Structural ratchet: neither a cold full-wire cache nor the O(corpus) client
;; projection may be selected by :show.
(with-redefs [server/facts-wire-snapshot
              (fn [& _]
                (throw (ex-info "whole wire projection selected" {})))
              server/client-view-facts-from
              (fn [& _]
                (throw (ex-info "whole client projection selected" {})))]
  (let [first-show (handle {:op :show :te target})
        write-result (handle {:op :assert :te "@unrelated"
                              :p "title" :r "new version"})
        second-show (handle {:op :show :te target})
        missing-show (handle {:op :show :te "@missing"})]
    (check! "exact show succeeds without any whole-corpus projection"
            (= #{["title" "indexed show"]
                 ["progress" "ready"]
                 ["owner" "personal"]
                 ["depends_on" "@other"]}
               (set (:rows first-show))))
    (check! "unrelated write advances the global version" (:ok write-result))
    (check! "post-write exact show remains subject-indexed"
            (= (set (:rows first-show)) (set (:rows second-show))))
    (check! "post-write exact show reports the new captured version"
            (= (:ok write-result) (:version second-show)))
    (check! "missing exact show is an indexed empty result"
            (empty? (:rows missing-show)))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do
      (println "\nsnapshot-show-indexed:" (count failures) "FAILED")
      (System/exit 1))
    (println "\nsnapshot-show-indexed:"
             (count @checks) "/" (count @checks) "PASS")))
