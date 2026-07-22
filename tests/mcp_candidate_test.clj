;; mcp_candidate_test.clj — graph-edit-candidate-v1: the ATOMIC CANDIDATE GATE.
;; ============================================================================
;; Drives the REAL coordinator (:edit-prepare/:edit-commit/:edit-protocol) and the
;; REAL bin/fram-mcp over stdio against a hermetic NESTED corpus, and proves the
;; corrective contract of thread 019f8741-5b28 end to end:
;;
;;   A. NESTED TRACKED-PATH E2E — a genuinely nested module (src.fram.wkfix,
;;      tracked at <src>/src/fram/wkfix.bclj) edits through the five-tool MCP
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
;;      replacing the parent entry (symlink to an outside tree, or an impostor
;;      directory) between pin and publish writes NOTHING outside the checkout
;;      (bytes land in the pinned inode) and reports projection-stale.
;;   F. PROJECTION-STALE — a commit whose tracked-view write fails reports the
;;      stale projection loudly (log canonical, repair command included), and
;;      warm render-from-log repairs the file.
;;   G. PROTOCOL FENCE — a coordinator that cannot answer :edit-protocol with
;;      graph-edit-candidate-v1 (legacy/wrong protocol) is refused AT STARTUP.
;;   H. TRACKED-PATH PATHOLOGIES — missing, duplicate, relative, outside-root,
;;      traversal, and symlink-escape file facts each reject BEFORE mutation,
;;      and no module-name artifact is created anywhere.
;;
;;   bb -cp out tests/mcp_candidate_test.clj      (run from the repo root)
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
(def roundtrip (str beagle-home "/beagle-lib/private/facts-roundtrip.rkt"))
(def check-emit (str beagle-home "/beagle-lib/private/facts-check-emit.rkt"))
(doseq [[f label] [[roundtrip "facts-roundtrip.rkt"] [check-emit "facts-check-emit.rkt"]
                   [(str root "/out/fram/rt.clj") "out/ (build first)"]]]
  (when-not (.exists (io/file f))
    (println "SKIP — missing prerequisite:" label "(" f ")") (System/exit 0)))

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
(def bad-log (str src-dir "/.fram/bad.log"))
(def facts-log (str tmp "/facts.log"))
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
(def modules ["wkfix" "missmod" "dupmod" "relmod" "outmod" "travmod" "linkmod"])
(doseq [m modules]
  (spit (str nested-dir "/" m ".bclj") (str/replace fixture-body "%NAME%" m)))

;; HERMETIC SPAWNS — :env REPLACES the environment everywhere below. An ambient
;; live-runtime FRAM_TELEMETRY_LOG would otherwise leak into the daemons via
;; :extra-env and read-logs-merged would fold the FOREIGN telemetry log into the
;; corpus version (nondeterministic, inflates :version past the code log's max :tx).
(def fram-racket
  (or (System/getenv "FRAM_RACKET")
      (let [r (try (p/sh {:out :string :err :string} "direnv" "exec" beagle-home "which" "racket")
                   (catch Exception _ nil))]
        (when (and r (zero? (:exit r)) (not (str/blank? (:out r)))) (str/trim (:out r))))))
(def scrub-env
  (cond-> {"PATH" (System/getenv "PATH") "HOME" home "BEAGLE_HOME" beagle-home}
    fram-racket (assoc "FRAM_RACKET" fram-racket)))

(println "ingesting" (count modules) "nested fixture modules …")
(let [r (p/shell {:continue true :out :string :err :string :env scrub-env}
                 ;; ingest lists a directory NON-recursively — pass the nested dir,
                 ;; with --root at the checkout root so module names qualify
                 ;; (src/fram/wkfix.bclj -> src.fram.wkfix).
                 "bin/fram-ingest-code" nested-dir "--root" src-dir "--out" code-log)]
  (when-not (zero? (:exit r))
    (println "SKIP — ingest failed (beagle/racket unavailable?):" (str/trim (str (:err r))))
    (System/exit 0)))

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

;; --- throwaway coordinators ---------------------------------------------------
(defn port-free? [pt]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int pt)) 300) false)
       (catch Exception _ true)))
