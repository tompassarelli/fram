;; coord_assert_batch_atomicity_test.clj — real-socket receipt for :assert-batch,
;; the atomic multi-fact publication op (thread 019f9063 / incident 019f8958).
;;
;; The bug it closes: `send` published from/subject/body/sent_at/to as SEPARATE
;; single-fact :assert requests, so a crash/disconnect mid-send left a torn subject
;; (an observed from-only orphan). :assert-batch admits the whole set as ONE request
;; → ONE dlock turn → ONE store tx + ONE flat-log fsync, all-or-none.
;;
;; This test drives the REAL socket daemon because only a real socket can induce the
;; disconnect/timeout boundaries the all-or-none bar is stated against: a truncated
;; request, a disconnect before the ack, a mid-batch validation reject, and concurrent
;; writers. Each boundary must leave the complete subject once or nothing — never a
;; partial subject.
;;
;; Run: bb -cp out tests/coord_assert_batch_atomicity_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

;; full round-trip: send request line, read one reply line.
(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

;; SEND-AND-DROP: write the request, flush, then close WITHOUT reading the ack —
;; the disconnect-before-ack boundary. The daemon has already parsed the whole line
;; before it enters the dlock turn, so the commit runs to completion regardless.
(defn send-and-drop! [port request]
  (let [socket (java.net.Socket. "127.0.0.1" (int port))]
    (try
      (let [writer (io/writer (.getOutputStream socket))]
        (.write writer (str (pr-str request) "\n"))
        (.flush writer))
      (finally (.close socket)))))       ; slam it shut; never read the reply

;; SEND-PARTIAL: write a truncated request (no terminating newline) then close — the
;; request never becomes a complete protocol line, so parse must fail and write nothing.
(defn send-partial! [port ^String bytes]
  (let [socket (java.net.Socket. "127.0.0.1" (int port))]
    (try
      (let [writer (io/writer (.getOutputStream socket))]
        (.write writer bytes)             ; NB: no "\n"
        (.flush writer))
      (finally (.close socket)))))

(defn eventually
  ([f] (eventually f 200))
  ([f tries]
   (loop [remaining tries]
     (cond
       (try (f) (catch Exception _ false)) true
       (zero? remaining) false
       :else (do (Thread/sleep 25) (recur (dec remaining)))))))

(defn values-of [port subject predicate]
  (set (:values (client port {:op :resolved :te subject :p predicate}))))

;; a message's canonical fact set — the exact torn-subject unit from the incident.
(def msg-preds ["from" "subject" "body" "sent_at" "to"])
(defn msg-facts [m]
  (mapv (fn [p] {:p p :r (str (get m p))}) msg-preds))
(defn present-preds [port subject]
  (set (filter #(seq (values-of port subject %)) msg-preds)))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-assert-batch"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log "")
      daemon
      (proc/process
       {:dir root :out :string :err :string}
       "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
       (str port) (.getPath log))
      checks (atom [])
      check! (fn [label value]
               (swap! checks conj [label (boolean value)]))]
  (try
    ;; Boot folds the whole flat log; a large local corpus can take tens of seconds,
    ;; so the startup budget is generous (~90s) — per-op reads afterward are fast.
    (let [up? (eventually #(integer? (:version (client port {:op :version}))) 3600)]
      (check! "real socket daemon starts" up?)
      (when-not up?
        (proc/destroy-tree daemon)
        (println "DAEMON STDERR>>>\n" (:err @daemon))
        (System/exit 1)))

    ;; (1) HAPPY PATH — a whole message lands as one unit.
    (let [msg {"from" "@agent:a" "subject" "hi" "body" "the body"
               "sent_at" "2026-07-24T00:00:00" "to" "@agent:b"}
          res (client port {:op :assert-batch :te "@msg:1" :facts (msg-facts msg)})]
      (check! "batch commits, :ok + :batch marker"
              (and (integer? (:ok res)) (:batch res)))
      (check! "batch reports all five written preds"
              (= (set msg-preds) (set (:written res))))
      (check! "every message fact is live after the batch"
              (and (= #{"@agent:a"} (values-of port "@msg:1" "from"))
                   (= #{"hi"} (values-of port "@msg:1" "subject"))
                   (= #{"the body"} (values-of port "@msg:1" "body"))
                   (= #{"2026-07-24T00:00:00"} (values-of port "@msg:1" "sent_at"))
                   (= #{"@agent:b"} (values-of port "@msg:1" "to")))))

    ;; (2) REGRESSION — single-fact :assert is byte-identical and unaffected.
    (let [before (:version (client port {:op :version}))
          res (client port {:op :assert :te "@plain" :p "note" :r "solo"})
          after (:version (client port {:op :version}))]
      (check! "single :assert still returns {:ok seq}"
              (and (integer? (:ok res)) (> after before)))
      (check! "single :assert value is live"
              (= #{"solo"} (values-of port "@plain" "note"))))

    ;; (3) ALL-OR-NONE — a reserved predicate anywhere rejects the WHOLE batch; no
    ;; earlier fact in the same batch may leak.
    (let [res (client port {:op :assert-batch :te "@msg:reserved"
                            :facts [{:p "from" :r "@agent:a"}
                                    {:p "subject" :r "should not land"}
                                    {:p "name" :r "@evil"}]})]
      (check! "reserved-pred batch rejects"
              (= :reserved-in-batch (:code res)))
      (check! "rejected batch leaves ZERO partial facts (subject absent)"
              (empty? (present-preds port "@msg:reserved"))))

    ;; (4) ALL-OR-NONE — a cyclic link rejects mid-validation; the good fact ahead
    ;; of it in the batch must not land either.
    (let [res (client port {:op :assert-batch :te "@msg:cycle"
                            :facts [{:p "subject" :r "ahead of the cycle"}
                                    {:p "depends_on" :r "@msg:cycle"}]})]
      (check! "self-cycle batch rejects"
              (and (:reject res) (integer? (:at res))))
      (check! "cycle-rejected batch leaves nothing"
              (and (empty? (values-of port "@msg:cycle" "subject"))
                   (empty? (values-of port "@msg:cycle" "depends_on")))))

    ;; (5) DISCONNECT-BEFORE-ACK — send the whole batch, slam the socket before
    ;; reading the reply. The committed set must still be COMPLETE, never partial.
    (let [msg {"from" "@agent:c" "subject" "dropped-ack" "body" "b"
               "sent_at" "t" "to" "@agent:d"}]
      (send-and-drop! port {:op :assert-batch :te "@msg:drop" :facts (msg-facts msg)})
      (check! "disconnect-before-ack still commits the COMPLETE message"
              (eventually #(= (set msg-preds) (present-preds port "@msg:drop"))))
      ;; and never a strict-subset partial at any observed instant afterward
      (check! "dropped-ack subject is never a partial subset"
              (let [p (present-preds port "@msg:drop")]
                (or (empty? p) (= (set msg-preds) p)))))

    ;; (6) TRUNCATED REQUEST — a partial line (no newline) then close: the request
    ;; never parses, so nothing is written.
    (send-partial! port "{:op :assert-batch :te \"@msg:torn\" :facts [{:p \"from\" :r \"@a\"}")
    (Thread/sleep 100)
    (check! "truncated request writes nothing"
            (empty? (present-preds port "@msg:torn")))
    (check! "daemon still healthy after truncated request"
            (integer? (:version (client port {:op :version}))))

    ;; (7) IDEMPOTENT RE-SEND ("...once") — re-publishing the identical multi-valued
    ;; facts is a no-op: no duplicate live values.
    (let [facts [{:p "from" :r "@agent:x"} {:p "subject" :r "dup"} {:p "to" :r "@agent:y"}]
          first-send (client port {:op :assert-batch :te "@msg:dup" :facts facts})
          resend (client port {:op :assert-batch :te "@msg:dup" :facts facts})]
      (check! "first send writes the multi facts"
              (= #{"from" "subject" "to"} (set (:written first-send))))
      (check! "identical re-send writes nothing (idempotent)"
              (and (empty? (:written resend))
                   (= #{"from" "subject" "to"} (set (:idempotent resend)))))
      (check! "no duplicate live values after re-send"
              (and (= #{"@agent:x"} (values-of port "@msg:dup" "from"))
                   (= #{"dup"} (values-of port "@msg:dup" "subject"))
                   (= #{"@agent:y"} (values-of port "@msg:dup" "to")))))

    ;; (8) CONCURRENT WRITERS — N batches to DISTINCT subjects at once; every subject
    ;; must end complete, zero partial.
    (let [n 16
          subjects (mapv #(str "@msg:conc-" %) (range n))
          futs (mapv (fn [s]
                       (future
                         (client port {:op :assert-batch :te s
                                       :facts (msg-facts {"from" (str s "-f")
                                                          "subject" (str s "-s")
                                                          "body" (str s "-b")
                                                          "sent_at" "t"
                                                          "to" (str s "-t")})})))
                     subjects)
          results (mapv deref futs)]
      (check! "all concurrent batches acked :ok"
              (every? #(integer? (:ok %)) results))
      (check! "every concurrent subject is complete (zero partial)"
              (every? #(= (set msg-preds) (present-preds port %)) subjects)))

    ;; (9) CONCURRENT SAME-SUBJECT MULTI-APPEND — many batches append distinct multi
    ;; values to ONE subject at once; all values coexist, none torn.
    (let [n 12
          futs (mapv (fn [i]
                       (future
                         (client port {:op :assert-batch :te "@msg:shared"
                                       :facts [{:p "from" :r (str "sender-" i)}
                                               {:p "to" :r (str "recip-" i)}]})))
                     (range n))
          _ (mapv deref futs)]
      (check! "all concurrent appends to one subject coexist"
              (and (= (set (map #(str "sender-" %) (range n)))
                      (values-of port "@msg:shared" "from"))
                   (= (set (map #(str "recip-" %) (range n)))
                      (values-of port "@msg:shared" "to")))))

    ;; (10) EMPTY / MALFORMED batch is rejected cleanly, writes nothing.
    (let [empty-res (client port {:op :assert-batch :te "@msg:empty" :facts []})
          bad-res (client port {:op :assert-batch :te "@msg:bad"
                                 :facts [{:r "no-pred"}]})]
      (check! "empty :facts rejected" (= :invalid-batch (:code empty-res)))
      (check! "predicate-less fact rejected" (= :invalid-batch (:code bad-res))))

    (finally
      (proc/destroy-tree daemon)
      (try @daemon (catch Exception _ nil))
      (doseq [[label ok?] @checks]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [failed (remove second @checks)]
        (println (format "\n%d/%d passed"
                         (- (count @checks) (count failed))
                         (count @checks)))
        (when (seq failed) (System/exit 1))))))
