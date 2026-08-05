(ns fram.text-search
  (:require [clojure.string :as str]
            [fram.text-index :as text-index]
            [fram.types :as t]))

(def WORD-SEPARATOR-RE (re-pattern "[^\\p{L}\\p{Nd}]+"))

(defrecord SearchAnalyzers [token-rows normalized-rows stem-postings trigram-postings bytes])

(defn searchanalyzers-token-rows [r] (:token-rows r))

(defn searchanalyzers-normalized-rows [r] (:normalized-rows r))

(defn searchanalyzers-stem-postings [r] (:stem-postings r))

(defn searchanalyzers-trigram-postings [r] (:trigram-postings r))

(defn searchanalyzers-bytes [r] (:bytes r))

(defrecord TextSearchSource [exact rows maximum analyzers-cell bytes])

(defn textsearchsource-exact [r] (:exact r))

(defn textsearchsource-rows [r] (:rows r))

(defn textsearchsource-maximum [r] (:maximum r))

(defn textsearchsource-analyzers-cell [r] (:analyzers-cell r))

(defn textsearchsource-bytes [r] (:bytes r))

(defrecord SearchBuildState [token-rows normalized-rows stem-postings trigram-postings])

(defn searchbuildstate-token-rows [r] (:token-rows r))

(defn searchbuildstate-normalized-rows [r] (:normalized-rows r))

(defn searchbuildstate-stem-postings [r] (:stem-postings r))

(defn searchbuildstate-trigram-postings [r] (:trigram-postings r))

(defn source-weight [^TextSearchSource source]
  (let [analyzers (deref (textsearchsource-analyzers-cell source))]
  (+ (textsearchsource-bytes source) (if (nil? analyzers) 0 (searchanalyzers-bytes analyzers)))))

(defn- ^String lower [^String value]
  (str/lower-case value))

(defn word-tokens [^String value]
  (let [parts (str/split value WORD-SEPARATOR-RE)]
  (loop [index 0
   tokens []]
  (if (>= index (count parts)) tokens (let [token (nth parts index)]
  (recur (inc index) (if (= "" token) tokens (conj tokens (lower token)))))))))

(defn- ^String drop-last [^String value amount]
  (subs value 0 (- (count value) amount)))

