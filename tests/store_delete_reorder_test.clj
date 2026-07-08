;; ============================================================================
;; store_delete_reorder_test.clj — the `delete` + `reorder` authoring verbs are
;; claim-native, fail-closed, and round-trip through render-EDN → beagle --build-edn.
;; ============================================================================
;; The last two verbs wired into the claim-native authoring loop (#36 / #33):
;;
;;   delete   — remove a top-level def by name. EFFECT = supersede the wrapper's
;;              fN form-edge claim pointing at the form (NOT a render-time omission
;;              flag), so the form drops out of the wrapper's live children: the
;;              minimal-op harvest sees ONE retract; the render reachability filter
;;              drops the orphaned subtree. FAIL-CLOSED: a delete that would orphan
;;              a SURVIVING reference REFUSES (no-orphaned-refs), mutating nothing.
;;   reorder  — MOVE a def in place by RE-SPELLING its wrapper order key (#36 CRDT
;;              f<path>~<tie>) — NOT by insert+delete (which would churn the moved
;;              subtree's node identity). Same form root, one supersede + one assert.
;;
;; PROVES:
;;   D1  delete supersedes exactly the wrapper form-edge(s); the def is gone from
;;       the render, its body subtree absent, surviving forms intact.
;;   D2  delete of a STILL-REFERENCED def is REFUSED (orphan invariant; nothing mutated).
;;   R1  reorder moves the form to its new slot WITHOUT re-minting it (the SAME node id
;;       backs the moved form — 0 node churn) and the new order is what the render shows.
;;   E   the post-delete+reorder module builds via beagle --build-edn with 0 errors and
;;       the `:- T` annotations survive (^T) — the verbs compose with the `:-` encode fix.
;;
;;   bb -cp out tests/store_delete_reorder_test.clj   (from the repo root)
;; SAFE: /tmp work dir, in-process; no daemon, no socket, no canonical log touched.
;; ============================================================================
(require '[fram.store :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.java.io :as io] '[babashka.process :as proc])

(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (or (System/getenv "FRAM_ROUNDTRIP") (str beagle-home "/beagle-lib/private/claims-roundtrip.rkt")))
(def build-all (or (System/getenv "FRAM_BUILD_ALL") (str beagle-home "/bin/beagle-build-all")))

(doseq [[p label] [[(str root "/chartroom/src/resolve.clj") "chartroom resolve.clj"]
                   [roundtrip-rkt "claims-roundtrip.rkt"]
                   [build-all "beagle-build-all"]]]
  (when-not (.exists (io/file p))
    (println "SKIP — missing prerequisite:" label "(" p ")") (System/exit 0)))

(load-file (str root "/chartroom/src/resolve.clj"))

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))

(def work (str (System/getProperty "java.io.tmpdir") "/delete-reorder-" (System/nanoTime)))
(.mkdirs (io/file work))

