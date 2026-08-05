#!/usr/bin/env bb
;; ============================================================================
;; show_reporter_join_test.clj — thread 019f9ab9: `show` must name the lane that
;; REPORTED an evidence observation, not the server that appended the
;; projection for it.
;;
;; A thread's bar_evidence fact is a non-authoritative projection written by the
;; server, so its :by is always "database". The mechanically bound proof is the
;; run subject's `run_bar_evidence` JSON (run/thread/reporter/bar/observed);
;; cmd-show joins the projection literal — exactly (bar " → " observed) — back to
;; it at read time. This test pins the join AND its three honest degradations.
;;
;;   bb -cp out tests/show_reporter_join_test.clj      (from the repo ROOT)
;; ============================================================================
(require '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root (System/getProperty "user.dir"))
(def tmp (str (System/getProperty "java.io.tmpdir") "/show-reporter-join-" (System/nanoTime)))
(def log (str tmp "/facts.log"))
(.mkdirs (io/file tmp))

(def thread "@019f9ab9-0000-7000-8000-000000000001")
(def lane-a "@agent:lane-alpha")
(def lane-b "@agent:lane-beta")
(def bar-a "bar A")   (def obs-a "lane A observed exit 0")
(def bar-b "bar B")   (def obs-b "lane B observed exit 0")
(def bar-c "bar C")   (def obs-c "identical observation text")

(def tx (atom 0))
(defn- line [l p r by]
  (str (pr-str {:tx (swap! tx inc) :op "assert" :l l :p p :r r :by by}) "\n"))
(defn- projection [bar observed] (str bar " → " observed))
(defn- run-record [run reporter bar observed]
  (json/generate-string
   (sorted-map "bar" bar "observed" observed
               "recordedAt" "2026-07-26T00:00:00Z"
               "reporter" reporter "run" run "thread" thread
               "version" "north:run-bar-evidence:v1")))

;; One flat log: the thread projections (all written by database, as north writes
;; them) plus the run subjects that carry the true reporters.
(spit log
      (str
       (line thread "title" "reporter-join test thread" "database")
       (line thread "bar_evidence" (projection bar-a obs-a) "database")
       (line thread "bar_evidence" (projection bar-b obs-b) "database")
       (line thread "bar_evidence" (projection bar-c obs-c) "database")
       ;; degradation 1: a projection with no run record behind it
       (line thread "bar_evidence" "bar D → hand-written, no run record" "database")
       (line "@run:alpha-1" "run_bar_evidence" (run-record "@run:alpha-1" lane-a bar-a obs-a) "database")
       (line "@run:beta-1"  "run_bar_evidence" (run-record "@run:beta-1"  lane-b bar-b obs-b) "database")
       ;; degradation 2: two lanes, identical bar AND observation -> one thread
       ;; literal, two candidate reporters -> unresolvable by construction
       (line "@run:alpha-2" "run_bar_evidence" (run-record "@run:alpha-2" lane-a bar-c obs-c) "database")
       (line "@run:beta-2"  "run_bar_evidence" (run-record "@run:beta-2"  lane-b bar-c obs-c) "database")
       ;; degradation 3: a malformed run record must be skipped, never crash
       (line "@run:broken" "run_bar_evidence" "{not json" "database")))

;; FRAM_SERVER_PORT=1 forces the cold read: no daemon, this log only.
(def out
  (:out (proc/shell {:out :string :err :string :continue true :dir root
                     :extra-env {"FRAM_LOG" log "FRAM_THREADS" (str tmp "/threads")
                                 "FRAM_TELEMETRY_LOG" "" "FRAM_SERVER_PORT" "1"}}
                    "bb" "-cp" "out" "-m" "fram.main" "show" (subs thread 1))))

(def fails (atom 0))
(defn check [label ok?]
  (println (str "  " (if ok? "PASS" "FAIL") " — " label))
  (when-not ok? (swap! fails inc)))
(defn marked [pat]
  (or (first (filter #(str/includes? % pat) (str/split-lines out))) ""))

(println "show output:\n" out)
(check "lane A's evidence names lane-alpha"
       (str/includes? (marked (projection bar-a obs-a)) "· by lane-alpha via database"))
(check "lane B's evidence names lane-beta"
       (str/includes? (marked (projection bar-b obs-b)) "· by lane-beta via database"))
(check "neither evidence fact claims the other lane"
       (and (not (str/includes? (marked (projection bar-a obs-a)) "beta"))
            (not (str/includes? (marked (projection bar-b obs-b)) "alpha"))))
(check "no run record -> writer only, no reporter claimed"
       (let [l (marked "bar D →")]
         (and (str/includes? l "· by database") (not (str/includes? l "lane-")))))
(check "run records disagree -> ambiguous, no lane claimed"
       (let [l (marked (projection bar-c obs-c))]
         (and (str/includes? l "reporter ambiguous") (not (str/includes? l "lane-")))))
(check "malformed run record is skipped, show still renders"
       (str/includes? out "title  reporter-join test thread"))
(check "non-evidence facts carry no marker"
       (not (str/includes? (marked "title  reporter-join") "·")))

(println (str (if (zero? @fails) "OK" (str @fails " FAILURE(S)")) " — " log))
(System/exit (if (zero? @fails) 0 1))
