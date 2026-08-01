(ns coord-daemon-wire
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fram.types :as t])
  (:import [java.io ByteArrayOutputStream]
           [java.io OutputStream]
           [java.nio ByteBuffer]
           [java.nio ByteOrder]
           [java.nio CharBuffer]
           [java.nio.charset CharacterCodingException]
           [java.nio.charset CodingErrorAction]
           [java.nio.charset StandardCharsets]))

^{:line 24 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn effective-request-op [req]
  ^{:line 25 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 25 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :for-log ^{:line 25 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)) ^{:line 26 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (get-in req ^{:line 26 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:request :op]) ^{:line 27 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)))

^{:line 29 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^Boolean query-request? [req]
  ^{:line 30 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 30 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (map? req) ^{:line 31 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 31 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? ^{:line 31 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} #{:query :query-page :pull} ^{:line 31 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)) ^{:line 32 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 32 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :as-of ^{:line 32 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)) ^{:line 32 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:query req)))))

^{:line 34 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defrecord QueryLimitPlan [timeout-ms deadline-ns max-steps max-rows max-response-bytes])

(defn querylimitplan-timeout-ms [r] (:timeout-ms r))

(defn querylimitplan-deadline-ns [r] (:deadline-ns r))

(defn querylimitplan-max-steps [r] (:max-steps r))

(defn querylimitplan-max-rows [r] (:max-rows r))

(defn querylimitplan-max-response-bytes [r] (:max-response-bytes r))

^{:line 41 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn lower-limit [req key ceiling]
  ^{:line 42 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [n ^{:line 42 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (get req key)]
  ^{:line 43 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 43 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 43 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (integer? n) ^{:line 43 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (pos? n)) ^{:line 44 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (min n ceiling) ceiling)))

^{:line 50 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^QueryLimitPlan query-limit-plan [req ceilings now-ns]
  ^{:line 52 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [timeout ^{:line 52 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (lower-limit req :query-timeout-ms ^{:line 52 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:timeout-ms ceilings))]
  ^{:line 53 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->QueryLimitPlan timeout ^{:line 55 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ now-ns ^{:line 55 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (* 1000000 timeout)) ^{:line 56 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (lower-limit req :query-max-steps ^{:line 56 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:max-steps ceilings)) ^{:line 57 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (lower-limit req :query-max-rows ^{:line 57 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:max-rows ceilings)) ^{:line 58 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (lower-limit req :query-max-response-bytes ^{:line 59 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:max-response-bytes ceilings)))))

^{:line 64 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def dispatch-table ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:query ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :query :handler :query :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:query] :response :query} :query-page ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :query :handler :query-page :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:query :limit] :response :query} :pull ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :query :handler :pull :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:root :pattern] :response :query} :as-of ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :as-of :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:seq] :response :read} :for-log ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :for-log :handler :for-log :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :fenced} :version-free ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :version-free :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :version} :seen ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :seen :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:v] :response :read} :reload-status ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :reload-status :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :status} :cutover-status ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :cutover-status :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:token] :response :status} :cutover-prepare ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :cutover-prepare :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:token :cutover-id] :response :status} :cutover-demote ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :cutover-demote :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:token :cutover-id :expected-instance] :response :status} :cutover-promote ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :cutover-promote :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:token :cutover-id :marker] :response :status} :write-def ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :write-def :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:spec] :response :structured} :read-def ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :read-def :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:spec] :response :structured} :index ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :index :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:spec] :response :structured} :check ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :check :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :structured} :edit-min ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :edit-min :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:spec] :response :edit} :edit-prepare ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :edit-prepare :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:spec] :response :edit} :edit-commit ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :direct :handler :edit-commit :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:candidate] :response :edit} :version ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :version :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :version} :assert ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :assert :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r] :response :mutation} :assert-existing ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :assert-existing :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r] :response :mutation} :assert-batch ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :assert-batch :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :facts] :response :mutation} :assert-batch-at-version ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :assert-batch-at-version :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :facts :base] :response :mutation} :claim-cite ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :claim-cite :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :mutation} :claim-decision ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :claim-decision :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :mutation} :claim-unverify ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :claim-unverify :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :mutation} :managed-agent-publish ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :managed-agent-publish :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :mutation} :assert-with-fence ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :assert-with-fence :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r :res :holder :epoch] :response :mutation} :assert-at-version ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :assert-at-version :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r] :response :mutation} :assert-at-version-with-fence ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :assert-at-version-with-fence :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r :base :res :holder :epoch] :response :mutation} :retract ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :retract :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r] :response :mutation} :retract-existing ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :retract-existing :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r] :response :mutation} :retract-with-fence ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :retract-with-fence :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :r :res :holder :epoch] :response :mutation} :bump ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :bump :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p :n] :response :mutation} :acquire-lease ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :acquire-lease :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:res :holder :ttl-ms] :response :lease} :renew-lease ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :renew-lease :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:res :holder :epoch :ttl-ms] :response :lease} :release-lease ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :release-lease :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:res :holder] :response :lease} :fence-ok ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :fence-ok :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:res :holder :epoch] :response :read} :edit-protocol ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :edit-protocol :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :status} :validate ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :validate :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :read} :show ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :show :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te] :response :read} :warm-check ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :warm-check :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :status} :status ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :status :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :status} :claim-read ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :claim-read :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :read} :claims-read ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :claims-read :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :read} :claims-needing-reverification ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :claims-needing-reverification :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :validation :handler :response :read} :facts ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :facts :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :read} :facts-for-subjects ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :facts-for-subjects :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:subjects] :response :read} :snapshot ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :snapshot :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :snapshot} :snapshot-reconcile ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :snapshot-reconcile :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :snapshot} :built-through ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :built-through :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :status} :module-path ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :module-path :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:module] :response :read} :render ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :render :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:module] :response :read} :callers ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :callers :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :read} :blast ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :blast :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :read} :concern-overlap ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :concern-overlap :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te] :response :read} :refers-ensure ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :refers-ensure :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :read} :refers-keyset ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :refers-keyset :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :read} :resolved ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :resolved :required ^{:line 65 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [:te :p] :response :read}})

^{:line 134 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn request-dispatch [req state config]
  ^{:line 136 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [op ^{:line 136 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)
   base ^{:line 137 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (get dispatch-table op ^{:line 138 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:route :locked :handler :unknown :required ^{:line 138 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [] :response :error})
   route ^{:line 139 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 140 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:durability-stop? state) :durability-stop
  ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= op :for-log) ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (query-request? ^{:line 141 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request req))) :fenced-query
  ^{:line 142 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (query-request? req) :query
  :else ^{:line 143 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:route base))
   reload-policy ^{:line 144 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 145 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:reload-checked? config) :already-checked
  ^{:line 146 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? ^{:line 146 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:reload-deferred-ops config) op) :deferred
  ^{:line 147 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? ^{:line 147 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:reload-mutation-ops config) op) :mutation
  :else :fresh)]
  ^{:line 149 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc base :op op :route route :reload-policy reload-policy)))

^{:line 151 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^Boolean target-request? [req]
  ^{:line 152 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 152 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? req :te) ^{:line 153 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 153 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? req :module) ^{:line 153 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? req :name))))

^{:line 155 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn request-validation-errors [req decision]
  ^{:line 156 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 157 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 157 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (map? req)) ^{:line 158 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} ["request must be a map"]
  ^{:line 160 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 160 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? req :op)) ^{:line 161 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [":op is required"]
  ^{:line 163 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :unknown ^{:line 163 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:handler decision)) ^{:line 164 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} []
  ^{:line 166 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :handler ^{:line 166 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:validation decision)) ^{:line 167 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} []
  :else ^{:line 170 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [missing ^{:line 170 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (vec ^{:line 170 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (filter ^{:line 170 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (fn [k] ^{:line 170 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 170 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? req k))) ^{:line 171 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:required decision)))
   base ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (mapv ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (fn [k] ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str ^{:line 172 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (name k) " is required")) missing)
   op ^{:line 173 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)]
  ^{:line 174 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 175 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 175 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? ^{:line 175 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} #{:callers :blast} op) ^{:line 175 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 175 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (target-request? req))) ^{:line 176 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (conj base "te or module+name is required")
  ^{:line 178 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 178 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= op :as-of) ^{:line 179 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 179 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:query req)) ^{:line 180 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 180 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 180 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? req :te) ^{:line 180 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (contains? req :p)))) ^{:line 181 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (conj base "query or te+p is required")
  :else base))))

^{:line 185 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn invalid-request-response [errors]
  ^{:line 186 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 186 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (seq errors) ^{:line 186 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 187 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:error ^{:line 187 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (vec errors) :code :invalid-request})))

^{:line 189 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^Boolean json-format? [fmt]
  ^{:line 190 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 190 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= fmt :json) ^{:line 190 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= fmt "json")))

