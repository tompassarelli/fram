;; datalog_scale_test.clj — the canonical 200-chain bar for the INDEXED evaluator.
;; Transitive closure over a 200-node chain = 200*199/2 = 19900 reaches-pairs,
;; through the public query path (q/run -> d/fixpoint). The scan-join oracle does
;; this in ~4.2 s; the positional index (datalog.bclj) tightens it to well under a
;; second. Bar: EXACTLY 19900 pairs, under 1000 ms, with honest machine/load/RSS
;; metadata printed so a slow-box failure is diagnosable rather than mysterious.
;;   bb -cp out datalog_scale_test.clj
(require '[fram.kernel :as k] '[fram.query :as q] '[clojure.string :as str])

;; --- honest machine + load + memory metadata (measured, never asserted) ------
;; NB: bb's slurp fails on /proc with "Invalid argument"; a FileReader reads them.
(defn- proc-read [p] (slurp (java.io.FileReader. p)))
(defn nproc [] (.availableProcessors (Runtime/getRuntime)))
(defn loadavg []
  (try (str/join " " (take 3 (str/split (str/trim (proc-read "/proc/loadavg")) #"\s+")))
       (catch Exception _ "n/a")))
(defn rss-kb []
  (try (->> (str/split-lines (proc-read "/proc/self/status"))
            (some (fn [l] (when (str/starts-with? l "VmRSS:")
                            (Long/parseLong (re-find #"\d+" l))))))
       (catch Exception _ nil)))

(def N 200)
;; chain: @a1 -depends_on-> @a2 -> ... -> @aN
(def facts
  (mapv (fn [i] (k/->Fact (str "@a" i) "depends_on" (str "@a" (inc i)))) (range 1 N)))

(def query
  {:find "reaches"
   :rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
            :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
           {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
            :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                   {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}]})

;; one warm-up (JIT/class-load) then the measured run — honest steady-state timing.
(def _warm (q/run facts query))
(def rss-before (rss-kb))
(def t0 (System/currentTimeMillis))
(def res (q/run facts query))
(def ms (- (System/currentTimeMillis) t0))
(def rss-after (rss-kb))

(def n (count (:ok res)))
(def expected (/ (* N (dec N)) 2))

(println (str "  machine: nproc=" (nproc) "  loadavg(1/5/15)=" (loadavg)
              "  RSS=" (if rss-after (str (long (/ rss-after 1024)) " MiB") "n/a")
              (when (and rss-before rss-after) (str " (Δ" (long (/ (- rss-after rss-before) 1024)) " MiB)"))))
(println (str "  reaches-pairs: " n " (expected " expected ")  in " ms " ms  (bar: <1000 ms)"))

(let [ok-count (= n expected)
      ok-time (< ms 1000)]
  (println (if ok-count "  [PASS] " "  [FAIL] ") "200-chain closure is EXACTLY 19900 pairs")
  (println (if ok-time  "  [PASS] " "  [FAIL] ") "completed under the 1000 ms bar (indexed positional join)")
  (if (and ok-count ok-time)
    (println "\ndatalog scale: 2 / 2 PASS")
    (do (println "\ndatalog scale: FAILED") (System/exit 1))))
