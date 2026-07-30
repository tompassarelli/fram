;; mcp_candidate_test.clj — graph-edit-candidate-v1: the ATOMIC CANDIDATE GATE.
;; ============================================================================
;; Drives the REAL coordinator (:edit-prepare/:edit-commit/:edit-protocol) and the
;; REAL bin/fram-mcp over stdio against a hermetic NESTED corpus, and proves the
;; corrective contract of thread 019f8741-5b28 end to end:
;;
;;   A. NESTED TRACKED-PATH E2E — a genuinely nested module (src.fram.wkfix,
;;      tracked at <src>/src/fram/wkfix.bclj) edits through the MCP
;;      surface; the edit compiles, writes EXACTLY the tracked nested file, and
;;      NO root-level <src>/src.fram.wkfix.bclj artifact is created.
;;   B. INVALID CANDIDATES REJECT WITH ZERO MUTATION — an unreadable payload, a
;;      syntax-invalid body (beagle parse: bad let bindings), and a type-invalid
;;      body (String on :- Int) each yield a typed rejection BEFORE any commit;
;;      the canonical log, version, and tracked projection stay byte-identical.
;;   C. STALE CAS — a candidate prepared at version V rejects (:stale-version)
;;      after a concurrent edit lands, with zero canonical operations.
;;   D. INJECTED MID-BATCH FAILURE — with the FRAM_EDIT_INJECT seam, a failure
;;      at EVERY operation boundary (0 through n, inclusive) yields a typed
;;      rejection and zero canonical operations; a clean batch then commits
;;      COMPLETELY.
;;   E. RECOVERY-INTENT REPLAY — the journal is recovery INTENT bound to the
;;      canonical log identity + exact pre-state digest, not a commit by
;;      itself. A sealed intent redoes the WHOLE batch over a torn mid-batch
;;      log (byte-exact); a torn journal is discarded with the log untouched;
;;      a sidecar COPIED beside another log is rejected with that log
;;      byte-identical; a REWRITTEN PREFIX (same length) is rejected with the
;;      log byte-identical; a real daemon boots the crash state and serves the
;;      redone batch.
;;   I. DURABLE-APPEND FAILURE BEFORE ROOT SWAP — an injected append/fsync
;;      failure (partial write) and an injected directory-fsync failure each
;;      return a typed :durability-failure with the exact pre-state restored
;;      (log/version/projection unchanged, no journal residue), and boot
;;      recovery over the post-failure state is a NO-OP: a reported failure is
;;      never scheduled to appear after restart. A clean commit then lands.
;;   J. DIRECTORY-DURABILITY SEAMS (direct) — journal publication uses
;;      same-directory temp + atomic rename + parent fsync and binds the v2
;;      identity fields; removal durably retires the intent; a forced
;;      directory-fsync failure propagates TYPED from both (fail closed).
;;   K. PARENT-IDENTITY PINNING (direct, deterministic) — the projection
;;      publishes only through the pinned validated parent-directory identity:
;;      moving the original parent to a same-filesystem location OUTSIDE the
;;      checkout and replacing its old entry makes publication stop BEFORE temp
;;      creation/write; both moved original and replacement stay byte-identical.
;;      A deterministic short-write oracle also proves ByteBuffer drain-to-empty.
;;   L. POST-COMMIT OUTCOME TRUTH — every operation after append+fsync is faulted
;;      on an isolated log. Each returns an exact COMMITTED warning receipt, exact
;;      retry is byte-idempotent, and one cold restart reconstructs the receipt.
;;      A hard process halt immediately after append acknowledgement proves the
;;      same contract before any in-memory receipt/root publication.
;;   F. PROJECTION-STALE — a commit whose tracked-view write fails reports the
;;      stale projection loudly (log canonical, repair command included), and
;;      warm render-from-log repairs the file.
;;   G. PROTOCOL FENCE — a coordinator that cannot answer :edit-protocol with
;;      graph-edit-candidate-v1 (legacy/wrong protocol) is refused AT STARTUP.
;;   H. TRACKED-PATH PATHOLOGIES — missing, duplicate, relative, outside-root,
;;      traversal, and symlink-escape file facts each reject BEFORE mutation,
;;      and no module-name artifact is created anywhere.
;;   M. COHERENT MULTI-MODULE TRANSACTIONS — per-edit module overrides preserve
;;      the top-level default, same-named definitions in different modules are
;;      distinct targets, all touched projections cross one final-state check
;;      and one graph envelope, and one red module rolls the whole world back.
;;      Removing an externally referenced variant or synthesized accessor fails
;;      closed unless its consumer is coherently rewritten in the same batch;
;;      provider-first edit order is valid because only the final world matters.
;;
;;   FRAM_COORD_READ_TIMEOUT_MS=180000 bb -cp out tests/mcp_candidate_test.clj
;;   (run from the repo root; coherent-world verification owns the long bound)
;; Needs: bb + out/ + clojure (JVM daemons) + racket + beagle. Boots throwaway
;; coordinators; NEVER touches a live daemon (fresh high ports, hermetic tmp).
(require '[babashka.process :as p] '[cheshire.core :as json]
         '[clojure.string :as str] '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[fram.rt :as rt])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]) (println (if ok "  [PASS] " "  [FAIL] ") nm))

(def root (System/getProperty "user.dir"))
(def home (System/getProperty "user.home"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def beagle-bin (or (System/getenv "FRAM_BEAGLE") (str beagle-home "/bin/beagle")))
(def check-emit (str beagle-home "/beagle-lib/private/facts-check-emit.rkt"))
(doseq [[f label] [[beagle-bin "Beagle CLI"] [check-emit "facts-check-emit.rkt"]
                   [(str root "/out/fram/rt.clj") "out/ (build first)"]]]
  (when-not (.exists (io/file f))
    (binding [*out* *err*]
      (println "FAIL — missing required prerequisite:" label "(" f ")"))
    (System/exit 1)))

(defn sha256-hex [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))
(defn read-bytes [f] (java.nio.file.Files/readAllBytes (.toPath (io/file f))))
(defn write-bytes [f ^bytes bs] (java.nio.file.Files/write (.toPath (io/file f)) bs
                                                           (make-array java.nio.file.OpenOption 0)))

;; --- hermetic workspace (canonicalized — the path checks compare canonical forms)
(def tmp (.getCanonicalPath (io/file (str (System/getProperty "java.io.tmpdir")
                                          "/fram-mcp-cand-" (System/nanoTime)))))
(def src-dir (str tmp "/srcroot"))
(def nested-dir (str src-dir "/src/fram"))
(def code-log (str src-dir "/.fram/code.log"))
(def transaction-log (str src-dir "/.fram/transaction.log"))
(def slow-log (str src-dir "/.fram/slow.log"))
(def bad-log (str src-dir "/.fram/bad.log"))
(def facts-log (str tmp "/facts.log"))
(def verifier-fixture (str root "/tests/edit_verifier_fixture.clj"))
(def verifier-adapter (str root "/bin/fram-edit-verifier"))
(def verifier-count-file (str tmp "/verifier-invocations.log"))
(def outside-dir (str tmp "/outside"))
(.mkdirs (io/file nested-dir))
(.mkdirs (io/file (str src-dir "/.fram")))
(.mkdirs (io/file outside-dir))
(spit facts-log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"test\"}\n")

(def fixture-body
  (str "#lang beagle/clj\n\n"
       ";; %NAME% — hermetic nested fixture for graph-edit-candidate-v1 probes.\n\n"
       "(defn double-it [x :- Int] :- Int\n  (* 2 x))\n\n"
       "(defn plus-both [a :- Int b :- Int] :- Int\n  (+ (double-it a) (double-it b)))\n"))
(def modules ["wkfix" "schema" "missmod" "typedtxn"
              "txalpha" "txbeta"
              "txvariantprov" "txvariantconsumer"
              "txaccessorprov" "txaccessorconsumer"
              "overlayroot" "overlaydependent" "overlaythird"
              "dupmod" "relmod" "outmod" "travmod" "linkmod"])
(doseq [m modules]
  (spit (str nested-dir "/" m ".bclj") (str/replace fixture-body "%NAME%" m)))
(spit (str nested-dir "/schema.bclj")
      (str "#lang beagle/clj\n\n"
           ";; schema — exact verifier-reproduction fixture.\n\n"
           "(defn cardinality [ctx :- Int pname :- Int] :- Int\n  (* 2 pname))\n"))
(spit (str nested-dir "/missmod.bclj")
      (str "#lang beagle/clj\n\n"
           ";; jointred — two type errors that can only cross the gate together.\n\n"
           "(defn left-red [] :- Int\n  \"red-left\")\n\n"
           "(defn right-red [] :- Int\n  \"red-right\")\n"))
(spit (str nested-dir "/typedtxn.bclj")
      (str "#lang beagle/clj\n\n"
           ";; typedtxn — adding the throwable declaration alone makes the "
           "existing raw throw illegal.\n\n"
           "(defn classify-rewrite-crash [path :- String] :- String\n"
           "  (throw (ex-info \"boom\" {:path path :fram/doctor-refusal true})))\n"))
(spit (str nested-dir "/txalpha.bclj")
      (str "#lang beagle/clj\n"
           "(ns src.fram.txalpha)\n\n"
           "(defn shared-name [] :- Int 10)\n"))
(spit (str nested-dir "/txbeta.bclj")
      (str "#lang beagle/clj\n"
           "(ns src.fram.txbeta)\n\n"
           "(defn shared-name [] :- Int 20)\n"))
(spit (str nested-dir "/txvariantprov.bclj")
      (str "#lang beagle/clj\n"
           ";; Graph source id is src.fram.txvariantprov; declared namespace is intentionally different.\n"
           "(ns fram.txvariantprov)\n\n"
           "(defunion Event\n"
           "  (Created [value :- Int])\n"
           "  (Retired [reason :- String]))\n\n"
           "(defn provider-marker [] :- Int 1)\n"))
(spit (str nested-dir "/txvariantconsumer.bclj")
      (str "#lang beagle/clj\n"
           "(ns fram.txvariantconsumer)\n"
           "(require fram.txvariantprov :as p)\n\n"
           "(defn use-created [x :- Int] :- Any (p/->Created x))\n"))
(spit (str nested-dir "/txaccessorprov.bclj")
      (str "#lang beagle/clj\n"
           "(ns fram.txaccessorprov)\n\n"
           "(defrecord Profile [name :- String legacy :- Int])\n\n"
           "(defn provider-marker [] :- Int 1)\n"))
(spit (str nested-dir "/txaccessorconsumer.bclj")
      (str "#lang beagle/clj\n"
           "(ns fram.txaccessorconsumer)\n"
           "(require fram.txaccessorprov :as p)\n\n"
           "(defn use-legacy [x :- Int] :- Int\n"
           "  (p/profile-legacy (p/->Profile \"fixture\" x)))\n"))
(spit (str nested-dir "/overlayroot.bclj")
      (str "#lang beagle/clj\n"
           "(ns fram.overlayroot)\n\n"
           "(defn root-value [x :- Int] :- Int (* 2 x))\n"))
(spit (str nested-dir "/overlaythird.bclj")
      (str "#lang beagle/clj\n"
           "(ns fram.overlaythird)\n\n"
           "(defn third-value [x :- Int] :- Int (+ x 1))\n"))
(spit (str nested-dir "/overlaydependent.bclj")
      (str "#lang beagle/clj\n"
           "(ns fram.overlaydependent)\n"
           "(require fram.overlayroot :as r)\n"
           "(require fram.overlaythird :as t)\n\n"
           "(defn dependent-value [x :- Int] :- Int\n"
           "  (t/third-value (r/root-value x)))\n"))

;; HERMETIC SPAWNS — :env REPLACES the environment everywhere below. An ambient
;; live-runtime FRAM_TELEMETRY_LOG would otherwise leak into the daemons via
;; :extra-env and read-logs-merged would fold the FOREIGN telemetry log into the
;; corpus version (nondeterministic, inflates :version past the code log's max :tx).
(def fram-racket
  (or (System/getenv "FRAM_RACKET")
      (let [r (try (p/sh {:out :string :err :string} "direnv" "exec" beagle-home "which" "racket")
                   (catch Exception _ nil))]
        (when (and r (zero? (:exit r)) (not (str/blank? (:out r)))) (str/trim (:out r))))))
(when-not (and fram-racket (.canExecute (io/file fram-racket)))
  (binding [*out* *err*]
    (println "FAIL — missing required prerequisite: executable pinned Racket"
             (pr-str fram-racket)))
  (System/exit 1))
(def scrub-env
  (cond-> {"PATH" (System/getenv "PATH") "HOME" home "BEAGLE_HOME" beagle-home
           ;; A coherent full-world verification is deliberately allowed to
           ;; take as long as its coordinator-side 120s verifier budget.
           "FRAM_COORD_READ_TIMEOUT_MS" "180000"}
    fram-racket (assoc "FRAM_RACKET" fram-racket)))

(println "ingesting" (count modules) "nested fixture modules …")
(let [r (p/shell {:continue true :out :string :err :string :env scrub-env}
                 ;; ingest lists a directory NON-recursively — pass the nested dir,
                 ;; with --root at the checkout root so module names qualify
                 ;; (src/fram/wkfix.bclj -> src.fram.wkfix).
                 "bin/fram-ingest-code" nested-dir "--root" src-dir "--out" code-log)]
  (when-not (zero? (:exit r))
    (binding [*out* *err*]
      (println "FAIL — required Beagle ingest failed:" (str/trim (str (:err r)))))
    (System/exit 1)))

;; --- pathology log: same corpus with each module's file fact doctored ---------
;; missmod: file fact DELETED; dupmod: second (conflicting) file fact appended;
;; relmod: relative path; outmod: absolute path OUTSIDE the source root;
;; travmod: absolute but non-canonical (../ traversal); linkmod: a path through a
;; symlink that escapes the root (canonical form differs -> refused).
(java.nio.file.Files/createSymbolicLink
 (.toPath (io/file (str src-dir "/src/esc")))
 (.toPath (io/file outside-dir))
 (make-array java.nio.file.attribute.FileAttribute 0))
(let [file-line? (fn [ln mod]
                   (and (str/includes? ln (str "\"@src.fram." mod "#root\""))
                        (str/includes? ln ":p \"file\"")))
      rewrite (fn [ln mod new-path]
                (if (file-line? ln mod)
                  (let [m (edn/read-string ln)] (pr-str (assoc m :r new-path)))
                  ln))
      lines (str/split-lines (slurp code-log))
      lines (remove #(file-line? % "missmod") lines)
      lines (map (fn [ln] (-> ln
                              (rewrite "relmod" "rel/relmod.bclj")
                              (rewrite "outmod" (str outside-dir "/outmod.bclj"))
                              (rewrite "travmod" (str src-dir "/src/fram/../../../trav/travmod.bclj"))
                              (rewrite "linkmod" (str src-dir "/src/esc/linkmod.bclj"))))
                 lines)
      dup (pr-str {:tx 999999 :op "assert" :l "@src.fram.dupmod#root" :p "file"
                   :r (str outside-dir "/dup2.bclj") :ts "2026-07-22T00:00:00Z" :by "test"})]
  (spit bad-log (str (str/join "\n" (concat lines [dup])) "\n")))
(write-bytes transaction-log (read-bytes code-log))
(write-bytes slow-log (read-bytes code-log))

;; --- throwaway coordinators ---------------------------------------------------
(defn port-free? [pt]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int pt)) 300) false)
       (catch Exception _ true)))
