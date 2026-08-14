#!/usr/bin/env bb
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[framrpc :as wire]
         '[fram.store :as store]
         '[fram.types :as t]
         '[fri-port])

(import '[java.nio.file Files Path StandardOpenOption]
        '[java.security MessageDigest]
        '[java.util HexFormat])

(def default-corpus "tests/native_stage6_probe.tsv")
(def space-id "native-stage6")
(def hex (HexFormat/of))
(def encode-term (deref (ns-resolve 'fri-port 'encode-term-v1!)))
(def decode-term (deref (ns-resolve 'fri-port 'decode-term-v1!)))
(def encode-dump (deref (ns-resolve 'fri-port 'write-payload!)))

(defn fail! [message data]
  (throw (ex-info message data)))

(defn parse-line [line]
  (str/split line #"\t" -1))

(defn corpus-lines [path]
  (->> (str/split-lines (slurp path))
       (remove str/blank?)
       (mapv parse-line)))

(defn decode-hex [encoded]
  (try
    (.parseHex hex encoded)
    (catch IllegalArgumentException error
      (fail! "invalid corpus hex" {:encoded encoded :cause (.getMessage error)}))))

(defn decode-triple [encoded]
  (let [term (decode-term (decode-hex encoded))]
    (if (t/triple? term)
      term
      (fail! "operation corpus row is not a Triple" {:encoded encoded}))))

(defn projections [ctx]
  {:occurrences (store/occurrences ctx)
   :withdrawals (store/withdrawals ctx)
   :live-occurrences (store/live-occurrences ctx)
   :live-propositions (store/live-propositions ctx)
   :dump (store/dump-term-store ctx)})

(defn occurrence-term [occurrence]
  (wire/rpc-occurrence!
   (t/operationoccurrence-coordinate occurrence)
   (t/operationoccurrence-action occurrence)
   (t/operationoccurrence-proposition occurrence)))

(defn withdrawal-term [withdrawal]
  (wire/rpc-record!
   :rpc/withdrawal
   [(t/operationoccurrence-coordinate (t/withdrawal-retraction withdrawal))
    (t/operationoccurrence-coordinate (t/withdrawal-assertion withdrawal))]))

(defn reload! [ctx]
  (let [before (projections @ctx)
        reloaded (store/new-term-store space-id)]
    (store/load-term-store! reloaded (:dump before))
    (let [after (projections reloaded)]
      (when-not (= before after)
        (fail! "fresh TermStore reload changed an observation channel"
               {:before before :after after})))
    (reset! ctx reloaded)))

(defn execute! [lines]
  (let [ctx (atom (store/new-term-store space-id))
        reload-count (atom 0)
        operation-count (atom 0)]
    (doseq [[operation encoded & extra :as fields] lines]
      (case operation
        ("assert-term" "retract-term")
        (do
          (when (or (seq extra) (str/blank? encoded))
            (fail! "invalid operation corpus row" {:fields fields}))
          (store/commit-transaction!
           @ctx
           [(t/->CommitOperation
             (if (= operation "assert-term") t/assert-action t/retract-action)
             (decode-triple encoded))])
          (swap! operation-count inc))

        "dump-reload"
        (do
          (when (some? encoded)
            (fail! "dump-reload takes no fields" {:fields fields}))
          (reload! ctx)
          (swap! reload-count inc))

        ("malformed-term" "invalid-coordinate") nil
        (fail! "unknown probe corpus operation" {:fields fields})))
    (when-not (= 1 @reload-count)
      (fail! "probe corpus must contain one dump/reload seam"
             {:reload-count @reload-count}))
    {:ctx @ctx :operation-count @operation-count :reload-count @reload-count}))

(defn term-lines [terms]
  (.getBytes
   (apply str (map #(str (.formatHex hex (encode-term %)) "\n") terms))
   "UTF-8"))

(defn rejection-channels [lines]
  (let [malformed
        (for [[operation label encoded & extra :as fields] lines
              :when (= operation "malformed-term")]
          (do
            (when (or (seq extra) (str/blank? label) (str/blank? encoded))
              (fail! "invalid malformed-term row" {:fields fields}))
            (try
              (decode-term (decode-hex encoded))
              (fail! "malformed Term was accepted" {:label label})
              (catch clojure.lang.ExceptionInfo _
                (str label "\trejected\n")))))
        coordinates
        (for [[operation label encoded & extra :as fields] lines
              :when (= operation "invalid-coordinate")]
          (do
            (when (or (seq extra) (str/blank? label) (str/blank? encoded))
              (fail! "invalid invalid-coordinate row" {:fields fields}))
            (let [term (decode-term (decode-hex encoded))]
              (when (t/occurrence-coordinate? term)
                (fail! "invalid occurrence coordinate was accepted"
                       {:label label :term term})))
            (str label "\trejected\n")))]
    {:malformed-term.tsv (.getBytes (apply str malformed) "UTF-8")
     :invalid-coordinate.tsv (.getBytes (apply str coordinates) "UTF-8")}))

(defn sha256 [bytes]
  (.formatHex hex (.digest (doto (MessageDigest/getInstance "SHA-256")
                             (.update bytes)))))

(defn write-bytes! [directory name bytes]
  (Files/write (.resolve ^Path directory name)
               bytes
               (into-array StandardOpenOption
                           [StandardOpenOption/CREATE
                            StandardOpenOption/TRUNCATE_EXISTING
                            StandardOpenOption/WRITE])))

(defn write-observation! [output-dir lines ctx]
  (let [directory (Files/createDirectories (Path/of output-dir (make-array String 0))
                                           (make-array java.nio.file.attribute.FileAttribute 0))
        channels (projections ctx)
        artifacts
        (merge
         {:history.hex
          (term-lines
           (into (mapv occurrence-term (:occurrences channels))
                 (map withdrawal-term (:withdrawals channels))))
          :live-occurrences.hex
          (term-lines (mapv occurrence-term (:live-occurrences channels)))
          :live-propositions.hex (term-lines (:live-propositions channels))
          :term-store-dump.bin (encode-dump (:dump channels))}
         (rejection-channels lines))
        ordered (sort-by (comp name key) artifacts)]
    (doseq [[artifact-name bytes] ordered]
      (write-bytes! directory (name artifact-name) bytes))
    (write-bytes!
     directory
     "digests.tsv"
     (.getBytes
      (apply str
             (for [[artifact-name bytes] ordered]
               (str (name artifact-name) "\t" (alength bytes) "\t"
                    (sha256 bytes) "\n")))
      "UTF-8"))
    artifacts))

(defn assert-corpus-shape! [artifacts operation-count reload-count]
  (let [line-count #(count (str/split-lines (String. ^bytes % "UTF-8")))]
    (when-not (= {:operations 6
                  :reloads 1
                  :history 8
                  :live-occurrences 2
                  :live-propositions 2
                  :malformed 3
                  :invalid-coordinates 4}
                 {:operations operation-count
                  :reloads reload-count
                  :history (line-count (:history.hex artifacts))
                  :live-occurrences (line-count (:live-occurrences.hex artifacts))
                  :live-propositions (line-count (:live-propositions.hex artifacts))
                  :malformed (line-count (:malformed-term.tsv artifacts))
                  :invalid-coordinates (line-count (:invalid-coordinate.tsv artifacts))})
      (fail! "native Stage 6 corpus shape changed" {}))))

(let [[mode output-dir corpus & extra] *command-line-args*
      corpus (or corpus default-corpus)]
  (when (or (not= mode "jvm") (str/blank? output-dir) (seq extra))
    (fail! "usage: native_stage6_probe.clj jvm OUTPUT_DIR [CORPUS]" {}))
  (let [lines (corpus-lines corpus)
        {:keys [ctx operation-count reload-count]} (execute! lines)
        artifacts (write-observation! output-dir lines ctx)]
    (assert-corpus-shape! artifacts operation-count reload-count)
    (println (str "native Stage 6 JVM probe: " operation-count
                  " operations, dump/reload=" reload-count
                  ", history=8, live=2, rejection-matrix=7, artifacts="
                  (count artifacts)))))
