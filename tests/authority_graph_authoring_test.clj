;; Preserved, non-secret graph-authoring transcript index integrity.
;;   bb -cp out tests/authority_graph_authoring_test.clj
(require '[cheshire.core :as json]
         '[fram.authority :as authority])

(def checks (atom []))
(defn chk [name ok] (swap! checks conj [name (boolean ok)]))

(def root (System/getProperty "user.dir"))
(def fixture
  (json/parse-string
   (slurp (str root "/tests/fixtures/authority_graph_authoring_v1.json"))))
(def source-text (slurp (str root "/src/fram/authority.bclj")))
(def calls (get fixture "calls"))
(def summary (get fixture "summary"))
(def derived-tail (get fixture "derivedTail"))
(def rejected (get fixture "zeroMutationRejections"))
(def expected-tools
  ["add-def" "set-body" "rename-def" "insert-after" "replace-in-body"])
(def source-definitions
  (set (map second
            (re-seq #"(?m)^\((?:def|defn|defn-)\s+([^\s]+)" source-text))))
(def final-calls (filterv #(get % "finalDefinition") calls))

(chk "fixture version exact"
     (= "fram.graph-edit-authoring-transcript/v1" (get fixture "fixtureVersion")))
(chk "header-only seed starts graph at 504"
     (= {"scope" "header-only" "initialGraphVersion" "504"
         "firstSubstantiveTx" "505"
         "seedTextSha256" "sha256:c587d9fd964f7ae023d4f947c2ea30ebe59c0476645d0e2777961a0be15bd1b1"}
        (get fixture "seed")))
(chk "tools/list exposed exactly five ordered tools twice"
     (and (= expected-tools (get-in fixture ["restrictedProfile" "servedTools"]))
          (= ["2" "50"] (get-in fixture ["restrictedProfile" "toolsListRequestIds"]))))
(chk "65 successful candidates preserved"
     (= 65 (count calls) (parse-long (get summary "successfulCandidates"))))
(chk "60 final definitions preserved"
     (= 60 (count final-calls) (count source-definitions)
        (parse-long (get summary "finalDefinitions"))))
(chk "final graph definition set equals final successful requests"
     (= source-definitions (set (map #(get % "definition") final-calls))))
(chk "candidate and durable batch identities are unique and equal"
     (and (= (count calls) (count (set (map #(get % "candidateId") calls))))
          (every? #(= (get % "candidateId") (get % "durableBatchId")) calls)))
(chk "every substantive request used restricted add-def"
     (every? #(and (= "add-def" (get % "tool"))
                   (= "src.fram.authority" (get % "module")))
             calls))
(chk "request argument and graph metadata digests are complete"
     (every? #(and (.matches (get % "argumentsDigest") "sha256:[0-9a-f]{64}")
                   (.matches (get % "opsDigest") "[0-9a-f]{64}")
                   (.matches (get % "ednDigest") "[0-9a-f]{64}"))
             calls))
(chk "candidate graph versions form one exact contiguous chain"
     (= "15747"
        (reduce
         (fn [previous call]
           (let [base (bigint (get call "expectedBaseVersion"))
                 installed (bigint (get call "installed"))
                 committed (bigint (get call "committedGraphVersion"))]
             (when-not (and (= previous (get call "expectedBaseVersion"))
                            (= committed (+ base installed))
                            (= (get call "installed") (get call "responseOps"))
                            (= (get call "committedGraphVersion")
                               (get call "responseGraphVersion")))
               (throw (ex-info "broken candidate chain" {:call call})))
             (get call "committedGraphVersion")))
         "504" calls)))
(chk "all candidates correlate to final rendered projection hash"
     (every? #(= (get summary "authoringProjectionSha256")
                 (get % "authoringProjectionSha256"))
             calls))
(chk "authoring graph and render summary exact"
     (and (= "15747" (get summary "authoringEndVersion"))
          (= "sha256:1c9c64e49bfae5160b2830c97f99f21ff2dc9e74aaeaaf58656feb3e984c6072"
             (get summary "authoringLogPrefixSha256"))
          (= "sha256:db0bde7bfc47237edd07f5eb88c9d92211205f7a85701c607a70e29001698a99"
             (get summary "authoringProjectionSha256"))))
(chk "derived tail is explicit and source-neutral"
     (= {"cause" "warm-render-reference-materialization"
         "startTx" "15748" "endTx" "16661" "materializedLogVersion" "16661"
         "materializedLogSha256"
         "sha256:ad5db5c5e174250b3a02ca634695f4495452bb25f4744fc421567f0eb69863d4"
         "count" "914" "operations" {"assert" "914"}
         "predicates" {"bound_to" "914"} "astTopLevelSourceDelta" "none"
         "materializedProjectionSha256"
         "sha256:db0bde7bfc47237edd07f5eb88c9d92211205f7a85701c607a70e29001698a99"}
        derived-tail))
(chk "tracked source equals authoring and materialized projection hashes"
     (= (get summary "authoringProjectionSha256")
        (get derived-tail "materializedProjectionSha256")
        (authority/sha256-text source-text)))
(chk "RPC line accounting includes one transport-truncated request"
     (and (= "70" (get summary "requestLines"))
          (= "69" (get summary "responseLines"))))
(chk "both rejected requests are explicit zero mutations"
     (and (= #{"39" "49"} (set (map #(get % "requestId") rejected)))
          (every? #(= (get % "graphVersionBefore")
                      (get % "graphVersionAfter"))
                  rejected)))
(chk "rejection classes distinguish typecheck and transport failures"
     (= #{"sealed-beagle-typecheck" "pty-canonical-line-truncation"}
        (set (map #(get % "class") rejected))))
(chk "module alias logic contains no ambient-locale case conversion"
     (not (re-find #"toLowerCase|toUpperCase|lower-case|upper-case" source-text)))

(let [results @checks
      failures (filterv (fn [[_ ok]] (not ok)) results)]
  (doseq [[name ok] results]
    (println (if ok "  [PASS] " "  [FAIL] ") name))
  (if (empty? failures)
    (println "\nfram.authority graph authoring:" (count results) "/" (count results) "PASS")
    (do
      (println "\nfram.authority graph authoring:" (count failures) "FAILED of" (count results))
      (System/exit 1))))
