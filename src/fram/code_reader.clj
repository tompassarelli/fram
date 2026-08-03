(ns fram.code-reader
  "Version-pinned code snapshots and Beagle projections over FRAMRPC v1."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [coord-daemon-wire :as wire]
            [fram.rt :as rt]
            [fram.types :as t]))

(def ^:private default-page-limit 128)
(def ^:private maximum-page-limit 200)

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :type :invalid-code-snapshot))))

(defn- code-page-limit! [value]
  (let [limit (or value default-page-limit)]
    (when-not (and (integer? limit) (<= 1 limit maximum-page-limit))
      (invalid! "code snapshot page limit must be between 1 and 200"
                {:page-limit limit}))
    limit))

(defn- response-triples! [response]
  (let [[values] (rt/rpc-record-fields! (rt/native-payload response)
                                        :rpc/triples 1)
        triples (rt/rpc-list-values! values)]
    (when-not (every? t/triple? triples)
      (invalid! "code snapshot scan returned a non-Triple value" {}))
    triples))

(defn- drain-corpus! [port space page-limit]
  (let [pattern (wire/rpc-triple-pattern! nil nil nil)]
    (loop [cursor nil
           seen #{}
           version nil
           pages 0
           triples []]
      (let [response (-> (rt/native-call!
                          port space :rpc/scan pattern nil
                          (wire/rpc-page-request! page-limit cursor) nil)
                         rt/require-native-success!)
            served (t/rpcresponse-served-version response)
            page (t/rpcresponse-page response)]
        (when-not page
          (invalid! "code snapshot scan returned no page metadata"
                    {:served-version served}))
        (when (and version (not= version served))
          (invalid! "code snapshot pages came from different versions"
                    {:expected-version version :served-version served}))
        (let [all-triples (into triples (response-triples! response))
              next-cursor (t/rpc-page-response-cursor-value page)
              next-version (or version served)
              next-pages (inc pages)]
          (if (t/rpcpageresponse-done page)
            {:version next-version
             :pages next-pages
             :triples all-triples}
            (do
              (when (nil? next-cursor)
                (invalid! "unfinished code snapshot page has no cursor"
                          {:served-version served :page next-pages}))
              (when (contains? seen next-cursor)
                (invalid! "code snapshot cursor repeated before completion"
                          {:served-version served :page next-pages}))
              (recur next-cursor (conj seen next-cursor) next-version
                     next-pages all-triples))))))))

(defn read-corpus-snapshot!
  "Drain one pinned whole-corpus scan for graph-control preflight."
  ([port space]
   (read-corpus-snapshot! port space nil))
  ([port space page-limit]
   (drain-corpus! port space (code-page-limit! page-limit))))

(defn- module-node-suffix [module value]
  (when (string? value)
    (let [prefix (str "@" module "#")]
      (when (str/starts-with? value prefix)
        (subs value (count prefix))))))

(defn- module-subject? [module value]
  (let [suffix (module-node-suffix module value)]
    (boolean
     (and suffix
          (or (= "root" suffix)
              (re-matches #"[0-9]+" suffix))))))

(defn- registered-root! [checkout-root module triples]
  (let [subject (str "@" module "#root")
        paths (->> triples
                   (keep (fn [triple]
                           (when (and (= subject (t/triple-slot0 triple))
                                      (= "file" (t/triple-slot1 triple)))
                             (t/triple-slot2 triple))))
                   vec)]
    (when-not (= 1 (count paths))
      (invalid! "module must have exactly one registered root path"
                {:module module :root-facts (count paths)}))
    (let [path (first paths)]
      (when-not (string? path)
        (invalid! "module root path must be a String"
                  {:module module :root path}))
      (.getCanonicalPath
       (let [file (io/file path)]
         (if (.isAbsolute file)
           file
           (io/file checkout-root path)))))))

(defn read-module-snapshot!
  "Drain one pinned whole-corpus scan and return one exact module snapshot.
   Every successful result carries the drained {:version :root} citation."
  ([port space checkout-root module]
   (read-module-snapshot! port space checkout-root module nil))
  ([port space checkout-root module page-limit]
   (when (str/blank? module)
     (invalid! "module name must be nonempty" {:module module}))
   (let [{:keys [version pages triples]}
         (read-corpus-snapshot! port space page-limit)
         module-triples (filterv #(module-subject? module (t/triple-slot0 %))
                                 triples)
         root (registered-root! checkout-root module module-triples)]
     {:module module
      :snapshot {:version version :root root}
      :pages pages
      :triples module-triples})))

(defn- local-node-id [module value]
  (when-let [suffix (module-node-suffix module value)]
    (when (re-matches #"[0-9]+" suffix)
      (Long/parseLong suffix))))

(defn project-module-edn
  "Project a native module snapshot into Beagle facts-roundtrip EDN."
  [{:keys [module snapshot triples]}]
  (when-not (and (string? module)
                 (integer? (:version snapshot))
                 (string? (:root snapshot)))
    (invalid! "module projection requires a cited version and root"
              {:module module :snapshot snapshot}))
  (let [rows
        (->> triples
             (keep (fn [triple]
                     (when-let [subject (local-node-id module
                                                       (t/triple-slot0 triple))]
                       [subject
                        (t/triple-slot1 triple)
                        (or (local-node-id module (t/triple-slot2 triple))
                            (t/triple-slot2 triple))])))
             (sort-by (fn [[subject predicate object]]
                        [subject (str predicate) (pr-str object)])))]
    (str "@file " (:root snapshot) "\n"
         (str/join "\n" (map pr-str rows))
         "\n")))

(defn render-module!
  "Render one cited module snapshot to canonical Beagle source text."
  [beagle snapshot]
  (let [path (java.nio.file.Files/createTempFile
              "fram-code-snapshot-" ".edn"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (str path) (project-module-edn snapshot))
      (let [result (shell/sh beagle "facts-roundtrip" "--render" (str path))]
        (when-not (zero? (:exit result))
          (throw (ex-info "Beagle graph projection failed"
                          {:type :beagle-render-failed
                           :module (:module snapshot)
                           :snapshot (:snapshot snapshot)
                           :exit (:exit result)
                           :stderr (:err result)})))
        {:module (:module snapshot)
         :snapshot (:snapshot snapshot)
         :source (:out result)})
      (finally
        (java.nio.file.Files/deleteIfExists path)))))
