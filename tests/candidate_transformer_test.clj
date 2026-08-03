;; Pure candidate construction: no coordinator, socket, log, or filesystem mutation.
;;   cd ~/code/fram/main && bb -cp ~/code/fram/main/out ~/code/fram/main/tests/candidate_transformer_test.clj
(require '[clojure.set :as set]
         '[fram.candidate-transformer :as candidate])

(def checks (atom []))
(defn check! [name value]
  (swap! checks conj [name (boolean value)]))

(def alpha-facts
  #{["@demo#1" "kind" "list"]
    ["@demo#1" "f0" "@demo#2"]
    ["@demo#1" "f1" "@demo#10"]
    ["@demo#1" "f2" "@demo#30"]
    ["@demo#2" "kind" "symbol"]
    ["@demo#2" "v" "beagle-file"]
    ["@demo#10" "kind" "list"]
    ["@demo#10" "f0" "@demo#11"]
    ["@demo#10" "f1" "@demo#12"]
    ["@demo#10" "f2" "@demo#13"]
    ["@demo#10" "f3" "@demo#16"]
    ["@demo#10" "f4" "@demo#17"]
    ["@demo#10" "f5" "@demo#18"]
    ["@demo#11" "kind" "symbol"]
    ["@demo#11" "v" "defn"]
    ["@demo#12" "kind" "symbol"]
    ["@demo#12" "v" "alpha"]
    ["@demo#13" "kind" "list"]
    ["@demo#13" "f0" "@demo#14"]
    ["@demo#14" "kind" "symbol"]
    ["@demo#14" "v" "#%brackets"]
    ["@demo#16" "kind" "symbol"]
    ["@demo#16" "v" ":-"]
    ["@demo#17" "kind" "symbol"]
    ["@demo#17" "v" "Int"]
    ["@demo#18" "kind" "number"]
    ["@demo#18" "v" "0"]})

(def beta-facts
  #{["@demo#30" "kind" "list"]
    ["@demo#30" "f0" "@demo#31"]
    ["@demo#30" "f1" "@demo#40"]
    ["@demo#31" "kind" "symbol"]
    ["@demo#31" "v" "js/export"]
    ["@demo#40" "kind" "list"]
    ["@demo#40" "f0" "@demo#41"]
    ["@demo#40" "f1" "@demo#42"]
    ["@demo#40" "f2" "@demo#50"]
    ["@demo#40" "f3" "@demo#53"]
    ["@demo#41" "kind" "symbol"]
    ["@demo#41" "v" "defn"]
    ["@demo#42" "kind" "list"]
    ["@demo#42" "f0" "@demo#43"]
    ["@demo#42" "f1" "@demo#44"]
    ["@demo#42" "f2" "@demo#45"]
    ["@demo#43" "kind" "symbol"]
    ["@demo#43" "v" "#%meta"]
    ["@demo#44" "kind" "symbol"]
    ["@demo#44" "v" ":private"]
    ["@demo#45" "kind" "symbol"]
    ["@demo#45" "v" "beta"]
    ["@demo#50" "kind" "list"]
    ["@demo#50" "f0" "@demo#51"]
    ["@demo#51" "kind" "symbol"]
    ["@demo#51" "v" "#%brackets"]
    ["@demo#53" "kind" "number"]
    ["@demo#53" "v" "1"]})

(def unrelated-facts
  #{["@demo#90" "line" "@demo#89"]
    ["@demo.extra#999" "kind" "list"]
    ["@demo.extra#999" "f0" "@demo.extra#1000"]})

(def base-facts (set/union alpha-facts beta-facts unrelated-facts))
(def snapshot {:version 77 :module "demo" :facts base-facts})
(def result
  (candidate/multi-set-body
   snapshot
   [{:name "alpha" :body '(+ 40 2)}
    {:name "beta" :body (with-meta 'answer {:tag 'String})}]))

(check! "base snapshot is unchanged" (= base-facts (:facts snapshot)))
(check! "candidate preserves the pinned base version" (= 77 (:base-version result)))
(check! "exact definition identities survive the candidate"
        (= [{:name "alpha" :form "@demo#10" :definition "@demo#10"}
            {:name "beta" :form "@demo#30" :definition "@demo#40"}]
           (:definition-identities result)))
(check! "metadata-wrapped definition name is unwrapped"
        (contains? (:retracts result) ["@demo#40" "f3" "@demo#53"]))
(check! "typed defn body begins after return type"
        (contains? (:retracts result) ["@demo#10" "f5" "@demo#18"]))
(check! "numeric minting starts above the exact module maximum"
        (contains? (:asserts result) ["@demo#91" "kind" "list"]))
(check! "similar module prefixes do not move the mint counter"
        (not-any? #(some #{"@demo#1001"} %) (:asserts result)))
(check! "new parent edges retain original definition identity"
        (and (contains? (:asserts result) ["@demo#10" "f5" "@demo#91"])
             (some #(= ["@demo#40" "f3"] (subvec % 0 2)) (:asserts result))))
(check! "reader metadata mints a #%meta wrapper"
        (some (fn [[_ predicate object]]
                (and (= predicate "v") (= object "#%meta")))
              (:asserts result)))
(check! "asserts are the exact candidate-minus-base net"
        (= (:asserts result) (set/difference (:ast result) base-facts)))
(check! "retracts are the exact base-minus-candidate net"
        (= (:retracts result) (set/difference base-facts (:ast result))))
(check! "replaying the net delta reconstructs the candidate"
        (= (:ast result)
           (-> base-facts
               (set/difference (:retracts result))
               (set/union (:asserts result)))))

(let [results @checks
      failures (filterv (comp not second) results)]
  (doseq [[name ok] results]
    (println (if ok "  [PASS] " "  [FAIL] ") name))
  (if (empty? failures)
    (println "\ncandidate transformer:" (count results) "/" (count results) "PASS")
    (do
      (println "\ncandidate transformer:" (count failures) "FAILED of" (count results))
      (System/exit 1))))
