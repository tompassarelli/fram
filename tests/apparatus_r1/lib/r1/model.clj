;; R-1 apparatus — shared reference models (TEST-ONLY, non-production).
;;
;; This file implements TWO reference models of the reification laws:
;;   * b2      — the ACCEPTED B2 contract (thread 019f79eb-e8e0), §1-§5.
;;   * bprime  — the REJECTED B-prime shape (explicitly revoked clauses).
;; It exercises no production source: it is an independent oracle so the
;; sensitivity cells can be proven RED against B-prime and GREEN against B2.
;;
;; Loads identically under babashka (SCI) and Clojure (JVM): clojure.core +
;; clojure.edn + java.security.MessageDigest + java.io/java.nio only.
(ns r1.model
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermission PosixFilePermissions]))

;; ---------------------------------------------------------------------------
;; raw-byte helpers — the identity law is a RAW-BYTE law, never a string law.
;; ---------------------------------------------------------------------------
(def ^:private LF (byte 10))

(defn read-bytes ^bytes [path]
  (Files/readAllBytes (.toPath (File. (str path)))))

(defn str->bytes ^bytes [^String s]
  (.getBytes s "UTF-8"))

(defn bytes->str ^String [^bytes b]
  (String. b "UTF-8"))

(defn sha256-hex
  "Full lowercase hex sha256 of the exact bytes."
  [^bytes b]
  (let [md (MessageDigest/getInstance "SHA-256")
        dig (.digest md b)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) dig))))

(defn sha256-16hex
  "First 16 hex chars of sha256 — the contract's :src_*_sha / :sha1 form."
  [^bytes b]
  (subs (sha256-hex b) 0 16))

(defn prefix-bytes ^bytes [^bytes b n]
  (let [n (min (int n) (alength b))
        out (byte-array n)]
    (System/arraycopy b 0 out 0 n)
    out))

(defn unsigned-byte-compare
  "Unsigned lexicographic byte-array comparison (the K_ev ordering)."
  [^bytes a ^bytes b]
  (let [la (alength a) lb (alength b) n (min la lb)]
    (loop [i 0]
      (if (< i n)
        (let [x (bit-and (aget a i) 0xff)
              y (bit-and (aget b i) 0xff)]
          (if (= x y) (recur (inc i)) (Integer/compare x y)))
        (Integer/compare la lb)))))

(defn split-lines
  "Split a raw log into physical lines. Returns [{:bytes ^bytes :start n :end n} ...]
   where end is the offset of the byte AFTER the terminating LF (or EOF).
   The line :bytes exclude the trailing LF. A trailing partial (torn) line with
   no LF is included and flagged :torn true."
  [^bytes buf]
  (let [n (alength buf)]
    (loop [i 0 start 0 out []]
      (cond
        (>= i n)
        (if (> i start)
          (let [len (- i start) lb (byte-array len)]
            (System/arraycopy buf start lb 0 len)
            (conj out {:bytes lb :start start :end i :torn true}))
          out)
        (= (aget buf i) LF)
        (let [len (- i start) lb (byte-array len)]
          (System/arraycopy buf start lb 0 len)
          (recur (inc i) (inc i) (conj out {:bytes lb :start start :end (inc i) :torn false})))
        :else (recur (inc i) start out)))))

