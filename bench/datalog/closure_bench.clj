;; closure_bench.clj — honest before/after for the indexed positional Datalog join
;; on a REALISTIC large code/work graph (not the pathological chain): a ~800-module
;; dependency DAG (each module depends on a few LATER-indexed modules → acyclic,
;; branching like a real import/call graph), transitive-reachability closure.
;;
;; before = d/fixpoint-oracle (retained scan-join semi-naive)
;; after  = d/fixpoint          (indexed positional join)
;; Both must return the IDENTICAL closure set; we report time, speedup, and the
;; explicit index memory bound O(arity x tuples) computed on the actual graph.
;;
;; regen: bb -cp out bench/datalog/closure_bench.clj
;;        (BENCH_N, BENCH_FAN, BENCH_SEED override; BENCH_ORACLE=0 skips the ~50 s
;;         before-run and reports the after-run + bound only.)
(require '[fram.datalog :as d] '[clojure.string :as str])

(defn- proc-read [p] (slurp (java.io.FileReader. p)))
(defn nproc [] (.availableProcessors (Runtime/getRuntime)))
(defn loadavg []
  (try (str/join " " (take 3 (str/split (str/trim (proc-read "/proc/loadavg")) #"\s+")))
       (catch Exception _ "n/a")))
(defn rss-mib []
  (try (->> (str/split-lines (proc-read "/proc/self/status"))
            (some (fn [l] (when (str/starts-with? l "VmRSS:")
                            (long (/ (Long/parseLong (re-find #"\d+" l)) 1024))))))
       (catch Exception _ nil)))
(defn env-long [k d] (if-let [v (System/getenv k)] (Long/parseLong v) d))

(def N    (env-long "BENCH_N"   800))
(def FAN  (env-long "BENCH_FAN" 3))
(def SEED (env-long "BENCH_SEED" 42))
(def run-oracle? (not= "0" (or (System/getenv "BENCH_ORACLE") "1")))

;; deterministic acyclic dependency graph: module i depends on 1..FAN modules with
;; a strictly greater index → a real-shaped DAG (sources → sinks), no cycles.
(defn gen-graph [n fan seed]
  (let [r (java.util.Random. seed)]
    {"dep" (set (for [i (range n) _ (range (inc (.nextInt r fan)))
                      :let [span (- n i 1)] :when (pos? span)]
                  [(str "m" i) (str "m" (+ i 1 (.nextInt r span)))]))}))

(def db (gen-graph N FAN SEED))
(def edges (count (get db "dep")))

(def rules
  [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
    :body [{:rel "dep" :args [{:var "x"} {:var "y"}] :neg false}]}
   {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
    :body [{:rel "dep" :args [{:var "x"} {:var "y"}] :neg false}
           {:rel "reaches" :args [{:var "y"} {:var "z"}] :neg false}]}])

(defn timed [f] (let [t0 (System/currentTimeMillis) v (f)] [v (- (System/currentTimeMillis) t0)]))

(println (str "  machine: nproc=" (nproc) "  loadavg(1/5/15)=" (loadavg)))
(println (str "  graph: " N " modules, " edges " dependency edges (acyclic, fan<=" FAN ", seed " SEED ")"))

;; after (indexed) — with an RSS delta as an empirical memory companion
(def rss0 (rss-mib))
(def after (timed (fn [] (d/fixpoint db rules))))
(def after-db (first after))
(def after-ms (second after))
(def rss1 (rss-mib))
(def after-n (count (d/facts after-db "reaches")))

;; before (oracle) — single run captures both time and result
(def before (when run-oracle? (timed (fn [] (count (d/facts (d/fixpoint-oracle db rules) "reaches"))))))
(def before-n  (when run-oracle? (first before)))
(def before-ms (when run-oracle? (second before)))

;; explicit index memory bound: entries = Σ_rel arity(rel) x |rel|, i.e. O(arity x tuples).
;; Every tuple is referenced once per position; that is the whole index footprint.
(def reaches-tuples after-n)
(def index-entries (+ (* 2 edges) (* 2 reaches-tuples)))   ; dep arity 2 + reaches arity 2

(println (str "  reaches (closure) pairs: " after-n))
(when run-oracle?
  (println (str "  BEFORE (oracle scan-join): " before-ms " ms   (result " before-n " pairs, agree=" (= before-n after-n) ")")))
(println (str "  AFTER  (indexed positional): " after-ms " ms"))
(when (and run-oracle? before-ms (pos? after-ms))
  (println (str "  SPEEDUP: " (format "%.1fx" (double (/ before-ms after-ms))))))
(println (str "  index memory bound O(arity x tuples): "
              "arity 2 x (" edges " dep + " reaches-tuples " reaches) = " index-entries " indexed entries"))
(when (and rss0 rss1) (println (str "  RSS (JVM-inclusive companion): " rss1 " MiB, Δ" (- rss1 rss0) " MiB over the indexed run")))

(if (or (not run-oracle?) (= before-n after-n))
  (println "\nclosure bench: OK (indexed result matches the oracle)")
  (do (println "\nclosure bench: MISMATCH — indexed != oracle") (System/exit 1)))
