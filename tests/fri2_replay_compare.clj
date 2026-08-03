#!/usr/bin/env bb
;; State agreement between the Beagle replay and the FRAMLOG the frozen Zig
;; daemon persisted. The two are not byte-equal by construction: the Zig daemon
;; masks a single-cardinality displacement in its live index and leaves the
;; displaced assertion in history, while fram.fri-replay records the
;; displacement as a retraction so the TermStore needs no projection layer.
;; Agreement is therefore: same version, every Beagle fact still live in the log,
;; and every extra log fact explained by a declared-single predicate.
(require '[clojure.string :as str])

(defn read-state [path]
  (let [lines (remove str/blank? (str/split-lines (slurp path)))]
    {:version (some (fn [line]
                      (when (str/starts-with? line "final-version\t")
                        (parse-long (second (str/split line #"\t" 2)))))
                    lines)
     :facts (into #{}
                  (comp (filter #(str/starts-with? % "fact\t"))
                        (map #(vec (rest (str/split % #"\t" 4)))))
                  lines)}))

(let [[zig-path beagle-path & extra] *command-line-args*]
  (when (or (nil? zig-path) (nil? beagle-path) (seq extra))
    (binding [*out* *err*]
      (println "usage: bb tests/fri2_replay_compare.clj ZIG-STATE BEAGLE-STATE"))
    (System/exit 2))
  (let [zig (read-state zig-path)
        beagle (read-state beagle-path)
        singles (into #{}
                      (comp (filter (fn [[_ p r]] (and (= p "cardinality") (= r "single"))))
                            (map (fn [[l _ _]] (str/replace-first l #"^@" ""))))
                      (:facts beagle))
        missing (sort (remove (:facts zig) (:facts beagle)))
        extra-facts (sort (remove (:facts beagle) (:facts zig)))
        unexplained (remove (fn [[_ p _]] (contains? singles p)) extra-facts)
        problems (cond-> []
                   (not= (:version zig) (:version beagle))
                   (conj (str "version " (:version zig) " vs " (:version beagle)))
                   (seq missing)
                   (conj (str "live in the replay but not in the FRAMLOG: " (vec missing)))
                   (seq unexplained)
                   (conj (str "live in the FRAMLOG with no declared-single predicate: "
                              (vec unexplained))))]
    (if (seq problems)
      (do (binding [*out* *err*]
            (doseq [problem problems] (println "fri2-replay:" problem)))
          (System/exit 1))
      (println (str "state agrees: version " (:version beagle)
                    ", " (count (:facts beagle)) " live facts, "
                    (count extra-facts) " displaced assertions retained in the log")))))
