;; The container and Nix package must carry every repo-root file the daemon
;; load-files; out/ is copied as one generated namespace closure.
(require '[clojure.set :as set]
         '[clojure.string :as str])

(def daemon-source
  (->> (str/split-lines (slurp "coord_daemon.clj"))
       (map #(str/replace % #";.*$" ""))
       (str/join "\n")))
(def docker-source (slurp "deploy/cloudflare/Dockerfile"))
(def flake-source (slurp "flake.nix"))
(def package-smoke-source (slurp "tests/package_daemon_smoke.sh"))

(def direct-loads
  (map second
       (re-seq #"\(load-file\s+\"([^\"]+)\"\)" daemon-source)))
(def cwd-loads
  (map second
       (re-seq #"\(load-file\s+\(str\s+\(System/getProperty\s+\"user.dir\"\)\s+\"/([^\"]+)\"\)\)"
               daemon-source)))
(def load-assets (set (concat direct-loads cwd-loads)))
(def root-assets
  (conj (set (remove #(str/starts-with? % "out/") load-assets))
        "coord_daemon.clj"))

(def docker-copy-sources
  (->> (str/split-lines docker-source)
       (remove #(str/includes? % "--from="))
       (keep #(second (re-matches #"\s*COPY\s+(.+?)\s*" %)))
       (mapcat #(drop-last (str/split % #"\s+")))
       set))
(def expected-docker-sources
  (set/union root-assets
             #{"deps.edn" "out/" "native/deps.edn" "native/build.sh"
               "native/reachability-metadata.json" "native/src/"}))

(def checks (atom []))
(defn chk [label ok detail]
  (swap! checks conj [label ok detail]))

(chk "daemon load-file closure is recognized"
     (= #{"coord.clj" "coord_writer_authority.clj"} load-assets)
     load-assets)
(chk "Docker source COPYs equal the Graal build closure"
     (= expected-docker-sources docker-copy-sources)
     {:expected expected-docker-sources :actual docker-copy-sources})
(chk "Nix package includes every root runtime asset"
     (every? #(str/includes? flake-source %) root-assets)
     root-assets)
(chk "installed-package smoke asserts every root runtime asset"
     (every? #(str/includes? package-smoke-source (str "$runtime/" %))
             root-assets)
     root-assets)

(let [failures (remove second @checks)]
  (doseq [[label ok detail] @checks]
    (println (if ok "  [PASS]" "  [FAIL]") label)
    (when-not ok (println "         " (pr-str detail))))
  (if (empty? failures)
    (println "\ncloudflare runtime closure:" (count @checks) "/" (count @checks) "PASS")
    (do
      (println "\ncloudflare runtime closure:" (count failures) "FAILED")
      (System/exit 1))))
