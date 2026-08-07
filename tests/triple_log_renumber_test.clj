;; triple_log_renumber_test.clj — --renumber seals an epoch-compacted flat log
;; without changing conversion semantics; without the flag it still fails closed.
;;   clojure -M tests/triple_log_renumber_test.clj

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(load-file "database.clj")

(def failures (atom 0))

(defn check! [label ok]
  (println (str (if ok "[PASS] " "[FAIL] ") label))
  (when-not ok (swap! failures inc)))

(defn throwable-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:fram/code (ex-data e)))))

(def tmp-dir
  (.toFile (java.nio.file.Files/createTempDirectory
            "fram-renumber-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn write-source! [name lines terminal-lf?]
  (let [file (java.io.File. tmp-dir name)
        text (str (str/join "\n" lines) (when terminal-lf? "\n"))]
    (spit file text)
    (.getCanonicalPath file)))

(defn row [tx subject predicate value]
  (str "{:tx " tx ", :op \"assert\", :l \"@" subject "\", :p \"" predicate
       "\", :r \"" value "\", :ts \"2026-07-29T22:43:33.723039082Z\"}"))

(defn target [name] (.getCanonicalPath (java.io.File. tmp-dir name)))

(defn manifest [path] (edn/read-string (slurp (str path ".migration.edn"))))

;; Subject-grouped exactly as an epoch pass emits it: coordinates jump backward
;; between groups, and `title` is single-valued so the later row must win.
(def compacted
  [(row 34716 "alpha" "display_name" "alpha")
   (row 34593 "beta" "display_name" "beta")
   (row 34593 "beta" "title" "first")
   (row 12000 "beta" "title" "second")
   (row 4643 "gamma" "display_name" "gamma")])

(let [source (write-source! "compacted.log" compacted true)]
  (check! "a non-monotonic source fails closed without --renumber"
          (= :migration-nonmonotonic-transaction
             (throwable-code
              #(database/migrate-legacy-flat-log! source "s" (target "strict.framlog")))))

  (let [out (database/migrate-legacy-flat-log! source "s" (target "renumbered.framlog")
                                              {:renumber? true})
        record (manifest (target "renumbered.framlog"))
        legacy (:legacy-coordinates record)]
    (check! "--renumber seals the compacted source" (some? out))
    (check! "the manifest records the renumbering"
            (= :renumbered-in-file-order (:disposition legacy)))
    (check! "contiguous runs collapse into one transaction each"
            (= 4 (:transactions legacy)))
    (check! "every source row survives" (= 5 (:rows legacy)))
    (check! "the original coordinate range stays on the manifest"
            (= [34716 4643] (:original-range legacy)))
    (check! "sealed coordinates start at one and advance"
            (= [1 4] (:transaction-range record)))))

;; An already-monotonic source must seal identically with or without the flag:
;; renumbering may never be a second, divergent conversion semantics.
(def monotonic
  [(row 1 "alpha" "display_name" "alpha")
   (row 2 "beta" "title" "first")
   (row 3 "beta" "title" "second")])

(let [source (write-source! "monotonic.log" monotonic true)
      plain (database/migrate-legacy-flat-log! source "s" (target "plain.framlog"))
      renumbered (database/migrate-legacy-flat-log! source "s" (target "mono-renumbered.framlog")
                                                    {:renumber? true})]
  (check! "an already-monotonic source seals byte-identically either way"
          (= (:sha256 plain) (:sha256 renumbered)))
  (check! "a preserved-coordinate seal says so"
          (= :preserved (:disposition (:legacy-coordinates (manifest (target "plain.framlog")))))))

(let [source (write-source! "torn.log" compacted false)]
  (check! "a torn tail cannot be renumbered"
          (= :migration-renumber-torn-unsupported
             (throwable-code
              #(database/migrate-legacy-flat-log! source "s" (target "torn.framlog")
                                                  {:renumber? true})))))

(check! "options still reject a non-Boolean value"
        (= :migration-options-invalid
           (throwable-code
            #(database/migrate-legacy-flat-log! (write-source! "opt.log" monotonic true)
                                                "s" (target "opt.framlog")
                                                {:renumber? "yes"}))))

(println (str "\n" (if (zero? @failures) "renumber gate PASSED"
                       (str "renumber gate FAILED: " @failures))))
(System/exit (if (zero? @failures) 0 1))
