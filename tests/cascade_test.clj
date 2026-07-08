;; cascade_test.clj — the-model §9 atomic terminal-transition cascade. On a
;; successful, non-idempotent terminal assert (outcome|abandoned) the coordinator,
;; IN THE SAME serialized turn, retracts any live driver and closes any running
;; clock session on that thread. Driven through the real socket write path (so it
;; exercises do-assert -> terminal-cascade! exactly as production does).
;;   bb -cp out tests/cascade_test.clj      (from the repo ROOT)
(require '[fram.kernel :as k] '[fram.rt] '[clojure.java.io :as io])
(load-file "coord.clj")
(load-file "coord_daemon.clj")

(def port 7991)
(def logf "/tmp/cascade-test.log")
(spit logf "")
(boot! logf)
;; declare the cardinality the kernel single-valued set already covers; register
;; the few preds we drive so the store supersedes correctly.
(register-pred! @co "title" "single" "literal")
(register-pred! @co "driver" "single" "ref")
(register-pred! @co "outcome" "single" "literal")
(register-pred! @co "abandoned" "single" "literal")
(register-pred! @co "session_of" "single" "ref")
(register-pred! @co "start_time" "single" "literal")
(register-pred! @co "end_time" "single" "literal")

(def server (future (serve port)))
(Thread/sleep 500)

(defn live-idx [] (k/build-index (reified->facts @co)))
(defn v [] (:version (client port {:op :version})))

;; --- @W: title + driver + a running session (start_time, no end_time) --------
(client port {:op :assert :te "@W" :p "title" :r "Work W" :base (v)})
(client port {:op :assert :te "@W" :p "driver" :r "@tom" :base (v)})
(client port {:op :assert :te "@tom" :p "name" :r "Tom" :base (v)})
(client port {:op :assert :te "@S" :p "session_of" :r "@W" :base (v)})
(client port {:op :assert :te "@S" :p "start_time" :r "2026-06-22T09:00:00" :base (v)})

(def before-idx (live-idx))
(def driver-before (k/one-i before-idx "@W" "driver"))
(def end-before (k/one-i before-idx "@S" "end_time"))

;; --- terminal transition: outcome on @W => cascade fires ---------------------
(def outcome-res (client port {:op :assert :te "@W" :p "outcome" :r "shipped" :base (v)}))

(def after-idx (live-idx))
(def driver-after (k/one-i after-idx "@W" "driver"))
(def end-after (k/one-i after-idx "@S" "end_time"))
(def outcome-after (k/one-i after-idx "@W" "outcome"))

;; --- replay-idempotence: a 2nd outcome assert is :idempotent => no double-fire
(def outcome-res2 (client port {:op :assert :te "@W" :p "outcome" :r "shipped" :base (v)}))

;; --- @X: driver but NO clock — driver retracted, no session touched ----------
(client port {:op :assert :te "@X" :p "title" :r "Work X" :base (v)})
(client port {:op :assert :te "@X" :p "driver" :r "@tom" :base (v)})
(def x-before (k/one-i (live-idx) "@X" "driver"))
(client port {:op :assert :te "@X" :p "abandoned" :r "dropped" :base (v)})
(def x-after-idx (live-idx))
(def x-driver-after (k/one-i x-after-idx "@X" "driver"))

(future-cancel server)

(def checks
  [["precondition: driver present before terminal"  (= driver-before "@tom")]
   ["precondition: no end_time before terminal"     (nil? end-before)]
   ["(a) live driver on @W cleared by cascade"      (nil? driver-after)]
   ["(b) session @S got a live end_time"            (some? end-after)]
   ["(c) outcome on @W is live"                     (= outcome-after "shipped")]
   ["2nd outcome assert is idempotent (no re-fire)" (boolean (:ok outcome-res2))]
   ["abandoned on @X retracts its driver"           (nil? x-driver-after)]
   ["(@X precondition) driver was present"          (= x-before "@tom")]])

(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (do (println "\ncascade:" (count checks) "/" (count checks) "PASS") (System/exit 0))
    (do (println "\ncascade:" (count fails) "FAILED") (System/exit 1))))