^{:line 192 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^Boolean edn-too-deep? [^String s max-depth]
  ^{:line 193 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (loop [i 0
   depth 0
   mx 0
   in-str false
   esc false]
  ^{:line 194 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 194 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= i ^{:line 194 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (count s)) ^{:line 195 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> mx max-depth) ^{:line 196 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [c ^{:line 196 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 196 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.charAt s i))]
  ^{:line 197 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  esc ^{:line 199 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 199 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc i) depth mx in-str false)
  ^{:line 201 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and in-str ^{:line 201 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 92)) ^{:line 202 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 202 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc i) depth mx in-str true)
  in-str ^{:line 205 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 205 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc i) depth mx ^{:line 205 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 205 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 34)) false)
  ^{:line 207 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 34) ^{:line 208 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 208 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc i) depth mx true false)
  ^{:line 210 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 210 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 40) ^{:line 210 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 91) ^{:line 210 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 123)) ^{:line 211 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [d ^{:line 211 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc depth)]
  ^{:line 212 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 212 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc i) d ^{:line 212 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (max mx d) in-str false))
  ^{:line 214 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 214 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 41) ^{:line 214 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 93) ^{:line 214 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= c 125)) ^{:line 215 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 215 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc i) ^{:line 215 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (max 0 ^{:line 215 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (dec depth)) mx in-str false)
  :else ^{:line 218 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 218 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (inc i) depth mx in-str false))))))

^{:line 220 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn parse-request [^String line max-depth]
  ^{:line 221 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 222 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 222 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (edn-too-deep? line max-depth) ^{:line 222 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 223 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (throw ^{:line 223 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ex-info "edn too deep" ^{:line 223 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:type :edn-too-deep}))))
  ^{:line 224 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (edn/read-string line)))

^{:line 226 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn strict-log-fence-rejection [^Boolean required? req served-log]
  ^{:line 228 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 228 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and required? ^{:line 228 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not= :for-log ^{:line 228 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req))) ^{:line 228 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 229 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:reject ^{:line 229 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} ["this coordinator requires a :for-log envelope"] :code :log-fence-required :served-log served-log})))

^{:line 233 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^Boolean fenced-subscribe? [req]
  ^{:line 234 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [inner ^{:line 234 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request req)]
  ^{:line 235 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 235 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :for-log ^{:line 235 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)) ^{:line 236 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (map? inner) ^{:line 237 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :subscribe ^{:line 237 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op inner)))))

^{:line 239 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn subscription-request [req]
  ^{:line 240 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 240 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (fenced-subscribe? req) ^{:line 240 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request req) req))

^{:line 242 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn actual-request [req]
  ^{:line 243 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 243 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :for-log ^{:line 243 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)) ^{:line 243 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request req) req))

^{:line 245 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn subscription-response [version ^Boolean fenced? served-log]
  ^{:line 247 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if fenced? ^{:line 248 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:subscribed version :log served-log} ^{:line 249 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:subscribed version}))

^{:line 251 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defrecord ConnectionState [phase request actual format response fenced-subscription query])

(defn connectionstate-phase [r] (:phase r))

(defn connectionstate-request [r] (:request r))

(defn connectionstate-actual [r] (:actual r))

(defn connectionstate-format [r] (:format r))

(defn connectionstate-response [r] (:response r))

(defn connectionstate-fenced-subscription [r] (:fenced-subscription r))

(defn connectionstate-query [r] (:query r))

^{:line 260 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^ConnectionState connection-start []
  ^{:line 261 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :reading nil nil nil nil false false))

^{:line 267 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^ConnectionState connection-transition [^ConnectionState state event]
  ^{:line 269 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [kind ^{:line 269 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:event event)]
  ^{:line 270 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 271 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :request) ^{:line 272 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [req ^{:line 272 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request event)
   strict-reject ^{:line 273 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:strict-reject event)
   fence-reject ^{:line 274 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:fence-reject event)
   fenced? ^{:line 275 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (fenced-subscribe? req)
   subscription? ^{:line 276 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or fenced? ^{:line 276 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :subscribe ^{:line 276 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:op req)))
   actual ^{:line 277 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (actual-request req)
   fmt ^{:line 278 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:fmt req)]
  ^{:line 279 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  strict-reject ^{:line 281 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :reply req actual fmt strict-reject fenced? false)
  fence-reject ^{:line 284 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :reply req actual fmt fence-reject fenced? false)
  subscription? ^{:line 287 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :subscribe req ^{:line 287 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (subscription-request req) fmt nil fenced? false)
  :else ^{:line 291 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :handle req actual fmt nil false ^{:line 292 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (query-request? actual))))
  ^{:line 294 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :handled) ^{:line 295 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :reply ^{:line 295 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request state) ^{:line 295 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:actual state) ^{:line 296 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:format state) ^{:line 296 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:response event) ^{:line 297 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:fenced-subscription state) ^{:line 297 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:query state))
  ^{:line 299 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :replied) ^{:line 300 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :done ^{:line 300 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request state) ^{:line 300 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:actual state) ^{:line 301 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:format state) ^{:line 301 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:response state) ^{:line 302 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:fenced-subscription state) ^{:line 302 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:query state))
  ^{:line 304 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :eof) ^{:line 305 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (->ConnectionState :done ^{:line 305 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:request state) ^{:line 305 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:actual state) ^{:line 306 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:format state) ^{:line 306 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:response state) ^{:line 307 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:fenced-subscription state) ^{:line 307 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:query state))
  :else state)))

^{:line 311 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^String serialize-response [fmt resp to-json]
  ^{:line 312 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 312 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (json-format? fmt) ^{:line 313 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (to-json resp) ^{:line 314 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (pr-str resp)))

^{:line 316 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn structured-error [stage at message expected got suggestion nearest]
  ^{:line 324 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [base ^{:line 324 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:ok false :stage stage}
   with-at ^{:line 325 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if at ^{:line 325 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc base :at at) base)
   with-message ^{:line 326 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if message ^{:line 326 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc with-at :message message) with-at)
   with-expected ^{:line 327 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if expected ^{:line 327 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc with-message :expected expected) with-message)
   with-got ^{:line 328 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if got ^{:line 328 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc with-expected :got got) with-expected)
   with-suggestion ^{:line 329 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if suggestion ^{:line 329 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc with-got :suggestion suggestion) with-got)]
  ^{:line 330 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 330 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (seq nearest) ^{:line 331 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc with-suggestion :nearest ^{:line 331 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (vec nearest)) with-suggestion)))

^{:line 334 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn exception-gate-error [module nm t]
  ^{:line 335 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [d ^{:line 335 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ex-data t)
   class-name ^{:line 336 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getSimpleName ^{:line 336 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (class t))
   msg ^{:line 337 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 337 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not-empty ^{:line 337 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str ^{:line 337 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getMessage t))) ^{:line 338 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:message d) ^{:line 339 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str "internal error: " class-name))]
  ^{:line 340 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (structured-error :gate ^{:line 342 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:module module :def nm} msg nil class-name ^{:line 346 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 346 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:suggestion d) "simplify the form; ensure every referenced helper/type exists") nil)))

^{:line 350 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn reject-gate-error [module nm er]
  ^{:line 351 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [msg ^{:line 351 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 351 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (vector? ^{:line 351 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:reject er)) ^{:line 352 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str/join "; " ^{:line 352 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:reject er)) ^{:line 353 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str ^{:line 353 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:reject er)))
   base ^{:line 354 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (structured-error :canon ^{:line 356 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:module module :def nm} ^{:line 357 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str "verb rejected: " msg) nil nil "send exactly one named value def per form; narrow ambiguous edits" nil)]
  ^{:line 362 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 362 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:disambiguation er) ^{:line 363 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc base :disambiguation ^{:line 363 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:disambiguation er)) base)))

^{:line 366 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn edit-min-error-response [spec t version]
  ^{:line 367 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [d ^{:line 367 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ex-data t)
   msg ^{:line 368 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 368 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not-empty ^{:line 368 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str ^{:line 368 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getMessage t))) ^{:line 369 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:message d) ^{:line 370 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str "internal error: " ^{:line 370 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getSimpleName ^{:line 370 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (class t))))
   base ^{:line 371 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:reject ^{:line 371 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 371 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str "edit-min: " msg)] :error ^{:line 371 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (exception-gate-error ^{:line 371 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:module spec) ^{:line 371 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:name spec) t) :version version}]
  ^{:line 374 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 374 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:disambiguation d) ^{:line 375 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (assoc base :disambiguation ^{:line 375 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:disambiguation d)) base)))

^{:line 378 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn unknown-op-response []
  ^{:line 379 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:error "unknown op"})

^{:line 381 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn bad-request-response [error-data ^String class-name]
  ^{:line 383 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:error ^{:line 383 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str "bad request: " ^{:line 383 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 383 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (:type error-data) class-name))})

^{:line 388 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn connection-error-selection [scope error-kind error-data ^String class-name]
  ^{:line 393 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 394 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not= scope :request) ^{:line 395 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:action :close}
  ^{:line 397 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= error-kind :socket-timeout) ^{:line 398 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:action :close}
  :else ^{:line 401 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:action :reply :response ^{:line 401 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (bad-request-response error-data class-name)}))

^{:line 408 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def term-codec-v1-depth-limit 256)

^{:line 410 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-fail! [code ^String message]
  ^{:line 411 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (throw ^{:line 411 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ex-info message ^{:line 411 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:type code :fram/code code})))

^{:line 413 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- require-codec-limits! [max-string-bytes max-nodes max-depth]
  ^{:line 415 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 415 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 415 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> max-string-bytes 0) ^{:line 416 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 416 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> max-nodes 0) ^{:line 416 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 416 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> max-depth 0) ^{:line 416 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= max-depth 256)))) nil ^{:line 418 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-limit "TermCodecV1 limits must be positive and depth at most 256")))

^{:line 421 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- utf8-length! [^String value maximum ^String label]
  ^{:line 423 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (loop [index 0
   total 0]
  ^{:line 424 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 424 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= index ^{:line 424 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (count value)) total ^{:line 426 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [unit ^{:line 426 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 426 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.charAt value index))
   high? ^{:line 427 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 427 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= unit 55296) ^{:line 427 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= unit 56319))
   low? ^{:line 428 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 428 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= unit 56320) ^{:line 428 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= unit 57343))
   pair-unit ^{:line 430 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 430 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and high? ^{:line 430 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< ^{:line 430 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ index 1) ^{:line 430 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (count value))) ^{:line 431 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 431 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.charAt value ^{:line 431 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ index 1))) -1)
   pair? ^{:line 432 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and high? ^{:line 432 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 432 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= pair-unit 56320) ^{:line 432 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= pair-unit 57343)))
   width ^{:line 434 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 435 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= unit 127) 1
  ^{:line 436 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= unit 2047) 2
  pair? 4
  ^{:line 438 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or high? low?) -1
  :else 3)]
  ^{:line 440 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 440 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= width -1) ^{:line 441 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 442 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " contains an unpaired UTF-16 surrogate")) ^{:line 443 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [next-total ^{:line 443 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ total width)]
  ^{:line 444 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 444 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> next-total maximum) ^{:line 445 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-string-limit ^{:line 446 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " exceeds the UTF-8 byte limit")) ^{:line 447 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 447 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ index ^{:line 447 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if pair? 2 1)) next-total))))))))

