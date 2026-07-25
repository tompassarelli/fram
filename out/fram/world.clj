(ns fram.world)

(def max-blob-bytes 524288)

(def max-record-bytes 786432)

(def max-name-bytes 63)

(def max-slot-bytes 1024)

(def ^String nul (str (char 0)))

(def ^String blob-tag (str "fram.world.v1.blob" nul))

(def ^String version-tag (str "fram.world.v1.version" nul))

(def ^String candidate-tag (str "fram.world.v1.candidate" nul))

(def ^String manifest-tag (str "fram.world.v1.manifest" nul))

(def ^String lock-tag (str "fram.world.v1.lock" nul))

(def ^String receipt-tag (str "fram.world.v1.receipt" nul))

(defn utf8 [^String text]
  (.getBytes text "UTF-8"))

(defn byte-len [^String text]
  (alength (utf8 text)))

(defn ^String hex-of [digest]
  (apply str (mapv (fn [b] (format "%02x" (bit-and (int b) 255))) (vec digest))))

(defn ^String domain-hash [^String tag content]
  (let [md (. java.security.MessageDigest getInstance "SHA-256")
   primed (.update md (utf8 tag))]
  (hex-of (.digest md content))))

(defn ^String hash-text [^String tag ^String text]
  (domain-hash tag (utf8 text)))

(defn ^String byte-key [text]
  (apply str (mapv (fn [b] (str (char (bit-and (int b) 255)))) (vec (utf8 (str text))))))

(defn ^String join-space [parts]
  (let [v (vec parts)]
  (if (empty? v) "" (reduce (fn [acc part] (str acc " " part)) (first v) (vec (rest v))))))

(defn ^Boolean plain-string? [^String text]
  (loop [i 0]
  (if (>= i (count text)) true (let [n (int (.charAt text i))]
  (if (or (< n 32) (= n 34) (= n 92)) false (recur (inc i)))))))

(defn ^String render-string [^String text]
  (if (plain-string? text) (str "\"" text "\"") (str "\"" (apply str (mapv (fn [i] (let [n (int (.charAt text i))]
  (cond
  (= n 34) "\\\""
  (= n 92) "\\\\"
  (= n 10) "\\n"
  (= n 13) "\\r"
  (= n 9) "\\t"
  (< n 32) (format "\\u%04x" n)
  :else (subs text i (inc i))))) (vec (range (count text))))) "\"")))

(defn ^String render-key [k]
  (cond
  (keyword? k) (str k)
  (string? k) (render-string k)
  :else (throw (ex-info "fram.world: map key outside the canonical domain" {:reject :world-uncanonical-key}))))

(defn ^String render-value [v]
  (cond
  (nil? v) "nil"
  (boolean? v) (if v "true" "false")
  (string? v) (render-string v)
  (keyword? v) (str v)
  (number? v) (str v)
  (vector? v) (str "[" (join-space (mapv (fn [x] (render-value x)) v)) "]")
  (map? v) (let [ks (vec (sort-by (fn [k] (byte-key (render-key k))) (vec (keys v))))]
  (str "{" (join-space (mapv (fn [k] (str (render-key k) " " (render-value (get v k)))) ks)) "}"))
  :else (throw (ex-info "fram.world: value outside the canonical EDN domain" {:reject :world-uncanonical-value}))))

(defn ^String render-record [record]
  (render-value record))

(defn record-bytes [record]
  (byte-len (render-record record)))

(defn ^String blob-id [raw]
  (domain-hash blob-tag raw))

(defn ^String blob-b64 [raw]
  (.encodeToString (. java.util.Base64 getEncoder) raw))

(defn validate-blob [raw]
  (let [n (alength raw)]
  (if (> n max-blob-bytes) {:reject :world-blob-too-large :bytes n :max max-blob-bytes} nil)))

(defn validate-record [record]
  (let [n (record-bytes record)]
  (if (> n max-record-bytes) {:reject :world-record-too-large :bytes n :max max-record-bytes} nil)))

(defn ^Boolean clean-text? [^String text]
  (loop [i 0]
  (if (>= i (count text)) true (let [n (int (.charAt text i))]
  (if (or (< n 32) (= n 92) (and (<= 127 n) (<= n 159))) false (recur (inc i)))))))

(defn validate-world-name [name0]
  (if (string? name0) (let [n (byte-len name0)]
  (cond
  (zero? n) {:reject :world-name-illegal}
  (> n max-name-bytes) {:reject :world-name-too-long :bytes n :max max-name-bytes}
  (not (clean-text? name0)) {:reject :world-name-illegal}
  (not (. java.text.Normalizer isNormalized name0 (. java.text.Normalizer$Form valueOf "NFC"))) {:reject :world-name-illegal}
  :else nil)) {:reject :world-name-illegal}))

(defn split-slash [^String text]
  (loop [i 0
   start 0
   out []]
  (if (>= i (count text)) (conj out (subs text start i)) (if (= (int (.charAt text i)) 47) (recur (inc i) (inc i) (conj out (subs text start i))) (recur (inc i) start out)))))

