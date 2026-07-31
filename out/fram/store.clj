(ns fram.store
  (:require [fram.types :as t]))

^{:line 8 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (def empty-ids ^{:line 8 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} [])

^{:line 9 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (def empty-values ^{:line 9 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} [])

^{:line 10 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (def empty-facts ^{:line 10 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} [])

^{:line 11 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (def empty-tx-of ^{:line 11 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} [])

^{:line 12 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (def empty-txs ^{:line 12 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} [])

^{:line 13 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (def empty-id-buckets ^{:line 13 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} [])

^{:line 14 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (def empty-pair-buckets ^{:line 14 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} [])

^{:line 16 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn new-store []
  ^{:line 17 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (atom ^{:line 17 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->Store 0 0 nil empty-ids empty-values empty-facts empty-tx-of empty-txs empty-ids empty-id-buckets empty-id-buckets empty-id-buckets empty-pair-buckets empty-pair-buckets)))

^{:line 20 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- ^Boolean includes-id? [ids id]
  ^{:line 21 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 22 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 22 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 22 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count ids)) false ^{:line 23 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 23 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= id ^{:line 23 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth ids i)) true ^{:line 23 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 23 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i))))))

^{:line 26 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- add-id [ids id]
  ^{:line 27 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 27 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (includes-id? ids id) ids ^{:line 27 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj ids id)))

^{:line 32 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fresh-id! [ctx]
  ^{:line 33 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:next-id ^{:line 33 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :next-id inc)))

^{:line 35 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn entity! [ctx]
  ^{:line 36 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [id ^{:line 36 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fresh-id! ctx)]
  ^{:line 37 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :objects ^{:line 37 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [ids] ^{:line 37 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj ids id)))
  id))

^{:line 40 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- ^Boolean value=? [a b]
  ^{:line 41 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 41 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (string? a) ^{:line 41 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 41 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (string? b) ^{:line 41 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= a b)) ^{:line 42 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 42 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (integer? a) ^{:line 42 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 42 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (integer? b) ^{:line 42 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= a b)) ^{:line 43 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 43 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (boolean? a) ^{:line 43 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 43 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (boolean? b) ^{:line 43 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= a b)) ^{:line 44 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 44 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (keyword? a) ^{:line 44 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 44 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (keyword? b) ^{:line 44 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= a b)) ^{:line 45 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 45 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (vector? b) ^{:line 45 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= a b)))))))

^{:line 47 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- find-value [values v]
  ^{:line 48 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 49 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 49 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 49 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count values)) nil ^{:line 50 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 50 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth values i)]
  ^{:line 51 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 51 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (value=? ^{:line 51 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedvalue-value entry) v) entry ^{:line 51 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 51 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))))

^{:line 53 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- find-value-by-id [values id]
  ^{:line 54 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 55 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 55 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 55 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count values)) nil ^{:line 56 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 56 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth values i)]
  ^{:line 57 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 57 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= id ^{:line 57 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedvalue-id entry)) entry ^{:line 57 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 57 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))))

^{:line 59 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- find-fact [facts cid]
  ^{:line 60 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 61 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 61 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 61 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count facts)) nil ^{:line 62 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 62 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth facts i)]
  ^{:line 63 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 63 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= cid ^{:line 63 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-id entry)) entry ^{:line 63 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 63 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))))

^{:line 65 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- find-tx-of [entries cid]
  ^{:line 66 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 67 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 67 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 67 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count entries)) nil ^{:line 68 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 68 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth entries i)]
  ^{:line 69 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 69 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= cid ^{:line 69 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtxof-cid entry)) entry ^{:line 69 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 69 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))))

^{:line 71 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- find-tx [entries id]
  ^{:line 72 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 73 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 73 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 73 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count entries)) nil ^{:line 74 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 74 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth entries i)]
  ^{:line 75 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 75 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= id ^{:line 75 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-id entry)) entry ^{:line 75 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 75 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))))

^{:line 77 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn value! [ctx v]
  ^{:line 78 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 78 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)
   known ^{:line 78 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-value ^{:line 78 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:values s) v)]
  ^{:line 79 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 79 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? known) ^{:line 80 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedvalue-id known) ^{:line 81 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [id ^{:line 81 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fresh-id! ctx)]
  ^{:line 82 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :objects ^{:line 82 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [ids] ^{:line 82 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj ids id)))
  ^{:line 83 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :values ^{:line 83 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [entries] ^{:line 84 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj entries ^{:line 84 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->StoredValue id v))))
  id))))

