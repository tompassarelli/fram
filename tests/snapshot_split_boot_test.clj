;; Split-log snapshot boot golden.
;;
;; The checkpoint is one image of the unified store at one global :tx watermark.
;; Its fence carries a byte offset + identity for each physical log. Boot replays
;; both tails above the watermark, then (in verification mode) independently
;; whole-folds both logs and requires an empty store/version diff.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(load-file "server.clj")

(def root (str (System/getProperty "java.io.tmpdir")
               "/fram-snapshot-split-" (System/nanoTime)))
(def coordination (str root "/coordination.log"))
(def telemetry (str root "/telemetry.log"))

(defn line [tx op l p r]
  (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "split-golden"}))

(defn write-lines! [path lines]
  (spit path (str (str/join "\n" lines) "\n")))

(defn append-lines! [path lines]
  (spit path (str (str/join "\n" lines) "\n") :append true))

(def checks (atom []))
(defn check! [label ok]
  (println (str (if ok "PASS " "FAIL ") label))
  (swap! checks conj [label (boolean ok)]))

(.mkdirs (io/file root))
(write-lines! coordination
              [(line 1 "assert" "@title" "cardinality" "single")
               (line 2 "assert" "@thread" "title" "Before")])
(write-lines! telemetry
              [(line 3 "assert" "@run:one" "kind" "run")
               (line 4 "assert" "@run:one" "sample" "before")])

(reset! telemetry-log telemetry)
(reset! snapshot-boot-enabled? true)
(reset! snapshot-boot-verify? false)
(reset! mmap-image-enabled? false)

;; No checkpoint yet: the automatic fallback establishes the full-fold oracle.
(binding [*out* (java.io.StringWriter.)]
  (boot-flat! coordination))
(check! "first boot falls back loudly when no snapshot exists"
        (and (= :fold (:mode @last-boot))
             (str/includes? (:reason @last-boot) "no checkpoint")))

(write-snapshot! @db coordination)
(let [sidecar (read-sidecar coordination)]
  (check! "checkpoint carries one explicit global watermark"
          (= (:watermark sidecar) (:seq sidecar)))
  (check! "checkpoint fence covers coordination and telemetry logs"
          (= #{:coordination :telemetry} (set (keys (:logs sidecar)))))
  (check! "each log fence carries identity and byte offset"
          (every? #(and (contains? % :identity)
                        (int? (:byte_offset %)))
                  (vals (:logs sidecar)))))

(let [base (current-seq @db)]
  (append-lines! coordination
                 [(line (inc base) "assert" "@thread" "title" "After")
                  (line (+ base 2) "assert" "@thread-2" "title" "Tail")])
  (append-lines! telemetry
                 [(line (+ base 3) "assert" "@run:one" "sample" "after")
                  (line (+ base 4) "assert" "@run:two" "kind" "run")]))

(reset! snapshot-boot-verify? true)
(binding [*out* (java.io.StringWriter.)]
  (boot-flat! coordination))
(check! "split snapshot boot consumes the checkpoint"
        (= :snapshot (:mode @last-boot)))
(check! "split snapshot boot replays both physical tails"
        ;; snapshot-reader also carries the checkpoint's six queryable metadata
        ;; facts, which intentionally live after the image watermark.
        (= {:coordination 8 :telemetry 2} (:tail-lines @last-boot)))
(check! "boot-both-ways verification diff is empty"
        (= {:ok true
            :only-snapshot 0
            :only-fold 0
            :snapshot-version (current-seq @db)
            :fold-version (current-seq @db)}
           (:verification @last-boot)))
(check! "snapshot+tails equals an independent full merged fold"
        (:ok (snapshot-reconcile @db coordination)))

;; Keep the snapshot-reader identity and offsets intact but replace telemetry
;; history. The per-log fence must reject the stale checkpoint and full-fold the
;; new pair; silently accepting the image would retain @run:one.
(write-lines! telemetry
              [(line 3 "assert" "@run:fresh" "kind" "run")
               (line 4 "assert" "@run:fresh" "sample" "fresh")])
(binding [*out* (java.io.StringWriter.)]
  (boot-flat! coordination))
(check! "telemetry identity mismatch falls back to full fold"
        (= :fold (:mode @last-boot)))
(check! "fallback reason names the telemetry identity mismatch"
        (str/includes? (:reason @last-boot) "telemetry log identity mismatch"))
(check! "fallback state is the new full merged fold"
        (:ok (snapshot-reconcile @db coordination)))

(when (nil? (System/getenv "FRAM_SNAPSHOT_BOOT"))
  (check! "FRAM_SNAPSHOT_BOOT defaults on when unset"
          @snapshot-boot-enabled?))

(let [failures (remove second @checks)]
  (println)
  (if (empty? failures)
    (println "snapshot_snapshot_split_boot_test:" (count @checks) "/" (count @checks) "PASS")
    (do
      (println "snapshot_snapshot_split_boot_test:" (count failures) "FAILED")
      (System/exit 1))))
