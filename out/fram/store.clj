(ns fram.store
  (:require [fram.types :as t]))

(def empty-ids [])

(def empty-values [])

(def empty-facts [])

(def empty-tx-of [])

(def empty-txs [])

(def empty-id-buckets [])

(def empty-pair-buckets [])

(def initial-slots 64)

(def slot-load 4)

(defn- value-hash [v]
  (if (string? v) (hash v) (if (integer? v) (hash v) (if (boolean? v) (hash v) (if (keyword? v) (hash v) (if (vector? v) (hash v) 0))))))

(defn- slot-of [v width]
  (mod (value-hash v) width))

(defn- fresh-slots [width]
  (loop [acc empty-id-buckets
   i 0]
  (if (>= i width) acc (recur (conj acc (t/->IdBucket i empty-ids)) (inc i)))))

(defn- slot-put [slots v pos]
  (let [i (slot-of v (count slots))
   bucket (nth slots i)]
  (assoc slots i (t/->IdBucket i (conj (t/idbucket-ids bucket) pos)))))

(defn- build-slots [values width]
  (loop [slots (fresh-slots width)
   i 0]
  (if (>= i (count values)) slots (recur (slot-put slots (t/storedvalue-value (nth values i)) i) (inc i)))))

(defn- slots-width-for [n]
  (loop [width initial-slots]
  (if (>= (* slot-load width) n) width (recur (* 2 width)))))

(defn new-store []
  (atom (t/->Store 0 0 nil empty-ids empty-values empty-facts empty-tx-of empty-txs empty-ids empty-id-buckets empty-id-buckets empty-id-buckets empty-pair-buckets empty-pair-buckets (fresh-slots initial-slots))))

(defn- ^Boolean includes-id? [ids id]
  (loop [i 0]
  (if (>= i (count ids)) false (if (= id (nth ids i)) true (recur (inc i))))))

(defn- add-id [ids id]
  (if (includes-id? ids id) ids (conj ids id)))

(defn fresh-id! [ctx]
  (:next-id (swap! ctx update :next-id inc)))

(defn entity! [ctx]
  (let [id (fresh-id! ctx)]
  (swap! ctx update :objects (fn [ids] (conj ids id)))
  id))

(defn- ^Boolean value=? [a b]
  (if (string? a) (and (string? b) (= a b)) (if (integer? a) (and (integer? b) (= a b)) (if (boolean? a) (and (boolean? b) (= a b)) (if (keyword? a) (and (keyword? b) (= a b)) (and (vector? b) (= a b)))))))

(defn- find-value [s v]
  (let [values (:values s)
   slots (:value-slots s)
   ids (t/idbucket-ids (nth slots (slot-of v (count slots))))]
  (loop [i 0]
  (if (>= i (count ids)) nil (let [entry (nth values (nth ids i))]
  (if (value=? (t/storedvalue-value entry) v) entry (recur (inc i))))))))

(defn- find-value-by-id [values id]
  (loop [i 0]
  (if (>= i (count values)) nil (let [entry (nth values i)]
  (if (= id (t/storedvalue-id entry)) entry (recur (inc i)))))))

(defn- find-fact [facts cid]
  (loop [i 0]
  (if (>= i (count facts)) nil (let [entry (nth facts i)]
  (if (= cid (t/storedfact-id entry)) entry (recur (inc i)))))))

(defn- find-tx-of [entries cid]
  (loop [i 0]
  (if (>= i (count entries)) nil (let [entry (nth entries i)]
  (if (= cid (t/storedtxof-cid entry)) entry (recur (inc i)))))))

(defn- find-tx [entries id]
  (loop [i 0]
  (if (>= i (count entries)) nil (let [entry (nth entries i)]
  (if (= id (t/storedtx-id entry)) entry (recur (inc i)))))))

(defn- index-value! [ctx v pos]
  (let [s (swap! ctx update :value-slots (fn [slots] (slot-put slots v pos)))]
  (if (> (count (:values s)) (* slot-load (count (:value-slots s)))) (swap! ctx assoc :value-slots (build-slots (:values s) (* 2 (count (:value-slots s))))) s)))

(defn value! [ctx v]
  (let [s (deref ctx)
   known (find-value s v)]
  (if (some? known) (t/storedvalue-id known) (let [id (fresh-id! ctx)
   pos (count (:values s))]
  (swap! ctx update :objects (fn [ids] (conj ids id)))
  (swap! ctx update :values (fn [entries] (conj entries (t/->StoredValue id v))))
  (index-value! ctx v pos)
  id))))

(defn ^Boolean value-object? [ctx id]
  (some? (find-value-by-id (:values (let [s (deref ctx)]
  s)) id)))

