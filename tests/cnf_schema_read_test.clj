;; cnf_schema_read_test.clj — F4: schema-writable facts are READ-VISIBLE.
;; F3 opened the daemon's WRITE path for cardinality/value_kind; the READ view still hid
;; them (the pre-F3 blanket schema-pred filter in claim->triple), so `show <pred>` returned
;; nothing even though the fact was in the log and the cold fold saw it — breaking the
;; shipped "predicates are entities" promise AND warm<->cold parity (the CLI daemon-first
;; read path is contracted to render byte-identical to the cold fold).
;;
;; The store CANNOT host the schema read view: migrate's def-predicate! seeds a cardinality
;; + value_kind claim for EVERY predicate (subject = the pred-name's value-object), so the
;; reified store holds ~1 seed pair per pred that the cold fold (log lines only) never emits.
;; So the read view sources schema-writable facts from the LOG (schema-view), exactly what
;; the cold fold reads. Proves, hermetically (synthetic store via boot-flat!, NEVER the live
;; 7977 coordinator):
;;   (a) PARITY (load-bearing): after do-assert @title cardinality single, the daemon's
;;       :claims-op view INCLUDES that fact AND equals the cold fold of the appended log —
;;       set-equal always, and VECTOR/byte-identical at real-log scale (>8 keys, hash-order).
;;   (b) name / cnf-supersedes (hard-reserved) stay HIDDEN from the domain read view.
;;   (c) show-path: q-by-l over the warm view for "@title" returns the cardinality fact.
;;   (d) acyclic (a plain domain pred) is UNAFFECTED — present, byte-identical to cold.
;;   (e) do-retract drops the fact from the read view (cold fold drops it too — parity holds).
;; Run: bb -cp out tests/cnf_schema_read_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s] '[fram.fold :as fold] '[fram.kernel :as ck] '[fram.rt] '[clojure.string :as str])
(load-file "cnf_coord_daemon.clj")
(reset! snapshot-boot-enabled? false)          ; force the deterministic cold whole-migrate boot

(def LOG "/tmp/cnf-schema-read-test.log")
(defn ln [tx op l p r] (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "test"}))
(defn write-lines! [path lines] (spit path (str (str/join "\n" lines) "\n")))

