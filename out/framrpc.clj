(ns framrpc
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

(def dispatch-table {:query {:route :query :handler :query :required [:query] :response :query} :query-page {:route :query :handler :query-page :required [:query :limit] :response :query} :pull {:route :query :handler :pull :required [:root :pattern] :response :query} :as-of {:route :locked :handler :as-of :required [:seq] :response :read} :for-log {:route :for-log :handler :for-log :required [] :validation :handler :response :fenced} :version-free {:route :direct :handler :version-free :required [] :response :version} :seen {:route :direct :handler :seen :required [:v] :response :read} :reload-status {:route :direct :handler :reload-status :required [] :response :status} :cutover-status {:route :direct :handler :cutover-status :required [:token] :response :status} :cutover-prepare {:route :direct :handler :cutover-prepare :required [:token :cutover-id] :response :status} :cutover-demote {:route :direct :handler :cutover-demote :required [:token :cutover-id :expected-instance] :response :status} :cutover-promote {:route :direct :handler :cutover-promote :required [:token :cutover-id :marker] :response :status} :write-def {:route :direct :handler :write-def :required [:spec] :response :structured} :read-def {:route :direct :handler :read-def :required [:spec] :response :structured} :index {:route :direct :handler :index :required [:spec] :response :structured} :check {:route :direct :handler :check :required [] :response :structured} :edit-min {:route :direct :handler :edit-min :required [:spec] :response :edit} :edit-prepare {:route :direct :handler :edit-prepare :required [:spec] :response :edit} :edit-commit {:route :direct :handler :edit-commit :required [:candidate] :response :edit} :version {:route :locked :handler :version :required [] :response :version} :assert {:route :locked :handler :assert :required [:te :p :r] :response :mutation} :assert-existing {:route :locked :handler :assert-existing :required [:te :p :r] :response :mutation} :assert-batch {:route :locked :handler :assert-batch :required [:te :facts] :response :mutation} :assert-batch-at-version {:route :locked :handler :assert-batch-at-version :required [:te :facts :base] :response :mutation} :claim-cite {:route :locked :handler :claim-cite :required [] :validation :handler :response :mutation} :claim-decision {:route :locked :handler :claim-decision :required [] :validation :handler :response :mutation} :claim-unverify {:route :locked :handler :claim-unverify :required [] :validation :handler :response :mutation} :managed-agent-publish {:route :locked :handler :managed-agent-publish :required [] :validation :handler :response :mutation} :assert-with-fence {:route :locked :handler :assert-with-fence :required [:te :p :r :res :holder :epoch] :response :mutation} :assert-at-version {:route :locked :handler :assert-at-version :required [:te :p :r] :response :mutation} :assert-at-version-with-fence {:route :locked :handler :assert-at-version-with-fence :required [:te :p :r :base :res :holder :epoch] :response :mutation} :retract {:route :locked :handler :retract :required [:te :p :r] :response :mutation} :retract-existing {:route :locked :handler :retract-existing :required [:te :p :r] :response :mutation} :retract-with-fence {:route :locked :handler :retract-with-fence :required [:te :p :r :res :holder :epoch] :response :mutation} :bump {:route :locked :handler :bump :required [:te :p :n] :response :mutation} :acquire-lease {:route :locked :handler :acquire-lease :required [:res :holder :ttl-ms] :response :lease} :renew-lease {:route :locked :handler :renew-lease :required [:res :holder :epoch :ttl-ms] :response :lease} :release-lease {:route :locked :handler :release-lease :required [:res :holder] :response :lease} :fence-ok {:route :locked :handler :fence-ok :required [:res :holder :epoch] :response :read} :edit-protocol {:route :locked :handler :edit-protocol :required [] :response :status} :validate {:route :locked :handler :validate :required [] :response :read} :show {:route :locked :handler :show :required [:te] :response :read} :warm-check {:route :locked :handler :warm-check :required [] :response :status} :status {:route :locked :handler :status :required [] :response :status} :claim-read {:route :locked :handler :claim-read :required [] :validation :handler :response :read} :claims-read {:route :locked :handler :claims-read :required [] :validation :handler :response :read} :claims-needing-reverification {:route :locked :handler :claims-needing-reverification :required [] :validation :handler :response :read} :facts {:route :locked :handler :facts :required [] :response :read} :facts-for-subjects {:route :locked :handler :facts-for-subjects :required [:subjects] :response :read} :snapshot {:route :locked :handler :snapshot :required [] :response :snapshot} :snapshot-reconcile {:route :locked :handler :snapshot-reconcile :required [] :response :snapshot} :built-through {:route :locked :handler :built-through :required [] :response :status} :module-path {:route :locked :handler :module-path :required [:module] :response :read} :render {:route :locked :handler :render :required [:module] :response :read} :callers {:route :locked :handler :callers :required [] :response :read} :blast {:route :locked :handler :blast :required [] :response :read} :concern-overlap {:route :locked :handler :concern-overlap :required [:te] :response :read} :refers-ensure {:route :locked :handler :refers-ensure :required [] :response :read} :refers-keyset {:route :locked :handler :refers-keyset :required [] :response :read} :resolved {:route :locked :handler :resolved :required [:te :p] :response :read}})

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
  {:reject ["this server requires a :for-log envelope"] :code :log-fence-required :served-log served-log})))

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

(def term-codec-v1-depth-limit 256)

(defn- codec-fail! [code ^String message]
  (throw (ex-info message {:type code :fram/code code})))

(defn- require-codec-limits! [max-string-bytes max-nodes max-depth]
  (if (and (> max-string-bytes 0) (and (> max-nodes 0) (and (> max-depth 0) (<= max-depth 256)))) nil (codec-fail! :term-codec-invalid-limit "TermCodecV1 limits must be positive and depth at most 256")))

