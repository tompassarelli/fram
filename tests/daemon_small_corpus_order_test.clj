;; Small-corpus parity regression for the daemon's :facts/:show projection.
;; PersistentArrayMap preserves insertion order below nine keys, so rebuilding
;; from the reified store must not silently choose the store's CID-map order.
(require '[clojure.java.io :as io]
         '[fram.fold :as fold]
         '[fram.rt :as rt])

(load-file "coord_daemon.clj")
(reset! snapshot-boot-enabled? false)

(def failures (atom 0))
(def checks (atom 0))

(defn check! [label pass?]
  (swap! checks inc)
  (println (if pass? "PASS" "FAIL") label)
  (when-not pass? (swap! failures inc)))

(def scratch-dir
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-daemon-small-order-"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (.getCanonicalPath (io/file scratch-dir "facts.log")))
(def subject "@small-order")

(spit log-path
      (str
       (pr-str {:tx 1 :op "assert" :l subject :p "title"
                :r "cold truth" :by "parity-test"})
       "\n"
       (pr-str {:tx 2 :op "assert" :l subject :p "owner"
                :r "scratch" :by "parity-test"})
       "\n"))

(boot-flat! log-path)

(let [cold-triples
      (mapv (fn [fact] [(:l fact) (:p fact) (:r fact)])
            (:facts (fold/fold (rt/read-log log-path))))
      warm-triples (:triples (facts-wire-snapshot))
      cold-rows
      (mapv (fn [[_ predicate value]] [predicate value])
            (filter #(= subject (first %)) cold-triples))
      warm-rows (:rows (handle {:op :show :te subject}))]
  (check! "daemon :facts preserves the two-key cold-fold order"
          (= cold-triples warm-triples))
  (check! "daemon :show is byte-order-identical to the cold exact subject"
          (= cold-rows warm-rows)))

(println (format "daemon-small-corpus-order: %d / %d PASS"
                 (- @checks @failures) @checks))
(System/exit (if (zero? @failures) 0 1))
