;; rotations.clj — the covering read index: SPO / POS / OSP over ONE flat value
;; space, maintained as in-memory NOVELTY merged over content-addressed immutable
;; sorted SEGMENTS.  Loaded by coord_daemon.clj as a library (ns `rotations`).
;; ============================================================================
;; WHY THREE ROTATIONS ARE EXACTLY ENOUGH
;;
;; Fram interns subject, predicate and object into ONE value space — there is no
;; privileged slot, so (unlike RDF/Datomic) there is no VAET special case to add.
;; A triple pattern binds some subset of {s,p,o}; the index must serve every one
;; of the 8 subsets by an exact prefix probe.  Order the three rotations
;; cyclically — SPO, POS, OSP — and every 1-element and 2-element subset is a
;; PREFIX of exactly one of them:
;;
;;   {}        -> :tuples (the whole relation)
;;   {s}       -> SPO prefix (s)          {s,p}   -> SPO prefix (s,p)
;;   {p}       -> POS prefix (p)          {p,o}   -> POS prefix (p,o)
;;   {o}       -> OSP prefix (o)          {o,s}   -> OSP prefix (o,s)
;;   {s,p,o}   -> membership test
;;
;; Three cyclic rotations cover all six proper prefixes because the 3-cycle
;; (s p o) acts transitively on both the 1-subsets and the 2-subsets.  Adding a
;; fourth rotation would be redundant; dropping one would leave a subset — in
;; practice {o,s}, the "who points at this value, with which predicate" question
;; the catalog/membership scans ask — with no exact bucket.
;;
;; IN-MEMORY vs ON-DISK REPRESENTATION.  In memory a rotation is a two-level
;; persistent map keyed on the fold's own value objects (the log parse already
;; shares one String per distinct value, so this IS the flat interned space,
;; addressed by reference).  Level 1 is the 1-subset bucket, level 2 the
;; 2-subset bucket; both hold the SAME shared tuple vectors, so the six buckets
;; cost pointers, not copies.  Integer interning appears at the SEGMENT
;; boundary, where it buys fixed-width sortable rows, content addressing, and
;; mmap — see `write-set!` / `open-set` below.
;;
;; THE PROJECTION IS NOT REBUILT.  `datalog-projection` hands fram.datalog the
;; level-1 buckets AS its base index in O(1) — no whole-corpus materialization.
;; That is what removes the O(history) from the read path: a write applies an
;; O(delta) `add`/`del` and the very next query reuses the index, instead of
;; invalidating a whole-corpus projection that then has to be refolded under a
;; 5s deadline it cannot meet.
;;
;; The flat log stays the SOLE source of truth.  Everything here is derived: a
;; segment set can be deleted at any time and the next boot refolds it.
(ns rotations
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set])
  (:import [java.io DataOutputStream FileOutputStream RandomAccessFile]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel FileChannel$MapMode]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

;; ============================================================================
;; PART 1 — the in-memory covering index
;; ============================================================================

(def empty-index
  {:tuples #{}
   ;; level 1: the 1-subset buckets. These are handed to fram.datalog VERBATIM as
   ;; the base index for positions 0/1/2 of the "fact" relation.
   :s {} :p {} :o {}
   ;; level 2: the 2-subset buckets, keyed on the rotation's leading PAIR.
   :sp {} :po {} :os {}
   ;; novelty bookkeeping — triples added/removed since the last published
   ;; segment set. Bounded drain target for the compactor (PART 3).
   :novelty {}
   :watermark -1})

