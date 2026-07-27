#!/usr/bin/env bb
;; ============================================================================
;; Turtle #5 — the identity steal: references store IDENTITY, not spelling.
;; ============================================================================
;; A real LEXICAL resolver adds `refers_to <binding-node-id>` to each reference,
;; pointing it at the *correct* binding under shadowing (nearest enclosing scope),
;; not merely a binding with the right spelling. Binding occurrence = a symbol
;; leaf: a top-level def name, a fn/defn param, or a let/loop binding.
;;
;; With references carrying identity:
;;   - rename is O(1): edit ONE binding's name; references render via refers_to.
;;   - rename is EXACT under shadowing: a shadowed inner `red` is a different node
;;     than the outer def, so renaming one leaves the other alone — which text
;;     (sed) structurally cannot do.
;;   - "orphaned reference after delete" is a query (refers_to → a dead node).
;;
;;   bb -cp ~/code/fram/out src/resolve.clj resolve <edn>...
;;   bb -cp ~/code/fram/out src/resolve.clj rename <old> <new> <target> <edn>...
;;   bb -cp ~/code/fram/out src/resolve.clj delete <name> <target> <edn>...
;; ============================================================================
(ns resolve
  (:require [clojure.edn :as edn] [clojure.string :as str] [fram.store :as c]
            [fram.datalog :as d] [cheshire.core :as json]   ; datalog+json: the `callgraph` mode
            [resolve-core :as rc]    ; M1 Cut A: the CRDT order-key algebra + form vocabulary, in Beagle
            [resolve-read :as rr]    ; M1 Cut B: the view-relative read layer + ordered-tree navigation, in Beagle
            [resolve-binds :as rb]   ; M1 Cut C: what a binding form binds (patterns, params, let/for vectors)
            [resolve-modules :as rm] ; M1 Cut D: one module's frame + its import/export surface
            [resolve-render :as rv]  ; M1 Cut E: render a node back to source + the anchor search
            [resolve-query :as rq]   ; M1 Cut F: the code queries — call graph, blast closure, dead private
            [resolve-walk :as rw]    ; M1 Cut G: the lexical walk — every reference to its nearest binding
            [resolve-mint :as rmi]   ; M1 Cut I: the mint/author layer — a datum enters the store as facts
            [resolve-verbs :as rvb]))  ; M1 Cut H: the authoring verbs — an edit is a fact operation

