;; fold_cardinality_test.clj — schema-as-claims cardinality, the TWO-PASS fold.
;; Proves pass 1 (fram.fold/card-map) reads `@<pred> cardinality single|multi` claims —
;; @-prefix stripped, latest-:tx-wins per predicate, meta-preds seeded — and pass 2 keys
;; the fold by the EFFECTIVE cardinality (claim > env > fallback, via k/single-eff?), so
;; a cardinality claim overrides the env/fallback classification in BOTH directions and a
;; log with no cardinality claims folds identically to before.
;;   bb -cp out tests/fold_cardinality_test.clj
(require '[fram.fold :as fold] '[fram.kernel :as k])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))
(defn a [tx op l p r] (fold/->FactOp tx op l p r "test"))

;; --- pass 1: card-map -------------------------------------------------------
;; kernel-single 'title' forced MULTI; non-kernel 'tag' forced SINGLE; @ stripped.
(let [cm (fold/card-map [(a 1 "assert" "@title" "cardinality" "multi")
                         (a 2 "assert" "@tag"   "cardinality" "single")])]
  (chk "card-map strips @ and maps title->multi (false)" (= false (get cm "title")))
  (chk "card-map maps tag->single (true)"                (= true (get cm "tag")))
  (chk "meta-pred 'cardinality' seeded single"           (= true (get cm "cardinality"))))

;; latest-:tx-wins per predicate (a re-declaration supersedes).
(let [cm (fold/card-map [(a 1 "assert" "@foo" "cardinality" "single")
                         (a 2 "assert" "@foo" "cardinality" "multi")])]
  (chk "latest-tx wins: foo single(tx1) then multi(tx2) -> multi" (= false (get cm "foo"))))

;; a retracted declaration falls back (drops out of the map -> env/fallback).
(let [cm (fold/card-map [(a 1 "assert"  "@bar" "cardinality" "single")
                         (a 2 "retract" "@bar" "cardinality" "single")])]
  (chk "retracted declaration falls back (bar absent from map)" (nil? (get cm "bar"))))

;; --- single-eff?: claim > env > fallback ------------------------------------
(let [cm {"title" false "tag" true}]
  (chk "single-eff?: claim forces kernel-single 'title' -> NOT single" (= false (k/single-eff? cm "title")))
  (chk "single-eff?: claim forces non-kernel 'tag' -> single"          (= true  (k/single-eff? cm "tag")))
  (chk "single-eff?: undeclared pred falls back to single? (title kernel-single)"
       (= (k/single? "title") (k/single-eff? {} "title"))))

;; --- pass 2: the whole fold honors the claims -------------------------------
;; title forced multi -> BOTH values survive; tag forced single -> latest only.
(let [f (fold/fold [(a 1 "assert" "@title" "cardinality" "multi")
                    (a 2 "assert" "@tag"   "cardinality" "single")
                    (a 3 "assert" "@T1"    "title" "A")
                    (a 4 "assert" "@T1"    "title" "B")
                    (a 5 "assert" "@T1"    "tag"   "x")
                    (a 6 "assert" "@T1"    "tag"   "y")])
      cl (:facts f)
      titles (set (map :r (filter #(and (= (:l %) "@T1") (= (:p %) "title")) cl)))
      tags   (set (map :r (filter #(and (= (:l %) "@T1") (= (:p %) "tag"))   cl)))]
  (chk "fold: kernel-single 'title' forced MULTI keeps {A,B}" (= #{"A" "B"} titles))
  (chk "fold: non-kernel 'tag' forced SINGLE collapses to {y}" (= #{"y"} tags)))

;; back-compat: a log with NO cardinality claims folds by env/fallback exactly as before.
(let [f (fold/fold [(a 1 "assert" "@T1" "title" "A")
                    (a 2 "assert" "@T1" "title" "B")   ; title is kernel-single -> B wins
                    (a 3 "assert" "@T1" "tag"   "x")
                    (a 4 "assert" "@T1" "tag"   "y")]) ; tag is multi -> both
      cl (:facts f)]
  (chk "no-claim fold: kernel-single title collapses to latest B"
       (= #{"B"} (set (map :r (filter #(and (= (:l %) "@T1") (= (:p %) "title")) cl)))))
  (chk "no-claim fold: multi tag keeps {x,y}"
       (= #{"x" "y"} (set (map :r (filter #(and (= (:l %) "@T1") (= (:p %) "tag")) cl))))))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfold-cardinality:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfold-cardinality:" (count fails) "FAILED") (System/exit 1))))
