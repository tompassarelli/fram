;; fram_mcp.clj — the AI-facing edge of a Fram instance.
;; ============================================================================
;; Speaks MCP (JSON-RPC 2.0, newline-delimited, over stdio). The surface is CLOSED
;; and O(1): exactly the ten tools of the TELL/ASK knowledge-base core (Russell &
;; Norvig KB interface) — tell / retract / show / ask / validate + the five graph-edit
;; verbs — served straight from fram.tools/catalog, never minted per-predicate. The
;; old ~200-tool generated catalog was a per-session context tax buying no safety the
;; engine doesn't already give: EVERY write is serialized + rule-checked at the
;; coordinator, and single-vs-multi cardinality is DATA in the log (a `<pred>
;; cardinality single|multi` fact; the fold keys by it), so `tell` = assert subsumes
;; set-P AND add-P with identical semantics. Vocabulary is discoverable, not tooled:
;; a predicate is an entity, so `show <pred>` reveals its cardinality/value_kind facts
;; and `ask` enumerates it. `threads` and `dependents-of` are NOT here — threads are a
;; NORTH concept (north serves them) and a reverse edge is an `ask`.
;; Reads fold the current log; writes route through the coordinator.
;; cheshire keywordizes the JSON arguments into exactly the EDN shape fram.tools /
;; fram.query expect, so a model fills typed params (or, for `ask`, emits a
;; structured Datalog-shaped object) and can't author broken syntax.
;;
;;   bb -cp out fram_mcp.clj        (usually via bin/fram-mcp)
;; Diagnostics go to STDERR; stdout is the JSON-RPC channel only.
;; ============================================================================
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.process :as proc]
         '[fram.kernel :as k]
         '[fram.fold :as fold]
         '[fram.tools :as tl]
         '[fram.rt])

(defn- log! [& xs] (binding [*out* *err*] (apply println xs)))

;; --- the closed surface -------------------------------------------------------
;; One instructions string; the tool list is fram.tools/catalog verbatim (10 tools).
(def instructions
  (str
   "Fram is a FACT engine: every fact is a triple (subject predicate object); a "
   "thread is any @id with a `title`. Lifecycle is DERIVED from facts (committed / "
   "outcome / abandoned / driver / depends_on), never a stored status. Facts record "
   "what was ASSERTED, not what is verified — supersession, retraction, and views "
   "handle disagreement.\n\n"
   "TELL/ASK knowledge-base interface: `tell` asserts a fact (single-valued "
   "predicates replace their value; multi-valued ones accumulate — repeat tells), "
   "`retract` removes one (the verb `untell` is an accepted alias), `show` reads "
   "every fact on a subject, and `ask` answers "
   "multi-hop questions with a structured Datalog query (validated before it runs; "
   "recursion + stratified negation). Every write is serialized and rule-checked by "
   "the coordinator. `validate` reports integrity violations.\n\n"
   "Predicates are entities: `show <predicate>` reveals its cardinality/value_kind "
   "facts, and `ask` can enumerate the vocabulary — the tool surface stays closed "
   "(ten tools) while the vocabulary lives in the graph as data.\n\n"
   "Graph-owned Beagle modules (registered `graph-upstream`) are authored by GRAPH "
   "EDIT: add-def / set-body / rename-def / insert-after / replace-in-body "
   "(recompile-gated, fail-closed)."))

;; --- per-request state: fold the current log fresh (sees others' writes) -----
(defn load-state []
  (let [facts (:facts (fold/fold (fram.rt/read-configured-logs)))]
    {:facts facts :idx (k/build-index facts) :cat (tl/catalog facts)}))

;; --- catalog spec -> MCP tool descriptor -------------------------------------
(defn- input-schema [params]
  {:type "object"
   :properties (reduce (fn [m p] (assoc m (:name p) {:type (:type p) :description (str (:name p))})) {} params)
   :required (vec (keep (fn [p] (when (:required p) (:name p))) params))})

(defn- ->tool [spec]
  {:name (:name spec) :description (:desc spec) :inputSchema (input-schema (:params spec))})

;; --- writes -> through the coordinator (mirrors the CLI's route-write) -------
(defn- route-write [w]
  (let [port (fram.rt/coord-port)
        log (fram.rt/log-path)
        initial-version (fram.rt/coord-version-for-log port log)]
    (if (neg? initial-version)
      {:isError true
       :text (case initial-version
               -1 "no coordinator on 127.0.0.1 — start it with bin/fram-up"
               -2 "refusing write: coordinator serves a different log (run `fram doctor` for both paths)"
               -3 "refusing write: coordinator lacks the required log-fence protocol; restart it with current Fram"
               "refusing write: coordinator is unusable")}
      (loop [tries 5]
        (let [v (fram.rt/coord-version-for-log port log)]
          (if (neg? v)
            {:isError true
             :text (case v
                     -1 "no coordinator on 127.0.0.1 — start it with bin/fram-up"
                     -2 "refusing write: coordinator serves a different log (run `fram doctor` for both paths)"
                     -3 "refusing write: coordinator lacks the required log-fence protocol; restart it with current Fram"
                     "refusing write: coordinator is unusable")}
            (let [resp (if (= (:op w) "assert")
                         (fram.rt/coord-assert-for-log port log (:l w) (:p w) (:r w) v)
                         (fram.rt/coord-retract-for-log port log (:l w) (:p w) (:r w) v))]
              (cond
                (and (= resp "conflict") (pos? tries)) (recur (dec tries))
                (str/starts-with? (str resp) "ok:") {:text (str "committed: " (:l w) " " (:p w) " = " (:r w) " [" (:op w) "]")}
                :else {:isError true :text (str "rejected by coordinator: " resp)}))))))))

;; --- graph-AST edits -> the gated authoring transaction (out-of-band) --------
;; A {:edit ...} is NOT a single coordinator triple — it mints/supersedes a whole
;; subtree of kind/v/fN facts. The coordinator wire is single-(te,p,r) ONLY, so
;; this runs the SAME loop the code-as-facts gate proves (authoring-verbs.sh):
;;   project .bclj -> AST facts (facts-roundtrip --emit-edn)
;;   apply the verb as a FACT OP (chartroom resolve.clj <mode>) -> $RESOLVE_OUT EDN
;;   regenerate byte-stable text (--render)
;;   recompile-gate (beagle-build-all '0 error') over the regenerated tree
;; On PASS: overwrite the source .bclj (graph-upstream text is a downstream view).
;; On the engine REFUSING the edit (nonzero exit; resolve.clj fail-closes with
;; "REJECTED ... no facts mutated") OR the regen NOT recompiling: return
;; {:isError true :text <diagnostic>} and write NOTHING. Fail-closed throughout.
;;
;; Tool/binary locations are overridable for tests/CI; defaults match the live tree.
(defn- env-or [k d] (or (System/getenv k) d))
(def ^:private beagle-home   (env-or "BEAGLE_HOME"   (str (System/getProperty "user.home") "/code/beagle")))
(def ^:private roundtrip-rkt (env-or "FRAM_ROUNDTRIP" (str beagle-home "/beagle-lib/private/facts-roundtrip.rkt")))
(def ^:private build-all     (env-or "FRAM_BUILD_ALL" (str beagle-home "/bin/beagle-build-all")))
;; INCREMENTAL DE-HANDICAP: per-edit gate checks+emits ONLY the edited module from
;; its facts (facts->AST->type-check->emit clj), instead of rendering + building
;; the whole tree. beagle's checker is per-file (declare-extern resolves cross-module
;; refs), so unchanged modules need no work. Kills the .bclj round-trip handicap.
(def ^:private check-emit-rkt (env-or "FRAM_CHECK_EMIT" (str beagle-home "/beagle-lib/private/facts-check-emit.rkt")))
(def ^:private resolve-clj   (env-or "FRAM_RESOLVE"   (str (System/getProperty "user.dir") "/chartroom/src/resolve.clj")))
(def ^:private fram-out      (env-or "FRAM_OUT"       (str (System/getProperty "user.dir") "/out")))
;; the source tree graph-upstream modules live in (the .bclj scope is resolved here).
(def ^:private fram-src      (env-or "FRAM_SRC"       (str (System/getProperty "user.dir") "/src/fram")))

(defn- bclj-files [dir]
  (->> (.listFiles (io/file dir))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % ".bclj"))
       sort vec))