(def mode (first *command-line-args*))
;; --- bound resolution state (DYNAMIC, inert root) ---------------------------
;; Every piece of computed resolution state lives in a dynamic var with an INERT
;; root binding (nil / empty atom). `resolve-edn!` rebinds them all to a FRESH
;; store before loading EDN, so the resolver runs over an ARBITRARY bound store
;; (a daemon's warm in-memory store) — not a load-time global. The CLI path is
;; byte-identical: it just calls `resolve-edn!` inside the same binding scope and
;; reads the bound vars exactly as before. Predicate/marker VALUE IDS are
;; store-local (cnf interns ids per store), so they MUST be recomputed against
;; the fresh store and are dynamic too — keeping a root-store value id would write
;; a foreign id into store B (the load-bearing seam GATE B guards).
(def ^:dynamic ctx nil)
(def ^:dynamic tx  nil)
(def ^:dynamic SUP nil)
;; *reject!* — how a verb signals an UNACCEPTABLE edit (collision / no-capture /
;; nothing-to-do / shape violation). The CLI path (-main) wants a process exit code;
;; a LONG-LIVED daemon running the verb in-process must NOT die on a rejected edit —
;; it binds *reject!* to throw, converting the exit into a catchable signal. Default
;; = real exit (verbatim CLI behavior). Verb arms call (*reject!* code) — or
;; (*reject!* code detail) to hand the driver a structured disambiguation payload
;; (replace-in-body's candidates/:within remedy) — instead of (System/exit code), so
;; the same verb body serves both drivers. The default ignores the detail (the CLI
;; exits); the daemon binding threads it into the ex-info it throws so `handle`
;; surfaces the candidates to the model.
(def ^:dynamic *reject!* (fn [code & _] (System/exit code)))
;; *resolve-walk?* — does resolve-warm-store! run the whole-corpus lexical walk
;; (run-resolution!, ~the dominant verb-setup cost)? The walk WRITES refers_to over
;; every module. The MINIMAL-OP authoring path (daemon :edit-min) does NOT need that
;; walk: set-body/upsert-form mint/supersede AST facts and never read refers_to, and
;; rename's no-capture check reads refers_to that the daemon has ALREADY materialized
;; on the store (the clone inherits it) — so re-walking is pure waste AND would double
;; the inherited edges. Bound false by do-edit-min => corpus tables only, no walk. The
;; CLI/text path + cold materialize leave it true (verbatim whole-corpus resolution).
(def ^:dynamic *resolve-walk?* true)
;; *corpus-scope* — restrict corpus-from-store!'s EXPENSIVE per-module FRAME builds
;; (module-defs/types/accessors) to this set of module-name strings, while still
;; deriving the full src/module list + the cheap cross-module export/type/accessor
;; export tables for EVERY module. A verb that edits ONE module (set-body/upsert-form)
;; only reads its OWN module's frame (def-binding/form-for-victim), so building 11
;; modules' frames is waste. nil => full frames for every module (verbatim behavior;
;; rename, which walks consumers' require/frame tables cross-module, leaves it nil).
(def ^:dynamic *corpus-scope* nil)
;; *corpus-cache* — when bound (by the daemon), the module->entity-ids map to use INSTEAD of the
;; O(total) name-fact reduce in corpus-from-store!. The daemon maintains it incrementally (add the
;; commit's new named nodes to their module — O(delta)), so the per-verb corpus build drops from
;; O(total-app) to O(edited-module-frame). Valid because the verb's clone == the committed store at
;; clone time, which the cache reflects. nil => full reduce (cold path, reads, the CLI). Just the
;; `groups` map (module-src -> [entity-ids]); frames are still derived (scoped) from it.
(def ^:dynamic *corpus-cache* nil)
(def ^:dynamic file->ents (atom {}))

(defn load-edn [path]
  (let [lines (str/split-lines (slurp path))
        src   (-> (first (filter #(str/starts-with? % "@file") lines)) (subs 6))
        local (atom {})
        ent   (fn [lid] (or (@local lid)
                            (let [e (c/entity! ctx)] (swap! local assoc lid e)
                                 (swap! file->ents update src (fnil conj []) e) e)))]
    (doseq [line lines :when (str/starts-with? line "[")]
      (let [[s p o] (edn/read-string line)]
        (c/fact! ctx (ent s) (c/value! ctx p) (if (integer? o) (ent o) (c/value! ctx o)) tx)))
    src))

;; --- fact-graph accessors --------------------------------------------------
;; render-mode marker predicate value-ids — DYNAMIC, rebound (recomputed against
;; the fresh store) inside `resolve-edn!`; store-local ids must match their store.
(def ^:dynamic Vp nil) (def ^:dynamic KIND nil) (def ^:dynamic REFERS nil)
(def ^:dynamic BOUND nil)   ; #(a) the DURABLE identity edge `bound_to` (persisted; reference -> binding's @mod#int)
(def ^:dynamic FIXED nil) (def ^:dynamic QUAL nil)   ; render-mode markers
(def ^:dynamic CTOR nil)    ; a `->Name` auto-constructor ref: render `->` + the type's name
(def ^:dynamic ACC  nil)    ; a synth field accessor `<lower(Name)>-<field>`: stores the field
;; --- read-time path-selection: the "default-main view" -----------------------
;; Fram is view-relative (docs/VIEWS_AND_BRANCHES.md): a node's live (l,p) group MAY hold
;; more than one fact, and choosing one is a VIEW decision. Today there is exactly one view —
;; default-main — and its policy is "first live". `select-main-1` is THE explicit selection
;; point: it does NOT prove uniqueness, it SELECTS the default-main member of a possibly-multi
;; group. Routing the former bare `(first …)` take-firsts through it makes the selection named
;; rather than silently buried; when first-class views land (VIEWS_AND_BRANCHES §8) this is
;; where a `view` argument attaches (thread E: it now does, via `*view*`).
;; *view* — the read-side attach point first-class views land on (VIEWS_AND_BRANCHES §8;
;; thread E). nil = the privileged default-main view (today's only view): elect the whole
;; group, byte-identical to before. Bound to a view-subject NAME = a named branch overlay:
;; restrict the election to the cids that view selects ((view selects @cid) facts), so the
;; resolver renders a *branch's* line of the code, isolated from main and sibling branches.
(def ^:dynamic *view* nil)
;; M1 Cut B — the view-relative READ layer is now Beagle (src/resolve_read.bclj).
;; The ^:dynamic vars STAY here: coord_daemon.clj and tests/coord_*.clj `binding`
;; them by qualified name (resolve/ctx, resolve/*view*, ...), and a var cannot be
;; moved to another namespace without breaking that — a value alias would no
;; longer see the binding, and a :refer alias leaves resolve/ctx unresolvable
;; (qualified-symbol lookup is findInternedVar, which ignores referred vars). So
;; each name below is a one-line wrapper that reads the dynamic state and hands
;; it to the ported function explicitly. Docstrings live with the logic.
(defn view-cids      [v cids]  (rr/view-cids ctx v cids))
(defn select-main-1  [cids]    (rr/select-main-1 ctx *view* cids))
(defn select-causal-1 [cids]   (rr/select-causal-1 ctx *view* cids))

(defn pred-val [e pname] (rr/pred-val ctx *view* e pname))
(defn kind-of  [e] (rr/kind-of ctx *view* e))                           ; default-main kind of node e
(defn sym-val  [e] (rr/sym-val ctx *view* e))                           ; default-main spelling of a symbol
;; ---- CRDT order keys (#36): positions as DATA, insert-anywhere commute ----------
;; A child-position predicate is "f<path>~<tie>": path = logoot int-vector (dense — a
;; path strictly between any two always exists), tie = the child node's atomic name-int
;; (unique -> concurrent same-gap inserts get DISTINCT keys -> both land -> commute).
;; Compare by (path, tie). DUAL parser: also reads the OLD "f<int>" format (path
;; [(inc i)*STEP], tie 0) so the resolver keeps working during corpus migration.
;; Library confirmed standalone in cnf_ordkey_test.clj (Stage A, 12/12).
;; #36 CRDT ORDER KEYS — now Beagle (src/resolve_core.bclj), aliased here so every
;; call site in this file, in coord_daemon.clj and in tests/coord_crdt_*.clj keeps
;; its unqualified spelling. ord-parse returns a resolve-core/OrdKey record; it
;; answers :path and :tie exactly as the map it replaced did.
(def ORD-STEP rc/ORD-STEP)
(def ord-parse rc/ord-parse)
(def ord-pos? rc/ord-pos?)
(def ord-str rc/ord-str)
(def ord-veccmp rc/ord-veccmp)
(def ord-cmp rc/ord-cmp)
(def ord-append rc/ord-append)
(def ord-between rc/ord-between)

(defn ordered-children [e] (rr/ordered-children ctx e))   ; fN children, in CRDT-key (path,tie) order
(defn ordered-segs [e] (rr/ordered-segs ctx e))           ; Turtle #6: a comment node's segN children, in order
(defn head-sym [e] (rr/head-sym ctx *view* e))
(defn unwrap-meta [e] (rr/unwrap-meta ctx *view* e))      ; D2: peel leading (#%meta …) wrappers off a bound form
(defn bound-target [L] (rr/bound-target ctx *view* BOUND L))   ; DURABLE identity edge (bound_to)
(defn refers-target [L] (rr/refers-target ctx *view* BOUND REFERS L)) ; bound_to, else derived refers_to
(defn live-node? [e] (rr/live-node? ctx KIND e))

;; --- binding extraction -----------------------------------------------------
(def PARAM-FORMS rc/PARAM-FORMS)   ; have a [param] vector
(def DEF-FORMS   rc/DEF-FORMS)    ; module value binding: (def name :- T val)
(def VALUE-DEFS  rc/VALUE-DEFS) ; everything that binds a value at module scope
(def TYPE-DEFS   rc/TYPE-DEFS)
;; EFFECT-DEFS — top-level forms whose effect is a REGISTRATION (a multimethod method,
;; a defmulti dispatch table, a protocol extension) rather than a fresh value binding.
;; They ARE addressable (read-def surfaces them: defmethod as `M:dispatch`, extensions
;; per target block) and they MUST be writable — the fix a real Clojure issue needs is
;; often exactly one such form (malli-01 = a new `(defmethod accept 'bytes? …)`). The old
;; write-def gate accepted only VALUE-DEFS, so it rejected defmethod with the wrong
;; medicine ("wrap it as (def <name> (defmethod …))" — a multimethod registration is a
;; top-level effect, not a value, so the wrap misleads the model into churn).
(def EXTEND-FORMS rc/EXTEND-FORMS)
(def EFFECT-DEFS  rc/EFFECT-DEFS)
;; every top-level head write-def / upsert-form accepts.
(def WRITABLE-DEFS rc/WRITABLE-DEFS)
(def writable-def-head? rc/writable-def-head?)
;; a writable form whose IDENTITY is its def-name (def/defn/type/protocol/defmulti) — as
;; opposed to defmethod (name+dispatch) and the extend forms (head+target), which key
;; differently and coexist under a shared head/name.
(defn named-def-head? [h] (and (writable-def-head? h)
                               (not (contains? (into #{"defmethod"} EXTEND-FORMS) h))))
;; extend-target-lint — REPAIR-GRADE canon lint for extend-protocol / extend-type.
;; In these two macros the TARGET positions (the type/class being extended, and — for
;; extend-protocol — each type in the type/method alternation) must be class SYMBOLS
;; resolvable at MACROEXPANSION. The classic footgun is a LIST in target position:
;;   (extend-protocol Foldable (class (byte-array 0)) (fold [this] ...))
;; extend-protocol/extend-type partition their body by SYMBOL targets vs method forms; a
;; runtime expression there silently MIS-PARTITIONS — the byte-array target never gets the
;; impl, no error fires, the oracle stays red (EXP-025 p2g ring-01 autopsy, 3 red attempts).
;; The correct idiom for a runtime class is a SEPARATE top-level `extend` call:
;;   (extend (Class/forName "[B") ProtocolName {:method-name (fn [args] ...)})
;; plain `extend` DOES take expression targets, so it is NOT linted (that's the fix, not the bug).
;;
;; Detection (per the b4/8d4be17 body-shape): walk everything after the head. A METHOD FORM
;; is a seq whose SECOND element is a param VECTOR `(m [args] body)` or an arity-list
;; `(m ([args] body) ([a b] body))`. A SYMBOL is a legal designator. A seq that is NOT a
;; method form sitting where a designator belongs = a runtime-expression target -> reject.
(defn- extend-method-form? [f]
  (and (seq? f) (>= (count f) 2)
       (let [s (second f)]
         (or (vector? s)                               ; (m [args] body...)
             (and (seq? s) (vector? (first s)))))))    ; (m ([args] body) ([a b] body))
(defn extend-target-lint [form]
  (when (and (seq? form) (#{"extend-protocol" "extend-type"} (str (first form))))
    (let [bad (filter #(and (seq? %) (not (extend-method-form? %))) (rest form))]
      (when (seq bad)
        {:message (str "extend-protocol targets must be class SYMBOLS resolvable at "
                       "macroexpansion — a runtime expression like (class (byte-array 0)) "
                       "silently mis-partitions")
         :got (pr-str (first bad))
         :suggestion (str "for runtime classes (e.g. Java arrays) write a separate top-level "
                          "(extend (Class/forName \"[B\") ProtocolName {:method-name (fn [args] ...)}) "
                          "form instead")
         :nearest (mapv pr-str bad)}))))
(def TYPE-COLON  rc/TYPE-COLON)  ; inline type-annotation markers (`:` is legal in field/param position)
(def LET-FORMS   rc/LET-FORMS)
(def FOR-FORMS   rc/FOR-FORMS)             ; binding vector carries :when/:while/:let modifiers
(def MATCH-FORMS rc/MATCH-FORMS)                    ; (match expr [pattern body] ...) — patterns bind + ref ctors
;; M1 Cut C — the binding extractor is now Beagle (src/resolve_binds.bclj):
;; what a destructuring pattern / param vector / let-or-for binding vector /
;; match pattern BINDS, and in what order. Wrappers as in Cut B — the ^:dynamic
;; state stays here and is handed over explicitly.
(defn brackets?         [e] (rb/brackets? ctx *view* e))
(defn map-node?         [e] (rb/map-node? ctx *view* e))
(defn collect-bind-syms [node]    (rb/collect-bind-syms ctx *view* node))  ; symbol leaves a pattern binds
(defn collect-or-vals   [node]    (rb/collect-or-vals ctx *view* node))    ; :or DEFAULT value-exprs (live refs)
(defn param-binds       [bracket] (rb/param-binds ctx *view* bracket))     ; param names from [x :- T y]
;; let/loop bindings are SEQUENTIAL — binding i's value (and :or defaults) see bindings
;; 0..i-1. let-bind-pairs returns ORDERED entries [bind-syms value-node or-default-vals]
;; so walk/capture can build the frame incrementally (a flat outer-scope walk misses
;; sibling shadowing — a real capture / mis-resolve bug).
(defn let-bind-pairs    [bracket] (rb/let-bind-pairs ctx *view* bracket))
(defn for-bind-pairs    [bracket] (rb/for-bind-pairs ctx *view* bracket))  ; [:bind syms vnode orvals] | [:expr node]
(defn frame-of          [bsyms]   (rb/frame-of ctx *view* bsyms))
(defn match-pat-binds   [pat]     (rb/match-pat-binds ctx *view* pat))     ; the NON-head leaves of a (Ctor a b) pattern

;; --- the lexical walk: resolve each reference to its nearest binding ---------
;; resolution counters — DYNAMIC (fresh atoms per `resolve-edn!` call), so a
;; long-lived daemon's repeated resolves don't accumulate across runs.
(def ^:dynamic n-resolved (atom 0)) (def ^:dynamic n-unresolved (atom 0))
(def ^:dynamic n-xmod (atom 0)) (def ^:dynamic n-type (atom 0))
(def ^:dynamic n-comment (atom 0))               ; Turtle #6: comment identifier mentions resolved
;; S3.3 scoped-walk instrumentation — count the TOP-LEVEL FORMS the walk visited and
;; the modules it walked, so a caller (the daemon's gate) can prove a scoped re-resolve
;; is genuinely O(edit-scope): it walks only the affected modules' forms, not O(corpus).
(def ^:dynamic n-forms-walked (atom 0)) (def ^:dynamic walked-modules (atom #{}))
(def ^:dynamic *xresolve* (fn [_] nil))          ; cross-module value resolver: name -> {:node :mode :alias}
(def ^:dynamic *tresolve* (fn [_] nil))          ; type-name -> type-def node (module-local)
(def ^:dynamic *aresolve* (fn [_] nil))          ; accessor-name `point-x` -> [type-def-leaf field-string]
;; M1 Cut G — THE LEXICAL WALK is now Beagle (src/resolve_walk.bclj): the whole
;; descend-and-bind engine (walk / walk-all / walk-fn-arity / walk-pat-heads /
;; walk-quasi / walk-quasi-seq, the binding writes bind! / bind-xmod! /
;; bound-render!, the type-position resolvers, the comment resolver and the
;; per-src driver). As in Cuts B–F the ^:dynamic vars STAY here — coord_daemon.clj
;; and tests/coord_*.clj `binding` them by qualified name — so `walk-env` reads the
;; dynamic state at call time and hands it over as ONE explicit record. Docstrings
;; and the per-def rationale live with the logic, in the module header.
(defn walk-env []
  (rw/->Walk ctx *view* tx REFERS BOUND FIXED QUAL CTOR ACC
             n-resolved n-unresolved n-xmod n-type n-comment
             *xresolve* *tresolve* *aresolve*))
(defn bind! [L target] (rw/bind! (walk-env) L target))
(defn bind-xmod! [node x] (rw/bind-xmod! (walk-env) node x))
;; ->Name / map->Name auto-constructor prefix in a spelling (bare OR alias-qualified), else nil.
(def ctor-prefix rc/ctor-prefix)
(defn bound-render! [node nm bt] (rw/bound-render! (walk-env) node nm bt))
(defn walk-type [node] (rw/walk-type! (walk-env) node))
(defn resolve-type-after-colon! [nodes] (rw/resolve-type-after-colon! (walk-env) (vec nodes)))
(defn resolve-types-in-bracket! [bracket] (rw/resolve-types-in-bracket! (walk-env) bracket))
(defn walk [node scope] (rw/walk! (walk-env) node (vec scope)))
(defn walk-all [nodes scope] (rw/walk-all! (walk-env) (vec nodes) (vec scope) rw/walk!))
(defn walk-fn-arity [forms scope] (rw/walk-fn-arity! (walk-env) (vec forms) (vec scope) rw/walk!))
(defn walk-pat-heads [pat scope] (rw/walk-pat-heads! (walk-env) pat (vec scope) rw/walk!))
(defn walk-quasi [node scope quoted?] (rw/walk-quasi! (walk-env) node (vec scope) quoted? rw/walk! rw/walk-quasi-seq!))
(defn walk-quasi-seq [children scope quoted?] (rw/walk-quasi-seq! (walk-env) (vec children) (vec scope) quoted? rw/walk!))

;; module frame = all top-level defs (so forward references resolve) ----------
;; M1 Cut D — one module's frame + its import/export surface is now Beagle
;; (src/resolve_modules.bclj). Wrappers as in Cuts B/C; the entity list comes out
;; of the ^:dynamic `file->ents` atom here and is handed over explicitly.
(defn unwrap-def [form] (rm/unwrap-def ctx *view* form))
(defn module-defs [src] (rm/module-defs ctx *view* (@file->ents src)))
;; --- cross-module: parse ns/:require (imports) and js/export (exports) -------
(defn forms-of [src] (rm/forms-of ctx *view* (@file->ents src)))
(defn ns-form [src] (rm/ns-form ctx *view* (@file->ents src)))
(defn module-name [src] (rm/module-name ctx *view* (@file->ents src)))
(defn merge-import-opts [acc modn kids] (rm/merge-import-opts ctx *view* acc modn (vec kids)))
(defn parse-require [src] (rm/parse-require ctx *view* (@file->ents src)))   ; {:refer {name->mod}, :as {alias->mod}, :rename {local->[mod srcname]}}
(defn module-exports [src] (rm/module-exports ctx *view* (@file->ents src))) ; {exported-name -> binding-node}
(defn type-name-leaf [d] (rm/type-name-leaf ctx *view* d))                   ; a type def's name-leaf, (Name Params) head unwrapped
(defn module-types [src] (rm/module-types ctx *view* (@file->ents src)))     ; {type-name -> name-leaf}
(defn module-accessors [src] (rm/module-accessors ctx *view* (@file->ents src)))  ; {"point-x" -> [Point-name-leaf "x"]}

;; --- corpus tables (DYNAMIC, inert root) ------------------------------------
;; The loaded sources + every frame/export table derived from them. INERT at root
;; (nil / empty), COMPUTED inside `resolve-edn!` from the FRESHLY-loaded srcs of
;; the bound store. Functions that read these (def-binding, make-xresolve,
;; re-resolve!, the mode dispatch) read the dynamic value at call time, so they
;; see the per-run tables — never a stale load-time global.
(def ^:dynamic srcs [])
(def ^:dynamic file-modframe {})
(def ^:dynamic file-typeframe {})
(def ^:dynamic file-accessors {})
(defn def-binding [src nm] (or (get (file-modframe src) nm) (get (file-typeframe src) nm)))  ; value OR type
;; module-name -> {exported-name -> binding-node}
;; beagle modules carry an (ns ...) form but export IMPLICITLY (no js/export), so
;; fall back to ALL top-level defs as the export surface. JS modules with explicit
;; js/export use those. (Clojure semantics agree: a public def IS exported.)
(def ^:dynamic global-exports {})
;; module-name -> {type-name -> type-def name-leaf}
;; types export implicitly too; a consumer's :refer/:as of a record/union/protocol
;; resolves here. Without it, a foreign type in a `:- T` annotation never tracks a
;; rename and a cross-module delete of the type false-reports 'safe'.
(def ^:dynamic global-type-exports {})
;; module-name -> {"point-x" -> [type-name-leaf field]}
;; synthesized field accessors export too; the cross-module half of the local *aresolve*,
;; so a record rename carries c/point-x / :refer'd point-x (parallel to global-type-exports).
(def ^:dynamic global-accessor-exports {})
(defn make-xresolve [src]
  (let [{:keys [refer as rename]} (parse-require src)
        ;; a :refer'd / :as-qualified / :rename'd name may be a VALUE or a TYPE export
        xport (fn [m n] (or (get-in global-exports [m n]) (get-in global-type-exports [m n])))
        xacc  (fn [m n] (get-in global-accessor-exports [m n]))]   ; [type-leaf field] or nil
    (fn [nm]
      (cond
        ;; a symbol node with no `v` (nil name) is not a resolvable cross-module ref — bail
        ;; before `str/includes?` NPEs. Such nodes appear inside .cljc reader-conditional
        ;; content (`#?(:clj …)`), which the warm resolver descends into during render;
        ;; leaving them unresolved renders their own spelling, exactly right under no-rename.
        (nil? nm) nil
        (get refer nm)  (let [m (get refer nm)]
                          (if-let [t (xport m nm)] {:target t :mode :tracking}
                            (when-let [a (xacc m nm)] {:target (first a) :mode :tracking :accessor (second a)})))
        (get rename nm) (let [[m sn] (get rename nm)] {:target (xport m sn) :mode :fixed})
        (str/includes? nm "/")
        ;; qualifier is an :as alias OR a fully-spelled module name (e.g. (require acc.prod) then acc.prod/Box)
        (let [[al pn] (str/split nm #"/" 2)
              m (or (get as al)
                    (when (some #(contains? % al) [global-exports global-type-exports global-accessor-exports]) al))]
          (when m
            (if-let [t (xport m pn)] {:target t :mode :qual :alias al}
              (when-let [a (xacc m pn)] {:target (first a) :mode :qual :alias al :accessor (second a)}))))
        :else nil))))
;; --- Turtle #6: resolve identifier mentions INSIDE comments -----------------
;; A comment is a sequence of text + symbol-candidate segments. A symbol segment
;; that EXACTLY names an in-scope binding (module def / type / refer-import) gets
;; a refers_to edge — so it renders the binding's CURRENT name and renames with
;; it, exactly like code. A `red-zone` token is one symbol (≠ `red`) and a quoted
;; `"red"` was demoted to text by beagle's lexer, so neither resolves: the rename
;; win without the sed corruption. Module scope (comments-in-bodies are a follow-up).
;; M1 Cut G — the comment resolver and the per-src walk driver are Beagle too
;; (src/resolve_walk.bclj). `walk-corpus` packages the four corpus tables the
;; driver reads; `make-xresolve` stays here (it closes over parse-require and the
;; global export tables) and is handed over as the per-src resolver factory, which
;; is why the ^:dynamic *xresolve*/*tresolve*/*aresolve* are no longer rebound on
;; this path — the module builds each src's three resolvers itself. re-resolve! still
;; binds them, and reads them through walk-env.
(defn walk-corpus [] (rw/->Corpus (vec srcs) file-modframe file-typeframe file-accessors @file->ents))
(defn cbind! [L target] (rw/cbind! (walk-env) L target))
(defn resolve-comment [e src] (rw/resolve-comment! (walk-env) (walk-corpus) e src))
(defn walk-comments [src] (rw/walk-comments! (walk-env) (walk-corpus) src))
;; the lexical walk over a CHOSEN subset of srcs (reads bound tables). The
;; cross-module tables (global-exports / file-typeframe / ...) are already bound
;; from the WHOLE corpus, so each walked module's imports resolve against every
;; other module's exports exactly as a full walk would — we just restrict WHICH
;; modules we re-walk (and re-write refers_to for). This is the resolver half of
;; S3.3 scoped re-resolve: full tables, partial walk.
(defn run-resolution-over! [walk-srcs]
  (rw/run-resolution-over! (walk-env) (walk-corpus) (vec walk-srcs) make-xresolve
                           n-forms-walked walked-modules))
(defn run-resolution! []        ; the lexical walk over every bound src (reads bound tables)
  (rw/run-resolution! (walk-env) (walk-corpus) make-xresolve n-forms-walked walked-modules))

;; #(a) LIFT — make the materialized refers_to identity-COMPLETE: every DURABLE bound_to
;; edge (leaf -> binding node) must be reflected as a refers_to edge, so identity-first reads
;; (render / callers / callgraph, which read refers_to) follow a renamed or cold-restarted
;; binding even for references the SPELLING walk cannot re-derive — a comment mention whose
;; word no longer names any def, or a same-module reference whose def spelling has moved.
;; The walk (walk / bound-render!) already resolves the references it reaches, WITH their
;; render markers; this only fills the GAP: a bound leaf with no live refers_to gets a plain
;; refers_to to its durable target. Warm-only (refers_to is a derived resolve-pred, re-cut
;; each materialize). Idempotent: leaves already resolved are skipped.
(defn lift-bound-to-refers! []
  (when BOUND
    (doseq [cid (c/by-p ctx BOUND)]
      (let [cl (c/fact-of ctx cid) L (:l cl) D (:r cl)]
        (when (and (integer? D) (live-node? D) (empty? (c/by-lp ctx L REFERS)))
          (c/fact! ctx L REFERS D tx))))))

;; ============================================================================
;; resolve-edn! — the RUNNABLE pipeline over an ARBITRARY bound store.
;; Binds a FRESH store (ctx/tx/SUP + predicate value-ids recomputed against it),
;; a fresh file->ents atom, and fresh counters; load-edn's `edn-paths` into that
;; bound store; computes + binds the corpus tables from those srcs; runs the
;; resolution driver; then invokes `body` WITHIN the binding scope (so CLI
;; dispatch — rename/delete/extract/author — and tests read the bound state).
;; The store is local to this call: a daemon resolving over its warm store gets a
;; clean store B every time, and NOTHING leaks to the inert root binding.
;; ============================================================================
(defn resolve-edn!
  ([edn-paths] (resolve-edn! edn-paths (fn [])))
  ([edn-paths body]
   (let [store (c/new-store)
         t     (c/begin-tx! store "resolve")
         sup   (c/value! store "supersedes")]
     (c/set-supersedes-pred! store sup)
     (binding [ctx store, tx t, SUP sup
               file->ents (atom {})
               Vp (c/value! store "v") KIND (c/value! store "kind") REFERS (c/value! store "refers_to") BOUND (c/value! store "bound_to")
               FIXED (c/value! store "keep_spelling") QUAL (c/value! store "qualifier")
               CTOR (c/value! store "ctor_prefix") ACC (c/value! store "accessor_field")
               n-resolved (atom 0) n-unresolved (atom 0) n-xmod (atom 0) n-type (atom 0) n-comment (atom 0)
               n-forms-walked (atom 0) walked-modules (atom #{})
               srcs [] file-modframe {} file-typeframe {} file-accessors {}
               global-exports {} global-type-exports {} global-accessor-exports {}]
       ;; load EDN into the FRESH bound store, then compute the corpus tables from
       ;; THOSE srcs (set! the thread-local binding — never the root value).
       (set! srcs (mapv load-edn edn-paths))
       ;; M1 Cut G: the six per-run tables are one Beagle call (rw/corpus-tables);
       ;; the `binding`/`set!` scaffolding stays here because the ^:dynamic vars
       ;; themselves cannot move namespace.
       (let [tb (rw/corpus-tables ctx *view* (vec srcs) @file->ents)]
         (set! file-modframe (:modframe tb))
         (set! file-typeframe (:typeframe tb))
         (set! file-accessors (:accessors tb))
         (set! global-exports (:exports tb))
         (set! global-type-exports (:type-exports tb))
         (set! global-accessor-exports (:accessor-exports tb)))
       (run-resolution!)
       (body)))))

;; ============================================================================
;; S3.2 — resolve WARM, over the daemon's live store (no EDN reload).
;; The daemon holds a populated store whose AST nodes are entities carrying the
;; same kind/v/fN facts an --emit-edn projection has, PLUS a `name` fact
;; `@<module>#<int>` (fram.schema/name!). Grouping there is by the name prefix,
;; not by load-edn's per-src tracking — so the ONLY thing that differs from the
;; EDN path is how the corpus structure (file->ents/srcs + frame/export tables)
;; is DERIVED. Everything downstream (module-defs/forms-of/run-resolution!/...)
;; reads file->ents + ctx, which are the bound store, so it is reused verbatim.
;; ============================================================================
;; module of `@kernel#127` -> "kernel" ; the daemon names every node `@<mod>#<int>`.
(defn name->module [nm]
  (when (string? nm)
    (when-let [[_ m] (re-matches #"@([^#]+)#\d+" nm)] m)))
;; corpus-from-store! — from the BOUND, already-populated store, derive the SAME
;; corpus structure resolve-edn! computes from EDN: file->ents grouped by module,
;; srcs = the module list, then the per-module frame/export tables (reusing
;; module-defs/module-types/module-accessors/module-exports/module-name — they
;; read @file->ents + ctx, which now ARE the warm store). `set!` (not root) so
;; nothing leaks past the binding scope, exactly like resolve-edn!.
(defn corpus-from-store! []
  (let [t0     (System/nanoTime)
        NAME   (c/value-id ctx "name")            ; the daemon's node-name predicate
        groups (cond
                 ;; INCREMENTAL CORPUS CACHE — skip the O(total) name reduce when the daemon
                 ;; supplies the maintained module->entity-ids map (the dominant per-verb cost).
                 (some? *corpus-cache*) *corpus-cache*
                 NAME (reduce (fn [acc cid]
                                (let [cl (c/fact-of ctx cid)
                                      nm (c/literal ctx (:r cl))
                                      m  (name->module nm)]
                                  (if m (update acc m (fnil conj []) (:l cl)) acc)))
                              {} (c/by-p ctx NAME))
                 :else {})
        t-groups (System/nanoTime)]
    (reset! file->ents groups)                    ; module-keyed entity lists
    (set! srcs (vec (keys groups)))               ; the modules ARE the srcs
    ;; SCOPED corpus (Build B): *corpus-scope* restricts the EXPENSIVE per-module FRAME
    ;; builds (module-defs/types/accessors) to the module(s) the verb actually reads.
    ;; The src/module LIST is still the whole corpus (groups), so module membership /
    ;; name->module are correct — only the frame TABLES are scoped. The only caller
    ;; that sets a scope is the no-walk minimal-op path (set-body/upsert-form), which
    ;; reads ONLY its target module's frame via def-binding and never run-resolution!'s
    ;; cross-module export tables — so scoping frames + skipping the global export
    ;; tables under a scope is sound. nil scope => full frames + exports (verbatim).
    (let [frame-srcs (if *corpus-scope* (filter *corpus-scope* srcs) srcs)]
      (set! file-modframe  (into {} (map (fn [s] [s (module-defs s)]) frame-srcs)))
      (set! file-typeframe (into {} (map (fn [s] [s (module-types s)]) frame-srcs)))
      (set! file-accessors (into {} (map (fn [s] [s (module-accessors s)]) frame-srcs))))
    (when-not *corpus-scope*                       ; cross-module export tables — only the WALK reads them
      (set! global-exports
            (into {} (map (fn [s] [(module-name s)
                                   (let [e (module-exports s)] (if (seq e) e (module-defs s)))])
                          (filter module-name srcs))))
      (set! global-type-exports
            (into {} (map (fn [s] [(module-name s) (module-types s)]) (filter module-name srcs))))
      (set! global-accessor-exports
            (into {} (map (fn [s] [(module-name s) (module-accessors s)]) (filter module-name srcs)))))
    (when (= "1" (System/getenv "FRAM_PROF"))
      (binding [*out* *err*]
        (println (format "  corpus-from-store!: groups=%.1fms frames+exports=%.1fms cached=%s nsrcs=%d scoped=%s"
                         (/ (- t-groups t0) 1e6) (/ (- (System/nanoTime) t-groups) 1e6)
                         (some? *corpus-cache*) (count srcs) (boolean *corpus-scope*)))))))

;; ============================================================================
;; S3.3 scoped-classifier helpers — computed from the BOUND warm corpus (call
;; under a binding that has run corpus-from-store!, e.g. with-resolve-read or
;; resolve-modules!'s body). These let the daemon classify an edit by its
;; binding-SET delta (the load-bearing correctness point), not by syntactic site.
;; ============================================================================
;; module-src-of: the corpus `src` (= module-name string, in the warm path) for a
;; node entity-id, via its module prefix. corpus-from-store! keys srcs by module
;; name, so the `src` and the module name coincide there.
;; module-export-set: every NAME module M makes resolvable to a consumer — the value
;; exports (js/export or, beagle-implicit, all top-level defs) UNION the type exports
;; (records/unions/protocols + variants) UNION the synth field-accessor names. This is
;; precisely the surface a consumer's :refer/:as/:rename can bind, so a change to THIS
;; set is exactly what forces a consumer re-walk; an internal body edit leaves it fixed.
(defn module-export-set [src]
  (let [v (module-exports src)
        vexp (if (seq v) v (module-defs src))]   ; beagle implicit-export fallback (mirrors global-exports)
    (into #{} (concat (keys vexp)
                      (keys (module-types src))
                      (keys (module-accessors src))))))
;; import-graph: {module -> #{modules it imports}} over the whole corpus, from each
;; module's (ns :require ...) / bare (require ...). Consumers of M = the modules whose
;; import-set contains M (the reverse edge). Used to widen the dirty set when M's
;; export-set changed: M PLUS everyone importing M re-resolves.
(defn module-imports [src]
  (let [{:keys [refer as rename]} (parse-require src)]
    (into #{} (concat (vals refer) (vals as) (map first (vals rename))))))
(defn import-graph []
  (into {} (map (fn [s] [(module-name s) (module-imports s)]) (filter module-name srcs))))
;; module-has-macro?: does M define a defmacro at top level? A macro edit can change
;; how OTHER modules expand, so its blast radius isn't bounded by the import graph —
;; the daemon falls back to a whole-corpus re-resolve (sound; dormant in fram, which
;; has zero defmacro).
(defn module-has-macro? [src]
  (boolean (some (fn [f] (= "defmacro" (head-sym (unwrap-def f)))) (forms-of src))))

;; resolve-warm-store! — bind ctx=the daemon's store (+ a fresh tx + the value-ids
;; recomputed against THAT store — store-local ids must match their store, the
;; same seam GATE B guards), derive the corpus FROM the store, run the lexical
;; walk (writing refers_to into the store), then invoke body within the scope.
;; Mirror of resolve-edn! with the ONLY change being the corpus source. The store
;; is supplied (the daemon's warm `co`), not minted, and is mutated in place: the
;; warm refers_to edges callers-of / blast-radius read come straight from here.
(defn resolve-warm-store!
  ([store] (resolve-warm-store! store (fn [])))
  ([store body]
   (let [t   (c/begin-tx! store "resolve-warm")
         sup (or (c/value-id store "supersedes") (c/value! store "supersedes"))]
     (c/set-supersedes-pred! store sup)
     (binding [ctx store, tx t, SUP sup
               file->ents (atom {})
               Vp (c/value! store "v") KIND (c/value! store "kind") REFERS (c/value! store "refers_to") BOUND (c/value! store "bound_to")
               FIXED (c/value! store "keep_spelling") QUAL (c/value! store "qualifier")
               CTOR (c/value! store "ctor_prefix") ACC (c/value! store "accessor_field")
               n-resolved (atom 0) n-unresolved (atom 0) n-xmod (atom 0) n-type (atom 0) n-comment (atom 0)
               n-forms-walked (atom 0) walked-modules (atom #{})
               srcs [] file-modframe {} file-typeframe {} file-accessors {}
               global-exports {} global-type-exports {} global-accessor-exports {}]
       (corpus-from-store!)
       (when *resolve-walk?* (run-resolution!) (lift-bound-to-refers!))   ; Build B: the minimal-op path skips the whole-corpus walk (and the identity lift)
       (body)))))

;; ============================================================================
;; S3.3 — resolve-modules! : SCOPED re-resolve over the warm store.
;; Identical store-binding + corpus derivation to resolve-warm-store! (so it sees
;; the FULL cross-module export/import tables — M's imports resolve against every
;; module's exports), but only WALKS (and writes refers_to for) `module-set`. The
;; caller (the daemon) is responsible for stripping the affected modules' prior
;; refers_to first (resolve-warm-store! re-walks the whole corpus, so the daemon's
;; whole-corpus strip suffices there; the scoped path strips only module-set). The
;; module list is exposed via `body` (corpus-from-store! sets `srcs` = all modules,
;; so a caller can read it under the binding) and `module-set` selects the walk.
;; module-set is a set of module-name strings (the `@<module>#` prefix), matching
;; the keys `srcs` carries after corpus-from-store!. An empty set walks nothing
;; (a pure table rebuild) — sound when the daemon classified no module dirty.
;; ============================================================================
(defn resolve-modules!
  ([store module-set] (resolve-modules! store module-set (fn [])))
  ([store module-set body]
   (let [t   (c/begin-tx! store "resolve-scoped")
         sup (or (c/value-id store "supersedes") (c/value! store "supersedes"))]
     (c/set-supersedes-pred! store sup)
     (binding [ctx store, tx t, SUP sup
               file->ents (atom {})
               Vp (c/value! store "v") KIND (c/value! store "kind") REFERS (c/value! store "refers_to") BOUND (c/value! store "bound_to")
               FIXED (c/value! store "keep_spelling") QUAL (c/value! store "qualifier")
               CTOR (c/value! store "ctor_prefix") ACC (c/value! store "accessor_field")
               n-resolved (atom 0) n-unresolved (atom 0) n-xmod (atom 0) n-type (atom 0) n-comment (atom 0)
               n-forms-walked (atom 0) walked-modules (atom #{})
               srcs [] file-modframe {} file-typeframe {} file-accessors {}
               global-exports {} global-type-exports {} global-accessor-exports {}]
       (corpus-from-store!)                          ; FULL tables from the whole store
       (run-resolution-over! (filter module-set srcs))  ; WALK only the affected module subset
       (lift-bound-to-refers!)                       ; #(a) fill identity gaps the spelling walk missed
       (body)))))

;; --- projection: emit EDN for beagle --render, names resolved via refers_to --
;; follow refers_to transitively (re-export chains: a (js/export name) re-export is
;; itself a reference) to the ULTIMATE binding, and render its current name.
;; M1 Cut E — the render-back-to-source layer is now Beagle (src/resolve_render.bclj).
(defn ultimate [B] (rv/ultimate ctx *view* BOUND REFERS B))        ; follow refers_to to the node that HOLDS the name
(defn binding-name [B] (rv/binding-name ctx *view* BOUND REFERS B))

;; ============================================================================
;; AUTHORING — mint a NEW datum subtree into the SAME fact store (the inverse of
;; facts-roundtrip.rkt's datum->facts). This is what makes add-def / set-body a
;; FACT OPERATION, not a text splice: a Clojure EDN datum (the structured edit
;; spec the agent emits, e.g. `(defn add-two [x :- Int] :- Int (+ x 2))`) is walked
;; into fresh entities carrying `kind`/`v`/`fN` facts — exactly the reader-datum
;; shape --emit-edn projects — and registered in file->ents so extract-file! emits
;; them. The wrapper/body fN edges are then wired (append) or SUPERSEDED (replace),
;; reusing the rename template (assert new, supersede old; reads filter superseded).
;; The renderer reconstructs purely from fN/tail, so a minted subtree round-trips
;; byte-stable, and any reference in it resolves via the SAME lexical walk (a fresh
;; pass over forms-of after minting), giving scope-correctness for free.
;; ============================================================================
;; M1 Cut I — THE MINT LAYER is Beagle (src/resolve_mint.bclj). As in Cuts B–H the
;; ^:dynamic vars STAY here, so `mint-env` reads the dynamic state at call time and
;; hands it over as ONE record; `file->ents` rides as the ATOM ITSELF (register!
;; must mutate the var the projection reads, not a snapshot).
(defn mint-env [] (rmi/->Mint ctx tx SUP KIND Vp file->ents))
(defn register! [src e] (rmi/register! (mint-env) src e))
;; leaf-kind: the reader `kind` for a Clojure scalar (mirrors datum->facts:55-64).
;; Beagle reads [..] as (#%brackets ..) and {..} as (#%map ..), so a vector/map datum
;; in the spec is minted as a `list` headed by that desugaring symbol — identical to
;; what --emit-edn produces, keeping the projection lossless.
(defn mint-leaf! [src kind v] (rmi/mint-leaf! (mint-env) src kind v))
;; Reader metadata (`^Type` / `^:flag` / `^{..}`) rides on the datum as Clojure meta —
;; the host LispReader normalizes `^Type`→{:tag Type}, `^:dynamic`→{:dynamic true}. The
;; write-def raw-source path minted a bare `(str sym)`, DROPPING it silently: a whole-block
;; `extend-protocol` re-author lost every `^OutputStream` param hint (→ reflection). Mint it
;; as beagle's `(#%meta <m> <target>)` node (unwrap-meta's inverse) so hints + `^:dynamic`
;; names survive. Match beagle's reader shorthand (parse.rkt): {:tag Sym}→Sym (bare symbol),
;; a single {:flag true}→:flag (keyword), else the full map (`^{..}` longhand). Source-position
;; keys the reader may attach are stripped (a plain PushbackReader adds none, but be defensive).
(defn- clj-meta->beagle-meta [m]
  (cond
    (and (= 1 (count m)) (contains? m :tag) (symbol? (:tag m))) (:tag m)
    (and (= 1 (count m)) (true? (val (first m))))               (key (first m))
    :else m))
(defn- reader-meta [d]
  (when (instance? clojure.lang.IObj d)
    (not-empty (apply dissoc (meta d) [:line :column :end-line :end-column :file]))))

(defn mint-datum! [src d]
  (if-let [m (reader-meta d)]
    ;; strip the meta and re-mint under an explicit (#%meta …) wrapper (recursion mints the
    ;; bare target normally; a single #%meta suffices — Clojure merges stacked hints to one map).
    (mint-datum! src (list (symbol "#%meta") (clj-meta->beagle-meta m) (with-meta d nil)))
  (cond
    ;; EDN `nil` must mint as the SYMBOL nil — beagle reads source `nil` via Racket's
    ;; reader (no nil there), so the corpus convention is kind="symbol" v="nil"; the
    ;; kind="nil" leaf means Racket '() and RENDERS as `()`, which the type-checker
    ;; rejects ("unsupported expression: '()"). Same re-encoding rationale as the
    ;; keyword branch below. (Found by the macro-crossover set-body probe, 2026-07-02.)
    (nil? d)        (mint-leaf! src "symbol" "nil")
    (symbol? d)     (mint-leaf! src "symbol"  (str d))
    ;; A `:foo` token is a SYMBOL leaf with the colon RETAINED — beagle reads .bclj via
    ;; Racket's reader, which has no keyword syntax: `:enable`/`:-` come back as the symbol
    ;; |:enable|/|:-| (kind="symbol", v=":enable"). facts-roundtrip's emit/decode shares
    ;; that convention, so a `keyword`-kind leaf (colon stripped) decodes to a Clojure keyword
    ;; that renders as `#:-` — corrupting the `:-` type marker and rejecting the build. The
    ;; authoring spec is parsed by clojure.edn (where `:-` IS a keyword), so we MUST re-encode
    ;; it the beagle way here, or graph-authored typed defs never round-trip to text.
    (keyword? d)    (mint-leaf! src "symbol"  (str d))
    (string? d)     (mint-leaf! src "string"  d)
    ;; same convention: beagle source `true`/`false` read as SYMBOLS (the corpus has
    ;; zero kind="bool" leaves) — mint them the way the reader does.
    (boolean? d)    (mint-leaf! src "symbol"  (if d "true" "false"))
    (char? d)       (mint-leaf! src "char"    (str d))
    (number? d)     (mint-leaf! src "number"  (str d))
    (or (list? d) (seq? d) (vector? d) (map? d))
    ;; symbols built via (symbol ..) — writing #%brackets literally would be read as a
    ;; reader tag by Clojure's reader at load time of this file.
    (let [head  (cond (vector? d) [(symbol "#%brackets")] (map? d) [(symbol "#%map")] :else [])
          elems (concat head (if (map? d) (apply concat (seq d)) (seq d)))
          e     (register! src (c/entity! ctx))]
      (c/fact! ctx e KIND (c/value! ctx "list") tx)
      (doseq [[i x] (map-indexed vector elems)]
        (c/fact! ctx e (c/value! ctx (str "f" i)) (mint-datum! src x) tx))
      e)
    ;; Reader forms the host LispReader hands us as OBJECTS (write-def raw-source path),
    ;; not as `#%…`-headed lists like beagle's Racket reader emits. Re-encode them into
    ;; the SAME `(#%regex "pat")` / `(#%set e…)` list node --emit-edn mints, so the
    ;; renderer (facts-roundtrip datum->src + node->str) inverts them back to the reader
    ;; literal. Without this a regex/set fell to the :else leaf and was pr-str'd — a
    ;; `#","` became the STRING `"#\",\""`, corrupting the def. (EXP-025 b1 substrate defect.)
    (instance? java.util.regex.Pattern d)
    (mint-datum! src (list (symbol "#%regex") (.pattern ^java.util.regex.Pattern d)))
    (set? d)
    (mint-datum! src (cons (symbol "#%set") (seq d)))
    :else (mint-leaf! src "other" (pr-str d)))))
;; the body fN edges of a defn form = the consecutive fN child facts whose slot is
;; AFTER the params bracket (everything --emit-edn put at f5,f6,... in `defn` :122).
(defn fN-facts [parent] (rmi/fN-facts (mint-env) parent))  ; -> [[N fact-id child-node] ...] over LIVE fN edges, ordered
;; supersede a fact WITHOUT a replacement value (e.g. retiring a wrapper/body fN edge).
;; The supersedes edge needs a subject; a fresh entity is fine — the live-view filter
;; keys off the superseded :r (the old fact id), not the subject (cnf.bclj:105-106,116).
(defn retire-fact! [oldc] (rmi/retire-fact! (mint-env) oldc))

;; --- delete projection: omit a top-level form + its subtree, renumber siblings ---
;; The renderer reads fN children CONSECUTIVELY and includes only nodes reachable from
;; the root, so deleting a form means (a) skip its whole subtree (else its orphaned root
;; would compete with the real wrapper) and (b) re-emit the wrapper's surviving forms at
;; consecutive fN (a gap would truncate the file). Pure projection — the store is not mutated.
(def ^:dynamic *deleted-forms* #{})      ; wrapper-child form node-ids to omit (per src, but ids are global)
(def ^:dynamic *deleted-subtree* #{})    ; all entity ids under deleted forms — skipped on emit
(defn wrapper-of [src] (some (fn [e] (when (= "beagle-file" (head-sym e)) e)) (@file->ents src)))
(defn structural-kids [n]                ; child node ids via fN/segN/commentN/tail edges
  (->> (c/by-l ctx n) (map #(c/fact-of ctx %))
       (keep (fn [cl] (let [p (c/literal ctx (:p cl)) r (:r cl)]
                        (when (and (integer? r) (string? p)
                                   (or (ord-pos? p) (re-matches #"seg\d+" p)         ; #36: ord-pos? = old f<int> OR new CRDT key
                                       (re-matches #"comment\d+" p) (= p "tail")))   ; a form's own doc-comment
                          r))))))
(defn descendants [root]                 ; root + all transitive structural descendants
  (loop [seen #{} stack [root]]
    (if (empty? stack) seen
      (let [n (peek stack)]
        (if (seen n) (recur seen (pop stack))
          (recur (conj seen n) (into (pop stack) (structural-kids n))))))))
(defn form-for-victim [src victim]       ; the top-level form whose def-NAME is victim (only the name —
  (some (fn [f]                          ; a defunion VARIANT is not its own top-level form, so it won't match)
          (let [nl0 (unwrap-meta (second (ordered-children (unwrap-def f))))   ; D2: peel (#%meta …) off the name
                nl (if (= "list" (kind-of nl0)) (first (ordered-children nl0)) nl0)]   ; (Name Params) head
            (when (= victim nl) f)))
        (rest (ordered-children (wrapper-of src)))))
(defn extract-file! [src out-path]
  (with-open [w (clojure.java.io/writer out-path)]
    (binding [*out* w]
      (println (str "@file " src))
      (let [wrap (wrapper-of src)   ; #36: ALWAYS renumber wrapper form-edges to consecutive
                                    ;; integers. The graph keeps CRDT keys (f<path>~<tie>); the
                                    ;; .bclj view must be clean integer fN so racket --render (which
                                    ;; only understands integer fN) sees every form. Previously this
                                    ;; ran ONLY under deletes, so a CRDT-keyed insert survived raw
                                    ;; and racket silently dropped it. Idempotent for clean modules.
            ;; REACHABILITY filter: emit ONLY nodes reachable from the beagle-file
            ;; wrapper via LIVE structural edges (fN/tail/segN/commentN/child). An
            ;; authoring verb that SUPERSEDES a body/form fN edge (set-body, upsert
            ;; REPLACE) leaves the OLD subtree's nodes in file->ents with their parent
            ;; edge retired — so they become parentless ROOT CANDIDATES that the
            ;; renderer's edn-root may pick instead of the real wrapper (observed:
            ;; render of a re-edited module collapsed to just the orphaned old body).
            ;; Restricting to descendants(wrapper) drops those orphans cleanly. (Skip
            ;; the filter under the delete path, which has its own subtree machinery.)
            root  (when (empty? *deleted-forms*) (wrapper-of src))
            live  (when root (descendants root))
            keep? (fn [e] (or (nil? live) (contains? live e)))]
        (doseq [e (@file->ents src) :when (and (not (*deleted-subtree* e)) (keep? e)), cid (c/by-l ctx e)]
          (let [cl (c/fact-of ctx cid) p (:p cl) r (:r cl) ps (c/literal ctx p)]
            (cond
              ;; wrapper form-edges: drop them all (except f0, the beagle-file head); the
              ;; surviving forms are re-emitted at consecutive integer fN below.
              (and (= e wrap) (string? ps) (ord-pos? ps) (not= ps "f0")) nil   ; #36: wrapper form-edge (old f<int> OR new CRDT key) except f0 (beagle-file head) — renumbered consecutively below
              (#{"supersedes" "refers_to" "keep_spelling" "qualifier" "ctor_prefix" "accessor_field"} ps) nil  ; internal edges
              (and (= ps "v") (refers-target e))              ; a resolved reference: render per mode
              (let [D (refers-target e)
                    fixed? (seq (c/by-lp ctx e FIXED))        ; :rename alias — keep own spelling
                    qual (pred-val e "qualifier")             ; x/name — show alias/current-name
                    cpfx (pred-val e "ctor_prefix")           ; "->" / "map->" auto-constructor — re-prefix
                    afield (pred-val e "accessor_field")      ; synth accessor — render <lower(name)>-<field>
                    nm (binding-name D)
                    nm (cond cpfx   (str cpfx nm)
                             afield (str (str/lower-case nm) "-" afield)
                             :else  nm)]
                (println (str "[" e " \"v\" "
                              (pr-str (cond fixed? (c/literal ctx r)
                                            qual   (str qual "/" nm)
                                            :else  nm))
                              "]")))
              (c/value-object? ctx r) (println (str "[" e " " (pr-str ps) " " (pr-str (c/literal ctx r)) "]"))
              :else (println (str "[" e " " (pr-str ps) " " r "]")))))
        (when wrap                                            ; re-emit surviving forms at consecutive fN
          (let [forms (remove *deleted-forms* (rest (ordered-children wrap)))]
            (doseq [[i f] (map-indexed vector forms)]
              (println (str "[" wrap " \"f" (inc i) "\" " f "]")))))))))
;; render output dir honors *resolve-out* (default $RESOLVE_OUT, then /tmp) so
;; concurrent gate runs / agents don't collide on a global /tmp/resolved-*.edn —
;; the gates set it to a per-run temp dir. *resolve-out* is the IN-PROCESS override
;; (System/getenv can't be set at runtime, so the warm-store driver binds this var
;; to route the verb's projection into a per-run dir without re-launching bb).
(def ^:dynamic *resolve-out* nil)
(defn out-path [src] (str (or *resolve-out* (System/getenv "RESOLVE_OUT") "/tmp")
                          "/resolved-" (-> src (str/split #"/") last) ".edn"))

;; --- no-capture invariant ---------------------------------------------------
;; Renaming def B to `new` is UNSOUND if a reference to B would, after rendering
;; as `new`, be captured by a LOCAL binding `new` in scope at that reference —
;; e.g. (def src 1)(defn f [dst] (+ dst src)), rename src->dst yields (+ dst dst).
;; This is the lexical dual of the def-vs-def collision guard: a reference that
;; resolves to B (unqualified, name-tracking — not a :rename-fixed or x/qualified
;; ref, which don't render as a bare `new`) is captured iff `new` is in its scope.
;; capture-refs reuses walk's exact frame construction so the check is scope-precise.
(defn renders-as-tracked-name? [node]            ; reference that will render the binding's CURRENT name
  (and (not (seq (c/by-lp ctx node FIXED)))      ; :rename — keeps its own spelling
       (not (pred-val node "qualifier"))))        ; x/name — renders alias/, can't be captured by a bare local
(defn capture-refs [node scope B new]            ; refs to B that a local `new` in scope would capture
  (case (kind-of node)
    "symbol" (if (and (refers-target node) (= B (ultimate (refers-target node)))
                      (renders-as-tracked-name? node) (some #(get % new) scope))
               [node] [])
    "list"
    (let [kids (ordered-children node) h (head-sym node)]
      (cond
        (PARAM-FORMS h)                          ; single- OR multi-arity (mirror walk)
        (let [after-name (if (#{"defn" "defn-" "defmacro"} h) (drop 2 kids) (rest kids))
              cap-arity (fn [forms]
                          (let [pv (first (filter brackets? forms))
                                frame (frame-of (if pv (param-binds pv) []))
                                or-vals (when pv (mapcat collect-or-vals (rest (ordered-children pv))))
                                body (loop [xs (rest (drop-while #(not (brackets? %)) forms))]
                                       (if (#{":-" ":" ":raises"} (sym-val (first xs))) (recur (drop 2 xs)) xs))]
                            (concat (mapcat #(capture-refs % scope B new) or-vals)        ; :or defaults: outer scope
                                    (mapcat #(capture-refs % (cons frame scope) B new) body))))]
          (if (some brackets? after-name)
            (cap-arity after-name)
            (mapcat (fn [a] (if (and (= "list" (kind-of a)) (brackets? (first (ordered-children a))))
                              (cap-arity (ordered-children a)) []))
                    after-name)))
        (LET-FORMS h)                            ; SEQUENTIAL: value/:or of binding i see bindings 0..i-1
        (let [bracket (second kids)
              pairs (if (and bracket (brackets? bracket)) (let-bind-pairs bracket) [])
              [final vcaps] (reduce (fn [[sc caps] [bsyms vnode orvals]]
                                      [(cons (frame-of bsyms) sc)
                                       (into caps (concat (mapcat #(capture-refs % sc B new) orvals)
                                                          (when vnode (capture-refs vnode sc B new))))])
                                    [scope []] pairs)]
          (concat vcaps (mapcat #(capture-refs % final B new) (drop 2 kids))))
        (FOR-FORMS h)
        (let [bracket (second kids)
              entries (if (and bracket (brackets? bracket)) (for-bind-pairs bracket) [])
              [final vcaps] (reduce (fn [[sc caps] e]
                                      (if (= :expr (first e))
                                        [sc (into caps (capture-refs (second e) sc B new))]
                                        (let [[_ bsyms vnode orvals] e]
                                          [(cons (frame-of bsyms) sc)
                                           (into caps (concat (mapcat #(capture-refs % sc B new) orvals)
                                                              (when vnode (capture-refs vnode sc B new))))])))
                                    [scope []] entries)]
          (concat vcaps (mapcat #(capture-refs % final B new) (drop 2 kids))))
        (MATCH-FORMS h)                          ; match clause bodies see the pattern's bound names
        (let [kids (ordered-children node)]
          (concat (capture-refs (second kids) scope B new)
                  (mapcat (fn [clause]
                            (if (brackets? clause)
                              (let [cc (rest (ordered-children clause)) pat (first cc) body (rest cc)
                                    frame (frame-of (match-pat-binds pat))]
                                (concat (capture-refs pat scope B new)   ; ctor heads (bind-vars have no refers_to)
                                        (mapcat #(capture-refs % (cons frame scope) B new) body)))
                              []))
                          (drop 2 kids))))
        (= h "letfn")                            ; fn names + each fn's params are bindings
        (let [bracket (second kids)
              fnlists (when (and bracket (brackets? bracket)) (filter #(= "list" (kind-of %)) (rest (ordered-children bracket))))
              frame (frame-of (keep #(first (ordered-children %)) fnlists))
              bodyscope (cons frame scope)
              cap-arity (fn [forms]              ; one fn impl: param frame over bodyscope
                          (let [pv (first (filter brackets? forms))
                                pframe (frame-of (if pv (param-binds pv) []))
                                fbody (loop [xs (rest (drop-while #(not (brackets? %)) forms))]
                                        (if (#{":-" ":" ":raises"} (sym-val (first xs))) (recur (drop 2 xs)) xs))]
                            (mapcat #(capture-refs % (cons pframe bodyscope) B new) fbody)))]
          (concat (mapcat (fn [fl] (cap-arity (rest (ordered-children fl)))) fnlists)
                  (mapcat #(capture-refs % bodyscope B new) (drop 2 kids))))
        (#{"extend-type" "extend-protocol"} h)   ; impl method params are bindings
        (mapcat (fn [c]
                  (if (= "list" (kind-of c))
                    (let [ic (ordered-children c) pv (first (filter brackets? (rest ic)))
                          pframe (frame-of (if pv (param-binds pv) []))
                          fbody (loop [xs (rest (drop-while #(not (brackets? %)) (rest ic)))]
                                  (if (#{":-" ":" ":raises"} (sym-val (first xs))) (recur (drop 2 xs)) xs))]
                      (concat (capture-refs (first ic) scope B new)        ; method name ref
                              (mapcat #(capture-refs % (cons pframe scope) B new) fbody)))
                    (capture-refs c scope B new)))                          ; Type / Proto refs
                (rest kids))
        (= h "as->")                             ; accumulator `name` binds in every step
        (let [init (nth kids 1 nil) name (nth kids 2 nil)
              frame (frame-of (when (sym-val name) [name]))]
          (concat (when init (capture-refs init scope B new))
                  (mapcat #(capture-refs % (cons frame scope) B new) (drop 3 kids))))
        :else (mapcat #(capture-refs % scope B new) kids)))
    []))

;; --- authoring support (used by the upsert-form / set-body case arms) -------
;; re-resolve!: after a mint, the module frame is stale (a new def, or a new body's
;; references). Recompute every module's frame + re-walk forms so fresh references
;; carry refers_to. Idempotent — bind! only adds an edge where one resolves.
(defn re-resolve! []
  (let [modframe  (into {} (map (fn [s] [s (module-defs s)]) srcs))
        typeframe (into {} (map (fn [s] [s (module-types s)]) srcs))
        accessors (into {} (map (fn [s] [s (module-accessors s)]) srcs))]
    (doseq [src srcs]
      (binding [*xresolve* (make-xresolve src)
                *tresolve* (fn [nm] (get (get typeframe src) nm))
                *aresolve* (fn [nm] (get (get accessors src) nm))]
        (walk-all (forms-of src) (list (get modframe src)))
        (walk-comments src)))))
(defn author-emit! [op detail]
  (doseq [src srcs] (extract-file! src (out-path src)))
  (binding [*out* *err*]
    (println (str "================ authoring: " op " ================"))
    (println detail)
    (doseq [src srcs] (println (str "projected -> " (out-path src) "   <- " src)))))

;; ============================================================================
;; STANDALONE AUTHORING VERBS — the SAME arm bodies as the -main case, lifted to
;; named functions so BOTH drivers can run them: the TEXT path (-main, over
;; resolve-edn! of emit-edn(text)) AND the GRAPH path (run-verb-warm!, over a
;; LOG-booted warm store via resolve-warm-store!). They are store-agnostic by
;; construction — they read the dynamic ctx/tx/SUP/srcs/frame tables and write
;; via c/fact!/retire-fact!/mint-datum!, never touching text — so the same code
;; runs unchanged under either binding scope.
;;
;; *project-srcs* selects which module(s) author-emit! / extract-file! project.
;; The TEXT path projects EVERY src (whole-corpus EDN round-trip, the old shape).
;; The GRAPH path binds it to just the affected module — render-from-store needs
;; only that one, and projecting the 11-module warm corpus would be wasteful.
;; Default = nil => "all srcs" (verbatim text-path behavior).
(def ^:dynamic *project-srcs* nil)
(defn- emit-srcs [] (or *project-srcs* srcs))
;; *capture-only?* — the MINIMAL-OP graph edit (daemon :edit-min) runs the verb ONLY
;; to capture its fact mint/supersede ops; it does NOT want the verb's two heavy
;; downstream SIDE EFFECTS: (1) re-resolve! (a whole-corpus lexical re-walk that
;; writes DERIVED refers_to edges — discarded, since the daemon re-resolves SCOPED
;; over the real store after the commit), and (2) author-emit-scoped! (rendering the
;; module's resolved EDN to disk — the minimal path commits fact ops, not text).
;; Bound true by do-edit-min so the verb does its fact work and stops. The CLI/text
;; path leaves it false => verbatim behavior (re-resolve + project EDN).
(def ^:dynamic *capture-only?* false)
;; like author-emit!, but only over *project-srcs* (the affected module on the graph path).
(defn author-emit-scoped! [op detail]
  (when-not *capture-only?*
    (doseq [src (emit-srcs)] (extract-file! src (out-path src)))
    (binding [*out* *err*]
      (println (str "================ authoring: " op " ================"))
      (println detail)
      (doseq [src (emit-srcs)] (println (str "projected -> " (out-path src) "   <- " src))))))

;; D1: resolve a scope to its target module at a dot-SEGMENT boundary. A raw substring
;; filter (str/includes?) collides a module with any sibling it PREFIXES — scope
;; "cheshire.generate" matched BOTH "cheshire.generate" AND "cheshire.generate_seq" ->
;; rejected as ambiguous (code 3), leaving the module unauthorable. But scope may
;; legitimately be the SHORT module name ("schema" for module "fram.schema"), which the
;; old substring also bridged, so a naive `=` over-tightens (0 matches). The right rule
;; matches only on a `.`-delimited segment boundary: scope equals the module, or is a
;; trailing dotted-suffix of it ("schema" ⊴ "fram.schema" ✓; "cheshire.generate" ⋬
;; "…generate_seq" ✗). Test both the raw src string (warm store keys srcs by module name)
;; and its ns-form module name (the EDN/CLI path keys srcs by the `@file` value) — each
;; direction covers short↔qualified without reopening the prefix-sibling collision.
(defn- scope-match? [src scope]
  (letfn [(seg? [m] (boolean (and m (or (= m scope) (str/ends-with? m (str "." scope))))))]
    (or (seg? src) (seg? (module-name src)))))
(defn- scope->srcs [scope] (filter #(scope-match? % scope) srcs))

;; datum->canon / node->canon are defined further down; forward-declare so the upsert
;; victim finder can compare a NEW datum's dispatch/target against an EXISTING node's —
;; both reduce to the SAME canonical vector, so the match is representation-independent.
(declare datum->canon node->canon)
;; writable-victim — the existing top-level FORM node this upsert should REPLACE (nil ->
;; APPEND). Identity is head-shaped:
;;   named (def/defn/type/protocol/defmulti) -> the def NAME (meta/Params unwrapped),
;;     matched by string so `def`->`defn` upsert and metadata-named defs round-trip;
;;   defmethod -> multimethod name + dispatch value (canon-compared) — two methods on the
;;     same M with different dispatch are DISTINCT and coexist; re-writing one replaces it;
;;   extend-type/extend-protocol/extend -> head + primary target (the form's second child),
;;     so re-authoring `(extend-type T …)` replaces the T block. (Multiple extend blocks on
;;     the same target under one head collapse to one identity — a known, documented bound.)
(defn- node-def-name [f]                       ; meta/Params-unwrapped def NAME of a node
  (let [nl0 (unwrap-meta (second (ordered-children (unwrap-def f))))
        nl  (if (= "list" (kind-of nl0)) (first (ordered-children nl0)) nl0)]
    (sym-val nl)))
(defn- writable-victim [src datum]
  (let [head  (str (first datum))
        forms (rest (ordered-children (wrapper-of src)))]
    (cond
      (= head "defmethod")
      (let [m  (str (second datum))
            dv (datum->canon (nth (vec datum) 2 nil))]        ; dispatch value, canon
        (some (fn [f] (let [d (unwrap-def f) k (ordered-children d)]
                        (when (and (= "defmethod" (head-sym d))
                                   (= m (sym-val (second k)))
                                   (= dv (node->canon (nth (vec k) 2 nil))))
                          f)))
              forms))
      (contains? EXTEND-FORMS head)
      (let [tgt (datum->canon (second datum))]                ; primary target, canon
        (some (fn [f] (let [d (unwrap-def f)]
                        (when (and (= head (head-sym d))
                                   (= tgt (node->canon (second (ordered-children d)))))
                          f)))
              forms))
      :else                                                   ; named identity
      (let [nm (str (second datum))]
        (some (fn [f] (when (and (named-def-head? (head-sym (unwrap-def f)))
                                 (= nm (node-def-name f)))
                        f))
              forms)))))
;; a human label for the upserted form (author-emit + result naming). For a value def it
;; is the name; for defmethod it is `M:dispatch`; for an extension it is `head <target>`.
(defn writable-disp-name [datum]
  (let [head (str (first datum))]
    (cond
      (= head "defmethod")      (str (second datum) ":" (pr-str (nth (vec datum) 2 nil)))
      (contains? EXTEND-FORMS head) (str head " " (pr-str (second datum)))
      :else                     (str (second datum)))))

;; ============================================================================
;; replace-in-body — SUB-DEF surgical edit. Replace ONE interior form inside a def,
;; addressed by an ANCHOR datum (the OLD form, as it reads in source), with a NEW
;; form — WITHOUT re-emitting the whole def. Kills the mega-def floor (duel lever #2):
;; changing one case in a 1,768-line def costs ONE fN-edge swap, not a whole-def
;; re-mint. Same fact-op discipline as set-body — supersede exactly the touched fN
;; edge, mint the replacement, recompile-gated + fail-closed — but at INTERIOR
;; granularity. Because only one edge moves and the def is never re-minted, EVERY
;; sibling form + all attached comments (the def's leading comments, other cases)
;; survive untouched — it does not carry set-body's comment-fidelity loss (019f208b-d67b).
;;
;; ADDRESSING — anchor-form match (Edit-tool old_string, but on the AST). The model
;; emits the OLD interior form + the NEW one; we canonicalize both STRUCTURALLY (kind
;; + rendered spelling + child shape, whitespace/formatting ignored) and require the
;; anchor to match EXACTLY ONE interior node. 0 or >1 matches REFUSE (no facts
;; mutated) — the model disambiguates by supplying a larger enclosing form, exactly
;; like old_string uniqueness. Structural (not textual) match is why the model need
;; not reproduce whitespace: it emits the form, we compare shape.
;;
;; render-sym — the spelling a symbol node RENDERS as (mirrors extract-file!'s
;; reference-rendering): a resolved reference shows its binding's CURRENT name
;; (mode-adjusted: ctor prefix, x/qualifier, :rename keep-spelling); an unresolved /
;; literal symbol shows its stored v. Matching the anchor against the RENDERED
;; spelling (not the stored v) is what makes the model's old-form — read off the
;; current source text — line up with the graph even after a prior graph rename.
(defn render-sym [e] (rv/render-sym ctx *view* BOUND REFERS FIXED e))
;; canonical comparison form — structural, formatting-insensitive. Leaf -> [:leaf kind
;; spelling]; list -> [:list child-canon...]. Both an anchor DATUM (as clojure.edn read
;; it) and a graph NODE canonicalize into the SAME shape, re-encoding EDN nil/bool/
;; keyword the beagle way (mint-datum!'s conventions: beagle reads .b* via Racket, so
;; nil/true/false/:kw are all SYMBOL leaves) so `nil`/`true`/`:foo` match their storage.
(defn datum->canon [d]
  (cond
    (nil? d)      [:leaf "symbol" "nil"]
    (symbol? d)   [:leaf "symbol" (str d)]
    (keyword? d)  [:leaf "symbol" (str d)]
    (boolean? d)  [:leaf "symbol" (if d "true" "false")]
    (string? d)   [:leaf "string" d]
    (char? d)     [:leaf "char"   (str d)]
    (number? d)   [:leaf "number" (str d)]
    (vector? d)   (into [:list [:leaf "symbol" "#%brackets"]] (map datum->canon d))
    (map? d)      (into [:list [:leaf "symbol" "#%map"]] (map datum->canon (apply concat (seq d))))
    ;; regex/set: SAME `#%…`-headed canon mint-datum! + node->canon produce, so anchor
    ;; matching + :within self-validation round-trip these reader forms (mirror of the
    ;; write-def mint fix — a bare :else pr-str leaf here mismatches the stored node).
    (instance? java.util.regex.Pattern d)
                  [:list [:leaf "symbol" "#%regex"] [:leaf "string" (.pattern ^java.util.regex.Pattern d)]]
    (set? d)      (into [:list [:leaf "symbol" "#%set"]] (map datum->canon d))
    (or (list? d) (seq? d)) (into [:list] (map datum->canon d))
    :else         [:leaf "other" (pr-str d)]))
;; anchor-matches — single POST-ORDER pass over a def form's subtree that computes each
;; node's canon EXACTLY ONCE (O(N), not O(N^2) — a naive "canonize every candidate" re-walks
;; each subtree per candidate and blows up on a 10k-node mega-def). Returns every
;; [parent pos-literal edge-cid child-node] fN edge whose child's canon equals `target`.
;; Children are visited in CRDT (ord-key) order so the list canon matches datum order.
;; The def-form ROOT itself is never a candidate (only CHILDREN are recorded) — replacing
;; a whole top-level def is upsert-form's job, not this verb's.
(defn ord-edges [n] (rv/ord-edges ctx n))   ; [ord-key pos-lit cid child] fN edges of n, CRDT-ordered
(defn anchor-matches [root target] (rv/anchor-matches ctx *view* BOUND REFERS FIXED root target))

;; ============================================================================
;; AUTO-DISAMBIGUATION (019f22bd-137d) — on an ambiguous/failed anchor, hand the
;; model a STRUCTURED remedy instead of a bare match count. Two mechanisms:
;;   (1) :candidates on REJECT — per match site, a parent-chain breadcrumb + a
;;       copy-pastable enclosing form (:within) that isolates THAT occurrence.
;;   (2) :within scope-narrowing — the caller supplies an enclosing anchor; the
;;       :old match must be unique WITHIN it. Fail-closed at every level (exactly
;;       one match or reject). Structural, not ordinal — survives concurrent edits.
;; ============================================================================
;; node->str / node->canon — render a graph node back to source. node->str is a
;; compact, copy-pastable STRING (references via their CURRENT name, like render-sym;
;; structural fN children only, CRDT-ordered; comments/tails omitted). node->canon is
;; the SAME canonical shape datum->canon + anchor-match-sites compute, so a suggested
;; :within is SELF-VALIDATING: we offer it only when (datum->canon (edn/read it)) ==
;; the node's canon (round-trips) AND it matches exactly one interior form.
(defn node->str [n] (rv/node->str ctx *view* BOUND REFERS FIXED n))
(defn node->canon [n] (rv/node->canon ctx *view* BOUND REFERS FIXED n))
;; anchor-match-sites — anchor-matches, but each match also carries its ENCLOSING
;; ANCESTOR CHAIN (def-root .. immediate-parent, descent order) so a reject can build
;; breadcrumbs + a distinctive :within suggestion. Still ONE O(N) post-order pass
;; (canon computed once per node). Returns [{:parent :pos :cid :child :chain} ...];
;; the search ROOT itself is never a candidate (only its interior children).
(defn anchor-match-sites [root target] (rv/anchor-match-sites ctx *view* BOUND REFERS FIXED root target))
;; a short, distinctive label for one breadcrumb rung (an enclosing form's head).
(defn- crumb-label [n] (rv/crumb-label ctx *view* n))
;; DISAMBIG-CAP — at most this many candidates in a reject payload (report the total).
(def DISAMBIG-CAP rc/DISAMBIG-CAP)
;; build the reject candidate for match site `site`, relative to search-root `root`,
;; given the OTHER sites (to find the smallest enclosing form that isolates THIS one).
(defn- reject-candidate [root site others idx]
  (let [chain       (:chain site)
        breadcrumb  (mapv crumb-label chain)
        parent      (:parent site)
        other-nodes (into #{} (mapcat :chain others))
        ;; :within — smallest enclosing form (deepest ancestor identity-distinct from
        ;; every other site) whose source round-trips AND matches exactly one interior
        ;; form of the def. Guarantees a valid, copy-pastable scope-narrower.
        within (some (fn [a]
                       (when-not (contains? other-nodes a)
                         (let [ac (node->canon a) s (node->str a)]
                           (when (and (<= (count s) 800)
                                      (= ac (try (datum->canon (edn/read-string s)) (catch Throwable _ nil)))
                                      (= 1 (count (anchor-match-sites root ac))))
                             s))))
                     (reverse chain))
        ctx-str (let [s (node->str parent)] (if (<= (count s) 200) s (str (subs s 0 197) "...")))]
    {:n idx :breadcrumb breadcrumb :within within :context ctx-str}))
;; assemble the structured disambiguation payload (+ a human-readable :message) the
;; daemon threads through *reject!* into `handle`'s reject response.
(defn- disambig-payload [reason name scope root sites]
  (let [total (count sites)
        shown (vec (map-indexed (fn [i s] (reject-candidate root s (concat (take i sites) (drop (inc i) sites)) (inc i)))
                                (take DISAMBIG-CAP sites)))
        head  (case reason
                :ambiguous-old   (str "anchor `old` is AMBIGUOUS inside `" name "` in \"" scope "\" ("
                                      total " matches; no facts mutated).")
                :ambiguous-within (str "`within` is AMBIGUOUS inside `" name "` in \"" scope "\" ("
                                       total " matches; no facts mutated). It must match exactly one enclosing form."))
        lines (map (fn [c]
                     (str "  [" (:n c) "] " (str/join " > " (:breadcrumb c))
                          (when (:within c) (str "\n      within: " (:within c)))))
                   shown)
        remedy (case reason
                 :ambiguous-old   "Retry with :within set to one candidate's `within` form (it isolates that occurrence), or supply a larger :old."
                 :ambiguous-within "Supply a `within` that names exactly one enclosing form (use a larger/more distinctive form).")]
    {:reason reason :verb "replace-in-body" :name name :scope scope
     :total total :shown (count shown) :candidates shown :remedy remedy
     :message (str "REJECTED — " head "\n" (str/join "\n" lines)
                   "\n  remedy: " remedy)}))

;; wrap-forms — the wrapper's top-level form edges in CRDT (path,tie) order.
;; -> [[{:path :tie} cid child] ...]. Dual-parse (old f<int> + new f<path>~tie).
(defn wrap-forms [parent]
  (->> (c/by-l ctx parent)
       (keep (fn [cid] (let [cl (c/fact-of ctx cid)
                             k (ord-parse (c/literal ctx (:p cl)))]
                         (when k [k cid (:r cl)]))))
       (sort-by first ord-cmp) vec))

;; ============================================================================
;; M1 Cut H — THE VERB LAYER is now Beagle (src/resolve_verbs.bclj): every
;; authoring verb's invariant order, reject codes/messages, fact mutations and
;; emit reporting. As in Cuts B–G the ^:dynamic vars STAY here (coord_daemon.clj
;; and tests/*.clj `binding` them by qualified name), so `verb-env` reads the
;; dynamic state at call time and hands it over as ONE explicit record — together
;; with the resolve.clj-local helpers the verbs call (mint/retire/extract/
;; capture-refs/…), which ride as function VALUES exactly as Cut G's xres/tres/
;; ares did. `binding [*out* *err*]` cannot move namespace, so stderr reporting is
;; the `warn` closure, called once per LINE (the goldens compare bytes).
;; *reject!* is passed at BOTH arities: callers bind it as `(fn [code] …)` (the
;; unit tests) or `(fn [code & [detail]] …)` (the daemon), so each call site keeps
;; the arity the original used — 1 everywhere but replace-in-body's structured
;; disambiguation payload. Docstrings + per-def rationale live in the module header.
;; ============================================================================
(defn verb-env []
  (rvb/->Verb ctx *view* tx SUP KIND Vp (vec srcs) *capture-only?* (vec (emit-srcs))
              (fn [code] (*reject!* code))
              (fn [code detail] (*reject!* code detail))
              (fn [line] (binding [*out* *err*] (println line)))
              author-emit-scoped!
              (fn [src] (extract-file! src (out-path src)))
              out-path
              def-binding file-typeframe file-modframe forms-of module-name
              parse-require capture-refs ultimate
              BOUND REFERS wrapper-of wrap-forms form-for-victim descendants
              retire-fact! re-resolve! @file->ents
              mint-datum! register! scope->srcs writable-victim writable-disp-name fN-facts
              FIXED datum->canon disambig-payload (fn [code] (System/exit code))))

;; rename — every INVARIANT + fact mutation from the old `rename` case arm.
(defn verb-rename! [old new target] (rvb/verb-rename! (verb-env) old new target))


;; upsert-form — add/replace a top-level def from an EDN datum spec (M1 Cut H;
;; logic in src/resolve_verbs.bclj). A REPLACE reuses the victim's CRDT path (new
;; tie) and retires the victim's edge; an APPEND lands strictly after the last form.
(defn verb-upsert-form! [scope datum] (rvb/verb-upsert-form! (verb-env) scope datum))

;; insert-form — the CRDT MIDDLE-INSERT (#36): a def AFTER an anchor def, at a path
;; strictly between the anchor and its next sibling (M1 Cut H). Concurrent inserts
;; after the same anchor compute the same path -> distinct tie -> both land (commute).
(defn verb-insert-form! [scope after-name datum] (rvb/verb-insert-form! (verb-env) scope after-name datum))

;; insert-comment — author a standalone LINE comment (Turtle #6) leading/trailing an
;; anchor def (M1 Cut H): a kind="comment" node + one `text` seg (the lexeme, which
;; renders verbatim) + the `commentN` edge that attaches it to the anchor FORM.
(defn verb-insert-comment! [scope anchor-name text placement]
  (rvb/verb-insert-comment! (verb-env) scope anchor-name text placement))


;; set-body — replace a def/defn's body with a freshly-minted body datum (M1 Cut H;
;; logic in src/resolve_verbs.bclj). Handles BOTH shapes — a defn, whose body follows
;; the [param] vector, and a plain value-def, whose body follows the NAME — symmetric
;; under an optional `:- T` return annotation.
(defn verb-set-body! [name scope datum] (rvb/verb-set-body! (verb-env) name scope datum))

;; verb-replace-in-body! — swap ONE interior form of def `name` (matched by `old-datum`)
;; for `new-datum` (M1 Cut H; logic in src/resolve_verbs.bclj). Reuses the matched edge's
;; EXACT position literal (integer fN preserved -> byte-stable), so the def is never
;; re-minted and every sibling form + attached comment survives. Optional `within-datum`
;; narrows the search to the (unique) enclosing form it matches — fail-closed at every
;; level. On an ambiguous/absent match the reject carries a structured disambiguation
;; payload (:candidates + :within suggestions) via *reject!*.
(defn verb-replace-in-body!
  ([name scope old-datum new-datum] (verb-replace-in-body! name scope old-datum new-datum nil))
  ([name scope old-datum new-datum within-datum]
   (rvb/verb-replace-in-body! (verb-env) name scope old-datum new-datum within-datum)))

;; delete — remove a top-level def by name (M1 Cut H; logic in
;; src/resolve_verbs.bclj). FACT-NATIVE + fail-closed: the EFFECT is a supersede of
;; the wrapper's fN form-edge fact(s) pointing at the deleted form(s), so the
;; minimal-op harvest sees a RETRACT and the render reachability filter drops the
;; orphaned subtree — ONE mechanism, both drivers. A delete that would ORPHAN a
;; surviving reference REFUSES (no facts mutated).
(defn verb-delete! [name scope] (rvb/verb-delete! (verb-env) name scope))

;; reorder — MOVE a def to a new position by RE-SPELLING its wrapper order key
;; (M1 Cut H; logic in src/resolve_verbs.bclj), NOT by re-minting the form:
;; insert+delete would churn node identity. `:after nil`/"" moves it to the FRONT.
(defn verb-reorder! [name scope after-name] (rvb/verb-reorder! (verb-env) name scope after-name))


;; ============================================================================
;; run-verb-warm! — THE GRAPH EDIT PATH. Run an authoring verb over a LOG-booted
;; warm store (NOT emit-edn of text). `store` is `(migrate-flat->co code.log)`'s
;; :store; resolve-warm-store! binds ctx=store + tx + the store-local value-ids +
;; corpus-from-store! (srcs/frames derived from the store's `name` facts), runs
;; the lexical walk, then invokes our body. Inside that scope we bind
;; *project-srcs* to the affected module and call the SAME verb function the text
;; path calls — minting/superseding fact ops against LOG-RESIDENT node identity,
;; projecting render EDN for ONLY that module. NO src/fram/*.bclj is ever read:
;; the corpus, the verb's targets, and the projection all come from the store.
;;
;; `spec` is {:op "rename"|"upsert-form"|"set-body" + verb args + optional
;; :resolve-out (the in-process projection dir; bound to *resolve-out* so the
;; verb's extract-file! writes resolved EDN there without re-launching bb)}.
;; Returns the affected module name (so the caller renders + commits exactly that
;; module). The store is mutated IN PLACE; the caller renders the affected module
;; FROM the projected EDN — the .bclj is downstream of the log.
;; ============================================================================
;; M1 Cut H — the OP TABLE is now Beagle (rvb/dispatch-verb!). What stays here is
;; the dynamic scaffolding it runs inside: *resolve-out* (the in-process projection
;; dir) and *project-srcs* (the affected module, so the verb projects ONLY that one
;; instead of the whole warm corpus) are ^:dynamic vars, and a var cannot change
;; namespace. Inside that scope the dispatch is ordinary — the SAME verb functions
;; the text path calls, over the LOG-booted warm store.
(defn run-verb-warm! [store spec]
  (let [module (:module spec)]
    (binding [*resolve-out* (:resolve-out spec)]      ; nil => env/$RESOLVE_OUT/tmp
      (resolve-warm-store!
       store
       (fn []
         (binding [*project-srcs* (when module
                                    (filter #(str/includes? % module) srcs))]
           (rvb/dispatch-verb! (verb-env) spec)))))
    module))

;; ============================================================================
;; call graph — the scope-correct calls_defn edges + transitive blast radius.
;; ============================================================================
;; Factored out of the `callgraph` MODE so the daemon's warm :blast/:concern-overlap,
;; the offline `callgraph` mode, and codegraph/src/callgraph.bclj all share ONE derivation
;; (call-edges) and ONE reaches closure (blast-closure) — the per-query throwaway-store
;; rebuild now lives in exactly one place (decision J: "one implementation shared by
;; concern-overlap and who-calls").

;; call-edges — scope-correct defn->defn edges over the CURRENTLY BOUND corpus
;; (srcs/file-modframe + materialized refers_to must already be in scope — call under
;; with-resolve-read / resolve-edn!). A "call" is any resolved reference inside a
;; top-level defn's body whose binding (via refers_to, transitively) is itself a
;; top-level defn; the caller is that enclosing defn. Edges are keyed on the binding's
;; NODE entity-id (@mod#int identity — rename-stable, scope-correct: same-named fns in
;; different modules are distinct nodes, so they never false-merge). Returns
;; {:defn-meta {leaf -> {:key :file :module :name}} :edges [[caller-leaf callee-leaf]]
;; :defn-set #{leaf}} — the daemon joins footprint @concern->@node against :edges; the
;; CLI maps leaf->:key for JSON.
(defn call-edges [] (rq/call-edges ctx *view* BOUND REFERS (vec srcs) file-modframe @file->ents))

;; blast-closure — transitive blast radius over a set of [caller callee] edges via Fram
;; Datalog: blast(D) = {x | x transitively calls D} = D's transitive callers (who breaks
;; if D changes). Edge keys are any hashable (node-ids for the warm path, "src#leaf"
;; strings for JSON). Returns {:reaches #{[x y]} :blast {callee -> #{transitive-callers}}}.
;; The ONE reaches implementation; the throwaway recursion store lives only here.
(defn blast-closure [edges] (rq/blast-closure! (vec edges)))

;; binding-privacy — {binding-leaf -> :public | :private} over the CURRENTLY BOUND corpus.
;; A top-level `def-`/`defn-` is PRIVATE; every other top-level value binding (def/defn/
;; defonce/fn/…) is PUBLIC — a reachability ROOT. Keyed on the binding NODE (identity), so
;; same-spelling bindings in different modules stay DISTINCT (a private `helper` in mod A is a
;; different node than a public `helper` in mod B). Call under with-resolve-read / resolve-edn!.
(defn binding-privacy [] (rq/binding-privacy ctx *view* (vec srcs) @file->ents))

;; dead-private-bindings — the identity-keyed STRATIFIED-Datalog code query. Over the
;; scope-correct call graph (call-edges), derive the PRIVATE top-level bindings UNREACHABLE
;; from any PUBLIC binding. Two strata via fram.datalog/run-strata:
;;   (1) POSITIVE + RECURSIVE live reachability — live(x) seeded from every public root
;;       (is-root self-loop), grown along calls(x,y); a reachable private chain is live.
;;   (2) NEGATED dead — dead(p) :- private(p), ¬live(p). An unreachable private recursive
;;       cycle is NEVER seeded by a root, so positive reachability skips it -> it is dead.
;; Because everything is keyed on the @mod#int NODE, same-spelling cross-module bindings are
;; classified independently. Returns the set of dead private binding leaves. `privacy` maps
;; leaf -> :public|:private (binding-privacy); a leaf absent from it (e.g. a protocol method)
;; is treated as a non-private root, never dead.
(defn dead-private-bindings [cg privacy] (rq/dead-private-bindings! cg privacy))

;; ============================================================================
;; CLI entry. Slice the edn paths off *command-line-args* per mode (the old
;; `(def srcs ...)` slice), then run the WHOLE pipeline + mode dispatch inside
;; one `resolve-edn!` binding scope, so dispatch reads the freshly-bound store /
;; tables / counters exactly as the old top-level code did. GUARDED: loaded as a
;; library (no recognized mode), nothing runs — no load-edn over mis-sliced args.
(def MODES rc/MODES)
(defn -main []
  (let [;; strip an optional `--within-file <path>` flag (replace-in-body's scope-narrower)
        ;; so it never lands in the edn-paths slice below (load-edn would slurp it as a file).
        raw      (vec *command-line-args*)
        fi       (.indexOf raw "--within-file")
        stripped (if (neg? fi) raw (concat (take fi raw) (drop (+ fi 2) raw)))
        edn-paths (drop (case mode "resolve" 1 "rename" 4 "delete" 3 "reorder" 4 "callgraph" 1
                                   "upsert-form" 3 "set-body" 4 "replace-in-body" 5)
                        stripped)]
    (resolve-edn!
     edn-paths
     (fn []
(case mode
  "resolve"
  (binding [*out* *err*]
    (println "================ Turtle #5 — lexical resolution pass ================")
    (println (str "references resolved (carry refers_to → a binding node): " @n-resolved
                  "  (" @n-xmod " cross-module, " @n-type " type references)"))
    (println (str "unresolved (builtins / native — correctly NO refers_to): " @n-unresolved))
    (println (str "comment identifier mentions resolved (rename-correct doc comments): " @n-comment))
    ;; write the resolved projection so identity can be checked: with NO rename,
    ;; projecting through refers_to must reproduce the original source exactly.
    (doseq [src srcs] (extract-file! src (out-path src)))
    (doseq [src srcs]
      (println (str "  " (-> src (str/split #"/") last) ": "
                    (count (filter #(and (= "symbol" (kind-of %)) (refers-target %)) (@file->ents src)))
                    " references carry refers_to; projected (identity) -> " (out-path src)))))

  "rename"
  (let [[old new target] (drop 1 *command-line-args*)]
    (verb-rename! old new target))

  ;; delete : remove a top-level def by name. Delegates to verb-delete! (the SAME
  ;; fact-native body the warm/minimal-op path runs) — the default *reject!* is
  ;; System/exit, so the CLI keeps its exit-code contract (5 no-victim, 6 orphan).
  "delete"
  (let [[name target] (drop 1 *command-line-args*)]
    (verb-delete! name target))

  ;; reorder : move a top-level def after an anchor (or to the front), in place.
  "reorder"
  (let [[name target after] (drop 1 *command-line-args*)]
    (verb-reorder! name target after))

  ;; ============================================================================
  ;; AUTHORING VERBS — the GAP closed: a fact operation for novel authoring.
  ;; upsert-form : add a NEW top-level def (append a wrapper fN edge) OR replace an
  ;;               existing top-level def by name (supersede its wrapper fN edge to
  ;;               point at a freshly-minted subtree). The form is given as an EDN
  ;;               datum (the structured edit spec), minted into the SAME store.
  ;; Both reuse extract-file! (the rename/delete render machine) and re-run the
  ;; lexical walk over the post-mint corpus, so a reference in the new code resolves
  ;; via refers_to (scope-correct) exactly like hand-written code — then the recompile
  ;; gate (authoring.sh) is the only acceptance criterion. fail-closed before that.
  ;; ============================================================================
  "upsert-form"
  (let [[scope spec-file] (drop 1 *command-line-args*)
        datum (edn/read-string (slurp spec-file))]
    (verb-upsert-form! scope datum))

  ;; set-body : replace a defn's BODY — supersede every post-params fN edge of the
  ;; named defn and re-wire to a freshly-minted body datum.
  "set-body"
  (let [[name scope body-file] (drop 1 *command-line-args*)
        datum (edn/read-string (slurp body-file))]
    (verb-set-body! name scope datum))

  ;; replace-in-body : SUB-DEF surgical edit — replace ONE interior form of the named
  ;; def (matched structurally by the OLD form) with a NEW form, WITHOUT re-emitting the
  ;; def. Anchor-form addressing (Edit-tool old_string on the AST): unique match required,
  ;; fail-closed on 0/>1. old-file/new-file each hold one EDN datum (the verb reads them).
  ;; An OPTIONAL `--within-file <path>` flag (anywhere after new-file; stripped from the
  ;; edn-paths slice in -main) holds an enclosing-form datum that narrows the search (the
  ;; `old` match must be unique WITHIN it) — the disambiguation remedy.
  "replace-in-body"
  (let [[name scope old-file new-file] (drop 1 *command-line-args*)
        within-file (second (drop-while #(not= "--within-file" %) *command-line-args*))
        old-datum (edn/read-string (slurp old-file))
        new-datum (edn/read-string (slurp new-file))
        within-datum (when within-file (edn/read-string (slurp within-file)))]
    (verb-replace-in-body! name scope old-datum new-datum within-datum))

  ;; ============================================================================
  ;; callgraph — the scope-correct call graph + transitive blast radius, derived
  ;; from the SAME refers_to edges the rename/delete engine uses. A "call" is a
  ;; reference in list-HEAD position whose binding (followed transitively) is a
  ;; top-level defn; the caller is its enclosing top-level defn. Because refers_to
  ;; is the converged cross-module/multi-arity/collision-correct resolution, this
  ;; call graph is too — unlike a bare-callname index, it does NOT drop qualified
  ;; (a/f, m/f) cross-module calls. Emits the JSON beagle-cascade consumes.
  ;; ============================================================================
  "callgraph"
  (let [{:keys [defn-meta edges] :as cg} (call-edges)
        key->s  (fn [leaf] (:key (defn-meta leaf)))
        edges-s (mapv (fn [[a b]] [(key->s a) (key->s b)]) edges)
        {:keys [reaches blast]} (blast-closure edges-s)
        ;; identity-keyed stratified-Datalog: private bindings unreachable from public roots.
        dead-priv (dead-private-bindings cg (binding-privacy))
        dead-s    (vec (sort (map key->s dead-priv)))]
    (binding [*out* *err*]
      (println (format "callgraph: %d defns, %d scope-correct edges, %d transitive reaches-pairs, %d dead private (refers_to + Fram Datalog)"
                       (count defn-meta) (count edges-s) (count reaches) (count dead-s))))
    (println (json/generate-string
              {:defns (vec (vals defn-meta)) :edges edges-s
               :blast (into {} (map (fn [[k vs]] [k (vec vs)]) blast))
               ;; ADDITIVE field — the dead-private derivation, keyed like :defns/:edges
               ;; ("src#leaf"). No Beagle syntax added; a consumer that ignores it is unaffected.
               :dead-private dead-s}))))))))

;; GUARD: run the pipeline only when invoked as a CLI with a recognized mode.
;; Loaded as a library (no mode arg, or an unrecognized one), this is a no-op —
;; so a daemon can `require`/load this file and call `resolve-edn!` over its own
;; warm store without the old top-level load-edn crashing on mis-sliced args.
(when (MODES mode) (-main))
