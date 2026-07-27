;; claims_spec_test.clj — EXECUTABLE SPECIFICATION for the claims module
;; (fram.claims), thread 019f9cf2-0bf6-725c-8bc4-63995db4332f, design
;; docs/claims-design.md.
;;
;;   bb -cp out tests/claims_spec_test.clj        # from the repo ROOT
;;
;; THIS SUITE IS EXPECTED TO FAIL TODAY — that is the deliverable. There is no
;; src/fram/claims.bclj and no out/fram/claims.clj, so every claims bar below
;; reports "ABSENT" and fails INDIVIDUALLY with a named reason instead of the
;; suite dying at load time. The contract is stated before the module exists.
;; Do not weaken a bar to make it pass; implement fram.claims (graph-upstream,
;; via the mcp__fram graph-edit verbs, like fram.world) until the bars go green.
;;
;; WHAT A CLAIM IS. A claim is an ORDINARY FACT that participates in a
;; verification discipline expressed as MORE FACTS. There is no claim atom, no
;; marker predicate and no engine change: the module is a predicate vocabulary,
;; a set of Datalog rules, and the views mechanics the engine already has
;; ((view selects @cid) — docs/VIEWS_AND_BRANCHES.md §8, coord.clj select!).
;; The word "claim" is legal ONLY in this module's namespace (docs/naming.md:
;; the substrate atom is a FACT; tests/vocab_ratchet_test.sh polices the border).
;;
;; SECTION 0 IS THE HEADLINE AND MUST PASS. It drives the entire lifecycle —
;; claim, cite, verify, un-verify, dispute, re-verify, and a two-version world —
;; through ops that exist TODAY (coord.clj commit!/select!, fram.schema
;; assert!/link! on a fact cid, the store's supersession marker, fram.datalog,
;; the world verbs). If section 0 is green and the rest is red, the only thing
;; missing is the module: the ENGINE needs nothing.
;;
;; NORMATIVE SURFACE this suite pins (all in fram.claims; every predicate is a
;; dotted lowercase name in the claim/evidence namespaces, world.head style):
;;
;;   evidence-pred      "claim.evidence"        claim fact cid -> evidence node
;;   reason-pred        "claim.reason"          claim fact cid -> rejection reason
;;   source-pred        "evidence.source"       evidence node -> artifact id
;;   region-pred        "evidence.region"       evidence node -> app locator
;;   fingerprint-pred   "evidence.fingerprint"  evidence node -> content hash
;;   world-pred         "evidence.world"        evidence node -> world VersionId (OPTIONAL)
;;   verified-view      "@view:claim.verified"  the verified-view family root
;;   rejected-view      "@view:claim.rejected"  the rejected-view family root
;;   (scoped-view root agent)   -> "<root>:<agent>", a verifier's OWN verdict view
;;
;;   (evidence-nodes co claim-cid)  -> live evidence node ids, cid-ascending
;;   (evidence co node-id)          -> {:source :region :fingerprint :world}, nil per absent key
;;   (provenance co claim-cid)      -> one map per evidence node (the whole chain)
;;   (verdict co claim-cid) (verdict co views claim-cid)
;;                                  -> {:verdict :verified|:rejected :view v :by agent :cid sel} | nil
;;   (verifier co claim-cid)        -> the writing agent of the live verified selection
;;   (rejection co claim-cid)       -> {:reason r :by agent :cid sel} | nil
;;   (status co claim-cid) (status co views claim-cid)
;;                                  -> :verified | :rejected | :pending | :superseded | nil
;;   (needs-reverification co from-version to-version) -> SET of claim cids
;;   (reverification-rules co from-version to-version) -> a STRATA program (data)
;;   reverification-relation        the head relation name that program derives
;;
;; `views` is {:verified <view-name> :rejected <view-name>}; a view name matches
;; ITSELF and its ":"-scoped children (the family). Status is view-relative —
;; the substrate has no view-free settled facts (docs/VIEWS_AND_BRANCHES.md §0).
;;
;; SCOPE. Verification MACHINERY — queues, outboxes, citation policy, the
;; material a human verifier reads — stays app-side and is specified nowhere
;; here (docs/claims-design.md, the mapping table). This file touches no socket
;; and no daemon; it writes one scratch log under /tmp.
(require '[fram.store :as c] '[fram.schema :as s] '[fram.datalog :as d]
         '[fram.world :as w] '[clojure.string :as str])