^{:line 449 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- strict-utf8-bytes! [^String value maximum ^String label]
  ^{:line 451 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [expected ^{:line 451 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (utf8-length! value maximum label)]
  ^{:line 452 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (try
  ^{:line 453 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [encoder ^{:line 453 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (doto ^{:line 453 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.newEncoder StandardCharsets/UTF_8)
  ^{:line 454 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.onMalformedInput CodingErrorAction/REPORT)
  ^{:line 455 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.onUnmappableCharacter CodingErrorAction/REPORT))
   buffer ^{:line 456 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.encode encoder ^{:line 456 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (CharBuffer/wrap value))
   bytes ^{:line 457 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (byte-array ^{:line 457 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.remaining buffer))]
  ^{:line 458 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.get buffer bytes)
  ^{:line 459 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 459 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= expected ^{:line 459 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (alength bytes)) bytes ^{:line 461 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 462 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " encoded to an unexpected byte length"))))
  (catch CharacterCodingException _
    ^{:line 464 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 465 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " is not valid UTF-8 text"))))))

^{:line 467 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^String strict-utf8-string! [bytes ^String label]
  ^{:line 468 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (try
  ^{:line 469 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [decoder ^{:line 469 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (doto ^{:line 469 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.newDecoder StandardCharsets/UTF_8)
  ^{:line 470 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.onMalformedInput CodingErrorAction/REPORT)
  ^{:line 471 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.onUnmappableCharacter CodingErrorAction/REPORT))]
  ^{:line 472 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str ^{:line 472 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.decode decoder ^{:line 472 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ByteBuffer/wrap bytes))))
  (catch CharacterCodingException _
    ^{:line 474 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 475 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " is not valid UTF-8")))))

^{:line 477 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-write-u8! [out value]
  ^{:line 478 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.write out ^{:line 478 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 478 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (bit-and 255 value)))
  nil)

^{:line 481 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-write-u32-le! [out value]
  ^{:line 482 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 482 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 482 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= value 0) ^{:line 482 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= value 4294967295)) ^{:line 483 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 484 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (loop [offset 0]
  ^{:line 485 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 485 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< offset 4) ^{:line 486 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 487 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out ^{:line 487 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (unsigned-bit-shift-right value ^{:line 487 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (* offset 8)))
  ^{:line 488 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 488 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ offset 1))) nil))
  nil) ^{:line 491 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-integer-range "u32 value is out of range")))

^{:line 493 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-write-i64-le! [out value]
  ^{:line 494 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (loop [offset 0]
  ^{:line 495 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 495 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< offset 8) ^{:line 496 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 497 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out ^{:line 497 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (unsigned-bit-shift-right value ^{:line 497 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (* offset 8)))
  ^{:line 498 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 498 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ offset 1))) nil)))

^{:line 501 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-node! [counter maximum]
  ^{:line 502 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [count-value ^{:line 502 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (swap! counter inc)]
  ^{:line 503 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 503 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> count-value maximum) ^{:line 504 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-node-limit "TermCodecV1 node count exceeds the configured bound") nil)))

^{:line 508 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- measure-term-core! [term depth max-string-bytes max-nodes max-depth counter]
  ^{:line 511 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 511 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> depth max-depth) ^{:line 512 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-depth-exceeded "recursive Term exceeds the TermCodecV1 depth bound") ^{:line 514 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 515 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-node! counter max-nodes)
  ^{:line 516 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 517 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple? term) ^{:line 518 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 1 ^{:line 519 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 519 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (measure-term-core! ^{:line 519 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot0 term) ^{:line 519 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 521 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 521 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (measure-term-core! ^{:line 521 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot1 term) ^{:line 521 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 523 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (measure-term-core! ^{:line 523 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot2 term) ^{:line 523 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter))))
  ^{:line 525 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (string? term) ^{:line 525 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 5 ^{:line 525 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (utf8-length! term max-string-bytes "String atom"))
  ^{:line 526 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (integer? term) 9
  ^{:line 527 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 527 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (number? term) ^{:line 527 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 527 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (integer? term))) 9
  ^{:line 528 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (boolean? term) 1
  ^{:line 529 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (keyword? term) ^{:line 530 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [spelling ^{:line 530 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (subs ^{:line 530 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str term) 1)]
  ^{:line 531 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 531 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (empty? spelling) ^{:line 532 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-keyword "Keyword atom spelling must be nonempty") ^{:line 534 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 5 ^{:line 534 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (utf8-length! spelling max-string-bytes "Keyword atom"))))
  ^{:line 535 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/instant? term) 13
  :else ^{:line 537 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-unsupported-term "TermCodecV1 encountered a value outside Term")))))

^{:line 540 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn measure-term-codec-v1! [term max-string-bytes max-nodes max-depth]
  ^{:line 543 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-codec-limits! max-string-bytes max-nodes max-depth)
  ^{:line 544 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [counter ^{:line 544 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (atom 0)
   byte-count ^{:line 546 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (measure-term-core! term 0 max-string-bytes max-nodes max-depth counter)]
  ^{:line 547 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->TermCodecMeasure byte-count ^{:line 547 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (deref counter))))

^{:line 549 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- write-sized-text-core! [out ^String value max-string-bytes ^String label]
  ^{:line 551 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [bytes ^{:line 551 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (strict-utf8-bytes! value max-string-bytes label)]
  ^{:line 552 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u32-le! out ^{:line 552 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (alength bytes))
  ^{:line 553 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.write out bytes)
  nil))

^{:line 556 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- write-term-core! [out term max-string-bytes]
  ^{:line 558 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 559 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple? term) ^{:line 560 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 561 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 7)
  ^{:line 562 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-term-core! out ^{:line 562 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot0 term) max-string-bytes)
  ^{:line 563 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-term-core! out ^{:line 563 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot1 term) max-string-bytes)
  ^{:line 564 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-term-core! out ^{:line 564 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot2 term) max-string-bytes))
  ^{:line 565 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (string? term) ^{:line 566 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 566 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 1)
  ^{:line 567 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-sized-text-core! out term max-string-bytes "String atom"))
  ^{:line 568 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (integer? term) ^{:line 569 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 569 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 2)
  ^{:line 569 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-i64-le! out term))
  ^{:line 570 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 570 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (number? term) ^{:line 570 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (not ^{:line 570 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (integer? term))) ^{:line 571 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 571 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 3)
  ^{:line 572 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-i64-le! out ^{:line 572 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (Double/doubleToLongBits ^{:line 572 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (double term))))
  ^{:line 573 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (false? term) ^{:line 573 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 4)
  ^{:line 574 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (true? term) ^{:line 574 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 5)
  ^{:line 575 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (keyword? term) ^{:line 576 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 576 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 6)
  ^{:line 577 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-sized-text-core! out ^{:line 577 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (subs ^{:line 577 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str term) 1) max-string-bytes "Keyword atom"))
  ^{:line 579 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/instant? term) ^{:line 580 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 581 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 8)
  ^{:line 582 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-i64-le! out ^{:line 582 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/instant-epoch-seconds term))
  ^{:line 583 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u32-le! out ^{:line 583 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/instant-nanos term)))
  :else ^{:line 585 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-unsupported-term "TermCodecV1 encountered a value outside Term")))

^{:line 588 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn write-term-codec-v1! [out term max-string-bytes max-nodes max-depth]
  ^{:line 591 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [measure ^{:line 592 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (measure-term-codec-v1! term max-string-bytes max-nodes max-depth)]
  ^{:line 593 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-term-core! out term max-string-bytes)
  ^{:line 594 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/termcodecmeasure-nodes measure)))

^{:line 596 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-ensure! [buffer count-value ^String context]
  ^{:line 597 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 597 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< ^{:line 597 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.remaining buffer) count-value) ^{:line 598 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-truncated ^{:line 599 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str "TermCodecV1 ended inside " context)) nil))

^{:line 602 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-read-u8! [buffer ^String context]
  ^{:line 603 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-ensure! buffer 1 context)
  ^{:line 604 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [one ^{:line 604 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (byte-array 1)]
  ^{:line 605 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.get buffer one)
  ^{:line 606 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (bit-and 255 ^{:line 606 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 606 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (aget one 0)))))

^{:line 608 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- codec-read-u32-le! [buffer ^String context]
  ^{:line 609 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-ensure! buffer 4 context)
  ^{:line 610 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (Integer/toUnsignedLong ^{:line 610 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getInt buffer)))

^{:line 612 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^String read-sized-text-core! [buffer max-string-bytes ^String context]
  ^{:line 614 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [length ^{:line 614 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-read-u32-le! buffer context)]
  ^{:line 615 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 615 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> length max-string-bytes) ^{:line 616 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-string-limit ^{:line 617 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str context " exceeds the UTF-8 byte limit")) ^{:line 618 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 619 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-ensure! buffer length context)
  ^{:line 620 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [bytes ^{:line 620 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (byte-array length)]
  ^{:line 621 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.get buffer bytes)
  ^{:line 622 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (strict-utf8-string! bytes context))))))

^{:line 624 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- decode-term-core! [buffer depth max-string-bytes max-nodes max-depth counter]
  ^{:line 627 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 627 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> depth max-depth) ^{:line 628 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-depth-exceeded "recursive Term exceeds the TermCodecV1 depth bound") ^{:line 630 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 631 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-node! counter max-nodes)
  ^{:line 632 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [tag ^{:line 632 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-read-u8! buffer "Term tag")]
  ^{:line 633 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 634 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 1) ^{:line 634 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-sized-text-core! buffer max-string-bytes "String atom")
  ^{:line 635 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 2) ^{:line 635 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 635 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-ensure! buffer 8 "Int atom")
  ^{:line 635 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getLong buffer))
  ^{:line 636 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 3) ^{:line 637 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 637 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-ensure! buffer 8 "Float atom")
  ^{:line 638 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (Double/longBitsToDouble ^{:line 638 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getLong buffer)))
  ^{:line 639 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 4) false
  ^{:line 640 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 5) true
  ^{:line 641 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 6) ^{:line 642 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [spelling ^{:line 643 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-sized-text-core! buffer max-string-bytes "Keyword atom")]
  ^{:line 644 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 644 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (empty? spelling) ^{:line 645 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-keyword "Keyword atom spelling must be nonempty") ^{:line 647 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (keyword spelling)))
  ^{:line 648 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 7) ^{:line 649 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple ^{:line 650 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (decode-term-core! buffer ^{:line 650 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 652 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (decode-term-core! buffer ^{:line 652 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 654 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (decode-term-core! buffer ^{:line 654 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter))
  ^{:line 656 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 8) ^{:line 657 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 658 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-ensure! buffer 12 "Instant atom")
  ^{:line 659 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [seconds ^{:line 659 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getLong buffer)
   nanos ^{:line 660 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-read-u32-le! buffer "Instant nanos")]
  ^{:line 661 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 661 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< nanos 1000000000) ^{:line 662 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/instant seconds nanos) ^{:line 663 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-invalid-instant "Instant nanoseconds are outside the canonical range"))))
  :else ^{:line 666 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-fail! :term-codec-bad-tag "TermCodecV1 contains an unknown tag"))))))

^{:line 669 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn decode-term-codec-v1! [buffer max-string-bytes max-nodes max-depth]
  ^{:line 672 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-codec-limits! max-string-bytes max-nodes max-depth)
  ^{:line 673 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.order buffer ByteOrder/LITTLE_ENDIAN)
  ^{:line 674 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [counter ^{:line 674 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (atom 0)
   value ^{:line 676 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (decode-term-core! buffer 0 max-string-bytes max-nodes max-depth counter)]
  ^{:line 677 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->TermCodecDecoded value ^{:line 677 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (deref counter))))

^{:line 683 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-major 1)

^{:line 684 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-minor 0)

^{:line 685 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-header-bytes 26)

^{:line 686 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-max-body-bytes 1048576)

^{:line 687 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-max-frame-bytes 1048602)

^{:line 688 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-max-string-bytes 1048576)

^{:line 689 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-max-space-bytes 4096)

^{:line 690 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-max-term-nodes 65536)

^{:line 691 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-max-term-depth 256)

^{:line 692 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-v1-magic ^{:line 693 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getBytes "FRAMRPC\u0000" StandardCharsets/UTF_8))

^{:line 695 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-fail! [code ^String message]
  ^{:line 696 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (throw ^{:line 696 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ex-info message ^{:line 696 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} {:type code :fram/code code})))

^{:line 698 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^Boolean rpc-u32? [value]
  ^{:line 699 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 699 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= value 0) ^{:line 699 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= value 4294967295)))

^{:line 701 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^Boolean rpc-i64? [value]
  ^{:line 702 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 702 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= value -9223372036854775808) ^{:line 703 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= value 9223372036854775807)))

^{:line 705 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-kind-code! [kind]
  ^{:line 706 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 707 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :request) 1
  ^{:line 708 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :response) 2
  ^{:line 709 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :cancel) 3
  ^{:line 710 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :event) 4
  :else ^{:line 711 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown")))

^{:line 713 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-code-kind! [code]
  ^{:line 714 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 715 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= code 1) :request
  ^{:line 716 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= code 2) :response
  ^{:line 717 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= code 3) :cancel
  ^{:line 718 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= code 4) :event
  :else ^{:line 719 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown")))

^{:line 721 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- require-rpc-term! [value ^String label]
  ^{:line 722 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 722 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/term? value) nil ^{:line 724 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-term ^{:line 724 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " must be a Term"))))

^{:line 726 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- require-rpc-optional-term! [value ^String label]
  ^{:line 727 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 727 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 727 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? value) ^{:line 727 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/term? value)) nil ^{:line 729 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-term ^{:line 729 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " must be nil or a Term"))))

^{:line 731 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- require-rpc-string! [value ^String label]
  ^{:line 732 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 732 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (string? value) nil ^{:line 734 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 734 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " must be a String"))))

^{:line 736 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- require-rpc-keyword! [value ^String label]
  ^{:line 737 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 737 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (keyword? value) nil ^{:line 739 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 739 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " must be a Keyword"))))

^{:line 741 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- require-rpc-u32! [value ^String label]
  ^{:line 742 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 742 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-u32? value) nil ^{:line 744 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-integer-range ^{:line 744 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " is outside u32"))))

^{:line 746 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- require-rpc-i64! [value ^String label]
  ^{:line 747 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 747 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-i64? value) nil ^{:line 749 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-integer-range ^{:line 749 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str label " is outside i64"))))

^{:line 751 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-page-request! [limit cursor]
  ^{:line 752 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-u32! limit "page limit")
  ^{:line 753 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! cursor "page cursor")
  ^{:line 754 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcPageRequest limit cursor))

^{:line 756 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-page-response! [ordinal next-cursor ^Boolean done]
  ^{:line 758 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-u32! ordinal "page ordinal")
  ^{:line 759 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! next-cursor "next cursor")
  ^{:line 760 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcPageResponse ordinal next-cursor done))

^{:line 762 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-error! [code ^Boolean retryable ^String message detail]
  ^{:line 764 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-keyword! code "error code")
  ^{:line 765 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-string! message "error message")
  ^{:line 766 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! detail "error detail")
  ^{:line 767 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcError code retryable message detail))

^{:line 769 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-request! [^String space op expected-version page timeout-ms payload]
  ^{:line 772 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-string! space "request space")
  ^{:line 773 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-keyword! op "request op")
  ^{:line 774 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if expected-version ^{:line 775 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-i64! expected-version "expected version") nil)
  ^{:line 776 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if timeout-ms ^{:line 777 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-u32! timeout-ms "timeout-ms") nil)
  ^{:line 778 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! payload "request payload")
  ^{:line 779 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcRequest space op expected-version page timeout-ms payload))

^{:line 781 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-response! [^String space op served-version page error payload]
  ^{:line 784 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-string! space "response space")
  ^{:line 785 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-keyword! op "response op")
  ^{:line 786 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-i64! served-version "served version")
  ^{:line 787 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! payload "response payload")
  ^{:line 788 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcResponse space op served-version page error payload))

^{:line 790 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-request-frame [request-id request]
  ^{:line 792 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcFrameV1 :request 0 request-id request nil))

^{:line 794 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-response-frame [request-id response]
  ^{:line 796 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcFrameV1 :response 0 request-id nil response))

^{:line 798 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-cancel-frame [request-id]
  ^{:line 799 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcFrameV1 :cancel 0 request-id nil nil))

^{:line 801 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-event-frame [request-id event]
  ^{:line 803 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/->RpcFrameV1 :event 0 request-id nil event))

^{:line 805 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- validate-rpc-page-request! [page]
  ^{:line 806 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-u32! ^{:line 806 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcpagerequest-limit page) "page limit")
  ^{:line 807 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! ^{:line 807 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-page-request-cursor-value page) "page cursor"))

^{:line 810 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- validate-rpc-page-response! [page]
  ^{:line 811 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-u32! ^{:line 811 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcpageresponse-ordinal page) "page ordinal")
  ^{:line 812 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! ^{:line 812 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-page-response-cursor-value page) "next cursor"))

^{:line 815 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- validate-rpc-error! [error]
  ^{:line 816 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-keyword! ^{:line 816 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcerror-code error) "error code")
  ^{:line 817 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-string! ^{:line 817 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcerror-message error) "error message")
  ^{:line 818 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! ^{:line 818 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-error-detail-value error) "error detail"))

^{:line 820 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- validate-rpc-request! [request]
  ^{:line 821 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-string! ^{:line 821 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-space request) "request space")
  ^{:line 822 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (utf8-length! ^{:line 822 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-space request) rpc-v1-max-space-bytes "SpaceId")
  ^{:line 823 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-keyword! ^{:line 823 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-op request) "request op")
  ^{:line 824 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [expected ^{:line 824 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-expected-version request)
   page ^{:line 825 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-page request)
   timeout ^{:line 826 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-timeout-ms request)]
  ^{:line 827 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if expected ^{:line 827 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-i64! expected "expected version") nil)
  ^{:line 828 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page ^{:line 828 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (validate-rpc-page-request! page) nil)
  ^{:line 829 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if timeout ^{:line 829 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-u32! timeout "timeout-ms") nil)
  ^{:line 830 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! ^{:line 830 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-request-payload-value request) "request payload")))

^{:line 832 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- validate-rpc-response! [response]
  ^{:line 833 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-string! ^{:line 833 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-space response) "response space")
  ^{:line 834 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (utf8-length! ^{:line 834 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-space response) rpc-v1-max-space-bytes "SpaceId")
  ^{:line 835 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-keyword! ^{:line 835 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-op response) "response op")
  ^{:line 836 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-i64! ^{:line 836 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-served-version response) "served version")
  ^{:line 837 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [page ^{:line 837 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-page response)
   error ^{:line 838 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-error response)]
  ^{:line 839 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page ^{:line 839 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (validate-rpc-page-response! page) nil)
  ^{:line 840 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if error ^{:line 840 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (validate-rpc-error! error) nil)
  ^{:line 841 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-optional-term! ^{:line 841 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-response-payload-value response) "response payload")))

^{:line 844 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- validate-rpc-frame! [frame]
  ^{:line 845 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 845 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= 0 ^{:line 845 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-flags frame)) nil ^{:line 847 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-flags "FRAMRPC v1 flags must be zero"))
  ^{:line 848 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-i64! ^{:line 848 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-request-id frame) "request id")
  ^{:line 849 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [kind ^{:line 849 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-kind frame)
   request ^{:line 850 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-request frame)
   response ^{:line 851 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-response frame)]
  ^{:line 852 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 853 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :request) ^{:line 854 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 854 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and request ^{:line 854 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? response)) ^{:line 855 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (validate-rpc-request! request) ^{:line 856 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-shape "request frame must carry exactly one RpcRequest"))
  ^{:line 858 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 858 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :response) ^{:line 858 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :event)) ^{:line 859 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 859 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and response ^{:line 859 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? request)) ^{:line 860 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (validate-rpc-response! response) ^{:line 861 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-shape "response/event frame must carry exactly one RpcResponse"))
  ^{:line 863 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :cancel) ^{:line 864 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 864 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 864 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? request) ^{:line 864 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? response)) nil ^{:line 866 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-shape "cancel frame body must be empty"))
  :else ^{:line 867 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown"))))

^{:line 869 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-add-measured-term! [term nodes]
  ^{:line 871 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [remaining ^{:line 871 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (- rpc-v1-max-term-nodes ^{:line 871 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (deref nodes))]
  ^{:line 872 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 872 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= remaining 0) ^{:line 873 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") ^{:line 875 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [measure ^{:line 876 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (measure-term-codec-v1! term rpc-v1-max-string-bytes remaining rpc-v1-max-term-depth)]
  ^{:line 878 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (swap! nodes + ^{:line 878 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/termcodecmeasure-nodes measure))
  ^{:line 879 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/termcodecmeasure-bytes measure)))))

^{:line 881 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-page-request-bytes! [page nodes]
  ^{:line 883 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [cursor ^{:line 883 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-page-request-cursor-value page)]
  ^{:line 884 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 5 ^{:line 884 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 884 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? cursor) 0 ^{:line 884 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! cursor nodes)))))

^{:line 886 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-page-response-bytes! [page nodes]
  ^{:line 888 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [cursor ^{:line 888 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-page-response-cursor-value page)]
  ^{:line 889 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 6 ^{:line 889 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 889 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? cursor) 0 ^{:line 889 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! cursor nodes)))))

^{:line 891 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-error-bytes! [error nodes]
  ^{:line 892 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [detail ^{:line 892 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-error-detail-value error)]
  ^{:line 893 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 2 ^{:line 894 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 894 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! ^{:line 894 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcerror-code error) nodes) ^{:line 895 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 895 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! ^{:line 895 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcerror-message error) nodes) ^{:line 896 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 896 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? detail) 0 ^{:line 896 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! detail nodes)))))))

^{:line 898 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-request-body-bytes! [request nodes]
  ^{:line 900 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [expected ^{:line 900 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-expected-version request)
   page ^{:line 901 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-page request)
   timeout ^{:line 902 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-timeout-ms request)]
  ^{:line 903 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 3 ^{:line 904 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 904 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! ^{:line 904 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-space request) nodes) ^{:line 905 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 905 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! ^{:line 905 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-op request) nodes) ^{:line 906 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 906 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if expected 8 0) ^{:line 907 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 907 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page ^{:line 907 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-page-request-bytes! page nodes) 0) ^{:line 908 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 908 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if timeout 4 0) ^{:line 909 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! ^{:line 909 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-request-payload-value request) nodes)))))))))

^{:line 912 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-response-body-bytes! [response nodes]
  ^{:line 914 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [page ^{:line 914 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-page response)
   error ^{:line 915 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-error response)
   payload ^{:line 916 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-response-payload-value response)]
  ^{:line 917 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ 11 ^{:line 918 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 918 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! ^{:line 918 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-space response) nodes) ^{:line 919 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 919 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! ^{:line 919 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-op response) nodes) ^{:line 920 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 920 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page ^{:line 920 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-page-response-bytes! page nodes) 0) ^{:line 921 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ ^{:line 921 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if error ^{:line 921 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-error-bytes! error nodes) 0) ^{:line 922 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 922 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? payload) 0 ^{:line 923 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-add-measured-term! payload nodes)))))))))

^{:line 925 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-body-bytes! [frame]
  ^{:line 926 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (validate-rpc-frame! frame)
  ^{:line 927 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [nodes ^{:line 927 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (atom 0)
   kind ^{:line 928 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-kind frame)
   request ^{:line 929 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-request frame)
   response ^{:line 930 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-response frame)
   size ^{:line 932 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 933 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 933 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :request) request) ^{:line 934 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-request-body-bytes! request nodes)
  ^{:line 935 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 935 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 935 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :response) ^{:line 935 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :event)) response) ^{:line 936 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-response-body-bytes! response nodes)
  :else 0)]
  ^{:line 938 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 938 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> size rpc-v1-max-body-bytes) ^{:line 939 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-frame-too-large "FRAMRPC body exceeds the configured byte limit") size)))

^{:line 943 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-write-u16-le! [out value]
  ^{:line 944 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out value)
  ^{:line 945 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out ^{:line 945 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (unsigned-bit-shift-right value 8)))

^{:line 947 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-write-present! [out value]
  ^{:line 948 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out ^{:line 948 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 948 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? value) 0 1)))

^{:line 950 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-write-term! [out value]
  ^{:line 951 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-term-codec-v1! out value rpc-v1-max-string-bytes rpc-v1-max-term-nodes rpc-v1-max-term-depth)
  nil)

^{:line 955 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- write-rpc-page-request! [out page]
  ^{:line 957 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u32-le! out ^{:line 957 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcpagerequest-limit page))
  ^{:line 958 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [cursor ^{:line 958 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-page-request-cursor-value page)]
  ^{:line 959 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out cursor)
  ^{:line 960 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 960 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? cursor) nil ^{:line 960 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out cursor))))

^{:line 962 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- write-rpc-page-response! [out page]
  ^{:line 964 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u32-le! out ^{:line 964 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcpageresponse-ordinal page))
  ^{:line 965 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [cursor ^{:line 965 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-page-response-cursor-value page)]
  ^{:line 966 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out cursor)
  ^{:line 967 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 967 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? cursor) nil ^{:line 967 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out cursor)))
  ^{:line 968 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out ^{:line 968 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 968 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcpageresponse-done page) 1 0)))

^{:line 970 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- write-rpc-error! [out error]
  ^{:line 971 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out ^{:line 971 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcerror-code error))
  ^{:line 972 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out ^{:line 972 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 972 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcerror-retryable error) 1 0))
  ^{:line 973 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out ^{:line 973 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcerror-message error))
  ^{:line 974 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [detail ^{:line 974 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-error-detail-value error)]
  ^{:line 975 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out detail)
  ^{:line 976 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 976 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? detail) nil ^{:line 976 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out detail))))

^{:line 978 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- write-rpc-request! [out request]
  ^{:line 979 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out ^{:line 979 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-space request))
  ^{:line 980 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out ^{:line 980 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-op request))
  ^{:line 981 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [expected ^{:line 981 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-expected-version request)
   page ^{:line 982 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-page request)
   timeout ^{:line 983 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcrequest-timeout-ms request)]
  ^{:line 984 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out expected)
  ^{:line 985 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if expected ^{:line 985 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-i64-le! out expected) nil)
  ^{:line 986 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out page)
  ^{:line 987 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page ^{:line 987 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-rpc-page-request! out page) nil)
  ^{:line 988 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out timeout)
  ^{:line 989 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if timeout ^{:line 989 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u32-le! out timeout) nil)
  ^{:line 990 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out ^{:line 990 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-request-payload-value request))))

^{:line 992 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- write-rpc-response! [out response]
  ^{:line 993 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out ^{:line 993 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-space response))
  ^{:line 994 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out ^{:line 994 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-op response))
  ^{:line 995 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-i64-le! out ^{:line 995 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-served-version response))
  ^{:line 996 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [page ^{:line 996 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-page response)
   error ^{:line 997 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcresponse-error response)
   payload ^{:line 998 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpc-response-payload-value response)]
  ^{:line 999 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out page)
  ^{:line 1000 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page ^{:line 1000 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-rpc-page-response! out page) nil)
  ^{:line 1001 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out error)
  ^{:line 1002 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if error ^{:line 1002 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-rpc-error! out error) nil)
  ^{:line 1003 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-present! out payload)
  ^{:line 1004 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1004 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? payload) nil ^{:line 1004 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-term! out payload))))

^{:line 1006 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn encode-rpc-frame-v1! [frame]
  ^{:line 1007 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [body-size ^{:line 1007 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-body-bytes! frame)
   body ^{:line 1008 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ByteArrayOutputStream. body-size)
   kind ^{:line 1009 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-kind frame)
   request ^{:line 1010 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-request frame)
   response ^{:line 1011 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-response frame)]
  ^{:line 1012 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 1013 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1013 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :request) request) ^{:line 1014 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-rpc-request! body request)
  ^{:line 1015 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1015 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 1015 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :response) ^{:line 1015 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :event)) response) ^{:line 1016 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (write-rpc-response! body response)
  :else nil)
  ^{:line 1018 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1018 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= body-size ^{:line 1018 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.size body)) ^{:line 1019 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [out ^{:line 1019 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ByteArrayOutputStream. ^{:line 1019 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ rpc-v1-header-bytes body-size))]
  ^{:line 1020 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.write out rpc-v1-magic)
  ^{:line 1021 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-u16-le! out rpc-v1-major)
  ^{:line 1022 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-write-u16-le! out rpc-v1-minor)
  ^{:line 1023 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out ^{:line 1023 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-kind-code! kind))
  ^{:line 1024 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u8! out 0)
  ^{:line 1025 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-u32-le! out body-size)
  ^{:line 1026 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (codec-write-i64-le! out ^{:line 1026 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/rpcframev1-request-id frame))
  ^{:line 1027 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.write out ^{:line 1027 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.toByteArray body))
  ^{:line 1028 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.toByteArray out)) ^{:line 1029 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-size-mismatch "FRAMRPC preflight size disagrees with encoded body"))))

