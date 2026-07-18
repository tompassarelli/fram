;; kernel_violations_test.clj — the GENERIC structural integrity rules the engine
;; KEEPS after the work-semantics rules moved out to north.validate: dangling
;; refs (a ref target has no live facts at all) and cycles. Title/thread shape is
;; consumer policy, not generic entity existence. The WORK rules (thread-only
;; refs, person-refs, depends_on→abandoned) now live in north — see
;; north/validate_test.clj. Mirrors BOTH the indexed (violations-i) and flat
;; (violations) paths.
;;   bb -cp out tests/kernel_violations_test.clj      (from the repo ROOT)
(require '[fram.kernel :as k])

(defn idx-of [facts] (k/build-index facts))
(defn has? [v sub] (some #(clojure.string/includes? % sub) v))
(defn vi [facts te] (k/violations-i (idx-of facts) te))
(defn vf [facts te] (k/violations facts te))

;; @w1 -> @w2 (a real thread); clean.
(def ok-facts
  [(k/->Fact "@w1" "title" "W1")
   (k/->Fact "@w2" "title" "W2")
   (k/->Fact "@w1" "depends_on" "@w2")])

;; A non-existent target (not the subject of any fact) is a dangling entity ref.
(def dangling-dep  [(k/->Fact "@w" "title" "W") (k/->Fact "@w" "depends_on" "@ghost")])
(def dangling-part [(k/->Fact "@w" "title" "W") (k/->Fact "@w" "part_of" "@ghost")])
(def dangling-rel  [(k/->Fact "@w" "title" "W") (k/->Fact "@w" "relates_to" "@ghost")])

;; A declared generic ref may target a titleless entity. Integration links are
;; intentionally fact-bearing entities, not threads.
(def titleless-link
  [(k/->Fact "@linear_link" "value_kind" "ref")
   (k/->Fact "@w" "title" "W")
   (k/->Fact "@w" "linear_link" "@link:linear:fixture")
   (k/->Fact "@link:linear:fixture" "kind" "integration_link")])

(def missing-link
  [(k/->Fact "@linear_link" "value_kind" "ref")
   (k/->Fact "@w" "title" "W")
   (k/->Fact "@w" "linear_link" "@link:linear:missing")])

;; @a depends_on @b, @b depends_on @a => cycle.
(def dep-cycle
  [(k/->Fact "@a" "title" "A") (k/->Fact "@b" "title" "B")
   (k/->Fact "@a" "depends_on" "@b") (k/->Fact "@b" "depends_on" "@a")])

;; the engine must NOT derive a person/role violation (that moved to north).
(def role-facts [(k/->Fact "@w" "title" "W") (k/->Fact "@w" "driver" "@ghost")])

(def checks
  [["(i) clean graph => no violations" (empty? (vi ok-facts "@w1"))]
   ["(f) clean graph => no violations" (empty? (vf ok-facts "@w1"))]
   ["(i) depends_on -> ghost => missing entity" (has? (vi dangling-dep "@w") "depends_on references missing entity @ghost")]
   ["(f) depends_on -> ghost => missing entity" (has? (vf dangling-dep "@w") "depends_on references missing entity @ghost")]
   ["(i) part_of -> ghost => missing entity" (has? (vi dangling-part "@w") "part_of references missing entity @ghost")]
   ["(f) part_of -> ghost => missing entity" (has? (vf dangling-part "@w") "part_of references missing entity @ghost")]
   ["(i) relates_to -> ghost => missing entity" (has? (vi dangling-rel "@w") "relates_to references missing entity @ghost")]
   ["(f) relates_to -> ghost => missing entity" (has? (vf dangling-rel "@w") "relates_to references missing entity @ghost")]
   ["(i) declared ref -> titleless fact entity => clean" (empty? (vi titleless-link "@w"))]
   ["(f) declared ref -> titleless fact entity => clean" (empty? (vf titleless-link "@w"))]
   ["(i) declared ref -> absent subject => missing entity" (has? (vi missing-link "@w") "linear_link references missing entity @link:linear:missing")]
   ["(f) declared ref -> absent subject => missing entity" (has? (vf missing-link "@w") "linear_link references missing entity @link:linear:missing")]
   ["(i) depends_on cycle detected" (has? (vi dep-cycle "@a") "depends_on cycle")]
   ["(f) depends_on cycle detected" (has? (vf dep-cycle "@a") "depends_on cycle")]
   ["(i) engine does NOT derive person-ref violations" (not (has? (vi role-facts "@w") "unknown person"))]
   ["(f) engine does NOT derive person-ref violations" (not (has? (vf role-facts "@w") "unknown person"))]])

(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nkernel_violations (generic):" (count checks) "/" (count checks) "PASS")
    (do (println "\nkernel_violations:" (count fails) "FAILED") (System/exit 1))))
