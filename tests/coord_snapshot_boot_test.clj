;; coord_snapshot_boot_test.clj — thread 019f2190 plan b gate: FLAG-GATED snapshot boot.
;; coord_snapshot_test.clj proves the incremental reconstruction itself; THIS file proves
;; the activation/invalidation contract around it:
;;   - default OFF (FRAM_SNAPSHOT_BOOT gates checkpoint consumption at boot);
;;   - a checkpoint boot reproduces the whole-log fold EXACTLY — state AND version,
;;     torn tail line included;
;;   - EVERY invalidation (stale fold fingerprint, rotated/reset log, truncated log,
;;     torn sidecar, torn image) falls back to the whole-log fold with a reason —
;;     a bad checkpoint may cost a slower boot, NEVER wrong state.
;; Run from the repo root: bb -cp out tests/coord_snapshot_boot_test.clj
(require '[fram.store :as c] '[fram.schema :as s] '[fram.fold :as fold] '[fram.rt]
         '[clojure.string :as str])
(load-file "coord_daemon.clj")

(def LOG "/tmp/store-snapshot-boot-test.log")
(defn ln [tx op l p r] (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "test"}))
(defn write-lines! [path lines] (spit path (str (str/join "\n" lines) "\n")))
(defn append-lines! [path lines] (spit path (str (str/join "\n" lines) "\n") :append true))
(defn clean! []
  (let [d (java.io.File. (str LOG ".snapshots"))]
    (when (.exists d) (doseq [f (.listFiles d)] (.delete f)) (.delete d)))
  (doseq [p [LOG (str LOG ".snap") (str LOG ".snap.tmp")]]
    (let [f (java.io.File. p)] (when (.exists f) (.delete f)))))

(defn ground-truth [] (live-name-triples (migrate-flat->co LOG)))
(defn fold-version-of-log [] (:version (fold/fold (fram.rt/read-log LOG))))
(defn boot-mode [] (:mode @last-boot))
(defn boot-reason [] (str (:reason @last-boot)))

