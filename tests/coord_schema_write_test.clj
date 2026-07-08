;; coord_schema_write_test.clj — F3: the VALIDATED schema-write gate.
;; Lane F made the engine READ log-resident `@<pred> cardinality single|multi` /
;; `@<pred> value_kind ref|literal` claims; F3 opens the daemon's domain WRITE boundary
;; so those claims can be legitimately authored. cardinality + value_kind become validated
;; domain writes (routed through the schema layer, appended verbatim so the CLI fold sees
;; them); name + cnf-supersedes stay hard-reserved. Proves, hermetically (a synthetic
;; store booted via boot-flat!, NEVER the live 7977 coordinator):
;;   (a) a cardinality write is accepted + visible to BOTH the daemon store AND the CLI fold
;;   (b) a bad value is rejected, pointed (names the allowed values / the @-prefix rule)
;;   (c) a multi->single flip with a live >1-value group is rejected, offending subjects named
;;   (d) a single->multi flip (relaxation) is accepted
;;   (e) name / cnf-supersedes are STILL reserved (rejected)
;;   (f) retracting a declaration falls back to env/fallback classification
;; Run: bb -cp out tests/coord_schema_write_test.clj
(require '[fram.store :as c] '[fram.schema :as s] '[fram.fold :as fold] '[fram.kernel :as ck] '[fram.rt] '[clojure.string :as str])
(load-file "coord_daemon.clj")
(reset! snapshot-boot-enabled? false)          ; force the deterministic cold whole-migrate boot

(def LOG "/tmp/cnf-schema-write-test.log")
(defn ln [tx op l p r] (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "test"}))
(defn write-lines! [path lines] (spit path (str (str/join "\n" lines) "\n")))
;; predname->is-single as the COLD CLI fold derives it from the (now-appended) flat log.
(defn cmap-of [log] (log-card-map (fram.rt/read-log log)))