(defn- bucket-add [m k t] (assoc m k (conj (get m k #{}) t)))
;; Drop an emptied bucket rather than leaving k -> #{}: a fresh `build` never
;; emits an empty bucket, so an incremental index that kept one would DIFFER in
;; representation from a from-scratch fold even though every query agrees. The
;; daemon's :warm-check tripwire compares representations, so keep them equal.
(defn- bucket-del [m k t]
  (let [b (disj (get m k #{}) t)] (if (empty? b) (dissoc m k) (assoc m k b))))

(defn add
  "Add one [s p o] triple to every rotation. O(1) amortized."
  [idx t]
  (let [s (nth t 0) p (nth t 1) o (nth t 2)]
    (-> idx
        (update :tuples conj t)
        (update :s bucket-add s t) (update :p bucket-add p t) (update :o bucket-add o t)
        (update :sp bucket-add [s p] t) (update :po bucket-add [p o] t)
        (update :os bucket-add [o s] t))))

(defn del
  "Remove one [s p o] triple from every rotation. O(1) amortized."
  [idx t]
  (let [s (nth t 0) p (nth t 1) o (nth t 2)]
    (-> idx
        (update :tuples disj t)
        (update :s bucket-del s t) (update :p bucket-del p t) (update :o bucket-del o t)
        (update :sp bucket-del [s p] t) (update :po bucket-del [p o] t)
        (update :os bucket-del [o s] t))))

;; Novelty tracking is SEPARATE from the rotations so that turning it on/off can
;; never change a query answer. `note-*` record the newest intent per triple; the
;; compactor drains them.
(defn note-add [idx t version]
  (-> idx (update :novelty assoc t {:version version :present? true})
      (assoc :watermark (max (long (:watermark idx -1)) (long version)))))
(defn note-del [idx t version]
  (-> idx (update :novelty assoc t {:version version :present? false})
      (assoc :watermark (max (long (:watermark idx -1)) (long version)))))
(defn novelty-count [idx] (count (:novelty idx)))
(defn drain-novelty [idx] (assoc idx :novelty {}))

(defn build
  "Bulk-build from a seq of [s p o] triples."
  [triples] (reduce add empty-index triples))

(defn triple-count [idx] (count (:tuples idx)))

;; ---- the covering probe -----------------------------------------------------
;; `pattern` is [s-or-nil p-or-nil o-or-nil]. Returns the EXACT bucket for the
;; bound subset — never a superset that the caller must re-filter, except for the
;; all-unbound case (the genuine full relation).
(defn matching [idx [s p o]]
  (cond
    (and s p o) (if (contains? (:tuples idx) [s p o]) #{[s p o]} #{})
    (and s p)   (get (:sp idx) [s p] #{})
    (and p o)   (get (:po idx) [p o] #{})
    (and o s)   (get (:os idx) [o s] #{})
    s           (get (:s idx) s #{})
    p           (get (:p idx) p #{})
    o           (get (:o idx) o #{})
    :else       (:tuples idx)))

;; The narrowest bucket for a partially-ground literal, WITHOUT materializing a
;; set when nothing is bound (the caller then scans the whole relation, exactly
;; as before). Mirrors `matching` but returns the relation itself for {}.
(defn candidates [idx s p o] (matching idx [s p o]))

;; ---- the O(1) Datalog projection -------------------------------------------
;; fram.datalog's base index is {rel {position {value <candidate-coll>}}} and its
;; ONLY uses of a bucket are `count` (selectivity choice) and `seq` (iteration) —
;; see idx-lookup / indexed-candidates. A persistent SET satisfies both with the
;; same results as the vector `build-index` produces, so the level-1 rotations are
;; a legal base index verbatim. That is the whole trick: the projection the
;; coordinator used to rebuild per version is now three map references.
;;
;; NOTE the deliberate omission of the "fact-id" base relation. Its cids are
;; POSITIONAL (fram.query/facts->edb numbers c0..cN by fold order), so it is not
;; incrementally maintainable without changing cid values. Callers must route a
;; query that mentions fact-id to the whole-corpus projection — see
;; `projectable?`. Absence is invisible to every other query: fram.datalog only
;; consults relations the rules actually name.
(defn datalog-projection [idx]
  {:edb {"fact" (:tuples idx)}
   :base-index {"fact" {0 (:s idx) 1 (:p idx) 2 (:o idx)}}})

;; ============================================================================
;; PART 2 — content-addressed immutable sorted segments
;; ============================================================================
;; One published SET is: a dictionary (the flat value space, id 1..N) plus three
;; rotation segments, each a header + fixed-width sorted rows of three 8-byte
;; ids. Every file is named by the sha256 of its own bytes, so a set is
;; verifiable, shareable, and dovetails with the content-addressed world
;; versions; `latest.edn` is the only mutable byte in the tree and is published
;; by an atomic rename.

(def ^:private magic (.getBytes "FRROT001" StandardCharsets/US_ASCII))
(def format-version 1)
(def ^:private header-bytes 20)   ; 8 magic + 4 rotation tag + 8 row count
(def ^:private row-bytes 24)      ; 3 x int64
(def rotation-perm {:spo [0 1 2] :pos [1 2 0] :osp [2 0 1]})
(def ^:private rotation-tag {:spo 0 :pos 1 :osp 2})
(def ^:private rotation-order [:spo :pos :osp])

(defn index-root
  "Segment tree for a flat log — a sibling directory, never inside the log."
  [flat] (str flat ".rotations"))
(defn manifest-path [root] (str root "/latest.edn"))

(defn- hex [^bytes ds] (apply str (map #(format "%02x" (bit-and % 0xff)) ds)))
(defn- sha256-bytes [^bytes bs] (hex (.digest (MessageDigest/getInstance "SHA-256") bs)))
(defn- sha256-file [path]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [is (io/input-stream (str path))]
      (loop [] (let [n (.read is buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (hex (.digest md))))

(defn- force-write! [path ^bytes bs]
  (with-open [fos (FileOutputStream. (str path))
              out (DataOutputStream. fos)]
    (.write out bs) (.flush out)
    (.force (.getChannel fos) true)))

(defn- atomic-move! [from to]
  (Files/move (.toPath (io/file (str from))) (.toPath (io/file (str to)))
              (into-array java.nio.file.CopyOption
                          [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING])))

;; Content addressing makes the write idempotent: an identical segment already in
;; the store is reused, so republishing an unchanged rotation costs one stat.
(defn- install-content! [root ext ^bytes bs]
  (let [sha (sha256-bytes bs)
        dir (io/file root "segments")
        _ (.mkdirs dir)
        target (io/file dir (str sha ext))]
    (when-not (.exists target)
      (let [tmp (io/file dir (str "." sha "." (System/nanoTime) ".tmp"))]
        (try
          (force-write! tmp bs)
          (try (atomic-move! tmp target)
               (catch java.nio.file.FileAlreadyExistsException _))
          (finally
            (Files/deleteIfExists (.toPath tmp))))))
    {:sha256 sha :file (str "segments/" sha ext) :bytes (.length target)}))

(defn- segment-bytes [rotation id-triples]
  (let [perm (get rotation-perm rotation)
        rows (sort (map (fn [t] [(long (nth t (nth perm 0)))
                                 (long (nth t (nth perm 1)))
                                 (long (nth t (nth perm 2)))])
                        id-triples))
        buf (ByteBuffer/allocate (+ header-bytes (* row-bytes (count rows))))]
    (.put buf ^bytes magic)
    (.putInt buf (int (get rotation-tag rotation)))
    (.putLong buf (long (count rows)))
    (doseq [row rows, x row] (.putLong buf (long x)))
    (.array buf)))

(defn- dictionary-bytes [values]
  (.getBytes (pr-str {:format format-version :values (vec values)}) StandardCharsets/UTF_8))

(defn- write-manifest! [root manifest]
  (.mkdirs (io/file root))
  (let [path (manifest-path root)
        tmp (str path "." (System/nanoTime) ".tmp")]
    (try
      (force-write! tmp (.getBytes (pr-str manifest) StandardCharsets/UTF_8))
      (atomic-move! tmp path)
      manifest
      (finally
        (Files/deleteIfExists (.toPath (io/file tmp)))))))

(defn- value-sort-key [value]
  [(if (nil? value) "" (.getName (class value))) (pr-str value)])

(defn- storage-value! [value]
  (if (or (string? value) (integer? value) (boolean? value) (keyword? value)
          (and (vector? value) (every? integer? value)))
    value
    (throw (ex-info (str "rotation triple contains a non-canonical value: "
                         (if (nil? value) "nil" (.getName (class value))))
                    {:code :rotation-invalid-value :stage :input}))))

(defn write-set!
  "Publish ONE immutable covering segment set for `triples` and atomically swap
   `latest.edn` to it. `metadata` carries the provenance a boot must agree with
   (:watermark, :byte-offset, :fold-fingerprint, :log-identity). Returns the
   manifest."
  [root triples metadata]
  (let [triples (into #{} (map #(mapv storage-value! %)) triples)
        values (vec (sort-by value-sort-key (into #{} cat triples)))
        ids (zipmap values (map inc (range)))          ; flat space: 1..N, 0 reserved
        id-triples (mapv (fn [t] [(ids (nth t 0)) (ids (nth t 1)) (ids (nth t 2))]) triples)
        dict (assoc (install-content! root ".dict" (dictionary-bytes values))
                    :count (count values))
        segments (into {} (for [r rotation-order]
                            [r (assoc (install-content! root ".rot" (segment-bytes r id-triples))
                                      :count (count id-triples) :rotation r)]))]
    (write-manifest! root (merge {:format format-version :dictionary dict
                                  :segments segments :fact-count (count triples)}
                                 metadata))))

(defn read-manifest [root]
  (try
    (let [f (io/file (manifest-path root))]
      (when (and (.exists f) (pos? (.length f))) (edn/read-string (slurp f))))
    (catch Throwable _ nil)))

(defn- abs-file [root rel] (str (io/file (str root) (str rel))))

(defn- content-valid? [root {:keys [file sha256]}]
  (let [f (io/file (abs-file root file))]
    (and (.exists f) (pos? (.length f)) (= sha256 (sha256-file f)))))

(declare open-segment close-set!)

(defn- track-open-segment! [opened root rotation meta]
  (let [segment (open-segment root rotation meta)]
    (try
      (swap! opened assoc rotation segment)
      segment
      (catch Throwable error
        (close-set! {:segments {rotation segment}})
        (throw error)))))

(defn- opened-set-value [root manifest values segments]
  {:root root :manifest manifest
   :values (into [nil] values)                       ; id -> value (id 0 unused)
   :segments segments})

(defn- open-segment [root rotation meta]
  (let [path (abs-file root (:file meta))
        raf (RandomAccessFile. path "r")]
    (try
      (let [ch (.getChannel raf)]
        (try
          (let [len (.length raf)
                buf (.map ch FileChannel$MapMode/READ_ONLY 0 len)
                got (byte-array 8)]
            (.get (.duplicate buf) got)
            (let [dup (.duplicate buf)
                  _ (.position dup 8)
                  tag (.getInt dup)
                  n (.getLong dup)]
              (when-not (and (= (seq magic) (seq got))
                             (= tag (get rotation-tag rotation))
                             (= len (+ header-bytes (* row-bytes n)))
                             (= n (:count meta)))
                (throw (ex-info "invalid rotation segment header"
                                {:rotation rotation :path path})))
              {:rotation rotation :raf raf :channel ch :buf buf :count n}))
          (catch Throwable error
            (try (.close ch) (catch Throwable _))
            (throw error))))
      (catch Throwable error
        (try (.close raf) (catch Throwable _))
        (throw error)))))

(defn open-set
  "Open + fully verify the latest published set. `expected` is a map of manifest
   keys that must agree (fold fingerprint, log identity); any mismatch, missing
   file, or content-hash disagreement returns nil so the caller refolds. Fails
   CLOSED — a bad segment set costs a slow boot, never wrong state."
  [root expected]
  (when-let [manifest (read-manifest root)]
    (when (and (= format-version (:format manifest))
               (every? (fn [[k v]] (= v (get manifest k))) expected)
               (content-valid? root (:dictionary manifest))
               (every? #(content-valid? root (get-in manifest [:segments %])) rotation-order))
      (try
        (let [opened (atom {})
              transferred? (atom false)]
          (try
            (let [dict (edn/read-string
                        (slurp (abs-file root (get-in manifest [:dictionary :file]))))
                  values (:values dict)
                  _ (when-not (and (= format-version (:format dict))
                                   (= (count values)
                                      (get-in manifest [:dictionary :count])))
                      (throw (ex-info "invalid rotation dictionary" {:root root})))
                  _ (doseq [rotation rotation-order]
                      (track-open-segment! opened root rotation
                                           (get-in manifest [:segments rotation])))
                  result (opened-set-value root manifest values @opened)]
              (reset! transferred? true)
              result)
            (catch Throwable _ nil)
            (finally
              (when-not @transferred?
                (close-set! {:segments @opened})))))
        (catch Throwable _ nil)))))

(defn close-set! [opened]
  (doseq [[_ {:keys [channel raf]}] (:segments opened)]
    (try (.close ^FileChannel channel) (catch Throwable _ nil))
    (try (.close ^RandomAccessFile raf) (catch Throwable _ nil))))

(defn- row-long [segment row pos]
  (.getLong ^ByteBuffer (:buf segment) (int (+ header-bytes (* row-bytes row) (* 8 pos)))))

(defn segment-triples
  "Stream the SPO segment back as [s p o] string triples — the boot path. Reads
   ONE rotation (the other two are the same set in a different order), so a
   snapshot boot pays O(facts) mmap reads instead of O(log lines) of EDN parse."
  [opened]
  (let [seg (get-in opened [:segments :spo])
        vals (:values opened)
        n (long (:count seg))]
    (loop [i 0 acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (recur (inc i)
               (conj! acc [(nth vals (int (row-long seg i 0)))
                           (nth vals (int (row-long seg i 1)))
                           (nth vals (int (row-long seg i 2)))]))))))

(defn set-summary [opened]
  (when opened
    {:watermark (get-in opened [:manifest :watermark])
     :byte-offset (get-in opened [:manifest :byte-offset])
     :fact-count (get-in opened [:manifest :fact-count])
     :dictionary-count (get-in opened [:manifest :dictionary :count])
     :segments (into {} (for [r rotation-order]
                          [r (select-keys (get-in opened [:manifest :segments r])
                                          [:sha256 :count :bytes])]))}))

;; ---- garbage collection of unreferenced segments ---------------------------
;; A published set is immutable and content-addressed, so retiring the previous
;; generation is just "delete what latest.edn no longer names". Never touches the
;; live manifest's own files.
(defn gc-segments! [root]
  (let [m (read-manifest root)
        live (into #{} (keep :file) (cons (:dictionary m) (vals (:segments m))))
        dir (io/file root "segments")]
    (if-not (and m (.isDirectory dir))
      0
      (count (for [^java.io.File f (.listFiles dir)
                   :let [rel (str "segments/" (.getName f))]
                   :when (and (not (contains? live rel))
                              (not (.endsWith (.getName f) ".tmp"))
                              (.delete f))]
               rel)))))
