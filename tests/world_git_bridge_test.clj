#!/usr/bin/env bb

(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set])

(load-file "coord.clj")
(load-file "bridge/world_git.clj")

(def failures (atom 0))
(def total (atom 0))

(defn check
  ([label ok?] (check label ok? nil))
  ([label ok? detail]
   (swap! total inc)
   (println (str "  [" (if ok? "PASS" "FAIL") "] " label
                 (when (and (not ok?) detail) (str " <- " detail))))
   (when-not ok? (swap! failures inc))))

(defmacro bar [label & body]
  `(let [result# (try {:ok (boolean (do ~@body))}
                      (catch Throwable error#
                        {:error (or (ex-message error#) (str error#))}))]
     (check ~label (:ok result#) (:error result#))))

(def root (.getCanonicalPath (io/file ".")))
(def scratch
  (str (System/getProperty "java.io.tmpdir")
       "/fram-world-git-bridge-" (System/nanoTime)))
(.mkdirs (io/file scratch))

(def git-env
  {"GIT_AUTHOR_NAME" "W5 Corpus"
   "GIT_AUTHOR_EMAIL" "w5@example.invalid"
   "GIT_COMMITTER_NAME" "W5 Corpus"
   "GIT_COMMITTER_EMAIL" "w5@example.invalid"})

(defn git-result [repo & args]
  (apply proc/sh {:dir repo
                  :extra-env git-env
                  :out :string
                  :err :string}
         "git" args))

(defn git [repo & args]
  (clojure.string/trim (:out (apply git-result repo args))))

(defn write-bytes! [path values]
  (let [file (io/file path)]
    (.mkdirs (.getParentFile file))
    (java.nio.file.Files/write (.toPath file) (byte-array values)
                               (make-array java.nio.file.OpenOption 0))))

(defn git-commit! [repo message]
  (git repo "add" "-A")
  (git repo "commit" "-q" "-m" message)
  (git repo "rev-parse" "HEAD"))

(def corpus (str scratch "/corpus"))
(.mkdirs (io/file corpus))
(git corpus "init" "-q" "-b" "main")
(git corpus "config" "user.name" "W5 Corpus")
(git corpus "config" "user.email" "w5@example.invalid")

(spit (str corpus "/README.md") "world git bridge corpus\n")
(spit (str corpus "/run.sh") "#!/bin/sh\nprintf 'one\\n'\n")
(.setExecutable (io/file (str corpus "/run.sh")) true false)
(def c1 (git-commit! corpus "root files"))

(spit (str corpus "/README.md") "world git bridge corpus\nsecond line\n")
(let [file (io/file (str corpus "/src/nested.txt"))]
  (.mkdirs (.getParentFile file))
  (spit file "nested\n"))
(write-bytes! (str corpus "/data/binary.bin") [0 1 2 3 127 -128 -1])
(def c2 (git-commit! corpus "nested and binary"))

(git corpus "checkout" "-q" "-b" "feature")
(spit (str corpus "/feature.txt") "from feature\n")
(def c-feature (git-commit! corpus "feature slot"))

(git corpus "checkout" "-q" "main")
(spit (str corpus "/run.sh") "#!/bin/sh\nprintf 'main\\n'\n")
(.setExecutable (io/file (str corpus "/run.sh")) false false)
(def c-main (git-commit! corpus "main content and mode"))

(git corpus "merge" "-q" "--no-ff" "feature" "-m" "merge feature")
(def c-merge (git corpus "rev-parse" "HEAD"))
(git corpus "commit" "-q" "--allow-empty" "-m" "empty generation")
(def c-empty (git corpus "rev-parse" "HEAD"))

;; Exercise a real clone/object database, not an in-memory fixture.
(def cloned (str scratch "/cloned-corpus"))
(git scratch "clone" "-q" corpus cloned)
(git cloned "branch" "feature" "origin/feature")

(def log (str scratch "/worlds.log"))
(def co (coord/new-coord log))
(def imported
  (bridge.world-git/import-repo!
   co "w5" cloned {:world-prefix "corpus"}))
(def import-ok (:ok imported))

(println "world ⇄ git bridge — real object database, branches, merge, binary, modes")

(bar "import: the complete six-commit corpus becomes six Versions"
     (= 6 (count (:versions import-ok))))

(bar "import: the returned commit order contains every reachable commit"
     (= #{c1 c2 c-feature c-main c-merge c-empty}
        (set (:commits import-ok))))

(bar "import: both local Git branch tips become named world heads"
     (= #{"refs/heads/feature" "refs/heads/main"}
        (set (keys (:worlds import-ok)))))

(bar "import: each branch world head is exactly its tip commit's Version"
     (every?
      (fn [[ref world-name]]
        (= (get (:versions import-ok)
                (git cloned "rev-parse" ref))
           (coord/world-head co world-name)))
      (:worlds import-ok)))

(defn expected-world-manifest [repo commit]
  (->> (bridge.world-git/git-manifest repo commit)
       vals
       (map (fn [{:keys [slot mode git-oid]}]
              {:slot slot
               :mode mode
               :blob-id
               (fram.world/blob-id
                (:out (proc/sh {:dir repo :out :bytes :err :string}
                               "git" "cat-file" "blob" git-oid)))}))
       (sort-by :slot)
       vec))

(defn actual-world-manifest [version]
  (->> (coord/world-manifest co version)
       (map #(select-keys % [:slot :mode :blob-id]))
       (sort-by :slot)
       vec))

(bar "parity: every imported commit manifest matches Git path, mode, and bytes"
     (every?
      (fn [[commit version]]
        (= (expected-world-manifest cloned commit)
           (actual-world-manifest version)))
      (:versions import-ok)))

(bar "modes: executable and regular Git modes survive import exactly"
     (let [root-modes (into {} (map (juxt :slot :mode)
                                    (actual-world-manifest
                                     (get (:versions import-ok) c1))))
           main-modes (into {} (map (juxt :slot :mode)
                                    (actual-world-manifest
                                     (get (:versions import-ok) c-main))))]
       (and (= "100755" (get root-modes "run.sh"))
            (= "100644" (get main-modes "run.sh")))))

(defn changed-slots [before after]
  (let [slots (set/union (set (keys before)) (set (keys after)))]
    (set (filter #(not= (get before %) (get after %)) slots))))

(bar "sparsity: every Version overlay names only first-parent-changed slots"
     (every?
      (fn [commit]
        (let [parents (get (:parents import-ok) commit)
              parent (first parents)
              before (if parent
                       (bridge.world-git/git-manifest cloned parent)
                       {})
              after (bridge.world-git/git-manifest cloned commit)
              version (get (:versions import-ok) commit)
              overlay (:overlay (coord/world-version co version))]
          (= (changed-slots before after)
             (set (map :slot overlay)))))
      (:commits import-ok)))

(bar "linear-chain: main's five first-parent commits form five world Versions"
     (loop [version (get (:versions import-ok) c-empty)
            n 0]
       (if (= version (fram.world/version-id nil []))
         (= 5 n)
         (recur (:base (coord/world-version co version)) (inc n)))))

(bar "empty commit: a new Version is retained with an empty sparse overlay"
     (let [record (coord/world-version co (get (:versions import-ok) c-empty))]
       (and (= [] (:overlay record))
            (= (get (:versions import-ok) c-merge) (:base record)))))

(def rendered-a (str scratch "/rendered-a"))
(def rendered-b (str scratch "/rendered-b"))
(def main-version (get (:versions import-ok) c-empty))
(def render-a
  (bridge.world-git/render-version!
   co main-version rendered-a
   {:object-format (:object-format import-ok)}))
(def render-b
  (bridge.world-git/render-version!
   co main-version rendered-b
   {:object-format (:object-format import-ok)}))

(def source-tree (git cloned "rev-parse" "main^{tree}"))
(def rendered-tree (git rendered-a "rev-parse" "HEAD^{tree}"))

(bar "round-trip: source HEAD tree and rendered HEAD tree are identical"
     (= source-tree rendered-tree))

(bar "determinism: two independent renders produce the same tree and commit"
     (= (select-keys (:ok render-a) [:tree :commit])
        (select-keys (:ok render-b) [:tree :commit])))

(def head-before-format-reject (git rendered-a "rev-parse" "HEAD"))
(def format-reject
  (bridge.world-git/render-version!
   co main-version rendered-a {:object-format "sha256"}))

(bar "format: an existing repository rejects an explicit object-format mismatch"
     (and (= :git-object-format-mismatch (:reject format-reject))
          (= "sha256" (:requested format-reject))
          (= "sha1" (:actual format-reject))
          (= head-before-format-reject
             (git rendered-a "rev-parse" "HEAD"))))

(def replay-log (str scratch "/replay.log"))
(io/copy (io/file log) (io/file replay-log))
(def rendered-process (str scratch "/rendered-process"))
(def fresh-code
  (str
   "(load-file \"coord.clj\")"
   "(load-file \"bridge/world_git.clj\")"
   "(let [co {:store (coord/replay (System/getenv \"W5_LOG\"))"
   " :log nil :lock (Object.)}"
   " r (bridge.world-git/render-version!"
   " co (System/getenv \"W5_VERSION\") (System/getenv \"W5_OUT\")"
   " {:object-format (System/getenv \"W5_OBJECT_FORMAT\")})]"
   " (prn r))"))
(def fresh
  (proc/sh {:dir root
            :extra-env {"W5_LOG" replay-log
                        "W5_VERSION" main-version
                        "W5_OUT" rendered-process
                        "W5_OBJECT_FORMAT" (:object-format import-ok)}
            :out :string
            :err :string}
           "bb" "-cp" "out:." "-e" fresh-code))
(def fresh-render (edn/read-string (clojure.string/trim (:out fresh))))

(bar "determinism: replay in a fresh process reproduces tree and commit IDs"
     (= (select-keys (:ok render-a) [:tree :commit])
        (select-keys (:ok fresh-render) [:tree :commit])))

(bar "determinism: fresh-process Git rev-parse observes the same tree"
     (= source-tree (git rendered-process "rev-parse" "HEAD^{tree}")))

;; Kernel limit: preflight the whole repo, reject, and append no world bytes.
(def oversized (str scratch "/oversized"))
(.mkdirs (io/file oversized))
(git oversized "init" "-q" "-b" "main")
(git oversized "config" "user.name" "W5 Corpus")
(git oversized "config" "user.email" "w5@example.invalid")
(write-bytes! (str oversized "/too-big.bin")
              (repeat (inc fram.world/max-blob-bytes) 65))
(git-commit! oversized "oversized blob")
(def oversized-log (str scratch "/oversized-worlds.log"))
(def oversized-co (coord/new-coord oversized-log))
(def oversized-before (.length (io/file oversized-log)))
(def oversized-result
  (bridge.world-git/import-repo!
   oversized-co "w5" oversized {:world-prefix "oversized"}))

(bar "limit: a 524289-byte Git blob is rejected, never split"
     (and (= :world-blob-too-large (:reject oversized-result))
          (= 524289 (:bytes oversized-result))
          (= fram.world/max-blob-bytes (:max oversized-result))))

(bar "limit: oversized preflight appends zero bytes to the world log"
     (= oversized-before (.length (io/file oversized-log))))

(println (str "\n" (- @total @failures) "/" @total " passed"))
(when (pos? @failures)
  (System/exit 1))
