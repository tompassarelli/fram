;; ============================================================================
;; defcheck_gate.clj — the incremental def-level check primitive (adapter v2, A2).
;; ============================================================================
;; ONE callable the verb layer (A1's write-def) invokes per write. Signature,
;; agreed with A1 over the fact feed:
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

;; ============================================================================
;; UNTYPED MODE — the def-check path made usable on PLAIN Clojure (EXP-025).
;; ============================================================================
;; The typed Beagle checker rejects `(defn- f [x] …)` as "malformed defn- —
;; expected :- RET", so replaying a real (untyped) OSS repo through the graph arm
;; forced FRAM_DEFCHECK=0 and dropped the in-loop inner gate to apply+render
;; coherence only — semantic misses (calling a helper that does not exist at C0,
;; obvious arity mismatch) surfaced ONLY at the oracle (a full suite run).
;;
;; This mode restores a real inner gate for untyped modules with checks that pay
;; rent on plain Clojure, in-process, sub-second warm (NO beagle type-check spawn):
;;   (1) the module's rendered text READS and the target is a recognized def shape;
;;   (2) every FREE symbol RESOLVES — against the module's own defs, its :require
;;       aliases/refers, clojure.core, and java-interop patterns. An unresolved
;;       symbol is the #1 semantic-miss class (a helper that is not defined at C0);
;;   (3) OBVIOUS arity mismatch against a call to an own-def whose fixed arities
;;       are known in-graph (skipped for variadic / unknown-arity defs).
;; Errors come back in the SAME adapter-v2 ERROR shape the S-profile already
;; renders. The analyzer is CONSERVATIVE by construction: it flags only what it is
;; confident is wrong (a false REJECTION would block the agent on valid code), so
;; every ambiguity resolves toward "accepted" — locals over-collected, interop and
;; namespaced refs assumed live, refer-:all suppressing unqualified-symbol reports.
;;
;; Dispatch: FRAM_DEFCHECK_MODE = auto (default) | typed | untyped. `auto` reads
;; the rendered module and picks untyped when it carries NO `:-` type annotation.
;; ============================================================================

;; --- mode detection ----------------------------------------------------------
(defn- source-typed? [src]
  ;; a `:-` return/param annotation token — the sole discriminator of a typed
  ;; Beagle module. Real Clojure never carries a bare ` :- ` token.
  (boolean (re-find #"(?m)(^|[\s(\[]):-(\s|$)" (str src))))

(defn- untyped-mode? [src]
  (case (or (System/getenv "FRAM_DEFCHECK_MODE") "auto")
    "typed"   false
    "untyped" true
    (not (source-typed? src))))

;; --- render module -> Clojure text (warm, via the sidecar EDN->text render) --
(defn- module-src-text!
  "Refresh `module`'s sibling .bclj (warm EDN->text) and return its text."
  [module]
  (refresh-sibling! module)
  (slurp (src-path module)))

;; --- reading the rendered text into datum forms ------------------------------
(defn- read-forms
  "Read every top-level form from `src`. Returns {:forms [...] :read-error msg?}.
  Permissive: reader conditionals allowed (:clj branch), unknown tagged literals
  pass their value through, read-eval disabled. A mid-stream read failure stops
  and reports (the target def, if unreadable, is a real check-1 failure)."
  [src]
  (let [rdr  (java.io.PushbackReader. (java.io.StringReader. (str src)))
        opts {:read-cond :allow :features #{:clj} :eof ::eof}]
    (binding [*read-eval* false
              *default-data-reader-fn* (fn [_tag v] v)]
      (loop [acc []]
        (let [f (try (read opts rdr)
                     (catch Throwable t {::read-error (or (.getMessage t) (str (class t)))}))]
          (cond
            (and (map? f) (::read-error f)) {:forms acc :read-error (::read-error f)}
            (= f ::eof)                     {:forms acc}
            :else                           (recur (conj acc f))))))))

;; --- static symbol tables ----------------------------------------------------
(def ^:private special-forms
  '#{def if do let let* fn fn* loop loop* recur throw try catch finally quote var
     monitor-enter monitor-exit new set! . & deftype* reify* case* letfn* import*
     clojure.core/import* unquote unquote-splicing})

(def ^:private core-names
  (delay (into #{} (map (comp symbol name)) (keys (ns-publics 'clojure.core)))))

(def ^:private def-heads
  ;; recognized top-level def shapes (check 1)
  '#{def defn defn- defonce def- defmacro definline defmulti defmethod deftype
     defrecord defprotocol declare defstruct extend-type extend-protocol extend})

;; Arity checking is DISABLED inside threading macros: a stage like `(f a)` under
;; ->> is really `(f a threaded)`, so the literal arg count lies. Resolution still
;; runs; only the arity gate is suppressed (arity is the "obvious" tier, and a
;; false rejection on valid threaded code is the cardinal failure to avoid).
(def ^:private ^:dynamic *arity-check?* true)
(def ^:private thread-heads
  '#{-> ->> some-> some->> cond-> cond->> as-> doto})

(def ^:private binding-vec-heads
  ;; (head [pat expr pat expr …] body…) — every pair pat introduces locals
  '#{let let* loop loop* binding when-let if-let when-some if-some when-first
     with-open with-local-vars with-redefs dotimes})

;; --- destructuring: symbols a binding pattern introduces ---------------------
(declare ^:private pattern-locals)
(defn- pattern-locals [pat]
  (cond
    (symbol? pat) (if (= pat '&) #{} #{pat})
    (vector? pat) (into #{} (mapcat pattern-locals) pat)
    (map? pat)    (reduce-kv
                    (fn [acc k v]
                      (cond
                        (= k :keys)   (into acc (map (comp symbol name)) v)
                        (= k :strs)   (into acc (map (comp symbol name)) v)
                        (= k :syms)   (into acc (map (comp symbol name)) v)
                        (= k :as)     (conj acc v)
                        (= k :or)     acc                         ; defaults, not names
                        (keyword? k)  (into acc (pattern-locals k)) ; ns'd :keys etc
                        :else         (into acc (pattern-locals k)))) ; {sym expr}
                    #{} pat)
    :else #{}))

(defn- arglist-locals [params] (into #{} (mapcat pattern-locals) (remove #{'&} params)))

;; --- own-def collection (names + call arities) -------------------------------
(defn- fn-arities
  "Given a defn/fn tail (after the name), return {:fixed #{n…} :variadic min|nil}."
  [tail]
  (let [tail   (cond->> tail (string? (first tail)) (drop 1))      ; docstring
        tail   (cond->> tail (map? (first tail))    (drop 1))      ; attr-map
        bodies (cond
                 (vector? (first tail))               [(first tail)]
                 (and (seq? (first tail))
                      (vector? (ffirst tail)))        (map first tail)
                 :else                                 nil)]
    (when (seq bodies)
      (reduce (fn [acc params]
                (let [amp   (.indexOf ^java.util.List (vec params) '&)
                      fixed (if (neg? amp) (count params) amp)]
                  (if (neg? amp)
                    (update acc :fixed conj fixed)
                    (update acc :variadic (fnil min fixed) fixed))))
              {:fixed #{} :variadic nil} bodies))))

(defn- collect-defs
  "Walk top-level forms → {:names #{sym…} :arities {sym {:fixed.. :variadic..}}}.
  Names cover def/defn/deftype/defrecord (+ ->Ctor/map->Ctor/Ctor.), defprotocol
  method names, declare, defmulti. Arities only for fn-shaped defs."
  [forms]
  (reduce
    (fn [acc form]
      (if-not (and (seq? form) (symbol? (first form))) acc
        (let [h    (first form)
              nm   (when (>= (count form) 2) (first (rest form)))
              base (fn [a s] (if (symbol? s) (update a :names conj (symbol (name s))) a))]
          (cond
            ('#{defn defn- defmacro definline} h)
            (let [nm* (symbol (name nm))]
              (-> acc (base nm) (assoc-in [:arities nm*] (fn-arities (drop 2 form)))))

            ('#{def defonce def- defstruct defmulti} h) (base acc nm)

            (= 'declare h) (reduce base acc (rest form))

            ('#{deftype defrecord} h)
            (-> acc (base nm)
                (update :names into (when (symbol? nm)
                                      [(symbol (str "->" (name nm)))
                                       (symbol (str "map->" (name nm)))
                                       (symbol (str (name nm) "."))])))

            (= 'defprotocol h)
            (reduce (fn [a sig] (if (and (seq? sig) (symbol? (first sig)))
                                  (base a (first sig)) a))
                    (base acc nm) (drop 2 form))

            :else acc))))
    {:names #{} :arities {}} forms))

;; --- ns form → aliases / refers / imports / refer-all ------------------------
(defn- parse-require-spec [acc spec]
  (cond
    (symbol? spec) (update acc :ns-names conj spec)
    (vector? spec)
    (let [nsym (first spec)
          opts (when (and (keyword? (second spec)) (even? (count (rest spec))))
                 (apply hash-map (rest spec)))]     ; nil for prefix libspecs (safe)
      (cond-> (update acc :ns-names conj nsym)
        (get opts :as)                  (update :aliases conj (get opts :as))
        (get opts :as-alias)            (update :aliases conj (get opts :as-alias))
        (= :all (get opts :refer))      (assoc :refer-all? true)
        (sequential? (get opts :refer)) (update :refers into (get opts :refer))))
    :else acc))

(defn- parse-ns-env
  "Extract {:aliases #{} :refers #{} :ns-names #{} :imports #{class-syms}
  :refer-all? bool} from the module's forms (ns form + top-level require/use/import)."
  [forms]
  (let [empty' {:aliases #{} :refers #{} :ns-names #{} :imports #{} :refer-all? false}
        add-import (fn [acc spec]
                     (cond
                       (symbol? spec)  (update acc :imports conj (symbol (peek (str/split (name spec) #"\."))))
                       (sequential? spec) (update acc :imports into (map #(symbol (name %)) (rest spec)))
                       :else acc))
        handle-clause
        (fn [acc clause]
          (if-not (seq? clause) acc
            (let [k (first clause)]
              (cond
                (#{:require :require-macros} k)
                (reduce parse-require-spec acc (rest clause))
                (= :use k)  (assoc acc :refer-all? true)
                (= :import k) (reduce add-import acc (rest clause))
                :else acc))))]
    (reduce
      (fn [acc form]
        (if-not (and (seq? form) (symbol? (first form))) acc
          (case (first form)
            ns      (reduce handle-clause acc (drop 2 form))
            require (reduce parse-require-spec acc (map #(if (and (seq? %) (= 'quote (first %))) (second %) %) (rest form)))
            use     (assoc acc :refer-all? true)
            import  (reduce add-import acc (rest form))
            acc)))
      empty' forms)))

;; --- nearest-name suggestion (Levenshtein over the candidate set) ------------
(defn- lev [a b]
  (let [a (str a) b (str b) m (count a) n (count b)]
    (if (or (zero? m) (zero? n)) (max m n)
      (loop [i 1 prev (vec (range (inc n)))]
        (if (> i m) (peek prev)
          (recur (inc i)
                 (reduce (fn [cur j]
                           (conj cur (if (= (.charAt a (dec i)) (.charAt b (dec j)))
                                       (nth prev (dec j))
                                       (inc (min (nth prev j) (peek cur) (nth prev (dec j)))))))
                         [i] (range 1 (inc n)))))))))

(defn- nearest [sym candidates]
  (let [s (name sym)
        thr (max 2 (quot (count s) 3))]
    (->> candidates
         (map (fn [c] [c (lev s (name c))]))
         (filter (fn [[_ d]] (<= d thr)))
         (sort-by second)
         (take 2)
         (mapv (comp str first)))))

;; --- the free-symbol + arity walker ------------------------------------------
(defn- anon-arg? [s] (boolean (re-matches #"%\d*|%&" (name s))))
(defn- interop-sym? [s]
  (let [n (name s)]
    (or (str/starts-with? n ".")     ; .method / .-field
        (str/ends-with? n ".")       ; Ctor.
        (str/includes? n "/")        ; ns/name or Class/static
        (str/includes? n "."))))     ; fully-qualified class/pkg (java.io.File)
(defn- class-like? [s] (boolean (re-matches #"[A-Z].*" (name s))))

(defn- qualifier-known? [env s]
  (let [q (namespace s)]
    (or (nil? q)
        (contains? (:aliases env) (symbol q))
        (contains? (:ns-names env) (symbol q))
        (contains? (:imports env) (symbol q))
        (str/includes? q ".")                 ; fully-qualified ns / package
        (re-matches #"[A-Z].*" q))))          ; Class/static interop

(defn- resolved-sym? [env defs locals s]
  (or (contains? locals s)
      (= s '&) (anon-arg? s)
      (if (namespace s)
        (qualifier-known? env s)
        (let [n (symbol (name s))]
          (or (contains? special-forms n)
              (contains? (:names defs) n)
              (contains? (:refers env) n)
              (contains? @core-names n)
              (interop-sym? s)
              (class-like? s))))))

(defn- unresolved-error [module def-name env defs s]
  (let [cands (into (vec (:names defs)) (:refers env))]
    {:ok false :stage :type
     :at (cond-> {:module module} def-name (assoc :def def-name))
     :message (str "unresolved symbol `" s "` in " def-name
                   " — not a local, an own def, a :require refer/alias, clojure.core, or interop")
     :got (str s)
     :suggestion "define it, require/refer it, or fix the name"
     :nearest (nearest s cands)
     :kind "unresolved-symbol"}))

(defn- arity-error [module def-name f n arities]
  (let [{:keys [fixed variadic]} arities]
    {:ok false :stage :type
     :at (cond-> {:module module} def-name (assoc :def def-name))
     :message (str "arity mismatch: `" f "` called with " n " arg(s), but is defined for "
                   (str/join "/" (sort fixed)) (when variadic (str " (or " variadic "+)")))
     :expected (str/join "/" (sort fixed))
     :got (str n)
     :suggestion (str "call `" f "` with " (str/join " or " (sort fixed)) " argument(s)")
     :nearest [(str f)]
     :kind "arity-mismatch"}))

(defn- walk-fn-tail
  "Walk a fn/defn tail (`([params] body…)` or `(([p] b)…)`), seeding each arity's
  params as locals. `rec` recurses a subform, `ls0` is the enclosing scope."
  [rec ls0 tail]
  (let [tail (if (string? (first tail)) (rest tail) tail)     ; docstring
        tail (if (map? (first tail))    (rest tail) tail)]    ; attr-map
    (cond
      (vector? (first tail))
      (let [ls (into ls0 (arglist-locals (first tail)))]
        (doseq [b (rest tail)] (rec b ls)))
      :else
      (doseq [a tail :when (and (seq? a) (vector? (first a)))]
        (let [ls (into ls0 (arglist-locals (first a)))]
          (doseq [b (rest a)] (rec b ls)))))))

(defn- walk-body
  "Analyze `form` for free-symbol + arity errors, threading lexical `locals`.
  Appends ERROR maps to the `errs` atom. Conservative: unknown binding forms
  over-collect locals (suppress, never false-flag)."
  [module def-name env defs errs form locals]
  (let [rec  (fn [f ls] (walk-body module def-name env defs errs f ls))
        emit (fn [e] (swap! errs conj e))
        classify (fn [s ls]
                   (when (and (symbol? s) (not (:refer-all? env))
                              (not (resolved-sym? env defs ls s)))
                     (emit (unresolved-error module def-name env defs s))))]
    (cond
      (symbol? form) (classify form locals)

      (or (vector? form) (set? form)) (doseq [x form] (rec x locals))
      (map? form) (doseq [x (mapcat identity form)] (rec x locals))

      (seq? form)
      (let [h (first form)]
        (cond
          (empty? form) nil
          (= 'quote h)  nil
          (#{'var} h)   nil

          ;; threading macros — resolve symbols, but suppress arity (stage arg
          ;; counts are rewritten by the macro). as-> also introduces a name.
          (= 'as-> h)
          (binding [*arity-check?* false]
            (rec (second form) locals)
            (let [ls (into locals (when (symbol? (nth form 2 nil)) [(nth form 2)]))]
              (doseq [b (drop 3 form)] (rec b ls))))
          (contains? thread-heads h)
          (binding [*arity-check?* false] (doseq [x (rest form)] (rec x locals)))

          ;; def-heads (top-level or nested): skip the name, walk the value/body
          (#{'defn 'defn- 'defmacro 'definline} h)
          (walk-fn-tail rec locals (drop 2 form))
          (#{'def 'defonce 'def-} h)
          (doseq [b (drop 2 form)] (rec b locals))
          (#{'defmulti 'defstruct 'declare} h) nil

          ;; fn / fn* — optional self-name + one-or-more (params body) arities
          (#{'fn 'fn*} h)
          (let [named? (symbol? (second form))
                ls0    (into locals (when named? [(second form)]))
                tail   (if named? (drop 2 form) (drop 1 form))]
            (walk-fn-tail rec ls0 tail))

          ;; let-family: (head [pat e pat e …] body…)
          (contains? binding-vec-heads h)
          (let [bvec (second form)
                [ls _] (reduce (fn [[ls _] [pat e]]
                                 (rec e ls)
                                 [(into ls (pattern-locals pat)) nil])
                               [locals nil] (partition 2 (if (vector? bvec) bvec [])))]
            (doseq [b (drop 2 form)] (rec b ls)))

          ;; for / doseq — bindings with :let/:when/:while modifiers
          (#{'for 'doseq} h)
          (let [bvec (if (vector? (second form)) (second form) [])
                ls (loop [pairs (partition 2 bvec) ls locals]
                     (if-let [[k v] (first pairs)]
                       (cond
                         (= k :let)   (recur (rest pairs)
                                             (reduce (fn [l [p e]] (rec e l) (into l (pattern-locals p)))
                                                     ls (partition 2 (if (vector? v) v []))))
                         (#{:when :while} k) (do (rec v ls) (recur (rest pairs) ls))
                         :else        (do (rec v ls) (recur (rest pairs) (into ls (pattern-locals k)))))
                       ls))]
            (doseq [b (drop 2 form)] (rec b ls)))

          ;; letfn — (letfn [(name [params] body)…] body)
          (= 'letfn h)
          (let [fspecs (if (vector? (second form)) (second form) [])
                fnames (into #{} (keep #(when (and (seq? %) (symbol? (first %))) (first %))) fspecs)
                ls0 (into locals fnames)]
            (doseq [fs fspecs]
              (when (and (seq? fs) (vector? (second fs)))
                (let [ls (into ls0 (arglist-locals (second fs)))]
                  (doseq [b (drop 2 fs)] (rec b ls)))))
            (doseq [b (drop 2 form)] (rec b ls0)))

          ;; catch — (catch Class e body)  [Class + e are not free refs]
          (= 'catch h)
          (let [ls (into locals (when (symbol? (nth form 2 nil)) [(nth form 2)]))]
            (doseq [b (drop 3 form)] (rec b ls)))

          ;; interop new / dot — skip the class/member head token, walk the rest
          (#{'new '. '.. 'set! 'monitor-enter 'monitor-exit} h)
          (doseq [x (rest form)] (when-not (symbol? x) (rec x locals)))

          ;; defmethod — (defmethod multi dispatch-val [params] body…)
          (= 'defmethod h)
          (let [after (drop 2 form)]                 ; drop `defmethod` + multi name
            (rec (first after) locals)               ; dispatch value (may ref syms)
            (let [pv (second after)]
              (when (vector? pv)
                (let [ls (into locals (arglist-locals pv))]
                  (doseq [b (drop 2 after)] (rec b ls))))))

          ;; method-bearing forms (deftype/defrecord/reify/extend-*/proxy): a
          ;; child `(mname [params] body…)` is a METHOD IMPLEMENTATION — mname is
          ;; NOT a call and protocol/class-name children are NOT refs. Walk only
          ;; method BODIES (params as locals); skip heads + bare type names.
          (#{'deftype 'defrecord 'reify 'extend-type 'extend-protocol 'proxy 'definterface} h)
          ;; deftype/defrecord FIELDS (the vector after the name) are in scope in
          ;; every method body — seed them as locals alongside each method's params.
          (let [fields (nth form 2 nil)                ; (deftype Name [fields] …)
                base   (into locals
                             (when (and (#{'deftype 'defrecord} h) (vector? fields))
                               (arglist-locals fields)))]
            (doseq [x (rest form)]
              (cond
                (and (seq? x) (vector? (second x)))   ; method form
                (let [ls (into base (arglist-locals (second x)))]
                  (doseq [b (drop 2 x)] (rec b ls)))
                (vector? x) (doseq [e x] (when (coll? e) (rec e base)))  ; supers/ctor-args
                :else nil)))                          ; bare type/protocol name → skip

          ;; ordinary call / unknown macro
          :else
          (do
            ;; head: resolve + arity-check when it's a call to a known own-def
            (when (symbol? h)
              (classify h locals)
              (when-let [ar (and (not (contains? locals h))
                                 (get-in defs [:arities (symbol (name h))]))]
                (let [n (count (rest form))
                      {:keys [fixed variadic]} ar]
                  (when (and *arity-check?* (seq fixed)
                             (not (contains? fixed n))
                             (or (nil? variadic) (< n variadic)))
                    (emit (arity-error module def-name h n ar))))))
            ;; unknown-macro guard: over-collect locals from direct child vectors
            ;; so a custom binding macro never yields a false unresolved report.
            (let [child-locals (into locals (mapcat (fn [x] (when (vector? x) (arglist-locals x))) (rest form)))]
              (doseq [x (rest form)] (rec x child-locals))))))
      :else nil)))

(defn- def-target-name [form]
  (when (and (seq? form) (>= (count form) 2))
    (let [nm (first (rest form))]
      (cond (symbol? nm) (symbol (name nm))
            (and (seq? nm) (symbol? (second nm))) (symbol (name (second nm)))
            :else nil))))

(defn- analyze-untyped-module
  "In-process untyped def-check for `module` from rendered Clojure `src`.
  Returns a vector of ERROR-shape maps (empty when clean)."
  [module src]
  (let [{:keys [forms read-error]} (read-forms src)]
    (if read-error
      [{:ok false :stage :parse :at {:module module}
        :message (str "module did not read as Clojure: " read-error)
        :kind "read-error"
        :suggestion "fix the malformed form so the module parses"}]
      (let [env  (parse-ns-env forms)
            defs (collect-defs forms)
            errs (atom [])]
        (doseq [form forms
                :when (and (seq? form) (symbol? (first form))
                           (contains? def-heads (first form)))]
          ;; walk-body seeds params per-arity and, when the module has a
          ;; :refer :all / :use, suppresses unresolved-symbol reports (unknowable
          ;; names) while keeping parse + arity checks. Documented limitation.
          (walk-body module (def-target-name form) env defs errs form #{}))
        @errs))))

;; --- mode-dispatching check --------------------------------------------------
(defn- check-module-errors-any
  "Render `module` to text once; route to the untyped in-process analyzer (no
  beagle type-check) when the module carries no `:-` annotation (or the env forces
  it), else the typed sidecar checker. Returns a vector of ERROR-shape maps."
  [module]
  (let [src (module-src-text! module)]
    (if (untyped-mode? src)
      (analyze-untyped-module module src)
      (check-module-errors module))))

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
    (let [errs (check-module-errors-any module)]
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
    (let [all (vec (mapcat check-module-errors-any (live-modules)))]
      (when (seq all)
        (assoc (first all) :stage :gate
               :errors (mapv #(assoc % :stage :gate) all))))
    (catch Exception e
      {:ok false :stage :gate
       :message (str "whole-tree-check unavailable: " (.getMessage e))})))