(defn parse-edn-line
  "Parse a physical line's bytes as an EDN map; nil if not a map / unparsable."
  [line]
  (try
    (let [m (edn/read-string (bytes->str (:bytes line)))]
      (when (map? m) m))
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; §1 SHADOW LAW — the load-bearing divergence between B-prime and B2.
;; ---------------------------------------------------------------------------
(defn generation-record
  "Line 1 of coordination.log parsed as a generation control record, or nil."
  [coord-lines]
  (let [m (some-> coord-lines first parse-edn-line)]
    (when (and (map? m) (= "generation" (:p m))) m)))

(defn b2-shadow-boundary
  "B2 §1: returns {:valid bool :boundary <end-offset>}.
   Valid iff line1 is a generation record with :gen_n>=1 AND sha256-16hex of the
   first :src_telem_bytes bytes of telemetry == :src_telem_sha. Only then are
   lines wholly within that boundary shadows. sha mismatch => nothing shadowed."
  [coord-lines ^bytes telem-buf]
  (let [g (generation-record coord-lines)]
    (if (and g (>= (long (:gen_n g 0)) 1))
      (let [b   (long (:src_telem_bytes g 0))
            sha (:src_telem_sha g)
            got (sha256-16hex (prefix-bytes telem-buf b))]
        (if (and sha (not= sha "none") (= got sha))
          {:valid true :boundary b}
          {:valid false :boundary 0}))
      {:valid false :boundary 0})))

(defn b2-telem-shadow?
  "B2: a physical telemetry line is a shadow iff it lies wholly within the
   recorded, sha-verified source boundary. Position, not byte-equality."
  [{:keys [boundary valid]} line]
  (and valid (<= (long (:end line)) (long boundary))))

(defn coord-line-byteset
  "B-prime helper: set of (tx, sha-of-bytes) pairs over coordination lines."
  [coord-lines]
  (into #{}
        (for [l coord-lines
              :let [m (parse-edn-line l)]]
          [(:tx m) (sha256-hex (:bytes l))])))

(defn bprime-telem-shadow?
  "B-prime (REVOKED): a telemetry line is a shadow iff it is byte-equal to some
   coordination line at the SAME tx — regardless of physical position. This is
   the marker-aware byte-equality shadow the sixth review rejected."
  [coord-byteset line]
  (let [m (parse-edn-line line)]
    (contains? coord-byteset [(:tx m) (sha256-hex (:bytes line))])))

;; ---------------------------------------------------------------------------
;; K_ev — the identity key. [tx-or-0, exact record bytes (unsigned-byte-lex),
;; occurrence rank n AFTER shadowing]. Emitted deterministically so bb/JVM/JS
;; produce byte-identical output.
;; ---------------------------------------------------------------------------
(defn logical-events
  "Given raw coordination + telemetry bytes and a model (:b2 | :bprime), return
   the logical event sequence (coordination lines are always authored; telemetry
   lines that are NOT shadows are authored). Each event: {:tx :bytes}."
  [model ^bytes coord-buf ^bytes telem-buf]
  (let [coord-lines (remove :torn (split-lines coord-buf))
        telem-lines (remove :torn (split-lines telem-buf))
        coord-evs   (for [l coord-lines]
                      {:tx (:tx (parse-edn-line l) 0) :bytes (:bytes l)})
        telem-evs   (case model
                      :b2 (let [bnd (b2-shadow-boundary coord-lines telem-buf)]
                            (for [l telem-lines
                                  :when (not (b2-telem-shadow? bnd l))]
                              {:tx (:tx (parse-edn-line l) 0) :bytes (:bytes l)}))
                      :bprime (let [cs (coord-line-byteset coord-lines)]
                                (for [l telem-lines
                                      :when (not (bprime-telem-shadow? cs l))]
                                  {:tx (:tx (parse-edn-line l) 0) :bytes (:bytes l)})))]
    (vec (concat coord-evs telem-evs))))

(defn kev-vector
  "Total order by K = [tx, bytes(unsigned-lex), rank], rank assigned to equal
   (tx,bytes) occurrences in K order. Returns a vector of triples
   [tx sha256-16hex-of-bytes rank]."
  [events]
  (let [sorted (sort (fn [a b]
                       (let [c (Long/compare (long (:tx a 0)) (long (:tx b 0)))]
                         (if (zero? c) (unsigned-byte-compare (:bytes a) (:bytes b)) c)))
                     events)]
    (loop [[e & more] sorted, prev nil, rank 0, out []]
      (if (nil? e)
        out
        (let [full (sha256-hex (:bytes e))          ;; one digest per event
              k [(:tx e 0) full]
              rank (if (= k prev) (inc rank) 0)
              triple [(long (:tx e 0)) (subs full 0 16) rank]]
          (recur more k rank (conj out triple)))))))

(defn kev-serialize
  "Deterministic, runtime-neutral serialization of a K_ev vector.
   One event per line: <tx>\\t<sha256-16hex>\\t<rank>. Trailing LF."
  [kv]
  (str (str/join "\n" (map (fn [[tx sha rank]] (str tx "\t" sha "\t" rank)) kv)) "\n"))

;; ---------------------------------------------------------------------------
;; §3 MODE / DOCTOR — exact-mode restore vs the revoked 0644 constant.
;; ---------------------------------------------------------------------------
(def ^:private perm-bits
  {PosixFilePermission/OWNER_READ    0400 PosixFilePermission/OWNER_WRITE   0200
   PosixFilePermission/OWNER_EXECUTE 0100 PosixFilePermission/GROUP_READ   0040
   PosixFilePermission/GROUP_WRITE   0020 PosixFilePermission/GROUP_EXECUTE 0010
   PosixFilePermission/OTHERS_READ   0004 PosixFilePermission/OTHERS_WRITE  0002
   PosixFilePermission/OTHERS_EXECUTE 0001})

(defn stat-mode
  "Actual st_mode & 07777 (permission bits) of a path, as a decimal int."
  [path]
  (let [perms (Files/getPosixFilePermissions (.toPath (File. (str path))) (make-array java.nio.file.LinkOption 0))]
    (reduce (fn [acc p] (bit-or acc (perm-bits p))) 0 perms)))

(defn set-mode!
  "chmod path to the given octal-int permission bits."
  [path mode]
  (let [perms (java.util.HashSet.)]
    (doseq [[p bit] perm-bits]
      (when (not (zero? (bit-and mode bit))) (.add perms p)))
    (Files/setPosixFilePermissions (.toPath (File. (str path))) perms)))

(defn doctor-restore-mode
  "Given the model and an intent record's recorded mode, return the mode the
   doctor WOULD restore. B2 => exact recorded mode; B-prime => constant 0644."
  [model recorded-mode]
  (case model
    :b2 recorded-mode
    :bprime 0644))

;; ---------------------------------------------------------------------------
;; §2 ADMISSION — flock floor vs the revoked /proc drain scan.
;; Modeled at the verdict level: given a racing writer scenario, what happens
;; to an acknowledged write?
;; ---------------------------------------------------------------------------
(defn admit-racing-write
  "scenario keys: :writer-holds-lock (supported, holds shared flock),
                   :fd-transferred (child inherited a pre-fence write fd),
                   :acked (the writer received an ack).
   Returns {:outcome :preserved|:named-residual|:silent-loss :acked bool}.

   B2: supported (lock-holding) acked writes are always preserved; an
       unsupported fd-transferred write is a NAMED residual (loud), never acked
       as supported data, never silent corruption.
   B-prime: the /proc drain scan cannot see a post-scan fork/fd-transfer, so it
       ADMITS the racing writer, acks it, then loses it silently at flip."
  [model {:keys [writer-holds-lock fd-transferred acked]}]
  (case model
    :b2 (cond
          writer-holds-lock {:outcome :preserved :acked true}
          fd-transferred    {:outcome :named-residual :acked false}
          :else             {:outcome :named-residual :acked false})
    :bprime (cond
              writer-holds-lock {:outcome :preserved :acked true}
              ;; drain scan missed the transferred fd -> admitted -> ack -> lost
              fd-transferred    {:outcome :silent-loss :acked true}
              :else             {:outcome :preserved :acked (boolean acked)})))

;; ---------------------------------------------------------------------------
;; §3 FLIP STATE GENERATOR — deterministic on-disk states for kill cells K0-K8.
;; Produces the coordination.log / telemetry.log / intent bytes present on disk
;; had the process been killed at each phase. Modes tracked separately.
;; ---------------------------------------------------------------------------
(defn gen-record-bytes
  "§1 line-1 generation control record for a telemetry prefix."
  [{:keys [tx gen-n telem-prefix]}]
  (let [tb (str->bytes telem-prefix)]
    (str->bytes
     (str "{:tx " tx " :op \"assert\" :l \"@log:gen\" :p \"generation\""
          " :r \"gen-" gen-n "\" :by \"fram:unify\" :ts \"2026-07-19T00:00:00Z\""
          " :gen_prev \"" (if (> (long gen-n) 1) (str "gen-" (dec (long gen-n))) "genesis") "\""
          " :gen_n " gen-n
          " :gen_src \"none\""
          " :src_coord_bytes 0 :src_coord_sha \"none\""
          " :src_telem_bytes " (alength tb)
          " :src_telem_sha \"" (sha256-16hex tb) "\"}"))))

(defn intent-bytes
  "§3 .fram.rewrite.intent single-line EDN, recording exact modes/inodes/boundaries."
  [{:keys [gen-n phase coord-mode telem-mode telem-bytes telem-sha]}]
  (str->bytes
   (str "{:v 1 :gen \"gen-" gen-n "\" :gen_n " gen-n " :verb \"unify\""
        " :phase \"" phase "\" :pid 8500 :ts \"2026-07-19T00:00:00Z\""
        " :coord {:ino 100 :mode " coord-mode " :bytes 0 :sha \"none\"}"
        " :telem {:ino 101 :mode " telem-mode " :bytes " telem-bytes " :sha \"" telem-sha "\"}"
        " :new_coord {:ino 200 :sha1 \"none\"}"
        " :new_telem {:ino 201}}")))

;; ---------------------------------------------------------------------------
;; DOCTOR (real filesystem) — reads a durable intent record from <dir> and
;; classifies + restores. B2 restores the EXACT recorded modes; B-prime clobbers
;; both logs to the 0644 constant (the revoked hardcoded restore).
;; Classification is by inode/sha state, never by :phase (advisory only).
;; Returns {:action :roll-forward|:roll-back :coord-mode m :telem-mode m}.
;; ---------------------------------------------------------------------------
(defn read-intent
  "Parse <dir>/.fram.rewrite.intent, or nil if absent/unparsable."
  [dir]
  (let [f (File. (str dir) ".fram.rewrite.intent")]
    (when (.exists f)
      (try (edn/read-string (bytes->str (read-bytes (.getPath f))))
           (catch Throwable _ nil)))))

(defn doctor!
  "Run the modeled doctor over a scratch dir holding coordination.log,
   telemetry.log and an intent record. classify-fn (optional) decides
   :roll-forward vs :roll-back from live inode/sha state; default rolls forward
   when coordination.log already carries a generation line-1, else rolls back."
  [model dir & [{:keys [classify-fn]}]]
  (let [intent (read-intent dir)]
    (when-not intent (throw (ex-info "no intent record" {:dir dir})))
    (when-not (= 1 (:v intent))
      (throw (ex-info "unknown intent version — refuse" {:v (:v intent)})))
    (let [coord-f (File. (str dir) "coordination.log")
          telem-f (File. (str dir) "telemetry.log")
          coord-lines (when (.exists coord-f) (remove :torn (split-lines (read-bytes (.getPath coord-f)))))
          renamed? (boolean (generation-record coord-lines))
          action (cond classify-fn (classify-fn intent renamed?)
                       renamed? :roll-forward
                       :else :roll-back)
          cmode (doctor-restore-mode model (get-in intent [:coord :mode]))
          tmode (doctor-restore-mode model (get-in intent [:telem :mode]))]
      (when (.exists coord-f) (set-mode! (.getPath coord-f) cmode))
      (when (.exists telem-f) (set-mode! (.getPath telem-f) tmode))
      {:action action :coord-mode cmode :telem-mode tmode})))
