;; Actor child process for the external kill runner. Real process, real fsync,
;; real advisory flock. Roles model the contract's writer actors.
;; Usage: bb -cp lib cells/actor.clj <role> <adir> [n]
(require '[r1.model :as m] '[clojure.java.io :as io] '[babashka.process :as bp])
(import '[java.io RandomAccessFile])

(def ^:private args *command-line-args*)
(def role (nth args 0))
(def adir  (nth args 1))
(def n    (Integer/parseInt (nth args 2 "200")))
(def lockpath (str adir "/.fram.rewrite.lock"))
(def logpath  (str adir "/coordination.log"))
(def ledgerpath (str adir "/acked.ledger"))

(defn self-pid [] (.pid (java.lang.ProcessHandle/current)))

(defn with-shared-lock
  "Run f while holding a SHARED advisory lock — the supported-writer floor."
  [f]
  ;; The lock is released when the channel closes (with-open) — bb/SCI disallows
  ;; a direct .release on FileLockImpl, and channel-close release is equivalent.
  (with-open [raf (RandomAccessFile. lockpath "rw")]
    (.lock (.getChannel raf) 0 Long/MAX_VALUE true)
    (f)))

(defn append-fsync!
  "Append s to path and fsync (force true). Data durable on return."
  [path s]
  (with-open [raf (RandomAccessFile. path "rw")]
    (.seek raf (.length raf))
    (.write raf (m/str->bytes s))
    (.force (.getChannel raf) true)))

(case role
  ;; supported writer: fsync the log line FIRST, only THEN record the ack in the
  ;; ledger. Invariant: an id in the ledger is always durable in the log.
  "appender"
  (do
    (io/make-parents logpath)
    (spit logpath "" :append true) (spit ledgerpath "" :append true)
    (println (str "APPENDER pid=" (self-pid) " starting n=" n))
    (flush)
    (dotimes [i n]
      (let [id (inc i)]
        (with-shared-lock
          (fn []
            (append-fsync! logpath (str "{:tx " id " :op \"assert\" :l \"@k\" :p \"note\" :r \"" id "\" :by \"actor:appender\"}\n"))
            (append-fsync! ledgerpath (str id "\n"))))
        (Thread/sleep 3)))
    (println "APPENDER done") (flush))

  ;; SIGSTOP holder: open a pre-fence write fd, hold it, then stop itself. The
  ;; runner must detect and reap it (unsupported residual, loud).
  "sigstop"
  (let [raf (RandomAccessFile. logpath "rw")]
    (.seek raf (.length raf))
    (println (str "SIGSTOP-HOLDER pid=" (self-pid) " holding write fd, stopping"))
    (flush)
    (m/read-bytes logpath) ; touch
    (bp/shell "kill" "-STOP" (str (self-pid)))
    ;; if resumed, exit cleanly
    (.close raf))

  ;; fd-transfer: open a write fd, spawn a child that INHERITS it, then exit —
  ;; the child holds a transferred pre-fence fd (S2 in-flight scenario).
  "fd-transfer"
  (let [_ (RandomAccessFile. logpath "rw")]
    (println (str "FD-TRANSFER parent pid=" (self-pid) " spawning fd-inheriting child"))
    (flush)
    (let [p (bp/process {:inherit false :out :string}
                                      "bash" "-c" (str "sleep 30 </dev/null; echo child-done"))]
      (println (str "FD-TRANSFER child pid=" (.pid (:proc p))))
      (flush)))

  (do (println "unknown role" role) (System/exit 2)))
