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

(defn- slot-add [slots i pos]
  (assoc slots i (t/->IdBucket i (conj (t/idbucket-ids (nth slots i)) pos))))

(defn- slot-put [slots v pos]
  (slot-add slots (slot-of v (count slots)) pos))

(defn- key-slot [key width]
  (mod (hash key) width))

(defn- pair-slot [left right width]
  (mod (+ (* 31 (hash left)) (hash right)) width))

(defn- build-slots [values width]
  (loop [slots (fresh-slots width)
   i 0]
  (if (>= i (count values)) slots (recur (slot-put slots (t/storedvalue-value (nth values i)) i) (inc i)))))

(defn- build-fact-slots [facts width]
  (loop [slots (fresh-slots width)
   i 0]
  (if (>= i (count facts)) slots (recur (slot-add slots (key-slot (t/storedfact-id (nth facts i)) width) i) (inc i)))))

(defn- build-tx-slots [entries width]
  (loop [slots (fresh-slots width)
   i 0]
  (if (>= i (count entries)) slots (recur (slot-add slots (key-slot (t/storedtx-id (nth entries i)) width) i) (inc i)))))

(defn- build-key-slots [buckets width]
  (loop [slots (fresh-slots width)
   i 0]
  (if (>= i (count buckets)) slots (recur (slot-add slots (key-slot (t/idbucket-key (nth buckets i)) width) i) (inc i)))))

(defn- build-pair-slots [buckets width]
  (loop [slots (fresh-slots width)
   i 0]
  (if (>= i (count buckets)) slots (let [bucket (nth buckets i)]
  (recur (slot-add slots (pair-slot (t/pairbucket-left bucket) (t/pairbucket-right bucket) width) i) (inc i))))))

(defn- slots-width-for [n]
  (loop [width initial-slots]
  (if (>= (* slot-load width) n) width (recur (* 2 width)))))

(defn new-store []
  (atom (t/->Store 0 0 nil empty-ids empty-values empty-facts empty-tx-of empty-txs empty-ids empty-id-buckets empty-id-buckets empty-id-buckets empty-pair-buckets empty-pair-buckets (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots))))

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
  (loop [lo 0
   hi (dec (count values))]
  (if (> lo hi) nil (let [mid (quot (+ lo hi) 2)
   entry (nth values mid)
   k (t/storedvalue-id entry)]
  (if (= k id) entry (if (< k id) (recur (inc mid) hi) (recur lo (dec mid))))))))

(defn- find-fact [s cid]
  (let [facts (:facts s)
   slots (:fact-slots s)
   ids (t/idbucket-ids (nth slots (key-slot cid (count slots))))]
  (loop [i 0]
  (if (>= i (count ids)) nil (let [entry (nth facts (nth ids i))]
  (if (= cid (t/storedfact-id entry)) entry (recur (inc i))))))))

(defn- find-tx-of [entries cid]
  (loop [lo 0
   hi (dec (count entries))]
  (if (> lo hi) nil (let [mid (quot (+ lo hi) 2)
   entry (nth entries mid)
   k (t/storedtxof-cid entry)]
  (if (= k cid) entry (if (< k cid) (recur (inc mid) hi) (recur lo (dec mid))))))))

(defn- find-tx [s id]
  (let [entries (:txs s)
   slots (:tx-slots s)
   ids (t/idbucket-ids (nth slots (key-slot id (count slots))))]
  (loop [i 0]
  (if (>= i (count ids)) nil (let [entry (nth entries (nth ids i))]
  (if (= id (t/storedtx-id entry)) entry (recur (inc i))))))))

(defn- index-value! [ctx v pos]
  (let [s (swap! ctx update :value-slots (fn [slots] (slot-put slots v pos)))]
  (if (> (count (:values s)) (* slot-load (count (:value-slots s)))) (swap! ctx assoc :value-slots (build-slots (:values s) (* 2 (count (:value-slots s))))) s)))

(defn- index-fact-slot! [ctx cid pos]
  (let [s (swap! ctx update :fact-slots (fn [slots] (slot-add slots (key-slot cid (count slots)) pos)))]
  (if (> (count (:facts s)) (* slot-load (count (:fact-slots s)))) (swap! ctx assoc :fact-slots (build-fact-slots (:facts s) (* 2 (count (:fact-slots s))))) s)))

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
   seq (:next-seq updated)
   after (swap! ctx update :txs (fn [entries] (conj entries (t/->StoredTx tx seq agent nil nil))))
   pos (dec (count (:txs after)))
   s (swap! ctx update :tx-slots (fn [slots] (slot-add slots (key-slot tx (count slots)) pos)))]
  (if (> (count (:txs s)) (* slot-load (count (:tx-slots s)))) (do
  (swap! ctx assoc :tx-slots (build-tx-slots (:txs s) (* 2 (count (:tx-slots s)))))))
  tx))

