;; fri.clj — FRAM_MMAP_IMAGE V1: the .fri columnar snapshot image (ns fri).
;; ============================================================================
;; The beyond-RAM slice (thread 019f82d9). Today's warm cache duplicates the whole
;; corpus as String facts + a 5-way bucket index (~8.5x raw log bytes resident);
;; an 8GB box saturates at ~1.1-1.7M facts. This is the fram-OWNED columnar image:
;; the checkpoint writer serializes the live reified Store into fixed-width long
;; columns + string dictionaries + sorted postings, and the daemon mmaps it
;; READ-ONLY so the OS page cache — not the JVM heap — holds the cold corpus.
;;
;; Two consumption modes (the daemon picks per op, see coord_daemon.clj):
;;   * mmap-served LOCAL primitives — by-l / by-lp / fact-of / value & name render
;;     answered straight off the MappedByteBuffers, WITHOUT materializing heap. This
;;     is where the RSS win lives.
;;   * lazy heap-materialize — cold->dump reconstructs the exact assemble-dump map
;;     that coord.clj/replay feeds fram.store/load-store!, so any whole-corpus op
;;     (warm rebuild, datalog, as-of, validate) gets a byte-identical heap Store on
;;     first touch. Correctness first; the win is not paying for it until asked.
;;
;; FORMAT (little sketch deltas noted in the lane report):
;;   MAGIC(8) "FRAMFRI1" | fmt(1 int) | <segments, back to back> | FOOTER(edn) | footerOff(8)
;; The footer (read last 8 bytes -> its offset) is the segment table: per-segment
;; {:off :len :sha256} + header fields (covers_seq, next_id, supersedes_pred, counts,
;; fold_fingerprint). Written temp + ATOMIC_MOVE by the caller; per-segment sha lands
;; in the .snap sidecar so boot's hash gate rejects a torn/edited segment -> full fold.
;;
;; Column encodings (all big-endian, ByteBuffer default):
;;   :facts        n rows x [cid:long l:long p:long r:long tx:long], SORTED by cid
;;                 (row index == the dense "ordinal"; cid->ordinal by binary search).
;;   :values-id    n x [id:long blobOff:long blobLen:int], sorted by id  -> literal(id).
;;   :values-str   n x [blobOff:long blobLen:int id:long], sorted by UTF-8 bytes -> value-id.
;;   :values-blob  concatenated UTF-8 value strings.
;;   :entities     sorted entity ids (objects that are neither values nor fact cids).
;;   :txs          n x [tx:long seq:long agentOff:long agentLen:int] + :txs-blob.
;;   :postings-l   n x [lid:long runOff:long runLen:int], sorted by lid; runs -> :pl-runs
;;   :postings-lp  n x [lid:long pid:long runOff:long runLen:int], sorted; runs -> :plp-runs
;;   :pl-runs/:plp-runs  ordinals (int) — row indices into :facts.
;;   :superseded   bitset over ordinals, ceil(nfacts/8) bytes; bit set == superseded.
;; NOT built in V1 (cut, lazy-materialize covers them): by-p / by-r / by-pr postings,
;; a resident name reverse-dict (name-of goes through by-lp on the name pred).
;; ============================================================================
(ns fri
  (:require [clojure.string :as str] [clojure.edn :as edn])
  (:import [java.io RandomAccessFile DataOutputStream BufferedOutputStream FileOutputStream ByteArrayOutputStream]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel FileChannel$MapMode]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def MAGIC "FRAMFRI1")
(def FMT 1)
(def ^:private CHUNK (* 1024 1024 1024))   ; 1 GiB — FileChannel.map ceiling honored per segment

