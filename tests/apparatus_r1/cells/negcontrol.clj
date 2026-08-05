;; Negative control — proves the apparatus itself FAILS LOUD (nonzero exit) on an
;; unexpected verdict. run.sh asserts this cell exits nonzero; if it ever exits
;; zero the whole harness is untrustworthy. This deliberately asserts a claim the
;; B2 model contradicts (that B-prime preserves the byte-identical append).
(require '[r1.model :as m] '[r1.harness :as h])

(def prefix "{:tx 1 :p \"e\" :r \"a\"}\n{:tx 2 :p \"e\" :r \"b\"}\n")
(def gen (m/bytes->str (m/gen-record-bytes {:tx 3 :gen-n 1 :telem-prefix prefix})))
(def coord (m/str->bytes (str gen "\n" prefix)))
(def telem (m/str->bytes (str prefix "{:tx 2 :p \"e\" :r \"b\"}\n")))
(def bp-mult
  (count (filter (fn [[tx _ _]] (= tx 2))
                 (m/kev-vector (m/logical-events :bprime coord telem)))))

(h/section "NEGATIVE CONTROL (must fail)")
;; DELIBERATELY WRONG: claim B-prime preserves the append (it suppresses it).
(h/check! "INTENTIONALLY-FALSE: B-prime multiplicity of tx=2 == 2" 2 bp-mult)
(h/finish!) ; must System/exit 1
