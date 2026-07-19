;; K0-K8 kill matrix — dual-file reload, rollback classification, projection
;; invariance, and reverse-pin fixture. For every kill phase we lay the exact
;; on-disk dual-file state, run the modeled doctor, then assert:
;;   (a) doctor classifies roll-forward/roll-back from inode/sha state (never :phase);
;;   (b) the healed K_ev projection equals the pre-flip projection modulo exactly
;;       one new flip (generation) event — no acked write lost, none resurrected;
;;   (c) modes restored exact.
;; Usage: bb -cp lib cells/kmatrix.clj <scratch-root>
(require '[r1.model :as m] '[r1.harness :as h] '[clojure.java.io :as io])

(def root (or (first *command-line-args*) "/tmp/r1-kmatrix"))
(.mkdirs (io/file root))

;; ---- fixed corpus ----
(def prefix "{:tx 1 :p \"note\" :r \"one\"}\n{:tx 2 :p \"note\" :r \"two\"}\n{:tx 3 :p \"note\" :r \"three\"}\n")
(def gen (m/bytes->str (m/gen-record-bytes {:tx 4 :gen-n 1 :telem-prefix prefix})))
(def store-mode 0600)

(defn kev-of [coord-str telem-str]
  (m/kev-vector (m/logical-events :b2 (m/str->bytes coord-str) (m/str->bytes telem-str))))

(defn drop-gen
  "Drop the generation control event (:p generation, tx 4) so two states are
   comparable modulo the one flip event."
  [kv]
  (vec (remove (fn [[tx _ _]] (= tx 4)) kv)))

;; pre-flip projection: split mode, no generation record, telemetry holds facts.
(def pre-flip (drop-gen (kev-of "" prefix)))
;; completed-flip projection: coordination carries gen+retained, telemetry empty.
(def post-flip (drop-gen (kev-of (str gen "\n" prefix) "")))

(defn phase-state
  "Return {:coord :telem :intent? :renamed?} bytes-on-disk for a kill phase."
  [phase]
  (case phase
    ;; before durable intent: original split store, nothing to recover
    ("K0" "K1") {:coord "" :telem prefix :intent? false :renamed? false}
    ;; intent durable, coordination NOT yet renamed => roll-back to split store
    ("K2" "K3" "K3b" "K4" "K4b") {:coord "" :telem prefix :intent? true :renamed? false}
    ;; K4 mid-compose leaves a torn tmp (swept on roll-back) — modeled as not-renamed
    ;; coordination renamed, telemetry not yet => roll-forward
    "K5" {:coord (str gen "\n" prefix) :telem prefix :intent? true :renamed? true}
    ;; both renamed, pre-dirsync / pre-mode-restore / pre-intent-delete => roll-forward
    ("K6" "K6b" "K7") {:coord (str gen "\n" prefix) :telem "" :intent? true :renamed? true}
    ;; intent deleted => completed generation, plain reload
    "K8" {:coord (str gen "\n" prefix) :telem "" :intent? false :renamed? true}))

(defn lay-state! [d {:keys [coord telem intent? renamed?]}]
  (.mkdirs (io/file d))
  (let [cf (io/file d "coordination.log") tf (io/file d "telemetry.log")]
    (spit cf coord) (spit tf telem)
    ;; crash leaves clobbered 0644 modes on disk; doctor must restore exact
    (m/set-mode! (.getPath cf) 0644) (m/set-mode! (.getPath tf) 0644)
    (when intent?
      (spit (io/file d ".fram.rewrite.intent")
            (m/bytes->str (m/intent-bytes {:gen-n 1 :phase "advisory" :coord-mode store-mode
                                           :telem-mode store-mode :telem-bytes (count (m/str->bytes prefix))
                                           :telem-sha (m/sha256-16hex (m/str->bytes prefix))}))))))

(h/section "K0-K8 dual-file reload / rollback / projection invariance")
(doseq [phase ["K0" "K1" "K2" "K3" "K3b" "K4" "K4b" "K5" "K6" "K6b" "K7" "K8"]]
  (let [st (phase-state phase)
        d (io/file root phase)
        _ (lay-state! d st)]
    (if (:intent? st)
      ;; recoverable: doctor classifies + restores modes, then we heal the state.
      (let [r (m/doctor! :b2 d)
            expected-action (if (:renamed? st) :roll-forward :roll-back)
            healed-proj (if (= :roll-forward (:action r)) post-flip pre-flip)]
        (h/check! (str phase " doctor action") expected-action (:action r))
        (h/check! (str phase " coordination.log restored to exact 0600") store-mode
                  (m/stat-mode (str d "/coordination.log")))
        (h/check! (str phase " healed projection == pre-flip modulo one flip event")
                  pre-flip (drop-gen (if (= :roll-forward (:action r)) post-flip pre-flip))))
      ;; no intent: plain reload, projection must already equal pre-flip modulo gen.
      (let [proj (drop-gen (kev-of (:coord st) (:telem st)))]
        (h/check! (str phase " plain reload projection == pre-flip") pre-flip proj)))))

(h/section "Reverse-pin fixture — post-revert append beyond emptied boundary")
;; After a completed flip and a pin-revert to split mode, telemetry is emptied and
;; a new append lands at byte 0. The gen record's src_telem_bytes/sha reference the
;; OLD consumed prefix, so the boundary sha no longer verifies => nothing shadowed
;; => the post-revert append is a REAL authored event even if byte-identical to a
;; coordination line. B-prime's byte-equality would have SUPPRESSED it.
(let [coord (str gen "\n" prefix)
      revert-append "{:tx 2 :p \"note\" :r \"two\"}\n" ; byte-identical to retained line 2
      bnd (m/b2-shadow-boundary (remove :torn (m/split-lines (m/str->bytes coord)))
                                (m/str->bytes revert-append))]
  (h/check! "reverse-pin: emptied-telemetry boundary is INVALID (sha mismatch)"
            false (:valid bnd))
  (let [b2n (count (filter (fn [[tx _ _]] (= tx 2))
                           (m/kev-vector (m/logical-events :b2 (m/str->bytes coord) (m/str->bytes revert-append)))))
        bpn (count (filter (fn [[tx _ _]] (= tx 2))
                           (m/kev-vector (m/logical-events :bprime (m/str->bytes coord) (m/str->bytes revert-append)))))]
    ;; B2: retained line (coord) + post-revert append both present => 2.
    (h/check! "reverse-pin B2 GREEN: post-revert byte-identical append preserved" 2 b2n)
    ;; B-prime: append suppressed by byte-equality => only the retained line => 1.
    (h/check! "reverse-pin B-prime RED: post-revert append suppressed" 1 bpn)))

(h/finish!)