(let [checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm (boolean ok)]))]
  (clean!)
  (reset! snapshot-boot-enabled? true)

  ;; --- seed a log, boot, checkpoint, then append a tail PAST the checkpoint ---
  (write-lines! LOG
    [(ln 1 "assert" "@T1" "title" "First")     ; single (kernel single-valued)
     (ln 2 "assert" "@T1" "tag"   "a")         ; multi
     (ln 3 "assert" "@T2" "title" "Two")])
  (boot-flat! LOG)
  (write-snapshot! @co LOG)
  (chk "checkpoint: fold fingerprint covers resolver-derived snapshot state"
       (some #{"chartroom/src/resolve.clj"} fold-fingerprint-files))
  (chk "checkpoint: sidecar carries a fold_version stamp" (some? (:fold_version (read-sidecar LOG))))
  (chk "checkpoint: sidecar carries a log_identity stamp" (some? (:log_identity (read-sidecar LOG))))
  (chk "checkpoint: atomic writes leave no .tmp litter"
       (and (not (.exists (java.io.File. (str (sidecar-path LOG) ".tmp"))))
            (not (.exists (java.io.File. (str (:image (read-sidecar LOG)) ".tmp"))))))
  (let [base (current-seq @co)]
    (append-lines! LOG
      [(ln (+ base 1) "assert"  "@T1" "title" "Updated")   ; supersede across the boundary
       (ln (+ base 2) "assert"  "@T3" "title" "Three")     ; new subject
       (ln (+ base 3) "retract" "@T1" "tag"   "a")])       ; retract a checkpoint-era edge
    ;; TORN final line: EDN-valid but missing :r — the realistic append-no-fsync state
    (spit LOG (str (pr-str {:tx (+ base 4) :op "assert" :l "@torn" :p "title"}) "\n") :append true))
  (def truth (ground-truth))                 ; independent whole-migrate ground truth
  (def truth-version (fold-version-of-log))  ; UNFILTERED fold version (counts the torn tx)

  ;; --- (1) snapshot boot == whole-log fold: state AND version, torn tail tolerated ---
  (boot-flat! LOG)
  (chk "snapshot boot: used the checkpoint" (= :snapshot (boot-mode)))
  (chk "snapshot boot: state == whole-migrate ground truth" (= (live-name-triples @co) truth))
  (chk "snapshot boot: version == unfiltered fold version (torn tail counted)"
       (= (current-seq @co) truth-version))
  (chk "snapshot boot: reconcile gate green" (:ok (snapshot-reconcile @co LOG)))
  (let [st (:store @co) t1 (s/resolve-name st "@T1")]
    (chk "tail superseded the checkpoint-era single" (= "Updated" (s/lookup st t1 "title")))
    (chk "tail retracted the checkpoint-era multi edge" (empty? (s/lookup-all st t1 "tag")))
    (chk "torn line NOT applied as state" (nil? (s/resolve-name st "@torn"))))

  ;; --- (2) DEFAULT OFF: the flag gates checkpoint consumption ---
  (reset! snapshot-boot-enabled? false)
  (boot-flat! LOG)
  (chk "flag OFF: whole-log fold" (= :fold (boot-mode)))
  (chk "flag OFF: reason says disabled" (str/includes? (boot-reason) "disabled"))
  (chk "flag OFF: state still correct" (= (live-name-triples @co) truth))
  (reset! snapshot-boot-enabled? true)

  ;; --- (3) fold-version mismatch: checkpoint from OLDER fold logic self-invalidates ---
  (write-sidecar! LOG (assoc (read-sidecar LOG) :fold_version "stale-fold-fingerprint"))
  (boot-flat! LOG)
  (chk "stale fold_version: fallback" (= :fold (boot-mode)))
  (chk "stale fold_version: reason names the mismatch" (str/includes? (boot-reason) "fold-version mismatch"))
  (chk "stale fold_version: state correct via fold" (= (live-name-triples @co) truth))
  (write-sidecar! LOG (assoc (read-sidecar LOG) :fold_version (fold-fingerprint)))  ; restore
  (boot-flat! LOG)
  (chk "restored fold_version: checkpoint path again" (= :snapshot (boot-mode)))

  ;; --- (4) log identity mismatch: a rotated/RESET log invalidates the checkpoint ---
  (def saved-log (slurp LOG))
  (write-lines! LOG [(ln 1 "assert" "@FRESH" "title" "brand-new history")])
  (boot-flat! LOG)
  (chk "reset log: fallback" (= :fold (boot-mode)))
  (chk "reset log: reason = log identity" (str/includes? (boot-reason) "log identity"))
  (chk "reset log: state == fold of the NEW log (never the stale checkpoint)"
       (= (live-name-triples @co) (ground-truth)))
  (spit LOG saved-log)

  ;; --- (5) offset past EOF: log truncated below the checkpoint boundary ---
  ;; keep line 1 (identity intact) but cut everything after -> length < byte_offset
  (spit LOG (str (first (str/split-lines saved-log)) "\n"))
  (boot-flat! LOG)
  (chk "truncated log: fallback" (= :fold (boot-mode)))
  (chk "truncated log: reason = offset past EOF" (str/includes? (boot-reason) "past EOF"))
  (chk "truncated log: state == fold of what remains" (= (live-name-triples @co) (ground-truth)))
  (spit LOG saved-log)

  ;; --- (6) torn/corrupt sidecar EDN -> unreadable -> fallback ---
  (spit (sidecar-path LOG) "{:seq 5 :image \"torn-mid-wri")   ; direct spit, not write-sidecar!
  (boot-flat! LOG)
  (chk "torn sidecar: fallback" (= :fold (boot-mode)))
  (chk "torn sidecar: reason names the sidecar" (str/includes? (boot-reason) "sidecar"))
  (chk "torn sidecar: state correct via fold" (= (live-name-triples @co) truth))

  ;; --- (7) torn IMAGE: hash gate rejects, fallback ---
  (write-snapshot! @co LOG)                   ; fresh good checkpoint (log grew @snapshot:* facts)
  (def truth2 (ground-truth))
  (let [img (:image (read-sidecar LOG))
        s   (slurp img)]
    (spit img (subs s 0 (quot (count s) 2))))   ; image torn mid-write
  (boot-flat! LOG)
  (chk "torn image: fallback (hash gate)" (= :fold (boot-mode)))
  (chk "torn image: reason names the image" (str/includes? (boot-reason) "image"))
  (chk "torn image: state correct via fold" (= (live-name-triples @co) truth2))

  ;; --- (8) missing sidecar entirely -> fallback (the today-in-prod case) ---
  (.delete (java.io.File. (sidecar-path LOG)))
  (boot-flat! LOG)
  (chk "no sidecar: fallback" (= :fold (boot-mode)))
  (chk "no sidecar: state correct via fold" (= (live-name-triples @co) truth2))

  ;; --- report ---
  (let [cs @checks fails (remove second cs)]
    (println "\n=== snapshot boot: flag gate + invalidation matrix (thread 019f2190 plan b) ===")
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (do (println (str "\nSnapshot boot: " (count cs) " / " (count cs) " PASS")) (System/exit 0))
      (do (println (str "\nSnapshot boot: " (count fails) " FAILED")) (System/exit 1)))))