(defn pick-port [cands] (or (some #(when (port-free? %) %) cands)
                            (throw (ex-info "no free test port" {}))))
(def main-port (pick-port [39911 39913 39915 39917 39919]))
(def bad-port  (pick-port [39912 39914 39916 39918 39920]))
(def transaction-port (pick-port [39891 39893 39895 39897 39899]))
(def slow-port (pick-port [39791 39793 39795 39797 39799]))
(def stub-port (pick-port [39921 39923 39925 39927 39929]))
(def replay-port (pick-port [39931 39933 39935 39937 39939]))
(def poison-port (pick-port [39941 39943 39945 39947 39949]))
(def poison-restart-port (pick-port [39951 39953 39955 39957 39959]))
(def post-port (pick-port [39961 39963 39965 39967 39969]))
(def post-restart-port (pick-port [39962 39964 39966 39968 39970]))
(def crash-port (pick-port [39971 39973 39975 39977 39979]))
(def crash-restart-port (pick-port [39972 39974 39976 39978 39980]))
(def dead-port (pick-port [59981 59983 59985 59987 59989]))

(defn boot-daemon!
  ([port log] (boot-daemon! port log {}))
  ([port log extra-env]
   (let [outf (str tmp "/daemon-" port ".log")
         _ (java.nio.file.Files/deleteIfExists (.toPath (io/file outf)))
         proc (p/process {:out (io/file outf) :err (io/file outf)
                          :env (merge scrub-env
                                      {"FRAM_REQUIRE_LOG_FENCE" "1"
                                       "FRAM_EDIT_INJECT" "1"
                                       "FRAM_EDIT_VERIFIER" verifier-fixture
                                       "FRAM_EDIT_VERIFIER_COUNT_FILE" verifier-count-file
                                       "FRAM_EDIT_VERIFIER_FIXTURE_DELEGATE"
                                       verifier-adapter
                                       "FRAM_EDIT_VERIFIER_REQUIRE_SOURCE"
                                       "src.fram.overlaythird"
                                       ;; Candidate/OCC tests need a quiescent canonical log.
                                       ;; Snapshot behavior has dedicated suites; its async
                                       ;; post-boot metadata append would be an unrelated,
                                       ;; legitimate stale-version writer here.
                                       "FRAM_SNAPSHOT_BOOT" "0"}
                                      extra-env)}
                         "clojure" "-M" "coord_daemon.clj" "serve-flat" (str port) log)]
     (loop [i 0]
       (cond
         (and (.exists (io/file outf)) (str/includes? (slurp outf) "listening on")) proc
         (> i 360) (do (p/destroy-tree proc)
                       (throw (ex-info (str "daemon on :" port " never came up") {:log outf})))
         :else (do (Thread/sleep 500) (recur (inc i))))))))

(defn stop-daemon! [proc]
  (when (and proc (.isAlive ^Process (:proc proc)))
    (p/destroy-tree proc))
  (loop [i 0]
    (when (and proc (.isAlive ^Process (:proc proc)) (< i 100))
      (Thread/sleep 50)
      (recur (inc i))))
  (not (and proc (.isAlive ^Process (:proc proc)))))

(println "booting throwaway coordinators (main:" main-port " pathology:" bad-port ") …")
(def main-daemon (boot-daemon! main-port code-log))
(def bad-daemon  (boot-daemon! bad-port bad-log))
(def transaction-daemon (boot-daemon! transaction-port transaction-log))

(defn coord-raw [port log req]
  (rt/coord-request-for-log port log req))
(defn coord
  ([req] (coord main-port code-log req))
  ([port log req]
   ;; Keep legacy candidate regressions focused on their original durability/OCC
   ;; subject while exercising the new hard gate. An exact first commit attempt
   ;; must say :candidate-unverified; only then does this test helper request
   ;; coordinator-owned verification and retry the byte-identical commit.
   (if (= :edit-commit (:op req))
     (let [first-result (coord-raw port log req)]
       (if (= :candidate-unverified (:code first-result))
         (let [verified (coord-raw port log
                                   {:op :edit-verify
                                    :candidate (:candidate req)})]
           (if (:ok verified)
             (coord-raw port log req)
             verified))
         first-result))
     (coord-raw port log req))))
(defn cur-version [] (:version (coord {:op :version})))
(defn transaction-coord [req]
  (coord transaction-port transaction-log req))
(defn transaction-version []
  (:version (transaction-coord {:op :version})))

(defn verifier-invocation-count []
  (if (.isFile (io/file verifier-count-file))
    (count (remove str/blank? (str/split-lines (slurp verifier-count-file))))
    0))

;; ============================================================================
;; V0. COORDINATOR-OWNED VERIFICATION LIFECYCLE — no caller proof can authorize
;;     commit; one launch-sealed verification is cached; a proof is exact-version
;;     scoped; and selected checks still receive the complete provider overlay.
;; ============================================================================
(let [configured (:configured-logs
                  (transaction-coord {:op :edit-protocol}))]
  (chk "V0: edit protocol exposes the exact single-log coordinator identity"
       (= {:coordination (.getCanonicalPath (io/file transaction-log))
           :telemetry nil}
          configured)))

(let [prep (transaction-coord
            {:op :edit-prepare
             :spec {:op "set-body" :module "src.fram.wkfix"
                    :name "double-it" :datum 6}})
      commit-req {:op :edit-commit
                  :candidate (:candidate prep)
                  :version (:version prep)
                  :module (:module prep)
                  :path (:path prep)
                  :ops-digest (:ops-digest prep)
                  :edn-digest (:edn-digest prep)}
      log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      unverified (coord-raw transaction-port transaction-log commit-req)
      fake (coord-raw
            transaction-port transaction-log
            (assoc commit-req
                   :verification-state :verified
                   :verification {:schema "caller-forged"
                                  :input-digest (:edn-digest prep)}))
      preverify-unchanged?
      (and (= v0 (transaction-version))
           (= log0 (vec (read-bytes transaction-log))))
      calls0 (verifier-invocation-count)
      verified (coord-raw transaction-port transaction-log
                          {:op :edit-verify
                           :candidate (:candidate prep)})
      cached (coord-raw transaction-port transaction-log
                        {:op :edit-verify
                         :candidate (:candidate prep)})
      calls1 (verifier-invocation-count)
      commit (coord-raw transaction-port transaction-log commit-req)
      proof (:verification verified)]
  (chk "V0: direct prepared→commit is hard-rejected with zero canonical writes"
       (and (= :candidate-unverified (:code unverified))
            preverify-unchanged?))
  (chk "V0: caller-supplied verification fields cannot authorize commit"
       (= :candidate-unverified (:code fake)))
  (chk "V0: coordinator invokes its launch-sealed verifier exactly once and caches success"
       (and (true? (:ok verified))
            (false? (:cached verified))
            (true? (:ok cached))
            (true? (:cached cached))
            (= 1 (- calls1 calls0))))
  (chk "V0: proof binds candidate/base/ops/EDN/closure/overlay and verifier closure identities"
       (and (= (:candidate prep) (:candidate proof))
            (= (:version prep) (:base-version proof))
            (= (:ops-digest prep) (:ops-digest proof))
            (= (:edn-digest prep) (:edn-digest proof))
            (= (:checked-closure-digest prep) (:closure-digest proof))
            (= (:overlay-digest prep) (:overlay-digest proof))
            (re-matches #"[0-9a-f]{64}"
                        (:verifier-content-digest proof))
            (re-matches #"[0-9a-f]{64}"
                        (:toolchain-closure-digest proof))
            (= (:receipt-digest proof)
               (sha256-hex
                (pr-str ["fram-edit-verifier-receipt-v1"
                         true
                         (:input-digest proof)
                         (:world-digest proof)
                         (:toolchain-closure-digest proof)
                         (:modules proof)])))))
  (chk "V0: only the coordinator-verified candidate commits"
       (and (true? (:ok commit)) (true? (:committed commit)))))

(let [prep (transaction-coord
            {:op :edit-prepare
             :spec {:op "set-body" :module "src.fram.overlayroot"
                    :name "root-value" :datum 41}})
      checked (set (:checked-modules prep))
      calls0 (verifier-invocation-count)
      verified (coord-raw transaction-port transaction-log
                          {:op :edit-verify
                           :candidate (:candidate prep)})
      calls1 (verifier-invocation-count)]
  (chk "V0: reverse selector includes the dependent but not its untouched third provider"
       (and (checked "src.fram.overlayroot")
            (checked "src.fram.overlaydependent")
            (not (checked "src.fram.overlaythird"))))
  (chk "V0: complete overlay includes the untouched provider required by the verifier"
       (and (true? (:ok verified))
            (= 1 (- calls1 calls0))
            (> (:overlay-module-count prep)
               (count (:checked-modules prep))))))

(let [daemon (boot-daemon! slow-port slow-log
                           {"FRAM_EDIT_VERIFIER_FIXTURE_MODE" "accept"
                            "FRAM_EDIT_VERIFIER_SLEEP_MS" "1500"})]
  (try
    (let [prep (coord-raw slow-port slow-log
                          {:op :edit-prepare
                           :spec {:op "set-body" :module "src.fram.wkfix"
                                  :name "double-it" :datum 12}})
          verification (future
                         (coord-raw slow-port slow-log
                                    {:op :edit-verify
                                     :candidate (:candidate prep)}))
          deadline (+ (System/currentTimeMillis) 2000)
          active
          (loop []
            (let [status (coord-raw slow-port slow-log
                                    {:op :edit-candidate-status
                                     :candidate (:candidate prep)})]
              (cond
                (= :verifying (:verification-state status)) status
                (< (System/currentTimeMillis) deadline)
                (do (Thread/sleep 10) (recur))
                :else status)))
          started (System/nanoTime)
          in-progress (coord-raw slow-port slow-log
                                 {:op :edit-verify
                                  :candidate (:candidate prep)})
          elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
          completed (deref verification 5000 ::timed-out)]
      (chk "V0: slow verifier exposes the addressable in-progress candidate directly"
           (and (= (:candidate prep) (:candidate active))
                (= :verifying (:verification-state active))))
      (chk "V0: concurrent direct verification returns bounded in-progress instead of waiting"
           (and (= :verification-in-progress (:code in-progress))
                (= (:candidate prep) (:candidate in-progress))
                (true? (:retryable in-progress))
                (< elapsed-ms 500.0)
                (true? (:ok completed)))))
    (finally (stop-daemon! daemon))))

(let [prep (transaction-coord
            {:op :edit-prepare
             :spec {:op "set-body" :module "src.fram.wkfix"
                    :name "double-it" :datum "\"not-an-int\""}})
      rejected (coord-raw transaction-port transaction-log
                          {:op :edit-verify :candidate (:candidate prep)})
      status (transaction-coord
              {:op :edit-candidate-status :candidate (:candidate prep)})
      rows (->> (str/split-lines (:edn prep))
                rest
                (mapv edn/read-string))
      structural-rows
      (filterv
       (fn [[_ p _]]
         (and (string? p)
              (or (#{"child" "tail"} p)
                  (re-matches #"f\d+" p)
                  (re-matches #"(?:seg|comment)\d+" p))))
       rows)
      diagnostic (:diagnostic status)]
  (chk "V0: candidate EDN keeps structural edges as node references"
       (and (seq structural-rows)
            (every? integer? (map #(nth % 2) structural-rows))))
  (chk "V0: rejected candidate status retains a bounded checker diagnostic"
       (and (= :candidate-check-failed (:code rejected))
            (= (:candidate prep) (:candidate rejected))
            (= (:candidate prep) (:candidate status) (:candidate diagnostic))
            (= :rejected (:verification-state status) (:status diagnostic))
            (= 1 (:exit diagnostic))
            (string? (:code diagnostic))
            (vector? (:errors diagnostic))
            (string? (:stderr diagnostic)))))

(let [stale-prep (transaction-coord
                  {:op :edit-prepare
                   :spec {:op "set-body" :module "src.fram.wkfix"
                          :name "double-it" :datum 7}})
      stale-verified (coord-raw transaction-port transaction-log
                                {:op :edit-verify
                                 :candidate (:candidate stale-prep)})
      winner (transaction-coord
              {:op :edit-prepare
               :spec {:op "set-body" :module "src.fram.wkfix"
                      :name "plus-both" :datum 8}})
      winner-commit
      (transaction-coord
       {:op :edit-commit
        :candidate (:candidate winner)
        :version (:version winner)
        :module (:module winner)
        :path (:path winner)
        :ops-digest (:ops-digest winner)
        :edn-digest (:edn-digest winner)})
      stale-commit
      (coord-raw
       transaction-port transaction-log
       {:op :edit-commit
        :candidate (:candidate stale-prep)
        :version (:version stale-prep)
        :module (:module stale-prep)
        :path (:path stale-prep)
        :ops-digest (:ops-digest stale-prep)
        :edn-digest (:edn-digest stale-prep)})]
  (chk "V0: candidate verifies before an interleaved exact-version winner"
       (and (true? (:ok stale-verified))
            (true? (:ok winner-commit))))
  (chk "V0: a verified proof cannot outlive its exact base version"
       (= :stale-version (:code stale-commit))))

;; ============================================================================
;; T0. COORDINATOR MULTI-DEFINITION CANDIDATE — one end-state projection,
;;     one sealed emission, and exact-version serialization against a single edit.
;; ============================================================================
(let [specs [{:op "set-body" :module "src.fram.wkfix"
              :name "double-it" :datum 11}
             {:op "set-body" :module "src.fram.wkfix"
              :name "plus-both" :datum 22}]
      log0 (read-bytes transaction-log)
      prep (transaction-coord {:op :edit-prepare :specs specs})
      cand-edn (str tmp "/transaction-candidate.bclj.edn")
      cand-src (str tmp "/transaction-candidate.bclj")
      _ (spit cand-edn (:edn prep))
      rendered (p/shell {:continue true :out (io/file cand-src) :err :string :env scrub-env}
                        beagle-bin "facts-roundtrip" "--render" cand-edn)
      text (when (zero? (:exit rendered)) (slurp cand-src))
      commit (transaction-coord
              {:op :edit-commit :candidate (:candidate prep)
               :version (:version prep) :module (:module prep)
               :path (:path prep) :ops-digest (:ops-digest prep)
               :edn-digest (:edn-digest prep)})
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes (read-bytes transaction-log)
                         (alength ^bytes log0)
                         (alength ^bytes (read-bytes transaction-log)))
                        "UTF-8")]
  (chk "T0: coordinator accepts two distinct definition edits as one candidate"
       (and (true? (:ok prep)) (= 2 (:edits prep)) (pos? (:ops prep))))
  (chk "T0: one-module prepare preserves legacy scalars and publishes coherent-world metadata"
       (and (= "src.fram.wkfix" (:module prep))
            (= (str nested-dir "/wkfix.bclj") (:path prep))
            (string? (:edn prep))
            (= ["src.fram.wkfix"] (:modules prep))
            (= {"src.fram.wkfix" (str nested-dir "/wkfix.bclj")}
               (:paths prep))
            (string? (get (:edn-by-module prep) "src.fram.wkfix"))
            (some #{"src.fram.wkfix"} (:checked-modules prep))))
  (chk "T0: the one prepared end state contains both staged bodies"
       (and (zero? (:exit rendered))
            (str/includes? text ":- Int 11)")
            (str/includes? text ":- Int 22)")))
  (chk "T0: the transaction commits through the ordinary atomic candidate path"
       (and (true? (:ok commit)) (= (:ops commit) (:installed commit))))
  (chk "T0: one transaction emits exactly one durable batch envelope"
       (= 1 (count (re-seq #":fram-edit-envelope" appended)))))

(let [batch (transaction-coord
             {:op :edit-prepare
              :specs [{:op "set-body" :module "src.fram.wkfix"
                       :name "double-it" :datum 31}
                      {:op "set-body" :module "src.fram.wkfix"
                       :name "plus-both" :datum 32}]})
      single (transaction-coord
              {:op :edit-prepare
               :spec {:op "set-body" :module "src.fram.wkfix"
                      :name "double-it" :datum 33}})
      single-commit (transaction-coord
                     {:op :edit-commit :candidate (:candidate single)
                      :version (:version single) :module (:module single)
                      :path (:path single) :ops-digest (:ops-digest single)
                      :edn-digest (:edn-digest single)})
      log-after-single (vec (read-bytes transaction-log))
      version-after-single (transaction-version)
      batch-commit (transaction-coord
                    {:op :edit-commit :candidate (:candidate batch)
                     :version (:version batch) :module (:module batch)
                     :path (:path batch) :ops-digest (:ops-digest batch)
                     :edn-digest (:edn-digest batch)})]
  (chk "T0: concurrent single-definition candidate commits first"
       (true? (:ok single-commit)))
  (chk "T0: the interleaved batch is serialized by exact-version CAS"
       (= :stale-version (:code batch-commit)))
  (chk "T0: stale batch rejection records and emits nothing"
       (and (= version-after-single (transaction-version))
            (= log-after-single (vec (read-bytes transaction-log))))))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      duplicate (transaction-coord
                 {:op :edit-prepare
                  :specs [{:op "set-body" :module "src.fram.wkfix"
                           :name "double-it" :datum 41}
                          {:op "replace-in-body" :module "src.fram.wkfix"
                           :name "double-it" :old 33 :new 42}]})]
  (chk "T0: duplicate-definition transaction rejects before candidate creation"
       (= :duplicate-definition (:code duplicate)))
  (chk "T0: invalid transaction leaves log and version byte-identical"
       (and (= v0 (transaction-version))
            (= log0 (vec (read-bytes transaction-log))))))

;; --- spawning the MCP hermetically --------------------------------------------
(def base-env
  (cond-> {"PATH" (System/getenv "PATH") "HOME" home
           "FRAM_COORD_READ_TIMEOUT_MS" "180000"
           "FRAM_LOG" facts-log "FRAM_THREADS" tmp "FRAM_PORT" (str dead-port)
           "FRAM_MCP_PROFILE" "graph-edit-v1"
           "FRAM_GRAPH_EDIT" "1" "FRAM_FLIP" "1"
           "FRAM_CODE_PORT" (str main-port)
           "FRAM_CODE_LOG" code-log
           "FRAM_SRC" src-dir
           "FRAM_OUT" (str root "/out") "FRAM_BIN" (str root "/bin")
           "FRAM_RESOLVE" (str root "/out/resolve.clj")
           "BEAGLE_HOME" beagle-home
           "FRAM_BEAGLE" beagle-bin "FRAM_CHECK_EMIT" check-emit
           "FRAM_BUILD_ALL" (str beagle-home "/bin/beagle-build-all")}
    (System/getenv "FRAM_RACKET") (assoc "FRAM_RACKET" (System/getenv "FRAM_RACKET"))))
(def transaction-env
  (assoc base-env
         "FRAM_CODE_PORT" (str transaction-port)
         "FRAM_CODE_LOG" transaction-log))

(defn run-mcp [env reqs]
  (let [in (str (str/join "\n" (map json/generate-string reqs)) "\n")
        res (p/shell {:in in :out :string :err :string :continue true :env env}
                     "bin/fram-mcp")
        by-id (reduce (fn [m line]
                        (if (str/blank? line) m
                          (let [r (try (json/parse-string line true) (catch Exception _ nil))]
                            (if (and r (:id r)) (assoc m (:id r) r) m))))
                      {} (str/split-lines (or (:out res) "")))]
    {:exit (:exit res) :out (:out res) :err (:err res) :by-id by-id}))
(defn rtext [r] (get-in r [:result :content 0 :text]))
(defn rerr? [r] (boolean (get-in r [:result :isError])))
(def init-req {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
(defn call-req [id tool args] {:jsonrpc "2.0" :id id :method "tools/call"
                               :params {:name tool :arguments args}})
(defn mcp-edit [env id tool args] (get (:by-id (run-mcp env [init-req (call-req id tool args)])) id))

(def wkfix-file (str nested-dir "/wkfix.bclj"))
(def wkfix-root-artifact (str src-dir "/src.fram.wkfix.bclj"))
(def jointred-file (str nested-dir "/missmod.bclj"))

;; ============================================================================
;; T1. MCP END-STATE TRANSACTION — a red baseline cannot cross the sealed
;;     checker one definition at a time, but two jointly-green edits commit as
;;     one emission. A still-red end state rejects with zero recorded bytes.
;; ============================================================================
(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      file0 (slurp jointred-file)
      single (mcp-edit transaction-env 5 "set-body"
                       {:module "src.fram.missmod" :name "left-red" :body "1"})
      single-text (or (rtext single) "")]
  (chk "T1: one legal definition edit is rejected while the module end state stays red"
       (and (rerr? single)
            (str/includes? single-text "coordinator TYPE/WORLD check")
            (str/includes? single-text "nothing committed")))
  (chk "T1: rejected single edit leaves the red baseline byte-identical"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= file0 (slurp jointred-file)))))

(let [before (read-bytes transaction-log)
      result (mcp-edit
              transaction-env 6 "edit-transaction"
              {:module "src.fram.missmod"
               :edits [{:op "set-body" :name "left-red" :body "1"}
                       {:op "set-body" :name "right-red" :body "2"}]})
      text (or (rtext result) "")
      after (read-bytes transaction-log)
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes after (alength ^bytes before) (alength ^bytes after))
                        "UTF-8")
      source (slurp jointred-file)]
  (chk "T1: two jointly-green definition edits are accepted through one MCP transaction"
       (and (not (rerr? result))
            (str/includes? text "committed + TYPE/WORLD CHECK CLEAN")
            (str/includes? text "edit-transaction")))
  (chk "T1: the tracked projection contains both green bodies"
       (and (str/includes? source ":- Int 1)")
            (str/includes? source ":- Int 2)")))
  (chk "T1: the accepted transaction emits exactly one durable batch envelope"
       (= 1 (count (re-seq #":fram-edit-envelope" appended)))))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      file0 (slurp jointred-file)
      result (mcp-edit
              transaction-env 7 "edit-transaction"
              {:module "src.fram.missmod"
               :edits [{:op "set-body" :name "left-red" :body "\"still-red\""}
                       {:op "set-body" :name "right-red" :body "22"}]})
      text (or (rtext result) "")]
  (chk "T1: transaction whose end state stays red is rejected by the one sealed check"
       (and (rerr? result)
            (str/includes? text "coordinator TYPE/WORLD check")
            (str/includes? text "nothing committed")))
  (chk "T1: rejected red transaction records nothing and leaves projection unchanged"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= file0 (slurp jointred-file)))))

;; T2. TYPE + SIGNATURE ATOMICITY — a throwable declaration changes how the
;; checker judges every raw throw in the module. Adding the type alone is red;
;; upserting the type and the function that cites it must therefore cross the
;; candidate gate as one final-state transaction. Upserts derive their target
;; from the form when :name is omitted; a supplied :name is only an assertion.
;; A second schema/signature evolution proves retained leaf identity warm and
;; after a cold coordinator restart.
(def typedtxn-file (str nested-dir "/typedtxn.bclj"))
(def rewrite-error-form
  "(defunion :throwable RewriteCrashError (RewriteCrash [message :- String path :- String doctor-refusal :- Bool]))")
(def typed-classifier-form
  "(defn classify-rewrite-crash [path :- String] :- String :raises RewriteCrashError (throw (ex-info \"boom\" {:path path :fram/doctor-refusal true})))")
(def evolved-rewrite-error-form
  "(defunion :throwable RewriteCrashError (RewriteCrash [message :- String path :- String doctor-refusal :- String]))")
(def evolved-typed-classifier-form
  "(defn classify-rewrite-crash [path :- String refusal :- String] :- String :raises RewriteCrashError (throw (ex-info \"boom-v2\" {:path path :fram/doctor-refusal refusal})))")
(def retained-typed-bindings
  ["RewriteCrashError" "RewriteCrash" "classify-rewrite-crash"])
(defn transaction-binding-targets []
  (into {}
        (map (fn [nm]
               [nm (:target (transaction-coord
                             {:op :callers :module "src.fram.typedtxn" :name nm}))])
             retained-typed-bindings)))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      file0 (slurp typedtxn-file)
      single (mcp-edit transaction-env 8 "add-def"
                       {:module "src.fram.typedtxn"
                        :form rewrite-error-form})
      text (or (rtext single) "")]
  (chk "T2: adding a throwable type alone is rejected while its raw throw is uncovered"
       (and (rerr? single)
            (str/includes? text "throwing path is not covered by :raises")
            (str/includes? text "nothing committed")))
  (chk "T2: rejected type-only edit leaves log, version, and projection unchanged"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= file0 (slurp typedtxn-file)))))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      file0 (slurp typedtxn-file)
      result (mcp-edit
              transaction-env 9 "edit-transaction"
              {:module "src.fram.typedtxn"
               :edits [{:op "upsert-form"
                        :name "NotRewriteCrashError"
                        :form rewrite-error-form}
                       {:op "upsert-form"
                        :form typed-classifier-form}]})
      text (or (rtext result) "")]
  (chk "T2: an optional supplied upsert name is an assertion and mismatches reject before prepare"
       (and (rerr? result)
            (str/includes? text "does not match form identity")
            (str/includes? text "nothing prepared, nothing committed")))
  (chk "T2: supplied-name mismatch leaves log, version, and projection byte-identical"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= file0 (slurp typedtxn-file)))))

(let [before (read-bytes transaction-log)
      result (mcp-edit
              transaction-env 10 "edit-transaction"
              {:module "src.fram.typedtxn"
               :edits [{:op "upsert-form"
                        :form rewrite-error-form}
                       {:op "upsert-form"
                        :form typed-classifier-form}]})
      text (or (rtext result) "")
      after (read-bytes transaction-log)
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes after (alength ^bytes before) (alength ^bytes after))
                        "UTF-8")
      source (slurp typedtxn-file)]
  (chk "T2: omitted upsert names derive type + function identities and commit atomically"
       (and (not (rerr? result))
            (str/includes? text "committed + TYPE/WORLD CHECK CLEAN")
            (str/includes? text "edit-transaction")))
  (chk "T2: final projection contains the throwable type and typed function"
       (and (str/includes? source "RewriteCrashError")
            (str/includes? source ":raises")
            (str/includes? source ":fram/doctor-refusal")))
  (chk "T2: type/signature transaction emits exactly one durable batch envelope"
       (= 1 (count (re-seq #":fram-edit-envelope" appended)))))

(def retained-targets-v1 (transaction-binding-targets))
(chk "T2: public callers lookup exposes stable addresses for the type, variant, and function"
     (and (every? string? (vals retained-targets-v1))
          (= (count retained-typed-bindings)
             (count (set (vals retained-targets-v1))))))

;; Modifier-bearing type definitions key by their declared name, not by the
;; `:throwable` modifier. Two throwable unions must coexist.
(let [other-form
      "(defunion :throwable OtherError (OtherFailure [message :- String code :- Int]))"
      result (mcp-edit transaction-env 11 "add-def"
                       {:module "src.fram.typedtxn" :form other-form})
      source (slurp typedtxn-file)]
  (chk "T2: a second :throwable union appends instead of replacing the first"
       (and (not (rerr? result))
            (str/includes? source "RewriteCrashError")
            (str/includes? source "OtherError"))))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      file0 (slurp typedtxn-file)
      result (mcp-edit
              transaction-env 12 "edit-transaction"
              {:module "src.fram.typedtxn"
               :edits [{:op "upsert-form"
                        :form typed-classifier-form}
                       {:op "set-body"
                        :name "classify-rewrite-crash"
                        :body "\"duplicate target must not stage\""}]})
      text (or (rtext result) "")]
  (chk "T2: a derived-identity upsert plus body edit of the same target rejects as duplicate"
       (and (rerr? result)
            (str/includes? text "duplicate-definition")
            (str/includes? text "distinct module-qualified top-level definition")))
  (chk "T2: duplicate target rejection leaves log, version, and projection byte-identical"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= file0 (slurp typedtxn-file)))))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      file0 (slurp typedtxn-file)
      type-only (mcp-edit transaction-env 13 "add-def"
                          {:module "src.fram.typedtxn"
                           :form evolved-rewrite-error-form})
      text (or (rtext type-only) "")]
  (chk "T2: evolving the throwable field type alone is rejected against the old classifier"
       (and (rerr? type-only)
            (str/includes? text "coordinator TYPE/WORLD check")
            (str/includes? text "nothing committed")))
  (chk "T2: rejected one-sided schema evolution is byte-identical"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= file0 (slurp typedtxn-file)))))

