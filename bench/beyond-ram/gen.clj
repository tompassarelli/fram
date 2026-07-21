;; bench/beyond-ram/gen.clj — synthetic code-graph corpus for the FRAM_MMAP_IMAGE
;; RSS bench (thread 019f82d9). Mirrors the probe's recipe: ~7 facts/subject,
;; ~40-char subject names, 5% of subjects carry an 80-200 char doc body. Flat log,
;; one keyed-latest fact per line, global monotonic :tx.
;;   bb bench/beyond-ram/gen.clj <out-path> <n-facts>
(require '[clojure.java.io :as io])

(def out (or (first *command-line-args*) "/tmp/fram-beyond-ram.log"))
(def n-facts (Long/parseLong (or (second *command-line-args*) "1000000")))

;; ~40-char subject: a package-path-ish symbol id.
(defn subj [i] (format "@acme.platform.svc.mod%05d.Symbol_%06d" (mod i 4096) i))
(defn modl [i] (format "@acme.platform.svc.mod%05d" (mod i 4096)))
(def bodies
  (mapv (fn [k] (apply str (repeat (+ 80 (mod (* k 37) 121)) (char (+ 97 (mod k 26))))))
        (range 64)))                        ; pool of 80-200 char doc bodies

;; the ~7 facts a subject i contributes (a doc body on every 20th => 5%).
(defn subject-facts [i]
  (cond-> [["kind" "def"]
           ["in_module" (modl i)]
           ["line" (str (mod (* i 7) 9000))]
           ["arity" (str (mod i 6))]
           ["calls" (subj (mod (+ i 1) (max 1 n-facts)))]
           ["calls" (subj (mod (+ i 7) (max 1 n-facts)))]]
    (zero? (mod i 20)) (conj ["doc" (nth bodies (mod i 64))])))

(let [t0 (System/nanoTime)]
  (with-open [w (io/writer out)]
    ;; single loop over (tx, subject i, that subject's pending facts) — advance to the
    ;; next subject when its facts are drained; stop at n-facts.
    (loop [tx 1 i 0 fs (subject-facts 0)]
      (when (<= tx n-facts)
        (if (seq fs)
          (let [[p r] (first fs)]
            (.write w (pr-str {:tx tx :op "assert" :l (subj i) :p p :r r
                               :ts "bench" :by "beyond-ram/gen"}))
            (.write w "\n")
            (recur (inc tx) i (rest fs)))
          (recur tx (inc i) (subject-facts (inc i)))))))
  (println (pr-str {:generated (.getPath (java.io.File. out)) :n-facts n-facts
                    :ms (quot (- (System/nanoTime) t0) 1000000)})))
