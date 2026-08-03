;; Full-text relation semantics and boundary validation.
(require '[fram.query :as q]
         '[fram.text-index :as text-index]
         '[fram.types :as t])

(def checks (atom []))
(defn chk [name ok] (swap! checks conj [name ok]))

(def propositions
  [(t/triple "@a" "title" "The QUICK-brown_fox 42")
   (t/triple "@b" "title" "quick turtle")
   (t/triple "@c" "summary" "Café noir café 42")
   (t/triple "@needle" "value" "FOX quick")
   (t/triple "@n" "count" 999)])

(defn text-query [needle]
  {:find "hit"
   :rules [{:head {:rel "hit" :args [{:var "e"} {:var "a"}]}
            :body [{:rel "text-match"
                    :args [{:var "e"} {:var "a"} needle]}]}]})

(defn result [form] (q/run-syntax! propositions form))
(defn rows [form] (set (q/result-rows (result form))))
(defn error-codes [form]
  (set (map q/error-code (q/result-errors (result form)))))

(chk "tokenizer folds case and splits punctuation, underscore, and hyphen"
     (= ["42" "brown" "fox" "quick" "the"]
        (text-index/tokenize "The QUICK-brown_fox 42")))
(chk "tokenizer keeps Unicode letters and deduplicates repeated terms"
     (= ["42" "café" "noir"]
        (text-index/tokenize "Café noir café 42")))
(chk "one-token query returns both matching string propositions"
     (= #{["@a" "title"] ["@b" "title"] ["@needle" "value"]}
        (rows (text-query "QUICK"))))
(chk "multi-token query is conjunction independent of order"
     (= #{["@a" "title"] ["@needle" "value"]}
        (rows (text-query "fox quick"))))
(chk "repeated query tokens do not change conjunction semantics"
     (= #{["@c" "summary"]} (rows (text-query "CAFÉ café 42"))))
(chk "non-string values are absent from the virtual relation"
     (empty? (rows (text-query "999"))))

(def bound-needle-query
  {:find "bound-hit"
   :rules [{:head {:rel "bound-hit" :args [{:var "e"}]}
            :body [{:rel "triple"
                    :args ["@needle" "value" {:var "needle"}]}
                   {:rel "text-match"
                    :args [{:var "e"} {:var "a"} {:var "needle"}]}]}]})
(chk "an already-bound string needle is accepted"
     (= #{["@a"] ["@needle"]} (rows bound-needle-query)))
(chk "an unbound needle variable is rejected"
     (contains? (error-codes (text-query {:var "needle"}))
                :query-text-unbound-needle))
(chk "negative text-match is rejected"
     (contains?
      (error-codes
       {:find "bad"
        :rules [{:head {:rel "bad" :args [{:var "e"}]}
                 :body [{:rel "text-match" :neg true
                         :args [{:var "e"} {:var "a"} "quick"]}]}]})
      :query-text-negative))
(chk "empty-token needle is rejected"
     (contains? (error-codes (text-query "_ -- !!!"))
                :query-text-invalid-needle))

(let [bad (remove second @checks)]
  (doseq [[name ok] @checks]
    (println (str "  [" (if ok "PASS" "FAIL") "] " name)))
  (if (empty? bad)
    (println (str "\ntext-match: " (count @checks) "/" (count @checks) " PASS"))
    (do (println (str "\ntext-match: " (count bad) " FAILED"))
        (System/exit 1))))
