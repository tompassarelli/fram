;; Regression for graph cold start spending one full predicate-registry scan per
;; domain fact. The compiled migration map must equal the kernel oracle while
;; classification work stays bounded by predicate spellings, not fact count.
(require '[fram.fold :as fold]
         '[fram.kernel :as ck]
         '[coord-commit :as cc])
(load-file "coord_daemon.clj")

(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok
    (throw (ex-info label {}))))

(def schema-lines
  [{:tx 1 :op "assert" :l "@edge" :p "predicate_name" :r "edge"}
   {:tx 2 :op "assert" :l "@edge" :p "predicate_alias" :r "old_edge"}
   {:tx 3 :op "assert" :l "@edge" :p "value_kind" :r "ref"}
   {:tx 4 :op "assert" :l "@depends_on" :p "value_kind" :r "literal"}
   {:tx 5 :op "assert" :l "@title" :p "cardinality" :r "multi"}
   {:tx 6 :op "assert" :l "@single_note" :p "cardinality" :r "single"}])

(def representative-preds
  ["edge" "old_edge" "depends_on" "title" "single_note"
   "session_of" "f0" "f1.2~7" "child" "tail" "seg3" "comment4"
   "ordinary"])

(def domain-lines
  (mapv
   (fn [i]
     (let [p (nth representative-preds (mod i (count representative-preds)))]
       {:tx (+ 10 i)
        :op "assert"
        :l (str "@subject-" i)
        :p p
        :r (if (#{"edge" "old_edge" "depends_on" "session_of"
                  "f0" "f1.2~7" "child"
                  "tail" "seg3" "comment4"} p)
             (str "@target-" i)
             (str "value-" i))}))
   (range 4000)))

(def raw (into schema-lines domain-lines))
(def facts (:facts (fold/fold raw)))
(def metadata-facts
  (filterv #(#'coord-daemon/schema-writable (:p %)) facts))
(def legacy-config
  (#'coord-daemon/legacy-ref-config facts))
(def schema-plan
  (cc/migrate-schema-plan
   (vec (distinct representative-preds))
   []
   (set representative-preds)
   @#'coord-daemon/schema-preds))

(def oracle
  (into {}
        (map (fn [p]
               (let [configured
                     (if (#'coord-daemon/code-structural-link-pred? p)
                       (assoc legacy-config p "ref")
                       legacy-config)
                     value-kind (ck/value-kind-of facts configured p)]
                 [p {:cardinality (ck/cardinality-of facts {} p)
                     :value-kind value-kind
                     :link? (= "ref" value-kind)}])))
        representative-preds))

(def classifications
  (#'coord-daemon/migrate-predicate-classifications
   metadata-facts schema-plan legacy-config))

(check! "compiled classifications equal the effective migration oracle"
        (= oracle classifications))
(check! "explicit ref applies through canonical name and alias"
        (every? #(= "ref" (get-in classifications [% :value-kind]))
                ["edge" "old_edge"]))
(check! "explicit literal overrides the depends_on ref fallback"
        (let [classification (get classifications "depends_on")]
          (and (= "literal" (:value-kind classification))
               (false? (:link? classification)))))
(check! "legacy session_of rows preserve their per-predicate reference kind"
        (= {:value-kind "ref" :link? true}
           (select-keys (get classifications "session_of")
                        [:value-kind :link?])))
(check! "structural code predicates remain links"
        (every? #(= {:value-kind "ref" :link? true}
                    (select-keys (get classifications %)
                                 [:value-kind :link?]))
                ["f0" "f1.2~7" "child" "tail" "seg3" "comment4"]))

(let [value-kind-calls (atom 0)
      cardinality-calls (atom 0)
      original-value-kind ck/value-kind-of
      original-cardinality ck/cardinality-of
      migrate #'coord-daemon/migrate-flat->co
      reader #'coord-daemon/read-logs-merged
      result
      (with-redefs [ck/value-kind-of
                    (fn [fs configured p]
                      (swap! value-kind-calls inc)
                      (original-value-kind fs configured p))
                    ck/cardinality-of
                    (fn [fs configured p]
                      (swap! cardinality-calls inc)
                      (original-cardinality fs configured p))]
        (with-redefs-fn {reader (fn [_] raw)}
          #(migrate "unused")))]
  (check! "migration preserves every representative domain fact"
          (= 4000
             (count
              (remove #(#{":cardinality" ":value-kind"} (:p %))
                      (#'coord-daemon/reified->facts result)))))
  (check! "startup classification calls are bounded by predicate spellings"
          (and (<= @value-kind-calls (count representative-preds))
               (<= @cardinality-calls (count representative-preds)))))

(println "coord migrate predicate classification — PASS")
