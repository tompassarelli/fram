;; ============================================================================
;; cnf_comment_authoring_receipt.clj — #30: author a LINE comment THROUGH the graph
;;   bb -cp out tests/cnf_comment_authoring_receipt.clj
;;
;; The first incremental authoring of a comment NODE. Previously comments entered the
;; code graph only via whole-file text->graph ingest; the wired authoring verbs
;; (set-body/upsert-form/insert-form/rename) could not mint one. insert-comment mints a
;; kind="comment" node + a `text` seg + a `commentN` edge on the anchor FORM, then the
;; module renders with the comment as a leading line above the anchor def — proving the
;; verb produces a render-correct comment from the graph alone (no hand text edit).
;;
;; RED (implicit): the baseline render of `schema` has no such note above `lookup`.
;; GREEN: after insert-comment, the note renders DIRECTLY ABOVE `(defn lookup ...)`.
;;
;; SAFE: isolated /tmp COPY of .fram/code.log, --no-commit (renders only, commits
;; NOTHING). Never the canonical log, never a coordinator/port.
;; ============================================================================
(require '[babashka.process :as proc] '[clojure.java.io :as io] '[clojure.string :as str])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no .fram/code.log") (System/exit 0))

(def tmp (str "/tmp/cmt-receipt-" (System/nanoTime) ".log"))
(io/copy (io/file code-log) (io/file tmp))
(def out (str "/tmp/cmt-receipt-schema-" (System/nanoTime) ".bclj"))
(def note ";; RECEIPT-NOTE authored via the graph insert-comment verb (not a hand text edit)")

(def r (proc/sh {:dir root :out :string :err :string :extra-env (into {} (System/getenv))}
                "bb" "-cp" "out" "bin/fram-edit-code" "insert-comment" "schema"
                "--after" "lookup" "--text" note "--no-commit" "--out" out "--log" tmp))
(println (str/trim (str (:out r) (:err r))))

(def rendered (when (.exists (io/file out)) (slurp out)))
(def lines (vec (when rendered (str/split-lines rendered))))
(def note-idx (first (keep-indexed #(when (str/includes? %2 "RECEIPT-NOTE") %1) lines)))
(def lookup-idx (first (keep-indexed #(when (re-find #"\(defn lookup " %2) %1) lines)))

(println "\n=== VERDICT (#30 — comment authored through the graph) ===")
(if (and note-idx lookup-idx (= (inc note-idx) lookup-idx))
  (do (println (format "PASS — insert-comment minted a comment node via the graph; it renders as a LEADING line DIRECTLY ABOVE (defn lookup ...) (note line %d, def line %d). Render-correct comment from the graph alone." note-idx lookup-idx))
      (System/exit 0))
  (do (println (format "FAIL — note-idx=%s lookup-idx=%s (want the note directly above the def)" note-idx lookup-idx))
      (System/exit 1)))
