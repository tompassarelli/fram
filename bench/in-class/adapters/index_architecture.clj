;; Discriminating index-architecture adapter.
;;
;; Candidate A keeps live fact ids in three Store-native integer hash-prefix
;; tries. Candidate B queries the production immutable sorted mmap rotations.
;; This file is benchmark-only: it neither changes Store nor server routing.
(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[fram.store :as store])
(load-file "rotations.clj")

(def engine-name (or (first *command-line-args*) "store-id-hash"))
(def corpus-size (Long/parseLong (or (second *command-line-args*) "3000")))
(def run-id (Long/parseLong (or (nth *command-line-args* 2 nil) "1")))
(def contract-path (or (nth *command-line-args* 3 nil)
                       "bench/in-class/scenario-contract.edn"))
(def contract (-> contract-path slurp edn/read-string :index-architecture))
(def contract-version (:contract-version contract))

(when-not (contains? (set (:engines contract)) engine-name)
  (throw (ex-info "unknown index engine" {:engine engine-name})))
(when-not (or (and (pos? corpus-size) (zero? (mod corpus-size 3)))
              (= corpus-size (:rotation-corpus-size contract)))
  (throw (ex-info "invalid index corpus size" {:corpus-triples corpus-size})))

(defn sh-line [& args]
  (-> (apply shell/sh args) :out str/trim))

(def process-pid
  (Long/parseLong (sh-line "bash" "-c" "printf %s \"$PPID\"")))

