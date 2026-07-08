;; doctor_fingerprint_test.clj — `fram doctor` surfaces the CLAIMS-DERIVED cardinality
;; source (the finding #23 operator cross-check). Drives the real CLI over a synthetic
;; log with a `@<pred> cardinality single` claim (FRAM_PORT points at a dead port so the
;; live coordinator can't leak in) and asserts doctor reports the claims-derived count,
;; the cardinality-overlay fingerprint (which reflects the claim), and the claim > env >
;; fallback precedence note.
;;   bb -cp out tests/doctor_fingerprint_test.clj
(require '[babashka.process :as p] '[clojure.string :as str])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))

(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-doctor-test-" (System/nanoTime)))
(.mkdirs (java.io.File. tmp))
(def logpath (str tmp "/claims.log"))
(spit logpath
  (str/join "\n"
    ['{:tx 1 :op "assert" :l "@tag" :p "cardinality" :r "single" :frame "test"}
     '{:tx 2 :op "assert" :l "@T1"  :p "title" :r "A" :frame "test"}
     '{:tx 3 :op "assert" :l "@T1"  :p "tag"   :r "x" :frame "test"}]))

(def out (:out (p/shell {:out :string :err :string
                         :extra-env {"FRAM_LOG" logpath "FRAM_THREADS" tmp "FRAM_PORT" "59997"}}
                        "bb" "-cp" "out" "-m" "fram.main" "doctor")))

(chk "doctor reports a claims-derived cardinality count (>=1)"
     (boolean (re-find #"cardinality-claims:\s*[1-9]\d*\s+claims-derived" out)))
(chk "doctor's cardinality-overlay fingerprint reflects the claim (tag=single)"
     (and (str/includes? out "cardinality-overlay") (str/includes? out "tag=single")))
(chk "doctor names the claim > env > fallback precedence (claims overlay authoritative)"
     (str/includes? out "vocab-source: claims (overlay)"))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\ndoctor-fingerprint:" (count cs) "/" (count cs) "PASS")
    (do (println "\ndoctor-fingerprint:" (count fails) "FAILED")
        (println "--- doctor output ---") (println out) (System/exit 1))))
