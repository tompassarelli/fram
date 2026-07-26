;; coord_projection_singleflight_test.clj — the coordinator's per-version Datalog
;; projection cache (coord_daemon/whole-corpus-projection + projection-cache +
;; projection-build-lock). This is the memory-ceiling half of the fix: exactly ONE
;; full-corpus projection is materialized per cold version, and every concurrent
;; scan/page reader at that version SHARES the one immutable value — so N concurrent
;; cold queries can no longer each allocate their own full-corpus EDB+index (the
;; multiplication behind the load-stress OOM). A new facts set (a new version)
;; rebuilds exactly once and the cache holds a single entry.
;;
;; Since thread 019f9e66 this whole-corpus build is no longer the DEFAULT read path
;; — the SPO/POS/OSP covering rotations serve it in O(1) — but it survives verbatim
;; as the fallback for queries naming the `fact-id` relation, whose cids are
;; positional and so cannot be maintained incrementally. Section (5) pins that
;; routing, because a routing bug would silently serve a query from an index that
;; structurally lacks the relation it asks for.
;;
;; Loads coord_daemon.clj in-process (its entry point is *command-line-args*-guarded,
;; so no server starts) and drives the real functions.
;;   bb -cp out tests/coord_projection_singleflight_test.clj
(require '[fram.kernel :as k]
         '[fram.query :as q])
(load-file "coord_daemon.clj")

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

(defn corpus [tag n]
  (set (mapcat (fn [i] [(k/->Fact (str "@" tag i) "to" "@me")
                        (k/->Fact (str "@" tag i) "from" (str "@s" (mod i 5)))])
               (range n))))

(def facts-a (corpus "a" 500))
(def facts-b (corpus "b" 500))

;; reset the cache to a known-empty state before probing.
(reset! coord-daemon/projection-cache nil)

;; --- (1) same facts set: cached, single build, identical object ---------------
(def build-count (atom 0))
(with-redefs [q/project (let [orig q/project]
                          (fn [facts] (swap! build-count inc) (orig facts)))]
  (let [p1 (coord-daemon/whole-corpus-projection facts-a)
        p2 (coord-daemon/whole-corpus-projection facts-a)]
    (chk "same facts set returns the identical projection object" (identical? p1 p2))
    (chk "same facts set builds the projection exactly once" (= 1 @build-count))
    (chk "cache holds that facts set" (identical? facts-a (:facts @coord-daemon/projection-cache)))

    ;; --- (2) concurrency: many readers, still exactly one materialization -----
    (reset! build-count 0)
    (reset! coord-daemon/projection-cache nil)
    (let [n 32
          barrier (java.util.concurrent.CountDownLatch. 1)
          results (atom [])
          threads (mapv (fn [_]
                          (Thread.
                           (fn []
                             (.await barrier)
                             (swap! results conj (coord-daemon/whole-corpus-projection facts-a)))))
                        (range n))]
      (doseq [t threads] (.start t))
      (.countDown barrier)
      (doseq [t threads] (.join t))
      (let [rs @results]
        (chk "concurrent readers all get one identical shared projection"
             (and (= n (count rs)) (every? #(identical? (first rs) %) rs)))
        (chk "concurrent cold readers materialize exactly once (single-flight)"
             (= 1 @build-count))))

    ;; --- (3) a new facts set (new version) rebuilds once, cache advances ------
    (reset! build-count 0)
    (let [pa (coord-daemon/whole-corpus-projection facts-a)   ; still cached from (2)
          pb (coord-daemon/whole-corpus-projection facts-b)]  ; miss → one rebuild
      (chk "cached version is a hit (no rebuild)" (= 1 @build-count))
      (chk "new version yields a distinct projection" (not (identical? pa pb)))
      (chk "cache advances to the new facts set (single retained entry)"
           (identical? facts-b (:facts @coord-daemon/projection-cache))))))

;; --- (4) the cached projection actually answers the production page query -----
(let [proj (coord-daemon/whole-corpus-projection facts-a)
      pending {:find "pending_message"
               :strata [[{:head {:rel "message_candidate" :args [{:var "e"}]}
                          :body [{:rel "fact" :args [{:var "e"} "to" "@me"]}]}]
                        [{:head {:rel "pending_message" :args [{:var "e"}]}
                          :body [{:rel "message_candidate" :args [{:var "e"}]}]}]]}
      page (q/run-page-projected proj pending 10 nil)]
  (chk "cached projection serves a bounded page" (= 10 (count (:ok page))))
  (chk "cached projection page has a continuation cursor" (string? (:next page))))

;; --- (5) routing: fact-id -> whole corpus, everything else -> rotations -------
;; The two projections are structurally distinguishable: the rotations projection
;; keys its :edb by "fact" ONLY, while the whole-corpus projection also carries
;; the positional "fact-id" relation. Assert the router picks by that property.
(let [snapshot {:facts facts-a :idx (rotations/build (map (fn [f] [(:l f) (:p f) (:r f)]) facts-a))}
      plain {:find "m" :rules [{:head {:rel "m" :args [{:var "e"}]}
                                :body [{:rel "fact" :args [{:var "e"} "to" "@me"]}]}]}
      with-id {:find "m" :rules [{:head {:rel "m" :args [{:var "e"} {:var "c"}]}
                                  :body [{:rel "fact-id" :args [{:var "c"} {:var "e"} "to" "@me"]}]}]}
      pf (coord-daemon/snapshot-projection snapshot plain)
      pi (coord-daemon/snapshot-projection snapshot with-id)]
  (chk "a plain query is served by the rotations projection (no fact-id relation)"
       (and (contains? (:edb pf) "fact") (not (contains? (:edb pf) "fact-id"))))
  (chk "a fact-id query is routed to the whole-corpus projection"
       (contains? (:edb pi) "fact-id"))
  (chk "the fact-id route reuses the single-flight cache"
       (identical? pi (coord-daemon/snapshot-projection snapshot with-id)))
  ;; the rotations projection must still ANSWER the plain query identically
  (chk "rotations projection answers the plain query exactly as the whole corpus"
       (= (set (:ok (q/run-projected pf plain)))
          (set (:ok (q/run-projected (coord-daemon/whole-corpus-projection facts-a) plain))))))

(def failed (filter (comp not second) @checks))
(doseq [[nm ok] @checks] (println (str "  [" (if ok "PASS" "FAIL") "]  " nm)))
(println (str "\ncoord projection single-flight: " (- (count @checks) (count failed)) " / " (count @checks) " PASS"))
(when (seq failed) (System/exit 1))
