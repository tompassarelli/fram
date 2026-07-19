;; query_test.clj — the structured/validated Datalog-shaped query surface.
;; Proves: (1) positive join + transitive closure return correct tuples over a
;; flat fact fold; (2) the boundary REJECTS malformed/unsafe queries (unknown
;; relation, bad term, unstratified negation) instead of running them — the
;; "can't emit broken" property; (3) stratified negation runs when well-formed.
;;   bb -cp out query_test.clj
(require '[fram.kernel :as k]
         '[fram.query :as q]
         '[fram.datalog :as d])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

;; a tiny graph as FACTS (strings, exactly the flat fold's shape):
;;   a -depends_on-> b -depends_on-> c   ; b,c are "hub"
(def facts
  [(k/->Fact "@a" "depends_on" "@b")
   (k/->Fact "@b" "depends_on" "@c")
   (k/->Fact "@b" "kind" "hub")
   (k/->Fact "@c" "kind" "hub")
   (k/->Fact "@a" "title" "Alpha")])

;; (1) positive 2-literal join: hubdep(X,H) :- triple(X,depends_on,H), triple(H,kind,hub)
(let [r (q/run facts
          {:find "hubdep"
           :rules [{:head {:rel "hubdep" :args [{:var "x"} {:var "h"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "h"}]}
                           {:rel "triple" :args [{:var "h"} "kind" "hub"]}]}]})
      got (set (:ok r))]
  (chk "positive join finds a->b (b is a hub)" (contains? got ["@a" "@b"]))
  (chk "positive join excludes a->c (a doesn't directly depend on c)" (not (contains? got ["@a" "@c"]))))

;; (2) transitive closure (recursion): reaches(X,Y) base + step
(let [r (q/run facts
          {:find "reaches"
           :rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                   {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                           {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}]})
      got (set (:ok r))]
  (chk "transitive closure reaches a->b" (contains? got ["@a" "@b"]))
  (chk "transitive closure reaches a->c (a->b->c)" (contains? got ["@a" "@c"]))
  (chk "transitive closure reaches b->c" (contains? got ["@b" "@c"])))

;; (3) BOUNDARY: unknown relation is rejected, not silently empty
(let [r (q/run facts
          {:find "x" :rules [{:head {:rel "x" :args [{:var "a"}]}
                              :body [{:rel "bogus" :args [{:var "a"}]}]}]})]
  (chk "unknown relation -> :error (not :ok)" (and (contains? r :error) (not (contains? r :ok))))
  (chk "unknown relation error mentions the bad rel"
       (some #(re-find #"bogus" %) (:error r))))

;; (4) BOUNDARY: a malformed term (not {:var} / not constant) is rejected
(let [r (q/run facts
          {:find "x" :rules [{:head {:rel "x" :args [{:var "a"}]}
                              :body [{:rel "triple" :args [{:not-a-var "a"} "depends_on" {:var "a"}]}]}]})]
  (chk "bad term -> :error" (contains? r :error)))

;; (5) BOUNDARY: unstratified negation (negate a derived rel in the same stratum) is rejected
(let [r (q/run facts
          {:find "safe"
           :rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                   {:head {:rel "safe" :args [{:var "x"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                           {:rel "reaches" :args [{:var "x"} {:var "y"}] :neg true}]}]})]
  (chk "negating a same-stratum derived rel -> :error (stratification)" (contains? r :error)))

;; (6) stratified negation that IS well-formed runs: terminal via explicit strata.
;;   stratum 0: done(X) :- triple(X,kind,hub)        (treat hub as "done")
;;   stratum 1: open(X) :- triple(X,depends_on,_), NOT done(X)
(let [r (q/run facts
          {:find "open"
           :strata [[{:head {:rel "done" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "kind" "hub"]}]}]
                    [{:head {:rel "open" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                             {:rel "done" :args [{:var "x"}] :neg true}]}]]})
      got (set (map first (:ok r)))]
  (chk "stratified negation runs (well-formed)" (contains? r :ok))
  (chk "open includes @a (depends, not a hub)" (contains? got "@a"))
  (chk "open excludes @b (depends, but IS a hub/done)" (not (contains? got "@b"))))

;; (7) BOUNDARY FIXES from adversarial review — the head is now as rigorous as the
;; body, and validation is total (never crashes on malformed input).
(defn err? [q] (contains? (q/run facts q) :error))
(chk "bad HEAD term rejected (was silently emitted as a constant)"
     (err? {:find "r" :rules [{:head {:rel "r" :args [{:foo 1}]}
                               :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}]}]}))
(chk "unbound HEAD var rejected (was emitting nil tuples)"
     (err? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"} {:var "UNBOUND"}]}
                               :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}]}]}))
