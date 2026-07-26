;; world_kernel_test.clj — EXECUTABLE SPECIFICATION for the worlds kernel
;; (fram.world), thread 019f93bb-37b2-7bb6-8ee0-7f0b5e976260, authority design
;; 019f9358-5617-7db6-8662-8ac7556717e8 (RECONCILED DESIGN + REVISION 2).
;;
;;   bb -cp out tests/world_kernel_test.clj        # from the repo ROOT
;;
;; THIS SUITE IS EXPECTED TO FAIL TODAY. src/fram/world.bclj is a graph-upstream
;; SEED (ns form only) and out/fram/world.clj does not exist, so every kernel
;; call below reports "ABSENT". That is the deliverable: the contract is stated
;; before the kernel exists, and each bar fails INDIVIDUALLY with a named reason
;; instead of the suite dying at load time. Do not weaken a bar to make it pass;
;; implement fram.world through the mcp__fram graph-edit verbs (the .bclj carries
;; the @upstream:graph sentinel — a text edit would desync the graph).
;;
;; WHAT A WORLD IS. A world is a versioned VIEW over the fact graph, never a
;; copy. A Version is IMMUTABLE and content-addressed as (immutable base
;; VersionId + canonical sparse overlay); an overlay entry is PUT(mode, BlobId),
;; DELETE, or INHERIT. Fork therefore appends ONE head fact pointing at an
;; existing VersionId — O(1), with no blob and no manifest copy. Canonical-ness
;; is itself a facts layer, so head succession is DERIVED from the version layer
;; rather than stored.
;;
;; NORMATIVE SURFACE this suite pins (all in fram.world; every durable id is a
;; stable name or a DOMAIN-SEPARATED lowercase SHA-256, 64 hex chars — the
;; persisted process-local `cid` object of the existing view/election code is
;; explicitly forbidden as world format):
;;
;;   max-blob-bytes 524288 · max-record-bytes 786432 · max-name-bytes 63
;;   max-slot-bytes 1024
;;   (blob-id ^bytes raw)                -> 64-hex BlobId
;;   (blob-b64 ^bytes raw)               -> canonical RFC 4648 base64, padded, no ws
;;   (render-record m)                   -> canonical UTF-8 EDN string (key order fixed)
;;   (record-bytes m)                    -> UTF-8 BYTE length of (render-record m)
;;   (validate-world-name s) (validate-slot s) (validate-blob ^bytes raw)
;;   (validate-record m)                 -> nil when admissible, else {:reject <kw> ..}
;;   (put-op slot mode blob-id) (delete-op slot) (inherit-op slot)
;;   (overlay-of ops)                    -> canonical sparse overlay, ONE entry per
;;                                          slot, highest operation index wins
;;   (version-record base-version-id ops) -> immutable Version record
;;   (version-id base-version-id ops)     -> 64-hex VersionId
;;   (resolve-slot versions version-id slot)
;;                                       -> {:present true :mode m :blob-id b :origin v}
;;                                          | {:present false :origin v-or-nil}
;;   (manifest versions version-id)      -> present slots, slot-BYTE-sorted, w/ origins
;;   (compose versions base-version-id selections) -> an ORDINARY Version record
;;   (candidate-id world-name expected-head nonce) -> 64-hex CandidateId
;;   (nonce-hex? s)                      -> true only for exactly 32 lowercase hex
;;   (fork-head world-name version-id)  -> the ONE head fact a fork appends
;;   (lock-record version-id build-spec) (world-lock-id version-id build-spec)
;;   (derive-head claims world-name)     -> derived head VersionId (nil when unknown)
;;
;; `versions` is a plain map VersionId -> Version record. Base `nil` means the
;; canonical empty root (no base), so resolution is TOTAL for every slot.
;;
;; SCOPE. Promotion CAS, receipt gating and zero-head-movement-on-rejection are
;; specified in tests/world_promotion_test.clj; log replay / torn tail / cold
;; restart in tests/world_persistence_test.clj; Git projection in
;; tests/world_git_projection_test.clj. This file is the PURE kernel only: it
;; touches no log, no socket and no filesystem.
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; harness — torn_tail_test.clj's failures atom + [PASS]/[FAIL] lines, extended
;; with a per-bar reason so an ABSENT kernel fn names itself instead of taking
;; the whole suite down. `bar` catches Throwable: absence and misbehaviour are
;; the same contract failure, reported at bar granularity.
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

