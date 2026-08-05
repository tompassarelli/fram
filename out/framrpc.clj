(ns framrpc
  (:require [fram.types :as t])
  (:import [java.io ByteArrayOutputStream]
           [java.io OutputStream]
           [java.nio ByteBuffer]
           [java.nio ByteOrder]
           [java.nio CharBuffer]
           [java.nio.charset CharacterCodingException]
           [java.nio.charset CodingErrorAction]
           [java.nio.charset StandardCharsets]))

^{:line 12 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def term-codec-v1-depth-limit 256)

^{:line 14 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-fail! [code ^String message]
  ^{:line 17 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (throw ^{:line 17 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (ex-info message ^{:line 17 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} {:type code :fram/code code})))

^{:line 19 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- require-codec-limits! [max-string-bytes max-nodes max-depth]
  ^{:line 23 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 23 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 23 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> max-string-bytes 0) ^{:line 24 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 24 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> max-nodes 0) ^{:line 24 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 24 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> max-depth 0) ^{:line 24 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= max-depth 256)))) nil ^{:line 26 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-limit "TermCodecV1 limits must be positive and depth at most 256")))

^{:line 29 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- utf8-length! [^String value maximum ^String label]
  ^{:line 33 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (loop [index 0
   total 0]
  ^{:line 34 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 34 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= index ^{:line 34 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (count value)) total ^{:line 36 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [unit ^{:line 36 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (int ^{:line 36 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.charAt value index))
   high? ^{:line 37 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 37 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= unit 55296) ^{:line 37 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= unit 56319))
   low? ^{:line 38 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 38 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= unit 56320) ^{:line 38 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= unit 57343))
   pair-unit ^{:line 40 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 40 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and high? ^{:line 40 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< ^{:line 40 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ index 1) ^{:line 40 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (count value))) ^{:line 41 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (int ^{:line 41 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.charAt value ^{:line 41 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ index 1))) -1)
   pair? ^{:line 42 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and high? ^{:line 42 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 42 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= pair-unit 56320) ^{:line 42 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= pair-unit 57343)))
   width ^{:line 44 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 45 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= unit 127) 1
  ^{:line 46 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= unit 2047) 2
  pair? 4
  ^{:line 48 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or high? low?) -1
  :else 3)]
  ^{:line 50 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 50 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= width -1) ^{:line 51 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 52 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " contains an unpaired UTF-16 surrogate")) ^{:line 53 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [next-total ^{:line 53 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ total width)]
  ^{:line 54 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 54 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> next-total maximum) ^{:line 55 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-string-limit ^{:line 56 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " exceeds the UTF-8 byte limit")) ^{:line 57 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (recur ^{:line 57 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ index ^{:line 57 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if pair? 2 1)) next-total))))))))

