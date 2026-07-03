;; ============================================================================
;; cnf_write_def_test.clj — thread A1 acceptance: S-profile text-bridge verbs.
;; ============================================================================
;; Boots ONE warm code daemon over a /tmp COPY of .fram/code.log on a verified-free
;; port >= 49010 (NEVER 7977/48942/48950 — live coordinators), then drives the new
;; write-def / read-def / index wire verbs THROUGH the socket, asserting each of the
;; spec's known authoring fumbles either canonicalizes OR returns a structured error
;; whose :suggestion names the fix. Runs on the JVM (clojure -M) so the reader is the
;; SAME LispReader production uses (bb's SCI reader differs on #(..)/::).
;;
;;   clojure -M tests/cnf_write_def_test.clj > /tmp/a1-selftest.out 2>&1; echo EXIT=$?
;;
;; When tests/fixtures/authoring-fumbles.edn (thread A0) lands, this runner also folds
;; every fixture in (see the fixtures section at the end).
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io])

(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log))
  (println "SKIP — no .fram/code.log (run bin/fram-ingest-code first)") (System/exit 0))

(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

;; --- throwaway daemon over a /tmp COPY, on a free port >= 49010 -------------
(def flat (str (System/getProperty "java.io.tmpdir") "/write-def-test-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) (range 49010 49040)) 49010))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 600)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))

(def status (client port {:op :status}))
(when-not (and (= flat (str (:log status))) (pos? (:claims status)))
  (println "ABORT: daemon serves" (pr-str (:log status)) "expected" flat) (shutdown!) (System/exit 1))
(println "daemon up:" (:claims status) "claims, port=" port ", log=" flat "\n")

;; --- tiny assertion harness -------------------------------------------------
(def failures (atom 0))
(defn- check [label ok? detail]
  (println (format "  [%s] %s%s" (if ok? "PASS" "FAIL") label (if ok? "" (str "  <-- " detail))))
  (when-not ok? (swap! failures inc)))
(defn- w [module source] (client port {:op :write-def :spec {:module module :source source}}))
(defn- r [module name]   (client port {:op :read-def  :spec {:module module :name name}}))
(defn- idx [& [module]]  (client port {:op :index     :spec (when module {:module module})}))
;; the ERROR shape: per-form errors ride under :failed (a form was rejected mid-write);
;; top-level errors (bad module / parse failure / prose) ARE the resp itself.
(defn- err-of [resp] (or (:failed resp) resp))

(def M "schema")   ; a real ingested module

(println "=== index ===")
(let [all (idx)]
  (check "index lists modules (incl schema)"
         (and (:ok all) (some #(str/includes? % M) (:modules all)))
         (pr-str all)))
(let [si (idx M)]
  (check "index schema returns defs with sigs"
         (and (:ok si) (pos? (:count si)) (every? :name (:defs si)))
         (pr-str (dissoc si :defs))))

(println "\n=== write-def happy path + read-def round-trip ===")
(let [resp (w M "(defn a1-selftest-inc [x :- Int] :- Int (+ x 1))")]
  (check "write typed defn -> :ok" (and (:ok resp) (= 1 (:written resp))) (pr-str resp))
  (check "deep-check status is surfaced (never silent)"
         (contains? #{:deferred :ran} (:deep-check resp)) (pr-str (:deep-check resp))))
(let [resp (r M "a1-selftest-inc")]
  (check "read-def returns source text" (and (:ok resp) (str/includes? (str (:source resp)) "a1-selftest-inc")) (pr-str resp))
  (check "read-def derives sig (Int -> Int)" (= "(Int -> Int)" (:sig resp)) (pr-str (:sig resp))))

(println "\n=== FUMBLE: #(...) lambda -> (fn ...) ===")
(let [resp (w M "(def a1-selftest-lam #(+ % 1))")]
  (check "#() def canonicalizes -> :ok" (:ok resp) (pr-str resp)))
(let [resp (r M "a1-selftest-lam")]
  (check "#() rendered back as (fn ...), not #(...)"
         (and (:ok resp) (str/includes? (str (:source resp)) "(fn") (not (str/includes? (str (:source resp)) "#(")))
         (pr-str (:source resp))))

(println "\n=== FUMBLE: ::kw -> pointed reject ===")
(let [resp (w M "(def a1-selftest-kw ::foo)")
      e (err-of resp)]
  (check "::kw write fails closed" (false? (:ok resp)) (pr-str resp))
  (check "::kw error is :stage :canon" (= :canon (:stage e)) (pr-str e))
  (check "::kw :suggestion names the fix (:foo)" (str/includes? (str (:suggestion e)) ":foo") (pr-str e)))

(println "\n=== FUMBLE: (Vector String) -> nearest-miss Vec ===")
(let [resp (w M "(defn a1-selftest-vt [x :- (Vector String)] :- Nil nil)")
      e (err-of resp)]
  (check "(Vector ..) write fails closed" (false? (:ok resp)) (pr-str resp))
  (check "unknown-type error is :stage :type" (= :type (:stage e)) (pr-str e))
  (check "(Vector) :nearest suggests Vec" (some #{"Vec"} (:nearest e)) (pr-str e))
  (check "(Vector) :suggestion names Vec" (str/includes? (str (:suggestion e)) "Vec") (pr-str e)))

(println "\n=== FUMBLE: markdown fences around code ===")
(let [resp (w M "```clojure\n(def a1-selftest-fenced 42)\n```")]
  (check "fenced code strips + writes -> :ok" (:ok resp) (pr-str resp)))

(println "\n=== FUMBLE: prose preamble ===")
(let [resp (w M "Here is the code:\n\n(def a1-selftest-prose 7)")
      e (err-of resp)]
  (check "prose write fails closed" (false? (:ok resp)) (pr-str resp))
  (check "prose error :suggestion names the fix"
         (and (:suggestion e) (or (str/includes? (str (:suggestion e)) "prose")
                                  (str/includes? (str (:suggestion e)) "def")))
         (pr-str e)))

(println "\n=== FUMBLE: atom-wrapped value def (valid — Atom is a real ctor) ===")
(let [resp (w M "(def a1-selftest-atom (atom 0))")]
  (check "atom-wrapped def canonicalizes -> :ok" (:ok resp) (pr-str resp)))

(println "\n=== LOOKUP: unknown module -> nearest-miss ===")
(let [resp (w "shema" "(def z 1)")]
  (check "unknown module fails closed" (false? (:ok resp)) (pr-str resp))
  (check "unknown module :stage :lookup" (= :lookup (:stage resp)) (pr-str resp))
  (check "unknown module :nearest suggests schema" (some #(str/includes? % "schema") (:nearest resp)) (pr-str resp)))

(println "\n=== read-def / index unknown-name nearest-miss ===")
(let [resp (r M "a1-selftest-inx")]   ; typo of a1-selftest-inc
  (check "unknown def :stage :lookup + :nearest" (and (= :lookup (:stage resp)) (seq (:nearest resp))) (pr-str resp)))

;; --- A0's real t-r1 fumble corpus (thread A0) -------------------------------
;; Each fixture carries `:emitted` — the RAW text the model actually produced in
;; EXP-021 t-r1 (often a fenced EDN changeset, prose, JSON keys, or a bare form). We
;; feed it to write-def AS `:source` — that IS what the agentic arm will send. The
;; phase-1 GUARANTEE proven here: closed-under-mistake — every emission either
;; canonicalizes OR returns a structured error carrying a repair :suggestion; NEVER a
;; bare exception / message-less reject. (Whether a :gate-stage fumble's error names
;; the EXACT gate fix is phase 2 — the def-level check seam, thread A2.)
(def fixtures-file (str root "/tests/fixtures/authoring-fumbles.edn"))
(when (.exists (io/file fixtures-file))
  (println "\n=== A0 t-r1 fumble corpus (closed-under-mistake over REAL emissions) ===")
  (let [fixtures (try (edn/read-string (slurp fixtures-file)) (catch Throwable t (println "  (unreadable:" (.getMessage t) ")") nil))
        cats (atom {:canon-ok 0 :err-with-suggestion 0 :bare 0})]
    (doseq [fx fixtures]
      (let [module (or (:module fx) M)
            src    (:emitted fx)]
        (when-not (str/blank? (str src))
          (let [resp (w module src)
                e    (err-of resp)
                ok   (boolean (:ok resp))
                has-suggest (and (not ok) (not (str/blank? (str (:suggestion e)))))]
            (swap! cats update (cond ok :canon-ok has-suggest :err-with-suggestion :else :bare) inc)
            (check (str "fixture " (:id fx))
                   (or ok has-suggest)                  ; MUST be canon-ok OR error-with-suggestion
                   (str "stage=" (:stage e) " msg=" (:message e) " (no :suggestion — bare error!)"))))))
    (println "  breakdown:" (pr-str @cats)
             (str "(" (:canon-ok @cats) " canon-ok, " (:err-with-suggestion @cats) " repair-errors, " (:bare @cats) " BARE)"))))

(println (format "\n==== %s : %d failure(s) ====" (if (zero? @failures) "PASS" "FAIL") @failures))
(shutdown!)
(System/exit (if (zero? @failures) 0 1))
