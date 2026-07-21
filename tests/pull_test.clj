;; pull_test.clj — PULL API gate (in-process, mirrors coord_test.clj bootstrap).
;; Drives pull/validate + pull/run against a store built through the coord API; no
;; network daemon. A dispatch-level test at the end boots the daemon's `co`/`schema`
;; state in-process and exercises execute-query for {:op :pull}.
;;   bb -cp out tests/pull_test.clj
(require '[fram.store :as c] '[fram.schema :as s])
(load-file "coord.clj")   ; coord readers land in `user` (pull references them as user/…)
(load-file "pull.clj")    ; MUST follow coord.clj (analysis-time qualified-symbol resolution)

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))
(defn throws? [f] (try (f) false (catch Throwable _ true)))

(let [log "/tmp/pull-test.log"
      co  (new-coord log)
      st  (:store co)]
  (register-pred! co "title" "single" "literal")
  (register-pred! co "status" "single" "literal")
  (register-pred! co "tag" "multi" "literal")
  (register-pred! co "depends_on" "multi" "ref")
  (register-pred! co "part_of" "single" "ref")
  (register-pred! co "rel" "multi" "ref")            ; unguarded ref: for cycle/depth tests

  ;; --- corpus ---------------------------------------------------------------
  (commit! co "u" "@x" "title" :assert "Ship v1" nil)
  (commit! co "u" "@x" "status" :assert "open" nil)
  (commit! co "u" "@x" "tag" :assert "red" nil)
  (commit! co "u" "@x" "tag" :assert "blue" nil)
  (commit! co "u" "@x" "depends_on" :link "@dep1" nil)
  (commit! co "u" "@dep1" "title" :assert "Design" nil)
  (commit! co "u" "@a" "part_of" :link "@x" nil)     ; reverse: (@a part_of @x)
  (commit! co "u" "@b" "part_of" :link "@x" nil)

  ;; --- (1) flat scalar attrs ------------------------------------------------
  (let [r (pull/run st "@x" ["title" "status"] {})]
    (chk "flat: :fram/id present" (= "@x" (:fram/id r)))
    (chk "flat: single scalar title" (= "Ship v1" (get r "title")))
    (chk "flat: single scalar status" (= "open" (get r "status"))))

  ;; --- (2) single- vs multi-cardinality rendering ---------------------------
  (let [r (pull/run st "@x" ["status" "tag"] {})]
    (chk "cardinality: single -> scalar" (string? (get r "status")))
    (chk "cardinality: multi -> vector" (vector? (get r "tag")))
    (chk "cardinality: multi values present" (= #{"red" "blue"} (set (get r "tag")))))

  ;; --- (3) nested ref recursion ---------------------------------------------
  (let [r (pull/run st "@x" [{"depends_on" ["title"]}] {})
        deps (get r "depends_on")]
    (chk "nested: depends_on is a vector" (vector? deps))
    (chk "nested: target :fram/id" (= "@dep1" (:fram/id (first deps))))
    (chk "nested: target attr pulled" (= "Design" (get (first deps) "title"))))

  ;; --- (4) reverse ref ------------------------------------------------------
  (let [r (pull/run st "@x" ["_part_of"] {})
        rev (get r "_part_of")]
    (chk "reverse: vector of subjects" (vector? rev))
    (chk "reverse: found the pointing subjects" (= #{"@a" "@b"} (set (map :fram/id rev)))))

  ;; --- (5) :* wildcard ------------------------------------------------------
  (let [r (pull/run st "@x" [:*] {})]
    (chk "wildcard: includes title" (= "Ship v1" (get r "title")))
    (chk "wildcard: includes multi tag" (= #{"red" "blue"} (set (get r "tag"))))
    (chk "wildcard: ref rendered as name string (no recursion)" (= ["@dep1"] (get r "depends_on")))
    (chk "wildcard: reserved preds hidden" (not (contains? r "name"))))

  ;; --- (6) provenance -------------------------------------------------------
  (let [r (pull/run st "@x" ["status"] {:provenance true})
        p (get r "status")]
    (chk "prov: :val" (= "open" (:val p)))
    (chk "prov: :cid is an int" (integer? (:cid p)))
    (chk "prov: :by is the agent" (= "u" (:by p)))
    (chk "prov: :seq is an int" (integer? (:seq p)))
    (chk "prov: live value :withdrawn false" (= false (:withdrawn p))))

  ;; --- (7) provenance surfaces a WITHDRAWN value with attribution ------------
  (retract! co "w" "@x" "tag" "red" nil "no longer relevant")
  (let [plain (pull/run st "@x" ["tag"] {})
        prov  (pull/run st "@x" ["tag"] {:provenance true})
        red   (first (filter #(= "red" (:val %)) (get prov "tag")))]
    (chk "withdraw: plain view drops the withdrawn value" (= #{"blue"} (set (get plain "tag"))))
    (chk "withdraw: provenance surfaces the withdrawn value" (some? red))
    (chk "withdraw: :withdrawn true" (= true (:withdrawn red)))
    (chk "withdraw: :withdrawn_by" (= "w" (:withdrawn_by red)))
    (chk "withdraw: :withdrawn_reason" (= "no longer relevant" (:withdrawn_reason red)))
    (chk "withdraw: :withdrawn_at present" (some? (:withdrawn_at red))))

  ;; --- (8) as-of: historical value + historical withdrawal state ------------
  (let [r1 (commit! co "u" "@y" "status" :assert "s1" nil)
        r2 (commit! co "u" "@y" "status" :assert "s2" nil)
        atS1 (pull/run st "@y" ["status"] {:as-of (:ok r1)})
        now  (pull/run st "@y" ["status"] {})]
    (chk "as-of: sees the historical value at S1" (= "s1" (get atS1 "status")))
    (chk "as-of: current view sees the latest" (= "s2" (get now "status"))))
  (let [rt (commit! co "u" "@y" "tag" :assert "T" nil)
        _  (retract! co "u" "@y" "tag" "T" nil)                 ; withdrawn AFTER seqT
        atT (pull/run st "@y" ["tag"] {:provenance true :as-of (:ok rt)})
        tv  (first (filter #(= "T" (:val %)) (get atT "tag")))]
    (chk "as-of: value withdrawn after S is live at S" (some? tv))
    (chk "as-of: withdrawal after S renders :withdrawn false at S" (= false (:withdrawn tv))))

  ;; --- (9) caps: max-depth truncation ---------------------------------------
  (commit! co "u" "@c1" "rel" :link "@c2" nil)
  (commit! co "u" "@c2" "rel" :link "@c3" nil)
  (commit! co "u" "@c3" "rel" :link "@c4" nil)
  (let [r (pull/run st "@c1" [{"rel" :...}] {:max-depth 2})
        c2 (first (get r "rel"))
        c3 (first (get c2 "rel"))
        c4 (first (get c3 "rel"))]
    (chk "max-depth: chain expands to the cap" (and (= "@c2" (:fram/id c2)) (= "@c3" (:fram/id c3))))
    (chk "max-depth: node beyond the cap is truncated" (and (= "@c4" (:fram/id c4)) (:fram/truncated c4))))

  ;; --- (10) caps: max-nodes -> :fram/truncated ------------------------------
  (let [r (pull/run st "@x" [{"depends_on" ["title"]}] {:max-nodes 1})
        dep (first (get r "depends_on"))]
    (chk "max-nodes: budget exhausted truncates deeper subjects"
         (and (= "@dep1" (:fram/id dep)) (:fram/truncated dep) (not (contains? dep "title")))))

  ;; --- (11) cycle: :fram/cycle stub + termination ---------------------------
  (commit! co "u" "@k1" "rel" :link "@k2" nil)
  (commit! co "u" "@k2" "rel" :link "@k1" nil)
  (let [r (pull/run st "@k1" [{"rel" :...}] {})            ; terminating IS the evidence
        k2 (first (get r "rel"))
        back (first (get k2 "rel"))]
    (chk "cycle: revisit on current path becomes a :fram/cycle stub"
         (and (= "@k1" (:fram/id back)) (:fram/cycle back))))

  ;; --- (12) validation: total, never throws ---------------------------------
  (chk "validate: ok pattern -> []"
       (= [] (pull/validate "@x" ["title" {"depends_on" ["title"]} "_part_of" :* {"rel" 3} {"rel" :...}]
                            {:max-depth 3 :max-nodes 10})))
  (chk "validate: pattern must be a vector" (seq (pull/validate "@x" "nope" {})))
  (chk "validate: malformed element" (seq (pull/validate "@x" [42] {})))
  (chk "validate: bad recursion token (0)" (seq (pull/validate "@x" [{"rel" 0}] {})))
  (chk "validate: bad recursion token (string)" (seq (pull/validate "@x" [{"rel" "bad"}] {})))
  (chk "validate: non-positive :max-depth" (seq (pull/validate "@x" ["title"] {:max-depth 0})))
  (chk "validate: non-positive :max-nodes" (seq (pull/validate "@x" ["title"] {:max-nodes -3})))
  (chk "validate: bad root" (seq (pull/validate 42 ["title"] {})))
  (chk "validate: never throws on garbage" (not (throws? #(pull/validate {:x 1} {:y 2} {:max-depth :bad}))))
  (chk "run: malformed pattern -> {:error}, no throw"
       (let [r (pull/run st "@x" [42] {})] (and (map? r) (contains? r :error))))

  ;; --- (13) unknown root: sensible empty node (documented choice) ------------
  (chk "unknown root -> bare {:fram/id} node, not an error"
       (= {:fram/id "@nope"} (pull/run st "@nope" ["title"] {})))

  ;; --- (14) vector root -----------------------------------------------------
  (let [r (pull/run st ["@x" "@dep1"] ["title"] {})]
    (chk "vector root: one node per root" (and (vector? r) (= 2 (count r))))
    (chk "vector root: nodes resolve independently"
         (= ["Ship v1" "Design"] (mapv #(get % "title") r)))))

;; --- (15) WIRE: dispatch-level {:op :pull} through the daemon's execute-query -
;; Boot the daemon's co/schema/cache state in-process (no socket) and drive the real
;; execute-query path, proving edits #2/#3 (query-request? + the :pull case branch) wire
;; the op end-to-end. If loading coord_daemon.clj fails standalone (it also load-files
;; chartroom/src/resolve.clj + requires fram.{fold,query,datalog,rt}), we SKIP loudly
;; rather than fake the dispatch.
(let [loaded? (try (load-file "coord_daemon.clj") true
                   (catch Throwable t
                     (println "  [SKIP]  wire dispatch: coord_daemon.clj not loadable standalone —"
                              (.getMessage t))
                     false))]
  (when loaded?
    ;; Daemon globals don't exist at THIS file's analysis time (coord_daemon loads at
    ;; runtime), so reach them through runtime `resolve` — keeps the core suite above
    ;; decoupled from coord_daemon's own load-time dependencies.
    (let [co-var     (resolve 'co)
          schema-var (resolve 'schema-view)
          cache-var  (resolve 'cache)
          capture    (resolve 'capture-query-roots!)
          exec       (resolve 'execute-query)
          dlog "/tmp/pull-wire-test.log"
          dco  (new-coord dlog)]
      (register-pred! dco "title" "single" "literal")
      (commit! dco "u" "@w1" "title" :assert "Wired" nil)
      (reset! @co-var dco)                      ; the daemon's global coordinator atom
      (reset! @schema-var {})
      (reset! @cache-var {:index nil :version -1})
      (let [roots (capture)
            resp  (exec {:op :pull :root "@w1" :pattern ["title"]} roots)]
        (chk "wire: {:op :pull} dispatches through execute-query"
             (= "Wired" (get resp "title")))
        (chk "wire: response carries :fram/id" (= "@w1" (:fram/id resp))))
      (let [roots (capture)
            resp  (exec {:op :pull :root "@w1" :pattern [{"title" 0}]} roots)]
        (chk "wire: validation errors return the standard :error envelope"
             (and (contains? resp :error) (seq (:error resp))))))))

(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nPULL API —" (count cs) "/" (count cs) "PASS")
    (do (println "\nPULL API:" (count fails) "FAILED of" (count cs)) (System/exit 1))))
