(ns resolve-core
  (:require [clojure.string :as str]))

(def ORD-STEP 65536)

(defrecord OrdKey [path tie])

(defn ordkey-path [r] (:path r))

(defn ordkey-tie [r] (:tie r))

(defn- digits [^String s]
  (let [v (parse-long s)]
  (if (nil? v) 0 v)))

(defn ord-parse [p]
  (if (string? p) (do
  (let [m (re-matches #"f(\d+(?:\.\d+)*)~(\d+)" (str p))]
  (if (some? m) (->OrdKey (mapv digits (vec (str/split (nth m 1) #"\."))) (digits (nth m 2))) (let [d (re-matches #"f(\d+)" (str p))]
  (if (some? d) (do
  (->OrdKey [(* (inc (digits (nth d 1))) ORD-STEP)] 0)))))))))

(defn ^Boolean ord-pos? [p]
  (some? (ord-parse p)))

(defn ^String ord-str [path tie]
  (str "f" (str/join "." path) "~" tie))

(defn ord-veccmp [a b]
  (loop [i 0]
  (let [ae (>= i (count a))
   be (>= i (count b))]
  (cond
  (and ae be) 0
  ae -1
  be 1
  :else (let [c (compare (nth a i) (nth b i))]
  (if (zero? c) (recur (inc i)) c))))))

(defn ord-cmp [^OrdKey x ^OrdKey y]
  (let [c (ord-veccmp (:path x) (:path y))]
  (if (zero? c) (compare (:tie x) (:tie y)) c)))

(defn ord-append [last-path]
  (if (or (nil? last-path) (empty? last-path)) [ORD-STEP] (conj (vec (butlast last-path)) (+ (nth last-path (dec (count last-path))) ORD-STEP))))

(defn ord-between [lo hi]
  (cond
  (and (nil? lo) (nil? hi)) [ORD-STEP]
  (nil? hi) (ord-append lo)
  :else (let [lo2 (if (nil? lo) [0] lo)]
  (loop [i 0
   acc []]
  (let [a (if (< i (count lo2)) (nth lo2 i) 0)
   b (if (< i (count hi)) (nth hi i) (+ a (* 2 ORD-STEP)))]
  (if (> (- b a) 1) (conj acc (quot (+ a b) 2)) (recur (inc i) (conj acc a))))))))

(def PARAM-FORMS #{"defn" "defn-" "fn" "defmacro" "fn*"})

(def DEF-FORMS #{"def" "def-" "defonce"})

(def TYPE-DEFS #{"defrecord" "deftype" "defprotocol" "definterface" "defunion"})

(def EXTEND-FORMS #{"extend-type" "extend-protocol" "extend"})

(def EFFECT-DEFS (into #{"defmulti" "defmethod"} EXTEND-FORMS))

(def VALUE-DEFS (into PARAM-FORMS DEF-FORMS))

(def WRITABLE-DEFS (into (into VALUE-DEFS TYPE-DEFS) EFFECT-DEFS))

(defn ^Boolean writable-def-head? [^String h]
  (contains? WRITABLE-DEFS h))

(def TYPE-COLON #{":-" ":"})

(def LET-FORMS #{"let" "loop" "when-let" "if-let" "when-some" "if-some" "binding" "with-open" "with-local-vars" "dotimes" "with-redefs" "if-let*" "when-let*"})

(def FOR-FORMS #{"doseq" "for"})

(def MATCH-FORMS #{"match"})

(def DISAMBIG-CAP 8)

(def MODES #{"resolve" "rename" "delete" "reorder" "callgraph" "upsert-form" "set-body" "replace-in-body"})

(defn ctor-prefix [nm]
  (let [s (if (nil? nm) "" nm)]
  (cond
  (or (str/starts-with? s "map->") (str/includes? s "/map->")) "map->"
  (or (str/starts-with? s "->") (str/includes? s "/->")) "->"
  :else nil)))
