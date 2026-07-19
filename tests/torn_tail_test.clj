;; torn_tail_test.clj — deep-recovery contract for fram.rt/read-log (thread
;; 019f791c). Three bars + one distinction-guard, run from the worktree root:
;;
;;   bb -cp out tests/torn_tail_test.clj
;;
;; 1. TORN FINAL line (unparseable, UNTERMINATED tail — the live daemon appends
;;    without fsync, so a reader can catch a mid-write tail): every prior fact is
;;    recovered AND exactly one warning names the EXACT UTF-8 byte offset where the
;;    torn line starts. Byte offset, not char offset: an earlier value carries a
;;    multi-byte char so char-count != byte-count and only the byte answer passes.
;; 2. CLEAN log: zero warnings on *err*.
;; 3. MID-LOG corruption (unparseable line that IS newline-terminated / non-final):
;;    fail closed — an explicit error naming file + byte offset, never a silent skip.
;; 4. DISTINCTION GUARD: an EDN-VALID-but-incomplete final line (parses, missing :r)
;;    is NOT torn and NOT corrupt — it is returned as a FactOp (fold filters it,
;;    max-tx counts its :tx) with no warning and no throw. Preserves migrate-flat->co.
(require '[fram.rt :as rt]
         '[fram.fold :as fold]
         '[clojure.java.io :as io])

(def failures (atom 0))
(defn check [name ok?]
  (println (str "  [" (if ok? "PASS" "FAIL") "] " name))
  (when-not ok? (swap! failures inc)))

(defn bytelen ^long [^String s] (long (alength (.getBytes s "UTF-8"))))

(defn capture-err
  "Run thunk; return {:val v :err <stderr-string>} or {:threw e :err ...}."
  [thunk]
  (let [sw (java.io.StringWriter.)]
    (try
      (let [v (binding [*err* sw] (thunk))]
        {:val v :err (str sw)})
      (catch Throwable e
        {:threw e :err (str sw)}))))

(defn tmp-log [^String content]
  (let [f (java.io.File/createTempFile "torn-tail-" ".log")]
    (.deleteOnExit f)
    (with-open [os (io/output-stream f)]
      (.write os (.getBytes content "UTF-8")))
    (.getAbsolutePath f)))

;; --- shared valid prefix: two complete, newline-terminated lines. The first
;; value carries a multi-byte char (☕ = 3 UTF-8 bytes, café's é = 2) so the byte
;; offset of anything after it exceeds its char offset. -------------------------
(def line1 (pr-str {:tx 1 :op "assert" :l "@café" :p "title" :r "Café ☕ time"}))
(def line2 (pr-str {:tx 2 :op "assert" :l "@b" :p "note" :r "ok"}))
(def prefix (str line1 "\n" line2 "\n"))
(def prefix-bytes (bytelen prefix))

(println "torn-tail deep-recovery:")

;; ---- Bar 1: torn final line recovers prior + warns with exact byte offset ----
(let [torn "{:tx 3 :op \"assert\" :l \"@c\" :p \"tit"   ; unbalanced, no newline
      path (tmp-log (str prefix torn))
      {:keys [val err threw]} (capture-err #(rt/read-log path))]
  (check "torn final: no throw (recovers, does not fail closed)" (nil? threw))
  (check "torn final: all prior facts recovered (2)" (= 2 (count val)))
  (check "torn final: recovered facts are the prior ones"
         (= [1 2] (mapv :tx (or val []))))
  (check "torn final: exactly one warning emitted"
         (= 1 (count (filter #(re-find #"(?i)torn" %)
                             (remove clojure.string/blank?
                                     (clojure.string/split-lines (or err "")))))))
  (check (str "torn final: warning names EXACT byte offset " prefix-bytes
              " (byte, not char)")
         (boolean (re-find (re-pattern (str "\\b" prefix-bytes "\\b")) (or err ""))))
  ;; char offset would be smaller than byte offset here — prove we didn't emit it
  (check "torn final: char offset (< byte offset) is NOT what was emitted"
         (not= (count prefix) prefix-bytes)))

;; ---- Bar 2: clean log is silent -------------------------------------------
(let [path (tmp-log prefix)
      {:keys [val err threw]} (capture-err #(rt/read-log path))]
  (check "clean: no throw" (nil? threw))
  (check "clean: all facts read (2)" (= 2 (count val)))
  (check "clean: ZERO warnings on stderr"
         (clojure.string/blank? (or err ""))))

;; ---- Bar 3: mid-log corruption fails closed --------------------------------
(let [good1 (str line1 "\n")
      bad   "{:tx 2 :op broken not-edn (((\n"           ; unparseable, TERMINATED
      good3 (str (pr-str {:tx 3 :op "assert" :l "@d" :p "note" :r "z"}) "\n")
      corrupt-offset (bytelen good1)
      path (tmp-log (str good1 bad good3))
      {:keys [val err threw]} (capture-err #(rt/read-log path))]
  (check "mid-log: FAILS CLOSED (throws, never silent skip)" (some? threw))
  (check "mid-log: does not return a partial fold"
         (nil? val))
  (check "mid-log: error names the file path"
         (boolean (and threw (clojure.string/includes? (.getMessage ^Throwable threw) path))))
  (check (str "mid-log: error names the exact byte offset " corrupt-offset)
         (boolean (and threw (re-find (re-pattern (str "\\b" corrupt-offset "\\b"))
                                      (.getMessage ^Throwable threw))))))

;; ---- Bar 4: EDN-valid-but-incomplete final line is a DISTINCT, silent case --
(let [incomplete (pr-str {:tx 5 :op "assert" :l "@x" :p "title"})  ; valid EDN, no :r
      path (tmp-log (str prefix incomplete))                       ; no trailing newline
      {:keys [val err threw]} (capture-err #(rt/read-log path))]
  (check "edn-incomplete: no throw (not corrupt)" (nil? threw))
  (check "edn-incomplete: no warning (not torn)" (clojure.string/blank? (or err "")))
  (check "edn-incomplete: returned as a FactOp (3 ops incl the incomplete one)"
         (= 3 (count (or val []))))
  (check "edn-incomplete: the incomplete op has nil :r"
         (nil? (:r (last (or val [nil])))))
  (check "edn-incomplete: its :tx counts toward max-tx (5)"
         (= 5 (fold/max-tx (or val [])))))

(if (zero? @failures)
  (println "\ntorn-tail: ALL BARS PASS")
  (do (println (str "\ntorn-tail: " @failures " FAILED")) (System/exit 1)))