;; helper: catch a verb rejection (the *reject!* throw) -> :rejected, else :ok.
(defmacro caught? [& body]
  `(try (do ~@body :ok) (catch Exception _# :rejected)))

;; seed a module: three referenced typed defs (base <- greet, punct <- greet) plus
;; one UNREFERENCED typed def (`dead`) — the safe delete target. Seeding `dead` (vs
;; upsert-then-delete) keeps it in the initial module frame, so the delete victim is
;; resolvable without a mid-binding frame refresh (the warm/daemon path rebuilds the
;; frame per verb; the resolve-edn! test path computes it once at setup).
(def seed (str work "/demo.bclj"))
(spit seed (str "#lang beagle/clj\n"
                "(def base :- String \"hello\")\n"
                "(def punct :- String \"!\")\n"
                "(def dead :- String \"x\")\n"
                "(defn greet [who :- String] :- String (str base \" \" who punct))\n"))
(def seed-edn (str work "/demo.edn"))
(def emit-r (proc/sh {:out :string :err :string} "racket" roundtrip-rkt "--emit-edn" seed))

(if-not (zero? (:exit emit-r))
  (chk "B0: emit-edn seed module" false)
  (do
    (spit seed-edn (:out emit-r))

    ;; --- D2: fail-closed — delete a STILL-REFERENCED def must REFUSE, mutate nothing ---
    (resolve/resolve-edn! [seed-edn]
      (fn []
        (binding [resolve/*reject!* (fn [code] (throw (ex-info (str "rejected " code) {:code code})))]
          (let [src (first resolve/srcs)
                v-before (count (filter #(seq (c/by-lp resolve/ctx % resolve/Vp)) (@resolve/file->ents src)))
                res (caught? (resolve/verb-delete! "base" "demo"))   ; greet references base -> orphan
                v-after (count (filter #(seq (c/by-lp resolve/ctx % resolve/Vp)) (@resolve/file->ents src)))]
            (chk "D2a: delete of still-referenced `base` is REFUSED (orphan invariant)" (= :rejected res))
            (chk "D2b: the refused delete mutated NOTHING (live v-claim count unchanged)" (= v-before v-after))))))

    ;; --- D1 + R1 + E: delete an UNREFERENCED def, reorder a def, render, build -----
    (def out-edn (str work "/rendered.edn"))
    (resolve/resolve-edn! [seed-edn]
      (fn []
        (binding [resolve/*reject!* (fn [code] (throw (ex-info (str "rejected " code) {:code code})))]
          (let [src   (first resolve/srcs)
                wrap  (resolve/wrapper-of src)
                ;; capture the node id backing `base` BEFORE the reorder, to prove no re-mint.
                base-form-before (resolve/form-for-victim src (resolve/def-binding src "base"))]
            ;; def name of a LIVE wrapper form (value def only; nil for ns/define-mode).
            (let [form-name (fn [r] (let [d (resolve/unwrap-def r)]
                                      (when (resolve/VALUE-DEFS (resolve/head-sym d))
                                        (resolve/sym-val (second (resolve/ordered-children d))))))
                  dead-form (resolve/form-for-victim src (resolve/def-binding src "dead"))]
              ;; delete the UNREFERENCED def `dead` (safe: nothing refers to it).
              (chk "D1a: the to-delete def `dead` exists pre-delete" (some? (resolve/def-binding src "dead")))
              (resolve/verb-delete! "dead" "demo")
              ;; the EFFECT is claim-native: `dead`'s wrapper form-edge is no longer LIVE
              ;; (def-binding reads the stale frame table, so we query live wrapper edges).
              (chk "D1b: `dead`'s wrapper form-edge is no longer live post-delete"
                   (not (some (fn [[_ _ r]] (= r dead-form)) (resolve/wrap-forms wrap))))
              ;; reorder `base` AFTER `punct` (base,punct,greet -> punct,base,greet). Same node id.
              (resolve/verb-reorder! "base" "demo" "punct")
              (let [base-form-after (resolve/form-for-victim src (resolve/def-binding src "base"))]
                (chk "R1a: reorder moved `base` WITHOUT re-minting it (SAME node id backs the form)"
                     (= base-form-before base-form-after)))
              ;; the new VALUE-DEF order, read off the live wrapper (by CRDT key): punct, base, greet.
              (let [order (->> (resolve/wrap-forms wrap) (map (fn [[_ _ r]] (form-name r))) (remove nil?) vec)]
                (chk "R1b: post-reorder value-def order is [punct base greet]"
                     (= order ["punct" "base" "greet"]))))))
        (resolve/extract-file! (first resolve/srcs) out-edn)))

    (let [edn (slurp out-edn)]
      (chk "D1c: render-EDN does NOT contain the deleted def's name (`dead`)"
           (not (str/includes? edn "\"dead\"")))
      (let [br (proc/sh {:out :string :err :string} build-all out-edn "--build-edn")
            blob (str (:out br) (:err br))]
        (chk "E1: beagle --build-edn of the post-delete+reorder module builds 0 errors"
             (re-find #"\b1 built, 0 error\(s\)" blob))
        (let [bout (str work "/build")]
          (.mkdirs (io/file bout))
          (proc/sh {:out :string :err :string} build-all out-edn "--build-edn" "--out" bout)
          (let [emitted (->> (file-seq (io/file bout)) (filter #(.isFile %))
                             (map slurp) (str/join "\n"))]
            (chk "E2: the `:- String` annotations survive to the emitted Clojure (^String)"
                 (str/includes? emitted "^String"))
            (chk "E3: the deleted def `dead` is absent from the emitted program"
                 (not (str/includes? emitted "dead")))))))))

;; --- verdict ----------------------------------------------------------------
(println "\n=== delete + reorder verbs: claim-native, fail-closed, build-clean ===")
(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (do (println "\nPASS —" (count cs) "/" (count cs) ": delete + reorder are sound and compose with --build-edn.")
        (System/exit 0))
    (do (println "\nFAIL —" (count fails) "of" (count cs) "checks failed.") (System/exit 1))))