(defn literal [ctx id]
  (let [entry (find-value-by-id (:values (let [s (deref ctx)]
  s)) id)]
  (if (some? entry) (t/storedvalue-value entry) nil)))

(defn value-id [ctx v]
  (let [entry (find-value (let [s (deref ctx)]
  s) v)]
  (if (some? entry) (t/storedvalue-id entry) nil)))

(defn begin-tx! [ctx agent]
  (let [tx (fresh-id! ctx)
   updated (swap! ctx update :next-seq inc)
   seq (:next-seq updated)]
  (swap! ctx update :txs (fn [entries] (conj entries (t/->StoredTx tx seq agent nil nil))))
  tx))

(defn tx-seq [ctx tx]
  (let [entry (find-tx (:txs (let [s (deref ctx)]
  s)) tx)]
  (if (some? entry) (t/storedtx-seq entry) 0)))

(defn tx-agent [ctx tx]
  (let [entry (find-tx (:txs (let [s (deref ctx)]
  s)) tx)]
  (if (some? entry) (t/storedtx-agent entry) nil)))

(defn tx-observed [ctx tx]
  (let [entry (find-tx (:txs (let [s (deref ctx)]
  s)) tx)]
  (if (some? entry) (t/storedtx-observed entry) nil)))

(defn tx-ts [ctx tx]
  (let [entry (find-tx (:txs (let [s (deref ctx)]
  s)) tx)]
  (if (some? entry) (t/storedtx-ts entry) nil)))

(defn current-seq [ctx]
  (:next-seq (let [s (deref ctx)]
  s)))

(defn supersedes-pred [ctx]
  (:supersedes-pred (let [s (deref ctx)]
  s)))

(defn- replace-tx [entries id observed ts]
  (mapv (fn [entry] (if (= id (t/storedtx-id entry)) (t/->StoredTx id (t/storedtx-seq entry) (t/storedtx-agent entry) observed ts) entry)) entries))

(defn set-tx-observed! [ctx tx observed]
  (let [old (find-tx (:txs (let [s (deref ctx)]
  s)) tx)]
  (if (some? old) (swap! ctx update :txs replace-tx tx observed (t/storedtx-ts old)) (let [s (deref ctx)]
  s))))

(defn set-tx-ts! [ctx tx ^String ts]
  (let [old (find-tx (:txs (let [s (deref ctx)]
  s)) tx)]
  (if (some? old) (swap! ctx update :txs replace-tx tx (t/storedtx-observed old) ts) (let [s (deref ctx)]
  s))))

(defn set-supersedes-pred! [ctx pid]
  (swap! ctx assoc :supersedes-pred pid))

(defn- bucket-ids [buckets key]
  (loop [i 0]
  (if (>= i (count buckets)) empty-ids (let [bucket (nth buckets i)]
  (if (= key (t/idbucket-key bucket)) (t/idbucket-ids bucket) (recur (inc i)))))))

(defn- put-bucket [buckets key cid]
  (let [found (loop [i 0]
  (if (>= i (count buckets)) false (if (= key (t/idbucket-key (nth buckets i))) true (recur (inc i)))))]
  (if found (mapv (fn [bucket] (if (= key (t/idbucket-key bucket)) (t/->IdBucket key (conj (t/idbucket-ids bucket) cid)) bucket)) buckets) (conj buckets (t/->IdBucket key (conj empty-ids cid))))))

(defn- pair-bucket-ids [buckets left right]
  (loop [i 0]
  (if (>= i (count buckets)) empty-ids (let [bucket (nth buckets i)]
  (if (and (= left (t/pairbucket-left bucket)) (= right (t/pairbucket-right bucket))) (t/pairbucket-ids bucket) (recur (inc i)))))))

(defn- put-pair-bucket [buckets left right cid]
  (let [found (loop [i 0]
  (if (>= i (count buckets)) false (let [bucket (nth buckets i)]
  (if (and (= left (t/pairbucket-left bucket)) (= right (t/pairbucket-right bucket))) true (recur (inc i))))))]
  (if found (mapv (fn [bucket] (if (and (= left (t/pairbucket-left bucket)) (= right (t/pairbucket-right bucket))) (t/->PairBucket left right (conj (t/pairbucket-ids bucket) cid)) bucket)) buckets) (conj buckets (t/->PairBucket left right (conj empty-ids cid))))))

(defn- index-fact! [ctx cid l p r]
  (swap! ctx update :idx-by-l put-bucket l cid)
  (swap! ctx update :idx-by-p put-bucket p cid)
  (swap! ctx update :idx-by-r put-bucket r cid)
  (swap! ctx update :idx-by-lp put-pair-bucket l p cid)
  (swap! ctx update :idx-by-pr put-pair-bucket p r cid))

