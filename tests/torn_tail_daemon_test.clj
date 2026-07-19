;; torn_tail_daemon_test.clj — parity of the daemon's incremental tail reader
;; (coord_daemon read-log-tail*) with fram.rt/read-log's torn/fail-closed policy
;; (thread 019f791c). Run from the worktree root:
;;
;;   bb -cp out tests/torn_tail_daemon_test.clj
;;
;; read-log-tail* is a same-file private fn; load-file puts it in `user` so a bare
;; call reaches it. Its seek-hint contract (from-byte may split a line) means the
;; ONE tolerated silent skip survives: an unparseable FIRST fragment after a
;; too-low seek. Genuine mid-tail corruption fails closed; a torn unparseable tail
;; recovers + warns with the exact byte offset.
(require '[clojure.string :as str] '[clojure.java.io :as io])
(load-file "coord_daemon.clj")

(def failures (atom 0))
(defn check [name ok?]
  (println (str "  [" (if ok? "PASS" "FAIL") "] " name))
  (when-not ok? (swap! failures inc)))

(defn bytelen ^long [^String s] (long (alength (.getBytes s "UTF-8"))))
(defn tmp-log [^String content]
  (let [f (java.io.File/createTempFile "torn-tail-daemon-" ".log")]
    (.deleteOnExit f)
    (with-open [os (io/output-stream f)] (.write os (.getBytes content "UTF-8")))
    (.getAbsolutePath f)))
(defn capture-err [thunk]
  (let [sw (java.io.StringWriter.)]
    (try {:val (binding [*err* sw] (thunk)) :err (str sw)}
         (catch Throwable e {:threw e :err (str sw)}))))

;; multi-byte value in an early line so byte offset != char offset
(def l1 (pr-str {:tx 10 :op "assert" :l "@café" :p "title" :r "Café ☕"}))
(def l2 (pr-str {:tx 11 :op "assert" :l "@b" :p "note" :r "ok"}))

(println "torn-tail daemon tail reader:")

;; ---- 1. unparseable torn final tail: recover accumulated, warn at exact offset ----
(let [prefix (str l1 "\n" l2 "\n")
      torn "{:tx 12 :op \"assert\" :l \"@c\" :p \"ti"           ; unbalanced, no newline
      off (bytelen prefix)
      path (tmp-log (str prefix torn))
      {:keys [val err threw]} (capture-err #(read-log-tail* path 0 9))]
  (check "torn tail: no throw" (nil? threw))
  (check "torn tail: prior tail lines recovered (2)" (= 2 (count (:lines val))))
  (check "torn tail: max-tx counts recovered lines (11)" (= 11 (:max-tx val)))
  (check (str "torn tail: warning names exact byte offset " off)
         (boolean (re-find (re-pattern (str "\\b" off "\\b")) (or err "")))))

;; ---- 2. mid-tail corruption (unparseable, TERMINATED, real position): fail closed ----
(let [good (str l1 "\n")
      bad  "{:tx 11 :op broken (((\n"                            ; unparseable, terminated
      off  (bytelen good)
      path (tmp-log (str good bad (pr-str {:tx 12 :op "assert" :l "@d" :p "n" :r "z"}) "\n"))
      {:keys [threw]} (capture-err #(read-log-tail* path 0 9))]
  (check "mid-tail: FAILS CLOSED (throws)" (some? threw))
  (check (str "mid-tail: error names exact byte offset " off)
         (boolean (and threw (re-find (re-pattern (str "\\b" off "\\b"))
                                      (.getMessage ^Throwable threw))))))

;; ---- 3. seek-straddle first fragment tolerated (from-byte lands mid-line) ----
(let [content (str l1 "\n" l2 "\n")
      ;; seek into the MIDDLE of line 1 -> first segment is an unparseable fragment
      straddle (quot (bytelen l1) 2)
      path (tmp-log content)
      {:keys [val threw]} (capture-err #(read-log-tail* path straddle 9))]
  (check "seek-straddle: no throw (partial first fragment dropped)" (nil? threw))
  (check "seek-straddle: the intact later line is still read (@b)"
         (= ["@b"] (mapv :l (:lines val)))))

;; ---- 4. clean tail: silent, all past-tx lines read ----
(let [path (tmp-log (str l1 "\n" l2 "\n"))
      {:keys [val err threw]} (capture-err #(read-log-tail* path 0 9))]
  (check "clean tail: no throw" (nil? threw))
  (check "clean tail: both lines read" (= 2 (count (:lines val))))
  (check "clean tail: zero warnings" (str/blank? (or err ""))))

(if (zero? @failures)
  (println "\ntorn-tail daemon: ALL PASS")
  (do (println (str "\ntorn-tail daemon: " @failures " FAILED")) (System/exit 1)))