(defn- utf8-length! [^String value maximum ^String label]
  (loop [index 0
   total 0]
  (if (>= index (count value)) total (let [unit (int (.charAt value index))
   high? (and (>= unit 55296) (<= unit 56319))
   low? (and (>= unit 56320) (<= unit 57343))
   pair-unit (if (and high? (< (+ index 1) (count value))) (int (.charAt value (+ index 1))) -1)
   pair? (and high? (and (>= pair-unit 56320) (<= pair-unit 57343)))
   width (cond
  (<= unit 127) 1
  (<= unit 2047) 2
  pair? 4
  (or high? low?) -1
  :else 3)]
  (if (= width -1) (codec-fail! :term-codec-invalid-utf8 (str label " contains an unpaired UTF-16 surrogate")) (let [next-total (+ total width)]
  (if (> next-total maximum) (codec-fail! :term-codec-string-limit (str label " exceeds the UTF-8 byte limit")) (recur (+ index (if pair? 2 1)) next-total))))))))

(defn- strict-utf8-bytes! [^String value maximum ^String label]
  (let [expected (utf8-length! value maximum label)]
  (try
  (let [encoder (doto (.newEncoder StandardCharsets/UTF_8)
  (.onMalformedInput CodingErrorAction/REPORT)
  (.onUnmappableCharacter CodingErrorAction/REPORT))
   buffer (.encode encoder (CharBuffer/wrap value))
   bytes (byte-array (.remaining buffer))]
  (.get buffer bytes)
  (if (= expected (alength bytes)) bytes (codec-fail! :term-codec-invalid-utf8 (str label " encoded to an unexpected byte length"))))
  (catch CharacterCodingException _
    (codec-fail! :term-codec-invalid-utf8 (str label " is not valid UTF-8 text"))))))

(defn- ^String strict-utf8-string! [bytes ^String label]
  (try
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
  (.onMalformedInput CodingErrorAction/REPORT)
  (.onUnmappableCharacter CodingErrorAction/REPORT))]
  (str (.decode decoder (ByteBuffer/wrap bytes))))
  (catch CharacterCodingException _
    (codec-fail! :term-codec-invalid-utf8 (str label " is not valid UTF-8")))))

(defn- codec-write-u8! [out value]
  (.write out (int (bit-and 255 value)))
  nil)

(defn- codec-write-u32-le! [out value]
  (if (and (>= value 0) (<= value 4294967295)) (do
  (loop [offset 0]
  (if (< offset 4) (do
  (codec-write-u8! out (unsigned-bit-shift-right value (* offset 8)))
  (recur (+ offset 1))) nil))
  nil) (codec-fail! :term-codec-integer-range "u32 value is out of range")))

(defn- codec-write-i64-le! [out value]
  (loop [offset 0]
  (if (< offset 8) (do
  (codec-write-u8! out (unsigned-bit-shift-right value (* offset 8)))
  (recur (+ offset 1))) nil)))

(defn- codec-node! [counter maximum]
  (let [count-value (swap! counter inc)]
  (if (> count-value maximum) (codec-fail! :term-codec-node-limit "TermCodecV1 node count exceeds the configured bound") nil)))

(defn- measure-term-core! [term depth max-string-bytes max-nodes max-depth counter]
  (if (> depth max-depth) (codec-fail! :term-depth-exceeded "recursive Term exceeds the TermCodecV1 depth bound") (do
  (codec-node! counter max-nodes)
  (cond
  (t/triple? term) (+ 1 (+ (measure-term-core! (t/triple-slot0 term) (+ depth 1) max-string-bytes max-nodes max-depth counter) (+ (measure-term-core! (t/triple-slot1 term) (+ depth 1) max-string-bytes max-nodes max-depth counter) (measure-term-core! (t/triple-slot2 term) (+ depth 1) max-string-bytes max-nodes max-depth counter))))
  (string? term) (+ 5 (utf8-length! term max-string-bytes "String atom"))
  (integer? term) 9
  (and (number? term) (not (integer? term))) 9
  (boolean? term) 1
  (keyword? term) (let [spelling (subs (str term) 1)]
  (if (empty? spelling) (codec-fail! :term-codec-invalid-keyword "Keyword atom spelling must be nonempty") (+ 5 (utf8-length! spelling max-string-bytes "Keyword atom"))))
  (t/instant? term) 13
  :else (codec-fail! :term-codec-unsupported-term "TermCodecV1 encountered a value outside Term")))))

(defn measure-term-codec-v1! [term max-string-bytes max-nodes max-depth]
  (require-codec-limits! max-string-bytes max-nodes max-depth)
  (let [counter (atom 0)
   byte-count (measure-term-core! term 0 max-string-bytes max-nodes max-depth counter)]
  (t/->TermCodecMeasure byte-count (deref counter))))

(defn- write-sized-text-core! [out ^String value max-string-bytes ^String label]
  (let [bytes (strict-utf8-bytes! value max-string-bytes label)]
  (codec-write-u32-le! out (alength bytes))
  (.write out bytes)
  nil))

(defn- write-term-core! [out term max-string-bytes]
  (cond
  (t/triple? term) (do
  (codec-write-u8! out 7)
  (write-term-core! out (t/triple-slot0 term) max-string-bytes)
  (write-term-core! out (t/triple-slot1 term) max-string-bytes)
  (write-term-core! out (t/triple-slot2 term) max-string-bytes))
  (string? term) (do
  (codec-write-u8! out 1)
  (write-sized-text-core! out term max-string-bytes "String atom"))
  (integer? term) (do
  (codec-write-u8! out 2)
  (codec-write-i64-le! out term))
  (and (number? term) (not (integer? term))) (do
  (codec-write-u8! out 3)
  (codec-write-i64-le! out (Double/doubleToLongBits (double term))))
  (false? term) (codec-write-u8! out 4)
  (true? term) (codec-write-u8! out 5)
  (keyword? term) (do
  (codec-write-u8! out 6)
  (write-sized-text-core! out (subs (str term) 1) max-string-bytes "Keyword atom"))
  (t/instant? term) (do
  (codec-write-u8! out 8)
  (codec-write-i64-le! out (t/instant-epoch-seconds term))
  (codec-write-u32-le! out (t/instant-nanos term)))
  :else (codec-fail! :term-codec-unsupported-term "TermCodecV1 encountered a value outside Term")))