(load-file "coord.clj")   ; new-coord / commit! / select! / the world verbs (into THIS ns)

;; ---------------------------------------------------------------------------
;; harness — world_kernel_test.clj's, unchanged: a failures atom, [PASS]/[FAIL]
;; lines, and a per-bar reason so an ABSENT module names itself instead of
;; taking the whole suite down. `bar` catches Throwable: absence and
;; misbehaviour are the same contract failure, reported at bar granularity.
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

;; --- test-side primitives ---------------------------------------------------
(defn b8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))
(defn log-sha [path] (sha256-hex (java.nio.file.Files/readAllBytes (.toPath (java.io.File. path)))))
(defn next-id [co] (:next-id @(store co)))

;; The vocabulary as the APP spells it. The fixture writes with these literals;
;; section 1 then pins that the module's constants EQUAL them — the dependency
;; runs app -> module, never the other way round.
(def ev-pred    "claim.evidence")
(def reason-pd  "claim.reason")
(def src-pred   "evidence.source")
(def rgn-pred   "evidence.region")
(def fp-pred    "evidence.fingerprint")
(def wld-pred   "evidence.world")
(def v-root     "@view:claim.verified")
(def r-root     "@view:claim.rejected")
(def alice-v    (str v-root ":alice"))
(def bob-v      (str v-root ":bob"))
(def alice-r    (str r-root ":alice"))
(def foreign    "@view:other")

;; --- write helpers: EXISTING ops, no new engine primitive -------------------
(defn cid-of
  "The cid of the newest live fact on (subject, predicate)."
  [co te pred]
  (let [st (store co)]
    (apply max (live-cids-lp co (s/resolve-name st te) (c/value-id st pred)))))

;; The subject-is-a-cid write helper this spec once carried inline became the
;; real coordinator verb — coord.clj `about!` (the generic seam this doc's
;; "one integration seam" section anticipated). The spec now exercises THAT
;; verb, so the 90 bars are the contract for the shipped write path. Note the
;; signature difference from the old fixture: :link takes the target's NAME
;; (about! resolves it), and the return is a result map, not the new cid.

(defn supersede!
  "Retire ONE fact by cid with the store's own supersession marker — the same
   marker retract! writes. retract!'s public signature is name-oriented
   ((subject, predicate, value)), so it cannot NAME a selection fact; the
   marker itself is an ordinary public store write."
  [co agent cid]
  (locking (:lock co)
    (let [st    (store co)
          since (:next-id @st)
          tx    (c/begin-tx! st agent)
          sup   (c/value! st "store-supersedes")]
      (c/fact! st cid sup cid tx)
      (append-tx! co (delta-records co since tx))
      cid)))

;; --- world fixture material -------------------------------------------------
(def world-nm  "claims-spec")
(def mode      "100644")
(def slot-plan "docs/plan.md")
(def slot-note "docs/notes.md")
(def slot-gone "docs/gone.md")
(def raw-plan1 (b8 "budget: 2.4M\n"))
(def raw-plan2 (b8 "budget: 3.1M\n"))
(def raw-note  (b8 "the team is nine people\n"))
(def raw-gone  (b8 "clause 4 applies\n"))
(def nonce-a   "0123456789abcdef0123456789abcdef")
(def nonce-b   "fedcba9876543210fedcba9876543210")

(def scratch (str "/tmp/claims-spec-" (System/currentTimeMillis)))
(.mkdirs (java.io.File. scratch))
(def spec-log (str scratch "/claims.log"))
(def bare-log (str scratch "/worldless.log"))