^{:line 87 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn ^Boolean value-object? [ctx id]
  ^{:line 88 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? ^{:line 88 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-value-by-id ^{:line 88 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:values ^{:line 88 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 88 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) id)))

^{:line 90 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn literal [ctx id]
  ^{:line 91 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 91 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-value-by-id ^{:line 91 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:values ^{:line 91 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 91 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) id)]
  ^{:line 92 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 92 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 92 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedvalue-value entry) nil)))

^{:line 94 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn value-id [ctx v]
  ^{:line 95 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 95 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-value ^{:line 95 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:values ^{:line 95 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 95 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) v)]
  ^{:line 96 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 96 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 96 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedvalue-id entry) nil)))

^{:line 98 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn begin-tx! [ctx agent]
  ^{:line 99 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [tx ^{:line 99 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fresh-id! ctx)
   updated ^{:line 99 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :next-seq inc)
   seq ^{:line 100 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:next-seq updated)]
  ^{:line 101 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :txs ^{:line 101 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [entries] ^{:line 102 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj entries ^{:line 102 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->StoredTx tx seq agent nil nil))))
  tx))

^{:line 105 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn tx-seq [ctx tx]
  ^{:line 106 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 106 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-tx ^{:line 106 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs ^{:line 106 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 106 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) tx)]
  ^{:line 107 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 107 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 107 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-seq entry) 0)))

^{:line 108 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn tx-agent [ctx tx]
  ^{:line 109 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 109 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-tx ^{:line 109 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs ^{:line 109 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 109 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) tx)]
  ^{:line 110 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 110 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 110 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-agent entry) nil)))

^{:line 111 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn tx-observed [ctx tx]
  ^{:line 112 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 112 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-tx ^{:line 112 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs ^{:line 112 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 112 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) tx)]
  ^{:line 113 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 113 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 113 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-observed entry) nil)))

^{:line 114 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn tx-ts [ctx tx]
  ^{:line 115 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 115 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-tx ^{:line 115 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs ^{:line 115 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 115 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) tx)]
  ^{:line 116 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 116 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 116 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-ts entry) nil)))

^{:line 117 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn current-seq [ctx]
  ^{:line 117 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:next-seq ^{:line 117 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 117 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)))

^{:line 118 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn supersedes-pred [ctx]
  ^{:line 118 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:supersedes-pred ^{:line 118 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 118 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)))

^{:line 120 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- replace-tx [entries id observed ts]
  ^{:line 121 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (mapv ^{:line 121 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [entry] ^{:line 122 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 122 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= id ^{:line 122 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-id entry)) ^{:line 123 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->StoredTx id ^{:line 123 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-seq entry) ^{:line 123 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-agent entry) observed ts) entry)) entries))

^{:line 125 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn set-tx-observed! [ctx tx observed]
  ^{:line 126 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [old ^{:line 126 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-tx ^{:line 126 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs ^{:line 126 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 126 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) tx)]
  ^{:line 127 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 127 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? old) ^{:line 128 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :txs replace-tx tx observed ^{:line 128 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-ts old)) ^{:line 129 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 129 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s))))

^{:line 130 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn set-tx-ts! [ctx tx ^String ts]
  ^{:line 131 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [old ^{:line 131 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-tx ^{:line 131 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs ^{:line 131 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 131 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) tx)]
  ^{:line 132 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 132 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? old) ^{:line 133 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :txs replace-tx tx ^{:line 133 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtx-observed old) ts) ^{:line 134 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 134 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s))))

^{:line 135 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn set-supersedes-pred! [ctx pid]
  ^{:line 135 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx assoc :supersedes-pred pid))

^{:line 137 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- bucket-ids [buckets key]
  ^{:line 138 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 139 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 139 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 139 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count buckets)) empty-ids ^{:line 140 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [bucket ^{:line 140 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth buckets i)]
  ^{:line 141 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 141 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= key ^{:line 141 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/idbucket-key bucket)) ^{:line 141 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/idbucket-ids bucket) ^{:line 141 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 141 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))))