(defn fact! [ctx l p r tx]
  (let [cid (fresh-id! ctx)]
  (swap! ctx update :objects (fn [ids] (conj ids cid)))
  (swap! ctx update :facts (fn [facts] (conj facts (t/->StoredFact cid l p r))))
  (swap! ctx update :tx-of (fn [entries] (conj entries (t/->StoredTxOf cid tx))))
  (index-fact! ctx cid l p r)
  (if (= p (supersedes-pred ctx)) (do
  (swap! ctx update :superseded (fn [ids] (add-id ids r)))))
  cid))

(defn fact-of [ctx cid]
  (let [entry (find-fact (:facts (let [s (deref ctx)]
  s)) cid)]
  (if (some? entry) (t/->FactView (t/storedfact-l entry) (t/storedfact-p entry) (t/storedfact-r entry)) nil)))

(defn fact-tx [ctx cid]
  (let [entry (find-tx-of (:tx-of (let [s (deref ctx)]
  s)) cid)]
  (if (some? entry) (t/storedtxof-tx entry) nil)))

(defn fact-l [ctx cid]
  (let [f (fact-of ctx cid)]
  (if (some? f) (:l f) nil)))

(defn fact-p [ctx cid]
  (let [f (fact-of ctx cid)]
  (if (some? f) (:p f) nil)))

(defn fact-r [ctx cid]
  (let [f (fact-of ctx cid)]
  (if (some? f) (:r f) nil)))

(defn ^Boolean live? [ctx cid]
  (not (includes-id? (:superseded (let [s (deref ctx)]
  s)) cid)))

(defn- live-only [ctx ids]
  (filterv (fn [id] (live? ctx id)) ids))

(defn raw-by-l [ctx l]
  (bucket-ids (:idx-by-l (let [s (deref ctx)]
  s)) l))

(defn raw-by-p [ctx p]
  (bucket-ids (:idx-by-p (let [s (deref ctx)]
  s)) p))

(defn raw-by-r [ctx r]
  (bucket-ids (:idx-by-r (let [s (deref ctx)]
  s)) r))

(defn raw-by-lp [ctx l p]
  (pair-bucket-ids (:idx-by-lp (let [s (deref ctx)]
  s)) l p))

(defn raw-by-pr [ctx p r]
  (pair-bucket-ids (:idx-by-pr (let [s (deref ctx)]
  s)) p r))

(defn by-l [ctx l]
  (live-only ctx (raw-by-l ctx l)))

(defn by-p [ctx p]
  (live-only ctx (raw-by-p ctx p)))

(defn by-r [ctx r]
  (live-only ctx (raw-by-r ctx r)))

(defn by-lp [ctx l p]
  (live-only ctx (raw-by-lp ctx l p)))

(defn by-pr [ctx p r]
  (live-only ctx (raw-by-pr ctx p r)))

(defn all-facts [ctx]
  (mapv (fn [entry] (t/storedfact-id entry)) (:facts (let [s (deref ctx)]
  s))))

(defn current-facts [ctx]
  (live-only ctx (all-facts ctx)))

(defn object-ids [ctx]
  (:objects (let [s (deref ctx)]
  s)))

(defn value-entries [ctx]
  (:values (let [s (deref ctx)]
  s)))

(defn fact-entries [ctx]
  (:facts (let [s (deref ctx)]
  s)))

(defn tx-entries [ctx]
  (:txs (let [s (deref ctx)]
  s)))

(defn dump-store [ctx]
  (let [s (deref ctx)]
  (t/->StoreDump 1 (:next-id s) (:next-seq s) (:supersedes-pred s) (:objects s) (:values s) (:facts s) (:tx-of s) (:txs s) (:superseded s))))

(defn load-store! [ctx data]
  (let [values (t/storedump-values data)]
  (reset! ctx (t/->Store (t/storedump-next-id data) (t/storedump-next-seq data) (t/storedump-supersedes-pred data) (t/storedump-objects data) values (t/storedump-facts data) (t/storedump-tx-of data) (t/storedump-txs data) (t/storedump-superseded data) empty-id-buckets empty-id-buckets empty-id-buckets empty-pair-buckets empty-pair-buckets (build-slots values (slots-width-for (count values)))))
  (doseq [entry (t/storedump-facts data)]
  (index-fact! ctx (t/storedfact-id entry) (t/storedfact-l entry) (t/storedfact-p entry) (t/storedfact-r entry)))
  ctx))
