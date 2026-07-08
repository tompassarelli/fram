#!/usr/bin/env bb
;; ============================================================================
;; rep_jurisdiction — the compiler's REPRESENTATION JUDGMENT as a queryable claim.
;; ============================================================================
;; Beagle's emit-js decides, per allocation site, native-JS-coll vs $$bc HAMT.
;; Today that judgment escapes only as a per-MODULE comment (// collection-rep).
;; `beagle-rep-claims` projects it as a CNF claim PER DEF instead:
;;
;;   [<id> "form-kind" "rep-def"]  [<id> "name" "<def>"]
;;   [<id> "rep-regime" "native"|"hamt"|"mixed"]
;;   [<id> "native-sites" N] [<id> "hamt-sites" M] [<id> "module" "<ns>"]
;;
;; This loads that stream into a REAL Fram store and answers jurisdiction queries
;; the grep-the-comment incumbent structurally CANNOT:
;;
;;   Q1. Which defs ship the HAMT (pull persistent runtime)?      — a regime filter
;;   Q2. Which defs are 100% native (zero persistent-runtime cost)?
;;   Q3. Which defs are MIXED (a rep boundary lives inside them)?
;;   Q4. CROSS-MODULE BLAST: which defs transitively CALL a HAMT-shipping def?
;;       (join rep-regime against the scope-correct call graph — the payoff a
;;        per-module comment can't reach: "who is downstream of the HAMT?")
;;
;; Run:
;;   bb -cp ~/code/fram/out:chartroom/src chartroom/src/rep_jurisdiction.clj \
;;      <rep.claims> [<callgraph.claims>]
;;
;; <rep.claims>       : output of `beagle-rep-claims <src.bjs>...`
;; <callgraph.claims> : OPTIONAL — output of `beagle-claims <src>...`; when given,
;;                      Q4 derives the transitive HAMT blast radius over the call graph.
;; ============================================================================
(ns rep-jurisdiction
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [callgraph :as cg]
            [fram.store :as c]
            [fram.datalog :as d]))

(def rep-path (first *command-line-args*))
(def cg-path  (second *command-line-args*))

;; ---- parse the @file-delimited rep-claim stream ----------------------------
;; Each rep-def block keys its triples by a per-file integer id; we re-key by the
;; def NAME (carried in the "name" triple) so a def is one stable graph entity.
(defn parse-rep-blocks [path]
  (loop [ls (str/split-lines (slurp path)), cur nil, out []]
    (if (empty? ls)
      (if cur (conj out cur) out)
      (let [l (first ls)]
        (cond
          (str/starts-with? l "@file ")
          (recur (rest ls) {:file (subs l 6) :triples []} (if cur (conj out cur) out))
          (str/starts-with? l "[")
          (recur (rest ls) (update cur :triples conj (edn/read-string l)) out)
          :else (recur (rest ls) cur out))))))

;; one block's triples -> [{:name :regime :native :hamt :module} ...]
(defn block->defs [block]
  (let [ts (:triples block)
        by-subj (group-by first ts)]
    (for [[_ trips] by-subj
          :let [m (into {} (map (fn [[_ p o]] [p o]) trips))]
          :when (= "rep-def" (get m "form-kind"))]
      {:name   (get m "name")
       :regime (get m "rep-regime")
       :native (get m "native-sites")
       :hamt   (get m "hamt-sites")
       :module (get m "module")})))

;; ============================================================================
(defn -main []
  (let [blocks  (parse-rep-blocks rep-path)
        rep-defs (vec (mapcat block->defs blocks))]
    (println "================ REP JURISDICTION — compiler judgment as claims ================")
    (println "rep corpus:" rep-path)
    (println "rep-def claims:" (count rep-defs)
             " across" (count (distinct (map :module rep-defs))) "module(s)")

    ;; ---- load rep claims into a real Fram store --------------------------
    (let [ctx     (c/new-store)
          tx      (c/begin-tx! ctx "rep")
          REGIME  (c/value! ctx "rep-regime")
          n->ent  (volatile! {})
          ent     (fn [nm] (or (get @n->ent nm)
                               (let [e (c/entity! ctx)] (vswap! n->ent assoc nm e) e)))
          ;; assert one claim per def: (def-entity rep-regime <"native"|"hamt"|"mixed">)
          _ (doseq [d rep-defs]
              (c/fact! ctx (ent (:name d)) REGIME (c/value! ctx (:regime d)) tx))
          ent->n (into {} (map (fn [[k v]] [v k]) @n->ent))]

      ;; ---- Q1/Q2/Q3: jurisdiction filters via the live (p,r) index --------
      (let [defs-with-regime
            (fn [reg]
              (let [R (c/value! ctx reg)]
                (->> (c/by-pr ctx REGIME R)             ; live claims with this regime
                     (map #(c/fact-of ctx %))
                     (map #(ent->n (:l %)))
                     sort vec)))]
        (println "\n---- Q1. defs that SHIP THE HAMT (pull persistent runtime) ----")
        (doseq [nm (defs-with-regime "hamt")] (println "  HAMT  " nm))
        (println "\n---- Q2. defs that are 100% NATIVE (zero persistent runtime) ----")
        (doseq [nm (defs-with-regime "native")] (println "  native" nm))
        (println "\n---- Q3. defs that are MIXED (a rep boundary lives inside) ----")
        (doseq [nm (defs-with-regime "mixed")] (println "  mixed " nm)))

      ;; ---- Q4. CROSS-MODULE HAMT BLAST: who transitively calls a HAMT def? --
      (when cg-path
        (println "\n---- Q4. transitive HAMT blast: defs DOWNSTREAM of any HAMT/mixed def ----")
        (println "(scope-correct call graph; a caller \"forces\" a HAMT if it reaches one)")
        (let [cg-blocks (cg/parse-corpus cg-path)
              {:keys [defns edges]} (cg/build-graph cg-blocks)
              ;; def NAME -> #{caller-names...}: collapse the [file s] keys to names.
              key->name (into {} (map (fn [d] [(:key d) (:name d)]) defns))
              name-edges (->> edges
                              (keep (fn [[a b]] (let [an (key->name a) bn (key->name b)]
                                                  (when (and an bn) [an bn]))))
                              distinct vec)
              ;; the set of HAMT-shipping defs (regime hamt OR mixed), by name
              hamt-names (set (->> rep-defs
                                   (filter #(#{"hamt" "mixed"} (:regime %)))
                                   (map :name)))
              ;; load name-edges + a "ships-hamt" base relation into a fresh store,
              ;; derive "forces-hamt": x forces if x calls a hamt def, transitively.
              gctx  (c/new-store)
              gtx   (c/begin-tx! gctx "cg")
              CALLS (c/value! gctx "calls")
              HAMT  (c/value! gctx "ships-hamt")
              MARK  (c/value! gctx "yes")
              gname->ent* (volatile! {})
              gent  (fn [nm] (or (get @gname->ent* nm)
                                 (let [e (c/entity! gctx)] (vswap! gname->ent* assoc nm e) e)))
              ;; ALL call-graph def names (not just allocating ones) — a non-allocating
              ;; def like `run` has no rep-def claim but still forces a HAMT if it CALLS
              ;; one. Seed every defn so its entity exists in the reverse map.
              _ (doseq [d defns] (when (:name d) (gent (:name d))))
              _ (doseq [[a b] name-edges] (c/fact! gctx (gent a) CALLS (gent b) gtx))
              _ (doseq [nm hamt-names] (c/fact! gctx (gent nm) HAMT MARK gtx))
              ent->name  (into {} (map (fn [[k v]] [v k]) @gname->ent*))
              db (d/run-rules gctx
                   [;; forces(X) :- calls(X,Y), ships-hamt(Y,yes).   (direct)
                    (d/rule "forces" [(d/v :x)]
                            [(d/lit "triple" [(d/v :x) CALLS (d/v :y)])
                             (d/lit "triple" [(d/v :y) HAMT  MARK])])
                    ;; forces(X) :- calls(X,Y), forces(Y).            (transitive)
                    (d/rule "forces" [(d/v :x)]
                            [(d/lit "triple" [(d/v :x) CALLS (d/v :y)])
                             (d/lit "forces" [(d/v :y)])])])
              forced (->> (d/facts db "forces")
                          (map (fn [[xid]] (ent->name xid)))
                          (remove nil?) sort distinct vec)]
          (if (seq forced)
            (doseq [nm forced] (println "  forces-HAMT" nm))
            (println "  (no caller transitively reaches a HAMT def in this corpus)"))
          (println (format "\nHAMT-shipping defs: %d ; defs that FORCE a HAMT downstream: %d"
                           (count hamt-names) (count forced)))
          (println "  ^ THIS is the query grep-the-comment cannot answer: the comment is")
          (println "    per-module and disconnected from the call graph; the claim is a")
          (println "    graph node you JOIN against scope-correct edges — blast radius of a")
          (println "    rep decision, across module boundaries, in one fixpoint."))))))

(when (and (System/getProperty "babashka.file")
           (= (System/getProperty "babashka.file") *file*))
  (-main))
