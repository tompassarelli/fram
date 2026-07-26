;; Covering-rotation index receipt (thread 019f9e66).
;;
;; Proves the three properties the index architecture rests on:
;;   A. COVERING — all 8 bound-subsets of {s,p,o} are answered by an EXACT bucket,
;;      and every answer equals a brute-force filter of the same triples.
;;   B. INCREMENTAL == FRESH — a sequence of adds/dels leaves an index that is
;;      VALUE-EQUAL (not merely answer-equal) to a from-scratch build. This is the
;;      whole basis for never invalidating on write.
;;   C. PROJECTION EQUIVALENCE — the O(1) rotations projection makes fram.query
;;      return exactly what the whole-corpus q/project projection returns, over a
;;      randomized corpus and a battery of query shapes (joins, negation, recursion,
;;      derived relations, aggregates, paging).
;;   D. SEGMENTS — a published content-addressed segment set round-trips the exact
;;      triple set, is verified on open, and fails CLOSED on tamper.
;;
;; Run: bb -cp out tests/coord_rotations_test.clj
(require '[clojure.java.io :as io]
         '[clojure.set]
         '[fram.query :as q]
         '[fram.kernel :as ck])
(load-file "rotations.clj")

(def failures (atom []))
(defn check! [label ok]
  (println (str (if ok "PASS " "FAIL ") label))
  (when-not ok (swap! failures conj label)))

;; ---------------------------------------------------------------------------
;; a deterministic pseudo-random corpus (no test flake, but structurally varied:
;; repeated subjects, repeated predicates, values that are ALSO subjects — the
;; flat-value-space property that makes OSP load-bearing).
(def rng (java.util.Random. 424242))
(defn pick [v] (nth v (.nextInt rng (count v))))
(def subjects (mapv #(str "@n" %) (range 60)))
(def preds ["title" "kind" "lead" "depends_on" "tag" "owner"])
(def objects (into (mapv #(str "v" %) (range 40)) subjects))

(def corpus
  (vec (distinct (for [_ (range 1200)] [(pick subjects) (pick preds) (pick objects)]))))

(def idx (rotations/build corpus))

;; ---- A. covering ----------------------------------------------------------
(defn brute [triples [s p o]]
  (into #{} (filter (fn [[a b c]] (and (or (nil? s) (= s a))
                                       (or (nil? p) (= p b))
                                       (or (nil? o) (= o c))))
                    triples)))

(let [probes (concat
              [[nil nil nil]]
              (for [s (take 8 subjects)] [s nil nil])
              (for [p preds] [nil p nil])
              (for [o (take 8 objects)] [nil nil o])
              (for [s (take 6 subjects) p (take 3 preds)] [s p nil])
              (for [p (take 3 preds) o (take 6 objects)] [nil p o])
              ;; the subset the pre-rotation index had NO exact bucket for
              (for [s (take 6 subjects) o (take 6 objects)] [s nil o])
              (for [t (take 20 corpus)] (vec t)))
      bad (remove (fn [pat] (= (rotations/matching idx pat) (brute corpus pat))) probes)]
  (check! (str "A. covering: all 8 bound-subsets exact over " (count probes) " probes")
          (empty? bad))
  (when (seq bad) (println "   first bad pattern:" (pr-str (first bad)))))

;; the {s,o} probe must be an EXACT bucket, i.e. it must not be the whole relation
;; nor a superset needing re-filtering. Assert it is served by :os directly.
(let [[s _ o] (first corpus)]
  (check! "A. {s,o} is served by the OSP rotation as an exact bucket"
          (= (rotations/matching idx [s nil o]) (get (:os idx) [o s]))))

;; ---- B. incremental == fresh ----------------------------------------------
(let [half (vec (take 600 corpus))
      extra (vec (drop 600 corpus))
      ;; add everything, then delete the extras back out
      churned (as-> (rotations/build half) ix
                (reduce rotations/add ix extra)
                (reduce rotations/del ix extra))
      fresh (rotations/build half)]
  (check! "B. add-then-delete returns a VALUE-EQUAL index (no dangling buckets)"
          (= (select-keys churned [:tuples :s :p :o :sp :po :os])
             (select-keys fresh [:tuples :s :p :o :sp :po :os]))))

(let [interleaved (reduce (fn [ix [op t]] (if (= op :+) (rotations/add ix t) (rotations/del ix t)))
                          rotations/empty-index
                          (concat (map (fn [t] [:+ t]) corpus)
                                  (map (fn [t] [:- t]) (take 300 corpus))
                                  (map (fn [t] [:+ t]) (take 150 corpus))))
      expected (rotations/build (concat (drop 300 corpus) (take 150 corpus)))]
  (check! "B. interleaved churn == fresh build of the surviving set"
          (= (select-keys interleaved [:tuples :s :p :o :sp :po :os])
             (select-keys expected [:tuples :s :p :o :sp :po :os]))))

(check! "B. empty index round-trips to empty"
        (= rotations/empty-index
           (select-keys (rotations/del (rotations/add rotations/empty-index ["@x" "p" "v"])
                                       ["@x" "p" "v"])
                        (keys rotations/empty-index))))

;; ---- C. projection equivalence vs the whole-corpus oracle ------------------
(def facts (mapv (fn [[l p r]] (ck/->Fact l p r)) corpus))
(def oracle-projection (q/project facts))
(def rot-projection (rotations/datalog-projection idx))

(def query-shapes
  {"single literal, bound predicate"
   {:find "r1" :rules [{:head {:rel "r1" :args [{:var "s"} {:var "o"}]}
                        :body [{:rel "triple" :args [{:var "s"} "title" {:var "o"}]}]}]}

   "two-literal self join"
   {:find "r2" :rules [{:head {:rel "r2" :args [{:var "s"} {:var "t"}]}
                        :body [{:rel "triple" :args [{:var "s"} "kind" {:var "k"}]}
                               {:rel "triple" :args [{:var "s"} "title" {:var "t"}]}]}]}

   "object-bound (OSP) probe"
   {:find "r3" :rules [{:head {:rel "r3" :args [{:var "s"} {:var "p"}]}
                        :body [{:rel "triple" :args [{:var "s"} {:var "p"} "@n7"]}]}]}

   "three-hop chain"
   {:find "r4" :rules [{:head {:rel "r4" :args [{:var "a"} {:var "c"}]}
                        :body [{:rel "triple" :args [{:var "a"} "depends_on" {:var "b"}]}
                               {:rel "triple" :args [{:var "b"} "depends_on" {:var "c"}]}]}]}

   "derived relation over two rules"
   {:find "r5" :rules [{:head {:rel "kinded" :args [{:var "s"}]}
                        :body [{:rel "triple" :args [{:var "s"} "kind" {:var "k"}]}]}
                       {:head {:rel "r5" :args [{:var "s"} {:var "t"}]}
                        :body [{:rel "kinded" :args [{:var "s"}]}
                               {:rel "triple" :args [{:var "s"} "title" {:var "t"}]}]}]}

   "recursive transitive closure"
   {:find "reach" :rules [{:head {:rel "reach" :args [{:var "a"} {:var "b"}]}
                           :body [{:rel "triple" :args [{:var "a"} "depends_on" {:var "b"}]}]}
                          {:head {:rel "reach" :args [{:var "a"} {:var "c"}]}
                           :body [{:rel "reach" :args [{:var "a"} {:var "b"}]}
                                  {:rel "triple" :args [{:var "b"} "depends_on" {:var "c"}]}]}]}

   "stratified negation"
   {:find "r7" :strata [[{:head {:rel "tagged" :args [{:var "s"}]}
                          :body [{:rel "triple" :args [{:var "s"} "tag" {:var "v"}]}]}]
                        [{:head {:rel "r7" :args [{:var "s"}]}
                          :body [{:rel "triple" :args [{:var "s"} "title" {:var "t"}]}
                                 {:rel "tagged" :neg true :args [{:var "s"}]}]}]]}

   "aggregate count by predicate"
   {:find {:rel "byp" :group [0] :aggs [{:op :count}]}
    :rules [{:head {:rel "byp" :args [{:var "p"} {:var "s"}]}
             :body [{:rel "triple" :args [{:var "s"} {:var "p"} {:var "o"}]}]}]}

   "unbound-everything scan"
   {:find "r9" :rules [{:head {:rel "r9" :args [{:var "s"} {:var "p"} {:var "o"}]}
                        :body [{:rel "triple" :args [{:var "s"} {:var "p"} {:var "o"}]}]}]}})

(let [mismatches
      (for [[label qq] query-shapes
            :let [a (q/run-projected oracle-projection qq)
                  b (q/run-projected rot-projection qq)
                  norm (fn [r] (if (:ok r) (assoc r :ok (set (:ok r))) r))]
            :when (not= (norm a) (norm b))]
        [label (select-keys a [:error]) (select-keys b [:error])])]
  (check! (str "C. rotations projection == whole-corpus projection on all "
               (count query-shapes) " query shapes")
          (empty? mismatches))
  (doseq [m mismatches] (println "   mismatch:" (pr-str m))))

;; paging must agree too, page for page, including the cursors.
(let [pq (get query-shapes "single literal, bound predicate")
      pages (fn [proj]
              (loop [after nil acc [] guard 0]
                (let [r (q/run-page-projected proj pq 25 after)]
                  (if (or (:error r) (nil? (:next r)) (> guard 200))
                    (conj acc r)
                    (recur (:next r) (conj acc r) (inc guard))))))]
  (check! "C. run-page-projected agrees page-for-page (rows AND cursors)"
          (= (pages oracle-projection) (pages rot-projection))))

;; fact-id is deliberately ABSENT from the rotations projection; prove that (a) it
;; is absent and (b) the whole-corpus path still serves it, so the daemon's
;; mentions-fact-id? routing is a real necessity and not superstition.
(let [fq {:find "fid" :rules [{:head {:rel "fid" :args [{:var "c"} {:var "s"}]}
                               :body [{:rel "fact-id" :args [{:var "c"} {:var "s"} "title" {:var "o"}]}]}]}
      via-oracle (q/run-projected oracle-projection fq)
      via-rot (q/run-projected rot-projection fq)]
  (check! "C. fact-id is absent from the rotations projection (routing is required)"
          (and (nil? (get (:edb rot-projection) "fact-id"))
               (seq (:ok via-oracle))
               (empty? (:ok via-rot)))))

;; ---- D. content-addressed segments ----------------------------------------
(def seg-root (str (System/getProperty "java.io.tmpdir") "/fram-rot-seg-" (System/nanoTime)))

(let [manifest (rotations/write-set! seg-root corpus {:watermark 4242 :byte-offset 99
                                                      :fold-fingerprint "fp-abc"
                                                      :log-identity "log-xyz"})
      opened (rotations/open-set seg-root {:fold-fingerprint "fp-abc" :log-identity "log-xyz"})]
  (check! "D. manifest names three rotations + one dictionary over the flat space"
          (and (= #{:spo :pos :osp} (set (keys (:segments manifest))))
               (= (count (into #{} cat (map vec corpus)))
                  (get-in manifest [:dictionary :count]))))
  (check! "D. every segment is named by the sha256 of its own bytes"
          (every? (fn [[_ s]] (= (str "segments/" (:sha256 s) ".rot") (:file s)))
                  (:segments manifest)))
  (check! "D. opened set round-trips the EXACT triple set" (some? opened))
  (when opened
    (check! "D. segment-triples == the corpus"
            (= (set (map vec corpus)) (set (rotations/segment-triples opened))))
    (check! "D. an index rebuilt from segments == a fresh build"
            (= (select-keys (rotations/build (rotations/segment-triples opened))
                            [:tuples :s :p :o :sp :po :os])
               (select-keys idx [:tuples :s :p :o :sp :po :os])))
    (rotations/close-set! opened))

  (check! "D. provenance mismatch fails CLOSED (no stale set is ever opened)"
          (nil? (rotations/open-set seg-root {:fold-fingerprint "fp-DIFFERENT"})))

  ;; write the SAME triples again: content addressing must reuse the identical files.
  (let [again (rotations/write-set! seg-root corpus {:watermark 4243 :byte-offset 100
                                                     :fold-fingerprint "fp-abc"
                                                     :log-identity "log-xyz"})]
    (check! "D. republishing an unchanged set reuses the identical content files"
            (= (into {} (map (fn [[k v]] [k (:sha256 v)])) (:segments manifest))
               (into {} (map (fn [[k v]] [k (:sha256 v)])) (:segments again)))))

  ;; tamper with a segment's bytes: the content hash must reject the whole set.
  (let [f (io/file seg-root (get-in manifest [:segments :pos :file]))
        bs (java.nio.file.Files/readAllBytes (.toPath f))]
    (aset-byte bs (dec (alength bs)) (unchecked-byte (bit-xor 0xff (aget bs (dec (alength bs))))))
    (java.nio.file.Files/write (.toPath f) bs
                               (into-array java.nio.file.OpenOption
                                           [java.nio.file.StandardOpenOption/TRUNCATE_EXISTING
                                            java.nio.file.StandardOpenOption/WRITE]))
    (check! "D. a tampered segment fails CLOSED on open"
            (nil? (rotations/open-set seg-root {:fold-fingerprint "fp-abc"})))))

;; ---------------------------------------------------------------------------
(println)
(if (empty? @failures)
  (println "coord_rotations_test: ALL PASS")
  (do (println "coord_rotations_test FAILURES:" (pr-str @failures))
      (System/exit 1)))
