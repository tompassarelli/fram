(ns coord-daemon-wire
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn effective-request-op [req]
  (if (= :for-log (:op req)) (get-in req [:request :op]) (:op req)))

(defn ^Boolean query-request? [req]
  (and (map? req) (or (contains? #{:query :query-page :pull} (:op req)) (and (= :as-of (:op req)) (:query req)))))

(defrecord QueryLimitPlan [timeout-ms deadline-ns max-steps max-rows max-response-bytes])

(defn querylimitplan-timeout-ms [r] (:timeout-ms r))

(defn querylimitplan-deadline-ns [r] (:deadline-ns r))

(defn querylimitplan-max-steps [r] (:max-steps r))

(defn querylimitplan-max-rows [r] (:max-rows r))

(defn querylimitplan-max-response-bytes [r] (:max-response-bytes r))

(defn lower-limit [req key ceiling]
  (let [n (get req key)]
  (if (and (integer? n) (pos? n)) (min n ceiling) ceiling)))

(defn ^QueryLimitPlan query-limit-plan [req ceilings now-ns]
  (let [timeout (lower-limit req :query-timeout-ms (:timeout-ms ceilings))]
  (->QueryLimitPlan timeout (+ now-ns (* 1000000 timeout)) (lower-limit req :query-max-steps (:max-steps ceilings)) (lower-limit req :query-max-rows (:max-rows ceilings)) (lower-limit req :query-max-response-bytes (:max-response-bytes ceilings)))))

(def dispatch-table {:query {:route :query :handler :query :required [:query] :response :query} :query-page {:route :query :handler :query-page :required [:query :limit] :response :query} :pull {:route :query :handler :pull :required [:root :pattern] :response :query} :as-of {:route :locked :handler :as-of :required [:seq] :response :read} :for-log {:route :for-log :handler :for-log :required [] :validation :handler :response :fenced} :version-free {:route :direct :handler :version-free :required [] :response :version} :seen {:route :direct :handler :seen :required [:v] :response :read} :reload-status {:route :direct :handler :reload-status :required [] :response :status} :write-def {:route :direct :handler :write-def :required [:spec] :response :structured} :read-def {:route :direct :handler :read-def :required [:spec] :response :structured} :index {:route :direct :handler :index :required [:spec] :response :structured} :check {:route :direct :handler :check :required [] :response :structured} :edit-min {:route :direct :handler :edit-min :required [:spec] :response :edit} :edit-prepare {:route :direct :handler :edit-prepare :required [:spec] :response :edit} :edit-commit {:route :direct :handler :edit-commit :required [:candidate] :response :edit} :version {:route :locked :handler :version :required [] :response :version} :assert {:route :locked :handler :assert :required [:te :p :r] :response :mutation} :assert-existing {:route :locked :handler :assert-existing :required [:te :p :r] :response :mutation} :assert-batch {:route :locked :handler :assert-batch :required [:te :facts] :response :mutation} :assert-batch-at-version {:route :locked :handler :assert-batch-at-version :required [:te :facts :base] :response :mutation} :claim-cite {:route :locked :handler :claim-cite :required [] :validation :handler :response :mutation} :claim-decision {:route :locked :handler :claim-decision :required [] :validation :handler :response :mutation} :claim-unverify {:route :locked :handler :claim-unverify :required [] :validation :handler :response :mutation} :managed-agent-publish {:route :locked :handler :managed-agent-publish :required [] :validation :handler :response :mutation} :assert-with-fence {:route :locked :handler :assert-with-fence :required [:te :p :r :res :holder :epoch] :response :mutation} :assert-at-version {:route :locked :handler :assert-at-version :required [:te :p :r :base] :response :mutation} :assert-at-version-with-fence {:route :locked :handler :assert-at-version-with-fence :required [:te :p :r :base :res :holder :epoch] :response :mutation} :retract {:route :locked :handler :retract :required [:te :p :r] :response :mutation} :retract-existing {:route :locked :handler :retract-existing :required [:te :p :r] :response :mutation} :retract-with-fence {:route :locked :handler :retract-with-fence :required [:te :p :r :res :holder :epoch] :response :mutation} :bump {:route :locked :handler :bump :required [:te :p :n] :response :mutation} :acquire-lease {:route :locked :handler :acquire-lease :required [:res :holder :ttl-ms] :response :lease} :renew-lease {:route :locked :handler :renew-lease :required [:res :holder :epoch :ttl-ms] :response :lease} :release-lease {:route :locked :handler :release-lease :required [:res :holder] :response :lease} :fence-ok {:route :locked :handler :fence-ok :required [:res :holder :epoch] :response :read} :edit-protocol {:route :locked :handler :edit-protocol :required [] :response :status} :validate {:route :locked :handler :validate :required [] :response :read} :show {:route :locked :handler :show :required [:te] :response :read} :warm-check {:route :locked :handler :warm-check :required [] :response :status} :status {:route :locked :handler :status :required [] :response :status} :claim-read {:route :locked :handler :claim-read :required [] :validation :handler :response :read} :claims-read {:route :locked :handler :claims-read :required [] :validation :handler :response :read} :claims-needing-reverification {:route :locked :handler :claims-needing-reverification :required [] :validation :handler :response :read} :facts {:route :locked :handler :facts :required [] :response :read} :snapshot {:route :locked :handler :snapshot :required [] :response :snapshot} :snapshot-reconcile {:route :locked :handler :snapshot-reconcile :required [] :response :snapshot} :built-through {:route :locked :handler :built-through :required [] :response :status} :module-path {:route :locked :handler :module-path :required [:module] :response :read} :render {:route :locked :handler :render :required [:module] :response :read} :callers {:route :locked :handler :callers :required [] :response :read} :blast {:route :locked :handler :blast :required [] :response :read} :concern-overlap {:route :locked :handler :concern-overlap :required [:te] :response :read} :refers-ensure {:route :locked :handler :refers-ensure :required [] :response :read} :refers-keyset {:route :locked :handler :refers-keyset :required [] :response :read} :resolved {:route :locked :handler :resolved :required [:te :p] :response :read}})

(defn request-dispatch [req state config]
  (let [op (:op req)
   base (get dispatch-table op {:route :locked :handler :unknown :required [] :response :error})
   route (cond
  (:durability-stop? state) :durability-stop
  (and (= op :for-log) (query-request? (:request req))) :fenced-query
  (query-request? req) :query
  :else (:route base))
   reload-policy (cond
  (:reload-checked? config) :already-checked
  (contains? (:reload-deferred-ops config) op) :deferred
  (contains? (:reload-mutation-ops config) op) :mutation
  :else :fresh)]
  (assoc base :op op :route route :reload-policy reload-policy)))

(defn ^Boolean target-request? [req]
  (or (contains? req :te) (and (contains? req :module) (contains? req :name))))

(defn request-validation-errors [req decision]
  (cond
  (not (map? req)) ["request must be a map"]
  (not (contains? req :op)) [":op is required"]
  (= :unknown (:handler decision)) []
  (= :handler (:validation decision)) []
  :else (let [missing (vec (filter (fn [k] (not (contains? req k))) (:required decision)))
   base (mapv (fn [k] (str (name k) " is required")) missing)
   op (:op req)]
  (cond
  (and (contains? #{:callers :blast} op) (not (target-request? req))) (conj base "te or module+name is required")
  (and (= op :as-of) (not (:query req)) (not (and (contains? req :te) (contains? req :p)))) (conj base "query or te+p is required")
  :else base))))

(defn invalid-request-response [errors]
  (if (seq errors) (do
  {:error (vec errors) :code :invalid-request})))

(defn ^Boolean json-format? [fmt]
  (or (= fmt :json) (= fmt "json")))

(defn ^Boolean edn-too-deep? [^String s max-depth]
  (loop [i 0
   depth 0
   mx 0
   in-str false
   esc false]
  (if (>= i (count s)) (> mx max-depth) (let [c (int (.charAt s i))]
  (cond
  esc (recur (inc i) depth mx in-str false)
  (and in-str (= c 92)) (recur (inc i) depth mx in-str true)
  in-str (recur (inc i) depth mx (not (= c 34)) false)
  (= c 34) (recur (inc i) depth mx true false)
  (or (= c 40) (= c 91) (= c 123)) (let [d (inc depth)]
  (recur (inc i) d (max mx d) in-str false))
  (or (= c 41) (= c 93) (= c 125)) (recur (inc i) (max 0 (dec depth)) mx in-str false)
  :else (recur (inc i) depth mx in-str false))))))

(defn parse-request [^String line max-depth]
  (do
  (if (edn-too-deep? line max-depth) (do
  (throw (ex-info "edn too deep" {:type :edn-too-deep}))))
  (edn/read-string line)))

(defn strict-log-fence-rejection [^Boolean required? req served-log]
  (if (and required? (not= :for-log (:op req))) (do
  {:reject ["this coordinator requires a :for-log envelope"] :code :log-fence-required :served-log served-log})))

(defn ^Boolean fenced-subscribe? [req]
  (let [inner (:request req)]
  (and (= :for-log (:op req)) (map? inner) (= :subscribe (:op inner)))))

(defn subscription-request [req]
  (if (fenced-subscribe? req) (:request req) req))

(defn actual-request [req]
  (if (= :for-log (:op req)) (:request req) req))

(defn subscription-response [version ^Boolean fenced? served-log]
  (if fenced? {:subscribed version :log served-log} {:subscribed version}))

(defrecord ConnectionState [phase request actual format response fenced-subscription query])

(defn connectionstate-phase [r] (:phase r))

(defn connectionstate-request [r] (:request r))

(defn connectionstate-actual [r] (:actual r))

(defn connectionstate-format [r] (:format r))

(defn connectionstate-response [r] (:response r))

(defn connectionstate-fenced-subscription [r] (:fenced-subscription r))

(defn connectionstate-query [r] (:query r))

(defn ^ConnectionState connection-start []
  (->ConnectionState :reading nil nil nil nil false false))

(defn ^ConnectionState connection-transition [^ConnectionState state event]
  (let [kind (:event event)]
  (cond
  (= kind :request) (let [req (:request event)
   strict-reject (:strict-reject event)
   fence-reject (:fence-reject event)
   fenced? (fenced-subscribe? req)
   subscription? (or fenced? (= :subscribe (:op req)))
   actual (actual-request req)
   fmt (:fmt req)]
  (cond
  strict-reject (->ConnectionState :reply req actual fmt strict-reject fenced? false)
  fence-reject (->ConnectionState :reply req actual fmt fence-reject fenced? false)
  subscription? (->ConnectionState :subscribe req (subscription-request req) fmt nil fenced? false)
  :else (->ConnectionState :handle req actual fmt nil false (query-request? actual))))
  (= kind :handled) (->ConnectionState :reply (:request state) (:actual state) (:format state) (:response event) (:fenced-subscription state) (:query state))
  (= kind :replied) (->ConnectionState :done (:request state) (:actual state) (:format state) (:response state) (:fenced-subscription state) (:query state))
  (= kind :eof) (->ConnectionState :done (:request state) (:actual state) (:format state) (:response state) (:fenced-subscription state) (:query state))
  :else state)))

(defn ^String serialize-response [fmt resp to-json]
  (if (json-format? fmt) (to-json resp) (pr-str resp)))

(defn structured-error [stage at message expected got suggestion nearest]
  (let [base {:ok false :stage stage}
   with-at (if at (assoc base :at at) base)
   with-message (if message (assoc with-at :message message) with-at)
   with-expected (if expected (assoc with-message :expected expected) with-message)
   with-got (if got (assoc with-expected :got got) with-expected)
   with-suggestion (if suggestion (assoc with-got :suggestion suggestion) with-got)]
  (if (seq nearest) (assoc with-suggestion :nearest (vec nearest)) with-suggestion)))

(defn exception-gate-error [module nm t]
  (let [d (ex-data t)
   class-name (.getSimpleName (class t))
   msg (or (not-empty (str (.getMessage t))) (:message d) (str "internal error: " class-name))]
  (structured-error :gate {:module module :def nm} msg nil class-name (or (:suggestion d) "simplify the form; ensure every referenced helper/type exists") nil)))

(defn reject-gate-error [module nm er]
  (let [msg (if (vector? (:reject er)) (str/join "; " (:reject er)) (str (:reject er)))
   base (structured-error :canon {:module module :def nm} (str "verb rejected: " msg) nil nil "send exactly one named value def per form; narrow ambiguous edits" nil)]
  (if (:disambiguation er) (assoc base :disambiguation (:disambiguation er)) base)))

(defn edit-min-error-response [spec t version]
  (let [d (ex-data t)
   msg (or (not-empty (str (.getMessage t))) (:message d) (str "internal error: " (.getSimpleName (class t))))
   base {:reject [(str "edit-min: " msg)] :error (exception-gate-error (:module spec) (:name spec) t) :version version}]
  (if (:disambiguation d) (assoc base :disambiguation (:disambiguation d)) base)))

(defn unknown-op-response []
  {:error "unknown op"})

(defn bad-request-response [error-data ^String class-name]
  {:error (str "bad request: " (or (:type error-data) class-name))})

(defn connection-error-selection [scope error-kind error-data ^String class-name]
  (cond
  (not= scope :request) {:action :close}
  (= error-kind :socket-timeout) {:action :close}
  :else {:action :reply :response (bad-request-response error-data class-name)}))
