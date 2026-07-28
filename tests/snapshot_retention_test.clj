;; ============================================================================
;; snapshot_retention_test.clj — the checkpoint prune decides what to ERASE, so
;; its selection is proven here rather than first exercised against a real
;; directory. On 2026-07-29 that directory held 102 images of ~41.8 MB — 3.7 GB
;; of checkpoints for a 34 MB log, never pruned.
;;
;; Loads the REAL coord_daemon.clj function; a copied reimplementation would
;; drift from the code that actually deletes files.
;;   bb -cp out tests/snapshot_retention_test.clj
;; ============================================================================
(require '[clojure.java.io :as io])
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))

(def failures (atom 0))
(def checks (atom 0))
(defn check! [label pass?]
  (swap! checks inc)
  (if pass? (println "PASS" label)
      (do (swap! failures inc) (println "FAIL" label))))

(defn f [n] (io/file "/tmp/snapshot-retention-fixture" n))
(defn names [fs] (set (map #(.getName %) fs)))
(defn snaps [& seqs] (map #(f (str "snap-" % ".v2log")) seqs))

;; --- ordering: seq is numeric, and "10" sorts before "9" as text ------------
(check! "orders by seq numerically, not lexicographically"
        (= #{"snap-9.v2log"}
           (names (coord-daemon/snapshots-to-prune (snaps 9 10 100) 2))))

;; --- retention count --------------------------------------------------------
(check! "keeps exactly `retain` newest"
        (= #{"snap-1.v2log" "snap-2.v2log"}
           (names (coord-daemon/snapshots-to-prune (snaps 1 2 3 4 5) 3))))

(check! "fewer checkpoints than `retain` deletes nothing"
        (empty? (coord-daemon/snapshots-to-prune (snaps 1 2) 3)))

(check! "empty directory is a no-op"
        (empty? (coord-daemon/snapshots-to-prune [] 3)))

;; --- an image and its sidecar are ONE checkpoint -----------------------------
;; A .fri without its .v2log is unusable, so they must never be split.
(check! "image and sidecar of the same seq drop together"
        (= #{"snap-1.v2log" "snap-1.fri"}
           (names (coord-daemon/snapshots-to-prune
                   [(f "snap-1.v2log") (f "snap-1.fri")
                    (f "snap-2.v2log") (f "snap-2.fri")] 1))))

(check! "a seq PAIR counts as one retained checkpoint, not two"
        (= 2 (count (coord-daemon/snapshots-to-prune
                     [(f "snap-1.v2log") (f "snap-1.fri")
                      (f "snap-2.v2log") (f "snap-2.fri")] 1))))

;; --- the property that matters most -----------------------------------------
;; This function's output is fed straight to .delete. Anything it does not
;; recognise must be invisible to it, even at retain=0.
(check! "never selects a file outside the exact snap-<digits>.<ext> shape"
        (empty? (coord-daemon/snapshots-to-prune
                 [(f "README") (f "coordination.log") (f "snap-.v2log")
                  (f "snap-1.v2log.tmp") (f "snapshot-1.v2log") (f "snap-abc.v2log")]
                 0)))

(check! "retain=1 still keeps the newest"
        (= #{"snap-1.v2log" "snap-2.v2log"}
           (names (coord-daemon/snapshots-to-prune (snaps 1 2 3) 1))))

;; --- the configured default -------------------------------------------------
;; 3, not 1: an interrupted or corrupt newest image must still have an intact
;; fallback before a boot falls all the way back to folding the log.
(check! "default retention keeps more than one checkpoint"
        (>= coord-daemon/snapshot-retain 2))

(println (format "snapshot_retention: %d / %d PASS"
                 (- @checks @failures) @checks))
(System/exit (if (zero? @failures) 0 1))
