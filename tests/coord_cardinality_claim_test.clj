;; coord_cardinality_claim_test.clj — the finding #23 daemon-seam gate.
;; Proves the DAEMON classifies predicate cardinality from log-resident
;; `@<pred> cardinality single|multi` facts IDENTICALLY to the cold CLI fold
;; (fram.fold), in BOTH directions:
;;   - a kernel-single pred forced MULTI by a claim keeps every value (accumulates)
;;   - a non-kernel pred forced SINGLE by a claim collapses to its latest value
;; across the whole-migrate path AND the incremental snapshot+tail path. This is the
;; CLI-vs-daemon equality gate the whole-vs-incremental snapshot-reconcile can't give.
;; Run: bb -cp out tests/coord_cardinality_claim_test.clj
(require '[fram.store :as c] '[fram.schema :as s] '[fram.fold :as fold] '[fram.rt] '[clojure.string :as str])
(load-file "coord_daemon.clj")
(reset! snapshot-boot-enabled? true)

(def LOG "/tmp/cnf-cardinality-claim-test.log")
(defn ln [tx op l p r] (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "test"}))
(defn write-lines! [path lines] (spit path (str (str/join "\n" lines) "\n")))
(defn append-lines! [path lines] (spit path (str (str/join "\n" lines) "\n") :append true))

;; the schema/meta predicates the daemon never MATERIALIZES as domain triples — filter them
;; from the CLI fold so it compares like-for-like against the daemon's live-name-triples
;; (the store-materialization view). NB (F4): schema-writable facts ARE surfaced in the
;; daemon's CLIENT read view (client-view-facts), but live-name-triples is the domain-pure
;; reconcile view, so this filter is unchanged.
(def schema-ps #{"cardinality" "value_kind" "name" "store-supersedes"})
(defn cli-domain [log]
  (set (remove #(schema-ps (:p %)) (:facts (fold/fold (fram.rt/read-log log))))))

(let [checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm (boolean ok)]))]

  ;; clean any prior snapshot dir/sidecar so a stale image can't mask a regression
  (doseq [p [(str LOG ".snap")]]
    (let [f (java.io.File. p)] (when (.exists f) (.delete f))))
  (let [d (java.io.File. (str LOG ".snapshots"))]
    (when (.exists d) (doseq [f (.listFiles d)] (.delete f))))

  ;; ===================================================================
  ;; (A) WHOLE-MIGRATE: both cardinality facts + domain facts in one log
  ;;   @title cardinality multi   -> a KERNEL-SINGLE pred forced MULTI (accumulates)
  ;;   @tag   cardinality single  -> a NON-KERNEL pred forced SINGLE (collapses)
  ;; ===================================================================
  (write-lines! LOG
    [(ln 1 "assert" "@title" "cardinality" "multi")   ; claim overrides env (title is kernel-single)
     (ln 2 "assert" "@tag"   "cardinality" "single")  ; claim overrides fallback (tag is multi)
     (ln 3 "assert" "@T1"    "title" "A")
     (ln 4 "assert" "@T1"    "title" "B")             ; higher tx; multi => BOTH survive
     (ln 5 "assert" "@T1"    "tag"   "x")
     (ln 6 "assert" "@T1"    "tag"   "y")])           ; higher tx; single => collapses to y

  ;; --- CLI side (cold fold) ---
  (let [dom (cli-domain LOG)
        titles (set (map :r (filter #(and (= (:l %) "@T1") (= (:p %) "title")) dom)))
        tags   (set (map :r (filter #(and (= (:l %) "@T1") (= (:p %) "tag"))   dom)))]
    (chk "CLI: kernel-single 'title' forced MULTI -> keeps {A,B}" (= #{"A" "B"} titles))
    (chk "CLI: non-kernel 'tag' forced SINGLE -> collapses to {y}" (= #{"y"} tags)))

  ;; --- daemon side (whole migrate) ---
  (let [co (migrate-flat->co LOG) st (:store co) t1 (s/resolve-name st "@T1")]
    (chk "daemon: s/cardinality title == multi (claim wins over env-single)"
         (= "multi" (s/cardinality st "title")))
    (chk "daemon: s/cardinality tag == single (claim wins over multi-fallback)"
         (= "single" (s/cardinality st "tag")))
    (chk "daemon: title (multi) keeps {A,B}" (= #{"A" "B"} (set (s/lookup-all st t1 "title"))))
    (chk "daemon: tag (single) collapses to y" (= "y" (s/lookup st t1 "tag")))
    ;; the money assertion: daemon domain state == cold CLI fold domain state
    (chk "CLI ≡ daemon: domain triples set-equal" (= (cli-domain LOG) (live-name-triples co))))

  ;; ===================================================================
  ;; (B) INCREMENTAL: cardinality claim + its flip land in the TAIL, past a boot,
  ;; so the tail-fold key fn (tail-keyed-latest / apply-tail!) is exercised, not
  ;; the whole migrate. snapshot-reconcile is the whole-vs-incremental gate; the
  ;; live == cold-fold check is the daemon-vs-CLI gate. Both must hold.
  ;; ===================================================================
  (let [f (java.io.File. (str LOG ".snap"))] (when (.exists f) (.delete f)))
  ;; base log: 'tag' has NO cardinality claim yet -> multi (fallback); two edges live.
  (write-lines! LOG
    [(ln 1 "assert" "@T1" "title" "First")
     (ln 2 "assert" "@T1" "tag"   "a")
     (ln 3 "assert" "@T1" "tag"   "b")])
  (boot-flat! LOG)
  (chk "B/boot(cold): live == whole-migrate" (:ok (snapshot-reconcile)))
  ;; TAIL: declare tag SINGLE across the boundary, then two more tag values. Under the
  ;; new claim, tag collapses to its single latest — the tail keyer must honor the claim.
  (let [base (current-seq @co)]
    (append-lines! LOG
      [(ln (+ base 1) "assert" "@tag" "cardinality" "single")   ; FLIP tag multi->single in the tail
       (ln (+ base 2) "assert" "@T1"  "tag" "c")
       (ln (+ base 3) "assert" "@T1"  "tag" "d")])              ; highest tx -> the survivor
  (maybe-reload!)
  (chk "B/reload(tail-fold): live == whole-migrate (reconcile ok)" (:ok (snapshot-reconcile)))
  (chk "B/reload: live == cold CLI fold (daemon≡CLI over the tail)"
       (= (cli-domain LOG) (live-name-triples @co)))
  (let [st (:store @co) t1 (s/resolve-name st "@T1")]
    (chk "B: tail flip to single honored -> tag collapses to {d}"
         (= #{"d"} (set (s/lookup-all st t1 "tag"))))
    (chk "B: s/cardinality tag == single (tail claim materialized)"
         (= "single" (s/cardinality st "tag")))))

  (let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (println "\ncnf-cardinality-claim:" (count cs) "/" (count cs) "PASS")
      (do (println "\ncnf-cardinality-claim:" (count fails) "FAILED") (System/exit 1)))))
