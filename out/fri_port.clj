(ns fri-port
  (:require [fram.store :as store]
            [fram.types :as t])
  (:import [java.io ByteArrayOutputStream]
           [java.io File]
           [java.io FileOutputStream]
           [java.nio ByteBuffer]
           [java.nio ByteOrder]
           [java.nio CharBuffer]
           [java.nio.charset CodingErrorAction]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file StandardCopyOption]
           [java.security MessageDigest]
           [java.util Arrays]
           [java.util HexFormat]))

^{:line 16 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (def ^String MAGIC "FRAMFRI2")

^{:line 17 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (def FMT 2)

^{:line 18 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (def FLAGS 0)

^{:line 19 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (def PAYLOAD-FLAGS 0)

^{:line 20 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (def MAX-TERM-DEPTH 256)

^{:line 21 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (def FINGERPRINT-BYTES 32)

^{:line 25 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defrecord CacheSource [space-id fingerprint valid-bytes])

(defn cachesource-space-id [r] (:space-id r))

(defn cachesource-fingerprint [r] (:fingerprint r))

(defn cachesource-valid-bytes [r] (:valid-bytes r))

^{:line 26 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defrecord CacheReceipt [format space-id source-fingerprint source-position sha256])

(defn cachereceipt-format [r] (:format r))

(defn cachereceipt-space-id [r] (:space-id r))

(defn cachereceipt-source-fingerprint [r] (:source-fingerprint r))

(defn cachereceipt-source-position [r] (:source-position r))

(defn cachereceipt-sha256 [r] (:sha256 r))

^{:line 29 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defrecord CacheImage [source dump indexes store])

(defn cacheimage-source [r] (:source r))

(defn cacheimage-dump [r] (:dump r))

(defn cacheimage-indexes [r] (:indexes r))

(defn cacheimage-store [r] (:store r))

^{:line 32 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- fail [^String message type]
  ^{:line 33 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (throw ^{:line 33 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ex-info message ^{:line 33 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} {:type type})))

^{:line 35 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ^Boolean valid-fingerprint? [^String value]
  ^{:line 36 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (some? ^{:line 36 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (re-matches #"[0-9a-f]{64}" value)))

^{:line 38 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn ^CacheSource source-binding [^String space-id ^String fingerprint valid-bytes]
  ^{:line 40 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 40 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 40 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (pos? ^{:line 40 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count space-id)) ^{:line 41 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 41 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (valid-fingerprint? fingerprint) ^{:line 41 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= valid-bytes 0))) ^{:line 42 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (->CacheSource space-id fingerprint valid-bytes) ^{:line 43 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: source binding requires SpaceId, sha256, and non-negative valid-byte position" :invalid-cache-source)))

^{:line 46 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ^String hex [bytes]
  ^{:line 47 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (apply str ^{:line 47 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map ^{:line 47 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [value] ^{:line 48 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (format "%02x" ^{:line 48 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (bit-and value 255))) bytes)))

^{:line 51 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- sha256-bytes [bytes]
  ^{:line 52 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.digest ^{:line 52 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (MessageDigest/getInstance "SHA-256") bytes))

^{:line 54 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ^String sha256 [bytes]
  ^{:line 55 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (hex ^{:line 55 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sha256-bytes bytes)))

^{:line 57 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- strict-utf8 [^String value ^String context]
  ^{:line 58 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (try
  ^{:line 59 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [encoder ^{:line 60 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doto ^{:line 60 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.newEncoder StandardCharsets/UTF_8)
  ^{:line 61 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.onMalformedInput CodingErrorAction/REPORT)
  ^{:line 62 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.onUnmappableCharacter CodingErrorAction/REPORT))
   buffer ^{:line 63 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.encode encoder ^{:line 63 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (CharBuffer/wrap value))
   bytes ^{:line 64 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (byte-array ^{:line 64 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.remaining buffer))]
  ^{:line 65 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.get buffer bytes)
  bytes)
  (catch java.nio.charset.CharacterCodingException error
    ^{:line 68 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail ^{:line 68 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str "fri: invalid UTF-8 in " context) :invalid-fri-cache))))

^{:line 70 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ^String strict-utf8-string [bytes ^String context]
  ^{:line 71 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (try
  ^{:line 72 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [decoder ^{:line 73 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doto ^{:line 73 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.newDecoder StandardCharsets/UTF_8)
  ^{:line 74 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.onMalformedInput CodingErrorAction/REPORT)
  ^{:line 75 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.onUnmappableCharacter CodingErrorAction/REPORT))]
  ^{:line 76 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str ^{:line 76 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.decode decoder ^{:line 76 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ByteBuffer/wrap bytes))))
  (catch java.nio.charset.CharacterCodingException error
    ^{:line 78 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail ^{:line 78 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str "fri: invalid UTF-8 in " context) :invalid-fri-cache))))

^{:line 80 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- require-u32 [value ^String context]
  ^{:line 81 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 81 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 81 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= value 0) ^{:line 81 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (<= value 4294967295)) value ^{:line 83 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail ^{:line 83 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str "fri: " context " exceeds u32") :invalid-fri-cache)))

^{:line 85 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- require-i64 [value ^String context]
  ^{:line 86 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 86 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 86 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= value -9223372036854775808) ^{:line 86 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (<= value 9223372036854775807)) value ^{:line 88 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail ^{:line 88 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str "fri: " context " exceeds i64") :invalid-fri-cache)))

^{:line 90 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-u8! [out value]
  ^{:line 91 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 91 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write out ^{:line 91 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (int ^{:line 91 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (bit-and value 255)))
  nil))

^{:line 93 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-u16-le! [out value]
  ^{:line 94 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 95 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [position ^{:line 95 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range 2)]
  ^{:line 96 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out ^{:line 96 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (unsigned-bit-shift-right value ^{:line 96 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (* position 8))))
  nil))

^{:line 99 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-u32-le! [out value ^String context]
  ^{:line 100 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [checked ^{:line 100 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (require-u32 value context)]
  ^{:line 101 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [position ^{:line 101 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range 4)]
  ^{:line 102 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out ^{:line 102 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (unsigned-bit-shift-right checked ^{:line 102 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (* position 8))))
  nil))

^{:line 105 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-i64-le! [out value ^String context]
  ^{:line 106 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [checked ^{:line 106 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (require-i64 value context)]
  ^{:line 107 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [position ^{:line 107 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range 8)]
  ^{:line 108 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out ^{:line 108 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (unsigned-bit-shift-right checked ^{:line 108 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (* position 8))))
  nil))

^{:line 111 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ensure-remaining! [buffer amount ^String context]
  ^{:line 112 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 112 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 112 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= amount 0) ^{:line 112 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= ^{:line 112 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.remaining buffer) amount)) nil ^{:line 114 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail ^{:line 114 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str "fri: truncated " context) :invalid-fri-cache)))

^{:line 116 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-u8! [buffer ^String context]
  ^{:line 117 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 118 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ensure-remaining! buffer 1 context)
  ^{:line 119 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [position ^{:line 119 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.position buffer)
   value ^{:line 120 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.get buffer position)]
  ^{:line 121 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.position buffer ^{:line 121 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc position))
  ^{:line 122 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (bit-and 255 ^{:line 122 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (int value)))))

^{:line 124 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-u16-le! [buffer ^String context]
  ^{:line 125 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 125 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ensure-remaining! buffer 2 context)
  ^{:line 126 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (bit-and 65535 ^{:line 126 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (int ^{:line 126 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.getShort buffer)))))

^{:line 128 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-u32-le! [buffer ^String context]
  ^{:line 129 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 129 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ensure-remaining! buffer 4 context)
  ^{:line 130 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Integer/toUnsignedLong ^{:line 130 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.getInt buffer))))

^{:line 132 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-i64-le! [buffer ^String context]
  ^{:line 133 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 133 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ensure-remaining! buffer 8 context)
  ^{:line 133 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.getLong buffer)))

^{:line 135 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-fixed! [buffer amount ^String context]
  ^{:line 136 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 137 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ensure-remaining! buffer amount context)
  ^{:line 138 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [bytes ^{:line 138 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (byte-array amount)]
  ^{:line 139 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.get buffer bytes)
  bytes)))

^{:line 142 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-sized-text! [out ^String value ^String context]
  ^{:line 143 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [bytes ^{:line 143 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (strict-utf8 value context)]
  ^{:line 144 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 144 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (alength bytes) context)
  ^{:line 145 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write out bytes)
  nil))

^{:line 148 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-sized-bytes! [buffer ^String context]
  ^{:line 149 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [amount ^{:line 149 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u32-le! buffer context)]
  ^{:line 150 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 150 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (> amount 2147483647) ^{:line 151 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail ^{:line 151 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str "fri: " context " length exceeds JVM bounds") :invalid-fri-cache) ^{:line 152 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-fixed! buffer amount context))))

^{:line 154 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ^String read-sized-text! [buffer ^String context]
  ^{:line 155 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (strict-utf8-string ^{:line 155 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-sized-bytes! buffer context) context))

^{:line 161 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (declare write-term-v1!)

^{:line 163 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-triple-v1! [out value depth]
  ^{:line 164 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 165 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 165 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 165 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple? value)) ^{:line 165 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 166 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TermCodecV1 expected Triple" :invalid-fri-cache)))
  ^{:line 167 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 167 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (> depth MAX-TERM-DEPTH) ^{:line 167 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 168 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TermCodecV1 depth exceeds 256" :invalid-fri-cache)))
  ^{:line 169 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 7)
  ^{:line 170 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-term-v1! out ^{:line 170 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple-slot0 value) ^{:line 170 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc depth))
  ^{:line 171 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-term-v1! out ^{:line 171 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple-slot1 value) ^{:line 171 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc depth))
  ^{:line 172 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-term-v1! out ^{:line 172 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple-slot2 value) ^{:line 172 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc depth))))

^{:line 174 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-term-v1! [out term depth]
  ^{:line 175 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cond
  ^{:line 176 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple? term) ^{:line 176 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-triple-v1! out term depth)
  ^{:line 177 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (string? term) ^{:line 178 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 178 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 1)
  ^{:line 178 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-sized-text! out term "String atom"))
  ^{:line 179 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (integer? term) ^{:line 180 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 180 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 2)
  ^{:line 180 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out term "Int atom"))
  ^{:line 181 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 181 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (number? term) ^{:line 181 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 181 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (integer? term))) ^{:line 182 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 182 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 3)
  ^{:line 183 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 183 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Double/doubleToLongBits ^{:line 183 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (double term)) "Float atom"))
  ^{:line 184 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (false? term) ^{:line 184 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 4)
  ^{:line 185 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (true? term) ^{:line 185 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 5)
  ^{:line 186 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (keyword? term) ^{:line 187 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [spelling ^{:line 187 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (subs ^{:line 187 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str term) 1)]
  ^{:line 188 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 188 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (empty? spelling) ^{:line 188 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 189 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TermCodecV1 Keyword is empty" :invalid-fri-cache)))
  ^{:line 190 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 6)
  ^{:line 191 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-sized-text! out spelling "Keyword atom"))
  ^{:line 192 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/instant? term) ^{:line 193 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 193 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out 8)
  ^{:line 194 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 194 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/instant-epoch-seconds term) "Instant seconds")
  ^{:line 195 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 195 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/instant-nanos term) "Instant nanos"))
  :else ^{:line 196 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: value outside TermCodecV1" :invalid-fri-cache)))

^{:line 198 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (declare read-term-v1!)

^{:line 200 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-term-v1! [buffer depth]
  ^{:line 201 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 202 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 202 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (> depth MAX-TERM-DEPTH) ^{:line 202 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 203 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TermCodecV1 depth exceeds 256" :invalid-fri-cache)))
  ^{:line 204 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [tag ^{:line 204 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u8! buffer "Term tag")]
  ^{:line 205 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cond
  ^{:line 206 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 1) ^{:line 206 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-sized-text! buffer "String atom")
  ^{:line 207 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 2) ^{:line 207 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "Int atom")
  ^{:line 208 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 3) ^{:line 208 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Double/longBitsToDouble ^{:line 208 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "Float atom"))
  ^{:line 209 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 4) false
  ^{:line 210 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 5) true
  ^{:line 211 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 6) ^{:line 212 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [spelling ^{:line 212 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-sized-text! buffer "Keyword atom")]
  ^{:line 213 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 213 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (empty? spelling) ^{:line 214 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TermCodecV1 Keyword is empty" :invalid-fri-cache) ^{:line 215 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (keyword spelling)))
  ^{:line 216 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 7) ^{:line 217 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple ^{:line 217 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-term-v1! buffer ^{:line 217 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc depth)) ^{:line 218 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-term-v1! buffer ^{:line 218 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc depth)) ^{:line 219 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-term-v1! buffer ^{:line 219 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc depth)))
  ^{:line 220 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= tag 8) ^{:line 221 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/instant ^{:line 221 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "Instant seconds") ^{:line 222 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u32-le! buffer "Instant nanos"))
  :else ^{:line 223 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: unknown TermCodecV1 tag" :invalid-fri-cache)))))

^{:line 225 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- encode-term-v1! [term]
  ^{:line 226 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [out ^{:line 226 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ByteArrayOutputStream.)]
  ^{:line 227 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-term-v1! out term 0)
  ^{:line 228 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.toByteArray out)))

^{:line 230 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- decode-term-v1! [bytes]
  ^{:line 231 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [buffer ^{:line 232 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doto ^{:line 232 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ByteBuffer/wrap bytes)
  ^{:line 232 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.order ByteOrder/LITTLE_ENDIAN))
   term ^{:line 233 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-term-v1! buffer 0)]
  ^{:line 234 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 234 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= 0 ^{:line 234 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.remaining buffer)) term ^{:line 236 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: trailing bytes inside TermCodecV1 row" :invalid-fri-cache))))

^{:line 238 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- atom-row [value]
  ^{:line 239 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cond
  ^{:line 240 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (string? value) ^{:line 240 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->AtomRow :string value nil nil nil nil nil)
  ^{:line 241 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (integer? value) ^{:line 241 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->AtomRow :int nil value nil nil nil nil)
  ^{:line 242 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 242 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (number? value) ^{:line 242 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 242 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (integer? value))) ^{:line 243 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->AtomRow :float nil nil value nil nil nil)
  ^{:line 244 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (boolean? value) ^{:line 244 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->AtomRow :bool nil nil nil value nil nil)
  ^{:line 245 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (keyword? value) ^{:line 245 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->AtomRow :keyword nil nil nil nil value nil)
  ^{:line 246 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/instant? value) ^{:line 246 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->AtomRow :instant nil nil nil nil nil value)
  :else ^{:line 247 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TermCodecV1 AtomRow contains a Triple" :invalid-fri-cache)))

^{:line 249 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- action-code [action]
  ^{:line 250 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cond
  ^{:line 251 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= action t/assert-action) 1
  ^{:line 252 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= action t/retract-action) 2
  :else ^{:line 253 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: operation has an unknown action" :invalid-fri-cache)))

^{:line 255 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- code-action [code]
  ^{:line 256 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cond
  ^{:line 257 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= code 1) t/assert-action
  ^{:line 258 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= code 2) t/retract-action
  :else ^{:line 259 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: operation has an unknown action code" :invalid-fri-cache)))

^{:line 261 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- atom-handle [position]
  ^{:line 261 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (* 2 position))

^{:line 262 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- triple-handle [position]
  ^{:line 262 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc ^{:line 262 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (* 2 position)))

^{:line 264 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- index-data [rows]
  ^{:line 265 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [with-handles ^{:line 266 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map-indexed ^{:line 266 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position row] ^{:line 267 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 267 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (triple-handle position) row]) rows)]
  ^{:line 269 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 269 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (vec ^{:line 269 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sort ^{:line 269 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map ^{:line 269 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 270 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 270 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot0 ^{:line 270 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 270 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 0)]) with-handles))) ^{:line 272 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (vec ^{:line 272 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sort ^{:line 272 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map ^{:line 272 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 273 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 273 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot1 ^{:line 273 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 273 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 0)]) with-handles))) ^{:line 275 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (vec ^{:line 275 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sort ^{:line 275 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map ^{:line 275 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 276 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 276 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot2 ^{:line 276 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 276 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 0)]) with-handles))) ^{:line 278 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (vec ^{:line 278 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sort ^{:line 278 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map ^{:line 278 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 279 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 279 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot0 ^{:line 279 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 280 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot1 ^{:line 280 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 281 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 0)]) with-handles))) ^{:line 283 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (vec ^{:line 283 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sort ^{:line 283 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map ^{:line 283 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 284 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 284 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot1 ^{:line 284 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 285 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot2 ^{:line 285 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 286 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 0)]) with-handles))) ^{:line 288 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (vec ^{:line 288 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sort ^{:line 288 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map ^{:line 288 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 289 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 289 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot0 ^{:line 289 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 290 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot2 ^{:line 290 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 1)) ^{:line 291 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry 0)]) with-handles)))]))

^{:line 294 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (declare atom-value resolve-handle)

^{:line 296 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-term-row! [out term]
  ^{:line 297 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [bytes ^{:line 297 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (encode-term-v1! term)]
  ^{:line 298 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 298 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (alength bytes) "Term row length")
  ^{:line 299 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write out bytes)
  nil))

^{:line 302 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-payload! [dump]
  ^{:line 303 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [out ^{:line 303 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ByteArrayOutputStream.)
   atoms ^{:line 304 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-atoms dump)
   triples ^{:line 305 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-triples dump)
   transactions ^{:line 306 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-transactions dump)
   operations ^{:line 307 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-operations dump)
   indexes ^{:line 308 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-data triples)]
  ^{:line 309 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u16-le! out store/term-store-dump-version)
  ^{:line 310 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u16-le! out PAYLOAD-FLAGS)
  ^{:line 311 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 311 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-next-sequence dump) "next sequence")
  ^{:line 312 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 312 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count atoms) "AtomRow count")
  ^{:line 313 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [row atoms]
  ^{:line 313 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-term-row! out ^{:line 313 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-value row)))
  ^{:line 314 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 314 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count triples) "TripleRow count")
  ^{:line 315 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [position ^{:line 315 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range ^{:line 315 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count triples))]
  ^{:line 316 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-term-row! out ^{:line 316 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 316 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (triple-handle position))))
  ^{:line 317 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 317 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count transactions) "TransactionRow count")
  ^{:line 318 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [row transactions]
  ^{:line 319 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 319 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/transactionrow-sequence row) "transaction sequence")
  ^{:line 320 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 320 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/transactionrow-first-operation row) "first operation")
  ^{:line 321 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 321 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/transactionrow-operation-count row) "operation count"))
  ^{:line 322 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 322 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count operations) "OperationRow count")
  ^{:line 323 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [row operations]
  ^{:line 324 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 324 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-tx-sequence row) "operation transaction")
  ^{:line 325 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 325 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-ordinal row) "operation ordinal")
  ^{:line 326 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u8! out ^{:line 326 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (action-code ^{:line 326 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-action row)))
  ^{:line 327 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out ^{:line 327 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-triple-handle row) "operation handle"))
  ^{:line 328 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [entries indexes]
  ^{:line 329 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u32-le! out ^{:line 329 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count entries) "slot index row count")
  ^{:line 330 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [entry entries]
  ^{:line 331 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doseq [handle entry]
  ^{:line 332 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! out handle "slot index handle"))))
  ^{:line 333 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.toByteArray out)))

^{:line 335 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-count! [buffer ^String context minimum-row]
  ^{:line 336 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row-count ^{:line 336 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u32-le! buffer context)]
  ^{:line 337 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 337 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (or ^{:line 337 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (> row-count 2147483647) ^{:line 338 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (> ^{:line 338 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (* row-count minimum-row) ^{:line 338 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.remaining buffer))) ^{:line 339 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail ^{:line 339 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str "fri: impossible " context) :invalid-fri-cache) row-count)))

^{:line 342 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-term-row! [buffer]
  ^{:line 343 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (decode-term-v1! ^{:line 343 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-sized-bytes! buffer "Term row")))

^{:line 345 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-atoms! [buffer]
  ^{:line 346 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row-count ^{:line 346 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-count! buffer "AtomRow count" 5)]
  ^{:line 347 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 347 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position] ^{:line 348 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [term ^{:line 348 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-term-row! buffer)]
  ^{:line 349 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 349 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple? term) ^{:line 350 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: AtomRow decoded as Triple" :invalid-fri-cache) ^{:line 351 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-row term)))) ^{:line 352 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range row-count))))

^{:line 354 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- initial-handles [atoms]
  ^{:line 355 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (into ^{:line 355 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} {} ^{:line 356 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map-indexed ^{:line 356 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position row] ^{:line 357 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 357 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-value row) ^{:line 357 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-handle position)]) atoms)))

^{:line 360 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- required-handle [handles term]
  ^{:line 361 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 361 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (contains? handles term) ^{:line 362 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (get handles term) ^{:line 363 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TripleRow references a term absent from prior rows" :invalid-fri-cache)))

^{:line 366 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-triples! [buffer atoms]
  ^{:line 367 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row-count ^{:line 367 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-count! buffer "TripleRow count" 8)]
  ^{:line 368 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (loop [position 0
   rows ^{:line 368 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} []
   handles ^{:line 368 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (initial-handles atoms)]
  ^{:line 369 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 369 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= position row-count) rows ^{:line 371 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [term ^{:line 371 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-term-row! buffer)]
  ^{:line 372 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 372 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 372 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple? term)) ^{:line 373 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: TripleRow decoded as Atom" :invalid-fri-cache) ^{:line 374 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 374 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (contains? handles term) ^{:line 375 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: duplicate structural TripleRow" :invalid-fri-cache) ^{:line 376 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row ^{:line 377 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->TripleRow ^{:line 378 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (required-handle handles ^{:line 378 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple-slot0 term)) ^{:line 379 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (required-handle handles ^{:line 379 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple-slot1 term)) ^{:line 380 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (required-handle handles ^{:line 380 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple-slot2 term)))]
  ^{:line 381 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (recur ^{:line 381 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc position) ^{:line 382 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (conj rows row) ^{:line 383 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (assoc handles term ^{:line 383 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (triple-handle position)))))))))))

^{:line 385 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-transactions! [buffer]
  ^{:line 386 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row-count ^{:line 386 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-count! buffer "TransactionRow count" 24)]
  ^{:line 387 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 387 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position] ^{:line 388 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->TransactionRow ^{:line 389 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "transaction sequence") ^{:line 390 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "first operation") ^{:line 391 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "operation count"))) ^{:line 392 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range row-count))))

^{:line 394 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-operations! [buffer]
  ^{:line 395 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row-count ^{:line 395 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-count! buffer "OperationRow count" 25)]
  ^{:line 396 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 396 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position] ^{:line 397 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->OperationRow ^{:line 398 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "operation transaction") ^{:line 399 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "operation ordinal") ^{:line 400 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (code-action ^{:line 400 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u8! buffer "operation action")) ^{:line 401 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "operation handle"))) ^{:line 402 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range row-count))))

^{:line 404 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-index! [buffer width]
  ^{:line 405 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row-count ^{:line 405 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-count! buffer "slot index row count" ^{:line 405 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (* width 8))]
  ^{:line 406 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 406 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position] ^{:line 407 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 407 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [field] ^{:line 408 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "slot index handle")) ^{:line 409 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range width))) ^{:line 410 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (range row-count))))

^{:line 412 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-indexes! [buffer]
  ^{:line 413 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 413 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [width] ^{:line 413 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-index! buffer width)) ^{:line 413 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [2 2 2 3 3 3]))

^{:line 415 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-payload! [payload ^String space-id]
  ^{:line 416 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [buffer ^{:line 417 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doto ^{:line 417 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ByteBuffer/wrap payload)
  ^{:line 417 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.order ByteOrder/LITTLE_ENDIAN))
   dump-version ^{:line 418 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u16-le! buffer "TermStoreDump version")
   payload-flags ^{:line 419 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u16-le! buffer "payload flags")]
  ^{:line 420 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 420 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 420 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= dump-version store/term-store-dump-version)) ^{:line 421 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: legacy TermStore payload requires rebuild" :cache-rebuild-required) ^{:line 422 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 422 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 422 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= payload-flags PAYLOAD-FLAGS)) ^{:line 423 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: unsupported payload flags" :invalid-fri-cache) ^{:line 424 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [next-sequence ^{:line 424 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "next sequence")
   atoms ^{:line 425 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-atoms! buffer)
   triples ^{:line 426 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-triples! buffer atoms)
   transactions ^{:line 427 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-transactions! buffer)
   operations ^{:line 428 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-operations! buffer)
   indexes ^{:line 429 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-indexes! buffer)
   dump ^{:line 431 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/->TermStoreDump dump-version space-id next-sequence atoms triples transactions operations)]
  ^{:line 433 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 433 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 433 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= 0 ^{:line 433 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.remaining buffer))) ^{:line 434 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: trailing bytes in binary payload" :invalid-fri-cache) ^{:line 435 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 435 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 435 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= indexes ^{:line 435 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-data triples))) ^{:line 436 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: slot index does not match TripleRow table" :invalid-fri-cache) ^{:line 438 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} {:dump dump :indexes indexes})))))))

^{:line 440 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- validate-dump! [dump ^CacheSource source]
  ^{:line 441 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 441 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 441 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/term-store-dump? dump)) ^{:line 442 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: legacy cache input requires rebuild from canonical FRAMLOG" :cache-rebuild-required) ^{:line 444 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 444 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 444 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= store/term-store-dump-version ^{:line 444 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-version dump))) ^{:line 445 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: legacy cache input requires rebuild from canonical FRAMLOG" :cache-rebuild-required) ^{:line 447 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 447 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 447 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= ^{:line 447 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-space-id source) ^{:line 448 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-space-id dump))) ^{:line 449 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: cache source and TermStore belong to different spaces" :cache-space-mismatch) ^{:line 451 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [ctx ^{:line 451 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/new-term-store ^{:line 451 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-space-id source))]
  ^{:line 452 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/load-term-store! ctx dump)
  ctx)))))

^{:line 455 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- write-bytes! [^String path bytes]
  ^{:line 456 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [target ^{:line 456 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (File. path)
   parent ^{:line 457 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.getParentFile target)
   temporary ^{:line 458 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (File. ^{:line 458 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str path ".tmp"))]
  ^{:line 459 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 459 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (some? parent) ^{:line 459 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 459 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.mkdirs parent)))
  ^{:line 460 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (with-open [stream ^{:line 460 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (FileOutputStream. temporary)]
  ^{:line 461 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write stream bytes)
  ^{:line 462 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.flush stream)
  ^{:line 463 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.force ^{:line 463 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.getChannel stream) true))
  ^{:line 464 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Files/move ^{:line 464 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.toPath temporary) ^{:line 464 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.toPath target) ^{:line 465 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (into-array StandardCopyOption ^{:line 466 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
  nil))

^{:line 470 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn write-fri! [dump ^String path ^CacheSource source]
  ^{:line 472 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  ^{:line 473 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (validate-dump! dump source)
  ^{:line 474 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [payload ^{:line 474 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-payload! dump)
   payload-sha ^{:line 475 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sha256-bytes payload)
   buffer ^{:line 476 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ByteArrayOutputStream.)]
  ^{:line 477 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write buffer ^{:line 477 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (strict-utf8 MAGIC "magic"))
  ^{:line 478 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u16-le! buffer FMT)
  ^{:line 479 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-u16-le! buffer FLAGS)
  ^{:line 480 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-sized-text! buffer ^{:line 480 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-space-id source) "SpaceId")
  ^{:line 481 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write buffer ^{:line 481 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.parseHex ^{:line 481 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (HexFormat/of) ^{:line 482 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-fingerprint source)))
  ^{:line 483 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! buffer ^{:line 483 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-valid-bytes source) "source position")
  ^{:line 484 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write buffer payload-sha)
  ^{:line 485 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-i64-le! buffer ^{:line 485 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (alength payload) "payload length")
  ^{:line 486 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.write buffer payload)
  ^{:line 487 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [bytes ^{:line 487 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.toByteArray buffer)]
  ^{:line 488 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (write-bytes! path bytes)
  ^{:line 489 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (->CacheReceipt FMT ^{:line 490 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-space-id source) ^{:line 491 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-fingerprint source) ^{:line 492 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-valid-bytes source) ^{:line 493 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sha256 bytes))))))

^{:line 495 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ^Boolean bytes-prefix? [bytes prefix]
  ^{:line 496 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 496 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= ^{:line 496 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (alength bytes) ^{:line 496 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (alength prefix)) ^{:line 497 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Arrays/equals prefix ^{:line 497 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Arrays/copyOfRange bytes 0 ^{:line 497 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (alength prefix)))))

^{:line 499 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- read-envelope! [^String path]
  ^{:line 500 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (try
  ^{:line 501 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [bytes ^{:line 501 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Files/readAllBytes ^{:line 501 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.toPath ^{:line 501 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (File. path)))
   legacy ^{:line 502 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (strict-utf8 "FRAMFRI1" "legacy magic")]
  ^{:line 503 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 503 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (bytes-prefix? bytes legacy) ^{:line 504 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: legacy cache requires rebuild from canonical FRAMLOG" :cache-rebuild-required) ^{:line 506 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [buffer ^{:line 507 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (doto ^{:line 507 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ByteBuffer/wrap bytes)
  ^{:line 507 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.order ByteOrder/LITTLE_ENDIAN))
   magic ^{:line 509 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (strict-utf8-string ^{:line 509 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-fixed! buffer ^{:line 509 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count MAGIC) "cache magic") "cache magic")]
  ^{:line 511 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 511 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 511 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= magic MAGIC)) ^{:line 512 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: invalid cache magic" :invalid-fri-cache) ^{:line 513 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [format ^{:line 513 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u16-le! buffer "cache format")
   flags ^{:line 514 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-u16-le! buffer "cache flags")]
  ^{:line 515 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 515 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= format 1) ^{:line 516 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: legacy cache format requires rebuild from canonical FRAMLOG" :cache-rebuild-required) ^{:line 518 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 518 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 518 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= format FMT)) ^{:line 519 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: unsupported cache format" :invalid-fri-cache) ^{:line 520 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 520 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 520 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= flags FLAGS)) ^{:line 521 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: unsupported cache flags" :invalid-fri-cache) ^{:line 522 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [space-id ^{:line 522 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-sized-text! buffer "SpaceId")
   fingerprint ^{:line 524 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (hex ^{:line 524 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-fixed! buffer FINGERPRINT-BYTES "source fingerprint"))
   source-position ^{:line 527 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "source position")
   payload-sha ^{:line 529 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-fixed! buffer FINGERPRINT-BYTES "payload checksum")
   payload-length ^{:line 531 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-i64-le! buffer "payload length")]
  ^{:line 532 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 532 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (or ^{:line 532 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (< source-position 0) ^{:line 533 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (or ^{:line 533 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (< payload-length 0) ^{:line 534 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (> payload-length 2147483647))) ^{:line 535 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: invalid source position or payload length" :invalid-fri-cache) ^{:line 537 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [payload ^{:line 538 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-fixed! buffer payload-length "cache payload")]
  ^{:line 539 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 539 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 539 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= 0 ^{:line 539 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (.remaining buffer))) ^{:line 540 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: cache has trailing bytes" :invalid-fri-cache) ^{:line 541 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} {:space-id space-id :fingerprint fingerprint :source-position source-position :payload-sha payload-sha :payload payload}))))))))))))
  (catch clojure.lang.ExceptionInfo error
    ^{:line 547 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (throw error))
  (catch Throwable error
    ^{:line 549 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (throw ^{:line 549 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (ex-info "fri: cache is truncated or malformed" ^{:line 550 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} {:type :invalid-fri-cache :cause ^{:line 550 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (str error)})))))

^{:line 552 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn open-fri! [^String path ^CacheSource source]
  ^{:line 553 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [envelope ^{:line 553 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-envelope! path)
   payload ^{:line 554 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (:payload envelope)
   stored-space ^{:line 555 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (:space-id envelope)
   stored-fingerprint ^{:line 556 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (:fingerprint envelope)
   stored-position ^{:line 557 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (:source-position envelope)
   stored-sha ^{:line 558 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (:payload-sha envelope)]
  ^{:line 559 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 559 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 559 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= stored-space ^{:line 559 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-space-id source))) ^{:line 560 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: cache belongs to a different SpaceId" :cache-space-mismatch) ^{:line 561 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 561 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 561 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (and ^{:line 561 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= stored-fingerprint ^{:line 561 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-fingerprint source)) ^{:line 562 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= stored-position ^{:line 562 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-valid-bytes source)))) ^{:line 563 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: cache does not match the canonical FRAMLOG prefix" :cache-source-mismatch) ^{:line 565 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 565 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (not ^{:line 565 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (Arrays/equals stored-sha ^{:line 565 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (sha256-bytes payload))) ^{:line 566 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: cache payload checksum mismatch" :invalid-fri-cache) ^{:line 567 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [cache-data ^{:line 568 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (read-payload! payload stored-space)
   dump ^{:line 569 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (:dump cache-data)
   ctx ^{:line 570 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (validate-dump! dump source)]
  ^{:line 571 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (->CacheImage source dump ^{:line 571 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (:indexes cache-data) ctx)))))))

^{:line 573 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- atom-value [row]
  ^{:line 574 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [kind ^{:line 574 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/atomrow-kind row)]
  ^{:line 575 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cond
  ^{:line 576 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= kind :string) ^{:line 576 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/atomrow-string-value row)
  ^{:line 577 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= kind :int) ^{:line 577 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/atomrow-int-value row)
  ^{:line 578 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= kind :float) ^{:line 578 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/atomrow-float-value row)
  ^{:line 579 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= kind :bool) ^{:line 579 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/atomrow-bool-value row)
  ^{:line 580 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= kind :keyword) ^{:line 580 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/atomrow-keyword-value row)
  ^{:line 581 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= kind :instant) ^{:line 581 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/atomrow-instant-value row)
  :else ^{:line 582 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: AtomRow has an unknown kind" :invalid-fri-cache))))

^{:line 584 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- ^Boolean atom-handle? [handle]
  ^{:line 584 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= 0 ^{:line 584 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mod handle 2)))

^{:line 585 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- handle-position [handle]
  ^{:line 585 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (quot handle 2))

^{:line 587 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- resolve-handle [dump handle]
  ^{:line 588 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [position ^{:line 588 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (handle-position handle)]
  ^{:line 589 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 589 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-handle? handle) ^{:line 590 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-value ^{:line 590 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth ^{:line 590 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-atoms dump) position)) ^{:line 591 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row ^{:line 591 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth ^{:line 591 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-triples dump) position)]
  ^{:line 592 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triple ^{:line 592 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 592 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot0 row)) ^{:line 593 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 593 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot1 row)) ^{:line 594 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 594 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/triplerow-slot2 row)))))))

^{:line 596 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- term-handles [dump]
  ^{:line 597 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (into ^{:line 597 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} {} ^{:line 598 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (concat ^{:line 599 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map-indexed ^{:line 599 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position row] ^{:line 600 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 600 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-value row) ^{:line 600 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (atom-handle position)]) ^{:line 601 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-atoms dump)) ^{:line 602 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (map-indexed ^{:line 602 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position row] ^{:line 603 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [^{:line 603 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 603 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (triple-handle position)) ^{:line 604 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (triple-handle position)]) ^{:line 605 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-triples dump)))))

^{:line 607 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- index-matches [image index-position keys]
  ^{:line 608 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [dump ^{:line 608 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-dump image)
   handles ^{:line 609 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (term-handles dump)
   key-handles ^{:line 610 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 610 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [term] ^{:line 610 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (get handles term)) keys)]
  ^{:line 611 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 611 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (some nil? key-handles) ^{:line 612 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [] ^{:line 613 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [entries ^{:line 613 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth ^{:line 613 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-indexes image) index-position)
   key-count ^{:line 614 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count key-handles)]
  ^{:line 615 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 615 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 616 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 616 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth entry key-count))) ^{:line 617 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (filter ^{:line 617 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [entry] ^{:line 618 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= key-handles ^{:line 618 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (subvec entry 0 key-count))) entries))))))

^{:line 621 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- live-positions-as-of [image sequence]
  ^{:line 622 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 622 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (< sequence 0) ^{:line 623 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fail "fri: as-of sequence must be non-negative" :invalid-as-of-sequence) ^{:line 624 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [dump ^{:line 624 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-dump image)
   operations ^{:line 625 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-operations dump)]
  ^{:line 626 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (loop [position 0
   active ^{:line 626 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} {}
   live ^{:line 626 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} []]
  ^{:line 627 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 627 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (or ^{:line 627 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (>= position ^{:line 627 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (count operations)) ^{:line 628 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (> ^{:line 628 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-tx-sequence ^{:line 628 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth operations position)) sequence)) ^{:line 629 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (vec ^{:line 629 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (keep-indexed ^{:line 629 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [index present?] ^{:line 630 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if present? ^{:line 630 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (do
  index))) live)) ^{:line 632 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row ^{:line 632 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth operations position)
   handle ^{:line 633 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-triple-handle row)
   positions ^{:line 634 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (get active handle ^{:line 634 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [])]
  ^{:line 635 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 635 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= t/assert-action ^{:line 635 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-action row)) ^{:line 636 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (recur ^{:line 636 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc position) ^{:line 637 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (assoc active handle ^{:line 637 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (conj positions position)) ^{:line 638 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (conj live true)) ^{:line 639 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 639 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (empty? positions) ^{:line 640 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (recur ^{:line 640 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc position) active ^{:line 640 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (conj live false)) ^{:line 641 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [target ^{:line 641 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (peek positions)]
  ^{:line 642 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (recur ^{:line 642 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (inc position) ^{:line 643 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (assoc active handle ^{:line 643 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (pop positions)) ^{:line 644 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (conj ^{:line 644 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (assoc live target false) false)))))))))))

^{:line 646 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn- occurrence-event [dump position]
  ^{:line 647 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [row ^{:line 647 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth ^{:line 647 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-operations dump) position)
   transaction ^{:line 649 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/transaction-coordinate ^{:line 649 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-space-id dump) ^{:line 650 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-tx-sequence row))
   occurrence ^{:line 652 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/occurrence-coordinate transaction ^{:line 652 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-ordinal row))
   proposition ^{:line 654 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 654 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-triple-handle row))]
  ^{:line 655 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (if ^{:line 655 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (= t/assert-action ^{:line 655 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-action row)) ^{:line 656 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/assertion-occurrence occurrence proposition) ^{:line 657 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/retraction-occurrence occurrence proposition))))

^{:line 659 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn close-fri! [image]
  nil)

^{:line 660 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn restore-store! [image target]
  ^{:line 661 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/load-term-store! target ^{:line 661 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-dump image)))

^{:line 662 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn ^String space-id [image]
  ^{:line 663 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-space-id ^{:line 663 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-source image)))

^{:line 664 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn ^String source-fingerprint [image]
  ^{:line 665 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-fingerprint ^{:line 665 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-source image)))

^{:line 666 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn source-position [image]
  ^{:line 667 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cachesource-valid-bytes ^{:line 667 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-source image)))

^{:line 668 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn transaction-count [image]
  ^{:line 669 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/transaction-count ^{:line 669 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-store image)))

^{:line 670 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn operation-count [image]
  ^{:line 671 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/operation-count ^{:line 671 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-store image)))

^{:line 672 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn semantic-history [image]
  ^{:line 673 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/semantic-history ^{:line 673 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-store image)))

^{:line 674 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn operation-occurrences [image]
  ^{:line 675 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/operation-occurrences ^{:line 675 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-store image)))

^{:line 676 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn live-occurrences [image]
  ^{:line 677 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/live-occurrences ^{:line 677 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-store image)))

^{:line 678 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn live-propositions [image]
  ^{:line 679 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (store/live-propositions ^{:line 679 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-store image)))

^{:line 680 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn by-slot0 [image term]
  ^{:line 680 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-matches image 0 ^{:line 680 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [term]))

^{:line 681 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn by-slot1 [image term]
  ^{:line 681 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-matches image 1 ^{:line 681 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [term]))

^{:line 682 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn by-slot2 [image term]
  ^{:line 682 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-matches image 2 ^{:line 682 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [term]))

^{:line 683 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn by-slot01 [image slot0 slot1]
  ^{:line 684 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-matches image 3 ^{:line 684 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [slot0 slot1]))

^{:line 685 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn by-slot12 [image slot1 slot2]
  ^{:line 686 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-matches image 4 ^{:line 686 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [slot1 slot2]))

^{:line 687 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn by-slot02 [image slot0 slot2]
  ^{:line 688 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (index-matches image 5 ^{:line 688 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} [slot0 slot2]))

^{:line 689 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn live-occurrences-as-of [image sequence]
  ^{:line 690 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [dump ^{:line 690 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-dump image)]
  ^{:line 691 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 691 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position] ^{:line 691 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (occurrence-event dump position)) ^{:line 692 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (live-positions-as-of image sequence))))

^{:line 693 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (defn live-propositions-as-of [image sequence]
  ^{:line 694 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (let [dump ^{:line 694 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (cacheimage-dump image)]
  ^{:line 695 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (mapv ^{:line 695 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (fn [position] ^{:line 696 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (resolve-handle dump ^{:line 698 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/operationrow-triple-handle ^{:line 699 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (nth ^{:line 699 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (t/termstoredump-operations dump) position)))) ^{:line 700 :file "/home/tom/code/fram/wt-triple-fri/src/fri_port.bclj"} (live-positions-as-of image sequence))))
