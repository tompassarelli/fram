(ns resolve-core
  (:require [clojure.string :as str]))

(def ORD-STEP 65536)

(defrecord OrdKey [path tie])

(defn ordkey-path [r] (:path r))

(defn ordkey-tie [r] (:tie r))

(defn- digits [^String s]
  (let [v (parse-long s)]
  (if (nil? v) 0 v)))

(def ORD-RE (re-pattern "f(\\d+(?:\\.\\d+)*)~(\\d+)"))

(def ORD-DOT-RE (re-pattern "\\."))

(def ORD-FLAT-RE (re-pattern "f(\\d+)"))

(defn ord-parse [p]
  (if (string? p) (do
  (let [m (re-matches ORD-RE (str p))]
  (if (some? m) (->OrdKey (mapv digits (vec (str/split (nth m 1) ORD-DOT-RE))) (digits (nth m 2))) (let [d (re-matches ORD-FLAT-RE (str p))]
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

(def PARAM-FORMS #{"defn" "fn" "fn*" "defn-" "defmacro"})

(def DEF-FORMS #{"def-" "defonce" "def"})

(def TYPE-DEFS #{"defunion" "defrecord" "definterface" "defprotocol" "deftype"})

(def EXTEND-FORMS #{"extend-protocol" "extend" "extend-type"})

(def EFFECT-DEFS (into #{"defmulti" "defmethod"} EXTEND-FORMS))

(def VALUE-DEFS (into PARAM-FORMS DEF-FORMS))

(def WRITABLE-DEFS (into (into VALUE-DEFS TYPE-DEFS) EFFECT-DEFS))

(defn ^Boolean writable-def-head? [^String h]
  (contains? WRITABLE-DEFS h))

(def TYPE-COLON #{":-" ":"})

(def LET-FORMS #{"binding" "with-local-vars" "loop" "when-some" "when-let*" "if-let" "with-redefs" "let" "when-let" "if-some" "with-open" "if-let*" "dotimes"})

(def FOR-FORMS #{"doseq" "for"})

(def MATCH-FORMS #{"match"})

(def DISAMBIG-CAP 8)

(def MODES #{"reorder" "rename" "resolve" "delete" "replace-in-body" "set-body" "callgraph" "upsert-form"})

(defn ctor-prefix [nm]
  (let [s (if (nil? nm) "" nm)]
  (cond
  (or (str/starts-with? s "map->") (str/includes? s "/map->")) "map->"
  (or (str/starts-with? s "->") (str/includes? s "/->")) "->"
  :else nil)))
