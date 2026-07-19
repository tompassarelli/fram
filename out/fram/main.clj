(ns fram.main
  (:gen-class)
  (:require [fram.kernel :as k]
            [fram.fold :as fold]
            [fram.import :as imp]
            [fram.export :as exp]
            [clojure.string :as str]
            [fram.tools :as tl]
            [fram.query :as q]
            [fram.rt :as rt]))

(defn- ^String short-id [^String te]
  (if (str/starts-with? te "@") (subs te 1) te))

(defn- live-facts [^String log]
  (let [warm (fram.rt/coord-live-facts (fram.rt/coord-port) log)]
  (if (empty? warm) (:facts (fold/fold (fram.rt/read-log log))) warm)))

(defn- ^String fact-sig [c]
  (str (:l c) "|" (:p c) "|" (:r c)))

(defn- sig-set [facts]
  (vec (sort (mapv fact-sig facts))))

(defn- sig-member-map [facts]
  (reduce (fn [m c] (assoc m (fact-sig c) true)) {} facts))

(defn- pending-coord-count [^String log file-sigs]
  (count (filterv (fn [v] (and (or (= (:frame v) "coord") (= (:frame v) "agent") (= (:frame v) "cli")) (nil? (get file-sigs (str (:l v) "|" (:p v) "|" (:r v)))))) (fold/fold-latest (fram.rt/read-log log)))))

(defn cmd-import [^String threads-dir ^String log ^Boolean force]
  (let [as (imp/load-corpus threads-dir)
   file-sigs (sig-member-map (:facts (fold/fold as)))
   lost (pending-coord-count log file-sigs)]
  (if (and (> lost 0) (not force)) (println (str "REFUSING import: " lost " coordinator write(s) in the log are not in the " "files (would be lost). Run `export` first, or `import --force`.")) (do
  (fram.rt/write-log log as)
  (println (str "imported -> " (count as) " facts -> " log))))))

(defn cmd-export [^String threads-dir ^String log ^String out-dir ^Boolean force]
  (let [log-facts (:facts (fold/fold (fram.rt/read-log log)))
   file-facts (:facts (fold/fold (imp/load-corpus threads-dir)))
   log-sigs (sig-member-map log-facts)
   file-ahead (filterv (fn [c] (nil? (get log-sigs (fact-sig c)))) file-facts)]
  (if (and (not (empty? file-ahead)) (not force)) (println (str "REFUSING export: " (count file-ahead) " file fact(s) are not in the log " "(a thread .md was hand-edited). Merge them via the coordinator (`tell`), or " "`export <dir> --force` to regenerate files FROM the log (discards those edits).")) (let [idx (k/build-index log-facts)
   tes (k/thread-ids-i idx)]
  (fram.rt/ensure-dir out-dir)
  (doseq [te tes]
  (let [title (k/one-i idx te "title")
   id (if (str/starts-with? te "@") (subs te 1) te)
   fname (str id "-" (fram.rt/slugify (if (some? title) title "untitled")) ".md")]
  (fram.rt/spit-file (str out-dir "/" fname) (exp/thread-md log-facts te))))
  (println (str "exported " (count tes) " threads -> " out-dir))))))

(defn cmd-validate [^String log]
  (let [idx (k/build-index (:facts (fold/fold (fram.rt/read-log log))))
   problems (reduce (fn [acc te] (reduce (fn [a v] (conj a (str (short-id te) ": " v))) acc (k/violations-i idx te))) [] (k/thread-ids-i idx))]
  (if (empty? problems) (println (str "OK — " (count (k/thread-ids-i idx)) " threads, no violations.")) (do
  (doseq [p problems]
  (println (str "  " p)))
  (println (str "\n" (count problems) " violation(s)."))))))

(defn cmd-show [^String log ^String id]
  (let [facts (live-facts log)
   te (str "@" id)
   exact (k/q-by-l facts te)
   matches (if (or (not (empty? exact)) (str/blank? id)) [] (filterv (fn [t] (str/starts-with? (short-id t) id)) (k/thread-ids-i (k/build-index facts))))]
  (cond
  (not (empty? exact)) (doseq [c exact]
  (println (str "  " (:p c) "  " (:r c))))
  (= (count matches) 1) (doseq [c (k/q-by-l facts (first matches))]
  (println (str "  " (:p c) "  " (:r c))))
  (> (count matches) 1) (do
  (println (str "ambiguous prefix @" id " matches " (count matches) " threads:"))
  (doseq [m matches]
  (let [title (k/one facts m "title")]
  (println (str "  " (short-id m) (if (some? title) (str "  " title) ""))))))
  :else (println (str "no facts for " te)))))

