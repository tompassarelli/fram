#!/usr/bin/env bb
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def seed 0x5EED2D6)
(def gamma (Long/parseUnsignedLong "9E3779B97F4A7C15" 16))
(def mix-a (Long/parseUnsignedLong "BF58476D1CE4E5B9" 16))
(def mix-b (Long/parseUnsignedLong "94D049BB133111EB" 16))

(defn splitmix64 [state]
  (let [next-state (unchecked-add state gamma)
        z1 (unchecked-multiply
            (bit-xor next-state (unsigned-bit-shift-right next-state 30))
            mix-a)
        z2 (unchecked-multiply
            (bit-xor z1 (unsigned-bit-shift-right z1 27))
            mix-b)]
    [next-state (bit-xor z2 (unsigned-bit-shift-right z2 31))]))

(defn rng [initial]
  (let [state (atom (long initial))]
    (fn [bound]
      (let [[next-state value] (splitmix64 @state)]
        (reset! state next-state)
        (int (Long/remainderUnsigned value (long bound)))))))

(def subjects ["@fuzz_a" "@fuzz_b" "@fuzz_c" "@fuzz_d"])
(def predicates ["p_fuzz_single_a" "p_fuzz_single_b"
                 "p_fuzz_multi_a" "p_fuzz_multi_b"
                 "p_fuzz_multi_c" "p_fuzz_multi_d"])
(def values ["v0" "v1" "v2" "v3" "v4" "v5" "v6" "v7"])
(def single-predicates (set (take 2 predicates)))

(defn pick [rand-int xs]
  (nth xs (rand-int (count xs))))

(defn fact-token [p r base]
  (str p "=" r (when (some? base) (str "@" base))))

(defn generate [corpus-index]
  (let [rand-int (rng (unchecked-add seed corpus-index))
        estimated-head (atom 2)
        written-groups (atom #{})
        subject #(pick rand-int subjects)
        predicate #(pick rand-int predicates)
        value #(pick rand-int values)
        maybe-base
        (fn [te p]
          (when (< (rand-int 100) 50)
            (if (and (contains? single-predicates p)
                     (contains? @written-groups [te p])
                     (< (rand-int 100) 40))
              0
              @estimated-head)))
        note-write!
        (fn [te p]
          (swap! written-groups conj [te p])
          (swap! estimated-head inc))
        random-op
        (fn []
          (let [roll (rand-int 100)
                te (subject)
                p (predicate)
                r (value)]
            (cond
              (< roll 50)
              (let [base (maybe-base te p)]
                (note-write! te p)
                (str/join "\t" (cond-> ["assert" te p r]
                                  (some? base) (conj (str base)))))

              (< roll 65)
              (let [base (maybe-base te p)]
                (swap! estimated-head inc)
                (str/join "\t" (cond-> ["retract" te p r]
                                  (some? base) (conj (str base)))))

              (< roll 75)
              (let [base (if (< (rand-int 100) 55) @estimated-head 0)]
                (swap! estimated-head inc)
                (str/join "\t" ["assert-at-version" te p r (str base)]))

              (< roll 85)
              (let [p2 (predicate)
                    r2 (value)
                    b2 (maybe-base te p2)]
                (swap! estimated-head inc)
                (str/join "\t"
                          ["assert-batch" te
                           (str (fact-token p r nil) "|" (fact-token p2 r2 b2))]))

              (< roll 95)
              (let [p2 (predicate)
                    r2 (value)
                    base (if (< (rand-int 100) 55) @estimated-head 0)]
                (swap! estimated-head inc)
                (str/join "\t"
                          ["assert-batch-at-version" te (str base)
                           (str (fact-token p r nil) "|" (fact-token p2 r2 nil))]))

              :else "version")))]
    (vec (concat
          ["version"
           "assert\t@p_fuzz_single_a\tcardinality\tsingle"
           "assert\t@p_fuzz_single_b\tcardinality\tsingle"]
          (repeatedly 196 random-op)
          ["version"]))))

(def output-dir (io/file "tests/oracle"))
(.mkdirs output-dir)
(doseq [index (range 1 4)]
  (let [path (io/file output-dir (str "F" index ".tsv"))]
    (spit path (str (str/join "\n" (generate index)) "\n"))
    (println (.getPath path))))