(let [before (read-bytes transaction-log)
      result (mcp-edit
              transaction-env 14 "edit-transaction"
              {:module "src.fram.typedtxn"
               :edits [{:op "upsert-form"
                        :name "RewriteCrashError"
                        :form evolved-rewrite-error-form}
                       {:op "upsert-form"
                        :name "classify-rewrite-crash"
                        :form evolved-typed-classifier-form}]})
      text (or (rtext result) "")
      after (read-bytes transaction-log)
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes after
                         (alength ^bytes before)
                         (alength ^bytes after))
                        "UTF-8")
      source (slurp typedtxn-file)]
  (chk "T2: the same throwable union and classifier evolve together through one final-state check"
       (and (not (rerr? result))
            (str/includes? text "committed + TYPE/WORLD CHECK CLEAN")
            (str/includes? source "doctor-refusal :- String")
            (str/includes? source "[path :- String refusal :- String]")))
  (chk "T2: second schema/signature evolution emits exactly one durable batch envelope"
       (= 1 (count (re-seq #":fram-edit-envelope" appended)))))

(def retained-targets-v2 (transaction-binding-targets))
(chk "T2: whole-form schema/signature evolution retains exact binding identities"
     (= retained-targets-v1 retained-targets-v2))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      file0 (slurp typedtxn-file)
      result (mcp-edit
              transaction-env 15 "edit-transaction"
              {:module "src.fram.typedtxn"
               :edits [{:op "upsert-form"
                        :name "BadError"
                        :form "(defunion :throwable BadError (Bad [message :- String path :- String]))"}
                       {:op "upsert-form"
                        :name "classify-rewrite-crash"
                        :form "(defn classify-rewrite-crash [path :- String] :- String (throw (ex-info \"bad\" {:path path})))"}]})
      text (or (rtext result) "")]
  (chk "T2: a red upsert transaction is rejected by the one sealed end-state check"
       (and (rerr? result)
            (str/includes? text "coordinator TYPE/WORLD check")
            (str/includes? text "nothing committed")))
  (chk "T2: rejected upsert transaction records nothing and preserves projection"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= file0 (slurp typedtxn-file)))))