^{:line 142 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- put-bucket [buckets key cid]
  ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [found ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count buckets)) false ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= key ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/idbucket-key ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth buckets i))) true ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 143 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))]
  ^{:line 144 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if found ^{:line 145 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (mapv ^{:line 145 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [bucket] ^{:line 146 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 146 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= key ^{:line 146 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/idbucket-key bucket)) ^{:line 146 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->IdBucket key ^{:line 146 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj ^{:line 146 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/idbucket-ids bucket) cid)) bucket)) buckets) ^{:line 147 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj buckets ^{:line 147 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->IdBucket key ^{:line 147 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj empty-ids cid))))))

^{:line 148 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- pair-bucket-ids [buckets left right]
  ^{:line 149 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 150 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 150 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 150 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count buckets)) empty-ids ^{:line 151 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [bucket ^{:line 151 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth buckets i)]
  ^{:line 152 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 152 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 152 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= left ^{:line 152 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-left bucket)) ^{:line 152 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= right ^{:line 152 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-right bucket))) ^{:line 153 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-ids bucket) ^{:line 153 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 153 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i)))))))

^{:line 154 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- put-pair-bucket [buckets left right cid]
  ^{:line 155 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [found ^{:line 155 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (loop [i 0]
  ^{:line 155 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 155 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (>= i ^{:line 155 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (count buckets)) false ^{:line 156 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [bucket ^{:line 156 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (nth buckets i)]
  ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= left ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-left bucket)) ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= right ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-right bucket))) true ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (recur ^{:line 157 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (inc i))))))]
  ^{:line 158 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if found ^{:line 159 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (mapv ^{:line 159 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [bucket] ^{:line 160 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 160 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (and ^{:line 160 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= left ^{:line 160 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-left bucket)) ^{:line 160 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= right ^{:line 160 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-right bucket))) ^{:line 161 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->PairBucket left right ^{:line 161 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj ^{:line 161 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/pairbucket-ids bucket) cid)) bucket)) buckets) ^{:line 162 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj buckets ^{:line 162 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->PairBucket left right ^{:line 162 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj empty-ids cid))))))

^{:line 164 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- index-fact! [ctx cid l p r]
  ^{:line 165 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :idx-by-l put-bucket l cid)
  ^{:line 166 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :idx-by-p put-bucket p cid)
  ^{:line 167 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :idx-by-r put-bucket r cid)
  ^{:line 168 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :idx-by-lp put-pair-bucket l p cid)
  ^{:line 169 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :idx-by-pr put-pair-bucket p r cid))

