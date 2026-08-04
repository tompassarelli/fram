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

;; A thrown append cannot reveal whether the frame reached stable storage. The
;; coordinator rebuilds its readable state from disk but stays mutation-fenced.
(def append-frame-var
  (ns-resolve 'coord 'append-frame-durable!))
(def append-frame-original @append-frame-var)

(def pre-append-file (java.io.File. scratch "pre-append-failure.framlog"))
(coord/create-triple-log! (.getPath pre-append-file) "pre-append-space")
(def pre-append-co
  (coord/open-coordinator! (.getPath pre-append-file) "pre-append-space"))
(def pre-append-error
  (with-redefs-fn
    {append-frame-var
     (fn [_ _ _]
       (throw (ex-info "injected before append" {:type :injected-pre-append})))}
    #(error-code
      (fn [] (coord/assert! pre-append-co (t/triple "pre" :state "attempted") {})))))
(check! "pre-append exception is reported as durability-ambiguous"
        (= :durability-ambiguous pre-append-error))
(check! "pre-append reconciliation preserves the exact durable version"
        (and (= :recovery-required
                (:status (coord/coordinator-recovery-state pre-append-co)))
             (= (t/transaction-coordinate "pre-append-space" 0)
                (coord/current-transaction pre-append-co))
             (empty? (:frames
                      (coord/read-triple-log! (.getPath pre-append-file))))))
(check! "pre-append coordinator rejects retry until restart"
        (= :recovery-required
           (error-code
            #(coord/assert! pre-append-co (t/triple "pre" :state "retry") {}))))

(def append-cohort-var
  (ns-resolve 'coord 'append-frame-cohort-durable!))
(def append-cohort-original @append-cohort-var)
(def cohort-file (java.io.File. scratch "cohort.framlog"))
(coord/create-triple-log! (.getPath cohort-file) "cohort-space")
(def cohort-co (coord/open-coordinator! (.getPath cohort-file) "cohort-space"))
(def cohort-barriers (atom 0))
(def cohort-result
  (with-redefs-fn
    {append-cohort-var
     (fn [path frames deflate?]
       (swap! cohort-barriers inc)
       (append-cohort-original path frames deflate?))}
    #(coord/commit-cohort!
      cohort-co
      [(fn [co] (coord/assert! co (t/triple "group" :item 1) {}))
       (fn [co] (coord/assert! co (t/triple "group" :item 2) {}))])))
(check! "a cohort keeps two logical transaction frames under one barrier"
        (and (= 1 @cohort-barriers)
             (= 2 (:frame-count cohort-result))
             (= [1 2]
                (mapv :tx-seq
                      (:frames (coord/read-triple-log! (.getPath cohort-file)))))))
(check! "a successful cohort publishes its final private root atomically"
        (and (= (t/transaction-coordinate "cohort-space" 2)
                (coord/current-transaction cohort-co))
             (= #{(t/triple "group" :item 1) (t/triple "group" :item 2)}
                (set (coord/live-propositions cohort-co)))))

(def failed-cohort-file (java.io.File. scratch "failed-cohort.framlog"))
(coord/create-triple-log! (.getPath failed-cohort-file) "failed-cohort-space")
(def failed-cohort-co
  (coord/open-coordinator! (.getPath failed-cohort-file) "failed-cohort-space"))
(def failed-cohort-error
  (with-redefs-fn
    {append-cohort-var
     (fn [_ _ _]
       (throw (ex-info "injected cohort barrier failure"
                       {:type :injected-cohort-barrier})))}
    #(error-code
      (fn []
        (coord/commit-cohort!
         failed-cohort-co
         [(fn [co] (coord/assert! co (t/triple "group" :failed 1) {}))
          (fn [co] (coord/assert! co (t/triple "group" :failed 2) {}))])))))
(check! "a cohort barrier failure publishes nothing and fences all retries"
        (and (= :durability-ambiguous failed-cohort-error)
             (= :recovery-required
                (:status (coord/coordinator-recovery-state failed-cohort-co)))
             (= (t/transaction-coordinate "failed-cohort-space" 0)
                (coord/current-transaction failed-cohort-co))
             (empty? (:frames
                      (coord/read-triple-log! (.getPath failed-cohort-file))))))

(def post-force-file (java.io.File. scratch "post-force-failure.framlog"))
(coord/create-triple-log! (.getPath post-force-file) "post-force-space")
(def post-force-co
  (coord/open-coordinator! (.getPath post-force-file) "post-force-space"))
(def post-force-error
  (with-redefs-fn
    {append-frame-var
     (fn [path frame deflate?]
       (append-frame-original path frame deflate?)
       (throw (ex-info "injected after force" {:type :injected-post-force})))}
    #(error-code
      (fn []
        (coord/assert! post-force-co (t/triple "post" :state "durable") {})))))
(check! "post-force exception is reported as durability-ambiguous"
        (= :durability-ambiguous post-force-error))
(check! "post-force reconciliation advances readable memory to durable tx1"
        (and (= :recovery-required
                (:status (coord/coordinator-recovery-state post-force-co)))
             (= (t/transaction-coordinate "post-force-space" 1)
                (coord/current-transaction post-force-co))
             (= [1] (mapv :tx-seq
                           (:frames
                            (coord/read-triple-log!
                             (.getPath post-force-file)))))))
(check! "post-force coordinator rejects a stale-sequence retry"
        (= :recovery-required
           (error-code
            #(coord/assert! post-force-co (t/triple "post" :state "retry") {}))))
(def post-force-restarted
  (coord/open-coordinator! (.getPath post-force-file) "post-force-space"))
(def post-force-next
  (coord/assert! post-force-restarted (t/triple "post" :state "next") {}))
(check! "restart resumes at tx2 without duplicate tx1"
        (and (= (t/transaction-coordinate "post-force-space" 2)
                (:ok post-force-next))
             (= [1 2]
                (mapv :tx-seq
                      (:frames
                       (coord/read-triple-log! (.getPath post-force-file)))))
             (= (t/transaction-coordinate "post-force-space" 2)
                (coord/current-transaction
                 (coord/open-coordinator! (.getPath post-force-file)
                                          "post-force-space")))))

(def corrupt-file (java.io.File. scratch "reconcile-corrupt.framlog"))
(coord/create-triple-log! (.getPath corrupt-file) "corrupt-space")
(def corrupt-co (coord/open-coordinator! (.getPath corrupt-file) "corrupt-space"))
(def corrupt-error
  (with-redefs-fn
    {append-frame-var
     (fn [path frame deflate?]
       (append-frame-original path frame deflate?)
       (with-open [file (java.io.RandomAccessFile. (str path) "rw")]
         (.seek file (dec (.length file)))
         (let [last-byte (.read file)]
           (.seek file (dec (.length file)))
           (.write file (bit-xor last-byte 1))
           (.force (.getChannel file) true)))
       (throw (ex-info "injected corrupt durable frame"
                       {:type :injected-corruption})))}
    #(error-code
      (fn [] (coord/assert! corrupt-co (t/triple "bad" :frame true) {})))))
(check! "failed durable replay permanently fences the coordinator as corrupt"
        (and (= :coordinator-corrupt corrupt-error)
             (= :corrupt (:status (coord/coordinator-recovery-state corrupt-co)))
             (= :coordinator-corrupt
                (error-code
                 #(coord/assert! corrupt-co (t/triple "bad" :retry true) {})))
             (= :coordinator-corrupt
                (error-code #(coord/current-transaction corrupt-co)))))

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