;; shell helper (used by the flip helpers below + route-edit).
(defn- sh [opts & args] (apply proc/sh opts args))

;; ============================================================================
;; THE FLIP — source-of-truth demotion for CODE (staged; OFF by default).
;; ============================================================================
;; When FRAM_FLIP=1 AND a CODE coordinator is reachable (FRAM_CODE_PORT, serving
;; .fram/code.log), the PASS arm below does NOT io/copy-overwrite the .bclj. Instead
;; it COMMITS the AST fact delta of the edited module THROUGH the coordinator
;; (bin/fram-commit-code: single-(te,p,r) :assert/:retract at base version), THEN
;; renders the .bclj FROM the updated log (bin/fram-render-code). The log is the
;; source; the .bclj is downstream. With FRAM_FLIP unset, the legacy io/copy path is
;; used verbatim (conservative default — the flip is proven on schema.bclj only).
;; (See experiments/flip/DESIGN.md §4 + coord_code_flip_test.clj.)
(def ^:private flip-on?      (= "1" (System/getenv "FRAM_FLIP")))
(def ^:private flip-bin-dir  (env-or "FRAM_BIN" (str (System/getProperty "user.dir") "/bin")))
(def ^:private flip-code-port (System/getenv "FRAM_CODE_PORT"))
;; the code log render-from-log reads — MUST be the same log the code coordinator on
;; FRAM_CODE_PORT serves (else render would read a stale/other log). Default matches
;; bin/fram-render-code's default ($PWD/.fram/code.log); set explicitly under a
;; hermetic corpus (the test) so render reads the corpus's log, not the repo's.
(def ^:private flip-code-log (System/getenv "FRAM_CODE_LOG"))

