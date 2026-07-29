(ns fram.import
  (:require [fram.kernel :as k]
            [fram.fold :as fold]
            [clojure.string :as str]
            [fram.rt :as rt]))

^{:line 20 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defrecord Doc [head body])

(defn doc-head [r] (:head r))

(defn doc-body [r] (:body r))

^{:line 22 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- ^Doc split-doc [^String content]
  ^{:line 23 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [lines ^{:line 23 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fram.rt/split-on content "\n")
   n ^{:line 24 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (count lines)]
  ^{:line 25 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (loop [i 0]
  ^{:line 26 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (cond
  ^{:line 27 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (>= i n) ^{:line 27 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (->Doc content "")
  ^{:line 28 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (= "---" ^{:line 28 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/trim ^{:line 28 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (nth lines i))) ^{:line 29 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (->Doc ^{:line 29 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/join "\n" ^{:line 29 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (subvec ^{:line 29 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (vec lines) 0 i)) ^{:line 30 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/join "\n" ^{:line 30 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (subvec ^{:line 30 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (vec lines) ^{:line 30 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (+ i 1) n)))
  :else ^{:line 31 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (recur ^{:line 31 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (+ i 1))))))

^{:line 34 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- ^String parse-obj [^String tok]
  ^{:line 35 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (cond
  ^{:line 36 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/starts-with? tok "@") tok
  ^{:line 37 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/starts-with? tok "\"") ^{:line 37 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fram.rt/edn-unquote tok)
  :else tok))

^{:line 40 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- ^Boolean identity-fact? [c]
  ^{:line 41 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (or ^{:line 41 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (= ^{:line 41 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:p c) "predicate_name") ^{:line 41 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (= ^{:line 41 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:p c) "predicate_alias")))

^{:line 43 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- normalize-predicate-facts [facts]
  ^{:line 44 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [reg ^{:line 44 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (k/predicate-registry facts)
   normalized ^{:line 46 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (mapv ^{:line 47 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fn [c] ^{:line 48 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [p ^{:line 48 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (k/predicate-name reg ^{:line 48 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:p c))
   r ^{:line 49 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:r c)
   rv ^{:line 50 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (if ^{:line 50 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (and ^{:line 50 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (= "ref" ^{:line 50 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (k/value-kind-of facts ^{:line 50 :file "/home/tom/code/fram/main/src/fram/import.bclj"} {} p)) ^{:line 51 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (not ^{:line 51 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/starts-with? r "@"))) ^{:line 52 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str "@" r) r)]
  ^{:line 54 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (k/->Fact ^{:line 54 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:l c) p rv))) facts)]
  ^{:line 59 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (vec ^{:line 59 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (concat ^{:line 59 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (filterv identity-fact? normalized) ^{:line 60 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (filterv ^{:line 60 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fn [c] ^{:line 60 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (not ^{:line 60 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (identity-fact? c))) normalized)))))

^{:line 65 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- warn [^String msg]
  ^{:line 66 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (binding [*out* *err*]
  ^{:line 66 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (println ^{:line 66 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str "WARN import: " msg))))

^{:line 71 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- file->facts [^String path ^String content]
  ^{:line 72 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [doc ^{:line 72 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (split-doc content)
   lines ^{:line 73 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fram.rt/split-on ^{:line 73 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:head doc) "\n")
   n ^{:line 74 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (count lines)
   si ^{:line 75 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (loop [i 0]
  ^{:line 76 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (cond
  ^{:line 77 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (>= i n) ^{:line 77 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (- 0 1)
  ^{:line 78 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/starts-with? ^{:line 78 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/trim ^{:line 78 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (nth lines i)) "@") i
  :else ^{:line 79 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (recur ^{:line 79 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (+ i 1))))]
  ^{:line 80 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (if ^{:line 80 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (< si 0) ^{:line 84 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (do
  ^{:line 85 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (if ^{:line 85 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/blank? ^{:line 85 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:head doc)) nil ^{:line 87 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (warn ^{:line 87 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str path " — no @subject line found in head; dropping " n " head line(s) (a corrupted/hand-edited first line, or a stray BOM/whitespace before @?)")))
  ^{:line 89 :file "/home/tom/code/fram/main/src/fram/import.bclj"} []) ^{:line 90 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [subj ^{:line 90 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/trim ^{:line 90 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (nth lines si))
   facts ^{:line 91 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (loop [i ^{:line 91 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (+ si 1)
   acc ^{:line 91 :file "/home/tom/code/fram/main/src/fram/import.bclj"} []]
  ^{:line 92 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (if ^{:line 92 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (>= i n) acc ^{:line 94 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [t ^{:line 94 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/trim ^{:line 94 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (nth lines i))]
  ^{:line 95 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (if ^{:line 95 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/blank? t) ^{:line 96 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (recur ^{:line 96 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (+ i 1) acc) ^{:line 97 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [kv ^{:line 97 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fram.rt/split-kv t)]
  ^{:line 98 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (recur ^{:line 98 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (+ i 1) ^{:line 99 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (conj acc ^{:line 99 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (k/->Fact subj ^{:line 99 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (nth kv 0) ^{:line 99 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (parse-obj ^{:line 99 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (nth kv 1))))))))))
   body ^{:line 100 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:body doc)]
  ^{:line 101 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (if ^{:line 101 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str/blank? body) facts ^{:line 101 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (conj facts ^{:line 101 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (k/->Fact subj "body" body)))))))

^{:line 104 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- number-fact-ops [facts]
  ^{:line 105 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (loop [cs facts
   i 1
   acc ^{:line 105 :file "/home/tom/code/fram/main/src/fram/import.bclj"} []]
  ^{:line 106 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (if ^{:line 106 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (empty? cs) acc ^{:line 108 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [c ^{:line 108 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (first cs)]
  ^{:line 109 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (recur ^{:line 109 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (rest cs) ^{:line 109 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (+ i 1) ^{:line 110 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (conj acc ^{:line 110 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fold/->FactOp i "assert" ^{:line 110 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:l c) ^{:line 110 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:p c) ^{:line 110 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (:r c) "import")))))))

^{:line 117 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn- safe-file->facts [^String path]
  ^{:line 118 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (try
  ^{:line 118 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (file->facts path ^{:line 118 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fram.rt/slurp path))
  (catch Exception e
    ^{:line 120 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (warn ^{:line 120 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (str path " — skipped (could not parse): " ^{:line 120 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (.getMessage e)))
    ^{:line 121 :file "/home/tom/code/fram/main/src/fram/import.bclj"} [])))

^{:line 123 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (defn load-corpus [^String threads-dir]
  ^{:line 124 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (let [files ^{:line 124 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fram.rt/list-md threads-dir)
   facts ^{:line 125 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (reduce ^{:line 125 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (fn [acc path] ^{:line 126 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (vec ^{:line 126 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (concat acc ^{:line 126 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (safe-file->facts path)))) ^{:line 127 :file "/home/tom/code/fram/main/src/fram/import.bclj"} [] files)]
  ^{:line 129 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (number-fact-ops ^{:line 129 :file "/home/tom/code/fram/main/src/fram/import.bclj"} (normalize-predicate-facts facts))))
