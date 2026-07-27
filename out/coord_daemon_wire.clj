(ns coord-daemon-wire
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn effective-request-op [req]
  (if (= :for-log (:op req)) (get-in req [:request :op]) (:op req)))

(defn ^Boolean query-request? [req]
  (and (map? req) (or (contains? #{:query :query-page :pull} (:op req)) (and (= :as-of (:op req)) (:query req)))))

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

(defn bad-request-response [t]
  {:error (str "bad request: " (or (:type (ex-data t)) (.getSimpleName (class t))))})