(defn cmd-set [^String log ^String id ^String pred ^String value]
  (let [as (fram.rt/read-log log)
   f (fold/fold as)
   facts (:facts f)
   cmap (fold/card-map as)
   te (str "@" id)
   rv (tl/ref-value facts pred value)
   cand (k/apply-assert-c cmap facts (k/->Fact te pred rv))
   viol (k/violations cand te)]
  (if (not (empty? viol)) (println (str "REJECTED — " (str/join "; " viol))) (let [tx (+ (fold/max-tx (fram.rt/read-log log)) 1)]
  (fram.rt/append-fact-op log (fold/->FactOp tx "assert" te pred rv "cli"))
  (println (str "ok — " id " " pred " = " rv " (v" tx ")"))))))

(defn- facts->fact-ops [facts ^String frame]
  (loop [cs facts
   i 1
   acc []]
  (if (empty? cs) acc (let [c (first cs)]
  (recur (rest cs) (+ i 1) (conj acc (fold/->FactOp i "assert" (:l c) (:p c) (:r c) frame)))))))

(defn cmd-merge [^String log ^String from ^String to]
  (let [facts (:facts (fold/fold (fram.rt/read-log log)))
   rewritten (mapv (fn [c] (k/->Fact (if (= (:l c) from) to (:l c)) (:p c) (if (= (:r c) from) to (:r c)))) facts)
   withrec (conj rewritten (k/->Fact from "merged_into" to))
   deduped (:facts (fold/fold (facts->fact-ops withrec "merge")))]
  (fram.rt/write-log log (facts->fact-ops deduped "merge"))
  (println (str "merged " from " -> " to "  (" (count facts) " facts -> " (count deduped) ")"))))

(defn- ^String tell-once [port ^String log ^String op ^String te ^String pred ^String rv]
  (let [v (fram.rt/coord-version-for-log port log)]
  (cond
  (= v -1) "nodaemon"
  (= v -2) "log-mismatch"
  (= v -3) "protocol-incompatible"
  (= op "assert") (fram.rt/coord-assert-for-log port log te pred rv v)
  :else (fram.rt/coord-retract-for-log port log te pred rv v))))

(defn- ^String tell-retry [port ^String log ^String op ^String te ^String pred ^String rv tries]
  (let [resp (tell-once port log op te pred rv)]
  (if (and (= resp "conflict") (> tries 0)) (tell-retry port log op te pred rv (- tries 1)) resp)))

(defn cmd-tell [^String log ^String op ^String id ^String pred ^String value]
  (let [facts (:facts (fold/fold (fram.rt/read-log log)))
   te (str "@" id)
   rv (tl/ref-value facts pred value)
   resp (tell-retry (fram.rt/coord-port) log op te pred rv 5)]
  (cond
  (= resp "nodaemon") (println (str "no coordinator on 127.0.0.1:" (fram.rt/coord-port) " — start it with bin/fram-up, or use `set` (single-writer)"))
  (= resp "log-mismatch") (println "REJECTED by coordinator: the daemon serves a different log (run `fram doctor` for both paths)")
  (= resp "protocol-incompatible") (println "REJECTED by coordinator: daemon lacks the required log-fence protocol; restart it with current Fram")
  (= resp "conflict") (println "rejected: write conflict after retries (another agent is racing this id+pred)")
  (str/starts-with? resp "ok:") (println (str "committed via coordinator (v" (subs resp 3) "): " id " " pred " = " rv))
  :else (println (str "REJECTED by coordinator: " resp)))))

(defn cmd-watch [^String log]
  (let [port (fram.rt/coord-port)]
  (println (str "watching coordinator on 127.0.0.1:" port " — Ctrl-C to stop"))
  (fram.rt/coord-watch-for-log port log)))

(defn cmd-doctor [^String log]
  (let [port (fram.rt/coord-port)
   as (fram.rt/read-log log)
   cmap (fold/card-map as)
   declared (filterv (fn [c] (= (:p c) "cardinality")) (:facts (fold/fold as)))]
  (println (fram.rt/coord-status-for-log port log))
  (println (str "vocab " (k/vocab-fingerprint)))
  (println (str "cardinality-facts: " (count declared) " facts-derived (in the log)"))
  (println (str "cardinality-overlay " (k/cards-fingerprint cmap)))
  (if (k/single-valued-from-env?) (println "vocab-source: facts (overlay) > FRAM_SINGLE_VALUED (env, injected) > fallback list") (println (str "vocab-source: facts (overlay) > FRAM_SINGLE_VALUED (UNSET in this process) > " "TRANSITIONAL FALLBACK list. A cardinality FACT (`tell <pred> cardinality " "single|multi`) overrides the env/fallback for BOTH the daemon and this CLI, so a " "predicate declared in the log classifies identically regardless of each process's " "FRAM_SINGLE_VALUED; only preds NOT declared in the log fall back to it — export the " "SAME FRAM_SINGLE_VALUED in every process that folds/serves this log for those.")))))

