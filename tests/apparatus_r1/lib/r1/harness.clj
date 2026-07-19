;; Minimal verdict harness for the R-1 apparatus. Every cell asserts an EXPECTED
;; verdict; an UNEXPECTED verdict is recorded and forces a nonzero process exit,
;; so the apparatus itself fails loud when a model behaves unlike the contract.
(ns r1.harness)

(def state (atom {:pass 0 :fail 0 :lines []}))

(defn- log! [ok? line]
  (swap! state (fn [s]
                 (-> s
                     (update (if ok? :pass :fail) inc)
                     (update :lines conj (str (if ok? "  PASS " "  FAIL ") line))))))

(defn check!
  "Assert actual = expected. label describes the cell/claim."
  [label expected actual]
  (let [ok? (= expected actual)]
    (log! ok? (str label " — expected=" (pr-str expected) " actual=" (pr-str actual)))
    ok?))

(defn note [line] (swap! state update :lines conj (str "  ---- " line)))

(defn section [title] (swap! state update :lines conj (str "\n== " title " ==")))

(defn finish!
  "Print the report and exit nonzero if any check failed."
  []
  (let [{:keys [pass fail lines]} @state]
    (doseq [l lines] (println l))
    (println (format "\nRESULT %d passed, %d failed" pass fail))
    (when (pos? fail)
      (println "APPARATUS VERDICT: UNEXPECTED — exiting nonzero")
      (System/exit 1))
    (println "APPARATUS VERDICT: all cells matched contract expectation")))
