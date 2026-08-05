(ns fram.fri-replay
  (:require [fram.types :as t]
            [fram.fold :as fold]))

(def digit-table ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9"])

(def no-strings [])

(defn- ^String char-at [^String text index]
  (subs text index (+ index 1)))

(defn- split-on [^String text ^String separator]
  (loop [index 0
   start 0
   parts no-strings]
  (if (>= index (count text)) (conj parts (subs text start index)) (if (= separator (char-at text index)) (recur (+ index 1) (+ index 1) (conj parts (subs text start index))) (recur (+ index 1) start parts)))))

(defn- index-of [^String text ^String needle]
  (loop [index 0]
  (if (>= index (count text)) -1 (if (= needle (char-at text index)) index (recur (+ index 1))))))

(defn- last-index-of [^String text ^String needle]
  (loop [index (- (count text) 1)]
  (if (< index 0) -1 (if (= needle (char-at text index)) index (recur (- index 1))))))

(defn- ^Boolean trim-character? [^String character]
  (or (= character " ") (= character "\t") (= character "\r")))

(defn- ^String trim-line [^String text]
  (let [limit (loop [index (count text)]
  (if (and (> index 0) (trim-character? (char-at text (- index 1)))) (recur (- index 1)) index))
   start (loop [index 0]
  (if (and (< index limit) (trim-character? (char-at text index))) (recur (+ index 1)) index))]
  (subs text start limit)))

(defn- ^String join-strings [values ^String separator]
  (loop [index 0
   result ""]
  (if (>= index (count values)) result (recur (+ index 1) (if (= index 0) (nth values index) (str result separator (nth values index)))))))

(defrecord IntParse [ok value])

(defn intparse-ok [r] (:ok r))

(defn intparse-value [r] (:value r))

(defn- digit-value [^String character]
  (loop [index 0]
  (if (>= index (count digit-table)) -1 (if (= character (nth digit-table index)) index (recur (+ index 1))))))

(defn- ^IntParse parse-int [^String text]
  (let [negative (and (> (count text) 0) (= "-" (char-at text 0)))
   start (if negative 1 0)]
  (if (>= start (count text)) (->IntParse false 0) (loop [index start
   magnitude 0]
  (if (>= index (count text)) (->IntParse true (if negative (- 0 magnitude) magnitude)) (let [value (digit-value (char-at text index))]
  (if (< value 0) (->IntParse false 0) (recur (+ index 1) (+ (* magnitude 10) value)))))))))

(def line-version 0)

(def line-assert 1)

(def line-retract 2)

(def line-batch 3)

(def line-invalid 4)

(defrecord ParsedFact [valid predicate value base has-base])

(defn parsedfact-valid [r] (:valid r))

(defn parsedfact-predicate [r] (:predicate r))

(defn parsedfact-value [r] (:value r))

(defn parsedfact-base [r] (:base r))

(defn parsedfact-has-base [r] (:has-base r))

(defrecord ParsedOp [kind subject predicate value base has-base facts error])

(defn parsedop-kind [r] (:kind r))

(defn parsedop-subject [r] (:subject r))

(defn parsedop-predicate [r] (:predicate r))

(defn parsedop-value [r] (:value r))

(defn parsedop-base [r] (:base r))

(defn parsedop-has-base [r] (:has-base r))

(defn parsedop-facts [r] (:facts r))

(defn parsedop-error [r] (:error r))

(def no-facts [])

(def ^ParsedFact invalid-fact (->ParsedFact false "" "" 0 false))

(defn- ^ParsedOp invalid-op [^String message]
  (->ParsedOp line-invalid "" "" "" 0 false no-facts message))

(defn- ^ParsedFact parse-fact [^String token]
  (let [equals (index-of token "=")]
  (if (or (< equals 1) (>= (+ equals 1) (count token))) invalid-fact (let [predicate (subs token 0 equals)
   encoded (subs token (+ equals 1) (count token))
   at (last-index-of encoded "@")]
  (if (< at 0) (->ParsedFact true predicate encoded 0 false) (if (>= (+ at 1) (count encoded)) invalid-fact (let [parsed (parse-int (subs encoded (+ at 1) (count encoded)))]
  (if (intparse-ok parsed) (->ParsedFact true predicate (subs encoded 0 at) (intparse-value parsed) true) (->ParsedFact true predicate encoded 0 false)))))))))

(defn- parse-facts [^String encoded]
  (if (= 0 (count encoded)) no-facts (let [tokens (split-on encoded "|")]
  (loop [index 0
   facts no-facts]
  (if (>= index (count tokens)) facts (recur (+ index 1) (conj facts (parse-fact (nth tokens index)))))))))

(defn- ^Boolean facts-parsed? [facts]
  (loop [index 0]
  (if (>= index (count facts)) true (if (parsedfact-valid (nth facts index)) (recur (+ index 1)) false))))

(defn- ^ParsedOp parse-mutation [^String operation fields arity]
  (if (or (< arity 4) (> arity 5)) (invalid-op (str "invalid field count for " operation)) (if (and (= operation "assert-at-version") (not (= arity 5))) (invalid-op "assert-at-version requires a base") (let [kind (if (= operation "retract") line-retract line-assert)
   subject (nth fields 1)
   predicate (nth fields 2)
   value (nth fields 3)]
  (if (= arity 4) (->ParsedOp kind subject predicate value 0 false no-facts "") (let [parsed (parse-int (nth fields 4))]
  (if (intparse-ok parsed) (->ParsedOp kind subject predicate value (intparse-value parsed) true no-facts "") (invalid-op "base is not an integer"))))))))

(defn- ^ParsedOp parse-batch [^String operation fields arity]
  (if (= operation "assert-batch-at-version") (if (= arity 4) (let [parsed (parse-int (nth fields 2))]
  (if (intparse-ok parsed) (->ParsedOp line-batch (nth fields 1) "" "" (intparse-value parsed) true (parse-facts (nth fields 3)) "") (invalid-op "batch base is not an integer"))) (invalid-op "assert-batch-at-version takes a subject, a base, and facts")) (if (or (= arity 2) (= arity 3)) (->ParsedOp line-batch (nth fields 1) "" "" 0 false (parse-facts (if (= arity 3) (nth fields 2) "")) "") (invalid-op "invalid field count for assert-batch"))))

(defn- ^ParsedOp parse-line [^String line]
  (let [fields (split-on line "\t")
   operation (nth fields 0)
   arity (count fields)]
  (if (= operation "version") (if (= arity 1) (->ParsedOp line-version "" "" "" 0 false no-facts "") (invalid-op "version takes no further fields")) (if (or (= operation "assert") (= operation "retract") (= operation "assert-at-version")) (parse-mutation operation fields arity) (if (or (= operation "assert-batch") (= operation "assert-batch-at-version")) (parse-batch operation fields arity) (invalid-op (str "unknown corpus operation " operation)))))))

(def act-assert 1)

(def act-retract 2)

(defrecord ModelTriple [t1 t2 t3])

(defn modeltriple-t1 [r] (:t1 r))

(defn modeltriple-t2 [r] (:t2 r))

(defn modeltriple-t3 [r] (:t3 r))

(defrecord Model [triples version])

(defn model-triples [r] (:triples r))

(defn model-version [r] (:version r))

(defrecord Action [operation t1 t2 t3 local-base])

(defn action-operation [r] (:operation r))

(defn action-t1 [r] (:t1 r))

(defn action-t2 [r] (:t2 r))

(defn action-t3 [r] (:t3 r))

(defn action-local-base [r] (:local-base r))

(defrecord ReplayCommit [action triple])

(defn replaycommit-action [r] (:action r))

(defn replaycommit-triple [r] (:triple r))

(def no-triples [])

(def no-actions [])

(def no-commits [])

(def ^Model initial-model (->Model no-triples 0))

(defn- ^String strip-at [^String text]
  (if (and (> (count text) 0) (= "@" (char-at text 0))) (subs text 1 (count text)) text))

(defn- find-exact [triples ^String t1 ^String t2 ^String t3]
  (loop [index 0]
  (if (>= index (count triples)) -1 (let [row (nth triples index)]
  (if (and (= t1 (modeltriple-t1 row)) (= t2 (modeltriple-t2 row)) (= t3 (modeltriple-t3 row))) index (recur (+ index 1)))))))

(defn- find-group [triples ^String t1 ^String t2]
  (loop [index 0]
  (if (>= index (count triples)) -1 (let [row (nth triples index)]
  (if (and (= t1 (modeltriple-t1 row)) (= t2 (modeltriple-t2 row))) index (recur (+ index 1)))))))

(defn- remove-at [triples position]
  (loop [index 0
   result no-triples]
  (if (>= index (count triples)) result (recur (+ index 1) (if (= index position) result (conj result (nth triples index)))))))

(defn- ^Boolean single-predicate? [triples ^String predicate]
  (loop [index 0]
  (if (>= index (count triples)) false (let [row (nth triples index)]
  (if (and (= "cardinality" (modeltriple-t2 row)) (= "single" (modeltriple-t3 row)) (= predicate (strip-at (modeltriple-t1 row)))) true (recur (+ index 1)))))))

(defn- subject-predicate-count [triples ^String subject ^String predicate]
  (loop [index 0
   total 0]
  (if (>= index (count triples)) total (let [row (nth triples index)]
  (recur (+ index 1) (if (and (= subject (modeltriple-t1 row)) (= predicate (modeltriple-t2 row))) (+ total 1) total))))))

(defn- ^Boolean collapses? [triples ^String predicate]
  (loop [index 0]
  (if (>= index (count triples)) false (let [row (nth triples index)]
  (if (and (= predicate (modeltriple-t2 row)) (> (subject-predicate-count triples (modeltriple-t1 row) predicate) 1)) true (recur (+ index 1)))))))

(defn- ^Boolean declaration-collapse? [^Model model ^Action action]
  (and (= act-assert (action-operation action)) (= "cardinality" (action-t2 action)) (= "single" (action-t3 action)) (collapses? (model-triples model) (strip-at (action-t1 action)))))

(defrecord GroupRemoval [triples commits])

(defn groupremoval-triples [r] (:triples r))

(defn groupremoval-commits [r] (:commits r))

(defn- ^GroupRemoval remove-group [triples ^String t1 ^String t2]
  (loop [current triples
   commits no-commits]
  (let [position (find-group current t1 t2)]
  (if (< position 0) (->GroupRemoval current commits) (recur (remove-at current position) (conj commits (->ReplayCommit act-retract (nth current position))))))))

(defrecord ApplyResult [model changed collapse commits])

(defn applyresult-model [r] (:model r))

(defn applyresult-changed [r] (:changed r))

(defn applyresult-collapse [r] (:collapse r))

(defn applyresult-commits [r] (:commits r))

(defn- ^ApplyResult apply-assert [^Model model ^Action action]
  (let [triples (model-triples model)
   t1 (action-t1 action)
   t2 (action-t2 action)
   t3 (action-t3 action)]
  (if (>= (find-exact triples t1 t2 t3) 0) (->ApplyResult model false false no-commits) (let [removal (if (single-predicate? triples t2) (remove-group triples t1 t2) (->GroupRemoval triples no-commits))
   row (->ModelTriple t1 t2 t3)]
  (->ApplyResult (->Model (conj (groupremoval-triples removal) row) (model-version model)) true false (conj (groupremoval-commits removal) (->ReplayCommit act-assert row)))))))

(defn- ^ApplyResult apply-retract [^Model model ^Action action]
  (let [triples (model-triples model)
   t1 (action-t1 action)
   t2 (action-t2 action)
   position (if (single-predicate? triples t2) (find-group triples t1 t2) (find-exact triples t1 t2 (action-t3 action)))]
  (if (< position 0) (->ApplyResult model false false no-commits) (->ApplyResult (->Model (remove-at triples position) (model-version model)) true false (conj no-commits (->ReplayCommit act-retract (nth triples position)))))))

(defn- ^ApplyResult apply-action [^Model model ^Action action]
  (if (declaration-collapse? model action) (->ApplyResult model false true no-commits) (if (= act-assert (action-operation action)) (apply-assert model action) (apply-retract model action))))

(def kind-version 0)

(def kind-ok 1)

(def kind-reject 2)

(def ^String reason-invalid-request "invalid-request")

(def ^String reason-conflict "conflict")

(def ^String reason-cardinality-collapse "cardinality-collapse")

(defrecord Outcome [kind batch version reason written idempotent])

(defn outcome-kind [r] (:kind r))

(defn outcome-batch [r] (:batch r))

(defn outcome-version [r] (:version r))

(defn outcome-reason [r] (:reason r))

(defn outcome-written [r] (:written r))

(defn outcome-idempotent [r] (:idempotent r))

(defrecord MutationResult [model outcome commits])

(defn mutationresult-model [r] (:model r))

(defn mutationresult-outcome [r] (:outcome r))

(defn mutationresult-commits [r] (:commits r))

(def no-outcomes [])

(defn- ^MutationResult reject [^Model model ^Boolean batch ^String reason]
  (->MutationResult model (->Outcome kind-reject batch (model-version model) reason no-strings no-strings) no-commits))

(defn- op-actions [^ParsedOp op]
  (if (= line-batch (parsedop-kind op)) (let [facts (parsedop-facts op)]
  (loop [index 0
   actions no-actions]
  (if (>= index (count facts)) actions (let [fact (nth facts index)]
  (recur (+ index 1) (conj actions (->Action act-assert (parsedop-subject op) (parsedfact-predicate fact) (parsedfact-value fact) (parsedfact-has-base fact)))))))) (conj no-actions (->Action (if (= line-retract (parsedop-kind op)) act-retract act-assert) (parsedop-subject op) (parsedop-predicate op) (parsedop-value op) false))))

(defn- ^Boolean any-local-base? [actions]
  (loop [index 0]
  (if (>= index (count actions)) false (if (action-local-base (nth actions index)) true (recur (+ index 1))))))

(defn- ^MutationResult apply-actions [^Model model actions ^Boolean batch]
  (loop [index 0
   trial model
   commits no-commits
   written no-strings
   idempotent no-strings
   collapsed false]
  (if (or collapsed (>= index (count actions))) (if collapsed (reject model batch reason-cardinality-collapse) (let [version (if (> (count written) 0) (+ (model-version model) 1) (model-version model))]
  (->MutationResult (->Model (model-triples trial) version) (->Outcome kind-ok batch version "" written idempotent) commits))) (let [action (nth actions index)
   result (apply-action trial action)]
  (if (applyresult-collapse result) (recur (count actions) trial commits written idempotent true) (recur (+ index 1) (applyresult-model result) (vec (concat commits (applyresult-commits result))) (if (applyresult-changed result) (conj written (action-t2 action)) written) (if (applyresult-changed result) idempotent (conj idempotent (action-t2 action))) false))))))

(defn- ^MutationResult mutate [^Model model ^ParsedOp op]
  (let [actions (op-actions op)
   batch (= line-batch (parsedop-kind op))]
  (if (and (parsedop-has-base op) (< (parsedop-base op) 0)) (reject model batch reason-invalid-request) (if (and (parsedop-has-base op) (not (= (parsedop-base op) (model-version model)))) (reject model batch reason-conflict) (if (or (= 0 (count actions)) (any-local-base? actions) (not (facts-parsed? (parsedop-facts op)))) (reject model batch reason-invalid-request) (apply-actions model actions batch))))))

(defrecord ReplayFrame [sequence commits])

(defn replayframe-sequence [r] (:sequence r))

(defn replayframe-commits [r] (:commits r))

(defrecord ReplayResult [operations model outcomes frames error])

(defn replayresult-operations [r] (:operations r))

(defn replayresult-model [r] (:model r))

(defn replayresult-outcomes [r] (:outcomes r))

(defn replayresult-frames [r] (:frames r))

(defn replayresult-error [r] (:error r))

(def no-frames [])

(defn ^ReplayResult replay [^String text]
  (let [lines (split-on text "\n")]
  (loop [index 0
   model initial-model
   outcomes no-outcomes
   frames no-frames
   error ""]
  (if (or (> (count error) 0) (>= index (count lines))) (->ReplayResult (count outcomes) model outcomes frames error) (let [line (trim-line (nth lines index))]
  (if (= 0 (count line)) (recur (+ index 1) model outcomes frames error) (let [op (parse-line line)]
  (if (= line-invalid (parsedop-kind op)) (recur (+ index 1) model outcomes frames (parsedop-error op)) (if (= line-version (parsedop-kind op)) (recur (+ index 1) model (conj outcomes (->Outcome kind-version false (model-version model) "" no-strings no-strings)) frames error) (let [result (mutate model op)
   next-model (mutationresult-model result)
   commits (mutationresult-commits result)]
  (recur (+ index 1) next-model (conj outcomes (mutationresult-outcome result)) (if (> (count commits) 0) (conj frames (->ReplayFrame (model-version next-model) commits)) frames) error)))))))))))

(defn- to-triple [^ModelTriple row]
  (t/->Triple (modeltriple-t1 row) (modeltriple-t2 row) (modeltriple-t3 row)))

(defn- to-commit-operation [^ReplayCommit commit]
  (t/->CommitOperation (if (= act-assert (replaycommit-action commit)) t/assert-action t/retract-action) (to-triple (replaycommit-triple commit))))

(defn- to-transaction-frame [^ReplayFrame frame]
  (let [commits (replayframe-commits frame)]
  (t/->TransactionFrame (replayframe-sequence frame) (loop [index 0
   operations []]
  (if (>= index (count commits)) operations (recur (+ index 1) (conj operations (to-commit-operation (nth commits index)))))))))

(defn transaction-frames [^ReplayResult result]
  (let [frames (replayresult-frames result)]
  (loop [index 0
   built []]
  (if (>= index (count frames)) built (recur (+ index 1) (conj built (to-transaction-frame (nth frames index))))))))

(defn fold-replay! [^String space-id ^ReplayResult result]
  (fold/fold! space-id (transaction-frames result)))

(defn- ^Boolean string-slots? [triple]
  (and (string? (t/triple-t1 triple)) (string? (t/triple-t2 triple)) (string? (t/triple-t3 triple))))

(defn- ^String slot-text [value]
  (str value))

(defn store-facts [folded]
  (let [live (fold/fold-live-propositions folded)]
  (loop [index 0
   rows no-triples]
  (if (>= index (count live)) rows (let [triple (nth live index)]
  (recur (+ index 1) (if (string-slots? triple) (conj rows (->ModelTriple (slot-text (t/triple-t1 triple)) (slot-text (t/triple-t2 triple)) (slot-text (t/triple-t3 triple)))) rows)))))))

(defn- ^Boolean triple-member? [rows ^ModelTriple row]
  (>= (find-exact rows (modeltriple-t1 row) (modeltriple-t2 row) (modeltriple-t3 row)) 0))

(defn ^Boolean store-agrees? [^ReplayResult result facts]
  (let [modelled (model-triples (replayresult-model result))]
  (if (not (= (count modelled) (count facts))) false (loop [index 0]
  (if (>= index (count modelled)) true (if (triple-member? facts (nth modelled index)) (recur (+ index 1)) false))))))

(defn- ^String outcome-line [index ^Outcome outcome]
  (let [head (str index "\t")]
  (if (= kind-version (outcome-kind outcome)) (str head "version\t" (outcome-version outcome)) (if (= kind-reject (outcome-kind outcome)) (str head "reject\t" (outcome-version outcome) "\treason=" (outcome-reason outcome)) (if (outcome-batch outcome) (str head "ok\t" (outcome-version outcome) "\twritten=" (join-strings (outcome-written outcome) ",") "\tidempotent=" (join-strings (outcome-idempotent outcome) ",")) (str head "ok\t" (outcome-version outcome)))))))

(defn ^String render-outcomes [^ReplayResult result]
  (let [outcomes (replayresult-outcomes result)]
  (loop [index 0
   text ""]
  (if (>= index (count outcomes)) text (recur (+ index 1) (str text (outcome-line index (nth outcomes index)) "\n"))))))

(defn ^String render-facts [facts]
  (loop [index 0
   text ""]
  (if (>= index (count facts)) text (let [row (nth facts index)]
  (recur (+ index 1) (str text "fact\t" (modeltriple-t1 row) "\t" (modeltriple-t2 row) "\t" (modeltriple-t3 row) "\n"))))))

(defn ^String render-frames [^ReplayResult result]
  (let [frames (replayresult-frames result)]
  (loop [index 0
   text ""]
  (if (>= index (count frames)) text (let [frame (nth frames index)
   commits (replayframe-commits frame)]
  (recur (+ index 1) (loop [position 0
   body text]
  (if (>= position (count commits)) body (let [commit (nth commits position)
   row (replaycommit-triple commit)]
  (recur (+ position 1) (str body "tx\t" (replayframe-sequence frame) "\t" (if (= act-assert (replaycommit-action commit)) "assert" "retract") "\t" (modeltriple-t1 row) "\t" (modeltriple-t2 row) "\t" (modeltriple-t3 row) "\n")))))))))))

(defn ^String summary-line [^String path ^ReplayResult result folded facts]
  (str "oracle " path ": " (replayresult-operations result) " operations, version " (fold/fold-version folded) ", " (count facts) " live triples"))
