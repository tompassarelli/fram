(ns fram.program-inspection
  "Snapshot-pinned semantic reads over fram-code-on's resolved corpus."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security MessageDigest]))

(def named-tool-names
  ["read_definition" "find_references" "trace_impact" "occurrence_history"])

(def batch-tool-name "inspect_program")

(def program-tool-names (conj named-tool-names batch-tool-name))

(def ^:private operation-tags
  {"read_definition" "read-definition"
   "find_references" "find-references"
   "trace_impact" "trace-impact"
   "occurrence_history" "trace-impact"})

(defn program-tool? [tool]
  (contains? (set program-tool-names) tool))

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- bytes->hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- sha256 [bytes]
  (str "sha256:"
       (bytes->hex (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- finish-block [{:keys [current blocks] :as state}]
  (if current
    (assoc state :current nil :blocks (conj blocks current))
    state))

(defn- parse-corpus [text]
  (let [{:keys [blocks skipped]}
        (->> (str/split-lines text)
             (reduce
              (fn [{:keys [current] :as state} line]
                (cond
                  (str/starts-with? line "@file ")
                  (assoc (finish-block state)
                         :current {:file (subs line 6) :triples []})

                  (str/starts-with? line "[")
                  (if-not current
                    (fail! :invalid-program-corpus
                           "corpus fact appears before its @file anchor" {})
                    (try
                      (let [triple (edn/read-string line)]
                        (if (and (vector? triple) (= 3 (count triple)))
                          (update-in state [:current :triples] conj triple)
                          (fail! :invalid-program-corpus
                                 "corpus fact is not a three-slot vector"
                                 {:line line})))
                      (catch clojure.lang.ExceptionInfo error (throw error))
                      (catch Throwable _ (update state :skipped inc))))

                  :else state))
              {:current nil :blocks [] :skipped 0})
             finish-block)]
    (when (empty? blocks)
      (fail! :invalid-program-corpus
             "resolved corpus contains no @file blocks" {}))
    {:blocks blocks :skippedFacts skipped}))

(defn- module-of [file]
  (-> file io/file .getName (str/replace #"\.[^.]+$" "")))

(defn- node-sort-key [node]
  (if (integer? node) [0 node] [1 (pr-str node)]))

(defn- anchor [file node kind]
  {:file file :nodeId node :kind kind})

(defn- identity-of [[file node]]
  (str file "#" node))

(defn- index-by [predicate triples]
  (reduce (fn [result [slot0 slot1 slot2]]
            (if (= predicate slot1) (assoc result slot0 slot2) result))
          {} triples))

(defn- derive-block [{:keys [file triples]}]
  (let [kinds (index-by "form-kind" triples)
        names (index-by "name" triples)
        calls (index-by "calls" triples)
        children (reduce (fn [result [slot0 slot1 slot2]]
                           (if (= "child" slot1)
                             (update result slot0 (fnil conj []) slot2)
                             result))
                         {} triples)
        child-set (set (mapcat identity (vals children)))
        nodes (sort-by node-sort-key (keys kinds))
        roots (remove child-set nodes)
        definitions
        (mapv (fn [node]
                {:key [file node]
                 :identity (identity-of [file node])
                 :name (get names node)
                 :module (module-of file)
                 :file file
                 :node node
                 :anchor (anchor file node "definition")
                 :root-facts (mapv vec (filter #(= node (nth % 0)) triples))})
              (filter #(= "defn" (get kinds %)) nodes))
        mentions
        (loop [stack (vec (reverse (map #(vector % nil) roots)))
               result []]
          (if (empty? stack)
            result
            (let [[node owner] (peek stack)
                  stack* (pop stack)
                  owner* (if (= "defn" (get kinds node)) node owner)
                  result* (if (and owner* (= "call" (get kinds node))
                                   (contains? calls node))
                            (conj result {:caller-key [file owner*]
                                          :call-name (get calls node)
                                          :anchor (anchor file node "reference")})
                            result)
                  next-nodes (reverse (get children node []))]
              (recur (into stack* (map #(vector % owner*) next-nodes))
                     result*))))]
    {:definitions definitions :mentions mentions}))

(defn- resolve-call [by-name caller-key call-name]
  (let [candidates (vec (get by-name call-name []))
        caller-file (first caller-key)
        local (filterv #(= caller-file (first (:key %))) candidates)]
    (cond
      (= 1 (count local)) (:key (first local))
      (= 1 (count candidates)) (:key (first candidates))
      :else nil)))

(defn- build-program [blocks]
  (let [derived (mapv derive-block blocks)
        definitions (vec (mapcat :definitions derived))
        by-name (group-by :name definitions)
        references
        (->> derived
             (mapcat :mentions)
             (keep (fn [{:keys [caller-key call-name anchor]}]
                     (when-let [callee-key (resolve-call by-name caller-key call-name)]
                       {:caller-key caller-key
                        :callee-key callee-key
                        :anchor anchor})))
             vec)]
    {:definitions (into {} (map (juxt :identity identity) definitions))
     :definitions-by-key (into {} (map (juxt :key identity) definitions))
     :references references}))

(defn read-snapshot!
  "Read and parse one immutable corpus image. Its content digest is the logical version."
  [path]
  (let [file (io/file path)]
    (when-not (.isFile file)
      (fail! :program-corpus-unavailable
             "resolved program corpus is unavailable"
             {:path (.getCanonicalPath file)}))
    (let [bytes (Files/readAllBytes (.toPath file))
          parsed (parse-corpus (String. bytes StandardCharsets/UTF_8))]
      (merge {:logical-version (sha256 bytes)
              :version-kind "corpus-sha256"
              :path (.getCanonicalPath file)}
             parsed
             (build-program (:blocks parsed))))))

(defn- exact-map! [arguments allowed required label]
  (when-not (map? arguments)
    (fail! :invalid-tool-arguments (str label " arguments must be an object") {}))
  (let [actual (set (keys arguments))]
    (when-not (and (every? actual required) (every? allowed actual))
      (fail! :invalid-tool-arguments
             (str label " arguments have unknown or missing fields")
             {:allowed (sort allowed) :required (sort required)
              :actual (sort actual)})))
  arguments)

(defn- identity! [arguments]
  (let [identity (:semanticIdentity arguments)]
    (when-not (and (string? identity) (not (str/blank? identity)))
      (fail! :invalid-semantic-identity
             "semanticIdentity must be a nonblank exact corpus identity" {}))
    identity))

(defn- common [snapshot tool identity direction depth anchors]
  {:tool tool
   :operation (get operation-tags tool)
   :outcome "ok"
   :logicalVersion (:logical-version snapshot)
   :versionKind (:version-kind snapshot)
   :semanticIdentity identity
   :sourceAnchors (vec anchors)
   :direction direction
   :depth depth})

(defn- not-found [snapshot tool identity direction depth]
  (assoc (common snapshot tool identity direction depth [])
         :outcome "not-found"
         :message "semanticIdentity is absent from this corpus snapshot"))

(defn- definition! [snapshot identity]
  (get-in snapshot [:definitions identity]))

(defn- read-definition [snapshot arguments]
  (exact-map! arguments #{:semanticIdentity :name :file} #{}
              "read_definition")
  (let [identity-selector (:semanticIdentity arguments)
        name-selector (:name arguments)
        file-selector (:file arguments)
        _ (when-not (or (and (string? identity-selector)
                             (not (str/blank? identity-selector))
                             (nil? name-selector) (nil? file-selector))
                        (and (nil? identity-selector)
                             (string? name-selector)
                             (not (str/blank? name-selector))
                             (or (nil? file-selector)
                                 (and (string? file-selector)
                                      (not (str/blank? file-selector))))))
            (fail! :invalid-definition-selector
                   "read_definition requires semanticIdentity, or name with optional file"
                   {}))
        candidates (if identity-selector
                     (keep identity [(definition! snapshot identity-selector)])
                     (filterv #(and (= name-selector (:name %))
                                    (or (nil? file-selector)
                                        (= file-selector (:file %))))
                              (vals (:definitions snapshot))))
        _ (when (> (count candidates) 1)
            (fail! :ambiguous-definition
                   "definition name is ambiguous; add file or use semanticIdentity"
                   {:candidates (sort (map :identity candidates))}))
        definition (first candidates)
        identity (or (:identity definition) identity-selector)]
    (if-let [definition (definition! snapshot identity)]
      (assoc (common snapshot "read_definition" identity "self" 0
                     [(:anchor definition)])
             :definition (select-keys definition
                                      [:identity :name :module :root-facts]))
      (not-found snapshot "read_definition" identity "self" 0))))

(defn- direction! [arguments allowed]
  (let [direction (:direction arguments)]
    (when-not (contains? allowed direction)
      (fail! :invalid-direction
             (str "direction must be one of " (str/join ", " (sort allowed)))
             {:direction direction}))
    direction))

(defn- reference-view [snapshot seed direction reference]
  (let [related-key (if (= direction "inbound")
                      (:caller-key reference) (:callee-key reference))
        related (get-in snapshot [:definitions-by-key related-key])]
    {:semanticIdentity (:identity related)
     :referenceOf seed
     :ownerIdentity (identity-of (:caller-key reference))
     :relation "calls"
     :sourceAnchors [(:anchor reference)]
     :direction direction
     :depth 1}))

(defn- reference-sort-key [reference]
  (let [{:keys [file nodeId]} (first (:sourceAnchors reference))]
    [file (node-sort-key nodeId) (:direction reference)
     (:semanticIdentity reference)]))

(defn- references-for [snapshot identity direction]
  (let [definition (definition! snapshot identity)
        key (:key definition)
        inbound (when (contains? #{"inbound" "both"} direction)
                  (for [reference (:references snapshot)
                        :when (= key (:callee-key reference))]
                    (reference-view snapshot identity "inbound" reference)))
        outbound (when (contains? #{"outbound" "both"} direction)
                   (for [reference (:references snapshot)
                         :when (= key (:caller-key reference))]
                     (reference-view snapshot identity "outbound" reference)))]
    (vec (sort-by reference-sort-key (concat inbound outbound)))))

(defn- find-references [snapshot arguments]
  (exact-map! arguments #{:semanticIdentity :direction}
              #{:semanticIdentity :direction} "find_references")
  (let [identity (identity! arguments)
        direction (direction! arguments #{"inbound" "outbound" "both"})]
    (if-let [definition (definition! snapshot identity)]
      (let [references (references-for snapshot identity direction)]
        (assoc (common snapshot "find_references" identity direction 1
                       (mapcat :sourceAnchors references))
               :definitionAnchor (:anchor definition)
               :references references))
      (not-found snapshot "find_references" identity direction 1))))

(defn- adjacency [references direction]
  (reduce (fn [result reference]
            (let [[from to] (if (= direction "inbound")
                              [(:callee-key reference) (:caller-key reference)]
                              [(:caller-key reference) (:callee-key reference)])]
              (update result from (fnil conj #{}) to)))
          {} references))

(defn- trace-nodes [snapshot seed-key direction max-depth]
  (let [neighbors (adjacency (:references snapshot) direction)]
    (loop [frontier [[seed-key 0]] visited #{seed-key} result []]
      (if (empty? frontier)
        result
        (let [[node depth] (first frontier)
              remaining (subvec (vec frontier) 1)
              next-depth (inc depth)
              next-nodes (if (< depth max-depth)
                           (remove visited
                                   (sort-by identity-of (get neighbors node #{})))
                           [])
              visited* (into visited next-nodes)
              frontier* (into remaining (map #(vector % next-depth) next-nodes))
              result* (into result (map #(vector % next-depth) next-nodes))]
          (recur frontier* visited* result*))))))

(defn- trace-impact [snapshot arguments]
  (exact-map! arguments #{:semanticIdentity :direction :maxDepth}
              #{:semanticIdentity :direction} "trace_impact")
  (let [identity (identity! arguments)
        direction (direction! arguments #{"inbound" "outbound"})
        max-depth (get arguments :maxDepth 32)]
    (when-not (and (integer? max-depth) (<= 1 max-depth 64))
      (fail! :invalid-depth "maxDepth must be an integer from 1 through 64"
             {:maxDepth max-depth}))
    (if-let [definition (definition! snapshot identity)]
      (let [traced (trace-nodes snapshot (:key definition) direction max-depth)
            impacts (mapv (fn [[key depth]]
                            (let [target (get-in snapshot [:definitions-by-key key])]
                              {:semanticIdentity (:identity target)
                               :sourceAnchors [(:anchor target)]
                               :direction direction
                               :depth depth}))
                          traced)
            observed-depth (reduce max 0 (map :depth impacts))]
        (assoc (common snapshot "trace_impact" identity direction observed-depth
                       [(:anchor definition)])
               :maxDepth max-depth
               :impacts impacts))
      (not-found snapshot "trace_impact" identity direction 0))))

(defn- occurrence-history [snapshot arguments]
  (exact-map! arguments #{:semanticIdentity} #{:semanticIdentity}
              "occurrence_history")
  (let [identity (identity! arguments)]
    (if-let [definition (definition! snapshot identity)]
      (let [references (references-for snapshot identity "inbound")
            occurrences
            (vec
             (sort-by
              (fn [occurrence]
                (let [{:keys [file nodeId]} (:sourceAnchor occurrence)]
                  [file (node-sort-key nodeId) (:kind occurrence)]))
              (cons {:kind "definition"
                     :semanticIdentity identity
                     :ownerIdentity identity
                     :sourceAnchor (:anchor definition)
                     :direction "self"
                     :depth 0}
                    (map (fn [reference]
                           {:kind "reference"
                            :semanticIdentity identity
                            :ownerIdentity (:ownerIdentity reference)
                            :sourceAnchor (first (:sourceAnchors reference))
                            :direction "inbound"
                            :depth 1})
                         references))))]
        (assoc (common snapshot "occurrence_history" identity "inbound" 1
                       (map :sourceAnchor occurrences))
               :occurrenceScope "snapshot-source-order"
               :occurrences occurrences))
      (not-found snapshot "occurrence_history" identity "inbound" 1))))

(defn execute-named
  "Execute one named request against an already-pinned snapshot."
  [snapshot tool arguments]
  (case tool
    "read_definition" (read-definition snapshot arguments)
    "find_references" (find-references snapshot arguments)
    "trace_impact" (trace-impact snapshot arguments)
    "occurrence_history" (occurrence-history snapshot arguments)
    (fail! :unknown-program-tool (str "unknown program inspection tool: " tool)
           {:tool tool})))

(defn- child-error [snapshot tag request error]
  {:tag tag
   :request request
   :outcome "error"
   :logicalVersion (:logical-version snapshot)
   :versionKind (:version-kind snapshot)
   :error {:type (name (or (:type (ex-data error)) :program-inspection-error))
           :message (or (.getMessage error) (str (class error)))}})

(defn inspect-program
  "Execute ordered children against one immutable snapshot, preserving every tag."
  [snapshot arguments]
  (exact-map! arguments #{:requests} #{:requests} "inspect_program")
  (let [requests (:requests arguments)]
    (when-not (and (vector? requests) (<= 1 (count requests) 32))
      (fail! :invalid-inspection-batch
             "requests must be an array containing 1..32 named requests" {}))
    {:tool batch-tool-name
     :outcome "ok"
     :logicalVersion (:logical-version snapshot)
     :versionKind (:version-kind snapshot)
     :children
     (mapv
      (fn [child]
        (let [tag (when (map? child) (:tag child))
              request (when (map? child) (:request child))]
          (try
            (exact-map! child #{:tag :request :arguments}
                        #{:tag :request :arguments} "inspect_program child")
            (when-not (and (string? tag) (not (str/blank? tag)))
              (fail! :invalid-inspection-tag "each child tag must be nonblank" {}))
            (when-not (contains? (set named-tool-names) request)
              (fail! :invalid-inspection-request
                     "each child request must name a program inspection tool"
                     {:request request}))
            (merge {:tag tag :request request}
                   (execute-named snapshot request (:arguments child)))
            (catch Throwable error
              (child-error snapshot tag request error)))))
      requests)}))

(defn invoke-path!
  "Load one snapshot and execute either a named read or the typed batch."
  [path tool arguments]
  (let [snapshot (read-snapshot! path)]
    (if (= batch-tool-name tool)
      (inspect-program snapshot arguments)
      (execute-named snapshot tool arguments))))

(def ^:private identity-schema
  {:type "object" :additionalProperties false
   :properties {:semanticIdentity {:type "string" :minLength 1}}
   :required ["semanticIdentity"]})

(def ^:private definition-schema
  {:type "object"
   :oneOf
   [identity-schema
    {:type "object" :additionalProperties false
     :properties {:name {:type "string" :minLength 1}
                  :file {:type "string" :minLength 1}}
     :required ["name"]}]})

(def ^:private reference-schema
  {:type "object" :additionalProperties false
   :properties {:semanticIdentity {:type "string" :minLength 1}
                :direction {:type "string"
                            :enum ["inbound" "outbound" "both"]}}
   :required ["semanticIdentity" "direction"]})

(def ^:private impact-schema
  {:type "object" :additionalProperties false
   :properties {:semanticIdentity {:type "string" :minLength 1}
                :direction {:type "string" :enum ["inbound" "outbound"]}
                :maxDepth {:type "integer" :minimum 1 :maximum 64}}
   :required ["semanticIdentity" "direction"]})

(defn- batch-child-schema [request argument-schema]
  {:type "object" :additionalProperties false
   :properties {:tag {:type "string" :minLength 1}
                :request {:const request}
                :arguments argument-schema}
   :required ["tag" "request" "arguments"]})

(def tool-descriptors
  [{:name "read_definition"
    :description "Resolve a definition by exact semantic identity or by name plus optional file in the pinned corpus. Returns structural anchors and root facts; use text to read the rendered body."
    :inputSchema definition-schema}
   {:name "find_references"
    :description "Find direct scope-resolved inbound or outbound references for one exact semantic identity."
    :inputSchema reference-schema}
   {:name "trace_impact"
    :description "Trace transitive callers or callees from one exact semantic identity with bounded depth."
    :inputSchema impact-schema}
   {:name "occurrence_history"
    :description "List the definition and its resolved reference occurrences in deterministic source order for one corpus snapshot."
    :inputSchema identity-schema}
   {:name batch-tool-name
    :description "Execute 1..32 ordered named program reads against one immutable corpus version, preserving each child tag and outcome."
    :inputSchema
    {:type "object" :additionalProperties false
     :properties
     {:requests
      {:type "array" :minItems 1 :maxItems 32
       :items
       {:oneOf [(batch-child-schema "read_definition" definition-schema)
                (batch-child-schema "find_references" reference-schema)
                (batch-child-schema "trace_impact" impact-schema)
                (batch-child-schema "occurrence_history" identity-schema)]}}}
     :required ["requests"]}}])
