;; snapshot_reader_golden.clj — deterministic oracle for database.clj's pure readers.
;;
;; Capture runs before Cut A changes database.clj. Verification runs the same
;; original public names after database.clj delegates them to the Beagle reader
;; module. The setup may use writers to build a real store, but every printed
;; observation comes from the reader layer.
(require '[fram.store :as c] '[fram.schema :as s])
(load-file "database.clj")

(defn with-db [f]
  (let [path (.toString (java.nio.file.Files/createTempFile
                         "snapshot-reader-golden-" ".log"
                         (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f (new-database path))
      (finally
        (.delete (java.io.File. path))))))

(defn tx-of [db cid] (get (:tx-of @(:store db)) cid))

(defn set-tx-meta! [db cid k v]
  (swap! (:store db) assoc-in [:txs (tx-of db cid) k] v))

(defn literal-of [db cid]
  (c/literal (:store db) (:r (c/fact-of (:store db) cid))))

(defn live-group [db subj pred]
  (vec (live-cids-lp db
                     (s/resolve-name (:store db) subj)
                     (c/value-id (:store db) pred))))

(defn print-case [name value]
  (println name (pr-str value)))

(defn case-live-basics []
  (with-db
    (fn [db]
      (register-pred! db "status" "single" "literal")
      (let [r1 (commit! db "a" "T" "status" :assert "one" 0)
            r2 (commit! db "b" "T" "status" :assert "two" (:ok r1))
            te (s/resolve-name (:store db) "T")
            p (c/value-id (:store db) "status")]
        (print-case "live-cids-lp" (live-group db "T" "status"))
        (print-case "seq-of" [(seq-of db (:cid r1)) (seq-of db (:cid r2))])
        (print-case "base-version" (base-version db te p))
        (print-case "current-seq" (current-seq db))))))

(defn case-provenance []
  (with-db
    (fn [db]
      (let [r (commit! db "writer-z" "T" "p" :assert "v" nil)
            cid (:cid r)]
        (set-tx-meta! db cid :observed 17)
        (set-tx-meta! db cid :ts "2030-01-02T03:04:05Z")
        (print-case "agent-of" (agent-of db cid))
        (print-case "observed-of" (observed-of db cid))
        (print-case "ts-of" (ts-of db cid))
        (print-case "causal-key" (causal-key db cid))
        (print-case "missing-provenance"
                    [(agent-of db -1) (observed-of db -1) (ts-of db -1)
                     (causal-key db -1)])))))

(defn case-as-of []
  (with-db
    (fn [db]
      (register-pred! db "status" "single" "literal")
      (let [r1 (commit! db "a" "T" "status" :assert "one" 0)
            r2 (commit! db "b" "T" "status" :assert "two" (:ok r1))
            c1 (:cid r1)
            c2 (:cid r2)
            s1 (:ok r1)
            s2 (:ok r2)]
        (print-case "live-as-of-membership"
                    {:s1 [(contains? (live-as-of db s1) c1)
                          (contains? (live-as-of db s1) c2)]
                     :s2 [(contains? (live-as-of db s2) c1)
                          (contains? (live-as-of db s2) c2)]})
        (print-case "superseded-as-of"
                    {:s1 (contains? (superseded-as-of db s1) c1)
                     :s2 (contains? (superseded-as-of db s2) c1)})))))

(defn case-as-of-group []
  (with-db
    (fn [db]
      (register-pred! db "status" "single" "literal")
      (let [r1 (commit! db "a" "T" "status" :assert "one" 0)
            r2 (commit! db "b" "T" "status" :assert "two" (:ok r1))
            te (s/resolve-name (:store db) "T")
            p (c/value-id (:store db) "status")]
        (print-case "live-as-of-lp-s1"
                    (mapv #(literal-of db %) (live-as-of-lp db (:ok r1) te p)))
        (print-case "live-as-of-lp-s2"
                    (mapv #(literal-of db %) (live-as-of-lp db (:ok r2) te p)))))))

(defn withdrawn-corpus [db]
  (register-pred! db "tag" "multi" "literal")
  (let [r (commit! db "add" "T" "tag" :assert "red" nil)]
    (retract! db "remove" "T" "tag" "red" nil "obsolete")
    (:cid r)))

(defn case-withdrawal []
  (with-db
    (fn [db]
      (let [cid (withdrawn-corpus db)]
        (print-case "withdrawal-of" (withdrawal-of! db cid))
        (print-case "withdrawn?" [(withdrawn?! db cid) (withdrawn?! db -1)])))))

(defn case-live-members []
  (with-db
    (fn [db]
      (let [cid (withdrawn-corpus db)
            te (s/resolve-name (:store db) "T")
            p (c/value-id (:store db) "tag")]
        (print-case "remove-wins" (live-members! db te p :remove-wins))
        (print-case "add-wins" (mapv #(literal-of db %) (live-members! db te p :add-wins)))
        (print-case "default-policy" (live-members! db te p))
        (print-case "withdrawn-cid" cid)))))

(defn view-corpus [db]
  (let [base (commit! db "main" "T" "color" :assert "base" nil)
        b1 (commit-on-view! db "@view:b1" "b1" "T" "color" :assert "b1")
        b2 (commit-on-view! db "@view:b2" "b2" "T" "color" :assert "b2")]
    {:base (:cid base) :b1 (:cid b1) :b2 (:cid b2)
     :live (live-group db "T" "color")}))

(defn case-view-selects []
  (with-db
    (fn [db]
      (let [{:keys [b1 b2]} (view-corpus db)]
        (print-case "b1" (= #{b1} (view-selects! db "@view:b1")))
        (print-case "b2" (= #{b2} (view-selects! db "@view:b2")))
        (print-case "unknown" (view-selects! db "@view:unknown"))))))

(defn case-elect-main []
  (with-db
    (fn [db]
      (let [{:keys [base live]} (view-corpus db)]
        (print-case "main" [(= base (elect! db live))
                             (= base (elect! db nil live))
                             (= base (elect! db (vec (reverse live))))])
        (print-case "main-value" (literal-of db (elect! db live)))))))

(defn case-elect-views []
  (with-db
    (fn [db]
      (let [{:keys [base b1 b2 live]} (view-corpus db)]
        (print-case "views"
                    [(= b1 (elect! db "@view:b1" live))
                     (= b2 (elect! db "@view:b2" live))
                     (= base (elect! db "@view:unknown" live))])
        (print-case "values"
                    (mapv #(literal-of db (elect! db % live))
                          ["@view:b1" "@view:b2" "@view:unknown"]))))))

(defn case-elect-causal []
  (with-db
    (fn [db]
      (let [a (commit! db "z" "T" "color" :assert "first-cid" nil)
            b (commit! db "a" "T" "color" :assert "earlier-view" nil)
            live (live-group db "T" "color")]
        (set-tx-meta! db (:cid a) :observed 50)
        (set-tx-meta! db (:cid b) :observed 10)
        (print-case "causal-winner" (literal-of db (elect-causal! db live)))
        (print-case "causal-reversed" (= (elect-causal! db live)
                                          (elect-causal! db (vec (reverse live)))))
        (print-case "causal-keys" (mapv #(causal-key db %) live))))))

(defn case-empty []
  (with-db
    (fn [db]
      (print-case "empty-election" [(elect! db []) (elect-causal! db [])])
      (print-case "empty-view-election" [(elect! db "@view:x" [])
                                          (elect-causal! db "@view:x" [])])
      (print-case "unknown-withdrawal" [(withdrawal-of! db -1)
                                         (withdrawn?! db -1)])
      (print-case "unknown-view" (view-selects! db "@view:x")))))

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
        (println "unknown database reader golden case:" (pr-str case-name)))
      (System/exit 2))))
