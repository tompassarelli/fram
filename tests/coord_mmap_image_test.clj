;; coord_mmap_image_test.clj — FRAM_MMAP_IMAGE V1 (thread 019f82d9).
;; Proves the mmap-cold slice around the existing snapshot seam:
;;   (A) flag-OFF default path: checkpoint+boot are byte-for-byte the v2log path.
;;   (B) flag-ON write->boot->serve: the .fri is written, boot mmaps it (empty tail =>
;;       :cold), and by-l / by-lp / fact-render answer correctly from the mmap INCLUDING
;;       pre-checkpoint superseded exclusion — then a whole-corpus op lazily materializes
;;       byte-identically.
;;   (C) parity gate: the mmap projection == a from-scratch whole-log fold on a corpus
;;       with supersedes + multi-valued groups (mmap-reconcile).
;;   (D) tail overlay: a post-checkpoint tail forces a byte-identical heap fold at boot.
;;   (E) corrupt-a-segment: the per-segment hash gate rejects -> fallback boot still serves.
;;   (F) edges: empty store; image with 0 tail.
;; Run from the repo root: bb -cp out tests/coord_mmap_image_test.clj
(require '[fram.store :as c] '[fram.schema :as s] '[fram.fold :as fold] '[fram.rt]
         '[clojure.string :as str] '[clojure.set])
(load-file "coord_daemon.clj")

(def LOG "/tmp/store-mmap-image-test.log")
(defn ln [tx op l p r] (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "test"}))
(defn write-lines! [path lines] (spit path (str (str/join "\n" lines) "\n")))
(defn append-lines! [path lines] (spit path (str (str/join "\n" lines) "\n") :append true))
(defn clean! []
  (let [d (java.io.File. (str LOG ".snapshots"))]
    (when (.exists d) (doseq [f (.listFiles d)] (.delete f)) (.delete d)))
  (doseq [p [LOG (str LOG ".snap") (str LOG ".snap.tmp")]]
    (let [f (java.io.File. p)] (when (.exists f) (.delete f)))))
(defn ground-truth [] (live-name-triples (migrate-flat->co LOG)))
(defn boot-mode [] (:mode @last-boot))

