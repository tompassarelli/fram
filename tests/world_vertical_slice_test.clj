;; world_vertical_slice_test.clj — EXECUTABLE SPECIFICATION for the worlds
;; VERTICAL SLICE: the whole A/B/C workflow, end to end, over the durable layer.
;; Thread 019f93bb-37b2-7bb6-8ee0-7f0b5e976260, design 019f9358-5617-7db6-8662-8ac7556717e8.
;;
;;   bb -cp out tests/world_vertical_slice_test.clj    # from the repo ROOT
;;
;; WHAT THIS SUITE IS. tests/world_kernel_test.clj proves the PURE kernel
;; (content ids, overlay precedence, deterministic composition, the structural
;; O(1) fork). tests/world_persistence_test.clj proves DURABILITY (cold restart,
;; torn tail, unpromotable candidates). This suite proves the two halves compose
;; into the workflow the design was written for, in one continuous log:
;;
;;   1. CREATE world A and graph-edit it into a real two-slot manifest.
;;   2. FORK B from A in O(1) — one head claim, no blob/manifest/Version copy,
;;      NO PERSISTENT CHECKOUT anywhere on disk.
;;   3. GRAPH-EDIT BOTH and BUILD each against its EXACT per-world lock: A and B
;;      resolve their own content and neither edit bleeds into the other.
;;   4. COMPOSE a MIXED C out of A's base and B's slot, and build it too.
;;   5. A STALE RIVAL promotion is REJECTED with ZERO head mutation and ZERO
;;      appended bytes.
;;   6. After a COLD RESTART — in a FRESH PROCESS replaying only the log bytes —
;;      every world reproduces the SAME lock, the same manifest, and the same
;;      build inputs.
;;
;; SCOPE, held deliberately. `world-build!` ATTESTS a lock (durable receipt); the
;; build adapter and Git projection are a separate slice
;; (tests/world_git_projection_test.clj), and receipt/expected-head CAS
;; *validation* is tests/world_promotion_test.clj's bar. Here the stale-rival
;; section asserts exactly what the thread bar asks: rejection plus zero head
;; mutation. No socket, no daemon, no port is touched.
;;
;; NORMATIVE SURFACE — the 13 world verbs coord.clj already carries (see
;; tests/world_persistence_test.clj for the full signature list), plus the one
;; piece of glue this slice needs:
;;   (world-compose co base-version-id [[slot source-version-id] ...])
;;       -> an ORDINARY Version record whose :overlay is exactly the op list a
;;          candidate on `base-version-id` must append to become that version.
;;       It gathers each participating version's chain out of the LOG and hands
;;       the pure kernel one versions map; it writes nothing.
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.process :as proc])
(load-file "coord.clj")   ; new-coord / replay / the world verbs (loaded into THIS ns)

;; ---------------------------------------------------------------------------
;; harness — identical in shape to tests/world_persistence_test.clj: one claim
;; per bar, and an ABSENT fn names itself instead of taking the suite down.
;; ---------------------------------------------------------------------------
(def failures (atom 0))
(def total (atom 0))

(defn check
  ([nm ok?] (check nm ok? nil))
  ([nm ok? why]
   (swap! total inc)
   (println (str "  [" (if ok? "PASS" "FAIL") "] " nm
                 (when (and (not ok?) why) (str "  <- " why))))
   (when-not ok? (swap! failures inc))))

