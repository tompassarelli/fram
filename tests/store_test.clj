;; store_test.clj — reified store kernel: smoke + the app-B checks + SEMANTIC
;; conformance to the Racket oracle's real fixtures (store-experiments).
;;   bb -cp out store_test.clj
(require '[fram.store :as k])

(def ctx (k/new-store))
(def tx (k/begin-tx! ctx "test"))

;; --- interning (oracle fixture A/B) -----------------------------------------
(def h1 (k/value! ctx "hello"))
(def h2 (k/value! ctx "hello"))
(def w  (k/value! ctx "world"))
(def i42a (k/value! ctx 42))
(def i42b (k/value! ctx 42))
(def f42  (k/value! ctx 42.0))
(def kw   (k/value! ctx :foo))
(def vfalse (k/value! ctx false))
(def vlist (k/value! ctx [1 2 3]))

;; --- entities + facts + reification (fixture D) ----------------------------
(def e (k/entity! ctx))
(def p-title (k/value! ctx "title"))
(def foo (k/value! ctx "Foo"))
(def c1 (k/fact! ctx e p-title foo tx))
(def p-noted (k/value! ctx "noted_by"))
(def who (k/value! ctx "claude"))
(def c-meta (k/fact! ctx c1 p-noted who tx))

;; --- facts are NOT interned: identical (l p r) -> two distinct cids ---------
(def e2 (k/entity! ctx))
(def p-tag (k/value! ctx "tag"))
(def vred (k/value! ctx "red"))
(def d1 (k/fact! ctx e2 p-tag vred tx))
(def d2 (k/fact! ctx e2 p-tag vred tx))

;; --- ordering: multiple (l p) values keep insertion order (fixture E) -------
(def e3 (k/entity! ctx))
(def p-child (k/value! ctx "child"))
(def cx (k/fact! ctx e3 p-child (k/value! ctx "x") tx))
(def cy (k/fact! ctx e3 p-child (k/value! ctx "y") tx))
(def cz (k/fact! ctx e3 p-child (k/value! ctx "z") tx))

;; --- data-driven supersession (fixture C/C2) --------------------------------
(def sp (k/value! ctx "superseded_by"))
(k/set-supersedes-pred! ctx sp)
(def e4 (k/entity! ctx))
(def p-name (k/value! ctx "name"))
(def old (k/fact! ctx e4 p-name (k/value! ctx "v1") tx))
(def newc (k/fact! ctx e4 p-name (k/value! ctx "v2") tx))
;; assert (newc superseded_by old) -> marks `old` not-live
(def sup (k/fact! ctx newc sp old tx))

(def checks
  [["[A] same literal interns to same id"            (= h1 h2)]
   ["[A] value-id round-trips"                        (= h1 (k/value-id ctx "hello"))]
   ["[A] distinct literal -> distinct id"             (not= h1 w)]
   ["[B] number interns idempotently"                 (= i42a i42b)]
   ["[B] 42 and 42.0 are distinct values"             (not= i42a f42)]
   ["[B] keyword interns to an id"                    (integer? kw)]
   ["[B] #f is a real value-object (not 'absent')"    (and (k/value-object? ctx vfalse) (= false (k/literal ctx vfalse)))]
   ["[B] list literal round-trips"                    (= [1 2 3] (k/literal ctx vlist))]
   ["[check1] interning is type-general"              (and (integer? i42a) (integer? kw) (integer? vlist))]
   ["fact! returns a fresh object id"                (and (integer? c1) (not= c1 e))]
   ["fact-of resolves the triple"                    (= {:l e :p p-title :r foo} (k/fact-of ctx c1))]
   ["fact-tx records the tx"                         (= tx (k/fact-tx ctx c1))]
   ["[check3/D] fact-id usable as l (reification)"   (= [c-meta] (k/by-lp ctx c1 p-noted))]
   ["[check3/D] meta-fact resolves with fact as l"  (= {:l c1 :p p-noted :r who} (k/fact-of ctx c-meta))]
   ["facts are NOT interned (2 distinct cids)"        (and (not= d1 d2) (= [d1 d2] (k/by-lp ctx e2 p-tag)))]
   ["[check2/E] insertion order preserved"            (= [cx cy cz] (k/by-lp ctx e3 p-child))]
   ["[C] supersedes-fact marks old not-live"         (not (k/live? ctx old))]
   ["[C] new fact stays live"                        (k/live? ctx newc)]
   ["[C] live view excludes the superseded fact"     (= [newc] (k/by-lp ctx e4 p-name))]
   ["[C] the supersedes-fact itself is live"         (k/live? ctx sup)]
   ["[check4] single monotonic id space, all distinct" (apply distinct? [e e2 e3 e4 c1 c-meta d1 d2 foo p-title])]])

(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nstore kernel conformance:" (count checks) "/" (count checks) "PASS")
    (do (println "\nstore kernel:" (count fails) "FAILED") (System/exit 1))))
