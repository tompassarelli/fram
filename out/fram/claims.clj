(ns fram.claims
  (:require [fram.store :as c]
            [fram.schema :as s]
            [fram.datalog :as d]
            [fram.world :as w]
            [fram.rt :as rt]
            [clojure.string :as str]))

(def ^String evidence-pred "claim.evidence")

(def ^String reason-pred "claim.reason")

(def ^String source-pred "evidence.source")

(def ^String region-pred "evidence.region")

(def ^String fingerprint-pred "evidence.fingerprint")

(def ^String world-pred "evidence.world")

(def ^String verified-view "@view:claim.verified")

(def ^String rejected-view "@view:claim.rejected")

(def ^String select-pred "selects")

(def ^String world-record-pred "world.record")

(def ^String version-prefix "world.version:")

(def ^String reverification-relation "claim.reverification")

(def ^String changed-slot-relation "claim.changed-slot")

(def ^String verdict-view-relation "claim.verdict-view")

(def default-views {:verified verified-view :rejected rejected-view})

(defn ^String scoped-view [^String root ^String agent]
  (str root ":" agent))

(defn- sto [co]
  (:store co))

(defn- ^Boolean family? [^String root nm]
  (if (nil? nm) false (or (= nm root) (str/starts-with? (str nm) (str root ":")))))

(defn- writer-of [co cid]
  (let [m (deref (sto co))]
  (:agent (get (:txs m) (get (:tx-of m) cid)))))

(defn evidence-nodes [co claim-cid]
  (let [st (sto co)
   pid (c/value-id st evidence-pred)]
  (if (nil? pid) [] (vec (sort (vec (distinct (mapv (fn [cid] (:r (c/fact-of st cid))) (c/by-lp st claim-cid pid)))))))))

(defn evidence [co node]
  (let [st (sto co)]
  {:node node :source (s/lookup st node source-pred) :region (s/lookup st node region-pred) :fingerprint (s/lookup st node fingerprint-pred) :world (s/lookup st node world-pred)}))

(defn provenance [co claim-cid]
  (mapv (fn [n] (evidence co n)) (evidence-nodes co claim-cid)))

(defn- selections-of [co claim-cid]
  (let [st (sto co)
   selp (c/value-id st select-pred)]
  (if (nil? selp) [] (c/by-pr st selp claim-cid))))

(defn- verdict-of [co views sel]
  (let [st (sto co)
   nm (s/name-of st (:l (c/fact-of st sel)))]
  (cond
  (family? (:verified views) nm) {:verdict :verified :view nm :by (writer-of co sel) :cid sel}
  (family? (:rejected views) nm) {:verdict :rejected :view nm :by (writer-of co sel) :cid sel}
  :else nil)))

(defn verdict
  ([co claim-cid]
    (verdict co default-views claim-cid))
  ([co views claim-cid]
    (reduce (fn [best sel] (let [v (verdict-of co views sel)]
  (cond
  (nil? v) best
  (nil? best) v
  (> (:cid v) (:cid best)) v
  :else best))) nil (selections-of co claim-cid))))

(defn verifier [co claim-cid]
  (let [v (verdict co claim-cid)]
  (if (and (some? v) (= :verified (:verdict v))) (:by v) nil)))

(defn rejection [co claim-cid]
  (let [v (verdict co claim-cid)]
  (if (and (some? v) (= :rejected (:verdict v))) {:reason (s/lookup (sto co) claim-cid reason-pred) :by (:by v) :cid (:cid v)} nil)))

(defn status
  ([co claim-cid]
    (status co default-views claim-cid))
  ([co views claim-cid]
    (if (not (c/live? (sto co) claim-cid)) :superseded (let [v (verdict co views claim-cid)]
  (cond
  (some? v) (:verdict v)
  (not (empty? (evidence-nodes co claim-cid))) :pending
  :else nil)))))

(defn- version-record-of [st vid]
  (let [e (s/resolve-name st (str version-prefix vid))]
  (if (nil? e) nil (let [t (s/lookup st e world-record-pred)]
  (if (nil? t) nil (fram.rt/parse-edn (str t)))))))

(defn- version-chain [st vid]
  (loop [v vid
   acc {}]
  (if (or (nil? v) (contains? acc v)) acc (let [r (version-record-of st v)]
  (if (nil? r) acc (recur (:base r) (assoc acc v r)))))))

(defn- slot-blobs [st vid]
  (reduce (fn [m e] (assoc m (:slot e) (:blob-id e))) {} (w/manifest (version-chain st vid) vid)))

(defn- changed-slots [st from to]
  (let [a (slot-blobs st from)
   b (slot-blobs st to)]
  (vec (filterv (fn [k] (not (= (get a k) (get b k)))) (vec (sort (vec (keys a))))))))

(defn- verdict-view-ids [co views]
  (let [st (sto co)
   selp (c/value-id st select-pred)]
  (if (nil? selp) [] (vec (sort (vec (distinct (reduce (fn [acc cid] (let [l (:l (c/fact-of st cid))]
  (if (family? (:verified views) (s/name-of st l)) (conj acc l) acc))) [] (c/by-p st selp)))))))))

(defn reverification-rules
  ([co from to]
    (reverification-rules co default-views from to))
  ([co views from to]
    (let [st (sto co)
   evp (c/value-id st evidence-pred)
   srp (c/value-id st source-pred)
   wlp (c/value-id st world-pred)
   selp (c/value-id st select-pred)
   fromv (if (nil? from) nil (c/value-id st from))
   slots (if (nil? from) [] (filterv (fn [i] (some? i)) (mapv (fn [k] (c/value-id st k)) (changed-slots st from to))))
   vids (verdict-view-ids co views)]
  (if (or (nil? evp) (or (nil? srp) (or (nil? wlp) (or (nil? selp) (or (nil? fromv) (or (empty? slots) (empty? vids))))))) [[] []] [(vec (concat (mapv (fn [sid] (d/rule changed-slot-relation [sid] [])) slots) (mapv (fn [v] (d/rule verdict-view-relation [v] [])) vids))) [(d/rule reverification-relation [(d/v :x)] [(d/lit "triple" [(d/v :e) wlp fromv]) (d/lit "triple" [(d/v :e) srp (d/v :s)]) (d/lit changed-slot-relation [(d/v :s)]) (d/lit "triple" [(d/v :x) evp (d/v :e)]) (d/lit "fact-id" [(d/v :x) (d/v :l) (d/v :p) (d/v :r)]) (d/lit "triple" [(d/v :view) selp (d/v :x)]) (d/lit verdict-view-relation [(d/v :view)])])]]))))

(defn needs-reverification
  ([co from to]
    (needs-reverification co default-views from to))
  ([co views from to]
    (let [db (d/run-strata (sto co) (reverification-rules co views from to))]
  (set (mapv (fn [t] (get t 0)) (d/facts db reverification-relation))))))