(def kernel
  (try (require 'fram.world) {:ok true}
       (catch Throwable _ {:ok false :why "fram.world is not on the classpath (seed only)"})))

(defn kvar [nm]
  (when (:ok kernel) (try (ns-resolve 'fram.world (symbol nm)) (catch Throwable _ nil))))

(defn kv
  "The Var for fram.world/NM, or throw a per-bar-catchable absence."
  [nm]
  (or (kvar nm)
      (throw (ex-info (str "fram.world/" nm " ABSENT"
                           (when-not (:ok kernel) (str " — " (:why kernel))))
                      {:missing nm}))))

(defn k [nm & args] (apply (kv nm) args))    ; call a kernel fn
(defn kd [nm] @(kv nm))                      ; deref a kernel constant

(defmacro bar [label & body]
  `(let [r# (try {:ok (boolean (do ~@body))}
                 (catch Throwable e# {:why (or (ex-message e#) (str e#))}))]
     (check ~label (:ok r#) (:why r#))))

;; --- test-side primitives (self-checked in section 0, so a FAIL is never ours)
(defn b8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn blen ^long [^String s] (long (alength (b8 s))))
(defn sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))
(defn b64 [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))
(defn hex64? [x] (boolean (and (string? x) (re-matches #"[0-9a-f]{64}" x))))
(defn arg-names [v] (map name (mapcat identity (:arglists (meta v)))))

(defn byte-lex
  "UTF-8 byte-lexicographic order. NOT clojure.core/compare over (vec bytes):
   vector compare is count-first, and Java bytes are SIGNED, so both get
   multi-byte paths wrong. Canonical overlay order is exactly this."
  [^String a ^String b]
  (let [x (b8 a) y (b8 b) n (min (alength x) (alength y))]
    (loop [i 0]
      (if (= i n)
        (compare (alength x) (alength y))
        (let [c (compare (bit-and 0xff (aget x i)) (bit-and 0xff (aget y i)))]
          (if (zero? c) (recur (inc i)) c))))))

;; --- fixtures ---------------------------------------------------------------
(def mode "100644")
(def slot-a "src/app/core.bclj")
(def slot-b "src/app/util.bclj")
(def slot-utf8 "src/app/café-☕.bclj")           ; multi-byte: byte len > char len
(def raw-a (b8 "(ns app.core)\n"))
(def raw-b (b8 "(ns app.util)\n"))
(def raw-c (b8 "(ns app.core) ;; edited\n"))
(def build-spec
  {:adapter "beagle" :toolchain "sha256:tc" :platform "x86_64-linux"
   :entrypoint "app.core/-main" :purpose "test" :argv []
   :env {} :locale "C" :timezone "UTC" :epoch 0 :random "none" :network "none"})

(println "worlds kernel — executable specification (fram.world)")
(when-not (:ok kernel)
  (println (str "  NOTE: " (:why kernel)
                " — every kernel bar below FAILS by absence, as expected of a seed.")))

;; ===========================================================================
(println "\n-- 0. harness self-check (must PASS: proves later FAILs are the kernel) --")
;; ===========================================================================
(bar "self: SHA-256 of \"abc\" is the known vector"
     (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        (sha256-hex (b8 "abc"))))
(bar "self: base64 of 524288 bytes is exactly 699052 chars (the design's figure)"
     (= 699052 (count (b64 (byte-array 524288)))))
(bar "self: a multi-byte slot path has byte length > char length"
     (> (blen slot-utf8) (count slot-utf8)))

;; ===========================================================================
(println "\n-- 1. stable content IDs (domain-separated SHA-256, no process state) --")
;; ===========================================================================
(bar "id: BlobId is 64 lowercase hex" (hex64? (k "blob-id" raw-a)))
(bar "id: BlobId is stable across calls" (= (k "blob-id" raw-a) (k "blob-id" raw-a)))
(bar "id: BlobId is of the BYTES only — a fresh identical array hashes equal"
     (= (k "blob-id" raw-a) (k "blob-id" (b8 "(ns app.core)\n"))))
(bar "id: distinct bytes -> distinct BlobId" (not= (k "blob-id" raw-a) (k "blob-id" raw-c)))
(bar "id: the EMPTY blob has an id (0 bytes is a legal blob)"
     (hex64? (k "blob-id" (byte-array 0))))
;; design: \"every durable BlobId/VersionId/CandidateId/... is a stable name or
;; DOMAIN-SEPARATED lowercase SHA-256\". Bare content hashing lets one byte string
;; collide across record kinds; a domain tag forbids that.
(bar "id: BlobId is DOMAIN-SEPARATED — NOT the bare SHA-256 of the raw bytes"
     (not= (sha256-hex raw-a) (k "blob-id" raw-a)))
(bar "id: VersionId is 64 lowercase hex"
     (hex64? (k "version-id" nil [(k "put-op" slot-a mode (k "blob-id" raw-a))])))
(bar "id: VersionId is stable across calls"
     (let [ops [(k "put-op" slot-a mode (k "blob-id" raw-a))]]
       (= (k "version-id" nil ops) (k "version-id" nil ops))))
(bar "id: VersionId is INDEPENDENT of op input order (canonical overlay first)"
     (let [pa (k "put-op" slot-a mode (k "blob-id" raw-a))
           pb (k "put-op" slot-b mode (k "blob-id" raw-b))]
       (= (k "version-id" nil [pa pb]) (k "version-id" nil [pb pa]))))
(bar "id: VersionId is INDEPENDENT of the host collection type (vector/list/seq)"
     (let [pa (k "put-op" slot-a mode (k "blob-id" raw-a))
           pb (k "put-op" slot-b mode (k "blob-id" raw-b))]
       (= (k "version-id" nil [pa pb])
          (k "version-id" nil (list pa pb))
          (k "version-id" nil (seq [pa pb])))))
(bar "id: VersionId binds the BASE — the same overlay on another base differs"
     (let [ops [(k "put-op" slot-a mode (k "blob-id" raw-a))]
           v1  (k "version-id" nil ops)]
       (not= v1 (k "version-id" v1 ops))))
(bar "id: VersionId binds the MODE — 100755 vs 100644 differ"
     (not= (k "version-id" nil [(k "put-op" slot-a "100644" (k "blob-id" raw-a))])
           (k "version-id" nil [(k "put-op" slot-a "100755" (k "blob-id" raw-a))])))
(bar "id: canonical render round-trips through EDN and re-hashes identically"
     (let [ops [(k "put-op" slot-a mode (k "blob-id" raw-a)) (k "delete-op" slot-b)]
           rec (k "version-record" nil ops)
           rt  (edn/read-string (k "render-record" rec))]
       (and (= rec rt)
            (= (k "version-id" nil ops) (k "version-id" (:base rt) (:overlay rt))))))
(bar "id: an overlay already in canonical form is a FIXPOINT of overlay-of"
     (let [ov (k "overlay-of" [(k "put-op" slot-b mode (k "blob-id" raw-b))
                               (k "put-op" slot-a mode (k "blob-id" raw-a))])]
       (= ov (k "overlay-of" ov))))
(bar "id: if a Version record carries its own :id it EQUALS the computed VersionId"
     (let [ops [(k "put-op" slot-a mode (k "blob-id" raw-a))]
           rec (k "version-record" nil ops)]
       (or (not (contains? rec :id)) (= (:id rec) (k "version-id" nil ops)))))
(bar "id: cross-KIND separation — a blob OF a rendered Version record != that VersionId"
     (let [ops [(k "put-op" slot-a mode (k "blob-id" raw-a))]
           rec (k "version-record" nil ops)]
       (not= (k "blob-id" (b8 (k "render-record" rec))) (k "version-id" nil ops))))
(bar "id: cross-KIND separation — a CandidateId never equals a VersionId"
     (let [v0 (k "version-id" nil [])]
       (not= (k "candidate-id" "a" v0 (apply str (repeat 32 "0")))
             (k "version-id" v0 []))))
(bar "id: NO process-local cid leaks into a rendered record (forbidden as format)"
     (let [rec (k "version-record" nil [(k "put-op" slot-a mode (k "blob-id" raw-a))])]
       (nil? (re-find #"(?i)\bcid\b" (k "render-record" rec)))))

;; ===========================================================================
(println "\n-- 2. nonce-distinct candidates (REVISION 2 item 1) --")
;; ===========================================================================
(def n1 "0123456789abcdef0123456789abcdef")
(def n2 "fedcba9876543210fedcba9876543210")
(bar "nonce: a 32-lowercase-hex nonce is accepted" (true? (k "nonce-hex?" n1)))
(bar "nonce: 31 hex chars are REJECTED" (false? (k "nonce-hex?" (subs n1 0 31))))
(bar "nonce: 33 hex chars are REJECTED" (false? (k "nonce-hex?" (str n1 "0"))))
(bar "nonce: UPPERCASE hex is REJECTED (lowercase is normative)"
     (false? (k "nonce-hex?" (str/upper-case n1))))
(bar "nonce: non-hex is REJECTED" (false? (k "nonce-hex?" (apply str (repeat 32 "z")))))
;; the exact falsifier REVISION 2 exists to close: two candidates begun on the
;; same world at the same V0 must not share an identity.
(bar "candidate: two candidates on the SAME world at the SAME V0 get DISTINCT ids"
     (let [v0 (k "version-id" nil [])]
       (not= (k "candidate-id" "A" v0 n1) (k "candidate-id" "A" v0 n2))))
(bar "candidate: CandidateId is 64 lowercase hex"
     (hex64? (k "candidate-id" "A" (k "version-id" nil []) n1)))
(bar "candidate: id is REPRODUCIBLE for an injected nonce (tests can pin it)"
     (let [v0 (k "version-id" nil [])]
       (= (k "candidate-id" "A" v0 n1) (k "candidate-id" "A" v0 n1))))
(bar "candidate: id binds the WorldName"
     (let [v0 (k "version-id" nil [])]
       (not= (k "candidate-id" "A" v0 n1) (k "candidate-id" "B" v0 n1))))
(bar "candidate: id binds the expectedHead basis"
     (let [v0 (k "version-id" nil [])
           v1 (k "version-id" v0 [(k "delete-op" slot-a)])]
       (not= (k "candidate-id" "A" v0 n1) (k "candidate-id" "A" v1 n1))))
;; the fn is resolved BEFORE the inner try, so an ABSENT candidate-id can never
;; be mistaken for the rejection this bar is looking for.
(bar "candidate: an inadmissible nonce is REJECTED, never silently hashed"
     (let [f  (kv "candidate-id")
           v0 (k "version-id" nil [])]
       (try (f "A" v0 (str/upper-case n1)) false
            (catch Throwable _ true))))
(bar "nonce: randomness is candidate identity ONLY — distinct nonces, identical ops, SAME VersionId"
     (let [v0  (k "version-id" nil [])
           ops [(k "put-op" slot-a mode (k "blob-id" raw-a))]]
       (and (not= (k "candidate-id" "A" v0 n1) (k "candidate-id" "A" v0 n2))
            (= (k "version-id" v0 ops) (k "version-id" v0 ops)))))
(bar "nonce: version-id takes NO nonce/candidate parameter (determinism is structural)"
     (not-any? #(re-find #"(?i)nonce|candidate|random" %) (arg-names (kv "version-id"))))
(bar "nonce: a nonce never appears in a rendered WorldLock (never a build input)"
     (let [v0  (k "version-id" nil [])
           txt (k "render-record" (k "lock-record" v0 build-spec))]
       (and (not (str/includes? txt n1))
            (not (str/includes? txt (k "candidate-id" "A" v0 n1))))))
(bar "lock: WorldLockId is a function of (version, build spec) only"
     (let [v0 (k "version-id" nil [])]
       (and (hex64? (k "world-lock-id" v0 build-spec))
            (= (k "world-lock-id" v0 build-spec) (k "world-lock-id" v0 build-spec))
            (not-any? #(re-find #"(?i)nonce|candidate" %)
                      (arg-names (kv "world-lock-id"))))))

;; ===========================================================================
(println "\n-- 3. fail-before-append blob and record bounds (REVISION 2 item 2) --")
;; ===========================================================================
(bar "bound: max-blob-bytes is EXACTLY 524288" (= 524288 (kd "max-blob-bytes")))
(bar "bound: max-record-bytes is EXACTLY 786432" (= 786432 (kd "max-record-bytes")))
(bar "bound: max-name-bytes is EXACTLY 63" (= 63 (kd "max-name-bytes")))
(bar "bound: max-slot-bytes is EXACTLY 1024" (= 1024 (kd "max-slot-bytes")))
;; coord_daemon.clj:290 (def ^:const max-line-bytes (* 1024 1024)) is the wire cap
;; the world record must stay strictly under, with the margin the design states.
(bar "bound: the record ceiling is exactly 262144 B below the 1 MiB wire cap"
     (= 262144 (- (* 1024 1024) (kd "max-record-bytes"))))
(bar "bound: a max blob's base64 (699052 B) leaves exactly 87380 B of envelope budget"
     (= 87380 (- (kd "max-record-bytes") 699052)))
(bar "blob: 0 bytes is ADMISSIBLE" (nil? (k "validate-blob" (byte-array 0))))
(bar "blob: exactly 524288 bytes is ADMISSIBLE (0..524288 INCLUSIVE)"
     (nil? (k "validate-blob" (byte-array 524288))))
(bar "blob: 524289 bytes is REJECTED :world-blob-too-large"
     (= :world-blob-too-large (:reject (k "validate-blob" (byte-array 524289)))))
(bar "blob: the rejection reports the observed size and the cap"
     (let [r (k "validate-blob" (byte-array 524289))]
       (and (= 524289 (:bytes r)) (= 524288 (:max r)))))
(bar "blob: rejection is a VALUE, not a throw (fail BEFORE append, never mid-write)"
     (map? (k "validate-blob" (byte-array 600000))))
(bar "blob: a rejection mints NO id (nothing is addressed before it is admissible)"
     (let [r (k "validate-blob" (byte-array 524289))]
       (and (not (contains? r :id)) (not (contains? r :blob-id)))))
(bar "blob: validation is PURE — twice over the same bytes is identical"
     (= (k "validate-blob" (byte-array 524289)) (k "validate-blob" (byte-array 524289))))
(bar "blob: canonical base64 is RFC 4648 WITH padding and NO whitespace"
     (let [s (k "blob-b64" (byte-array 524288))]
       (and (= 699052 (count s))
            (boolean (re-matches #"[A-Za-z0-9+/]+={0,2}" s))
            (not (re-find #"\s" s)))))
(bar "blob: base64 round-trips the exact raw bytes"
     (java.util.Arrays/equals ^bytes raw-a
                              ^bytes (.decode (java.util.Base64/getDecoder)
                                              ^String (k "blob-b64" raw-a))))
(bar "record: record-bytes is the UTF-8 BYTE length of the render, not chars"
     (let [rec (k "version-record" nil [(k "put-op" slot-utf8 mode (k "blob-id" raw-a))])]
       (and (= (blen (k "render-record" rec)) (k "record-bytes" rec))
            (> (k "record-bytes" rec) (count (k "render-record" rec))))))
(bar "record: a render within the ceiling is ADMISSIBLE"
     (nil? (k "validate-record"
              (k "version-record" nil [(k "put-op" slot-a mode (k "blob-id" raw-a))]))))
;; ~1400 individually ADMISSIBLE slots overrun the record ceiling: the RECORD cap
;; is checked on the rendered envelope, independently of every per-field cap.
(bar "record: a render OVER 786432 B is REJECTED :world-record-too-large"
     (let [bid (k "blob-id" raw-a)
           ops (mapv (fn [i]
                       (k "put-op"
                          (str "src/pkg" i "/" (apply str (repeat 500 \x)) ".bclj")
                          mode bid))
                     (range 1400))
           rec (k "version-record" nil ops)]
       (and (> (k "record-bytes" rec) 786432)
            (= :world-record-too-large (:reject (k "validate-record" rec))))))
(bar "name: 63 ASCII bytes is ADMISSIBLE"
     (nil? (k "validate-world-name" (apply str (repeat 63 "a")))))
(bar "name: 64 ASCII bytes is REJECTED :world-name-too-long"
     (= :world-name-too-long (:reject (k "validate-world-name" (apply str (repeat 64 "a"))))))
(bar "name: the cap is BYTES not chars — 40 two-byte chars (80 B) is REJECTED"
     (= :world-name-too-long (:reject (k "validate-world-name" (apply str (repeat 40 "é"))))))
(bar "slot: 1024 normalized UTF-8 bytes is ADMISSIBLE"
     (nil? (k "validate-slot" (apply str (repeat 1024 "a")))))
(bar "slot: 1025 bytes is REJECTED :world-slot-too-long"
     (= :world-slot-too-long (:reject (k "validate-slot" (apply str (repeat 1025 "a"))))))
(bar "slot: the cap is BYTES not chars — 1024 two-byte chars (2048 B) is REJECTED"
     (= :world-slot-too-long (:reject (k "validate-slot" (apply str (repeat 1024 "é"))))))

;; ===========================================================================
(println "\n-- 4. PUT / DELETE / INHERIT precedence (total tri-state resolution) --")
;; ===========================================================================
;; chain: v1 PUTs a+b · v2 DELETEs a and re-PUTs b (mode 100755) · v3 INHERITs both
(def bid-a (delay (k "blob-id" raw-a)))
(def bid-b (delay (k "blob-id" raw-b)))
(def bid-c (delay (k "blob-id" raw-c)))
(def chain
  (delay
    (let [o1 [(k "put-op" slot-a mode @bid-a) (k "put-op" slot-b mode @bid-b)]
          v1 (k "version-id" nil o1)
          r1 (k "version-record" nil o1)
          o2 [(k "delete-op" slot-a) (k "put-op" slot-b "100755" @bid-c)]
          v2 (k "version-id" v1 o2)
          r2 (k "version-record" v1 o2)
          o3 [(k "inherit-op" slot-a) (k "inherit-op" slot-b)]
          v3 (k "version-id" v2 o3)
          r3 (k "version-record" v2 o3)]
      {:versions {v1 r1 v2 r2 v3 r3} :v1 v1 :v2 v2 :v3 v3})))
(defn rs [vk slot]
  (let [c @chain] (k "resolve-slot" (:versions c) (get c vk) slot)))

(bar "resolve: PUT resolves to the EXACT BlobId and mode"
     (let [r (rs :v1 slot-a)]
       (and (true? (:present r)) (= @bid-a (:blob-id r)) (= mode (:mode r)))))
(bar "resolve: DELETE resolves ABSENT even though the base PUT it"
     (false? (:present (rs :v2 slot-a))))
(bar "resolve: NO ENTRY recurses to the immediate base"
     (let [c   @chain
           ops [(k "put-op" "src/app/new.bclj" mode @bid-c)]
           v4  (k "version-id" (:v1 c) ops)
           r4  (k "version-record" (:v1 c) ops)
           r   (k "resolve-slot" (assoc (:versions c) v4 r4) v4 slot-a)]
       (and (true? (:present r)) (= @bid-a (:blob-id r)))))
(bar "resolve: INHERIT is EXPLICIT and behaves exactly like no entry"
     (let [r (rs :v3 slot-b)]
       (and (true? (:present r)) (= @bid-c (:blob-id r)) (= "100755" (:mode r)))))
(bar "resolve: INHERIT recurses THROUGH a DELETE and stays absent (multi-level)"
     (false? (:present (rs :v3 slot-a))))
(bar "resolve: an unknown slot on the empty root is ABSENT, not an error (TOTAL)"
     (let [v0 (k "version-id" nil [])
           r0 (k "version-record" nil [])]
       (false? (:present (k "resolve-slot" {v0 r0} v0 "src/never/seen.bclj")))))
(bar "resolve: the NEAREST overlay decides — v2's PUT beats v1's inherited PUT"
     (= "100755" (:mode (rs :v2 slot-b))))
;; locks carry origin versions, so resolution must report WHICH version supplied
;; the live entry — the reading version is not the answer.
(bar "resolve: :origin names the Version whose overlay SUPPLIED the entry"
     (let [c @chain] (= (:v2 c) (:origin (rs :v3 slot-b)))))
(bar "resolve: :origin of a slot living in the ROOT of the chain is that root"
     (let [c @chain] (= (:v1 c) (:origin (rs :v1 slot-a)))))
(bar "overlay: HIGHEST candidate operation index wins BEFORE canonical seal"
     (= 1 (count (k "overlay-of" [(k "put-op" slot-a mode @bid-a)
                                  (k "delete-op" slot-a)
                                  (k "put-op" slot-a "100755" @bid-c)]))))
(bar "overlay: last-op-wins keeps the LAST op's exact payload"
     (let [ops [(k "put-op" slot-a mode @bid-a) (k "put-op" slot-a "100755" @bid-c)]
           v   (k "version-id" nil ops)
           r   (k "version-record" nil ops)
           x   (k "resolve-slot" {v r} v slot-a)]
       (and (= @bid-c (:blob-id x)) (= "100755" (:mode x)))))
(bar "overlay: a later DELETE supersedes an earlier PUT on the same slot"
     (let [ops [(k "put-op" slot-a mode @bid-a) (k "delete-op" slot-a)]
           v   (k "version-id" nil ops)
           r   (k "version-record" nil ops)]
       (false? (:present (k "resolve-slot" {v r} v slot-a)))))
(bar "overlay: the canonical overlay is SPARSE — one entry per DISTINCT slot"
     (= 2 (count (k "overlay-of" [(k "put-op" slot-a mode @bid-a)
                                  (k "put-op" slot-b mode @bid-b)
                                  (k "delete-op" slot-a)]))))
(bar "overlay: canonical order is by slot UTF-8 BYTES (deterministic, not map order)"
     (let [slots ["src/z.bclj" "src/a.bclj" "src/M.bclj" slot-utf8]
           ov    (k "overlay-of" (map #(k "delete-op" %) slots))]
       (= (mapv :slot ov) (vec (sort byte-lex slots)))))
(bar "manifest: only PRESENT slots, slot-sorted, with origin versions"
     (let [c @chain
           m (k "manifest" (:versions c) (:v3 c))]
       (and (= [slot-b] (mapv :slot m))
            (= [@bid-c] (mapv :blob-id m))
            (= [(:v2 c)] (mapv :origin m)))))
(bar "manifest: the empty root has an EMPTY manifest"
     (let [v0 (k "version-id" nil [])]
       (= [] (vec (k "manifest" {v0 (k "version-record" nil [])} v0)))))
(bar "manifest: is a pure function of the version — twice is identical"
     (let [c @chain]
       (= (k "manifest" (:versions c) (:v3 c)) (k "manifest" (:versions c) (:v3 c)))))

;; ===========================================================================
(println "\n-- 5. deterministic ORDINARY-Version composition --")
;; ===========================================================================
;; A = va (a=raw-a, b=raw-b) · B = vb, a fork of A that edited slot-a -> raw-c
;; C = compose(base A, {slot-a from B}) — a MIXED version, ORDINARY in form.
(def comp-fx
  (delay
    (let [oa [(k "put-op" slot-a mode @bid-a) (k "put-op" slot-b mode @bid-b)]
          va (k "version-id" nil oa)
          ra (k "version-record" nil oa)
          ob [(k "put-op" slot-a mode @bid-c)]
          vb (k "version-id" va ob)
          rb (k "version-record" va ob)]
      {:versions {va ra vb rb} :va va :vb vb})))
(defn cvid [c] (k "version-id" (:base c) (:overlay c)))

(bar "compose: is DETERMINISTIC — two calls yield an equal record"
     (let [{:keys [versions va vb]} @comp-fx]
       (= (k "compose" versions va [[slot-a vb]])
          (k "compose" versions va [[slot-a vb]]))))
(bar "compose: is INDEPENDENT of selection input order"
     (let [{:keys [versions va vb]} @comp-fx]
       (= (k "compose" versions va [[slot-a vb] [slot-b va]])
          (k "compose" versions va [[slot-b va] [slot-a vb]]))))
(bar "compose: yields an ORDINARY Version record — no composition marker survives"
     (let [{:keys [versions va vb]} @comp-fx
           c   (k "compose" versions va [[slot-a vb]])
           ord (k "version-record" nil [(k "delete-op" slot-a)])]
       (= (set (keys c)) (set (keys ord)))))
(bar "compose: the composed record is re-derivable from its own (base, overlay)"
     (let [{:keys [versions va vb]} @comp-fx]
       (hex64? (cvid (k "compose" versions va [[slot-a vb]])))))
(bar "compose: the SELECTED slot resolves to the SOURCE's exact BlobId"
     (let [{:keys [versions va vb]} @comp-fx
           c  (k "compose" versions va [[slot-a vb]])
           vc (cvid c)
           r  (k "resolve-slot" (assoc versions vc c) vc slot-a)]
       (and (true? (:present r)) (= @bid-c (:blob-id r)))))
(bar "compose: an UNSELECTED slot INHERITS the exact base (no copy, no drift)"
     (let [{:keys [versions va vb]} @comp-fx
           c  (k "compose" versions va [[slot-a vb]])
           vc (cvid c)
           r  (k "resolve-slot" (assoc versions vc c) vc slot-b)]
       (and (true? (:present r)) (= @bid-b (:blob-id r)) (= va (:origin r)))))
(bar "compose: the overlay stays SPARSE — never a materialized whole manifest"
     (let [{:keys [versions va vb]} @comp-fx]
       (<= (count (:overlay (k "compose" versions va [[slot-a vb]]))) 1)))
;; \"compose resolves each selected slot from its EXACT source\": if the source
;; lacks the slot, exactness against a base that HAS it requires a DELETE entry,
;; not a skip. (Derived from the design clause, not quoted from it.)
(bar "compose: selecting a slot the SOURCE lacks records an EXPLICIT DELETE"
     (let [{:keys [versions va]} @comp-fx
           od [(k "delete-op" slot-b)]
           vd (k "version-id" va od)
           rd (k "version-record" va od)
           vs (assoc versions vd rd)
           c  (k "compose" vs va [[slot-b vd]])
           vc (cvid c)]
       (false? (:present (k "resolve-slot" (assoc vs vc c) vc slot-b)))))
(bar "compose: ZERO selections reproduces the base's manifest exactly"
     (let [{:keys [versions va]} @comp-fx
           c  (k "compose" versions va [])
           vc (cvid c)]
       (= (mapv (juxt :slot :blob-id) (k "manifest" versions va))
          (mapv (juxt :slot :blob-id) (k "manifest" (assoc versions vc c) vc)))))
(bar "compose: host-map insertion order of `versions` never leaks into the id"
     (let [{:keys [versions va vb]} @comp-fx
           shuffled (into (sorted-map) (reverse (seq versions)))]
       (= (cvid (k "compose" versions va [[slot-a vb]]))
          (cvid (k "compose" shuffled va [[slot-a vb]])))))

;; ===========================================================================
(println "\n-- 6. O(1) fork: NO blob copy, NO manifest copy --")
;; ===========================================================================
(def big-base
  (delay
    (let [ops (mapv (fn [i] (k "put-op" (str "src/pkg/f" i ".bclj") mode @bid-a)) (range 512))
          v   (k "version-id" nil ops)
          r   (k "version-record" nil ops)]
      {:v v :r r :versions {v r}})))
(def small-base
  (delay
    (let [ops [(k "put-op" slot-a mode @bid-a)]
          v   (k "version-id" nil ops)
          r   (k "version-record" nil ops)]
      {:v v :r r :versions {v r}})))

(bar "fork: appends exactly ONE head claim record"
     (map? (k "fork-head" "B" (:v @small-base))))
(bar "fork: the claim carries the EXACT base VersionId (a fork is a reference)"
     (let [v (:v @small-base)]
       (str/includes? (k "render-record" (k "fork-head" "B" v)) v)))
;; the STRUCTURAL O(1) proof: fork-head is handed the name and the VersionId and
;; nothing else, so it has no blob store, no versions map and no manifest to copy.
(bar "fork: takes only (world-name version-id) — it CANNOT copy what it cannot see"
     (let [al (:arglists (meta (kv "fork-head")))]
       (and (seq al)
            (every? #(= 2 (count %)) al)
            ;; plural/collection words only: a param legitimately named
            ;; `version-id` is the POINT of the fork, `versions` would be the leak
            (not-any? #(re-find #"(?i)store|versions|blobs|bytes|manifest|slots|overlay" %)
                      (arg-names (kv "fork-head"))))))
(bar "fork: the claim is BYTE-CONSTANT in base size (512-slot base == 1-slot base)"
     (= (blen (k "render-record" (k "fork-head" "B" (:v @small-base))))
        (blen (k "render-record" (k "fork-head" "B" (:v @big-base))))))
(bar "fork: the claim is under 512 B in absolute terms regardless of base size"
     (< (blen (k "render-record" (k "fork-head" "B" (:v @big-base)))) 512))
(bar "fork: NO BLOB COPY — the claim's render contains no BlobId from the base"
     (not (str/includes? (k "render-record" (k "fork-head" "B" (:v @big-base))) @bid-a)))
(bar "fork: NO MANIFEST COPY — the claim's render contains no base slot path"
     (let [txt (k "render-record" (k "fork-head" "B" (:v @big-base)))]
       (and (not (str/includes? txt "src/pkg/f0.bclj"))
            (not (str/includes? txt "src/pkg/f511.bclj")))))
(bar "fork: the claim carries NO overlay, manifest, blobs or slots key at all"
     (let [c (k "fork-head" "B" (:v @big-base))]
       (and (not (contains? c :overlay)) (not (contains? c :manifest))
            (not (contains? c :blobs)) (not (contains? c :slots)))))
(bar "fork: MINTS NO NEW VERSION — a head claim is not shaped like a Version record"
     (let [c (k "fork-head" "B" (:v @small-base))
           v (k "version-record" nil [(k "put-op" slot-a mode @bid-a)])]
       (not= (set (keys c)) (set (keys v)))))
(bar "fork: the forked name's DERIVED head IS the base VersionId (two names, one Version)"
     (let [v (:v @big-base)]
       (= v (k "derive-head" [(k "fork-head" "B" v)] "B"))))
(bar "fork: forking leaves the versions map untouched; both names read one manifest"
     (let [{:keys [v versions]} @big-base
           before versions]
       (k "fork-head" "B" v)
       (and (= before versions)
            (= 512 (count (k "manifest" versions v))))))
(bar "fork: an unknown world's derived head is nil (heads are DERIVED, never stored)"
     (nil? (k "derive-head" [] "nope")))

;; ---------------------------------------------------------------------------
(let [pass (- @total @failures)]
  (println (str "\nworld-kernel: " pass "/" @total " PASS"))
  (if (zero? @failures)
    (println "world-kernel: ALL BARS PASS")
    (do (println (str "world-kernel: " @failures " FAILED — these bars DEFINE the"
                      " kernel contract; fram.world is still a graph seed."
                      " Implement it via the mcp__fram graph-edit verbs."))
        (System/exit 1))))