(defn pick-port [cands] (or (some #(when (port-free? %) %) cands)
                            (throw (ex-info "no free test port" {}))))
(def main-port (pick-port [39911 39913 39915 39917 39919]))
(def bad-port  (pick-port [39912 39914 39916 39918 39920]))
(def stub-port (pick-port [39921 39923 39925 39927 39929]))
(def replay-port (pick-port [39931 39933 39935 39937 39939]))
(def dead-port (pick-port [59981 59983 59985 59987 59989]))

(defn boot-daemon! [port log]
  (let [outf (str tmp "/daemon-" port ".log")
        proc (p/process {:out (io/file outf) :err (io/file outf)
                         :env (assoc scrub-env "FRAM_REQUIRE_LOG_FENCE" "1" "FRAM_EDIT_INJECT" "1")}
                        "clojure" "-M" "coord_daemon.clj" "serve-flat" (str port) log)]
    (loop [i 0]
      (cond
        (and (.exists (io/file outf)) (str/includes? (slurp outf) "listening on")) proc
        (> i 360) (do (p/destroy-tree proc)
                      (throw (ex-info (str "daemon on :" port " never came up") {:log outf})))
        :else (do (Thread/sleep 500) (recur (inc i)))))))

(println "booting throwaway coordinators (main:" main-port " pathology:" bad-port ") …")
(def main-daemon (boot-daemon! main-port code-log))
(def bad-daemon  (boot-daemon! bad-port bad-log))

(defn coord
  ([req] (coord main-port code-log req))
  ([port log req] (rt/coord-request-for-log port log req)))
(defn cur-version [] (:version (coord {:op :version})))

;; --- spawning the MCP hermetically --------------------------------------------
(def base-env
  (cond-> {"PATH" (System/getenv "PATH") "HOME" home
           "FRAM_LOG" facts-log "FRAM_THREADS" tmp "FRAM_PORT" (str dead-port)
           "FRAM_MCP_PROFILE" "graph-edit-v1"
           "FRAM_GRAPH_EDIT" "1" "FRAM_FLIP" "1"
           "FRAM_CODE_PORT" (str main-port)
           "FRAM_CODE_LOG" code-log
           "FRAM_SRC" src-dir
           "FRAM_OUT" (str root "/out") "FRAM_BIN" (str root "/bin")
           "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")
           "BEAGLE_HOME" beagle-home
           "FRAM_ROUNDTRIP" roundtrip "FRAM_CHECK_EMIT" check-emit
           "FRAM_BUILD_ALL" (str beagle-home "/bin/beagle-build-all")}
    (System/getenv "FRAM_RACKET") (assoc "FRAM_RACKET" (System/getenv "FRAM_RACKET"))))

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