(let [checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm (boolean ok)]))]

  ;; ======================================================================
  ;; (A) flag OFF (mmap disabled, snapshot-boot on): the v2log path, unchanged.
  ;; ======================================================================
  (clean!)
  (reset! snapshot-boot-enabled? true)
  (reset! mmap-image-enabled? false)
  (write-lines! LOG [(ln 1 "assert" "@A" "title" "First")
                     (ln 2 "assert" "@A" "tag" "x")
                     (ln 3 "assert" "@B" "title" "Bee")])
  (boot-flat! LOG)
  (write-snapshot! @co LOG)
  (chk "flag-off: NO .fri image emitted" (nil? (:fri_image (read-sidecar LOG))))
  (chk "flag-off: sidecar has no :image_format" (nil? (:image_format (read-sidecar LOG))))
  (def truth-a (ground-truth))
  (boot-flat! LOG)
  (chk "flag-off: boot used the checkpoint (v2log)" (= :snapshot (boot-mode)))
  (chk "flag-off: boot is NOT mmap-cold" (nil? @cold-image))
  (chk "flag-off: state == whole-migrate ground truth" (= (live-name-triples @co) truth-a))

  ;; ======================================================================
  ;; (B) flag ON: write -> boot(mmap-cold) -> serve primitives -> materialize.
  ;; ======================================================================
  (clean!)
  (reset! mmap-image-enabled? true)
  ;; @A: title superseded pre-checkpoint (First -> Updated); @A tag multi (x,y);
  ;; @B single title. All BEFORE the checkpoint => empty tail => mmap-cold boot.
  (write-lines! LOG [(ln 1 "assert" "@A" "title" "First")
                     (ln 2 "assert" "@A" "title" "Updated")   ; supersedes First
                     (ln 3 "assert" "@A" "tag" "x")
                     (ln 4 "assert" "@A" "tag" "y")
                     (ln 5 "assert" "@B" "title" "Bee")])
  (boot-flat! LOG)
  (write-snapshot! @co LOG)
  (chk "flag-on: .fri image emitted" (some? (:fri_image (read-sidecar LOG))))
  (chk "flag-on: sidecar :image_format = :fri" (= :fri (:image_format (read-sidecar LOG))))
  (chk "flag-on: sidecar carries per-segment sha map" (map? (:fri_segments (read-sidecar LOG))))
  (def truth-b (ground-truth))
  (boot-flat! LOG)
  (chk "flag-on: booted mmap-cold (:cold)" (some? @cold-image))
  (chk "flag-on: boot mode :snapshot, cold true" (and (= :snapshot (boot-mode)) (:cold @last-boot)))
  (chk "flag-on: cold-served? true" (cold-served?))
  ;; --- mmap-served reads WITHOUT a heap fold ---
  (chk "cold by-lp @A title = one live cid (superseded First excluded)"
       (= 1 (count (cold-by-lp "@A" "title"))))
  (chk "cold by-lp @A title renders Updated"
       (= "Updated" (nth (cold-render (first (cold-by-lp "@A" "title"))) 2)))
  (chk "cold by-lp @A tag = two live edges (multi group)"
       (= #{"x" "y"} (set (map #(nth (cold-render %) 2) (cold-by-lp "@A" "tag")))))
  (chk "cold by-l @A lists 4 live facts (title + 2 tags + name)"
       (= 4 (count (cold-by-l "@A"))))
  (chk "cold by-lp @B title renders Bee"
       (= "Bee" (nth (cold-render (first (cold-by-lp "@B" "title"))) 2)))
  (def cold-count (hybrid-fact-count))       ; served without materializing
  (chk "hybrid-fact-count stays cold (mmap handle still open)" (some? @cold-image))
  ;; --- parity gate green while mmap-cold ---
  (chk "(C) mmap-reconcile: mmap projection == whole-log fold" (:ok (mmap-reconcile LOG)))
  ;; --- first whole-corpus op lazily materializes, byte-identically ---
  (ensure-materialized!)
  (chk "hybrid-fact-count(cold) == materialized store live-fact count"
       (= cold-count (count (c/current-facts (:store @co)))))
  (chk "materialize: cold-image dropped" (nil? @cold-image))
  (chk "materialize: state == whole-migrate ground truth" (= (live-name-triples @co) truth-b))
  (chk "materialize: snapshot-reconcile green" (:ok (snapshot-reconcile @co LOG)))
  (let [st (:store @co) a (s/resolve-name st "@A")]
    (chk "materialize: @A title = Updated (supersede held)" (= "Updated" (s/lookup st a "title")))
    (chk "materialize: @A tags = {x y}" (= #{"x" "y"} (set (s/lookup-all st a "tag")))))

  ;; ======================================================================
  ;; (D) tail overlay: append a post-checkpoint tail -> heap fold at boot.
  ;; ======================================================================
  (clean!)
  (write-lines! LOG [(ln 1 "assert" "@A" "title" "First")
                     (ln 2 "assert" "@A" "tag" "x")
                     (ln 3 "assert" "@B" "title" "Bee")])
  (boot-flat! LOG)
  (write-snapshot! @co LOG)
  (let [base (current-seq @co)]
    (append-lines! LOG [(ln (+ base 1) "assert"  "@A" "title" "Updated")  ; supersede across boundary
                        (ln (+ base 2) "assert"  "@C" "title" "Cee")       ; new subject
                        (ln (+ base 3) "retract" "@A" "tag"   "x")]))       ; retract checkpoint-era edge
  (def truth-d (ground-truth))
  (boot-flat! LOG)
  (chk "tail: non-empty tail -> materialized at boot (not cold)" (nil? @cold-image))
  (chk "tail: boot mode :snapshot" (= :snapshot (boot-mode)))
  (chk "tail: state == whole-migrate ground truth" (= (live-name-triples @co) truth-d))
  (let [st (:store @co) a (s/resolve-name st "@A")]
    (chk "tail: supersede across boundary held (@A title = Updated)" (= "Updated" (s/lookup st a "title")))
    (chk "tail: retract across boundary held (@A tag empty)" (empty? (s/lookup-all st a "tag")))
    (chk "tail: new subject @C present" (some? (s/resolve-name st "@C"))))

  ;; ======================================================================
  ;; (E) corrupt-a-segment: hash gate rejects -> fallback boot still serves.
  ;; ======================================================================
  (clean!)
  (write-lines! LOG [(ln 1 "assert" "@A" "title" "First")
                     (ln 2 "assert" "@A" "title" "Updated")
                     (ln 3 "assert" "@B" "title" "Bee")])
  (boot-flat! LOG)
  (write-snapshot! @co LOG)
  (def truth-e (ground-truth))
  ;; flip a byte in the middle of the .fri facts region (post header, pre footer)
  (let [img (:fri_image (read-sidecar LOG))
        raf (java.io.RandomAccessFile. (str img) "rw")
        pos (quot (.length raf) 3)]
    (.seek raf pos)
    (let [b (.read raf)] (.seek raf pos) (.write raf (bit-xor b 0xFF)))
    (.close raf))
  (boot-flat! LOG)
  (chk "corrupt segment: NOT booted mmap-cold (hash gate rejected)" (nil? @cold-image))
  (chk "corrupt segment: state still correct via fallback" (= (live-name-triples @co) truth-e))

  ;; ======================================================================
  ;; (F) edges: empty store (schema-seed only); image with 0 tail.
  ;; ======================================================================
  (clean!)
  (write-lines! LOG [(ln 1 "assert" "@solo" "title" "Only")])
  (boot-flat! LOG)
  (write-snapshot! @co LOG)
  (def truth-f (ground-truth))
  (boot-flat! LOG)
  (chk "edge: single-fact corpus boots mmap-cold" (some? @cold-image))
  (chk "edge: cold by-lp @solo title = Only"
       (= "Only" (nth (cold-render (first (cold-by-lp "@solo" "title"))) 2)))
  (chk "edge: mmap-reconcile green on tiny corpus" (:ok (mmap-reconcile LOG)))
  (ensure-materialized!)
  (chk "edge: materialize state correct" (= (live-name-triples @co) truth-f))

  ;; --- report ---
  (let [cs @checks fails (remove second cs)]
    (println "\n=== FRAM_MMAP_IMAGE V1: mmap-cold image round-trip + parity + fallback ===")
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (do (println (str "\nmmap image: " (count cs) " / " (count cs) " PASS")) (System/exit 0))
      (do (println (str "\nmmap image: " (count fails) " FAILED")) (System/exit 1)))))
