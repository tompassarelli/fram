;; coord_reader_golden.clj — deterministic oracle for coord.clj's pure readers.
;;
;; Capture runs before Cut A changes coord.clj. Verification runs the same
;; original public names after coord.clj delegates them to the Beagle reader
;; module. The setup may use writers to build a real store, but every printed
;; observation comes from the reader layer.
(require '[fram.store :as c] '[fram.schema :as s])
(load-file "coord.clj")

(defn with-co [f]
  (let [path (.toString (java.nio.file.Files/createTempFile
                         "coord-reader-golden-" ".log"
                         (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f (new-coord path))
      (finally
        (.delete (java.io.File. path))))))

(defn tx-of [co cid] (get (:tx-of @(:store co)) cid))

(defn set-tx-meta! [co cid k v]
  (swap! (:store co) assoc-in [:txs (tx-of co cid) k] v))

(defn literal-of [co cid]
  (c/literal (:store co) (:r (c/fact-of (:store co) cid))))

(defn live-group [co subj pred]
  (vec (live-cids-lp co
                     (s/resolve-name (:store co) subj)
                     (c/value-id (:store co) pred))))

(defn print-case [name value]
  (println name (pr-str value)))

(defn case-live-basics []
  (with-co
    (fn [co]
      (register-pred! co "status" "single" "literal")
      (let [r1 (commit! co "a" "T" "status" :assert "one" 0)
            r2 (commit! co "b" "T" "status" :assert "two" (:ok r1))
            te (s/resolve-name (:store co) "T")
            p (c/value-id (:store co) "status")]
        (print-case "live-cids-lp" (live-group co "T" "status"))
        (print-case "seq-of" [(seq-of co (:cid r1)) (seq-of co (:cid r2))])
        (print-case "base-version" (base-version co te p))
        (print-case "current-seq" (current-seq co))))))

(defn case-provenance []
  (with-co
    (fn [co]
      (let [r (commit! co "writer-z" "T" "p" :assert "v" nil)
            cid (:cid r)]
        (set-tx-meta! co cid :observed 17)
        (set-tx-meta! co cid :ts "2030-01-02T03:04:05Z")
        (print-case "agent-of" (agent-of co cid))
        (print-case "observed-of" (observed-of co cid))
        (print-case "ts-of" (ts-of co cid))
        (print-case "causal-key" (causal-key co cid))
        (print-case "missing-provenance"
                    [(agent-of co -1) (observed-of co -1) (ts-of co -1)
                     (causal-key co -1)])))))

(defn case-as-of []
  (with-co
    (fn [co]
      (register-pred! co "status" "single" "literal")
      (let [r1 (commit! co "a" "T" "status" :assert "one" 0)
            r2 (commit! co "b" "T" "status" :assert "two" (:ok r1))
            c1 (:cid r1)
            c2 (:cid r2)
            s1 (:ok r1)
            s2 (:ok r2)]
        (print-case "live-as-of-membership"
                    {:s1 [(contains? (live-as-of co s1) c1)
                          (contains? (live-as-of co s1) c2)]
                     :s2 [(contains? (live-as-of co s2) c1)
                          (contains? (live-as-of co s2) c2)]})
        (print-case "superseded-as-of"
                    {:s1 (contains? (superseded-as-of co s1) c1)
                     :s2 (contains? (superseded-as-of co s2) c1)})))))

(defn case-as-of-group []
  (with-co
    (fn [co]
      (register-pred! co "status" "single" "literal")
      (let [r1 (commit! co "a" "T" "status" :assert "one" 0)
            r2 (commit! co "b" "T" "status" :assert "two" (:ok r1))
            te (s/resolve-name (:store co) "T")
            p (c/value-id (:store co) "status")]
        (print-case "live-as-of-lp-s1"
                    (mapv #(literal-of co %) (live-as-of-lp co (:ok r1) te p)))
        (print-case "live-as-of-lp-s2"
                    (mapv #(literal-of co %) (live-as-of-lp co (:ok r2) te p)))))))

(defn withdrawn-corpus [co]
  (register-pred! co "tag" "multi" "literal")
  (let [r (commit! co "add" "T" "tag" :assert "red" nil)]
    (retract! co "remove" "T" "tag" "red" nil "obsolete")
    (:cid r)))

(defn case-withdrawal []
  (with-co
    (fn [co]
      (let [cid (withdrawn-corpus co)]
        (print-case "withdrawal-of" (withdrawal-of co cid))
        (print-case "withdrawn?" [(withdrawn? co cid) (withdrawn? co -1)])))))

(defn case-live-members []
  (with-co
    (fn [co]
      (let [cid (withdrawn-corpus co)
            te (s/resolve-name (:store co) "T")
            p (c/value-id (:store co) "tag")]
        (print-case "remove-wins" (live-members co te p :remove-wins))
        (print-case "add-wins" (mapv #(literal-of co %) (live-members co te p :add-wins)))
        (print-case "default-policy" (live-members co te p))
        (print-case "withdrawn-cid" cid)))))

(defn view-corpus [co]
  (let [base (commit! co "main" "T" "color" :assert "base" nil)
        b1 (commit-on-view! co "@view:b1" "b1" "T" "color" :assert "b1")
        b2 (commit-on-view! co "@view:b2" "b2" "T" "color" :assert "b2")]
    {:base (:cid base) :b1 (:cid b1) :b2 (:cid b2)
     :live (live-group co "T" "color")}))

(defn case-view-selects []
  (with-co
    (fn [co]
      (let [{:keys [b1 b2]} (view-corpus co)]
        (print-case "b1" (= #{b1} (view-selects co "@view:b1")))
        (print-case "b2" (= #{b2} (view-selects co "@view:b2")))
        (print-case "unknown" (view-selects co "@view:unknown"))))))

(defn case-elect-main []
  (with-co
    (fn [co]
      (let [{:keys [base live]} (view-corpus co)]
        (print-case "main" [(= base (elect co live))
                             (= base (elect co nil live))
                             (= base (elect co (vec (reverse live))))])
        (print-case "main-value" (literal-of co (elect co live)))))))

(defn case-elect-views []
  (with-co
    (fn [co]
      (let [{:keys [base b1 b2 live]} (view-corpus co)]
        (print-case "views"
                    [(= b1 (elect co "@view:b1" live))
                     (= b2 (elect co "@view:b2" live))
                     (= base (elect co "@view:unknown" live))])
        (print-case "values"
                    (mapv #(literal-of co (elect co % live))
                          ["@view:b1" "@view:b2" "@view:unknown"]))))))

(defn case-elect-causal []
  (with-co
    (fn [co]
      (let [a (commit! co "z" "T" "color" :assert "first-cid" nil)
            b (commit! co "a" "T" "color" :assert "earlier-view" nil)
            live (live-group co "T" "color")]
        (set-tx-meta! co (:cid a) :observed 50)
        (set-tx-meta! co (:cid b) :observed 10)
        (print-case "causal-winner" (literal-of co (elect-causal co live)))
        (print-case "causal-reversed" (= (elect-causal co live)
                                          (elect-causal co (vec (reverse live)))))
        (print-case "causal-keys" (mapv #(causal-key co %) live))))))

(defn case-empty []
  (with-co
    (fn [co]
      (print-case "empty-election" [(elect co []) (elect-causal co [])])
      (print-case "empty-view-election" [(elect co "@view:x" [])
                                          (elect-causal co "@view:x" [])])
      (print-case "unknown-withdrawal" [(withdrawal-of co -1)
                                         (withdrawn? co -1)])
      (print-case "unknown-view" (view-selects co "@view:x")))))

(let [case-name (first *command-line-args*)
      cases {"live-basics" case-live-basics
             "provenance" case-provenance
             "as-of" case-as-of
             "as-of-group" case-as-of-group
             "withdrawal" case-withdrawal
             "live-members" case-live-members
             "view-selects" case-view-selects
             "elect-main" case-elect-main
             "elect-views" case-elect-views
             "elect-causal" case-elect-causal
             "empty" case-empty}]
  (if-let [f (get cases case-name)]
    (f)
    (do
      (binding [*out* *err*]
        (println "unknown coord reader golden case:" (pr-str case-name)))
      (System/exit 2))))
