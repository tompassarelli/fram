(ns resolve-read
  (:require [fram.types :as t]
            [resolve-ident :as ri]
            [resolve-core :as rc]
            [fram.rotation :as rot]
            [fram.txn :as txn]))

^{:line 43 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (def SEG-RE ^{:line 43 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (re-pattern "seg\\d+"))

^{:line 45 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defrecord OrdPair [key child])

(defn ordpair-key [r] (:key r))

(defn ordpair-child [r] (:child r))

^{:line 49 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defrecord SegPair [idx child])

(defn segpair-idx [r] (:idx r))

(defn segpair-child [r] (:child r))

^{:line 53 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn view-cids [ctx v cids]
  ^{:line 57 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 57 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? v) nil ^{:line 59 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [sel ^{:line 59 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (reduce ^{:line 59 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [acc occ] ^{:line 61 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [r ^{:line 61 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/value-at ctx occ)]
  ^{:line 61 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 61 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? r) acc ^{:line 61 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (conj acc r)))) ^{:line 61 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} #{} ^{:line 61 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/by-subject-predicate ctx v "selects"))]
  ^{:line 62 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (filterv ^{:line 62 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [cid] ^{:line 62 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (contains? sel cid)) cids))))

^{:line 64 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn- pool-of [ctx view cids]
  ^{:line 68 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [in-view ^{:line 68 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 68 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? view) nil ^{:line 68 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (view-cids ctx view cids))]
  ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? in-view) ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (empty? in-view)) cids in-view)))

^{:line 71 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn- occ-key [ctx occ]
  ^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} [^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/occurrence-order occ) ^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/writer-of ctx occ)])

