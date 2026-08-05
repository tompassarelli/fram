(ns bridge.world-git
  "Bidirectional projection between durable Fram worlds and Git objects.

  This adapter deliberately depends only on database.clj's public world verbs and
  Git plumbing. It does not materialize a checkout."
  (:require [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fram.world :as world])
  (:import [java.io ByteArrayInputStream]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.security MessageDigest]
           [java.text Normalizer Normalizer$Form]))

(def ^:private fixed-git-env
  {"GIT_AUTHOR_NAME" "Fram World Bridge"
   "GIT_AUTHOR_EMAIL" "world-bridge@fram.invalid"
   "GIT_AUTHOR_DATE" "1970-01-01T00:00:00Z"
   "GIT_COMMITTER_NAME" "Fram World Bridge"
   "GIT_COMMITTER_EMAIL" "world-bridge@fram.invalid"
   "GIT_COMMITTER_DATE" "1970-01-01T00:00:00Z"})

(defn- reject! [data]
  (throw (ex-info (str "world-git bridge rejected " (:reject data)) data)))

(defn- sha256 [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 255)) digest))))

(defn- strict-utf8 [^bytes raw context]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap raw)))
    (catch Throwable _
      (reject! (merge {:reject :git-path-not-utf8} context)))))

(defn- database-var [name]
  (or (ns-resolve 'database (symbol name))
      (ns-resolve 'user (symbol name))
      (reject! {:reject :world-api-unavailable :verb name})))

(defn- call-world [name & args]
  (apply (database-var name) args))

(defn- process!
  [opts & command]
  (let [result (apply proc/sh
                      (merge {:continue true :err :string} opts)
                      command)]
    (if (zero? (:exit result))
      result
      (reject! {:reject :git-command-failed
                :command (vec command)
                :exit (:exit result)
                :stderr (str/trim (str (:err result)))}))))

(defn- git-text [repo & args]
  (str/trim
   (:out (apply process! {:dir (str repo) :out :string} "git" args))))

(defn- git-bytes [repo & args]
  (:out (apply process! {:dir (str repo) :out :bytes} "git" args)))

(defn- git-input-text [repo input & args]
  (str/trim
   (:out (apply process!
                {:dir (str repo)
                 :in (ByteArrayInputStream.
                      (.getBytes (str input) StandardCharsets/UTF_8))
                 :out :string}
                "git" args))))

(defn- git-input-bytes [repo ^bytes input & args]
  (str/trim
   (:out (apply process!
                {:dir (str repo)
                 :in (ByteArrayInputStream. input)
                 :out :string}
                "git" args))))

(defn- init-repo! [repo object-format]
  (let [dir (io/file (str repo))]
    (.mkdirs dir)
    (let [probe (proc/sh {:dir (str repo)
                          :continue true
                          :out :string
                          :err :string}
                         "git" "rev-parse" "--git-dir")]
      (when-not (zero? (:exit probe))
        (apply process!
               {:out :string}
               "git" "init" "-q"
               (cond-> []
                 object-format
                 (conj (str "--object-format=" object-format))
                 true
                 (conj (str repo))))))
    (let [actual (git-text repo "rev-parse" "--show-object-format")]
      (when (and object-format (not= object-format actual))
        (reject! {:reject :git-object-format-mismatch
                  :requested object-format
                  :actual actual}))
      actual)))

(defn- parse-ls-tree-entry [commit entry]
  (let [[header slot] (str/split entry #"\t" 2)
        [mode type oid] (str/split header #" ")]
    (when (or (nil? slot) (nil? oid))
      (reject! {:reject :git-ls-tree-malformed :commit commit :entry entry}))
    (when-not (= "blob" type)
      (reject! {:reject :git-entry-unsupported
                :commit commit :slot slot :mode mode :type type}))
    (when-let [rejection (world/validate-slot slot)]
      (reject! (assoc rejection :commit commit :slot slot)))
    (when-let [rejection (world/validate-mode mode)]
      (reject! (assoc rejection :commit commit :slot slot :type type)))
    [slot {:slot slot :mode mode :git-oid oid}]))

(defn git-manifest
  "Read one Git commit as a slot-keyed manifest. Paths are decoded as strict
  UTF-8 and checked against the world kernel's slot and mode domain."
  [repo commit]
  (let [raw (git-bytes repo "ls-tree" "-r" "-z" "--full-tree" commit)
        text (strict-utf8 raw {:commit commit})
        entries (remove str/blank? (str/split text #"\u0000"))]
    (into (sorted-map)
          (map #(parse-ls-tree-entry commit %) entries))))

(defn- local-branches [repo]
  (let [out (git-text repo "for-each-ref"
                      "--sort=refname"
                      "--format=%(refname)\t%(objectname)"
                      "refs/heads")]
    (if (str/blank? out)
      {}
      (into (sorted-map)
            (map (fn [line]
                   (let [[ref oid] (str/split line #"\t" 2)]
                     [ref oid]))
                 (str/split-lines out))))))

(defn- commit-graph [repo]
  (let [out (git-text repo "rev-list" "--topo-order" "--reverse"
                      "--parents" "--all")]
    (when (str/blank? out)
      (reject! {:reject :git-empty}))
    (let [rows (mapv #(str/split % #" ") (str/split-lines out))
          order (mapv first rows)
          parents (into {} (map (fn [row] [(first row) (vec (rest row))]) rows))
          known (set order)]
      (doseq [[commit ps] parents
              parent ps
              :when (not (contains? known parent))]
        (reject! {:reject :git-history-incomplete
                  :commit commit :missing-parent parent}))
      {:order order :parents parents})))

(defn- preflight-repo [repo]
  (let [{:keys [order parents]} (commit-graph repo)
        branches (local-branches repo)
        manifests (into {} (map (fn [commit]
                                  [commit (git-manifest repo commit)])
                                order))
        locations
        (reduce
         (fn [acc [commit manifest]]
           (reduce (fn [m [slot {:keys [git-oid]}]]
                     (update m git-oid (fnil conj []) [commit slot]))
                   acc manifest))
         {} manifests)]
    (doseq [[oid [[commit slot]]] locations]
      (let [size (parse-long (git-text repo "cat-file" "-s" oid))]
        (when (> size world/max-blob-bytes)
          (reject! {:reject :world-blob-too-large
                    :bytes size
                    :max world/max-blob-bytes
                    :commit commit
                    :slot slot
                    :git-oid oid}))))
    (when (empty? branches)
      (reject! {:reject :git-no-local-branches}))
    {:order order
     :parents parents
     :branches branches
     :manifests manifests
     :object-format (git-text repo "rev-parse" "--show-object-format")}))

(defn- changed-ops! [co agent repo base-manifest manifest]
  (let [slots (sort (into #{} (concat (keys base-manifest) (keys manifest))))]
    (mapv
     (fn [slot]
       (let [before (get base-manifest slot)
             after (get manifest slot)]
         (cond
           (= before after) nil
           (nil? after) (world/delete-op slot)
           :else
           (let [raw (git-bytes repo "cat-file" "blob" (:git-oid after))
                 put (call-world "world-blob-put!" co agent raw)]
             (when (:reject put)
               (reject! (merge put {:slot slot :git-oid (:git-oid after)})))
             (world/put-op slot (:mode after) (:ok put))))))
     slots)))

(defn- checked-world! [result context]
  (when (:reject result)
    (reject! (merge result context)))
  result)

(defn- advance-world!
  [co agent cursor base-version commit object-format ops]
  (let [nonce (subs (sha256 (str "fram.git.import.v1\u0000" commit)) 0 32)
        candidate (:ok (checked-world!
                        (call-world "world-begin!"
                                    co agent cursor base-version nonce)
                        {:commit commit :world cursor}))
        _ (doseq [op (remove nil? ops)]
            (checked-world! (call-world "world-append!"
                                       co agent candidate op)
                            {:commit commit :world cursor}))
        version (:ok (checked-world!
                      (call-world "world-seal!" co agent candidate)
                      {:commit commit :world cursor}))
        spec {:adapter "git-import"
              :object-format object-format
              :git-commit commit}
        lock (:ok (checked-world!
                   (call-world "world-lock!" co version spec)
                   {:commit commit :world cursor}))
        receipt (:ok (checked-world!
                      (call-world "world-build!" co agent lock)
                      {:commit commit :world cursor}))]
    (checked-world!
     (call-world "world-promote!" co agent cursor base-version
                 candidate receipt)
     {:commit commit :world cursor})
    version))

(defn- safe-prefix [prefix]
  (let [normalized (Normalizer/normalize (str prefix) Normalizer$Form/NFC)]
    (when-let [rejection (world/validate-world-name normalized)]
      (reject! (assoc rejection :world-prefix prefix)))
    (when (> (world/byte-len normalized) 32)
      (reject! {:reject :world-prefix-too-long
                :bytes (world/byte-len normalized)
                :max 32}))
    normalized))

(defn- cursor-name [prefix import-id n]
  (str prefix "-stage-" import-id "-" n))

(defn- branch-name [prefix ref]
  (str prefix "-branch-" (subs (sha256 ref) 0 16)))

(defn- import-repo*
  [co agent repo {:keys [world-prefix] :or {world-prefix "git"}}]
  (let [prefix (safe-prefix world-prefix)
        {:keys [order parents branches manifests object-format]}
        (preflight-repo repo)
        import-id (subs (sha256
                         (str (git-text repo "rev-parse" "--git-dir")
                              "\u0000" (str/join "\u0000" order)))
                        0 10)
        root-version (world/version-id nil [])
        first-parent (into {} (map (fn [[commit ps]]
                                     [commit (first ps)])
                                   parents))
        children (reduce (fn [m commit]
                           (update m (get first-parent commit)
                                   (fnil conj []) commit))
                         {} order)
        cursor-count (atom 0)
        cursor-of (atom {})
        new-cursor (fn []
                     (cursor-name prefix import-id
                                  (swap! cursor-count inc)))
        roots (get children nil)
        versions (atom {})]
    (doseq [[i commit] (map-indexed vector roots)]
      (let [cursor (new-cursor)]
        (checked-world!
         (if (zero? i)
           (call-world "world-create!" co agent cursor root-version)
           (call-world "world-fork!" co agent cursor root-version))
         {:commit commit :world cursor})
        (swap! cursor-of assoc commit cursor)))
    (doseq [commit order]
      (let [parent (get first-parent commit)
            base-version (if parent
                           (or (get @versions parent)
                               (reject! {:reject :git-history-order
                                         :commit commit :parent parent}))
                           root-version)
            cursor (or (get @cursor-of commit)
                       (reject! {:reject :git-history-order
                                 :commit commit :parent parent}))
            base-manifest (if parent (get manifests parent) {})
            ops (changed-ops! co agent repo base-manifest
                              (get manifests commit))
            version (advance-world! co agent cursor base-version commit
                                    object-format ops)
            child-commits (get children commit)]
        (swap! versions assoc commit version)
        (doseq [[i child] (map-indexed vector child-commits)]
          (if (zero? i)
            (swap! cursor-of assoc child cursor)
            (let [fork (new-cursor)]
              (checked-world!
               (call-world "world-fork!" co agent fork version)
               {:commit child :world fork})
              (swap! cursor-of assoc child fork))))))
    (let [worlds
          (into
           (sorted-map)
           (map
            (fn [[ref commit]]
              (let [version (or (get @versions commit)
                                (reject! {:reject :git-branch-tip-unimported
                                          :ref ref :commit commit}))
                    name (branch-name prefix ref)]
                (checked-world!
                 (call-world "world-fork!" co agent name version)
                 {:ref ref :commit commit :world name})
                [ref name]))
            branches))]
      {:commits order
       :parents parents
       :versions @versions
       :worlds worlds
       :object-format object-format})))

(defn import-repo!
  "Import every commit reachable from local branches.

  Each Git commit becomes one sparse world Version over its first parent.
  Every local branch gets a deterministic world name whose head is its Git
  tip's Version. Expected projection rejections are returned as maps."
  ([co agent repo] (import-repo! co agent repo {}))
  ([co agent repo opts]
   (try
     {:ok (import-repo* co agent repo opts)}
     (catch clojure.lang.ExceptionInfo e
       (if (:reject (ex-data e))
         (ex-data e)
         (throw e))))))

(defn- add-trie-path [node segments entry full-slot]
  (let [segment (first segments)
        more (next segments)
        current (get node segment)]
    (if more
      (cond
        (= :blob (:kind current))
        (reject! {:reject :git-path-conflict :slot full-slot
                  :conflict segment})

        :else
        (assoc node segment
               {:kind :tree
                :children (add-trie-path
                           (or (:children current) {})
                           more entry full-slot)}))
      (cond
        current
        (reject! {:reject :git-path-conflict :slot full-slot
                  :conflict segment})

        :else
        (assoc node segment (assoc entry :kind :blob))))))

(defn- manifest-trie! [co repo manifest]
  (reduce
   (fn [trie {:keys [slot mode blob-id]}]
     (let [raw (call-world "world-blob" co blob-id)]
       (when (nil? raw)
         (reject! {:reject :world-blob-unknown
                   :slot slot :blob-id blob-id}))
       (let [oid (git-input-bytes repo raw "hash-object" "-w" "--stdin")]
         (add-trie-path trie (str/split slot #"/")
                        {:mode mode :oid oid} slot))))
   {} manifest))

(defn- write-tree! [repo trie]
  (let [rows
        (mapv
         (fn [[name entry]]
           (if (= :tree (:kind entry))
             (str "040000 tree "
                  (write-tree! repo (:children entry))
                  "\t" name "\u0000")
             (str (:mode entry) " blob " (:oid entry)
                  "\t" name "\u0000")))
         (sort-by key trie))]
    (git-input-text repo (apply str rows) "mktree" "-z")))

(defn- version-chain [co version-id]
  (let [root (world/version-id nil [])]
    (if (= version-id root)
      [{:version root :record nil}]
      (loop [current version-id
             acc []]
        (if (or (nil? current) (= current root))
          (vec (reverse acc))
          (let [record (call-world "world-version" co current)]
            (when (nil? record)
              (reject! {:reject :world-version-unknown
                        :version current}))
            (recur (:base record)
                   (conj acc {:version current :record record}))))))))

(defn- commit-tree! [repo tree parent version]
  (let [args (cond-> ["commit-tree" tree]
               parent (conj "-p" parent))
        result
        (apply process!
               {:dir (str repo)
                :extra-env fixed-git-env
                :in (ByteArrayInputStream.
                     (.getBytes (str "Fram world " version "\n")
                                StandardCharsets/UTF_8))
                :out :string}
               "git" args)]
    (str/trim (:out result))))

(defn- render-version*
  [co version-id repo {:keys [ref object-format]
                       :or {ref "refs/heads/main"
                            object-format nil}}]
  (let [actual-object-format (init-repo! repo object-format)
        chain (version-chain co version-id)
        rendered
        (reduce
         (fn [{:keys [commit] :as acc} {:keys [version]}]
           (let [manifest (if (= version (world/version-id nil []))
                            []
                            (call-world "world-manifest" co version))
                 trie (manifest-trie! co repo manifest)
                 tree (write-tree! repo trie)
                 next-commit (commit-tree! repo tree commit version)]
             (assoc acc :tree tree :commit next-commit)))
         {:commit nil :tree nil}
         chain)]
    (git-text repo "update-ref" ref (:commit rendered))
    (when (str/starts-with? ref "refs/heads/")
      (git-text repo "symbolic-ref" "HEAD" ref))
    (assoc rendered
           :version version-id
           :ref ref
           :object-format actual-object-format
           :versions (mapv :version chain))))

(defn render-version!
  "Write a world's base chain as deterministic Git trees and commits.

  Commit metadata and messages are fixed; a VersionId therefore produces the
  same tree and commit object in every process for a given Git object format."
  ([co version-id repo] (render-version! co version-id repo {}))
  ([co version-id repo opts]
   (try
     {:ok (render-version* co version-id repo opts)}
     (catch clojure.lang.ExceptionInfo e
       (if (:reject (ex-data e))
         (ex-data e)
         (throw e))))))