;; ===========================================================================
;; THE FIXTURE — one continuous log holding a two-version world and ten claims
;; in every lifecycle state. Every write below uses an op that exists TODAY.
;; ===========================================================================
(def fx
  (delay
    (let [co (new-coord spec-log)
          ;; ---- a minimal two-version world: A -> B ------------------------
          bp1 (:ok (world-blob-put! co "spec" raw-plan1))
          bp2 (:ok (world-blob-put! co "spec" raw-plan2))
          bn  (:ok (world-blob-put! co "spec" raw-note))
          bg  (:ok (world-blob-put! co "spec" raw-gone))
          ca  (:ok (world-begin! co "spec" world-nm nil nonce-a))
          _   (doseq [op [(w/put-op slot-plan mode bp1)
                          (w/put-op slot-note mode bn)
                          (w/put-op slot-gone mode bg)]]
                (world-append! co "spec" ca op))
          vA  (:ok (world-seal! co "spec" ca))
          _   (world-create! co "spec" world-nm vA)
          cb  (:ok (world-begin! co "spec" world-nm vA nonce-b))
          ;; B EDITS the plan and DELETES the gone slot; notes is untouched.
          _   (doseq [op [(w/put-op slot-plan mode bp2) (w/delete-op slot-gone)]]
                (world-append! co "spec" cb op))
          vB  (:ok (world-seal! co "spec" cb))
          ;; ---- claims are ORDINARY facts, written by an ordinary commit! --
          claim! (fn [agent subj pred v]
                   (commit! co agent subj pred :assert v nil)
                   (cid-of co subj pred))
          ev!    (fn [nm src rgn fp wld]
                   (commit! co "extractor" nm src-pred :assert src nil)
                   (when rgn (commit! co "extractor" nm rgn-pred :assert rgn nil))
                   (commit! co "extractor" nm fp-pred :assert fp nil)
                   (when wld (commit! co "extractor" nm wld-pred :assert wld nil))
                   (s/resolve-name (store co) nm))
          cite!  (fn [claim node] (about! co "extractor" claim ev-pred :link (s/name-of (store co) node)))
          ;; a verdict is ONE view-selection; the tx's agent is the view subject,
          ;; so a verifier-scoped view carries the verifier identity for free.
          ;; select! returns the SELECTED cid, so the SELECTION fact's own cid is
          ;; read back off the view's overlay group.
          verdict! (fn [view claim] (select! co view claim) (cid-of co view "selects"))
          ;; ---- the evidence nodes ----------------------------------------
          e1 (ev! "@ev:plan-budget"  slot-plan "L3:C1-L3:C13" bp1 vA)
          e2 (ev! "@ev:notes-team"   slot-note "L1:C1-L1:C24" bn  vA)
          e2b(ev! "@ev:notes-team-2" slot-note "L1:C5-L1:C9"  bn  vA)
          e3 (ev! "@ev:gone-clause"  slot-gone "L1:C1-L1:C17" bg  vA)
          e4 (ev! "@ev:plan-nowrld"  slot-plan "L3:C1-L3:C13" bp1 nil)   ; NO evidence.world
          e5 (ev! "@ev:plan-pending" slot-plan "L3:C9-L3:C13" bp1 vA)
          e6 (ev! "@ev:plan-wrong"   slot-plan "L3:C1-L3:C13" bp1 vA)
          ;; ---- the claims -------------------------------------------------
          c1 (claim! "extractor" "@doc:plan-7" "states" "the budget is 2.4M")
          c2 (claim! "extractor" "@doc:notes"  "states" "the team is nine people")
          c3 (claim! "extractor" "@doc:gone"   "states" "clause 4 applies")
          c4 (claim! "extractor" "@doc:plan-7" "restates" "the budget is 2.4M")
          c5 (claim! "extractor" "@doc:plan-7" "cites" "RFC 4648")
          c6 (claim! "extractor" "@doc:plan-7" "asserts" "the budget is 24M")
          c7 (claim! "extractor" "@doc:plan-7" "implies" "the budget rose")
          c8 (claim! "extractor" "@doc:plan-7" "supersedable" "an earlier reading")
          c9 (claim! "extractor" "@doc:plan-7" "unclaimed" "a plain fact, never cited")
          c10 (claim! "extractor" "@doc:plan-7" "reverifiable" "the budget is 2.4M")
          r1 (claim! "extractor" "@doc:plan-7" "author" "alice")
          r2 (claim! "extractor" "@doc:plan-7" "author" "bob")      ; a RIVAL of r1
          ;; ---- citations ---------------------------------------------------
          _      (cite! c1 e1)
          _      (cite! c2 e2)
          _      (cite! c2 e2b)
          _      (cite! c3 e3)
          _      (cite! c4 e4)
          _      (cite! c5 e5)
          _      (cite! c6 e6)
          dead   (:cid (cite! c6 e2))   ; a citation the extractor later withdrew
          _      (supersede! co "extractor" dead)
          _      (cite! c7 e1)
          _      (cite! c8 e1)
          _      (cite! c10 e2)
          ;; ---- verdicts ----------------------------------------------------
          s1  (verdict! alice-v c1)
          _   (verdict! alice-v c2)
          _   (verdict! bob-v   c3)
          _   (verdict! alice-v c4)
          ;; c5 stays PENDING: evidence, no verdict.
          s6  (verdict! alice-r c6)
          _   (about! co "alice" c6 reason-pd :assert "the region does not support the number")
          s7  (verdict! alice-v c7)
          _   (supersede! co "alice" s7)        ; verdict WITHDRAWN -> back to pending
          _   (verdict! alice-v c8)
          _   (supersede! co "extractor" c8)    ; the CLAIM FACT itself retired
          _   (select! co foreign c9)           ; a verdict in a FOREIGN view family
          s10a (verdict! alice-v c10)
          _   (supersede! co "alice" s10a)      ; withdrawn ...
          s10b (verdict! alice-v c10)           ; ... then RE-verified
          _   (verdict! alice-v r1)
          _   (verdict! bob-v   r2)]
      {:co co :vA vA :vB vB :bp1 bp1 :bp2 bp2 :bn bn :bg bg
       :e1 e1 :e2 e2 :e2b e2b :e3 e3 :e4 e4 :e5 e5 :e6 e6 :dead dead
       :c1 c1 :c2 c2 :c3 c3 :c4 c4 :c5 c5 :c6 c6 :c7 c7 :c8 c8 :c9 c9 :c10 c10
       :r1 r1 :r2 r2 :s1 s1 :s6 s6 :s7 s7 :s10a s10a :s10b s10b})))

