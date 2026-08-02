(ns fram-fast
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [coord-daemon-wire :as wire]
            [fram.rt :as rt]
            [fram.types :as t]))

;; Public data commands speak only FRAMRPC. EDN exists here solely as the
;; human command-line syntax and is lowered to Terms before any socket opens.

(defn- coord-port []
  (if-let [port (System/getenv "FRAM_PORT")]
    (Integer/parseInt port)
    7977))

(defn- term-datum [value]
  (cond
    (t/triple? value)
    [(term-datum (t/triple-slot0 value))
     (term-datum (t/triple-slot1 value))
     (term-datum (t/triple-slot2 value))]
    (t/instant? value)
    {:instant [(t/instant-epoch-seconds value) (t/instant-nanos value)]}
    :else value))

(defn- render-term [value]
  (if (string? value) value (pr-str (term-datum value))))

(defn- parse-subject! [text]
  (if (or (str/starts-with? text "@")
          (str/starts-with? text "[")
          (str/starts-with? text "{")
          (str/starts-with? text "\"")
          (str/starts-with? text ":"))
    (rt/parse-human-term! text)
    (str "@" text)))

(defn- response-error-message [response]
  (when-let [error (rt/native-error response)]
    (str (name (t/rpcerror-code error)) ": " (t/rpcerror-message error))))

(defn- version! []
  (let [response (rt/native-call! (coord-port) :rpc/version wire/rpc-unit)]
    (rt/require-native-success! response)
    (t/rpcresponse-served-version response)))

(defn- mutation-results! [response]
  (rt/require-native-success! response)
  (let [[results]
        (rt/rpc-record-fields! (rt/native-payload response)
                               :rpc/mutation-result 1)]
    (mapv #(rt/rpc-record-fields! % :rpc/action-result 3)
          (rt/rpc-list-values! results))))

(defn- write-once! [operation proposition policy]
  (let [base (version!)
        response
        (rt/native-call!
         (coord-port) (rt/rpc-space-id) operation
         (wire/rpc-write! proposition policy nil) base nil nil)]
    response))

(defn- write-retrying! [operation proposition policy]
  (loop [remaining 5]
    (let [response (write-once! operation proposition policy)]
      (if (and (= :rpc/conflict (rt/native-error-code response))
               (pos? remaining))
        (recur (dec remaining))
        response))))

(defn fast-write! [operation subject predicate value policy]
  (let [proposition (t/triple subject predicate value)
        response (write-retrying! operation proposition policy)]
    (if-let [message (response-error-message response)]
      (do (println (str "REJECTED by coordinator: " message)) false)
      (let [[[input-index changed occurrences]] (mutation-results! response)]
        (println
         (str (if changed "committed" "no change")
              " via coordinator (v" (t/rpcresponse-served-version response) "): "
              (render-term subject) " " (render-term predicate) " = "
              (render-term value)
              " [input " input-index ", occurrences "
              (count (rt/rpc-list-values! occurrences)) "]"))
        true))))

(defn fast-show! [subject]
  (let [response
        (rt/native-call!
         (coord-port) :rpc/scan
         (wire/rpc-triple-pattern! subject nil nil))]
    (rt/require-native-success! response)
    (let [[values] (rt/rpc-record-fields! (rt/native-payload response)
                                          :rpc/triples 1)
          triples (rt/rpc-list-values! values)]
      (if (empty? triples)
        (println (str "no triples for " (render-term subject)))
        (doseq [triple triples]
          (println (str "  " (render-term (t/triple-slot1 triple))
                        "  " (render-term (t/triple-slot2 triple))))))
      true)))

(defn- parse-query! [text]
  (let [value (edn/read-string text)]
    (when-not (map? value)
      (throw (ex-info "query must be one EDN map"
                      {:type :query-invalid-syntax})))
    value))