;; ============================================================================
;; A. NESTED TRACKED-PATH E2E through the five-tool MCP surface.
;; ============================================================================
(let [log0 (count (read-bytes code-log))
      v0 (cur-version)
      r (mcp-edit base-env 10 "set-body" {:module "src.fram.wkfix" :name "double-it" :body "(* 21 x)"})
      t (or (rtext r) "")
      txt (slurp wkfix-file)]
  (chk "A: nested set-body through MCP -> isError=false" (and (some? r) (not (rerr? r))))
  (chk "A: reply reports the atomic candidate gate (graph-edit-candidate-v1) + committed"
       (and (str/includes? t "committed") (str/includes? t "graph-edit-candidate-v1")))
  (chk "A: EXACTLY the tracked nested file <src>/src/fram/wkfix.bclj was updated"
       (str/includes? txt "(* 21 x)"))
  (chk "A: the candidate text was compiled (type-checked) before commit — reply says so"
       (str/includes? t "TYPE-CHECKS CLEAN"))
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
                       (str/includes? t "graph-edit-candidate-v1"))))]
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
    (chk "E: recovery over torn-mid-batch log reports a REDO" (str/includes? (str (:err r) (:out r)) "redoing atomic batch"))
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
           (not (.exists (io/file (str elog6 ".edit-batch"))))))))

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
        "                    (catch Throwable t (:code (ex-data t))))]\n"
        "    (println (pr-str {:pub-fail pub-err :rm-fail rm-err}))))\n"))
      r (p/shell {:continue true :out :string :err :string :env scrub-env :dir root}
                 "bb" "-cp" "out" probe-file)
      lines (->> (str/split-lines (or (:out r) "")) (remove str/blank?) vec)
      m1 (try (edn/read-string (nth lines 0)) (catch Throwable _ nil))
      m2 (try (edn/read-string (nth lines 1 nil)) (catch Throwable _ nil))]
  (chk "J: direct probe ran (exit 0, two result maps)" (and (zero? (:exit r)) (map? m1) (map? m2)))
  (chk "J: journal PUBLICATION fsyncs the containing directory (>= 1 call, correct dir)"
       (and m1 (pos? (:pub-dir-fsyncs m1)) (some #(str/includes? % "/jdir") (:dirs-forced m1))))
  (chk "J: journal REMOVAL fsyncs the containing directory (>= 1 call)"
       (and m1 (pos? (:rm-dir-fsyncs m1))))
  (chk "J: published journal is v2 — bound to the canonical log + pre-state digest; temp gone; removal removes"
       (and m1 (:v2 m1) (:bound-log m1) (:pre-sha? m1) (:tmp-gone m1) (:removed m1)))
  (chk "J: forced directory-fsync failure propagates TYPED from publication (fail closed)"
       (and m2 (= :forced-dirsync (:pub-fail m2))))
  (chk "J: forced directory-fsync failure propagates TYPED from removal (fail closed)"
       (and m2 (= :forced-dirsync (:rm-fail m2)))))

;; ============================================================================
;; K. PARENT-IDENTITY PINNING (direct, deterministic) — replace the tracked
;;    parent entry between pin and publication: NOTHING is written outside the
;;    checkout (bytes land in the PINNED inode) and the publish reports stale.
;; ============================================================================
(let [probe-file (str tmp "/k_probe.clj")
      kdir (str tmp "/kfix")
      _ (spit probe-file (str
        "(load-file \"tests/fram_mcp.clj\")\n"
        "(let [base \"" kdir "\"\n"
        "      parent (str base \"/checkout/src/fram\")\n"
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
        "        ;; replacement 1: parent entry -> SYMLINK to an outside tree\n"
        "        _ (mv parent (str base \"/aside\"))\n"
        "        _ (lnk parent outside)\n"
        "        _ (spit src \"SECOND\")\n"
        "        r1 (publish-projection-pinned! pin (clojure.java.io/file src) \"leaf.bclj\")\n"
        "        aside-leaf (slurp (str base \"/aside/leaf.bclj\"))\n"
        "        outside-files (vec (.list (java.io.File. ^String outside)))\n"
        "        ;; replacement 2: parent entry -> a plain IMPOSTOR directory\n"
        "        _ (java.nio.file.Files/delete (.toPath (java.io.File. ^String parent)))\n"
        "        _ (mv impostor parent)\n"
        "        _ (spit src \"THIRD\")\n"
        "        r2 (publish-projection-pinned! pin (clojure.java.io/file src) \"leaf.bclj\")\n"
        "        impostor-files (vec (.list (java.io.File. ^String parent)))\n"
        "        aside-leaf2 (slurp (str base \"/aside/leaf.bclj\"))\n"
        "        residue (vec (filter #(clojure.string/starts-with? % \".fram-proj-\") (.list (java.io.File. ^String (str base \"/aside\")))))]\n"
        "    (release-pin! pin)\n"
        "    (println (pr-str {:pin-ok (not (:err pin))\n"
        "                      :clean-stale (boolean (:stale clean))\n"
        "                      :clean-leaf clean-leaf\n"
        "                      :symlink-stale (boolean (:stale r1))\n"
        "                      :outside-files outside-files\n"
        "                      :aside-leaf aside-leaf\n"
        "                      :impostor-stale (boolean (:stale r2))\n"
        "                      :impostor-files impostor-files\n"
        "                      :aside-leaf2 aside-leaf2\n"
        "                      :residue residue}))))\n"))
      r (p/shell {:continue true :out :string :err :string
                  :env (assoc scrub-env "FRAM_MCP_LIBRARY" "1") :dir root}
                 "bb" "-cp" "out" probe-file)
      m (try (edn/read-string (last (remove str/blank? (str/split-lines (or (:out r) ""))))) (catch Throwable _ nil))]
  (chk "K: direct pin probe ran (exit 0, result map)" (and (zero? (:exit r)) (map? m)))
  (chk "K: parent pinned after canonical validation; clean publish lands through the pin (not stale)"
       (and m (:pin-ok m) (not (:clean-stale m)) (= "FIRST" (:clean-leaf m))))
  (chk "K: SYMLINK-replaced parent entry -> publish reports projection-stale"
       (and m (:symlink-stale m)))
  (chk "K: NO file written outside the checkout (outside tree EMPTY; bytes landed in the pinned inode)"
       (and m (empty? (:outside-files m)) (= "SECOND" (:aside-leaf m))))
  (chk "K: IMPOSTOR-directory parent entry -> publish reports projection-stale, impostor EMPTY"
       (and m (:impostor-stale m) (empty? (:impostor-files m)) (= "THIRD" (:aside-leaf2 m))))
  (chk "K: no temp residue in the pinned directory" (and m (empty? (:residue m)))))

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
  (chk "G: refusal names graph-edit-candidate-v1"
       (and (str/includes? (or err "") "REFUSING to start")
            (str/includes? (or err "") "graph-edit-candidate-v1"))))

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
(try (.close ^java.net.ServerSocket (:socket stub-server)) (catch Throwable _ nil))
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (if (empty? fails)
    (do (println (str "\nfram-mcp-candidate: " (count cs) " / " (count cs)
                      " PASS — graph-edit-candidate-v1 is an atomic, fail-closed, tracked-path candidate gate"))
        (p/shell {} "rm" "-rf" tmp))
    (do (println (str "\nfram-mcp-candidate: " (count fails) " FAILED  (workspace left at " tmp ")"))
        (System/exit 1))))
