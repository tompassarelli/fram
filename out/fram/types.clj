(ns fram.types)

^{:line 10 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord StoredValue [id value])

(defn storedvalue-id [r] (:id r))

(defn storedvalue-value [r] (:value r))

^{:line 11 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord StoredFact [id l p r])

(defn storedfact-id [r] (:id r))

(defn storedfact-l [r] (:l r))

(defn storedfact-p [r] (:p r))

(defn storedfact-r [r] (:r r))

^{:line 12 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord FactView [l p r])

(defn factview-l [r] (:l r))

(defn factview-p [r] (:p r))

(defn factview-r [r] (:r r))

^{:line 13 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord StoredTxOf [cid tx])

(defn storedtxof-cid [r] (:cid r))

(defn storedtxof-tx [r] (:tx r))

^{:line 14 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord StoredTx [id seq agent observed ts])

(defn storedtx-id [r] (:id r))

(defn storedtx-seq [r] (:seq r))

(defn storedtx-agent [r] (:agent r))

(defn storedtx-observed [r] (:observed r))

(defn storedtx-ts [r] (:ts r))

^{:line 15 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord IdBucket [key ids])

(defn idbucket-key [r] (:key r))

(defn idbucket-ids [r] (:ids r))

^{:line 16 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord PairBucket [left right ids])

(defn pairbucket-left [r] (:left r))

(defn pairbucket-right [r] (:right r))

(defn pairbucket-ids [r] (:ids r))

^{:line 18 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord StoreDump [version next-id next-seq supersedes-pred objects values facts tx-of txs superseded])

(defn storedump-version [r] (:version r))

(defn storedump-next-id [r] (:next-id r))

(defn storedump-next-seq [r] (:next-seq r))

(defn storedump-supersedes-pred [r] (:supersedes-pred r))

(defn storedump-objects [r] (:objects r))

(defn storedump-values [r] (:values r))

(defn storedump-facts [r] (:facts r))

(defn storedump-tx-of [r] (:tx-of r))

(defn storedump-txs [r] (:txs r))

(defn storedump-superseded [r] (:superseded r))

^{:line 24 :file "/home/tom/code/fram/wt-zig-store-engine-native-e8b5/src/fram/types.bclj"} (defrecord Store [next-id next-seq supersedes-pred objects values facts tx-of txs superseded idx-by-l idx-by-p idx-by-r idx-by-lp idx-by-pr])

(defn store-next-id [r] (:next-id r))

(defn store-next-seq [r] (:next-seq r))

(defn store-supersedes-pred [r] (:supersedes-pred r))

(defn store-objects [r] (:objects r))

(defn store-values [r] (:values r))

(defn store-facts [r] (:facts r))

(defn store-tx-of [r] (:tx-of r))

(defn store-txs [r] (:txs r))

(defn store-superseded [r] (:superseded r))

(defn store-idx-by-l [r] (:idx-by-l r))

(defn store-idx-by-p [r] (:idx-by-p r))

(defn store-idx-by-r [r] (:idx-by-r r))

(defn store-idx-by-lp [r] (:idx-by-lp r))

(defn store-idx-by-pr [r] (:idx-by-pr r))