;; A SECOND, worlds-free store: the same discipline with no world verb ever
;; called and no evidence.world fact ever written.
(def bare
  (delay
    (let [co (new-coord bare-log)
          claim! (fn [subj pred v] (commit! co "extractor" subj pred :assert v nil)
                   (cid-of co subj pred))
          ev!    (fn [nm src rgn fp]
                   (commit! co "extractor" nm src-pred :assert src nil)
                   (commit! co "extractor" nm rgn-pred :assert rgn nil)
                   (commit! co "extractor" nm fp-pred :assert fp nil)
                   (s/resolve-name (store co) nm))
          eb (ev! "@ev:bare" "docs/plan.md" "L3:C1-L3:C13" "sha256:nope")
          b1 (claim! "@doc:bare" "states" "the budget is 2.4M")
          b2 (claim! "@doc:bare" "cites" "RFC 4648")]
      (about! co "extractor" b1 ev-pred :link (s/name-of (store co) eb))
      (about! co "extractor" b2 ev-pred :link (s/name-of (store co) eb))
      (select! co alice-v b1)
      {:co co :eb eb :b1 b1 :b2 b2})))

(println "claims — executable specification (fram.claims)")
(println (str "  scratch: " scratch))
(when-not (:ok module)
  (println (str "  NOTE: " (:why module)
                " — every claims bar below FAILS by absence, as expected of a spec-first module.")))

;; ===========================================================================
(println "\n-- 0. substrate self-check: THE ENGINE ALREADY DOES THIS (must PASS) --")
;; ===========================================================================
(bar "self: the fixture built — a two-version world and every claim state"
     (let [f @fx]
       (and (string? (:vA f)) (string? (:vB f)) (not= (:vA f) (:vB f))
            (every? integer? [(:c1 f) (:c6 f) (:r2 f) (:s1 f)]))))
(bar "self: a claim is an ORDINARY fact — commit! minted it, nothing else"
     (let [f @fx] (map? (c/fact-of (store (:co f)) (:c1 f)))))
