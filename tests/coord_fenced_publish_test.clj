;; coord_fenced_publish_test.clj — real-socket proof for the additive
;; :managed-agent-publish operation.
;;
;; The operation is one request that derives/acquires the managed-agent subject
;; lease, commits the complete identity body + manifest marker in one store
;; transaction, verifies exact readback, and releases the lease. The legacy
;; :assert / :assert-batch / explicit lease verbs remain available unchanged.
;;
;; Run: bb -cp out tests/coord_fenced_publish_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.rt])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

(defn free-high-port []
  (loop [port (+ 8200 (rand-int 700))]
    (let [available?
          (try
            (with-open [_ (java.net.ServerSocket. port)] true)
            (catch java.net.BindException _ false))]
      (if available? port (recur (inc port))))))

(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader.
                      (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn send-and-drop! [port request]
  (let [socket (java.net.Socket. "127.0.0.1" (int port))]
    (try
      (let [writer (io/writer (.getOutputStream socket))]
        (.write writer (str (pr-str request) "\n"))
        (.flush writer))
      (finally (.close socket)))))

(defn eventually
  ([f] (eventually f 400))
  ([f tries]
   (loop [remaining tries]
     (cond
       (try (f) (catch Exception _ false)) true
       (zero? remaining) false
       :else (do (Thread/sleep 10) (recur (dec remaining)))))))

(defn sha256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value)
                                   java.nio.charset.StandardCharsets/UTF_8))]
    (format "%064x" (java.math.BigInteger. 1 digest))))

(def identity-preds ["goal" "kind" "model" "provider" "role"])
(def guard-preds
  ["outcome" "process_outcome" "terminal_manifest_sha256"])

(defn marker [facts]
  (let [by-pred (into {} (map (juxt :p :r)) facts)]
    (sha256
     (apply str
            (for [predicate (sort identity-preds)
                  :let [value (get by-pred predicate)]
                  :when value]
              (str predicate "\u0000" value "\n"))))))

(defn facts [generation]
  [{:p "kind" :r "lane"}
   {:p "role" :r (str "integrator-" generation)}
   {:p "model" :r (str "model-" generation)}
   {:p "provider" :r "openai"}
   {:p "goal" :r (str "publish-" generation)}
   {:p "display_name" :r (str "lane " generation)}])

(defn publish-request [subject holder generation]
  (let [body (facts generation)]
    {:op :managed-agent-publish
     :te subject
     :holder holder
     :ttl-ms 60000
     :facts body
     :identity-preds identity-preds
     :guard-preds guard-preds
     :manifest-sha256 (marker body)}))

(defn values-of [port subject predicate]
  (set (:values (client port {:op :resolved :te subject :p predicate}))))