(defn ^Boolean slot-segments-ok? [^String text]
  (every? (fn [seg] (and (pos? (count seg)) (not= seg ".") (not= seg ".."))) (split-slash text)))

(defn validate-slot [slot]
  (if (string? slot) (let [n (byte-len slot)]
  (cond
  (zero? n) {:reject :world-slot-illegal}
  (not (clean-text? slot)) {:reject :world-slot-illegal}
  (not (slot-segments-ok? slot)) {:reject :world-slot-illegal}
  (not (. java.text.Normalizer isNormalized slot (. java.text.Normalizer$Form valueOf "NFC"))) {:reject :world-slot-not-nfc}
  (> n max-slot-bytes) {:reject :world-slot-too-long :bytes n :max max-slot-bytes}
  :else nil)) {:reject :world-slot-illegal}))

(defn validate-mode [mode]
  (if (or (= mode "100644") (= mode "100755")) nil {:reject :world-illegal-mode :mode mode}))

(defn put-op [^String slot ^String mode ^String id]
  {:op :put :slot slot :mode mode :blob-id id})

(defn delete-op [^String slot]
  {:op :delete :slot slot})

(defn inherit-op [^String slot]
  {:op :inherit :slot slot})

(defn overlay-of [ops]
  (let [v (vec ops)
   by-slot (reduce (fn [acc op] (assoc acc (:slot op) op)) {} v)
   slots (vec (sort-by (fn [s] (byte-key s)) (vec (keys by-slot))))]
  (mapv (fn [s] (get by-slot s)) slots)))

(defn version-record [base-version-id ops]
  {:kind :world/version :base base-version-id :overlay (overlay-of ops)})

(defn ^String version-id [base-version-id ops]
  (hash-text version-tag (render-record (version-record base-version-id ops))))

(defn overlay-entry [record slot]
  (let [hits (filterv (fn [e] (= (:slot e) slot)) (vec (:overlay record)))]
  (if (empty? hits) nil (first hits))))

(defn resolve-slot [versions version slot]
  (loop [vid version]
  (if (nil? vid) {:present false :origin nil} (let [record (get versions vid)]
  (if (nil? record) {:present false :origin nil} (let [e (overlay-entry record slot)]
  (cond
  (nil? e) (recur (:base record))
  (= (:op e) :inherit) (recur (:base record))
  (= (:op e) :delete) {:present false :origin vid}
  :else {:present true :mode (:mode e) :blob-id (:blob-id e) :origin vid})))))))

(defn chain-slots [versions version]
  (loop [vid version
   acc []]
  (if (nil? vid) (vec (distinct acc)) (let [record (get versions vid)]
  (if (nil? record) (vec (distinct acc)) (recur (:base record) (vec (concat acc (mapv (fn [e] (:slot e)) (vec (:overlay record)))))))))))

(defn manifest [versions version]
  (let [slots (vec (sort-by (fn [s] (byte-key s)) (chain-slots versions version)))]
  (filterv (fn [row] (not (nil? row))) (mapv (fn [s] (let [r (resolve-slot versions version s)]
  (if (:present r) {:slot s :mode (:mode r) :blob-id (:blob-id r) :origin (:origin r)} nil))) slots))))

(defn compose [versions base-version-id selections]
  (let [ops (filterv (fn [op] (not (nil? op))) (mapv (fn [sel] (let [pair (vec sel)
   slot (get pair 0)
   source (get pair 1)
   rs (resolve-slot versions source slot)
   rb (resolve-slot versions base-version-id slot)]
  (cond
  (:present rs) (if (and (:present rb) (= (:blob-id rb) (:blob-id rs)) (= (:mode rb) (:mode rs))) nil (put-op slot (:mode rs) (:blob-id rs)))
  (:present rb) (delete-op slot)
  :else nil))) (vec selections)))]
  (version-record base-version-id ops)))

(defn ^Boolean nonce-hex? [nonce]
  (if (string? nonce) (and (= 32 (count nonce)) (.matches nonce "[0-9a-f]{32}")) false))

(defn ^String candidate-id [^String world-name expected-head ^String nonce]
  (if (nonce-hex? nonce) (hash-text candidate-tag (render-record {:kind :world/candidate :world world-name :expected-head expected-head :nonce nonce})) (throw (ex-info "fram.world: candidate nonce must be exactly 32 lowercase hex" {:reject :world-nonce-inadmissible}))))

(defn fork-claim [^String world-name ^String version]
  {:kind :world/head :world world-name :version version})

(defn derive-head [claims world-name]
  (let [hits (filterv (fn [c] (and (= (:kind c) :world/head) (= (:world c) world-name))) (vec claims))]
  (if (empty? hits) nil (:version (last hits)))))

(defn lock-record [version build-spec]
  {:kind :world/lock :version version :build-spec build-spec})

(defn ^String world-lock-id [version build-spec]
  (hash-text lock-tag (render-record (lock-record version build-spec))))