(defn cmd-tools [^String log]
  (let [facts (:facts (fold/fold (fram.rt/read-log log)))
   cat (tl/catalog facts)]
  (println (str (count cat) " tools (closed catalog; vocabulary is data — `show <pred>` reveals " "a predicate's cardinality/value_kind facts, `ask` enumerates it):"))
  (doseq [spec cat]
  (println (str "  " (:name spec) "  —  " (:desc spec))))))

(defn- print-rows [rows]
  (if (empty? rows) (println "  (no results)") (doseq [r rows]
  (println (str "  " r)))))

(defn- route-write [^String log w]
  (let [resp (tell-retry (fram.rt/coord-port) log (:op w) (:l w) (:p w) (:r w) 5)]
  (cond
  (= resp "nodaemon") (println (str "no coordinator on 127.0.0.1:" (fram.rt/coord-port) " — start it with bin/fram-up"))
  (= resp "log-mismatch") (println "REJECTED by coordinator: the daemon serves a different log (run `fram doctor` for both paths)")
  (= resp "protocol-incompatible") (println "REJECTED by coordinator: daemon lacks the required log-fence protocol; restart it with current Fram")
  (= resp "conflict") (println "rejected: write conflict after retries")
  (str/starts-with? resp "ok:") (println (str "committed (v" (subs resp 3) "): " (:l w) " " (:p w) " = " (:r w) " [" (:op w) "]"))
  :else (println (str "REJECTED by coordinator: " resp)))))

(defn cmd-call [^String log ^String tool ^String edn]
  (let [args (fram.rt/parse-edn edn)]
  (if (nil? args) (println (str "bad EDN args: " edn)) (let [facts (:facts (fold/fold (fram.rt/read-log log)))
   idx (k/build-index facts)
   cat (tl/catalog facts)
   res (tl/call facts idx cat tool args)]
  (cond
  (some? (:error res)) (doseq [e (:error res)]
  (println (str "  error: " e)))
  (some? (:write res)) (route-write log (:write res))
  (some? (:ok res)) (print-rows (:ok res))
  :else (print-rows (:rows res)))))))

(defn cmd-query [^String log ^String edn]
  (let [qd (fram.rt/parse-edn edn)]
  (if (nil? qd) (println (str "bad EDN query: " edn)) (let [facts (:facts (fold/fold (fram.rt/read-log log)))
   res (q/run facts qd)]
  (if (some? (:error res)) (doseq [e (:error res)]
  (println (str "  error: " e))) (print-rows (:ok res)))))))

(defn dispatch [args ^String threads-dir ^String log]
  (let [cmd (if (empty? args) "" (first args))]
  (cond
  (= cmd "import") (cmd-import threads-dir log (and (> (count args) 1) (= (nth args 1) "--force")))
  (= cmd "export") (if (> (count args) 1) (cmd-export threads-dir log (nth args 1) (and (> (count args) 2) (= (nth args 2) "--force"))) (println "usage: export <out-dir> [--force]"))
  (= cmd "validate") (cmd-validate log)
  (= cmd "watch") (cmd-watch log)
  (= cmd "doctor") (cmd-doctor log)
  (= cmd "show") (cmd-show log (if (> (count args) 1) (nth args 1) ""))
  (= cmd "history") (if (> (count args) 1) (fram.rt/history log (nth args 1)) (println "usage: history <id>"))
  (= cmd "tools") (cmd-tools log)
  (= cmd "query") (if (> (count args) 1) (cmd-query log (nth args 1)) (println "usage: query '<edn>'  e.g. '{:find \"reaches\" :rules [...]}'"))
  (= cmd "call") (if (>= (count args) 3) (cmd-call log (nth args 1) (nth args 2)) (println "usage: call <tool> '<edn-args>'   (run `tools` for the catalog)"))
  (= cmd "set") (if (>= (count args) 4) (cmd-set log (nth args 1) (nth args 2) (nth args 3)) (println "usage: set <id> <pred> <value>"))
  (= cmd "merge") (if (>= (count args) 3) (cmd-merge log (nth args 1) (nth args 2)) (println "usage: merge <from-entity> <to-entity>"))
  (= cmd "tell") (if (>= (count args) 4) (cmd-tell log "assert" (nth args 1) (nth args 2) (nth args 3)) (println "usage: tell <id> <pred> <value>"))
  (or (= cmd "retract") (= cmd "untell")) (if (>= (count args) 4) (cmd-tell log "retract" (nth args 1) (nth args 2) (nth args 3)) (println (str "usage: " cmd " <id> <pred> <value>")))
  :else (println "fram (engine) usage: import | export <out-dir> | show <id> | history <id> | validate | watch | doctor | set <id> <pred> <value> | tell <id> <pred> <value> | retract <id> <pred> <value> (alias: untell) | merge <from> <to> | tools | query <edn> | call <tool> <edn> | selfcheck --deep"))))

(defn -main [& args]
  (dispatch (vec args) (fram.rt/threads-dir) (fram.rt/log-path)))
