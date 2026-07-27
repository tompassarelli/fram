(ns fri
  (:require [fri-port :as fp]))

(def ^String MAGIC fp/MAGIC)

(def FMT fp/FMT)

(def ^:dynamic *cache-cap* nil)

(defn write-fri! [store-val path & opts]
  (apply fp/write-fri! store-val path opts))

(defn render-cache-cap []
  (fp/render-cache-cap *cache-cap*))

(defn clear-render-caches! [img]
  (fp/clear-render-caches! img))

(defn open-fri [path]
  (fp/open-fri-with-cap path *cache-cap*))

(defn close-fri! [img]
  (fp/close-fri! img))

(defn nfacts [img]
  (fp/nfacts img))

(defn covers-seq [img]
  (fp/covers-seq img))

(defn next-id [img]
  (fp/next-id img))

(defn supersedes-pred [img]
  (fp/supersedes-pred img))

(defn cid->ord [img cid]
  (fp/cid->ord img cid))

(defn ^Boolean superseded-ord? [img ord]
  (fp/superseded-ord? img ord))

(defn ^Boolean live-cid? [img cid]
  (fp/live-cid? img cid))

(defn fact-of [img cid]
  (fp/fact-of img cid))

(defn literal [img id]
  (fp/literal img id))

(defn ^Boolean value-object? [img id]
  (fp/value-object? img id))

(defn value-id [img ^String value]
  (fp/value-id img value))

(defn by-l [img lid]
  (fp/by-l img lid))

(defn by-lp [img lid pid]
  (fp/by-lp img lid pid))

(defn pred-id [img ^String value]
  (fp/pred-id img value))

(defn resolve-name [img ^String value]
  (fp/resolve-name img value))

(defn name-of [img subj]
  (fp/name-of img subj))

(defn cold->dump [img]
  (fp/cold->dump img))

(defn render [img cid]
  (fp/render img cid))

(defn render-ord [img ord]
  (fp/render-ord img ord))

(defn by-lp-ords [img lid pid]
  (fp/by-lp-ords img lid pid))

(defn render-lp [img ^String subj-name ^String pred-name]
  (fp/render-lp img subj-name pred-name))

(defn cold-name-triples [img schema-pred? read-hidden-pred?]
  (fp/cold-name-triples img schema-pred? read-hidden-pred?))

(defn ^Boolean verify-segments? [img segments]
  (fp/verify-segments? img segments))