(chk "HEAD :rel shadowing 'triple' rejected (was injecting fabricated facts)"
     (err? {:find "triple" :rules [{:head {:rel "triple" :args ["@fake" "hacked" "yes"]}
                                    :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}]}]}))
(chk "malformed :strata -> :error, not a crash"  (err? {:find "r" :strata "nope"}))
(chk "malformed :rules -> :error, not a crash"   (err? {:find "r" :rules "nope"}))
(chk "non-boolean :neg rejected"
     (err? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"}]}
                               :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}
                                      {:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}] :neg "yes"}]}]}))
(chk "valid query still runs after tightening (regression)"
     (contains? (q/run facts {:find "r" :rules [{:head {:rel "r" :args [{:var "x"} {:var "y"}]}
                                                  :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}]}) :ok))

;; ============================================================================
;; (8) AUDIT REGRESSIONS — soundness holes in the validate boundary. Each query
;; below validated CLEAN before the fix (silently wrong / unbounded) and is now
;; REJECTED (or bounded) with a clear, specific signal.
;; ============================================================================

;; #7 (HIGH) recursion-through-negation: p is in stratum 0 (so it lands in `lower`)
;; AND re-derived in stratum 1, which also negates p. The negative cycle
;; p -(neg)-> q -> p was accepted as stratifiable (strata-violations returned []);
;; NOT p was evaluated against a still-growing p (eval-order-dependent, inconsistent
;; — q(@b) asserted because ¬p(@b) held, yet p(@b) then derived). Now REJECTED.
(let [seed-facts [(k/->Fact "@a" "seed" "yes")
                   (k/->Fact "@a" "node" "yes")
                   (k/->Fact "@b" "node" "yes")]
      r (q/run seed-facts
          {:find "p"
           :strata [[{:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "seed" "yes"]}]}]
                    [{:head {:rel "q" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "node" "yes"]}
                             {:rel "p" :args [{:var "x"}] :neg true}]}
                     {:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "q" :args [{:var "x"}]}]}]]})]
  (chk "#7 recursion-through-negation REJECTED (negated rel re-derived in same stratum)"
       (and (contains? r :error) (not (contains? r :ok))))
  (chk "#7 error names the recursion-through-negation cause"
       (some #(re-find #"recursion through negation" %) (:error r))))

;; #7 (companion at the engine level): strata-violations itself now flags the cycle.
(chk "#7 d/strata-violations flags the re-derived negated rel (was [])"
     (not (empty? (d/strata-violations
                   [[{:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "seed" "yes"]}]}]
                    [{:head {:rel "q" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "node" "yes"]}
                             {:rel "p" :args [{:var "x"}] :neg true}]}
                     {:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "q" :args [{:var "x"}]}]}]]))))

