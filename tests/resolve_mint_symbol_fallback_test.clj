(require '[fram.store :as c]
         '[resolve-mint :as rmi])

(def failures (atom 0))

(defn- check! [label expected actual]
  (let [ok? (= expected actual)]
    (println (if ok? "PASS" "FAIL") label)
    (when-not ok?
      (println "  expected:" (pr-str expected))
      (println "  actual:  " (pr-str actual))
      (swap! failures inc))))

(def ctx (c/new-store))
(def tx (c/begin-tx! ctx "resolve-mint-symbol-fallback-test"))
(def KIND (c/value! ctx "kind"))
(def Vp (c/value! ctx "v"))
(def BOUND (c/value! ctx "bound_to"))
(def REFERS (c/value! ctx "refers_to"))
(def FIXED (c/value! ctx "keep_spelling"))
(def QUALIFIER (c/value! ctx "qualifier"))

(defn- symbol! [spelling]
  (let [e (c/entity! ctx)]
    (c/fact! ctx e KIND (c/value! ctx "symbol") tx)
    [e (c/fact! ctx e Vp (c/value! ctx spelling) tx)]))

(def emit
  (rmi/->Emit ctx nil BOUND REFERS FIXED {} identity identity #{} #{}))
(def emit-line (ns-resolve 'resolve-mint 'emit-line))

(let [[leaf v-cid] (symbol! "zig/getenv")
      external-target (c/entity! ctx)]
  (c/fact! ctx leaf REFERS external-target tx)
  (c/fact! ctx leaf QUALIFIER (c/value! ctx "must-not-prefix") tx)
  (check! "unresolved external target preserves the quoted leaf spelling"
          (str "[" leaf " \"v\" \"zig/getenv\"]")
          (emit-line emit nil leaf v-cid)))

(let [[leaf v-cid] (symbol! "old-name")
      [binding _] (symbol! "renamed-name")]
  (c/fact! ctx leaf REFERS binding tx)
  (check! "resolved binding identity still projects its renamed spelling"
          (str "[" leaf " \"v\" \"renamed-name\"]")
          (emit-line emit nil leaf v-cid)))

(when (pos? @failures)
  (System/exit 1))
