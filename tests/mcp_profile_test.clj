;; mcp_profile_test.clj — FRAM_MCP_PROFILE contract for bin/fram-mcp (graph-edit-v1).
;; ============================================================================
;; Drives the REAL bin/fram-mcp over stdio (fresh hermetic :env per spawn — no
;; ambient FRAM_* leaks) and proves the opt-in profile seam end to end:
;;
;;   A. DEFAULT COMPATIBILITY — profile unset AND profile=full both serve the
;;      exact ten-tool closed catalog, and the untell->retract alias still
;;      resolves (the pre-profile behavior, byte-for-byte).
;;   B. UNKNOWN PROFILE FAILS CLOSED — any unrecognized FRAM_MCP_PROFILE exits
;;      nonzero before serving a single request, even with an otherwise fully
;;      valid restricted environment.
;;   C. RESTRICTED STARTUP FENCE — graph-edit-v1 refuses to start unless:
;;      graph-edit mode (FRAM_GRAPH_EDIT=1 + FRAM_FLIP=1), canonical ABSOLUTE
;;      FRAM_SRC / FRAM_CODE_LOG / FRAM_CODE_PORT identity, an EXISTING log
;;      INSIDE the source binding, and a LIVE STRICT-FENCED coordinator serving
;;      exactly that log. Dead / wrong-log / permissive daemons all fail closed.
;;   D. RESTRICTED SURFACE — tools/list is EXACTLY the five graph-edit verbs;
;;      tools/call DENIES tell/retract/show/ask/validate, the query/untell
;;      aliases, and unknown names BEFORE alias normalization or dispatch, with
;;      ZERO mutation (both logs byte-identical across the denial batch); a
;;      module whose rendered target escapes FRAM_SRC is refused pre-dispatch.
;;   E. AUTHORIZED VERBS STILL WORK — a real set-body lands through the
;;      restricted surface (warm :edit-min via the strict-fenced coordinator),
;;      proving the profile authorizes exactly the five, not zero.
;;
;;   bb -cp out tests/mcp_profile_test.clj      (run from the repo root)
;; Needs: bb + out/. Parts C-E boot throwaway coordinators (clojure JVM).
;; Part E additionally needs racket + beagle (facts-roundtrip / facts-check-emit)
;; and SKIPS with a message when absent.
(require '[babashka.process :as p] '[cheshire.core :as json]
         '[clojure.string :as str] '[clojure.java.io :as io])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]) (println (if ok "  [PASS] " "  [FAIL] ") nm))

(def root (System/getProperty "user.dir"))
(def home (System/getProperty "user.home"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))

;; hermetic workspace — canonicalized so the CANONICAL startup checks see the
;; same string we pass (a symlinked java.io.tmpdir would otherwise diverge).
(def tmp (.getCanonicalPath (io/file (str (System/getProperty "java.io.tmpdir")
                                          "/fram-mcp-profile-" (System/nanoTime)))))
(def src-dir (str tmp "/srcroot"))                 ; FRAM_SRC (the source binding)
(def code-log (str src-dir "/.fram/code.log"))     ; inside the binding, like fram-code-on
(def other-log (str src-dir "/.fram/other.log"))   ; exists, but the daemon won't serve it
(def outside-log (str tmp "/outside.log"))         ; exists, but OUTSIDE the binding
(def facts-log (str tmp "/facts.log"))             ; the KB corpus (irrelevant but pinned)
(.mkdirs (io/file (str src-dir "/.fram")))
(spit facts-log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"test\"}\n")
(doseq [f [other-log outside-log]]
  (spit f "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"test\"}\n"))

