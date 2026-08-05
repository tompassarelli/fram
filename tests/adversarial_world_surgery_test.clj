;; W7 adversarial receipts: candidate log surgery and promotion-reject purity.
;; Run from the repository root:
;;   bb -cp out tests/adversarial_world_surgery_test.clj
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.world :as w])
(load-file "database.clj")

(def failures (atom 0))
(def total (atom 0))

(defn check [label ok?]
  (swap! total inc)
  (println (str "  [" (if ok? "PASS" "FAIL") "] " label))
  (when-not ok? (swap! failures inc)))

(defn b8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn read-bytes ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
(defn write-bytes! [path ^bytes content]
  (with-open [os (java.io.FileOutputStream. path false)]
    (.write os content)))
(defn flen ^long [path] (.length (io/file path)))
(defn sha256 [^bytes content]
  (apply str
         (map #(format "%02x" %)
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       content))))
(defn log-sha [path] (sha256 (read-bytes path)))
(defn reopen [path] {:store (replay path) :log path :lock (Object.)})

(def scratch
  (str (System/getProperty "java.io.tmpdir")
       "/fram-adversarial-surgery-" (System/nanoTime)))
(.mkdirs (io/file scratch))
(def copies (atom 0))
(defn copy-log [source tag]
  (let [path (str scratch "/" tag "-" (swap! copies inc) ".log")]
    (io/copy (io/file source) (io/file path))
    path))

(defn without-range ^bytes [^bytes content start end]
  (let [out (java.io.ByteArrayOutputStream.)]
    (.write out content 0 (int start))
    (.write out content (int end) (int (- (alength content) end)))
    (.toByteArray out)))

(defn replace-range ^bytes [^bytes content start end ^bytes replacement]
  (let [out (java.io.ByteArrayOutputStream.)]
    (.write out content 0 (int start))
    (.write out replacement 0 (alength replacement))
    (.write out content (int end) (int (- (alength content) end)))
    (.toByteArray out)))

(def mode "100644")
(def slots ["src/w7/alpha.bclj" "src/w7/beta.bclj" "src/w7/gamma.bclj"])
(def tampered-slot "src/w7/betx.bclj")
(def nonce "0123456789abcdef0123456789abcdef")
(def winner-nonce "fedcba9876543210fedcba9876543210")
(def build-spec
  {:adapter "beagle" :toolchain "sha256:w7" :platform "x86_64-linux"
   :entrypoint "w7.core/-main" :purpose "test" :argv [] :env {}
   :locale "C" :timezone "UTC" :epoch 0 :random "none" :network "none"})

(def fixture-log (str scratch "/fixture.log"))
(def fixture
  (let [db (new-database fixture-log)
        root (w/version-id nil [])
        _ (world-create! db "w7" "A" root)
        blob (:ok (world-blob-put! db "w7" (b8 "(ns w7.core)\n")))
        cid (:ok (world-begin! db "w7" "A" root nonce))
        op-ranges
        (mapv (fn [slot]
                (let [start (flen fixture-log)
                      result (world-append! db "w7" cid
                                            (w/put-op slot mode blob))]
                  {:slot slot :start start :end (flen fixture-log)
                   :result result}))
              slots)
        seal-start (flen fixture-log)
        version (:ok (world-seal! db "w7" cid))
        seal-end (flen fixture-log)
        lock (:ok (world-lock! db version build-spec))
        receipt (:ok (world-build! db "w7" lock))]
    {:root root :blob blob :cid cid :version version :receipt receipt
     :op-ranges op-ranges :seal-start seal-start :seal-end seal-end}))

(defn reject-probe [db path expected-head cid receipt]
  (let [head0 (world-head db "A")
        sha0 (log-sha path)
        len0 (flen path)
        result (try
                 (world-promote! db "w7" "A" expected-head cid receipt)
                 (catch Throwable e
                   {:threw (or (ex-message e) (str e))}))]
    {:result result
     :head-pure? (= head0 (world-head db "A"))
     :bytes-pure? (and (= sha0 (log-sha path)) (= len0 (flen path)))}))

(defn exact-pure-reject? [probe expected]
  (and (= {:reject expected} (:result probe))
       (:head-pure? probe)
       (:bytes-pure? probe)))

;; Remove each complete interior candidate-op transaction independently. Later
;; op subjects survive, so the seal's declared count exposes every hole.
(def gap-probes
  (mapv
   (fn [{:keys [slot start end]}]
     (let [path (copy-log fixture-log (str "gap-" (hash slot)))
           source (read-bytes path)]
       (write-bytes! path (without-range source start end))
       {:slot slot
        :removed (- end start)
        :probe (reject-probe (reopen path) path (:root fixture)
                             (:cid fixture) (:receipt fixture))}))
   (:op-ranges fixture)))

