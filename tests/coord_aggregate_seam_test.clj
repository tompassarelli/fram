;; coord_aggregate_seam_test.clj — CROSS-LANE SEAM: aggregates + comparison
;; predicates (fram.query/fram.datalog lane) exercised THROUGH the daemon's real
;; execute-query dispatch (coord_daemon.clj lane), in-process — the composition no
;; single lane tested. Mirrors tests/pull_test.clj section (15): boot the daemon's
;; co/schema/cache globals without a socket and drive execute-query via resolve.
;; Proves:
;;   (a) a map-shaped :find (aggregate) through {:op :query} returns correct group
;;       rows AND is stamped :engine "scan" — the use-idx guard's (string? :find)
;;       clause keeps aggregates off idx-run;
;;   (a2) a plain string-:find triple query still earns :engine "index" — the guard
;;       discriminates, it does not disable the fast path;
;;   (b) comparison-predicate rules ({:pred :gt/:le ...}) through {:op :query}
;;       filter correctly (predicate literals are never simple-query?, so scan);
;;   (c) an aggregate over {:op :as-of :seq S} sums the HISTORICAL values (a later
;;       supersede is invisible at S, visible now).
;;   bb -cp out tests/coord_aggregate_seam_test.clj
(require '[fram.store :as c] '[fram.schema :as s]
         '[clojure.java.io :as io])
(load-file "coord.clj")          ; new-coord / register-pred! / commit! land in `user`

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))

(let [loaded? (try (load-file "coord_daemon.clj") true
                   (catch Throwable t
                     (println "FATAL: coord_daemon.clj not loadable standalone —"
                              (.getMessage t))
                     false))]
  (when-not loaded? (System/exit 1)))

;; Daemon globals resolve at runtime (coord_daemon loads after this file's analysis).
(def co-var     (resolve 'co))
(def schema-var (resolve 'schema-view))
(def cache-var  (resolve 'cache))
(def capture    (resolve 'capture-query-roots!))
(def exec       (resolve 'execute-query))

(def dlog "/tmp/agg-seam-test.log")
(io/delete-file dlog true)                       ; fresh corpus every run
(def dco (new-coord dlog))
(register-pred! dco "depends_on" "multi" "ref")
(register-pred! dco "cost" "single" "literal")

;; --- graph: 5 subjects, depends_on edges, numeric cost ----------------------
;; edges: @s1->@s2 @s1->@s3 @s2->@s3 @s4->@s5   (out-degree: @s1=2 @s2=1 @s4=1)
(commit! dco "u" "@s1" "depends_on" :link "@s2" nil)
(commit! dco "u" "@s1" "depends_on" :link "@s3" nil)
(commit! dco "u" "@s2" "depends_on" :link "@s3" nil)
(commit! dco "u" "@s4" "depends_on" :link "@s5" nil)
(commit! dco "u" "@s1" "cost" :assert "10"  nil)
(commit! dco "u" "@s2" "cost" :assert "50"  nil)
(commit! dco "u" "@s3" "cost" :assert "150" nil)
(commit! dco "u" "@s4" "cost" :assert "200" nil)
(def seq0 (:ok (commit! dco "u" "@s5" "cost" :assert "5" nil)))  ; S: all initial costs live

(reset! @co-var dco)                             ; the daemon's global coordinator atom
(reset! @schema-var {})
(reset! @cache-var {:index nil :version -1})

(def deg-rule {:head {:rel "deg" :args [{:var "x"} {:var "y"}]}
               :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}]})
(def cv-rule  {:head {:rel "cv" :args [{:var "x"} {:var "v"}]}
               :body [{:rel "fact" :args [{:var "x"} "cost" {:var "v"}]}]})

;; --- (a) aggregate THROUGH the daemon dispatch ------------------------------
(let [resp (exec {:op :query
                  :query {:find {:rel "deg" :group [0] :agg [{:op :count}]}
                          :rules [deg-rule]}}
                 (capture))]
  (chk "a: grouped :count rows exact (out-degree per source)"
       (= #{["@s1" 2] ["@s2" 1] ["@s4" 1]} (set (:ok resp))))
  (chk "a: exactly three group rows" (= 3 (count (:ok resp))))
  (chk "a: aggregate did NOT take idx-run (:engine != \"index\")"
       (not= "index" (:engine resp)))
  (chk "a: engine stamped \"scan\"" (= "scan" (:engine resp)))
  (chk "a: version stamped" (integer? (:version resp))))

;; --- (a2) control: plain string-:find triple query still earns the fast path -
(let [plain {:find "deg2"
             :rules [{:head {:rel "deg2" :args [{:var "x"} {:var "y"}]}
                      :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}]}
      resp (exec {:op :query :query plain} (capture))]
  (chk "a2: plain triple query still routes to :engine \"index\""
       (= "index" (:engine resp)))
  (chk "a2: index path returns all four edges"
       (= #{["@s1" "@s2"] ["@s1" "@s3"] ["@s2" "@s3"] ["@s4" "@s5"]}
          (set (:ok resp)))))

;; --- (b) comparison predicates THROUGH the daemon dispatch -------------------
(let [big {:head {:rel "big" :args [{:var "x"}]}
           :body [{:rel "fact" :args [{:var "x"} "cost" {:var "c"}]}
                  {:pred :gt :args [{:var "c"} 100]}]}
      resp (exec {:op :query :query {:find "big" :rules [big]}} (capture))]
  (chk "b: :gt filter keeps only cost > 100"
       (= #{["@s3"] ["@s4"]} (set (:ok resp))))
  (chk "b: predicate query runs on the scan engine" (= "scan" (:engine resp))))
(let [cheap {:head {:rel "cheap" :args [{:var "x"}]}
             :body [{:rel "fact" :args [{:var "x"} "cost" {:var "c"}]}
                    {:pred :le :args [{:var "c"} 50]}]}
      resp (exec {:op :query :query {:find "cheap" :rules [cheap]}} (capture))]
  (chk "b: :le filter keeps only cost <= 50"
       (= #{["@s1"] ["@s2"] ["@s5"]} (set (:ok resp)))))

;; --- (c) aggregate over :as-of ----------------------------------------------
;; supersede @s3 cost AFTER S; historical sum at S must not see it
(commit! dco "u" "@s3" "cost" :assert "999" nil)
(let [agg {:find {:rel "cv" :group [] :agg [{:op :sum :arg 1}]} :rules [cv-rule]}
      hist (exec {:op :as-of :seq seq0 :query agg} (capture))
      curr (exec {:op :query :query agg} (capture))]
  (chk "c: historical global :sum at S = 415 (10+50+150+200+5)"
       (= [[415]] (:ok hist)))
  (chk "c: response echoes :as-of seq" (= seq0 (:as-of hist)))
  (chk "c: as-of never takes idx-run" (not= "index" (:engine hist)))
  (chk "c: current global :sum sees the supersede (…+999 = 1264)"
       (= [[1264]] (:ok curr))))

(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\ncoord aggregate/predicate seam —" (count cs) "/" (count cs) "PASS")
    (do (println "\ncoord aggregate/predicate seam:" (count fails) "FAILED of" (count cs))
        (System/exit 1))))
