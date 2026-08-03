(ns resolve-read
  (:require [fram.types :as t]
            [fram.store :as c]
            [fram.schema :as schema]
            [fram.rotation :as rot]
            [fram.txn :as txn]
            [resolve-core :as rc]))

(def SEG-RE (re-pattern "seg\\d+"))

(defrecord Context [session builder shadow shadow-view])

(defn context-session [r] (:session r))

(defn context-builder [r] (:builder r))

(defn context-shadow [r] (:shadow r))

(defn context-shadow-view [r] (:shadow-view r))

(defrecord OrdPair [key event child])

(defn ordpair-key [r] (:key r))

(defn ordpair-event [r] (:event r))

(defn ordpair-child [r] (:child r))

(defrecord SegPair [idx event child])

(defn segpair-idx [r] (:idx r))

(defn segpair-event [r] (:event r))

(defn segpair-child [r] (:child r))

(defn ^Context context [store]
  (let [session (schema/session store)
   shadow (atom (deref store))]
  (->Context session (txn/open store) shadow (atom (rot/project shadow)))))

(defn store-of [^Context ctx]
  (schema/store-of (context-session ctx)))

(defn builder [^Context ctx]
  (context-builder ctx))

(defn current-view [^Context ctx]
  (deref (context-shadow-view ctx)))

(defn- ^Context stage-appended! [^Context ctx before]
  (let [operations (txn/operations (builder ctx))
   appended (subvec operations before (count operations))]
  (do
  (if (not (empty? appended)) (do
  (do
  (c/commit-transaction! (context-shadow ctx) appended)
  (reset! (context-shadow-view ctx) (rot/refresh (current-view ctx) (context-shadow ctx))))))
  ctx)))

(defn mint! [^Context ctx]
  (txn/mint! (builder ctx)))

(defn assert! [^Context ctx subject predicate value]
  (let [before (txn/operation-count (builder ctx))
   occurrence (txn/assert! (builder ctx) (t/triple subject predicate value))]
  (do
  (stage-appended! ctx before)
  occurrence)))

(defn update-single! [^Context ctx subject predicate value]
  (let [before (txn/operation-count (builder ctx))
   occurrence (txn/update-single! (builder ctx) (current-view ctx) subject predicate value)]
  (do
  (stage-appended! ctx before)
  occurrence)))

(defn retract-event! [^Context ctx event]
  (let [before (txn/operation-count (builder ctx))
   occurrence (txn/retract! (builder ctx) (rot/proposition-of event))]
  (do
  (stage-appended! ctx before)
  occurrence)))

(defn commit! [^Context ctx]
  (if (zero? (txn/operation-count (builder ctx))) nil (let [coordinate (txn/commit! (store-of ctx) (builder ctx))]
  (do
  (schema/refresh! (context-session ctx))
  (reset! (context-builder ctx) (deref (txn/open (store-of ctx))))
  (reset! (context-shadow ctx) (deref (store-of ctx)))
  (reset! (context-shadow-view ctx) (schema/view (context-session ctx)))
  coordinate))))

(defn events-by-subject [^Context ctx subject]
  (rot/by-slot0 (current-view ctx) subject))

(defn events-by-subject-predicate [^Context ctx subject predicate]
  (rot/by-slot01 (current-view ctx) subject predicate))

(defn events-by-predicate [^Context ctx predicate]
  (rot/by-slot1 (current-view ctx) predicate))

(defn event-subject [event]
  (t/triple-slot0 (rot/proposition-of event)))

(defn event-predicate [event]
  (t/triple-slot1 (rot/proposition-of event)))

(defn event-value [event]
  (t/triple-slot2 (rot/proposition-of event)))

(defn view-cids [^Context ctx v events]
  (let [selected (set (rot/values (rot/by-slot01 (current-view ctx) v "selects")))]
  (filterv (fn [event] (contains? selected (rot/occurrence-of event))) events)))

(defn- pool-of [^Context ctx view events]
  (let [in-view (if (nil? view) [] (view-cids ctx view events))]
  (if (empty? in-view) events in-view)))

(defn select-main-1 [ctx view events]
  (if (empty? events) nil (if (nil? ctx) (first events) (first (pool-of ctx view events)))))

(defn select-causal-1 [ctx view events]
  (select-main-1 ctx view events))

(defn- event-r [event]
  (if (nil? event) nil (event-value event)))

(defn pred-val [ctx view e pname]
  (if (or (nil? ctx) (nil? e)) nil (event-r (select-main-1 ctx view (events-by-subject-predicate ctx e pname)))))

(defn kind-of [ctx view e]
  (pred-val ctx view e "kind"))

(defn sym-val [ctx view e]
  (if (= "symbol" (kind-of ctx view e)) (pred-val ctx view e "v") nil))

(defn ordered-children [ctx e]
  (if (or (nil? ctx) (nil? e)) [] (let [pairs (reduce (fn [acc event] (let [predicate (event-predicate event)
   key (if (string? predicate) (rc/ord-parse predicate) nil)]
  (if (nil? key) acc (conj acc (->OrdPair key event (event-value event)))))) [] (events-by-subject ctx e))]
  (mapv (fn [pair] (ordpair-child pair)) (sort-by (fn [pair] (ordpair-key pair)) rc/ord-cmp pairs)))))

(defn ordered-segs [ctx e]
  (if (or (nil? ctx) (nil? e)) [] (let [pairs (reduce (fn [acc event] (let [predicate (event-predicate event)]
  (if (and (string? predicate) (some? (re-matches SEG-RE predicate))) (let [idx (parse-long (subs predicate 3))]
  (if (nil? idx) acc (conj acc (->SegPair idx event (event-value event))))) acc))) [] (events-by-subject ctx e))]
  (mapv (fn [pair] (segpair-child pair)) (sort-by (fn [pair] (segpair-idx pair)) pairs)))))

(defn head-sym [ctx view e]
  (if (= "list" (kind-of ctx view e)) (sym-val ctx view (first (ordered-children ctx e))) nil))

(defn unwrap-meta [ctx view e]
  (loop [current e
   n 0]
  (if (and (some? current) (< n 64) (= "#%meta" (head-sym ctx view current))) (recur (nth (ordered-children ctx current) 2 nil) (inc n)) current)))

(defn bound-target [ctx view BOUND leaf]
  (if (or (nil? ctx) (nil? BOUND) (nil? leaf)) nil (event-r (select-main-1 ctx view (events-by-subject-predicate ctx leaf BOUND)))))

(defn refers-target [ctx view BOUND REFERS leaf]
  (let [bt (bound-target ctx view BOUND leaf)]
  (if (some? bt) bt (if (or (nil? ctx) (nil? REFERS) (nil? leaf)) nil (event-r (select-main-1 ctx view (events-by-subject-predicate ctx leaf REFERS)))))))

(defn ^Boolean live-node? [ctx KIND e]
  (if (or (nil? ctx) (nil? KIND) (nil? e)) false (not (empty? (events-by-subject-predicate ctx e KIND)))))