;; Tamper only the second op transaction, preserving its byte length.
(def tamper-probe
  (let [{:keys [start end slot]} (second (:op-ranges fixture))
        path (copy-log fixture-log "tamper")
        source (read-bytes path)
        block (String. (java.util.Arrays/copyOfRange source (int start) (int end))
                        "UTF-8")
        changed (str/replace block slot tampered-slot)
        replacement (b8 changed)]
    (when (or (= block changed) (not= (- end start) (alength replacement)))
      (throw (ex-info "tamper fixture did not make one equal-length change"
                      {:before-bytes (- end start)
                       :after-bytes (alength replacement)})))
    (write-bytes! path (replace-range source start end replacement))
    (reject-probe (reopen path) path (:root fixture)
                  (:cid fixture) (:receipt fixture))))

;; Cut halfway through the seal transaction and discard every later lock/receipt
;; byte. Replay must drop that uncommitted tail, leaving an unsealed candidate.
(def unsealed-probe
  (let [path (copy-log fixture-log "seal-cut")
        cut (+ (:seal-start fixture)
               (quot (- (:seal-end fixture) (:seal-start fixture)) 2))]
    (with-open [raf (java.io.RandomAccessFile. (io/file path) "rw")]
      (.setLength raf cut))
    (reject-probe (reopen path) path (:root fixture)
                  (:cid fixture) (:receipt fixture))))

(def receipt-probe
  (let [path (copy-log fixture-log "receipt-invalid")]
    (reject-probe (reopen path) path (:root fixture) (:cid fixture)
                  {:version (apply str (repeat 64 "0"))})))

(def unknown-probe
  (let [path (copy-log fixture-log "candidate-unknown")]
    (reject-probe (reopen path) path (:root fixture)
                  (apply str (repeat 64 "f")) (:receipt fixture))))

(def stale-probe
  (let [path (copy-log fixture-log "head-stale")
        db (reopen path)
        winner (:ok (world-begin! db "w7" "A" (:root fixture) winner-nonce))]
    (world-append! db "w7" winner
                   (w/put-op "src/w7/winner.bclj" mode (:blob fixture)))
    (let [version (:ok (world-seal! db "w7" winner))
          lock (:ok (world-lock! db version build-spec))
          receipt (:ok (world-build! db "w7" lock))]
      (world-promote! db "w7" "A" (:root fixture) winner receipt))
    (reject-probe db path (:root fixture) (:cid fixture) (:receipt fixture))))

(println "adversarial world log surgery:")
(check "fixture contains three distinct candidate-op tx ranges"
       (and (= 3 (count (:op-ranges fixture)))
            (every? #(pos? (- (:end %) (:start %))) (:op-ranges fixture))))
(check "removing every candidate op independently yields :world-candidate-gapped"
       (every? #(= :world-candidate-gapped
                   (get-in % [:probe :result :reject]))
               gap-probes))
(check "every gapped rejection preserves head, SHA-256, and byte length"
       (every? #(and (get-in % [:probe :head-pure?])
                     (get-in % [:probe :bytes-pure?]))
               gap-probes))
(check "equal-length op tamper yields a digest-mismatch pure reject"
       (exact-pure-reject? tamper-probe :world-candidate-digest-mismatch))
(check "torn seal transaction yields an unsealed pure reject"
       (exact-pure-reject? unsealed-probe :world-candidate-unsealed))

(println "\nadversarial promotion reject purity:")
(check "unknown candidate reject is byte-pure"
       (exact-pure-reject? unknown-probe :world-candidate-unknown))
(check "stale head reject is byte-pure"
       (exact-pure-reject? stale-probe :world-head-stale))
(check "invalid receipt reject is byte-pure"
       (exact-pure-reject? receipt-probe :world-receipt-invalid))
(check "all six promotion reject branches returned typed reject maps"
       (every? #(and (map? (:result %))
                     (keyword? (get-in % [:result :reject]))
                     (nil? (get-in % [:result :ok]))
                     (nil? (get-in % [:result :threw])))
               (concat (map :probe gap-probes)
                       [tamper-probe unsealed-probe unknown-probe
                        stale-probe receipt-probe])))

(let [pass (- @total @failures)]
  (println (str "\nadversarial-world-surgery: " pass "/" @total " PASS"))
  (when-not (zero? @failures)
    (println "  gap probes:" (pr-str gap-probes))
    (println "  tamper:" (pr-str tamper-probe))
    (println "  unsealed:" (pr-str unsealed-probe))
    (println "  unknown:" (pr-str unknown-probe))
    (println "  stale:" (pr-str stale-probe))
    (println "  receipt:" (pr-str receipt-probe))
    (System/exit 1)))
