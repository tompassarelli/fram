(ns resolve-corpus
  (:require [clojure.string :as str]
            [fram.types :as t]
            [fram.store :as c]
            [resolve-read :as rr]
            [resolve-modules :as rm]
            [resolve-walk :as rw]))

(def MODULE-NAME-RE (re-pattern "@([^#]+)#\\d+"))

(def SLASH-RE (re-pattern "/"))

(defrecord CorpusState [ctx view tx KIND BOUND REFERS file-ents corpus-cache corpus-scope resolve-walk? srcs install-srcs install-tables install-warm run-all run-over profile])

(defn corpusstate-ctx [r] (:ctx r))

(defn corpusstate-view [r] (:view r))

(defn corpusstate-tx [r] (:tx r))

(defn corpusstate-KIND [r] (:KIND r))

(defn corpusstate-BOUND [r] (:BOUND r))

(defn corpusstate-REFERS [r] (:REFERS r))

(defn corpusstate-file-ents [r] (:file-ents r))

(defn corpusstate-corpus-cache [r] (:corpus-cache r))

(defn corpusstate-corpus-scope [r] (:corpus-scope r))

(defn corpusstate-resolve-walk? [r] (:resolve-walk? r))

(defn corpusstate-srcs [r] (:srcs r))

(defn corpusstate-install-srcs [r] (:install-srcs r))

(defn corpusstate-install-tables [r] (:install-tables r))

(defn corpusstate-install-warm [r] (:install-warm r))

(defn corpusstate-run-all [r] (:run-all r))

(defn corpusstate-run-over [r] (:run-over r))

(defn corpusstate-profile [r] (:profile r))

(defrecord CorpusHost [with-state load-edn state])

(defn corpushost-with-state [r] (:with-state r))

(defn corpushost-load-edn [r] (:load-edn r))

(defn corpushost-state [r] (:state r))

(defn walk-corpus [srcs modframe typeframe accessors ents]
  (rw/->Corpus srcs modframe typeframe accessors ents))

(defn name->module [nm]
  (if (string? nm) (let [hit (re-matches MODULE-NAME-RE nm)]
  (if (some? hit) (second hit) nil)) nil))

(defn make-xresolve [ctx view ents-of exports type-exports accessor-exports src]
  (let [{:keys [refer as rename]} (rm/parse-require ctx view (vec (get ents-of src [])))
   xport (fn [m n] (or (get-in exports [m n]) (get-in type-exports [m n])))
   xacc (fn [m n] (get-in accessor-exports [m n]))]
  (fn [nm] (cond
  (nil? nm) nil
  (get refer nm) (let [m (get refer nm)]
  (let [target (xport m nm)]
  (if target {:target target :mode :tracking} (let [accessor (xacc m nm)]
  (if accessor (do
  {:target (first accessor) :mode :tracking :accessor (second accessor)}))))))
  (get rename nm) (let [[m source-name] (get rename nm)]
  {:target (xport m source-name) :mode :fixed})
  (str/includes? nm "/") (let [[alias public-name] (str/split nm SLASH-RE 2)
   module (or (get as alias) (if (some (fn [table] (contains? table alias)) [exports type-exports accessor-exports]) (do
  alias)))]
  (if module (do
  (let [target (xport module public-name)]
  (if target {:target target :mode :qual :alias alias} (let [accessor (xacc module public-name)]
  (if accessor (do
  {:target (first accessor) :mode :qual :alias alias :accessor (second accessor)}))))))))
  :else nil))))