(defn- ^Boolean vowel? [^String character]
  (contains? #{"a" "e" "i" "o" "u"} character))

(defn- ^Boolean vowel-at? [^String value index]
  (vowel? (subs value index (inc index))))

(defn- ^Boolean has-vowel? [^String value]
  (loop [index 0]
  (if (>= index (count value)) false (if (vowel-at? value index) true (recur (inc index))))))

(defn- ^Boolean double-consonant? [^String value]
  (let [size (count value)]
  (and (>= size 2) (let [last-char (subs value (dec size) size)
   prior-char (subs value (- size 2) (dec size))]
  (and (= last-char prior-char) (and (not (vowel? last-char)) (not (contains? #{"l" "s" "z"} last-char))))))))

(defn- ^Boolean short-syllable? [^String value]
  (let [size (count value)]
  (and (>= size 3) (let [first-char (subs value (- size 3) (- size 2))
   middle-char (subs value (- size 2) (dec size))
   last-char (subs value (dec size) size)]
  (and (not (vowel? first-char)) (and (vowel? middle-char) (and (not (vowel? last-char)) (not (contains? #{"w" "x" "y"} last-char)))))))))

(defn- ^String normalize-verb-stem [^String value]
  (if (double-consonant? value) (drop-last value 1) (if (short-syllable? value) (str value "e") value)))

(defn- ^String plural-stem [^String value]
  (cond
  (and (> (count value) 4) (str/ends-with? value "ies")) (str (drop-last value 3) "y")
  (str/ends-with? value "sses") (drop-last value 2)
  (and (> (count value) 3) (and (str/ends-with? value "s") (not (or (str/ends-with? value "ss") (or (str/ends-with? value "us") (str/ends-with? value "is")))))) (drop-last value 1)
  :else value))

(defn ^String english-stem [^String token]
  (let [value (plural-stem (lower token))]
  (cond
  (and (> (count value) 4) (str/ends-with? value "ied")) (str (drop-last value 3) "y")
  (and (> (count value) 5) (str/ends-with? value "ing")) (let [base (drop-last value 3)]
  (if (has-vowel? base) (normalize-verb-stem base) value))
  (and (> (count value) 4) (str/ends-with? value "ed")) (let [base (drop-last value 2)]
  (if (has-vowel? base) (normalize-verb-stem base) value))
  (and (> (count value) 4) (str/ends-with? value "ly")) (drop-last value 2)
  (and (> (count value) 4) (str/ends-with? value "er")) (let [base (drop-last value 2)]
  (if (has-vowel? base) (normalize-verb-stem base) value))
  (and (> (count value) 5) (str/ends-with? value "est")) (let [base (drop-last value 3)]
  (if (has-vowel? base) (normalize-verb-stem base) value))
  :else value)))

(defn- stems [tokens]
  (set (mapv (fn [token] (english-stem token)) tokens)))

(defn- trigrams [^String value]
  (loop [index 0
   grams #{}]
  (if (> (+ index 3) (count value)) grams (recur (inc index) (conj grams (subs value index (+ index 3)))))))

(defn- postings-add [postings keys handle]
  (reduce (fn [current key] (assoc current key (conj (get current key []) handle))) postings keys))

(defn- postings-weight [postings]
  (reduce-kv (fn [total key handles] (+ total 84 (* 4 (count key)) (* 42 (count handles)))) 0 postings))

(defn- token-rows-weight [rows]
  (reduce (fn [total tokens] (+ total 42 (reduce (fn [subtotal token] (+ subtotal 42 (* 4 (count token)))) 0 tokens))) 0 rows))

(defn- strings-weight [values]
  (reduce (fn [total value] (+ total 70 (* 4 (count value)))) 0 values))

(defn- index-limit [weight maximum]
  (if (> weight maximum) (throw (ex-info "text search index exceeds the snapshot cache budget" {:type :query-text-index-limit :fram/code :query-text-index-limit :bytes weight :maximum maximum})) nil))

(defn- ^SearchBuildState build-state [rows]
  (loop [handle 0
   state (->SearchBuildState [] [] {} {})]
  (if (>= handle (count rows)) state (let [value (t/triple-slot2 (nth rows handle))]
  (if (string? value) (let [tokens (word-tokens value)
   normalized (lower value)
   stem-keys (stems tokens)
   gram-keys (trigrams normalized)]
  (recur (inc handle) (->SearchBuildState (conj (searchbuildstate-token-rows state) tokens) (conj (searchbuildstate-normalized-rows state) normalized) (postings-add (searchbuildstate-stem-postings state) (vec stem-keys) handle) (postings-add (searchbuildstate-trigram-postings state) (vec gram-keys) handle)))) (recur (inc handle) (->SearchBuildState (conj (searchbuildstate-token-rows state) []) (conj (searchbuildstate-normalized-rows state) "") (searchbuildstate-stem-postings state) (searchbuildstate-trigram-postings state))))))))

(defn ^TextSearchSource build-source [propositions maximum]
  (let [rows (vec propositions)
   exact (text-index/build-source rows maximum)
   weight (+ (text-index/source-weight exact) 112 (* 28 (count rows)))
   cell (atom nil)]
  (index-limit weight maximum)
  (->TextSearchSource exact rows maximum cell weight)))

(defn- ^SearchAnalyzers analyzers-of! [^TextSearchSource source]
  (let [current (deref (textsearchsource-analyzers-cell source))]
  (if (nil? current) (let [state (build-state (textsearchsource-rows source))
   token-rows (searchbuildstate-token-rows state)
   normalized-rows (searchbuildstate-normalized-rows state)
   stem-postings (searchbuildstate-stem-postings state)
   trigram-postings (searchbuildstate-trigram-postings state)
   weight (+ (token-rows-weight token-rows) (strings-weight normalized-rows) (postings-weight stem-postings) (postings-weight trigram-postings))
   analyzers (->SearchAnalyzers token-rows normalized-rows stem-postings trigram-postings weight)]
  (index-limit (+ (textsearchsource-bytes source) weight) (textsearchsource-maximum source))
  (reset! (textsearchsource-analyzers-cell source) analyzers)
  analyzers) current)))

(defn- needle-error [^String message]
  (throw (ex-info message {:type :fram-query-abort :code :query-text-invalid-needle})))

(defn- ^String string-needle [^String relation needle]
  (if (string? needle) needle (needle-error (str relation " needle must be a string"))))

(defn- word-needle [^String relation needle]
  (let [tokens (word-tokens (string-needle relation needle))]
  (if (empty? tokens) (needle-error (str relation " needle must contain at least one word token")) tokens)))

(defn- ^String substring-needle [^String relation needle]
  (let [value (lower (string-needle relation needle))]
  (if (empty? (str/trim value)) (needle-error (str relation " needle must contain non-whitespace text")) value)))

(defn ^Boolean word-needle-valid? [needle]
  (and (string? needle) (not (empty? (word-tokens needle)))))

(defn ^Boolean substring-needle-valid? [needle]
  (and (string? needle) (not (empty? (str/trim needle)))))

(defn- shortest-posting [vectors]
  (loop [index 0
   best nil]
  (if (>= index (count vectors)) (or best []) (let [candidate (nth vectors index)]
  (recur (inc index) (if (or (nil? best) (< (count candidate) (count best))) candidate best))))))

(defn- postings-handles [postings keys]
  (let [wanted (vec (sort (vec (set keys))))]
  (if (empty? wanted) [] (let [vectors (mapv (fn [key] (get postings key [])) wanted)
   seed (shortest-posting vectors)]
  (if (or (empty? seed) (= 1 (count vectors))) seed (let [sets (mapv (fn [handles] (set handles)) vectors)]
  (filterv (fn [handle] (every? (fn [handles] (contains? handles handle)) sets)) seed)))))))

(defn- all-handles [^TextSearchSource source]
  (vec (range (count (textsearchsource-rows source)))))

(defn- ^Boolean sequence-at? [haystack needle start]
  (loop [offset 0]
  (if (>= offset (count needle)) true (if (= (nth haystack (+ start offset)) (nth needle offset)) (recur (inc offset)) false))))

(defn- ^Boolean sequence-contains? [haystack needle]
  (if (or (empty? needle) (> (count needle) (count haystack))) false (loop [start 0]
  (if (> (+ start (count needle)) (count haystack)) false (if (sequence-at? haystack needle start) true (recur (inc start)))))))

(defn phrase-handles! [^TextSearchSource source needle]
  (let [tokens (word-needle "text-phrase" needle)
   candidates (text-index/indexed-handles (textsearchsource-exact source) needle)
   rows (searchanalyzers-token-rows (analyzers-of! source))]
  (filterv (fn [handle] (sequence-contains? (nth rows handle) tokens)) candidates)))

(defn phrase-scan-handles! [^TextSearchSource source needle]
  (let [tokens (word-needle "text-phrase" needle)
   rows (searchanalyzers-token-rows (analyzers-of! source))]
  (filterv (fn [handle] (sequence-contains? (nth rows handle) tokens)) (all-handles source))))

(defn substring-handles! [^TextSearchSource source needle]
  (let [value (substring-needle "text-substring" needle)
   grams (vec (trigrams value))
   analyzers (analyzers-of! source)
   candidates (if (empty? grams) (all-handles source) (postings-handles (searchanalyzers-trigram-postings analyzers) grams))
   rows (searchanalyzers-normalized-rows analyzers)]
  (filterv (fn [handle] (str/includes? (nth rows handle) value)) candidates)))

(defn substring-scan-handles! [^TextSearchSource source needle]
  (let [value (substring-needle "text-substring" needle)
   rows (searchanalyzers-normalized-rows (analyzers-of! source))]
  (filterv (fn [handle] (str/includes? (nth rows handle) value)) (all-handles source))))

(defn stem-handles! [^TextSearchSource source needle]
  (let [keys (vec (stems (word-needle "text-stem" needle)))]
  (postings-handles (searchanalyzers-stem-postings (analyzers-of! source)) keys)))

(defn stem-scan-handles! [^TextSearchSource source needle]
  (let [keys (stems (word-needle "text-stem" needle))
   rows (searchanalyzers-token-rows (analyzers-of! source))]
  (filterv (fn [handle] (let [row-stems (stems (nth rows handle))]
  (every? (fn [key] (contains? row-stems key)) keys))) (all-handles source))))

(defn- count-token [tokens ^String wanted]
  (reduce (fn [total token] (if (= token wanted) (inc total) total)) 0 tokens))

(defn- score-at! [^TextSearchSource source handle needle]
  (let [value (substring-needle "text-search" needle)
   query-tokens (word-tokens value)
   query-stems (stems query-tokens)
   analyzers (analyzers-of! source)
   document-tokens (nth (searchanalyzers-token-rows analyzers) handle)
   document-token-set (set document-tokens)
   document-stems (stems document-tokens)
   normalized (nth (searchanalyzers-normalized-rows analyzers) handle)
   substring-match (str/includes? normalized value)
   stem-covered (and (not (empty? query-stems)) (every? (fn [key] (contains? document-stems key)) query-stems))
   phrase-match (and (not (empty? query-tokens)) (sequence-contains? document-tokens query-tokens))]
  (if (not (or substring-match stem-covered)) 0 (let [token-score (reduce (fn [total token] (+ total (cond
  (contains? document-token-set token) 100
  (contains? document-stems (english-stem token)) 60
  :else 0) (* 5 (min 3 (count-token document-tokens token))))) 0 query-tokens)
   raw (+ token-score (if phrase-match 250 0) (if substring-match 80 0) (if (= normalized value) 400 0))
   penalty (min 40 (max 0 (- (count document-tokens) (count query-tokens))))]
  (max 1 (- raw penalty))))))

(defn- union-handles [left right]
  (vec (sort (vec (set (concat left right))))))

(defn ranked-handles! [^TextSearchSource source needle]
  (let [value (substring-needle "text-search" needle)
   query-tokens (word-tokens value)
   analyzers (analyzers-of! source)
   stem-candidates (if (empty? query-tokens) [] (postings-handles (searchanalyzers-stem-postings analyzers) (vec (stems query-tokens))))
   gram-keys (vec (trigrams value))
   substring-candidates (if (empty? gram-keys) (all-handles source) (postings-handles (searchanalyzers-trigram-postings analyzers) gram-keys))
   candidates (union-handles stem-candidates substring-candidates)]
  (filterv (fn [handle] (pos? (score-at! source handle needle))) candidates)))

(defn ranked-scan-handles! [^TextSearchSource source needle]
  (filterv (fn [handle] (pos? (score-at! source handle needle))) (all-handles source)))

(defn- rows-for-handles [^TextSearchSource source needle handles]
  (let [rows (textsearchsource-rows source)]
  (mapv (fn [handle] (let [proposition (nth rows handle)]
  [(t/triple-slot0 proposition) (t/triple-slot1 proposition) needle])) handles)))

(defn- ranked-rows-for-handles! [^TextSearchSource source needle handles]
  (let [rows (textsearchsource-rows source)]
  (mapv (fn [handle] (let [proposition (nth rows handle)]
  [(t/triple-slot0 proposition) (t/triple-slot1 proposition) needle (score-at! source handle needle)])) handles)))

(defn exact-indexed-rows [^TextSearchSource source needle]
  (text-index/indexed-rows (textsearchsource-exact source) needle))

(defn exact-scan-rows [^TextSearchSource source needle]
  (text-index/scan-rows (textsearchsource-exact source) needle))

(defn phrase-indexed-rows! [^TextSearchSource source needle]
  (rows-for-handles source needle (phrase-handles! source needle)))

(defn phrase-scan-rows! [^TextSearchSource source needle]
  (rows-for-handles source needle (phrase-scan-handles! source needle)))

(defn substring-indexed-rows! [^TextSearchSource source needle]
  (rows-for-handles source needle (substring-handles! source needle)))

(defn substring-scan-rows! [^TextSearchSource source needle]
  (rows-for-handles source needle (substring-scan-handles! source needle)))

(defn stem-indexed-rows! [^TextSearchSource source needle]
  (rows-for-handles source needle (stem-handles! source needle)))

(defn stem-scan-rows! [^TextSearchSource source needle]
  (rows-for-handles source needle (stem-scan-handles! source needle)))

(defn ranked-indexed-rows! [^TextSearchSource source needle]
  (ranked-rows-for-handles! source needle (ranked-handles! source needle)))

(defn ranked-scan-rows! [^TextSearchSource source needle]
  (ranked-rows-for-handles! source needle (ranked-scan-handles! source needle)))