(bar "self: a fact ABOUT a fact cid is an ordinary fact TODAY (the evidence edge)"
     (let [f  @fx
           st (store (:co f))
           p  (c/value-id st ev-pred)]
       (= #{(:e1 f)} (set (map #(:r (c/fact-of st %)) (c/by-lp st (:c1 f) p))))))
(bar "self: a view SELECTS a fact cid TODAY (the verdict) — select! + view-selects"
     (let [f @fx] (contains? (view-selects (:co f) alice-v) (:c1 f))))
(bar "self: the selection fact records its WRITING AGENT (provenance for free)"
     (let [f @fx] (= alice-v (agent-of (:co f) (:s1 f)))))
(bar "self: superseding a selection drops it from the view overlay (un-verify)"
     (let [f @fx] (not (contains? (view-selects (:co f) alice-v) (:c7 f)))))
(bar "self: superseding the CLAIM FACT retires it from the live view"
     (let [f @fx] (not (c/live? (store (:co f)) (:c8 f)))))
(bar "self: rival claims COEXIST — two live facts on one (subject, predicate)"
     (let [f  @fx
           st (store (:co f))]
       (= 2 (count (live-cids-lp (:co f) (s/resolve-name st "@doc:plan-7")
                                 (c/value-id st "author"))))))
(bar "self: no predicate registration was needed — claim vocabulary is MULTI"
     (let [f @fx]
       (not-any? #(= "single" (s/cardinality (store (:co f)) %))
                 [ev-pred reason-pd src-pred rgn-pred fp-pred wld-pred])))
(bar "self: rules-as-data over the LIVE store derive the citation relation"
     (let [f  @fx
           st (store (:co f))
           db (d/run-rules st [(d/rule "cited" [(d/v :c) (d/v :e)]
                                       [(d/lit "triple" [(d/v :c) (c/value-id st ev-pred) (d/v :e)])])])]
       (contains? (set (d/facts db "cited")) [(:c1 f) (:e1 f)])))
(bar "self: the two world versions resolve the cited slot to DIFFERENT blobs"
     (let [f  @fx
           at (fn [v slot] (:blob-id (first (filter #(= slot (:slot %))
                                                    (world-manifest (:co f) v)))))]
       (and (= (:bp1 f) (at (:vA f) slot-plan))
            (= (:bp2 f) (at (:vB f) slot-plan))
            (nil? (at (:vB f) slot-gone)))))
(bar "self: EVERY op this suite writes with already exists (no engine change)"
     (every? #(some? (resolve (symbol %)))
             ["commit!" "select!" "view-selects" "retract!" "agent-of"
              "world-begin!" "world-append!" "world-seal!" "world-manifest"]))
(bar "self: the worlds-FREE store built with no world verb and no evidence.world"
     (let [b @bare]
       (and (integer? (:b1 b))
            (nil? (c/value-id (store (:co b)) wld-pred)))))

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
(bar "claimhood: claiming a fact adds NO fact to the claim's own (subject, predicate) group"
     (let [f  @fx
           st (store (:co f))
           _  (cv "status")]
       (= 1 (count (live-cids-lp (:co f) (s/resolve-name st "@doc:notes")
                                 (c/value-id st "states"))))))

;; ===========================================================================
(println "\n-- 2. evidence and the provenance chain --")
;; ===========================================================================
(bar "evidence: a claim's evidence nodes are its live claim.evidence targets"
     (let [f @fx] (= [(:e1 f)] (vec (q "evidence-nodes" (:co f) (:c1 f))))))
(bar "evidence: a claim with NO evidence has an empty node list, not an error"
     (let [f @fx] (empty? (q "evidence-nodes" (:co f) (:c9 f)))))
(bar "evidence: several evidence nodes on one claim are ALL returned, cid-ascending"
     (let [f @fx] (= [(:e2 f) (:e2b f)] (vec (q "evidence-nodes" (:co f) (:c2 f))))))
(bar "evidence: a node reports source, region and fingerprint"
     (let [f @fx
           e (q "evidence" (:co f) (:e1 f))]
       (and (= slot-plan (:source e)) (= "L3:C1-L3:C13" (:region e)) (= (:bp1 f) (:fingerprint e)))))
(bar "evidence: evidence.world is OPTIONAL — absent reads nil, never an error"
     (let [f @fx] (nil? (:world (q "evidence" (:co f) (:e4 f))))))
(bar "evidence: a world-tagged node reports the VERSION it was extracted against"
     (let [f @fx] (= (:vA f) (:world (q "evidence" (:co f) (:e1 f))))))
(bar "provenance: claim -> evidence -> source/region/fingerprint is ONE query"
     (let [f @fx
           p (q "provenance" (:co f) (:c1 f))]
       (and (= 1 (count p))
            (= slot-plan (:source (first p)))
            (= (:bp1 f) (:fingerprint (first p))))))
(bar "provenance: a SUPERSEDED citation drops out of the chain"
     (let [f @fx] (= [(:e6 f)] (vec (q "evidence-nodes" (:co f) (:c6 f))))))
(bar "provenance: the withdrawn citation fact is still IN the log — nothing was deleted"
     (let [f @fx]
       (and (map? (c/fact-of (store (:co f)) (:dead f)))
            (not (c/live? (store (:co f)) (:dead f)))
            (not (contains? (set (q "evidence-nodes" (:co f) (:c6 f))) (:e2 f))))))
(bar "provenance: the chain is a pure READ — twice is identical"
     (let [f @fx] (= (q "provenance" (:co f) (:c2 f)) (q "provenance" (:co f) (:c2 f)))))

;; ===========================================================================
(println "\n-- 3. status is DERIVED: four states, never stored --")
;; ===========================================================================
(bar "status: evidence and no verdict is :pending"
     (let [f @fx] (= :pending (q "status" (:co f) (:c5 f)))))
(bar "status: a verified-view selection is :verified"
     (let [f @fx] (= :verified (q "status" (:co f) (:c1 f)))))
(bar "status: a rejected-view selection is :rejected"
     (let [f @fx] (= :rejected (q "status" (:co f) (:c6 f)))))
(bar "status: a superseded claim fact is :superseded (existing SUP mechanics)"
     (let [f @fx] (= :superseded (q "status" (:co f) (:c8 f)))))
(bar "status: :superseded DOMINATES — c8 was verified before it was retired"
     (let [f @fx]
       (and (contains? (view-selects (:co f) alice-v) (:c8 f))
            (= :superseded (q "status" (:co f) (:c8 f))))))
(bar "status: an ordinary fact with neither evidence nor verdict is NOT a claim (nil)"
     (let [f @fx] (nil? (q "status" (:co f) (:c9 f)))))
(bar "status: a verdict in a FOREIGN view family does not confer a status"
     (let [f @fx]
       (and (contains? (view-selects (:co f) foreign) (:c9 f))
            (nil? (q "status" (:co f) (:c9 f))))))
(bar "status: a verdict from a verifier-SCOPED view counts (the family, not one name)"
     (let [f @fx] (= :verified (q "status" (:co f) (:c3 f)))))
(bar "status: the words verified/pending/rejected were never INTERNED as values"
     (let [f  @fx
           st (store (:co f))
           _  (cv "status")]
       (every? nil? (map #(c/value-id st %) ["verified" "pending" "rejected" "claim.status"]))))
(bar "status: derivation is a pure READ — twice is identical"
     (let [f @fx] (= (q "status" (:co f) (:c1 f)) (q "status" (:co f) (:c1 f)))))

;; ===========================================================================
(println "\n-- 4. the verdict flip, and verdict provenance --")
;; ===========================================================================
(bar "flip: superseding the verdict selection returns the claim to :pending"
     (let [f @fx] (= :pending (q "status" (:co f) (:c7 f)))))
(bar "flip: ... and the CLAIM FACT is untouched — still live, evidence intact"
     (let [f @fx]
       (and (c/live? (store (:co f)) (:c7 f))
            (= [(:e1 f)] (vec (q "evidence-nodes" (:co f) (:c7 f)))))))
(bar "flip: re-verifying after a withdrawal is :verified again"
     (let [f @fx] (= :verified (q "status" (:co f) (:c10 f)))))
(bar "flip: the live verdict is the NEW selection, not the withdrawn one"
     (let [f @fx] (= (:s10b f) (:cid (q "verdict" (:co f) (:c10 f))))))
(bar "verdict: reports the kind, the selecting view and the selection fact"
     (let [f @fx
           v (q "verdict" (:co f) (:c1 f))]
       (and (= :verified (:verdict v)) (= alice-v (:view v)) (= (:s1 f) (:cid v)))))
(bar "verdict: a claim with no verdict has none (nil), not a fabricated one"
     (let [f @fx] (nil? (q "verdict" (:co f) (:c5 f)))))
(bar "verifier: the verifier IS the selection fact's writing agent — no extra schema"
     (let [f @fx]
       (= (agent-of (:co f) (:cid (q "verdict" (:co f) (:c1 f))))
          (q "verifier" (:co f) (:c1 f)))))
(bar "verifier: a verifier-scoped view therefore names the verifier"
     (let [f @fx] (= alice-v (q "verifier" (:co f) (:c1 f)))))
(bar "verifier: a REJECTED claim has no verifier"
     (let [f @fx] (nil? (q "verifier" (:co f) (:c6 f)))))
(bar "rejection: carries the reason fact asserted about the claim cid"
     (let [f @fx]
       (= "the region does not support the number" (:reason (q "rejection" (:co f) (:c6 f))))))
(bar "rejection: carries the rejecter — the rejection view's writing agent"
     (let [f @fx] (= alice-r (:by (q "rejection" (:co f) (:c6 f))))))
(bar "rejection: names the rejecting selection fact (auditable, not a summary)"
     (let [f @fx] (= (:s6 f) (:cid (q "rejection" (:co f) (:c6 f))))))
(bar "rejection: a VERIFIED claim has no rejection"
     (let [f @fx] (nil? (q "rejection" (:co f) (:c1 f)))))

;; ===========================================================================
(println "\n-- 5. rival claims coexist; verdicts elect PER VIEW --")
;; ===========================================================================
(bar "rivals: both rival claims stay live AND both carry a verdict — no supersession"
     (let [f @fx]
       (and (c/live? (store (:co f)) (:r1 f)) (c/live? (store (:co f)) (:r2 f))
            (some? (q "verdict" (:co f) (:r1 f))) (some? (q "verdict" (:co f) (:r2 f))))))
(bar "rivals: each is :verified in the family — the family is not single-valued"
     (let [f @fx]
       (= [:verified :verified] [(q "status" (:co f) (:r1 f)) (q "status" (:co f) (:r2 f))])))
(bar "rivals: under ALICE's views only alice's rival is verified"
     (let [f @fx
           vs {:verified alice-v :rejected alice-r}]
       (and (= :verified (q "status" (:co f) vs (:r1 f)))
            (= nil       (q "status" (:co f) vs (:r2 f))))))
(bar "rivals: under BOB's view the election is the other way round"
     (let [f @fx
           vs {:verified bob-v :rejected r-root}]
       (and (= :verified (q "status" (:co f) vs (:r2 f)))
            (= nil       (q "status" (:co f) vs (:r1 f))))))
(bar "rivals: the two verdicts carry DISTINCT verifiers"
     (let [f @fx]
       (not= (q "verifier" (:co f) (:r1 f)) (q "verifier" (:co f) (:r2 f)))))
(bar "rivals: a view-relative status never mutates the family answer"
     (let [f @fx]
       (and (= :verified (q "status" (:co f) {:verified alice-v :rejected alice-r} (:r1 f)))
            (= :verified (q "status" (:co f) (:r2 f))))))

;; ===========================================================================
(println "\n-- 6. the transition rule: which verified claims A->B invalidates --")
;; ===========================================================================
(bar "transition: a verified claim whose cited slot CHANGED at B needs re-verification"
     (let [f @fx] (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c1 f))))
(bar "transition: a verified claim whose cited slot was DELETED at B needs it too"
     (let [f @fx] (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c3 f))))
(bar "transition: a verified claim whose evidence is UNCHANGED at B does not"
     (let [f @fx] (not (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c2 f)))))
(bar "transition: a PENDING claim is never returned — only verified claims re-verify"
     (let [f @fx] (not (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c5 f)))))
(bar "transition: a REJECTED claim is never returned"
     (let [f @fx] (not (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c6 f)))))
(bar "transition: a WITHDRAWN verdict is never returned (c7 is pending again)"
     (let [f @fx] (not (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c7 f)))))
(bar "transition: a SUPERSEDED claim is never returned"
     (let [f @fx] (not (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c8 f)))))
