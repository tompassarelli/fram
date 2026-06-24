;; ============================================================================
;; bench/propagation/check-budget.clj — #50 — CI perf-budget gate for the
;; #44 propagation thesis. Runs the CONTENT-ASSERTED K-sweep (sweep.clj), parses the
;; (Relocated 2026-06-25: the propagation WRITEUPS moved to the after-text package;
;;  this slim gate stays in fram as its own perf-regression test.)
;; SHAPE table, and asserts:
;;   1. graph-prop FLAT in K           (ratio graph-prop[maxK]/graph-prop[minK])  [machine-indep]
;;   2. graph beats git at maxK        (ratio git-prop[maxK]/graph-prop[maxK])    [machine-indep]
;;   3. graph-prop absolute ceiling    (catastrophe-catcher, very generous)
;;   4. graph-write absolute ceiling   (the mirror cost stays bounded)
;;   5. no lost writes                 (landed=K/K both arms)
;; The two RATIO budgets encode the thesis and are immune to CI machine speed (both arms
;; measured in the same run on the same box). Budgets live in perf-budget.edn.
;;   bb -cp out bench/propagation/check-budget.clj
;; SAFE: sweep.clj runs /tmp-only, daemon on non-7977 port, never the canonical log.
;; ============================================================================
(require '[clojure.string :as str] '[clojure.java.io :as io]
         '[babashka.process :as proc] '[clojure.edn :as edn])

(def root (System/getProperty "user.dir"))
(def budget (edn/read-string (slurp (str root "/bench/propagation/perf-budget.edn"))))
(println "=== #50 propagation perf-budget gate ===")
(println "budget:" (pr-str budget))

;; --- run the content-asserted K-sweep ---
(def res (proc/sh {:dir root :out :string :err :string
                   :extra-env {"SWEEP_KS" (:sweep-ks budget)}}
                  "bb" "-cp" "out" "bench/propagation/sweep.clj"))
(def out (str (:out res) "\n" (:err res)))
(when (str/includes? out "SKIP — no .fram/code.log")
  (println "SKIP — no .fram/code.log present; cannot run the perf-budget (not a failure).")
  (System/exit 0))
(when-not (zero? (:exit res))
  (println "FAIL — sweep.clj exited" (:exit res) "\n" out)
  (System/exit 1))

;; --- parse the SHAPE table rows: "  K  git-write git-prop  graph-write graph-prop" ---
(def rows
  (->> (str/split-lines out)
       (keep (fn [ln]
               (when-let [[_ k gw gp grw grp]
                          (re-matches #"\s*(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*" ln)]
                 {:K (Integer/parseInt k)
                  :git-write (Double/parseDouble gw)   :git-prop (Double/parseDouble gp)
                  :graph-write (Double/parseDouble grw) :graph-prop (Double/parseDouble grp)})))
       (sort-by :K) vec))
(when (< (count rows) 2)
  (println "FAIL — could not parse >=2 SHAPE rows from sweep output. Raw:\n" out)
  (System/exit 1))

(def lo (first rows)) (def hi (last rows))
(println (format "parsed K=%d..%d | graph-prop %.2f->%.2f ms | git-prop %.2f->%.2f ms | graph-write@maxK %.1f ms"
                 (:K lo) (:K hi) (:graph-prop lo) (:graph-prop hi) (:git-prop lo) (:git-prop hi) (:graph-write hi)))

(def fails (atom []))
(defn chk [ok? msg] (println (str (if ok? "  ok   " "  FAIL ") msg)) (when-not ok? (swap! fails conj msg)))

(let [ratio (/ (:graph-prop hi) (max 1e-6 (:graph-prop lo)))]
  (chk (<= ratio (:graph-prop-flatness-ratio-max budget))
       (format "[1] graph-prop FLAT: ratio %.2f <= %.2f" ratio (:graph-prop-flatness-ratio-max budget))))
(let [factor (/ (:git-prop hi) (max 1e-6 (:graph-prop hi)))]
  (chk (>= factor (:graph-beats-git-factor-min budget))
       (format "[2] graph beats git @K=%d: %.1fx >= %.1fx" (:K hi) factor (:graph-beats-git-factor-min budget))))
(chk (<= (:graph-prop hi) (:graph-prop-abs-ceiling-ms budget))
     (format "[3] graph-prop[maxK] %.2f ms <= %.1f ms ceiling" (:graph-prop hi) (:graph-prop-abs-ceiling-ms budget)))
(chk (<= (:graph-write hi) (:graph-write-abs-ceiling-ms budget))
     (format "[4] graph-write[maxK] %.1f ms <= %.1f ms ceiling" (:graph-write hi) (:graph-write-abs-ceiling-ms budget)))
(when (:require-no-lost-writes budget)
  (let [lost (->> (re-seq #"landed=(\d+)/(\d+)" out) (some (fn [[_ a b]] (not= a b))))]
    (chk (not lost) "[5] no lost writes (landed=K/K both arms)")))

(println)
(if (empty? @fails)
  (do (println "PASS — propagation perf-budget held.") (System/exit 0))
  (do (println (format "BUDGET VIOLATED — %d check(s) failed:" (count @fails)))
      (doseq [f @fails] (println "  -" f))
      (System/exit 1)))
