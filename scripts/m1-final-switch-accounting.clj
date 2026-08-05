#!/usr/bin/env bb

(ns m1-final-switch-accounting
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

(def root
  (.getCanonicalPath
   (io/file (or (first *command-line-args*) "."))))

(def resolve-path (str root "/out/resolve.clj"))
(def link-9-revision "7396fc2")

(def reader-options
  {:read-cond :allow
   :features #{:bb :clj}})

(defn read-forms
  [source label]
  (let [reader (LineNumberingPushbackReader. (StringReader. source))
        eof (Object.)]
    (loop [forms []]
      (let [form (try
                   (read (assoc reader-options :eof eof) reader)
                   (catch Throwable t
                     (throw (ex-info (str "reader failed for " label
                                          " at line " (.getLineNumber reader))
                                     {:label label
                                      :line (.getLineNumber reader)}
                                     t))))]
        (if (identical? form eof)
          forms
          (recur (conj forms
                       {:form form
                        :line (or (:line (meta form))
                                  (.getLineNumber reader))
                        :end-line (.getLineNumber reader)})))))))

(defn nodes
  [form]
  (tree-seq coll? seq form))

(defn ns-form?
  [form]
  (and (seq? form) (= 'ns (first form))))

(defn def-form?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (str/starts-with? (name (first form)) "def")))

(defn def-name
  [form]
  (second form))

(defn require-vectors
  [forms]
  (->> forms
       (map :form)
       (filter ns-form?)
       first
       nodes
       (filter vector?)
       (filter #(symbol? (first %)))))

(defn alias-after
  [v marker]
  (some (fn [[a b]] (when (= a marker) b))
        (partition 2 1 v)))

(defn ported-aliases
  [forms]
  (->> (require-vectors forms)
       (keep (fn [v]
               (when (str/starts-with? (name (first v)) "resolve-")
                 (alias-after v :as))))
       set))

(defn qualified-namespaces
  [form]
  (->> (nodes form)
       (filter symbol?)
       (keep namespace)
       (map symbol)
       set))

(defn classification
  [ported form]
  (let [qualified (qualified-namespaces form)]
    (if (and (seq qualified)
             (set/subset? qualified ported))
      :pass-through
      :substantive)))

(defn source-accounting
  [source label]
  (let [forms (read-forms source label)
        ported (ported-aliases forms)
        defs (->> forms
                  (filter #(def-form? (:form %)))
                  (mapv (fn [{:keys [form line end-line]}]
                          (let [qualified (qualified-namespaces form)]
                            {:name (def-name form)
                             :head (first form)
                             :line line
                             :end-line end-line
                             :form-lines (inc (- end-line line))
                             :dynamic (true? (:dynamic (meta (def-name form))))
                             :qualified-namespaces (vec (sort qualified))
                             :classification (classification ported form)
                             :form form}))))]
    {:label label
     :source source
     :lines (count (str/split-lines source))
     :forms forms
     :top-level-forms (count forms)
     :ported-aliases ported
     :defs defs
     :pass-through (count (filter #(= :pass-through (:classification %)) defs))
     :substantive (count (filter #(= :substantive (:classification %)) defs))}))

(def corpus-names
  '#{ctx tx SUP *resolve-walk?* *corpus-scope* *corpus-cache* file->ents
     load-edn Vp KIND REFERS BOUND FIXED QUAL CTOR ACC *view*
     n-resolved n-unresolved n-xmod n-type n-comment n-forms-walked
     walked-modules *xresolve* *tresolve* *aresolve*
     module-defs forms-of ns-form module-name parse-require module-exports
     module-types module-accessors srcs file-modframe file-typeframe
     file-accessors def-binding global-exports global-type-exports
     global-accessor-exports make-xresolve walk-corpus install-corpus-tables!
     install-warm-corpus! with-corpus-state! module-export-set module-imports
     import-graph module-has-macro?})

(def extract-emit-names
  '#{*deleted-forms* *deleted-subtree* wrapper-of structural-kids descendants
     form-for-victim emit-env extract-file! *resolve-out* out-path
     *project-srcs* emit-srcs *capture-only?* author-emit-scoped!
     scope-match? scope->srcs})

(def cli-main-names
  '#{mode -main})

(defn group-of
  [name]
  (cond
    (corpus-names name) :corpus-store-frame-residue
    (extract-emit-names name) :extract-emit-residue
    (cli-main-names name) :cli-main
    :else :other))

(def excluded-prefixes
  #{"out/" ".git/" "node_modules/" "target/" "docs/private/"})

(def source-extensions
  #{".clj" ".bclj" ".bcljs" ".bjs"})

(defn relative-path
  [file]
  (-> (.toPath (io/file root))
      (.relativize (.toPath file))
      str
      (str/replace "\\" "/")))

(defn source-file?
  [file]
  (let [relative (relative-path file)]
    (and (.isFile file)
         (some #(str/ends-with? relative %) source-extensions)
         (not-any? #(str/starts-with? relative %) excluded-prefixes)
         (not= relative "resolve.clj"))))

(defn repo-source-files
  []
  (->> (file-seq (io/file root))
       (filter source-file?)
       (sort-by relative-path)))

(defn resolve-aliases
  [forms]
  (let [ns-aliases
        (keep (fn [v]
                (when (= 'resolve (first v))
                  (alias-after v :as)))
              (require-vectors forms))
        beagle-aliases
        (keep (fn [form]
                (when (and (seq? form)
                           (= 'require (first form))
                           (= 'resolve (second form)))
                  (alias-after form :as)))
              (map :form forms))]
    (into #{'resolve} (concat ns-aliases beagle-aliases))))

(defn qualified-ref-counts
  [forms aliases]
  (->> forms
       (mapcat #(nodes (:form %)))
       (filter symbol?)
       (filter #(and (namespace %)
                     (aliases (symbol (namespace %)))))
       (map (comp symbol name))
       frequencies))

(defn binding-targets
  [form aliases]
  (->> (nodes form)
       (filter seq?)
       (filter #(and (symbol? (first %))
                     (= "binding" (name (first %)))
                     (vector? (second %))))
       (mapcat #(take-nth 2 (second %)))
       (keep (fn [sym]
               (when (and (symbol? sym)
                          (namespace sym)
                          (aliases (symbol (namespace sym))))
                 (symbol (name sym)))))
       frequencies))

(defn parse-repo-file
  [file]
  (let [relative (relative-path file)]
    (try
      (let [source (slurp file)
            candidate? (boolean (re-find #"(?:resolve/|(?:\[|\(|\s)resolve(?:\s|\]))"
                                         source))]
        (if-not candidate?
          {:file relative
           :forms []
           :aliases #{'resolve}
           :qualified-refs {}
           :binding-targets {}}
          (let [source (if (str/starts-with? source "#lang beagle")
                         (str/replace-first source #"#lang beagle[^\n]*" ";; #lang beagle")
                         source)
                forms (read-forms source relative)
                aliases (resolve-aliases forms)]
            {:file relative
             :forms forms
             :aliases aliases
             :qualified-refs (qualified-ref-counts forms aliases)
             :binding-targets (apply merge-with +
                                     (map #(binding-targets (:form %) aliases)
                                          forms))})))
      (catch Throwable t
        {:file relative
         :error (ex-message t)}))))

(defn external-consumers
  [parsed-files substantive-names]
  (into
   (sorted-map)
   (for [name (sort substantive-names)]
     [name
      (->> parsed-files
           (keep (fn [{:keys [file qualified-refs]}]
                   (when-let [n (get qualified-refs name)]
                     {:file file :qualified-refs n})))
           vec)])))

(defn symbols-in
  [form]
  (->> (nodes form)
       (filter symbol?)
       set))

(defn internal-consumers
  [defs substantive-names]
  (into
   (sorted-map)
   (for [target (sort substantive-names)]
     [target
      (->> defs
           (remove #(= target (:name %)))
           (keep (fn [{:keys [name form]}]
                   (when ((symbols-in form) target) name)))
           distinct
           sort
           vec)])))

(defn dynamic-inventory
  [defs parsed-files]
  (let [dynamic (->> defs (filter :dynamic) (map :name) set)
        binder-files (filter #(or (= "server.clj" (:file %))
                                  (and (str/starts-with? (:file %) "tests/")
                                       (str/ends-with? (:file %) ".clj")))
                             parsed-files)]
    (mapv
     (fn [name]
       (let [sites (->> binder-files
                        (keep (fn [{:keys [file qualified-refs binding-targets]}]
                                (let [refs (get qualified-refs name 0)
                                      binds (get binding-targets name 0)]
                                  (when (pos? (+ refs binds))
                                    {:file file
                                     :qualified-refs refs
                                     :binding-sites binds}))))
                        vec)]
         {:name name
          :qualified-refs (reduce + 0 (map :qualified-refs sites))
          :binding-sites (reduce + 0 (map :binding-sites sites))
          :sites sites}))
     (sort dynamic))))

(defn group-summary
  [substantive external internal]
  (->> substantive
       (group-by #(group-of (:name %)))
       (map (fn [[group defs]]
              {:group group
               :defs (count defs)
               :form-lines (count
                            (into #{}
                                  (mapcat #(range (:line %) (inc (:end-line %)))
                                          defs)))
               :items
               (mapv (fn [{:keys [name line end-line form-lines
                                  qualified-namespaces]}]
                       {:name name
                        :line line
                        :end-line end-line
                        :form-lines form-lines
                        :qualified-namespaces qualified-namespaces
                        :internal-consumers (get internal name)
                        :external-consumers (get external name)})
                     (sort-by :line defs))}))
       (sort-by :group)
       vec))

(def current
  (source-accounting (slurp resolve-path) "out/resolve.clj"))

(def baseline
  (let [{:keys [exit out err]}
        (sh/sh "git" "show" (str link-9-revision ":resolve.clj") :dir root)]
    (when-not (zero? exit)
      (throw (ex-info "cannot read LINK 9 baseline"
                      {:revision link-9-revision :stderr err :exit exit})))
    (source-accounting out (str link-9-revision ":resolve.clj"))))

(def parsed-files
  (mapv parse-repo-file (repo-source-files)))

(def substantive
  (filterv #(= :substantive (:classification %)) (:defs current)))

(def substantive-names
  (set (map :name substantive)))

(def external
  (external-consumers parsed-files substantive-names))

(def internal
  (internal-consumers (:defs current) substantive-names))

(def result
  (let [dynamic-vars (dynamic-inventory (:defs current) parsed-files)]
    {:method
   {:reader "clojure.core/read over LineNumberingPushbackReader"
    :reader-options reader-options
    :pass-through-rule
    "a def has >=1 qualified symbol and every qualified namespace is a resolve-* require alias"
    :substantive-rule
    "zero qualified symbols, or any qualified namespace outside the ported resolve-* aliases"
    :consumer-rule
    "qualified resolve/<name> or a require alias such as rsv/<name>; resolve.clj internal refs are syntactic symbol refs"
    :binder-scope
    ["server.clj" "tests/**/*.clj"]}
   :baseline
   {:revision link-9-revision
    :lines (:lines baseline)
    :top-level-forms (:top-level-forms baseline)
    :defs (count (:defs baseline))
    :pass-through (:pass-through baseline)
    :substantive (:substantive baseline)}
   :current
   {:revision (str/trim (:out (sh/sh "git" "rev-parse" "HEAD" :dir root)))
    :lines (:lines current)
    :top-level-forms (:top-level-forms current)
    :defs (count (:defs current))
    :pass-through (:pass-through current)
    :substantive (:substantive current)
    :ported-aliases (vec (sort (:ported-aliases current)))}
   :trajectory
   {:lines (- (:lines current) (:lines baseline))
    :defs (- (count (:defs current)) (count (:defs baseline)))
    :pass-through (- (:pass-through current) (:pass-through baseline))
    :substantive (- (:substantive current) (:substantive baseline))}
   :groups (group-summary substantive external internal)
   :dynamic-summary
   {:vars (count dynamic-vars)
    :externally-bound-vars (count (filter #(pos? (:binding-sites %)) dynamic-vars))
    :qualified-refs (reduce + 0 (map :qualified-refs dynamic-vars))
    :binding-sites (reduce + 0 (map :binding-sites dynamic-vars))}
   :dynamic-vars dynamic-vars
   :parse-errors
   (->> parsed-files
        (keep #(when (:error %) (select-keys % [:file :error])))
        vec)}))

(prn result)