;; ---- writer -----------------------------------------------------------------
(defn- utf8 [^String s] (.getBytes s StandardCharsets/UTF_8))
(defn- sha256-hex [b]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") b)]
    (apply str (map #(format "%02x" %) d))))

;; a segment is built as one in-heap byte-array (transient, checkpoint-time only —
;; the writer's memory cost, NOT the daemon's; ~40-100MB/segment at 1M facts).
(defn- bb ^ByteBuffer [n] (ByteBuffer/allocate n))

(defn- facts-segment [facts-sorted]
  ;; facts-sorted: seq of [cid {:l :p :r}] sorted by cid; tx via tx-of map.
  (fn [tx-of]
    (let [n (count facts-sorted)
          buf (bb (* n 40))]
      (doseq [[cid m] facts-sorted]
        (.putLong buf (long cid)) (.putLong buf (long (:l m)))
        (.putLong buf (long (:p m))) (.putLong buf (long (:r m)))
        (.putLong buf (long (or (get tx-of cid) 0))))
      (.array buf))))

(defn- values-segments [values]
  ;; values: seq of [id string]. Emit blob + id-sorted table + str-sorted table.
  (let [rows (vec values)
        blob (ByteArrayOutputStream.)
        meta (reduce (fn [acc [id v]]
                       (let [b (utf8 (str v)) off (.size blob)]
                         (.write blob b 0 (alength b))
                         (conj acc {:id (long id) :off off :len (alength b) :s (str v)})))
                     [] rows)
        by-id (sort-by :id meta)
        by-str (sort-by :s meta)
        vid (bb (* (count meta) 20))       ; id long, off long, len int
        vstr (bb (* (count meta) 20))]     ; off long, len int, id long
    (doseq [m by-id] (.putLong vid (:id m)) (.putLong vid (long (:off m))) (.putInt vid (int (:len m))))
    (doseq [m by-str] (.putLong vstr (long (:off m))) (.putInt vstr (int (:len m))) (.putLong vstr (:id m)))
    {:values-id (.array vid) :values-str (.array vstr) :values-blob (.toByteArray blob)}))

(defn- longs-segment [ids]
  (let [v (vec (sort ids)) buf (bb (* (count v) 8))]
    (doseq [x v] (.putLong buf (long x))) (.array buf)))

(defn- txs-segments [txs]
  (let [rows (vec txs)
        blob (ByteArrayOutputStream.)
        meta (mapv (fn [[tx t]]
                     (let [a (utf8 (str (:agent t))) off (.size blob)]
                       (.write blob a 0 (alength a))
                       {:tx (long tx) :seq (long (or (:seq t) 0)) :off off :len (alength a)}))
                   rows)
        buf (bb (* (count meta) 28))]      ; tx long, seq long, off long, len int
    (doseq [m meta] (.putLong buf (:tx m)) (.putLong buf (:seq m))
           (.putLong buf (long (:off m))) (.putInt buf (int (:len m))))
    {:txs (.array buf) :txs-blob (.toByteArray blob)}))

;; postings: group live+superseded ORDINALS by key, sorted key table + concatenated
;; run block. Ordinals are the row indices into the (cid-sorted) facts segment, so a
;; posting can be resolved to a cid/fact without another lookup.
(defn- postings-l [ord->l n]
  (let [groups (reduce (fn [m ord] (update m (aget ^longs ord->l ord) (fnil conj []) ord))
                       (sorted-map) (range n))
        runs (ByteArrayOutputStream.)
        keytab (bb (* (count groups) 20))]   ; lid long, runOff long, runLen int
    (doseq [[lid ords] groups]
      (let [off (.size runs) rb (bb (* (count ords) 4))]
        (doseq [o ords] (.putInt rb (int o)))
        (let [a (.array rb)] (.write runs a 0 (alength a)))
        (.putLong keytab (long lid)) (.putLong keytab (long off)) (.putInt keytab (int (count ords)))))
    {:postings-l (.array keytab) :pl-runs (.toByteArray runs)}))

(defn- postings-lp [ord->l ord->p n]
  (let [groups (reduce (fn [m ord] (update m [(aget ^longs ord->l ord) (aget ^longs ord->p ord)]
                                           (fnil conj []) ord))
                       (sorted-map) (range n))
        runs (ByteArrayOutputStream.)
        keytab (bb (* (count groups) 28))]   ; lid long, pid long, runOff long, runLen int
    (doseq [[[lid pid] ords] groups]
      (let [off (.size runs) rb (bb (* (count ords) 4))]
        (doseq [o ords] (.putInt rb (int o)))
        (let [a (.array rb)] (.write runs a 0 (alength a)))
        (.putLong keytab (long lid)) (.putLong keytab (long pid))
        (.putLong keytab (long off)) (.putInt keytab (int (count ords)))))
    {:postings-lp (.array keytab) :plp-runs (.toByteArray runs)}))

;; NAMES: subject-name -> entity id, sorted by the name STRING (own small blob so a
;; binary-search probe reads one contiguous slice). Built from live name-pred facts.
(defn- names-segments [store-val]
  (let [m store-val
        name-pid (get (:val-intern m) "name")
        superseded (set (keys (:superseded m)))
        rows (when name-pid
               (for [[cid f] (:facts m)
                     :when (and (= (:p f) name-pid) (not (contains? superseded cid)))]
                 [(get (:values m) (:r f)) (:l f)]))   ; [name-string entity-id]
        blob (ByteArrayOutputStream.)
        meta (reduce (fn [acc [nm eid]]
                       (let [b (utf8 (str nm)) off (.size blob)]
                         (.write blob b 0 (alength b))
                         (conj acc {:s (str nm) :off off :len (alength b) :eid (long eid)})))
                     [] rows)
        by-str (sort-by :s meta)
        tab (bb (* (count meta) 20))]      ; off long, len int, eid long
    (doseq [x by-str] (.putLong tab (long (:off x))) (.putInt tab (int (:len x))) (.putLong tab (:eid x)))
    {:names (.array tab) :names-blob (.toByteArray blob)}))

(defn- superseded-segment [ord-superseded? n]
  (let [b (byte-array (quot (+ n 7) 8))]
    (dotimes [ord n]
      (when (ord-superseded? ord)
        (aset-byte b (quot ord 8) (unchecked-byte (bit-or (aget b (quot ord 8)) (bit-shift-left 1 (rem ord 8)))))))
    b))

;; write-fri! — serialize a live reified Store VALUE (deref of (:store co)) to `path`.
;; Returns {:covers_seq :next_id :counts :segments {seg {:off :len :sha256}}} for the
;; sidecar. `fold-fp` is stamped into the footer for the boot fold-version gate.
(defn write-fri! [store-val path & {:keys [fold-fingerprint]}]
  (let [m store-val
        ;; facts sorted by cid -> ordinal == row index.
        facts-sorted (sort-by first (:facts m))
        n (count facts-sorted)
        cids (long-array (map first facts-sorted))
        ord->l (long-array (map (comp :l second) facts-sorted))
        ord->p (long-array (map (comp :p second) facts-sorted))
        superseded (set (keys (:superseded m)))     ; cids
        ord-sup? (let [ss superseded] (fn [ord] (contains? ss (aget cids ord))))
        entities (remove #(or (contains? (:values m) %) (contains? (:facts m) %))
                         (keys (:objects m)))
        segs (merge
              {:facts ((facts-segment facts-sorted) (:tx-of m))
               :entities (longs-segment entities)
               :superseded (superseded-segment ord-sup? n)}
              (values-segments (:values m))
              (txs-segments (:txs m))
              (names-segments m)
              (postings-l ord->l n)
              (postings-lp ord->l ord->p n))
        order [:facts :values-id :values-str :values-blob :entities
               :txs :txs-blob :names :names-blob
               :postings-l :pl-runs :postings-lp :plp-runs :superseded]
        tmp (str path ".tmp")
        fos (FileOutputStream. tmp)]
    (with-open [os (DataOutputStream. (BufferedOutputStream. fos))]
      (.write os (utf8 MAGIC))
      (.writeInt os FMT)
      (let [start (+ (alength (utf8 MAGIC)) 4)
            [table foff]
            (loop [pos start acc {} [k & ks] order]
              (if (nil? k) [acc pos]
                  (let [b (get segs k)]
                    (.write os b 0 (alength b))
                    (recur (+ pos (alength b))
                           (assoc acc k {:off pos :len (alength b) :sha256 (sha256-hex b)})
                           ks))))
            footer {:magic MAGIC :fmt FMT
                    :covers_seq (or (:next-seq m) 0) :next_id (or (:next-id m) 0)
                    :supersedes_pred (:supersedes-pred m)
                    :fold_fingerprint fold-fingerprint
                    :counts {:facts n :values (count (:values m)) :entities (count entities)
                             :txs (count (:txs m)) :superseded (count superseded)}
                    :segments table}
            fb (utf8 (pr-str footer))]
        (.write os fb 0 (alength fb))
        (.writeLong os (long foff))
        (.flush os)
        (.force (.getChannel fos) true)   ; fsync bytes before the atomic rename
        (java.nio.file.Files/move
         (.toPath (java.io.File. tmp)) (.toPath (java.io.File. (str path)))
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                      java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
        {:covers_seq (:covers_seq footer) :next_id (:next_id footer)
         :supersedes_pred (:supersedes_pred footer)
         :counts (:counts footer) :segments table}))))

;; ---- reader (mmap) ----------------------------------------------------------
;; An image is {:raf :channel :footer :maps {seg [MappedByteBuffer ...]} :seg-off {seg base}}.
;; Each segment is mapped in <=1GiB chunks; a random-access read locates its chunk +
;; in-chunk offset. All the primitives below are pure over the mmap — no heap corpus.
;; An image is a plain map: {:raf :channel :footer :maps :name-pid :names-map :vid-memo}.
;; :names-map (name-string -> entity-id) and :name-pid are the small RESIDENT directories
;; the DECISION calls for — they turn resolve-name / name-of from a String-comparing
;; binary search (log n String allocations per call, the by-lp latency killer) into an
;; O(1) hashmap hit. They hold ONE copy of each subject name (~1 String/subject), not the
;; warm cache's 5-way Fact/index duplication. :vid-memo caches the handful of predicate
;; value-ids that a read resolves.
(declare value-id literal by-lp fact-of)

(defn- map-segment [^FileChannel ch off len]
  (loop [pos 0 acc []]
    (if (>= pos len) acc
        (let [sz (min CHUNK (- len pos))
              mbb (.map ch FileChannel$MapMode/READ_ONLY (+ off pos) sz)]
          (recur (+ pos sz) (conj acc [pos sz mbb]))))))

;; V1 segments are single-chunk (<=1GiB each; true through ~24M facts). open-fri stores
;; the sole MappedByteBuffer per segment under :buf so a hot read is a DIRECT, absolute,
;; type-hinted get with NO reflection, NO object-array, NO position mutation — the by-lp
;; latency bar. A >1-chunk segment (giant corpus, out of V1 scope) leaves :buf nil and the
;; chunked locate handles it (correct, slower). Absolute gets are concurrent-read-safe.
(defn- ^ByteBuffer segbuf [img seg] (get (:buf img) seg))

(defn- locate-chunked ^"[Ljava.lang.Object;" [img seg pos len]
  (loop [cs (get-in img [:maps seg])]
    (if-let [c (first cs)]
      (let [[cpos csz mbb] c]
        (if (and (>= pos cpos) (<= (+ pos len) (+ cpos csz)))
          (doto (object-array 2) (aset 0 mbb) (aset 1 (int (- pos cpos))))
          (recur (next cs))))
      (throw (ex-info "fri: read past segment" {:seg seg :pos pos})))))

(defn- seg-long ^long [img seg ^long pos]
  (if-let [b (segbuf img seg)] (.getLong b (int pos))
    (let [o (locate-chunked img seg pos 8)] (.getLong ^ByteBuffer (aget o 0) (int (aget o 1))))))
(defn- seg-int ^long [img seg ^long pos]
  (if-let [b (segbuf img seg)] (long (.getInt b (int pos)))
    (let [o (locate-chunked img seg pos 4)] (long (.getInt ^ByteBuffer (aget o 0) (int (aget o 1)))))))
(defn- seg-get-bytes [img seg ^long pos ^long len]
  (let [out (byte-array len)]
    (if-let [b (segbuf img seg)]
      (let [dup (.duplicate b)] (.position dup (int pos)) (.get dup out) out)
      (let [o (locate-chunked img seg pos len) dup (.duplicate ^ByteBuffer (aget o 0))]
        (.position dup (int (aget o 1))) (.get dup out) out))))

(defn open-fri [path]
  (let [raf (RandomAccessFile. (str path) "r")
        ch (.getChannel raf)
        flen (.length raf)
        _ (.seek raf (- flen 8))
        foff (.readLong raf)
        _ (.seek raf foff)
        fb (byte-array (- flen foff 8))
        _ (.readFully raf fb)
        footer (edn/read-string (String. fb StandardCharsets/UTF_8))
        maps (into {} (for [[seg {:keys [off len]}] (:segments footer)]
                        [seg (map-segment ch off len)]))
        ;; :buf — the sole ByteBuffer per single-chunk segment (the fast direct-read path).
        buf (into {} (for [[seg chunks] maps :when (= 1 (count chunks))]
                       [seg (nth (first chunks) 2)]))
        base {:raf raf :channel ch :footer footer :maps maps :buf buf :vid-memo (atom {})}
        ;; resident name->id directory (scan NAMES once).
        nn (long (/ (get-in footer [:segments :names :len] 0) 20))]
    (let [img base
          nmap (persistent!
                (reduce (fn [m i]
                          (let [b (* i 20)
                                soff (seg-long img :names b)
                                slen (seg-int img :names (+ b 8))
                                eid (seg-long img :names (+ b 12))
                                s (String. (seg-get-bytes img :names-blob soff slen) StandardCharsets/UTF_8)]
                            (assoc! m s eid)))
                        (transient {}) (range nn)))]
      (assoc img :names-map nmap :name-pid (value-id img "name")))))

(defn close-fri! [img]
  ;; JVM mmap caveat: there is no portable public unmap. Dropping the buffer refs +
  ;; a GC is the only guaranteed release; on Linux the pages evict lazily. The caller
  ;; (rotation) must drop ALL refs and (System/gc) BEFORE unlink, or the old inode
  ;; lingers until GC. We close the channel/raf (frees the fd) but the MappedByteBuffers
  ;; stay valid until GC'd — documented in coord_daemon rotation.
  (try (.close (:channel img)) (catch Exception _ nil))
  (try (.close (:raf img)) (catch Exception _ nil)))

(defn nfacts ^long [img] (long (get-in img [:footer :counts :facts])))
(defn covers-seq ^long [img] (long (get-in img [:footer :covers_seq])))
(defn next-id ^long [img] (long (get-in img [:footer :next_id])))
(defn supersedes-pred [img] (get-in img [:footer :supersedes_pred]))

;; ---- facts columns: ordinal <-> cid, fact-of, live? --------------------------
(defn- ord-cid ^long [img ord] (seg-long img :facts (* ord 40)))
(defn- ord-l ^long [img ord] (seg-long img :facts (+ (* ord 40) 8)))
(defn- ord-p ^long [img ord] (seg-long img :facts (+ (* ord 40) 16)))
(defn- ord-r ^long [img ord] (seg-long img :facts (+ (* ord 40) 24)))
(defn- ord-tx ^long [img ord] (seg-long img :facts (+ (* ord 40) 32)))

(defn cid->ord ^long [img cid]     ; binary search the cid-sorted facts column; -1 if absent
  (let [n (nfacts img)]
    (loop [lo 0 hi (dec n)]
      (if (> lo hi) -1
          (let [mid (quot (+ lo hi) 2) c (ord-cid img mid)]
            (cond (= c cid) mid (< c cid) (recur (inc mid) hi) :else (recur lo (dec mid))))))))

(defn superseded-ord? [img ^long ord]
  (let [b (if-let [buf (segbuf img :superseded)] (.get buf (int (quot ord 8)))
            (aget (seg-get-bytes img :superseded (quot ord 8) 1) 0))]
    (not (zero? (bit-and (int b) (bit-shift-left 1 (rem ord 8)))))))

(defn live-cid? [img cid]
  (let [ord (cid->ord img cid)] (and (>= ord 0) (not (superseded-ord? img ord)))))

(defn fact-of [img cid]
  (let [ord (cid->ord img cid)]
    (when (>= ord 0) {:l (ord-l img ord) :p (ord-p img ord) :r (ord-r img ord)})))

;; ---- value dictionary --------------------------------------------------------
(defn- vcount ^long [img] (long (get-in img [:footer :counts :values])))
(defn literal [img id]        ; id -> string, binary search values-id
  (let [n (vcount img)]
    (loop [lo 0 hi (dec n)]
      (if (> lo hi) nil
          (let [mid (quot (+ lo hi) 2) base (* mid 20) vid (seg-long img :values-id base)]
            (cond (= vid id) (let [off (seg-long img :values-id (+ base 8))
                                   len (seg-int img :values-id (+ base 16))]
                               (String. (seg-get-bytes img :values-blob off len) StandardCharsets/UTF_8))
                  (< vid id) (recur (inc mid) hi) :else (recur lo (dec mid))))))))

(defn value-object? [img id] (some? (literal img id)))

(defn value-id [img ^String s]   ; string -> id, binary search values-str by UTF-8 bytes
  (let [n (vcount img) target (utf8 s)]
    (loop [lo 0 hi (dec n)]
      (if (> lo hi) nil
          (let [mid (quot (+ lo hi) 2) base (* mid 20)
                off (seg-long img :values-str base) len (seg-int img :values-str (+ base 8))
                b (seg-get-bytes img :values-blob off len)
                cmp (compare (String. b StandardCharsets/UTF_8) s)]
            (cond (zero? cmp) (seg-long img :values-str (+ base 12))
                  (neg? cmp) (recur (inc mid) hi) :else (recur lo (dec mid))))))))

;; ---- postings: by-l / by-lp (ORDINAL runs -> live cids) ----------------------
(defn- run-ords [img runseg off len]
  (mapv (fn [i] (seg-int img runseg (+ off (* i 4)))) (range len)))

(defn by-l [img lid]           ; -> vector of LIVE cids on subject lid
  (let [n (long (/ (get-in img [:footer :segments :postings-l :len]) 20))]
    (loop [lo 0 hi (dec n)]
      (if (> lo hi) []
          (let [mid (quot (+ lo hi) 2) base (* mid 20) k (seg-long img :postings-l base)]
            (cond (= k lid) (let [off (seg-long img :postings-l (+ base 8))
                                  len (seg-int img :postings-l (+ base 16))]
                              (into [] (comp (remove #(superseded-ord? img %)) (map #(ord-cid img %)))
                                    (run-ords img :pl-runs off len)))
                  (< k lid) (recur (inc mid) hi) :else (recur lo (dec mid))))))))

(defn by-lp [img ^long lid ^long pid]      ; -> vector of LIVE cids on (lid,pid)
  (let [n (long (/ (get-in img [:footer :segments :postings-lp :len]) 28))]
    (loop [lo 0 hi (dec n)]
      (if (> lo hi) []
          (let [mid (quot (+ lo hi) 2) base (* mid 28)
                kl (seg-long img :postings-lp base) kp (seg-long img :postings-lp (+ base 8))
                ;; primitive (kl,kp) vs (lid,pid) compare — no vector allocation per probe.
                cmp (if (< kl lid) -1 (if (> kl lid) 1 (if (< kp pid) -1 (if (> kp pid) 1 0))))]
            (cond (zero? cmp) (let [off (seg-long img :postings-lp (+ base 16))
                                    len (seg-int img :postings-lp (+ base 24))]
                                (into [] (comp (remove #(superseded-ord? img %)) (map #(ord-cid img %)))
                                      (run-ords img :plp-runs off len)))
                  (neg? cmp) (recur (inc mid) hi) :else (recur lo (dec mid))))))))

;; memoized value-id for the FEW predicate/reserved strings a read resolves — a full
;; value-id binary search builds a String per probe, so caching the handful of hot keys
;; ("name", the queried predicate) is what keeps cold render off the String-alloc path.
(defn pred-id [img ^String s]
  (let [memo (:vid-memo img)]
    (or (get @memo s)
        (let [v (value-id img s)] (when v (swap! memo assoc s v)) v))))

;; ---- name resolution: RESIDENT name->id map (O(1), built at open) ------------
(defn resolve-name [img ^String nm] (get (:names-map img) nm))

;; name-of: entity id -> its name string (by-lp on the CACHED name pred).
(defn name-of [img subj]
  (when-let [name-pid (:name-pid img)]
    (let [cids (by-lp img subj name-pid)]
      (when (seq cids) (literal img (:r (fact-of img (first cids))))))))

;; ---- lazy heap-materialize: reconstruct the exact assemble-dump map ----------
;; == what coord.clj/replay feeds fram.store/load-store!, so a materialized Store is
;; byte-identical to a v2log replay of the same checkpoint.
(defn cold->dump [img]
  (let [n (nfacts img)
        facts (mapv (fn [ord] [(ord-cid img ord) {:l (ord-l img ord) :p (ord-p img ord) :r (ord-r img ord)}])
                    (range n))
        tx-of (mapv (fn [ord] [(ord-cid img ord) (ord-tx img ord)]) (range n))
        vc (vcount img)
        values (mapv (fn [i] (let [base (* i 20) id (seg-long img :values-id base)
                                   off (seg-long img :values-id (+ base 8))
                                   len (seg-int img :values-id (+ base 16))]
                               [id (String. (seg-get-bytes img :values-blob off len) StandardCharsets/UTF_8)]))
                     (range vc))
        ec (get-in img [:footer :counts :entities])
        ents (mapv (fn [i] (seg-long img :entities (* i 8))) (range ec))
        txc (get-in img [:footer :counts :txs])
        tb (get-in img [:footer :segments :txs-blob])
        txs (mapv (fn [i] (let [base (* i 28) tx (seg-long img :txs base) sq (seg-long img :txs (+ base 8))
                                off (seg-long img :txs (+ base 16)) len (seg-int img :txs (+ base 24))
                                agent (String. (seg-get-bytes img :txs-blob off len) StandardCharsets/UTF_8)]
                            [tx {:seq sq :agent agent}]))
                  (range txc))
        superd (into [] (comp (filter #(superseded-ord? img %)) (map #(ord-cid img %))) (range n))]
    {:next-id (next-id img) :next-seq (covers-seq img) :supersedes-pred (supersedes-pred img)
     :objects (vec (concat (map first values) ents (map first facts)))
     :values values :facts facts :tx-of tx-of :txs txs :superseded superd}))

;; render one cid to [subj-name pred-str r-rendered] — ONE value lookup for r (literal
;; if it's a value, else a name), not value-object? + literal doubling the search.
(defn render [img cid]
  (when-let [f (fact-of img cid)]
    [(name-of img (:l f)) (literal img (:p f))
     (let [r (:r f)] (or (literal img r) (name-of img r)))]))

;; render directly from an ORDINAL — the fast read path. by-lp already yields ordinals
;; (postings runs are ordinals); rendering from the ordinal skips the cid->ord binary
;; search that render(cid) pays. Used by the daemon's cold group-render read path.
(defn render-ord [img ord]
  [(name-of img (ord-l img ord)) (literal img (ord-p img ord))
   (let [r (ord-r img ord)] (or (literal img r) (name-of img r)))])

;; live ordinals on (lid,pid) — same binary search as by-lp but WITHOUT the ord->cid map.
(defn by-lp-ords [img ^long lid ^long pid]
  (let [n (long (/ (get-in img [:footer :segments :postings-lp :len]) 28))]
    (loop [lo 0 hi (dec n)]
      (if (> lo hi) []
          (let [mid (quot (+ lo hi) 2) base (* mid 28)
                kl (seg-long img :postings-lp base) kp (seg-long img :postings-lp (+ base 8))
                cmp (if (< kl lid) -1 (if (> kl lid) 1 (if (< kp pid) -1 (if (> kp pid) 1 0))))]
            (cond (zero? cmp) (let [off (seg-long img :postings-lp (+ base 16))
                                    len (seg-int img :postings-lp (+ base 24))]
                                (into [] (remove #(superseded-ord? img %)) (run-ords img :plp-runs off len)))
                  (neg? cmp) (recur (inc mid) hi) :else (recur lo (dec mid))))))))

;; the daemon's cold GROUP-RENDER: (subject-name, pred-name) -> rendered triples, direct
;; from ordinals. This is the intended mmap-served local read (no cid round-trip).
(defn render-lp [img ^String subj-name ^String pred-name]
  (let [lid (resolve-name img subj-name) pid (pred-id img pred-name)]
    (when (and lid pid) (mapv #(render-ord img %) (by-lp-ords img lid pid)))))

;; ---- cold projection for the parity gate (mmap-only, no materialize) ---------
;; live (l-name, p-str, r-rendered) domain triples, the same shape reified->facts
;; emits — reconstructed purely from the mmap columns. The parity gate asserts this
;; (unioned with the heap tail overlay) equals a from-scratch whole-log fold.
(defn cold-name-triples [img schema-pred? read-hidden-pred?]
  (let [n (nfacts img)]
    (persistent!
     (reduce
      (fn [acc ord]
        (if (superseded-ord? img ord) acc
            (let [l (ord-l img ord) p (ord-p img ord) r (ord-r img ord)
                  pstr (literal img p)]
              (if (or (nil? pstr) (schema-pred? pstr) (read-hidden-pred? pstr)) acc
                  (let [lname (name-of img l)
                        rrend (if (value-object? img r) (literal img r) (name-of img r))]
                    (conj! acc [lname pstr rrend]))))))
      (transient #{}) (range n)))))

;; per-segment integrity gate: RE-HASH the actual mmap'd bytes (streamed per <=1GiB
;; chunk) and compare to the sidecar's per-segment sha. The footer's OWN stored sha is
;; not authoritative here — a post-write byte flip leaves it intact — so the sidecar
;; (written atomically beside the boot-time validation surface) is the reference. A
;; mismatch => boot falls back to the v2log image / whole-log fold. `seg-table` is the
;; sidecar's :fri_segments map {seg {:off :len :sha256}}.
(defn verify-segments? [img seg-table]
  (and (map? seg-table)
       (every? (fn [[seg {want :sha256}]]
                 (let [chunks (get-in img [:maps seg])
                       md (MessageDigest/getInstance "SHA-256")]
                   (doseq [[_ csz ^ByteBuffer mbb] chunks]
                     (let [b (byte-array csz) dup (.duplicate mbb)]
                       (.position dup 0) (.get dup b) (.update md b)))
                   (= want (apply str (map #(format "%02x" %) (.digest md))))))
               seg-table)))