;; #18 (MEDIUM) positive cross-stratum FORWARD reference: stratum 0 positively
;; references `b`, which is defined only in stratum 1. run evaluates strata in
;; order, so `b` is still empty when stratum 0 runs -> `a` is silently empty.
;; validate's `known` (all heads everywhere) let it pass. Now REJECTED.
(let [r (q/run [(k/->Fact "@x" "title" "T")]
          {:find "a"
           :strata [[{:head {:rel "a" :args [{:var "x"}]}
                      :body [{:rel "b" :args [{:var "x"}]}]}]
                    [{:head {:rel "b" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "title" {:var "t"}]}]}]]})]
  (chk "#18 positive forward-reference REJECTED (was silently empty :ok)"
       (and (contains? r :error) (not (contains? r :ok))))
  (chk "#18 error names the forward-referenced rel + later-stratum cause"
       (some #(re-find #"defined only in a LATER stratum" %) (:error r))))

;; #18 the CORRECTLY-ordered program (b first, a after) must still RUN non-empty —
;; the fix rejects only the bad order, it does not break legitimate layering.
(let [r (q/run [(k/->Fact "@x" "title" "T")]
          {:find "a"
           :strata [[{:head {:rel "b" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "title" {:var "t"}]}]}]
                    [{:head {:rel "a" :args [{:var "x"}]}
                      :body [{:rel "b" :args [{:var "x"}]}]}]]})]
  (chk "#18 correctly-ordered strata still run (not over-rejected)"
       (and (contains? r :ok) (= (set (:ok r)) #{["@x"]}))))

;; #22 (LOW) a head relation defined at inconsistent arities -> ragged tuples.
;; `r` derived at arity 1 AND 2 previously validated clean and emitted mixed-shape
;; rows. Now REJECTED.
(let [r (q/run [(k/->Fact "@a" "p" "@b") (k/->Fact "@c" "p" "@d")]
          {:find "r"
           :rules [{:head {:rel "r" :args [{:var "x"}]}
                    :body [{:rel "triple" :args [{:var "x"} "p" {:var "z"}]}]}
                   {:head {:rel "r" :args [{:var "x"} {:var "y"}]}
                    :body [{:rel "triple" :args [{:var "x"} "p" {:var "y"}]}]}]})]
  (chk "#22 inconsistent head arity REJECTED (was ragged :ok output)"
       (and (contains? r :error) (not (contains? r :ok))))
  (chk "#22 error names inconsistent arities"
       (some #(re-find #"inconsistent arit" %) (:error r))))

;; #22 a head derived by MULTIPLE rules at the SAME arity must still run.
(chk "#22 same-arity multi-rule head still runs (not over-rejected)"
     (contains? (q/run facts
                  {:find "edge"
                   :rules [{:head {:rel "edge" :args [{:var "x"} {:var "y"}]}
                            :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                           {:head {:rel "edge" :args [{:var "x"} {:var "y"}]}
                            :body [{:rel "triple" :args [{:var "y"} "depends_on" {:var "x"}]}]}]})
                :ok))

;; #11 (HIGH) result-set bound on the AI-facing path. A cross-join (two
;; unconstrained `triple` literals sharing no variables) over M subjects yields M^2
;; pairs. With no cap this is materialized as a vector and JSON-serialized whole.
;; The cap is q/max-results (env FRAM_MAX_RESULTS, here whatever is configured):
;; build a corpus whose cross-join strictly exceeds it, and assert the over-limit
;; signal is returned INSTEAD of the (huge) result. Self-calibrates to the cap.
(let [cap q/max-results
      m (inc (long (Math/ceil (Math/sqrt (double cap)))))   ; m^2 > cap
      big-facts (mapv (fn [i] (k/->Fact (str "@s" i) "p" (str "@o" i))) (range m))
      r (q/run big-facts
          {:find "pair"
           :rules [{:head {:rel "pair" :args [{:var "a"} {:var "b"}]}
                    :body [{:rel "triple" :args [{:var "a"} {:var "pp"} {:var "qq"}]}
                           {:rel "triple" :args [{:var "b"} {:var "ss"} {:var "tt"}]}]}]})]
  (chk "#11 oversized result set is BOUNDED (over-limit signal, not :ok)"
       (and (not (contains? r :ok)) (contains? r :error)))
  (chk "#11 over-limit signal reports the count and the cap"
       (and (= (:max r) cap) (> (:over-limit r) cap)))
  (chk "#11 over-limit error is a clear, actionable message"
       (some #(re-find #"result set too large" %) (:error r))))

;; #11 a result set WITHIN the cap still returns :ok (the bound doesn't over-trigger).
(chk "#11 result within the cap still returns :ok"
     (contains? (q/run facts {:find "r" :rules [{:head {:rel "r" :args [{:var "x"} {:var "y"}]}
                                                  :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}]}) :ok))

;; ============================================================================
;; (9) INTERNAL QUERY PAGES — deterministic order/cursor semantics, strict
;; limits, mutation behavior, and byte-bounded envelopes.
;; ============================================================================
(def page-q
  {:find "page-row"
   :rules [{:head {:rel "page-row" :args [{:var "x"}]}
            :body [{:rel "fact" :args [{:var "x"} "page" {:var "v"}]}]}]})

(defn page-facts [subjects]
  (mapv (fn [subject] (k/->Fact subject "page" "yes")) subjects))

(defn drain-pages [facts limit]
  (loop [after nil rows [] cursors []]
    (let [page (q/run-page facts page-q limit after)]
      (if (or (:error page) (not (:more page)))
        {:page page :rows (into rows (:ok page)) :cursors cursors}
        (recur (:next page)
               (into rows (:ok page))
               (conj cursors (:next page)))))))

(let [subjects ["@z" "@a" "@m" "@é" "@🐢" "@10" "@2"]
      fs (page-facts subjects)
      first-run (drain-pages fs 3)
      second-run (drain-pages (vec (reverse fs)) 3)
      expected (vec (sort-by pr-str (mapv vector subjects)))]
  (chk "query pages drain every tuple exactly once in canonical order"
       (= expected (:rows first-run)))
  (chk "query page order and cursors ignore fact/set iteration order"
       (= (select-keys first-run [:rows :cursors])
          (select-keys second-run [:rows :cursors])))
  (chk "terminal query page has no cursor and reports :more false"
       (and (= false (get-in first-run [:page :more]))
            (nil? (get-in first-run [:page :next])))))

(let [base (page-facts ["@a" "@m" "@z"])
      first-page (q/run-page base page-q 1 nil)
      changed (page-facts ["@0" "@b" "@m" "@z"])
      next-page (q/run-page changed page-q 10 (:next first-page))]
  (chk "cursor remains valid when its source row is deleted"
       (= [["@b"] ["@m"] ["@z"]] (:ok next-page)))
  (chk "key cursor exposes post-cursor insertions and excludes pre-cursor insertions"
       (and (some #{["@b"]} (:ok next-page))
            (not (some #{["@0"]} (:ok next-page))))))

(let [one (page-facts ["@a"])
      canonical (q/page-cursor ["@a"])
      padded (str canonical "=")]
  (chk "generated cursor round-trips to the exact canonical row key"
       (= (pr-str ["@a"]) (:ok (q/decode-page-cursor canonical))))
  (chk "noncanonical padded base64url aliases are rejected"
       (contains? (q/decode-page-cursor padded) :error))
  (chk "wrong-version, malformed base64url, and malformed UTF-8 are rejected"
       (and (contains? (q/decode-page-cursor "other-v1.91") :error)
            (contains?
             (q/decode-page-cursor
              (str q/page-cursor-prefix "*"))
             :error)
            (contains?
             ;; _w is unpadded base64url for the invalid standalone byte 0xff.
             (q/decode-page-cursor
              (str q/page-cursor-prefix "_w"))
             :error)))
  (chk "oversized incoming cursors are rejected before query evaluation"
       (contains?
        (q/decode-page-cursor
         (str q/page-cursor-prefix
              (apply str (repeat q/max-page-cursor-bytes "1"))))
        :error))
  (chk "page limit is strict positive integer with a hard cap"
       (every?
        :error
        [(q/run-page one page-q 0 nil)
         (q/run-page one page-q (inc q/max-page-limit) nil)
         (q/run-page one page-q "1" nil)])))

;; Cursor size is not monotonic in row count. A 400k-character boundary row has
;; a >512KiB base64url cursor and is inadmissible; the immediately following short
;; row has a tiny cursor, so the two-row page is valid. This defeats the former
;; binary-search assumption and proves exhaustive boundary selection.
(let [huge-a (str "@a" (apply str (repeat 400000 "x")))
      huge-c (str "@c" (apply str (repeat 400000 "x")))
      fs (page-facts [huge-a "@b" huge-c "@d"])
      page1 (q/run-page fs page-q 3 nil)
      page2 (q/run-page fs page-q 3 (:next page1))
      combined (into (:ok page1) (:ok page2))]
  (chk "alternating huge/small cursors choose a later fitting boundary"
       (and (= 2 (count (:ok page1)))
            (= [["@b"]] [(last (:ok page1))])
            (:more page1)
            (string? (:next page1))))
  (chk "alternating cursor pages still drain without loss or duplication"
       (= (vec (sort-by pr-str [[huge-a] ["@b"] [huge-c] ["@d"]]))
          combined))
  (chk "every alternating-cursor page stays below the payload byte bound"
       (every?
        #(<= (count (.getBytes (pr-str %) "UTF-8"))
             q/max-page-payload-bytes)
        [page1 page2])))

;; Non-BMP characters exercise surrogate-pair cursor encoding and maximum UTF-8
;; accounting simultaneously.
(let [unicode-row (str "@a" (apply str (repeat 20000 "🐢")))
      fs (page-facts [unicode-row "@z"])
      page1 (q/run-page fs page-q 1 nil)
      page2 (q/run-page fs page-q 1 (:next page1))]
  (chk "max-Unicode row cursor round-trips exactly"
       (= (pr-str [unicode-row])
          (:ok (q/decode-page-cursor (:next page1)))))
  (chk "max-Unicode pages remain bounded and resume at the next tuple"
       (and (<= (count (.getBytes (pr-str page1) "UTF-8"))
                q/max-page-payload-bytes)
            (= [["@z"]] (:ok page2))
            (not (:more page2)))))

;; No boundary is possible: the first row needs an oversized cursor, while the
;; complete two-row page exceeds the payload cap. The returned error itself is
;; small and bounded.
(let [huge-a (str "@a" (apply str (repeat 1100000 "x")))
      r (q/run-page (page-facts [huge-a "@z"]) page-q 2 nil)]
  (chk "unpageable single-row/cursor combination fails closed"
       (and (:error r) (= q/max-page-wire-bytes (:max-bytes r))))
  (chk "unpageable error envelope is itself wire-bounded"
       (<= (count (.getBytes (pr-str r) "UTF-8"))
           q/max-page-payload-bytes)))

;; --- report ---
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.query:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.query:" (count fails) "FAILED") (System/exit 1))))
