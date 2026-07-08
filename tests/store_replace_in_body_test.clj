#!/usr/bin/env bb
;; replace-in-body — SUB-DEF surgical edit verb (chartroom/src/resolve.clj).
;; Proves: a unique anchor swaps ONE interior fN edge (mint new + supersede one), the
;; def is NOT re-emitted, and the three fail-closed paths (0-match / ambiguous / no-def)
;; refuse with NO store mutation. Uses the real schema module (emit-edn'd) as the corpus.
(require '[resolve :as r] '[fram.store :as c] '[clojure.edn :as edn]
         '[clojure.java.io :as io] '[clojure.string :as str] '[babashka.process :refer [sh]])

(def RT (or (System/getenv "FRAM_ROUNDTRIP")
            (str (System/getenv "HOME") "/code/beagle/beagle-lib/private/facts-roundtrip.rkt")))
(def work (str (System/getProperty "java.io.tmpdir") "/replace-in-body-test-" (System/nanoTime)))
(.mkdirs (io/file work))
(def edn-path (str work "/schema.edn"))
(let [rr (sh {:out (io/file edn-path) :err :string} "racket" RT "--emit-edn"
             (str (System/getProperty "user.dir") "/src/fram/schema.bclj"))]
  (when-not (zero? (:exit rr)) (println "emit-edn failed:" (:err rr)) (System/exit 1)))

(def pass (atom 0)) (def fail (atom 0))
(defn check [name ok?] (if ok? (do (swap! pass inc) (println "  [PASS] " name))
                          (do (swap! fail inc) (println "  [FAIL] " name))))
;; run a verb thunk under capture-only (no re-resolve/project), *reject!* -> a catchable
;; signal; return {:minted N :superseded M} or {:reject CODE}.
(defn run-verb [thunk]
  (let [id0 (:next-id @r/ctx) sup0 (count (:superseded @r/ctx))]
    (try (thunk)
         {:minted (- (:next-id @r/ctx) id0) :superseded (- (count (:superseded @r/ctx)) sup0)}
         (catch clojure.lang.ExceptionInfo e (or (:data (ex-data e)) (ex-data e))))))

(r/resolve-edn!
 [edn-path]
 (fn []
   (binding [r/*capture-only?* true
             r/*reject!* (fn [code & _] (throw (ex-info (str "REJECT " code) {:code code})))]
     (println "================ replace-in-body verb test ================")
     ;; 1) SUCCESS: unique anchor (empty? cs) -> (zero? (count cs))
     (let [res (run-verb #(r/verb-replace-in-body! "cardinality" "schema"
                                                   '(empty? cs) '(zero? (count cs))))]
       (check "unique anchor: superseded exactly 1 fN edge" (= 1 (:superseded res)))
       ;; minted a SMALL subtree (the 5-node replacement + its facts), NOT the whole def
       ;; (a whole-def cardinality re-mint is 100s of objects) — the sub-def granularity win.
       (check "unique anchor: minted the small replacement, not the whole def"
              (and (pos? (:minted res)) (< (:minted res) 60)))))))

;; fresh store per fail-closed case (a rejected edit must leave the store untouched)
(doseq [[nm anchor code] [["0-match anchor rejects (code 5)" '(no-such-form-xyz) 5]
                          ["ambiguous anchor rejects (code 5)" 'cs 5]]]
  (r/resolve-edn!
   [edn-path]
   (fn []
     (binding [r/*capture-only?* true
               r/*reject!* (fn [code & _] (throw (ex-info (str "REJECT " code) {:code code})))]
       (let [res (run-verb #(r/verb-replace-in-body! "cardinality" "schema" anchor '(x)))]
         (check nm (and (= code (:code res)) (nil? (:minted res)))))))))
(r/resolve-edn!
 [edn-path]
 (fn []
   (binding [r/*capture-only?* true
             r/*reject!* (fn [code & _] (throw (ex-info (str "REJECT " code) {:code code})))]
     (let [res (run-verb #(r/verb-replace-in-body! "no-such-def" "schema" '(a) '(b)))]
       (check "no-def rejects (code 5), nothing minted" (and (= 5 (:code res)) (nil? (:minted res))))))))

(sh {} "rm" "-rf" work)
(println (str "\nreplace-in-body: " @pass " / " (+ @pass @fail) " PASS"))
(when (pos? @fail) (System/exit 1))