(defn module-export-set [ctx view ents-of src]
  (let [ents (vec (get ents-of src []))
   exports (rm/module-exports ctx view ents)
   value-exports (if (seq exports) exports (rm/module-defs ctx view ents))]
  (into #{} (concat (keys value-exports) (keys (rm/module-types ctx view ents)) (keys (rm/module-accessors ctx view ents))))))

(defn module-imports [ctx view ents-of src]
  (let [{:keys [refer as rename]} (rm/parse-require ctx view (vec (get ents-of src [])))]
  (into #{} (concat (vals refer) (vals as) (map first (vals rename))))))

(defn import-graph [ctx view ents-of srcs]
  (into {} (map (fn [src] (let [ents (vec (get ents-of src []))]
  [(rm/module-name ctx view ents) (module-imports ctx view ents-of src)])) (filter (fn [src] (some? (rm/module-name ctx view (vec (get ents-of src []))))) srcs))))

(defn ^Boolean module-has-macro? [ctx view ents-of src]
  (let [ents (vec (get ents-of src []))]
  (boolean (some (fn [form] (let [def-form (rm/unwrap-def ctx view form)
   head (first (rr/ordered-children ctx def-form))]
  (= "defmacro" (rr/sym-val ctx view head)))) (rm/forms-of ctx view ents)))))

(defn lift-bound-to-refers! [ctx tx KIND BOUND REFERS]
  (if (some? BOUND) (do
  (doseq [cid (c/by-p ctx BOUND)]
  (let [fact (c/fact-of ctx cid)
   leaf (:l fact)
   target (:r fact)]
  (if (and (integer? target) (rr/live-node? ctx KIND target) (empty? (c/by-lp ctx leaf REFERS))) (do
  (c/fact! ctx leaf REFERS target tx))))))))

(defn corpus-from-store! [^CorpusState state]
  (let [t0 (System/nanoTime)
   groups (rw/warm-groups (:ctx state) (:corpus-cache state) name->module)
   t-groups (System/nanoTime)
   tables (rw/scoped-corpus-tables (:ctx state) (:view state) groups (:corpus-scope state))
   install (:install-warm state)]
  (do
  (install groups tables)
  (if (= "1" (System/getenv "FRAM_PROF")) (do
  (let [profile (:profile state)]
  (profile (format "  corpus-from-store!: groups=%.1fms frames+exports=%.1fms cached=%s nsrcs=%d scoped=%s" (/ (- t-groups t0) 1000000.0) (/ (- (System/nanoTime) t-groups) 1000000.0) (some? (:corpus-cache state)) (count (:srcs tables)) (boolean (:corpus-scope state)))))))
  tables)))

(defn resolve-edn! [^CorpusHost host edn-paths body]
  (let [store (c/new-store)
   tx (c/begin-tx! store "resolve")
   sup (c/value! store "supersedes")
   with-state (:with-state host)]
  (do
  (c/set-supersedes-pred! store sup)
  (with-state store tx sup (fn [] (let [state-fn (:state host)
   load (:load-edn host)
   loaded (mapv load edn-paths)
   state (state-fn)
   install-srcs (:install-srcs state)
   install-tables (:install-tables state)]
  (do
  (install-srcs loaded)
  (install-tables (rw/corpus-tables (:ctx state) (:view state) loaded @ (:file-ents state)))
  (let [run-all (:run-all state)]
  (run-all))
  (body))))))))

(defn resolve-warm-store! [^CorpusHost host store body]
  (let [tx (c/begin-tx! store "resolve-warm")
   sup (or (c/value-id store "supersedes") (c/value! store "supersedes"))
   with-state (:with-state host)]
  (do
  (c/set-supersedes-pred! store sup)
  (with-state store tx sup (fn [] (let [state-fn (:state host)
   state (state-fn)]
  (do
  (corpus-from-store! state)
  (if (:resolve-walk? state) (do
  (let [run-all (:run-all state)]
  (run-all))
  (lift-bound-to-refers! (:ctx state) (:tx state) (:KIND state) (:BOUND state) (:REFERS state))))
  (body))))))))

(defn resolve-modules! [^CorpusHost host store module-set body]
  (let [tx (c/begin-tx! store "resolve-scoped")
   sup (or (c/value-id store "supersedes") (c/value! store "supersedes"))
   with-state (:with-state host)]
  (do
  (c/set-supersedes-pred! store sup)
  (with-state store tx sup (fn [] (let [state-fn (:state host)
   state (state-fn)]
  (do
  (corpus-from-store! state)
  (let [fresh (state-fn)
   run-over (:run-over fresh)]
  (run-over (filter module-set (:srcs fresh)))
  (lift-bound-to-refers! (:ctx fresh) (:tx fresh) (:KIND fresh) (:BOUND fresh) (:REFERS fresh)))
  (body))))))))
