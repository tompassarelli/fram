(ns fram.text-index
  (:require [fram.types :as t]))

^{:line 11 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (def text-index-max-bytes ^{:line 11 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (* 64 1024 1024))

^{:line 13 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defrecord TextCandidateSource [rows postings bytes])

(defn textcandidatesource-rows [r] (:rows r))

(defn textcandidatesource-postings [r] (:postings r))

(defn textcandidatesource-bytes [r] (:bytes r))

^{:line 18 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn source-weight [^TextCandidateSource source]
  ^{:line 19 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (textcandidatesource-bytes source))

^{:line 23 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn- token-set [^String value]
  ^{:line 24 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (persistent! ^{:line 25 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (reduce ^{:line 25 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [folded token] ^{:line 28 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (conj! folded ^{:line 28 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (.toLowerCase token java.util.Locale/ROOT))) ^{:line 29 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (transient ^{:line 29 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} #{}) ^{:line 29 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (re-seq #"[\p{L}\p{Nd}]+" value))))

^{:line 31 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn tokenize [^String value]
  ^{:line 32 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (vec ^{:line 32 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (sort ^{:line 32 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (vec ^{:line 32 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (token-set value)))))

^{:line 34 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn ^Boolean text-needle-valid? [needle]
  ^{:line 35 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (and ^{:line 35 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (string? needle) ^{:line 35 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (not ^{:line 35 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (empty? ^{:line 35 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (tokenize needle)))))

^{:line 37 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn- postings-weight [postings]
  ^{:line 38 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (reduce ^{:line 38 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [total entry] ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [token ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (nth entry 0)
   handles ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (nth entry 1)]
  ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (+ total 84 ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (* 4 ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count token)) ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (* 42 ^{:line 40 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count handles))))) 0 ^{:line 42 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (vec postings)))

^{:line 44 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn- index-limit [weight maximum]
  ^{:line 47 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 47 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (> weight maximum) ^{:line 48 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (throw ^{:line 48 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (ex-info "text index exceeds the snapshot cache budget" ^{:line 48 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} {:type :query-text-index-limit :fram/code :query-text-index-limit :bytes weight :maximum maximum})) nil))

^{:line 51 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn ^TextCandidateSource build-source [propositions maximum]
  ^{:line 54 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [rows ^{:line 54 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (vec propositions)
   row-count ^{:line 55 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count rows)
   postings ^{:line 59 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (persistent! ^{:line 60 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (loop [handle 0
   current ^{:line 60 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (transient ^{:line 60 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} {})]
  ^{:line 61 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 61 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (>= handle row-count) current ^{:line 63 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [value ^{:line 63 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (t/triple-slot2 ^{:line 63 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (nth rows handle))]
  ^{:line 64 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (recur ^{:line 64 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (inc handle) ^{:line 65 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 65 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (string? value) ^{:line 66 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (reduce ^{:line 66 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [index token] ^{:line 69 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (assoc! index token ^{:line 70 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (conj ^{:line 70 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (get index token ^{:line 70 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} []) handle))) current ^{:line 71 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (token-set value)) current))))))
   weight ^{:line 73 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (+ 112 ^{:line 73 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (* 14 row-count) ^{:line 73 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (postings-weight postings))]
  ^{:line 74 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (index-limit weight maximum)
  ^{:line 75 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (->TextCandidateSource rows postings weight)))

^{:line 77 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn- needle-error [^String message]
  ^{:line 78 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (throw ^{:line 78 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (ex-info message ^{:line 78 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} {:type :fram-query-abort :code :query-text-invalid-needle})))

^{:line 80 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn- needle-tokens [needle]
  ^{:line 81 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 81 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (string? needle) ^{:line 82 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [tokens ^{:line 82 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (tokenize needle)]
  ^{:line 83 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 83 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (empty? tokens) ^{:line 84 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (needle-error "text-match needle must contain at least one word token") tokens)) ^{:line 86 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (needle-error "text-match needle must be a string")))

^{:line 88 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn- shortest-posting [posting-vectors]
  ^{:line 89 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (loop [position 0
   best nil]
  ^{:line 90 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 90 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (>= position ^{:line 90 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count posting-vectors)) ^{:line 91 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (or best ^{:line 91 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} []) ^{:line 92 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [candidate ^{:line 92 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (nth posting-vectors position)]
  ^{:line 93 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (recur ^{:line 93 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (inc position) ^{:line 94 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 94 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (or ^{:line 94 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (nil? best) ^{:line 94 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (< ^{:line 94 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count candidate) ^{:line 94 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count best))) candidate best))))))

^{:line 98 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn indexed-handles [^TextCandidateSource source needle]
  ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [tokens ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (needle-tokens needle)
   postings ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (textcandidatesource-postings source)
   posting-vectors ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (mapv ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [token] ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (get postings token ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} [])) tokens)
   seed ^{:line 101 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (shortest-posting posting-vectors)]
  ^{:line 102 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 102 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (or ^{:line 102 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (empty? seed) ^{:line 102 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (= 1 ^{:line 102 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count posting-vectors))) seed ^{:line 104 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [posting-sets ^{:line 104 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (mapv ^{:line 104 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [handles] ^{:line 104 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (set handles)) posting-vectors)]
  ^{:line 105 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (filterv ^{:line 105 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [handle] ^{:line 105 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (every? ^{:line 105 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [handles] ^{:line 105 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (contains? handles handle)) posting-sets)) seed)))))

^{:line 108 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn scan-handles [^TextCandidateSource source needle]
  ^{:line 111 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [tokens ^{:line 111 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (needle-tokens needle)
   rows ^{:line 111 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (textcandidatesource-rows source)]
  ^{:line 112 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (loop [handle 0
   matches ^{:line 112 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} []]
  ^{:line 113 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 113 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (>= handle ^{:line 113 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (count rows)) matches ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [value ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (t/triple-slot2 ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (nth rows handle))
   matched ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (string? value) ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [haystack ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (token-set value)]
  ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (every? ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [token] ^{:line 115 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (contains? haystack token)) tokens)) false)]
  ^{:line 116 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (recur ^{:line 116 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (inc handle) ^{:line 116 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (if matched ^{:line 116 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (conj matches handle) matches)))))))

^{:line 118 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn- rows-for-handles [^TextCandidateSource source needle handles]
  ^{:line 122 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [rows ^{:line 122 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (textcandidatesource-rows source)]
  ^{:line 123 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (mapv ^{:line 123 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (fn [handle] ^{:line 123 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (let [proposition ^{:line 123 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (nth rows handle)]
  ^{:line 123 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} [^{:line 123 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (t/triple-slot0 proposition) ^{:line 123 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (t/triple-slot1 proposition) needle])) handles)))

^{:line 126 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn indexed-rows [^TextCandidateSource source needle]
  ^{:line 129 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (rows-for-handles source needle ^{:line 129 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (indexed-handles source needle)))

^{:line 131 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (defn scan-rows [^TextCandidateSource source needle]
  ^{:line 134 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (rows-for-handles source needle ^{:line 134 :file "/home/tom/code/fram/wt-weight-cal/src/fram/text_index.bclj"} (scan-handles source needle)))