^{:line 76 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn select-main-1 [ctx view cids]
  ^{:line 80 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 80 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (empty? cids) nil ^{:line 82 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 82 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 83 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (first cids) ^{:line 84 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (first ^{:line 84 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (sort-by ^{:line 84 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [cid] ^{:line 84 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (occ-key ctx cid)) ^{:line 85 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (pool-of ctx view cids))))))

^{:line 87 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn select-causal-1 [ctx view cids]
  ^{:line 91 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 91 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (empty? cids) nil ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 94 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (first cids) ^{:line 95 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (first ^{:line 95 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (sort-by ^{:line 95 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [cid] ^{:line 95 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (occ-key ctx cid)) ^{:line 96 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (pool-of ctx view cids))))))

^{:line 98 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn- fact-r [ctx cid]
  ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? cid)) nil ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/target-at ctx cid)))

^{:line 103 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn pred-val [ctx view e pname]
  ^{:line 108 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 108 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 108 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 108 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? e)) nil ^{:line 110 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [occ ^{:line 110 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (select-main-1 ctx view ^{:line 110 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/by-subject-predicate ctx e pname))]
  ^{:line 111 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 111 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? occ) nil ^{:line 111 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/value-at ctx occ)))))

^{:line 113 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn kind-of [ctx view e]
  ^{:line 117 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (pred-val ctx view e "kind"))

^{:line 119 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn sym-val [ctx view e]
  ^{:line 123 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 123 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (= "symbol" ^{:line 123 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (kind-of ctx view e)) ^{:line 123 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (pred-val ctx view e "v") nil))

^{:line 125 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn ordered-children [ctx e]
  ^{:line 128 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 128 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 128 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 128 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? e)) ^{:line 129 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} [] ^{:line 130 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [pairs ^{:line 130 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (reduce ^{:line 130 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [acc cid] ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [k ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (rc/ord-parse ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/predicate-at ctx cid))
   r ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fact-r ctx cid)]
  ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? k) ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? r)) acc ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (conj acc ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (->OrdPair k r))))) ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} [] ^{:line 132 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/by-subject ctx e))]
  ^{:line 133 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (mapv ^{:line 133 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [pr] ^{:line 133 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (:child pr)) ^{:line 134 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (sort-by ^{:line 134 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [pr] ^{:line 134 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (:key pr)) rc/ord-cmp pairs)))))

^{:line 136 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn ordered-segs [ctx e]
  ^{:line 139 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 139 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 139 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 139 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? e)) ^{:line 140 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} [] ^{:line 141 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [pairs ^{:line 141 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (reduce ^{:line 141 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [acc cid] ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [p ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/predicate-at ctx cid)
   r ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fact-r ctx cid)]
  ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (and ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (string? p) ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (some? ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (re-matches SEG-RE ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (str p))) ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (some? r)) ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [n ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (parse-long ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (subs ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (str p) 3))]
  ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? n) acc ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (conj acc ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (->SegPair n r)))) acc))) ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} [] ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/by-subject ctx e))]
  ^{:line 144 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (mapv ^{:line 144 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [pr] ^{:line 144 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (:child pr)) ^{:line 145 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (sort-by ^{:line 145 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fn [pr] ^{:line 145 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (:idx pr)) pairs)))))

^{:line 147 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn head-sym [ctx view e]
  ^{:line 151 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 151 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (= "list" ^{:line 151 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (kind-of ctx view e)) ^{:line 152 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (sym-val ctx view ^{:line 152 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (first ^{:line 152 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ordered-children ctx e))) nil))

^{:line 155 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn unwrap-meta [ctx view e]
  ^{:line 159 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (loop [e e
   n 0]
  ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (and ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (some? e) ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (< n 64) ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (= "#%meta" ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (head-sym ctx view e))) ^{:line 161 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (recur ^{:line 161 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nth ^{:line 161 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ordered-children ctx e) 2 nil) ^{:line 161 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (inc n)) e)))

^{:line 164 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn bound-target [ctx view BOUND L]
  ^{:line 169 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 169 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 169 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 169 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? BOUND) ^{:line 169 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? L)) nil ^{:line 171 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fact-r ctx ^{:line 171 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (select-main-1 ctx view ^{:line 171 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/by-subject-predicate ctx L BOUND)))))

^{:line 173 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn refers-target [ctx view BOUND REFERS L]
  ^{:line 179 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [bt ^{:line 179 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (bound-target ctx view BOUND L)]
  ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (some? bt) bt ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? REFERS) ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? L)) nil ^{:line 184 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (fact-r ctx ^{:line 184 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (select-main-1 ctx view ^{:line 184 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/by-subject-predicate ctx L REFERS)))))))

^{:line 186 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn ^Boolean live-node? [ctx KIND e]
  ^{:line 190 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (if ^{:line 190 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (or ^{:line 190 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? ctx) ^{:line 190 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? KIND) ^{:line 190 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (nil? e)) false ^{:line 192 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (not ^{:line 192 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (empty? ^{:line 192 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/by-subject-predicate ctx e KIND)))))

^{:line 292 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (def builder-key :builder)

^{:line 294 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn builder [context]
  ^{:line 295 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (get ^{:line 295 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/writers-of context) builder-key))

^{:line 297 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn- resync! [context]
  ^{:line 298 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [open ^{:line 298 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (deref ^{:line 298 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (builder context))
   coordinate ^{:line 299 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/builder-coordinate open)
   store ^{:line 300 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/store-of context)]
  ^{:line 301 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/with-view! context ^{:line 302 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (rot/staged ^{:line 302 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (rot/project store) ^{:line 303 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (t/triple-slot0 coordinate) ^{:line 304 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (t/triple-slot2 coordinate) ^{:line 305 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/builder-operations open)))))

^{:line 309 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn context [store]
  ^{:line 310 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/graph store ^{:line 310 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} {builder-key ^{:line 310 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/open store)}))

^{:line 312 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn mint! [context]
  ^{:line 313 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/mint! ^{:line 313 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (builder context)))

^{:line 315 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn assert! [context subject predicate value]
  ^{:line 320 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [occurrence ^{:line 321 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/assert! ^{:line 321 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (builder context) ^{:line 321 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (t/triple subject predicate value))]
  ^{:line 322 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (do
  ^{:line 322 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (resync! context)
  occurrence)))

^{:line 324 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn update-single! [context subject predicate value]
  ^{:line 329 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [occurrence ^{:line 330 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/update-single! ^{:line 330 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (builder context) ^{:line 330 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/view context) subject predicate value)]
  ^{:line 332 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (do
  ^{:line 332 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (resync! context)
  occurrence)))

^{:line 336 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn commit! [context]
  ^{:line 337 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (let [store ^{:line 337 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/store-of context)
   cell ^{:line 338 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (builder context)
   coordinate ^{:line 339 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/commit! store cell)]
  ^{:line 340 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (do
  ^{:line 340 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (reset! cell ^{:line 340 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (deref ^{:line 340 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (txn/open store)))
  ^{:line 341 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (resync! context)
  coordinate)))

^{:line 344 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn events-by-subject [context subject]
  ^{:line 347 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (rot/by-slot0 ^{:line 347 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/view context) subject))

^{:line 349 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn events-by-subject-predicate [context subject predicate]
  ^{:line 353 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (rot/by-slot01 ^{:line 353 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (ri/view context) subject predicate))

^{:line 355 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn event-predicate [event]
  ^{:line 356 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (t/triple-slot1 ^{:line 356 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (rot/proposition-of event)))

^{:line 358 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (defn event-value [event]
  ^{:line 359 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (t/triple-slot2 ^{:line 359 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_read.bclj"} (rot/proposition-of event)))
