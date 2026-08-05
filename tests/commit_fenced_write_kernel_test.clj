;; commit_fenced_write_kernel_test.clj — kernel-level linearization proof for
;; with-fence!. The action itself supplies the deterministic barrier: once it
;; starts, lease takeover must remain blocked on the same commit sequencer lock.
;;
;; Run: bb -cp out tests/commit_fenced_write_kernel_test.clj
(require '[fram.store :as c]
         '[fram.schema :as s])
(load-file "database.clj")

(def checks (atom []))
(defn check [label value]
  (swap! checks conj [label (boolean value)]))
(defn scratch-log []
  (str "/tmp/fram-fenced-kernel-" (java.util.UUID/randomUUID) ".log"))
(defn await-thread-state [thread state-name timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (= state-name (str (.getState thread))) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/yield) (recur))))))

(let [db (new-database (scratch-log))
      first (acquire-lease! db "first" "atomic-window" 25)
      entered (promise)
      release-action (promise)
      mutation
      (future
        (with-fence!
         db "atomic-window" "first" (:epoch first)
         (fn []
           (deliver entered true)
           @release-action
           (commit! db "first" "@atomic" "marker"
                    :assert "first-linearized" nil))))
      _ @entered
      ;; Let the original wall-clock lease expire while its already-admitted
      ;; action still owns the commit sequencer lock.
      _ (Thread/sleep 40)
      takeover-thread (promise)
      takeover
      (future
        (deliver takeover-thread (Thread/currentThread))
        (acquire-lease! db "second" "atomic-window" 5000))
      takeover-blocked?
      (await-thread-state @takeover-thread "BLOCKED" 2000)]
  ;; Observing the takeover thread BLOCKED on the commit sequencer monitor proves it
  ;; reached acquire-lease!. A blind sleep could pass merely because the future
  ;; had not been scheduled yet.
  (check "takeover cannot interleave after fence validation"
         (and takeover-blocked? (not (realized? takeover))))
  (deliver release-action true)
  (let [mutation-result @mutation
        takeover-result @takeover
        stale-action-ran? (atom false)
        stale
        (with-fence!
         db "atomic-window" "first" (:epoch first)
         (fn []
           (reset! stale-action-ran? true)
           (commit! db "first" "@atomic" "marker"
                    :assert "stale-overwrite" nil)))
        subject (s/resolve-name (:store db) "@atomic")
        values (set (s/lookup-all (:store db) subject "marker"))]
    (check "admitted mutation linearizes before post-expiry takeover"
           (and (:ok mutation-result)
                (:ok takeover-result)
                (= "second" (:holder takeover-result))))
    (check "old holder is fenced after takeover without invoking its action"
           (and (= :fence-lost (:reject stale))
                (not @stale-action-ran?)))
    (check "winner mutation survives the stale post-takeover attempt"
           (= #{"first-linearized"} values))))

(let [db (new-database (scratch-log))
      holder "same-holder"
      resource "same-holder-aba"
      first (acquire-lease! db holder resource 5000)
      released (release-lease! db holder resource (:epoch first))
      second (acquire-lease! db holder resource 5000)
      seeded
      (with-fence!
       db resource holder (:epoch second)
       #(commit! db holder "@same-holder-aba" "marker"
                 :assert "successor" nil))
      stale-assert-ran? (atom false)
      stale-assert
      (with-fence!
       db resource holder (:epoch first)
       (fn []
         (reset! stale-assert-ran? true)
         (commit! db holder "@same-holder-aba" "marker"
                  :assert "stale" nil)))
      stale-retract-ran? (atom false)
      stale-retract
      (with-fence!
       db resource holder (:epoch first)
       (fn []
         (reset! stale-retract-ran? true)
         (retract! db holder "@same-holder-aba" "marker"
                   "successor" nil)))
      stale-release
      (release-lease! db holder resource (:epoch first))
      subject (s/resolve-name (:store db) "@same-holder-aba")
      values (set (s/lookup-all (:store db) subject "marker"))]
  (check "same-holder reacquire advances the global epoch"
         (and (:ok first) (:ok released) (:ok second)
              (> (:epoch second) (:epoch first))))
  (check "stale same-holder epoch cannot assert"
         (and (= :fence-lost (:reject stale-assert))
              (not @stale-assert-ran?)))
  (check "stale same-holder epoch cannot retract"
         (and (= :fence-lost (:reject stale-retract))
              (not @stale-retract-ran?)))
  (check "stale same-holder release cannot remove successor"
         (and (:noop stale-release)
              (fence-ok? db resource holder (:epoch second))
              (:ok seeded)
              (= #{"successor"} values))))

(doseq [[label ok?] @checks]
  (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
(let [failed (remove second @checks)]
  (println (format "\n%d/%d passed"
                   (- (count @checks) (count failed))
                   (count @checks)))
  (when (seq failed) (System/exit 1)))