;; --- part E prerequisites (real graph edit) ----------------------------------
(def roundtrip (str beagle-home "/beagle-lib/private/facts-roundtrip.rkt"))
(def check-emit (str beagle-home "/beagle-lib/private/facts-check-emit.rkt"))
(def schema-module (str root "/src/fram/schema.bclj"))
(def beagle-ok? (every? #(.exists (io/file %)) [roundtrip check-emit schema-module]))

;; the code log the daemons serve: a REAL ingested module when beagle is present
;; (so part E can edit it), else a tiny synthetic log (parts A-D need no module).
(if beagle-ok?
  (do (io/copy (io/file schema-module) (io/file (str src-dir "/schema.bclj")))
      (let [r (p/shell {:continue true :out :string :err :string
                        :extra-env {"BEAGLE_HOME" beagle-home}}
                       "bin/fram-ingest-code" src-dir "--module" "schema" "--out" code-log)]
        (when-not (zero? (:exit r))
          (println "ingest failed — falling back to synthetic code log:" (str/trim (:err r)))
          (spit code-log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"test\"}\n")
          (def beagle-ok? false))))
  (spit code-log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"test\"}\n"))

;; --- throwaway coordinators ---------------------------------------------------
(defn port-free? [p]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
       (catch Exception _ true)))
(defn pick-port [cands] (or (some #(when (port-free? %) %) cands)
                            (throw (ex-info "no free test port" {}))))
(def strict-port (pick-port [39871 39873 39875 39877 39879]))
(def perm-port   (pick-port [39872 39874 39876 39878 39880]))
(def dead-port   (pick-port [59991 59992 59993 59994 59995]))

(defn boot-daemon! [port log fence?]
  (let [outf (str tmp "/daemon-" port ".log")
        proc (p/process {:out (io/file outf) :err (io/file outf)
                         :extra-env {"FRAM_REQUIRE_LOG_FENCE" (if fence? "1" "0")}}
                        "clojure" "-M" "coord_daemon.clj" "serve-flat" (str port) log)]
    (loop [i 0]
      (cond
        (and (.exists (io/file outf)) (str/includes? (slurp outf) "listening on")) proc
        (> i 360) (do (p/destroy-tree proc)
                      (throw (ex-info (str "daemon on :" port " never came up") {:log outf})))
        :else (do (Thread/sleep 500) (recur (inc i)))))))

(println "booting throwaway coordinators (strict:" strict-port " permissive:" perm-port ") …")
(def strict-daemon (boot-daemon! strict-port code-log true))
(def perm-daemon   (boot-daemon! perm-port   code-log false))

;; --- spawning the server hermetically ------------------------------------------
;; :env REPLACES the environment (bb inherits ambient otherwise): only PATH/HOME
;; pass through, so a live north/fram runtime's FRAM_* can never leak in.
(def base-env
  (cond-> {"PATH" (System/getenv "PATH") "HOME" home
           "FRAM_LOG" facts-log "FRAM_THREADS" tmp "FRAM_PORT" (str dead-port)}
    (System/getenv "FRAM_RACKET") (assoc "FRAM_RACKET" (System/getenv "FRAM_RACKET"))))

(def good-env
  (merge base-env
         {"FRAM_MCP_PROFILE" "graph-edit-v1"
          "FRAM_GRAPH_EDIT" "1" "FRAM_FLIP" "1"
          "FRAM_CODE_PORT" (str strict-port)
          "FRAM_CODE_LOG" code-log
          "FRAM_SRC" src-dir
          "FRAM_OUT" (str root "/out") "FRAM_BIN" (str root "/bin")
          "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")
          "BEAGLE_HOME" beagle-home
          "FRAM_ROUNDTRIP" roundtrip "FRAM_CHECK_EMIT" check-emit
          "FRAM_BUILD_ALL" (str beagle-home "/bin/beagle-build-all")}))

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
(def list-req {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
(defn call-req [id tool args] {:jsonrpc "2.0" :id id :method "tools/call"
                               :params {:name tool :arguments args}})

(def ten #{"tell" "retract" "show" "ask" "validate"
           "add-def" "set-body" "rename-def" "insert-after" "replace-in-body"})
(def five #{"add-def" "set-body" "rename-def" "insert-after" "replace-in-body"})

;; ============================================================================
;; A. DEFAULT COMPATIBILITY — unset + explicit full are the exact legacy surface.
;; ============================================================================
(let [{:keys [by-id exit]} (run-mcp base-env
                                    [init-req list-req
                                     (call-req 3 "untell" {:subject "a" :predicate "owner"})])
      names (set (map :name (get-in (get by-id 2) [:result :tools])))]
  (chk "A: profile UNSET -> server serves (exit 0)" (zero? exit))
  (chk "A: profile UNSET -> tools/list is EXACTLY the ten-tool closed catalog" (= names ten))
  (chk "A: profile UNSET -> untell ALIAS still resolves to retract (param error, not unknown/denied)"
       (let [r (get by-id 3) t (or (rtext r) "")]
         (and (rerr? r) (str/includes? t "object") (not (str/includes? t "not authorized"))))))

(let [{:keys [by-id exit]} (run-mcp (assoc base-env "FRAM_MCP_PROFILE" "full")
                                    [init-req list-req])
      names (set (map :name (get-in (get by-id 2) [:result :tools])))]
  (chk "A: profile=full -> serves the exact ten-tool catalog, no fence checks"
       (and (zero? exit) (= names ten))))

;; ============================================================================
;; B. UNKNOWN PROFILE FAILS CLOSED — even with a fully valid restricted env.
;; ============================================================================
(let [{:keys [exit by-id err]} (run-mcp (assoc good-env "FRAM_MCP_PROFILE" "graph-edit-v2")
                                        [init-req list-req])]
  (chk "B: unknown profile -> exit nonzero, ZERO replies served"
       (and (not (zero? exit)) (empty? by-id)))
  (chk "B: unknown profile -> stderr says REFUSING to start"
       (str/includes? (or err "") "REFUSING to start")))

;; ============================================================================
;; C. RESTRICTED STARTUP FENCE — every broken identity fails closed (exit != 0,
;;    zero replies). Each case perturbs ONE thing in an otherwise valid env.
;; ============================================================================
(defn fence-refuses [nm env & [marker]]
  (let [{:keys [exit by-id err]} (run-mcp env [init-req list-req])]
    (chk (str "C: " nm " -> fails closed (no replies)")
         (and (not (zero? exit)) (empty? by-id)
              (str/includes? (or err "") "REFUSING to start")
              (or (nil? marker) (str/includes? (or err "") marker))))))

(fence-refuses "FRAM_GRAPH_EDIT missing (graph-edit mode required)"
               (dissoc good-env "FRAM_GRAPH_EDIT") "FRAM_GRAPH_EDIT=1")
(fence-refuses "FRAM_FLIP missing (graph-sourced editing required)"
               (dissoc good-env "FRAM_FLIP") "FRAM_FLIP=1")
(fence-refuses "FRAM_CODE_PORT missing" (dissoc good-env "FRAM_CODE_PORT") "FRAM_CODE_PORT")
(fence-refuses "FRAM_CODE_PORT non-numeric" (assoc good-env "FRAM_CODE_PORT" "notaport") "FRAM_CODE_PORT")
(fence-refuses "FRAM_SRC missing" (dissoc good-env "FRAM_SRC") "FRAM_SRC")
(fence-refuses "FRAM_SRC relative" (assoc good-env "FRAM_SRC" "srcroot") "ABSOLUTE")
(fence-refuses "FRAM_SRC non-canonical (/./ segment)"
               (assoc good-env "FRAM_SRC" (str tmp "/./srcroot")) "CANONICAL")
(fence-refuses "FRAM_SRC nonexistent" (assoc good-env "FRAM_SRC" (str tmp "/nosuch")) "directory")
(fence-refuses "FRAM_CODE_LOG missing" (dissoc good-env "FRAM_CODE_LOG") "FRAM_CODE_LOG")
(fence-refuses "FRAM_CODE_LOG relative" (assoc good-env "FRAM_CODE_LOG" "code.log") "ABSOLUTE")
(fence-refuses "FRAM_CODE_LOG nonexistent"
               (assoc good-env "FRAM_CODE_LOG" (str src-dir "/.fram/nope.log")) "file")
(fence-refuses "FRAM_CODE_LOG outside the FRAM_SRC source binding"
               (assoc good-env "FRAM_CODE_LOG" outside-log) "OUTSIDE")
(fence-refuses "dead coordinator port"
               (assoc good-env "FRAM_CODE_PORT" (str dead-port)) "strict-fenced")
(fence-refuses "coordinator serves a DIFFERENT log"
               (assoc good-env "FRAM_CODE_LOG" other-log) "DIFFERENT")
(fence-refuses "coordinator is PERMISSIVE (no strict log fence)"
               (assoc good-env "FRAM_CODE_PORT" (str perm-port)) "strict-fenced")

;; ============================================================================
;; D. RESTRICTED SURFACE — inventory, pre-dispatch denials, ZERO mutation.
;; ============================================================================
(def code-log-before (slurp code-log))
(def facts-log-before (slurp facts-log))

(def denial-run
  (run-mcp good-env
           [init-req list-req
            ;; excluded KB verbs with COMPLETE, WOULD-MUTATE arguments: if any of
            ;; these dispatched, tell/retract would reach the coordinator.
            (call-req 10 "tell"     {:subject "a" :predicate "note" :object "n"})
            (call-req 11 "retract"  {:subject "a" :predicate "title" :object "A"})
            (call-req 12 "show"     {:subject "a"})
            (call-req 13 "ask"      {:query {:find "x" :rules []}})
            (call-req 14 "validate" {})
            ;; the aliases, AS GIVEN (must be denied before normalization).
            (call-req 15 "query"    {:query {:find "x" :rules []}})
            (call-req 16 "untell"   {:subject "a" :predicate "title" :object "A"})
            ;; unknown tool name.
            (call-req 17 "frobnicate" {})
            ;; allowed verb, rendered target ESCAPES the source root.
            (call-req 18 "set-body" {:module "../escape" :name "x" :body "1"})
            (call-req 19 "add-def"  {:module "x/../../escape2" :form "(defn f [] :- Int 1)"})
            {:jsonrpc "2.0" :id 20 :method "no/such-method" :params {}}]))

(let [{:keys [by-id exit]} denial-run
      names (set (map :name (get-in (get by-id 2) [:result :tools])))]
  (chk "D: restricted startup serves (exit 0) against the strict-fenced coordinator" (zero? exit))
  (chk "D: initialize instructions declare the restricted profile"
       (str/includes? (str (get-in (get by-id 1) [:result :instructions])) "graph-edit-v1"))
  (chk "D: tools/list is EXACTLY the five graph-edit verbs" (= names five))
  (doseq [[id nm] [[10 "tell"] [11 "retract"] [12 "show"] [13 "ask"] [14 "validate"]
                   [15 "query"] [16 "untell"] [17 "frobnicate"]]]
    (let [r (get by-id id) t (or (rtext r) "")]
      (chk (str "D: tools/call '" nm "' DENIED pre-dispatch (isError, no dispatch markers)")
           (and (rerr? r)
                (str/includes? t "not authorized")
                ;; none of the post-dispatch shapes appear: tl/call's unknown-tool,
                ;; the param checker, a commit, or a coordinator refusal.
                (not (str/includes? t "unknown tool"))
                (not (str/includes? t "missing required param"))
                (not (str/includes? t "committed"))
                (not (str/includes? t "coordinator"))))))
  (let [r (get by-id 18) t (or (rtext r) "")]
    (chk "D: set-body module '../escape' -> refused, target outside the source root"
         (and (rerr? r) (str/includes? t "outside the source root"))))
  (let [r (get by-id 19) t (or (rtext r) "")]
    (chk "D: add-def module 'x/../../escape2' -> refused, target outside the source root"
         (and (rerr? r) (str/includes? t "outside the source root"))))
  (chk "D: unknown method still -> -32601 (JSON-RPC plumbing intact)"
       (= -32601 (get-in (get by-id 20) [:error :code]))))

(chk "D: ZERO MUTATION — code log byte-identical across the whole denial batch"
     (= code-log-before (slurp code-log)))
(chk "D: ZERO MUTATION — KB facts log byte-identical across the whole denial batch"
     (= facts-log-before (slurp facts-log)))
(chk "D: no escaped render target was created outside FRAM_SRC"
     (and (not (.exists (io/file (str tmp "/escape.bclj"))))
          (not (.exists (io/file (str tmp "/escape2.bclj"))))))

;; ============================================================================
;; E. AUTHORIZED VERBS STILL WORK — a REAL set-body through the restricted
;;    surface (warm :edit-min via the strict-fenced coordinator).
;; ============================================================================
(if-not beagle-ok?
  (println "E: SKIP — beagle/racket prerequisites missing (restricted denial + fence fully proven above)")
  (let [{:keys [by-id]} (run-mcp good-env
                                 [init-req
                                  (call-req 30 "set-body"
                                            {:module "schema" :name "cardinality" :body "\"single\""})])
        r (get by-id 30) t (or (rtext r) "")
        rendered (str src-dir "/schema.bclj")]
    (chk "E: set-body on the ingested module SUCCEEDS under graph-edit-v1"
         (and (some? r) (not (rerr? r))))
    (chk "E: reply reports a committed warm graph edit" (str/includes? t "committed"))
    (chk "E: the code log GREW (AST delta committed through the coordinator)"
         (> (count (slurp code-log)) (count code-log-before)))
    (chk "E: rendered .bclj view landed INSIDE the source root with the new body"
         (and (.exists (io/file rendered))
              (str/includes? (slurp rendered) "\"single\"")))))

;; ---------------------------------------------------------------------------
(p/destroy-tree strict-daemon)
(p/destroy-tree perm-daemon)
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (if (empty? fails)
    (do (println (str "\nfram-mcp-profile: " (count cs) " / " (count cs)
                      " PASS — graph-edit-v1 is a server-authorized, fail-closed surface"))
        (p/shell {} "rm" "-rf" tmp))
    (do (println (str "\nfram-mcp-profile: " (count fails) " FAILED  (workspace left at " tmp ")"))
        (System/exit 1))))
