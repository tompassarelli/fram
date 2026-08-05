;; In-process proof for bounded read-at-version query paging.
;; Run: bb -cp out tests/snapshot_query_page_test.clj
(require '[clojure.java.io :as io])
(binding [*command-line-args* []]
  (load-file "server.clj"))

(def checks (atom []))
(defn check! [label value]
  (swap! checks conj [label (boolean value)]))

(def page-q
  {:find "page-row"
   :rules [{:head {:rel "page-row" :args [{:var "x"}]}
            :body [{:rel "fact"
                    :args [{:var "x"} "page" "yes"]}]}]})

(defn page-request [limit after at-version]
  (cond-> {:op :query-page :query page-q :limit limit :after after}
    (some? at-version) (assoc :at-version at-version)))

(defn drain-pages [limit]
  (let [first-page (handle (page-request limit nil nil))
        pin (:version first-page)]
    (loop [page first-page rows []]
      (let [rows' (into rows (:ok page))]
        (if-let [after (:next page)]
          (recur (handle (page-request limit after pin)) rows')
          {:version pin :rows rows'})))))

(let [tmp-dir (.toFile
               (java.nio.file.Files/createTempDirectory
                "fram-query-page-snapshot"
                (make-array java.nio.file.attribute.FileAttribute 0)))
      log-path (.getCanonicalPath (io/file tmp-dir "facts.log"))]
  (spit log-path "")
  (with-redefs [query-page-snapshot-limit 1]
    (reset! telemetry-log nil)
    (boot-flat! log-path)
    (doseq [subject ["@a" "@b" "@c"]]
      (handle {:op :assert :te subject :p "page" :r "yes"}))

    (let [first-page (handle (page-request 1 nil nil))
          pinned-version (:version first-page)
          _ (handle {:op :assert :te "@late" :p "page" :r "yes"})
          pinned-rows
          (loop [page first-page rows []]
            (let [rows' (into rows (:ok page))]
              (if-let [after (:next page)]
                (recur (handle (page-request 1 after pinned-version)) rows')
                rows')))
          fresh (drain-pages 2)
          expired (handle (page-request 1 (:next first-page) pinned-version))
          envelope (get-in (handle {:op :status})
                           [:envelope :query-page-snapshots])]
      (println "pinned drain:" (pr-str {:version pinned-version :rows pinned-rows}))
      (println "fresh drain:" (pr-str fresh))
      (println "expired response:" (pr-str expired))
      (println "snapshot envelope:" (pr-str envelope))

      (check! "pinned drain excludes interleaved write"
              (and (= [["@a"] ["@b"] ["@c"]] pinned-rows)
                   (not (some #{["@late"]} pinned-rows))))
      (check! "fresh unpinned drain includes interleaved write"
              (some #{["@late"]} (:rows fresh)))
      (check! "evicted pin returns typed snapshot-expired rejection"
              (and (= :snapshot-expired (:code expired))
                   (= pinned-version (:at-version expired))
                   (:reject expired)))
      (check! "status exposes the bounded snapshot envelope"
              (and (= 1 (:capacity envelope))
                   (= 1 (:retained envelope))
                   (pos? (:evictions envelope))
                   (pos? (:expired envelope)))))))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do (println "\nsnapshot-query-page-snapshot:" (count failures) "FAILED")
        (System/exit 1))
    (println "\nsnapshot-query-page-snapshot:" (count @checks) "/" (count @checks) "PASS")))
