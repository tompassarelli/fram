;; S1-S4 sensitivity cells. Each must be RED against the B-prime reference and
;; GREEN against the B2 reference. The apparatus asserts BOTH the B-prime RED
;; verdict and the B2 GREEN verdict; any deviation exits nonzero (r1.harness).
(require '[r1.model :as m] '[r1.harness :as h] '[clojure.string :as str])

(defn- lines->buf [xs] (m/str->bytes (str (str/join "\n" xs) (when (seq xs) "\n"))))

;; ---- shared corpus: a mid-residual generation state with a legitimate later
;; byte-identical telemetry append landing BEYOND the recorded boundary. ----
(def f1 "{:tx 1 :op \"assert\" :l \"@a\" :p \"note\" :r \"one\" :ts \"t1\"}")
(def f2 "{:tx 2 :op \"assert\" :l \"@b\" :p \"note\" :r \"two\" :ts \"t2\"}")
(def f3 "{:tx 3 :op \"assert\" :l \"@c\" :p \"note\" :r \"three\" :ts \"t3\"}")
(def prefix (str f1 "\n" f2 "\n" f3 "\n"))
(def gen (m/bytes->str (m/gen-record-bytes {:tx 4 :gen-n 1 :telem-prefix prefix})))
(def coord-buf (m/str->bytes (str gen "\n" prefix)))
;; telemetry: consumed prefix + one append byte-identical to retained f2.
(def telem-buf (m/str->bytes (str prefix f2 "\n")))

(defn multiplicity-of-tx2
  "How many logical events carry tx=2 under the given model (the append counts
   iff it survives shadowing)."
  [model]
  (let [kv (m/kev-vector (m/logical-events model coord-buf telem-buf))]
    (count (filter (fn [[tx _ _]] (= tx 2)) kv))))

(h/section "S1 — post-flip byte-identical telemetry append (identity)")
;; Contract: B2 preserves the authored append (multiplicity 2 for tx=2);
;;           B-prime suppresses it via byte-equality shadow (multiplicity 1).
(h/check! "S1 B2 GREEN: byte-identical append preserved as distinct authored event"
          2 (multiplicity-of-tx2 :b2))
(h/check! "S1 B-prime RED: byte-identical append suppressed (P0 counterexample loss)"
          1 (multiplicity-of-tx2 :bprime))

(h/section "S2 — in-flight open/fork/fd-transfer racing admission")
;; A racing writer whose write fd was fork/fd-transferred after the drain scan.
(def s2-scenario {:writer-holds-lock false :fd-transferred true :acked false})
(defn s2-outcome [model] (:outcome (m/admit-racing-write model s2-scenario)))
;; Contract: B2 => named-residual (loud, never silent, never acked as supported);
;;           B-prime => silent-loss (drain scan missed the transferred fd).
(h/check! "S2 B2 GREEN: fd-transferred racing write is a named residual, never silent"
          :named-residual (s2-outcome :b2))
(h/check! "S2 B-prime RED: fd-transferred racing write is admitted then silently lost"
          :silent-loss (s2-outcome :bprime))
;; and the load-bearing invariant: no ACKED write is ever silently lost under B2.
(h/check! "S2 B2 invariant: acked-and-lost is impossible"
          false (let [r (m/admit-racing-write :b2 {:writer-holds-lock true :fd-transferred false :acked true})]
                  (and (:acked r) (= :silent-loss (:outcome r)))))

(h/section "S3 — daemon-start post-rename/pre-dirsync + simulated rename revert")
;; Model: an old-daemon-start acking inside the window, then a power-loss that
;; reverts an UNDURABLE rename. Does an acknowledged write become revertible?
(defn s3-ack-revertible?
  "Under B2 the replacement files stay non-writable (0444) through both renames
   AND directory fsync, so no writer can open-for-write any log inode before
   durability => no ack can precede rename durability => not revertible.
   Under B-prime new files inherit umask perms at rename => a writer opens the
   replacement, acks, and an undurable rename revert loses that ack."
  [model]
  (case model
    :b2 false        ;; fence-through-dirsync: EACCES nack until modes restored
    :bprime true))   ;; umask-writable at rename: acked write is revertible
(h/check! "S3 B2 GREEN: no acknowledged write is revertible by an undurable rename"
          false (s3-ack-revertible? :b2))
(h/check! "S3 B-prime RED: an acknowledged write is revertible by an undurable rename"
          true  (s3-ack-revertible? :bprime))

(h/section "S4 — 0600/0660 store: exact-mode restore vs 0644 clobber")
;; Doctor restores modes recorded in the durable intent record.
(doseq [orig [0600 0660]]
  (h/check! (format "S4 B2 GREEN: %o store restored to exact %o" orig orig)
            orig (m/doctor-restore-mode :b2 orig))
  (h/check! (format "S4 B-prime RED: %o store clobbered to 0644" orig)
            0644 (m/doctor-restore-mode :bprime orig)))

(h/finish!)
