;; pull.clj — the PULL API: nested-shape projection over the reified store.
;; ============================================================================
;; Coordinator-layer, PLAIN Clojure (NOT Beagle). Loaded by coord_daemon.clj via
;; (load-file "pull.clj") right AFTER coord.clj, so coord.clj's reader vars live in
;; `user` and resolve here as `user/…` (SCI resolves qualified symbols at analysis
;; time — coord MUST be loaded first, which the daemon and the tests both guarantee).
;;
;; Two entry points, both pure over the captured store snapshot (no locks, no writes):
;;   (validate root pattern opts) -> [] | [err-string …]   ; total, never throws
;;   (run store-atom root pattern opts) -> node-map | [node-maps…] | {:error [..]}
;;
;; The store-atom `run` receives is an IMMUTABLE snapshot (the daemon hands it
;; materialize-query-snapshot's :history-store — an atom over one Store value), so
;; every read here is a pure fn of that value. `co0 {:store store-atom}` is the shape
;; coord.clj's readers expect, so live-as-of / live-members / withdrawal-of / agent-of
;; are reused VERBATIM (identical as-of + withdrawal semantics to the :as-of query op).
;;   bb -cp out tests/pull_test.clj
;; ============================================================================
(ns pull
  (:require [fram.store :as c] [fram.schema :as s] [clojure.string :as str]))

(def ^:private default-max-depth 5)
(def ^:private default-max-nodes 1000)

;; predicates NEVER surfaced by :* wildcard (identity/schema/withdrawal bookkeeping,
;; mirroring coord_daemon's read-hidden/schema/reserved sets). :fram/id already carries
;; the subject name, so "name" is redundant too.
(def ^:private reserved-preds
  #{"name" "store-supersedes" "cardinality" "value_kind"
    "withdrawn_by" "withdrawn_at" "withdrawn_reason"})
(defn- reserved-pred? [p] (contains? reserved-preds p))

;; caps may only LOWER the daemon default (mirror coord_daemon's query-limit clamping).
(defn- clamp [v default]
  (if (and (integer? v) (pos? v)) (min v default) default))

;; --- validation: total, never throws ---------------------------------------
(declare valid-elem? valid-subpat?)
(defn- valid-subpat? [sp]
  (or (and (vector? sp) (every? valid-elem? sp))
      (and (integer? sp) (pos? sp))                 ; {"pred" N} bounded recursion
      (= sp :...)))                                 ; {"pred" :...} recurse to max-depth
(defn- valid-elem? [e]
  (cond
    (= e :*)     true                               ; wildcard
    (string? e)  (not (str/blank? e))               ; "pred" | "_pred"
    (map? e)     (and (seq e)
                      (every? (fn [[k v]]
                                (and (string? k) (not (str/blank? k)) (valid-subpat? v)))
                              e))
    :else        false))

(defn validate [root pattern opts]
  (let [errs (volatile! [])
        err! (fn [m] (vswap! errs conj m))]
    (when-not (or (and (string? root) (not (str/blank? root)))
                  (and (vector? root) (seq root) (every? #(and (string? %) (not (str/blank? %))) root)))
      (err! "root must be a subject-name string or a non-empty vector of name strings"))
    (if-not (vector? pattern)
      (err! "pattern must be a vector")
      (doseq [e pattern]
        (when-not (valid-elem? e)
          (err! (str "malformed pattern element: " (pr-str e))))))
    (doseq [k [:max-depth :max-nodes]]
      (when (contains? opts k)
        (let [v (get opts k)]
          (when-not (and (integer? v) (pos? v))
            (err! (str k " must be a positive integer"))))))
    (when (contains? opts :as-of)
      (let [v (:as-of opts)]
        (when-not (and (integer? v) (>= v 0))
          (err! ":as-of must be a non-negative integer"))))
    @errs))

;; --- the projection ---------------------------------------------------------
;; Unknown root: pull is a projection, and an absent subject simply has no facts, so
;; an unresolved root renders as a bare {:fram/id <root>} (no predicate keys) — a
;; sensible empty node, NOT an error. (Chosen over :error so a vector root mixing
;; known + unknown names still returns one node per requested root.)
(defn run [store-atom root pattern opts]
  (let [errs (validate root pattern opts)]
    (if (seq errs)
      {:error errs}
      (let [st        store-atom
            co0       {:store store-atom}
            asof      (:as-of opts)
            asof-set  (when asof (user/live-as-of co0 asof))   ; historical live cid set
            prov?     (boolean (:provenance opts))
            max-depth (clamp (:max-depth opts) default-max-depth)
            max-nodes (clamp (:max-nodes opts) default-max-nodes)
            state     (atom 0)]                                ; global subjects-materialized budget
        (letfn [(pid-of [p] (c/value-id st p))
                (nm-of  [id] (or (s/name-of st id) id))
                ;; restrict a raw cid list to the live view — as-of set membership when
                ;; historical, else the store's own live filter.
                (flive  [cids] (if asof-set (filterv asof-set cids) (filterv #(c/live? st %) cids)))
                (fwd-cids [lid pid]
                  (cond
                    asof-set (flive (get (:idx-by-lp @st) [lid pid] []))
                    ;; provenance surfaces WITHDRAWN members too (add-wins resurrects a
                    ;; cancelled value while a genuine overwrite still hides) — that is
                    ;; what makes :withdrawn/:withdrawn_by meaningful in the current view.
                    prov?    (vec (user/live-members co0 lid pid :add-wins))
                    :else    (flive (get (:idx-by-lp @st) [lid pid] []))))
                (rev-cids [pid rid] (flive (get (:idx-by-pr @st) [pid rid] [])))
                ;; a leaf value: literal or (for a ref) the target's name string. In
                ;; provenance mode each value carries its cid/agent/seq/withdrawn stamp.
                ;; as-of: a rendered member is live AS OF S, so its withdrawal state at S
                ;; is false (a value withdrawn after S is still live at S).
                (leaf [cid]
                  (let [cl (c/fact-of st cid) r (:r cl)
                        v  (if (c/value-object? st r) (c/literal st r) (nm-of r))]
                    (if-not prov?
                      v
                      (let [tx   (c/fact-tx st cid)
                            wd   (when-not asof (user/withdrawn? co0 cid))
                            base {:val v :cid cid
                                  :by (user/agent-of co0 cid)
                                  :seq (c/tx-seq st tx)
                                  :withdrawn (boolean wd)}]
                        (if wd
                          (let [w (user/withdrawal-of co0 cid)]
                            (assoc base :withdrawn_by (:by w)
                                        :withdrawn_at (:at w)
                                        :withdrawn_reason (:reason w)))
                          base)))))
                ;; cardinality FACT drives scalar (single) vs vector (multi) rendering.
                (values [pname pid lid]
                  (let [cids (fwd-cids lid pid)]
                    (when (seq cids)
                      (let [vs (mapv leaf cids)]
                        (if (= "single" (s/cardinality st pname)) (first vs) vs)))))
                ;; normalize a subpattern token into a pattern vector for the next level.
                (subpat->pattern [k sp]
                  (cond (vector? sp)  sp
                        (integer? sp) (if (> sp 1) [{k (dec sp)}] [])   ; N levels, same leaf shape
                        (= sp :...)   [{k :...}]                        ; bounded by max-depth
                        :else         []))
                (recur-target [tid subpat depth visited]
                  (if (> (inc depth) max-depth)
                    {:fram/id (nm-of tid) :fram/truncated true}         ; depth cap
                    (node tid (nm-of tid) subpat (inc depth) visited)))
                (elem [acc lid depth visited e]
                  (cond
                    (= e :*)                                           ; wildcard: every live pred
                    (reduce (fn [a pid]
                              (let [pname (c/literal st pid)]
                                (if (reserved-pred? pname)
                                  a
                                  (if-let [v (values pname pid lid)] (assoc a pname v) a))))
                            acc
                            (distinct (map #(:p (c/fact-of st %))
                                           (flive (get (:idx-by-l @st) lid [])))))

                    (and (string? e) (str/starts-with? e "_"))         ; reverse ref, bare
                    (let [pid (pid-of (subs e 1))]
                      (if (nil? pid)
                        acc
                        (let [ls (mapv #(:l (c/fact-of st %)) (rev-cids pid lid))]
                          (assoc acc e (mapv (fn [l] (node l (nm-of l) [] (inc depth) visited)) ls)))))

                    (string? e)                                        ; forward value(s)
                    (let [pid (pid-of e)]
                      (if (nil? pid) acc (if-let [v (values e pid lid)] (assoc acc e v) acc)))

                    (map? e)                                           ; ref/reverse recursion
                    (reduce
                     (fn [a [k sp]]
                       (if (str/starts-with? k "_")
                         (let [pid (pid-of (subs k 1))]               ; reverse recursion
                           (if (nil? pid)
                             a
                             (let [ls (mapv #(:l (c/fact-of st %)) (rev-cids pid lid))]
                               (assoc a k (mapv (fn [l] (recur-target l (subpat->pattern k sp) depth visited)) ls)))))
                         (let [pid (pid-of k)]                         ; forward recursion
                           (if (nil? pid)
                             a
                             (let [cids (fwd-cids lid pid)
                                   rendered (mapv (fn [cid]
                                                    (let [r (:r (c/fact-of st cid))]
                                                      (if (c/value-object? st r)
                                                        (c/literal st r)   ; literal under a subpattern: just the literal
                                                        (recur-target r (subpat->pattern k sp) depth visited))))
                                                  cids)]
                               (if (seq rendered)
                                 (assoc a k (if (= "single" (s/cardinality st k)) (first rendered) rendered))
                                 a))))))
                     acc e)

                    :else acc))
                (node [rid nm pat depth visited]
                  (cond
                    (contains? visited rid) {:fram/id nm :fram/cycle true}       ; cycle on current path
                    (>= @state max-nodes)   {:fram/id nm :fram/truncated true}   ; node budget exhausted
                    :else
                    (do (swap! state inc)
                        (reduce (fn [acc e] (elem acc rid depth (conj visited rid) e))
                                {:fram/id nm} pat))))]
          (let [one (fn [r]
                      (let [rid (s/resolve-name st r)]
                        (if (nil? rid) {:fram/id r} (node rid r pattern 0 #{}))))]
            (if (vector? root) (mapv one root) (one root))))))))
