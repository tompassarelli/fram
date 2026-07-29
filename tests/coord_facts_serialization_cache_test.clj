;; The whole-corpus :facts response is serialized ONCE per version, not once per
;; request.  Run:
;;   bb -cp out tests/coord_facts_serialization_cache_test.clj
;;
;; This is a stability fix, not a speed fix. G1HeapRegionSize is 8 MB on this
;; deployment, so any object over 4 MB is a HUMONGOUS allocation and is placed
;; directly into the old generation — it never gets the chance to die young. The
;; whole-corpus response is ~60 MB of JSON, so every board verb dumped ~107 MB
;; straight into old gen (measured on the live daemon: 464 -> 784 MB across three
;; `north threads`). At -Xmx16g old gen fills in roughly 150 verbs, and then G1
;; thrashes. The wedged coordinator found on 2026-07-29 was 20.5 GB RSS, old gen
;; 99.98%, 169 full GCs, 6.5 cores pegged — accepting connections and answering
;; nothing, which took the whole system down.
;;
;; The triples were already cached per version; only the STRING was rebuilt. The
;; corpus version moves in ~5% of one-second intervals, so ~95% of that
;; allocation was reproducing a byte-identical result.
;;
;; A serialization cache that ever returns the WRONG bytes would silently corrupt
;; every read, so identity of output is the first thing asserted here.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))

(def checks (atom []))
(defn check! [label value] (swap! checks conj [label (boolean value)]))

(def tmp-dir
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-facts-cache"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def log-path (.getCanonicalPath (io/file tmp-dir "facts.log")))

(defn line [tx l p r]
  (pr-str {:tx tx :op "assert" :l l :p p :r r :ts "t" :by "fixture"}))

(spit log-path
      (str (str/join "\n" (for [i (range 1 40)]
                            (line i (str "@s-" i) "title" (str "value-" i))))
           "\n"))

(reset! snapshot-boot-enabled? false)
(boot-flat! log-path)

;; --- output must be byte-identical to the uncached path ---------------------
(let [resp (let [{:keys [version log triples]} (facts-wire-snapshot)]
             {:version version :log log :facts triples})
      direct (wire/serialize-response :json resp fram.rt/to-json)
      cached (serialize-resp :json resp)]
  (check! "cached JSON equals the uncached serialization" (= direct cached))
  (check! "the cached answer is non-trivial" (< 100 (count cached))))

(let [resp (let [{:keys [version log triples]} (facts-wire-snapshot)]
             {:version version :log log :facts triples})
      direct (wire/serialize-response nil resp fram.rt/to-json)
      cached (serialize-resp nil resp)]
  (check! "cached EDN equals the uncached serialization" (= direct cached)))

;; --- the cache must actually HIT (identical object, not merely equal) -------
;; Without this the whole change could be inert and every equality assertion
;; above would still pass.
(let [resp (let [{:keys [version log triples]} (facts-wire-snapshot)]
             {:version version :log log :facts triples})
      a (serialize-resp :json resp)
      b (serialize-resp :json resp)]
  (check! "a repeat request reuses the SAME string, allocating nothing"
          (identical? a b)))

;; EDN and JSON are different answers and must not share an entry.
(let [resp (let [{:keys [version log triples]} (facts-wire-snapshot)]
             {:version version :log log :facts triples})
      j (serialize-resp :json resp)
      e (serialize-resp nil resp)]
  (check! "format is part of the key — JSON and EDN never collide"
          (not= j e))
  (check! "EDN output is EDN" (str/starts-with? e "{"))
  (check! "JSON output is JSON" (str/includes? j "\"facts\"")))

;; --- a write must invalidate --------------------------------------------------
(let [before (let [{:keys [version log triples]} (facts-wire-snapshot)]
               (serialize-resp :json {:version version :log log :facts triples}))
      _ (handle {:op :assert :te "@fresh" :p "title" :r "added"})
      after (let [{:keys [version log triples]} (facts-wire-snapshot)]
              (serialize-resp :json {:version version :log log :facts triples}))]
  (check! "a write produces a different serialization" (not= before after))
  (check! "the post-write answer contains the new fact"
          (str/includes? after "@fresh")))

;; --- a DIFFERENT fact vector must never reuse another's bytes ---------------
;; The scoped :facts-for-subjects op builds a fresh vector per request. If the
;; key were version+format alone, a slice would be served the whole corpus, or
;; one slice would be served another's rows. Both would be silent corruption.
(let [{:keys [version log triples]} (facts-wire-snapshot)
      whole (serialize-resp :json {:version version :log log :facts triples})
      slice-rows (filterv (fn [[l _ _]] (= "@s-1" l)) triples)
      slice (serialize-resp :json {:version version :log log :facts slice-rows})]
  (check! "a slice at the same version is NOT served the whole corpus"
          (not= whole slice))
  (check! "the slice contains only its own subject"
          (and (str/includes? slice "@s-1") (not (str/includes? slice "@s-2"))))
  (check! "re-serializing the whole corpus after a slice is still correct"
          (= whole (serialize-resp :json {:version version :log log :facts triples}))))

;; --- responses that merely LOOK like facts must not be cached ---------------
;; :status carries a :facts key too, but as a COUNT.
(let [status {:version 1 :facts 42 :log "x"}]
  (check! "a :facts COUNT response is not treated as a corpus"
          (false? (cacheable-facts-response? status)))
  (check! "a status response still serializes correctly"
          (= (wire/serialize-response :json status fram.rt/to-json)
             (serialize-resp :json status))))

(check! "an error response is not cacheable"
        (false? (cacheable-facts-response? {:error "nope"})))
(check! "a response with no version is not cacheable"
        (false? (cacheable-facts-response? {:facts []})))

(let [failures (remove second @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "  [PASS] " "  [FAIL] ") label))
  (if (seq failures)
    (do (println "\ncoord-facts-serialization-cache:" (count failures) "FAILED")
        (System/exit 1))
    (println "\ncoord-facts-serialization-cache:"
             (count @checks) "/" (count @checks) "PASS")))
