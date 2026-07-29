(ns fram.rt-core
  (:require [clojure.string :as str]))

^{:line 18 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def COMMA-RE ^{:line 18 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern ","))

^{:line 20 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def SPLIT-KV-RE ^{:line 20 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "^(\\S+)\\s+(.*)$"))

^{:line 22 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def SLUG-NONWORD-RE ^{:line 22 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "[^a-z0-9]+"))

^{:line 24 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def SLUG-LEADING-RE ^{:line 24 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "^_+"))

^{:line 26 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def SLUG-TRAILING-RE ^{:line 26 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "_+$"))

^{:line 28 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def DIGITS-ONLY-RE ^{:line 28 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "[^0-9]"))

^{:line 30 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def ISO19-RE ^{:line 30 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"))

^{:line 32 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def ISO16-RE ^{:line 32 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}"))

^{:line 34 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn str-index-of [^String s ^String sub]
  ^{:line 34 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/index-of s sub))

^{:line 36 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn split-comma [^String s]
  ^{:line 37 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (vec ^{:line 37 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (remove str/blank? ^{:line 37 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (map str/trim ^{:line 37 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/split s COMMA-RE)))))

^{:line 39 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean str-lt? [^String a ^String b]
  ^{:line 39 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (neg? ^{:line 39 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (compare a b)))

^{:line 41 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn split-kv [^String line]
  ^{:line 42 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [t ^{:line 42 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/trim line)
   m ^{:line 42 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-find SPLIT-KV-RE t)]
  ^{:line 43 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 43 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (some? m) ^{:line 44 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [parts ^{:line 44 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} [^{:line 44 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (nth m 1) ^{:line 44 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (nth m 2)]]
  parts) ^{:line 45 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [parts ^{:line 45 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} [t ""]]
  parts))))

^{:line 47 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String fmt-id [^String n]
  ^{:line 48 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [s ^{:line 48 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str n)]
  ^{:line 49 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str ^{:line 49 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (subs s 0 4) "-" ^{:line 49 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (subs s 4 6) "-" ^{:line 49 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (subs s 6 8) "-" ^{:line 49 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (subs s 8 14))))

^{:line 51 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String slugify [^String title]
  ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [base ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/replace ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/replace ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/replace ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/lower-case ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str title)) SLUG-NONWORD-RE "_") SLUG-LEADING-RE "") SLUG-TRAILING-RE "")
   capped ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (> ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (count base) 60) ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (subs base 0 60) base)
   clean ^{:line 52 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/replace capped SLUG-TRAILING-RE "")]
  ^{:line 53 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 53 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/blank? clean) "untitled" clean)))

^{:line 55 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String filter-digits [^String s]
  ^{:line 55 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/replace s DIGITS-ONLY-RE ""))

^{:line 57 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean is-iso-datetime-19 [^String s]
  ^{:line 58 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (boolean ^{:line 58 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 58 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= 19 ^{:line 58 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (count s)) ^{:line 58 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-matches ISO19-RE s))))

^{:line 60 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean is-iso-datetime-16 [^String s]
  ^{:line 61 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (boolean ^{:line 61 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 61 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= 16 ^{:line 61 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (count s)) ^{:line 61 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-matches ISO16-RE s))))

^{:line 63 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String repeat-str [^String s n]
  ^{:line 64 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (apply str ^{:line 64 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (repeat ^{:line 64 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (max 0 ^{:line 64 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (long n)) s)))

^{:line 66 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def EDIT-BATCH-ENVELOPE-VERSION 1)

^{:line 68 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def EDIT-BATCH-ENVELOPE-KEYS ^{:line 69 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} #{:fram-edit-seal-sha :fram-edit-candidate :fram-edit-line-count :fram-edit-path :fram-edit-ops :fram-edit-ops-digest :fram-edit-edn-digest :fram-edit-envelope :fram-edit-base-version :fram-edit-installed :fram-edit-log :fram-edit-final-version :fram-edit-module :fram-edit-batch-sha :fram-edit-batch})

^{:line 85 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def DIGEST-RE ^{:line 85 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-pattern "[0-9a-f]{64}"))

^{:line 87 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean edit-batch-envelope-marker? [record]
  ^{:line 88 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 88 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (map? record) ^{:line 88 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (contains? record :fram-edit-envelope)))

^{:line 90 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean digest? [^String value]
  ^{:line 91 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 91 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (string? value) ^{:line 91 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (boolean ^{:line 91 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (re-matches DIGEST-RE value))))

^{:line 93 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean nonblank? [^String value]
  ^{:line 94 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 94 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (string? value) ^{:line 94 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (not ^{:line 94 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/blank? value))))

^{:line 96 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean generation-record? [op]
  ^{:line 97 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 97 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= "@log:gen" ^{:line 97 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:l op)) ^{:line 97 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= "generation" ^{:line 97 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:p op))))

^{:line 99 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^Boolean valid-edit-batch-envelope? [record ^String expected-seal]
  ^{:line 100 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [base ^{:line 100 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-base-version record)
   final ^{:line 100 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-final-version record)
   ops ^{:line 100 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-ops record)
   installed ^{:line 100 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-installed record)
   lines ^{:line 100 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-line-count record)]
  ^{:line 101 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 101 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (map? record) ^{:line 102 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= EDIT-BATCH-ENVELOPE-KEYS ^{:line 102 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (set ^{:line 102 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (keys record))) ^{:line 103 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= EDIT-BATCH-ENVELOPE-VERSION ^{:line 103 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-envelope record)) ^{:line 104 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (nonblank? ^{:line 104 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-log record)) ^{:line 105 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (nonblank? ^{:line 105 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-candidate record)) ^{:line 106 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= ^{:line 106 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-candidate record) ^{:line 106 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-batch record)) ^{:line 107 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (nonblank? ^{:line 107 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-module record)) ^{:line 108 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (nonblank? ^{:line 108 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-path record)) ^{:line 109 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (int? base) ^{:line 110 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (not ^{:line 110 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (neg? base)) ^{:line 111 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (int? final) ^{:line 112 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (not ^{:line 112 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (neg? final)) ^{:line 113 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (int? ops) ^{:line 114 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (not ^{:line 114 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (neg? ops)) ^{:line 115 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (int? installed) ^{:line 116 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (not ^{:line 116 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (neg? installed)) ^{:line 117 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (int? lines) ^{:line 118 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (not ^{:line 118 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (neg? lines)) ^{:line 119 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= ops installed) ^{:line 120 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= installed lines) ^{:line 121 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= final ^{:line 121 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (+ base installed)) ^{:line 122 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (digest? ^{:line 122 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-ops-digest record)) ^{:line 123 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (digest? ^{:line 123 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-edn-digest record)) ^{:line 124 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (digest? ^{:line 124 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-batch-sha record)) ^{:line 125 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (digest? ^{:line 125 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-seal-sha record)) ^{:line 126 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= ^{:line 126 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fram-edit-seal-sha record) expected-seal))))

^{:line 128 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (def EDIT-BATCH-ENVELOPE-SEAL-FIELDS ^{:line 129 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} [:fram-edit-envelope :fram-edit-log :fram-edit-candidate :fram-edit-batch :fram-edit-module :fram-edit-path :fram-edit-base-version :fram-edit-final-version :fram-edit-ops :fram-edit-installed :fram-edit-ops-digest :fram-edit-edn-digest :fram-edit-line-count :fram-edit-batch-sha])

^{:line 144 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn classify-rewrite-crash [^String coord live-ino old-ino new-ino old-bytes old-sha new-sha1 live-line1-sha live-prefix-sha]
  ^{:line 147 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (cond
  ^{:line 148 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (nil? live-ino) ^{:line 149 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (throw ^{:line 149 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (ex-info ^{:line 149 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "rewrite intent present but " coord " does not exist — refusing to classify") ^{:line 149 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} {:path coord :fram/doctor-refusal true}))
  ^{:line 150 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 150 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (some? old-ino) ^{:line 150 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= live-ino old-ino)) :roll-back
  ^{:line 152 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 152 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (some? new-ino) ^{:line 152 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= live-ino new-ino)) :roll-forward
  ^{:line 154 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 154 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (some? new-sha1) ^{:line 154 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= new-sha1 live-line1-sha)) :roll-forward
  ^{:line 156 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 156 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (some? old-bytes) ^{:line 156 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (some? old-sha) ^{:line 156 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= old-sha live-prefix-sha)) :roll-back
  :else ^{:line 159 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (throw ^{:line 159 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (ex-info ^{:line 159 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "rewrite intent does not match the live corpus at " coord " (neither source nor replacement inode/sha) — refusing to classify; operator intervention required") ^{:line 159 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} {:path coord :fram/doctor-refusal true}))))

^{:line 161 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn log-envelope [^String canonical-log req]
  ^{:line 162 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [base ^{:line 162 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} {:op :for-log :expected-log canonical-log :request req}]
  ^{:line 163 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 163 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (contains? req :fmt) ^{:line 163 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (assoc base :fmt ^{:line 163 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:fmt req)) base)))

^{:line 165 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String reject-message [rejection]
  ^{:line 166 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 166 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (sequential? rejection) ^{:line 167 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str/join "; " ^{:line 167 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (map str rejection)) ^{:line 168 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str rejection)))

^{:line 170 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String coord-write-response [resp]
  ^{:line 171 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (cond
  ^{:line 172 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:ok resp) ^{:line 173 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "ok:" ^{:line 173 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:ok resp))
  ^{:line 174 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= ^{:line 174 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:reject resp) :conflict) "conflict"
  ^{:line 176 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= ^{:line 176 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:code resp) :log-mismatch) ^{:line 177 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "log-mismatch: expected " ^{:line 178 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:expected-log resp) "; daemon serves " ^{:line 180 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:served-log resp))
  ^{:line 181 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= "unknown op" ^{:line 181 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:error resp)) "protocol-incompatible"
  ^{:line 183 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:reject resp) ^{:line 184 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "reject:" ^{:line 184 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (reject-message ^{:line 184 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:reject resp)))
  :else ^{:line 186 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "error:" ^{:line 186 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (pr-str resp))))

^{:line 188 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn coord-version-response [resp]
  ^{:line 189 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (let [version ^{:line 189 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:version resp)]
  ^{:line 189 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 189 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (integer? version) version -1)))

^{:line 191 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn coord-version-for-log-response [resp]
  ^{:line 192 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (cond
  ^{:line 193 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (integer? ^{:line 193 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:version resp)) ^{:line 194 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:version resp)
  ^{:line 195 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= :log-mismatch ^{:line 195 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:code resp)) -2
  :else -3))

^{:line 200 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String coord-status-response [port resp]
  ^{:line 201 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (cond
  ^{:line 202 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (integer? ^{:line 202 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:version resp)) ^{:line 203 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "coordinator UP on 127.0.0.1:" port " (v" ^{:line 203 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:version resp) ")")
  ^{:line 204 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= :log-mismatch ^{:line 204 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:code resp)) ^{:line 205 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "coordinator WRONG LOG on 127.0.0.1:" port " — expected " ^{:line 208 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:expected-log resp) "; daemon serves " ^{:line 210 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:served-log resp) "; refusing fenced reads and writes")
  ^{:line 212 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= "unknown op" ^{:line 212 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:error resp)) ^{:line 213 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "coordinator INCOMPATIBLE on 127.0.0.1:" port " — daemon lacks required log-fence protocol; restart it with current Fram")
  :else ^{:line 217 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "coordinator UNUSABLE on 127.0.0.1:" port " — " ^{:line 217 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (pr-str resp))))

^{:line 219 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn ^String coord-status-down [port]
  ^{:line 220 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (str "coordinator DOWN on 127.0.0.1:" port " — start it with bin/fram-up"))

^{:line 222 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn warm-read-response [resp]
  ^{:line 223 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 223 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (and ^{:line 223 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (map? resp) ^{:line 223 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= "unknown op" ^{:line 223 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:error resp))) nil resp))

^{:line 225 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (defn warm-read-for-log-response [resp]
  ^{:line 226 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (if ^{:line 226 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (or ^{:line 226 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (= "unknown op" ^{:line 226 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (:error resp)) ^{:line 226 :file "/home/tom/code/fram/src/fram/rt_core.bclj"} (contains? resp :reject)) nil resp))

;; error RewriteCrashError = RewriteCrash
(defrecord RewriteCrash [message path doctor-refusal])
