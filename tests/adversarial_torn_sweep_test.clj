;; W7 adversarial receipt: every byte cut through a final committed tx block.
;; Run from the repository root:
;;   bb -cp out tests/adversarial_torn_sweep_test.clj
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

(defn bytes ^bytes [^String s] (.getBytes s "UTF-8"))
(defn read-bytes ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
(defn write-bytes! [path ^bytes content]
  (with-open [os (java.io.FileOutputStream. path false)]
    (.write os content)))
(defn prefix-bytes ^bytes [^bytes content n]
  (java.util.Arrays/copyOfRange content 0 (int n)))
(defn store-image [path] (c/dump-store (replay path)))

(def scratch
  (str (System/getProperty "java.io.tmpdir")
       "/fram-adversarial-torn-" (System/nanoTime)))
(.mkdirs (io/file scratch))

(def log (str scratch "/source.log"))
(def co (new-coord log))
(register-pred! co "status" "single" "literal")
(commit! co "w7" "fixture" "status" :assert "ready" nil)

(def root (w/version-id nil []))
(world-create! co "w7" "sweep" root)
(def blob (:ok (world-blob-put! co "w7" (bytes "(ns sweep.core)\n"))))
(def candidate
  (:ok (world-begin! co "w7" "sweep" root
                     "0123456789abcdef0123456789abcdef")))
(world-append! co "w7" candidate
               (w/put-op "src/sweep/core.bclj" "100644" blob))

;; The seal is deliberately the final block: it contains a Version record,
;; declared op-count, sealed digest, and its terminating commit.
(def block-start (.length (io/file log)))
(world-seal! co "w7" candidate)
(def source-bytes (read-bytes log))
(def block-end (alength source-bytes))
(def block-length (- block-end block-start))
(def complete-cut
  ;; line-seq returns a final EDN record even without LF. Therefore the cut
  ;; immediately before the file's trailing LF already contains the complete
  ;; :commit record and must fold identically to the untruncated log.
  (if (= 10 (aget source-bytes (dec block-end)))
    (dec block-length)
    block-length))

(def prefix-log (str scratch "/prefix.log"))
(write-bytes! prefix-log (prefix-bytes source-bytes block-start))
(def full-log (str scratch "/full.log"))
(write-bytes! full-log source-bytes)
(def prefix-image (store-image prefix-log))
(def full-image (store-image full-log))

(def sweep
  (reduce
   (fn [acc cut]
     (let [path (str scratch "/cut-" cut ".log")
           absolute (+ block-start cut)
           expected (if (>= cut complete-cut) full-image prefix-image)
           result (try
                    (write-bytes! path (prefix-bytes source-bytes absolute))
                    {:image (store-image path)}
                    (catch Throwable e
                      {:threw (or (ex-message e) (str e))}))]
       (cond-> (update acc :cuts inc)
         (:threw result)
         (update :throws conj {:cut cut :error (:threw result)})

         (and (nil? (:threw result)) (not= expected (:image result)))
         (update :mismatches conj cut))))
   {:cuts 0 :throws [] :mismatches []}
   (range (inc block-length))))

(println "adversarial torn-write sweep:")
(check "fixture has fact and world tx blocks before the swept block"
       (and (pos? block-start)
            (= root (world-head {:store (replay prefix-log)
                                 :log prefix-log :lock (Object.)}
                                "sweep"))))
(check "final world seal block is non-empty" (pos? block-length))
(check "sweep exercised every byte boundary, including both endpoints"
       (= (inc block-length) (:cuts sweep)))
(check "no byte cut threw during replay" (empty? (:throws sweep)))
(check "each cut equals its last complete-block fold"
       (empty? (:mismatches sweep)))
(check "the full block changes the store image (test detects half-application)"
       (not= prefix-image full-image))

(let [pass (- @total @failures)]
  (println (str "\nadversarial-torn-sweep: " pass "/" @total " PASS"))
  (when-not (zero? @failures)
    (when (seq (:throws sweep))
      (println "  first throws:" (pr-str (take 3 (:throws sweep)))))
    (when (seq (:mismatches sweep))
      (println "  first mismatched cuts:" (pr-str (take 10 (:mismatches sweep)))))
    (System/exit 1)))
