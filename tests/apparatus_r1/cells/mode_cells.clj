;; Exact 0600/0660 intent/mode/doctor crash cells — REAL files, REAL chmod.
;; For every kill phase where a durable intent record exists, the modeled doctor
;; must restore the EXACT recorded mode (A6). Intent absent => modes untouched
;; and equal to the pre-flip modes (A7). B-prime would clobber to 0644.
;; Usage: bb -cp lib cells/mode_cells.clj <scratch-root>
(require '[r1.model :as m] '[r1.harness :as h] '[clojure.java.io :as io])

(def root (or (first *command-line-args*) "/tmp/r1-mode-cells"))
(.mkdirs (io/file root))

(defn fresh-store!
  "Write coordination.log + telemetry.log at the given octal mode, with an intent
   record recording that mode at the given kill phase."
  [subdir mode phase]
  (let [d (io/file root subdir)]
    (.mkdirs d)
    (let [telem-prefix "{:tx 1 :p \"note\" :r \"x\"}\n"
          gen (m/bytes->str (m/gen-record-bytes {:tx 2 :gen-n 1 :telem-prefix telem-prefix}))
          cf (io/file d "coordination.log")
          tf (io/file d "telemetry.log")]
      ;; roll-forward states carry the composed coordination (gen line-1); roll-back
      ;; states do not. Phase drives which we lay down.
      (if (#{"coord-renamed" "telem-renamed" "dirsynced" "modes-restored"} phase)
        (spit cf (str gen "\n" telem-prefix))
        (spit cf telem-prefix))
      (spit tf "")
      ;; simulate the crash leaving stale/clobbered modes (0644) on disk
      (m/set-mode! (.getPath cf) 0644)
      (m/set-mode! (.getPath tf) 0644)
      (spit (io/file d ".fram.rewrite.intent")
            (m/bytes->str (m/intent-bytes {:gen-n 1 :phase phase :coord-mode mode
                                           :telem-mode mode :telem-bytes (count (m/str->bytes telem-prefix))
                                           :telem-sha (m/sha256-16hex (m/str->bytes telem-prefix))})))
      (.getPath d))))

(h/section "Exact-mode doctor crash cells (real chmod)")
(doseq [mode [0600 0660]
        phase ["fenced" "read" "composed" "coord-renamed" "telem-renamed"
               "dirsynced" "modes-restored"]]
  (let [d (fresh-store! (format "m%o-%s" mode phase) mode phase)
        r (m/doctor! :b2 d)
        cm (m/stat-mode (str d "/coordination.log"))
        tm (m/stat-mode (str d "/telemetry.log"))]
    (h/check! (format "B2 doctor @%s restores coordination.log to exact %o" phase mode) mode cm)
    (h/check! (format "B2 doctor @%s restores telemetry.log to exact %o"    phase mode) mode tm)))

(h/section "B-prime doctor clobbers to 0644 (RED)")
(doseq [mode [0600 0660]]
  (let [d (fresh-store! (format "bp-m%o" mode) mode "modes-restored")
        _ (m/doctor! :bprime d)
        cm (m/stat-mode (str d "/coordination.log"))]
    (h/check! (format "B-prime clobbers %o store to 0644" mode) 0644 cm)))

(h/section "A7 — intent absent => store modes untouched")
(let [d (io/file root "no-intent")]
  (.mkdirs d)
  (spit (io/file d "coordination.log") "{:tx 1 :p \"x\"}\n")
  (m/set-mode! (str (io/file d "coordination.log")) 0600)
  ;; no intent file present; a supported reader must not touch modes.
  (h/check! "A7 no-intent store keeps 0600" 0600 (m/stat-mode (str (io/file d "coordination.log")))))

(h/finish!)