(defn write-term-codec-v1! [out term max-string-bytes max-nodes max-depth]
  (let [measure (measure-term-codec-v1! term max-string-bytes max-nodes max-depth)]
  (write-term-core! out term max-string-bytes)
  (t/termcodecmeasure-nodes measure)))

(defn- codec-ensure! [buffer count-value ^String context]
  (if (< (.remaining buffer) count-value) (codec-fail! :term-codec-truncated (str "TermCodecV1 ended inside " context)) nil))

(defn- codec-read-u8! [buffer ^String context]
  (codec-ensure! buffer 1 context)
  (let [one (byte-array 1)]
  (.get buffer one)
  (bit-and 255 (int (aget one 0)))))

(defn- codec-read-u32-le! [buffer ^String context]
  (codec-ensure! buffer 4 context)
  (Integer/toUnsignedLong (.getInt buffer)))

(defn- ^String read-sized-text-core! [buffer max-string-bytes ^String context]
  (let [length (codec-read-u32-le! buffer context)]
  (if (> length max-string-bytes) (codec-fail! :term-codec-string-limit (str context " exceeds the UTF-8 byte limit")) (do
  (codec-ensure! buffer length context)
  (let [bytes (byte-array length)]
  (.get buffer bytes)
  (strict-utf8-string! bytes context))))))

(defn- decode-term-core! [buffer depth max-string-bytes max-nodes max-depth counter]
  (if (> depth max-depth) (codec-fail! :term-depth-exceeded "recursive Term exceeds the TermCodecV1 depth bound") (do
  (codec-node! counter max-nodes)
  (let [tag (codec-read-u8! buffer "Term tag")]
  (cond
  (= tag 1) (read-sized-text-core! buffer max-string-bytes "String atom")
  (= tag 2) (do
  (codec-ensure! buffer 8 "Int atom")
  (.getLong buffer))
  (= tag 3) (do
  (codec-ensure! buffer 8 "Float atom")
  (Double/longBitsToDouble (.getLong buffer)))
  (= tag 4) false
  (= tag 5) true
  (= tag 6) (let [spelling (read-sized-text-core! buffer max-string-bytes "Keyword atom")]
  (if (empty? spelling) (codec-fail! :term-codec-invalid-keyword "Keyword atom spelling must be nonempty") (keyword spelling)))
  (= tag 7) (t/triple (decode-term-core! buffer (+ depth 1) max-string-bytes max-nodes max-depth counter) (decode-term-core! buffer (+ depth 1) max-string-bytes max-nodes max-depth counter) (decode-term-core! buffer (+ depth 1) max-string-bytes max-nodes max-depth counter))
  (= tag 8) (do
  (codec-ensure! buffer 12 "Instant atom")
  (let [seconds (.getLong buffer)
   nanos (codec-read-u32-le! buffer "Instant nanos")]
  (if (< nanos 1000000000) (t/instant seconds nanos) (codec-fail! :term-codec-invalid-instant "Instant nanoseconds are outside the canonical range"))))
  :else (codec-fail! :term-codec-bad-tag "TermCodecV1 contains an unknown tag"))))))

(defn decode-term-codec-v1! [buffer max-string-bytes max-nodes max-depth]
  (require-codec-limits! max-string-bytes max-nodes max-depth)
  (.order buffer ByteOrder/LITTLE_ENDIAN)
  (let [counter (atom 0)
   value (decode-term-core! buffer 0 max-string-bytes max-nodes max-depth counter)]
  (t/->TermCodecDecoded value (deref counter))))

(def rpc-v1-major 1)

(def rpc-v1-minor 0)

(def rpc-v1-header-bytes 26)

(def rpc-v1-max-body-bytes 1048576)

(def rpc-v1-max-frame-bytes 1048602)

(def rpc-v1-max-string-bytes 1048576)

(def rpc-v1-max-space-bytes 4096)

(def rpc-v1-max-term-nodes 65536)

(def rpc-v1-max-term-depth 256)

(def rpc-v1-magic (.getBytes "FRAMRPC\u0000" StandardCharsets/UTF_8))

(defn- rpc-fail! [code ^String message]
  (throw (ex-info message {:type code :fram/code code})))

(defn- ^Boolean rpc-u32? [value]
  (and (>= value 0) (<= value 4294967295)))

(defn- ^Boolean rpc-i64? [value]
  (and (>= value -9223372036854775808) (<= value 9223372036854775807)))