;; ============================================================================
;; T3. COHERENT MULTI-MODULE WORLD — a transaction's target identity is
;;     [module, definition], every touched/downstream projection is checked from
;;     the same final graph, and the canonical install is still one envelope.
;;     External references make variant/accessor removal fail closed unless the
;;     surviving consumer is rewritten in the same transaction. Provider-first
;;     order is intentional: intermediate red worlds are not observable.
;; ============================================================================
(def txalpha-module "src.fram.txalpha")
(def txbeta-module "src.fram.txbeta")
(def txalpha-file (str nested-dir "/txalpha.bclj"))
(def txbeta-file (str nested-dir "/txbeta.bclj"))

(let [specs [{:op "set-body" :module txalpha-module
              :name "shared-name" :datum 101}
             {:op "set-body" :module txbeta-module
              :name "shared-name" :datum 202}]
      prep (transaction-coord {:op :edit-prepare :specs specs})
      touched [txalpha-module txbeta-module]
      paths {txalpha-module txalpha-file txbeta-module txbeta-file}]
  (chk "T3: duplicate target spellings are scoped by module during prepare"
       (and (true? (:ok prep))
            (= 2 (:edits prep))))
  (chk "T3: multi-module prepare returns all paths, projections, and check scope"
       (and (= touched (:modules prep))
            (= paths (:paths prep))
            (every? string?
                    (map #(get (:edn-by-module prep) %) touched))
            (every? (set (:checked-modules prep)) touched))))

(let [before (read-bytes transaction-log)
      result (mcp-edit
              transaction-env 16 "edit-transaction"
              {:module txalpha-module
               :edits [{:op "set-body"
                        :name "shared-name"
                        :body "101"}
                       {:op "set-body"
                        :module txbeta-module
                        :name "shared-name"
                        :body "202"}]})
      text (or (rtext result) "")
      after (read-bytes transaction-log)
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes after
                         (alength ^bytes before)
                         (alength ^bytes after))
                        "UTF-8")
      alpha-source (slurp txalpha-file)
      beta-source (slurp txbeta-file)]
  (chk "T3: top-level module defaults one edit while a nested override targets another"
       (and (not (rerr? result))
            (str/includes? text "committed + TYPE/WORLD CHECK CLEAN")
            (str/includes? alpha-source ":- Int 101)")
            (str/includes? beta-source ":- Int 202)")))
  (chk "T3: two-module commit emits exactly one durable graph envelope"
       (= 1 (count (re-seq #":fram-edit-envelope" appended)))))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      alpha0 (slurp txalpha-file)
      beta0 (slurp txbeta-file)
      result (mcp-edit
              transaction-env 17 "edit-transaction"
              {:module txalpha-module
               :edits [{:op "set-body"
                        :name "shared-name"
                        :body "303"}
                       {:op "set-body"
                        :module txbeta-module
                        :name "shared-name"
                        :body "\"red-module\""}]})
      text (or (rtext result) "")]
  (chk "T3: one red module rejects the coherent final world before commit"
       (and (rerr? result)
            (str/includes? text "coordinator TYPE/WORLD check")
            (str/includes? text "nothing committed")))
  (chk "T3: red multi-module world rolls log, version, and both projections back byte-identically"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= alpha0 (slurp txalpha-file))
            (= beta0 (slurp txbeta-file)))))

(def txvariant-provider-module "src.fram.txvariantprov")
(def txvariant-consumer-module "src.fram.txvariantconsumer")
(def txvariant-provider-file (str nested-dir "/txvariantprov.bclj"))
(def txvariant-consumer-file (str nested-dir "/txvariantconsumer.bclj"))
(def event-without-created-form
  "(defunion Event (Retired [reason :- String]))")
(def use-created-without-provider-form
  "(defn use-created [x :- Int] :- Int x)")

(let [prep (transaction-coord
            {:op :edit-prepare
             :spec {:op "set-body"
                    :module txvariant-provider-module
                    :name "provider-marker"
                    :datum 2}})]
  (chk "T3: dependency closure maps graph source ids through declared namespaces"
       (and (true? (:ok prep))
            (some #{txvariant-provider-module} (:checked-modules prep))
            (some #{txvariant-consumer-module} (:checked-modules prep)))))

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      provider0 (slurp txvariant-provider-file)
      consumer0 (slurp txvariant-consumer-file)
      result (mcp-edit
              transaction-env 18 "edit-transaction"
              {:module txvariant-provider-module
               :edits [{:op "upsert-form"
                        :form event-without-created-form}
                       {:op "set-body"
                        :name "provider-marker"
                        :body "2"}]})
      text (or (rtext result) "")]
  (chk "T3: removing a still-referenced union variant is a typed final-world rejection"
       (and (rerr? result)
            (str/includes? text "orphaned-binding-references")
            (str/includes? text "orphan")))
  (chk "T3: rejected referenced-variant removal is byte-identical everywhere"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= provider0 (slurp txvariant-provider-file))
            (= consumer0 (slurp txvariant-consumer-file)))))

(let [provider-spec {:op "upsert-form"
                     :module txvariant-provider-module
                     :datum (edn/read-string event-without-created-form)}
      consumer-spec {:op "upsert-form"
                     :module txvariant-consumer-module
                     :datum (edn/read-string use-created-without-provider-form)}
      log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      provider-first
      (transaction-coord
       {:op :edit-prepare :specs [provider-spec consumer-spec]})
      consumer-first
      (transaction-coord
       {:op :edit-prepare :specs [consumer-spec provider-spec]})
      touched [txvariant-consumer-module txvariant-provider-module]]
  (chk "T3: coherent final-world acceptance is invariant under provider/consumer edit order"
       (and (true? (:ok provider-first))
            (true? (:ok consumer-first))
            (= touched (:modules provider-first))
            (= touched (:modules consumer-first))
            (= (set (:checked-modules provider-first))
               (set (:checked-modules consumer-first)))))
  (chk "T3: both order rehearsals are prepare-only and leave the canonical log/version untouched"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version)))))

