;; coord_authority_snapshot_test.clj — the live sealed-runtime authority descriptor
;; SNAPSHOT derivation (thread 019f90f2). Proves derive-authority-snapshot builds a
;; descriptor snapshot strictly from the sealed launch boundary (Nix core manifest +
;; the wrapper-sealed FRAM_* bindings) and the LIVE coordinator store — runtime.closureDigest is
;; the ONLY North-supplied value FRAM binds without recomputing; runtime.system comes
;; from the sealed manifest, never ambient host state. Every manifest/path/module/log
;; defect and a malformed North digest must fail BEFORE the descriptor can seal or serve.
;;
;;   bb -cp out tests/coord_authority_snapshot_test.clj
;; Scratch /tmp only; never a live coordinator, no socket, no North/Gaffer.
(require '[fram.authority :as a]
         '[fram.tools :as tools]
         '[fram.store :as c]
         '[fram.schema :as s]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(load-file "coord_daemon.clj")

(def pass (atom 0)) (def fail (atom 0))
(defn check [nm ok]
  (if ok (do (swap! pass inc) (println "  [PASS]" nm))
         (do (swap! fail inc) (println "  [FAIL]" nm))))
(defn throws? [f] (try (f) false (catch Throwable _ true)))
(defn scratch [suffix] (str "/tmp/fram-snap-" (java.util.UUID/randomUUID) suffix))

;; ---- sealed launch boundary fixtures (on-disk, hostile-free) ----
(defn write-manifest! [overrides]
  (let [path (scratch "-core.json")
        base {"manifestVersion" "fram.graph-edit-runtime-core/v1"
              "verificationOwner" "north"
              "selfAttestation" false
              "authorityProfile" "graph-edit-authority-v1"
              "system" "x86_64-linux"
              "storeRoots" [{"role" "babashka" "path" "/nix/store/aaa-babashka"}
                            {"role" "beagle" "path" "/nix/store/bbb-beagle"}
                            {"role" "fram" "path" "/nix/store/ccc-fram"}
                            {"role" "jdk" "path" "/nix/store/ddd-jdk"}
                            {"role" "racket" "path" "/nix/store/eee-racket"}]}]
    (spit path (json/generate-string (merge base overrides)))
    path))

;; a live coordinator seeded with two module @<mod>#root `file` facts.
(defn live-coord-with-modules [code-log]
  (let [co (new-coord code-log)]
    (register-pred! co "file" "single" "literal")
    (commit! co "coord" "@empty#root" "file" :assert "empty.bclj" (current-seq co))
    (commit! co "coord" "@src.fram.authority#root" "file" :assert "src/fram/authority.bclj" (current-seq co))
    co))

(def north-digest (a/sha256-text "north-verified-closure"))

(defn make-checkout! []
  (let [checkout (scratch "-checkout")
        source (str checkout "/src")
        code-log (str checkout "/.fram/code.log")]
    (.mkdirs (io/file source))
    (.mkdirs (io/file checkout ".fram"))
    {:checkout checkout :source source :code-log code-log}))

(defn base-inputs [co manifest checkout source]
  {:core-manifest-path manifest
   :checkout-root checkout
   :source-root source
   :code-log (:log co)          ; the coordinator's own on-disk log is the code log
   :closure-digest north-digest
   :co co})

(def endpoint {"transport" "mtls-tcp" "host" "127.0.0.1" "port" "41455"
               "serverSpkiSha256" (a/sha256-text "server-spki")})
(def spki-a (a/sha256-text "client-a-spki"))

(defn seal-of [co inputs]
  (let [ctx (authority-context co (fresh-instance-id))
        opened (authority-session-open ctx "R" "client-a" spki-a 60000)]
    (seal-coordinator-descriptor ctx (:handle opened) endpoint
                                 (derive-authority-snapshot inputs))))

(println "## live authority descriptor snapshot derivation")

;; ===========================================================================
;; POSITIVE — a live-derived snapshot seals into a valid descriptor.
;; ===========================================================================
(let [{:keys [checkout source code-log]} (make-checkout!)
      co (live-coord-with-modules code-log)
      manifest (write-manifest! {})
      inputs (base-inputs co manifest checkout source)
      snap (derive-authority-snapshot inputs)
      sealed (seal-of co inputs)]
  (check "positive: sealed descriptor validates"
         (= sealed (a/validate-authority-descriptor! sealed)))
  (check "runtime.system is the SEALED manifest system, not the host"
         (= "x86_64-linux" (get-in snap ["runtime" "system"])))
  (check "runtime.sealVersion is the FRAM runtime-seal constant"
         (= "fram.runtime-seal/v1" (get-in snap ["runtime" "sealVersion"])))
  (check "runtime.roots come from the sealed manifest store roots (sorted, non-empty)"
         (= ["/nix/store/aaa-babashka" "/nix/store/bbb-beagle" "/nix/store/ccc-fram"
             "/nix/store/ddd-jdk" "/nix/store/eee-racket"]
            (get-in snap ["runtime" "roots"])))
  (check "runtime.closureDigest is EXACTLY the North-supplied value FRAM only binds"
         (= north-digest (get-in snap ["runtime" "closureDigest"])))
  (check "corpus module manifest reflects the LIVE store modules"
         (= ["empty" "src.fram.authority"]
            (mapv #(get % "moduleId")
                  (get-in snap ["corpus" "snapshot" "moduleManifest" "entries"]))))
  (check "corpus.sourceRootRelativeToCheckout is derived checkout-relative"
         (= "src" (get-in snap ["corpus" "sourceRootRelativeToCheckout"])))
  (check "corpus identity file keys are dev:*:ino:* live POSIX identities"
         (every? #(re-matches #"dev:\d+:ino:\d+" (get-in snap ["corpus" "identity" %]))
                 ["checkoutFileKey" "sourceFileKey" "logFileKey"]))
  (check "corpus log prefix binds exact byte length + sha256 of the live code log"
         (and (re-matches #"\d+" (get-in snap ["corpus" "snapshot" "logPrefixBytes"]))
              (re-matches #"sha256:[0-9a-f]{64}" (get-in snap ["corpus" "snapshot" "logPrefixSha256"]))))
  (check "tools catalog is the exact five graph-edit tools in served order"
         (= a/expected-tool-order (mapv #(get % "name") (get-in snap ["tools" "tools"]))))
  (check "tools catalog bytes match the authority schema's exact fixture digest"
         (= "sha256:226f40e6da724f8a8b38e58f490bf4f0ae09b2bc9991ba93c2b9fb04697eedad"
            (get-in snap ["tools" "catalogDigest"])))
  (check "lifecycle maps live healthy state to schema values clean/current"
         (and (= "clean" (get-in snap ["lifecycle" "durability" "state"]))
              (= "current" (get-in snap ["lifecycle" "projection" "state"])))))

;; ===========================================================================
;; DETERMINISM — unchanged inputs derive a byte-identical sealed descriptor;
;; the generation nonce alone still moves both digests.
;; ===========================================================================
(let [{:keys [checkout source code-log]} (make-checkout!)
      co (live-coord-with-modules code-log)
      manifest (write-manifest! {})
      inputs (base-inputs co manifest checkout source)
      handle (:handle (authority-session-open (authority-context co (fresh-instance-id))
                                              "R" "client-a" spki-a 60000))
      ctx (authority-context co "instance-fixed")
      d1 (seal-coordinator-descriptor ctx handle endpoint (derive-authority-snapshot inputs))
      d2 (seal-coordinator-descriptor ctx handle endpoint (derive-authority-snapshot inputs))
      d-other (seal-coordinator-descriptor (assoc ctx :instance "instance-other")
                                           handle endpoint (derive-authority-snapshot inputs))]
  (check "determinism: same inputs => byte-identical sealed descriptor" (= d1 d2))
  (check "determinism: same inputs => identical descriptorDigest"
         (= (get d1 "descriptorDigest") (get d2 "descriptorDigest")))
  (check "generation: instanceId alone changes the bindingDigest"
         (not= (get d1 "bindingDigest") (get d-other "bindingDigest")))
  (check "generation: instanceId alone changes the descriptorDigest"
         (not= (get d1 "descriptorDigest") (get d-other "descriptorDigest"))))

;; ===========================================================================
;; NEGATIVE — every sealed-boundary defect fails BEFORE seal/serve.
;; ===========================================================================
(let [{:keys [checkout source code-log]} (make-checkout!)
      co (live-coord-with-modules code-log)
      good (write-manifest! {})
      ok-inputs (base-inputs co good checkout source)]

  ;; --- manifest defects ---
  (check "negative: absent core manifest fails closed"
         (throws? #(derive-authority-snapshot (assoc ok-inputs :core-manifest-path (scratch "-missing.json")))))
  (let [not-json (scratch "-bad.json")]
    (spit not-json "this is not json {")
  (check "negative: malformed (non-JSON) core manifest fails closed"
           (throws? #(derive-authority-snapshot (assoc ok-inputs :core-manifest-path not-json)))))
  (check "negative: unsupported core manifest version has no seal mapping"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path
                           (write-manifest! {"manifestVersion" "fram.graph-edit-runtime-core/v2"})))))
  (check "negative: wrong core manifest authority profile fails closed"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path
                           (write-manifest! {"authorityProfile" "full"})))))
  (check "negative: wrong core manifest verification owner fails closed"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path
                           (write-manifest! {"verificationOwner" "fram"})))))
  (check "negative: self-attesting core manifest fails closed"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path
                           (write-manifest! {"selfAttestation" true})))))
  (check "negative: core manifest cannot supply runtime.closureDigest"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path
                           (write-manifest! {"closureDigest" north-digest})))))
  (check "negative: core manifest missing runtime system fails closed"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path (write-manifest! {"system" nil})))))
  (check "negative: core manifest missing store roots fails closed"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path (write-manifest! {"storeRoots" []})))))
  (check "negative: core manifest with a non-store runtime root fails closed"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :core-manifest-path
                           (write-manifest!
                            {"storeRoots" [{"role" "babashka" "path" "/tmp/babashka"}
                                           {"role" "beagle" "path" "/nix/store/bbb-beagle"}
                                           {"role" "fram" "path" "/nix/store/ccc-fram"}
                                           {"role" "jdk" "path" "/nix/store/ddd-jdk"}
                                           {"role" "racket" "path" "/nix/store/eee-racket"}]})))))

  ;; --- North closure digest: the sole supplied value is validated before sealing ---
  (let [bad-digest-inputs (assoc ok-inputs :closure-digest "not-a-sha256-digest")]
    (check "negative: a malformed North closure digest fails derivation"
           (throws? #(derive-authority-snapshot bad-digest-inputs))))
  (check "negative: a well-formed-but-wrong-length North digest cannot seal"
         (throws? #(seal-of co (assoc ok-inputs :closure-digest "sha256:abc"))))

  ;; --- path identity drift ---
  (check "negative: an absent checkout root fails closed (POSIX identity drift)"
         (throws? #(derive-authority-snapshot (assoc ok-inputs :checkout-root (scratch "-gone")))))
  (let [outside (scratch "-elsewhere")]
    (.mkdirs (io/file outside))
    (check "negative: an existing source root OUTSIDE the checkout fails closed (path drift)"
           (throws? #(derive-authority-snapshot (assoc ok-inputs :source-root outside)))))
  (check "negative: a non-canonical source root spelling fails closed"
         (throws? #(derive-authority-snapshot
                    (assoc ok-inputs :source-root (str checkout "/src/../src")))))

  ;; --- malformed module / log input ---
  (check "negative: an absent code log fails closed"
         (throws? #(derive-authority-snapshot (assoc ok-inputs :code-log (scratch "-nolog.log")))))
  (let [wrong-log (str checkout "/other.log")]
    (spit wrong-log "not the checkout log")
    (check "negative: a readable non-checkout code log fails closed"
           (throws? #(derive-authority-snapshot (assoc ok-inputs :code-log wrong-log)))))
  (let [co2 (new-coord (scratch ".log"))]
    (register-pred! co2 "file" "single" "literal")
    (commit! co2 "coord" "@weird#root" "file" :assert "weird.txt" (current-seq co2))  ; non-Beagle extension
    (check "negative: a module file fact without a Beagle extension fails closed"
           (throws? #(derive-authority-snapshot (assoc ok-inputs :co co2)))))
  (let [co3 (new-coord (scratch ".log"))]
    (register-pred! co3 "file" "single" "literal")
    (commit! co3 "coord" "@wrong#root" "file" :assert "valid.bclj" (current-seq co3))
    (check "negative: module root identity/path disagreement fails closed"
           (throws? #(derive-authority-snapshot (assoc ok-inputs :co co3)))))
  (let [saved @edit-durability-state]
    (try
      (reset! edit-durability-state {:state :poisoned})
      (check "negative: poisoned coordinator cannot advertise a lifecycle"
             (throws? #(derive-authority-snapshot ok-inputs)))
      (finally (reset! edit-durability-state saved)))))

;; ===========================================================================
;; DARKNESS — the env-boundary reader fails closed with NO ambient fallback and
;; NO coordinator/North/Gaffer contact when a launch binding is missing.
;; ===========================================================================
(let [{:keys [code-log]} (make-checkout!)
      co (live-coord-with-modules code-log)]
  (check "darkness: authority-launch-snapshot fails closed on a missing launch binding"
         (throws? #(authority-launch-snapshot co))))  ; test env supplies no sealed authority bindings

(println (str "\ncoord-authority-snapshot: " @pass " / " (+ @pass @fail) " PASS"))
(when (pos? @fail) (System/exit 1))
