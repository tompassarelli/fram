;; Comparative benchmark: fram (rotations engine, main @ edddcd2) vs DataScript
;; 1.7.3 (EPL-1.0, checked 2026-07-27 -- see deps.edn) on the SAME 350,701-live-fact
;; replayed corpus (`/tmp/fram-bench/pristine`, the corpus already staged for
;; bench/index-rotations/) and the SAME workload shapes: cold scan, indexed
;; lookup, multi-hop Datalog, write throughput.
;;
;; thread 019fa01d-ee47-7344-ba27-e7b0e63c86d2
;;
;; DataScript is an in-process embedded library, not a server -- there is no
;; daemon/socket boundary to cross, so this script drives it directly in the
;; SAME jvm process (no bin/fram-server involved on this side). The corpus is
;; folded to its final live-fact set with fram's OWN fold code
;; (fram.rt/read-log + fram.fold/fold -- the exact function the daemon calls
;; at boot) so both systems see byte-identical input facts; only the storage
;; engine differs.
;;
;; fram's own numbers are NOT reproduced by this script (fram needs its
;; daemon+socket harness, bench/index-rotations/cold-query-and-write-throughput.clj) --
;; run that separately; this script prints the DataScript column, and
;; run-all.sh stitches both into one table.
;;
;; Run: clojure -Sdeps-file deps.edn -M compare.clj   (from this directory)
(require '[datascript.core :as d]
         '[fram.rt :as rt]
         '[fram.fold :as fold]
         '[clojure.string :as str]
         '[clojure.java.shell])

(def home "/tmp/fram-bench/pristine")

(defn ms [f]
  (let [t0 (System/nanoTime) v (f)]
    [(/ (- (System/nanoTime) t0) 1e6) v]))

(defn pctl [xs p]
  (let [s (vec (sort xs)) n (count s)]
    (if (zero? n) nil (nth s (min (dec n) (int (* p n)))))))

(def out (atom []))
(defn emit! [k v] (swap! out conj [k v]) (println (format "%-34s %s" (str k) v)))

;; --- load + fold the SAME corpus fram's daemon folds at boot ----------------
(defn load-corpus! []
  (let [raw (into (rt/read-log (str home "/coordination.log"))
                  (rt/read-log (str home "/telemetry.log")))
        sorted (vec (sort-by #(or (:tx %) 0) raw))]
    sorted))

(defn -main []
  (println "=== fram vs DataScript 1.7.3 :: same 350,701-fact corpus ===")
  (println (str "load: " (str/trim (:out (clojure.java.shell/sh "cat" "/proc/loadavg"))) "  nproc=" (.availableProcessors (Runtime/getRuntime))))

  (let [[merge-ms raw] (ms load-corpus!)]
    (emit! :corpus-read-merge-ms (format "%.0f  (%d raw lines)" merge-ms (count raw)))

    ;; --- COLD LOAD: fold the raw log -> final live facts, then build the
    ;; DataScript db from them. This is DataScript's analog of fram's
    ;; whole-corpus fold-at-boot (bar 3's :mode :fold, :ms 9144 in the
    ;; sibling rotations benchmark) -- both systems pay to turn a replayed
    ;; log into a queryable indexed structure exactly once here.
    (let [[fold-ms folded] (ms #(fold/fold-facts (fold/fold raw)))
          _ (emit! :fold-ms (format "%.0f  (%d live facts)" fold-ms (count folded)))
          eid (atom {})
          next-eid (atom 0)
          ent! (fn [s] (or (get @eid s)
                           (let [i (swap! next-eid inc)] (swap! eid assoc s i) i)))
          ;; d/init-db needs real (post-tx0) tx ids -- a raw tx of 1 breaks its
          ;; internal e/a/v/tx ordering invariants and silently returns wrong
          ;; query results (found by testing: [?e :a ?v][?e :b ?t] conjunctions
          ;; came back empty until tx was offset). d/tx0 = 536870912 is
          ;; DataScript's own first-real-tx constant.
          [datom-ms datoms] (ms (fn [] (sort-by (fn [^datascript.db.Datom dm] [(.-e dm) (str (.-a dm)) (str (.-v dm))])
                                               (mapv (fn [{:keys [l p r]}]
                                                       (d/datom (ent! l) (keyword p) r d/tx0))
                                                     folded))))
          _ (emit! :datom-build-ms (format "%.0f" datom-ms))
          [initdb-ms db] (ms #(d/init-db datoms))]
      (emit! :cold-load-total-ms (format "%.0f  (fold + datom-build + init-db; DataScript's whole-corpus-to-queryable-db cost, analog of fram's boot-fold)"
                                          (+ fold-ms datom-ms initdb-ms)))
      (emit! :init-db-ms (format "%.0f" initdb-ms))

      (let [subject-eid (get @eid "@019f9e66-dd56-7326-8a6c-57a27a163956")
            ;; --- workload queries, same 5 shapes as bench/index-rotations ---
            q-titles    '[:find ?e ?v :where [?e :title ?v]]
            q-by-object '[:find ?e ?p :in $ ?v :where [?e ?p ?v]]
            q-join      '[:find ?e ?t :where [?e :kind "thread"] [?e :title ?t]]
            q-scan      '[:find ?e ?t :where [?e :lead _] [?e :title ?t]]
            run-titles    (fn [db] (d/q q-titles db))
            run-by-object (fn [db] (d/q q-by-object db "@tom_passarelli"))
            run-join      (fn [db] (d/q q-join db))
            run-subject   (fn [db] (if subject-eid (d/q '[:find ?p ?v :in $ ?e :where [?e ?p ?v]] db subject-eid) #{}))
            run-scan      (fn [db] (d/q q-scan db))
            workload [[:titles run-titles] [:by-object run-by-object] [:join run-join]
                      [:subject run-subject] [:scan-2rule run-scan]]
            conn (d/conn-from-db db)]

        ;; --- A. COLD query: first touch of each shape ------------------------
        (doseq [[k f] workload]
          (let [[t res] (ms #(f @conn))]
            (emit! (keyword (str "cold-" (name k) "-ms")) (format "%.0f  (%d rows)" t (count res)))))

        ;; --- B. WARM query (repeat, min of 3) ---------------------------------
        (doseq [[k f] workload]
          (let [ts (vec (for [_ (range 3)] (first (ms #(f @conn)))))]
            (emit! (keyword (str "warm-" (name k) "-ms")) (format "%.0f" (apply min ts)))))

        ;; --- C. READ-UNDER-WRITE: one write between each read -----------------
        (let [n 8]
          (doseq [[k f] workload]
            (let [ts (vec (for [i (range n)]
                            (do (d/transact! conn [{:db/id (ent! (str "@bench-" (name k))) :bench_tick (str i)}])
                                (first (ms #(f @conn))))))]
              (emit! (keyword (str "under-write-" (name k) "-ms"))
                     (format "min %.0f  p50 %.0f  max %.0f" (apply min ts) (pctl ts 0.5) (apply max ts))))))

        ;; --- D. WRITE throughput (serial, and under concurrent reader) --------
        (let [n 200
              [t _] (ms #(dotimes [i n] (d/transact! conn [{:db/id (ent! (str "@bench-w-" i)) :w (str i)}])))]
          (emit! :write-serial-per-min (format "%.0f  (%d writes in %.0f ms)" (* 60000.0 (/ n t)) n t)))
        (let [n 200
              stop (atom false)
              reader (future (loop [c 0] (if @stop c (do (try (run-join @conn) (catch Throwable _ nil)) (recur (inc c))))))
              [t _] (ms #(dotimes [i n] (d/transact! conn [{:db/id (ent! (str "@bench-wc-" i)) :w (str i)}])))]
          (reset! stop true)
          (emit! :write-under-read-per-min (format "%.0f  (%d writes in %.0f ms)" (* 60000.0 (/ n t)) n t))
          (emit! :concurrent-reads-completed (str @reader)))

        (spit "/tmp/fram-bench/result-datascript.edn" (pr-str (into {} @out)))
        (println "wrote /tmp/fram-bench/result-datascript.edn")))))

(-main)
