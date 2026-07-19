;; K_ev identity oracle — runs under BOTH babashka (SCI) and Clojure (JVM) from
;; the SAME source, proving the identity law is runtime-invariant across bb==JVM.
;; The node sibling (kev.mjs) is an INDEPENDENT reimplementation for the JS leg.
;; Usage: <bb|clojure> kev.clj <model :b2|:bprime> <coordination.log> <telemetry.log>
;; Emits the deterministic K_ev serialization to stdout.
(require '[r1.model :as m])

(let [[model coord-path telem-path] *command-line-args*
      model (keyword (clojure.string/replace (str model) #"^:" ""))
      coord (m/read-bytes coord-path)
      telem (m/read-bytes telem-path)
      evs   (m/logical-events model coord telem)
      kv    (m/kev-vector evs)]
  (print (m/kev-serialize kv))
  (flush))
