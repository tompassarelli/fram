;; ============================================================================
;; cnf_cardinality_reified_migrate_test — #2 reified-log cardinality migration.
;; ============================================================================
;; The flat migrator (migrate_cardinality_test / cnf_cardinality_test) covers the
;; `assert l p r` log. The FLEET coordinator log is CNF-REIFIED ({:k :value/:claim/
;; :tx/:commit}); #2 removed the runtime auto-pin so a pred with no cardinality claim
;; DEFAULTS TO MULTI. This proves the reified migration path: build a reified store
;; whose messaging preds are unpinned (read multi), append cardinality=single through
;; the engine (replay! -> claim! -> records-since -> append), and assert the round-trip
;; fold reads them single while a genuinely-multi pred stays multi and no live triple
;; is lost. Mirrors what bin/fram-cardinality-migrate-reified does on the live log.
;; ============================================================================
(require '[fram.cnf-coord :as cc]
         '[fram.cnf :as c]
         '[fram.schema :as s]
         '[fram.rt :as rt])

(def ^:dynamic *fails* (atom 0))
(defn check [name pred]
  (if pred (println "  ok  " name)
      (do (swap! *fails* inc) (println "  FAIL" name))))

(def tmp (str (System/getProperty "java.io.tmpdir") "/cnf-card-reified-" (System/nanoTime) ".log"))
(def migrated (str tmp ".migrated"))

;; --- build a small reified log: a coordinator with messaging-shaped claims -----
;; from/to/subject are SINGLE-vocab but UNPINNED here; acked_by is genuinely multi.
(def co (cc/new-coord! tmp))
(cc/commit! co "a" "@msg:1" "from" :assert "alice" 0)
(cc/commit! co "a" "@msg:1" "to" :assert "bob" 0)
(cc/commit! co "a" "@msg:1" "subject" :assert "hi" 0)
;; acked_by: two distinct ackers on the same subject -> genuinely multi
(cc/register-pred! co "acked_by" "multi" "literal")
(cc/commit! co "a" "@msg:1" "acked_by" :assert "carol" 0)
(cc/commit! co "a" "@msg:1" "acked_by" :assert "dave" 0)

;; pre-migration: unpinned single-vocab preds read MULTI (the deploy bug) ---------
(def pre (cc/replay! tmp))
(check "pre: from reads multi (unpinned)"    (= "multi" (s/cardinality pre "from")))
(check "pre: acked_by reads multi"           (= "multi" (s/cardinality pre "acked_by")))
(def pre-live (count (cc/live-triples pre)))

;; --- migrate: append cardinality=single for the single-vocab preds, one tx ------
(def st (cc/replay! tmp))
(def card (c/value! st "cardinality"))
(def sgl  (c/value! st "single"))
(def singles ["from" "to" "subject"])
(def since (c/next-id st))
(def tx (c/begin-tx! st "migrate-test"))
(doseq [p singles] (c/claim! st (c/value! st p) card sgl tx))
(rt/spit-file migrated (slurp tmp))                       ; migrated = orig ...
(rt/append-records-fsync! migrated (vec (c/records-since st since tx)))  ; ... + new tx

;; --- round-trip: replay migrated, assert the fold ------------------------------
(def post (cc/replay! migrated))
(check "post: from reads single"     (= "single" (s/cardinality post "from")))
(check "post: to reads single"       (= "single" (s/cardinality post "to")))
(check "post: subject reads single"  (= "single" (s/cardinality post "subject")))
(check "post: acked_by stays multi"  (= "multi"  (s/cardinality post "acked_by")))

(def post-live (cc/live-triples post))
(check "no live triple lost"
       (clojure.set/subset? (cc/live-triples pre) post-live))
(check "exactly 3 cardinality triples added"
       (= 3 (- (count post-live) pre-live)))

;; --- idempotency: re-pinning an already-single pred via single-write supersedes -
;; (the engine's single-valued contract: a second cardinality=single supersedes the
;; first, so the live set stays one cardinality claim per pred — no accumulation.)
(def st2 (cc/replay! migrated))
(check "post: from already single (idempotent target)"
       (= "single" (s/cardinality st2 "from")))

(rt/spit-file tmp "") (rt/spit-file migrated "")
(println (if (zero? @*fails*) "\nALL PASS" (str "\n" @*fails* " FAILURES")))
(when (pos? @*fails*) (System/exit 1))