(defn subject-facts [port subject]
  (->> (:facts (client port {:op :facts}))
       (filter #(= subject (nth % 0)))
       (map (fn [[_ p r]] [p r]))
       (into {})))

(defn valid-committed? [snapshot]
  (let [manifest (get snapshot "identity_manifest_sha256")
        body (mapv (fn [predicate] {:p predicate :r (get snapshot predicate)})
                   (filter #(contains? snapshot %) identity-preds))]
    (or (nil? manifest)
        (= manifest (marker body)))))

(defn resource [subject]
  (str "managed-agent-write:" (sha256 subject)))

(defn acquire-until! [port res holder]
  (loop []
    (let [r (client port {:op :acquire-lease :res res :holder holder :ttl-ms 60000})]
      (if (:ok r) r (do (Thread/sleep 5) (recur))))))

(defn legacy-publish!
  "The current sequential shape: explicit lease, marker withdrawn first, one
  fenced fact op at a time, marker last, exact epoch release."
  [port subject generation first-fact-published]
  (let [res (resource subject)
        holder (str "legacy-" generation)
        lease (acquire-until! port res holder)
        fence {:res res :holder holder :epoch (:epoch lease)}
        body (facts generation)]
    (try
      (doseq [old (values-of port subject "identity_manifest_sha256")]
        (client port (merge {:op :retract-with-fence :te subject
                             :p "identity_manifest_sha256" :r old}
                            fence)))
      (doseq [[i {:keys [p r]}] (map-indexed vector body)]
        (client port (merge {:op :assert-with-fence :te subject :p p :r r}
                            fence))
        (when (zero? i) (deliver first-fact-published true))
        (Thread/sleep 3))
      (client port
              (merge {:op :assert-with-fence :te subject
                      :p "identity_manifest_sha256" :r (marker body)}
                     fence))
      (finally
        (client port {:op :release-lease :res res :holder holder
                      :epoch (:epoch lease)})))))

(let [port (free-high-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-managed-agent-publish"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log "")
      singles
      (str/join " "
                (concat identity-preds
                        ["display_name" "identity_manifest_sha256"
                         "outcome" "process_outcome"
                         "terminal_manifest_sha256"]))
      daemon
      (proc/process
       {:dir root :out :string :err :string
        :extra-env {"FRAM_SINGLE_VALUED" singles}}
       "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
       (str port) (.getPath log))
      checks (atom [])
      check! (fn [label value]
               (swap! checks conj [label (boolean value)]))]
  (try
    (let [up? (eventually #(integer? (:version (client port {:op :version}))))]
      (check! "scratch daemon starts on a high non-production port"
              (and up? (>= port 7978) (not= port 7977)))
      (when-not up?
        (proc/destroy-tree daemon)
        (println "DAEMON STDERR>>>\n" (:err @daemon))
        (System/exit 1)))

    ;; Happy path: normalization, exact resource, body+marker, one identity tx.
    (let [subject "@agent:publish-happy"
          body (facts "happy")
          res (client port (publish-request "agent:publish-happy"
                                            "managed-agent-writer:happy"
                                            "happy"))
          snapshot (subject-facts port subject)
          subject-lines (filter #(= subject (:l %))
                                (fram.rt/read-log (.getPath log)))]
      (check! "one wire op normalizes the subject and derives the exact lease resource"
              (and (:ok res)
                   (= subject (:te res))
                   (= (resource subject) (:resource res))))
      (check! "body and byte-identical marker are live before success"
              (and (= (marker body) (:marker res))
                   (= (marker body) (get snapshot "identity_manifest_sha256"))
                   (every? (fn [{:keys [p r]}] (= r (get snapshot p))) body)))
      (check! "identity body plus marker share one flat-log transaction"
              (and (= (inc (count body)) (count subject-lines))
                   (= 1 (count (set (map :tx subject-lines))))))
      (check! "server releases its internally acquired lease"
              (let [next (client port {:op :acquire-lease
                                       :res (resource subject)
                                       :holder "successor"
                                       :ttl-ms 60000})]
                (when (:ok next)
                  (client port {:op :release-lease :res (resource subject)
                                :holder "successor" :epoch (:epoch next)}))
                (:ok next))))

    ;; Manifest mismatch and mid-batch validation failure mutate no identity fact.
    (let [subject "@agent:bad-manifest"
          bad (assoc (publish-request subject "managed-agent-writer:bad" "bad")
                     :manifest-sha256 (apply str (repeat 64 "0")))
          res (client port bad)]
      (check! "manifest mismatch fails before mutation"
              (and (= :manifest-mismatch (:code res))
                   (empty? (subject-facts port subject)))))

    (let [subject "@agent:reserved"
          req (update (publish-request subject
                                       "managed-agent-writer:reserved"
                                       "reserved")
                      :facts conj {:p "name" :r "@evil"})
          req (assoc req :manifest-sha256 (marker (:facts req)))
          res (client port req)]
      (check! "mid-batch reserved predicate rejection leaves no partial publish"
              (and (= :reserved-in-batch (:code res))
                   (empty? (subject-facts port subject)))))

    ;; Lost ack commits the whole tx; exact retry observes it without a second tx.
    (let [subject "@agent:lost-ack"
          req (publish-request subject "managed-agent-writer:lost-ack" "lost")]
      (send-and-drop! port req)
      (check! "disconnect before acknowledgement leaves a complete committed projection"
              (eventually
               #(let [snapshot (subject-facts port subject)]
                  (and (= (:manifest-sha256 req)
                          (get snapshot "identity_manifest_sha256"))
                       (valid-committed? snapshot)))))
      (let [before (count (filter #(= subject (:l %))
                                  (fram.rt/read-log (.getPath log))))
            replay (client port req)
            after (count (filter #(= subject (:l %))
                                 (fram.rt/read-log (.getPath log))))]
        (check! "byte-identical retry is idempotent and does not double-publish"
                (and (:ok replay) (:idempotent replay) (= before after)))))

    ;; A conflicting existing generation is left byte-for-byte unchanged.
    (let [subject "@agent:conflict"
          first (publish-request subject "managed-agent-writer:first" "first")
          second (publish-request subject "managed-agent-writer:second" "second")
          _ (client port first)
          before (subject-facts port subject)
          rejected (client port second)
          after (subject-facts port subject)]
      (check! "competing generation rejects and preserves the committed predecessor"
              (and (= :publish-conflict (:code rejected))
                   (= before after)
                   (valid-committed? after))))

    ;; Concurrent legacy sequential writer on the SAME subject. It acquires the
    ;; canonical lease and publishes one fact before the atomic request arrives.
    ;; The atomic request must observe :held, never interleave under that lease;
    ;; after legacy commits, retry sees a whole competing generation and rejects.
    (let [subject "@agent:legacy-first"
          started (promise)
          legacy (future (legacy-publish! port subject "legacy-first" started))
          _ @started
          while-held
          (client port (publish-request subject
                                        "managed-agent-writer:atomic-waiter"
                                        "atomic-waiter"))
          mid (subject-facts port subject)
          _ @legacy
          final (subject-facts port subject)
          after (client port (publish-request
                              subject
                              "managed-agent-writer:atomic-after"
                              "atomic-after"))]
      (check! "legacy-held canonical lease rejects the atomic writer without interleaving"
              (and (= :held (:reject while-held))
                   (nil? (get mid "identity_manifest_sha256"))))
      (check! "legacy marker-last publication becomes one valid committed generation"
              (valid-committed? final))
      (check! "atomic retry after legacy ordering rejects the whole predecessor, not a torn prefix"
              (and (= :publish-conflict (:code after))
                   (= final (subject-facts port subject)))))

    ;; Reverse ordering: atomic commits first, then legacy gets the released same
    ;; resource and replaces under single-valued semantics. Sample whole :facts
    ;; snapshots while legacy runs: a visible marker must always match a complete
    ;; generation; marker absence is the fail-closed in-progress state.
    (let [subject "@agent:atomic-first"
          atomic (client port (publish-request
                               subject
                               "managed-agent-writer:atomic-first"
                               "atomic-first"))
          started (promise)
          legacy (future (legacy-publish! port subject "legacy-second" started))
          _ @started
          samples
          (loop [rows []]
            (if (realized? legacy)
              rows
              (do (Thread/sleep 1)
                  (recur (conj rows (subject-facts port subject))))))
          _ @legacy
          final (subject-facts port subject)]
      (check! "atomic writer commits first and releases the shared ordering lease"
              (:ok atomic))
      (check! "every observable committed interleave snapshot is whole; markerless states fail closed"
              (every? valid-committed? samples))
      (check! "legacy successor ordering is final and marker-valid"
              (and (= "integrator-legacy-second" (get final "role"))
                   (valid-committed? final))))

    ;; Existing wire operations remain additive and unchanged.
    (let [single (client port {:op :assert :te "@legacy:single"
                               :p "note" :r "unchanged"})
          batch (client port {:op :assert-batch :te "@legacy:batch"
                              :facts [{:p "a" :r "1"} {:p "b" :r "2"}]})]
      (check! "legacy single :assert still works"
              (and (:ok single)
                   (= #{"unchanged"}
                      (values-of port "@legacy:single" "note"))))
      (check! "legacy :assert-batch still works"
              (and (:ok batch) (:batch batch)
                   (= #{"1"} (values-of port "@legacy:batch" "a"))
                   (= #{"2"} (values-of port "@legacy:batch" "b")))))

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
