;; vguard_floor_test.clj — Reification R0: the vGUARD rollback floor (B2 §2/§3/§5).
;; Run from the repo root:  bb -cp out tests/vguard_floor_test.clj
;;
;; Focused receipts for the floor laws, every one against REAL kernel state
;; (real FileChannel locks across processes, real chmod/EACCES, real renames):
;;   1. WRITER ADMISSION — cold append (fram set seam) and the daemon group
;;      batch seam BLOCK under a live exclusive rewrite lock held by ANOTHER
;;      PROCESS, and the ack (fn return / ticket delivery) lands only after the
;;      bytes are fsynced — never inside a flip's window.
;;   2. EACCES — an append to a 0444-fenced log fails LOUD (real EACCES), the
;;      corpus bytes untouched.
;;   3. GENERATION REFUSAL — import and merge FAIL CLOSED on a generation-managed
;;      corpus; --force does not override; bytes untouched.
;;   4. INTENT DOCTOR — a crashed flip rolls BACK (coordination not renamed) or
;;      FORWARD (renamed) classified by recorded inos/shas, restoring the EXACT
;;      recorded modes (0600/0660 fixtures — never a constant), sweeping
;;      tmps/sidecar/snapshots, idempotent, refusing loud on unknown version /
;;      unclassifiable state / special mode bits.
;;   5. BOOT GATE — a booting daemon heals a crashed flip, BLOCKS under a live
;;      flip, and holds the shared participation lock across its boot fold.
;;   6. ROLLBACK FLOOR — :status carries :rollback_floor and `fram doctor`
;;      prints it (explicitly queryable).
;;   7. PRIVACY — doctor/refusal output never leaks corpus fact payloads.
(require '[fram.rt :as rt]
         '[fram.fold :as fold]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def failures (atom 0))
(defn check [name ok?]
  (println (str "  [" (if ok? "PASS" "FAIL") "] " name))
  (when-not ok? (swap! failures inc)))

(def base (str "/tmp/vguard-floor-test-" (.pid (java.lang.ProcessHandle/current))))
(defn scratch! [n] (let [d (io/file base n)] (.mkdirs d) (.getPath d)))
(defn sha16 [^bytes bs]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)]
    (subs (apply str (map #(format "%02x" %) d)) 0 16)))
(defn fbytes ^bytes [path] (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
(defn ino [path] (rt/file-ino path))
(defn mode [path] (rt/file-mode path))
(defn now-ms [] (System/currentTimeMillis))

;; --- the cross-process lock holder (REAL kernel arbitration, not same-JVM) --
(def holder-src
  (str "(def lockf (first *command-line-args*))\n"
       "(def ms (Long/parseLong (second *command-line-args*)))\n"
       "(def shared? (= \"shared\" (nth *command-line-args* 2 \"excl\")))\n"
       "(def raf (java.io.RandomAccessFile. lockf \"rw\"))\n"
       "(def ch (.getChannel raf))\n"
       "(.lock ch 0 Long/MAX_VALUE shared?)\n"
       "(println \"HELD\") (flush)\n"
       "(Thread/sleep ms)\n"
       "(.close ch)\n"))
(def holder-path (str base "/lock_holder.clj"))
(.mkdirs (io/file base))
(spit holder-path holder-src)

(defn hold-lock!
  "Spawn a subprocess holding the rewrite lock for ms; returns the process
  after it confirms HELD (the lock is kernel-granted before we return)."
  [log-path ms kind]
  (let [pr (p/process ["bb" holder-path (rt/rewrite-lock-path log-path) (str ms) kind]
                      {:out :stream :err :inherit})
        rdr (io/reader (:out pr))]
    (when-not (= "HELD" (.readLine rdr))
      (throw (ex-info "lock holder failed to start" {})))
    pr))

(defn write-corpus! [dir lines]
  (let [f (str dir "/coordination.log")]
    (spit f (str (str/join "\n" (map pr-str lines)) "\n"))
    f))

(def plain-lines
  [{:tx 1 :op "assert" :l "@t1" :p "title" :r "SECRET-PAYLOAD-XYZZY" :by "coord" :ts "2026-07-20T00:00:00Z"}
   {:tx 2 :op "assert" :l "@t1" :p "owner" :r "personal" :by "coord" :ts "2026-07-20T00:00:00Z"}])
(def gen-line
  {:tx 3 :op "assert" :l "@log:gen" :p "generation" :r "0197f000-0000-7000-8000-000000000001"
   :by "fram:unify" :ts "2026-07-20T00:00:00Z" :gen_prev "genesis" :gen_n 1 :gen_src "none"
   :src_coord_bytes 0 :src_coord_sha "none" :src_telem_bytes 0 :src_telem_sha "none"})

;; ============================================================
(println "\n== 1. writer admission: cold append blocks under a live exclusive lock ==")
(let [d (scratch! "s1") log (write-corpus! d plain-lines)
      pre (alength (fbytes log))
      pr (hold-lock! log 1500 "excl")
      t0 (now-ms)
      _  (rt/append-fact-op log (fold/->FactOp 10 "assert" "@t2" "title" "later" "cli"))
      dt (- (now-ms) t0)]
  @pr
  (check (str "cold append BLOCKED under the exclusive lock (waited " dt "ms >= 1000)") (>= dt 1000))
  (check "the delayed append landed after the lock released" (> (alength (fbytes log)) pre))
  (check "appended line folds (ack was real)"
         (some #(= "@t2" (:l %)) (rt/read-log log))))

(println "\n== 1b. shared+shared coexist (no writer-vs-writer serialization regression) ==")
(let [d (scratch! "s1b") log (write-corpus! d plain-lines)
      pr (hold-lock! log 1500 "shared")
      t0 (now-ms)
      _  (rt/append-fact-op log (fold/->FactOp 11 "assert" "@t3" "title" "co" "cli"))
      dt (- (now-ms) t0)]
  (.destroy (:proc pr))
  (check (str "append under a SHARED holder is immediate (" dt "ms < 1000)") (< dt 1000)))

(println "\n== 1c. daemon group-batch seam: ticket delivered only after the flip window ==")
(load-file "coord.clj")   ; the daemon's group appender library (repo root)
(let [d (scratch! "s1c") log (write-corpus! d plain-lines)
      pr (hold-lock! log 1500 "excl")
      t0 (now-ms)
      r  (enqueue-durable! log [(str (pr-str {:tx 12 :op "assert" :l "@t4" :p "title" :r "batch" :by "coord" :ts "x"}) "\n")] nil)
      dt (- (now-ms) t0)]
  @pr
  (check (str "group batch ack DELAYED past the exclusive window (" dt "ms >= 1000)") (>= dt 1000))
  (check "batch ack is :ok" (= :ok r))
  (check "batch bytes durable at ack" (some #(= "@t4" (:l %)) (rt/read-log log))))

;; ============================================================
(println "\n== 2. real EACCES: append to a 0444-fenced log fails loud, bytes untouched ==")
(let [d (scratch! "s2") log (write-corpus! d plain-lines)
      pre (vec (fbytes log))]
  (rt/set-file-mode! log 0444)
  (let [threw (try (rt/append-fact-op log (fold/->FactOp 13 "assert" "@t5" "title" "denied" "cli"))
                   nil (catch Throwable t t))]
    (check "append threw (real EACCES, loud — the caller's ack path NACKs)" (some? threw))
    (check "corpus bytes byte-identical after the denied append" (= pre (vec (fbytes log)))))
  (rt/set-file-mode! log 0644))

;; ============================================================
(println "\n== 3. generation refusal: import/merge fail closed, --force does not override ==")
(let [d (scratch! "s3") log (write-corpus! d (conj plain-lines gen-line))
      threads (str d "/threads") _ (.mkdirs (io/file threads))
      pre (vec (fbytes log))
      env {"FRAM_LOG" log "FRAM_THREADS" threads}
      run (fn [& args] (let [r (apply p/shell {:out :string :err :string :continue true
                                               :extra-env env :dir "."} "bin/fram" args)]
                         (str (:out r) (:err r))))]
  (check "generation-managed? detects the @log:gen control record" (rt/generation-managed? log))
  (check "a plain corpus is NOT generation-managed"
         (not (rt/generation-managed? (write-corpus! (scratch! "s3p") plain-lines))))
  (let [o (run "merge" "@t1" "@t2")]
    (check "merge REFUSES on a generation-managed corpus" (str/includes? o "REFUSING merge: corpus is generation-managed")))
  (let [o (run "import")]
    (check "import REFUSES on a generation-managed corpus" (str/includes? o "REFUSING import: corpus is generation-managed")))
  (let [o (run "import" "--force")]
    (check "import --force STILL refuses (the floor is unconditional)" (str/includes? o "REFUSING import: corpus is generation-managed")))
  (check "refused corpus bytes byte-identical" (= pre (vec (fbytes log)))))

;; ============================================================
(println "\n== 4a. intent doctor: roll BACK (coordination never renamed), exact 0600/0660 ==")
(defn mk-intent [log tmap]
  (spit (rt/rewrite-intent-path log) (pr-str (merge {:v 1 :gen "g-1" :gen_n 1 :verb "unify"
                                                     :phase "fenced" :pid 1 :ts "t"} tmap))))
(let [d (scratch! "s4a")
      log (write-corpus! d plain-lines)
      telem (str d "/telemetry.log") _ (spit telem "{:tx 1 :op \"assert\" :l \"@run1\" :p \"kind\" :r \"run\"}\n")
      _ (rt/set-file-mode! log 0600) _ (rt/set-file-mode! telem 0660)
      pre (vec (fbytes log)) pret (vec (fbytes telem))
      _ (mk-intent log {:coord {:ino (ino log) :mode (mode log) :bytes (alength (fbytes log))
                                :sha (sha16 (fbytes log))}
                        :telem {:ino (ino telem) :mode (mode telem) :bytes (alength (fbytes telem))
                                :sha (sha16 (fbytes telem))}})
      _ (spit (rt/rewrite-coord-tmp-path log) "half-composed")
      _ (spit (rt/rewrite-telem-tmp-path log) "")
      ;; fence exactly as a flip would (both logs a-w)
      _ (rt/set-file-mode! log 0400) _ (rt/set-file-mode! telem 0440)
      h (rt/acquire-rewrite-lock! log false true)
      r (try (rt/doctor-rewrite-intent! log) (finally (rt/close-rewrite-lock! h)))]
  (check "classified ROLL-BACK from recorded ino (never :phase)" (= :rolled-back (:state r)))
  (check "coordination mode restored EXACTLY 0600" (= 0600 (mode log)))
  (check "telemetry mode restored EXACTLY 0660" (= 0660 (mode telem)))
  (check "coordination bytes untouched" (= pre (vec (fbytes log))))
  (check "telemetry bytes untouched" (= pret (vec (fbytes telem))))
  (check "composed tmps swept" (and (not (.exists (io/file (rt/rewrite-coord-tmp-path log))))
                                    (not (.exists (io/file (rt/rewrite-telem-tmp-path log))))))
  (check "intent deleted" (not (.exists (io/file (rt/rewrite-intent-path log)))))
  (let [h2 (rt/acquire-rewrite-lock! log false true)
        r2 (try (rt/doctor-rewrite-intent! log) (finally (rt/close-rewrite-lock! h2)))]
    (check "re-run is :clean (idempotent; A7 no-intent = touch nothing)" (= :clean (:state r2)))))

(println "\n== 4b. intent doctor: roll FORWARD (coordination renamed; K5 mid-crash) ==")
(let [d (scratch! "s4b")
      log (write-corpus! d plain-lines)
      telem (str d "/telemetry.log") _ (spit telem "{:tx 1 :op \"assert\" :l \"@run1\" :p \"kind\" :r \"run\"}\n")
      _ (rt/set-file-mode! log 0600) _ (rt/set-file-mode! telem 0660)
      old-intent {:coord {:ino (ino log) :mode (mode log) :bytes (alength (fbytes log))
                          :sha (sha16 (fbytes log))}
                  :telem {:ino (ino telem) :mode (mode telem) :bytes (alength (fbytes telem))
                          :sha (sha16 (fbytes telem))}}
      ;; compose the replacement: generation record line 1 + retained lines
      newc (str d "/.fram.rewrite.coord.compose")
      _ (spit newc (str (str/join "\n" (map pr-str (cons gen-line plain-lines))) "\n"))
      line1 (first (str/split (clojure.core/slurp newc) #"\n"))
      _ (rt/set-file-mode! newc 0444)
      new-ino (ino newc)
      _ (mk-intent log (assoc old-intent :new_coord {:ino new-ino :sha1 (sha16 (.getBytes ^String line1 "UTF-8"))}
                              :new_telem {:ino 0}))
      ;; the flip's step 7 happened (coordination renamed), step 8+ did NOT:
      _ (java.nio.file.Files/move (.toPath (io/file newc)) (.toPath (io/file log))
                                  (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      _ (spit (rt/rewrite-telem-tmp-path log) "") ; composed empty replacement
      _ (rt/set-file-mode! (rt/rewrite-telem-tmp-path log) 0444)
      _ (rt/set-file-mode! telem 0440)  ; still fenced
      ;; stale sidecar + snapshots that MUST be swept
      _ (spit (str log ".snap") "{:seq 1}")
      _ (.mkdirs (io/file (str log ".snapshots"))) _ (spit (str log ".snapshots/x.edn") "{}")
      h (rt/acquire-rewrite-lock! log false true)
      r (try (rt/doctor-rewrite-intent! log) (finally (rt/close-rewrite-lock! h)))]
  (check "classified ROLL-FORWARD from the replacement ino" (= :rolled-forward (:state r)))
  (check "telemetry EMPTIED, path preserved (bin trigger watches existence)"
         (and (.exists (io/file telem)) (zero? (.length (io/file telem)))))
  (check "coordination mode restored EXACTLY 0600" (= 0600 (mode log)))
  (check "telemetry mode restored EXACTLY 0660" (= 0660 (mode telem)))
  (check "sidecar + snapshots swept (log identity flipped)"
         (and (not (.exists (io/file (str log ".snap"))))
              (not (.exists (io/file (str log ".snapshots"))))))
  (check "intent deleted" (not (.exists (io/file (rt/rewrite-intent-path log)))))
  (check "corpus is now generation-managed (line 1 is the control record)"
         (rt/generation-managed? log))
  (let [h2 (rt/acquire-rewrite-lock! log false true)
        r2 (try (rt/doctor-rewrite-intent! log) (finally (rt/close-rewrite-lock! h2)))]
    (check "re-run is :clean (idempotent)" (= :clean (:state r2)))))

(println "\n== 4c. doctor refusals: unknown version / unclassifiable / special bits ==")
(let [d (scratch! "s4c") log (write-corpus! d plain-lines)]
  (spit (rt/rewrite-intent-path log) (pr-str {:v 99}))
  (check "unknown intent :v refuses loud naming the required version"
         (try (rt/read-rewrite-intent log) false
              (catch clojure.lang.ExceptionInfo e
                (and (:fram/doctor-refusal (ex-data e)) (str/includes? (.getMessage e) ":v 1")))))
  (mk-intent log {:coord {:ino 1 :mode 0644 :bytes 5 :sha "beef"} :new_coord {:ino 2 :sha1 "dead"}})
  (let [h (rt/acquire-rewrite-lock! log false true)]
    (check "unclassifiable corpus (no ino/sha match) refuses loud, touches nothing"
           (try (rt/doctor-rewrite-intent! log) false
                (catch clojure.lang.ExceptionInfo e (boolean (:fram/doctor-refusal (ex-data e))))
                (finally (rt/close-rewrite-lock! h)))))
  (io/delete-file (rt/rewrite-intent-path log) true)
  (check "special mode bits (setuid) refuse restore"
         (try (rt/set-file-mode! log 04755) false
              (catch clojure.lang.ExceptionInfo e (boolean (:fram/doctor-refusal (ex-data e)))))))

(println "\n== 4d. crashed-unhealed flip: the daemon batch seam refuses loud ==")
(let [d (scratch! "s4d") log (write-corpus! d plain-lines)
      pre (vec (fbytes log))]
  (mk-intent log {:coord {:ino (ino log) :mode (mode log) :bytes 1 :sha "x"}})
  (check "with-append-admission refuses with :fram/rewrite-in-progress"
         (try (rt/with-append-admission log (fn [] :wrote)) false
              (catch clojure.lang.ExceptionInfo e (boolean (:fram/rewrite-in-progress (ex-data e))))))
  (check "refused append changed nothing" (= pre (vec (fbytes log))))
  ;; the COLD seam (set/import/merge) auto-heals the same state instead:
  (rt/append-fact-op log (fold/->FactOp 14 "assert" "@t6" "title" "healed" "cli"))
  (check "cold seam healed the crashed flip then appended"
         (and (not (.exists (io/file (rt/rewrite-intent-path log))))
              (some #(= "@t6" (:l %)) (rt/read-log log)))))

;; ============================================================
(println "\n== 5. boot gate: heal-at-boot, block-under-live-flip, hold across the fold ==")
(let [d (scratch! "s5") log (write-corpus! d plain-lines)]
  (rt/set-file-mode! log 0600)
  (mk-intent log {:coord {:ino (ino log) :mode 0600 :bytes (alength (fbytes log)) :sha (sha16 (fbytes log))}})
  (spit (rt/rewrite-coord-tmp-path log) "junk")
  (let [gate (rt/boot-rewrite-gate! log)]
    (check "boot gate healed the crashed flip (intent gone, tmp swept, mode 0600)"
           (and (not (.exists (io/file (rt/rewrite-intent-path log))))
                (not (.exists (io/file (rt/rewrite-coord-tmp-path log))))
                (= 0600 (mode log))))
    ;; while the gate handle is held, a flip's exclusive tryLock must FAIL:
    (check "gate handle really holds the shared lock (exclusive try fails cross-check)"
           (nil? (rt/acquire-rewrite-lock! log false false)))
    (rt/close-rewrite-lock! gate)
    (let [h (rt/acquire-rewrite-lock! log false false)]
      (check "after close, the exclusive lock is free again" (some? h))
      (rt/close-rewrite-lock! h))))
(let [d (scratch! "s5b") log (write-corpus! d plain-lines)
      pr (hold-lock! log 1500 "excl")
      t0 (now-ms)
      gate (rt/boot-rewrite-gate! log)
      dt (- (now-ms) t0)]
  @pr
  (rt/close-rewrite-lock! gate)
  (check (str "boot gate BLOCKED under a live flip (" dt "ms >= 1000)") (>= dt 1000)))

;; ============================================================
(println "\n== 6. rollback floor queryable: daemon :status + doctor CLI ==")
(let [d (scratch! "s6") log (write-corpus! d plain-lines)
      _ (spit (str d "/telemetry.log") "")   ; pin split routing INSIDE scratch — the
      ;; ambient FRAM_TELEMETRY_LOG (north wrapper env) must never leak into the
      ;; scratch daemon or activate-split! refuses the mismatched pair.
      port (+ 8400 (rand-int 500))
      pr (p/process ["bb" "-cp" "out" "coord_daemon.clj" "serve-flat" (str port) log]
                    {:out :string :err :string
                     :extra-env {"FRAM_TELEMETRY_LOG" (str d "/telemetry.log")}})
      st (loop [k 0]
           (let [r (try (rt/coord-request-for-log port log {:op :status}) (catch Exception _ nil))]
             (cond (some? r) r
                   (> k 100) nil
                   :else (do (Thread/sleep 150) (recur (inc k))))))]
  (check "scratch daemon :status carries :rollback_floor \"vGUARD\""
         (= "vGUARD" (:rollback_floor st)))
  (.destroy (:proc pr))
  (.waitFor (:proc pr) 5 java.util.concurrent.TimeUnit/SECONDS)
  (check "scratch daemon reaped" (not (.isAlive (:proc pr)))))
(check "rollback-floor id is the pinned token" (= "vGUARD" (rt/rollback-floor-id)))

(println "\n== 7. doctor CLI: heals, prints the floor, exit 2 under a live flip, no payload leak ==")
(let [d (scratch! "s7") log (write-corpus! d plain-lines)
      threads (str d "/threads") _ (.mkdirs (io/file threads))
      env {"FRAM_LOG" log "FRAM_THREADS" threads}]
  (rt/set-file-mode! log 0600)
  (mk-intent log {:coord {:ino (ino log) :mode 0600 :bytes (alength (fbytes log)) :sha (sha16 (fbytes log))}})
  (let [r (p/shell {:out :string :err :string :continue true :extra-env env :dir "."}
                   "bin/fram" "doctor")
        o (str (:out r) (:err r))]
    (check "doctor CLI exit 0 after healing" (zero? (:exit r)))
    (check "doctor FIRST line stays the coordinator health contract (North lifecycle probe)"
           (str/starts-with? (first (str/split-lines (:out r))) "coordinator "))
    (check "doctor CLI reports the HEAL (rolled back) + exact recorded mode"
           (and (str/includes? o "HEALED") (str/includes? o "384")))  ; 0600 = 384 decimal, as recorded
    (check "doctor CLI prints the queryable floor" (str/includes? o "rollback_floor vGUARD"))
    (check "doctor output leaks NO corpus payload (privacy suppression)"
           (not (str/includes? o "SECRET-PAYLOAD-XYZZY")))
    (check "mode restored 0600 by the CLI heal" (= 0600 (mode log))))
  (let [pr (hold-lock! log 2000 "excl")
        r (p/shell {:out :string :err :string :continue true :extra-env env :dir "."}
                   "bin/fram" "doctor")]
    @pr
    (check "doctor CLI exits 2 while a live flip holds the lock" (= 2 (:exit r)))
    (check "live-flip refusal names the state, no payload leak"
           (and (str/includes? (str (:out r) (:err r)) "rewrite in progress")
                (not (str/includes? (str (:out r) (:err r)) "SECRET-PAYLOAD-XYZZY"))))))

;; ============================================================
(println "\n== 8. lock artifacts confined to scratch; cleanup ==")
(let [strays (->> (file-seq (io/file base))
                  (filter #(.isFile ^java.io.File %))
                  (filter #(str/starts-with? (.getName ^java.io.File %) ".fram.rewrite."))
                  (remove #(= ".fram.rewrite.lock" (.getName ^java.io.File %))))]
  (check "no leftover intent/tmp artifacts anywhere in scratch" (empty? strays)))
(doseq [^java.io.File f (reverse (file-seq (io/file base)))] (.delete f))
(check "scratch tree removed" (not (.exists (io/file base))))

(if (zero? @failures)
  (println "\nvguard-floor: ALL BARS PASS")
  (do (println (str "\nvguard-floor: " @failures " FAILED")) (System/exit 1)))
