;; kernel_violations_test.clj — the GENERIC structural integrity rules the engine
;; KEEPS after the work-semantics rules moved out to tern.validate: dangling
;; refs (depends_on / part_of / relates_to → a thread with no `title`) and cycles
;; (depends_on / part_of). The WORK rules (person-refs, depends_on→abandoned) now
;; live in tern — see tern/validate_test.clj. Mirrors BOTH the indexed
;; (violations-i) and flat (violations) paths.
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

;; a non-existent target (no `title`) is a dangling entity ref.
(def dangling-dep  [(k/->Fact "@w" "title" "W") (k/->Fact "@w" "depends_on" "@ghost")])
(def dangling-part [(k/->Fact "@w" "title" "W") (k/->Fact "@w" "part_of" "@ghost")])
(def dangling-rel  [(k/->Fact "@w" "title" "W") (k/->Fact "@w" "relates_to" "@ghost")])

;; @a depends_on @b, @b depends_on @a => cycle.
(def dep-cycle
  [(k/->Fact "@a" "title" "A") (k/->Fact "@b" "title" "B")
   (k/->Fact "@a" "depends_on" "@b") (k/->Fact "@b" "depends_on" "@a")])

;; the engine must NOT derive a person/role violation (that moved to tern).
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
   ["(i) depends_on cycle detected" (has? (vi dep-cycle "@a") "depends_on cycle")]
   ["(f) depends_on cycle detected" (has? (vf dep-cycle "@a") "depends_on cycle")]
   ["(i) engine does NOT derive person-ref violations" (not (has? (vi role-facts "@w") "unknown person"))]
   ["(f) engine does NOT derive person-ref violations" (not (has? (vf role-facts "@w") "unknown person"))]])

(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nkernel_violations (generic):" (count checks) "/" (count checks) "PASS")
    (do (println "\nkernel_violations:" (count fails) "FAILED") (System/exit 1))))
