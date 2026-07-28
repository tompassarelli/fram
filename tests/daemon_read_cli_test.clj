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
      warm-output
      (with-redefs [rt/coord-port (constantly 7977)
                    rt/coord-show-for-log
                    (fn [_ _ requested]
                      (when (= subject requested)
                        {:version 2 :rows projection}))
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
  (check! "warm exact show keeps provenance marker"
          (str/includes? warm-output "· by lane-probe")))

(let [show-calls (atom 0)
      sleeps (atom [])
      response {:version 12 :rows projection}
      result
      (with-redefs [rt/coord-show-for-log
                    (fn [& _]
                      (if (= 3 (swap! show-calls inc)) response nil))
                    rt/coord-version-for-log (fn [& _] -1)
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
      (with-redefs [rt/coord-show-for-log
                    (fn [& _] (swap! show-calls inc) nil)
                    rt/coord-version-for-log (fn [& _] -3)
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
        (with-redefs [rt/coord-show-for-log (fn [& _] malformed)
                      rt/coord-version-for-log (fn [& _] 1)]
          (#'fram-fast/coordinator-show 7977 log-path subject))]
    (check! (str "malformed daemon response rejected " (pr-str malformed))
            (nil? result))))

(check! "default retry window does not amplify coordinator absence"
        (empty? (#'fram-fast/retry-delays)))

(let [scan-called? (atom false)
      output
      (with-redefs [rt/coord-port (constantly 7977)
                    rt/coord-show-for-log
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
        (with-redefs [rt/coord-port (constantly 7977)
                      rt/coord-show-for-log
                      (fn [_ _ requested]
                        (when (= requested target)
                          {:version 10 :rows rows}))]
          (with-out-str
            (fram-fast/fast-show! log-path bare false)))
        cold-output
        (with-redefs [rt/coord-live-facts (fn [& _] [])]
          (with-out-str
            (main/cmd-show log-path bare false)))]
    (check! (str "warm exact show is byte-identical for " bare)
            (= warm-output cold-output))))

(let [read-called? (atom false)
      write-call (atom nil)
      output
      (with-redefs [rt/coord-port (constantly 7977)
                    rt/coord-version-for-log (fn [_ _] 8)
                    rt/coord-assert-for-log
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
      (with-redefs [rt/coord-port (constantly 7977)
                    rt/coord-version-for-log (fn [& _] 8)
                    rt/coord-assert-for-log (fn [& _] "reject:cycle")
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

(check! "daemon absence preserves cold show fallback signal"
        (with-redefs [rt/coord-show-for-log (fn [& _] nil)
                      rt/coord-version-for-log (fn [& _] -1)
                      fram-fast/retry-delays (constantly [])]
          (false? (fram-fast/fast-show!
                   log-path
                   "019fa4d4-93aa-7447-aae5-0a5bcfca6849"
                   false))))

(let [fallback-args (atom nil)]
  (with-redefs [fram-fast/coordinator-show (fn [& _] nil)
                fram-fast/cold-main! #(reset! fallback-args %)]
    (fram-fast/-main "show" "019fa4d4"))
  (check! "substring show preserves full resolver fallback"
          (= ["show" "019fa4d4"] @fallback-args)))

(println (format "daemon_read_cli: %d / %d PASS"
                 (- @checks @failures) @checks))
(System/exit (if (zero? @failures) 0 1))
