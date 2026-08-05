(ns resolve-ident
  (:require [fram.types :as t]
            [fram.store :as c]
            [fram.rotation :as rot]
            [fram.txn :as txn]))

^{:line 36 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (def no-occurrences ^{:line 36 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} [])

^{:line 38 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (def no-ordinals ^{:line 38 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} {})

^{:line 42 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Boolean minted-node-id? [x]
  ^{:line 42 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (txn/mint-coordinate? x))

^{:line 44 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Boolean legacy-node-id? [x]
  ^{:line 44 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (integer? x))

^{:line 46 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Boolean node-id? [x]
  ^{:line 47 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (or ^{:line 47 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (minted-node-id? x) ^{:line 47 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (legacy-node-id? x)))

^{:line 49 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Boolean literal? [x]
  ^{:line 49 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (not ^{:line 49 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (node-id? x)))

^{:line 53 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn literal! [x]
  ^{:line 54 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 54 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (node-id? x) ^{:line 55 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (throw ^{:line 55 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (ex-info "resolve: a code-graph literal may not be shaped like a node identity" ^{:line 55 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} {:type :ambiguous-code-literal :value x})) x))

^{:line 60 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defrecord Graph [store view writers ordinals])

(defn graph-store [r] (:store r))

(defn graph-view [r] (:view r))

(defn graph-writers [r] (:writers r))

(defn graph-ordinals [r] (:ordinals r))

^{:line 66 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Graph graph [store writers]
  ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (->Graph store ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (atom ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/project store)) writers ^{:line 69 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (atom no-ordinals)))

^{:line 71 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Graph new-graph [^String space-id]
  ^{:line 72 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph ^{:line 72 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (c/new-term-store space-id) ^{:line 72 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} {}))

^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn store-of [^Graph g]
  ^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-store g))

^{:line 76 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn writers-of [^Graph g]
  ^{:line 76 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-writers g))

^{:line 78 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn view [^Graph g]
  ^{:line 78 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (deref ^{:line 78 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-view g)))

^{:line 80 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Graph refresh! [^Graph g]
  ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (reset! ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-view g) ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/refresh ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g) ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-store g)))
  g))

^{:line 85 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Graph with-view! [^Graph g rotation]
  ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (reset! ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-view g) rotation)
  g))

^{:line 94 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ordinal [^Graph g node]
  ^{:line 97 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [book ^{:line 97 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (deref ^{:line 97 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-ordinals g))
   hit ^{:line 97 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (get book node)]
  ^{:line 98 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 98 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (some? hit) hit ^{:line 100 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [n ^{:line 100 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (+ 1 ^{:line 100 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (count book))]
  ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (swap! ^{:line 101 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-ordinals g) assoc node n)
  n)))))

^{:line 105 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn by-subject [^Graph g subject]
  ^{:line 108 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/occurrences ^{:line 108 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/by-t1 ^{:line 108 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g) subject)))

^{:line 110 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn by-subject-predicate [^Graph g subject predicate]
  ^{:line 114 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/occurrences ^{:line 114 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/by-t12 ^{:line 114 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g) subject predicate)))

^{:line 116 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn by-predicate [^Graph g predicate]
  ^{:line 119 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/occurrences ^{:line 119 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/by-t2 ^{:line 119 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g) predicate)))

^{:line 121 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn by-predicate-value [^Graph g predicate value]
  ^{:line 125 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/occurrences ^{:line 125 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/by-t23 ^{:line 125 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g) predicate value)))

^{:line 127 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn proposition-at [^Graph g occurrence]
  ^{:line 130 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [event ^{:line 130 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/event-at ^{:line 130 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g) occurrence)]
  ^{:line 131 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 131 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (nil? event) nil ^{:line 131 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/proposition-of event))))

^{:line 133 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn subject-at [^Graph g occurrence]
  ^{:line 136 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [p ^{:line 136 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (proposition-at g occurrence)]
  ^{:line 137 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 137 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (nil? p) nil ^{:line 137 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple-t1 p))))

^{:line 139 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn predicate-at [^Graph g occurrence]
  ^{:line 142 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [p ^{:line 142 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (proposition-at g occurrence)]
  ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (nil? p) nil ^{:line 143 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple-t2 p))))

^{:line 145 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn value-at [^Graph g occurrence]
  ^{:line 148 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [p ^{:line 148 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (proposition-at g occurrence)]
  ^{:line 149 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 149 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (nil? p) nil ^{:line 149 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple-t3 p))))

^{:line 153 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn target-at [^Graph g occurrence]
  ^{:line 156 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [r ^{:line 156 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 156 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (nil? occurrence) nil ^{:line 156 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (value-at g occurrence))]
  ^{:line 157 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 157 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (node-id? r) r nil)))

^{:line 159 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn live-propositions [^Graph g]
  ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/propositions ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/all-occurrences ^{:line 160 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g))))

^{:line 162 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Boolean live? [^Graph g occurrence]
  ^{:line 165 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (rot/live-occurrence? ^{:line 165 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (view g) occurrence))

^{:line 170 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn occurrence-order [occurrence]
  ^{:line 171 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 171 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/occurrence-coordinate? occurrence) ^{:line 172 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} [1 ^{:line 172 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple-t3 ^{:line 172 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple-t1 occurrence)) ^{:line 172 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple-t3 occurrence)] ^{:line 173 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} [0 ^{:line 173 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 173 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (integer? occurrence) occurrence 0) 0]))

^{:line 177 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^String writer-of [^Graph g occurrence]
  ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/occurrence-coordinate? occurrence) ^{:line 181 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [hit ^{:line 181 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (get ^{:line 181 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-writers g) ^{:line 181 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple-t1 occurrence))]
  ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (nil? hit) "" ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (str hit))) ""))

^{:line 187 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn minted-count [^Graph g]
  ^{:line 187 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (count ^{:line 187 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (deref ^{:line 187 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-ordinals g))))

^{:line 191 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn withdrawal-count [^Graph g]
  ^{:line 192 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (count ^{:line 192 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (c/withdrawal-triples ^{:line 192 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-store g))))

^{:line 194 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn open [^Graph g]
  ^{:line 194 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (txn/open ^{:line 194 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-store g)))

^{:line 198 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn mint! [^Graph g b]
  ^{:line 201 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [node ^{:line 201 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (txn/mint! b)]
  ^{:line 201 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 201 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (ordinal g node)
  node)))

^{:line 203 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn assert-on! [b subject predicate value]
  ^{:line 208 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (txn/assert! b ^{:line 208 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (t/triple subject predicate value)))

^{:line 210 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn retract-on! [^Graph g b occurrence]
  ^{:line 214 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [p ^{:line 214 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (proposition-at g occurrence)]
  ^{:line 215 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 215 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (nil? p) nil ^{:line 215 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (txn/retract! b p))))

^{:line 217 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn ^Graph commit! [^Graph g b]
  ^{:line 220 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 221 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (if ^{:line 221 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (pos? ^{:line 221 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (txn/operation-count b)) ^{:line 221 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 221 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (txn/commit! ^{:line 221 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (graph-store g) b)))
  ^{:line 222 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (refresh! g)))

^{:line 224 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn assert! [^Graph g subject predicate value]
  ^{:line 229 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [b ^{:line 229 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (open g)
   occurrence ^{:line 229 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (assert-on! b subject predicate value)]
  ^{:line 230 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 230 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (commit! g b)
  occurrence)))

^{:line 236 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (defn retire! [^Graph g occurrence]
  ^{:line 239 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (let [b ^{:line 239 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (open g)
   target ^{:line 239 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (retract-on! g b occurrence)]
  ^{:line 240 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (do
  ^{:line 240 :file "/home/tom/code/fram/wt-engine-fixes/src/resolve_ident.bclj"} (commit! g b)
  target)))
