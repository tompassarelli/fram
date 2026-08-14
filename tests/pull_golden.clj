;; tests/pull_golden.clj — behaviour golden for the PULL API (M0 migration oracle).
;; Runs the SAME script against the original ns `pull` (load-file pull.clj) and the
;; ported ns `pull` (require 'pull from out/), and prints a fully deterministic
;; transcript on stdout.
;;   bb -cp out tests/pull_golden.clj orig   # the hand-written pull.clj (DELETED after
;;                                           # adoption — restore it from git history first)
;;   bb -cp out tests/pull_golden.clj port   # the Beagle-emitted out/pull.clj
;; Capture + compare:
;;   bb -cp out tests/pull_golden.clj orig > tests/goldens/pull/transcript.txt
;;   diff <(bb -cp out tests/pull_golden.clj port) tests/goldens/pull/transcript.txt
;; DETERMINISM: provenance leaves carry :ts, a wall-clock instant. The harness
;; replaces it with :TS/present | :TS/absent BEFORE printing, so the transcript is
;; a pure function of the corpus. That is harness normalisation, not a diff mask —
;; the diff itself runs with zero sed/filters.
(require '[fram.store :as c] '[fram.schema :as s] '[clojure.string :as str]
         '[clojure.walk :as walk])
(load-file "database.clj")
(if (= "orig" (first *command-line-args*))
  (do (when-not (.exists (java.io.File. "pull.clj"))
        (binding [*out* *err*]
          (println "pull_golden: pull.clj is GONE — the hand-written original was deleted once")
          (println "the graph-authored module reached byte-identical parity. The committed")
          (println "tests/goldens/pull/transcript.txt IS the frozen oracle. To re-capture from")
          (println "the original, restore it first:")
          (println "  git show \"$(git log --diff-filter=D --format=%H -1 -- pull.clj)^:pull.clj\" > pull.clj"))
        (System/exit 2))
      (load-file "pull.clj"))
  (require 'pull))
(def V (resolve 'pull/validate))
(def R (resolve 'pull/run!))

(defn norm [x]
  (walk/postwalk
   (fn [n] (if (and (map? n) (contains? n :ts))
             (assoc n :ts (if (some? (:ts n)) :TS/present :TS/absent))
             n))
   x))
(defn p! [label v] (println label (pr-str (norm v))))
(defn safe [f] (try (f) (catch Throwable t [:THREW (.getName (class t)) (.getMessage t)])))

;; ---------------------------------------------------------------------------
;; PART 1 — validate: total, never throws. Table over every pattern shape.
;; ---------------------------------------------------------------------------
(println "=== validate table ===")
(def roots
  ["@x" "" "   " ["@x" "@y"] [] ["@x" ""] ["@x" 42] 42 nil {:x 1} :kw ["@x" nil]])
(def patterns
  [["title" "status"]
   [:*]
   ["_part_of"]
   [{"depends_on" ["title"]}]
   [{"rel" 3}] [{"rel" 1}] [{"rel" 0}] [{"rel" -2}]
   [{"rel" :...}]
   [{"rel" "bad"}] [{"rel" nil}] [{"rel" {}}] [{"rel" [42]}]
   [{}] [{"" ["title"]}] [{"  " ["title"]}] [{42 ["title"]}]
   [42] [nil] [:nope] [[]] [["title"]] [{"a" [{"b" [:*]}]}]
   "nope" nil 42 {:x 1} :kw
   [] ["" ] ["   "] ["title" 42] [:* :* "title"]])
(def optses
  [{} {:max-depth 3 :max-nodes 10} {:max-depth 0} {:max-depth -1} {:max-depth :bad}
   {:max-depth 1.5} {:max-nodes 0} {:max-nodes -3} {:max-nodes nil}
   {:as-of 0} {:as-of 7} {:as-of -1} {:as-of "x"} {:as-of nil}
   {:provenance true} {:max-depth 2 :max-nodes 2 :as-of 3 :provenance false}])
(def n (atom 0))
(doseq [r roots, pa patterns, o optses]
  (swap! n inc)
  (println (format "V%04d" @n) (pr-str r) (pr-str pa) (pr-str o) "->"
           (pr-str (safe #(V r pa o)))))
(println "validate cases:" @n)

;; ---------------------------------------------------------------------------
;; PART 2 — run over a real store built through the database API.
;; ---------------------------------------------------------------------------
(println "\n=== run over a real store ===")
(def work (str (or (System/getenv "TMPDIR") "/tmp") "/pull-golden-run"))
(.mkdirs (java.io.File. work))
(def log (str work "/corpus.log"))
(.delete (java.io.File. log))
(def db (new-database log))
(def st (:store db))
(register-pred! db "title" "single" "literal")
(register-pred! db "status" "single" "literal")
(register-pred! db "tag" "multi" "literal")
(register-pred! db "depends_on" "multi" "ref")
(register-pred! db "part_of" "single" "ref")
(register-pred! db "rel" "multi" "ref")

(commit! db "u" "@x" "title" :assert "Ship v1" nil)
(commit! db "u" "@x" "status" :assert "open" nil)
(commit! db "u" "@x" "tag" :assert "red" nil)
(commit! db "u" "@x" "tag" :assert "blue" nil)
(commit! db "u" "@x" "depends_on" :link "@dep1" nil)
(commit! db "u" "@dep1" "title" :assert "Design" nil)
(commit! db "u" "@dep1" "depends_on" :link "@dep2" nil)
(commit! db "u" "@dep2" "title" :assert "Deep" nil)
(commit! db "u" "@a" "part_of" :link "@x" nil)
(commit! db "u" "@b" "part_of" :link "@x" nil)
(commit! db "u" "@c1" "rel" :link "@c2" nil)
(commit! db "u" "@c2" "rel" :link "@c3" nil)
(commit! db "u" "@c3" "rel" :link "@c4" nil)
(commit! db "u" "@c4" "rel" :link "@c5" nil)
(commit! db "u" "@k1" "rel" :link "@k2" nil)
(commit! db "u" "@k2" "rel" :link "@k1" nil)
(def s1 (:ok (commit! db "u" "@y" "status" :assert "s1" nil)))
(def s2 (:ok (commit! db "u" "@y" "status" :assert "s2" nil)))
(def st-t (:ok (commit! db "u" "@y" "tag" :assert "T" nil)))
(retract! db "u" "@y" "tag" "T" nil)
(retract! db "w" "@x" "tag" "red" nil "no longer relevant")

(def cases
  [["flat scalars"        "@x"   ["title" "status"] {}]
   ["cardinality"         "@x"   ["status" "tag"] {}]
   ["nested ref"          "@x"   [{"depends_on" ["title"]}] {}]
   ["nested ref 2 deep"   "@x"   [{"depends_on" [{"depends_on" ["title"]}]}] {}]
   ["reverse bare"        "@x"   ["_part_of"] {}]
   ["reverse recursion"   "@x"   [{"_part_of" ["part_of"]}] {}]
   ["wildcard"            "@x"   [:*] {}]
   ["wildcard prov"       "@x"   [:*] {:provenance true}]
   ["provenance scalar"   "@x"   ["status"] {:provenance true}]
   ["provenance withdrawn" "@x"  ["tag"] {:provenance true}]
   ["plain hides withdrawn" "@x" ["tag"] {}]
   ["as-of s1"            "@y"   ["status"] {:as-of s1}]
   ["as-of s2"            "@y"   ["status"] {:as-of s2}]
   ["as-of current"       "@y"   ["status"] {}]
   ["as-of 0"             "@y"   ["status"] {:as-of 0}]
   ["as-of withdrawn-after" "@y" ["tag"] {:provenance true :as-of st-t}]
   ["as-of withdrawn-now" "@y"   ["tag"] {:provenance true}]
   ["depth cap 2"         "@c1"  [{"rel" :...}] {:max-depth 2}]
   ["depth cap 1"         "@c1"  [{"rel" :...}] {:max-depth 1}]
   ["depth default"       "@c1"  [{"rel" :...}] {}]
   ["bounded recursion 3" "@c1"  [{"rel" 3}] {}]
   ["bounded recursion 1" "@c1"  [{"rel" 1}] {}]
   ["node budget 1"       "@x"   [{"depends_on" ["title"]}] {:max-nodes 1}]
   ["node budget 2"       "@x"   [{"depends_on" ["title"]}] {:max-nodes 2}]
   ["cap above default"   "@c1"  [{"rel" :...}] {:max-depth 99 :max-nodes 99999}]
   ["cycle"               "@k1"  [{"rel" :...}] {}]
   ["unknown root"        "@nope" ["title"] {}]
   ["unknown pred"        "@x"   ["nosuch" {"nosuch" ["title"]}] {}]
   ["unknown reverse"     "@x"   ["_nosuch" {"_nosuch" ["title"]}] {}]
   ["literal under subpat" "@x"  [{"title" ["anything"]}] {}]
   ["vector root"         ["@x" "@dep1"] ["title"] {}]
   ["vector root w/ unknown" ["@x" "@nope"] ["title"] {}]
   ["empty pattern"       "@x"   [] {}]
   ["malformed pattern"   "@x"   [42] {}]
   ["bad opts"            "@x"   ["title"] {:max-depth 0}]
   ["reserved pred direct" "@x"  ["name"] {}]])
(doseq [[label root pat opts] cases]
  (p! (str "R " label " |") (safe #(R st root pat opts))))
(println "run cases:" (count cases))

;; ---------------------------------------------------------------------------
;; PART 3 — replay durability of the provenance :ts (same fact, fresh store).
;; ---------------------------------------------------------------------------
(println "\n=== replay ===")
(def rlog (str work "/replay.log"))
(.delete (java.io.File. rlog))
(def co2 (new-database rlog))
(register-pred! co2 "title" "single" "literal")
(commit! co2 "u" "@r" "title" :assert "Persisted" nil)
(def live (R (:store co2) "@r" ["title"] {:provenance true}))
(def boot (R (replay rlog) "@r" ["title"] {:provenance true}))
(p! "live |" live)
(p! "boot |" boot)
(println "ts equal:" (= (:ts (get live "title")) (:ts (get boot "title"))))
(println "\n=== done ===")