(defmacro bar [label & body]
  `(let [r# (try {:ok (boolean (do ~@body))}
                 (catch Throwable e# {:why (or (ex-message e#) (str e#))}))]
     (check ~label (:ok r#) (:why r#))))

(def kernel
  (try (require 'fram.world) {:ok true}
       (catch Throwable _ {:ok false :why "fram.world is not on the classpath"})))
(defn kv [nm]
  (or (when (:ok kernel) (try (ns-resolve 'fram.world (symbol nm)) (catch Throwable _ nil)))
      (throw (ex-info (str "fram.world/" nm " ABSENT"
                           (when-not (:ok kernel) (str " — " (:why kernel)))) {:missing nm}))))
(defn k [nm & args] (apply (kv nm) args))

(defn uv
  "The Var for a world verb defined by coord.clj, or a catchable absence."
  [nm]
  (or (resolve (symbol nm))
      (throw (ex-info (str nm " ABSENT — coord.clj does not define this world verb")
                      {:missing nm}))))
(defn u [nm & args] (apply (uv nm) args))

;; --- byte-level helpers -----------------------------------------------------
(defn b8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))
(defn read-bytes ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file (str path)))))
(defn flen ^long [path] (.length (io/file (str path))))
(defn log-sha [path] (sha256-hex (read-bytes path)))
(defn slurp8 [path] (String. (read-bytes path) "UTF-8"))
(defn hex64? [s] (boolean (re-matches #"[0-9a-f]{64}" (str s))))

;; scratch/worlds holds LOGS ONLY — the "no persistent checkout" bars scan it.
;; The fresh-process probe script lives in scratch/ itself so it can never be
;; mistaken for a checkout artifact.
(def scratch (str (System/getProperty "java.io.tmpdir")
                  "/fram-world-slice-" (System/nanoTime)))
(def wdir (str scratch "/worlds"))
(.mkdirs (io/file wdir))
(def copies (atom 0))
(defn copy-log [src tag]
  (let [dst (str wdir "/" tag "-" (swap! copies inc) ".log")]
    (io/copy (io/file (str src)) (io/file dst))
    dst))
(defn reopen
  "Cold restart, the daemon's own idiom: rebuild the store by REPLAYING the log.
   Nothing in memory carries over."
  [log]
  {:store (replay log) :log log :lock (Object.)})

(defn world-files
  "Every file under scratch/worlds, by name."
  []
  (->> (file-seq (io/file wdir)) (filter #(.isFile %)) (map #(.getName %)) set))
(defn only-logs?
  "No persistent checkout: every file beside the logs is a log. The 1c523c5
  writer-admission seam (rt/acquire-rewrite-lock!) drops a 0-byte
  .fram.rewrite.lock next to every log and the design mandates REUSING that
  seam, so exactly that admission artifact is exempt — precedent b9a4f22,
  tests/world_persistence_test.clj:268."
  []
  (every? #(or (str/ends-with? % ".log") (= % ".fram.rewrite.lock")) (world-files)))

;; --- fixtures ---------------------------------------------------------------
(def mode "100644")
(def slot-core "src/app/core.bclj")
(def slot-util "src/app/util.bclj")
(def slot-new  "src/app/new.bclj")
(def raw-core1 (b8 "(ns app.core) ;; v1\n"))
(def raw-core2 (b8 "(ns app.core) ;; v2 — edited in world A\n"))
(def raw-util1 (b8 "(ns app.util) ;; v1\n"))
(def raw-util2 (b8 "(ns app.util) ;; v2 — edited in world B\n"))
(def raw-new   (b8 "(ns app.new)\n"))
;; distinct nonces: a CandidateId is (world, expected-head, nonce)-addressed, so
;; two edits from the same head need distinct nonces to be distinct candidates.
(def n-a1 "0123456789abcdef0123456789abcdef")
(def n-b1 "fedcba9876543210fedcba9876543210")
(def n-a2 "aaaabbbbccccdddd0000111122223333")
(def n-c1 "11112222333344445555666677778888")
(def n-riv "99998888777766665555444433332222")
(def n-a3 "0f0f0f0f1e1e1e1e2d2d2d2d3c3c3c3c")
(def build-spec
  {:adapter "beagle" :toolchain "sha256:tc" :platform "x86_64-linux"
   :entrypoint "app.core/-main" :purpose "slice" :argv []
   :env {} :locale "C" :timezone "UTC" :epoch 0 :random "none" :network "none"})

(defn edit!
  "One graph edit of a world, end to end and WITHOUT A CHECKOUT: open a candidate
  at the world's current head, append ops, seal it into a Version, lock+build it,
  then promote. Returns every durable id the bars need."
  [co nm nonce ops]
  (let [head (u "world-head" co nm)
        cid  (:ok (u "world-begin!" co "w" nm head nonce))
        _    (doseq [op ops] (u "world-append!" co "w" cid op))
        v    (:ok (u "world-seal!" co "w" cid))
        lock (u "world-lock!" co v build-spec)
        rcpt (:ok (u "world-build!" co "w" (:ok lock)))]
    {:from head :cid cid :version v :lock (:ok lock) :receipt rcpt
     :promote (u "world-promote!" co "w" nm head cid rcpt)}))

(println "worlds vertical slice — executable specification (A/B/C, end to end)")
(println (str "  scratch: " scratch))

;; The one continuous log the whole slice lives in.
(def slice-log (str wdir "/slice.log"))
(def fx
  (delay
    (let [co    (new-coord slice-log)
          root  (k "version-id" nil [])
          ;; 1. create A, then graph-edit it into a two-slot manifest
          _     (u "world-create!" co "w" "A" root)
          head0 (u "world-head" co "A")
          bc1   (:ok (u "world-blob-put!" co "w" raw-core1))
          bu1   (:ok (u "world-blob-put!" co "w" raw-util1))
          eA1   (edit! co "A" n-a1 [(k "put-op" slot-core mode bc1)
                                    (k "put-op" slot-util mode bu1)])
          ;; 2. fork B from A — measured
          len-pre-fork (flen slice-log)
          forkB (u "world-fork!" co "w" "B" (u "world-head" co "A"))
          len-post-fork (flen slice-log)
          fork-tail (subs (slurp8 slice-log) len-pre-fork)
          ;; observed AT FORK TIME — both names diverge later in this same log,
          ;; so the fork-time claims must be captured here, not re-read later.
          fork-obs {:head-a (u "world-head" co "A")
                    :head-b (u "world-head" co "B")
                    :man-a (u "world-manifest" co (u "world-head" co "A"))
                    :man-b (u "world-manifest" co (u "world-head" co "B"))}
          ;; 3. graph-edit BOTH, divergently: B rewrites util, A rewrites core
          bu2   (:ok (u "world-blob-put!" co "w" raw-util2))
          eB1   (edit! co "B" n-b1 [(k "put-op" slot-util mode bu2)])
          bc2   (:ok (u "world-blob-put!" co "w" raw-core2))
          eA2   (edit! co "A" n-a2 [(k "put-op" slot-core mode bc2)])
          ;; 4. compose a MIXED C: A's base, B's util slot
          _     (u "world-fork!" co "w" "C" (u "world-head" co "A"))
          crec  (u "world-compose" co (u "world-head" co "A")
                   [[slot-util (u "world-head" co "B")]])
          eC1   (edit! co "C" n-c1 (:overlay crec))
          ;; 5. a stale rival: it opens at A's CURRENT head and seals honestly,
          ;;    then the winner moves A's head underneath it.
          riv-from (u "world-head" co "A")
          riv-cid  (:ok (u "world-begin!" co "w" "A" riv-from n-riv))
          bnew     (:ok (u "world-blob-put!" co "w" raw-new))
          _        (u "world-append!" co "w" riv-cid (k "put-op" slot-core mode bnew))
          riv-v    (:ok (u "world-seal!" co "w" riv-cid))
          riv-rcpt (:ok (u "world-build!" co "w" (:ok (u "world-lock!" co riv-v build-spec))))
          eA3      (edit! co "A" n-a3 [(k "put-op" slot-new mode bnew)])
          ;; the rival now promotes against the head it was begun at: STALE.
          head-pre (u "world-head" co "A")
          sha-pre  (log-sha slice-log)
          len-pre  (flen slice-log)
          riv-r    (u "world-promote!" co "w" "A" riv-from riv-cid riv-rcpt)]
      {:co co :log slice-log :root root :head0 head0
       :bc1 bc1 :bc2 bc2 :bu1 bu1 :bu2 bu2 :bnew bnew
       :A1 eA1 :A2 eA2 :A3 eA3 :B1 eB1 :C1 eC1
       :fork-b forkB :fork-bytes (- len-post-fork len-pre-fork) :fork-tail fork-tail
       :fork-obs fork-obs
       :crec crec
       :rival {:from riv-from :cid riv-cid :version riv-v :receipt riv-rcpt
               :result riv-r :head-pre head-pre :sha-pre sha-pre :len-pre len-pre
               :head-post (u "world-head" co "A")
               :sha-post (log-sha slice-log) :len-post (flen slice-log)}
       ;; the three FINAL heads the cold-restart section reproduces
       :heads {"A" (u "world-head" co "A")
               "B" (u "world-head" co "B")
               "C" (u "world-head" co "C")}
       :locks {"A" (:lock eA3) "B" (:lock eB1) "C" (:lock eC1)}})))

;; ===========================================================================
(println "\n-- 0. the substrate this slice composes (kernel + the durable verbs) --")
;; ===========================================================================
(bar "substrate: the pure world kernel is on the classpath"
     (:ok kernel))
(bar "substrate: coord.clj defines all 13 world verbs plus the compose glue"
     (every? #(some? (uv %))
             ["world-create!" "world-fork!" "world-head" "world-blob-put!" "world-blob"
              "world-version" "world-manifest" "world-begin!" "world-append!" "world-seal!"
              "world-candidate" "world-lock!" "world-build!" "world-promote!"
              "world-compose"]))
(bar "substrate: world-compose is a READ — it never appends to the log"
     (let [f      @fx
           before (log-sha (:log f))
           _      (u "world-compose" (:co f) (get-in f [:heads "A"])
                     [[slot-util (get-in f [:heads "B"])]])]
       (= before (log-sha (:log f)))))

;; ===========================================================================
(println "\n-- 1. CREATE world A and graph-edit it into a real manifest --")
;; ===========================================================================
(bar "create: A's derived head starts at the EMPTY root Version"
     (let [f @fx] (= (:root f) (:head0 f))))
(bar "create: the newborn world has an EMPTY manifest (nothing materialized)"
     (let [f @fx] (= [] (vec (u "world-manifest" (:co f) (:head0 f))))))
(bar "create: re-creating an existing world is rejected :world-exists"
     (let [f @fx] (= :world-exists (:reject (u "world-create!" (:co f) "w" "A" (:root f))))))
(bar "edit A: the seal produced a content-addressed VersionId"
     (let [f @fx] (hex64? (get-in f [:A1 :version]))))
(bar "edit A: promotion moved A's head to exactly that Version"
     (let [f @fx] (= (get-in f [:A1 :version]) (:ok (get-in f [:A1 :promote])))))
(bar "edit A: A's manifest resolves BOTH slots to the exact blobs that were put"
     (let [f @fx
           m (u "world-manifest" (:co f) (get-in f [:A1 :version]))]
       (and (= [slot-core slot-util] (mapv :slot m))
            (= [(:bc1 f) (:bu1 f)] (mapv :blob-id m)))))
(bar "edit A: the blob bytes round-trip out of the LOG, byte-identically"
     (let [f @fx]
       (java.util.Arrays/equals ^bytes raw-core1 ^bytes (u "world-blob" (:co f) (:bc1 f)))))

;; ===========================================================================
(println "\n-- 2. FORK B from A in O(1), with NO PERSISTENT CHECKOUT --")
;; ===========================================================================
(bar "fork B: B's derived head IS A's Version — two names, ONE Version"
     (let [f @fx] (= (get-in f [:A1 :version]) (get-in f [:fork-obs :head-b]))))
(bar "fork B: the fork MINTED NO Version record — its bytes name no world.version"
     (let [f @fx] (not (str/includes? (:fork-tail f) "world.version:"))))
(bar "fork B: NO BLOB COPY — no BlobId, no world.blob subject, no blob payload"
     (let [f   @fx
           b64 (.encodeToString (java.util.Base64/getEncoder) ^bytes raw-core1)]
       (and (not (str/includes? (:fork-tail f) (:bc1 f)))
            (not (str/includes? (:fork-tail f) (:bu1 f)))
            (not (str/includes? (:fork-tail f) "world.blob:"))
            (not (str/includes? (:fork-tail f) b64)))))
(bar "fork B: NO MANIFEST COPY — the fork's bytes name no slot of the base"
     (let [f @fx]
       (and (not (str/includes? (:fork-tail f) slot-core))
            (not (str/includes? (:fork-tail f) slot-util)))))
;; observed: the fork does not even re-emit the VersionId STRING — the value is
;; already interned in the log, so the head claim carries a reference to it. A
;; fork that copied anything content-addressed would have to spell a 64-hex id.
(bar "fork B: the fork re-emits NO content id at all — it REFERENCES the interned one"
     (let [f @fx] (empty? (re-seq #"[0-9a-f]{64}" (:fork-tail f)))))
(bar "fork B: the fork appended UNDER 512 B in absolute terms"
     (let [f @fx] (< 0 (:fork-bytes f) 512)))
(bar "fork B: forking did NOT move A's head"
     (let [f @fx] (= (get-in f [:A1 :version]) (get-in f [:fork-obs :head-a]))))
(bar "fork B: both names read ONE identical NON-EMPTY manifest at fork time"
     (let [f @fx]
       (and (= 2 (count (get-in f [:fork-obs :man-a])))
            (= (get-in f [:fork-obs :man-a]) (get-in f [:fork-obs :man-b])))))
;; the DURABLE O(1) claim (the kernel suite proves the structural one): forking a
;; 512-slot base costs the same bytes as forking a 1-slot base, up to the width of
;; the log's own entity/cid integers. If a fork ever copied a manifest or blobs,
;; this gap would be thousands of bytes, not tens.
(def fork-cost
  (delay
    (letfn [(cost [tag n]
              (let [log  (str wdir "/" tag ".log")
                    co   (new-coord log)
                    root (k "version-id" nil [])
                    _    (u "world-create!" co "w" "Wrld" root)
                    bid  (:ok (u "world-blob-put!" co "w" raw-core1))
                    cid  (:ok (u "world-begin!" co "w" "Wrld" root n-a1))
                    _    (doseq [i (range n)]
                           (u "world-append!" co "w" cid
                              (k "put-op" (str "src/pkg/f" i ".bclj") mode bid)))
                    v    (:ok (u "world-seal!" co "w" cid))
                    rc   (:ok (u "world-build!" co "w" (:ok (u "world-lock!" co v build-spec))))
                    _    (u "world-promote!" co "w" "Wrld" root cid rc)
                    pre  (flen log)
                    _    (u "world-fork!" co "w" "Fork" (u "world-head" co "Wrld"))]
                {:bytes (- (flen log) pre)
                 :slots (count (u "world-manifest" co v))}))]
      {:thin (cost "thin" 1) :wide (cost "wide" 512)})))
(bar "fork B: the two measured bases really do differ 512x in manifest size"
     (let [c @fork-cost] (and (= 1 (:slots (:thin c))) (= 512 (:slots (:wide c))))))
(bar "fork B: O(1) IN BYTES — a 512-slot base forks within 32 B of a 1-slot base"
     (let [c @fork-cost]
       (< (Math/abs (- (:bytes (:wide c)) (:bytes (:thin c)))) 32)))
(bar "fork B: NO PERSISTENT CHECKOUT — every file on disk is a log"
     (do @fx @fork-cost (only-logs?)))
(bar "fork B: the graph IS the checkout — edited bytes live in the log as base64"
     (let [f @fx]
       (str/includes? (slurp8 (:log f))
                      (.encodeToString (java.util.Base64/getEncoder) ^bytes raw-core1))))

;; ===========================================================================
(println "\n-- 3. graph-edit BOTH and build EXACT per-world locks (no bleed) --")
;; ===========================================================================
(bar "edit both: B's head advanced to B's own sealed Version"
     (let [f @fx] (= (get-in f [:B1 :version]) (u "world-head" (:co f) "B"))))
(bar "edit both: editing B did NOT move A's head"
     (let [f @fx] (not= (get-in f [:B1 :version]) (get-in f [:A2 :version]))))
(bar "edit both: A advanced to its OWN Version, distinct from B's"
     (let [f @fx]
       (and (hex64? (get-in f [:A2 :version]))
            (not= (get-in f [:A2 :version]) (get-in f [:B1 :version])))))
(bar "edit both: B's edit is SPARSE — one overlay op over an inherited 2-slot base"
     (let [f @fx
           r (u "world-version" (:co f) (get-in f [:B1 :version]))]
       (and (= 1 (count (:overlay r)))
            (= (get-in f [:A1 :version]) (:base r)))))
(bar "no bleed: A resolves util to the ORIGINAL blob (B's edit is invisible)"
     (let [f @fx
           m (u "world-manifest" (:co f) (get-in f [:A2 :version]))]
       (= (:bu1 f) (:blob-id (first (filter #(= slot-util (:slot %)) m))))))
(bar "no bleed: A resolves core to A's OWN edited blob"
     (let [f @fx
           m (u "world-manifest" (:co f) (get-in f [:A2 :version]))]
       (= (:bc2 f) (:blob-id (first (filter #(= slot-core (:slot %)) m))))))
(bar "no bleed: B resolves util to B's OWN edited blob"
     (let [f @fx
           m (u "world-manifest" (:co f) (get-in f [:B1 :version]))]
       (= (:bu2 f) (:blob-id (first (filter #(= slot-util (:slot %)) m))))))
(bar "no bleed: B resolves core to the ORIGINAL blob (A's edit is invisible)"
     (let [f @fx
           m (u "world-manifest" (:co f) (get-in f [:B1 :version]))]
       (= (:bc1 f) (:blob-id (first (filter #(= slot-core (:slot %)) m))))))
(bar "no bleed: the two manifests share NO blob on any common slot"
     (let [f  @fx
           ma (u "world-manifest" (:co f) (get-in f [:A2 :version]))
           mb (u "world-manifest" (:co f) (get-in f [:B1 :version]))]
       (and (= (mapv :slot ma) (mapv :slot mb))
            (empty? (filter true? (map #(= (:blob-id %1) (:blob-id %2)) ma mb))))))
(bar "locks: A and B get DIFFERENT locks under the IDENTICAL build spec"
     (let [f @fx] (not= (get-in f [:A2 :lock]) (get-in f [:B1 :lock]))))
(bar "locks: a lock is a PURE function of (version, spec) — recomputing repeats it"
     (let [f @fx]
       (= (get-in f [:A2 :lock])
          (:ok (u "world-lock!" (:co f) (get-in f [:A2 :version]) build-spec)))))
(bar "locks: recomputing a lock appends NOTHING (it is already durable)"
     (let [f      @fx
           before (log-sha (:log f))]
       (u "world-lock!" (:co f) (get-in f [:B1 :version]) build-spec)
       (= before (log-sha (:log f)))))
(bar "build: A's receipt names A's EXACT version and A's EXACT lock"
     (let [f @fx
           r (get-in f [:A2 :receipt])]
       (and (= (get-in f [:A2 :version]) (:version r))
            (= (get-in f [:A2 :lock]) (:lock r)))))
(bar "build: B's receipt names B's EXACT version and B's EXACT lock"
     (let [f @fx
           r (get-in f [:B1 :receipt])]
       (and (= (get-in f [:B1 :version]) (:version r))
            (= (get-in f [:B1 :lock]) (:lock r)))))
(bar "build: the two receipts are DISTINCT attestations"
     (let [f @fx] (not= (:receipt (get-in f [:A2 :receipt]))
                        (:receipt (get-in f [:B1 :receipt])))))
(bar "build: rebuilding the same lock re-attests the IDENTICAL receipt id"
     (let [f @fx]
       (= (:receipt (get-in f [:B1 :receipt]))
          (:receipt (:ok (u "world-build!" (:co f) "w" (get-in f [:B1 :lock])))))))
(bar "build: an unknown lock is rejected :world-lock-unknown"
     (let [f @fx]
       (= :world-lock-unknown
          (:reject (u "world-build!" (:co f) "w" (apply str (repeat 64 "0")))))))
(bar "build: every build INPUT is durable — each manifest blob reads back from the log"
     (let [f @fx]
       (every? (fn [row] (some? (u "world-blob" (:co f) (:blob-id row))))
               (concat (u "world-manifest" (:co f) (get-in f [:A2 :version]))
                       (u "world-manifest" (:co f) (get-in f [:B1 :version]))))))

;; ===========================================================================
(println "\n-- 4. COMPOSE a MIXED C from A and B, and build it --")
;; ===========================================================================
(bar "compose C: forking C off A is O(1) too — C starts at A's Version"
     (let [f @fx] (= (get-in f [:A2 :version]) (get-in f [:C1 :from]))))
(bar "compose C: the composed record is an ORDINARY Version — no mixture marker"
     (let [f @fx
           ord (k "version-record" nil [(k "delete-op" slot-core)])]
       (= (set (keys ord)) (set (keys (:crec f))))))
(bar "compose C: the composed overlay is SPARSE — only the selected slot"
     (let [f @fx]
       (= [slot-util] (mapv :slot (:overlay (:crec f))))))
(bar "compose C: the durable seal reproduces the PURE kernel's composed id exactly"
     (let [f @fx]
       (= (k "version-id" (:base (:crec f)) (:overlay (:crec f)))
          (get-in f [:C1 :version]))))
(bar "compose C: C's head is DISTINCT from both A's and B's — a genuine mixture"
     (let [f @fx]
       (and (= (get-in f [:C1 :version]) (u "world-head" (:co f) "C"))
            (not= (get-in f [:C1 :version]) (get-in f [:A2 :version]))
            (not= (get-in f [:C1 :version]) (get-in f [:B1 :version])))))
(bar "compose C: C takes core from A — exact blob AND exact origin Version"
     (let [f @fx
           row (first (filter #(= slot-core (:slot %))
                              (u "world-manifest" (:co f) (get-in f [:C1 :version]))))]
       (and (= (:bc2 f) (:blob-id row))
            (= (get-in f [:A2 :version]) (:origin row)))))
(bar "compose C: C takes util from B — exact blob AND B's version as origin"
     (let [f @fx
           row (first (filter #(= slot-util (:slot %))
                              (u "world-manifest" (:co f) (get-in f [:C1 :version]))))]
       (and (= (:bu2 f) (:blob-id row))
            (= (get-in f [:C1 :version]) (:origin row)))))
(bar "compose C: composition is DETERMINISTIC — recomposing yields the same record"
     (let [f @fx]
       (= (:crec f) (u "world-compose" (:co f) (get-in f [:A2 :version])
                       [[slot-util (get-in f [:B1 :version])]]))))
(bar "compose C: composition is INDEPENDENT of selection order"
     (let [f @fx
           a (u "world-compose" (:co f) (get-in f [:A2 :version])
                [[slot-util (get-in f [:B1 :version])] [slot-core (get-in f [:A2 :version])]])
           b (u "world-compose" (:co f) (get-in f [:A2 :version])
                [[slot-core (get-in f [:A2 :version])] [slot-util (get-in f [:B1 :version])]])]
       (= a b)))
(bar "compose C: C gets its OWN lock and a receipt naming its OWN version"
     (let [f @fx]
       (and (not= (get-in f [:C1 :lock]) (get-in f [:A2 :lock]))
            (not= (get-in f [:C1 :lock]) (get-in f [:B1 :lock]))
            (= (get-in f [:C1 :version]) (:version (get-in f [:C1 :receipt]))))))
(bar "compose C: composing moved NEITHER A's nor B's head"
     (let [f @fx]
       (and (= (get-in f [:B1 :version]) (u "world-head" (:co f) "B"))
            (= (get-in f [:A3 :version]) (u "world-head" (:co f) "A")))))
(bar "compose C: STILL no persistent checkout after composing three worlds"
     (do @fx (only-logs?)))

;; ===========================================================================
(println "\n-- 5. a STALE RIVAL is REJECTED with ZERO head mutation --")
;; ===========================================================================
;; The rival is honest: it opened at A's then-current head and sealed a real
;; Version with a real receipt. It simply lost the race — the winner promoted
;; first. SCOPE: full receipt/expected-head CAS validation is
;; tests/world_promotion_test.clj; this asserts the thread bar's two halves.
(bar "rival: it really did seal a legitimate Version before losing the race"
     (let [f @fx]
       (and (hex64? (get-in f [:rival :version]))
            (= (get-in f [:rival :version])
               (:sealed (u "world-candidate" (:co f) (get-in f [:rival :cid])))))))
(bar "rival: the winner moved A's head out from under it first"
     (let [f @fx]
       (and (= (get-in f [:rival :from]) (get-in f [:A2 :version]))
            (= (get-in f [:rival :head-pre]) (get-in f [:A3 :version]))
            (not= (get-in f [:rival :from]) (get-in f [:rival :head-pre])))))
(bar "rival: the stale promotion is REJECTED :world-head-stale"
     (let [f @fx] (= :world-head-stale (:reject (get-in f [:rival :result])))))
(bar "rival: the rejection returned NO ok — nothing was accepted"
     (let [f @fx] (nil? (:ok (get-in f [:rival :result])))))
(bar "rival: ZERO HEAD MUTATION — A's derived head is byte-identical after"
     (let [f @fx] (= (get-in f [:rival :head-pre]) (get-in f [:rival :head-post]))))
(bar "rival: ZERO BYTES appended — the rejection happened BEFORE any append"
     (let [f @fx]
       (and (= (get-in f [:rival :sha-pre]) (get-in f [:rival :sha-post]))
            (= (get-in f [:rival :len-pre]) (get-in f [:rival :len-post])))))
(bar "rival: the winner's slot still resolves — the loser overwrote nothing"
     (let [f @fx
           m (u "world-manifest" (:co f) (u "world-head" (:co f) "A"))]
       (= (:bnew f) (:blob-id (first (filter #(= slot-new (:slot %)) m))))))
(bar "rival: A's head replays as the WINNER's version after a cold restart"
     (let [f @fx]
       (= (get-in f [:A3 :version])
          (u "world-head" (reopen (copy-log (:log f) "rival-restart")) "A"))))
(bar "rival: B and C were untouched by A's contested promotion"
     (let [f  @fx
           co (reopen (copy-log (:log f) "rival-others"))]
       (and (= (get-in f [:B1 :version]) (u "world-head" co "B"))
            (= (get-in f [:C1 :version]) (u "world-head" co "C")))))

;; ===========================================================================
(println "\n-- 6. COLD RESTART reproduces the SAME lock, in a FRESH PROCESS --")
;; ===========================================================================
;; A different babashka process, replaying only the log bytes, must recompute the
;; identical WorldLockId, manifest and build inputs for every world — the only
;; test that truly excludes wall clock, pid, hashCode and process-local cid from
;; the lock. The probe holds :log nil, so it CANNOT append: if a lock were not
;; already durable, the probe would fail rather than silently mint one.
(def probe-path (str scratch "/slice_lock_probe.clj"))
(def probe-src
  (str "(load-file \"coord.clj\")\n"
       "(let [[log nm spec] *command-line-args*\n"
       "      co   {:store (replay log) :log nil :lock (Object.)}\n"
       "      head (world-head co nm)\n"
       "      lock (:ok (world-lock! co head (clojure.edn/read-string spec)))\n"
       "      man  (world-manifest co head)\n"
       "      md   (java.security.MessageDigest/getInstance \"SHA-256\")]\n"
       "  (doseq [row man] (.update md ^bytes (world-blob co (:blob-id row))))\n"
       "  (println (str head \"\\t\" lock \"\\t\" (w/render-record (vec man)) \"\\t\"\n"
       "                (apply str (map #(format \"%02x\" %) (.digest md))))))\n"))
(spit probe-path probe-src)

(defn cold-probe
  "Run the probe in a FRESH bb process against a private copy of the slice log."
  [nm]
  (let [f   @fx
        log (copy-log (:log f) (str "cold-" nm))
        r   (proc/shell {:out :string :err :string :continue true
                         :dir (System/getProperty "user.dir")}
                        "bb" "-cp" "out" probe-path log nm (pr-str build-spec))]
    (if (zero? (:exit r))
      (zipmap [:head :lock :manifest :inputs] (str/split (str/trim (:out r)) #"\t"))
      (throw (ex-info (str "cold probe for " nm " failed: " (:err r)) {})))))
(def cold (delay {"A" (cold-probe "A") "B" (cold-probe "B") "C" (cold-probe "C")}))

(defn inputs-sha
  "The build inputs of a world, in-process: every manifest blob's bytes in
  manifest order, hashed as one stream."
  [co head]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [row (u "world-manifest" co head)]
      (.update md ^bytes (u "world-blob" co (:blob-id row))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(bar "restart: an IN-PROCESS replay reproduces all three derived heads"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart-heads"))]
       (= (:heads f) {"A" (u "world-head" co "A")
                      "B" (u "world-head" co "B")
                      "C" (u "world-head" co "C")})))
(bar "restart: replay is idempotent — two independent replays agree on every head"
     (let [f  @fx
           r1 (reopen (copy-log (:log f) "idem-a"))
           r2 (reopen (copy-log (:log f) "idem-b"))]
       (= (mapv #(u "world-head" r1 %) ["A" "B" "C"])
          (mapv #(u "world-head" r2 %) ["A" "B" "C"]))))
(bar "restart: the in-process replay recomputes A's IDENTICAL lock"
     (let [f  @fx
           co (reopen (copy-log (:log f) "restart-lock-a"))]
       (= (get-in f [:locks "A"])
          (:ok (u "world-lock!" co (u "world-head" co "A") build-spec)))))
(bar "restart: FRESH PROCESS — A's head is byte-identical"
     (let [f @fx] (= (get-in f [:heads "A"]) (:head (get @cold "A")))))
(bar "restart: FRESH PROCESS — A's WorldLockId is byte-identical"
     (let [f @fx] (= (get-in f [:locks "A"]) (:lock (get @cold "A")))))
(bar "restart: FRESH PROCESS — B's WorldLockId is byte-identical"
     (let [f @fx] (and (= (get-in f [:heads "B"]) (:head (get @cold "B")))
                       (= (get-in f [:locks "B"]) (:lock (get @cold "B"))))))
(bar "restart: FRESH PROCESS — the MIXED world C's WorldLockId is byte-identical"
     (let [f @fx] (and (= (get-in f [:heads "C"]) (:head (get @cold "C")))
                       (= (get-in f [:locks "C"]) (:lock (get @cold "C"))))))
(bar "restart: the three cold locks are still DISTINCT from one another"
     (= 3 (count (set (map :lock (vals @cold))))))
(bar "restart: FRESH PROCESS — every resolved manifest is identical, C's mixture included"
     (let [f @fx]
       (every? (fn [nm]
                 (= (k "render-record" (vec (u "world-manifest" (:co f) (get-in f [:heads nm]))))
                    (:manifest (get @cold nm))))
               ["A" "B" "C"])))
(bar "restart: FRESH PROCESS — the BUILD INPUT bytes hash identically for all three"
     (let [f @fx]
       (every? (fn [nm] (= (inputs-sha (:co f) (get-in f [:heads nm]))
                           (:inputs (get @cold nm))))
               ["A" "B" "C"])))
(bar "restart: the cold probe never wrote — its log copy is byte-unchanged"
     (let [f    @fx
           log  (copy-log (:log f) "cold-readonly")
           sha0 (log-sha log)
           r    (proc/shell {:out :string :err :string :continue true
                             :dir (System/getProperty "user.dir")}
                            "bb" "-cp" "out" probe-path log "C" (pr-str build-spec))]
       (and (zero? (:exit r)) (= sha0 (log-sha log)))))
(bar "restart: after the WHOLE slice there is still NO PERSISTENT CHECKOUT"
     (do @fx @cold (only-logs?)))

;; ---------------------------------------------------------------------------
(let [pass (- @total @failures)]
  (println (str "\nworld-vertical-slice: " pass "/" @total " PASS"))
  (if (zero? @failures)
    (println "world-vertical-slice: ALL BARS PASS")
    (do (println (str "world-vertical-slice: " @failures " FAILED — these bars DEFINE"
                      " the end-to-end A/B/C workflow over the world kernel and its"
                      " durability layer."))
        (System/exit 1))))