;; module NAME from a target .bclj path (basename minus .bclj) — the @<mod># prefix.
(defn- flip-module-of [path] (-> path io/file .getName (str/replace #"\.bclj$" "")))

;; the code log the flip path reads/edits: FRAM_CODE_LOG if set, else $PWD/.fram/code.log
;; (matches the bin defaults). The coordinator on flip-code-port MUST serve this log.
(defn- flip-log [] (or flip-code-log (str (System/getProperty "user.dir") "/.fram/code.log")))
(defn- flip-log-args [] (if flip-code-log ["--log" flip-code-log] []))

;; ============================================================================
;; THE FLIP — GRAPH-SOURCED edit path. NO src/fram/*.bclj is read.
;; ============================================================================
;; The inversion (replaces the emit-edn(text) front of the old flip arm):
;;   1. enumerate modules FROM THE LOG (@<mod>#root facts) — not by globbing src.
;;   2. apply the verb over the LOG-booted warm store (bin/fram-edit-code
;;      --no-commit): mint/supersede fact ops against log-resident identity, then
;;      render the AFFECTED module from the edited store (atomic write). NO text read.
;;   3. recompile-gate: render EVERY OTHER module from the log into the same tree
;;      (so cross-module refs resolve), drop in the edited module, beagle-build-all,
;;      require '0 error'.
;;   4. on PASS: commit the affected module's AST delta THROUGH the coordinator
;;      (bin/fram-commit-code over the verb's render(log) .bclj — render(log), NOT a
;;      src file), then render the now-canonical .bclj view next to the source.
;; Fail-closed at every step; nothing is committed unless the whole corpus builds.
;;
;; verb-flags: the op-specific flags fram-edit-code needs (built by the caller from
;; the {:edit ...} payload). target-bclj: where to write the downstream .bclj view.
(defn- flip-graph-edit! [op module verb-flags target-bclj]
  (let [work (str (System/getProperty "java.io.tmpdir") "/fram-flip-" (System/nanoTime))
        tree (str work "/src") odir (str work "/out")
        _ (run! #(.mkdirs (io/file %)) [tree odir])
        ;; modules straight from the LOG (zero src/fram dependency).
        mres (apply sh {:out :string :err :string}
                    "bb" "-cp" fram-out (str flip-bin-dir "/fram-modules-of-log") (flip-log-args))]
    (if (not (zero? (:exit mres)))
      (do (sh {} "rm" "-rf" work)
          {:isError true :text (str "FLIP — could not enumerate modules from the log:\n" (str/trim (:err mres)))})
      (let [modules (->> (str/split-lines (:out mres)) (map str/trim) (remove str/blank?) vec)]
        (if-not (some #{module} modules)
          (do (sh {} "rm" "-rf" work)
              {:isError true :text (str "FLIP — module \"" module "\" is not in the code log "
                                        "(modules: " (str/join ", " modules) ")")})
          ;; 2. apply the verb over the warm store -> render the AFFECTED module (no commit yet).
          (let [edited (str work "/edited-" module ".bclj")
                edit (apply sh {:out :string :err :string}
                            "bb" "-cp" fram-out (str flip-bin-dir "/fram-edit-code")
                            op module (concat verb-flags ["--no-commit" "--out" edited] (flip-log-args)))]
            (if (not (zero? (:exit edit)))
              (do (sh {} "rm" "-rf" work)
                  {:isError true :text (str "REJECTED by the authoring engine — nothing mutated:\n"
                                            (str/trim (str (:out edit) (:err edit))))})
              ;; 3. INCREMENTAL: emit-edn the EDITED module's facts only — skip rendering every
              ;;    OTHER module (unchanged; per-file check resolves cross-refs via declare-extern).
              (let [ednf (str work "/edited.edn")
                    render-fail
                    (let [ee (sh {:out (io/file ednf) :err :string} "racket" roundtrip-rkt "--emit-edn" edited)]
                      (when (not (zero? (:exit ee))) (str "emit-edn of edited module failed: " (str/trim (:err ee)))))]
                (if render-fail
                  (do (sh {} "rm" "-rf" work) {:isError true :text (str "FLIP — " render-fail)})
                  ;; 4. INCREMENTAL GATE: facts-check-emit the edited module (facts->AST->type-check
                  ;;    ->clj), fail-closed on exit. No whole-tree build-all (the .bclj round-trip handicap).
                  (let [bg (sh {:out :string :err :string} "racket" check-emit-rkt ednf)
                        built (str (:out bg) (:err bg))]
                    (if-not (zero? (:exit bg))
                      (do (sh {} "rm" "-rf" work)
                          {:isError true :text (str "REJECTED — edited module does not type-check (nothing committed):\n"
                                                    (str/trim built))})
                      ;; PASS — commit the affected module's AST delta through the coordinator.
                      (let [commit (apply sh {:out :string :err :string}
                                          "bb" "-cp" fram-out (str flip-bin-dir "/fram-commit-code")
                                          module edited "--port" (str flip-code-port)
                                          (flip-log-args))]
                        (if (not (zero? (:exit commit)))
                          (do (sh {} "rm" "-rf" work)
                              {:isError true :text (str "FLIP REJECTED — delta-commit to the code log failed (nothing written):\n"
                                                        (str/trim (str (:out commit) (:err commit))))})
                          ;; render the now-canonical .bclj view next to the source (downstream).
                          (let [render (apply sh {:out (io/file target-bclj) :err :string}
                                              "bb" "-cp" fram-out (str flip-bin-dir "/fram-render-code")
                                              module (flip-log-args))]
                            (sh {} "rm" "-rf" work)
                            (if (not (zero? (:exit render)))
                              {:isError true :text (str "FLIP — delta committed but render-from-log view FAILED:\n" (str/trim (:err render)))}
                              {:text (str "committed (FLIP, graph-sourced): " op " on " module
                                          " — verb ran over the LOG-booted store, recompiled from the log, "
                                          "AST delta committed THROUGH the coordinator; .bclj is downstream "
                                          "(NO src/fram/*.bclj read on the edit path)")})))))))))))))))

;; build the fram-edit-code op + flags from the {:edit ...} payload. set-body /
;; upsert-form pass their datum via a temp file (the verb slurps + read-string's it).
(defn- flip-verb-flags [e work]
  (case (:op e)
    "rename"      ["rename" ["--old" (:name e) "--new" (:new-name e)]]
    "set-body"    (let [bf (str work "/body.edn")] (spit bf (:body e))
                    ["set-body" ["--name" (:name e) "--body-file" bf]])
    "upsert-form" (let [sf (str work "/spec.edn")] (spit sf (:form e))
                    ["upsert-form" ["--spec-file" sf]])
    ;; CRDT insert-anywhere (commute): the form datum goes to a temp file (the verb
    ;; slurps + read-string's it, same as upsert-form), the anchor as --after.
    "insert-form" (let [sf (str work "/spec.edn")] (spit sf (:form e))
                    ["insert-form" ["--after" (:after e) "--spec-file" sf]])
    "replace-in-body" (let [of (str work "/old.edn") nf (str work "/new.edn")]
                        (spit of (:old e)) (spit nf (:new e))
                        ["replace-in-body" ["--name" (:name e) "--old-file" of "--new-file" nf]])
    nil))

;; ============================================================================
;; WARM edit path — graph-edit-candidate-v1 against the live coordinator (the
;; persistent MCP server -> persistent coordinator). The fold stays amortized to
;; the coordinator's BOOT (no per-edit log re-fold); the edit itself is the
;; ATOMIC CANDIDATE GATE: :edit-prepare (clone + seal, zero writes) -> render +
;; Beagle parse/type check the candidate HERE -> :edit-commit (exact-version CAS,
;; journaled whole-batch install, one root swap) -> write the checked bytes to
;; the module's TRACKED path. Replaces the commit-first :edit-min warm flow.
;; ============================================================================
;; build the warm edit spec (inline datum, no temp file) from the {:edit} payload.
;; The body/form STRING is parsed to a DATUM here (clojure.edn/read-string) — exactly
;; what the CLI (bin/fram-edit-code) and the legacy text path (resolve.clj slurp +
;; read-string of the spec/body file) both do. The old warm path passed the RAW
;; string through, so the verb minted a STRING-LITERAL body instead of the form —
;; masked by String-returning test defns. An unreadable payload throws; route-edit
;; maps that to a typed rejection BEFORE any coordinator contact.
(defn- edit-min-spec [e]
  (let [datum! (fn [s label]
                 (try (clojure.edn/read-string s)
                      (catch Exception ex
                        (throw (ex-info (str label " is not readable EDN: " (.getMessage ex))
                                        {:spec-error true})))))]
    (case (:op e)
      "rename"      {:op "rename"      :module (:module e) :old (:name e) :new (:new-name e)}
      "set-body"    {:op "set-body"    :module (:module e) :name (:name e) :datum (datum! (:body e) "body")}
      "upsert-form" {:op "upsert-form" :module (:module e) :datum (datum! (:form e) "form")}
      "insert-form" {:op "insert-form" :module (:module e) :after (:after e) :datum (datum! (:form e) "form")}
      ;; SUB-DEF surgical edit — old/new are EDN-datum STRINGS from the MCP arg; parse them
      ;; to datums here (the verb canonicalizes/mints datums, exactly as the CLI does via
      ;; edn/read-string of the spec/body file), so the warm socket path is byte-correct.
      "replace-in-body" (cond-> {:op "replace-in-body" :module (:module e) :name (:name e)
                                 :old (datum! (:old e) "old") :new (datum! (:new e) "new")}
                          ;; optional :within scope-narrower (enclosing-form datum STRING) — the
                          ;; disambiguation remedy; parse it here exactly like :old/:new.
                          (:within e) (assoc :within (datum! (:within e) "within")))
      nil)))

;; the corpus the verb operates over = every .bclj in the source tree (so cross-module
;; references resolve), with the per-file projected EDN written next to it in a temp dir.

(declare route-edit-text)        ; the legacy text path, defined below (forward ref for bb/SCI)

;; canonical-path helper: resolves ., .., and symlinks; defined for a
;; not-yet-existing leaf (a render target may not exist yet).
(defn- canon [p] (.getCanonicalPath (io/file p)))

;; graph-edit-candidate-v1 — validate the module's TRACKED source path (the
;; coordinator resolved it from the sealed graph file fact @<mod>#root `file`)
;; against the canonical FRAM_SRC checkout root. Refuses non-absolute,
;; non-canonical (traversal `..`/`.` segments OR any symlink component — the
;; canonical form differs from the stored form in every such case), and
;; outside-root paths. nil = confined; else {:err <why>}.
(defn- validate-tracked-path [p]
  (let [root (canon fram-src)]
    (cond
      (not (string? p))
      {:err "coordinator returned no tracked source path for the module"}
      (not (.isAbsolute (io/file p)))
      {:err (str "tracked source path " (pr-str p) " is not ABSOLUTE — refused before mutation")}
      (not= p (canon p))
      {:err (str "tracked source path " (pr-str p) " is not CANONICAL (resolves to "
                 (pr-str (canon p)) ") — traversal/symlink segments are refused before mutation")}
      (not (str/starts-with? p (str root "/")))
      {:err (str "tracked source path " p " lies outside the source root " root " — refused before mutation")}
      :else nil)))

;; ---- pinned projection publication (parent-directory identity confinement) --
;; validate-tracked-path above is PATHNAME-based and one-time; between it and
;; the projection write the parent directory ENTRY could be replaced (e.g.
;; swapped for a symlink into an outside tree), redirecting a path-based write
;; OUTSIDE the checkout. bb's GraalVM image does not register Java's
;; SecureDirectoryStream methods for reflection (verified: getMethod -> NoSuchMethod),
;; so this uses the equivalently PINNED relative move: hold an open FileChannel
;; on the validated parent directory (an fd pins the INODE, not the name) and
;; address every publication op through /proc/self/fd/<N>/<leaf>, which the
;; kernel resolves via that pinned inode exactly like openat/renameat relative
;; ops. Identity is compared by fileKey (dev,ino) through the fd after pinning;
;; the directory fsync is .force on the pinned channel itself (never on a
;; re-resolved path). No /proc, an ambiguous fd attribution, or an identity
;; mismatch FAILS CLOSED before any commit — never a silent fallback to
;; path-based writes.
(defn- path-of [s] (java.nio.file.Paths/get (str s) (into-array String [])))
(defn- file-key-of
  ;; fileKey (dev,ino) of what `p` RESOLVES to, as a comparable string; nil if unreadable.
  [p]
  (try (str (.fileKey (java.nio.file.Files/readAttributes
                       (path-of p) java.nio.file.attribute.BasicFileAttributes
                       (into-array java.nio.file.LinkOption []))))
       (catch Throwable _ nil)))
(defn- dir-fds-matching
  ;; the /proc/self/fd entries currently resolving to directory identity `k`.
  [k]
  (set (for [f (or (seq (.listFiles (io/file "/proc/self/fd"))) [])
             :let [n (.getName ^java.io.File f)]
             :when (= k (file-key-of (str "/proc/self/fd/" n)))]
         n)))

(defn- pin-parent-dir!
  ;; -> {:ch <FileChannel> :fd-dir "/proc/self/fd/N" :key K :parent P} | {:err ..}
  ;; Pin protocol: capture the expected identity K from the validated path, scan
  ;; the fds already on K, open the directory channel, scan again — the single
  ;; NEW matching fd is ours (the channel keeps it alive, so the number cannot
  ;; be reused while pinned). Anything other than exactly one candidate refuses.
  [parent]
  (if-not (.isDirectory (io/file "/proc/self/fd"))
    {:err "pinned publication unsupported: /proc/self/fd unavailable on this platform"}
    (let [k (file-key-of parent)]
      (if (nil? k)
        {:err (str "cannot read the identity of tracked parent directory " parent)}
        (let [before (dir-fds-matching k)
              ch (try (java.nio.channels.FileChannel/open
                       (path-of parent)
                       (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/READ]))
                      (catch Throwable t t))]
          (if (instance? Throwable ch)
            {:err (str "cannot pin tracked parent directory " parent ": " (.getMessage ^Throwable ch))}
            (let [cands (vec (remove before (dir-fds-matching k)))]
              (if (= 1 (count cands))
                {:ch ch :fd-dir (str "/proc/self/fd/" (first cands)) :key k :parent parent}
                (do (try (.close ^java.nio.channels.FileChannel ch) (catch Throwable _ nil))
                    {:err (str "cannot attribute a pinned parent-directory fd for " parent
                               " (" (count cands) " candidates) — the directory identity changed during"
                               " pinning or fd attribution is ambiguous; refusing (no path-based fallback)")})))))))))

(defn- write-byte-buffer-all!
  ;; FileChannel.write is allowed to consume only part of a ByteBuffer. Drive it
  ;; until drained; zero progress yields and retries, while EOF-like negative
  ;; progress is a hard I/O failure. `write-one!` is injected so the exact
  ;; short-write contract has a deterministic direct probe.
  [write-one! ^java.nio.ByteBuffer buf]
  (loop [calls 0]
    (if-not (.hasRemaining buf)
      calls
      (let [n (long (write-one! buf))]
        (cond
          (neg? n) (throw (ex-info "projection FileChannel reported negative write progress"
                                   {:code :projection-short-write}))
          (zero? n) (do (Thread/yield) (recur (inc calls)))
          :else (recur (inc calls)))))))

(defn- publish-projection-pinned!
  ;; Publish `src-file`'s bytes as `leaf` THROUGH the pinned parent directory:
  ;; FIRST compare the current parent pathname to the pin, before creating a temp
  ;; or writing one byte. A parent moved anywhere (including outside FRAM_SRC)
  ;; and replaced therefore returns :stale with zero writes to both the moved
  ;; original and replacement. Recheck before the atomic publication, drain the
  ;; complete ByteBuffer, then fsync the PINNED directory channel. Returns nil on
  ;; a clean publish; {:stale <why>} when current pathname identity differs;
  ;; throws on I/O failure.
  [{:keys [ch fd-dir key parent]} src-file leaf]
  (cond
    (not= key (file-key-of fd-dir))
    (throw (ex-info (str "pinned parent-directory fd no longer matches its validated identity " key)
                    {:code :pin-identity-lost}))

    ;; LOAD-BEARING PRE-WRITE CHECK: do not create the same-directory temp and
    ;; do not consume src-file unless the checkout pathname still names the pin.
    (not= key (file-key-of parent))
    {:stale (str "the tracked parent directory " parent
                 " no longer resolves to its validated identity " key
                 " — projection NOT published; zero bytes written")}

    :else
    (let [tmp (str ".fram-proj-" (System/nanoTime) ".tmp")
          tmp-path (path-of (str fd-dir "/" tmp))]
      (try
        (with-open [wch (java.nio.channels.FileChannel/open
                         tmp-path
                         (into-array java.nio.file.OpenOption
                                     [java.nio.file.StandardOpenOption/CREATE_NEW
                                      java.nio.file.StandardOpenOption/WRITE]))]
          (let [buf (java.nio.ByteBuffer/wrap
                     (java.nio.file.Files/readAllBytes (.toPath (io/file src-file))))]
            (write-byte-buffer-all! #(.write wch ^java.nio.ByteBuffer %) buf))
          (.force wch true))
        ;; If identity moved while the temp was being prepared, remove the temp
        ;; and refuse to replace the leaf. The deterministic move-before-call
        ;; boundary above performs no create/write at all.
        (if (not= key (file-key-of parent))
          (do
            (java.nio.file.Files/deleteIfExists tmp-path)
            (.force ^java.nio.channels.FileChannel ch true)
            {:stale (str "the tracked parent directory " parent
                         " changed during projection preparation — leaf NOT published")})
          (do
            (java.nio.file.Files/move
             tmp-path (path-of (str fd-dir "/" leaf))
             (into-array java.nio.file.CopyOption
                         [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                          java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
            (.force ^java.nio.channels.FileChannel ch true)
            (when (not= key (file-key-of parent))
              {:stale (str "the tracked parent directory " parent
                           " changed during atomic publication; canonical graph stands and"
                           " the projection requires repair")})))
        (catch Throwable t
          (try (java.nio.file.Files/deleteIfExists tmp-path) (catch Throwable _ nil))
          (throw t))))))

(defn- release-pin! [pin]
  (when-let [ch (:ch pin)]
    (try (.close ^java.nio.channels.FileChannel ch) (catch Throwable _ nil))))

(defn- commit-candidate-exactly [port req]
  ;; A socket can disappear after the coordinator's append+fsync commit point but
  ;; before this process receives the receipt. Retry the IDENTICAL request once:
  ;; graph-edit-candidate-v1 deduplicates it from the durable batch identity, even
  ;; after coordinator restart. A second transport failure is explicitly UNKNOWN,
  ;; never mislabeled "nothing committed" and never automatically replayed again.
  (try
    (fram.rt/coord-request-for-log port (flip-log) req)
    (catch Throwable first-error
      (try
        (fram.rt/coord-request-for-log port (flip-log) req)
        (catch Throwable retry-error
          {:reject [(str "commit response is unknown after one exact-request retry: "
                         (.getMessage ^Throwable first-error) "; retry: "
                         (.getMessage ^Throwable retry-error))]
           :code :commit-response-unknown
           :candidate (:candidate req)
           :base-version (:version req)})))))

(defn- coordinator-commit-warning [commit]
  (when (or (:repair-needed commit) (seq (:warnings commit)))
    (str " (WARNING: coordinator reports "
         (let [code (or (:code commit) :committed-with-warning)]
           (if (keyword? code) (name code) (str code)))
         " for candidate " (:candidate commit) " at exact version " (:version commit)
         (when-let [ws (seq (:warnings commit))]
           (str ": " (str/join "; " (map (fn [w]
                                             (str (if (keyword? (:stage w))
                                                    (name (:stage w))
                                                    (or (:stage w) "unknown-stage"))
                                                  " — " (or (:message w) "unspecified warning")))
                                           ws))))
         ". The graph batch is COMMITTED; DO NOT RETRY."
         (when (:repair-needed commit) " Restart the coordinator to rebuild derived state.")
         ")")))

(defn- committed-postprocess-failure [commit op module t]
  {:text (str "COMMITTED WITH WARNING (graph-edit-candidate-v1, atomic batch): "
              op " on " module " — candidate " (:candidate commit) ", "
              (:installed commit) " ops at exact version " (:version commit)
              ". Post-commit projection/response handling failed: "
              (or (.getMessage ^Throwable t) (.getSimpleName (class t)))
              ". The graph batch is COMMITTED; DO NOT RETRY. Repair the tracked view with: "
              "bin/fram-render-code " module " --port " flip-code-port " "
              (str/join " " (flip-log-args)) " --out " (:path commit)
              (coordinator-commit-warning commit))})

(defn route-edit [e]
  (let [op (:op e) module (:module e)]
    (cond
      ;; FLIP path (FRAM_FLIP=1 + a code coordinator): the LOG is canonical, and the
      ;; edit goes through the ATOMIC CANDIDATE GATE (graph-edit-candidate-v1):
      ;;   1. :edit-prepare — the coordinator runs the verb over an exact-version
      ;;      clone (ZERO canonical writes), seals the op set, resolves the module's
      ;;      TRACKED path from the graph file fact, and returns the candidate EDN
      ;;      + digests. (The legacy warm flow committed FIRST and checked after —
      ;;      a failing check left broken canonical state — and rendered to
      ;;      FRAM_SRC/<module>.bclj, minting root-level artifacts for nested
      ;;      modules like src.fram.world.)
      ;;   2. the tracked path is confined under the canonical FRAM_SRC root here.
      ;;   3. the candidate text is rendered and Beagle PARSE + TYPE checked — all
      ;;      BEFORE any commit; a failing candidate rejects with canonical log,
      ;;      facts, version, and tracked projection untouched.
      ;;   4. :edit-commit — the coordinator revalidates log/version/path/digests
      ;;      (exact-version CAS), publishes a durable identity-bound recovery
      ;;      intent, awaits the whole-batch append+fsync (THE commit point)
      ;;      inside its serialized section, and only then swaps the root; an
      ;;      append failure restores the exact pre-state and rejects typed.
      ;;   5. the CHECKED candidate bytes are published to the tracked path
      ;;      THROUGH the parent directory pinned at validation time (identity-
      ;;      compared fd; temp + force + atomic replace + pinned-dir fsync) —
      ;;      checked-is-installed, no re-render race, and a replaced parent
      ;;      entry can never redirect the write outside the checkout (the
      ;;      reply reports PROJECTION-STALE instead).
      ;; NO fallback to the commit-first :edit-min flow: a coordinator that cannot
      ;; prepare/commit candidates gets a typed refusal, never a degraded write.
      (and flip-on? flip-code-port)
      (let [spec (try (edit-min-spec e)
                      (catch clojure.lang.ExceptionInfo ex
                        (if (:spec-error (ex-data ex)) {:spec-error (.getMessage ex)} (throw ex))))]
        (cond
          (nil? spec)
          {:isError true :text (str "unknown edit op: " op)}
          (:spec-error spec)
          {:isError true
           :text (str "REJECTED (nothing prepared, nothing committed): " (:spec-error spec)
                      " — the edit payload must be a readable EDN form")}
          :else
          (let [port (Integer/parseInt flip-code-port)
                prep (try (fram.rt/coord-request-for-log port (flip-log) {:op :edit-prepare :spec spec})
                          (catch Throwable t {:reject [(str "edit-prepare socket: " (.getMessage t))]}))]
            (cond
              (= "unknown op" (:error prep))
              {:isError true
               :text (str "REJECTED (nothing committed): the coordinator does not speak "
                          "graph-edit-candidate-v1 (legacy :edit-min-only daemon) — restart it "
                          "with current Fram (bin/fram-code-on)")}
              (:reject prep)
              {:isError true
               :text (str "REJECTED (graph-edit-candidate-v1 prepare — nothing committed"
                          (when-let [c (:code prep)] (str ", " (name c))) "): "
                          (str/join "; " (map str (:reject prep)))
                          ;; surface the structured disambiguation remedy (replace-in-body
                          ;; candidates + copy-pastable :within forms) so the model gets HOW
                          ;; to disambiguate, not just that it was ambiguous.
                          (when-let [d (:disambiguation prep)] (str "\n" (:message d))))}
              (not (:ok prep))
              {:isError true :text (str "edit-prepare unexpected response: " (pr-str prep))}
              :else
              (let [target (:path prep)]
                (if-let [pe (validate-tracked-path target)]
                  {:isError true :text (str "REJECTED (nothing committed): " (:err pe))}
                  ;; pin the validated parent directory BEFORE any commit: the
                  ;; projection publication below goes only through this pinned
                  ;; identity, and an environment where pinning is unsupported
                  ;; rejects here with nothing committed (fail closed) instead
                  ;; of always committing into a stale/unconfined projection.
                  (let [pin (pin-parent-dir! (.getParent (io/file target)))]
                    (if (:err pin)
                      {:isError true
                       :text (str "REJECTED (nothing committed): projection parent-directory pin failed: "
                                  (:err pin))}
                      (try
                  (let [work (str (System/getProperty "java.io.tmpdir") "/fram-cand-" (System/nanoTime))
                        _ (.mkdirs (io/file work))
                        ext (let [n (.getName (io/file target)) i (.lastIndexOf n ".")]
                              (if (pos? i) (subs n i) ".bclj"))
                        ednf  (str work "/candidate" ext ".edn")
                        candf (str work "/candidate" ext)
                        _ (spit ednf (:edn prep))
                        rr (sh {:out (io/file candf) :err :string} "racket" roundtrip-rkt "--render" ednf)]
                    (if-not (zero? (:exit rr))
                      (do (sh {} "rm" "-rf" work)
                          {:isError true
                           :text (str "REJECTED — candidate render failed (nothing committed):\n"
                                      (str/trim (:err rr)))})
                      ;; sealed Beagle checks — parse, then type — on the candidate, BEFORE commit.
                      (let [chk-edn (str candf ".chk.edn")
                            pe2 (sh {:out (io/file chk-edn) :err :string} "racket" roundtrip-rkt "--emit-edn" candf)]
                        (if-not (zero? (:exit pe2))
                          (do (sh {} "rm" "-rf" work)
                              {:isError true
                               :text (str "REJECTED — candidate module `" module "` does not PARSE "
                                          "(nothing committed). Re-issue the edit with valid Beagle "
                                          "(typed Clojure: `(defn f [x :- T] :- R body)`). Syntax error:\n"
                                          (str/trim (:err pe2)))})
                          (let [bg (sh {:out :string :err :string} "racket" check-emit-rkt chk-edn)]
                            (if-not (zero? (:exit bg))
                              (do (sh {} "rm" "-rf" work)
                                  {:isError true
                                   :text (str "REJECTED — candidate module `" module "` fails the sealed Beagle "
                                              "parse/type check (nothing committed). Re-issue the edit. Beagle error:\n"
                                              (str/trim (str (:out bg) (:err bg))))})
                              ;; checks green — commit the sealed candidate at its exact version.
                              (let [commit-req {:op :edit-commit
                                                :candidate (:candidate prep)
                                                :version (:version prep)
                                                :module module
                                                :path target
                                                :ops-digest (:ops-digest prep)
                                                :edn-digest (:edn-digest prep)
                                                :src-root (canon fram-src)}
                                    commit (commit-candidate-exactly port commit-req)]
                                (cond
                                  (:reject commit)
                                  (do (sh {} "rm" "-rf" work)
                                      (if (#{:durability-indeterminate :durability-poisoned
                                             :committed-repair-needed :commit-response-unknown}
                                           (:code commit))
                                        {:isError true
                                         :text (str "STOPPED (graph-edit-candidate-v1, " (name (:code commit))
                                                    "): " (str/join "; " (map str (:reject commit)))
                                                    " — outcome is NOT an ordinary rejection and may already be"
                                                    " committed; DO NOT RETRY. Stop/restart the coordinator and"
                                                    " inspect :edit-protocol/:status for the exact receipt")}
                                        {:isError true
                                         :text (str "REJECTED (graph-edit-candidate-v1 commit — nothing committed"
                                                    (when-let [c (:code commit)] (str ", " (name c))) "): "
                                                    (str/join "; " (map str (:reject commit)))
                                                    (when (= "stale-version" (some-> (:code commit) name))
                                                      " — a concurrent edit landed first; re-issue this edit"))}))
                                  (:ok commit)
                                  ;; the tracked projection: publish the CHECKED candidate bytes
                                  ;; THROUGH the parent directory pinned before the commit
                                  ;; (identity-verified fd; temp + force + atomic replace +
                                  ;; pinned-dir fsync). A parent entry already replaced when
                                  ;; publication starts is rejected before temp creation or source
                                  ;; read. A replacement racing the prepared temp can never redirect
                                  ;; bytes into the replacement: publication stays pinned and the
                                  ;; reply reports PROJECTION-STALE. Any failure leaves the LOG
                                  ;; canonical and the projection STALE — reported loudly with the
                                  ;; repair command.
                                  (try
                                    (let [proj (try (publish-projection-pinned!
                                                     pin (io/file candf) (.getName (io/file target)))
                                                    (catch Throwable t {:proj-err (str (.getMessage t))}))]
                                      (sh {} "rm" "-rf" work)
                                      (cond
                                        (:proj-err proj)
                                        {:text (str "committed (graph-edit-candidate-v1, atomic batch): " op " on " module
                                                    " — candidate " (:candidate commit) ", "
                                                    (:installed commit) " ops at exact version " (:version commit)
                                                    " (WARNING: tracked projection is STALE — writing " target
                                                    " failed: " (:proj-err proj) "; graph batch is COMMITTED; DO NOT RETRY;"
                                                    " repair with: bin/fram-render-code "
                                                    module " --port " flip-code-port " "
                                                    (str/join " " (flip-log-args)) " --out " target ")"
                                                    (coordinator-commit-warning commit))}
                                        (:stale proj)
                                        {:text (str "committed (graph-edit-candidate-v1, atomic batch): " op " on " module
                                                    " — candidate " (:candidate commit) ", "
                                                    (:installed commit) " ops at exact version " (:version commit)
                                                    " (WARNING: tracked projection is PROJECTION-STALE — "
                                                    (:stale proj) "; graph batch is COMMITTED; DO NOT RETRY; repair with: "
                                                    "bin/fram-render-code " module " --port " flip-code-port " "
                                                    (str/join " " (flip-log-args)) " --out " target ")"
                                                    (coordinator-commit-warning commit))}
                                        :else
                                        {:text (str "committed + TYPE-CHECKS CLEAN (graph-edit-candidate-v1, atomic batch): "
                                                    op " on " module " — candidate " (:candidate commit) ", "
                                                    (:installed commit) " ops at exact version "
                                                    (:version commit) "; tracked view " target " updated"
                                                    (coordinator-commit-warning commit))}))
                                    (catch Throwable t
                                      ;; Any failure after an :ok/:committed receipt is
                                      ;; presentation/projection repair, never edit failure.
                                      (try (sh {} "rm" "-rf" work) (catch Throwable _ nil))
                                      (committed-postprocess-failure commit op module t)))
                                  :else
                                  (do (sh {} "rm" "-rf" work)
                                      {:isError true
                                       :text (str "edit-commit unexpected response: " (pr-str commit))})))))))))
                        (finally (release-pin! pin)))))))))))

      ;; FRAM_FLIP=1 but no code coordinator: fail loud (don't silently text-fallback).
      flip-on?
      {:isError true :text "FRAM_FLIP=1 but FRAM_CODE_PORT is unset — refusing to fall back to text-sourced edits (set the code coordinator port or unset FRAM_FLIP)"}

      :else (route-edit-text e))))

;; ---- LEGACY (text-canonical) edit path — the pre-flip behavior, used when
;; FRAM_FLIP is unset. Sources from text, applies the verb, recompiles, overwrites
;; the .bclj. The flip path above replaces this when the log is canonical.
(defn route-edit-text [e]
  (let [op (:op e) module (:module e)
        src-files (bclj-files fram-src)
        targets (filter #(str/includes? % module) src-files)]
    (cond
      (empty? src-files) {:isError true :text (str "no .bclj source modules under FRAM_SRC=" fram-src)}
      (not= 1 (count targets))
      {:isError true :text (str "module scope \"" module "\" matches " (count targets)
                                " source files; an edit needs exactly one (no facts mutated)")}
      :else
      (let [work (str (System/getProperty "java.io.tmpdir") "/fram-edit-" (System/nanoTime))
            edir (str work "/e") regen (str work "/regen") odir (str work "/o")
            _ (run! #(.mkdirs (io/file %)) [edir regen odir])
            resolve-out work                                  ; $RESOLVE_OUT for resolve.clj
            ;; 1. project every source module to AST-facts EDN (cross-module resolve).
            edns (mapv (fn [f]
                         (let [b (.getName (io/file f))
                               out (str edir "/" b ".edn")
                               r (sh {:out (io/file out) :err :string} "racket" roundtrip-rkt "--emit-edn" f)]
                           [f b out (:exit r) (:err r)]))
                       src-files)
            emit-fail (some (fn [[_ b _ ex er]] (when (not (zero? ex)) (str "emit-edn failed for " b ": " er))) edns)]
        (if emit-fail
          (do (sh {} "rm" "-rf" work) {:isError true :text emit-fail})
          (let [edn-paths (mapv #(nth % 2) edns)
                ;; 2. apply the verb as a FACT OP. Spec/body datum strings go to temp files,
                ;; exactly how the gate passes them (resolve.clj slurps + edn/read-string).
                spec-file (str work "/spec.edn")
                resolve-args
                (case op
                  "upsert-form" (do (spit spec-file (:form e))
                                    (concat ["upsert-form" module spec-file] edn-paths))
                  "set-body"    (do (spit spec-file (:body e))
                                    (concat ["set-body" (:name e) module spec-file] edn-paths))
                  "rename"      (concat ["rename" (:name e) (:new-name e) module] edn-paths)
                  "replace-in-body" (let [of (str work "/old.edn") nf (str work "/new.edn")
                                          wf (when (:within e) (str work "/within.edn"))]
                                      (spit of (:old e)) (spit nf (:new e))
                                      (when wf (spit wf (:within e)))
                                      (concat ["replace-in-body" (:name e) module of nf] edn-paths
                                              (when wf ["--within-file" wf])))
                  nil)]
            (if (nil? resolve-args)
              (do (sh {} "rm" "-rf" work) {:isError true :text (str "unknown edit op: " op)})
              (let [rr (apply sh {:err :string :extra-env {"RESOLVE_OUT" resolve-out}}
                              "bb" "-cp" fram-out resolve-clj resolve-args)]
                (if (not (zero? (:exit rr)))
                  ;; engine REFUSED — resolve.clj prints "REJECTED — ... no facts mutated" to stderr.
                  (do (sh {} "rm" "-rf" work)
                      {:isError true :text (str "REJECTED by the authoring engine — nothing mutated:\n"
                                                (str/trim (or (:err rr) "")))})
                  ;; 3. regenerate byte-stable text for every module from the projected EDN.
                  (let [render-fail
                        (some (fn [f]
                                (let [b (.getName (io/file f))
                                      proj (str resolve-out "/resolved-" b ".edn")
                                      out (str regen "/" b)]
                                  (if (.exists (io/file proj))
                                    (let [r (sh {:out (io/file out) :err :string} "racket" roundtrip-rkt "--render" proj)]
                                      (when (not (zero? (:exit r))) (str "render failed for " b ": " (:err r))))
                                    (str "no projected EDN for " b " (expected " proj ")"))))
                              src-files)]
                    (if render-fail
                      (do (sh {} "rm" "-rf" work) {:isError true :text render-fail})
                      ;; 4. recompile-gate: build the regenerated tree; require '0 error'.
                      (let [bg (sh {:out :string :err :string} build-all regen "--out" odir)
                            built (str (:out bg) (:err bg))]
                        (if (str/includes? built "0 error")
                          ;; PASS — LEGACY (text-canonical) commit: overwrite the source
                          ;; .bclj with the regenerated text. (The FLIP path is handled
                          ;; upstream in route-edit; this branch is reached only when
                          ;; FRAM_FLIP is unset.)
                          (let [tf (first targets)
                                tb (.getName (io/file tf))]
                            (io/copy (io/file (str regen "/" tb)) (io/file tf))
                            (sh {} "rm" "-rf" work)
                            {:text (str "committed: " op " on " tb
                                        " (fact op, recompiled, byte-stable text regenerated)")})
                          ;; FAIL — does not recompile; mutate nothing, return the diagnostic.
                          (do (sh {} "rm" "-rf" work)
                              {:isError true :text (str "REJECTED — regenerated module does not recompile (no source written):\n"
                                                        (str/trim built))}))))))))))))))

;; the graph-AST edit tools — these route through route-edit (a long recompile-gated
;; transaction), NOT the query budget. Names match the structural ToolSpecs in tools.bclj.
(def ^:private edit-tools #{"add-def" "set-body" "rename-def" "insert-after" "replace-in-body"})
(defn- edit-tool? [nm] (contains? edit-tools nm))

;; ============================================================================
;; PROFILES — opt-in restricted tool surfaces (FRAM_MCP_PROFILE; unset = full).
;; ============================================================================
;; "full" (the default when FRAM_MCP_PROFILE is unset) is the exact ten-tool
;; closed catalog above — the pre-profile behavior, no new checks anywhere.
;; "graph-edit-v1" is the RESTRICTED authoring profile for graph-upstream repos
;; (the fram-code-on wiring): exactly the five graph-edit verbs are EXPOSED
;; (tools/list) *and* AUTHORIZED (tools/call). The call gate is server-side and
;; runs BEFORE alias normalization (untell->retract, query<->ask) and BEFORE any
;; dispatch: a denied name never reaches tl/call, load-state, the coordinator,
;; or a subprocess — zero mutation by construction. Filtering tools/list alone
;; would be advisory; the tools/call gate is the authority.
;; The profile is fixed at STARTUP from the server's own environment — never
;; from a request, never from a project .mcp.json (this server loads no MCP
;; config and execs nothing a caller names); the allow-list is a compile-time
;; constant. Unknown profile names FAIL CLOSED at startup (fence at the bottom).
(def ^:private profile (or (System/getenv "FRAM_MCP_PROFILE") "full"))
(def ^:private restricted? (= profile "graph-edit-v1"))
;; FRAM_MCP_LIBRARY=1: load this file as a LIBRARY (defs only — no profile
;; fence, no stdio loop). Test seam for driving the pinned projection
;; publication helpers directly and deterministically (mcp_candidate_test).
(def ^:private library-mode? (= "1" (System/getenv "FRAM_MCP_LIBRARY")))

;; (canon — the canonical-path helper — is defined above route-edit, which needs it.)

;; graph-edit-v1 per-call gate: nil = authorized, else {:text <denial>}.
;;   (1) the name must be one of the five edit verbs — everything else
;;       (tell/retract/show/ask/validate, the query/untell aliases, unknown
;;       names) is denied AS GIVEN, pre-normalization, pre-dispatch;
;;   (2) the rendered target FRAM_SRC/<module>.bclj must stay CONFINED under
;;       the FRAM_SRC root once canonicalized (a module like "../x" would
;;       otherwise render outside the intended source tree).
(defn- profile-gate [nm args]
  (when restricted?
    (if-not (edit-tool? nm)
      {:text (str "profile graph-edit-v1: tool '" nm "' is not authorized — this surface is exactly "
                  "add-def / set-body / rename-def / insert-after / replace-in-body. "
                  "Denied before alias normalization and dispatch; nothing mutated.")}
      (let [m (:module args)]
        (cond
          (and (some? m) (not (string? m)))
          {:text "profile graph-edit-v1: 'module' must be a string — refused before dispatch; nothing mutated."}
          (and (string? m)
               (not (str/starts-with? (canon (str fram-src "/" m ".bclj"))
                                      (str (canon fram-src) "/"))))
          {:text (str "profile graph-edit-v1: module '" m "' renders outside the source root "
                      fram-src " — refused before dispatch; nothing mutated.")}
          :else nil)))))

(def ^:private profile-instructions
  (if restricted?
    (str instructions
         "\n\nPROFILE graph-edit-v1 (restricted): only the five graph-edit verbs "
         "(add-def / set-body / rename-def / insert-after / replace-in-body) are exposed and "
         "authorized; tell / retract / show / ask / validate (and the untell/query aliases) "
         "are denied server-side before dispatch.")
    instructions))

;; --- dispatch one tools/call (catalog path) ------------------------------------
(defn- dispatch-call [name a]
  ;; WARM READ PATH (interface investigation #1): serve `query` off the daemon's warm
  ;; store instead of a COLD full-log fold per request (~60x: ~450ms cold vs ~7ms warm
  ;; on the canonical log). coord-query returns nil if the daemon is DOWN or PREDATES
  ;; the warm :query op ({:error "unknown op"}) -> fall through to the cold path, so
  ;; this is safe even against an older live daemon (no coordinated restart required).
  ;; The warm :query op returns the SAME q/run envelope the cold path produces, so the
  ;; formatting is identical. Rep-stable: keys on (l,p,r)/Datalog, no fN ordering.
  (if-let [warm (when (= name "query")
                  (fram.rt/coord-query-for-log
                   (fram.rt/coord-port) (fram.rt/log-path) (:query a)))]
    (cond (:error warm)        {:isError true :text (str/join "\n" (:error warm))}
          (contains? warm :ok) {:text (json/generate-string (:ok warm))}
          :else                {:text (json/generate-string warm)})
    (let [{:keys [facts idx cat]} (load-state)
          res (tl/call facts idx cat name a)]
      (cond
        (:error res) {:isError true :text (str/join "\n" (:error res))}
        (:write res) (route-write (:write res))
        (:edit res)  (route-edit (:edit res))
        (contains? res :ok) {:text (json/generate-string (:ok res))}
        :else {:text (json/generate-string (:rows res))}))))

;; --- dispatch one tools/call ---------------------------------------------------
;; Every tool — tell / retract / show / ask / validate + the edit verbs — dispatches
;; through the closed catalog (fram.tools/call): tell/retract lower to a {:write}
;; coordinator intent, ask/show/validate to reads. tl/call also accepts `untell` as an
;; alias for `retract` (and `query` for `ask`). The only pre-map here is the `ask` KB
;; name onto the engine's `query` op (also the warm-read fast path in dispatch-call).
(defn handle-call [name args]
  (let [name (if (= name "ask") "query" name)
        a (or args {})]
    (dispatch-call name a)))

;; wall-clock budget on the AI-facing path: validation makes a query STRUCTURALLY
;; safe, but evaluation is naive, so a deeply recursive query can be slow. Bound it
;; here. 10s is generous for the corpus sizes Fram targets; the CLI path runs
;; unbounded (a human can Ctrl-C).
;;
;; IMPORTANT: future-cancel only sets the worker's interrupt flag — it does NOT
;; stop CPU-bound work that never checks Thread.interrupted()/blocks. The naive
;; datalog fixpoint (fram.datalog/fixpoint) is exactly that: tight reduce/loop
;; forms with no interruption checks, so a cancelled runaway query would keep
;; pinning a core indefinitely, and "repeat a few times" pins every core. We can't
;; make the engine cooperatively interruptible from here (that per-iteration bound
;; belongs in fram.datalog, owned elsewhere — see FOLLOW-UP below), so we bound the
;; BLAST RADIUS two ways the budget can actually enforce:
;;   (1) run on a DAEMON thread we set the interrupt flag on and then abandon, so a
;;       runaway never keeps the JVM alive and is reaped on process exit; and
;;   (2) a hard CAP on how many query workers may be alive at once — an orphaned
;;       runaway holds its slot until it (eventually) finishes, so a client cannot
;;       pile up unbounded CPU-pegged threads by repeating an expensive query. Once
;;       the cap is hit, further queries are refused FAST (within budget) instead of
;;       spawning yet another never-dying core-pinning thread.
;; FOLLOW-UP (fram.datalog): add a cooperative deadline / max-iterations /
;; max-derived-facts bound inside fixpoint so a runaway actually STOPS at the
;; budget rather than running to completion on its abandoned daemon thread.
(def ^:private max-live-queries
  (max 1 (quot (.. Runtime getRuntime availableProcessors) 2)))
(def ^:private live-queries (atom 0))

(defn- with-timeout [ms thunk]
  ;; reserve a worker slot; refuse fast if too many (possibly orphaned) are alive.
  (if (> (swap! live-queries inc) max-live-queries)
    (do (swap! live-queries dec)
        {:isError true :text (str "query budget: too many concurrent/abandoned queries in flight (>" max-live-queries ") — a prior expensive query is still running; retry later or narrow it")})
    (let [result (promise)
          worker (doto (Thread.
                        (fn []
                          (try (deliver result (thunk))
                               (catch InterruptedException _ (deliver result ::timeout))
                               (catch Throwable t (deliver result {:isError true :text (str "query failed: " (.getMessage t))}))
                               (finally (swap! live-queries dec)))))
                   (.setDaemon true)         ; never blocks JVM shutdown
                   (.setName "fram-mcp-query")
                   (.start))
          r (deref result ms ::timeout)]
      (if (= r ::timeout)
        (do (.interrupt worker)              ; best-effort; abandoned if it ignores us
            {:isError true :text (str "query exceeded the " (quot ms 1000) "s time budget — narrow it (fewer rules / more constants)")})
        r))))

;; --- JSON-RPC plumbing -------------------------------------------------------
(defn- reply [id result] (println (json/generate-string {:jsonrpc "2.0" :id id :result result})) (flush))
(defn- reply-err [id code msg] (println (json/generate-string {:jsonrpc "2.0" :id id :error {:code code :message msg}})) (flush))

(defn handle [req]
  (let [has-id (contains? req :id)      ; a request WITHOUT an :id key is a notification
        id (:id req) method (:method req) params (:params req)]
    (cond
      ;; notification: never answer, whatever the method (and an explicit "id":null
      ;; below is still a request, so it DOES get answered — the contains? check
      ;; distinguishes "no id key" from "id is null").
      (not has-id) nil

      (= method "initialize")
      (reply id {:protocolVersion "2024-11-05"
                 :capabilities {:tools {}}
                 :serverInfo {:name "fram" :version "0.1"}
                 :instructions profile-instructions})

      (= method "tools/list")
      ;; a restricted profile EXPOSES exactly its allow-list. (Exposure is UX;
      ;; the profile-gate on tools/call below is the enforcement.)
      (reply id {:tools (->> (:cat (load-state))
                             (filter (fn [spec] (or (not restricted?) (edit-tool? (:name spec)))))
                             (mapv ->tool))})

      (= method "tools/call")
      ;; PROFILE GATE FIRST: under a restricted profile an unauthorized or
      ;; unconfined call is denied HERE — before alias normalization
      ;; (handle-call / tl-call), before load-state, before any coordinator or
      ;; subprocess contact. Zero mutation on the denial path.
      ;;
      ;; Then: graph-AST edits run a multi-process recompile-gated transaction that
      ;; far exceeds the 10s QUERY budget (and is bounded by its own subprocesses,
      ;; not a CPU-pegged datalog fixpoint), so they BYPASS with-timeout.
      ;; Reads/queries keep the budget. Classify by tool name against the catalog's
      ;; edit ops.
      (let [nm (:name params)]
        (if-let [denial (profile-gate nm (:arguments params))]
          (reply id {:content [{:type "text" :text (:text denial)}] :isError true})
          (let [r (if (edit-tool? nm)
                    (handle-call nm (:arguments params))
                    (with-timeout 10000 (fn [] (handle-call nm (:arguments params)))))]
            (reply id {:content [{:type "text" :text (:text r)}] :isError (boolean (:isError r))}))))

      :else (reply-err id -32601 (str "method not found: " method)))))

;; ============================================================================
;; PROFILE STARTUP FENCE — a restricted profile binds its WHOLE identity before
;; serving a single request; anything short of the exact contract fails closed
;; (exit 2, diagnostic on stderr, stdout stays a pure JSON-RPC channel).
;; ============================================================================
(defn- die! [msg]
  (log! (str "fram-mcp: REFUSING to start (FRAM_MCP_PROFILE=" profile "): " msg))
  (System/exit 2))

;; require an env path that is present, ABSOLUTE, already CANONICAL (equal to
;; its own canonical form — no relative segments, no symlink indirection), and
;; that exists as a directory/file. Returns the canonical path.
(defn- require-canonical! [label v dir?]
  (when (str/blank? (str v)) (die! (str label " is required")))
  (when-not (.isAbsolute (io/file v))
    (die! (str label " must be an ABSOLUTE path (got " (pr-str v) ")")))
  (let [c (canon v)]
    (when-not (= c v)
      (die! (str label " must be CANONICAL (got " (pr-str v) "; canonical form " (pr-str c) ")")))
    (when (and dir? (not (.isDirectory (io/file v))))
      (die! (str label " is not an existing directory: " v)))
    (when (and (not dir?) (not (.isFile (io/file v))))
      (die! (str label " is not an existing file: " v)))
    c))

;; strict-fence probe (mirrors bin/fram-code-on's coordinator_requires_fence):
;; an UNWRAPPED {:op :version} must be REJECTED with :code :log-fence-required.
;; A dead port, a permissive/legacy daemon, or any other answer -> NOT strict.
(defn- strict-fence-live? [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
      (.setSoTimeout s 2000)
      (let [w (io/writer (.getOutputStream s))
            r (java.io.BufferedReader. (io/reader (.getInputStream s)))]
        (.write w "{:op :version}\n") (.flush w)
        (let [line (.readLine r)]
          (and (some? line)
               (= :log-fence-required (:code (clojure.edn/read-string line)))))))
    (catch Throwable _ false)))

(defn- enforce-graph-edit-v1! []
  ;; graph-edit MODE is the licensed substrate: FRAM_GRAPH_EDIT declares the
  ;; fram-code-on wiring and FRAM_FLIP routes every verb graph-sourced (warm
  ;; :edit-min) — the text-legacy edit path is NOT licensed under this profile.
  (when-not (= "1" (System/getenv "FRAM_GRAPH_EDIT"))
    (die! "graph-edit mode is required: FRAM_GRAPH_EDIT=1 (wire with bin/fram-code-on)"))
  (when-not flip-on?
    (die! "graph-sourced editing is required: FRAM_FLIP=1 (text-legacy edits are not licensed under graph-edit-v1)"))
  (let [port (try (Integer/parseInt (str flip-code-port)) (catch Exception _ nil))]
    (when-not (and port (<= 1 port 65535))
      (die! (str "FRAM_CODE_PORT must name a TCP port (got " (pr-str flip-code-port) ")")))
    (let [src (require-canonical! "FRAM_SRC" (System/getenv "FRAM_SRC") true)
          log (require-canonical! "FRAM_CODE_LOG" (System/getenv "FRAM_CODE_LOG") false)]
      ;; the code log must live INSIDE the source binding (fram-code-on puts it
      ;; at <src>/.fram/code.log) — a log outside the tree is a foreign corpus.
      (when-not (str/starts-with? log (str src "/"))
        (die! (str "FRAM_CODE_LOG " log " lies OUTSIDE the FRAM_SRC source binding " src)))
      ;; live coordinator, STRICT fence, EXACT canonical log — all three.
      (when-not (strict-fence-live? port)
        (die! (str "no strict-fenced coordinator on 127.0.0.1:" port
                   " — unwrapped requests must be rejected with :log-fence-required"
                   " (daemon dead, or permissive/legacy; rerun bin/fram-code-on)")))
      (let [v (fram.rt/coord-version-for-log port log)]
        (when (neg? v)
          (die! (case v
                  -1 (str "no coordinator answers fenced requests on 127.0.0.1:" port)
                  -2 (str "coordinator on 127.0.0.1:" port " serves a DIFFERENT log than " log)
                  -3 (str "coordinator on 127.0.0.1:" port " lacks the log-fence protocol")
                  (str "coordinator on 127.0.0.1:" port " is unusable")))))
      ;; PROTOCOL HANDSHAKE — the restricted profile edits ONLY through the atomic
      ;; candidate gate (:edit-prepare/:edit-commit). A coordinator that cannot
      ;; answer the capability op is a legacy commit-first daemon (or a future
      ;; incompatible protocol) — refuse to serve rather than degrade to unsafe
      ;; commit-before-check editing.
      (let [pr (try (fram.rt/coord-request-for-log port log {:op :edit-protocol})
                    (catch Throwable _ nil))]
        (when-not (= "graph-edit-candidate-v1" (:protocol pr))
          (die! (str "coordinator on 127.0.0.1:" port " does not speak graph-edit-candidate-v1"
                     " (answered " (pr-str (or (:protocol pr) (:error pr) pr))
                     ") — legacy or wrong-protocol coordinator; restart it with current Fram"
                     " (rerun bin/fram-code-on)")))
        (let [state (get-in pr [:durability :state])]
          (when (#{:poisoned :committed-repair-needed} state)
            (die! (str "coordinator on 127.0.0.1:" port " is "
                       (if (= :poisoned state)
                         "DURABILITY-POISONED"
                         "COMMITTED-REPAIR-NEEDED")
                       " " (pr-str (:durability pr)) " — REFUSING to serve; stop/restart it"
                       " for sole-writer recovery/repair before authoring")))))
      (log! (str "fram-mcp: profile graph-edit-v1 bound — src " src ", log " log
                 ", strict-fenced coordinator 127.0.0.1:" port
                 " [graph-edit-candidate-v1]")))))

;; fail-closed profile admission: full = the exact legacy surface (no new
;; checks); graph-edit-v1 = the fence above; any OTHER name never serves.
;; FRAM_MCP_LIBRARY=1 skips admission AND the loop — library load, never serving.
(when-not library-mode?
  (case profile
    "full"          nil
    "graph-edit-v1" (enforce-graph-edit-v1!)
    (die! "unknown profile — known profiles: full, graph-edit-v1")))

(when library-mode?
  (log! "fram-mcp: loaded as a library (FRAM_MCP_LIBRARY=1) — no profile fence, no stdio loop"))
(when-not library-mode?
 (log! (if restricted?
        "fram-mcp: ready on stdio (profile graph-edit-v1: add-def/set-body/rename-def/insert-after/replace-in-body ONLY)"
        "fram-mcp: ready on stdio (closed catalog: tell/retract/show/ask/validate + 5 edit verbs)"))
 (loop []
  (let [line (read-line)]
    (when (some? line)
      (when (seq (str/trim line))
        (let [req (try (json/parse-string line true) (catch Exception e (log! "parse error:" (.getMessage e)) nil))]
          (cond
            (nil? req) nil
            ;; a valid request is a JSON object (map). Anything else — a top-level
            ;; array (JSON-RPC batch, which cheshire yields as a seq, removed in MCP
            ;; 2025-06-18), or a scalar — is rejected loudly so a client doesn't hang
            ;; on a missing response.
            (not (map? req))
            (do (println (json/generate-string {:jsonrpc "2.0" :id nil :error {:code -32600 :message "Invalid Request: expected a single JSON object (batches not supported)"}})) (flush))
            :else
            (try (handle req)
                 (catch Exception e (log! "handler error:" (.getMessage e))
                   (when (contains? req :id) (reply-err (:id req) -32603 (str (.getMessage e)))))))))
      (recur)))))
