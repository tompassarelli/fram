;; ============================================================================
;; cnf_defcheck.clj — the incremental def-level check primitive (adapter v2, A2).
;; ============================================================================
;; ONE callable the verb layer (A1's write-def) invokes per write. Signature,
;; agreed with A1 over the claim feed:
;;
;;     (check-def module name) -> nil            ; the module type-checks
;;                             -> error-map       ; adapter-v2 ERROR shape
;;
;; It is the FAST INNER LOOP that replaces the 40-90s whole-tree gate for
;; per-edit feedback. Profiling (docs/private/a2-profile-2026-07-03.md) proved
;; that 40-90s is ~100% racket PROCESS-SPAWN overhead, not type-check compute;
;; so the check runs against a PERSISTENT warm checker (bin/fram-defcheck-server)
;; that pays the ~5s beagle-compiler load once and then checks in ~50ms.
;;
;; Flow (all in-process except two loopback line-RPCs):
;;   1. coord :render module  -> resolved EDN triples (the daemon's warm render).
;;   2. write triples to <gwdir>/.edn/<module>.edn.
;;   3. sidecar `render` (warm) refreshes <gwdir>/<module>.bclj so SIBLINGS
;;      resolve this module's current signatures.
;;   4. sidecar `check` type-checks the module's EDN against the primed sibling
;;      gwdir (full cross-module fidelity) -> beagle diagnostics as JSON.
;;   5. map diagnostics -> ERROR shape; return the def's error (or first), nil if clean.
;;
;; Authority split (adapter-v2 spec gap 3): this catches def-level errors in the
;; EDITED module against the CACHED sibling environment. A def that checks alone
;; but breaks a caller in ANOTHER module is caught by the whole-tree gate at
;; promotion (:stage :gate) — NOT here. That is by design, not a gap.
;;
;; Decoupled from A1: the only contract is the ERROR shape below + the fn
;; signature. Coordinator/sidecar reached over sockets; ports are dynamic vars.
;; ============================================================================
(ns fram.defcheck
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [cheshire.core :as json]))

;; --- configuration (dynamic; A1 binds *coord-port* to the live serving port) --
(defn- env-int [k d] (or (some-> (System/getenv k) Integer/parseInt) d))
(def ^:dynamic *coord-port*   (env-int "FRAM_PORT" 7977))
(def ^:dynamic *sidecar-port* (env-int "FRAM_DEFCHECK_PORT" 49060))
(def ^:dynamic *gwdir*        nil)   ; sibling-resolution dir; defaults per coord-port
(def ^:dynamic *autostart?*   true)  ; boot the sidecar on demand if it is down

;; Injection seams — A1 (in-process, inside the coordinator) can bind these to
;; the coordinator's OWN render / module-list fns and skip the self-socket
;; entirely. Default: a loopback call to *coord-port* (the fram-render-code path).
(def ^:dynamic *render-fn*  nil)     ; (fn [module] -> edn-triples-string) | nil => socket :render
(def ^:dynamic *modules-fn* nil)     ; (fn [] -> [module …])              | nil => socket :srcs

(def ^:private beagle-home (or (System/getenv "BEAGLE_HOME") (str (System/getProperty "user.home") "/code/beagle")))
(def ^:private repo-root   (or (System/getenv "FRAM_HOME")   (System/getProperty "user.dir")))

;; --- loopback line-RPC (one request line, one response line) -----------------
(defn- rpc [port line]
  (with-open [s (java.net.Socket.)]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 3000)
    (let [w (io/writer (.getOutputStream s))
          r (io/reader (.getInputStream s))]
      (.write w (str line "\n")) (.flush w)
      (.readLine r))))

(defn- coord [req]   (edn/read-string (rpc *coord-port* (pr-str req))))
(defn- sidecar [line] (json/parse-string (rpc *sidecar-port* line) true))