;; the daemon's :claims-op view (what coord serves the CLI): client read view = domain +
;; log-sourced schema facts, in fold-emission order — exactly the :claims op's computation.
(defn daemon-triples [] (mapv (fn [c] [(:l c) (:p c) (:r c)]) (fold/refold-order (client-view-claims @co))))
;; the cold CLI fold of the SAME flat log — the parity target.
(defn cold-triples   [] (mapv (fn [c] [(:l c) (:p c) (:r c)]) (fold/refold-order (:claims (fold/fold (fram.rt/read-log LOG))))))
(defn has-claim? [triples l p r] (boolean (some #(= % [l p r]) triples)))
(defn has-pred?  [triples p]     (boolean (some #(= p (nth % 1)) triples)))

;; a >8-DISTINCT-KEY log so refold-order keys a PersistentHashMap (hash-order, input-order
;; independent) — the REAL-LOG regime where the warm<->cold byte-identity actually holds.
;; (A tiny log keys a PersistentArrayMap whose vals are insertion-ordered, so daemon and
;; cold differ in ORDER even pre-F4 for pure domain claims — an artifact, not our concern.)
(def BASE
  (concat (for [i (range 12)] (ln (inc i) "assert" (str "@N" i) "note" (str "v" i)))
          [(ln 100 "assert" "@P1" "title" "Hi")
           (ln 101 "assert" "@depends_on" "acyclic" "true")]))   ; acyclic = plain domain pred

(let [checks (atom [])
      chk    (fn [nm ok] (swap! checks conj [nm (boolean ok)]))]

  ;; ---- (a) PARITY — schema fact visible AND daemon == cold fold ----
  (write-lines! LOG BASE)
  (boot-flat! LOG)
  (let [res (do-assert "@title" "cardinality" "single" nil)
        d   (daemon-triples) cf (cold-triples)]
    (chk "(a) do-assert @title cardinality single -> :ok" (:ok res))
    (chk "(a) daemon read view INCLUDES @title cardinality single" (has-claim? d "@title" "cardinality" "single"))
    (chk "(a) cold fold INCLUDES it too (the parity target)"       (has-claim? cf "@title" "cardinality" "single"))
    (chk "(a) daemon read view SET-equals the cold fold"           (= (set d) (set cf)))
    (chk "(a) daemon read view VECTOR-equals cold (byte-identical, hash-order regime)" (= d cf)))
  ;; value_kind is likewise a read-visible fact, parity preserved
  (let [res (do-assert "@title" "value_kind" "literal" nil)
        d   (daemon-triples) cf (cold-triples)]
    (chk "(a) do-assert @title value_kind literal -> :ok" (:ok res))
    (chk "(a) value_kind fact visible in read view" (has-claim? d "@title" "value_kind" "literal"))
    (chk "(a) parity still holds after value_kind write" (= d cf)))

  ;; ---- (b) name / cnf-supersedes (hard-reserved) stay HIDDEN ----
  (write-lines! LOG BASE)
  (boot-flat! LOG)
  (let [d (daemon-triples)]
    (chk "(b) no 'name' predicate leaks into the domain read view"           (not (has-pred? d "name")))
    (chk "(b) no 'cnf-supersedes' predicate leaks into the domain read view" (not (has-pred? d "cnf-supersedes")))
    ;; the seed cardinality/value_kind pairs (one per pred) must NOT appear either:
    ;; only the log-declared schema fact is visible, never migrate's def-predicate! seeds.
    (chk "(b) no seed cardinality fact (e.g. @note) leaks — only log-declared show"
         (not (has-claim? d "@note" "cardinality" "single")))
    (chk "(b) read view SET-equals cold fold with NO schema facts (all seeds hidden)"
         (= (set d) (set (cold-triples)))))

  ;; ---- (c) show-path: q-by-l over the warm view returns the cardinality fact ----
  (write-lines! LOG BASE)
  (boot-flat! LOG)
  (do-assert "@title" "cardinality" "single" nil)
  (let [hits (mapv (fn [c] [(:l c) (:p c) (:r c)]) (ck/q-by-l (warm-claims) "@title"))]
    (chk "(c) q-by-l @title over the warm view returns the cardinality fact"
         (some #(= % ["@title" "cardinality" "single"]) hits)))

  ;; ---- (d) acyclic (plain domain pred) UNAFFECTED, byte-identical to cold ----
  (let [d (daemon-triples)]
    (chk "(d) acyclic fact present in read view"          (has-claim? d "@depends_on" "acyclic" "true"))
    (chk "(d) acyclic fact byte-identical to cold fold"   (= (some #(= % ["@depends_on" "acyclic" "true"]) d)
                                                             (some #(= % ["@depends_on" "acyclic" "true"]) (cold-triples)))))

  ;; ---- (e) do-retract drops the fact from the read view; parity holds ----
  (write-lines! LOG BASE)
  (boot-flat! LOG)
  (do-assert "@title" "cardinality" "single" nil)
  (let [res (do-retract "@title" "cardinality" "single" nil)
        d   (daemon-triples) cf (cold-triples)]
    (chk "(e) do-retract @title cardinality -> :ok" (:ok res))
    (chk "(e) read view no longer shows the cardinality fact" (not (has-claim? d "@title" "cardinality" "single")))
    (chk "(e) cold fold dropped it too (retract line keyed-latest)" (not (has-claim? cf "@title" "cardinality" "single")))
    (chk "(e) parity holds after retract" (= d cf)))

  (let [cs @checks fails (filter (fn [e] (not (second e))) cs)]
    (doseq [e cs] (println (if (second e) "  [PASS] " "  [FAIL] ") (first e)))
    (if (empty? fails)
      (println "\ncnf-schema-read:" (count cs) "/" (count cs) "PASS")
      (do (println "\ncnf-schema-read:" (count fails) "/" (count cs) "FAILED") (System/exit 1)))))