(defn fast-query! [query-text]
  (let [response
        (rt/native-call!
         (coord-port) :rpc/query
         (rt/native-query-payload! (parse-query! query-text)))]
    (rt/require-native-success! response)
    (let [[rows] (rt/rpc-record-fields! (rt/native-payload response)
                                        :query/rows 1)
          values (rt/rpc-list-values! rows)]
      (if (empty? values)
        (println "  (no results)")
        (doseq [row values]
          (let [[items] (rt/rpc-record-fields! row :query/row 1)]
            (println (str "  " (pr-str (mapv term-datum
                                               (rt/rpc-list-values! items))))))))
      true)))

(defn- show-status! []
  (let [response (rt/native-call! (coord-port) :rpc/status wire/rpc-unit)]
    (rt/require-native-success! response)
    (let [[state live-count engine _]
          (rt/rpc-record-fields! (rt/native-payload response) :rpc/status 4)]
      (println (str "up|" (t/rpcresponse-served-version response)
                    "|" live-count "|" (name state) "|" (name engine))))))

(defn- validate! []
  (let [response (rt/native-call! (coord-port) :rpc/validate wire/rpc-unit)]
    (rt/require-native-success! response)
    (let [[valid violations]
          (rt/rpc-record-fields! (rt/native-payload response)
                                 :rpc/validation 2)
          values (rt/rpc-list-values! violations)]
      (doseq [violation values]
        (println (str (if valid "advisory: " "violation: ")
                      (render-term violation))))
      (if valid
        (println "valid")
        nil)
      valid)))

(defn- scan! [arguments]
  (when-not (= 3 (count arguments))
    (throw (ex-info "usage: fram scan SLOT0|_ SLOT1|_ SLOT2|_" {})))
  (let [slots (mapv #(when-not (= "_" %) (rt/parse-human-term! %)) arguments)
        response (rt/native-call! (coord-port) :rpc/scan
                                  (apply wire/rpc-triple-pattern! slots))]
    (rt/require-native-success! response)
    (let [[values] (rt/rpc-record-fields! (rt/native-payload response)
                                          :rpc/triples 1)]
      (doseq [triple (rt/rpc-list-values! values)]
        (println (pr-str (term-datum triple)))))))

(defn- occurrences! []
  (let [response (rt/native-call! (coord-port) :rpc/occurrences wire/rpc-unit)]
    (rt/require-native-success! response)
    (let [[values]
          (rt/rpc-record-fields! (rt/native-payload response)
                                 :rpc/occurrences 1)]
      (doseq [occurrence (rt/rpc-list-values! values)]
        (println (pr-str (term-datum occurrence)))))))

(defn -main [& args]
  (let [command (first args)]
    (cond
      (= command "version") (println (version!))
      (= command "status") (show-status!)
      (= command "validate") (when-not (validate!) (System/exit 1))
      (= command "show")
      (do
        (when-not (= 2 (count args))
          (throw (ex-info "usage: fram show SUBJECT" {})))
        (fast-show! (parse-subject! (second args))))
      (= command "query")
      (do
        (when-not (= 2 (count args))
          (throw (ex-info "usage: fram query EDN-QUERY" {})))
        (fast-query! (second args)))
      (= command "scan") (scan! (vec (rest args)))
      (= command "occurrences") (occurrences!)
      (contains? #{"tell" "retract" "untell" "tell-existing"
                   "retract-existing" "untell-existing"} command)
      (do
        (when-not (= 4 (count args))
          (throw (ex-info
                  "usage: fram tell|retract SUBJECT SLOT VALUE"
                  {})))
        (let [assert? (contains? #{"tell" "tell-existing"} command)
              existing? (str/ends-with? command "-existing")
              ok? (fast-write!
                   (if assert? :rpc/assert :rpc/retract)
                   (parse-subject! (nth args 1))
                   (rt/parse-human-term! (nth args 2))
                   (rt/parse-human-term! (nth args 3))
                   (if existing?
                     wire/rpc-subject-existing
                     wire/rpc-subject-any))]
          (when-not ok? (System/exit 1))))
      :else
      (throw (ex-info (str "unsupported native data command: " command)
                      {:type :unsupported-command})))))

(when (= *file* (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch Throwable error
      (binding [*out* *err*]
        (println (str "fram: " (or (.getMessage error) (class error)))))
      (System/exit 1))))
