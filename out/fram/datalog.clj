(ns fram.datalog
  (:require [fram.kernel :as kernel]
            [fram.types :as t]))

^{:line 12 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defrecord QueryTerm [variable value])

(defn queryterm-variable [r] (:variable r))

(defn queryterm-value [r] (:value r))

^{:line 13 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defrecord Literal [kind relation arguments negated operator binding])

(defn literal-kind [r] (:kind r))

(defn literal-relation [r] (:relation r))

(defn literal-arguments [r] (:arguments r))

(defn literal-negated [r] (:negated r))

(defn literal-operator [r] (:operator r))

(defn literal-binding [r] (:binding r))

^{:line 16 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defrecord Rule [head-relation head-arguments body])

(defn rule-head-relation [r] (:head-relation r))

(defn rule-head-arguments [r] (:head-arguments r))

(defn rule-body [r] (:body r))

^{:line 18 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defrecord QueryControl [steps cancelled max-steps deadline-ns timeout-ms])

(defn querycontrol-steps [r] (:steps r))

(defn querycontrol-cancelled [r] (:cancelled r))

(defn querycontrol-max-steps [r] (:max-steps r))

(defn querycontrol-deadline-ns [r] (:deadline-ns r))

(defn querycontrol-timeout-ms [r] (:timeout-ms r))

^{:line 25 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def ^String triple-relation "triple")

^{:line 26 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def ^String occurrence-relation "occurrence")