(let [checks (atom [])
      chk    (fn [nm ok] (swap! checks conj [nm (boolean ok)]))
      reject? (fn [res] (contains? res :reject))
      msg-of  (fn [res] (first (:reject res)))]

  ;; ---- (a) cardinality write accepted + visible to daemon store AND CLI fold ----
  (write-lines! LOG [(ln 1 "assert" "@P1" "note" "hello")])   ; 'note' non-kernel -> multi fallback
  (boot-flat! LOG)
  (let [res (do-assert "@note" "cardinality" "single" nil)
        st  (:store @co)]
    (chk "(a) assert @note cardinality single -> :ok" (:ok res))
    (chk "(a) daemon store: s/cardinality note == single" (= "single" (s/cardinality st "note")))
    (chk "(a) CLI fold of appended log sees note=single" (true? (get (cmap-of LOG) "note"))))
  ;; value_kind is likewise a validated schema write
  (let [res (do-assert "@note" "value_kind" "ref" nil)]
    (chk "(a) assert @note value_kind ref -> :ok" (:ok res)))

  ;; ---- (b) bad value rejected, pointed ----
  (write-lines! LOG [(ln 1 "assert" "@P1" "note" "hello")])
  (boot-flat! LOG)
  (let [res (do-assert "@note" "cardinality" "bogus" nil)]
    (chk "(b) bad cardinality value rejected" (reject? res))
    (chk "(b) reject names allowed values single|multi" (str/includes? (msg-of res) "single|multi")))
  (let [res (do-assert "@note" "value_kind" "wat" nil)]
    (chk "(b) bad value_kind rejected, names ref|literal"
         (and (reject? res) (str/includes? (msg-of res) "ref|literal"))))
  (let [res (do-assert "note" "cardinality" "single" nil)]     ; bare subject, no @
    (chk "(b) bare subject rejected (needs @-prefix)"
         (and (reject? res) (str/includes? (msg-of res) "@-prefixed"))))

  ;; ---- (c) multi->single with live >1-value groups rejected, subjects named ----
  (write-lines! LOG
    [(ln 1 "assert" "@P1" "rel" "A")
     (ln 2 "assert" "@P1" "rel" "B")     ; @P1 rel = {A,B} live (multi)
     (ln 3 "assert" "@P2" "rel" "C")
     (ln 4 "assert" "@P2" "rel" "D")])   ; @P2 rel = {C,D} live (multi)
  (boot-flat! LOG)
  (let [res (do-assert "@rel" "cardinality" "single" nil)
        st  (:store @co) p1 (s/resolve-name st "@P1")]
    (chk "(c) multi->single flip with live >1 groups rejected" (reject? res))
    (chk "(c) reject counts 2 offending groups" (str/includes? (msg-of res) "2 live multi-valued"))
    (chk "(c) reject names @P1" (str/includes? (msg-of res) "@P1"))
    (chk "(c) reject names @P2" (str/includes? (msg-of res) "@P2"))
    (chk "(c) store UNCHANGED: rel still multi (both values live)"
         (= #{"A" "B"} (set (s/lookup-all st p1 "rel"))))
    (chk "(c) CLI fold: rel NOT collapsed (no cardinality claim landed)"
         (nil? (get (cmap-of LOG) "rel"))))

  ;; ---- (d) single->multi accepted (relaxation is always safe) ----
  (write-lines! LOG [(ln 1 "assert" "@P1" "title" "X")])   ; 'title' kernel-single
  (boot-flat! LOG)
  (let [res (do-assert "@title" "cardinality" "multi" nil)
        st  (:store @co)]
    (chk "(d) single->multi accepted" (:ok res))
    (chk "(d) daemon store: title now multi" (= "multi" (s/cardinality st "title")))
    (chk "(d) CLI fold sees title=multi" (false? (get (cmap-of LOG) "title"))))

  ;; ---- (e) name / cnf-supersedes STILL hard-reserved ----
  (write-lines! LOG [(ln 1 "assert" "@P1" "note" "hello")])
  (boot-flat! LOG)
  (chk "(e) assert name rejected reserved"
       (let [r (do-assert "@P1" "name" "foo" nil)]
         (and (reject? r) (str/includes? (msg-of r) "reserved predicate 'name'"))))
  (chk "(e) assert cnf-supersedes rejected reserved"
       (let [r (do-assert "@x" "cnf-supersedes" "y" nil)]
         (and (reject? r) (str/includes? (msg-of r) "reserved predicate 'cnf-supersedes'"))))
  (chk "(e) retract name rejected reserved"
       (let [r (do-retract "@P1" "name" "foo" nil)] (reject? r)))

  ;; ---- (f) retract of a declaration falls back to env/fallback classification ----
  ;; declare 'tag' single, then retract -> tag falls back (non-kernel -> multi).
  (write-lines! LOG
    [(ln 1 "assert" "@tag" "cardinality" "single")
     (ln 2 "assert" "@P1"  "tag" "y")])
  (boot-flat! LOG)
  (let [st (:store @co)]
    (chk "(f) pre: tag declared single" (= "single" (s/cardinality st "tag")))
    (let [res (do-retract "@tag" "cardinality" "single" nil)]
      (chk "(f) retract tag cardinality -> :ok" (:ok res))
      (chk "(f) daemon store: tag falls back to multi" (= "multi" (s/cardinality st "tag")))
      (chk "(f) CLI fold: tag declaration gone (falls back, absent from cmap)"
           (nil? (get (cmap-of LOG) "tag")))))

  (let [cs @checks fails (filter (fn [e] (not (second e))) cs)]
    (doseq [e cs] (println (if (second e) "  [PASS] " "  [FAIL] ") (first e)))
    (if (empty? fails)
      (println "\ncnf-schema-write:" (count cs) "/" (count cs) "PASS")
      (do (println "\ncnf-schema-write:" (count fails) "/" (count cs) "FAILED") (System/exit 1)))))