(defn proc-field [field]
  (some->> (str/split-lines
            (sh-line "cat" (str "/proc/" process-pid "/status")))
           (some #(when (str/starts-with? % (str field ":")) %))
           (re-find #"[0-9]+")
           Long/parseLong))

(defn mem-total-kb []
  (some->> (str/split-lines (sh-line "cat" "/proc/meminfo"))
           (some #(when (str/starts-with? % "MemTotal:") %))
           (re-find #"[0-9]+")
           Long/parseLong))

(defn heap-used []
  (let [rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(defn measured [f]
  (let [t0 (System/nanoTime)
        value (f)]
    {:ms (/ (- (System/nanoTime) t0) 1e6) :value value}))

(defn corpus-triple [tx]
  (if (= tx 350701)
    ["@rotation-extra" "benchmark_pad" "rotation-outage"]
    (let [subject (quot (dec tx) 3)
          slot (mod (dec tx) 3)
          outage? (= corpus-size (:rotation-corpus-size contract))]
      (case slot
        0 [(str "@corpus-" subject) "kind"
           (if (< subject 32) "agent" "thread")]
        1 [(str "@corpus-" subject) "title" (str "title-" subject)]
        2 (if (and outage? (< subject 1623))
            [(str "@corpus-" subject) "lead" "@tom_passarelli"]
            [(str "@corpus-" subject) "owner"
             (str "@owner-" (mod subject 32))])))))

(defn corpus []
  (map corpus-triple (range 1 (inc corpus-size))))

;; Hash-prefix tries post fact ids only at leaves. A prefix probe follows one
;; hash path, then visits exactly the leaves needed to return K matching facts.
(defn trie-add [trie [a b c] cid]
  (update-in trie [a b c] (fnil conj []) cid))

(defn build-hash-engine []
  (let [ctx (store/new-store)
        tx (store/begin-tx! ctx "bench/in-class")
        index
        (reduce
         (fn [idx [l p r]]
           (let [li (store/value! ctx l)
                 pi (store/value! ctx p)
                 ri (store/value! ctx r)
                 cid (store/fact! ctx li pi ri tx)]
             (-> idx
                 (update :spo trie-add [li pi ri] cid)
                 (update :pos trie-add [pi ri li] cid)
                 (update :osp trie-add [ri li pi] cid))))
         {:spo {} :pos {} :osp {}}
         (corpus))]
    {:kind :store-id-hash :store ctx :index index}))

(defn scratch-dir []
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-index-architecture-"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(def scratch (atom nil))

(defn build-mmap-engine []
  (let [dir (scratch-dir)
        root (.getPath (io/file dir "rotation-index"))
        metadata {:watermark corpus-size
                  :byte-offset corpus-size
                  :fold-fingerprint "bench/in-class/index-architecture"
                  :log-identity (str "deterministic-" corpus-size)}
        _ (reset! scratch dir)
        _ (rotations/write-set! root (corpus) metadata)
        opened (rotations/open-set
                root
                (select-keys metadata [:fold-fingerprint :log-identity]))]
    (when-not opened
      (throw (ex-info "mmap rotation set did not reopen" {:root root})))
    {:kind :mmap-rotations :opened opened}))

(defn value-id-mmap [opened value]
  (let [values (:values opened)]
    (loop [lo 1 hi (count values)]
      (when (< lo hi)
        (let [mid (quot (+ lo hi) 2)
              c (compare (nth values mid) value)]
          (cond
            (neg? c) (recur (inc mid) hi)
            (pos? c) (recur lo mid)
            :else mid))))))

(defn value-id [engine value]
  (case (:kind engine)
    :store-id-hash (store/value-id (:store engine) value)
    :mmap-rotations (value-id-mmap (:opened engine) value)))

(defn bound-id [engine value]
  (if (integer? value) value (value-id engine value)))

(defn choose-prefix [[s p o]]
  (cond
    (and s p o) [:spo [s p o]]
    (and s p) [:spo [s p]]
    (and p o) [:pos [p o]]
    (and o s) [:osp [o s]]
    s [:spo [s]]
    p [:pos [p]]
    o [:osp [o]]
    :else [:spo []]))

(defn leaf-cids [node]
  (cond
    (nil? node) ()
    (vector? node) node
    :else (mapcat leaf-cids (vals node))))

(defn hash-probe-cids [engine pattern]
  (let [[rotation prefix] (choose-prefix pattern)
        ids (mapv #(when % (bound-id engine %)) prefix)]
    (if (some nil? ids)
      ()
      (leaf-cids (get-in (:index engine) (into [rotation] ids))))))

(def header-bytes 20)
(def row-bytes 24)

(defn row-long [segment row pos]
  (.getLong ^java.nio.ByteBuffer
            (:buf segment)
            (int (+ header-bytes (* row-bytes row) (* 8 pos)))))

(defn compare-row-prefix [segment row prefix]
  (loop [pos 0]
    (if (= pos (count prefix))
      0
      (let [c (compare (row-long segment row pos) (long (nth prefix pos)))]
        (if (zero? c) (recur (inc pos)) c)))))

(defn lower-bound [segment prefix]
  (loop [lo 0 hi (long (:count segment))]
    (if (>= lo hi)
      lo
      (let [mid (quot (+ lo hi) 2)]
        (if (neg? (compare-row-prefix segment mid prefix))
          (recur (inc mid) hi)
          (recur lo mid))))))

(def inverse-permutation
  {:spo [0 1 2]
   :pos [2 0 1]
   :osp [1 2 0]})

(defn mmap-row-triple [engine rotation row]
  (let [segment (get-in engine [:opened :segments rotation])
        rotated [(row-long segment row 0)
                 (row-long segment row 1)
                 (row-long segment row 2)]
        inverse (get inverse-permutation rotation)]
    (mapv #(nth rotated %) inverse)))

(defn mmap-probe-triples [engine pattern]
  (let [[rotation prefix] (choose-prefix pattern)
        ids (mapv #(when % (bound-id engine %)) prefix)]
    (if (some nil? ids)
      ()
      (let [segment (get-in engine [:opened :segments rotation])
            start (lower-bound segment ids)
            total (long (:count segment))]
        (map #(mmap-row-triple engine rotation %)
             (take-while #(zero? (compare-row-prefix segment % ids))
                         (range start total)))))))

(defn probe-triples [engine pattern]
  (case (:kind engine)
    :store-id-hash
    (map (fn [cid]
           (let [{:keys [l p r]} (store/fact-of (:store engine) cid)]
             [l p r]))
         (hash-probe-cids engine pattern))
    :mmap-rotations
    (mmap-probe-triples engine pattern)))

(defn index-storage-bytes [engine]
  (if (= :mmap-rotations (:kind engine))
    (let [manifest (get-in engine [:opened :manifest])]
      (+ (long (get-in manifest [:dictionary :bytes]))
         (reduce + (map #(long (:bytes %)) (vals (:segments manifest))))))
    0))

(defn scenario-applies? [scenario]
  (let [sizes (:corpus-sizes scenario)]
    (if (= sizes :default)
      (contains? (set (:default-corpus-sizes contract)) corpus-size)
      (contains? (set sizes) corpus-size))))

(defn server-aggregate-scan [engine _scenario]
  {:result-count (count (probe-triples engine [nil "title" nil]))
   :expected-count-fn #(quot % 3)})

(defn staffing-projection [engine _scenario]
  (let [selected (map first (probe-triples engine [nil "kind" "agent"]))
        projected (reduce + (map #(count (probe-triples engine [% nil nil]))
                                 selected))]
    {:result-count projected
     :selected-subjects (count selected)
     :expected-count-fn #(* 3 (min 32 (quot % 3)))}))

(defn point-lookup [engine _scenario]
  {:result-count (count (probe-triples engine ["@corpus-0" "title" nil]))
   :expected-count-fn (constantly 1)})

(defn compound-datalog-join [engine _scenario]
  (let [candidates (map first
                        (probe-triples engine [nil "title" "title-0"]))
        matches
        (filter
         (fn [subject]
           (and (seq (probe-triples engine [subject "kind" "agent"]))
                (seq (probe-triples engine
                                    [subject "owner" "@owner-0"]))))
         candidates)]
    {:result-count (count matches)
     :expected-count-fn (constantly 1)}))

(defn rotation-outage-350701 [engine scenario]
  (let [lead-subjects (map first (probe-triples engine [nil "lead" nil]))
        joined (reduce
                +
                (map #(count (probe-triples engine [% "title" nil]))
                     lead-subjects))]
    (merge
     {:result-count joined
      :expected-count-fn (constantly 1623)}
     (:receipt-fields scenario))))

;; Scenario handlers are added one coherent scenario at a time.
(def scenario-handlers
  {:server-aggregate-scan server-aggregate-scan
   :staffing-projection staffing-projection
   :point-lookup point-lookup
   :compound-datalog-join compound-datalog-join
   :rotation-outage-350701 rotation-outage-350701})

(def started-utc (str (java.time.Instant/now)))
(def load-start (sh-line "cat" "/proc/loadavg"))
(def rss-before-kb (proc-field "VmRSS"))
(def build-result
  (measured #(case engine-name
               "store-id-hash" (build-hash-engine)
               "mmap-rotations" (build-mmap-engine))))
(def engine (:value build-result))

;; Record retained state after construction garbage is made collectible. This is
;; directional shared-host evidence, not an object-layout oracle.
(System/gc)
(Thread/sleep 50)
(def rss-retained-kb (proc-field "VmRSS"))
(def heap-retained-bytes (heap-used))

(def applicable (filter scenario-applies? (:scenarios contract)))
(when (empty? applicable)
  (throw (ex-info "no index scenarios apply" {:corpus-triples corpus-size})))

(def observations
  (mapv
   (fn [scenario]
     (let [handler (get scenario-handlers (:id scenario))]
       (when-not handler
         (throw (ex-info "scenario has no handler" {:scenario (:id scenario)})))
       (let [{:keys [ms value]} (measured #(handler engine scenario))
             expected ((:expected-count-fn value) corpus-size)
             actual (:result-count value)
             errors (if (= expected actual) 0 1)]
         {:scenario scenario :query-ms ms :result-count actual
          :expected-count expected :errors errors
          :details (dissoc value :result-count :expected-count-fn)})))
   applicable))

(def load-end (sh-line "cat" "/proc/loadavg"))
(def revision (sh-line "git" "rev-parse" "HEAD"))
(def cpu-model
  (if-let [line (some #(when (str/starts-with? % "Model name:") %)
                      (str/split-lines (sh-line "lscpu")))]
    (str/trim (subs line (inc (.indexOf line ":"))))
    "unknown"))
(def common
  {:receipt-kind "index-architecture"
   :contract-version contract-version
   :engine engine-name
   :run run-id
   :corpus-triples corpus-size
   :prepare-ms (:ms build-result)
   :rss-before-kb rss-before-kb
   :rss-retained-kb rss-retained-kb
   :heap-retained-bytes heap-retained-bytes
   :storage-bytes (index-storage-bytes engine)
   :started-utc started-utc
   :revision revision
   :kernel (sh-line "uname" "-srmo")
   :nproc (Long/parseLong (sh-line "nproc"))
   :cpu-model cpu-model
   :mem-total-kb (mem-total-kb)
   :load-start load-start
   :load-end load-end
   :java-version (System/getProperty "java.version")
   :babashka-version (sh-line "bb" "--version")})

(doseq [{:keys [scenario query-ms result-count expected-count errors details]}
        observations]
  (println
   "BENCHROW"
   (json/generate-string
    (merge common
           {:scenario (name (:id scenario))
            :decision-section (:decision-section scenario)
            :query-class (name (:query-class scenario))
            :query-ms query-ms
            :result-count result-count
            :expected-count expected-count
            :errors errors}
           details))))

(when (= :mmap-rotations (:kind engine))
  (rotations/close-set! (:opened engine)))
(when-let [^java.io.File dir @scratch]
  (doseq [^java.io.File f (reverse (file-seq dir))]
    (.delete f)))