^{:line 27 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def base-relations ^{:line 27 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{triple-relation occurrence-relation})

^{:line 28 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def comparison-operators ^{:line 28 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{:eq :ne :lt :le :gt :ge})

^{:line 29 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def subtract-operator ^{:line 29 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (keyword "-"))

^{:line 30 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def builtin-operators ^{:line 31 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{:+ subtract-operator :* :/ :mod})

^{:line 33 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def ^:dynamic *query-control* nil)

^{:line 35 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^QueryControl query-control [max-steps timeout-ms]
  ^{:line 36 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 36 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (and ^{:line 36 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (> max-steps 0) ^{:line 36 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= timeout-ms 0)) ^{:line 37 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->QueryControl ^{:line 37 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (atom 0) ^{:line 37 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (atom nil) max-steps ^{:line 38 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (+ ^{:line 38 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (System/nanoTime) ^{:line 38 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (* timeout-ms 1000000)) timeout-ms) ^{:line 39 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 39 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: query limits must be positive steps and non-negative milliseconds" ^{:line 40 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :invalid-query-control}))))

^{:line 42 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn cancel-query! [^QueryControl control reason]
  ^{:line 43 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reset! ^{:line 43 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-cancelled control) reason)
  nil)

^{:line 46 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn query-steps [^QueryControl control]
  ^{:line 47 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (deref ^{:line 47 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-steps control)))

^{:line 49 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- query-check! []
  ^{:line 50 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [control *query-control*]
  ^{:line 51 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 51 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? control) nil ^{:line 53 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [steps ^{:line 53 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (swap! ^{:line 53 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-steps control) inc)
   now ^{:line 54 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (System/nanoTime)
   cancelled ^{:line 55 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (deref ^{:line 55 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-cancelled control))
   code ^{:line 56 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 57 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? cancelled) :query-cancelled
  ^{:line 58 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (> steps ^{:line 58 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-max-steps control)) :query-work-limit
  ^{:line 59 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= now ^{:line 59 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-deadline-ns control)) :query-time-limit
  :else nil)]
  ^{:line 61 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 61 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? code) nil ^{:line 63 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 63 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info ^{:line 63 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (str "query evaluation stopped: " ^{:line 63 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (name code)) ^{:line 64 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :fram-query-abort :code code :reason cancelled :steps steps :max-steps ^{:line 64 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-max-steps control) :timeout-ms ^{:line 64 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (querycontrol-timeout-ms control)})))))))

^{:line 71 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^QueryTerm variable [^String name]
  ^{:line 72 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 72 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (pos? ^{:line 72 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count name)) ^{:line 73 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->QueryTerm name nil) ^{:line 74 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 74 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: query variable name must be non-empty" ^{:line 75 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :invalid-query-variable}))))

^{:line 77 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^QueryTerm constant [value]
  ^{:line 78 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 78 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/term? value) ^{:line 79 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->QueryTerm nil value) ^{:line 80 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 80 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: query constant must be a Term" ^{:line 81 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :invalid-query-constant}))))

^{:line 83 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Boolean query-term? [value]
  ^{:line 84 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (and ^{:line 84 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (instance? QueryTerm value) ^{:line 85 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 85 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? ^{:line 85 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-variable value)) ^{:line 86 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (and ^{:line 86 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (pos? ^{:line 86 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count ^{:line 86 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-variable value))) ^{:line 87 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? ^{:line 87 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value value))) ^{:line 88 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/term? ^{:line 88 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value value)))))

^{:line 90 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Literal relation-literal [^String relation arguments]
  ^{:line 92 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Literal :relation relation arguments false :none ""))

^{:line 94 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Literal negated-literal [^String relation arguments]
  ^{:line 96 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Literal :relation relation arguments true :none ""))

^{:line 98 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Literal comparison-literal [operator arguments]
  ^{:line 100 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Literal :comparison "" arguments false operator ""))

^{:line 102 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Literal builtin-literal [operator arguments ^String binding]
  ^{:line 104 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Literal :builtin "" arguments false operator binding))

^{:line 106 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Rule rule [^String head-relation head-arguments body]
  ^{:line 108 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Rule head-relation head-arguments body))

^{:line 110 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- triple-row [value]
  ^{:line 111 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [^{:line 111 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/triple-slot0 value) ^{:line 111 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/triple-slot1 value) ^{:line 111 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/triple-slot2 value)])

^{:line 113 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- rows [triples]
  ^{:line 114 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 114 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 115 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 115 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (triple-row value))) ^{:line 116 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} triples))

^{:line 120 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn edb [propositions]
  ^{:line 121 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {triple-relation ^{:line 121 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rows propositions)})

^{:line 123 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn edb-with-occurrences [propositions occurrences]
  ^{:line 125 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [checked ^{:line 126 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 126 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 127 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 127 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (kernel/operation-occurrence? value) ^{:line 128 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc value) ^{:line 129 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 129 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: occurrence relation accepts only operation occurrences" ^{:line 130 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :invalid-operation-occurrence})))) ^{:line 131 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] occurrences)]
  ^{:line 132 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {triple-relation ^{:line 132 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rows propositions) occurrence-relation ^{:line 132 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rows checked)}))

^{:line 135 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- term-value [^QueryTerm term subst]
  ^{:line 136 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [name ^{:line 136 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-variable term)]
  ^{:line 137 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 137 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? name) ^{:line 137 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get subst name) ^{:line 137 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value term))))

^{:line 139 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- unify [^QueryTerm term value subst]
  ^{:line 141 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [name ^{:line 141 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-variable term)]
  ^{:line 142 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 142 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? name) ^{:line 143 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 143 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? subst name) ^{:line 144 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 144 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 144 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get subst name) value) subst nil) ^{:line 145 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (assoc subst name value)) ^{:line 146 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 146 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 146 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value term) value) subst nil))))

^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- unify-arguments! [arguments tuple subst]
  ^{:line 150 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (query-check!)
  ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (not ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count arguments) ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count tuple))) nil ^{:line 153 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [position 0
   current subst]
  ^{:line 154 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 154 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 154 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? current) ^{:line 154 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= position ^{:line 154 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count arguments))) current ^{:line 156 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur ^{:line 156 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (+ position 1) ^{:line 157 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (unify ^{:line 157 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth arguments position) ^{:line 158 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth tuple position) current))))))

^{:line 161 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- ground [arguments subst]
  ^{:line 162 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (mapv ^{:line 162 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [term] ^{:line 163 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [value ^{:line 163 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (term-value term subst)]
  ^{:line 164 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 164 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? value) value ^{:line 166 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 166 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: unbound query variable reached evaluation" ^{:line 167 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :unbound-query-variable}))))) arguments))

^{:line 170 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- integer-value [value]
  ^{:line 171 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 172 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (integer? value) value
  ^{:line 173 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (string? value) ^{:line 173 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (parse-long value)
  :else nil))

^{:line 176 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- numeric-value [value]
  ^{:line 177 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 178 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (integer? value) ^{:line 178 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (double value)
  ^{:line 179 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (number? value) ^{:line 179 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (double value)
  ^{:line 180 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (string? value) ^{:line 181 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [integer-result ^{:line 181 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (parse-long value)]
  ^{:line 182 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 182 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? integer-result) ^{:line 183 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (double integer-result) ^{:line 184 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (parse-double value)))
  :else nil))

^{:line 187 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- ^Boolean comparison-result [^Literal literal subst]
  ^{:line 188 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [arguments ^{:line 188 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-arguments literal)
   left ^{:line 189 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (term-value ^{:line 189 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth arguments 0) subst)
   right ^{:line 190 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (term-value ^{:line 190 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth arguments 1) subst)
   operator ^{:line 191 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-operator literal)]
  ^{:line 192 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 192 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 192 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? left) ^{:line 192 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? right)) false ^{:line 194 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 195 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :eq) ^{:line 195 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= left right)
  ^{:line 196 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :ne) ^{:line 196 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (not ^{:line 196 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= left right))
  :else ^{:line 198 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [left-number ^{:line 198 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (numeric-value left)
   right-number ^{:line 199 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (numeric-value right)]
  ^{:line 200 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 200 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 200 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? left-number) ^{:line 200 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? right-number)) false ^{:line 202 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 203 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :lt) ^{:line 203 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (< left-number right-number)
  ^{:line 204 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :le) ^{:line 204 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (<= left-number right-number)
  ^{:line 205 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :gt) ^{:line 205 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (> left-number right-number)
  ^{:line 206 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :ge) ^{:line 206 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= left-number right-number)
  :else false)))))))

^{:line 209 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- builtin-value [operator left right]
  ^{:line 210 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [left-int ^{:line 210 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (integer-value left)
   right-int ^{:line 211 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (integer-value right)
   left-number ^{:line 212 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (numeric-value left)
   right-number ^{:line 213 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (numeric-value right)]
  ^{:line 214 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 215 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :mod) ^{:line 216 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 216 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 216 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? left-int) ^{:line 216 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? right-int) ^{:line 216 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= right-int 0)) nil ^{:line 218 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (mod left-int right-int))
  ^{:line 219 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :/) ^{:line 220 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 220 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 220 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? left-number) ^{:line 220 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? right-number) ^{:line 220 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= right-number 0.0)) nil ^{:line 222 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (/ left-number right-number))
  ^{:line 223 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 223 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :+) ^{:line 223 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 223 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator subtract-operator) ^{:line 223 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :*))) ^{:line 224 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 224 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (and ^{:line 224 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? left-int) ^{:line 224 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? right-int)) ^{:line 225 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 226 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :+) ^{:line 226 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (+ left-int right-int)
  ^{:line 227 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator subtract-operator) ^{:line 227 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (- left-int right-int)
  :else ^{:line 228 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (* left-int right-int)) ^{:line 229 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 229 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 229 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? left-number) ^{:line 229 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? right-number)) nil ^{:line 231 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 232 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator :+) ^{:line 232 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (+ left-number right-number)
  ^{:line 233 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= operator subtract-operator) ^{:line 233 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (- left-number right-number)
  :else ^{:line 234 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (* left-number right-number))))
  :else nil)))

^{:line 237 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- builtin-results [^Literal literal subst]
  ^{:line 239 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [arguments ^{:line 239 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-arguments literal)
   left ^{:line 240 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (term-value ^{:line 240 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth arguments 0) subst)
   right ^{:line 241 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (term-value ^{:line 241 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth arguments 1) subst)]
  ^{:line 242 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 242 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 242 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? left) ^{:line 242 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? right)) ^{:line 243 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] ^{:line 244 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [result ^{:line 244 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (builtin-value ^{:line 244 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-operator literal) left right)]
  ^{:line 245 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 245 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? result) ^{:line 246 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] ^{:line 247 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [^{:line 247 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (assoc subst ^{:line 247 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-binding literal) result)])))))

^{:line 249 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- relation-results! [db ^Literal literal subst]
  ^{:line 251 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relation ^{:line 251 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-relation literal)
   arguments ^{:line 252 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-arguments literal)]
  ^{:line 253 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 253 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-negated literal) ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get db relation ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}) ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ground arguments subst)) ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [subst]) ^{:line 255 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 255 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc tuple] ^{:line 256 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [matched ^{:line 256 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (unify-arguments! arguments tuple subst)]
  ^{:line 257 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 257 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? matched) ^{:line 257 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc matched) acc))) ^{:line 258 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] ^{:line 258 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 258 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get db relation ^{:line 258 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}))))))

^{:line 260 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- literal-results! [db ^Literal literal subst]
  ^{:line 262 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (query-check!)
  ^{:line 263 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 264 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= :relation ^{:line 264 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-kind literal)) ^{:line 264 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (relation-results! db literal subst)
  ^{:line 265 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= :comparison ^{:line 265 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-kind literal)) ^{:line 266 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 266 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (comparison-result literal subst) ^{:line 266 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [subst] ^{:line 266 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [])
  ^{:line 267 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= :builtin ^{:line 267 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-kind literal)) ^{:line 267 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (builtin-results literal subst)
  :else ^{:line 268 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} []))

^{:line 270 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- body-results! [db body seed]
  ^{:line 272 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 273 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [substitutions literal] ^{:line 274 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 274 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc subst] ^{:line 275 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 275 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (concat acc ^{:line 275 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-results! db literal subst)))) ^{:line 276 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] substitutions)) ^{:line 277 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [seed] body))

^{:line 279 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- derive-rule! [db ^Rule value]
  ^{:line 280 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 280 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc subst] ^{:line 281 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 281 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ground ^{:line 281 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-arguments value) subst))) ^{:line 282 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} ^{:line 282 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (body-results! db ^{:line 282 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-body value) ^{:line 282 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {})))

^{:line 284 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- rule-head-relations [rules]
  ^{:line 285 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 285 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 285 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 286 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 286 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relation value))) ^{:line 287 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} rules)))

^{:line 289 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- derive-round! [db rules]
  ^{:line 290 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 291 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 292 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relation ^{:line 292 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relation value)
   derived ^{:line 293 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (derive-rule! db value)]
  ^{:line 294 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (update acc relation ^{:line 295 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [current] ^{:line 296 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 296 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [rows-value row] ^{:line 297 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj rows-value row)) ^{:line 298 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or current ^{:line 298 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}) derived))))) db rules))

^{:line 301 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- ^Boolean relations-stable? [before after relations]
  ^{:line 302 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [remaining relations]
  ^{:line 303 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 303 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (empty? remaining) true ^{:line 305 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relation ^{:line 305 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (first remaining)]
  ^{:line 306 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 306 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 306 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get before relation ^{:line 306 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}) ^{:line 306 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get after relation ^{:line 306 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{})) ^{:line 307 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur ^{:line 307 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rest remaining)) false)))))

^{:line 310 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn fixpoint! [db0 rules]
  ^{:line 311 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relations ^{:line 311 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relations rules)]
  ^{:line 312 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [db db0]
  ^{:line 313 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [next ^{:line 313 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (derive-round! db rules)]
  ^{:line 314 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 314 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (relations-stable? db next relations) next ^{:line 316 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur next))))))

^{:line 318 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn run-rules! [propositions rules]
  ^{:line 319 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fixpoint! ^{:line 319 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (edb propositions) rules))

^{:line 321 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn run-strata-db! [db0 strata]
  ^{:line 322 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 322 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [db stratum] ^{:line 322 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fixpoint! db stratum)) db0 strata))

^{:line 325 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn run-strata! [propositions strata]
  ^{:line 327 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (run-strata-db! ^{:line 327 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (edb propositions) strata))

^{:line 329 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- negated-relations [stratum]
  ^{:line 330 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 331 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 332 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 332 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [relations literal] ^{:line 333 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 333 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (and ^{:line 333 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= :relation ^{:line 333 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-kind literal)) ^{:line 334 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-negated literal)) ^{:line 335 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj relations ^{:line 335 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-relation literal)) relations)) acc ^{:line 337 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-body value))) ^{:line 338 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] stratum))

^{:line 340 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn strata-violations [strata]
  ^{:line 341 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [index 0
   lower base-relations
   problems ^{:line 341 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} []]
  ^{:line 342 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 342 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= index ^{:line 342 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count strata)) problems ^{:line 344 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [stratum ^{:line 344 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth strata index)
   heads ^{:line 346 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 346 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 347 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 347 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relation value))) ^{:line 348 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} stratum)
   problems2 ^{:line 350 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 351 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc relation] ^{:line 352 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 353 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? heads relation) ^{:line 354 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 354 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (str "stratum " index ": negated '" relation "' is also derived in the same stratum"))
  ^{:line 356 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (not ^{:line 356 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? lower relation)) ^{:line 357 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 357 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (str "stratum " index ": negated '" relation "' is not a base or lower-stratum relation"))
  :else acc)) problems ^{:line 360 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (negated-relations stratum))]
  ^{:line 361 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur ^{:line 361 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (+ index 1) ^{:line 362 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 362 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc relation] ^{:line 363 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc relation)) lower heads) problems2)))))

^{:line 367 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn facts [db ^String relation]
  ^{:line 368 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 368 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get db relation ^{:line 368 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{})))
