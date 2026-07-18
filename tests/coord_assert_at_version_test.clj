;; coord_assert_at_version_test.clj — real socket receipt for the opt-in global
;; read-set commit seam.
;;
;; Ordinary :assert keeps cardinality-local move-C semantics: MULTI values
;; coexist and :base is only per-(subject,predicate) OCC on SINGLE predicates.
;; :assert-at-version is deliberately stronger. The daemon compares a
;; nonnegative integer :base to the GLOBAL current version and, under the same
;; dlock turn, routes an exact match through do-assert. This test uses the real
;; socket daemon because a mock of :base cannot prove that atomic boundary.
;;
;; Run: bb -cp out tests/coord_assert_at_version_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn eventually [f]
  (loop [remaining 200]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn values-of [port subject predicate]
  (set (:values (client port {:op :resolved :te subject :p predicate}))))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-assert-at-version"
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
    (check! "real socket daemon starts"
            (eventually #(integer? (:version (client port {:op :version})))))

    ;; An exact global base accepts a MULTI append. Repeating the identical
    ;; triple at the new exact head is the existing do-assert idempotent no-op:
    ;; success, no version movement, one live value.
    (let [base (:version (client port {:op :version}))
          first-write
          (client port {:op :assert-at-version
                        :te "@exact" :p "proof_sample" :r "same"
                        :base base})
          committed-head (:version (client port {:op :version}))
          duplicate
          (client port {:op :assert-at-version
                        :te "@exact" :p "proof_sample" :r "same"
                        :base committed-head})
          after-duplicate (:version (client port {:op :version}))]
      (check! "exact-base MULTI assert commits"
              (and (:ok first-write)
                   (= #{"same"} (values-of port "@exact" "proof_sample"))))
      (check! "exact-base identical triple is idempotent"
              (and (:ok duplicate)
                   (= committed-head after-duplicate)
                   (= #{"same"} (values-of port "@exact" "proof_sample")))))

    ;; Two distinct values validated at one global snapshot cannot coexist:
    ;; the first serialized request moves the head, the second sees conflict.
    (let [base (:version (client port {:op :version}))
          requests
          [{:op :assert-at-version :te "@race" :p "proof_sample"
            :r "left" :base base}
           {:op :assert-at-version :te "@race" :p "proof_sample"
            :r "right" :base base}]
          results (mapv deref
                        (mapv #(future (client port %)) requests))
          commits (filter :ok results)
          conflicts (filter #(= :conflict (:reject %)) results)]
      (check! "same-base concurrent distinct MULTI values admit exactly one"
              (and (= 1 (count commits))
                   (= 1 (count conflicts))
                   (= 1 (count (values-of port "@race" "proof_sample"))))))

    ;; A write to an unrelated group still invalidates a previously validated
    ;; global snapshot. The guarded target must remain absent.
    (let [base (:version (client port {:op :version}))
          intervening
          (client port {:op :assert
                        :te "@other" :p "other_predicate" :r "moved"})
          guarded
          (client port {:op :assert-at-version
                        :te "@guarded" :p "marker" :r "commit"
                        :base base})]
      (check! "unrelated graph write commits" (:ok intervening))
      (check! "unrelated graph write invalidates guarded assert"
              (and (= :conflict (:reject guarded))
                   (empty? (values-of port "@guarded" "marker")))))

    ;; Malformed, missing, and future bases all fail before do-assert. A future
    ;; integer is well-shaped but mismatched, so its stable rejection is
    ;; :conflict; malformed/missing inputs use :invalid-base.
    (doseq [[label base expected]
            [["missing" nil :invalid-base]
             ["negative" -1 :invalid-base]
             ["string" "0" :invalid-base]
             ["future" 999999999 :conflict]]]
      (let [request (cond-> {:op :assert-at-version
                             :te "@invalid" :p "proof_sample" :r label}
                      (some? base) (assoc :base base))
            result (client port request)]
        (check! (str label " base rejects without a write")
                (and (= expected (:reject result))
                     (empty? (values-of port "@invalid" "proof_sample"))))))

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
