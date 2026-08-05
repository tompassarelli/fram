;; The container and Nix package must carry every repo-root file the server
;; load-files; out/ is copied as one generated namespace closure.
(require '[clojure.set :as set]
         '[clojure.string :as str])

(def server-source
  (->> (str/split-lines (slurp "server.clj"))
       (map #(str/replace % #";.*$" ""))
       (str/join "\n")))
(def docker-source (slurp "deploy/cloudflare/Dockerfile"))
(def native-docker-source (slurp "deploy/cloudflare/Dockerfile.native"))
(def native-image-builder-source (slurp "bin/fram-cloudflare-native-image"))
(def flake-source (slurp "flake.nix"))
(def package-smoke-source (slurp "tests/package_daemon_smoke.sh"))

(def direct-loads
  (map second
       (re-seq #"\(load-file\s+\"([^\"]+)\"\)" server-source)))
(def cwd-loads
  (map second
       (re-seq #"\(load-file\s+\(str\s+\(System/getProperty\s+\"user.dir\"\)\s+\"/([^\"]+)\"\)\)"
               server-source)))
(def load-assets (set (concat direct-loads cwd-loads)))
(def root-assets
  (conj (set (remove #(str/starts-with? % "out/") load-assets))
        "server.clj"))

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

(chk "server load-file closure is recognized"
     (= #{"database.clj" "writer_authority.clj"} load-assets)
     load-assets)
(chk "Docker source COPYs equal the Graal build closure"
     (= expected-docker-sources docker-copy-sources)
     {:expected expected-docker-sources :actual docker-copy-sources})
(chk "native Cloudflare image accepts only a matching static READY artifact"
     (and (str/includes? native-docker-source "FROM scratch")
          (str/includes? native-docker-source "FRAM_NATIVE_ARTIFACT_HASH")
          (str/includes? native-docker-source "fram-native-build/v1")
          (str/includes? native-docker-source "COPY READY")
          (str/includes? native-image-builder-source "artifact directory name is not a content hash")
          (str/includes? native-image-builder-source "artifact READY receipt does not match")
          (str/includes? native-image-builder-source "dynamically linked")
          (not (str/includes? native-docker-source "native/build.sh"))
          (not (str/includes? native-docker-source "graalvm"))
          (not (str/includes? native-docker-source "clojure:")))
     nil)
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
