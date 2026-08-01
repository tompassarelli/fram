(ns fram.datalog
  (:require [fram.kernel :as kernel]
            [fram.types :as t]))

^{:line 11 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defrecord QueryTerm [variable value])

(defn queryterm-variable [r] (:variable r))

(defn queryterm-value [r] (:value r))

^{:line 12 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defrecord Literal [relation arguments negated])

(defn literal-relation [r] (:relation r))

(defn literal-arguments [r] (:arguments r))

(defn literal-negated [r] (:negated r))

^{:line 14 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defrecord Rule [head-relation head-arguments body])

(defn rule-head-relation [r] (:head-relation r))

(defn rule-head-arguments [r] (:head-arguments r))

(defn rule-body [r] (:body r))

^{:line 24 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def ^String triple-relation "triple")

^{:line 25 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def ^String occurrence-relation "occurrence")

^{:line 26 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def base-relations ^{:line 26 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{triple-relation occurrence-relation})

^{:line 28 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (def ^:dynamic *query-control* nil)

^{:line 30 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- query-check []
  ^{:line 31 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 31 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? *query-control*) nil ^{:line 33 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [steps ^{:line 33 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (.incrementAndGet ^{:line 33 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (:steps *query-control*))
   now ^{:line 34 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (System/nanoTime)
   cancelled ^{:line 35 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (deref ^{:line 35 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (:cancelled *query-control*))
   code ^{:line 36 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 37 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? cancelled) :query-cancelled
  ^{:line 38 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (> steps ^{:line 38 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (:max-steps *query-control*)) :query-work-limit
  ^{:line 39 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= now ^{:line 39 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (:deadline-ns *query-control*)) :query-time-limit
  :else nil)]
  ^{:line 41 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 41 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? code) nil ^{:line 43 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 43 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info ^{:line 43 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (str "query evaluation stopped: " ^{:line 43 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (name code)) ^{:line 44 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :fram-query-abort :code code :reason cancelled :steps steps :max-steps ^{:line 44 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (:max-steps *query-control*) :timeout-ms ^{:line 44 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (:timeout-ms *query-control*)}))))))

^{:line 51 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^QueryTerm variable [^String name]
  ^{:line 52 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 52 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (pos? ^{:line 52 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count name)) ^{:line 53 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->QueryTerm name nil) ^{:line 54 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 54 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: query variable name must be non-empty" ^{:line 55 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :invalid-query-variable}))))

^{:line 57 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^QueryTerm constant [value]
  ^{:line 58 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 58 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/term? value) ^{:line 59 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->QueryTerm nil value) ^{:line 60 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 60 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: query constant must be a Term" ^{:line 61 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :invalid-query-constant}))))

^{:line 63 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Boolean query-term? [value]
  ^{:line 64 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (and ^{:line 64 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (instance? QueryTerm value) ^{:line 65 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 65 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? ^{:line 65 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-variable value)) ^{:line 66 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? ^{:line 66 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value value)) ^{:line 67 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/term? ^{:line 67 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value value)))))

^{:line 69 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Literal relation-literal [^String relation arguments]
  ^{:line 71 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Literal relation arguments false))

^{:line 73 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Literal negated-literal [^String relation arguments]
  ^{:line 75 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Literal relation arguments true))

^{:line 77 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn ^Rule rule [^String head-relation head-arguments body]
  ^{:line 79 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (->Rule head-relation head-arguments body))

^{:line 81 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- triple-row [value]
  ^{:line 82 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [^{:line 82 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/triple-slot0 value) ^{:line 82 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/triple-slot1 value) ^{:line 82 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (t/triple-slot2 value)])

^{:line 84 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- rows [triples]
  ^{:line 85 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 85 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 86 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 86 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (triple-row value))) ^{:line 87 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} triples))

^{:line 91 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn edb [propositions]
  ^{:line 92 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {triple-relation ^{:line 92 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rows propositions)})

^{:line 94 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn edb-with-occurrences [propositions occurrences]
  ^{:line 96 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [checked ^{:line 97 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 97 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 98 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 98 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (kernel/operation-occurrence? value) ^{:line 99 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc value) ^{:line 100 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 100 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: occurrence relation accepts only operation occurrences" ^{:line 101 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :invalid-operation-occurrence})))) ^{:line 102 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] occurrences)]
  ^{:line 103 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {triple-relation ^{:line 103 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rows propositions) occurrence-relation ^{:line 103 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rows checked)}))

^{:line 106 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- term-value [^QueryTerm term subst]
  ^{:line 107 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [name ^{:line 107 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-variable term)]
  ^{:line 108 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 108 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? name) ^{:line 109 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get subst name) ^{:line 110 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value term))))

^{:line 112 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- unify [^QueryTerm term value subst]
  ^{:line 114 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [name ^{:line 114 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-variable term)]
  ^{:line 115 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 115 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? name) ^{:line 116 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 116 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? subst name) ^{:line 117 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 117 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 117 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get subst name) value) subst nil) ^{:line 118 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (assoc subst name value)) ^{:line 119 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 119 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 119 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (queryterm-value term) value) subst nil))))

^{:line 121 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- unify-arguments [arguments tuple subst]
  ^{:line 123 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (query-check)
  ^{:line 124 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 124 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (not ^{:line 124 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 124 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count arguments) ^{:line 124 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count tuple))) nil ^{:line 126 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [position 0
   current subst]
  ^{:line 127 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 127 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or ^{:line 127 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nil? current) ^{:line 127 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= position ^{:line 127 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count arguments))) current ^{:line 129 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur ^{:line 129 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (+ position 1) ^{:line 130 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (unify ^{:line 130 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth arguments position) ^{:line 131 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth tuple position) current))))))

^{:line 134 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- ground [arguments subst]
  ^{:line 135 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (mapv ^{:line 135 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [term] ^{:line 136 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [value ^{:line 136 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (term-value term subst)]
  ^{:line 137 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 137 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? value) value ^{:line 139 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (throw ^{:line 139 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ex-info "fram: unbound query variable reached projection" ^{:line 140 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {:type :unbound-query-variable}))))) arguments))

^{:line 143 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- relation-results [db ^Literal literal subst]
  ^{:line 145 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relation ^{:line 145 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-relation literal)
   arguments ^{:line 146 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-arguments literal)]
  ^{:line 147 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 147 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-negated literal) ^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? ^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get db relation ^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}) ^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ground arguments subst)) ^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] ^{:line 148 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [subst]) ^{:line 149 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 149 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc tuple] ^{:line 150 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [matched ^{:line 150 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (unify-arguments arguments tuple subst)]
  ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (some? matched) ^{:line 151 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc matched) acc))) ^{:line 152 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] ^{:line 152 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 152 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get db relation ^{:line 152 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}))))))

^{:line 154 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- literal-results [db ^Literal literal subst]
  ^{:line 156 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (relation-results db literal subst))

^{:line 158 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- body-results [db body seed]
  ^{:line 160 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 161 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [substitutions literal] ^{:line 162 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 162 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc subst] ^{:line 163 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 163 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (concat acc ^{:line 163 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-results db literal subst)))) ^{:line 164 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] substitutions)) ^{:line 165 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [seed] body))

^{:line 167 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- derive-rule [db ^Rule value]
  ^{:line 168 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 168 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc subst] ^{:line 169 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 169 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (ground ^{:line 169 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-arguments value) subst))) ^{:line 170 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} ^{:line 170 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (body-results db ^{:line 170 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-body value) ^{:line 170 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} {})))

^{:line 172 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- rule-head-relations [rules]
  ^{:line 173 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 173 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 173 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 174 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 174 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relation value))) ^{:line 175 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} rules)))

^{:line 177 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- derive-round [db rules]
  ^{:line 178 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 179 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 180 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relation ^{:line 180 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relation value)
   derived ^{:line 181 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (derive-rule db value)]
  ^{:line 182 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (update acc relation ^{:line 183 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [current] ^{:line 184 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 184 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [rows row] ^{:line 185 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj rows row)) ^{:line 186 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (or current ^{:line 186 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}) derived))))) db rules))

^{:line 189 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- ^Boolean relations-stable? [before after relations]
  ^{:line 190 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [remaining relations]
  ^{:line 191 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 191 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (empty? remaining) true ^{:line 193 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relation ^{:line 193 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (first remaining)]
  ^{:line 194 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 194 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (= ^{:line 194 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get before relation ^{:line 194 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{}) ^{:line 194 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get after relation ^{:line 194 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{})) ^{:line 195 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur ^{:line 195 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rest remaining)) false)))))

^{:line 200 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn fixpoint [db0 rules]
  ^{:line 201 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [relations ^{:line 201 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relations rules)]
  ^{:line 202 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [db db0]
  ^{:line 203 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [next ^{:line 203 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (derive-round db rules)]
  ^{:line 204 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 204 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (relations-stable? db next relations) next ^{:line 206 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur next))))))

^{:line 208 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn run-rules [propositions rules]
  ^{:line 210 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fixpoint ^{:line 210 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (edb propositions) rules))

^{:line 212 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn run-strata-db [db0 strata]
  ^{:line 213 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 213 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [db stratum] ^{:line 214 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fixpoint db stratum)) db0 strata))

^{:line 217 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn run-strata [propositions strata]
  ^{:line 219 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (run-strata-db ^{:line 219 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (edb propositions) strata))

^{:line 221 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn- negated-relations [stratum]
  ^{:line 222 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 223 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 224 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 224 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [rels literal] ^{:line 225 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 225 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-negated literal) ^{:line 226 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj rels ^{:line 226 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (literal-relation literal)) rels)) acc ^{:line 228 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-body value))) ^{:line 229 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} [] stratum))

^{:line 231 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn strata-violations [strata]
  ^{:line 232 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (loop [index 0
   lower base-relations
   problems ^{:line 232 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} []]
  ^{:line 233 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (if ^{:line 233 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (>= index ^{:line 233 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (count strata)) problems ^{:line 235 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (let [stratum ^{:line 235 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (nth strata index)
   heads ^{:line 237 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 237 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc value] ^{:line 238 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 238 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (rule-head-relation value))) ^{:line 239 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{} stratum)
   problems2 ^{:line 241 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 242 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc relation] ^{:line 243 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (cond
  ^{:line 244 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? heads relation) ^{:line 245 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 245 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (str "stratum " index ": negated '" relation "' is also derived in the same stratum"))
  ^{:line 247 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (not ^{:line 247 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (contains? lower relation)) ^{:line 248 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc ^{:line 248 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (str "stratum " index ": negated '" relation "' is not a base or lower-stratum relation"))
  :else acc)) problems ^{:line 251 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (negated-relations stratum))]
  ^{:line 252 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (recur ^{:line 252 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (+ index 1) ^{:line 253 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (reduce ^{:line 253 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (fn [acc relation] ^{:line 254 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (conj acc relation)) lower heads) problems2)))))

^{:line 258 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (defn facts [db ^String relation]
  ^{:line 259 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (vec ^{:line 259 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} (get db relation ^{:line 259 :file "/home/tom/code/fram/wt-triple-query/src/fram/datalog.bclj"} #{})))