^{:line 59 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- strict-utf8-bytes! [^String value maximum ^String label]
  ^{:line 63 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [expected ^{:line 63 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (utf8-length! value maximum label)]
  ^{:line 64 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (try
  ^{:line 65 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [encoder ^{:line 65 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (doto ^{:line 65 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.newEncoder StandardCharsets/UTF_8)
  ^{:line 66 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.onMalformedInput CodingErrorAction/REPORT)
  ^{:line 67 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.onUnmappableCharacter CodingErrorAction/REPORT))
   buffer ^{:line 68 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.encode encoder ^{:line 68 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (CharBuffer/wrap value))
   bytes ^{:line 69 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (byte-array ^{:line 69 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.remaining buffer))]
  ^{:line 70 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.get buffer bytes)
  ^{:line 71 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 71 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= expected ^{:line 71 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (alength bytes)) bytes ^{:line 73 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 74 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " encoded to an unexpected byte length"))))
  (catch CharacterCodingException _
    ^{:line 76 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 77 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " is not valid UTF-8 text"))))))

^{:line 79 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^String strict-utf8-string! [bytes ^String label]
  ^{:line 82 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (try
  ^{:line 83 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [decoder ^{:line 83 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (doto ^{:line 83 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.newDecoder StandardCharsets/UTF_8)
  ^{:line 84 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.onMalformedInput CodingErrorAction/REPORT)
  ^{:line 85 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.onUnmappableCharacter CodingErrorAction/REPORT))]
  ^{:line 86 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str ^{:line 86 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.decode decoder ^{:line 86 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (ByteBuffer/wrap bytes))))
  (catch CharacterCodingException _
    ^{:line 88 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-utf8 ^{:line 89 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " is not valid UTF-8")))))

^{:line 91 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-write-u8! [out value]
  ^{:line 94 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.write out ^{:line 94 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (int ^{:line 94 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (bit-and 255 value)))
  nil)

^{:line 97 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-write-u32-le! [out value]
  ^{:line 100 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 100 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 100 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= value 0) ^{:line 100 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= value 4294967295)) ^{:line 101 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 102 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (loop [offset 0]
  ^{:line 103 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 103 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< offset 4) ^{:line 104 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 105 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out ^{:line 105 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (unsigned-bit-shift-right value ^{:line 105 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (* offset 8)))
  ^{:line 106 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (recur ^{:line 106 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ offset 1))) nil))
  nil) ^{:line 109 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-integer-range "u32 value is out of range")))

^{:line 111 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-write-i64-le! [out value]
  ^{:line 114 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (loop [offset 0]
  ^{:line 115 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 115 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< offset 8) ^{:line 116 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 117 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out ^{:line 117 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (unsigned-bit-shift-right value ^{:line 117 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (* offset 8)))
  ^{:line 118 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (recur ^{:line 118 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ offset 1))) nil)))

^{:line 121 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-node! [counter maximum]
  ^{:line 124 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [count-value ^{:line 124 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (swap! counter inc)]
  ^{:line 125 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 125 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> count-value maximum) ^{:line 126 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-node-limit "TermCodecV1 node count exceeds the configured bound") nil)))

^{:line 130 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- measure-term-core! [term depth max-string-bytes max-nodes max-depth counter]
  ^{:line 137 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 137 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> depth max-depth) ^{:line 138 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-depth-exceeded "recursive Term exceeds the TermCodecV1 depth bound") ^{:line 140 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 141 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-node! counter max-nodes)
  ^{:line 142 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 143 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple? term) ^{:line 144 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 1 ^{:line 145 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 145 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (measure-term-core! ^{:line 145 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot0 term) ^{:line 145 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 147 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 147 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (measure-term-core! ^{:line 147 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot1 term) ^{:line 147 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 149 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (measure-term-core! ^{:line 149 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot2 term) ^{:line 149 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter))))
  ^{:line 151 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (string? term) ^{:line 151 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 5 ^{:line 151 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (utf8-length! term max-string-bytes "String atom"))
  ^{:line 152 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (integer? term) 9
  ^{:line 153 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 153 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (number? term) ^{:line 153 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (not ^{:line 153 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (integer? term))) 9
  ^{:line 154 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (boolean? term) 1
  ^{:line 155 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (keyword? term) ^{:line 156 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [spelling ^{:line 156 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (subs ^{:line 156 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str term) 1)]
  ^{:line 157 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 157 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (empty? spelling) ^{:line 158 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-keyword "Keyword atom spelling must be nonempty") ^{:line 160 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 5 ^{:line 160 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (utf8-length! spelling max-string-bytes "Keyword atom"))))
  ^{:line 161 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/instant? term) 13
  :else ^{:line 163 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-unsupported-term "TermCodecV1 encountered a value outside Term")))))

^{:line 166 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn measure-term-codec-v1! [term max-string-bytes max-nodes max-depth]
  ^{:line 171 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-codec-limits! max-string-bytes max-nodes max-depth)
  ^{:line 172 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [counter ^{:line 172 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (atom 0)
   byte-count ^{:line 174 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (measure-term-core! term 0 max-string-bytes max-nodes max-depth counter)]
  ^{:line 175 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->TermCodecMeasure byte-count ^{:line 175 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (deref counter))))

^{:line 177 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- write-sized-text-core! [out ^String value max-string-bytes ^String label]
  ^{:line 182 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [bytes ^{:line 182 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (strict-utf8-bytes! value max-string-bytes label)]
  ^{:line 183 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u32-le! out ^{:line 183 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (alength bytes))
  ^{:line 184 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.write out bytes)
  nil))

^{:line 187 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- write-term-core! [out term max-string-bytes]
  ^{:line 191 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 192 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple? term) ^{:line 193 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 194 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 7)
  ^{:line 195 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-term-core! out ^{:line 195 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot0 term) max-string-bytes)
  ^{:line 196 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-term-core! out ^{:line 196 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot1 term) max-string-bytes)
  ^{:line 197 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-term-core! out ^{:line 197 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot2 term) max-string-bytes))
  ^{:line 198 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (string? term) ^{:line 199 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 199 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 1)
  ^{:line 200 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-sized-text-core! out term max-string-bytes "String atom"))
  ^{:line 201 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (integer? term) ^{:line 202 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 202 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 2)
  ^{:line 202 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-i64-le! out term))
  ^{:line 203 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 203 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (number? term) ^{:line 203 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (not ^{:line 203 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (integer? term))) ^{:line 204 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 204 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 3)
  ^{:line 205 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-i64-le! out ^{:line 205 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (Double/doubleToLongBits ^{:line 205 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (double term))))
  ^{:line 206 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (false? term) ^{:line 206 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 4)
  ^{:line 207 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (true? term) ^{:line 207 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 5)
  ^{:line 208 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (keyword? term) ^{:line 209 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 209 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 6)
  ^{:line 210 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-sized-text-core! out ^{:line 210 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (subs ^{:line 210 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str term) 1) max-string-bytes "Keyword atom"))
  ^{:line 212 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/instant? term) ^{:line 213 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 214 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 8)
  ^{:line 215 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-i64-le! out ^{:line 215 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/instant-epoch-seconds term))
  ^{:line 216 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u32-le! out ^{:line 216 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/instant-nanos term)))
  :else ^{:line 218 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-unsupported-term "TermCodecV1 encountered a value outside Term")))

^{:line 221 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn write-term-codec-v1! [out term max-string-bytes max-nodes max-depth]
  ^{:line 227 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [measure ^{:line 228 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (measure-term-codec-v1! term max-string-bytes max-nodes max-depth)]
  ^{:line 229 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-term-core! out term max-string-bytes)
  ^{:line 230 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/termcodecmeasure-nodes measure)))

^{:line 232 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-ensure! [buffer count-value ^String context]
  ^{:line 236 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 236 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< ^{:line 236 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.remaining buffer) count-value) ^{:line 237 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-truncated ^{:line 238 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str "TermCodecV1 ended inside " context)) nil))

^{:line 241 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-read-u8! [buffer ^String context]
  ^{:line 244 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-ensure! buffer 1 context)
  ^{:line 245 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [one ^{:line 245 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (byte-array 1)]
  ^{:line 246 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.get buffer one)
  ^{:line 247 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (bit-and 255 ^{:line 247 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (int ^{:line 247 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (aget one 0)))))

^{:line 249 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- codec-read-u32-le! [buffer ^String context]
  ^{:line 252 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-ensure! buffer 4 context)
  ^{:line 253 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (Integer/toUnsignedLong ^{:line 253 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getInt buffer)))

^{:line 255 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^String read-sized-text-core! [buffer max-string-bytes ^String context]
  ^{:line 259 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [length ^{:line 259 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-read-u32-le! buffer context)]
  ^{:line 260 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 260 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> length max-string-bytes) ^{:line 261 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-string-limit ^{:line 262 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str context " exceeds the UTF-8 byte limit")) ^{:line 263 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 264 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-ensure! buffer length context)
  ^{:line 265 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [bytes ^{:line 265 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (byte-array length)]
  ^{:line 266 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.get buffer bytes)
  ^{:line 267 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (strict-utf8-string! bytes context))))))

^{:line 269 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- decode-term-core! [buffer depth max-string-bytes max-nodes max-depth counter]
  ^{:line 276 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 276 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> depth max-depth) ^{:line 277 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-depth-exceeded "recursive Term exceeds the TermCodecV1 depth bound") ^{:line 279 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 280 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-node! counter max-nodes)
  ^{:line 281 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [tag ^{:line 281 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-read-u8! buffer "Term tag")]
  ^{:line 282 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 283 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 1) ^{:line 283 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-sized-text-core! buffer max-string-bytes "String atom")
  ^{:line 284 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 2) ^{:line 284 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 284 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-ensure! buffer 8 "Int atom")
  ^{:line 284 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getLong buffer))
  ^{:line 285 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 3) ^{:line 286 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 286 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-ensure! buffer 8 "Float atom")
  ^{:line 287 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (Double/longBitsToDouble ^{:line 287 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getLong buffer)))
  ^{:line 288 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 4) false
  ^{:line 289 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 5) true
  ^{:line 290 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 6) ^{:line 291 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [spelling ^{:line 292 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-sized-text-core! buffer max-string-bytes "Keyword atom")]
  ^{:line 293 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 293 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (empty? spelling) ^{:line 294 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-keyword "Keyword atom spelling must be nonempty") ^{:line 296 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (keyword spelling)))
  ^{:line 297 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 7) ^{:line 298 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple ^{:line 299 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (decode-term-core! buffer ^{:line 299 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 301 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (decode-term-core! buffer ^{:line 301 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter) ^{:line 303 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (decode-term-core! buffer ^{:line 303 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ depth 1) max-string-bytes max-nodes max-depth counter))
  ^{:line 305 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 8) ^{:line 306 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 307 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-ensure! buffer 12 "Instant atom")
  ^{:line 308 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [seconds ^{:line 308 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getLong buffer)
   nanos ^{:line 309 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-read-u32-le! buffer "Instant nanos")]
  ^{:line 310 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 310 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< nanos 1000000000) ^{:line 311 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/instant seconds nanos) ^{:line 312 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-invalid-instant "Instant nanoseconds are outside the canonical range"))))
  :else ^{:line 315 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-fail! :term-codec-bad-tag "TermCodecV1 contains an unknown tag"))))))

^{:line 318 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn decode-term-codec-v1! [buffer max-string-bytes max-nodes max-depth]
  ^{:line 323 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-codec-limits! max-string-bytes max-nodes max-depth)
  ^{:line 324 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.order buffer ByteOrder/LITTLE_ENDIAN)
  ^{:line 325 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [counter ^{:line 325 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (atom 0)
   value ^{:line 327 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (decode-term-core! buffer 0 max-string-bytes max-nodes max-depth counter)]
  ^{:line 328 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->TermCodecDecoded value ^{:line 328 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (deref counter))))

^{:line 334 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-major 1)

^{:line 335 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-minor 0)

^{:line 336 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-header-bytes 26)

^{:line 337 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-max-body-bytes 1048576)

^{:line 338 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-max-frame-bytes 1048602)

^{:line 339 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-max-string-bytes 1048576)

^{:line 340 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-max-space-bytes 4096)

^{:line 341 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-max-term-nodes 65536)

^{:line 342 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-max-term-depth 256)

^{:line 343 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-v1-magic ^{:line 344 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getBytes "FRAMRPC\u0000" StandardCharsets/UTF_8))

^{:line 346 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-fail! [code ^String message]
  ^{:line 349 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (throw ^{:line 349 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (ex-info message ^{:line 349 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} {:type code :fram/code code})))

^{:line 351 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^Boolean rpc-u32? [value]
  ^{:line 352 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 352 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= value 0) ^{:line 352 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= value 4294967295)))

^{:line 354 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^Boolean rpc-i64? [value]
  ^{:line 355 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 355 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= value -9223372036854775808) ^{:line 356 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= value 9223372036854775807)))

^{:line 358 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-kind-code! [kind]
  ^{:line 359 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 360 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :request) 1
  ^{:line 361 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :response) 2
  ^{:line 362 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :cancel) 3
  ^{:line 363 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :event) 4
  :else ^{:line 364 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown")))

^{:line 366 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-code-kind! [code]
  ^{:line 367 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 368 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= code 1) :request
  ^{:line 369 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= code 2) :response
  ^{:line 370 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= code 3) :cancel
  ^{:line 371 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= code 4) :event
  :else ^{:line 372 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown")))

^{:line 374 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- require-rpc-term! [value ^String label]
  ^{:line 377 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 377 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/term? value) nil ^{:line 379 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-term ^{:line 379 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " must be a Term"))))

^{:line 381 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- require-rpc-optional-term! [value ^String label]
  ^{:line 384 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 384 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or ^{:line 384 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? value) ^{:line 384 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/term? value)) nil ^{:line 386 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-term ^{:line 386 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " must be nil or a Term"))))

^{:line 388 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- require-rpc-string! [value ^String label]
  ^{:line 391 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 391 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (string? value) nil ^{:line 393 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 393 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " must be a String"))))

^{:line 395 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- require-rpc-keyword! [value ^String label]
  ^{:line 398 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 398 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (keyword? value) nil ^{:line 400 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 400 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " must be a Keyword"))))

^{:line 402 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- require-rpc-u32! [value ^String label]
  ^{:line 405 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 405 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-u32? value) nil ^{:line 407 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-integer-range ^{:line 407 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " is outside u32"))))

^{:line 409 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- require-rpc-i64! [value ^String label]
  ^{:line 412 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 412 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-i64? value) nil ^{:line 414 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-integer-range ^{:line 414 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str label " is outside i64"))))

^{:line 416 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-page-request! [limit cursor]
  ^{:line 419 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-u32! limit "page limit")
  ^{:line 420 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! cursor "page cursor")
  ^{:line 421 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcPageRequest limit cursor))

^{:line 423 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-page-response! [ordinal next-cursor ^Boolean done]
  ^{:line 427 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-u32! ordinal "page ordinal")
  ^{:line 428 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! next-cursor "next cursor")
  ^{:line 429 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcPageResponse ordinal next-cursor done))

^{:line 431 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-error! [code ^Boolean retryable ^String message detail]
  ^{:line 436 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-keyword! code "error code")
  ^{:line 437 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-string! message "error message")
  ^{:line 438 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! detail "error detail")
  ^{:line 439 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcError code retryable message detail))

^{:line 441 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-request! [^String space op expected-version page timeout-ms payload]
  ^{:line 448 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-string! space "request space")
  ^{:line 449 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-keyword! op "request op")
  ^{:line 450 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if expected-version ^{:line 451 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-i64! expected-version "expected version") nil)
  ^{:line 452 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if timeout-ms ^{:line 453 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-u32! timeout-ms "timeout-ms") nil)
  ^{:line 454 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! payload "request payload")
  ^{:line 455 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcRequest space op expected-version page timeout-ms payload))

^{:line 457 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-response! [^String space op served-version page error payload]
  ^{:line 464 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-string! space "response space")
  ^{:line 465 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-keyword! op "response op")
  ^{:line 466 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-i64! served-version "served version")
  ^{:line 467 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! payload "response payload")
  ^{:line 468 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcResponse space op served-version page error payload))

^{:line 470 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-request-frame [request-id request]
  ^{:line 473 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcFrameV1 :request 0 request-id request nil))

^{:line 475 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-response-frame [request-id response]
  ^{:line 478 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcFrameV1 :response 0 request-id nil response))

^{:line 480 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-cancel-frame [request-id]
  ^{:line 481 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcFrameV1 :cancel 0 request-id nil nil))

^{:line 483 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-event-frame [request-id event]
  ^{:line 486 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/->RpcFrameV1 :event 0 request-id nil event))

^{:line 488 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- validate-rpc-page-request! [page]
  ^{:line 489 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-u32! ^{:line 489 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcpagerequest-limit page) "page limit")
  ^{:line 490 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! ^{:line 490 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-page-request-cursor-value page) "page cursor"))

^{:line 493 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- validate-rpc-page-response! [page]
  ^{:line 494 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-u32! ^{:line 494 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcpageresponse-ordinal page) "page ordinal")
  ^{:line 495 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! ^{:line 495 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-page-response-cursor-value page) "next cursor"))

^{:line 498 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- validate-rpc-error! [error]
  ^{:line 499 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-keyword! ^{:line 499 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcerror-code error) "error code")
  ^{:line 500 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-string! ^{:line 500 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcerror-message error) "error message")
  ^{:line 501 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! ^{:line 501 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-error-detail-value error) "error detail"))

^{:line 503 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- validate-rpc-request! [request]
  ^{:line 504 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-string! ^{:line 504 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-space request) "request space")
  ^{:line 505 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (utf8-length! ^{:line 505 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-space request) rpc-v1-max-space-bytes "SpaceId")
  ^{:line 506 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-keyword! ^{:line 506 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-op request) "request op")
  ^{:line 507 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [expected ^{:line 507 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-expected-version request)
   page ^{:line 508 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-page request)
   timeout ^{:line 509 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-timeout-ms request)]
  ^{:line 510 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if expected ^{:line 510 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-i64! expected "expected version") nil)
  ^{:line 511 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page ^{:line 511 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (validate-rpc-page-request! page) nil)
  ^{:line 512 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if timeout ^{:line 512 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-u32! timeout "timeout-ms") nil)
  ^{:line 513 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! ^{:line 513 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-request-payload-value request) "request payload")))

^{:line 515 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- validate-rpc-response! [response]
  ^{:line 516 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-string! ^{:line 516 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-space response) "response space")
  ^{:line 517 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (utf8-length! ^{:line 517 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-space response) rpc-v1-max-space-bytes "SpaceId")
  ^{:line 518 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-keyword! ^{:line 518 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-op response) "response op")
  ^{:line 519 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-i64! ^{:line 519 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-served-version response) "served version")
  ^{:line 520 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [page ^{:line 520 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-page response)
   error ^{:line 521 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-error response)]
  ^{:line 522 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page ^{:line 522 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (validate-rpc-page-response! page) nil)
  ^{:line 523 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if error ^{:line 523 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (validate-rpc-error! error) nil)
  ^{:line 524 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-optional-term! ^{:line 524 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-response-payload-value response) "response payload")))

^{:line 527 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- validate-rpc-frame! [frame]
  ^{:line 528 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 528 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= 0 ^{:line 528 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-flags frame)) nil ^{:line 530 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-flags "FRAMRPC v1 flags must be zero"))
  ^{:line 531 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-i64! ^{:line 531 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-request-id frame) "request id")
  ^{:line 532 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [kind ^{:line 532 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-kind frame)
   request ^{:line 533 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-request frame)
   response ^{:line 534 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-response frame)]
  ^{:line 535 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 536 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :request) ^{:line 537 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 537 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and request ^{:line 537 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? response)) ^{:line 538 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (validate-rpc-request! request) ^{:line 539 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-shape "request frame must carry exactly one RpcRequest"))
  ^{:line 541 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or ^{:line 541 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :response) ^{:line 541 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :event)) ^{:line 542 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 542 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and response ^{:line 542 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? request)) ^{:line 543 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (validate-rpc-response! response) ^{:line 544 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-shape "response/event frame must carry exactly one RpcResponse"))
  ^{:line 546 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :cancel) ^{:line 547 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 547 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 547 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? request) ^{:line 547 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? response)) nil ^{:line 549 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-shape "cancel frame body must be empty"))
  :else ^{:line 550 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-kind "FRAMRPC frame kind is unknown"))))

^{:line 552 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-add-measured-term! [term nodes]
  ^{:line 555 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [remaining ^{:line 555 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (- rpc-v1-max-term-nodes ^{:line 555 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (deref nodes))]
  ^{:line 556 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 556 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= remaining 0) ^{:line 557 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") ^{:line 559 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [measure ^{:line 560 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (measure-term-codec-v1! term rpc-v1-max-string-bytes remaining rpc-v1-max-term-depth)]
  ^{:line 562 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (swap! nodes + ^{:line 562 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/termcodecmeasure-nodes measure))
  ^{:line 563 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/termcodecmeasure-bytes measure)))))

^{:line 565 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-page-request-bytes! [page nodes]
  ^{:line 568 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [cursor ^{:line 568 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-page-request-cursor-value page)]
  ^{:line 569 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 5 ^{:line 569 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 569 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? cursor) 0 ^{:line 569 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! cursor nodes)))))

^{:line 571 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-page-response-bytes! [page nodes]
  ^{:line 574 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [cursor ^{:line 574 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-page-response-cursor-value page)]
  ^{:line 575 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 6 ^{:line 575 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 575 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? cursor) 0 ^{:line 575 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! cursor nodes)))))

^{:line 577 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-error-bytes! [error nodes]
  ^{:line 580 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [detail ^{:line 580 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-error-detail-value error)]
  ^{:line 581 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 2 ^{:line 582 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 582 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! ^{:line 582 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcerror-code error) nodes) ^{:line 583 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 583 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! ^{:line 583 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcerror-message error) nodes) ^{:line 584 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 584 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? detail) 0 ^{:line 584 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! detail nodes)))))))

^{:line 586 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-request-body-bytes! [request nodes]
  ^{:line 589 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [expected ^{:line 589 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-expected-version request)
   page ^{:line 590 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-page request)
   timeout ^{:line 591 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-timeout-ms request)]
  ^{:line 592 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 3 ^{:line 593 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 593 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! ^{:line 593 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-space request) nodes) ^{:line 594 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 594 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! ^{:line 594 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-op request) nodes) ^{:line 595 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 595 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if expected 8 0) ^{:line 596 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 596 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page ^{:line 596 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-page-request-bytes! page nodes) 0) ^{:line 597 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 597 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if timeout 4 0) ^{:line 598 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! ^{:line 598 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-request-payload-value request) nodes)))))))))

^{:line 601 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-response-body-bytes! [response nodes]
  ^{:line 604 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [page ^{:line 604 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-page response)
   error ^{:line 605 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-error response)
   payload ^{:line 606 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-response-payload-value response)]
  ^{:line 607 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ 11 ^{:line 608 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 608 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! ^{:line 608 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-space response) nodes) ^{:line 609 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 609 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! ^{:line 609 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-op response) nodes) ^{:line 610 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 610 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page ^{:line 610 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-page-response-bytes! page nodes) 0) ^{:line 611 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ ^{:line 611 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if error ^{:line 611 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-error-bytes! error nodes) 0) ^{:line 612 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 612 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? payload) 0 ^{:line 613 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-add-measured-term! payload nodes)))))))))

^{:line 615 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-body-bytes! [frame]
  ^{:line 616 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (validate-rpc-frame! frame)
  ^{:line 617 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [nodes ^{:line 617 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (atom 0)
   kind ^{:line 618 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-kind frame)
   request ^{:line 619 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-request frame)
   response ^{:line 620 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-response frame)
   size ^{:line 622 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 623 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 623 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :request) request) ^{:line 624 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-request-body-bytes! request nodes)
  ^{:line 625 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 625 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or ^{:line 625 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :response) ^{:line 625 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :event)) response) ^{:line 626 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-response-body-bytes! response nodes)
  :else 0)]
  ^{:line 628 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 628 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> size rpc-v1-max-body-bytes) ^{:line 629 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-frame-too-large "FRAMRPC body exceeds the configured byte limit") size)))

^{:line 633 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-write-u16-le! [out value]
  ^{:line 636 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out value)
  ^{:line 637 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out ^{:line 637 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (unsigned-bit-shift-right value 8)))

^{:line 639 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-write-present! [out value]
  ^{:line 642 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out ^{:line 642 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 642 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? value) 0 1)))

^{:line 644 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-write-term! [out value]
  ^{:line 647 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-term-codec-v1! out value rpc-v1-max-string-bytes rpc-v1-max-term-nodes rpc-v1-max-term-depth)
  nil)

^{:line 651 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- write-rpc-page-request! [out page]
  ^{:line 654 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u32-le! out ^{:line 654 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcpagerequest-limit page))
  ^{:line 655 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [cursor ^{:line 655 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-page-request-cursor-value page)]
  ^{:line 656 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out cursor)
  ^{:line 657 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 657 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? cursor) nil ^{:line 657 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out cursor))))

^{:line 659 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- write-rpc-page-response! [out page]
  ^{:line 662 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u32-le! out ^{:line 662 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcpageresponse-ordinal page))
  ^{:line 663 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [cursor ^{:line 663 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-page-response-cursor-value page)]
  ^{:line 664 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out cursor)
  ^{:line 665 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 665 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? cursor) nil ^{:line 665 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out cursor)))
  ^{:line 666 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out ^{:line 666 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 666 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcpageresponse-done page) 1 0)))

^{:line 668 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- write-rpc-error! [out error]
  ^{:line 671 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out ^{:line 671 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcerror-code error))
  ^{:line 672 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out ^{:line 672 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 672 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcerror-retryable error) 1 0))
  ^{:line 673 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out ^{:line 673 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcerror-message error))
  ^{:line 674 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [detail ^{:line 674 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-error-detail-value error)]
  ^{:line 675 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out detail)
  ^{:line 676 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 676 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? detail) nil ^{:line 676 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out detail))))

^{:line 678 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- write-rpc-request! [out request]
  ^{:line 681 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out ^{:line 681 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-space request))
  ^{:line 682 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out ^{:line 682 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-op request))
  ^{:line 683 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [expected ^{:line 683 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-expected-version request)
   page ^{:line 684 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-page request)
   timeout ^{:line 685 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcrequest-timeout-ms request)]
  ^{:line 686 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out expected)
  ^{:line 687 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if expected ^{:line 687 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-i64-le! out expected) nil)
  ^{:line 688 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out page)
  ^{:line 689 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page ^{:line 689 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-rpc-page-request! out page) nil)
  ^{:line 690 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out timeout)
  ^{:line 691 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if timeout ^{:line 691 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u32-le! out timeout) nil)
  ^{:line 692 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out ^{:line 692 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-request-payload-value request))))

^{:line 694 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- write-rpc-response! [out response]
  ^{:line 697 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out ^{:line 697 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-space response))
  ^{:line 698 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out ^{:line 698 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-op response))
  ^{:line 699 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-i64-le! out ^{:line 699 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-served-version response))
  ^{:line 700 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [page ^{:line 700 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-page response)
   error ^{:line 701 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcresponse-error response)
   payload ^{:line 702 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpc-response-payload-value response)]
  ^{:line 703 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out page)
  ^{:line 704 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page ^{:line 704 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-rpc-page-response! out page) nil)
  ^{:line 705 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out error)
  ^{:line 706 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if error ^{:line 706 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-rpc-error! out error) nil)
  ^{:line 707 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-present! out payload)
  ^{:line 708 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 708 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? payload) nil ^{:line 708 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-term! out payload))))

^{:line 710 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn encode-rpc-frame-v1! [frame]
  ^{:line 711 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [body-size ^{:line 711 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-body-bytes! frame)
   body ^{:line 712 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (ByteArrayOutputStream. body-size)
   kind ^{:line 713 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-kind frame)
   request ^{:line 714 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-request frame)
   response ^{:line 715 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-response frame)]
  ^{:line 716 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 717 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 717 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :request) request) ^{:line 718 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-rpc-request! body request)
  ^{:line 719 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 719 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or ^{:line 719 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :response) ^{:line 719 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :event)) response) ^{:line 720 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (write-rpc-response! body response)
  :else nil)
  ^{:line 722 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 722 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= body-size ^{:line 722 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.size body)) ^{:line 723 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [out ^{:line 723 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (ByteArrayOutputStream. ^{:line 723 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ rpc-v1-header-bytes body-size))]
  ^{:line 724 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.write out rpc-v1-magic)
  ^{:line 725 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-u16-le! out rpc-v1-major)
  ^{:line 726 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-write-u16-le! out rpc-v1-minor)
  ^{:line 727 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out ^{:line 727 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-kind-code! kind))
  ^{:line 728 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u8! out 0)
  ^{:line 729 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-u32-le! out body-size)
  ^{:line 730 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (codec-write-i64-le! out ^{:line 730 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/rpcframev1-request-id frame))
  ^{:line 731 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.write out ^{:line 731 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.toByteArray body))
  ^{:line 732 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.toByteArray out)) ^{:line 733 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-size-mismatch "FRAMRPC preflight size disagrees with encoded body"))))

^{:line 736 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-ensure! [buffer count-value ^String context]
  ^{:line 740 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 740 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< ^{:line 740 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.remaining buffer) count-value) ^{:line 741 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-truncated ^{:line 741 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str "FRAMRPC ended inside " context)) nil))

^{:line 744 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-read-u8! [buffer ^String context]
  ^{:line 747 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-ensure! buffer 1 context)
  ^{:line 748 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [one ^{:line 748 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (byte-array 1)]
  ^{:line 749 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.get buffer one)
  ^{:line 750 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (bit-and 255 ^{:line 750 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (int ^{:line 750 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (aget one 0)))))

^{:line 752 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-read-u16-le! [buffer ^String context]
  ^{:line 755 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-ensure! buffer 2 context)
  ^{:line 756 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (bit-and 65535 ^{:line 756 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (int ^{:line 756 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getShort buffer))))

^{:line 758 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-read-u32-le! [buffer ^String context]
  ^{:line 761 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-ensure! buffer 4 context)
  ^{:line 762 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (Integer/toUnsignedLong ^{:line 762 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getInt buffer)))

^{:line 764 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-read-i64-le! [buffer ^String context]
  ^{:line 767 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-ensure! buffer 8 context)
  ^{:line 768 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.getLong buffer))

^{:line 770 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^Boolean rpc-read-presence! [buffer ^String context]
  ^{:line 773 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [value ^{:line 773 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u8! buffer context)]
  ^{:line 774 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 775 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= value 0) false
  ^{:line 776 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= value 1) true
  :else ^{:line 778 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-presence ^{:line 779 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str context " must be the strict byte 0 or 1")))))

^{:line 781 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^Boolean rpc-read-bool! [buffer ^String context]
  ^{:line 784 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [value ^{:line 784 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u8! buffer context)]
  ^{:line 785 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 786 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= value 0) false
  ^{:line 787 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= value 1) true
  :else ^{:line 789 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-boolean ^{:line 790 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str context " must be the strict byte 0 or 1")))))

^{:line 792 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-read-term! [buffer nodes]
  ^{:line 795 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [remaining ^{:line 795 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (- rpc-v1-max-term-nodes ^{:line 795 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (deref nodes))]
  ^{:line 796 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 796 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (<= remaining 0) ^{:line 797 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") ^{:line 799 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [decoded ^{:line 800 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (decode-term-codec-v1! buffer rpc-v1-max-string-bytes remaining rpc-v1-max-term-depth)]
  ^{:line 802 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (swap! nodes + ^{:line 802 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/termcodecdecoded-nodes decoded))
  ^{:line 803 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/termcodecdecoded-value decoded)))))

^{:line 805 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^String rpc-read-space-term! [buffer nodes]
  ^{:line 808 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 808 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= ^{:line 808 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (deref nodes) rpc-v1-max-term-nodes) ^{:line 809 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-term-node-limit "FRAMRPC body exceeds the aggregate Term node limit") ^{:line 811 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (do
  ^{:line 812 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (swap! nodes inc)
  ^{:line 813 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [tag ^{:line 813 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u8! buffer "SpaceId Term tag")]
  ^{:line 814 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 814 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag 1) ^{:line 815 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-sized-text-core! buffer rpc-v1-max-space-bytes "SpaceId") ^{:line 816 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-field "FRAMRPC SpaceId must be a String Term"))))))

^{:line 818 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- rpc-read-keyword-term! [buffer nodes ^String context]
  ^{:line 822 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [value ^{:line 822 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-term! buffer nodes)]
  ^{:line 823 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 823 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (keyword? value) value ^{:line 825 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 825 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str context " must be a Keyword Term")))))

^{:line 827 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^String rpc-read-string-term! [buffer nodes ^String context]
  ^{:line 831 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [value ^{:line 831 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-term! buffer nodes)]
  ^{:line 832 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 832 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (string? value) value ^{:line 834 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-field ^{:line 834 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (str context " must be a String Term")))))

^{:line 836 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- read-rpc-page-request! [buffer nodes]
  ^{:line 839 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [limit ^{:line 839 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u32-le! buffer "page limit")
   cursor? ^{:line 840 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "page cursor presence")
   cursor ^{:line 841 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if cursor? ^{:line 841 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-term! buffer nodes) nil)]
  ^{:line 842 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-page-request! limit cursor)))

^{:line 844 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- read-rpc-page-response! [buffer nodes]
  ^{:line 847 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [ordinal ^{:line 847 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u32-le! buffer "page ordinal")
   cursor? ^{:line 848 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "next cursor presence")
   cursor ^{:line 849 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if cursor? ^{:line 849 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-term! buffer nodes) nil)
   done ^{:line 850 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-bool! buffer "page done")]
  ^{:line 851 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-page-response! ordinal cursor done)))

^{:line 853 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- read-rpc-error! [buffer nodes]
  ^{:line 856 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [code ^{:line 856 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-keyword-term! buffer nodes "error code")
   retryable ^{:line 857 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-bool! buffer "error retryable")
   message ^{:line 858 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-string-term! buffer nodes "error message")
   detail? ^{:line 859 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "error detail presence")
   detail ^{:line 860 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if detail? ^{:line 860 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-term! buffer nodes) nil)]
  ^{:line 861 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-error! code retryable message detail)))

^{:line 863 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- read-rpc-request! [buffer nodes]
  ^{:line 866 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [space ^{:line 866 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-space-term! buffer nodes)
   op ^{:line 867 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-keyword-term! buffer nodes "request op")
   expected? ^{:line 868 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "expected-version presence")
   expected ^{:line 869 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if expected? ^{:line 870 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-i64-le! buffer "expected-version") nil)
   page? ^{:line 871 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "request page presence")
   page ^{:line 872 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page? ^{:line 872 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-rpc-page-request! buffer nodes) nil)
   timeout? ^{:line 873 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "timeout-ms presence")
   timeout ^{:line 874 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if timeout? ^{:line 874 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u32-le! buffer "timeout-ms") nil)
   payload ^{:line 875 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-term! buffer nodes)]
  ^{:line 876 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-request! space op expected page timeout payload)))

^{:line 878 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- read-rpc-response! [buffer nodes]
  ^{:line 881 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [space ^{:line 881 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-space-term! buffer nodes)
   op ^{:line 882 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-keyword-term! buffer nodes "response op")
   served ^{:line 883 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-i64-le! buffer "served-version")
   page? ^{:line 884 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "response page presence")
   page ^{:line 885 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if page? ^{:line 885 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-rpc-page-response! buffer nodes) nil)
   error? ^{:line 886 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "response error presence")
   error ^{:line 887 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if error? ^{:line 887 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-rpc-error! buffer nodes) nil)
   payload? ^{:line 888 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-presence! buffer "response payload presence")
   payload ^{:line 889 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if payload? ^{:line 889 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-term! buffer nodes) nil)]
  ^{:line 890 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-response! space op served page error payload)))

^{:line 892 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn- ^Boolean rpc-magic-valid! [buffer]
  ^{:line 893 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (loop [index 0
   valid true]
  ^{:line 894 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 894 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< index 8) ^{:line 895 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [actual ^{:line 895 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u8! buffer "magic")
   expected ^{:line 896 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (bit-and 255 ^{:line 896 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (int ^{:line 896 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (aget rpc-v1-magic index)))]
  ^{:line 897 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (recur ^{:line 897 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ index 1) ^{:line 897 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and valid ^{:line 897 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= actual expected)))) valid)))

^{:line 900 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn decode-rpc-frame-v1! [bytes]
  ^{:line 901 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [byte-count ^{:line 901 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (alength bytes)]
  ^{:line 902 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 902 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> byte-count rpc-v1-max-frame-bytes) ^{:line 903 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-frame-too-large "FRAMRPC frame exceeds the configured byte limit") nil)
  ^{:line 906 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 906 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< byte-count rpc-v1-header-bytes) ^{:line 907 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-truncated "FRAMRPC frame ended inside its header") nil)
  ^{:line 909 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [buffer ^{:line 909 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (doto ^{:line 909 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (ByteBuffer/wrap bytes)
  ^{:line 910 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.order ByteOrder/LITTLE_ENDIAN))]
  ^{:line 911 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 911 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-magic-valid! buffer) nil ^{:line 913 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-magic "FRAMRPC magic does not match"))
  ^{:line 914 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [major ^{:line 914 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u16-le! buffer "major version")
   minor ^{:line 915 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u16-le! buffer "minor version")
   kind ^{:line 916 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-code-kind! ^{:line 916 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u8! buffer "frame kind"))
   flags ^{:line 917 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u8! buffer "frame flags")
   body-length ^{:line 918 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-u32-le! buffer "body length")
   request-id ^{:line 919 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-read-i64-le! buffer "request id")]
  ^{:line 920 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 920 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 920 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= major rpc-v1-major) ^{:line 920 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= minor rpc-v1-minor)) nil ^{:line 922 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-unsupported-version "FRAMRPC major/minor version is unsupported"))
  ^{:line 924 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 924 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= flags 0) nil ^{:line 926 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-flags "FRAMRPC v1 flags must be zero"))
  ^{:line 927 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 927 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> body-length rpc-v1-max-body-bytes) ^{:line 928 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-frame-too-large "FRAMRPC declared body exceeds the configured byte limit") nil)
  ^{:line 931 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 931 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (< ^{:line 931 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.remaining buffer) body-length) ^{:line 932 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-truncated "FRAMRPC body is shorter than declared") nil)
  ^{:line 934 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 934 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (> ^{:line 934 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.remaining buffer) body-length) ^{:line 935 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-trailing-bytes "FRAMRPC frame has bytes beyond its declared body") nil)
  ^{:line 938 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [nodes ^{:line 938 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (atom 0)
   frame ^{:line 940 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 941 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :request) ^{:line 942 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-request-frame request-id ^{:line 942 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-rpc-request! buffer nodes))
  ^{:line 943 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :response) ^{:line 944 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-response-frame request-id ^{:line 944 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-rpc-response! buffer nodes))
  ^{:line 945 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= kind :event) ^{:line 946 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-event-frame request-id ^{:line 946 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (read-rpc-response! buffer nodes))
  :else ^{:line 948 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 948 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= body-length 0) ^{:line 949 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-cancel-frame request-id) ^{:line 950 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-shape "FRAMRPC cancel body must be exactly empty")))]
  ^{:line 952 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 952 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (zero? ^{:line 952 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (.remaining buffer)) frame ^{:line 954 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-trailing-bytes "FRAMRPC body decoder left trailing bytes")))))))

^{:line 961 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-unit :rpc/unit)

^{:line 962 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-list-end :rpc/list-end)

^{:line 963 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-none :rpc/none)

^{:line 964 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-subject-any :rpc/subject-any)

^{:line 965 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def rpc-subject-existing :rpc/subject-existing)

^{:line 966 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (def query-current :query/current)

^{:line 968 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-list! [values]
  ^{:line 969 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (reduce ^{:line 969 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (fn [tail value] ^{:line 972 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! value "RPC list value")
  ^{:line 973 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple :rpc/list value tail)) rpc-list-end ^{:line 974 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (reverse values)))

^{:line 976 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-list-values! [value]
  ^{:line 977 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (loop [cursor value
   result ^{:line 977 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} []
   count-value 0]
  ^{:line 978 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 979 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= cursor rpc-list-end) result
  ^{:line 980 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (>= count-value rpc-v1-max-term-nodes) ^{:line 981 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-list "RPC list exceeds the Term node bound")
  ^{:line 982 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 982 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple? cursor) ^{:line 982 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= :rpc/list ^{:line 982 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot0 cursor))) ^{:line 983 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [head ^{:line 983 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot1 cursor)
   tail ^{:line 984 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot2 cursor)]
  ^{:line 985 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! head "RPC list head")
  ^{:line 986 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! tail "RPC list tail")
  ^{:line 987 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (recur tail ^{:line 987 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (conj result head) ^{:line 987 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (+ count-value 1)))
  :else ^{:line 988 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-list "RPC list must end with :rpc/list-end"))))

^{:line 991 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-some! [value]
  ^{:line 992 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! value "RPC option value")
  ^{:line 993 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple :rpc/some value :rpc/option))

^{:line 995 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-option! [value]
  ^{:line 996 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 996 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (nil? value) rpc-none ^{:line 996 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-some! value)))

^{:line 998 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn ^Boolean rpc-option-present?! [value]
  ^{:line 999 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (cond
  ^{:line 1000 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= value rpc-none) false
  ^{:line 1001 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 1001 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple? value) ^{:line 1002 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 1002 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= :rpc/some ^{:line 1002 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot0 value)) ^{:line 1003 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= :rpc/option ^{:line 1003 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot2 value)))) true
  :else ^{:line 1004 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-option "RPC option must be :rpc/none or (:rpc/some value :rpc/option)")))

^{:line 1007 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-option-value! [value]
  ^{:line 1008 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 1008 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option-present?! value) ^{:line 1008 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot1 value) nil))

^{:line 1010 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-record! [tag fields]
  ^{:line 1013 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple tag ^{:line 1013 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! fields) :rpc/record))

^{:line 1015 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-record-fields! [value tag field-count]
  ^{:line 1019 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 1019 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 1019 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple? value) ^{:line 1020 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (and ^{:line 1020 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= tag ^{:line 1020 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot0 value)) ^{:line 1021 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= :rpc/record ^{:line 1021 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot2 value)))) ^{:line 1022 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (let [fields ^{:line 1022 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list-values! ^{:line 1022 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (t/triple-slot1 value))]
  ^{:line 1023 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 1023 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= field-count ^{:line 1023 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (count fields)) fields ^{:line 1025 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-record "RPC record contains the wrong number of fields"))) ^{:line 1027 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-record "RPC record tag or marker is invalid")))

^{:line 1029 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-fence! [resource holder epoch]
  ^{:line 1033 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! resource "lease resource")
  ^{:line 1034 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (require-rpc-term! holder "lease holder")
  ^{:line 1035 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/fence ^{:line 1035 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [resource holder epoch]))

^{:line 1037 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-action! [operation proposition policy]
  ^{:line 1041 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 1041 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or ^{:line 1041 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= operation :rpc/assert) ^{:line 1041 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= operation :rpc/retract)) nil ^{:line 1043 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-action "RPC action operation is invalid"))
  ^{:line 1044 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 1044 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or ^{:line 1044 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= policy rpc-subject-any) ^{:line 1044 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= policy rpc-subject-existing)) nil ^{:line 1046 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-policy "RPC subject policy is invalid"))
  ^{:line 1047 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/action ^{:line 1047 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [operation proposition policy]))

^{:line 1049 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-action-result! [input-index ^Boolean changed occurrences]
  ^{:line 1053 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/action-result ^{:line 1054 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [input-index changed ^{:line 1054 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! occurrences)]))

^{:line 1056 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-mutation-result! [results]
  ^{:line 1057 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/mutation-result ^{:line 1057 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1057 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! results)]))

^{:line 1059 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-write! [proposition policy fence]
  ^{:line 1063 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (if ^{:line 1063 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (or ^{:line 1063 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= policy rpc-subject-any) ^{:line 1063 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (= policy rpc-subject-existing)) nil ^{:line 1065 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-fail! :rpc-invalid-policy "RPC subject policy is invalid"))
  ^{:line 1066 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/write ^{:line 1066 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [proposition policy ^{:line 1066 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option! fence)]))

^{:line 1068 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-batch! [actions fence]
  ^{:line 1071 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/batch ^{:line 1071 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1071 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! actions) ^{:line 1071 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option! fence)]))

^{:line 1073 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-triple-pattern! [slot0 slot1 slot2]
  ^{:line 1077 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/triple-pattern ^{:line 1078 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1078 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option! slot0) ^{:line 1078 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option! slot1) ^{:line 1078 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option! slot2)]))

^{:line 1080 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-status! [state live-count engine cache]
  ^{:line 1085 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/status ^{:line 1085 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [state live-count engine cache]))

^{:line 1087 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-triples! [values]
  ^{:line 1088 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/triples ^{:line 1088 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1088 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! values)]))

^{:line 1090 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-occurrences! [values]
  ^{:line 1091 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/occurrences ^{:line 1091 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1091 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! values)]))

^{:line 1093 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-lease-acquire! [resource holder ttl-ms]
  ^{:line 1097 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :lease/acquire ^{:line 1097 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [resource holder ttl-ms]))

^{:line 1099 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-lease-renew! [fence ttl-ms]
  ^{:line 1102 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :lease/renew ^{:line 1102 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [fence ttl-ms]))

^{:line 1104 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-lease-grant! [fence expires]
  ^{:line 1107 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :lease/grant ^{:line 1107 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [fence expires]))

^{:line 1109 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-lease-released! [^Boolean released]
  ^{:line 1110 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :lease/released ^{:line 1110 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [released]))

^{:line 1112 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-lease-check! [^Boolean valid expires]
  ^{:line 1115 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :lease/check ^{:line 1115 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [valid ^{:line 1115 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option! expires)]))

^{:line 1117 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-violation! [code detail]
  ^{:line 1120 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/violation ^{:line 1120 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [code detail]))

^{:line 1122 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-validation! [^Boolean valid violations]
  ^{:line 1125 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :rpc/validation ^{:line 1125 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [valid ^{:line 1125 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! violations)]))

^{:line 1127 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-variable! [^String name]
  ^{:line 1128 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/var ^{:line 1128 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [name]))

^{:line 1130 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-constant! [value]
  ^{:line 1131 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/const ^{:line 1131 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [value]))

^{:line 1133 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-head! [^String relation terms]
  ^{:line 1136 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/head ^{:line 1136 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [relation ^{:line 1136 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! terms)]))

^{:line 1138 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-relation! [^String relation terms ^Boolean negated]
  ^{:line 1142 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/relation ^{:line 1142 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [relation ^{:line 1142 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! terms) negated]))

^{:line 1144 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-predicate! [operation left right]
  ^{:line 1148 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/predicate ^{:line 1148 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [operation left right]))

^{:line 1150 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-function! [operation terms ^String bind-variable]
  ^{:line 1154 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/function ^{:line 1155 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [operation ^{:line 1155 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! terms) bind-variable]))

^{:line 1157 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-rule! [head clauses]
  ^{:line 1160 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/rule ^{:line 1160 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [head ^{:line 1160 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! clauses)]))

^{:line 1162 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-stratum! [rules]
  ^{:line 1163 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/stratum ^{:line 1163 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1163 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! rules)]))

^{:line 1165 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-find-relation! [^String relation]
  ^{:line 1166 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/find-relation ^{:line 1166 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [relation]))

^{:line 1168 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-aggregate! [operation argument-index]
  ^{:line 1171 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/aggregate ^{:line 1171 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [operation ^{:line 1171 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-option! argument-index)]))

^{:line 1173 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-having! [comparison aggregate-index value]
  ^{:line 1177 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/having ^{:line 1177 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [comparison aggregate-index value]))

^{:line 1179 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-find-aggregate! [^String relation grouping aggregates having]
  ^{:line 1184 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/find-aggregate ^{:line 1185 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [relation ^{:line 1185 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! grouping) ^{:line 1185 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! aggregates) ^{:line 1186 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! having)]))

^{:line 1188 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-plan! [find strata]
  ^{:line 1191 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/plan ^{:line 1191 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [find ^{:line 1191 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! strata)]))

^{:line 1193 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-as-of! [version]
  ^{:line 1194 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/as-of ^{:line 1194 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [version]))

^{:line 1196 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-since! [lower-exclusive upper]
  ^{:line 1199 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/since ^{:line 1199 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [lower-exclusive upper]))

^{:line 1201 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-request! [plan snapshot]
  ^{:line 1204 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/request ^{:line 1204 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [plan snapshot]))

^{:line 1206 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-row! [values]
  ^{:line 1207 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/row ^{:line 1207 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1207 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! values)]))

^{:line 1209 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-rows! [rows]
  ^{:line 1210 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/rows ^{:line 1210 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [^{:line 1210 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-list! rows)]))

^{:line 1212 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (defn rpc-query-cursor! [snapshot-version ^String query-sha256 next-page-ordinal after-row]
  ^{:line 1217 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} (rpc-record! :query/cursor ^{:line 1218 :file "/home/tom/code/fram/wt-legacy-surface-purge/src/framrpc.bclj"} [snapshot-version query-sha256 next-page-ordinal after-row]))
