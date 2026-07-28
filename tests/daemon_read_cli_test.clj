(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.fold :as fold]
         '[fram.kernel :as k]
         '[fram.main :as main]
         '[fram.rt :as rt])

(load-file "bin/fram-fast.clj")

(def failures (atom 0))
(def checks (atom 0))

(defn check! [label pass?]
  (swap! checks inc)
  (if pass?
    (println "PASS" label)
    (do
      (swap! failures inc)
      (println "FAIL" label))))

(def temp-dir
  (.toFile (java.nio.file.Files/createTempDirectory
            "fram-daemon-read-cli-"
            (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (str (io/file temp-dir "coordination.log")))
(def subject "@019fa4d4-93aa-7447-aae5-0a5bcfca6849")
(def projections
  {subject
   [["title" "daemon read test"]
    ["progress" "cli-fix probe"]]
   "@019fa4d4-93aa-7447-aae5-0a5bcfca6850"
   [["title" "second thread"] ["owner" "personal"]]
   "@019fa4d4-93aa-7447-aae5-0a5bcfca6851"
   [["title" "third thread"] ["committed" "2026-07-28"]]
   "@019fa4d4-93aa-7447-aae5-0a5bcfca6852"
   [["title" "fourth thread"] ["do_on" "2026-07-29"]]
   "@019fa4d4-93aa-7447-aae5-0a5bcfca6853"
   [["title" "fifth thread"] ["outcome" "observed result"]]})

(spit log-path
      (apply
       str
       (map-indexed
        (fn [index [target predicate value writer]]
          (str
           (pr-str {:tx (inc index) :op "assert" :l target :p predicate
                    :r value :by writer})
           "\n"))
        (mapcat
         (fn [[target rows]]
           (map (fn [[predicate value]]
                  [target predicate value
                   (if (contains? #{"bar_evidence" "progress" "outcome"} predicate)
                     "lane-probe"
                     "coord")])
                rows))
         projections))))

(def wire-projections
  (let [facts (:facts (fold/fold (rt/read-log log-path)))]
    (into {}
          (map (fn [target]
                 [target
                  (mapv (fn [fact] [(:p fact) (:r fact)])
                        (k/q-by-l facts target))]))
          (keys projections))))
(def projection (get wire-projections subject))

(let [read-called? (atom false)
      scan-called? (atom false)
      warm-output
      (with-redefs [fram-fast/coord-port (constantly 7977)
                    fram-fast/coord-show-for-log
                    (fn [_ _ requested]
                      (when (= subject requested)
                        {:version 2 :rows projection}))
                    fram-fast/matching-ops
                    (fn [& _]
                      (reset! scan-called? true)
                      (throw (ex-info "provenance scan selected" {})))
                    rt/read-log
                    (fn [& _]
                      (reset! read-called? true)
                      (throw (ex-info "whole-log read selected" {})))]
        (with-out-str
          (check! "warm exact show handled"
                  (fram-fast/fast-show! log-path
                                        "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                                        false))))]
  (check! "warm exact show never selects read-log" (not @read-called?))
  (check! "warm exact show never scans provenance logs" (not @scan-called?))
  (check! "warm exact show renders progress directly"
          (and (str/includes? warm-output "  progress  cli-fix probe")
               (not (str/includes? warm-output "· by lane-probe")))))

(let [warm-output
      (with-redefs [fram-fast/coord-port (constantly 7977)
                    fram-fast/coord-show-for-log
                    (fn [_ _ requested]
                      (when (= subject requested)
                        {:version 2 :rows projection}))]
        (with-out-str
          (fram-fast/fast-show! log-path
                                "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                                true)))]
  (check! "explicit provenance keeps the provenance marker"
          (str/includes? warm-output "· by lane-probe")))

(let [show-calls (atom 0)
      sleeps (atom [])
      response {:version 12 :rows projection}
      result
      (with-redefs [fram-fast/coord-show-for-log
                    (fn [& _]
                      (if (= 3 (swap! show-calls inc)) response nil))
                    fram-fast/coord-version-for-log (fn [& _] -1)
                    fram-fast/retry-delays (constantly [100 250 500])
                    fram-fast/*sleep!* #(swap! sleeps conj %)]
        (#'fram-fast/coordinator-show 7977 log-path subject))]
  (check! "unreachable coordinator retries until a complete response"
          (= response result))
  (check! "restart retry uses bounded ordered backoff"
          (= [100 250] @sleeps)))

(let [show-calls (atom 0)
      sleeps (atom [])
      result
      (with-redefs [fram-fast/coord-show-for-log
                    (fn [& _] (swap! show-calls inc) nil)
                    fram-fast/coord-version-for-log (fn [& _] -3)
                    fram-fast/retry-delays (constantly [100 250])
                    fram-fast/*sleep!* #(swap! sleeps conj %)]
        (#'fram-fast/coordinator-show 7977 log-path subject))]
  (check! "reachable incompatible daemon selects cold fallback" (nil? result))
  (check! "reachable incompatible daemon is not retried"
          (and (= 1 @show-calls) (empty? @sleeps))))

(doseq [malformed
        [{:version 1 :rows [["title"]]}
         {:version 1 :rows [["title" "ok"] "torn"]}
         {:version "1" :rows [["title" "ok"]]}
         {:version 1 :rows [["title" 7]]}]]
  (let [result
        (with-redefs [fram-fast/coord-show-for-log (fn [& _] malformed)
                      fram-fast/coord-version-for-log (fn [& _] 1)]
          (#'fram-fast/coordinator-show 7977 log-path subject))]
    (check! (str "malformed daemon response rejected " (pr-str malformed))
            (nil? result))))

(check! "default retry window does not amplify coordinator absence"
        (empty? (#'fram-fast/retry-delays)))

(let [scan-called? (atom false)
      output
      (with-redefs [fram-fast/coord-port (constantly 7977)
                    fram-fast/coord-show-for-log
                    (fn [& _] {:version 13 :rows [["title" "exact fast row"]]})
                    fram-fast/matching-ops
                    (fn [& _]
                      (reset! scan-called? true)
                      (throw (ex-info "provenance scan selected" {})))]
        (with-out-str
          (fram-fast/fast-show!
           log-path "019fa4d4-93aa-7447-aae5-0a5bcfca6849" false)))]
  (check! "plain exact show skips provenance log scan" (not @scan-called?))
  (check! "plain exact show renders daemon rows directly"
          (= "  title  exact fast row\n" output)))

(doseq [[target rows] wire-projections]
  (let [bare (subs target 1)
        warm-output
        (with-redefs [fram-fast/coord-port (constantly 7977)
                      fram-fast/coord-show-for-log
                      (fn [_ _ requested]
                        (when (= requested target)
                          {:version 10 :rows rows}))]
          (with-out-str
            (fram-fast/fast-show! log-path bare false)))
        direct-output
        (apply str
               (map (fn [[predicate value]]
                      (str "  " predicate "  " value "\n"))
                    rows))]
    (check! (str "warm exact show renders daemon rows directly for " bare)
            (= warm-output direct-output))))

(let [read-called? (atom false)
      write-call (atom nil)
      output
      (with-redefs [fram-fast/coord-port (constantly 7977)
                    fram-fast/coord-version-for-log (fn [_ _] 8)
                    fram-fast/coord-assert-for-log
                    (fn [port log subject predicate value version]
                      (reset! write-call
                              [port log subject predicate value version])
                      "ok:9")
                    rt/read-log
                    (fn [& _]
                      (reset! read-called? true)
                      (throw (ex-info "whole-log read selected" {})))]
        (with-out-str
          (check! "unambiguous literal write handled"
                  (fram-fast/fast-write!
                   log-path "assert"
                   "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                   "progress" "cli-fix probe"))))]
  (check! "warm write never selects read-log" (not @read-called?))
  (check! "warm write keeps exact fenced coordinator request"
          (= [7977 log-path subject "progress" "cli-fix probe" 8]
             @write-call))
  (check! "warm write keeps CLI receipt"
          (str/includes? output
                         "committed via coordinator (v9): 019fa4d4-93aa-7447-aae5-0a5bcfca6849 progress = cli-fix probe")))

(let [read-called? (atom false)
      output
      (with-redefs [fram-fast/coord-port (constantly 7977)
                    fram-fast/coord-version-for-log (fn [& _] 8)
                    fram-fast/coord-assert-for-log (fn [& _] "reject:cycle")
                    rt/read-log
                    (fn [& _]
                      (reset! read-called? true)
                      (throw (ex-info "rejected write selected fallback" {})))]
        (with-out-str
          (check! "coordinator rejection is terminal"
                  (fram-fast/fast-write!
                   log-path "assert"
                   "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                   "progress" "rejected probe"))))]
  (check! "coordinator rejection never falls through to a cold write"
          (not @read-called?))
  (check! "coordinator rejection remains visible"
          (str/includes? output "REJECTED by coordinator: reject:cycle")))

(check! "ambiguous single-token write preserves cold normalization fallback"
        (false? (fram-fast/fast-write!
                 log-path "assert"
                 "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                 "depends_on" "possibly-a-ref")))

(let [write-call (atom nil)
      output
      (with-redefs [fram-fast/coord-port (constantly 7977)
                    fram-fast/coord-write-existing-for-log
                    (fn [operation port log requested predicate value]
                      (reset! write-call
                              [operation port log requested predicate value])
                      "ok:22")]
        (with-out-str
          (check! "existing exact write handled in one client"
                  (= :handled
                     (fram-fast/fast-write-existing!
                      log-path "assert"
                      "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                      "progress" "one client")))))]
  (check! "existing exact write uses one atomic coordinator operation"
          (= [:assert 7977 log-path subject "progress" "one client"]
             @write-call))
  (check! "existing exact write keeps CLI receipt"
          (str/includes?
           output
           "committed via coordinator (v22): 019fa4d4-93aa-7447-aae5-0a5bcfca6849 progress = one client")))

(let [request (atom nil)
      response
      (with-redefs [fram-fast/coord-request-for-log
                    (fn [_ _ sent]
                      (reset! request sent)
                      {:ok 23})]
        (#'fram-fast/coord-write-existing-for-log
         :retract 7977 log-path subject "progress" "one client"))]
  (check! "existing write sends one atomic coordinator request"
          (and (= "ok:23" response)
               (= {:op :retract-existing
                   :te subject
                   :p "progress"
                   :r "one client"
                   :frame "agent"}
                  @request))))

(let [response
      (with-redefs [fram-fast/coord-request-for-log
                    (fn [& _] {:error "unknown op"})]
        (#'fram-fast/coord-write-existing-for-log
         :assert 7977 log-path subject "progress" "one client"))]
  (check! "old daemon rejects atomic op before any legacy mutation"
          (= "protocol-incompatible" response)))

(let [write-call (atom nil)]
  (with-redefs [fram-fast/coord-port (constantly 7977)
                fram-fast/coordinator-show
                (fn [& _]
                  (throw
                   (ex-info
                    "bare-token exact write performed a separate show"
                    {})))
                fram-fast/coord-write-existing-for-log
                (fn [operation port log requested predicate value]
                  (reset! write-call
                          [operation port log requested predicate value])
                  "ok:24")]
    (check! "bare-token exact write stays on the atomic coordinator path"
            (= :handled
               (fram-fast/fast-write-existing!
                log-path "assert"
                "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                "outcome" "done")))
    (check! "bare-token exact write requests in-lock normalization"
            (= [:assert 7977 log-path subject "outcome" "done"]
               @write-call))))

(let [write-called? (atom false)]
  (with-redefs [fram-fast/coord-port (constantly 7977)
                fram-fast/coord-write-existing-for-log
                (fn [& _]
                  (reset! write-called? true)
                  "missing-subject")]
    (check! "missing exact subject is refused before write"
            (= :missing
               (fram-fast/fast-write-existing!
                log-path "assert"
                "019fa4d4-93aa-7447-aae5-0a5bcfca6800"
                "progress" "missing probe")))
    (check! "missing exact subject uses the atomic coordinator seam"
            @write-called?)))

(check! "daemon absence preserves cold show fallback signal"
        (with-redefs [fram-fast/coord-show-for-log (fn [& _] nil)
                      fram-fast/coord-version-for-log (fn [& _] -1)
                      fram-fast/retry-delays (constantly [])]
          (false? (fram-fast/fast-show!
                   log-path
                   "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                   false))))

(let [scan-called? (atom false)
      output
      (with-redefs [fram-fast/coord-show-for-log
                    (fn [& _] {:version 15 :rows []})
                    fram-fast/relevant-ops
                    (fn [& _]
                      (reset! scan-called? true)
                      (throw (ex-info "missing exact id scanned history" {})))]
        (with-out-str
          (check! "missing exact UUID is handled by the daemon response"
                  (fram-fast/fast-show!
                   log-path
                   "019fa4d4-93aa-7447-aae5-0a5bcfca6800"
                   false))))]
  (check! "missing exact UUID never scans history" (not @scan-called?))
  (check! "missing exact UUID keeps the no-facts response"
          (str/includes?
           output
           "no facts for @019fa4d4-93aa-7447-aae5-0a5bcfca6800")))

(check! "substring show preserves cold fallback on an empty daemon projection"
        (with-redefs [fram-fast/coord-show-for-log
                      (fn [& _] {:version 16 :rows []})]
          (false? (fram-fast/fast-show! log-path "019fa4d4" false))))

(let [requested (atom nil)
      scan-called? (atom false)
      output
      (with-redefs [fram-fast/coord-show-for-log
                    (fn [_ _ subject]
                      (reset! requested subject)
                      {:version 17 :rows [["lease" "holder|9999999999999|1"]]})
                    fram-fast/relevant-ops
                    (fn [& _]
                      (reset! scan-called? true)
                      (throw (ex-info "explicit non-thread ref scanned history" {})))]
        (with-out-str
          (check! "explicit non-thread subject uses the daemon show path"
                  (fram-fast/fast-show!
                   log-path "@lease:session:native-probe" false))))]
  (check! "explicit non-thread subject is passed through without prefix classification"
          (= "@lease:session:native-probe" @requested))
  (check! "explicit non-thread subject never scans history" (not @scan-called?))
  (check! "explicit non-thread subject renders daemon rows directly"
          (str/includes? output "  lease  holder|9999999999999|1")))

(let [requested (atom nil)]
  (check! "bare existing non-thread subject uses the daemon show path"
          (with-redefs [fram-fast/coord-show-for-log
                        (fn [_ _ subject]
                          (reset! requested subject)
                          {:version 18 :rows [["agent_death" "dead"]]})]
            (fram-fast/fast-show! log-path "swarm" false)))
  (check! "bare existing non-thread subject probes the canonical ref"
          (= "@swarm" @requested)))

(check! "missing bare non-UUID token preserves the resolver fallback"
        (with-redefs [fram-fast/coord-show-for-log
                      (fn [& _] {:version 19 :rows []})]
          (false? (fram-fast/fast-show! log-path "missing-prefix" false))))

(let [fallback-args (atom nil)]
  (with-redefs [fram-fast/coordinator-show (fn [& _] nil)
                fram-fast/cold-main! #(reset! fallback-args %)]
    (fram-fast/-main "show" "019fa4d4"))
  (check! "substring show preserves full resolver fallback"
          (= ["show" "019fa4d4"] @fallback-args)))

;; ---- query fast path -------------------------------------------------------
;; fram.main/cmd-query folds the whole log before running the query, so cost was
;; the fold, not the result: a predicate matching ZERO rows measured 13.1s. The
;; daemon already answers :query over its live snapshot.

(def sample-query "{:find \"agg\" :rules []}")

(let [read-called? (atom false)
      out (with-redefs [fram-fast/coord-port (constantly 7977)
                        fram-fast/coord-query-for-log
                        (fn [_ _ q]
                          (when (= {:find "agg" :rules []} q)
                            {:ok [["@a" "ran"] ["@b" "died"]] :version 9}))
                        rt/read-log
                        (fn [& _]
                          (reset! read-called? true)
                          (throw (ex-info "whole-log read selected" {})))]
            (with-out-str
              (check! "warm query handled"
                      (fram-fast/fast-query! log-path sample-query))))]
  (check! "warm query never selects read-log" (not @read-called?))
  (check! "warm query renders rows with the cold two-space prefix"
          (and (str/includes? out "  [\"@a\" \"ran\"]")
               (str/includes? out "  [\"@b\" \"died\"]"))))

(let [out (with-redefs [fram-fast/coord-port (constantly 7977)
                        fram-fast/coord-query-for-log (fn [& _] {:ok [] :version 9})]
            (with-out-str (fram-fast/fast-query! log-path sample-query)))]
  (check! "empty result matches cold's (no results)"
          (str/includes? out "  (no results)")))

;; Every path the cold renderer must still own, so its diagnostics stay
;; byte-identical rather than being half-reproduced here.
(check! "unreachable daemon leaves query to cold"
        (false? (with-redefs [fram-fast/coord-port (constantly 7977)
                              fram-fast/coord-query-for-log (fn [& _] nil)]
                  (fram-fast/fast-query! log-path sample-query))))

(check! "daemon-reported query errors leave query to cold"
        (false? (with-redefs [fram-fast/coord-port (constantly 7977)
                              fram-fast/coord-query-for-log
                              (fn [& _] {:error ["unbound var ?x"]})]
                  (fram-fast/fast-query! log-path sample-query))))

(let [contacted? (atom false)]
  (check! "unparseable EDN leaves query to cold"
          (false? (with-redefs [fram-fast/coord-port (constantly 7977)
                                fram-fast/coord-query-for-log
                                (fn [& _] (reset! contacted? true) {:ok []})]
                    (fram-fast/fast-query! log-path "{:find"))))
  (check! "unparseable EDN never contacts the daemon" (not @contacted?)))

(let [fallback-args (atom nil)]
  (with-redefs [fram-fast/coord-query-for-log (fn [& _] nil)
                fram-fast/cold-main! #(reset! fallback-args %)]
    (fram-fast/-main "query" sample-query))
  (check! "query fallback reaches cold-main with its original args"
          (= ["query" sample-query] @fallback-args)))

;; --- the cold fallback must be explicable ----------------------------------
;; Falling back to the whole-log fold is a 20x+ latency cliff that still
;; returns a CORRECT answer, so nothing in the result reveals it happened. On
;; 2026-07-29 `north query` measured 13s while the same query through the daemon
;; measured 650ms, and the entire difference was an invisible fallback.
(let [out (with-out-str
            (binding [*err* *out*]
              (with-redefs [fram-fast/coord-port (constantly 7977)
                            fram-fast/debug-enabled? (constantly true)
                            fram-fast/coord-request-for-log
                            (fn [& _] {:reject ["log mismatch: client expects /a but coordinator serves /b"]})]
                (fram-fast/fast-query! log-path sample-query))))]
  (check! "a rejected request names the rejection at DEBUG"
          (and (str/includes? out "falling back to the cold path")
               (str/includes? out "log mismatch"))))

(let [out (with-out-str
            (binding [*err* *out*]
              (with-redefs [fram-fast/coord-port (constantly 7977)
                            fram-fast/debug-enabled? (constantly true)
                            fram-fast/coord-request-for-log
                            (fn [& _] (throw (ex-info "read timed out" {})))]
                (fram-fast/fast-query! log-path sample-query))))]
  (check! "a thrown probe names the exception at DEBUG"
          (and (str/includes? out "falling back to the cold path")
               (str/includes? out "read timed out"))))

;; Silent by default: this is a hot path, and a line per query would be its own
;; performance problem.
(let [out (with-out-str
            (binding [*err* *out*]
              (with-redefs [fram-fast/coord-port (constantly 7977)
                            fram-fast/debug-enabled? (constantly false)
                            fram-fast/coord-request-for-log
                            (fn [& _] (throw (ex-info "read timed out" {})))]
                (fram-fast/fast-query! log-path sample-query))))]
  (check! "silent at default verbosity" (not (str/includes? out "falling back"))))

;; A SUCCESSFUL warm query must never claim it fell back.
(let [out (with-out-str
            (binding [*err* *out*]
              (with-redefs [fram-fast/coord-port (constantly 7977)
                            fram-fast/debug-enabled? (constantly true)
                            fram-fast/coord-request-for-log
                            (fn [& _] {:ok [["@a" "ran"]] :version 9})]
                (fram-fast/fast-query! log-path sample-query))))]
  (check! "the warm path reports no fallback" (not (str/includes? out "falling back"))))

;; The dispatch case itself. Without this, deleting the `query` branch from
;; -main leaves every other check above green — the function is exercised
;; directly, and the fallback check passes identically whether the branch
;; exists or not, because an unhandled command also lands in cold-main!.
(let [reached-cold? (atom false)
      out (with-redefs [fram-fast/coord-port (constantly 7977)
                        fram-fast/coord-query-for-log
                        (fn [& _] {:ok [["@a" "ran"]] :version 9})
                        fram-fast/cold-main! (fn [_] (reset! reached-cold? true))]
            (with-out-str (fram-fast/-main "query" sample-query)))]
  (check! "-main routes query to the warm path, not cold" (not @reached-cold?))
  (check! "-main query renders the daemon rows"
          (str/includes? out "  [\"@a\" \"ran\"]")))

(println (format "daemon_read_cli: %d / %d PASS"
                 (- @checks @failures) @checks))
(System/exit (if (zero? @failures) 0 1))
