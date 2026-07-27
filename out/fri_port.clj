(ns fri-port
  (:require [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [java.io RandomAccessFile]
           [java.io DataOutputStream]
           [java.io BufferedOutputStream]
           [java.io FileOutputStream]
           [java.io ByteArrayOutputStream]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.channels FileChannel$MapMode]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file CopyOption]
           [java.nio.file StandardCopyOption]
           [java.security MessageDigest]
           [clojure.lang PersistentQueue]))

(def ^String MAGIC "FRAMFRI1")

(def FMT 1)

(def CHUNK (* 1024 1024 1024))

(defn utf8 [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn ^String sha256-hex [b]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") b)]
  (apply str (map (fn [x] (format "%02x" x)) d))))

(defn bb [n]
  (ByteBuffer/allocate n))

(defn facts-segment [facts-sorted]
  (fn [tx-of] (let [n (count facts-sorted)
   buf (bb (* n 40))]
  (do
  (doseq [[cid m] facts-sorted]
  (.putLong buf (long cid))
  (.putLong buf (long (:l m)))
  (.putLong buf (long (:p m)))
  (.putLong buf (long (:r m)))
  (.putLong buf (long (or (get tx-of cid) 0))))
  (.array buf)))))

(defn values-segments [values]
  (let [rows (vec values)
   blob (ByteArrayOutputStream.)
   meta (reduce (fn [acc row] (let [id (first row)
   v (second row)
   b (utf8 (str v))
   off (.size blob)]
  (do
  (.write blob b 0 (alength b))
  (conj acc {:id (long id) :off off :len (alength b) :s (str v)})))) [] rows)
   by-id (sort-by :id meta)
   by-str (sort-by :s meta)
   vid (bb (* (count meta) 20))
   vstr (bb (* (count meta) 20))]
  (do
  (doseq [m by-id]
  (.putLong vid (:id m))
  (.putLong vid (long (:off m)))
  (.putInt vid (int (:len m))))
  (doseq [m by-str]
  (.putLong vstr (long (:off m)))
  (.putInt vstr (int (:len m)))
  (.putLong vstr (:id m)))
  {:values-id (.array vid) :values-str (.array vstr) :values-blob (.toByteArray blob)})))

(defn longs-segment [ids]
  (let [v (vec (sort ids))
   buf (bb (* (count v) 8))]
  (do
  (doseq [x v]
  (.putLong buf (long x)))
  (.array buf))))

(defn txs-segments [txs]
  (let [rows (vec txs)
   blob (ByteArrayOutputStream.)
   meta (mapv (fn [row] (let [tx (first row)
   t (second row)
   a (utf8 (str (:agent t)))
   off (.size blob)]
  (do
  (.write blob a 0 (alength a))
  {:tx (long tx) :seq (long (or (:seq t) 0)) :off off :len (alength a)}))) rows)
   buf (bb (* (count meta) 28))]
  (do
  (doseq [m meta]
  (.putLong buf (:tx m))
  (.putLong buf (:seq m))
  (.putLong buf (long (:off m)))
  (.putInt buf (int (:len m))))
  {:txs (.array buf) :txs-blob (.toByteArray blob)})))

(defn postings-l [ord->l n]
  (let [groups (reduce (fn [m ord] (update m (aget ord->l ord) (fnil conj []) ord)) (sorted-map) (range n))
   runs (ByteArrayOutputStream.)
   keytab (bb (* (count groups) 20))]
  (do
  (doseq [row groups]
  (let [lid (first row)
   ords (second row)
   off (.size runs)
   rb (bb (* (count ords) 4))]
  (do
  (doseq [o ords]
  (.putInt rb (int o)))
  (let [a (.array rb)]
  (.write runs a 0 (alength a)))
  (.putLong keytab (long lid))
  (.putLong keytab (long off))
  (.putInt keytab (int (count ords))))))
  {:postings-l (.array keytab) :pl-runs (.toByteArray runs)})))