(defn- rpc-kind-code! [kind]
  (cond
  (= kind :request) 1
  (= kind :response) 2
  (= kind :cancel) 3
  (= kind :event) 4
  :else (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown")))

(defn- rpc-code-kind! [code]
  (cond
  (= code 1) :request
  (= code 2) :response
  (= code 3) :cancel
  (= code 4) :event
  :else (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown")))

(defn- require-rpc-term! [value ^String label]
  (if (t/term? value) nil (rpc-fail! :rpc-invalid-term (str label " must be a Term"))))

(defn- require-rpc-optional-term! [value ^String label]
  (if (or (nil? value) (t/term? value)) nil (rpc-fail! :rpc-invalid-term (str label " must be nil or a Term"))))

(defn- require-rpc-string! [value ^String label]
  (if (string? value) nil (rpc-fail! :rpc-invalid-field (str label " must be a String"))))

(defn- require-rpc-keyword! [value ^String label]
  (if (keyword? value) nil (rpc-fail! :rpc-invalid-field (str label " must be a Keyword"))))

(defn- require-rpc-u32! [value ^String label]
  (if (rpc-u32? value) nil (rpc-fail! :rpc-integer-range (str label " is outside u32"))))

(defn- require-rpc-i64! [value ^String label]
  (if (rpc-i64? value) nil (rpc-fail! :rpc-integer-range (str label " is outside i64"))))

(defn rpc-page-request! [limit cursor]
  (require-rpc-u32! limit "page limit")
  (require-rpc-optional-term! cursor "page cursor")
  (t/->RpcPageRequest limit cursor))

(defn rpc-page-response! [ordinal next-cursor ^Boolean done]
  (require-rpc-u32! ordinal "page ordinal")
  (require-rpc-optional-term! next-cursor "next cursor")
  (t/->RpcPageResponse ordinal next-cursor done))

(defn rpc-error! [code ^Boolean retryable ^String message detail]
  (require-rpc-keyword! code "error code")
  (require-rpc-string! message "error message")
  (require-rpc-optional-term! detail "error detail")
  (t/->RpcError code retryable message detail))

(defn rpc-request! [^String space op expected-version page timeout-ms payload]
  (require-rpc-string! space "request space")
  (require-rpc-keyword! op "request op")
  (if expected-version (require-rpc-i64! expected-version "expected version") nil)
  (if timeout-ms (require-rpc-u32! timeout-ms "timeout-ms") nil)
  (require-rpc-term! payload "request payload")
  (t/->RpcRequest space op expected-version page timeout-ms payload))

(defn rpc-response! [^String space op served-version page error payload]
  (require-rpc-string! space "response space")
  (require-rpc-keyword! op "response op")
  (require-rpc-i64! served-version "served version")
  (require-rpc-optional-term! payload "response payload")
  (t/->RpcResponse space op served-version page error payload))

(defn rpc-request-frame [request-id request]
  (t/->RpcFrameV1 :request 0 request-id request nil))

(defn rpc-response-frame [request-id response]
  (t/->RpcFrameV1 :response 0 request-id nil response))

(defn rpc-cancel-frame [request-id]
  (t/->RpcFrameV1 :cancel 0 request-id nil nil))

(defn rpc-event-frame [request-id event]
  (t/->RpcFrameV1 :event 0 request-id nil event))

(defn- validate-rpc-page-request! [page]
  (require-rpc-u32! (t/rpcpagerequest-limit page) "page limit")
  (require-rpc-optional-term! (t/rpc-page-request-cursor-value page) "page cursor"))

(defn- validate-rpc-page-response! [page]
  (require-rpc-u32! (t/rpcpageresponse-ordinal page) "page ordinal")
  (require-rpc-optional-term! (t/rpc-page-response-cursor-value page) "next cursor"))

(defn- validate-rpc-error! [error]
  (require-rpc-keyword! (t/rpcerror-code error) "error code")
  (require-rpc-string! (t/rpcerror-message error) "error message")
  (require-rpc-optional-term! (t/rpc-error-detail-value error) "error detail"))

(defn- validate-rpc-request! [request]
  (require-rpc-string! (t/rpcrequest-space request) "request space")
  (utf8-length! (t/rpcrequest-space request) rpc-v1-max-space-bytes "SpaceId")
  (require-rpc-keyword! (t/rpcrequest-op request) "request op")
  (let [expected (t/rpcrequest-expected-version request)
   page (t/rpcrequest-page request)
   timeout (t/rpcrequest-timeout-ms request)]
  (if expected (require-rpc-i64! expected "expected version") nil)
  (if page (validate-rpc-page-request! page) nil)
  (if timeout (require-rpc-u32! timeout "timeout-ms") nil)
  (require-rpc-term! (t/rpc-request-payload-value request) "request payload")))

(defn- validate-rpc-response! [response]
  (require-rpc-string! (t/rpcresponse-space response) "response space")
  (utf8-length! (t/rpcresponse-space response) rpc-v1-max-space-bytes "SpaceId")
  (require-rpc-keyword! (t/rpcresponse-op response) "response op")
  (require-rpc-i64! (t/rpcresponse-served-version response) "served version")
  (let [page (t/rpcresponse-page response)
   error (t/rpcresponse-error response)]
  (if page (validate-rpc-page-response! page) nil)
  (if error (validate-rpc-error! error) nil)
  (require-rpc-optional-term! (t/rpc-response-payload-value response) "response payload")))

(defn- validate-rpc-frame! [frame]
  (if (= 0 (t/rpcframev1-flags frame)) nil (rpc-fail! :rpc-invalid-flags "FRAMRPC v1 flags must be zero"))
  (require-rpc-i64! (t/rpcframev1-request-id frame) "request id")
  (let [kind (t/rpcframev1-kind frame)
   request (t/rpcframev1-request frame)
   response (t/rpcframev1-response frame)]
  (cond
  (= kind :request) (if (and request (nil? response)) (validate-rpc-request! request) (rpc-fail! :rpc-invalid-shape "request frame must carry exactly one RpcRequest"))
  (or (= kind :response) (= kind :event)) (if (and response (nil? request)) (validate-rpc-response! response) (rpc-fail! :rpc-invalid-shape "response/event frame must carry exactly one RpcResponse"))
  (= kind :cancel) (if (and (nil? request) (nil? response)) nil (rpc-fail! :rpc-invalid-shape "cancel frame body must be empty"))
  :else (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown"))))

(defn- rpc-add-measured-term! [term nodes]
  (let [remaining (- rpc-v1-max-term-nodes (deref nodes))]
  (if (<= remaining 0) (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") (let [measure (measure-term-codec-v1! term rpc-v1-max-string-bytes remaining rpc-v1-max-term-depth)]
  (swap! nodes + (t/termcodecmeasure-nodes measure))
  (t/termcodecmeasure-bytes measure)))))

(defn- rpc-page-request-bytes! [page nodes]
  (let [cursor (t/rpc-page-request-cursor-value page)]
  (+ 5 (if (nil? cursor) 0 (rpc-add-measured-term! cursor nodes)))))

(defn- rpc-page-response-bytes! [page nodes]
  (let [cursor (t/rpc-page-response-cursor-value page)]
  (+ 6 (if (nil? cursor) 0 (rpc-add-measured-term! cursor nodes)))))

(defn- rpc-error-bytes! [error nodes]
  (let [detail (t/rpc-error-detail-value error)]
  (+ 2 (+ (rpc-add-measured-term! (t/rpcerror-code error) nodes) (+ (rpc-add-measured-term! (t/rpcerror-message error) nodes) (if (nil? detail) 0 (rpc-add-measured-term! detail nodes)))))))

(defn- rpc-request-body-bytes! [request nodes]
  (let [expected (t/rpcrequest-expected-version request)
   page (t/rpcrequest-page request)
   timeout (t/rpcrequest-timeout-ms request)]
  (+ 3 (+ (rpc-add-measured-term! (t/rpcrequest-space request) nodes) (+ (rpc-add-measured-term! (t/rpcrequest-op request) nodes) (+ (if expected 8 0) (+ (if page (rpc-page-request-bytes! page nodes) 0) (+ (if timeout 4 0) (rpc-add-measured-term! (t/rpc-request-payload-value request) nodes)))))))))

(defn- rpc-response-body-bytes! [response nodes]
  (let [page (t/rpcresponse-page response)
   error (t/rpcresponse-error response)
   payload (t/rpc-response-payload-value response)]
  (+ 11 (+ (rpc-add-measured-term! (t/rpcresponse-space response) nodes) (+ (rpc-add-measured-term! (t/rpcresponse-op response) nodes) (+ (if page (rpc-page-response-bytes! page nodes) 0) (+ (if error (rpc-error-bytes! error nodes) 0) (if (nil? payload) 0 (rpc-add-measured-term! payload nodes)))))))))

(defn- rpc-body-bytes! [frame]
  (validate-rpc-frame! frame)
  (let [nodes (atom 0)
   kind (t/rpcframev1-kind frame)
   request (t/rpcframev1-request frame)
   response (t/rpcframev1-response frame)
   size (cond
  (and (= kind :request) request) (rpc-request-body-bytes! request nodes)
  (and (or (= kind :response) (= kind :event)) response) (rpc-response-body-bytes! response nodes)
  :else 0)]
  (if (> size rpc-v1-max-body-bytes) (rpc-fail! :rpc-frame-too-large "FRAMRPC body exceeds the configured byte limit") size)))

(defn- rpc-write-u16-le! [out value]
  (codec-write-u8! out value)
  (codec-write-u8! out (unsigned-bit-shift-right value 8)))

(defn- rpc-write-present! [out value]
  (codec-write-u8! out (if (nil? value) 0 1)))

(defn- rpc-write-term! [out value]
  (write-term-codec-v1! out value rpc-v1-max-string-bytes rpc-v1-max-term-nodes rpc-v1-max-term-depth)
  nil)

(defn- write-rpc-page-request! [out page]
  (codec-write-u32-le! out (t/rpcpagerequest-limit page))
  (let [cursor (t/rpc-page-request-cursor-value page)]
  (rpc-write-present! out cursor)
  (if (nil? cursor) nil (rpc-write-term! out cursor))))

(defn- write-rpc-page-response! [out page]
  (codec-write-u32-le! out (t/rpcpageresponse-ordinal page))
  (let [cursor (t/rpc-page-response-cursor-value page)]
  (rpc-write-present! out cursor)
  (if (nil? cursor) nil (rpc-write-term! out cursor)))
  (codec-write-u8! out (if (t/rpcpageresponse-done page) 1 0)))

(defn- write-rpc-error! [out error]
  (rpc-write-term! out (t/rpcerror-code error))
  (codec-write-u8! out (if (t/rpcerror-retryable error) 1 0))
  (rpc-write-term! out (t/rpcerror-message error))
  (let [detail (t/rpc-error-detail-value error)]
  (rpc-write-present! out detail)
  (if (nil? detail) nil (rpc-write-term! out detail))))

(defn- write-rpc-request! [out request]
  (rpc-write-term! out (t/rpcrequest-space request))
  (rpc-write-term! out (t/rpcrequest-op request))
  (let [expected (t/rpcrequest-expected-version request)
   page (t/rpcrequest-page request)
   timeout (t/rpcrequest-timeout-ms request)]
  (rpc-write-present! out expected)
  (if expected (codec-write-i64-le! out expected) nil)
  (rpc-write-present! out page)
  (if page (write-rpc-page-request! out page) nil)
  (rpc-write-present! out timeout)
  (if timeout (codec-write-u32-le! out timeout) nil)
  (rpc-write-term! out (t/rpc-request-payload-value request))))

(defn- write-rpc-response! [out response]
  (rpc-write-term! out (t/rpcresponse-space response))
  (rpc-write-term! out (t/rpcresponse-op response))
  (codec-write-i64-le! out (t/rpcresponse-served-version response))
  (let [page (t/rpcresponse-page response)
   error (t/rpcresponse-error response)
   payload (t/rpc-response-payload-value response)]
  (rpc-write-present! out page)
  (if page (write-rpc-page-response! out page) nil)
  (rpc-write-present! out error)
  (if error (write-rpc-error! out error) nil)
  (rpc-write-present! out payload)
  (if (nil? payload) nil (rpc-write-term! out payload))))

(defn encode-rpc-frame-v1! [frame]
  (let [body-size (rpc-body-bytes! frame)
   body (ByteArrayOutputStream. body-size)
   kind (t/rpcframev1-kind frame)
   request (t/rpcframev1-request frame)
   response (t/rpcframev1-response frame)]
  (cond
  (and (= kind :request) request) (write-rpc-request! body request)
  (and (or (= kind :response) (= kind :event)) response) (write-rpc-response! body response)
  :else nil)
  (if (= body-size (.size body)) (let [out (ByteArrayOutputStream. (+ rpc-v1-header-bytes body-size))]
  (.write out rpc-v1-magic)
  (rpc-write-u16-le! out rpc-v1-major)
  (rpc-write-u16-le! out rpc-v1-minor)
  (codec-write-u8! out (rpc-kind-code! kind))
  (codec-write-u8! out 0)
  (codec-write-u32-le! out body-size)
  (codec-write-i64-le! out (t/rpcframev1-request-id frame))
  (.write out (.toByteArray body))
  (.toByteArray out)) (rpc-fail! :rpc-size-mismatch "FRAMRPC preflight size disagrees with encoded body"))))

(defn- rpc-ensure! [buffer count-value ^String context]
  (if (< (.remaining buffer) count-value) (rpc-fail! :rpc-truncated (str "FRAMRPC ended inside " context)) nil))

(defn- rpc-read-u8! [buffer ^String context]
  (rpc-ensure! buffer 1 context)
  (let [one (byte-array 1)]
  (.get buffer one)
  (bit-and 255 (int (aget one 0)))))

(defn- rpc-read-u16-le! [buffer ^String context]
  (rpc-ensure! buffer 2 context)
  (bit-and 65535 (int (.getShort buffer))))

(defn- rpc-read-u32-le! [buffer ^String context]
  (rpc-ensure! buffer 4 context)
  (Integer/toUnsignedLong (.getInt buffer)))

(defn- rpc-read-i64-le! [buffer ^String context]
  (rpc-ensure! buffer 8 context)
  (.getLong buffer))

(defn- ^Boolean rpc-read-presence! [buffer ^String context]
  (let [value (rpc-read-u8! buffer context)]
  (cond
  (= value 0) false
  (= value 1) true
  :else (rpc-fail! :rpc-invalid-presence (str context " must be the strict byte 0 or 1")))))

(defn- ^Boolean rpc-read-bool! [buffer ^String context]
  (let [value (rpc-read-u8! buffer context)]
  (cond
  (= value 0) false
  (= value 1) true
  :else (rpc-fail! :rpc-invalid-boolean (str context " must be the strict byte 0 or 1")))))

(defn- rpc-read-term! [buffer nodes]
  (let [remaining (- rpc-v1-max-term-nodes (deref nodes))]
  (if (<= remaining 0) (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") (let [decoded (decode-term-codec-v1! buffer rpc-v1-max-string-bytes remaining rpc-v1-max-term-depth)]
  (swap! nodes + (t/termcodecdecoded-nodes decoded))
  (t/termcodecdecoded-value decoded)))))

(defn- ^String rpc-read-space-term! [buffer nodes]
  (if (>= (deref nodes) rpc-v1-max-term-nodes) (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") (do
  (swap! nodes inc)
  (let [tag (rpc-read-u8! buffer "SpaceId Term tag")]
  (if (= tag 1) (read-sized-text-core! buffer rpc-v1-max-space-bytes "SpaceId") (rpc-fail! :rpc-invalid-field "FRAMRPC SpaceId must be a String Term"))))))

(defn- rpc-read-keyword-term! [buffer nodes ^String context]
  (let [value (rpc-read-term! buffer nodes)]
  (if (keyword? value) value (rpc-fail! :rpc-invalid-field (str context " must be a Keyword Term")))))

(defn- ^String rpc-read-string-term! [buffer nodes ^String context]
  (let [value (rpc-read-term! buffer nodes)]
  (if (string? value) value (rpc-fail! :rpc-invalid-field (str context " must be a String Term")))))

(defn- read-rpc-page-request! [buffer nodes]
  (let [limit (rpc-read-u32-le! buffer "page limit")
   cursor? (rpc-read-presence! buffer "page cursor presence")
   cursor (if cursor? (rpc-read-term! buffer nodes) nil)]
  (rpc-page-request! limit cursor)))

(defn- read-rpc-page-response! [buffer nodes]
  (let [ordinal (rpc-read-u32-le! buffer "page ordinal")
   cursor? (rpc-read-presence! buffer "next cursor presence")
   cursor (if cursor? (rpc-read-term! buffer nodes) nil)
   done (rpc-read-bool! buffer "page done")]
  (rpc-page-response! ordinal cursor done)))

(defn- read-rpc-error! [buffer nodes]
  (let [code (rpc-read-keyword-term! buffer nodes "error code")
   retryable (rpc-read-bool! buffer "error retryable")
   message (rpc-read-string-term! buffer nodes "error message")
   detail? (rpc-read-presence! buffer "error detail presence")
   detail (if detail? (rpc-read-term! buffer nodes) nil)]
  (rpc-error! code retryable message detail)))

(defn- read-rpc-request! [buffer nodes]
  (let [space (rpc-read-space-term! buffer nodes)
   op (rpc-read-keyword-term! buffer nodes "request op")
   expected? (rpc-read-presence! buffer "expected-version presence")
   expected (if expected? (rpc-read-i64-le! buffer "expected-version") nil)
   page? (rpc-read-presence! buffer "request page presence")
   page (if page? (read-rpc-page-request! buffer nodes) nil)
   timeout? (rpc-read-presence! buffer "timeout-ms presence")
   timeout (if timeout? (rpc-read-u32-le! buffer "timeout-ms") nil)
   payload (rpc-read-term! buffer nodes)]
  (rpc-request! space op expected page timeout payload)))

(defn- read-rpc-response! [buffer nodes]
  (let [space (rpc-read-space-term! buffer nodes)
   op (rpc-read-keyword-term! buffer nodes "response op")
   served (rpc-read-i64-le! buffer "served-version")
   page? (rpc-read-presence! buffer "response page presence")
   page (if page? (read-rpc-page-response! buffer nodes) nil)
   error? (rpc-read-presence! buffer "response error presence")
   error (if error? (read-rpc-error! buffer nodes) nil)
   payload? (rpc-read-presence! buffer "response payload presence")
   payload (if payload? (rpc-read-term! buffer nodes) nil)]
  (rpc-response! space op served page error payload)))

(defn- ^Boolean rpc-magic-valid! [buffer]
  (loop [index 0
   valid true]
  (if (< index 8) (let [actual (rpc-read-u8! buffer "magic")
   expected (bit-and 255 (int (aget rpc-v1-magic index)))]
  (recur (+ index 1) (and valid (= actual expected)))) valid)))

(defn decode-rpc-frame-v1! [bytes]
  (let [byte-count (alength bytes)]
  (if (> byte-count rpc-v1-max-frame-bytes) (rpc-fail! :rpc-frame-too-large "FRAMRPC frame exceeds the configured byte limit") nil)
  (if (< byte-count rpc-v1-header-bytes) (rpc-fail! :rpc-truncated "FRAMRPC frame ended inside its header") nil)
  (let [buffer (doto (ByteBuffer/wrap bytes)
  (.order ByteOrder/LITTLE_ENDIAN))]
  (if (rpc-magic-valid! buffer) nil (rpc-fail! :rpc-invalid-magic "FRAMRPC magic does not match"))
  (let [major (rpc-read-u16-le! buffer "major version")
   minor (rpc-read-u16-le! buffer "minor version")
   kind (rpc-code-kind! (rpc-read-u8! buffer "frame kind"))
   flags (rpc-read-u8! buffer "frame flags")
   body-length (rpc-read-u32-le! buffer "body length")
   request-id (rpc-read-i64-le! buffer "request id")]
  (if (and (= major rpc-v1-major) (= minor rpc-v1-minor)) nil (rpc-fail! :rpc-unsupported-version "FRAMRPC major/minor version is unsupported"))
  (if (= flags 0) nil (rpc-fail! :rpc-invalid-flags "FRAMRPC v1 flags must be zero"))
  (if (> body-length rpc-v1-max-body-bytes) (rpc-fail! :rpc-frame-too-large "FRAMRPC declared body exceeds the configured byte limit") nil)
  (if (< (.remaining buffer) body-length) (rpc-fail! :rpc-truncated "FRAMRPC body is shorter than declared") nil)
  (if (> (.remaining buffer) body-length) (rpc-fail! :rpc-trailing-bytes "FRAMRPC frame has bytes beyond its declared body") nil)
  (let [nodes (atom 0)
   frame (cond
  (= kind :request) (rpc-request-frame request-id (read-rpc-request! buffer nodes))
  (= kind :response) (rpc-response-frame request-id (read-rpc-response! buffer nodes))
  (= kind :event) (rpc-event-frame request-id (read-rpc-response! buffer nodes))
  :else (if (= body-length 0) (rpc-cancel-frame request-id) (rpc-fail! :rpc-invalid-shape "FRAMRPC cancel body must be exactly empty")))]
  (if (zero? (.remaining buffer)) frame (rpc-fail! :rpc-trailing-bytes "FRAMRPC body decoder left trailing bytes")))))))

(def rpc-unit :rpc/unit)

(def rpc-list-end :rpc/list-end)

(def rpc-none :rpc/none)

(def rpc-subject-any :rpc/subject-any)

(def rpc-subject-existing :rpc/subject-existing)

(def query-current :query/current)

(defn rpc-list! [values]
  (reduce (fn [tail value] (require-rpc-term! value "RPC list value")
  (t/triple :rpc/list value tail)) rpc-list-end (reverse values)))

(defn rpc-list-values! [value]
  (loop [cursor value
   result []
   count-value 0]
  (cond
  (= cursor rpc-list-end) result
  (>= count-value rpc-v1-max-term-nodes) (rpc-fail! :rpc-invalid-list "RPC list exceeds the Term node bound")
  (and (t/triple? cursor) (= :rpc/list (t/triple-slot0 cursor))) (let [head (t/triple-slot1 cursor)
   tail (t/triple-slot2 cursor)]
  (require-rpc-term! head "RPC list head")
  (require-rpc-term! tail "RPC list tail")
  (recur tail (conj result head) (+ count-value 1)))
  :else (rpc-fail! :rpc-invalid-list "RPC list must end with :rpc/list-end"))))

(defn rpc-some! [value]
  (require-rpc-term! value "RPC option value")
  (t/triple :rpc/some value :rpc/option))

(defn rpc-option! [value]
  (if (nil? value) rpc-none (rpc-some! value)))

(defn ^Boolean rpc-option-present?! [value]
  (cond
  (= value rpc-none) false
  (and (t/triple? value) (and (= :rpc/some (t/triple-slot0 value)) (= :rpc/option (t/triple-slot2 value)))) true
  :else (rpc-fail! :rpc-invalid-option "RPC option must be :rpc/none or (:rpc/some value :rpc/option)")))

(defn rpc-option-value! [value]
  (if (rpc-option-present?! value) (t/triple-slot1 value) nil))

(defn rpc-record! [tag fields]
  (t/triple tag (rpc-list! fields) :rpc/record))

(defn rpc-record-fields! [value tag field-count]
  (if (and (t/triple? value) (and (= tag (t/triple-slot0 value)) (= :rpc/record (t/triple-slot2 value)))) (let [fields (rpc-list-values! (t/triple-slot1 value))]
  (if (= field-count (count fields)) fields (rpc-fail! :rpc-invalid-record "RPC record contains the wrong number of fields"))) (rpc-fail! :rpc-invalid-record "RPC record tag or marker is invalid")))

(defn rpc-fence! [resource holder epoch]
  (require-rpc-term! resource "lease resource")
  (require-rpc-term! holder "lease holder")
  (rpc-record! :rpc/fence [resource holder epoch]))

(defn rpc-action! [operation proposition policy]
  (if (or (= operation :rpc/assert) (= operation :rpc/retract)) nil (rpc-fail! :rpc-invalid-action "RPC action operation is invalid"))
  (if (or (= policy rpc-subject-any) (= policy rpc-subject-existing)) nil (rpc-fail! :rpc-invalid-policy "RPC subject policy is invalid"))
  (rpc-record! :rpc/action [operation proposition policy]))

(defn rpc-action-result! [input-index ^Boolean changed occurrences]
  (rpc-record! :rpc/action-result [input-index changed (rpc-list! occurrences)]))

(defn rpc-mutation-result! [results]
  (rpc-record! :rpc/mutation-result [(rpc-list! results)]))

(defn rpc-write! [proposition policy fence]
  (if (or (= policy rpc-subject-any) (= policy rpc-subject-existing)) nil (rpc-fail! :rpc-invalid-policy "RPC subject policy is invalid"))
  (rpc-record! :rpc/write [proposition policy (rpc-option! fence)]))

(defn rpc-batch! [actions fence]
  (rpc-record! :rpc/batch [(rpc-list! actions) (rpc-option! fence)]))

(defn rpc-triple-pattern! [slot0 slot1 slot2]
  (rpc-record! :rpc/triple-pattern [(rpc-option! slot0) (rpc-option! slot1) (rpc-option! slot2)]))

(defn rpc-status! [state live-count engine cache]
  (rpc-record! :rpc/status [state live-count engine cache]))

(defn rpc-triples! [values]
  (rpc-record! :rpc/triples [(rpc-list! values)]))

(defn rpc-occurrences! [values]
  (rpc-record! :rpc/occurrences [(rpc-list! values)]))

(defn rpc-lease-acquire! [resource holder ttl-ms]
  (rpc-record! :lease/acquire [resource holder ttl-ms]))

(defn rpc-lease-renew! [fence ttl-ms]
  (rpc-record! :lease/renew [fence ttl-ms]))

(defn rpc-lease-grant! [fence expires]
  (rpc-record! :lease/grant [fence expires]))

(defn rpc-lease-released! [^Boolean released]
  (rpc-record! :lease/released [released]))

(defn rpc-lease-check! [^Boolean valid expires]
  (rpc-record! :lease/check [valid (rpc-option! expires)]))

(defn rpc-violation! [code detail]
  (rpc-record! :rpc/violation [code detail]))

(defn rpc-validation! [^Boolean valid violations]
  (rpc-record! :rpc/validation [valid (rpc-list! violations)]))

(defn rpc-query-variable! [^String name]
  (rpc-record! :query/var [name]))

(defn rpc-query-constant! [value]
  (rpc-record! :query/const [value]))

(defn rpc-query-head! [^String relation terms]
  (rpc-record! :query/head [relation (rpc-list! terms)]))

(defn rpc-query-relation! [^String relation terms ^Boolean negated]
  (rpc-record! :query/relation [relation (rpc-list! terms) negated]))

(defn rpc-query-predicate! [operation left right]
  (rpc-record! :query/predicate [operation left right]))

(defn rpc-query-function! [operation terms ^String bind-variable]
  (rpc-record! :query/function [operation (rpc-list! terms) bind-variable]))

(defn rpc-query-rule! [head clauses]
  (rpc-record! :query/rule [head (rpc-list! clauses)]))

(defn rpc-query-stratum! [rules]
  (rpc-record! :query/stratum [(rpc-list! rules)]))

(defn rpc-query-find-relation! [^String relation]
  (rpc-record! :query/find-relation [relation]))

(defn rpc-query-aggregate! [operation argument-index]
  (rpc-record! :query/aggregate [operation (rpc-option! argument-index)]))

(defn rpc-query-having! [comparison aggregate-index value]
  (rpc-record! :query/having [comparison aggregate-index value]))

(defn rpc-query-find-aggregate! [^String relation grouping aggregates having]
  (rpc-record! :query/find-aggregate [relation (rpc-list! grouping) (rpc-list! aggregates) (rpc-list! having)]))

(defn rpc-query-plan! [find strata]
  (rpc-record! :query/plan [find (rpc-list! strata)]))

(defn rpc-query-as-of! [version]
  (rpc-record! :query/as-of [version]))

(defn rpc-query-since! [lower-exclusive upper]
  (rpc-record! :query/since [lower-exclusive upper]))

(defn rpc-query-request! [plan snapshot]
  (rpc-record! :query/request [plan snapshot]))

(defn rpc-query-row! [values]
  (rpc-record! :query/row [(rpc-list! values)]))

(defn rpc-query-rows! [rows]
  (rpc-record! :query/rows [(rpc-list! rows)]))

(defn rpc-query-cursor! [snapshot-version ^String query-sha256 next-page-ordinal after-row]
  (rpc-record! :query/cursor [snapshot-version query-sha256 next-page-ordinal after-row]))