;; --- sidecar lifecycle -------------------------------------------------------
(defn- sidecar-up? []
  (try (:ok (sidecar "ping")) (catch Exception _ false)))

(defn ensure-sidecar!
  "Ping the warm checker; if down and *autostart?*, launch bin/fram-defcheck and
  block (≤30s) until it announces ready. Idempotent. Returns true when up."
  []
  (or (sidecar-up?)
      (when *autostart?*
        (let [launcher (str repo-root "/bin/fram-defcheck")]
          ;; launcher takes the port as $1 — no env plumbing needed. setsid so the
          ;; warm checker outlives this caller (the coordinator spawns it once).
          (-> (ProcessBuilder. ["setsid" launcher (str *sidecar-port*)])
              (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
              (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
              (.start))
          (loop [n 0]
            (cond (sidecar-up?)  true
                  (>= n 60)      (throw (ex-info "fram-defcheck sidecar failed to start" {:port *sidecar-port*}))
                  :else          (do (Thread/sleep 500) (recur (inc n)))))))))

;; --- gwdir (primed sibling .bclj files for cross-module resolution) ----------
(defn- gwdir [] (or *gwdir* (str (System/getProperty "java.io.tmpdir") "/fram-defcheck-gw-" *coord-port*)))
(defn- src-path  [module] (str (gwdir) "/" module ".bclj"))
(defn- edn-path  [module] (str (gwdir) "/.edn/" module ".edn"))

(defn- live-modules
  "The live module set. Uses *modules-fn* if bound (in-process); else reads the
  stable :srcs the coordinator attaches to a render-miss (`:index` is A1's WIP)."
  []
  (if *modules-fn*
    (vec (*modules-fn*))
    (let [r (coord {:op :render :module "__nonexistent__"})]
      (vec (or (:srcs r) [])))))

(defn- render-edn!
  "Render module -> triples string, written to edn-path. Returns the path. Uses
  *render-fn* if bound (the coordinator's in-process render), else coord :render."
  [module]
  (let [edn (if *render-fn*
              (*render-fn* module)
              (let [resp (coord {:op :render :module module})]
                (when (:error resp) (throw (ex-info (str "render failed for " module ": " (:error resp)) {:module module})))
                (:edn resp)))]
    (when (str/blank? (str edn))
      (throw (ex-info (str "render returned no source for " module) {:module module})))
    (let [p (edn-path module)]
      (io/make-parents (io/file p))
      (spit p edn)
      p)))

(defn- refresh-sibling!
  "Render `module` fresh (coord :render) to its EDN, and warm EDN->text via the
  sidecar to <gwdir>/<module>.bclj — so OTHER modules resolve this module's
  CURRENT signatures on their next check. Returns the EDN path. The .bclj write
  is best-effort (a stale sibling only weakens cross-module fidelity, never
  corrupts a check)."
  [module]
  (let [epath (render-edn! module)]
    (io/make-parents (io/file (src-path module)))
    (try (sidecar (str "render " epath " " (src-path module))) (catch Exception _ nil))
    epath))

(defn prime-gwdir!
  "Populate <gwdir> with a .bclj for every live module (warm, via the sidecar).
  Idempotent + cheap to repeat. Call once when an arena's coordinator comes up
  (and internally by whole-tree-check, so cross-refs resolve current text)."
  []
  (ensure-sidecar!)
  (doseq [m (live-modules)]
    (try (refresh-sibling! m) (catch Exception _ nil)))
  (gwdir))

;; --- diagnostic (beagle JSON) -> adapter-v2 ERROR shape ----------------------
;; beagle diagnostic->json fields: kind file line col message + folded details
;; (expected/actual/expected-type/actual-type/name/error-line/error-code/fix_plan…).
(def ^:private parse-kinds #{"parse-error" "read-error" "reader" "syntax" "structural"})

(defn- diag->error [module diag]
  (let [kind      (:kind diag)
        stage     (if (contains? parse-kinds (str kind)) :parse :type)
        def-name  (:name diag)
        line      (or (:error-line diag) (:line diag))
        expected  (:expected diag)
        got       (:actual diag)
        msg       (str/replace (str (:message diag)) #"^beagle:\s*" "")
        fix       (:fix_plan diag)
        suggestion (cond
                     (map? fix)    (or (:suggestion fix) (:message fix) (:summary fix))
                     (string? fix) fix
                     (and expected got) (str "make it a " expected " (currently " got ")"))
        nearest   (:nearest diag)]
    (cond-> {:ok false
             :stage stage
             :at (cond-> {:module module}
                   def-name (assoc :def def-name)
                   line     (assoc :line line))
             :message msg}
      expected   (assoc :expected expected)
      got        (assoc :got got)
      suggestion (assoc :suggestion suggestion)
      (seq nearest) (assoc :nearest (vec nearest))
      kind       (assoc :kind kind)
      (:error-code diag) (assoc :error-code (:error-code diag)))))

(defn- pick-primary
  "The error to surface as the single return value: prefer one on the def just
  written, else the first. Full list travels in :errors."
  [name errs]
  (or (first (filter #(= name (get-in % [:at :def])) errs))
      (first errs)))

;; --- shared check path -------------------------------------------------------
(defn- check-module-errors
  "Render `module` fresh and type-check it against the primed sibling gwdir.
  Returns a vector of ERROR-shape maps (empty when clean). Refreshes this
  module's own sibling text so later cross-module checks see it."
  [module]
  (let [epath (refresh-sibling! module)
        resp  (sidecar (str "check " epath " " (src-path module)))]
    (if-not (:ok resp)
      [{:ok false :stage :type :at {:module module}
        :message (str "def-check infra error: " (:error resp))}]
      (mapv #(diag->error module %) (:errors resp)))))

;; --- THE PRIMITIVE (deliverable 3 — A1 resets this into def-check-hook) -------
(defn check-def
  "Incremental def-level type check. Returns nil when `module` type-checks
  against its cached sibling environment, else the adapter-v2 ERROR shape for
  the offending def (preferring `name`), with the full diagnostic list under
  :errors. Never throws for a type error — only for infra faults (coordinator or
  sidecar unreachable), which surface as {:ok false :stage :type :message …}.

  AUTHORITY: catches errors IN `module` (the edited def + its use of siblings).
  A sibling in ANOTHER module that calls a now-broken `name` is NOT re-checked
  here — that is whole-tree-check's job (adapter-v2 spec gap 3, deliverable 4b)."
  [module name]
  (try
    (ensure-sidecar!)
    (let [errs (check-module-errors module)]
      (when (seq errs) (assoc (pick-primary name errs) :errors errs)))
    (catch Exception e
      {:ok false :stage :type :at {:module module :def name}
       :message (str "def-check unavailable: " (.getMessage e))})))

;; --- the authority split's other half: the whole-tree gate -------------------
(defn whole-tree-check
  "Type-check EVERY live module against the whole (refreshed) tree and aggregate.
  nil when the tree is clean, else {:ok false :stage :gate …} with the first
  offending diagnostic promoted to the top level and ALL diagnostics under
  :errors (each tagged :stage :gate). This is where a def that checks alone but
  breaks a caller in another module is caught — the authoritative pre-promotion
  gate the S-profile `check {}` verb calls. Still warm (N × ~50ms), because it
  reuses the persistent checker; the harness's build-all remains the final
  byte-level acceptance oracle at commit."
  []
  (try
    (ensure-sidecar!)
    (prime-gwdir!)                                    ; every sibling's text current first
    (let [all (vec (mapcat check-module-errors (live-modules)))]
      (when (seq all)
        (assoc (first all) :stage :gate
               :errors (mapv #(assoc % :stage :gate) all))))
    (catch Exception e
      {:ok false :stage :gate
       :message (str "whole-tree-check unavailable: " (.getMessage e))})))
