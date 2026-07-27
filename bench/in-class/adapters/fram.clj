;; Fram adapter for the durable sole-writer in-class benchmark.
;; Runs the production coordinator state machine in-process because managed
;; worktrees cannot bind loopback ports. Every write still takes the durable
;; serve-flat commit path against a scratch log.
(require '[cheshire.core :as json]
         '[clojure.java.io :as io])
(load-file "coord_daemon.clj")

(def corpus-triples (Long/parseLong (or (first *command-line-args*) "3000")))
(def run-id (Long/parseLong (or (second *command-line-args*) "1")))
(when-not (and (pos? corpus-triples) (zero? (mod corpus-triples 3)))
  (throw (ex-info "corpus size must be a positive multiple of 3"
                  {:corpus-triples corpus-triples})))

(def scratch (.toFile (java.nio.file.Files/createTempDirectory
                       "fram-in-class-" (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (.getPath (io/file scratch "coordination.log")))
(def expected-rows (quot corpus-triples 3))

(defn corpus-fact [tx]
  (let [subject (quot (dec tx) 3)
        slot (mod (dec tx) 3)
        [predicate value] (case slot
                            0 ["kind" "thread"]
                            1 ["title" (str "title-" subject)]
                            2 ["owner" (str "@owner-" (mod subject 32))])]
    {:tx tx :op "assert" :l (str "@corpus-" subject)
     :p predicate :r value :ts "bench" :by "bench/in-class"}))

(with-open [w (io/writer log-path)]
  (doseq [tx (range 1 (inc corpus-triples))]
    (.write w (pr-str (corpus-fact tx)))
    (.write w "\n")))

(def q-join
  {:find "in-class"
   :rules [{:head {:rel "in-class" :args [{:var "s"} {:var "title"}]}
            :body [{:rel "triple" :args [{:var "s"} "kind" "thread"]}
                   {:rel "triple" :args [{:var "s"} "title" {:var "title"}]}]}]})

(defn ms [f]
  (let [t0 (System/nanoTime)
        value (f)]
    [(/ (- (System/nanoTime) t0) 1e6) value]))

(defn query! []
  (handle {:op :query :query q-join
           :query-timeout-ms 120000
           :query-max-rows 1000000
           :query-max-response-bytes 200000000}))

(defn write! [subject value]
  (handle {:op :assert :te subject :p "bench_value" :r value :base nil}))

(defn percentile [xs p]
  (let [sorted (vec (sort xs))]
    (nth sorted (min (dec (count sorted))
                     (int (Math/floor (* p (count sorted))))))))

(def errors (atom 0))
(defn checked-write! [subject value]
  (let [reply (write! subject value)]
    (when-not (:ok reply) (swap! errors inc))
    reply))
(defn checked-query! []
  (let [reply (query!)]
    (when (or (:error reply) (not= expected-rows (count (:ok reply))))
      (swap! errors inc))
    reply))

(def boot-result
  (let [[elapsed status]
        (ms #(do (boot-flat! log-path)
                 (handle {:op :status})))]
    {:ms elapsed :status status}))

(def cold
  (let [[elapsed result] (ms checked-query!)]
    {:ms elapsed :rows (count (:ok result))}))

;; JIT/first-touch warmup happens only after the cold measurements.
(dotimes [i 30]
  (checked-write! (str "@warm-" i) (str "warm-" i)))
(dotimes [_ 10] (checked-query!))

(def sustained
  (let [start (promise)
        reads (atom 0)
        reader (future
                 @start
                 (dotimes [_ 12]
                   (checked-query!)
                   (swap! reads inc)))
        _ (deliver start true)
        [elapsed _]
        (ms #(dotimes [i 240]
               (checked-write! (str "@sustained-" run-id "-" i)
                               (str "value-" i))))]
    @reader
    {:ops-s (/ 240.0 (/ elapsed 1000.0))
     :read-ops @reads}))

(def mixed
  (let [read-latencies (atom [])
        [elapsed _]
        (ms #(dotimes [i 40]
               (checked-write! (str "@mixed-" run-id "-" i) (str "value-" i))
               (dotimes [_ 3]
                 (let [[read-ms _] (ms checked-query!)]
                   (swap! read-latencies conj read-ms)))))]
    {:ops-s (/ 160.0 (/ elapsed 1000.0))
     :read-p50-ms (percentile @read-latencies 0.50)}))

(def row
  {:adapter "fram"
   :run run-id
   :corpus-triples corpus-triples
   :boot-to-serving-ms (:ms boot-result)
   :cold-start-query-ms (:ms cold)
   :cold-query-rows (:rows cold)
   :write-under-read-ops-s (:ops-s sustained)
   :concurrent-read-ops (:read-ops sustained)
   :mixed-ops-s (:ops-s mixed)
   :mixed-read-p50-ms (:read-p50-ms mixed)
   :errors @errors})

(println "BENCHROW" (json/generate-string row))

(doseq [^java.io.File f (reverse (file-seq scratch))]
  (.delete f))
