;; Authoritative coordinator gate: occurrence identity, OCC, durable FRAMLOG,
;; recursive terms, views, supersession, withdrawal, and lease fencing.
(require '[fram.kernel :as kernel]
         '[fram.store :as store]
         '[fram.types :as t])

(load-file "coord.clj")

(def checks (atom []))
(defn check! [label ok]
  (println (str (if ok "  [PASS] " "  [FAIL] ") label))
  (swap! checks conj [label ok]))

(defn error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (or (:fram/code (ex-data error)) (:type (ex-data error))))))

(def scratch
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-term-coordinator-"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-file (java.io.File. scratch "history.framlog"))
(coord/create-triple-log! (.getPath log-file) "coord-space")
(def co (coord/open-coordinator! (.getPath log-file) "coord-space"))

(def email (t/triple "Alice" :email "alice@example.com"))
(def nested
  (t/triple (t/triple "Alice" :knows "Bob")
            :reported-by
            (t/triple "CRM" :batch 71)))
(def recorded (t/instant 1785560000 123456789))

(def first-assertion
  (coord/assert! co email {:actor "Tom" :recorded-at recorded
                           :source-frame "coord-test"}))
(def first-event (first (:occurrences first-assertion)))
(def first-coordinate (kernel/occurrence-of first-event))
(def first-tx (:ok first-assertion))

(check! "first commit returns a transaction-coordinate Triple"
        (= (t/transaction-coordinate "coord-space" 1) first-tx))
(check! "first assertion returns its exact occurrence coordinate"
        (= (t/occurrence-coordinate first-tx 0) first-coordinate))
(check! "transaction time is an ordinary typed metadata Triple"
        (some #{(t/triple first-tx :kernel/recorded-at recorded)}
              (coord/live-propositions co)))
(check! "actor and source frame are ordinary metadata propositions"
        (and (some #{(t/triple first-tx :kernel/asserted-by "Tom")}
                   (coord/live-propositions co))
             (some #{(t/triple first-coordinate :kernel/source-frame "coord-test")}
                   (coord/live-propositions co))))

(def duplicate (coord/assert! co email {}))
(def duplicate-coordinate
  (kernel/occurrence-of (first (:occurrences duplicate))))
(check! "equal propositions remain separately occurrence-addressable"
        (and (not= first-coordinate duplicate-coordinate)
             (= 2 (count (filter #{email} (coord/live-propositions co))))))

(def withdrawn
  (coord/withdraw-occurrence! co duplicate-coordinate {:actor "Tom"}))
(check! "exact withdrawal removes only the named equal occurrence"
        (and (:ok withdrawn)
             (= [first-coordinate]
                (mapv kernel/occurrence-of
                      (filter #(= email (kernel/proposition-of %))
                              (coord/live-occurrences co))))))
(check! "withdrawal history points to the exact occurrence coordinate"
        (some #(= duplicate-coordinate (t/triple-slot2 %))
              (:withdrawals withdrawn)))

(def draft (t/triple "Task" :status "draft"))
(def final (t/triple "Task" :status "final"))
(def draft-result (coord/assert! co draft {}))
(def draft-coordinate
  (kernel/occurrence-of (first (:occurrences draft-result))))
(def superseded (coord/supersede! co draft-coordinate final {:actor "reviewer"}))
(def final-coordinate
  (kernel/occurrence-of (first (:occurrences superseded))))
(check! "supersession is an ordinary occurrence-to-occurrence Triple"
        (some #{(t/triple final-coordinate :kernel/supersedes draft-coordinate)}
              (coord/supersession-triples co)))
(check! "effective liveness follows supersession without mutating identity"
        (and (not (some #{draft} (coord/live-propositions co)))
             (some #{final} (coord/live-propositions co))
             (some #{draft} (store/live-propositions
                             (coord/coordinator-store co)))))

;; Global transaction-coordinate OCC is conservative until schema-specific
;; conflict domains migrate; every accepted write still advances one exact tx.
(def race-base (coord/current-transaction co))
(def race-results
  (mapv deref
        (mapv (fn [n]
                (future
                  (coord/assert! co (t/triple "race" :winner n)
                                 {:base race-base :actor (str "writer-" n)})))
              (range 24))))
(check! "one same-base racer wins"
        (= 1 (count (filter :ok race-results))))
(check! "all other same-base racers receive an OCC conflict"
        (= 23 (count (filter #(= :conflict (:reject %)) race-results))))
(let [before (store/operation-count (coord/coordinator-store co))
      stale (coord/assert! co nested {:base race-base})]
  (check! "stale OCC rejection leaks no TermStore operation"
          (and (= :conflict (:reject stale))
               (= before (store/operation-count
                          (coord/coordinator-store co))))))

(def view-result (coord/view-select! co "review-view" first-coordinate {}))
(check! "view selection names an occurrence using an ordinary Triple"
        (and (:ok view-result)
             (= [first-coordinate]
                (mapv kernel/occurrence-of
                      (coord/view-occurrences co "review-view")))))
(coord/view-deselect! co "review-view" first-coordinate {})
(check! "withdrawing the selection empties the view without touching its target"
        (and (empty? (coord/view-occurrences co "review-view"))
             (some #{first-coordinate}
                   (map kernel/occurrence-of (coord/live-occurrences co)))))

(def lease-1 (coord/acquire-lease! co "resource-A" "worker-A" 1000 10000))
(def lease-epoch-1 (:ok lease-1))
(check! "lease epoch is its assertion occurrence coordinate"
        (and (t/occurrence-coordinate? lease-epoch-1)
             (coord/lease-fence-valid? co "resource-A" "worker-A"
                                       lease-epoch-1 10500)))
(check! "unexpired lease rejects a rival holder"
        (= :lease-held
           (:reject (coord/acquire-lease! co "resource-A" "worker-B"
                                         1000 10500))))
(def lease-2
  (coord/renew-lease! co "resource-A" "worker-A" lease-epoch-1 2000 10500))
(def lease-epoch-2 (:ok lease-2))
(check! "renewal rotates the occurrence fence and supersedes the old lease"
        (and (not= lease-epoch-1 lease-epoch-2)
             (not (coord/lease-fence-valid? co "resource-A" "worker-A"
                                            lease-epoch-1 10600))
             (coord/lease-fence-valid? co "resource-A" "worker-A"
                                       lease-epoch-2 10600)))
(check! "stale release cannot cross the occurrence fence"
        (= :lease-fence-mismatch
           (:reject (coord/release-lease! co "resource-A" "worker-A"
                                         lease-epoch-1))))
(check! "epoch-exact release withdraws the current lease"
        (and (:ok (coord/release-lease! co "resource-A" "worker-A"
                                       lease-epoch-2))
             (nil? (coord/current-lease co "resource-A"))))

(def numeric-result
  (coord/assert! co (t/triple "measurement" :value (float 1.5)) {}))
(check! "commit canonicalizes Float atoms before memory and FRAMLOG diverge"
        (instance? Double
                   (t/triple-slot2
                    (kernel/proposition-of
                     (first (:occurrences numeric-result))))))

(def before-restart (store/dump-term-store (coord/coordinator-store co)))
(def restarted (coord/open-coordinator! (.getPath log-file) "coord-space"))
(check! "cold FRAMLOG replay reconstructs the exact TermStore v2 dump"
        (= before-restart
           (store/dump-term-store (coord/coordinator-store restarted))))
(check! "cold replay preserves semantic history and effective projections"
        (and (= (coord/history co) (coord/history restarted))
             (= (coord/live-occurrences co)
                (coord/live-occurrences restarted))))

;; A torn trailing frame is dropped as a whole. Passive readers report it and
;; cannot append; an authority-holding boot truncates exactly to valid-bytes.
(with-open [out (java.io.FileOutputStream. log-file true)]
  (.write out (byte-array [(byte 40) (byte 0) (byte 0) (byte 0)
                           (byte 1) (byte 2) (byte 3)]))
  (.force (.getChannel out) true))
(def passive (coord/open-coordinator! (.getPath log-file) "coord-space"))
(check! "passive boot drops and reports a torn trailing transaction frame"
        (and (:torn-tail passive)
             (= before-restart
                (store/dump-term-store (coord/coordinator-store passive)))))
(check! "passive torn generation refuses concatenating a later transaction"
        (= :torn-tail-repair-required
           (error-code #(coord/assert! passive nested {}))))
(def repaired
  (coord/open-coordinator! (.getPath log-file) "coord-space"
                           {:repair-torn? true}))
(check! "authority repair reports and truncates only the torn frame"
        (and (:recovered-tail repaired)
             (nil? (:torn-tail (coord/read-triple-log! (.getPath log-file))))))
(coord/assert! repaired nested {})
(def after-repair (coord/open-coordinator! (.getPath log-file) "coord-space"))
(check! "a repaired generation accepts and cold-replays the next whole frame"
        (some #{nested} (coord/live-propositions after-repair)))

(def legacy-file (java.io.File. scratch "legacy.log"))
(spit legacy-file "{:tx 1, :op \"assert\", :l \"A\", :p \"p\", :r \"B\"}\n")
(check! "runtime boot rejects legacy flat bytes with migration-required"
        (= :migration-required
           (error-code #(coord/open-coordinator! (.getPath legacy-file)))))
(def fri-file (java.io.File. scratch "legacy.fri"))
(spit fri-file "FRAMFRI1cache")
(check! "runtime boot rejects lossy FRI input as non-authoritative"
        (= :migration-v2-cache-not-source
           (error-code #(coord/open-coordinator! (.getPath fri-file)))))
(def v2-file (java.io.File. scratch "legacy.v2log"))
(spit v2-file "{:k :fact, :cid 1, :l 2, :p 3, :r 4, :tx 5}\n")
(check! "runtime boot rejects old v2 row caches as non-authoritative"
        (= :migration-v2-cache-not-source
           (error-code #(coord/open-coordinator! (.getPath v2-file)))))

(check! "public write responses expose no cid handle"
        (not-any? #(and (map? %) (contains? % :cid))
                  (tree-seq coll? seq first-assertion)))

(let [failures (remove second @checks)]
  (if (empty? failures)
    (do
      (println "\nTermStore coordinator:" (count @checks) "/" (count @checks) "PASS")
      (shutdown-agents))
    (do
      (println "\nTermStore coordinator:" (count failures) "FAILED")
      (shutdown-agents)
      (System/exit 1))))