(bar "transition: the answer is EXACTLY the two invalidated claims"
     (let [f @fx] (= #{(:c1 f) (:c3 f)} (set (q "needs-reverification" (:co f) (:vA f) (:vB f))))))
(bar "transition: the identity transition A->A returns NOTHING"
     (let [f @fx] (empty? (q "needs-reverification" (:co f) (:vA f) (:vA f)))))
(bar "transition: it is DIRECTIONAL — B->A returns nothing (no evidence cites B)"
     (let [f @fx] (empty? (q "needs-reverification" (:co f) (:vB f) (:vA f)))))
(bar "transition: it is a pure READ — twice is identical"
     (let [f @fx] (= (set (q "needs-reverification" (:co f) (:vA f) (:vB f)))
                     (set (q "needs-reverification" (:co f) (:vA f) (:vB f))))))
(bar "transition: the rule is DATA — a stratified program, no bespoke traversal"
     (let [f @fx
           p (q "reverification-rules" (:co f) (:vA f) (:vB f))]
       (and (vector? p) (every? vector? p) (empty? (d/strata-violations p)))))
(bar "transition: the existing datalog engine derives the SAME claims from it"
     (let [f  @fx
           p  (q "reverification-rules" (:co f) (:vA f) (:vB f))
           db (d/run-strata (store (:co f)) p)]
       (= #{(:c1 f) (:c3 f)} (set (map first (d/facts db (qd "reverification-relation")))))))

;; ===========================================================================
(println "\n-- 7. worlds-OPTIONAL: identical module, no world in sight --")
;; ===========================================================================
(bar "worlds-optional: evidence with NO evidence.world never appears in a transition"
     (let [f @fx] (not (contains? (set (q "needs-reverification" (:co f) (:vA f) (:vB f))) (:c4 f)))))
(bar "worlds-optional: ... even though its cited slot DID change at B"
     (let [f @fx]
       (and (= slot-plan (:source (q "evidence" (:co f) (:e4 f))))
            (= :verified (q "status" (:co f) (:c4 f))))))
(bar "worlds-optional: its status derivation is IDENTICAL to a world-tagged claim's"
     (let [f @fx] (= (q "status" (:co f) (:c1 f)) (q "status" (:co f) (:c4 f)))))
(bar "worlds-optional: its provenance is complete except for :world"
     (let [f @fx
           p (first (q "provenance" (:co f) (:c4 f)))]
       (and (= slot-plan (:source p)) (some? (:region p)) (some? (:fingerprint p))
            (nil? (:world p)))))
(bar "worlds-optional: a store that never called a world verb still derives :verified"
     (let [b @bare] (= :verified (q "status" (:co b) (:b1 b)))))
(bar "worlds-optional: ... and :pending"
     (let [b @bare] (= :pending (q "status" (:co b) (:b2 b)))))
(bar "worlds-optional: ... and the full provenance chain"
     (let [b @bare
           p (first (q "provenance" (:co b) (:b1 b)))]
       (and (= "docs/plan.md" (:source p)) (= "sha256:nope" (:fingerprint p)))))
(bar "worlds-optional: a transition over versions the store never heard of is EMPTY"
     (let [b @bare
           f @fx]
       (empty? (q "needs-reverification" (:co b) (:vA f) (:vB f)))))

;; ===========================================================================
(println "\n-- 8. zero engine mutation: the module READS, it never writes --")
;; ===========================================================================
(bar "purity: every claims read leaves the log BYTE-IDENTICAL"
     (let [f      @fx
           before (log-sha spec-log)]
       (q "status" (:co f) (:c1 f))
       (q "provenance" (:co f) (:c1 f))
       (q "verdict" (:co f) (:c1 f))
       (q "rejection" (:co f) (:c6 f))
       (q "needs-reverification" (:co f) (:vA f) (:vB f))
       (= before (log-sha spec-log))))
(bar "purity: ... and mints NO store id (no entity, value or fact is interned)"
     (let [f      @fx
           before (next-id (:co f))]
       (q "status" (:co f) (:c1 f))
       (q "provenance" (:co f) (:c1 f))
       (q "needs-reverification" (:co f) (:vA f) (:vB f))
       (= before (next-id (:co f)))))
(bar "purity: fram.claims exports NO write verb — no public name ends in \"!\""
     (let [_ (cv "status")]
       (not-any? #(str/ends-with? (name %) "!") (keys (ns-publics 'fram.claims)))))
(bar "purity: the module needs no predicate registration — its vocabulary stays MULTI"
     (let [f @fx]
       (not-any? #(= "single" (s/cardinality (store (:co f)) %))
                 [(qd "evidence-pred") (qd "reason-pred") (qd "source-pred")
                  (qd "fingerprint-pred") (qd "world-pred")])))
(bar "purity: a claims read never supersedes anything — the live fact count holds"
     (let [f      @fx
           live   #(count (c/current-facts (store (:co f))))
           before (live)]
       (q "needs-reverification" (:co f) (:vA f) (:vB f))
       (= before (live))))

;; ---------------------------------------------------------------------------
(let [pass (- @total @failures)]
  (println (str "\nclaims-spec: " pass "/" @total " PASS"))
  (if (zero? @failures)
    (println "claims-spec: ALL BARS PASS")
    (do (println (str "claims-spec: " @failures " FAILED — these bars DEFINE the claims"
                      " contract; fram.claims does not exist yet. Section 0 passing is the"
                      " point: the engine already carries every op the module needs."))
        (System/exit 1))))