^{:line 171 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fact! [ctx l p r tx]
  ^{:line 172 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [cid ^{:line 172 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fresh-id! ctx)]
  ^{:line 173 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :objects ^{:line 173 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [ids] ^{:line 173 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj ids cid)))
  ^{:line 174 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :facts ^{:line 174 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [facts] ^{:line 174 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj facts ^{:line 174 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->StoredFact cid l p r))))
  ^{:line 175 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :tx-of ^{:line 175 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [entries] ^{:line 175 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (conj entries ^{:line 175 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->StoredTxOf cid tx))))
  ^{:line 176 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (index-fact! ctx cid l p r)
  ^{:line 177 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 177 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (= p ^{:line 177 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (supersedes-pred ctx)) ^{:line 177 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (do
  ^{:line 178 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (swap! ctx update :superseded ^{:line 178 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [ids] ^{:line 178 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (add-id ids r)))))
  cid))

^{:line 183 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fact-of [ctx cid]
  ^{:line 184 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 184 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-fact ^{:line 184 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:facts ^{:line 184 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 184 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) cid)]
  ^{:line 185 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 185 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 185 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->FactView ^{:line 185 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-l entry) ^{:line 185 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-p entry) ^{:line 185 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-r entry)) nil)))

^{:line 186 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fact-tx [ctx cid]
  ^{:line 187 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [entry ^{:line 187 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (find-tx-of ^{:line 187 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:tx-of ^{:line 187 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 187 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) cid)]
  ^{:line 188 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 188 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? entry) ^{:line 188 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedtxof-tx entry) nil)))

^{:line 189 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fact-l [ctx cid]
  ^{:line 189 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [f ^{:line 189 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fact-of ctx cid)]
  ^{:line 189 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 189 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? f) ^{:line 189 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:l f) nil)))

^{:line 190 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fact-p [ctx cid]
  ^{:line 190 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [f ^{:line 190 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fact-of ctx cid)]
  ^{:line 190 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 190 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? f) ^{:line 190 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:p f) nil)))

^{:line 191 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fact-r [ctx cid]
  ^{:line 191 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [f ^{:line 191 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fact-of ctx cid)]
  ^{:line 191 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (if ^{:line 191 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (some? f) ^{:line 191 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:r f) nil)))

^{:line 192 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn ^Boolean live? [ctx cid]
  ^{:line 193 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (not ^{:line 193 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (includes-id? ^{:line 193 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:superseded ^{:line 193 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 193 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) cid)))

^{:line 194 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn- live-only [ctx ids]
  ^{:line 195 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (filterv ^{:line 195 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [id] ^{:line 195 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (live? ctx id)) ids))

^{:line 196 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn raw-by-l [ctx l]
  ^{:line 196 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (bucket-ids ^{:line 196 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:idx-by-l ^{:line 196 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 196 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) l))

^{:line 197 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn raw-by-p [ctx p]
  ^{:line 197 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (bucket-ids ^{:line 197 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:idx-by-p ^{:line 197 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 197 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) p))

^{:line 198 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn raw-by-r [ctx r]
  ^{:line 198 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (bucket-ids ^{:line 198 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:idx-by-r ^{:line 198 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 198 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) r))

^{:line 199 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn raw-by-lp [ctx l p]
  ^{:line 199 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (pair-bucket-ids ^{:line 199 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:idx-by-lp ^{:line 199 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 199 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) l p))

^{:line 200 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn raw-by-pr [ctx p r]
  ^{:line 200 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (pair-bucket-ids ^{:line 200 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:idx-by-pr ^{:line 200 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 200 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)) p r))

^{:line 201 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn by-l [ctx l]
  ^{:line 201 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (live-only ctx ^{:line 201 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (raw-by-l ctx l)))

^{:line 202 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn by-p [ctx p]
  ^{:line 202 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (live-only ctx ^{:line 202 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (raw-by-p ctx p)))

^{:line 203 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn by-r [ctx r]
  ^{:line 203 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (live-only ctx ^{:line 203 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (raw-by-r ctx r)))

^{:line 204 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn by-lp [ctx l p]
  ^{:line 204 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (live-only ctx ^{:line 204 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (raw-by-lp ctx l p)))

^{:line 205 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn by-pr [ctx p r]
  ^{:line 205 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (live-only ctx ^{:line 205 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (raw-by-pr ctx p r)))

^{:line 206 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn all-facts [ctx]
  ^{:line 207 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (mapv ^{:line 207 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (fn [entry] ^{:line 207 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-id entry)) ^{:line 208 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:facts ^{:line 208 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 208 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s))))

^{:line 209 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn current-facts [ctx]
  ^{:line 209 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (live-only ctx ^{:line 209 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (all-facts ctx)))

^{:line 210 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn object-ids [ctx]
  ^{:line 210 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:objects ^{:line 210 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 210 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)))

^{:line 211 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn value-entries [ctx]
  ^{:line 211 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:values ^{:line 211 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 211 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)))

^{:line 212 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn fact-entries [ctx]
  ^{:line 212 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:facts ^{:line 212 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 212 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)))

^{:line 213 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn tx-entries [ctx]
  ^{:line 213 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs ^{:line 213 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 213 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  s)))

^{:line 215 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn dump-store [ctx]
  ^{:line 216 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (let [s ^{:line 216 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (deref ctx)]
  ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->StoreDump 1 ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:next-id s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:next-seq s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:supersedes-pred s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:objects s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:values s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:facts s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:tx-of s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:txs s) ^{:line 217 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (:superseded s))))

^{:line 218 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (defn load-store! [ctx data]
  ^{:line 219 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (reset! ctx ^{:line 219 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/->Store ^{:line 219 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-next-id data) ^{:line 219 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-next-seq data) ^{:line 219 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-supersedes-pred data) ^{:line 220 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-objects data) ^{:line 220 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-values data) ^{:line 220 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-facts data) ^{:line 221 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-tx-of data) ^{:line 221 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-txs data) ^{:line 221 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-superseded data) empty-id-buckets empty-id-buckets empty-id-buckets empty-pair-buckets empty-pair-buckets))
  ^{:line 223 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (doseq [entry ^{:line 223 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedump-facts data)]
  ^{:line 224 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (index-fact! ctx ^{:line 224 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-id entry) ^{:line 224 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-l entry) ^{:line 224 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-p entry) ^{:line 224 :file "/home/tom/code/fram/wt-store-fold-perf/src/fram/store.bclj"} (t/storedfact-r entry)))
  ctx)
