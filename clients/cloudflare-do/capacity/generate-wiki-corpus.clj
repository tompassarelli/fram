;; SPDX-License-Identifier: MIT OR Apache-2.0
;; Generate the fixed wiki-shaped FRAMRPC corpus used by the capacity gate.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[cheshire.core :as json]
         '[fram.types :as t]
         '[framrpc :as wire])
(import '[java.security MessageDigest])

(def output-directory (io/file (or (first *command-line-args*) ".")))
(def profile-file
  (io/file (or (second *command-line-args*)
               "clients/cloudflare-do/capacity/corpus.json")))
(def profile (json/parse-string (slurp profile-file) true))
(def space "fram-wiki-capacity-v1")
(def request-id (atom 0))

(defn fail! [message]
  (binding [*out* *err*] (println (str "generate-wiki-corpus: " message)))
  (System/exit 2))

(when-not (= "fram-wiki-capacity-corpus/v1" (:schema profile))
  (fail! "unsupported corpus schema"))

(defn sha256-bytes [^bytes bytes]
  (.digest (MessageDigest/getInstance "SHA-256") bytes))

(defn hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn digest-text [text]
  (hex (sha256-bytes (.getBytes (str text) "UTF-8"))))

(defn article-id [index] (format "@wiki/article/%04d" index))
(defn revision-id [article revision]
  (format "@wiki/revision/%04d/%02d" article revision))

(defn body-text [article revision length]
  (let [blocks (map #(digest-text (str "wiki-capacity/" article "/" revision "/" %))
                    (range (inc (quot length 64))))]
    (subs (apply str blocks) 0 length)))

(defn fact-actions []
  (vec
   (mapcat
    (fn [article]
      (let [subject (article-id article)
            article-facts
            [(t/triple subject :wiki/type :wiki/article)
             (t/triple subject :wiki/title (format "Capacity article %04d" article))
             (t/triple subject :wiki/canonical true)]
            revision-facts
            (mapcat
             (fn [revision]
               (let [revision-subject (revision-id article revision)
                     links
                     (for [offset (range 1 (inc (:linksPerRevision profile)))]
                       (t/triple revision-subject :wiki/links-to
                                 (article-id
                                  (mod (+ article (* 17 revision) offset)
                                       (:articles profile)))))]
                 (concat
                  [(t/triple revision-subject :wiki/type :wiki/revision)
                   (t/triple revision-subject :wiki/revision-of subject)
                   (t/triple revision-subject :wiki/ordinal revision)
                   (t/triple revision-subject :wiki/body
                             (body-text article revision (:bodyBytes profile)))]
                  links)))
             (range (:revisionsPerArticle profile)))]
        (map #(wire/rpc-action! :rpc/assert % wire/rpc-subject-any)
             (concat article-facts revision-facts))))
    (range (:articles profile)))))

(defn encode-request [operation payload]
  (let [id (swap! request-id inc)]
    [id
     (wire/encode-rpc-frame-v1!
      (wire/rpc-request-frame
       id
       (wire/rpc-request! space operation nil nil nil payload)))]))

(defn emit! [manifest name entry operation payload]
  (let [[id bytes] (encode-request operation payload)
        filename (str name ".bin")
        digest (hex (sha256-bytes bytes))]
    (io/copy bytes (io/file output-directory filename))
    (swap! manifest conj
           (str entry " " filename " " (alength bytes) " " digest " " operation))
    id))

(.mkdirs output-directory)
(let [actions (fact-actions)
      expected (:expectedFacts profile)]
  (when-not (= expected (count actions))
    (fail! (str "profile expected " expected " facts, generated " (count actions))))
  (let [load-manifest (atom [])
        verify-manifest (atom [])
        batches (vec (partition-all (:actionsPerBatch profile) actions))]
    (doseq [[index batch] (map-indexed vector batches)]
      (emit! load-manifest (format "load-%03d" index) "t" :rpc/batch
             (wire/rpc-batch! (vec batch) nil)))
    (emit! verify-manifest "verify-status" "q" :rpc/status wire/rpc-unit)
    (let [title-subject (article-id 21)
          title-value "Capacity article 0021"
          title-request-id
          (emit! verify-manifest "verify-title" "q" :rpc/scan
                 (wire/rpc-triple-pattern! title-subject :wiki/title nil))
          expected-title-response
          (wire/encode-rpc-frame-v1!
           (wire/rpc-response-frame
            title-request-id
            (wire/rpc-response!
             space :rpc/scan (count batches) nil nil
             (wire/rpc-triples!
              [(t/triple title-subject :wiki/title title-value)]))))]
      (spit (io/file output-directory "expected-title-response.sha256")
            (str (hex (sha256-bytes expected-title-response)) "\n")))
    (spit (io/file output-directory "manifest-load.txt")
          (str (str/join "\n" @load-manifest) "\n"))
    (spit (io/file output-directory "manifest-verify.txt")
          (str (str/join "\n" @verify-manifest) "\n"))
    (spit (io/file output-directory "profile.json")
          (str (json/generate-string
                (assoc profile
                       :spaceId space
                       :batches (count batches)
                       :loadFrames (count batches)
                       :verifyFrames (count @verify-manifest))
                {:pretty true})
               "\n"))
    (println (str "generate-wiki-corpus: " (count actions) " facts in "
                  (count batches) " batches"))))
