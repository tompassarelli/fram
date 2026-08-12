;; Structured typed bindings survive graph authoring, EDN projection, and the
;; canonical Beagle renderer. This is a scratch-only, no-socket regression.
(require '[fram.store :as c]
         '[fram.schema :as s]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.process :as proc])

(def root (System/getProperty "user.dir"))
(def beagle-home
  (or (System/getenv "BEAGLE_HOME")
      (str (System/getProperty "user.home") "/code/beagle/main")))
(def beagle-bin
  (or (System/getenv "FRAM_BEAGLE") (str beagle-home "/bin/beagle")))

(doseq [[path label] [[(str root "/out/resolve.clj") "resolve entrypoint"]
                      [beagle-bin "Beagle CLI"]]]
  (when-not (.exists (io/file path))
    (println "SKIP — missing prerequisite:" label "(" path ")")
    (System/exit 0)))

(load-file (str root "/out/resolve.clj"))

(def checks (atom []))
(defn check! [label value]
  (swap! checks conj [label (boolean value)]))

(def work
  (str (System/getProperty "java.io.tmpdir")
       "/structured-binding-roundtrip-"
       (System/nanoTime)))
(.mkdirs (io/file work))

;; The graph representation must retain one typed binding as one structured
;; list. Aggregate destructuring is likewise one parameter entry, even though
;; it introduces multiple local names.
(resolve/resolve-edn! []
  (fn []
    (let [named (resolve/mint-datum! "demo" '(who String))
          named-kids (resolve/ordered-children named)
          aggregate (resolve/mint-datum!
                     "demo"
                     '([left right] (HVec Int Int)))
          aggregate-kids (resolve/ordered-children aggregate)
          config (resolve/mint-datum!
                  "demo"
                  '({:keys [host port]} Config))
          config-kids (resolve/ordered-children config)]
      (check! "named typed binding is a two-child list"
              (and (= "list" (resolve/kind-of named))
                   (= ["who" "String"]
                      (mapv resolve/sym-val named-kids))))
      (check! "typed destructuring remains one binding plus one type"
              (and (= 2 (count aggregate-kids))
                   (= "#%brackets"
                      (resolve/head-sym (first aggregate-kids)))
                   (= "HVec"
                      (resolve/head-sym (second aggregate-kids)))))
      (check! "nominal Config types one map destructuring parameter"
              (and (= 2 (count config-kids))
                   (= "#%map" (resolve/head-sym (first config-kids)))
                   (= "Config" (resolve/sym-val (second config-kids))))))))

(def seed (str work "/demo.bclj"))
(spit seed "#lang beagle/clj\n(ns demo)\n(def seed-marker Int 0)\n")
(def seed-edn (str work "/demo.edn"))
(def emit-result
  (proc/sh {:out :string :err :string}
           beagle-bin "facts-roundtrip" "--emit-edn" seed))

(if-not (zero? (:exit emit-result))
  (check! "seed module emits as facts" false)
  (do
    (spit seed-edn (:out emit-result))
    (def rendered-edn (str work "/rendered.edn"))
    (resolve/resolve-edn! [seed-edn]
      (fn []
        (binding [resolve/*reject!*
                  (fn [code]
                    (throw (ex-info (str "verb rejected " code) {})))]
          (resolve/verb-upsert-form! "demo" '(def base String "Howdy"))
          (resolve/verb-upsert-form!
           "demo"
           '(defn greet [(who String)] String (str base ", " who "!"))))
        (resolve/extract-file! (first resolve/srcs) rendered-edn)))
    (let [facts (slurp rendered-edn)
          render-result (proc/sh {:out :string :err :string}
                                 beagle-bin "facts-roundtrip" "--render"
                                 rendered-edn)
          rendered (:out render-result)
          rendered-source (str work "/rendered.bclj")]
      (spit rendered-source rendered)
      (check! "rendered facts contain no retired annotation marker leaves"
              (and (not (str/includes? facts "\"v\" \":-\""))
                   (not (str/includes? facts "\"v\" \"->\""))))
      (check! "certified renderer accepts the authored graph"
              (zero? (:exit render-result)))
      (check! "rendered source preserves the structured typed binding"
              (str/includes? rendered "(who String)"))
      (check! "rendered source preserves the positional return type"
              (str/includes? rendered "] String"))
      (let [syntax-result (proc/sh {:out :string :err :string}
                                   beagle-bin "syntax" rendered-source)]
        (check! "rendered source passes Beagle syntax"
                (zero? (:exit syntax-result)))))))

(println "\n=== structured binding graph round-trip ===")
(let [failures (remove second @checks)]
  (doseq [[label ok?] @checks]
    (println (if ok? "  [PASS] " "  [FAIL] ") label))
  (if (empty? failures)
    (do
      (println "\nPASS —" (count @checks) "/" (count @checks))
      (System/exit 0))
    (do
      (println "\nFAIL —" (count failures) "of" (count @checks))
      (System/exit 1))))
