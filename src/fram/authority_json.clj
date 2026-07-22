(ns fram.authority-json
  (:require [cheshire.core :as json]
            [cheshire.factory :as factory]))

;; Host boundary: Beagle cannot mark Cheshire's external dynamic factory var as
;; rebindable. This preflight consumes exactly one JSON value and detects decoded
;; duplicate names before any assoc; Cheshire then performs the actual decode
;; through a second, Jackson-level duplicate detector.
(def ^:private strict-factory
  (factory/make-json-factory
   {:strict-duplicate-detection true
    :allow-unquoted-control-chars false}))

(defn- preflight! [raw]
  (let [index (volatile! 0)
        length (count raw)]
    (letfn [(fail! [message]
              (throw (ex-info message {:offset @index})))
            (current []
              (when (< @index length) (.charAt raw @index)))
            (advance! []
              (vswap! index inc))
            (advance-by! [amount]
              (vswap! index + amount))
            (skip-whitespace! []
              (while (contains? #{\space \tab \return \newline} (current))
                (advance!)))
            (digit? [character]
              (and (some? character) (<= (int \0) (int character) (int \9))))
            (nonzero-digit? [character]
              (and (some? character) (<= (int \1) (int character) (int \9))))
            (high-surrogate? [unit]
              (<= 0xd800 unit 0xdbff))
            (low-surrogate? [unit]
              (<= 0xdc00 unit 0xdfff))
            (read-hex-unit! []
              (let [stop (+ @index 4)]
                (when (> stop length) (fail! "truncated Unicode escape"))
                (let [digits (subs raw @index stop)]
                  (when-not (re-matches #"[0-9a-fA-F]{4}" digits)
                    (fail! "invalid Unicode escape"))
                  (advance-by! 4)
                  (Integer/parseInt digits 16))))
            (parse-string! []
              (when-not (= \" (current)) (fail! "expected JSON string"))
              (advance!)
              (loop [parts []]
                (when (>= @index length) (fail! "unterminated JSON string"))
                (let [character (current)]
                  (cond
                    (= character \")
                    (do (advance!) (apply str parts))

                    (= character \\)
                    (do
                      (advance!)
                      (when (>= @index length) (fail! "unterminated JSON escape"))
                      (let [escape (current)]
                        (advance!)
                        (if-let [decoded (get {\" "\"" \\ "\\" \/ "/"
                                               \b "\b" \f "\f" \n "\n"
                                               \r "\r" \t "\t"}
                                              escape)]
                          (recur (conj parts decoded))
                          (if (= escape \u)
                            (let [first-unit (read-hex-unit!)]
                              (cond
                                (low-surrogate? first-unit)
                                (fail! "lone low surrogate")

                                (high-surrogate? first-unit)
                                (do
                                  (when-not (and (= \\ (current))
                                                 (< (inc @index) length)
                                                 (= \u (.charAt raw (inc @index))))
                                    (fail! "lone high surrogate"))
                                  (advance-by! 2)
                                  (let [second-unit (read-hex-unit!)]
                                    (when-not (low-surrogate? second-unit)
                                      (fail! "high surrogate without low surrogate"))
                                    (recur (conj parts
                                                 (str (char first-unit)
                                                      (char second-unit))))))

                                :else
                                (recur (conj parts (str (char first-unit))))))
                            (fail! "invalid JSON escape")))))

                    (<= (int character) 0x1f)
                    (fail! "raw control character in JSON string")

                    (low-surrogate? (int character))
                    (fail! "lone low surrogate")

                    (high-surrogate? (int character))
                    (let [next-index (inc @index)]
                      (when (or (>= next-index length)
                                (not (low-surrogate? (int (.charAt raw next-index)))))
                        (fail! "lone high surrogate"))
                      (let [pair (subs raw @index (+ @index 2))]
                        (advance-by! 2)
                        (recur (conj parts pair))))

                    :else
                    (do
                      (advance!)
                      (recur (conj parts (str character))))))))
            (parse-number! []
              (when (= \- (current)) (advance!))
              (cond
                (= \0 (current)) (advance!)
                (nonzero-digit? (current))
                (do (advance!) (while (digit? (current)) (advance!)))
                :else (fail! "invalid JSON number"))
              (when (= \. (current))
                (advance!)
                (when-not (digit? (current)) (fail! "invalid JSON fraction"))
                (while (digit? (current)) (advance!)))
              (when (contains? #{\e \E} (current))
                (advance!)
                (when (contains? #{\+ \-} (current)) (advance!))
                (when-not (digit? (current)) (fail! "invalid JSON exponent"))
                (while (digit? (current)) (advance!))))
            (parse-array! []
              (advance!)
              (skip-whitespace!)
              (if (= \] (current))
                (advance!)
                (loop []
                  (parse-value!)
                  (skip-whitespace!)
                  (cond
                    (= \] (current)) (advance!)
                    (= \, (current)) (do (advance!) (recur))
                    :else (fail! "expected comma or closing bracket")))))
            (parse-object! []
              (advance!)
              (skip-whitespace!)
              (if (= \} (current))
                (advance!)
                (loop [seen #{}]
                  (skip-whitespace!)
                  (let [name (parse-string!)]
                    (when (contains? seen name)
                      (fail! "duplicate JSON object name"))
                    (skip-whitespace!)
                    (when-not (= \: (current)) (fail! "expected colon"))
                    (advance!)
                    (parse-value!)
                    (skip-whitespace!)
                    (cond
                      (= \} (current)) (advance!)
                      (= \, (current)) (do (advance!) (recur (conj seen name)))
                      :else (fail! "expected comma or closing brace"))))))
            (parse-value! []
              (skip-whitespace!)
              (cond
                (= \" (current)) (parse-string!)
                (= \[ (current)) (parse-array!)
                (= \{ (current)) (parse-object!)
                (and (<= (+ @index 4) length)
                     (= "true" (subs raw @index (+ @index 4))))
                (advance-by! 4)
                (and (<= (+ @index 5) length)
                     (= "false" (subs raw @index (+ @index 5))))
                (advance-by! 5)
                (and (<= (+ @index 4) length)
                     (= "null" (subs raw @index (+ @index 4))))
                (advance-by! 4)
                (or (= \- (current)) (digit? (current))) (parse-number!)
                :else (fail! "expected JSON value")))]
      (parse-value!)
      (skip-whitespace!)
      (when-not (= @index length) (fail! "trailing JSON input")))))

(defn decode-strict [raw]
  (preflight! raw)
  (binding [factory/*json-factory* strict-factory]
    (json/parse-string-strict raw false)))
