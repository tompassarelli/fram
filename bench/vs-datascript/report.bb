;; Renders the fram-vs-DataScript comparison table from the two result .edn
;; files run-all.sh produces. Run standalone with: bb report.bb
(require '[clojure.edn :as edn])

(defn load-edn [path]
  (if (.exists (java.io.File. ^String path))
    (edn/read-string (slurp path))
    (do (println (str "WARNING: missing " path)) {})))

(def fram (load-edn "/tmp/fram-bench/result-vsds.edn"))
(def ds   (load-edn "/tmp/fram-bench/result-datascript.edn"))

(defn row [label fk dk]
  (println (format "| %-28s | %-40s | %-40s |" label (str (get fram fk "-")) (str (get ds dk "-")))))

(println "| shape | fram (this branch) | DataScript 1.7.3 |")
(println "|---|---|---|")
(row "boot / cold-load total"    :boot-ready-ms       :cold-load-total-ms)
(row "cold: titles (POS scan)"   :cold-titles-ms      :cold-titles-ms)
(row "cold: by-object (OSP)"     :cold-by-object-ms   :cold-by-object-ms)
(row "cold: join (2-literal)"    :cold-join-ms        :cold-join-ms)
(row "cold: subject (SPO)"       :cold-subject-ms     :cold-subject-ms)
(row "cold: scan-2rule (multi-hop)" :cold-scan-2rule-ms :cold-scan-2rule-ms)
(row "warm: titles"              :warm-titles-ms      :warm-titles-ms)
(row "warm: by-object"           :warm-by-object-ms   :warm-by-object-ms)
(row "warm: join"                :warm-join-ms        :warm-join-ms)
(row "warm: subject"             :warm-subject-ms     :warm-subject-ms)
(row "warm: scan-2rule"          :warm-scan-2rule-ms  :warm-scan-2rule-ms)
(row "under-write: titles"       :under-write-titles-ms      :under-write-titles-ms)
(row "under-write: by-object"    :under-write-by-object-ms   :under-write-by-object-ms)
(row "under-write: join"         :under-write-join-ms        :under-write-join-ms)
(row "under-write: subject"      :under-write-subject-ms     :under-write-subject-ms)
(row "under-write: scan-2rule"   :under-write-scan-2rule-ms  :under-write-scan-2rule-ms)
(row "write throughput, serial"  :write-serial-per-min       :write-serial-per-min)
(row "write throughput, under concurrent read" :write-under-read-per-min :write-under-read-per-min)
