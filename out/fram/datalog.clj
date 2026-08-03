(ns fram.datalog
  (:require [fram.kernel :as kernel]
            [fram.types :as t]))

(defrecord QueryTerm [variable value])

(defn queryterm-variable [r] (:variable r))

(defn queryterm-value [r] (:value r))

(defrecord Literal [kind relation arguments negated operator binding])

(defn literal-kind [r] (:kind r))

(defn literal-relation [r] (:relation r))

(defn literal-arguments [r] (:arguments r))

(defn literal-negated [r] (:negated r))

(defn literal-operator [r] (:operator r))

(defn literal-binding [r] (:binding r))

(defrecord Rule [head-relation head-arguments body])

(defn rule-head-relation [r] (:head-relation r))

(defn rule-head-arguments [r] (:head-arguments r))

(defn rule-body [r] (:body r))

(defrecord QueryControl [steps cancelled max-steps deadline-ns timeout-ms])

(defn querycontrol-steps [r] (:steps r))

(defn querycontrol-cancelled [r] (:cancelled r))

(defn querycontrol-max-steps [r] (:max-steps r))

(defn querycontrol-deadline-ns [r] (:deadline-ns r))

(defn querycontrol-timeout-ms [r] (:timeout-ms r))

(defrecord CandidateSource [rows positions spo pos osp])

(defn candidatesource-rows [r] (:rows r))

(defn candidatesource-positions [r] (:positions r))

(defn candidatesource-spo [r] (:spo r))

(defn candidatesource-pos [r] (:pos r))

(defn candidatesource-osp [r] (:osp r))

(def ^String triple-relation "triple")

(def ^String occurrence-relation "occurrence")

