;; External kill runner + acknowledged-write ledger. Spawns REAL actor processes,
;; kills them EXTERNALLY (real SIGKILL/SIGSTOP), and proves:
;;   A6 acked-write completeness — every id the appender ACKED (recorded in the
;;      ledger, after the log line was fsynced) is durably present in the log
;;      after an abrupt external kill. No acknowledged write is ever lost.
;;   SIGSTOP holder is detected (proc state 'T') and reaped — clean process exit.
;;   No child survives the runner (cleanup under kill).
;; Usage: bb -cp lib cells/kill_ledger.clj <scratch-root>
(require '[r1.model :as m] '[r1.harness :as h]
         '[clojure.java.io :as io] '[clojure.string :as str]
         '[babashka.process :as bp])

(def root (or (first *command-line-args*) "/tmp/r1-kill"))
(def here (str (System/getProperty "user.dir")))
(.mkdirs (io/file root))

(defn proc-state
  "Linux /proc state char for pid, or nil if gone. /proc files report length 0,
   so slurp/NIO transfer raises 'Invalid argument' — read the line via a stream."
  [pid]
  (try
    (with-open [r (-> (java.io.FileInputStream. (str "/proc/" pid "/stat"))
                      (java.io.InputStreamReader. "UTF-8")
                      (java.io.BufferedReader.))]
      (let [s (.readLine r)
            after (subs s (inc (.lastIndexOf s ")")))]
        ;; after ")" the next whitespace-delimited field is the state char.
        (first (str/split (str/triml after) #"\s+"))))
    (catch Throwable _ nil)))

(defn alive? [pid] (some? (proc-state pid)))

(defn wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn spawn-actor [role dir & [n]]
  (bp/process {:dir here :out :string :err :string}
              "bb" "-cp" "lib" "cells/actor.clj" role dir (str (or n 200))))

;; ---------------------------------------------------------------------------
(h/section "External kill runner — acked-write ledger completeness (A6)")
(let [d (str root "/appender")
      _ (.mkdirs (io/file d))
      p (spawn-actor "appender" d 5000)
      pid (.pid (:proc p))]
  ;; let it accumulate acks, then KILL EXTERNALLY mid-run.
  (h/note (str "appender pid=" pid))
  (Thread/sleep 900)
  (bp/destroy-tree p)                         ; SIGKILL the process tree
  (wait-until #(not (alive? pid)) 3000)
  (h/check! "appender killed externally (no longer alive)" false (alive? pid))
  ;; recover: every acked id must be durable in the log.
  (let [ledger (io/file d "acked.ledger")
        logf   (io/file d "coordination.log")
        acked  (if (.exists ledger)
                 (into #{} (remove str/blank? (str/split-lines (slurp ledger)))) #{})
        logged (if (.exists logf)
                 (into #{} (keep #(second (re-find #":tx (\d+)" %)) (str/split-lines (slurp logf)))) #{})
        missing (remove logged acked)]
    (h/note (str "acked=" (count acked) " logged=" (count logged)))
    (h/check! "A6: at least one write was acked before the kill" true (pos? (count acked)))
    (h/check! "A6: every acked id is durably present in the log (none lost)"
              [] (vec missing))))

;; ---------------------------------------------------------------------------
(h/section "SIGSTOP holder — detect + reap (cleanup under kill)")
(let [d (str root "/sigstop")
      _ (.mkdirs (io/file d))
      _ (spit (io/file d "coordination.log") "{:tx 0 :p \"seed\"}\n")
      p (spawn-actor "sigstop" d)
      pid (.pid (:proc p))]
  (let [stopped? (wait-until #(= "T" (proc-state pid)) 4000)]
    (h/check! "SIGSTOP holder reaches stopped state 'T' (unsupported residual, detected)"
              true stopped?)
    ;; reap it — the runner cleans up the stranded holder.
    (bp/destroy-tree p)
    (bp/shell {:continue true} "kill" "-CONT" (str pid))
    (bp/shell {:continue true} "kill" "-KILL" (str pid))
    (wait-until #(not (alive? pid)) 3000)
    (h/check! "SIGSTOP holder reaped (no stranded process)" false (alive? pid))))

;; ---------------------------------------------------------------------------
(h/section "In-flight fd-transfer actor — spawn + full cleanup")
(let [d (str root "/fd")
      _ (.mkdirs (io/file d))
      _ (spit (io/file d "coordination.log") "{:tx 0 :p \"seed\"}\n")
      p (spawn-actor "fd-transfer" d)
      pid (.pid (:proc p))]
  (Thread/sleep 500)
  (bp/destroy-tree p)
  (wait-until #(not (alive? pid)) 3000)
  (h/check! "fd-transfer actor tree cleaned up" false (alive? pid)))

(h/finish!)
