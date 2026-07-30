(ns fram.export
  (:require [fram.kernel :as k]
            [clojure.string :as str]
            [fram.rt :as rt]))

(def order ["title" "owner" "lead" "driver" "source" "proposed_by" "created_by" "created_at" "updated_at" "committed" "do_on" "valid_until" "estimate_hours" "repo" "part_of" "depends_on" "relates_to" "clarifies" "amends" "outcome" "abandoned"])

(defn- distinct-s [xs]
  (reduce (fn [acc x] (if (k/vec-contains? acc x) acc (conj acc x))) [] xs))

(defn- ^String render-obj [facts ^String p ^String v]
  (if (= "ref" (k/value-kind-of facts {} p)) (if (str/starts-with? v "@") v (str "@" v)) (if (or (str/blank? v) (str/includes? v " ") (str/includes? v "\t") (str/includes? v "\n") (str/includes? v "\r") (str/starts-with? v "@") (str/starts-with? v "\"")) (fram.rt/edn-quote v) v)))

(defn ^String thread-md [facts ^String te]
  (let [reg (k/predicate-registry facts)
   subject-facts (k/q-by-l facts te)
   present (distinct-s (mapv (fn [c] (k/predicate-name reg (:p c))) subject-facts))
   ordered (filterv (fn [p] (k/vec-contains? present p)) order)
   extra (vec (sort (filterv (fn [p] (and (not (k/vec-contains? order p)) (not (= p "body")))) present)))
   preds (vec (concat ordered extra))
   lines (reduce (fn [acc p] (let [pid (k/predicate-id reg p)
   values (mapv (fn [c] (:r c)) (filterv (fn [c] (= pid (k/predicate-id reg (:p c)))) subject-facts))]
  (vec (concat acc (mapv (fn [v] (str p "  " (render-obj facts p v))) values))))) [] preds)
   body-id (k/predicate-id reg "body")
   bodies (filterv (fn [c] (= body-id (k/predicate-id reg (:p c)))) subject-facts)
   b (if (empty? bodies) nil (:r (first bodies)))
   body (if (some? b) b "")]
  (str te "\n" (str/join "\n" lines) "\n---\n" body)))
