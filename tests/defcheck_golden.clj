;; Deterministic behavior probe for the original defcheck_gate.clj.
;; Keep this driver implementation-neutral: it reaches the public surface and
;; the private, behavior-bearing helpers through vars so later delegated ports
;; are measured against the same observations.
(require '[clojure.string :as str])

(load-file (str (System/getProperty "user.dir") "/defcheck_gate.clj"))

(defn- v [s] (ns-resolve 'fram.defcheck s))
(defn- f [s] (deref (v s)))
(defn- emit [label value] (prn [label value]))

(def clean-src
  (str "(ns demo.core (:require [clojure.string :as str]))\n"
       "(defn helper [x] (str/upper-case x))\n"
       "(defn use-helper [x] (helper x))\n"))
(def unresolved-src
  (str clean-src "(defn broken [x] (hepler x))\n"))
(def arity-src
  (str clean-src "(defn broken [x] (helper x x))\n"))
(def typed-src
  "#lang beagle/clj\n(ns demo.typed)\n(defn f [x :- Int] :- Int (+ x 1))\n")

(emit :mode
      {:typed-source? ((f 'source-typed?) typed-src)
       :plain-source? ((f 'source-typed?) clean-src)
       :typed-routes-untyped? ((f 'untyped-mode?) typed-src)
       :plain-routes-untyped? ((f 'untyped-mode?) clean-src)})

(emit :diag
      ((f 'diag->error)
       "demo"
       {:kind "type-error"
        :name "broken"
        :error-line 17
        :message "beagle: expected String"
        :expected "String"
        :actual "Int"
        :nearest ["helper"]
        :error-code "return-type"
        :fix_plan {:suggestion "return a String"}}))

(emit :diag-parse
      ((f 'diag->error)
       "demo"
       {:kind "parse-error" :line 3 :message "beagle: bad form"}))

(emit :primary
      ((f 'pick-primary)
       "wanted"
       [{:at {:def "other"} :message "first"}
        {:at {:def "wanted"} :message "preferred"}]))

(emit :read
      (let [ok ((f 'read-forms) "(ns demo)\n(defn f [x] x)\n")
            bad ((f 'read-forms) "(ns demo)\n(defn f [x]")]
        {:ok-count (count (:forms ok))
         :ok-error (:read-error ok)
         :bad-form-count (count (:forms bad))
         :bad-error? (boolean (:read-error bad))}))

(emit :patterns
      (mapv (f 'pattern-locals)
            ['x
             '[a & rest :as all]
             '{:keys [a b] :strs [c] :syms [d] :as whole}
             nil]))

(emit :arities
      {:single ((f 'fn-arities) '([x y] (+ x y)))
       :multi ((f 'fn-arities) '(([x] x) ([x y & more] x)))
       :doc-attr ((f 'fn-arities) '("doc" {:private true} [x] x))})

(emit :defs
      ((f 'collect-defs)
       '((ns demo)
         (def value 1)
         (defn one [x] x)
         (defn many ([x] x) ([x y & more] x))
         (declare later)
         (defrecord Box [value])
         (defprotocol Store (fetch [this key])))))

(emit :ns-env
      ((f 'parse-ns-env)
       '((ns demo
           (:require [clojure.string :as str]
                     [clojure.set :refer [union]]
                     [clojure.walk :as-alias walk])
           (:import [java.time Duration] java.io.File))
         (require '[cheshire.core :as json]))))

(emit :nearest
      ((f 'nearest) 'hepler '#{helper helpers alpha beta}))

(doseq [[label src] [[:analyze-clean clean-src]
                     [:analyze-unresolved unresolved-src]
                     [:analyze-arity arity-src]
                     [:analyze-read-error "(ns demo)\n(defn broken [x]"]]]
  (emit label ((f 'analyze-untyped-module) "demo" src)))

(emit :check-def-clean
      (with-redefs-fn
        {(v 'ensure-sidecar!) (fn [] true)
         (v 'check-module-errors-any) (fn [_] [])}
        #((f 'check-def) "demo" "wanted")))

(emit :check-def-error
      (with-redefs-fn
        {(v 'ensure-sidecar!) (fn [] true)
         (v 'check-module-errors-any)
         (fn [_] [{:ok false :stage :type :at {:module "demo" :def "other"} :message "first"}
                  {:ok false :stage :type :at {:module "demo" :def "wanted"} :message "preferred"}])}
        #((f 'check-def) "demo" "wanted")))

(emit :check-def-infra
      (with-redefs-fn
        {(v 'ensure-sidecar!) (fn [] (throw (ex-info "offline" {})))}
        #((f 'check-def) "demo" "wanted")))

(emit :whole-tree-clean
      (with-redefs-fn
        {(v 'ensure-sidecar!) (fn [] true)
         (v 'prime-gwdir!) (fn [] "/tmp/gw")
         (v 'live-modules) (fn [] ["a" "b"])
         (v 'check-module-errors-any) (fn [_] [])}
        #((f 'whole-tree-check))))

(emit :whole-tree-error
      (with-redefs-fn
        {(v 'ensure-sidecar!) (fn [] true)
         (v 'prime-gwdir!) (fn [] "/tmp/gw")
         (v 'live-modules) (fn [] ["a" "b"])
         (v 'check-module-errors-any)
         (fn [module]
           (if (= module "b")
             [{:ok false :stage :type :at {:module module :def "bad"} :message "broken"}]
             []))}
        #((f 'whole-tree-check))))