(defn postings-lp [ord->l ord->p n]
  (let [groups (reduce (fn [m ord] (update m [(aget ord->l ord) (aget ord->p ord)] (fnil conj []) ord)) (sorted-map) (range n))
   runs (ByteArrayOutputStream.)
   keytab (bb (* (count groups) 28))]
  (do
  (doseq [row groups]
  (let [key (first row)
   lid (first key)
   pid (second key)
   ords (second row)
   off (.size runs)
   rb (bb (* (count ords) 4))]
  (do
  (doseq [o ords]
  (.putInt rb (int o)))
  (let [a (.array rb)]
  (.write runs a 0 (alength a)))
  (.putLong keytab (long lid))
  (.putLong keytab (long pid))
  (.putLong keytab (long off))
  (.putInt keytab (int (count ords))))))
  {:postings-lp (.array keytab) :plp-runs (.toByteArray runs)})))

(defn names-segments [store-val]
  (let [name-pid (get (:val-intern store-val) "name")
   superseded (set (keys (:superseded store-val)))
   rows (if (nil? name-pid) [] (reduce (fn [acc row] (let [cid (first row)
   f (second row)]
  (if (and (= (:p f) name-pid) (not (contains? superseded cid))) (conj acc [(get (:values store-val) (:r f)) (:l f)]) acc))) [] (:facts store-val)))
   blob (ByteArrayOutputStream.)
   meta (reduce (fn [acc row] (let [nm (first row)
   eid (second row)
   b (utf8 (str nm))
   off (.size blob)]
  (do
  (.write blob b 0 (alength b))
  (conj acc {:s (str nm) :off off :len (alength b) :eid (long eid)})))) [] rows)
   by-str (sort-by :s meta)
   tab (bb (* (count meta) 20))]
  (do
  (doseq [x by-str]
  (.putLong tab (long (:off x)))
  (.putInt tab (int (:len x)))
  (.putLong tab (:eid x)))
  {:names (.array tab) :names-blob (.toByteArray blob)})))

(defn superseded-segment [ord-superseded? n]
  (let [b (byte-array (quot (+ n 7) 8))]
  (do
  (doseq [ord (range n)]
  (if (ord-superseded? ord) (do
  (aset-byte b (quot ord 8) (unchecked-byte (bit-or (aget b (quot ord 8)) (bit-shift-left 1 (rem ord 8))))))))
  b)))