(def base-relations #{triple-relation occurrence-relation})

(def comparison-operators #{:eq :ne :lt :le :gt :ge})

(def subtract-operator (keyword "-"))

(def builtin-operators #{:+ subtract-operator :* :/ :mod})

(def ^:dynamic *query-control* nil)

(defn ^QueryControl query-control [max-steps timeout-ms]
  (if (and (> max-steps 0) (>= timeout-ms 0)) (->QueryControl (atom 0) (atom nil) max-steps (+ (System/nanoTime) (* timeout-ms 1000000)) timeout-ms) (throw (ex-info "fram: query limits must be positive steps and non-negative milliseconds" {:type :invalid-query-control}))))

(defn cancel-query! [^QueryControl control reason]
  (reset! (querycontrol-cancelled control) reason)
  nil)

(defn query-steps [^QueryControl control]
  (deref (querycontrol-steps control)))

(defn- query-check! []
  (let [control *query-control*]
  (if (nil? control) nil (let [steps (swap! (querycontrol-steps control) inc)
   now (System/nanoTime)
   cancelled (deref (querycontrol-cancelled control))
   code (cond
  (some? cancelled) :query-cancelled
  (> steps (querycontrol-max-steps control)) :query-work-limit
  (>= now (querycontrol-deadline-ns control)) :query-time-limit
  :else nil)]
  (if (nil? code) nil (throw (ex-info (str "query evaluation stopped: " (name code)) {:type :fram-query-abort :code code :reason cancelled :steps steps :max-steps (querycontrol-max-steps control) :timeout-ms (querycontrol-timeout-ms control)})))))))

(defn ^QueryTerm variable [^String name]
  (if (pos? (count name)) (->QueryTerm name nil) (throw (ex-info "fram: query variable name must be non-empty" {:type :invalid-query-variable}))))

(defn ^QueryTerm constant [value]
  (if (t/term? value) (->QueryTerm nil value) (throw (ex-info "fram: query constant must be a Term" {:type :invalid-query-constant}))))

(defn ^Boolean query-term? [value]
  (and (instance? QueryTerm value) (if (some? (queryterm-variable value)) (and (pos? (count (queryterm-variable value))) (nil? (queryterm-value value))) (t/term? (queryterm-value value)))))

(defn ^Literal relation-literal [^String relation arguments]
  (->Literal :relation relation arguments false :none ""))

(defn ^Literal negated-literal [^String relation arguments]
  (->Literal :relation relation arguments true :none ""))

(defn ^Literal comparison-literal [operator arguments]
  (->Literal :comparison "" arguments false operator ""))

(defn ^Literal builtin-literal [operator arguments ^String binding]
  (->Literal :builtin "" arguments false operator binding))

(defn ^Rule rule [^String head-relation head-arguments body]
  (->Rule head-relation head-arguments body))

(defn- triple-row [value]
  [(t/triple-slot0 value) (t/triple-slot1 value) (t/triple-slot2 value)])

(defn- rows [triples]
  (reduce (fn [acc value] (conj acc (triple-row value))) #{} triples))

(defn edb [propositions]
  {triple-relation (rows propositions)})

(defn edb-with-occurrences [propositions occurrences]
  (let [checked (reduce (fn [acc value] (if (kernel/operation-occurrence? value) (conj acc value) (throw (ex-info "fram: occurrence relation accepts only operation occurrences" {:type :invalid-operation-occurrence})))) [] occurrences)]
  {triple-relation (rows propositions) occurrence-relation (rows checked)}))

(defn- term-value [^QueryTerm term subst]
  (let [name (queryterm-variable term)]
  (if (some? name) (get subst name) (queryterm-value term))))

(defn- unify [^QueryTerm term value subst]
  (let [name (queryterm-variable term)]
  (if (some? name) (if (contains? subst name) (if (= (get subst name) value) subst nil) (assoc subst name value)) (if (= (queryterm-value term) value) subst nil))))

(defn- unify-arguments! [arguments tuple subst]
  (query-check!)
  (if (not (= (count arguments) (count tuple))) nil (loop [position 0
   current subst]
  (if (or (nil? current) (>= position (count arguments))) current (recur (+ position 1) (unify (nth arguments position) (nth tuple position) current))))))

(defn- ground [arguments subst]
  (mapv (fn [term] (let [value (term-value term subst)]
  (if (some? value) value (throw (ex-info "fram: unbound query variable reached evaluation" {:type :unbound-query-variable}))))) arguments))

(defn- integer-value [value]
  (cond
  (integer? value) value
  (string? value) (parse-long value)
  :else nil))

(defn- numeric-value [value]
  (cond
  (integer? value) (double value)
  (number? value) (double value)
  (string? value) (let [integer-result (parse-long value)]
  (if (some? integer-result) (double integer-result) (parse-double value)))
  :else nil))

(defn- ^Boolean comparison-result [^Literal literal subst]
  (let [arguments (literal-arguments literal)
   left (term-value (nth arguments 0) subst)
   right (term-value (nth arguments 1) subst)
   operator (literal-operator literal)]
  (if (or (nil? left) (nil? right)) false (cond
  (= operator :eq) (= left right)
  (= operator :ne) (not (= left right))
  :else (let [left-number (numeric-value left)
   right-number (numeric-value right)]
  (if (or (nil? left-number) (nil? right-number)) false (cond
  (= operator :lt) (< left-number right-number)
  (= operator :le) (<= left-number right-number)
  (= operator :gt) (> left-number right-number)
  (= operator :ge) (>= left-number right-number)
  :else false)))))))

(defn- builtin-value [operator left right]
  (let [left-int (integer-value left)
   right-int (integer-value right)
   left-number (numeric-value left)
   right-number (numeric-value right)]
  (cond
  (= operator :mod) (if (or (nil? left-int) (nil? right-int) (= right-int 0)) nil (mod left-int right-int))
  (= operator :/) (if (or (nil? left-number) (nil? right-number) (= right-number 0.0)) nil (/ left-number right-number))
  (or (= operator :+) (or (= operator subtract-operator) (= operator :*))) (if (and (some? left-int) (some? right-int)) (cond
  (= operator :+) (+ left-int right-int)
  (= operator subtract-operator) (- left-int right-int)
  :else (* left-int right-int)) (if (or (nil? left-number) (nil? right-number)) nil (cond
  (= operator :+) (+ left-number right-number)
  (= operator subtract-operator) (- left-number right-number)
  :else (* left-number right-number))))
  :else nil)))

(defn- builtin-results [^Literal literal subst]
  (let [arguments (literal-arguments literal)
   left (term-value (nth arguments 0) subst)
   right (term-value (nth arguments 1) subst)]
  (if (or (nil? left) (nil? right)) [] (let [result (builtin-value (literal-operator literal) left right)]
  (if (nil? result) [] [(assoc subst (literal-binding literal) result)])))))

(defn- relation-results! [db ^Literal literal subst]
  (let [relation (literal-relation literal)
   arguments (literal-arguments literal)]
  (if (literal-negated literal) (if (contains? (get db relation #{}) (ground arguments subst)) [] [subst]) (reduce (fn [acc tuple] (let [matched (unify-arguments! arguments tuple subst)]
  (if (some? matched) (conj acc matched) acc))) [] (vec (get db relation #{}))))))

(defn- literal-results! [db ^Literal literal subst]
  (query-check!)
  (cond
  (= :relation (literal-kind literal)) (relation-results! db literal subst)
  (= :comparison (literal-kind literal)) (if (comparison-result literal subst) [subst] [])
  (= :builtin (literal-kind literal)) (builtin-results literal subst)
  :else []))

(defn- body-results! [db body seed]
  (reduce (fn [substitutions literal] (reduce (fn [acc subst] (vec (concat acc (literal-results! db literal subst)))) [] substitutions)) [seed] body))

(defn- derive-rule! [db ^Rule value]
  (reduce (fn [acc subst] (conj acc (ground (rule-head-arguments value) subst))) #{} (body-results! db (rule-body value) {})))

(defn- rule-head-relations [rules]
  (vec (reduce (fn [acc value] (conj acc (rule-head-relation value))) #{} rules)))

(defn- derive-round! [db rules]
  (reduce (fn [acc value] (let [relation (rule-head-relation value)
   derived (derive-rule! db value)]
  (update acc relation (fn [current] (reduce (fn [rows-value row] (conj rows-value row)) (or current #{}) derived))))) db rules))

(defn- ^Boolean relations-stable? [before after relations]
  (loop [remaining relations]
  (if (empty? remaining) true (let [relation (first remaining)]
  (if (= (get before relation #{}) (get after relation #{})) (recur (rest remaining)) false)))))

(defn- fixpoint-oracle! [db0 rules]
  (let [relations (rule-head-relations rules)]
  (loop [db db0]
  (let [next (derive-round! db rules)]
  (if (relations-stable? db next relations) next (recur next))))))

(defn- append-handle [index key handle]
  (update index key (fn [current] (conj (or current []) handle))))

(defn- add-position-handles [index tuple handle]
  (loop [position 0
   current index]
  (if (>= position (count tuple)) current (let [bucket (get current position {})]
  (recur (inc position) (assoc current position (append-handle bucket (nth tuple position) handle)))))))

(defn- trie-add [trie tuple order handle]
  (let [first-key (nth tuple (nth order 0))
   second-key (nth tuple (nth order 1))
   third-key (nth tuple (nth order 2))
   first-node (get trie first-key {})
   second-node (get first-node second-key {})
   leaf (get second-node third-key [])]
  (assoc trie first-key (assoc first-node second-key (assoc second-node third-key (conj leaf handle))))))

(defn- trie-node-handles [node]
  (cond
  (nil? node) []
  (vector? node) node
  (map? node) (reduce (fn [handles child] (into handles (trie-node-handles child))) [] (vec (vals node)))
  :else []))

(defn- trie-probe [trie prefix]
  (loop [node trie
   remaining prefix]
  (if (or (nil? node) (empty? remaining)) (trie-node-handles node) (recur (get node (first remaining)) (rest remaining)))))

(defn- ^CandidateSource empty-candidate-source []
  (->CandidateSource [] {} {} {} {}))

(defn- ^CandidateSource candidate-source-add [^String relation ^CandidateSource source tuple]
  (let [handle (count (candidatesource-rows source))
   with-row (assoc source :rows (conj (candidatesource-rows source) tuple))]
  (if (and (= relation triple-relation) (= 3 (count tuple))) (assoc with-row :spo (trie-add (candidatesource-spo source) tuple [0 1 2] handle) :pos (trie-add (candidatesource-pos source) tuple [1 2 0] handle) :osp (trie-add (candidatesource-osp source) tuple [2 0 1] handle)) (assoc with-row :positions (add-position-handles (candidatesource-positions source) tuple handle)))))

(defn- ^CandidateSource candidate-source-add-rows [^String relation ^CandidateSource source tuples]
  (reduce (fn [current tuple] (candidate-source-add relation current tuple)) source tuples))

(defn- build-candidate-sources [db relations]
  (reduce (fn [sources relation] (assoc sources relation (candidate-source-add-rows relation (empty-candidate-source) (get db relation #{})))) {} relations))

(defn- add-delta-sources [sources delta relations]
  (reduce (fn [current relation] (let [tuples (get delta relation #{})]
  (if (empty? tuples) current (assoc current relation (candidate-source-add-rows relation (get current relation (empty-candidate-source)) tuples))))) sources relations))

(defn- bound-term-value [^QueryTerm term subst]
  (let [name (queryterm-variable term)]
  (if (some? name) (if (contains? subst name) (get subst name) nil) (queryterm-value term))))

(defn- bound-prefix [arguments order subst]
  (loop [remaining order
   prefix []]
  (if (empty? remaining) prefix (let [value (bound-term-value (nth arguments (first remaining)) subst)]
  (if (nil? value) prefix (recur (rest remaining) (conj prefix value)))))))

(defn- triple-prefix-handles [^CandidateSource source arguments subst]
  (let [spo-key (bound-prefix arguments [0 1 2] subst)
   pos-key (bound-prefix arguments [1 2 0] subst)
   osp-key (bound-prefix arguments [2 0 1] subst)
   spo-count (count spo-key)
   pos-count (count pos-key)
   osp-count (count osp-key)]
  (cond
  (and (= 0 spo-count) (and (= 0 pos-count) (= 0 osp-count))) nil
  (and (>= spo-count pos-count) (>= spo-count osp-count)) (trie-probe (candidatesource-spo source) spo-key)
  (>= pos-count osp-count) (trie-probe (candidatesource-pos source) pos-key)
  :else (trie-probe (candidatesource-osp source) osp-key))))

(defn- positional-handles [^CandidateSource source arguments subst]
  (loop [position 0
   best nil
   found false]
  (if (>= position (count arguments)) (if found best nil) (let [value (bound-term-value (nth arguments position) subst)]
  (if (nil? value) (recur (inc position) best found) (let [candidate (get (get (candidatesource-positions source) position {}) value [])]
  (recur (inc position) (if (or (not found) (< (count candidate) (count best))) candidate best) true)))))))

(defn- source-handles [^CandidateSource source ^String relation arguments subst]
  (if (and (= relation triple-relation) (= 3 (count arguments))) (triple-prefix-handles source arguments subst) (positional-handles source arguments subst)))

(defn- relation-results-indexed! [db sources ^Literal literal subst]
  (let [relation (literal-relation literal)
   arguments (literal-arguments literal)]
  (if (literal-negated literal) (if (contains? (get db relation #{}) (ground arguments subst)) [] [subst]) (let [source (get sources relation)]
  (if (nil? source) (reduce (fn [acc tuple] (let [matched (unify-arguments! arguments tuple subst)]
  (if (some? matched) (conj acc matched) acc))) [] (vec (get db relation #{}))) (let [rows-value (candidatesource-rows source)
   handles (source-handles source relation arguments subst)]
  (if (nil? handles) (reduce (fn [acc tuple] (let [matched (unify-arguments! arguments tuple subst)]
  (if (some? matched) (conj acc matched) acc))) [] rows-value) (reduce (fn [acc handle] (let [tuple (nth rows-value handle)
   matched (unify-arguments! arguments tuple subst)]
  (if (some? matched) (conj acc matched) acc))) [] handles))))))))

(defn- literal-results-indexed! [db sources ^Literal literal subst]
  (query-check!)
  (cond
  (= :relation (literal-kind literal)) (relation-results-indexed! db sources literal subst)
  (= :comparison (literal-kind literal)) (if (comparison-result literal subst) [subst] [])
  (= :builtin (literal-kind literal)) (builtin-results literal subst)
  :else []))

(defn- body-results-indexed! [db sources body seed]
  (reduce (fn [substitutions literal] (reduce (fn [acc subst] (into acc (literal-results-indexed! db sources literal subst))) [] substitutions)) [seed] body))

(defn- derive-rule-indexed! [db sources ^Rule value]
  (reduce (fn [acc subst] (conj acc (ground (rule-head-arguments value) subst))) #{} (body-results-indexed! db sources (rule-body value) {})))

(defn- delta-relation-positions [body delta-relations]
  (loop [position 0
   remaining body
   positions []]
  (if (empty? remaining) positions (let [literal (first remaining)]
  (recur (inc position) (rest remaining) (if (and (= :relation (literal-kind literal)) (not (literal-negated literal)) (contains? delta-relations (literal-relation literal))) (conj positions position) positions))))))

(defn- positive-relation-names [rules]
  (vec (sort (reduce (fn [relations value] (reduce (fn [current literal] (if (and (= :relation (literal-kind literal)) (not (literal-negated literal))) (conj current (literal-relation literal)) current)) relations (rule-body value))) #{} rules))))

(defn- body-results-pinned! [db sources delta delta-sources body pin]
  (loop [position 0
   remaining body
   substitutions [{}]]
  (if (empty? remaining) substitutions (let [literal (first remaining)
   pinned (and (= position pin) (= :relation (literal-kind literal)) (not (literal-negated literal)))
   read-db (if pinned delta db)
   read-sources (if pinned delta-sources sources)
   next-substitutions (reduce (fn [acc subst] (into acc (literal-results-indexed! read-db read-sources literal subst))) [] substitutions)]
  (recur (inc position) (rest remaining) next-substitutions)))))

(defn- derive-rule-delta! [db sources delta delta-sources delta-relations ^Rule value]
  (let [head (rule-head-arguments value)
   body (rule-body value)]
  (reduce (fn [derived pin] (reduce (fn [current subst] (conj current (ground head subst))) derived (body-results-pinned! db sources delta delta-sources body pin))) #{} (delta-relation-positions body delta-relations))))

(defn- db-new-only [candidate db relations]
  (reduce (fn [delta relation] (let [new-tuples (reduce (fn [rows-value tuple] (if (contains? (get db relation #{}) tuple) rows-value (conj rows-value tuple))) #{} (get candidate relation #{}))]
  (if (empty? new-tuples) delta (assoc delta relation new-tuples)))) {} relations))

(defn- db-merge-delta [db delta relations]
  (reduce (fn [current relation] (let [new-tuples (get delta relation #{})]
  (if (empty? new-tuples) current (update current relation (fn [known] (reduce (fn [rows-value tuple] (conj rows-value tuple)) (or known #{}) new-tuples)))))) db relations))

(defn- ^Boolean delta-empty? [delta relations]
  (loop [remaining relations]
  (if (empty? remaining) true (if (empty? (get delta (first remaining) #{})) (recur (rest remaining)) false))))

(defn- derive-delta! [db sources delta rules relations delta-relations]
  (let [delta-set (set delta-relations)
   delta-sources (build-candidate-sources delta delta-relations)
   candidate (reduce (fn [current value] (let [relation (rule-head-relation value)
   derived (derive-rule-delta! db sources delta delta-sources delta-set value)]
  (update current relation (fn [known] (reduce (fn [rows-value tuple] (conj rows-value tuple)) (or known #{}) derived))))) {} rules)]
  (db-new-only candidate db relations)))

(defn fixpoint! [db0 rules]
  (let [relations (rule-head-relations rules)
   read-relations (positive-relation-names rules)
   head-set (set relations)
   delta-relations (vec (filter (fn [relation] (contains? head-set relation)) read-relations))
   initial-sources (build-candidate-sources db0 read-relations)
   seeded (reduce (fn [db value] (let [relation (rule-head-relation value)
   derived (derive-rule-indexed! db0 initial-sources value)]
  (update db relation (fn [known] (reduce (fn [rows-value tuple] (conj rows-value tuple)) (or known #{}) derived))))) db0 rules)
   delta0 (db-new-only seeded db0 relations)
   seeded-sources (add-delta-sources initial-sources delta0 read-relations)]
  (loop [db seeded
   sources seeded-sources
   delta delta0]
  (if (delta-empty? delta delta-relations) db (let [next-delta (derive-delta! db sources delta rules relations delta-relations)]
  (recur (db-merge-delta db next-delta relations) (add-delta-sources sources next-delta read-relations) next-delta))))))

(defn run-rules! [propositions rules]
  (fixpoint! (edb propositions) rules))

(defn run-strata-db! [db0 strata]
  (reduce (fn [db stratum] (fixpoint! db stratum)) db0 strata))

(defn run-strata! [propositions strata]
  (run-strata-db! (edb propositions) strata))

(defn- negated-relations [stratum]
  (reduce (fn [acc value] (reduce (fn [relations literal] (if (and (= :relation (literal-kind literal)) (literal-negated literal)) (conj relations (literal-relation literal)) relations)) acc (rule-body value))) [] stratum))

(defn strata-violations [strata]
  (loop [index 0
   lower base-relations
   problems []]
  (if (>= index (count strata)) problems (let [stratum (nth strata index)
   heads (reduce (fn [acc value] (conj acc (rule-head-relation value))) #{} stratum)
   problems2 (reduce (fn [acc relation] (cond
  (contains? heads relation) (conj acc (str "stratum " index ": negated '" relation "' is also derived in the same stratum"))
  (not (contains? lower relation)) (conj acc (str "stratum " index ": negated '" relation "' is not a base or lower-stratum relation"))
  :else acc)) problems (negated-relations stratum))]
  (recur (+ index 1) (reduce (fn [acc relation] (conj acc relation)) lower heads) problems2)))))

(defn facts [db ^String relation]
  (vec (get db relation #{})))
