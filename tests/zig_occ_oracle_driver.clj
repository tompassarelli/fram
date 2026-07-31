#!/usr/bin/env bb
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def subject-pattern #"@[a-z0-9_-]+")
(def predicate-pattern #"(?:p_[a-z0-9_]+|cardinality)")
(def value-pattern #"[a-z0-9_-]+")
(def schema-predicates
  #{"cardinality" "value_kind" "predicate_name" "predicate_alias" "name"})

(defn fail! [message data]
  (throw (ex-info message data)))

(defn parse-integer [text context]
  (or (parse-long text)
      (fail! "expected an integer" {:value text :context context})))

(defn require-match [pattern value context]
  (when-not (and (string? value) (re-matches pattern value))
    (fail! "corpus token violates the oracle alphabet"
           {:value value :context context}))
  value)

(defn parse-fact [token]
  (let [[p encoded & extra] (str/split token #"=" 3)]
    (when (or (seq extra) (str/blank? p) (nil? encoded))
      (fail! "invalid batch fact" {:token token}))
    (let [[r base]
          (if-let [[_ value local-base] (re-matches #"^(.*)@(-?[0-9]+)$" encoded)]
            [value (parse-integer local-base :fact-local-base)]
            [encoded nil])]
      (cond-> {:p (require-match predicate-pattern p :predicate)
               :r (require-match value-pattern r :value)}
        (some? base) (assoc :base base)))))

(defn parse-facts [encoded]
  (if (str/blank? (or encoded ""))
    []
    (mapv parse-fact (str/split encoded #"\|" -1))))

(defn parse-batch-tail [tail]
  (case (count tail)
    0 {:base nil :facts []}
    1 (let [field (first tail)]
        (if (str/includes? field "=")
          {:base nil :facts (parse-facts field)}
          {:base (parse-integer field :batch-base) :facts []}))
    2 {:base (when-not (str/blank? (first tail))
               (parse-integer (first tail) :batch-base))
       :facts (parse-facts (second tail))}
    (fail! "invalid assert-batch field count" {:fields tail})))

(defn exact-fields [op fields expected]
  (when-not (= expected (count fields))
    (fail! "invalid corpus field count"
           {:op op :expected expected :actual (count fields) :fields fields})))

(defn parse-line [line]
  (let [fields (str/split line #"\t" -1)
        op (first fields)]
    (case op
      "version"
      (do (exact-fields op fields 1) {:op :version})

      "assert"
      (do
        (when-not (#{4 5} (count fields))
          (fail! "invalid assert field count" {:fields fields}))
        (let [[_ te p r base] fields]
          (cond-> {:op :assert
                   :te (require-match subject-pattern te :subject)
                   :p (require-match predicate-pattern p :predicate)
                   :r (require-match value-pattern r :value)}
            (some? base) (assoc :base (parse-integer base :base)))))

      "retract"
      (do
        (when-not (#{4 5} (count fields))
          (fail! "invalid retract field count" {:fields fields}))
        (let [[_ te p r base] fields]
          (cond-> {:op :retract
                   :te (require-match subject-pattern te :subject)
                   :p (require-match predicate-pattern p :predicate)
                   :r (require-match value-pattern r :value)}
            (some? base) (assoc :base (parse-integer base :base)))))

      "assert-at-version"
      (do
        (exact-fields op fields 5)
        (let [[_ te p r base] fields]
          {:op :assert-at-version
           :te (require-match subject-pattern te :subject)
           :p (require-match predicate-pattern p :predicate)
           :r (require-match value-pattern r :value)
           :base (parse-integer base :base)}))

      "assert-batch"
      (do
        (when (< (count fields) 2)
          (fail! "assert-batch requires a subject" {:fields fields}))
        (let [[_ te & tail] fields
              {:keys [base facts]} (parse-batch-tail tail)]
          (cond-> {:op :assert-batch
                   :te (require-match subject-pattern te :subject)
                   :facts facts}
            (some? base) (assoc :base base))))

      "assert-batch-at-version"
      (do
        (exact-fields op fields 4)
        (let [[_ te base facts] fields]
          {:op :assert-batch-at-version
           :te (require-match subject-pattern te :subject)
           :base (parse-integer base :base)
           :facts (parse-facts facts)}))

      (fail! "unknown corpus operation" {:line line :op op}))))

(defn request [port payload]
  (with-open [socket (java.net.Socket.)]
    (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 5000)
    (with-open [writer (io/writer (.getOutputStream socket))
                reader (java.io.PushbackReader.
                        (io/reader (.getInputStream socket)))]
      (.write writer (str (pr-str payload) "\n"))
      (.flush writer)
      (edn/read reader))))

(defn reject-reason [reject]
  (cond
    (keyword? reject) (name reject)
    (vector? reject) (str/join "; " (map str reject))
    :else (str reject)))

(defn comma-list [values]
  (str/join "," (map str values)))

(defn normalize-response [index request response]
  (cond
    (= :version (:op request))
    (str index "\tversion\t" (:version response))

    (contains? response :ok)
    (if (#{:assert-batch :assert-batch-at-version} (:op request))
      (str index "\tok\t" (:ok response)
           "\twritten=" (comma-list (:written response))
           "\tidempotent=" (comma-list (:idempotent response)))
      (str index "\tok\t" (:ok response)))

    (contains? response :reject)
    (str index "\treject\t" (:version response)
         "\treason=" (reject-reason (:reject response))
         (when-let [code (:code response)] (str "\tcode=" (name code)))
         (when (contains? response :at) (str "\tat=" (:at response)))
         (when-let [pred (:pred response)] (str "\tpred=" pred)))

    :else
    (fail! "daemon returned an unrecognized response"
           {:index index :request request :response response})))

(let [[corpus-path port-text & extra] *command-line-args*]
  (when (or (nil? corpus-path) (nil? port-text) (seq extra))
    (binding [*out* *err*]
      (println "usage: bb tests/zig_occ_oracle_driver.clj CORPUS PORT"))
    (System/exit 2))
  (let [port (parse-integer port-text :daemon-port)
        lines (->> (str/split-lines (slurp corpus-path))
                   (remove str/blank?)
                   vec)
        raw (atom [])]
    (doseq [[index line] (map-indexed vector lines)]
      (let [parsed (parse-line line)
            response (request port parsed)]
        (swap! raw conj {:index index :request parsed :response response})
        (println (normalize-response index parsed response))))
    (let [facts-response (request port {:op :facts})]
      (swap! raw conj {:request {:op :facts} :response facts-response})
      (println (str "final-version\t" (:version facts-response)))
      (doseq [[l p r] (:facts facts-response)
              :when (not (contains? schema-predicates p))]
        (println (str "fact\t" l "\t" p "\t" r))))
    (when-let [raw-path (System/getenv "FRAM_ORACLE_RAW_PATH")]
      (spit raw-path (str (str/join "\n" (map pr-str @raw)) "\n")))))

