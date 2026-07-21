;; bench/beyond-ram/measure.clj — one measured daemon, in-process, in a FRESH JVM
;; (the driver runs the modes SEQUENTIALLY so RSS numbers don't share page-cache
;; noise). Boots the given corpus in one of three modes and prints an EDN row:
;;   off     — FRAM_MMAP_IMAGE unset: whole-log fold + warm cache (today's baseline).
;;   on      — FRAM_MMAP_IMAGE on: checkpoint .fri, reboot mmap-cold, NO whole-corpus
;;             op (the RSS-win scenario) — reads served from the mmap primitives.
;;   on-mat  — as `on`, then ONE whole-corpus op (materialize + warm) to document the
;;             lazy-materialize cost.
;; Reuses the VmHWM /proc reader idiom from tests/apparatus_r1/cells/perf_104k.clj.
;;   bb -cp out bench/beyond-ram/measure.clj <mode> <log>
(require '[fram.store :as c] '[fram.schema :as s] '[clojure.string :as str])
(load-file "coord_daemon.clj")

(def mode (keyword (or (first *command-line-args*) "off")))
(def LOG (or (second *command-line-args*) "/tmp/fram-beyond-ram.log"))

(defn proc-kib [key]                    ; VmRSS: / VmHWM: from /proc/self/status, in KiB
  (with-open [r (-> (java.io.FileInputStream. "/proc/self/status")
                    (java.io.InputStreamReader. "UTF-8") (java.io.BufferedReader.))]
    (loop []
      (when-let [line (.readLine r)]
        (if (str/starts-with? line key)
          (Long/parseLong (first (str/split (str/trim (subs line (count key))) #"\s+")))
          (recur))))))
(defn vmrss-mib [] (/ (proc-kib "VmRSS:") 1024.0))
(defn vmhwm-mib [] (/ (proc-kib "VmHWM:") 1024.0))
(defn heap-mib []
  (System/gc) (Thread/sleep 200) (System/gc) (Thread/sleep 100)
  (let [rt (Runtime/getRuntime)] (/ (- (.totalMemory rt) (.freeMemory rt)) 1048576.0)))
(defn ms [t0] (/ (double (- (System/nanoTime) t0)) 1e6))

;; sample keys that EXIST: walk the log once for a spread of subjects.
(defn sample-subjects [n]
  (with-open [r (clojure.java.io/reader LOG)]
    (let [lines (line-seq r)]
      (->> lines (take-nth 997) (map #(:l (read-string %))) (remove nil?) distinct (take n) vec))))

;; --- latency probes: the SAME logical op both ways (resolve name -> by-lp -> render) ---
(defn heap-by-lp-latency [subjects pred iters]
  (let [st (:store @co)
        t0 (System/nanoTime)]
    (dotimes [_ iters]
      (doseq [sn subjects]
        (let [lid (s/resolve-name st sn) pid (c/value-id st pred)]
          (when (and lid pid)
            (doseq [cid (c/by-lp st lid pid)]
              (let [f (c/fact-of st cid)]
                (if (c/value-object? st (:r f)) (c/literal st (:r f)) (s/name-of st (:r f)))))))))
    (/ (ms t0) (* iters (count subjects)))))     ; ms per (subject,pred) lookup+render

;; COLD FIRST-TOUCH: clear the render caches each round so every (subject,pred)
;; render is a cache MISS (the O(log n) dictionary-search path). Only the render is
;; timed — the clear is outside the timer. This is the bar-2 number (documented bound).
(defn firsttouch-by-lp-latency [subjects pred rounds]
  (let [img @cold-image]
    (loop [i 0 acc 0.0]
      (if (= i rounds) (/ acc (double (* rounds (count subjects))))
          (do (fri/clear-render-caches! img)
              (let [t0 (System/nanoTime)]
                (doseq [sn subjects] (cold-lp-render sn pred))
                (recur (inc i) (+ acc (ms t0)))))))))

;; HOT (repeat-key): warm the cache once (all keys resident), then time repeated
;; touches — every access is a whole-render cache HIT. This is the bar-1 number.
(defn hot-by-lp-latency [subjects pred iters]
  (doseq [sn subjects] (cold-lp-render sn pred))     ; prime the cache
  (let [t0 (System/nanoTime)]
    (dotimes [_ iters] (doseq [sn subjects] (cold-lp-render sn pred)))
    (/ (ms t0) (* iters (count subjects)))))

(defn heap-by-l-latency [subjects iters]
  (let [st (:store @co) t0 (System/nanoTime)]
    (dotimes [_ iters] (doseq [sn subjects] (let [lid (s/resolve-name st sn)] (when lid (count (c/by-l st lid))))))
    (/ (ms t0) (* iters (count subjects)))))
(defn cold-by-l-latency [subjects iters]
  (let [t0 (System/nanoTime)]
    (dotimes [_ iters] (doseq [sn subjects] (count (or (cold-by-l sn) []))))
    (/ (ms t0) (* iters (count subjects)))))

(def subjects (sample-subjects 500))
(def LIT-PRED "line")        ; literal-valued: render is a direct dict read
(def REF-PRED "in_module")   ; ref-valued: render also resolves the target's name
(def ITERS 300)
(def FT-ROUNDS 100)          ; cold first-touch: clear+sweep rounds (each = 500 misses)

;; heap modes (off/on-mat) have no cold render cache: first-touch == hot == warm heap.
(defn ft-by-lp [cold? pred rounds]
  (if cold? (firsttouch-by-lp-latency subjects pred rounds) (heap-by-lp-latency subjects pred rounds)))
(defn hot-by-lp [cold? pred iters]
  (if cold? (hot-by-lp-latency subjects pred iters) (heap-by-lp-latency subjects pred iters)))

(defn measure-row []
  (let [cold? (and (#{:on :on-mat} mode) (some? @cold-image))
        ;; warm the JIT (discard) so reported numbers are steady-state.
        _ (do (hot-by-lp cold? LIT-PRED 40) (hot-by-lp cold? REF-PRED 40)
              (when cold? (ft-by-lp true LIT-PRED 5) (ft-by-lp true REF-PRED 5))
              (if cold? (cold-by-l-latency subjects 40) (heap-by-l-latency subjects 40)))
        ft-lit  (ft-by-lp cold? LIT-PRED FT-ROUNDS)     ; cold first-touch (cache miss)
        ft-ref  (ft-by-lp cold? REF-PRED FT-ROUNDS)
        hot-lit (hot-by-lp cold? LIT-PRED ITERS)        ; hot repeat-key (cache hit)
        hot-ref (hot-by-lp cold? REF-PRED ITERS)
        by-l  (if cold? (cold-by-l-latency subjects ITERS) (heap-by-l-latency subjects ITERS))]
    {:mode mode
     :boot-ms (:ms @last-boot)
     :boot-mode (:mode @last-boot)
     :cold (boolean @cold-image)
     :facts (:facts (handle {:op :status}))
     :vmrss-mib (Math/round (vmrss-mib))
     :vmhwm-mib (Math/round (vmhwm-mib))
     :heap-mib (Math/round (heap-mib))
     :by-lp-lit-ft-us  (Math/round (* 1000.0 ft-lit))    ; cold first-touch, literal pred
     :by-lp-ref-ft-us  (Math/round (* 1000.0 ft-ref))    ; cold first-touch, ref pred
     :by-lp-lit-hot-us (Math/round (* 1000.0 hot-lit))   ; hot repeat-key, literal pred
     :by-lp-ref-hot-us (Math/round (* 1000.0 hot-ref))   ; hot repeat-key, ref pred
     :by-l-us  (Math/round (* 1000.0 by-l))
     :sampled-subjects (count subjects)}))

;; `prep` builds the checkpoint (.fri) in a THROWAWAY process and exits, so the
;; measured on/on-mat process boots mmap-cold FRESH — its RSS reflects only the
;; mmap-cold state, never the transient fold+writer peak. off needs no prep.
(case mode
  :prep
  (do (reset! snapshot-boot-enabled? true) (reset! mmap-image-enabled? true)
      (boot-flat! LOG) (write-snapshot! @co LOG)
      (println "BENCHROW {:mode :prep :ok true}") (System/exit 0))
  :off
  (do (reset! snapshot-boot-enabled? false) (reset! mmap-image-enabled? false)
      (boot-flat! LOG))
  ;; on / on-mat: boot from the ALREADY-written checkpoint -> mmap-cold. No fold here.
  (do (reset! snapshot-boot-enabled? true) (reset! mmap-image-enabled? true)
      (boot-flat! LOG)))

(when (= mode :on-mat)
  (ensure-materialized!)                    ; one whole-corpus op: fold cold -> heap
  (index!))                                 ; + warm cache (what a first :query would build)

(println (str "BENCHROW " (pr-str (measure-row))))