(defn tx-seq [ctx tx]
  (let [entry (find-tx (let [s (deref ctx)]
  s) tx)]
  (if (some? entry) (t/storedtx-seq entry) 0)))

(defn tx-agent [ctx tx]
  (let [entry (find-tx (let [s (deref ctx)]
  s) tx)]
  (if (some? entry) (t/storedtx-agent entry) nil)))

(defn tx-observed [ctx tx]
  (let [entry (find-tx (let [s (deref ctx)]
  s) tx)]
  (if (some? entry) (t/storedtx-observed entry) nil)))

(defn tx-ts [ctx tx]
  (let [entry (find-tx (let [s (deref ctx)]
  s) tx)]
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
  (let [old (find-tx (let [s (deref ctx)]
  s) tx)]
  (if (some? old) (swap! ctx update :txs replace-tx tx observed (t/storedtx-ts old)) (let [s (deref ctx)]
  s))))

(defn set-tx-ts! [ctx tx ^String ts]
  (let [old (find-tx (let [s (deref ctx)]
  s) tx)]
  (if (some? old) (swap! ctx update :txs replace-tx tx (t/storedtx-observed old) ts) (let [s (deref ctx)]
  s))))

(defn set-supersedes-pred! [ctx pid]
  (swap! ctx assoc :supersedes-pred pid))

(defn- bucket-pos [buckets slots key]
  (let [ids (t/idbucket-ids (nth slots (key-slot key (count slots))))]
  (loop [i 0]
  (if (>= i (count ids)) -1 (let [pos (nth ids i)]
  (if (= key (t/idbucket-key (nth buckets pos))) pos (recur (inc i))))))))

(defn- pair-bucket-pos [buckets slots left right]
  (let [ids (t/idbucket-ids (nth slots (pair-slot left right (count slots))))]
  (loop [i 0]
  (if (>= i (count ids)) -1 (let [pos (nth ids i)
   bucket (nth buckets pos)]
  (if (and (= left (t/pairbucket-left bucket)) (= right (t/pairbucket-right bucket))) pos (recur (inc i))))))))

(defn- bucket-ids [buckets slots key]
  (let [pos (bucket-pos buckets slots key)]
  (if (< pos 0) empty-ids (t/idbucket-ids (nth buckets pos)))))

(defn- pair-bucket-ids [buckets slots left right]
  (let [pos (pair-bucket-pos buckets slots left right)]
  (if (< pos 0) empty-ids (t/pairbucket-ids (nth buckets pos)))))

(defn- put-bucket [buckets pos key cid]
  (if (< pos 0) (conj buckets (t/->IdBucket key (conj empty-ids cid))) (assoc buckets pos (t/->IdBucket key (conj (t/idbucket-ids (nth buckets pos)) cid)))))

(defn- put-pair-bucket [buckets pos left right cid]
  (if (< pos 0) (conj buckets (t/->PairBucket left right (conj empty-ids cid))) (assoc buckets pos (t/->PairBucket left right (conj (t/pairbucket-ids (nth buckets pos)) cid)))))

(defn- grow-key-slots [slots buckets key]
  (if (> (count buckets) (* slot-load (count slots))) (build-key-slots buckets (* 2 (count slots))) (slot-add slots (key-slot key (count slots)) (dec (count buckets)))))

(defn- grow-pair-slots [slots buckets left right]
  (if (> (count buckets) (* slot-load (count slots))) (build-pair-slots buckets (* 2 (count slots))) (slot-add slots (pair-slot left right (count slots)) (dec (count buckets)))))

(defn- index-by-l! [ctx key cid]
  (let [s (deref ctx)
   buckets (:idx-by-l s)
   slots (:l-slots s)
   pos (bucket-pos buckets slots key)
   put (put-bucket buckets pos key cid)]
  (swap! ctx assoc :idx-by-l put)
  (if (< pos 0) (swap! ctx assoc :l-slots (grow-key-slots slots put key)) (deref ctx))))

(defn- index-by-p! [ctx key cid]
  (let [s (deref ctx)
   buckets (:idx-by-p s)
   slots (:p-slots s)
   pos (bucket-pos buckets slots key)
   put (put-bucket buckets pos key cid)]
  (swap! ctx assoc :idx-by-p put)
  (if (< pos 0) (swap! ctx assoc :p-slots (grow-key-slots slots put key)) (deref ctx))))

(defn- index-by-r! [ctx key cid]
  (let [s (deref ctx)
   buckets (:idx-by-r s)
   slots (:r-slots s)
   pos (bucket-pos buckets slots key)
   put (put-bucket buckets pos key cid)]
  (swap! ctx assoc :idx-by-r put)
  (if (< pos 0) (swap! ctx assoc :r-slots (grow-key-slots slots put key)) (deref ctx))))

(defn- index-by-lp! [ctx left right cid]
  (let [s (deref ctx)
   buckets (:idx-by-lp s)
   slots (:lp-slots s)
   pos (pair-bucket-pos buckets slots left right)
   put (put-pair-bucket buckets pos left right cid)]
  (swap! ctx assoc :idx-by-lp put)
  (if (< pos 0) (swap! ctx assoc :lp-slots (grow-pair-slots slots put left right)) (deref ctx))))

(defn- index-by-pr! [ctx left right cid]
  (let [s (deref ctx)
   buckets (:idx-by-pr s)
   slots (:pr-slots s)
   pos (pair-bucket-pos buckets slots left right)
   put (put-pair-bucket buckets pos left right cid)]
  (swap! ctx assoc :idx-by-pr put)
  (if (< pos 0) (swap! ctx assoc :pr-slots (grow-pair-slots slots put left right)) (deref ctx))))

(defn- index-fact! [ctx cid l p r]
  (index-by-l! ctx l cid)
  (index-by-p! ctx p cid)
  (index-by-r! ctx r cid)
  (index-by-lp! ctx l p cid)
  (index-by-pr! ctx p r cid))

(defn fact! [ctx l p r tx]
  (let [cid (fresh-id! ctx)
   pos (count (:facts (let [s (deref ctx)]
  s)))]
  (swap! ctx update :objects (fn [ids] (conj ids cid)))
  (swap! ctx update :facts (fn [facts] (conj facts (t/->StoredFact cid l p r))))
  (swap! ctx update :tx-of (fn [entries] (conj entries (t/->StoredTxOf cid tx))))
  (index-fact-slot! ctx cid pos)
  (index-fact! ctx cid l p r)
  (if (= p (supersedes-pred ctx)) (do
  (swap! ctx update :superseded (fn [ids] (add-id ids r)))))
  cid))

(defn fact-of [ctx cid]
  (let [entry (find-fact (let [s (deref ctx)]
  s) cid)]
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
  (let [s (deref ctx)]
  (bucket-ids (:idx-by-l s) (:l-slots s) l)))

(defn raw-by-p [ctx p]
  (let [s (deref ctx)]
  (bucket-ids (:idx-by-p s) (:p-slots s) p)))

(defn raw-by-r [ctx r]
  (let [s (deref ctx)]
  (bucket-ids (:idx-by-r s) (:r-slots s) r)))

(defn raw-by-lp [ctx l p]
  (let [s (deref ctx)]
  (pair-bucket-ids (:idx-by-lp s) (:lp-slots s) l p)))

(defn raw-by-pr [ctx p r]
  (let [s (deref ctx)]
  (pair-bucket-ids (:idx-by-pr s) (:pr-slots s) p r)))

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
  (let [values (t/storedump-values data)
   facts (t/storedump-facts data)
   txs (t/storedump-txs data)]
  (reset! ctx (t/->Store (t/storedump-next-id data) (t/storedump-next-seq data) (t/storedump-supersedes-pred data) (t/storedump-objects data) values facts (t/storedump-tx-of data) txs (t/storedump-superseded data) empty-id-buckets empty-id-buckets empty-id-buckets empty-pair-buckets empty-pair-buckets (build-slots values (slots-width-for (count values))) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (fresh-slots initial-slots) (build-fact-slots facts (slots-width-for (count facts))) (build-tx-slots txs (slots-width-for (count txs)))))
  (doseq [entry facts]
  (index-fact! ctx (t/storedfact-id entry) (t/storedfact-l entry) (t/storedfact-p entry) (t/storedfact-r entry)))
  ctx))
