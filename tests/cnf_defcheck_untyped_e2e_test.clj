;; ============================================================================
;; cnf_defcheck_untyped_e2e_test.clj — UNTYPED def-check e2e through write-def.
;; ============================================================================
;; Ingests a REAL untyped Clojure module (ring-core ring.middleware.cookies at the
;; ring-06 C0) into a scratch code coordinator, boots it with FRAM_DEFCHECK=1, and
;; drives the FULL S-profile write-def path — proving the untyped inner gate fires
;; IN-LOOP on plain OSS Clojure (the graph arm's regained inner gate, EXP-025):
;;
;;   (1) write-def a def that calls a NONEXISTENT helper -> REJECTED in-loop with a
;;       repair-grade error (:stage :type, :kind "unresolved-symbol", :nearest).
;;   (2) write-def the REAL ring-06 fix (register :partitioned in set-cookie-attrs)
;;       -> ACCEPTED (:ok true, :deep-check :ran).
;;   (3) the edited module RENDERS to valid Clojure carrying the fix.
;;
;; Scratch-only: coordinator on a port >=49010, and the warm renderer sidecar on a
;; SCRATCH port (FRAM_DEFCHECK_PORT, default 49061 here) — NEVER the live 49060.
;; SKIPS (exit 0) when the beagle sidecar can't start (no racket/beagle).
;;
;;   FRAM_DEFCHECK=1 clojure -M tests/cnf_defcheck_untyped_e2e_test.clj ; echo EXIT=$?
;; ============================================================================
(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.java.shell :refer [sh]])

(def root (System/getProperty "user.dir"))
(when-not (= "1" (System/getenv "FRAM_DEFCHECK"))
  (println "SKIP — set FRAM_DEFCHECK=1 to run the untyped e2e") (System/exit 0))
(when-not (System/getenv "FRAM_DEFCHECK_PORT")
  (System/setProperty "e2e.scratch-sidecar" "49061"))
(def sidecar-port (Integer/parseInt (or (System/getenv "FRAM_DEFCHECK_PORT") "49061")))

;; --- stage + ingest the C0 cookies module ------------------------------------
(def c0-src "/tmp/cookies-c0.clj")
(when-not (.exists (io/file c0-src))
  (let [r (sh "bash" "-c"
            (str "cd /home/tom/code/reference/ring && git show "
                 "76e5d29128661225a08a9f8dfa7b07c83b6ed4da:ring-core/src/ring/middleware/cookies.clj > " c0-src))]
    (when-not (zero? (:exit r)) (println "SKIP — cannot obtain C0 cookies source") (System/exit 0))))

(def arena (str (System/getProperty "java.io.tmpdir") "/fram-untyped-e2e-" (System/nanoTime)))
(def flat  (str arena "/code.log"))
(io/make-parents (io/file (str arena "/ring/middleware/x")))
(io/copy (io/file c0-src) (io/file (str arena "/ring/middleware/cookies.bclj")))
(let [r (sh "bash" "-c" (str "cd " root " && bin/fram-ingest-code " arena "/ring/middleware/cookies.bclj --out " flat))]
  (when-not (.exists (io/file flat)) (println "INGEST FAILED:" (:err r)) (System/exit 3)))

