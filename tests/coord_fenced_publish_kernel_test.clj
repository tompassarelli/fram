;; coord_fenced_publish_kernel_test.clj — portless coordinator proof for
;; :managed-agent-publish. The real wire/disconnect/concurrency receipt is
;; tests/coord_fenced_publish_test.clj; this companion drives the exact handler
;; in-process so restricted environments can still prove the mutation kernel.
;;
;; Run: bb -cp out tests/coord_fenced_publish_kernel_test.clj
(require '[clojure.java.io :as io]
         '[fram.rt])
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))

(reset! snapshot-boot-enabled? false)

(def identity-preds ["goal" "kind" "model" "provider" "role"])
(def guard-preds ["outcome" "process_outcome" "terminal_manifest_sha256"])

(defn sha256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value)
                                   java.nio.charset.StandardCharsets/UTF_8))]
    (format "%064x" (java.math.BigInteger. 1 digest))))

(defn body [generation]
  [{:p "kind" :r "lane"}
   {:p "role" :r (str "integrator-" generation)}
   {:p "model" :r (str "model-" generation)}
   {:p "provider" :r "openai"}
   {:p "goal" :r (str "publish-" generation)}
   {:p "display_name" :r (str "lane " generation)}])

(defn marker [facts]
  (let [by-pred (into {} (map (juxt :p :r)) facts)]
    (sha256
     (apply str
            (for [predicate (sort identity-preds)
                  :let [value (get by-pred predicate)]
                  :when value]
              (str predicate "\u0000" value "\n"))))))

(defn request [subject holder generation]
  (let [facts (body generation)]
    {:op :managed-agent-publish
     :te subject :holder holder :ttl-ms 60000
     :facts facts
     :identity-preds identity-preds
     :guard-preds guard-preds
     :manifest-sha256 (marker facts)}))

(defn subject-map [subject]
  (into {}
        (for [predicate (concat identity-preds
                                ["display_name" "identity_manifest_sha256"
                                 "outcome" "process_outcome"
                                 "terminal_manifest_sha256"])
              :let [values (live-string-values subject predicate)]
              :when (= 1 (count values))]
          [predicate (first values)])))

(defn identity-lines [log subject]
  (filter #(= subject (:l %)) (fram.rt/read-log log)))

(let [dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-fenced-publish-kernel"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (.getPath (io/file dir "facts.log"))
      _ (spit log "")
      _ (boot-flat! log)
      checks (atom [])
      check! (fn [label value] (swap! checks conj [label (boolean value)]))]
  (try
    ;; Match North's schema-as-facts deployment: managed identity and marker
    ;; predicates are declared single-valued before the first publication.
    (doseq [predicate (concat identity-preds
                              ["display_name" "identity_manifest_sha256"
                               "outcome" "process_outcome"
                               "terminal_manifest_sha256"])]
      (let [res (handle {:op :assert :te (str "@" predicate)
                         :p "cardinality" :r "single"})]
        (when-not (:ok res)
          (throw (ex-info "failed to seed single-valued predicate"
                          {:predicate predicate :response res})))))

    (let [subject "@agent:kernel"
          req (request "agent:kernel" "managed-agent-writer:kernel" "kernel")
          res (handle req)
          snapshot (subject-map subject)
          lines (identity-lines log subject)
          expected-resource (str "managed-agent-write:" (sha256 subject))]
      (check! "known canonical NUL-joined marker vector is byte-identical"
              (= "23e0d0d2707fb24074f21eed0022790555a63b122b212b39d098f1e1da7824b2"
                 (:manifest-sha256 req)))
      (check! "handler normalizes subject and derives canonical lease resource"
              (and (:ok res)
                   (= subject (:te res))
                   (= expected-resource (:resource res))))
      (check! "handler acknowledges only after exact body plus marker readback"
              (and (= (:manifest-sha256 req)
                      (get snapshot "identity_manifest_sha256"))
                   (every? (fn [{:keys [p r]}] (= r (get snapshot p)))
                           (:facts req))))
      (check! "body and marker persist under one identity transaction"
              (and (= (inc (count (:facts req))) (count lines))
                   (= 1 (count (set (map :tx lines))))))
      (check! "internally acquired lease is released after success"
              (nil? (read-lease @co expected-resource)))

      (let [before (count lines)
            replay (handle req)
            after (count (identity-lines log subject))]
        (check! "exact replay is idempotent with no double publication"
                (and (:ok replay) (:idempotent replay) (= before after)))))

    (let [subject "@agent:mismatch"
          req (assoc (request subject "managed-agent-writer:mismatch" "mismatch")
                     :manifest-sha256 (apply str (repeat 64 "0")))
          res (handle req)]
      (check! "manifest mismatch rejects before acquiring or mutating"
              (and (= :manifest-mismatch (:code res))
                   (empty? (identity-lines log subject))
                   (nil? (read-lease @co
                                     (str "managed-agent-write:"
                                          (sha256 subject)))))))

    (let [subject "@agent:reserved"
          req0 (request subject "managed-agent-writer:reserved" "reserved")
          req (update req0 :facts conj {:p "name" :r "@evil"})
          res (handle req)]
      (check! "mid-batch reserved predicate rejection is all-or-nothing"
              (and (= :reserved-in-batch (:code res))
                   (empty? (identity-lines log subject)))))

    (let [subject "@agent:held"
          resource (str "managed-agent-write:" (sha256 subject))
          lease (handle {:op :acquire-lease :res resource
                         :holder "legacy-holder" :ttl-ms 60000})
          res (handle (request subject "managed-agent-writer:held" "held"))]
      (check! "existing canonical subject lease remains the sole ordering authority"
              (and (:ok lease)
                   (= :held (:reject res))
                   (empty? (identity-lines log subject))))
      (handle {:op :release-lease :res resource
               :holder "legacy-holder" :epoch (:epoch lease)}))

    (let [subject "@agent:guarded"
          _ (handle {:op :assert :te subject :p "outcome" :r "legacy-terminal"})
          before (subject-map subject)
          res (handle (request subject "managed-agent-writer:guarded" "guarded"))]
      (check! "guarded reused subject rejects without disturbing prior state"
              (and (= :publish-conflict (:code res))
                   (= before (subject-map subject)))))

    ;; Existing entry points are deliberately untouched.
    (let [single (handle {:op :assert :te "@legacy:single"
                          :p "note" :r "still-works"})
          batch (handle {:op :assert-batch :te "@legacy:batch"
                         :facts [{:p "a" :r "1"} {:p "b" :r "2"}]})]
      (check! "legacy :assert remains accepted" (:ok single))
      (check! "legacy :assert-batch remains accepted" (and (:ok batch) (:batch batch))))

    (finally
      (doseq [[label ok?] @checks]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [failed (remove second @checks)]
        (println (format "\n%d/%d passed"
                         (- (count @checks) (count failed))
                         (count @checks)))
        (when (seq failed) (System/exit 1))))))