^{:line 1032 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-ensure! [buffer count-value ^String context]
  ^{:line 1033 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1033 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< ^{:line 1033 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.remaining buffer) count-value) ^{:line 1034 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-truncated ^{:line 1034 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str "FRAMRPC ended inside " context)) nil))

^{:line 1037 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-read-u8! [buffer ^String context]
  ^{:line 1038 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-ensure! buffer 1 context)
  ^{:line 1039 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [one ^{:line 1039 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (byte-array 1)]
  ^{:line 1040 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.get buffer one)
  ^{:line 1041 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (bit-and 255 ^{:line 1041 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 1041 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (aget one 0)))))

^{:line 1043 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-read-u16-le! [buffer ^String context]
  ^{:line 1044 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-ensure! buffer 2 context)
  ^{:line 1045 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (bit-and 65535 ^{:line 1045 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 1045 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getShort buffer))))

^{:line 1047 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-read-u32-le! [buffer ^String context]
  ^{:line 1048 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-ensure! buffer 4 context)
  ^{:line 1049 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (Integer/toUnsignedLong ^{:line 1049 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getInt buffer)))

^{:line 1051 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-read-i64-le! [buffer ^String context]
  ^{:line 1052 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-ensure! buffer 8 context)
  ^{:line 1053 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.getLong buffer))

^{:line 1055 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^Boolean rpc-read-presence! [buffer ^String context]
  ^{:line 1056 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [value ^{:line 1056 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u8! buffer context)]
  ^{:line 1057 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 1058 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= value 0) false
  ^{:line 1059 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= value 1) true
  :else ^{:line 1061 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-presence ^{:line 1062 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str context " must be the strict byte 0 or 1")))))

^{:line 1064 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^Boolean rpc-read-bool! [buffer ^String context]
  ^{:line 1065 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [value ^{:line 1065 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u8! buffer context)]
  ^{:line 1066 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 1067 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= value 0) false
  ^{:line 1068 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= value 1) true
  :else ^{:line 1070 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-boolean ^{:line 1071 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str context " must be the strict byte 0 or 1")))))

^{:line 1073 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-read-term! [buffer nodes]
  ^{:line 1074 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [remaining ^{:line 1074 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (- rpc-v1-max-term-nodes ^{:line 1074 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (deref nodes))]
  ^{:line 1075 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1075 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (<= remaining 0) ^{:line 1076 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") ^{:line 1078 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [decoded ^{:line 1079 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (decode-term-codec-v1! buffer rpc-v1-max-string-bytes remaining rpc-v1-max-term-depth)]
  ^{:line 1081 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (swap! nodes + ^{:line 1081 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/termcodecdecoded-nodes decoded))
  ^{:line 1082 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/termcodecdecoded-value decoded)))))

^{:line 1084 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^String rpc-read-space-term! [buffer nodes]
  ^{:line 1085 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1085 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= ^{:line 1085 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (deref nodes) rpc-v1-max-term-nodes) ^{:line 1086 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") ^{:line 1088 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (do
  ^{:line 1089 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (swap! nodes inc)
  ^{:line 1090 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [tag ^{:line 1090 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u8! buffer "SpaceId Term tag")]
  ^{:line 1091 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1091 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag 1) ^{:line 1092 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-sized-text-core! buffer rpc-v1-max-space-bytes "SpaceId") ^{:line 1093 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-field "FRAMRPC SpaceId must be a String Term"))))))

^{:line 1095 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- rpc-read-keyword-term! [buffer nodes ^String context]
  ^{:line 1097 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [value ^{:line 1097 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-term! buffer nodes)]
  ^{:line 1098 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1098 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (keyword? value) value ^{:line 1100 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 1100 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str context " must be a Keyword Term")))))

^{:line 1102 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^String rpc-read-string-term! [buffer nodes ^String context]
  ^{:line 1104 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [value ^{:line 1104 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-term! buffer nodes)]
  ^{:line 1105 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1105 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (string? value) value ^{:line 1107 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 1107 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (str context " must be a String Term")))))

^{:line 1109 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- read-rpc-page-request! [buffer nodes]
  ^{:line 1111 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [limit ^{:line 1111 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u32-le! buffer "page limit")
   cursor? ^{:line 1112 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "page cursor presence")
   cursor ^{:line 1113 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if cursor? ^{:line 1113 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-term! buffer nodes) nil)]
  ^{:line 1114 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-page-request! limit cursor)))

^{:line 1116 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- read-rpc-page-response! [buffer nodes]
  ^{:line 1118 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [ordinal ^{:line 1118 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u32-le! buffer "page ordinal")
   cursor? ^{:line 1119 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "next cursor presence")
   cursor ^{:line 1120 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if cursor? ^{:line 1120 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-term! buffer nodes) nil)
   done ^{:line 1121 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-bool! buffer "page done")]
  ^{:line 1122 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-page-response! ordinal cursor done)))

^{:line 1124 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- read-rpc-error! [buffer nodes]
  ^{:line 1125 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [code ^{:line 1125 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-keyword-term! buffer nodes "error code")
   retryable ^{:line 1126 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-bool! buffer "error retryable")
   message ^{:line 1127 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-string-term! buffer nodes "error message")
   detail? ^{:line 1128 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "error detail presence")
   detail ^{:line 1129 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if detail? ^{:line 1129 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-term! buffer nodes) nil)]
  ^{:line 1130 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-error! code retryable message detail)))

^{:line 1132 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- read-rpc-request! [buffer nodes]
  ^{:line 1133 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [space ^{:line 1133 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-space-term! buffer nodes)
   op ^{:line 1134 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-keyword-term! buffer nodes "request op")
   expected? ^{:line 1135 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "expected-version presence")
   expected ^{:line 1136 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if expected? ^{:line 1137 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-i64-le! buffer "expected-version") nil)
   page? ^{:line 1138 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "request page presence")
   page ^{:line 1139 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page? ^{:line 1139 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-rpc-page-request! buffer nodes) nil)
   timeout? ^{:line 1140 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "timeout-ms presence")
   timeout ^{:line 1141 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if timeout? ^{:line 1141 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u32-le! buffer "timeout-ms") nil)
   payload ^{:line 1142 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-term! buffer nodes)]
  ^{:line 1143 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-request! space op expected page timeout payload)))

^{:line 1145 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- read-rpc-response! [buffer nodes]
  ^{:line 1146 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [space ^{:line 1146 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-space-term! buffer nodes)
   op ^{:line 1147 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-keyword-term! buffer nodes "response op")
   served ^{:line 1148 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-i64-le! buffer "served-version")
   page? ^{:line 1149 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "response page presence")
   page ^{:line 1150 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if page? ^{:line 1150 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-rpc-page-response! buffer nodes) nil)
   error? ^{:line 1151 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "response error presence")
   error ^{:line 1152 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if error? ^{:line 1152 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-rpc-error! buffer nodes) nil)
   payload? ^{:line 1153 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-presence! buffer "response payload presence")
   payload ^{:line 1154 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if payload? ^{:line 1154 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-term! buffer nodes) nil)]
  ^{:line 1155 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-response! space op served page error payload)))

^{:line 1157 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn- ^Boolean rpc-magic-valid! [buffer]
  ^{:line 1158 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (loop [index 0
   valid true]
  ^{:line 1159 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1159 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< index 8) ^{:line 1160 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [actual ^{:line 1160 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u8! buffer "magic")
   expected ^{:line 1161 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (bit-and 255 ^{:line 1161 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (int ^{:line 1161 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (aget rpc-v1-magic index)))]
  ^{:line 1162 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur ^{:line 1162 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ index 1) ^{:line 1162 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and valid ^{:line 1162 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= actual expected)))) valid)))

^{:line 1165 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn decode-rpc-frame-v1! [bytes]
  ^{:line 1166 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [byte-count ^{:line 1166 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (alength bytes)]
  ^{:line 1167 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1167 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> byte-count rpc-v1-max-frame-bytes) ^{:line 1168 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-frame-too-large "FRAMRPC frame exceeds the configured byte limit") nil)
  ^{:line 1171 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1171 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< byte-count rpc-v1-header-bytes) ^{:line 1172 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-truncated "FRAMRPC frame ended inside its header") nil)
  ^{:line 1174 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [buffer ^{:line 1174 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (doto ^{:line 1174 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (ByteBuffer/wrap bytes)
  ^{:line 1175 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.order ByteOrder/LITTLE_ENDIAN))]
  ^{:line 1176 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1176 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-magic-valid! buffer) nil ^{:line 1178 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-magic "FRAMRPC magic does not match"))
  ^{:line 1179 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [major ^{:line 1179 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u16-le! buffer "major version")
   minor ^{:line 1180 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u16-le! buffer "minor version")
   kind ^{:line 1181 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-code-kind! ^{:line 1181 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u8! buffer "frame kind"))
   flags ^{:line 1182 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u8! buffer "frame flags")
   body-length ^{:line 1183 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-u32-le! buffer "body length")
   request-id ^{:line 1184 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-read-i64-le! buffer "request id")]
  ^{:line 1185 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1185 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1185 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= major rpc-v1-major) ^{:line 1185 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= minor rpc-v1-minor)) nil ^{:line 1187 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-unsupported-version "FRAMRPC major/minor version is unsupported"))
  ^{:line 1189 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1189 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= flags 0) nil ^{:line 1191 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-flags "FRAMRPC v1 flags must be zero"))
  ^{:line 1192 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1192 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> body-length rpc-v1-max-body-bytes) ^{:line 1193 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-frame-too-large "FRAMRPC declared body exceeds the configured byte limit") nil)
  ^{:line 1196 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1196 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (< ^{:line 1196 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.remaining buffer) body-length) ^{:line 1197 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-truncated "FRAMRPC body is shorter than declared") nil)
  ^{:line 1199 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1199 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (> ^{:line 1199 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.remaining buffer) body-length) ^{:line 1200 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-trailing-bytes "FRAMRPC frame has bytes beyond its declared body") nil)
  ^{:line 1203 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [nodes ^{:line 1203 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (atom 0)
   frame ^{:line 1205 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 1206 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :request) ^{:line 1207 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-request-frame request-id ^{:line 1207 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-rpc-request! buffer nodes))
  ^{:line 1208 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :response) ^{:line 1209 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-response-frame request-id ^{:line 1209 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-rpc-response! buffer nodes))
  ^{:line 1210 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= kind :event) ^{:line 1211 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-event-frame request-id ^{:line 1211 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (read-rpc-response! buffer nodes))
  :else ^{:line 1213 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1213 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= body-length 0) ^{:line 1214 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-cancel-frame request-id) ^{:line 1215 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-shape "FRAMRPC cancel body must be exactly empty")))]
  ^{:line 1217 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1217 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (zero? ^{:line 1217 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (.remaining buffer)) frame ^{:line 1219 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-trailing-bytes "FRAMRPC body decoder left trailing bytes")))))))

^{:line 1226 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-unit :rpc/unit)

^{:line 1227 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-list-end :rpc/list-end)

^{:line 1228 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-none :rpc/none)

^{:line 1229 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-subject-any :rpc/subject-any)

^{:line 1230 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def rpc-subject-existing :rpc/subject-existing)

^{:line 1231 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (def query-current :query/current)

^{:line 1233 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-list! [values]
  ^{:line 1234 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (reduce ^{:line 1234 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (fn [tail value] ^{:line 1235 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! value "RPC list value")
  ^{:line 1236 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple :rpc/list value tail)) rpc-list-end ^{:line 1237 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (reverse values)))

^{:line 1239 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-list-values! [value]
  ^{:line 1240 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (loop [cursor value
   result ^{:line 1240 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} []
   count-value 0]
  ^{:line 1241 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 1242 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= cursor rpc-list-end) result
  ^{:line 1243 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (>= count-value rpc-v1-max-term-nodes) ^{:line 1244 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-list "RPC list exceeds the Term node bound")
  ^{:line 1245 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1245 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple? cursor) ^{:line 1245 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :rpc/list ^{:line 1245 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot0 cursor))) ^{:line 1246 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [head ^{:line 1246 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot1 cursor)
   tail ^{:line 1247 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot2 cursor)]
  ^{:line 1248 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! head "RPC list head")
  ^{:line 1249 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! tail "RPC list tail")
  ^{:line 1250 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (recur tail ^{:line 1250 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (conj result head) ^{:line 1250 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (+ count-value 1)))
  :else ^{:line 1251 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-list "RPC list must end with :rpc/list-end"))))

^{:line 1254 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-some! [value]
  ^{:line 1255 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! value "RPC option value")
  ^{:line 1256 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple :rpc/some value :rpc/option))

^{:line 1258 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-option! [value]
  ^{:line 1259 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1259 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (nil? value) rpc-none ^{:line 1259 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-some! value)))

^{:line 1261 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn ^Boolean rpc-option-present?! [value]
  ^{:line 1262 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (cond
  ^{:line 1263 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= value rpc-none) false
  ^{:line 1264 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1264 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple? value) ^{:line 1265 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1265 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :rpc/some ^{:line 1265 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot0 value)) ^{:line 1266 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :rpc/option ^{:line 1266 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot2 value)))) true
  :else ^{:line 1267 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-option "RPC option must be :rpc/none or (:rpc/some value :rpc/option)")))

^{:line 1270 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-option-value! [value]
  ^{:line 1271 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1271 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option-present?! value) ^{:line 1271 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot1 value) nil))

^{:line 1273 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-record! [tag fields]
  ^{:line 1274 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple tag ^{:line 1274 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! fields) :rpc/record))

^{:line 1276 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-record-fields! [value tag field-count]
  ^{:line 1278 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1278 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1278 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple? value) ^{:line 1279 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (and ^{:line 1279 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= tag ^{:line 1279 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot0 value)) ^{:line 1280 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= :rpc/record ^{:line 1280 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot2 value)))) ^{:line 1281 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (let [fields ^{:line 1281 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list-values! ^{:line 1281 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (t/triple-slot1 value))]
  ^{:line 1282 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1282 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= field-count ^{:line 1282 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (count fields)) fields ^{:line 1284 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-record "RPC record contains the wrong number of fields"))) ^{:line 1286 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-record "RPC record tag or marker is invalid")))

^{:line 1288 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-fence! [resource holder epoch]
  ^{:line 1289 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! resource "lease resource")
  ^{:line 1290 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (require-rpc-term! holder "lease holder")
  ^{:line 1291 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/fence ^{:line 1291 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [resource holder epoch]))

^{:line 1293 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-action! [operation proposition policy]
  ^{:line 1295 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1295 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 1295 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= operation :rpc/assert) ^{:line 1295 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= operation :rpc/retract)) nil ^{:line 1297 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-action "RPC action operation is invalid"))
  ^{:line 1298 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1298 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 1298 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= policy rpc-subject-any) ^{:line 1298 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= policy rpc-subject-existing)) nil ^{:line 1300 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-policy "RPC subject policy is invalid"))
  ^{:line 1301 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/action ^{:line 1301 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [operation proposition policy]))

^{:line 1303 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-action-result! [input-index ^Boolean changed occurrences]
  ^{:line 1305 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/action-result ^{:line 1306 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [input-index changed ^{:line 1306 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! occurrences)]))

^{:line 1308 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-mutation-result! [results]
  ^{:line 1309 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/mutation-result ^{:line 1309 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1309 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! results)]))

^{:line 1311 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-write! [proposition policy fence]
  ^{:line 1312 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (if ^{:line 1312 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (or ^{:line 1312 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= policy rpc-subject-any) ^{:line 1312 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (= policy rpc-subject-existing)) nil ^{:line 1314 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-fail! :rpc-invalid-policy "RPC subject policy is invalid"))
  ^{:line 1315 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/write ^{:line 1315 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [proposition policy ^{:line 1315 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option! fence)]))

^{:line 1317 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-batch! [actions fence]
  ^{:line 1318 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/batch ^{:line 1318 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1318 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! actions) ^{:line 1318 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option! fence)]))

^{:line 1320 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-triple-pattern! [slot0 slot1 slot2]
  ^{:line 1321 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/triple-pattern ^{:line 1322 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1322 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option! slot0) ^{:line 1322 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option! slot1) ^{:line 1322 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option! slot2)]))

^{:line 1324 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-status! [state live-count engine]
  ^{:line 1325 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/status ^{:line 1325 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [state live-count engine]))

^{:line 1327 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-triples! [values]
  ^{:line 1328 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/triples ^{:line 1328 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1328 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! values)]))

^{:line 1330 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-occurrences! [values]
  ^{:line 1331 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/occurrences ^{:line 1331 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1331 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! values)]))

^{:line 1333 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-lease-acquire! [resource holder ttl-ms]
  ^{:line 1334 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :lease/acquire ^{:line 1334 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [resource holder ttl-ms]))

^{:line 1336 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-lease-renew! [fence ttl-ms]
  ^{:line 1337 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :lease/renew ^{:line 1337 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [fence ttl-ms]))

^{:line 1339 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-lease-grant! [fence expires]
  ^{:line 1340 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :lease/grant ^{:line 1340 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [fence expires]))

^{:line 1342 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-lease-released! [^Boolean released]
  ^{:line 1343 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :lease/released ^{:line 1343 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [released]))

^{:line 1345 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-lease-check! [^Boolean valid expires]
  ^{:line 1346 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :lease/check ^{:line 1346 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [valid ^{:line 1346 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option! expires)]))

^{:line 1348 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-violation! [code detail]
  ^{:line 1349 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/violation ^{:line 1349 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [code detail]))

^{:line 1351 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-validation! [^Boolean valid violations]
  ^{:line 1352 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :rpc/validation ^{:line 1352 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [valid ^{:line 1352 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! violations)]))

^{:line 1354 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-variable! [^String name]
  ^{:line 1355 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/var ^{:line 1355 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [name]))

^{:line 1357 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-constant! [value]
  ^{:line 1358 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/const ^{:line 1358 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [value]))

^{:line 1360 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-head! [^String relation terms]
  ^{:line 1362 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/head ^{:line 1362 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [relation ^{:line 1362 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! terms)]))

^{:line 1364 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-relation! [^String relation terms ^Boolean negated]
  ^{:line 1366 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/relation ^{:line 1366 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [relation ^{:line 1366 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! terms) negated]))

^{:line 1368 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-predicate! [operation left right]
  ^{:line 1370 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/predicate ^{:line 1370 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [operation left right]))

^{:line 1372 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-function! [operation terms ^String bind-variable]
  ^{:line 1374 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/function ^{:line 1375 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [operation ^{:line 1375 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! terms) bind-variable]))

^{:line 1377 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-rule! [head clauses]
  ^{:line 1378 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/rule ^{:line 1378 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [head ^{:line 1378 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! clauses)]))

^{:line 1380 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-stratum! [rules]
  ^{:line 1381 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/stratum ^{:line 1381 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1381 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! rules)]))

^{:line 1383 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-find-relation! [^String relation]
  ^{:line 1384 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/find-relation ^{:line 1384 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [relation]))

^{:line 1386 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-aggregate! [operation argument-index]
  ^{:line 1387 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/aggregate ^{:line 1387 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [operation ^{:line 1387 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-option! argument-index)]))

^{:line 1389 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-having! [comparison aggregate-index value]
  ^{:line 1391 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/having ^{:line 1391 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [comparison aggregate-index value]))

^{:line 1393 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-find-aggregate! [^String relation grouping aggregates having]
  ^{:line 1396 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/find-aggregate ^{:line 1397 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [relation ^{:line 1397 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! grouping) ^{:line 1397 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! aggregates) ^{:line 1398 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! having)]))

^{:line 1400 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-plan! [find strata]
  ^{:line 1401 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/plan ^{:line 1401 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [find ^{:line 1401 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! strata)]))

^{:line 1403 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-as-of! [version]
  ^{:line 1404 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/as-of ^{:line 1404 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [version]))

^{:line 1406 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-request! [plan snapshot]
  ^{:line 1407 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/request ^{:line 1407 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [plan snapshot]))

^{:line 1409 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-row! [values]
  ^{:line 1410 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/row ^{:line 1410 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1410 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! values)]))

^{:line 1412 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-rows! [rows]
  ^{:line 1413 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/rows ^{:line 1413 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [^{:line 1413 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-list! rows)]))

^{:line 1415 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (defn rpc-query-cursor! [snapshot-version ^String query-sha256 next-page-ordinal after-row]
  ^{:line 1418 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} (rpc-record! :query/cursor ^{:line 1419 :file "/home/tom/code/fram/wt-fram-rpc/src/coord_daemon_wire.bclj"} [snapshot-version query-sha256 next-page-ordinal after-row]))
