;; claims_spec_test.clj — EXECUTABLE SPECIFICATION for the claims module
;; (fram.claims), thread 019f9cf2-0bf6-725c-8bc4-63995db4332f, design
;; docs/archive/claims-design.md.
;;
;;   bb -cp out tests/claims_spec_test.clj        # from the repo ROOT
;;
;; SCOPE. The module's PUBLISHED SURFACE only: predicate spellings, view-family
;; roots, and the read-only export discipline. Lifecycle behaviour needs a
;; written FRAMLOG fixture, which this suite deliberately does not build.
;;
;; The word "claim" is legal ONLY in this module's namespace (docs/naming.md:
;; the substrate atom is a FACT; tests/vocab_ratchet_test.sh polices the border).
;;
;; NORMATIVE SURFACE this suite pins (all in fram.claims; every predicate is a
;; dotted lowercase name in the claim/evidence namespaces):
;;
;;   evidence-pred      "claim.evidence"        claim fact cid -> evidence node
;;   reason-pred        "claim.reason"          claim fact cid -> rejection reason
;;   source-pred        "evidence.source"       evidence node -> artifact id
;;   region-pred        "evidence.region"       evidence node -> app locator
;;   fingerprint-pred   "evidence.fingerprint"  evidence node -> content hash
;;   world-pred         "evidence.world"        evidence node -> VersionId (OPTIONAL)
;;   verified-view      "@view:claim.verified"  the verified-view family root
;;   rejected-view      "@view:claim.rejected"  the rejected-view family root
;;   (scoped-view root agent)   -> "<root>:<agent>", a verifier's OWN verdict view
;;
;; The evidence.world / world.record / world.version: spellings are DURABLE
;; FRAMLOG DATA, not the retired Worlds service: existing logs carry them, so
;; respelling them is a data migration, not a rename.
(require '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; harness — a failures atom, [PASS]/[FAIL] lines, and a per-bar reason so an
;; ABSENT module names itself instead of taking the whole suite down. `bar`
;; catches Throwable: absence and misbehaviour are the same contract failure,
;; reported at bar granularity.
;; ---------------------------------------------------------------------------
(def failures (atom 0))
(def total (atom 0))

(defn check
  ([nm ok?] (check nm ok? nil))
  ([nm ok? why]
   (swap! total inc)
   (println (str "  [" (if ok? "PASS" "FAIL") "] " nm
                 (when (and (not ok?) why) (str "  <- " why))))
   (when-not ok? (swap! failures inc))))

(defmacro bar [label & body]
  `(let [r# (try {:ok (boolean (do ~@body))}
                 (catch Throwable e# {:why (or (ex-message e#) (str e#))}))]
     (check ~label (:ok r#) (:why r#))))

(def module
  (try (require 'fram.claims) {:ok true}
       (catch Throwable _ {:ok false
                           :why "fram.claims is not on the classpath (the module does not exist)"})))

(defn cvar [nm]
  (when (:ok module) (try (ns-resolve 'fram.claims (symbol nm)) (catch Throwable _ nil))))

(defn cv
  "The Var for fram.claims/NM, or throw a per-bar-catchable absence."
  [nm]
  (or (cvar nm)
      (throw (ex-info (str "fram.claims/" nm " ABSENT"
                           (when-not (:ok module) (str " — " (:why module))))
                      {:missing nm}))))

(defn q [nm & args] (apply (cv nm) args))    ; call a module fn
(defn qd [nm] @(cv nm))                      ; deref a module constant

;; The vocabulary as the APP spells it. Section 1 pins that the module's
;; constants EQUAL these literals — the dependency runs app -> module, never the
;; other way round.
(def ev-pred    "claim.evidence")
(def reason-pd  "claim.reason")
(def src-pred   "evidence.source")
(def rgn-pred   "evidence.region")
(def fp-pred    "evidence.fingerprint")
(def wld-pred   "evidence.world")
(def v-root     "@view:claim.verified")
(def r-root     "@view:claim.rejected")
(def alice-v    (str v-root ":alice"))

(println "claims — executable specification (fram.claims)")
(when-not (:ok module)
  (println (str "  NOTE: " (:why module)
                " — every claims bar below FAILS by absence, as expected of a spec-first module.")))

;; ===========================================================================
(println "\n-- 1. vocabulary: predicate spellings, scoped to this module --")
;; ===========================================================================
(bar "vocab: evidence-pred is \"claim.evidence\"" (= ev-pred (qd "evidence-pred")))
(bar "vocab: reason-pred is \"claim.reason\"" (= reason-pd (qd "reason-pred")))
(bar "vocab: source-pred is \"evidence.source\"" (= src-pred (qd "source-pred")))
(bar "vocab: region-pred is \"evidence.region\"" (= rgn-pred (qd "region-pred")))
(bar "vocab: fingerprint-pred is \"evidence.fingerprint\"" (= fp-pred (qd "fingerprint-pred")))
(bar "vocab: world-pred is \"evidence.world\"" (= wld-pred (qd "world-pred")))
(bar "vocab: the verified-view family root is \"@view:claim.verified\""
     (= v-root (qd "verified-view")))
(bar "vocab: the rejected-view family root is \"@view:claim.rejected\""
     (= r-root (qd "rejected-view")))
(bar "vocab: scoped-view names a verifier's OWN view under the family root"
     (= alice-v (q "scoped-view" (qd "verified-view") "alice")))
(bar "vocab: every predicate is dotted lowercase in the claim/evidence namespace"
     (every? #(re-matches #"(claim|evidence)\.[a-z]+" %)
             [(qd "evidence-pred") (qd "reason-pred") (qd "source-pred")
              (qd "region-pred") (qd "fingerprint-pred") (qd "world-pred")]))
(bar "vocab: NO marker predicate — claimhood is evidence and/or a verdict, nothing else"
     (let [_ (cv "status")]
       (not-any? #(re-find #"(?i)is-claim|claim\.kind|claim\.type|claim\.status" (name %))
                 (keys (ns-publics 'fram.claims)))))

;; ===========================================================================
(println "\n-- 2. zero engine mutation: the module READS, it never writes --")
;; ===========================================================================
(bar "purity: fram.claims exports NO write verb — no public name ends in \"!\""
     (let [_ (cv "status")]
       (not-any? #(str/ends-with? (name %) "!") (keys (ns-publics 'fram.claims)))))

;; ---------------------------------------------------------------------------
(let [pass (- @total @failures)]
  (println (str "\nclaims-spec: " pass "/" @total " PASS"))
  (if (zero? @failures)
    (println "claims-spec: ALL BARS PASS")
    (do (println (str "claims-spec: " @failures " FAILED — these bars DEFINE the claims"
                      " module; implement it, never weaken them."))
        (System/exit 1))))
