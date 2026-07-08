;; ============================================================================
;; store_write_conc_test.clj — concurrent-writers CORRECTNESS gate for the
;; group-commit write path (fsync out of dlock, batched appends).
;;
;;   bb tests/store_write_conc_test.clj [port] [runtime]
;;     port    — scratch daemon port (default 48999; NEVER 7977/48942)
;;     runtime — "clojure" (default) | "bb"
;;
;; K=8 writers hammer a scratch serve-flat daemon over the socket:
;;   A. DIFFERENT (subject,pred) pairs — each writer w streams N sequential
;;      multi-valued asserts to its own @cw<w>. Gate: no lost, no duplicated,
;;      no reordered-within-subject claims — checked BOTH in the live view
;;      (:resolved) AND in the durable flat log's bytes (each acked line present
;;      exactly once, per-writer file order == issue order, :tx strictly rising).
;;   B. SAME (subject,pred) pair — K writers race rounds of read-:version ->
;;      :assert{:base v} on a single-valued pred. Gate: OCC still fires on stale
;;      bases (rejects > 0, shape {:reject :conflict}), every attempt is acked
;;      OR rejected (none vanish), and exactly ONE live value remains.
;;   C. Explicit stale-base probe: a base observed before 5 later commits MUST
;;      be rejected.
;; SAFETY: throwaway log; kills its own daemon; refuses live ports.
;; ============================================================================
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])
(import '[java.net Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]
        '[java.util.concurrent CountDownLatch])

(def port (or (some-> (first *command-line-args*) Integer/parseInt) 48999))
(def runtime (or (second *command-line-args*) "clojure"))
(when (#{7977 48942} port) (println "refusing live port" port) (System/exit 2))

(def log (str "/tmp/cnf-write-conc-" port "-" (System/currentTimeMillis) ".log"))
(spit log "")
(def fram-root (let [d (io/file "coord_daemon.clj")]
                 (if (.exists d) "." (str (System/getProperty "user.home") "/code/fram"))))

(defn start-daemon! []
  (let [cmd (if (= runtime "bb")
              ["bb" "-cp" "out" "coord_daemon.clj" "serve-flat" (str port) log]
              ["clojure" "-M" "coord_daemon.clj" "serve-flat" (str port) log])]
    (.start (doto (ProcessBuilder. ^java.util.List cmd)
              (.directory (io/file fram-root))
              (.redirectErrorStream true)
              (.redirectOutput (java.io.File. (str log ".daemon-out")))))))

(defn req1 [m]
  (with-open [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (.setSoTimeout s 30000)
    (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))
          r (BufferedReader. (InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

(def daemon (start-daemon!))
(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (.destroy ^Process daemon))))
(loop [i 0]
  (when (> i 600) (println "daemon never ready") (System/exit 1))
  (when-not (try (:version (req1 {:op :version})) (catch Exception _ nil))
    (Thread/sleep 200) (recur (inc i))))

(def K 8) (def N 25) (def M 10)
(def checks (atom []))
(defn check! [name ok] (swap! checks conj [name (boolean ok)])
  (println (str (if ok "  [PASS] " "  [FAIL] ") name)))

;; ---- A. different (subject,pred) pairs: K=8 x N sequential multi asserts ----
(let [start (CountDownLatch. 1) done (CountDownLatch. K)
      acks (atom {})]                          ; writer -> [resp ...] in issue order
  (dotimes [w K]
    (.start (Thread. (fn []
                       (.await start)
                       (let [rs (vec (for [i (range N)]
                                       (req1 {:op :assert :te (str "@cw" w) :p "note"
                                              :r (str "w" w "-i" i) :base nil})))]
                         (swap! acks assoc w rs))
                       (.countDown done)))))
  (.countDown start) (.await done)
  (check! "A: every assert acked {:ok} (8x25, zero lost acks)"
          (every? (fn [[_ rs]] (and (= N (count rs)) (every? :ok rs))) @acks))
  ;; live view: exactly N distinct values per writer (no lost, no duplicated)
  (let [views (into {} (for [w (range K)] [w (req1 {:op :resolved :te (str "@cw" w) :p "note"})]))]
    (check! "A: live view has exactly N members per subject (no lost/dup)"
            (every? (fn [[w v]] (and (= N (:members v))
                                     (= (set (map #(str "w" w "-i" %) (range N)))
                                        (set (:values v)))))
                    views)))
  ;; durable log: acked lines present EXACTLY once; per-writer file order == issue
  ;; order; :tx strictly increasing within a writer (no reorder-within-subject).
  (let [lines (keep #(try (edn/read-string %) (catch Exception _ nil))
                    (str/split-lines (slurp log)))
        mine (group-by :l (filter #(and (= "note" (:p %)) (= "assert" (:op %))) lines))]
    (check! "A: flat log holds each acked claim exactly once (no dup lines)"
            (every? (fn [w] (let [rs (map :r (get mine (str "@cw" w)))]
                              (and (= N (count rs)) (= N (count (distinct rs))))))
                    (range K)))
    (check! "A: per-writer file order == issue order (no reorder within subject)"
            (every? (fn [w] (= (mapv :r (get mine (str "@cw" w)))
                               (mapv #(str "w" w "-i" %) (range N))))
                    (range K)))
    (check! "A: per-writer :tx strictly increasing in the log"
            (every? (fn [w] (let [txs (mapv :tx (get mine (str "@cw" w)))]
                              (= txs (vec (sort (distinct txs))))))
                    (range K)))))

;; ---- B. same (subject,pred): OCC must still fire under the group commit -----
(let [_ (req1 {:op :assert :te "@occT" :p "title" :r "seed" :base nil})
      start (CountDownLatch. 1) done (CountDownLatch. K)
      outcomes (atom [])]                       ; every response, all writers
  (dotimes [w K]
    (.start (Thread. (fn []
                       (.await start)
                       (dotimes [i M]
                         (let [v (:version (req1 {:op :version}))
                               r (req1 {:op :assert :te "@occT" :p "title"
                                        :r (str "t-" w "-" i) :base v})]
                           (swap! outcomes conj r)))
                       (.countDown done)))))
  (.countDown start) (.await done)
  (let [os @outcomes
        oks (filter :ok os) rejs (filter :reject os)]
    (check! "B: every attempt acked or rejected (none vanish)"
            (= (* K M) (+ (count oks) (count rejs))))
    (check! "B: OCC conflicts still fire on stale bases (rejects > 0)"
            (pos? (count rejs)))
    (check! "B: rejects carry :conflict + a :version"
            (every? #(and (= :conflict (:reject %)) (number? (:version %))) rejs))
    (let [v (req1 {:op :resolved :te "@occT" :p "title"})]
      (check! "B: single-valued group stays exactly 1 live (no torn supersede)"
              (and (= 1 (:members v)) (not (:ambiguous? v)))))))

;; ---- C. explicit stale base -> must reject ---------------------------------
(let [v0 (:version (req1 {:op :version}))]
  (dotimes [i 5] (req1 {:op :assert :te "@occT" :p "title" :r (str "adv-" i) :base nil}))
  (let [r (req1 {:op :assert :te "@occT" :p "title" :r "stale-write" :base v0})]
    (check! "C: write on a 5-commit-stale base is rejected (:conflict)"
            (= :conflict (:reject r))))
  (let [v (req1 {:op :resolved :te "@occT" :p "title"})]
    (check! "C: stale write left no trace (live value is the newest ok write)"
            (= "adv-4" (:value v)))))

(.destroy ^Process daemon)
(let [fails (remove second @checks)]
  (println)
  (if (empty? fails)
    (do (println (str "concurrent-writers correctness: " (count @checks) "/" (count @checks) " PASS"))
        (io/delete-file log true) (io/delete-file (str log ".daemon-out") true)
        (System/exit 0))
    (do (println (str "concurrent-writers correctness: " (count fails) " FAILED — log kept at " log))
        (System/exit 1))))