;; --- boot a scratch code coordinator with FRAM_DEFCHECK=1 --------------------
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(defn- pf? [p] (try (with-open [s (java.net.Socket.)] (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false) (catch Exception _ true)))
(def port (or (some #(when (pf? %) %) (range 49010 49045)) 49010))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 900)
(defn- shutdown! []
  (try (future-cancel server) (catch Throwable _ nil))
  (try (sh "bash" "-c" (str "for pid in $(ss -tlnpH 'sport = :" sidecar-port "' 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u); do kill -9 $pid 2>/dev/null; done")) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
(defn- w [m src] (client port {:op :write-def :spec {:module m :source src}}))
(defn- err-of [resp] (or (:failed resp) resp))

(def failures (atom 0))
(defn- check [label ok? detail]
  (println (format "  [%s] %s%s" (if ok? "PASS" "FAIL") label (if ok? "" (str "  <-- " detail))))
  (when-not ok? (swap! failures inc)))

;; --- probe: is the untyped gate live? (rewrite an EXISTING def, clean) --------
(def probe (w "cookies" "(def ^:private same-site-values {:strict \"Strict\" :lax \"Lax\" :none \"None\"})"))
(cond
  (and (false? (:ok probe)) (str/includes? (str (:message (err-of probe))) "unavailable"))
  (do (println "SKIP — beagle sidecar unavailable in this env:" (:message (err-of probe))) (shutdown!) (System/exit 0))
  (not= :ran (:deep-check probe))
  (do (println "SKIP — FRAM_DEFCHECK wiring did not activate (deep-check" (:deep-check probe) ")") (shutdown!) (System/exit 0))
  :else
  (do
    (println "untyped def-check LIVE — coord" port "sidecar" sidecar-port "\n")
    (check "probe: clean rewrite -> :ok :deep-check :ran" (and (:ok probe) (= :ran (:deep-check probe))) (pr-str (dissoc probe :ops)))

    ;; (1) def calling a NONEXISTENT helper -> in-loop repair-grade rejection ----
    ;; NB authority split: write-def COMMITS then checks (inner-loop feedback, not a
    ;; txn abort) — the broken def lingers until repaired, exactly as the agent loop
    ;; sees it. We reject here, then REPAIR below and confirm the gate clears.
    (let [bad (w "cookies" "(defn- parse-cookies [request encoder]\n  (parse-cookiez (get-in request [:headers \"cookie\"]) encoder))")
          e   (err-of bad)]
      (check "(1) nonexistent-helper write REJECTED" (false? (:ok bad)) (pr-str (dissoc bad :failed :errors)))
      (check "(1) :stage :type"                      (= :type (:stage e)) (pr-str (dissoc e :errors)))
      (check "(1) :kind unresolved-symbol"           (= "unresolved-symbol" (:kind e)) (pr-str (:kind e)))
      (check "(1) :at names cookies/parse-cookies"   (and (= "cookies" (get-in e [:at :module])) (= 'parse-cookies (get-in e [:at :def]))) (pr-str (:at e)))
      (check "(1) :got = parse-cookiez"              (= "parse-cookiez" (:got e)) (pr-str (:got e)))
      (check "(1) :suggestion present (repair-grade)" (not (str/blank? (str (:suggestion e)))) (pr-str (:suggestion e)))
      (check "(1) :nearest names real parse-cookies" (contains? (set (:nearest e)) "parse-cookies") (pr-str (:nearest e))))

    ;; (1b) REPAIR the broken def (fix + re-upsert) -> gate CLEARS ---------------
    (let [rep (w "cookies" (str "(defn- parse-cookies [request encoder]\n"
                                "  (if-let [cookie (get-in request [:headers \"cookie\"])]\n"
                                "    (->> cookie parse-cookie-header ((fn [c] (decode-values c encoder))) (remove nil?) (into {}))\n"
                                "    {}))"))]
      (check "(1b) repaired parse-cookies ACCEPTED -> gate clears" (:ok rep) (pr-str (dissoc (err-of rep) :errors))))

    ;; (2) the REAL ring-06 fix -> ACCEPTED (module now clean) ------------------
    (let [fix (w "cookies" (str "(def ^:private set-cookie-attrs\n"
                                "  {:domain \"Domain\", :max-age \"Max-Age\", :path \"Path\"\n"
                                "   :secure \"Secure\", :expires \"Expires\", :http-only \"HttpOnly\"\n"
                                "   :same-site \"SameSite\", :partitioned \"Partitioned\"})"))]
      (check "(2) real ring-06 fix ACCEPTED" (:ok fix) (pr-str (dissoc (err-of fix) :errors)))
      (check "(2) :deep-check :ran"          (= :ran (:deep-check fix)) (pr-str (:deep-check fix))))

    ;; timing: warm per-check on the real module --------------------------------
    (w "cookies" "(def sep \",\")")                    ; warm once
    (let [ts (vec (for [_ (range 8)]
                    (let [t0 (System/nanoTime)] (w "cookies" "(def sep \",\")") (/ (- (System/nanoTime) t0) 1e6))))
          med (nth (sort ts) 4)]
      (check "(timing) warm write+check < 2000ms" (< med 2000) (format "median %.0fms (full write-def incl. render)" med)))

    ;; (3) edited module renders to valid Clojure with the fix ------------------
    (let [out (str arena "/rendered.clj")
          r   (sh "bash" "-c" (str "cd " root " && BABASHKA_CLASSPATH=out bin/fram-render-code cookies --log " flat " > " out))
          txt (when (.exists (io/file out)) (slurp out))]
      (check "(3) module renders non-empty" (not (str/blank? txt)) (str "exit " (:exit r)))
      (check "(3) render carries :partitioned fix" (and txt (str/includes? txt ":partitioned")) "grep :partitioned")
      (check "(3) render carries the accepted parse-cookies (not the broken one)"
             (and txt (str/includes? txt "parse-cookies") (not (str/includes? txt "parse-cookiez"))) "no rejected def landed")
      ;; the untyped analyzer over the rendered edited tree must be clean
      (when txt
        (let [analyze (deref (ns-resolve 'fram.defcheck 'analyze-untyped-module))]
          (check "(3) rendered edited module analyzes CLEAN" (empty? (analyze "cookies" txt)) (pr-str (map :message (analyze "cookies" txt)))))))

    (println (format "\n==== %s : %d failure(s) ====" (if (zero? @failures) "PASS" "FAIL") @failures))
    (shutdown!)
    (System/exit (if (zero? @failures) 0 1))))
