;; bb -cp out tests/coherence_doctor_test.clj
(require '[babashka.process :as proc]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root (System/getProperty "user.dir"))
(def scratch (str (System/getProperty "java.io.tmpdir") "/fram-coherence-" (System/nanoTime)))
(.mkdirs (io/file scratch))
(def checks (atom []))
(defn check [label result] (swap! checks conj [label (boolean result)]))
(defn sha256 [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
    (apply str (map #(format "%02x" %) (.digest digest)))))
(defn fingerprint [path] [(sha256 path) (.length (io/file path))])
(defn write-log! [name records]
  (let [path (str scratch "/" name ".log")]
    (spit path (str (str/join "\n" (map pr-str records)) "\n"))
    path))
(defn doctor [path]
  (proc/shell {:out :string :err :string :dir root :continue true}
              "bin/fram-coherence-doctor" path))

;; Raw retraction removes the definition spelling while the reference survives.
(let [log (write-log! "dangling"
                      [{:tx 1 :cid 101 :op "assert" :l "@use" :p "v" :r "target"}
                       {:tx 2 :cid 102 :op "assert" :l "@use" :p "bound_to" :r "@def"}
                       {:tx 3 :cid 103 :op "assert" :l "@def" :p "v" :r "target"}
                       {:tx 4 :cid 104 :op "retract" :l "@def" :p "v" :r "target"}])
      before (fingerprint log) result (doctor log) after (fingerprint log)]
  (check "raw retract names the dangling referenced binding"
         (and (= 1 (:exit result)) (str/includes? (:out result) "type=dangling-reference")
              (str/includes? (:out result) "spelling=\"target\"") (str/includes? (:out result) "target=\"@def\"")))
  (check "coherence report is a pure read (sha256 + byte length unchanged)" (= before after)))

(let [log (write-log! "rivals"
                      [{:tx 1 :cid 201 :op "assert" :l "@node" :p "v" :r "left"}
                       {:tx 2 :cid 202 :op "assert" :l "@node" :p "v" :r "right"}])
      result (doctor log)]
  (check "undeclared take-first rivals report both cids"
         (and (= 1 (:exit result)) (str/includes? (:out result) "type=undeclared-take-first-rival")
              (str/includes? (:out result) "201") (str/includes? (:out result) "202"))))

(let [log (write-log! "world"
                      [{:tx 1 :cid 301 :op "assert" :l "world:main" :p "world.head" :r "v-missing"}])
      result (doctor log)]
  (check "world head with no live version record is named unresolvable"
         (and (= 1 (:exit result)) (str/includes? (:out result) "type=world-unresolvable")
              (str/includes? (:out result) "world=\"main\""))))

(let [log (write-log! "clean"
                      [{:tx 1 :cid 401 :op "assert" :l "@v" :p "cardinality" :r "single"}
                       {:tx 2 :cid 402 :op "assert" :l "@def" :p "v" :r "target"}
                       {:tx 3 :cid 403 :op "assert" :l "@use" :p "v" :r "target"}
                       {:tx 4 :cid 404 :op "assert" :l "@use" :p "bound_to" :r "@def"}
                       {:tx 5 :cid 405 :op "assert" :l "world.version:v1" :p "world.record" :r "{}"}
                       {:tx 6 :cid 406 :op "assert" :l "world:main" :p "world.head" :r "v1"}])
      result (doctor log)]
  (check "clean corpus reports zero findings and exits zero"
         (and (= 0 (:exit result)) (= "coherence findings=0\n" (:out result)))))

(let [failed (filter (comp not second) @checks)]
  (doseq [[label ok] @checks] (println (if ok "[PASS]" "[FAIL]") label))
  (println (str "coherence-doctor: " (- (count @checks) (count failed)) "/" (count @checks) " PASS"))
  (when (seq failed) (System/exit 1)))