(defn write-fri! [store-val path & opts]
  (let [fold-fingerprint (:fold-fingerprint (apply hash-map opts))
   facts-sorted (sort-by first (:facts store-val))
   n (count facts-sorted)
   cids (long-array (map first facts-sorted))
   ord->l (long-array (map (comp :l second) facts-sorted))
   ord->p (long-array (map (comp :p second) facts-sorted))
   superseded (set (keys (:superseded store-val)))
   ord-sup? (fn [ord] (contains? superseded (aget cids ord)))
   make-facts (facts-segment facts-sorted)
   entities (remove (fn [x] (or (contains? (:values store-val) x) (contains? (:facts store-val) x))) (keys (:objects store-val)))
   segs (merge {:facts (make-facts (:tx-of store-val)) :entities (longs-segment entities) :superseded (superseded-segment ord-sup? n)} (values-segments (:values store-val)) (txs-segments (:txs store-val)) (names-segments store-val) (postings-l ord->l n) (postings-lp ord->l ord->p n))
   order [:facts :values-id :values-str :values-blob :entities :txs :txs-blob :names :names-blob :postings-l :pl-runs :postings-lp :plp-runs :superseded]
   tmp (str path ".tmp")
   fos (FileOutputStream. tmp)]
  (with-open [os (DataOutputStream. (BufferedOutputStream. fos))]
  (.write os (utf8 MAGIC))
  (.writeInt os FMT)
  (let [start (+ (alength (utf8 MAGIC)) 4)
   pair (loop [pos start
   acc {}
   ks order]
  (let [k (first ks)]
  (if (nil? k) [acc pos] (let [b (get segs k)]
  (do
  (.write os b 0 (alength b))
  (recur (+ pos (alength b)) (assoc acc k {:off pos :len (alength b) :sha256 (sha256-hex b)}) (next ks)))))))
   table (first pair)
   foff (second pair)
   footer {:magic MAGIC :fmt FMT :covers_seq (or (:next-seq store-val) 0) :next_id (or (:next-id store-val) 0) :supersedes_pred (:supersedes-pred store-val) :fold_fingerprint fold-fingerprint :counts {:facts n :values (count (:values store-val)) :entities (count entities) :txs (count (:txs store-val)) :superseded (count superseded)} :segments table}
   fb (utf8 (pr-str footer))]
  (do
  (.write os fb 0 (alength fb))
  (.writeLong os (long foff))
  (.flush os)
  (.force (.getChannel fos) true)
  (Files/move (.toPath (java.io.File. tmp)) (.toPath (java.io.File. (str path))) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
  {:covers_seq (:covers_seq footer) :next_id (:next_id footer) :supersedes_pred (:supersedes_pred footer) :counts (:counts footer) :segments table})))))

(defn map-segment [ch off len]
  (loop [pos 0
   acc []]
  (if (>= pos len) acc (let [sz (min CHUNK (- len pos))
   mbb (.map ch FileChannel$MapMode/READ_ONLY (+ off pos) sz)]
  (recur (+ pos sz) (conj acc [pos sz mbb]))))))

(defn segbuf [img seg]
  (get (:buf img) seg))

(defn locate-chunked [img seg pos len]
  (loop [cs (get-in img [:maps seg])]
  (let [c (first cs)]
  (if c (let [cpos (nth c 0)
   csz (nth c 1)
   mbb (nth c 2)]
  (if (and (>= pos cpos) (<= (+ pos len) (+ cpos csz))) (let [out (object-array 2)]
  (do
  (aset out 0 mbb)
  (aset out 1 (int (- pos cpos)))
  out)) (recur (next cs)))) (throw (ex-info "fri: read past segment" {:seg seg :pos pos}))))))

(defn seg-long [img seg pos]
  (let [b (segbuf img seg)]
  (if b (.getLong b (int pos)) (let [o (locate-chunked img seg pos 8)]
  (.getLong (aget o 0) (int (aget o 1)))))))

(defn seg-int [img seg pos]
  (let [b (segbuf img seg)]
  (if b (long (.getInt b (int pos))) (let [o (locate-chunked img seg pos 4)]
  (long (.getInt (aget o 0) (int (aget o 1))))))))

(defn seg-get-bytes [img seg pos len]
  (let [out (byte-array len)]
  (let [b (segbuf img seg)]
  (if b (let [dup (.duplicate b)]
  (do
  (.position dup (int pos))
  (.get dup out)
  out)) (let [o (locate-chunked img seg pos len)
   dup (.duplicate (aget o 0))]
  (do
  (.position dup (int (aget o 1)))
  (.get dup out)
  out))))))

(def DEFAULT-RENDER-CACHE 65536)

(defn env-long [^String k default]
  (or (try
  (let [raw (System/getenv k)]
  (if (nil? raw) nil (let [trimmed (str/trim raw)]
  (if (empty? trimmed) nil (Long/parseLong trimmed)))))
  (catch Exception _
    nil)) default))

(def ^:dynamic *cache-cap* nil)

(defn render-cache-cap [cap]
  (or cap (env-long "FRAM_MMAP_RENDER_CACHE" DEFAULT-RENDER-CACHE)))

(defn lru []
  (atom {:m {} :q PersistentQueue/EMPTY}))

(defn clear-render-caches! [img]
  (doseq [k [:render-cache :name-cache :lit-cache]]
  (let [a (get img k)]
  (if a (do
  (reset! a {:m {} :q PersistentQueue/EMPTY}))))))

(defn cache-get [a k]
  (get (:m (deref a)) k))

(defn cache-put! [a k v cap]
  (do
  (swap! a (fn [s] (let [m (:m s)
   q (:q s)]
  (if (contains? m k) s (let [m1 (assoc m k v)
   q1 (conj q k)]
  (if (> (count m1) cap) {:m (dissoc m1 (peek q1)) :q (pop q1)} {:m m1 :q q1}))))))
  v))

(def cache-put cache-put!)

(defn memo-put! [memo k v]
  (swap! memo assoc k v))

(def memo-put memo-put!)

(defn nfacts [img]
  (long (get-in img [:footer :counts :facts])))

(defn covers-seq [img]
  (long (get-in img [:footer :covers_seq])))

(defn next-id [img]
  (long (get-in img [:footer :next_id])))

(defn supersedes-pred [img]
  (get-in img [:footer :supersedes_pred]))

(defn ord-cid [img ord]
  (seg-long img :facts (* ord 40)))

(defn ord-l [img ord]
  (seg-long img :facts (+ (* ord 40) 8)))

(defn ord-p [img ord]
  (seg-long img :facts (+ (* ord 40) 16)))

(defn ord-r [img ord]
  (seg-long img :facts (+ (* ord 40) 24)))

(defn ord-tx [img ord]
  (seg-long img :facts (+ (* ord 40) 32)))

(defn cid->ord [img cid]
  (let [n (nfacts img)]
  (loop [lo 0
   hi (dec n)]
  (if (> lo hi) -1 (let [mid (quot (+ lo hi) 2)
   c (ord-cid img mid)]
  (cond
  (= c cid) mid
  (< c cid) (recur (inc mid) hi)
  :else (recur lo (dec mid))))))))

(defn ^Boolean superseded-ord? [img ord]
  (let [b (let [buf (segbuf img :superseded)]
  (if buf (.get buf (int (quot ord 8))) (aget (seg-get-bytes img :superseded (quot ord 8) 1) 0)))]
  (not (zero? (bit-and (int b) (bit-shift-left 1 (rem ord 8)))))))

(defn ^Boolean live-cid? [img cid]
  (let [ord (cid->ord img cid)]
  (and (>= ord 0) (not (superseded-ord? img ord)))))

(defn fact-of [img cid]
  (let [ord (cid->ord img cid)]
  (if (>= ord 0) (do
  {:l (ord-l img ord) :p (ord-p img ord) :r (ord-r img ord)}))))

(defn vcount [img]
  (long (get-in img [:footer :counts :values])))

(defn literal [img id]
  (let [n (vcount img)]
  (loop [lo 0
   hi (dec n)]
  (if (> lo hi) nil (let [mid (quot (+ lo hi) 2)
   base (* mid 20)
   vid (seg-long img :values-id base)]
  (cond
  (= vid id) (let [off (seg-long img :values-id (+ base 8))
   len (seg-int img :values-id (+ base 16))]
  (String. (seg-get-bytes img :values-blob off len) StandardCharsets/UTF_8))
  (< vid id) (recur (inc mid) hi)
  :else (recur lo (dec mid))))))))

(defn ^Boolean value-object? [img id]
  (some? (literal img id)))

(defn value-id [img ^String s]
  (let [n (vcount img)
   target (utf8 s)]
  (loop [lo 0
   hi (dec n)]
  (if (> lo hi) nil (let [mid (quot (+ lo hi) 2)
   base (* mid 20)
   off (seg-long img :values-str base)
   len (seg-int img :values-str (+ base 8))
   b (seg-get-bytes img :values-blob off len)
   cmp (compare (String. b StandardCharsets/UTF_8) s)]
  (cond
  (zero? cmp) (seg-long img :values-str (+ base 12))
  (neg? cmp) (recur (inc mid) hi)
  :else (recur lo (dec mid))))))))

(defn open-fri-with-cap [path cache-cap]
  (let [raf (RandomAccessFile. (str path) "r")
   ch (.getChannel raf)
   flen (.length raf)
   _seek-footer (.seek raf (- flen 8))
   foff (.readLong raf)
   _seek-body (.seek raf foff)
   fb (byte-array (- flen foff 8))
   _read (.readFully raf fb)
   footer (edn/read-string (String. fb StandardCharsets/UTF_8))
   maps (reduce (fn [acc row] (let [seg (first row)
   meta (second row)]
  (assoc acc seg (map-segment ch (:off meta) (:len meta))))) {} (:segments footer))
   buf (reduce (fn [acc row] (let [seg (first row)
   chunks (second row)]
  (if (= 1 (count chunks)) (assoc acc seg (nth (first chunks) 2)) acc))) {} maps)
   base {:raf raf :channel ch :footer footer :maps maps :buf buf :vid-memo (atom {})}
   nn (long (/ (get-in footer [:segments :names :len] 0) 20))
   nmap (reduce (fn [m i] (let [b (* i 20)
   soff (seg-long base :names b)
   slen (seg-int base :names (+ b 8))
   eid (seg-long base :names (+ b 12))
   s (String. (seg-get-bytes base :names-blob soff slen) StandardCharsets/UTF_8)]
  (assoc m s eid))) {} (range nn))]
  (assoc base :names-map nmap :name-pid (value-id base "name") :cache-cap (render-cache-cap cache-cap) :render-cache (lru) :name-cache (lru) :lit-cache (lru))))

(defn open-fri [path]
  (open-fri-with-cap path *cache-cap*))

(defn close-fri! [img]
  (do
  (try
  (.close (:channel img))
  (catch Exception _
    nil))
  (try
  (.close (:raf img))
  (catch Exception _
    nil))
  nil))

(defn run-ords [img runseg off len]
  (mapv (fn [i] (seg-int img runseg (+ off (* i 4)))) (range len)))

(defn by-l [img lid]
  (let [n (long (/ (get-in img [:footer :segments :postings-l :len]) 20))]
  (loop [lo 0
   hi (dec n)]
  (if (> lo hi) [] (let [mid (quot (+ lo hi) 2)
   base (* mid 20)
   k (seg-long img :postings-l base)]
  (cond
  (= k lid) (let [off (seg-long img :postings-l (+ base 8))
   len (seg-int img :postings-l (+ base 16))]
  (reduce (fn [acc ord] (if (superseded-ord? img ord) acc (conj acc (ord-cid img ord)))) [] (run-ords img :pl-runs off len)))
  (< k lid) (recur (inc mid) hi)
  :else (recur lo (dec mid))))))))

(defn by-lp [img lid pid]
  (let [n (long (/ (get-in img [:footer :segments :postings-lp :len]) 28))]
  (loop [lo 0
   hi (dec n)]
  (if (> lo hi) [] (let [mid (quot (+ lo hi) 2)
   base (* mid 28)
   kl (seg-long img :postings-lp base)
   kp (seg-long img :postings-lp (+ base 8))
   cmp (if (< kl lid) -1 (if (> kl lid) 1 (if (< kp pid) -1 (if (> kp pid) 1 0))))]
  (cond
  (zero? cmp) (let [off (seg-long img :postings-lp (+ base 16))
   len (seg-int img :postings-lp (+ base 24))]
  (reduce (fn [acc ord] (if (superseded-ord? img ord) acc (conj acc (ord-cid img ord)))) [] (run-ords img :plp-runs off len)))
  (neg? cmp) (recur (inc mid) hi)
  :else (recur lo (dec mid))))))))

(defn pred-id [img ^String s]
  (let [memo (:vid-memo img)]
  (or (get (deref memo) s) (let [v (value-id img s)]
  (do
  (if (some? v) (do
  (memo-put memo s v)))
  v)))))

(defn resolve-name [img ^String nm]
  (get (:names-map img) nm))

(defn name-of [img subj]
  (let [name-pid (:name-pid img)]
  (if name-pid (do
  (let [cids (by-lp img subj name-pid)]
  (if (seq cids) (do
  (let [f (fact-of img (first cids))]
  (if (nil? f) nil (literal img (:r f)))))))))))

(defn cold->dump [img]
  (let [n (nfacts img)
   facts (mapv (fn [ord] [(ord-cid img ord) {:l (ord-l img ord) :p (ord-p img ord) :r (ord-r img ord)}]) (range n))
   tx-of (mapv (fn [ord] [(ord-cid img ord) (ord-tx img ord)]) (range n))
   vc (vcount img)
   values (mapv (fn [i] (let [base (* i 20)
   id (seg-long img :values-id base)
   off (seg-long img :values-id (+ base 8))
   len (seg-int img :values-id (+ base 16))]
  [id (String. (seg-get-bytes img :values-blob off len) StandardCharsets/UTF_8)])) (range vc))
   ec (get-in img [:footer :counts :entities])
   ents (mapv (fn [i] (seg-long img :entities (* i 8))) (range ec))
   txc (get-in img [:footer :counts :txs])
   txs (mapv (fn [i] (let [base (* i 28)
   tx (seg-long img :txs base)
   sq (seg-long img :txs (+ base 8))
   off (seg-long img :txs (+ base 16))
   len (seg-int img :txs (+ base 24))
   agent (String. (seg-get-bytes img :txs-blob off len) StandardCharsets/UTF_8)]
  [tx {:seq sq :agent agent}])) (range txc))
   superd (reduce (fn [acc ord] (if (superseded-ord? img ord) (conj acc (ord-cid img ord)) acc)) [] (range n))]
  {:next-id (next-id img) :next-seq (covers-seq img) :supersedes-pred (supersedes-pred img) :objects (vec (concat (map first values) ents (map first facts))) :values values :facts facts :tx-of tx-of :txs txs :superseded superd}))

(defn literal* [img id]
  (let [c (:lit-cache img)
   v (cache-get c id)]
  (if (some? v) v (let [r (literal img id)]
  (do
  (if (some? r) (do
  (cache-put c id r (:cache-cap img))))
  r)))))

(defn name-of* [img subj]
  (let [c (:name-cache img)
   v (cache-get c subj)]
  (if (some? v) v (let [r (name-of img subj)]
  (do
  (if (some? r) (do
  (cache-put c subj r (:cache-cap img))))
  r)))))

(defn render [img cid]
  (let [f (fact-of img cid)]
  (if f (do
  [(name-of img (:l f)) (literal img (:p f)) (let [r (:r f)]
  (or (literal img r) (name-of img r)))]))))

(defn render-ord [img ord]
  [(name-of* img (ord-l img ord)) (literal* img (ord-p img ord)) (let [r (ord-r img ord)]
  (or (literal* img r) (name-of* img r)))])

(defn by-lp-ords [img lid pid]
  (let [n (long (/ (get-in img [:footer :segments :postings-lp :len]) 28))]
  (loop [lo 0
   hi (dec n)]
  (if (> lo hi) [] (let [mid (quot (+ lo hi) 2)
   base (* mid 28)
   kl (seg-long img :postings-lp base)
   kp (seg-long img :postings-lp (+ base 8))
   cmp (if (< kl lid) -1 (if (> kl lid) 1 (if (< kp pid) -1 (if (> kp pid) 1 0))))]
  (cond
  (zero? cmp) (let [off (seg-long img :postings-lp (+ base 16))
   len (seg-int img :postings-lp (+ base 24))]
  (reduce (fn [acc ord] (if (superseded-ord? img ord) acc (conj acc ord))) [] (run-ords img :plp-runs off len)))
  (neg? cmp) (recur (inc mid) hi)
  :else (recur lo (dec mid))))))))

(defn render-lp [img ^String subj-name ^String pred-name]
  (let [lid (resolve-name img subj-name)
   pid (pred-id img pred-name)]
  (if (and (some? lid) (some? pid)) (do
  (let [c (:render-cache img)
   k [lid pid]
   hit (cache-get c k)]
  (if (some? hit) hit (let [v (mapv (fn [ord] (render-ord img ord)) (by-lp-ords img lid pid))]
  (cache-put c k v (:cache-cap img)))))))))

(defn cold-name-triples [img schema-pred? read-hidden-pred?]
  (let [n (nfacts img)]
  (reduce (fn [acc ord] (if (superseded-ord? img ord) acc (let [l (ord-l img ord)
   p (ord-p img ord)
   r (ord-r img ord)
   pstr (literal img p)]
  (if (or (nil? pstr) (schema-pred? pstr) (read-hidden-pred? pstr)) acc (let [lname (name-of img l)
   rrend (if (value-object? img r) (literal img r) (name-of img r))]
  (conj acc [lname pstr rrend])))))) #{} (range n))))

(defn ^Boolean verify-segments? [img seg-table]
  (and (map? seg-table) (every? (fn [row] (let [seg (first row)
   want (:sha256 (second row))
   chunks (get-in img [:maps seg])
   md (MessageDigest/getInstance "SHA-256")]
  (do
  (doseq [chunk chunks]
  (let [csz (nth chunk 1)
   mbb (nth chunk 2)
   b (byte-array csz)
   dup (.duplicate mbb)]
  (do
  (.position dup 0)
  (.get dup b)
  (.update md b))))
  (= want (apply str (map (fn [x] (format "%02x" x)) (.digest md))))))) seg-table)))
