;; W7 adversarial receipts: malformed log tails and hostile world inputs.
;; Run from the repository root:
;;   bb -cp out tests/adversarial_world_fuzz_test.clj
(require '[clojure.java.io :as io]
         '[fram.store :as c]
         '[fram.world :as w])
(load-file "coord.clj")

(def failures (atom 0))
(def total (atom 0))

(defn check [label ok?]
  (swap! total inc)
  (println (str "  [" (if ok? "PASS" "FAIL") "] " label))
  (when-not ok? (swap! failures inc)))

(defn b8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn read-bytes ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
(defn sha256 [^bytes content]
  (apply str
         (map #(format "%02x" %)
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       content))))
(defn log-sha [path] (sha256 (read-bytes path)))
(defn flen ^long [path] (.length (io/file path)))
(defn append-bytes! [path ^bytes content]
  (with-open [os (java.io.FileOutputStream. path true)]
    (.write os content)))
(defn reopen [path] {:store (replay path) :log path :lock (Object.)})

(def scratch
  (str (System/getProperty "java.io.tmpdir")
       "/fram-adversarial-fuzz-" (System/nanoTime)))
(.mkdirs (io/file scratch))
(def copies (atom 0))
(defn copy-log [source tag]
  (let [path (str scratch "/" tag "-" (swap! copies inc) ".log")]
    (io/copy (io/file source) (io/file path))
    path))

(def log (str scratch "/fixture.log"))
(def co (new-coord log))
(register-pred! co "status" "single" "literal")
(commit! co "w7" "fuzz-fixture" "status" :assert "ready" nil)
(def root (w/version-id nil []))
(world-create! co "w7" "A" root)
(def candidate
  (:ok (world-begin! co "w7" "A" root
                     "0123456789abcdef0123456789abcdef")))
(def baseline (c/dump-store (replay log)))

(defn safe-call [thunk]
  (try
    {:value (thunk)}
    (catch Throwable e
      {:threw (or (ex-message e) (str e))
       :data (ex-data e)})))

(defn typed-outcome? [{:keys [value threw]}]
  (and (nil? threw)
       (map? value)
       (or (contains? value :ok)
           (and (keyword? (:reject value))
                (not (contains? value :ok))))))

(def malformed-log-tails
  [(b8 "{")
   (b8 "{:k :fact")
   (b8 "\"unterminated")
   (b8 "#{]")
   (b8 "{:k :commit :tx")
   (byte-array [-1 -2 0 123])])

(def malformed-replays
  (mapv
   (fn [[i tail]]
     (let [path (copy-log log (str "malformed-" i))
           _ (append-bytes! path tail)
           result (safe-call #(c/dump-store (replay path)))]
       {:index i :result result :same? (= baseline (:value result))}))
   (map-indexed vector malformed-log-tails)))

(def hostile-names
  [{:label :nil :value nil :expect :world-name-illegal}
   {:label :nul :value (str "bad" (char 0) "name")
    :expect :world-name-illegal}
   {:label :non-nfc :value (str "e" "\u0301")
    :expect :world-name-illegal}
   {:label :oversized :value (apply str (repeat (inc w/max-name-bytes) "n"))
    :expect :world-name-too-long}
   ;; World names are logical graph keys, not paths. Traversal spelling is safe
   ;; to accept so long as the coordinator returns a typed outcome and never
   ;; interprets it as a filesystem path.
   {:label :traversal-spelling :value "../logical-world" :expect nil}])

(def name-results
  (mapv
   (fn [{:keys [label value expect]}]
     (let [result (safe-call #(world-create! co "w7" value root))]
       {:label label :expect expect :result result}))
   hostile-names))

(def hostile-slots
  [{:label :nul :value (str "src/" (char 0) "/x")
    :expect :world-slot-illegal}
   {:label :non-nfc :value (str "src/e" "\u0301" ".bclj")
    :expect :world-slot-not-nfc}
   {:label :parent :value "../escape.bclj" :expect :world-slot-illegal}
   {:label :interior-parent :value "src/../escape.bclj"
    :expect :world-slot-illegal}
   {:label :dot :value "./escape.bclj" :expect :world-slot-illegal}
   {:label :empty-segment :value "src//escape.bclj"
    :expect :world-slot-illegal}
   {:label :oversized :value (apply str (repeat (inc w/max-slot-bytes) "s"))
    :expect :world-slot-too-long}])

(def slot-results
  (mapv
   (fn [{:keys [label value expect]}]
     {:label label :expect expect
      :result (safe-call
               #(world-append! co "w7" candidate
                               {:op :delete :slot value}))})
   hostile-slots))

;; These are malformed EDN *values* at the coordinator's record boundary. A
;; keyword lookup on each must remain total and route to a typed slot reject.
(def malformed-records
  [nil 0 true :keyword "not-a-record" [] [[:slot "x"]] #{}])
(def record-results
  (mapv (fn [record]
          {:record record
           :result (safe-call #(world-append! co "w7" candidate record))})
        malformed-records))

(defn random-bytes ^bytes [n seed]
  (let [raw (byte-array n)
        random (java.util.Random. (long seed))]
    (.nextBytes random raw)
    raw))

(def blob-sizes [(dec w/max-blob-bytes)
                 w/max-blob-bytes
                 (inc w/max-blob-bytes)])
(def blob-results
  (mapv
   (fn [n]
     (let [sha0 (log-sha log)
           len0 (flen log)
           result (safe-call #(world-blob-put! co "w7"
                                                (random-bytes n (+ 7000 n))))]
       {:size n :result result
        :bytes-pure? (and (= sha0 (log-sha log)) (= len0 (flen log)))}))
   blob-sizes))

(println "adversarial malformed-log replay:")
(check "all malformed EDN/random byte tails replay without an exception"
       (every? #(nil? (get-in % [:result :threw])) malformed-replays))
(check "all malformed tails are dropped without half-applying state"
       (every? :same? malformed-replays))

(println "\nadversarial world names and slots:")
(check "all hostile world names return typed outcomes without crashing"
       (every? #(typed-outcome? (:result %)) name-results))
(check "NUL, non-NFC, non-string, and oversized world names reject exactly"
       (every? (fn [{:keys [expect result]}]
                 (or (nil? expect) (= expect (get-in result [:value :reject]))))
               name-results))
(check "all hostile slots return typed rejects without crashing"
       (every? #(typed-outcome? (:result %)) slot-results))
(check "hostile slot reject taxonomy is exact"
       (every? (fn [{:keys [expect result]}]
                 (= expect (get-in result [:value :reject])))
               slot-results))
(check "malformed EDN record values all return typed rejects"
       (every? #(and (typed-outcome? (:result %))
                     (= :world-slot-illegal
                        (get-in % [:result :value :reject])))
               record-results))

(println "\nadversarial blob size boundary:")
(check "random blobs at max-1 and max bytes are accepted"
       (every? #(contains? (get-in % [:result :value]) :ok)
               (take 2 blob-results)))
(check "random blob at max+1 is a typed :world-blob-too-large reject"
       (let [result (last blob-results)]
         (and (typed-outcome? (:result result))
              (= :world-blob-too-large
                 (get-in result [:result :value :reject])))))
(check "oversized blob rejection leaves log SHA-256 and length unchanged"
       (:bytes-pure? (last blob-results)))

(let [pass (- @total @failures)]
  (println (str "\nadversarial-world-fuzz: " pass "/" @total " PASS"))
  (when-not (zero? @failures)
    (println "  malformed replays:" (pr-str malformed-replays))
    (println "  names:" (pr-str name-results))
    (println "  slots:" (pr-str slot-results))
    (println "  records:" (pr-str record-results))
    (println "  blobs:" (pr-str blob-results))
    (System/exit 1)))
