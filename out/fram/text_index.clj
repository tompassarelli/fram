(ns fram.text-index
  (:require [fram.types :as t]))

(def text-index-max-bytes (* 64 1024 1024))

(defrecord TextCandidateSource [rows postings bytes])

(defn textcandidatesource-rows [r] (:rows r))

(defn textcandidatesource-postings [r] (:postings r))

(defn textcandidatesource-bytes [r] (:bytes r))

(defn source-weight [^TextCandidateSource source]
  (textcandidatesource-bytes source))

(defn- token-set [^String value]
  (let [matches (vec (re-seq #"[\p{L}\p{Nd}]+" value))
   folded (mapv (fn [token] (.toLowerCase token java.util.Locale/ROOT)) matches)]
  (set folded)))

(defn tokenize [^String value]
  (vec (sort (vec (token-set value)))))

(defn ^Boolean text-needle-valid? [needle]
  (and (string? needle) (not (empty? (tokenize needle)))))

(defn- postings-weight [postings]
  (reduce (fn [total entry] (let [token (nth entry 0)
   handles (nth entry 1)]
  (+ total 48 (* 2 (count token)) (* 24 (count handles))))) 0 (vec postings)))

(defn- index-limit [weight maximum]
  (if (> weight maximum) (throw (ex-info "text index exceeds the snapshot cache budget" {:type :query-text-index-limit :fram/code :query-text-index-limit :bytes weight :maximum maximum})) nil))

(defn ^TextCandidateSource build-source [propositions maximum]
  (let [rows (vec propositions)
   postings (loop [handle 0
   current {}]
  (if (>= handle (count rows)) current (let [proposition (nth rows handle)
   value (t/triple-slot2 proposition)
   next-postings (if (string? value) (reduce (fn [index token] (assoc index token (conj (get index token []) handle))) current (token-set value)) current)]
  (recur (inc handle) next-postings))))
   weight (+ 64 (* 8 (count rows)) (postings-weight postings))]
  (index-limit weight maximum)
  (->TextCandidateSource rows postings weight)))

(defn- needle-error [^String message]
  (throw (ex-info message {:type :fram-query-abort :code :query-text-invalid-needle})))

(defn- needle-tokens [needle]
  (if (string? needle) (let [tokens (tokenize needle)]
  (if (empty? tokens) (needle-error "text-match needle must contain at least one word token") tokens)) (needle-error "text-match needle must be a string")))

(defn- shortest-posting [posting-vectors]
  (loop [position 0
   best nil]
  (if (>= position (count posting-vectors)) (or best []) (let [candidate (nth posting-vectors position)]
  (recur (inc position) (if (or (nil? best) (< (count candidate) (count best))) candidate best))))))

(defn indexed-handles [^TextCandidateSource source needle]
  (let [tokens (needle-tokens needle)
   postings (textcandidatesource-postings source)
   posting-vectors (mapv (fn [token] (get postings token [])) tokens)
   seed (shortest-posting posting-vectors)]
  (if (or (empty? seed) (= 1 (count posting-vectors))) seed (let [posting-sets (mapv (fn [handles] (set handles)) posting-vectors)]
  (filterv (fn [handle] (every? (fn [handles] (contains? handles handle)) posting-sets)) seed)))))

(defn scan-handles [^TextCandidateSource source needle]
  (let [tokens (needle-tokens needle)
   rows (textcandidatesource-rows source)]
  (loop [handle 0
   matches []]
  (if (>= handle (count rows)) matches (let [value (t/triple-slot2 (nth rows handle))
   matched (if (string? value) (let [haystack (token-set value)]
  (every? (fn [token] (contains? haystack token)) tokens)) false)]
  (recur (inc handle) (if matched (conj matches handle) matches)))))))

(defn- rows-for-handles [^TextCandidateSource source needle handles]
  (let [rows (textcandidatesource-rows source)]
  (mapv (fn [handle] (let [proposition (nth rows handle)]
  [(t/triple-slot0 proposition) (t/triple-slot1 proposition) needle])) handles)))

(defn indexed-rows [^TextCandidateSource source needle]
  (rows-for-handles source needle (indexed-handles source needle)))

(defn scan-rows [^TextCandidateSource source needle]
  (rows-for-handles source needle (scan-handles source needle)))
