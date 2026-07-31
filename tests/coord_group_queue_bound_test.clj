;; Run: bb -cp out tests/coord_group_queue_bound_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def script
  '(do
     (load-file "coord.clj")
     (let [dir (.toFile
                (java.nio.file.Files/createTempDirectory
                 "fram-group-queue"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
           path (str (java.io.File. dir "facts.log"))
           tickets (atom [])
           fourth (atom nil)
           blocked-status
           (locking coord/group-io-lock
             (binding [coord/*durable-tickets* tickets]
               (coord/enqueue-durable! path ["line-0\n"] nil)
               (loop [remaining 100]
                 (when (and (pos? (coord/durable-queue-depth))
                            (pos? remaining))
                   (Thread/sleep 10)
                   (recur (dec remaining))))
               (doseq [i [1 2]]
                 (coord/enqueue-durable!
                  path [(str "line-" i "\n")] nil)))
             (reset! fourth
                     (future
                       (binding [coord/*durable-tickets* tickets]
                         (coord/enqueue-durable!
                          path ["line-3\n"] nil))))
             (Thread/sleep 300)
             {:blocked (not (realized? @fourth))
              :status (coord/durable-queue-status)})]
       (deref @fourth 5000 ::timeout)
       (let [acks (mapv #(deref % 5000 ::timeout) @tickets)]
         (prn (assoc blocked-status
                     :acks acks
                     :line-count (count (clojure.string/split-lines
                                         (slurp path)))))))))

(let [env (assoc (into {} (System/getenv)) "FRAM_GROUP_QUEUE" "2")
      probe @(proc/process {:dir (System/getProperty "user.dir")
                            :out :string :err :string :env env}
                           "bb" "-cp" "out" "-e" (pr-str script))
      result (when (zero? (:exit probe))
               (edn/read-string (str/trim (:out probe))))
      pass? (and result
                 (:blocked result)
                 (= 2 (get-in result [:status :capacity]))
                 (= 2 (get-in result [:status :depth]))
                 (= 2 (get-in result [:status :max-depth]))
                 (<= 1 (get-in result [:status :saturations]))
                 (= [:ok :ok :ok :ok] (:acks result))
                 (= 4 (:line-count result)))]
  (if pass?
    (println "coord-group-queue-bound: all checks passed" (pr-str result))
    (do
      (binding [*out* *err*]
        (println "coord-group-queue-bound: FAILED" (pr-str result))
        (println (:err probe)))
      (System/exit 1))))