(let [before (read-bytes transaction-log)
      result (mcp-edit
              transaction-env 19 "edit-transaction"
              {:module txvariant-provider-module
               :edits [{:op "upsert-form"
                        :form event-without-created-form}
                       {:op "upsert-form"
                        :module txvariant-consumer-module
                        :form use-created-without-provider-form}]})
      text (or (rtext result) "")
      after (read-bytes transaction-log)
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes after
                         (alength ^bytes before)
                         (alength ^bytes after))
                        "UTF-8")
      provider (slurp txvariant-provider-file)
      consumer (slurp txvariant-consumer-file)]
  (chk "T3: provider-first variant removal commits when the same transaction removes its consumer"
       (and (not (rerr? result))
            (str/includes? text "committed + TYPE/WORLD CHECK CLEAN")
            (not (str/includes? provider "Created"))
            (not (str/includes? consumer "p/->Created"))
            (str/includes? consumer ":- Int x)")))
  (chk "T3: coherent variant removal and consumer rewrite remain one graph envelope"
       (= 1 (count (re-seq #":fram-edit-envelope" appended)))))

(def txaccessor-provider-module "src.fram.txaccessorprov")
(def txaccessor-consumer-module "src.fram.txaccessorconsumer")
(def txaccessor-provider-file (str nested-dir "/txaccessorprov.bclj"))
(def txaccessor-consumer-file (str nested-dir "/txaccessorconsumer.bclj"))
(def profile-without-legacy-form
  "(defrecord Profile [name :- String])")
(def use-legacy-without-provider-form
  "(defn use-legacy [x :- Int] :- Int x)")

(let [log0 (vec (read-bytes transaction-log))
      v0 (transaction-version)
      provider0 (slurp txaccessor-provider-file)
      consumer0 (slurp txaccessor-consumer-file)
      result (mcp-edit
              transaction-env 20 "edit-transaction"
              {:module txaccessor-provider-module
               :edits [{:op "upsert-form"
                        :form profile-without-legacy-form}
                       {:op "set-body"
                        :name "provider-marker"
                        :body "2"}]})
      text (or (rtext result) "")]
  (chk "T3: removing a still-referenced synthesized accessor is a typed final-world rejection"
       (and (rerr? result)
            (str/includes? text "orphaned-binding-references")
            (str/includes? text "orphan")))
  (chk "T3: rejected referenced-accessor removal is byte-identical everywhere"
       (and (= log0 (vec (read-bytes transaction-log)))
            (= v0 (transaction-version))
            (= provider0 (slurp txaccessor-provider-file))
            (= consumer0 (slurp txaccessor-consumer-file)))))

(let [before (read-bytes transaction-log)
      result (mcp-edit
              transaction-env 21 "edit-transaction"
              {:module txaccessor-provider-module
               :edits [{:op "upsert-form"
                        :form profile-without-legacy-form}
                       {:op "upsert-form"
                        :module txaccessor-consumer-module
                        :form use-legacy-without-provider-form}]})
      text (or (rtext result) "")
      after (read-bytes transaction-log)
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes after
                         (alength ^bytes before)
                         (alength ^bytes after))
                        "UTF-8")
      provider (slurp txaccessor-provider-file)
      consumer (slurp txaccessor-consumer-file)]
  (chk "T3: provider-first accessor removal commits with its consumer rewrite in the final world"
       (and (not (rerr? result))
            (str/includes? text "committed + TYPE/WORLD CHECK CLEAN")
            (not (str/includes? provider "legacy"))
            (not (str/includes? consumer "profile-legacy"))
            (str/includes? consumer ":- Int x)")))
  (chk "T3: coherent accessor removal and consumer rewrite remain one graph envelope"
       (= 1 (count (re-seq #":fram-edit-envelope" appended)))))

(def durable-multi-recovery (atom nil))

(let [specs [{:op "set-body" :module txalpha-module
              :name "shared-name" :datum 404}
             {:op "set-body" :module txbeta-module
              :name "shared-name" :datum 505}]
      expected-modules [txalpha-module txbeta-module]
      expected-paths {txalpha-module txalpha-file txbeta-module txbeta-file}
      before (read-bytes transaction-log)
      prep (transaction-coord {:op :edit-prepare :specs specs})
      req {:op :edit-commit
           :candidate (:candidate prep)
           :version (:version prep)
           :module (:module prep)
           :path (:path prep)
           :ops-digest (:ops-digest prep)
           :edn-digest (:edn-digest prep)}
      commit (if (:ok prep)
               (transaction-coord req)
               prep)
      after (read-bytes transaction-log)
      appended (String. ^bytes
                        (java.util.Arrays/copyOfRange
                         ^bytes after
                         (alength ^bytes before)
                         (alength ^bytes after))
                        "UTF-8")
      envelope
      (some (fn [line]
              (let [row (try (edn/read-string line)
                             (catch Throwable _ nil))]
                (when (and (map? row)
                           (contains? row :fram-edit-envelope))
                  row)))
            (str/split-lines appended))]
  (reset! durable-multi-recovery
          {:req req
           :commit commit
           :expected-modules expected-modules
           :expected-paths expected-paths
           :envelope envelope})
  (chk "T3: direct multi-module prepare/commit installs one durable canonical receipt"
       (and (true? (:ok prep))
            (true? (:ok commit))
            (true? (:committed commit))
            (= expected-modules (:modules commit))
            (= expected-paths (:paths commit))
            (= 1 (count (re-seq #":fram-edit-envelope" appended)))))
  (chk "T3: the durable envelope carries backward-compatible scalar tokens that decode losslessly"
       (and (string? (:fram-edit-module envelope))
            (string? (:fram-edit-path envelope))
            (= (:module req) (:fram-edit-module envelope))
            (= (:path req) (:fram-edit-path envelope))
            (= expected-modules
               (edn/read-string (:fram-edit-module envelope)))
            (= expected-paths
               (into {} (edn/read-string (:fram-edit-path envelope)))))))

(stop-daemon! transaction-daemon)
(let [restarted (boot-daemon! transaction-port transaction-log)]
  (try
    (let [{:keys [req commit expected-modules expected-paths]}
          @durable-multi-recovery
          bytes-before-retry (vec (read-bytes transaction-log))
          recovered (transaction-coord req)
          bytes-after-retry (vec (read-bytes transaction-log))
          cold-targets (transaction-binding-targets)
          reconcile (transaction-coord {:op :snapshot-reconcile})]
      (chk "T3: cold exact retry reconstructs the committed multi-module receipt from its envelope"
           (and (true? (:ok recovered))
                (true? (:committed recovered))
                (true? (:recovered recovered))
                (= :committed-recovered (:code recovered))
                (= (:candidate req) (:candidate recovered))
                (= (:version commit) (:version recovered))
                (= expected-modules (:modules recovered))
                (= expected-paths (:paths recovered))))
      (chk "T3: recovered multi-module receipt retry appends zero bytes"
           (= bytes-before-retry bytes-after-retry))
      (chk "T2: retained type, variant, and function identities survive a cold coordinator restart"
           (= retained-targets-v2 cold-targets))
      (chk "T2: cold-restarted transaction log reconciles to the authoritative snapshot"
           (true? (:ok reconcile))))
    (finally
      (stop-daemon! restarted))))

(when (= "1" (System/getenv "FRAM_TRANSACTION_TEST_ONLY"))
  (p/destroy-tree main-daemon)
  (p/destroy-tree bad-daemon)
  (p/destroy-tree transaction-daemon)
  (let [cs @checks
        fails (filter (fn [[_ ok]] (not ok)) cs)]
    (if (empty? fails)
      (do
        (println (str "\nfram-mcp-edit-transaction: " (count cs) " / "
                      (count cs) " PASS"))
        (p/shell {} "rm" "-rf" tmp)
        (System/exit 0))
      (do
        (println (str "\nfram-mcp-edit-transaction: " (count fails)
                      " FAILED  (workspace left at " tmp ")"))
        (System/exit 1)))))

;; ============================================================================
;; A. NESTED TRACKED-PATH E2E through the MCP surface.
;; ============================================================================
(let [log0 (count (read-bytes code-log))
      v0 (cur-version)
      r (mcp-edit base-env 10 "set-body" {:module "src.fram.wkfix" :name "double-it" :body "(* 21 x)"})
      t (or (rtext r) "")
      txt (slurp wkfix-file)]
  (chk "A: nested set-body through MCP -> isError=false" (and (some? r) (not (rerr? r))))
  (chk "A: reply reports the atomic candidate gate (graph-edit-candidate-v2) + committed"
       (and (str/includes? t "committed") (str/includes? t "graph-edit-candidate-v2")))
  (chk "A: EXACTLY the tracked nested file <src>/src/fram/wkfix.bclj was updated"
       (str/includes? txt "(* 21 x)"))
  (chk "A: the candidate text was compiled (type-checked) before commit — reply says so"
       (str/includes? t "TYPE/WORLD CHECK CLEAN"))
  (chk "A: NO root-level module-name artifact <src>/src.fram.wkfix.bclj exists"
       (not (.exists (io/file wkfix-root-artifact))))
  (chk "A: the code log GREW (the sealed batch is durable)"
       (> (count (read-bytes code-log)) log0))
  (chk "A: the canonical version ADVANCED" (> (cur-version) v0)))

;; ============================================================================
;; A2. THE WHOLE FIVE-TOOL SURFACE drives the candidate gate on the nested module:
;;     add-def, insert-after, replace-in-body, rename-def (set-body proven above).
;; ============================================================================
(let [ok-edit (fn [id tool args]
                (let [r (mcp-edit base-env id tool args) t (or (rtext r) "")]
                  (and (some? r) (not (rerr? r))
                       (str/includes? t "graph-edit-candidate-v2"))))]
  (chk "A2: add-def (upsert-form) lands through the candidate gate"
       (ok-edit 11 "add-def" {:module "src.fram.wkfix"
                              :form "(defn tripled [z :- Int] :- Int (* 3 z))"}))
  (chk "A2: insert-after (CRDT insert) lands through the candidate gate"
       (ok-edit 12 "insert-after" {:module "src.fram.wkfix" :after "tripled"
                                   :form "(defn quadded [q :- Int] :- Int (* 4 q))"}))
  (chk "A2: replace-in-body (sub-def surgical) lands through the candidate gate"
       (ok-edit 13 "replace-in-body" {:module "src.fram.wkfix" :name "tripled"
                                      :old "(* 3 z)" :new "(* 33 z)"}))
  (chk "A2: rename-def (identity rename incl. sealed bound_to ops) lands through the candidate gate"
       (ok-edit 14 "rename-def" {:module "src.fram.wkfix" :name "tripled" :new-name "trebled"}))
  (let [txt (slurp wkfix-file)]
    (chk "A2: tracked nested file reflects all four edits (added, inserted, replaced, renamed)"
         (and (str/includes? txt "defn trebled") (str/includes? txt "(* 33 z)")
              (str/includes? txt "defn quadded")
              (not (str/includes? txt "defn tripled"))))
    (chk "A2: still no root-level module-name artifact"
         (not (.exists (io/file wkfix-root-artifact))))))

;; ============================================================================
;; B. INVALID CANDIDATES — typed rejection, ZERO canonical mutation.
;; ============================================================================
(let [snap-log (vec (read-bytes code-log))
      snap-file (slurp wkfix-file)
      snap-v (cur-version)
      probe (fn [label id body expect-marker]
              (let [r (mcp-edit base-env id "set-body" {:module "src.fram.wkfix" :name "double-it" :body body})
                    t (or (rtext r) "")]
                (chk (str "B: " label " -> isError with REJECTED + nothing-committed marker")
                     (and (rerr? r) (str/includes? t "REJECTED") (str/includes? t "nothing committed")))
                (chk (str "B: " label " -> diagnostic carries " (pr-str expect-marker))
                     (str/includes? t expect-marker))))]
  ;; unreadable payload — refused BEFORE any coordinator contact.
  (let [r (mcp-edit base-env 20 "set-body" {:module "src.fram.wkfix" :name "double-it" :body "(* 2"})
        t (or (rtext r) "")]
    (chk "B: unreadable EDN body -> typed rejection before any coordinator contact"
         (and (rerr? r) (str/includes? t "not readable EDN") (str/includes? t "nothing"))))
  ;; syntax-invalid: reads as EDN, renders, but fails beagle's parse (bad let bindings).
  (probe "syntax-invalid body (let [x] x)" 21 "(let [x] x)" "bad let bindings")
  ;; type-invalid: a String body on a :- Int defn.
  (probe "type-invalid body \"not-an-int\" on :- Int" 22 "\"not-an-int\"" "sealed Beagle parse/type check")
  (chk "B: canonical code log BYTE-IDENTICAL across all invalid candidates"
       (= snap-log (vec (read-bytes code-log))))
  (chk "B: canonical version UNCHANGED across all invalid candidates"
       (= snap-v (cur-version)))
  (chk "B: tracked projection BYTE-IDENTICAL across all invalid candidates"
       (= snap-file (slurp wkfix-file)))
  (chk "B: no batch journal left behind" (not (.exists (io/file (str code-log ".edit-batch"))))))

;; ============================================================================
;; C. STALE CAS — candidate A prepared, edit B lands (full MCP cycle), commit A
;;    rejects :stale-version with zero canonical operations.
;; ============================================================================
(let [pa (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                          :name "double-it" :datum '(* 31 x)}})
      _ (chk "C: candidate A prepared (zero writes)" (true? (:ok pa)))
      rb (mcp-edit base-env 30 "set-body" {:module "src.fram.wkfix" :name "double-it" :body "(* 32 x)"})
      _ (chk "C: concurrent edit B lands through the full MCP surface" (not (rerr? rb)))
      bytes1 (vec (read-bytes code-log))
      v1 (cur-version)
      file1 (slurp wkfix-file)
      ca (coord {:op :edit-commit :candidate (:candidate pa) :version (:version pa)
                 :module "src.fram.wkfix" :path (:path pa)
                 :ops-digest (:ops-digest pa) :edn-digest (:edn-digest pa)})]
  (chk "C: commit A -> typed :stale-version rejection" (= :stale-version (:code ca)))
  (chk "C: stale rejection names both versions"
       (let [m (str (first (:reject ca)))]
         (and (str/includes? m (str (:version pa))) (str/includes? m "re-prepare"))))
  (chk "C: canonical log BYTE-IDENTICAL across the stale commit" (= bytes1 (vec (read-bytes code-log))))
  (chk "C: canonical version UNCHANGED across the stale commit" (= v1 (cur-version)))
  (chk "C: tracked projection UNCHANGED across the stale commit (B's content stands)"
       (and (= file1 (slurp wkfix-file)) (str/includes? file1 "(* 32 x)"))))

;; ============================================================================
;; D. INJECTED FAILURE AT EVERY OPERATION BOUNDARY -> typed rejection, zero
;;    canonical operations; then ONE clean batch commits COMPLETELY.
;; ============================================================================
(let [bytes0 (vec (read-bytes code-log))
      v0 (cur-version)
      n (:ops (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                               :name "double-it" :datum '(* 41 x)}}))
      _ (chk "D: probe candidate seals a multi-op batch (n >= 4)" (and (integer? n) (>= n 4)))
      ;; EVERY operation boundary, 0 through n inclusive — before the first op,
      ;; between every adjacent pair, and after the last op pre-install.
      boundaries (range 0 (inc n))]
  (doseq [b boundaries]
    (let [prep (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                                :name "double-it" :datum '(* 41 x)}})
          r (coord {:op :edit-commit :candidate (:candidate prep) :version (:version prep)
                    :module "src.fram.wkfix" :path (:path prep)
                    :ops-digest (:ops-digest prep) :edn-digest (:edn-digest prep)
                    :inject-fail-at b})]
      (chk (str "D: injected failure at boundary " b "/" n " -> typed :injected-failure at " b)
           (and (= :injected-failure (:code r)) (= b (:at r))))))
  (chk "D: canonical log BYTE-IDENTICAL across every injected failure"
       (= bytes0 (vec (read-bytes code-log))))
  (chk "D: canonical version UNCHANGED across every injected failure" (= v0 (cur-version)))
  ;; digest tampering on a FRESH candidate also rejects with zero ops.
  (let [prep (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                              :name "double-it" :datum '(* 41 x)}})
        r (coord {:op :edit-commit :candidate (:candidate prep) :version (:version prep)
                  :module "src.fram.wkfix" :path (:path prep)
                  :ops-digest "0000000000000000" :edn-digest (:edn-digest prep)})]
    (chk "D: ops-digest tampering -> typed :digest-mismatch, zero canonical operations"
         (and (= :digest-mismatch (:code r)) (= v0 (cur-version))
              (= bytes0 (vec (read-bytes code-log))))))
  ;; one clean batch commits COMPLETELY: version advances by exactly the installed
  ;; op count and the whole batch is durable in one append.
  (let [prep (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                              :name "double-it" :datum '(* 42 x)}})
        r (coord {:op :edit-commit :candidate (:candidate prep) :version (:version prep)
                  :module "src.fram.wkfix" :path (:path prep)
                  :ops-digest (:ops-digest prep) :edn-digest (:edn-digest prep)})]
    (chk "D: clean batch commits completely (ok, all ops installed)"
         (and (true? (:ok r)) (= (:ops r) (:installed r))))
    (chk "D: version advanced by EXACTLY the installed op count"
         (= (cur-version) (+ v0 (:installed r))))
    (chk "D: the whole batch is durable (log grew)"
         (> (count (read-bytes code-log)) (count bytes0)))))

;; ============================================================================
;; E. RECOVERY-INTENT REPLAY — the journal is recovery INTENT bound to the
;;    canonical log identity + exact pre-state digest; the commit point is the
;;    awaited batch append/fsync. Boot recovery redoes ONLY a proven-bound
;;    sealed intent; foreign/rewritten/torn journals leave the log untouched.
;; ============================================================================
(defn sha256-hex-bytes [^bytes bs]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" %) d))))
;; boot-recovery probe, exactly what serve-flat-daemon runs before its fold
;; (used by E's replay cases and I's restart-equivalence check).
(defn recover! [log-path]
  (p/shell {:continue true :out :string :err :string :env scrub-env :dir root}
           "bb" "-cp" "out" "-e"
           (str "(load-file \"coord_daemon.clj\") (recover-edit-journal! " (pr-str log-path) ")")))
(let [pre-bytes (read-bytes code-log)
      pre-len (count pre-bytes)
      prep (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                            :name "double-it" :datum '(* 51 x)}})
      cm (coord {:op :edit-commit :candidate (:candidate prep) :version (:version prep)
                 :module "src.fram.wkfix" :path (:path prep)
                 :ops-digest (:ops-digest prep) :edn-digest (:edn-digest prep)})
      _ (chk "E: reference batch committed live" (true? (:ok cm)))
      _ (chk "E: no recovery intent survives an ACKED commit (journal durably removed)"
             (not (.exists (io/file (str code-log ".edit-batch")))))
      v-after (cur-version)
      post-bytes (read-bytes code-log)
      batch (String. ^bytes (java.util.Arrays/copyOfRange ^bytes post-bytes pre-len (count post-bytes)) "UTF-8")
      batch-lines (mapv #(str % "\n") (str/split-lines batch))
      _ (chk "E: captured batch region is line-shaped (>= 4 lines)" (>= (count batch-lines) 4))
      pre-region (java.util.Arrays/copyOfRange ^bytes pre-bytes 0 pre-len)
      ;; v2 recovery intent: bound to the CANONICAL LOG PATH and the EXACT
      ;; pre-state digest over the first :pre-bytes bytes.
      journal-of (fn [log-path]
                   (pr-str {:fram-edit-batch 2
                            :log (.getCanonicalPath (io/file log-path))
                            :pre-bytes pre-len
                            :pre-sha (sha256-hex-bytes pre-region)
                            :lines batch-lines :sha (sha256-hex (apply str batch-lines))}))
      ;; (1) SEALED journal + torn MID-BATCH main log -> redo the WHOLE batch byte-exactly.
      elog (str tmp "/replay-torn.log")
      torn (byte-array (+ pre-len (count (.getBytes ^String (first batch-lines) "UTF-8")) 7))]
  (System/arraycopy pre-bytes 0 torn 0 pre-len)
  (let [l1 (.getBytes ^String (first batch-lines) "UTF-8")
        l2 (.getBytes ^String (second batch-lines) "UTF-8")]
    (System/arraycopy l1 0 torn pre-len (count l1))
    (System/arraycopy l2 0 torn (+ pre-len (count l1)) 7))          ; 7 bytes of line 2 = torn tail
  (write-bytes elog torn)
  (spit (str elog ".edit-batch") (journal-of elog))
  (let [r (recover! elog)]
    (chk "E: recovery over torn-mid-batch log reports completion" (str/includes? (str (:err r) (:out r)) "completing atomic batch"))
    (chk "E: recovered log == pre + WHOLE batch (byte-exact, all-or-nothing)"
         (= (vec post-bytes) (vec (read-bytes elog))))
    (chk "E: sealed journal consumed after redo" (not (.exists (io/file (str elog ".edit-batch"))))))
  ;; (2) TORN journal + pristine pre-batch log -> discard; log untouched (batch never happened).
  (let [elog2 (str tmp "/replay-tornj.log")
        j (journal-of elog2)]
    (write-bytes elog2 (java.util.Arrays/copyOfRange ^bytes pre-bytes 0 pre-len))
    (spit (str elog2 ".edit-batch") (subs j 0 (quot (count j) 2)))   ; torn journal write
    (let [r (recover! elog2)]
      (chk "E: torn journal is DISCARDED (batch never committed)"
           (str/includes? (str (:err r) (:out r)) "torn/invalid"))
      (chk "E: log byte-identical after torn-journal discard"
           (= (vec (java.util.Arrays/copyOfRange ^bytes pre-bytes 0 pre-len)) (vec (read-bytes elog2))))
      (chk "E: torn journal deleted" (not (.exists (io/file (str elog2 ".edit-batch")))))))
  ;; (3) a REAL daemon boots the crash state end-to-end (recovery wired at serve-flat
  ;;     boot) and serves the batch: version == the live daemon's post-commit version.
  (let [elog3 (str tmp "/replay-boot.log")]
    (write-bytes elog3 torn)
    (spit (str elog3 ".edit-batch") (journal-of elog3))
    (let [d (boot-daemon! replay-port elog3)]
      (try
        (chk "E: rebooted daemon serves the WHOLE redone batch (version == live post-commit)"
             (= v-after (:version (coord replay-port elog3 {:op :version}))))
        (chk "E: journal consumed by the boot recovery" (not (.exists (io/file (str elog3 ".edit-batch")))))
        (chk "E: recovered log byte-identical to the live post-commit log"
             (= (vec post-bytes) (vec (read-bytes elog3))))
        (finally (p/destroy-tree d)))))
  ;; (4) COPIED SIDECAR — a VALID sealed intent for one log, copied beside a
  ;;     DIFFERENT log, must be rejected by the canonical-log-identity binding
  ;;     and leave the victim log byte-identical (never redo onto a foreign log).
  (let [elog4 (str tmp "/replay-owner.log")
        elog5 (str tmp "/replay-victim.log")]
    (write-bytes elog4 torn)
    (write-bytes elog5 pre-region)                      ; victim: same pre-state content, different identity
    (spit (str elog5 ".edit-batch") (journal-of elog4)) ; sidecar bound to elog4, copied beside elog5
    (let [r (recover! elog5)]
      (chk "E: sidecar copied beside another log -> REJECTED by canonical-log-identity binding"
           (str/includes? (str (:err r) (:out r)) "DIFFERENT canonical log"))
      (chk "E: victim log BYTE-IDENTICAL after the sidecar rejection"
           (= (vec pre-region) (vec (read-bytes elog5))))
      (chk "E: rejected sidecar discarded (not left to re-fire)"
           (not (.exists (io/file (str elog5 ".edit-batch")))))))
  ;; (5) REWRITTEN PREFIX — the SAME log, same length, one byte changed BEFORE
  ;;     the recorded boundary, journal untouched: the exact pre-state digest
  ;;     must reject the redo and leave the (rewritten) log byte-identical.
  (let [elog6 (str tmp "/replay-rewritten.log")
        doctored (java.util.Arrays/copyOf ^bytes pre-region (int pre-len))]
    (aset-byte doctored 5 (byte (bit-xor (aget ^bytes doctored 5) 1)))
    (write-bytes elog6 doctored)
    (spit (str elog6 ".edit-batch") (journal-of elog6))  ; identity matches; PREFIX does not
    (let [r (recover! elog6)]
      (chk "E: rewritten prefix under an unchanged journal -> REJECTED by the exact pre-state digest"
           (str/includes? (str (:err r) (:out r)) "pre-state digest mismatch"))
      (chk "E: rewritten log BYTE-IDENTICAL after the rejection (recovery never touched it)"
           (= (vec doctored) (vec (read-bytes elog6))))
      (chk "E: rejected journal discarded (not left to re-fire)"
           (not (.exists (io/file (str elog6 ".edit-batch")))))))
  ;; (6) STALE VALID INTENT AFTER ACK — PRE+BATCH+LATER can arise when intent
  ;;     cleanup failed after the batch ack and later commits were acknowledged.
  ;;     Recovery must recognize BATCH in place, preserve every LATER byte, and
  ;;     retire the stale intent — never truncate back to PRE+BATCH.
  (let [elog7 (str tmp "/replay-acked-later.log")
        later (.getBytes "LATER-ACKNOWLEDGED-BYTES\n" "UTF-8")
        all (byte-array (+ (alength ^bytes post-bytes) (alength ^bytes later)))]
    (System/arraycopy post-bytes 0 all 0 (alength ^bytes post-bytes))
    (System/arraycopy later 0 all (alength ^bytes post-bytes) (alength ^bytes later))
    (write-bytes elog7 all)
    (spit (str elog7 ".edit-batch") (journal-of elog7))
    (let [before (vec (read-bytes elog7))
          r (recover! elog7)]
      (chk "E: PRE+BATCH+LATER recovery recognizes already-present batch"
           (str/includes? (str (:err r) (:out r)) "batch already present"))
      (chk "E: PRE+BATCH+LATER remains BYTE-IDENTICAL (later acknowledged tail preserved)"
           (= before (vec (read-bytes elog7))))
      (chk "E: stale valid intent retired after preserving later tail"
           (not (.exists (io/file (str elog7 ".edit-batch"))))))))

;; ============================================================================
;; I. DURABLE-APPEND FAILURE BEFORE ROOT SWAP — the live root never advances on
;;    an unproven append: injected append/fsync failure (with a real PARTIAL
;;    write on disk) and injected directory-fsync failure each return a typed
;;    :durability-failure, restore the exact pre-state, leave no recovery
;;    intent, and are NOT scheduled to appear after restart.
;; ============================================================================
(let [bytes0 (vec (read-bytes code-log))
      v0 (cur-version)
      file0 (slurp wkfix-file)
      commit! (fn [prep extra]
                (coord (merge {:op :edit-commit :candidate (:candidate prep) :version (:version prep)
                               :module "src.fram.wkfix" :path (:path prep)
                               :ops-digest (:ops-digest prep) :edn-digest (:edn-digest prep)}
                              extra)))
      prep1 (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                             :name "double-it" :datum '(* 81 x)}})
      r1 (commit! prep1 {:inject-durable-fail true})]
  (chk "I: injected append/fsync failure (partial write) -> typed :durability-failure"
       (= :durability-failure (:code r1)))
  (chk "I: the rejection names the injected durable-append failure + pre-state restore"
       (let [m (str (first (:reject r1)))]
         (and (str/includes? m "injected durable-append failure")
              (str/includes? m "restored"))))
  (chk "I: exact pre-state restored — log BYTE-IDENTICAL (partial write erased)"
       (= bytes0 (vec (read-bytes code-log))))
  (chk "I: canonical version UNCHANGED (root never advanced)" (= v0 (cur-version)))
  (chk "I: tracked projection UNCHANGED" (= file0 (slurp wkfix-file)))
  (chk "I: no recovery intent left behind (journal durably removed)"
       (not (.exists (io/file (str code-log ".edit-batch")))))
  ;; restart-equivalence: boot recovery over a COPY of the exact post-failure
  ;; disk state (log + absent journal) is a NO-OP — the reported-failed batch
  ;; is not scheduled to appear after restart.
  (let [cp (str tmp "/post-durable-fail.log")]
    (write-bytes cp (read-bytes code-log))
    (let [rr (recover! cp)]
      (chk "I: boot recovery over the post-failure state is a NO-OP (nothing scheduled to appear after restart)"
           (and (zero? (:exit rr))
                (not (str/includes? (str (:out rr) (:err rr)) "redoing"))
                (= bytes0 (vec (read-bytes cp)))))))
  ;; directory-fsync failure at intent PUBLICATION: fail-closed typed rejection,
  ;; zero canonical mutation, no journal residue (nothing of the batch touched the log).
  (let [prep2 (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                               :name "double-it" :datum '(* 81 x)}})
        r2 (commit! prep2 {:inject-dirsync-fail true})]
    (chk "I: injected directory-fsync failure -> typed :durability-failure (fail closed, nothing committed)"
         (and (= :durability-failure (:code r2))
              (str/includes? (str (first (:reject r2))) "injected directory-fsync failure")))
    (chk "I: log BYTE-IDENTICAL + version UNCHANGED across the dirsync failure"
         (and (= bytes0 (vec (read-bytes code-log))) (= v0 (cur-version))))
    (chk "I: no journal residue after the dirsync failure"
         (not (.exists (io/file (str code-log ".edit-batch"))))))
  ;; the daemon is CONSISTENT after the restores: a clean commit still lands whole.
  (let [prep3 (coord {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                               :name "double-it" :datum '(* 82 x)}})
        r3 (commit! prep3 {})]
    (chk "I: clean commit after the restores lands completely (daemon consistent)"
         (and (true? (:ok r3)) (= (:ops r3) (:installed r3))
              (= (cur-version) (+ v0 (:installed r3)))))))

;; Indeterminate path on an isolated daemon: append writes a real partial batch,
;; exact restore is injected to fail, so the sealed intent remains the one boot
;; authority. The triggering response is NOT :durability-failure, state is
;; externally visible, all later work is poison-stopped, a new restricted MCP
;; refuses admission, and sole-writer restart completes exactly that batch.
(def poison-log (str src-dir "/.fram/poison-restart.log"))
(write-bytes poison-log (read-bytes code-log))
(def poison-before-version
  (let [daemon (boot-daemon! poison-port poison-log)]
    (try
      (let [v0 (:version (coord poison-port poison-log {:op :version}))
            prep (coord poison-port poison-log
                        {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                                  :name "double-it" :datum '(* 83 x)}})
            r (coord poison-port poison-log
                     {:op :edit-commit :candidate (:candidate prep) :version (:version prep)
                      :module "src.fram.wkfix" :path (:path prep)
                      :ops-digest (:ops-digest prep) :edn-digest (:edn-digest prep)
                      :inject-durable-fail true :inject-restore-fail true})
            status (coord poison-port poison-log {:op :status})
            protocol (coord poison-port poison-log {:op :edit-protocol})
            later (coord poison-port poison-log
                         {:op :edit-prepare :spec {:op "set-body" :module "src.fram.wkfix"
                                                   :name "double-it" :datum '(* 84 x)}})
            mcp (run-mcp (assoc base-env
                                "FRAM_CODE_PORT" (str poison-port)
                                "FRAM_CODE_LOG" poison-log)
                         [init-req])]
        (chk "I: append + restore failure -> typed :durability-indeterminate (never definitive failure)"
             (= :durability-indeterminate (:code r)))
        (chk "I: indeterminate response explicitly says POISONED + DO NOT RETRY"
             (let [m (str (first (:reject r)))]
               (and (str/includes? m "POISONED") (str/includes? m "MUST NOT be retried"))))
        (chk "I: :status exposes poisoned phase/log for external supervision"
             (and (= :poisoned (get-in status [:durability :state]))
                  (= :pre-state-restore (get-in status [:durability :phase]))
                  (= (.getCanonicalPath (io/file poison-log))
                     (get-in status [:durability :log]))))
        (chk "I: :edit-protocol exposes the same poisoned admission state"
             (= :poisoned (get-in protocol [:durability :state])))
        (chk "I: subsequent commit preparation is poison-stopped, not ordinary retryable failure"
             (= :durability-poisoned (:code later)))
        (chk "I: valid sealed intent remains for deterministic restart authority"
             (.isFile (io/file (str poison-log ".edit-batch"))))
        (chk "I: a new graph-edit-v1 MCP explicitly refuses DURABILITY-POISONED admission"
             (and (not (zero? (:exit mcp))) (empty? (:by-id mcp))
                  (str/includes? (str (:err mcp)) "DURABILITY-POISONED")))
        v0)
      (finally (p/destroy-tree daemon)))))

(Thread/sleep 250)
(let [daemon (boot-daemon! poison-restart-port poison-log)]
  (try
    (let [status (coord poison-restart-port poison-log {:op :status})]
      (chk "I: sole-writer restart consumes the valid recovery intent"
           (not (.exists (io/file (str poison-log ".edit-batch")))))
      (chk "I: restart deterministically lands the indeterminate batch and returns healthy"
           (and (> (:version status) poison-before-version)
                (= :healthy (get-in status [:durability :state])))))
    (finally (p/destroy-tree daemon))))

;; ============================================================================
;; J. DIRECTORY-DURABILITY SEAMS (direct) — publication/removal call the parent
;;    directory fsync; forced failure propagates TYPED from both (fail closed);
;;    publication is same-directory temp + atomic rename with v2 identity binding.
;; ============================================================================
(let [probe-file (str tmp "/j_probe.clj")
      _ (spit probe-file (str
        "(load-file \"coord_daemon.clj\")\n"
        "(let [dir \"" tmp "/jdir\"\n"
        "      _ (.mkdirs (java.io.File. ^String dir))\n"
        "      log (str dir \"/j.log\")\n"
        "      jf  (str log \".edit-batch\")\n"
        "      calls (atom [])\n"
        "      orig fsync-dir!]\n"
        "  (spit log \"pre-1\\npre-2\\n\")\n"
        "  (with-redefs [fsync-dir! (fn [d] (swap! calls conj (str d)) (orig d))]\n"
        "    (publish-edit-journal! log [\"x1\\n\" \"x2\\n\"] (.length (java.io.File. ^String log)))\n"
        "    (let [pub-calls (count @calls)\n"
        "          j (clojure.edn/read-string (slurp jf))\n"
        "          tmp-gone (not (.exists (java.io.File. ^String (str jf \".tmp\"))))]\n"
        "      (remove-edit-journal! log)\n"
        "      (println (pr-str {:pub-dir-fsyncs pub-calls\n"
        "                        :rm-dir-fsyncs (- (count @calls) pub-calls)\n"
        "                        :dirs-forced (vec (distinct @calls))\n"
        "                        :v2 (= 2 (:fram-edit-batch j))\n"
        "                        :bound-log (= (:log j) (.getCanonicalPath (java.io.File. ^String log)))\n"
        "                        :pre-sha? (string? (:pre-sha j))\n"
        "                        :tmp-gone tmp-gone\n"
        "                        :removed (not (.exists (java.io.File. ^String jf)))}))))\n"
        "  (let [fail (fn [_] (throw (ex-info \"forced dir-fsync failure\" {:code :forced-dirsync})))\n"
        "        pub-err (try (with-redefs [fsync-dir! fail]\n"
        "                       (publish-edit-journal! log [\"y\\n\"] (.length (java.io.File. ^String log)))) nil\n"
        "                     (catch Throwable t (:code (ex-data t))))\n"
        "        _ (try (remove-edit-journal! log) (catch Throwable _ nil))   ; real fsync: clear residue\n"
        "        _ (publish-edit-journal! log [\"z\\n\"] (.length (java.io.File. ^String log)))\n"
        "        rm-err (try (with-redefs [fsync-dir! fail] (remove-edit-journal! log)) nil\n"
        "                    (catch Throwable t (ex-data t)))]\n"
        "    (println (pr-str {:pub-fail pub-err :rm-fail rm-err})))\n"
        "  (try (remove-edit-journal! log) (catch Throwable _ nil))\n"
        "  (let [capture (fn [at]\n"
        "                  (try (remove-edit-journal! log at) nil\n"
        "                       (catch Throwable t (ex-data t))))\n"
        "        _ (publish-edit-journal! log [\"b\n\"] (.length (java.io.File. ^String log)))\n"
        "        before (capture :before-invalidate)\n"
        "        before-valid-edn (try (= 2 (:fram-edit-batch (clojure.edn/read-string (slurp jf))))\n"
        "                              (catch Throwable _ false))\n"
        "        _ (remove-edit-journal! log)\n"
        "        _ (publish-edit-journal! log [\"a\n\"] (.length (java.io.File. ^String log)))\n"
        "        after-invalidate (capture :after-invalidate)\n"
        "        after-invalidate-zero (and (.exists (java.io.File. ^String jf))\n"
        "                                   (zero? (.length (java.io.File. ^String jf))))\n"
        "        _ (remove-edit-journal! log)\n"
        "        _ (publish-edit-journal! log [\"u\n\"] (.length (java.io.File. ^String log)))\n"
        "        after-unlink (capture :after-unlink)\n"
        "        after-unlink-absent (not (.exists (java.io.File. ^String jf)))]\n"
        "    (println (pr-str {:before before :before-valid-edn before-valid-edn\n"
        "                      :after-invalidate after-invalidate\n"
        "                      :after-invalidate-zero after-invalidate-zero\n"
        "                      :after-unlink after-unlink\n"
        "                      :after-unlink-absent after-unlink-absent}))))\n"))
      r (p/shell {:continue true :out :string :err :string :env scrub-env :dir root}
                 "bb" "-cp" "out" probe-file)
      lines (->> (str/split-lines (or (:out r) "")) (remove str/blank?) vec)
      m1 (try (edn/read-string (nth lines 0)) (catch Throwable _ nil))
      m2 (try (edn/read-string (nth lines 1 nil)) (catch Throwable _ nil))
      m3 (try (edn/read-string (nth lines 2 nil)) (catch Throwable _ nil))]
  (chk "J: direct probe ran (exit 0, three result maps)"
       (and (zero? (:exit r)) (map? m1) (map? m2) (map? m3)))
  (chk "J: journal PUBLICATION fsyncs the containing directory (>= 1 call, correct dir)"
       (and m1 (pos? (:pub-dir-fsyncs m1)) (some #(str/includes? % "/jdir") (:dirs-forced m1))))
  (chk "J: journal REMOVAL fsyncs the containing directory (>= 1 call)"
       (and m1 (pos? (:rm-dir-fsyncs m1))))
  (chk "J: published journal is v2 — bound to the canonical log + pre-state digest; temp gone; removal removes"
       (and m1 (:v2 m1) (:bound-log m1) (:pre-sha? m1) (:tmp-gone m1) (:removed m1)))
  (chk "J: forced directory-fsync failure propagates TYPED from publication (fail closed)"
       (and m2 (= :forced-dirsync (:pub-fail m2))))
  (chk "J: dir-fsync failure after unlink says intent is already durably INVALID"
       (and m2 (= :intent-retirement-failed (get-in m2 [:rm-fail :code]))
            (false? (get-in m2 [:rm-fail :intent-valid?]))))
  (chk "J: cleanup failure BEFORE invalidation reports valid replay intent + leaves valid EDN"
       (and m3 (true? (get-in m3 [:before :intent-valid?])) (:before-valid-edn m3)))
  (chk "J: cleanup failure AFTER truncation reports invalid intent + zero-byte residue"
       (and m3 (false? (get-in m3 [:after-invalidate :intent-valid?]))
            (:after-invalidate-zero m3)))
  (chk "J: cleanup failure AFTER unlink reports invalid intent + absent sidecar"
       (and m3 (false? (get-in m3 [:after-unlink :intent-valid?]))
            (:after-unlink-absent m3))))

;; ============================================================================
;; K. PARENT-IDENTITY PINNING + WRITE-ALL (direct, deterministic) — move the
;;    tracked parent outside the checkout and replace its old entry before the
;;    call: publication reports stale BEFORE temp creation/write, leaving both
;;    moved original and replacement byte-identical. The short-write oracle
;;    forces multiple partial writes and proves the buffer is fully drained.
;; ============================================================================
(let [probe-file (str tmp "/k_probe.clj")
      kdir (str tmp "/kfix")
      _ (spit probe-file (str
        "(load-file \"tests/fram_mcp.clj\")\n"
        "(let [base \"" kdir "\"\n"
        "      parent (str base \"/checkout/src/fram\")\n"
        "      moved (str base \"/../k-moved-outside\")\n"
        "      outside (str base \"/outside\")\n"
        "      impostor (str base \"/impostor\")\n"
        "      _ (.mkdirs (java.io.File. ^String parent))\n"
        "      _ (.mkdirs (java.io.File. ^String outside))\n"
        "      _ (.mkdirs (java.io.File. ^String impostor))\n"
        "      src (str base \"/cand.bclj\")\n"
        "      mv (fn [a b] (java.nio.file.Files/move (.toPath (java.io.File. ^String a)) (.toPath (java.io.File. ^String b)) (into-array java.nio.file.CopyOption [])))\n"
        "      lnk (fn [a b] (java.nio.file.Files/createSymbolicLink (.toPath (java.io.File. ^String a)) (.toPath (java.io.File. ^String b)) (into-array java.nio.file.attribute.FileAttribute [])))]\n"
        "  (spit (str parent \"/leaf.bclj\") \"OLD\")\n"
        "  (spit src \"FIRST\")\n"
        "  (let [pin (pin-parent-dir! parent)\n"
        "        clean (publish-projection-pinned! pin (clojure.java.io/file src) \"leaf.bclj\")\n"
        "        clean-leaf (slurp (str parent \"/leaf.bclj\"))\n"
        "        ;; move ORIGINAL outside checkout, then replace its old entry with a symlink\n"
        "        _ (mv parent moved)\n"
        "        _ (lnk parent outside)\n"
        "        _ (spit src \"SECOND\")\n"
        "        r1 (publish-projection-pinned! pin (clojure.java.io/file src) \"leaf.bclj\")\n"
        "        moved-leaf (slurp (str moved \"/leaf.bclj\"))\n"
        "        outside-files (vec (.list (java.io.File. ^String outside)))\n"
        "        ;; replacement 2: parent entry -> a plain IMPOSTOR directory\n"
        "        _ (java.nio.file.Files/delete (.toPath (java.io.File. ^String parent)))\n"
        "        _ (mv impostor parent)\n"
        "        _ (spit src \"THIRD\")\n"
        "        r2 (publish-projection-pinned! pin (clojure.java.io/file src) \"leaf.bclj\")\n"
        "        impostor-files (vec (.list (java.io.File. ^String parent)))\n"
        "        moved-leaf2 (slurp (str moved \"/leaf.bclj\"))\n"
        "        residue (vec (filter #(clojure.string/starts-with? % \".fram-proj-\") (.list (java.io.File. ^String moved))))\n"
        "        emitted (atom [])\n"
        "        wb (java.nio.ByteBuffer/wrap (.getBytes \"WRITE-ALL\" \"UTF-8\"))\n"
        "        calls (write-byte-buffer-all!\n"
        "               (fn [b]\n"
        "                 (let [n (min 2 (.remaining ^java.nio.ByteBuffer b))\n"
        "                       bs (byte-array n)]\n"
        "                   (.get ^java.nio.ByteBuffer b bs)\n"
        "                   (swap! emitted conj (String. bs \"UTF-8\"))\n"
        "                   n)) wb)]\n"
        "    (release-pin! pin)\n"
        "    (println (pr-str {:pin-ok (not (:err pin))\n"
        "                      :clean-stale (boolean (:stale clean))\n"
        "                      :clean-leaf clean-leaf\n"
        "                      :symlink-stale (boolean (:stale r1))\n"
        "                      :outside-files outside-files\n"
        "                      :moved-leaf moved-leaf\n"
        "                      :impostor-stale (boolean (:stale r2))\n"
        "                      :impostor-files impostor-files\n"
        "                      :moved-leaf2 moved-leaf2\n"
        "                      :residue residue\n"
        "                      :write-calls calls\n"
        "                      :write-bytes (apply str @emitted)\n"
        "                      :write-remaining (.remaining wb)}))))\n"))
      r (p/shell {:continue true :out :string :err :string
                  :env (assoc scrub-env "FRAM_MCP_LIBRARY" "1") :dir root}
                 "bb" "-cp" "out" probe-file)
      m (try (edn/read-string (last (remove str/blank? (str/split-lines (or (:out r) ""))))) (catch Throwable _ nil))]
  (chk "K: direct pin probe ran (exit 0, result map)" (and (zero? (:exit r)) (map? m)))
  (chk "K: parent pinned after canonical validation; clean publish lands through the pin (not stale)"
       (and m (:pin-ok m) (not (:clean-stale m)) (= "FIRST" (:clean-leaf m))))
  (chk "K: SYMLINK-replaced parent entry -> publish reports projection-stale"
       (and m (:symlink-stale m)))
  (chk "K: moved-outside original remains BYTE-IDENTICAL; outside replacement EMPTY"
       (and m (empty? (:outside-files m)) (= "FIRST" (:moved-leaf m))))
  (chk "K: IMPOSTOR replacement stale+EMPTY; moved original still BYTE-IDENTICAL"
       (and m (:impostor-stale m) (empty? (:impostor-files m)) (= "FIRST" (:moved-leaf2 m))))
  (chk "K: no temp residue in the moved original" (and m (empty? (:residue m))))
  (chk "K: deterministic short-write oracle required multiple writes and drained exact bytes"
       (and m (> (:write-calls m) 1) (= "WRITE-ALL" (:write-bytes m))
            (zero? (:write-remaining m)))))

;; ============================================================================
;; L. POST-COMMIT OUTCOME TRUTH — append+fsync is the commit point. Fault every
;;    later source-order operation on an isolated log, restart that log exactly
;;    once, and prove both the live warning receipt and cold reconstructed receipt
;;    name the same candidate/final version without duplicating a byte. Then halt
;;    a real process immediately after append acknowledgement, before receipt/root.
;; ============================================================================
(def post-publication-stages
  [:outcome-record :journal-retire :root-swap :index-cache-invalidate
   :wire-cache-invalidate :mark-dirty :notify :candidate-retire
   :warning-aggregation :response-construction])

(defn candidate-commit-req [prep extra]
  (merge {:op :edit-commit
          :candidate (:candidate prep)
          :version (:version prep)
          :module (:module prep)
          :path (:path prep)
          :ops-digest (:ops-digest prep)
          :edn-digest (:edn-digest prep)}
         extra))

(doseq [stage post-publication-stages]
  (let [seam-log (str tmp "/post-" (name stage) ".log")
        _ (write-bytes seam-log (read-bytes code-log))
        daemon (boot-daemon! post-port seam-log)]
    (try
      (let [datum 'pname
            prep (coord post-port seam-log
                        {:op :edit-prepare
                         :spec {:op "set-body" :module "src.fram.schema"
                                :name "cardinality" :datum datum}})
            req (candidate-commit-req prep {:inject-post-publication-at stage})
            v0 (:version prep)
            bytes0 (count (read-bytes seam-log))
            r (coord post-port seam-log req)
            final (+ v0 (:installed r))
            warning (some #(when (= stage (:stage %)) %) (:warnings r))
            committed-bytes (vec (read-bytes seam-log))]
        (chk (str "L: " (name stage) " candidate is the exact three-op effective schema/cardinality batch")
             (and (true? (:ok prep)) (= 3 (:ops prep)) (= 3 (:installed r))))
        (chk (str "L: " (name stage) " returns explicit COMMITTED warning with exact receipt")
             (and (true? (:ok r)) (true? (:committed r))
                  (= :committed-with-warning (:code r))
                  (= (:candidate prep) (:candidate r) (:batch r))
                  (= v0 (:base-version r)) (= final (:version r))
                  (= stage (:stage warning))))
        (chk (str "L: " (name stage) " advanced version/log exactly and retired the journal")
             (and (= final (:version (coord post-port seam-log {:op :version})))
                  (> (count committed-bytes) bytes0)
                  (not (.exists (io/file (str seam-log ".edit-batch"))))))
        (when (= :notify stage)
          (chk "L: notify seam directly reproduces the verifier's escaped failure text"
               (= "forced post-publication notification failure" (:message warning))))
        (chk (str "L: " (name stage) " exact live retry returns same receipt with zero duplicate bytes")
             (let [again (coord post-port seam-log req)]
               (and (true? (:committed again))
                    (= (:candidate r) (:candidate again))
                    (= (:version r) (:version again))
                    (= committed-bytes (vec (read-bytes seam-log))))))
        (chk (str "L: " (name stage) " initial daemon stops before its one restart")
             (stop-daemon! daemon))
        (let [restarted (boot-daemon! post-restart-port seam-log)]
          (try
            (let [restart-bytes (vec (read-bytes seam-log))
                  recovered (coord post-restart-port seam-log req)
                  status (coord post-restart-port seam-log {:op :status})]
              (chk (str "L: " (name stage) " cold retry reconstructs exact committed receipt after one restart")
                   (and (true? (:ok recovered)) (true? (:committed recovered))
                        (= :committed-recovered (:code recovered))
                        (= (:candidate r) (:candidate recovered))
                        (= (:version r) (:version recovered)
                           (:version status)
                           (get-in status [:last-edit-outcome :version]))
                        (= restart-bytes (vec (read-bytes seam-log)))
                        (not (.exists (io/file (str seam-log ".edit-batch")))))))
            (finally (stop-daemon! restarted)))))
      (finally (stop-daemon! daemon)))))

(let [repair-log (str tmp "/post-root-repair-needed.log")
      _ (write-bytes repair-log (read-bytes code-log))
      daemon (boot-daemon! post-port repair-log)]
  (try
    (let [prep (coord post-port repair-log
                      {:op :edit-prepare
                       :spec {:op "set-body" :module "src.fram.schema"
                              :name "cardinality" :datum 'pname}})
          req (candidate-commit-req
               prep {:inject-post-publication-permanent-at :root-swap})
          bytes0 (count (read-bytes repair-log))
          r (coord post-port repair-log req)
          status (coord post-port repair-log {:op :status})
          again (coord post-port repair-log req)
          blocked (coord post-port repair-log
                         {:op :edit-prepare
                          :spec {:op "set-body" :module "src.fram.schema"
                                 :name "cardinality" :datum 999}})]
      (chk "L: unrecoverable post-commit root failure returns COMMITTED-REPAIR-NEEDED, never rejection"
           (and (true? (:ok r)) (true? (:committed r))
                (= :committed-repair-needed (:code r))
                (true? (:repair-needed r))
                (= :committed-repair-needed (get-in r [:durability :state]))
                (= :root-swap (:stage (last (:warnings r))))))
      (chk "L: repair-needed state keeps exact receipt retryable and stops unrelated authoring"
           (and (= (:candidate r) (:candidate again))
                (= (:version r) (:version again))
                (= :committed-repair-needed (:code again))
                (= :committed-repair-needed (:code blocked))
                (= (:candidate r) (get-in status [:last-edit-outcome :candidate]))))
      (chk "L: repair-needed root stayed pre-commit in memory while canonical log is durably final"
           (and (= (:version prep) (:version status))
                (= (+ (:version prep) (:installed r)) (:version r))
                (> (count (read-bytes repair-log)) bytes0)
                (not (.exists (io/file (str repair-log ".edit-batch"))))))
      (stop-daemon! daemon)
      (let [restarted (boot-daemon! post-restart-port repair-log)]
        (try
          (let [recovered (coord post-restart-port repair-log req)
                healthy (coord post-restart-port repair-log {:op :status})]
            (chk "L: one restart repairs the root and reconstructs the exact committed receipt"
                 (and (= :healthy (get-in healthy [:durability :state]))
                      (= (:version r) (:version recovered) (:version healthy))
                      (= :committed-recovered (:code recovered))
                      (= (:candidate r) (:candidate recovered)))))
          (finally (stop-daemon! restarted)))))
    (finally (stop-daemon! daemon))))

(let [crash-log (str tmp "/post-append-hard-crash.log")
      _ (write-bytes crash-log (read-bytes code-log))
      daemon (boot-daemon! crash-port crash-log)
      prep (coord crash-port crash-log
                  {:op :edit-prepare
                   :spec {:op "set-body" :module "src.fram.schema"
                          :name "cardinality" :datum 'pname}})
      req (candidate-commit-req prep {:inject-crash-after-append-ack true})
      v0 (:version prep)
      bytes0 (count (read-bytes crash-log))
      response (try (coord crash-port crash-log req)
                    (catch Throwable t {:transport-error (.getMessage t)}))]
  (Thread/sleep 250)
  (chk "L: hard-crash candidate is the exact three-op effective schema/cardinality batch"
       (and (true? (:ok prep)) (= 3 (:ops prep))))
  (chk "L: Runtime.halt immediately after append acknowledgement yields no ordinary failure response"
       (and (:transport-error response)
            (not (.isAlive ^Process (:proc daemon)))))
  (chk "L: hard crash happened after durable append and before journal retirement"
       (and (> (count (read-bytes crash-log)) bytes0)
            (.exists (io/file (str crash-log ".edit-batch")))))
  (let [restarted (boot-daemon! crash-restart-port crash-log)]
    (try
      (let [before-retry (vec (read-bytes crash-log))
            recovered (coord crash-restart-port crash-log req)
            status (coord crash-restart-port crash-log {:op :status})
            exact-final (+ v0 (:ops prep))]
        (chk "L: one restart retires the crash journal and boots the exact committed final version"
             (and (= exact-final (:version status))
                  (not (.exists (io/file (str crash-log ".edit-batch"))))))
        (chk "L: exact post-crash retry reconstructs committed receipt, never duplicates the batch"
             (and (true? (:ok recovered)) (true? (:committed recovered))
                  (= :committed-recovered (:code recovered))
                  (= (:candidate prep) (:candidate recovered) (:batch recovered))
                  (= v0 (:base-version recovered))
                  (= exact-final (:version recovered))
                  (= before-retry (vec (read-bytes crash-log))))))
      (finally (stop-daemon! restarted)))))

;; ============================================================================
;; M. COLD RECEIPT ENVELOPE — a recovered receipt requires one exact closed v1
;;    envelope immediately followed by the complete indexed operation batch.
;;    Fact metadata alone, malformed/incomplete groups, wrong recomputed content,
;;    and every truncated prefix remain ordinary/incomplete log data and cannot
;;    produce a committed receipt. Legacy facts still fold unchanged.
;; ============================================================================
(let [seal-log (str tmp "/receipt-envelope-reference.log")
      _ (write-bytes seal-log (read-bytes code-log))
      before (read-bytes seal-log)
      pre-len (count before)
      daemon (boot-daemon! post-port seal-log)]
  (try
    (let [prep (coord post-port seal-log
                      {:op :edit-prepare
                       :spec {:op "set-body" :module "src.fram.schema"
                              :name "cardinality" :datum 'pname}})
          req (candidate-commit-req prep {})
          committed (coord post-port seal-log req)
          committed-bytes (vec (read-bytes seal-log))
          retry (coord post-port seal-log req)
          after (read-bytes seal-log)
          region (String. ^bytes
                          (java.util.Arrays/copyOfRange ^bytes after pre-len (count after))
                          "UTF-8")
          region-lines (mapv #(str % "\n") (str/split-lines region))
          envelope0 (edn/read-string (first region-lines))
          fact-lines (subvec region-lines 1)
          rebind-envelope
          (fn [path overrides]
            (let [e (-> envelope0
                        (merge overrides)
                        (assoc :fram-edit-log (.getCanonicalPath (io/file path)))
                        (dissoc :fram-edit-seal-sha))]
              (assoc e :fram-edit-seal-sha (rt/edit-batch-envelope-seal e))))
          write-case!
          (fn [label lines case-req]
            (let [path (str tmp "/receipt-" label ".log")]
              (spit path (str (String. ^bytes before "UTF-8") (apply str lines)))
              [(keyword label) {:path (.getCanonicalPath (io/file path)) :req case-req}]))
          group-lines (fn [path overrides]
                        (let [e (rebind-envelope path overrides)]
                          (into [(str (pr-str e) "\n")] fact-lines)))
          valid-path (str tmp "/receipt-valid.log")
          valid-case (write-case! "valid" (group-lines valid-path {}) req)
          standalone-row (-> (edn/read-string (first fact-lines))
                             (assoc :fram-edit-module (:module prep)
                                    :fram-edit-path (:path prep)
                                    :fram-edit-base-version (:version prep)
                                    :fram-edit-final-version (:version committed)
                                    :fram-edit-ops (:ops committed)
                                    :fram-edit-installed (:installed committed)
                                    :fram-edit-ops-digest (:ops-digest prep)
                                    :fram-edit-edn-digest (:edn-digest prep)))
          standalone-case (write-case! "standalone" [(str (pr-str standalone-row) "\n")] req)
          wrong-ops (sha256-hex "wrong persisted operation digest")
          wrong-ops-req (assoc req :ops-digest wrong-ops)
          wrong-ops-path (str tmp "/receipt-wrong-ops.log")
          wrong-ops-case (write-case! "wrong-ops"
                                      (group-lines wrong-ops-path
                                                   {:fram-edit-ops-digest wrong-ops})
                                      wrong-ops-req)
          wrong-bytes-path (str tmp "/receipt-wrong-bytes.log")
          wrong-bytes-case (write-case! "wrong-bytes"
                                        (group-lines wrong-bytes-path
                                                     {:fram-edit-batch-sha
                                                      (sha256-hex "wrong exact batch bytes")})
                                        req)
          duplicate-path (str tmp "/receipt-duplicate.log")
          duplicate-lines (let [g (group-lines duplicate-path {})]
                            (vec (concat [(first g)] [(second g) (second g)] (drop 2 g))))
          duplicate-case (write-case! "duplicate" duplicate-lines req)
          interleaved-path (str tmp "/receipt-interleaved.log")
          interloper (str (pr-str {:tx (:version committed) :op "assert"
                                   :l "@interloper" :p "title" :r "between"
                                   :ts "probe" :by "probe"}) "\n")
          interleaved-lines (let [g (group-lines interleaved-path {})]
                              (vec (concat (take 2 g) [interloper] (drop 2 g))))
          interleaved-case (write-case! "interleaved" interleaved-lines req)
          envelope-only-path (str tmp "/receipt-envelope-only.log")
          envelope-only-case (write-case! "envelope-only"
                                          [(first (group-lines envelope-only-path {}))] req)
          facts-only-case (write-case! "facts-only" fact-lines req)
          conflicting-path (str tmp "/receipt-conflicting-count.log")
          conflicting-lines (let [g (group-lines conflicting-path {})
                                  bad (assoc (edn/read-string (nth g 2))
                                             :fram-edit-count (inc (count fact-lines)))]
                              (assoc g 2 (str (pr-str bad) "\n")))
          conflicting-case (write-case! "conflicting-count" conflicting-lines req)
          boundary-cases
          (mapv (fn [cut]
                  (let [label (str "boundary-" cut)
                        path (str tmp "/receipt-" label ".log")
                        g (group-lines path {})]
                    (write-case! label (subvec g 0 cut) req)))
                (range (inc (count fact-lines))))
          partial-cases
          (mapv (fn [cut]
                  (let [label (str "partial-" cut)
                        path (str tmp "/receipt-" label ".log")
                        g (group-lines path {})
                        next-line (nth g cut)
                        partial (subs next-line 0 (max 1 (quot (count next-line) 2)))]
                    (write-case! label
                                 (conj (subvec g 0 cut) partial)
                                 req)))
                (range (inc (count fact-lines))))
          zero-id "00000000-0000-4000-8000-000000000000"
          zero-path (str tmp "/receipt-zero-op.log")
          zero-ops-digest (sha256-hex (pr-str []))
          zero-envelope-base (-> envelope0
                                 (assoc :fram-edit-candidate zero-id
                                        :fram-edit-batch zero-id
                                        :fram-edit-log (.getCanonicalPath (io/file zero-path))
                                        :fram-edit-final-version (:fram-edit-base-version envelope0)
                                        :fram-edit-ops 0
                                        :fram-edit-installed 0
                                        :fram-edit-line-count 0
                                        :fram-edit-ops-digest zero-ops-digest
                                        :fram-edit-batch-sha (sha256-hex ""))
                                 (dissoc :fram-edit-seal-sha))
          zero-envelope (assoc zero-envelope-base
                               :fram-edit-seal-sha (rt/edit-batch-envelope-seal zero-envelope-base))
          zero-req (assoc req :candidate zero-id
                          :ops-digest zero-ops-digest
                          :version (:fram-edit-base-version zero-envelope))
          zero-case (write-case! "zero-op" [(str (pr-str zero-envelope) "\n")] zero-req)
          cases (into {} (concat [valid-case standalone-case wrong-ops-case wrong-bytes-case
                                  duplicate-case interleaved-case envelope-only-case facts-only-case
                                  conflicting-case zero-case]
                                 boundary-cases partial-cases))
          cases-file (str tmp "/receipt-cases.edn")
          probe-file (str tmp "/receipt-probe.clj")
          _ (spit cases-file (pr-str cases))
          _ (spit probe-file
                  (str "(require '[clojure.edn :as edn])\n"
                       "(binding [*command-line-args* []] (load-file " (pr-str (str root "/coord_daemon.clj")) "))\n"
                       "(let [cases (edn/read-string (slurp (first *command-line-args*)))]\n"
                       "  (println (pr-str (into {} (map (fn [[k v]] [k (boolean (persisted-edit-outcome (:path v) (:req v)))]) cases)))))\n"))
          probe (p/shell {:continue true :out :string :err :string :env scrub-env :dir root}
                         "bb" "-cp" "out" probe-file cases-file)
          scan-results (try (edn/read-string
                             (last (remove str/blank? (str/split-lines (or (:out probe) "")))))
                            (catch Throwable _ nil))]
      (chk "M: live commit appends one closed envelope plus its exact three effective operation rows"
           (and (true? (:ok committed)) (= 3 (:installed committed))
                (= 4 (count region-lines))
                (rt/valid-edit-batch-envelope? envelope0)
                (= (:candidate prep) (:fram-edit-candidate envelope0)
                   (:fram-edit-batch envelope0))))
      (chk "M: exact live retry preserves log bytes and final version (no duplicate movement)"
           (and (= committed-bytes (vec (read-bytes seal-log)))
                (= (:version committed) (:version retry))
                (= (:candidate committed) (:candidate retry))))
      (chk "M: direct cold receipt matrix ran successfully"
           (and (zero? (:exit probe)) (map? scan-results)))
      (chk "M: exact envelope + contiguous rows reconstructs the committed receipt"
           (true? (:valid scan-results)))
      (chk "M: internally consistent standalone fact metadata cannot reconstruct a receipt"
           (false? (:standalone scan-results)))
      (chk "M: complete-looking group with wrong recomputed operation digest cannot reconstruct"
           (false? (:wrong-ops scan-results)))
      (chk "M: exact batch-byte digest mismatch cannot reconstruct"
           (false? (:wrong-bytes scan-results)))
      (chk "M: duplicate and interleaved rows cannot reconstruct"
           (and (false? (:duplicate scan-results))
                (false? (:interleaved scan-results))))
      (chk "M: envelope-only, facts-only, and conflicting-count groups cannot reconstruct"
           (and (false? (:envelope-only scan-results))
                (false? (:facts-only scan-results))
                (false? (:conflicting-count scan-results))))
      (chk "M: every envelope/fact line-boundary truncation cannot reconstruct"
           (every? false? (map #(get scan-results (first %)) boundary-cases)))
      (chk "M: every envelope/fact partial-line truncation cannot reconstruct"
           (every? false? (map #(get scan-results (first %)) partial-cases)))
      (chk "M: zero-effective-op receipt is envelope-bound without a minimum-op heuristic"
           (true? (:zero-op scan-results)))
      (chk "M: legacy standalone row carrying old similarly named fields still folds as an ordinary fact"
           (let [legacy-path (get-in cases [:standalone :path])
                 folded (rt/read-log legacy-path)]
             (= (:l standalone-row) (:l (last folded)))))
      (let [malformed-path (str tmp "/receipt-malformed-near-match.log")
            malformed (-> (rebind-envelope malformed-path {})
                          (dissoc :fram-edit-batch-sha))
            _ (spit malformed-path
                    (str (String. ^bytes before "UTF-8") (pr-str malformed) "\n"))
            err (try (rt/read-log malformed-path) nil (catch Throwable t (ex-data t)))]
        (chk "M: discriminator-bearing malformed near-match fails the normal cold fold"
             (true? (:fram/malformed-edit-envelope err)))))
    (finally (stop-daemon! daemon))))

;; ============================================================================
;; F. PROJECTION-STALE — commit lands, tracked-view write fails: loud warning +
;;    repair command; warm render-from-log repairs the file.
;; ============================================================================
(let [v0 (cur-version)
      dir (io/file nested-dir)]
  (.setWritable dir false)
  (let [r (try (mcp-edit base-env 60 "set-body" {:module "src.fram.wkfix" :name "double-it" :body "(* 61 x)"})
               (finally (.setWritable dir true)))
        t (or (rtext r) "")]
    (chk "F: commit with unwritable tracked dir -> NON-error reply (the log is canonical)"
         (and (some? r) (not (rerr? r))))
    (chk "F: reply reports the STALE projection loudly with the repair command"
         (and (str/includes? t "STALE") (str/includes? t "fram-render-code")))
    (chk "F: the canonical version ADVANCED (commit landed despite the stale projection)"
         (> (cur-version) v0))
    (chk "F: tracked file does NOT yet contain the committed body (genuinely stale)"
         (not (str/includes? (slurp wkfix-file) "(* 61 x)")))
    ;; repair: warm render-from-log writes the tracked view back in sync.
    (let [rr (p/shell {:continue true :out :string :err :string :env base-env}
                      "bb" "-cp" (str root "/out") "bin/fram-render-code" "src.fram.wkfix"
                      "--log" code-log "--port" (str main-port) "--out" wkfix-file)]
      (chk "F: warm render-from-log repair exits 0" (zero? (:exit rr)))
      (chk "F: repaired projection contains the committed body (stale detected + healed)"
           (str/includes? (slurp wkfix-file) "(* 61 x)")))))

;; ============================================================================
;; G. PROTOCOL FENCE — a strict-fenced coordinator WITHOUT the candidate protocol
;;    (legacy :edit-min era) is refused at MCP startup.
;; ============================================================================
(def stub-server
  (let [ss (java.net.ServerSocket. stub-port 16 (java.net.InetAddress/getByName "127.0.0.1"))
        t (Thread.
           (fn []
             (try
               (loop []
                 (let [s (.accept ss)]
                   (future
                     (try
                       (with-open [sock s
                                   rd (io/reader (.getInputStream sock))
                                   wr (io/writer (.getOutputStream sock))]
                         (when-let [line (.readLine ^java.io.BufferedReader rd)]
                           (let [req (edn/read-string line)
                                 reply (fn [m] (.write wr (str (pr-str m) "\n")) (.flush wr))]
                             (if (not= :for-log (:op req))
                               ;; strict legacy fence behavior: unwrapped -> :log-fence-required
                               (reply {:reject ["this coordinator requires a :for-log envelope"]
                                       :code :log-fence-required :served-log code-log})
                               (let [inner (:request req)]
                                 (case (:op inner)
                                   :version (reply {:version 42})
                                   ;; NO :edit-protocol / :edit-prepare / :edit-commit — legacy.
                                   (reply {:error "unknown op"})))))))
                       (catch Throwable _ nil))))
                 (recur))
               (catch Throwable _ nil))))]
    (.setDaemon t true) (.start t)
    {:socket ss :thread t}))

(let [{:keys [exit by-id err]} (run-mcp (assoc base-env "FRAM_CODE_PORT" (str stub-port))
                                        [init-req])]
  (chk "G: legacy (no candidate protocol) coordinator -> MCP REFUSES to start (exit != 0, zero replies)"
       (and (not (zero? exit)) (empty? by-id)))
  (chk "G: refusal names graph-edit-candidate-v2"
       (and (str/includes? (or err "") "REFUSING to start")
            (str/includes? (or err "") "graph-edit-candidate-v2"))))

;; ============================================================================
;; H. TRACKED-PATH PATHOLOGIES — each rejects BEFORE mutation; no artifacts.
;; ============================================================================
(def bad-env (assoc base-env "FRAM_CODE_PORT" (str bad-port) "FRAM_CODE_LOG" bad-log))
(let [bytes0 (vec (read-bytes bad-log))
      v0 (:version (coord bad-port bad-log {:op :version}))
      case! (fn [id mod marker label]
              (let [r (mcp-edit bad-env id "set-body" {:module (str "src.fram." mod)
                                                       :name "double-it" :body "(* 71 x)"})
                    t (or (rtext r) "")]
                (chk (str "H: " label " -> typed rejection (" marker ")")
                     (and (rerr? r) (str/includes? t marker)))))]
  (case! 70 "missmod" "no live" "MISSING file fact")
  (case! 71 "dupmod" "ambiguous" "DUPLICATE file facts")
  (case! 72 "relmod" "not ABSOLUTE" "RELATIVE tracked path")
  (case! 73 "outmod" "outside the source root" "absolute path OUTSIDE the root")
  (case! 74 "travmod" "not CANONICAL" "TRAVERSAL (..) path")
  (case! 75 "linkmod" "not CANONICAL" "SYMLINK-ESCAPE path")
  (chk "H: pathology log BYTE-IDENTICAL across every rejection"
       (= bytes0 (vec (read-bytes bad-log))))
  (chk "H: pathology daemon version UNCHANGED across every rejection"
       (= v0 (:version (coord bad-port bad-log {:op :version}))))
  (chk "H: no module-name artifacts created (root-level or outside)"
       (and (empty? (filter #(str/includes? (str %) "src.fram.") (.listFiles (io/file src-dir))))
            (not (.exists (io/file (str outside-dir "/outmod.bclj"))))
            (not (.exists (io/file (str outside-dir "/linkmod.bclj"))))
            (not (.exists (io/file (str tmp "/trav")))))))

;; ---------------------------------------------------------------------------
(p/destroy-tree main-daemon)
(p/destroy-tree bad-daemon)
(p/destroy-tree transaction-daemon)
(try (.close ^java.net.ServerSocket (:socket stub-server)) (catch Throwable _ nil))
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (if (empty? fails)
    (do (println (str "\nfram-mcp-candidate: " (count cs) " / " (count cs)
                      " PASS — graph-edit-candidate-v2 is an atomic, fail-closed, tracked-path candidate gate"))
        (p/shell {} "rm" "-rf" tmp))
    (do (println (str "\nfram-mcp-candidate: " (count fails) " FAILED  (workspace left at " tmp ")"))
        (System/exit 1))))
