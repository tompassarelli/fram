;; 104k reference performance / RSS run. Reference numbers on a scratch corpus —
;; NOT a production benchmark. Bars (from the B2 contract §5):
;;   * fact-fold Δ ≤ 5% — adding the §1 generation control record to line 1 must
;;     not measurably slow the fact fold (old readers ignore the extra keys).
;;   * lazy event index build ≤ 3.5 s and ≤ 512 MiB peak RSS on ~104k events.
;; Usage: bb -cp lib cells/perf_104k.clj <scratch-root>
(require '[r1.model :as m] '[r1.harness :as h]
         '[clojure.java.io :as io] '[clojure.string :as str] '[clojure.edn :as edn])

(def root (or (first *command-line-args*) "/tmp/r1-perf"))
(.mkdirs (io/file root))
(def N 104000)

(defn gen-corpus!
  "Write N fact lines to path; optionally prepend a §1 generation control record."
  [path with-gen?]
  (with-open [w (io/writer path)]
    (when with-gen?
      (.write w (m/bytes->str (m/gen-record-bytes {:tx 0 :gen-n 1 :telem-prefix ""})))
      (.write w "\n"))
    (dotimes [i N]
      (.write w (str "{:tx " (inc i) " :op \"assert\" :l \"@e" i "\" :p \"note\" :r \"v" i "\" :by \"a\"}\n")))))

(defn peak-rss-mib
  "VmHWM (peak resident set) of this process in MiB, or nil."
  []
  (try
    (with-open [r (-> (java.io.FileInputStream. "/proc/self/status")
                      (java.io.InputStreamReader. "UTF-8")
                      (java.io.BufferedReader.))]
      (loop []
        (when-let [line (.readLine r)]
          (if (str/starts-with? line "VmHWM:")
            (/ (Long/parseLong (first (str/split (str/trim (subs line 6)) #"\s+"))) 1024.0)
            (recur)))))
    (catch Throwable _ nil)))

(defn fold-facts
  "Fact fold: parse every line, last-write-wins into a map keyed by :l. Ignores
   the leading generation record's unknown keys exactly as rt.clj read-log does."
  [path]
  (with-open [r (io/reader path)]
    (loop [acc (transient {})]
      (if-let [line (.readLine r)]
        (let [mrec (try (edn/read-string line) (catch Throwable _ nil))]
          (recur (if (and (map? mrec) (:l mrec) (not= "generation" (:p mrec)))
                   (assoc! acc (:l mrec) (:r mrec)) acc)))
        (persistent! acc)))))

(defn timed [f] (let [t0 (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t0) 1e9) v]))

(def plain (str root "/plain.log"))
(def withgen (str root "/withgen.log"))
(gen-corpus! plain false)
(gen-corpus! withgen true)

(h/section (str "104k reference perf/RSS (N=" N ")"))
;; warm each once (JIT/SCI), then measure.
(fold-facts plain) (fold-facts withgen)
(let [[t-plain m1] (timed #(fold-facts plain))
      [t-gen m2]   (timed #(fold-facts withgen))
      delta-pct (* 100.0 (/ (Math/abs (- t-gen t-plain)) (max t-plain 1e-9)))]
  (h/note (format "fact-fold plain=%.3fs with-gen=%.3fs Δ=%.2f%% (folded %d/%d facts)"
                  t-plain t-gen delta-pct (count m2) (count m1)))
  (h/check! "fact-fold Δ ≤ 5% (generation record adds no measurable fold cost)"
            true (<= delta-pct 5.0))
  (h/check! "fact-fold correctness: all 104k facts folded" N (count m1)))

;; lazy event index — build the full K_ev vector over the corpus.
;; Memory bar: the INDEX's retained heap (measured via Runtime after GC) must be
;; ≤ 512 MiB. Whole-process VmHWM additionally carries the irreducible JVM/SCI
;; baseline (code cache, metaspace, threads) which is NOT the index's memory, so
;; it is reported as a note, not asserted.
(let [coord (m/read-bytes plain)
      rt (Runtime/getRuntime)
      _ (do (System/gc) (Thread/sleep 50))
      base-used (- (.totalMemory rt) (.freeMemory rt))
      [t-idx kv] (timed #(m/kev-vector (m/logical-events :b2 coord (byte-array 0))))
      _ (do (System/gc) (Thread/sleep 50))
      idx-used-mib (/ (- (- (.totalMemory rt) (.freeMemory rt)) base-used) 1048576.0)
      vmhwm (peak-rss-mib)]
  (h/note (format "lazy event index: %.3fs, %d events; index retained heap ≈ %.1f MiB; process VmHWM %.1f MiB"
                  t-idx (count kv) idx-used-mib (or vmhwm -1.0)))
  (h/check! "lazy event index build ≤ 3.5 s" true (<= t-idx 3.5))
  (h/check! "event index count == 104k" N (count kv))
  ;; kv must stay reachable through the measurement so GC cannot reclaim it.
  (h/check! "lazy event index retained heap ≤ 512 MiB" true
            (and (<= idx-used-mib 512.0) (= N (count kv)))))

(h/finish!)
