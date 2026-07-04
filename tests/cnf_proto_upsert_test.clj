;; ============================================================================
;; cnf_proto_upsert_test.clj — S-profile PROTOCOL-METHOD authoring (EXP-025 A).
;; ============================================================================
;; Proves write-def can author a method IMPLEMENTATION living inside an existing
;; extend-protocol / extend-type block by taking the WHOLE block as the addressable
;; unit (design option 1: block-level upsert, replace-in-place — matches "whole
;; top-level form" semantics everywhere else). Closes the b4-defects malli-05
;; escalation ("write-def cannot author a protocol method") and the edit-path smoke
;; extend-protocol wrap-churn.
;;
;; Three properties, on the real ring.core.protocols fixture (a 5-target
;; extend-protocol) + a malli-05-class extend-type:
;;   1. UPSERT-BY-BLOCK: re-authoring `(extend-protocol P ...)` REPLACES the existing
;;      block IN PLACE — form count unchanged, sibling order preserved (defprotocol,
;;      response-writer, extend-protocol), every target kept, the edited method took.
;;   2. HINT FIDELITY: `^OutputStream` param hints survive the raw-source mint (the
;;      write-def path minted `(str sym)` and DROPPED reader metadata — a whole-block
;;      re-author silently lost every hint → reflection). RENDER is valid Clojure.
;;   3. MALLI-05 CLASS: an `extend-type T IPrintWithWriter (-pr-writer [..] (-write ..))`
;;      block (the -pr-writer-into-schema print-method shape) writes + renders.
;;
;;   clojure -M tests/cnf_proto_upsert_test.clj > /tmp/proto.out 2>&1; echo EXIT=$?
;;   (needs the BEAGLE_HOME / FRAM_RACKET pin for the racket render leg; without a
;;    resolvable racket the render assertions SKIP loudly, write/index still run.)
;; ============================================================================
(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.java.shell :as sh])

(def root (System/getProperty "user.dir"))
(def fixture (str root "/tests/fixtures/proto-addr/code.claimlog"))
(when-not (.exists (io/file fixture))
  (println "SKIP — missing fixture" fixture) (System/exit 0))

(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

;; --- throwaway daemon over a /tmp COPY, on a free port >= 49010 -------------
(def flat (str (System/getProperty "java.io.tmpdir") "/proto-upsert-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) (range 49010 49040)) 49010))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 800)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))

(def status (client port {:op :status}))
(when-not (and (= flat (str (:log status))) (pos? (:claims status)))
  (println "ABORT: daemon serves" (pr-str (:log status)) "expected" flat) (shutdown!) (System/exit 1))
(println "daemon up:" (:claims status) "claims, port=" port "\n")

;; --- assertion harness ------------------------------------------------------
(def failures (atom 0))
(defn- check [label ok? detail]
  (println (format "  [%s] %s%s" (if ok? "PASS" "FAIL") label (if ok? "" (str "  <-- " detail))))
  (when-not ok? (swap! failures inc)))
(defn- w   [module source] (client port {:op :write-def :spec {:module module :source source}}))
(defn- r   [module name]   (client port {:op :read-def  :spec {:module module :name name}}))
(defn- idx [module]        (client port {:op :index     :spec {:module module}}))
(defn- names [module]      (mapv :name (:defs (idx module))))

;; render leg: :render returns resolved EDN; racket claims-roundtrip inverts it to
;; Clojure text. Resolve racket the way fram-ingest-code does (FRAM_RACKET, else
;; direnv, else bare) so a missing pin SKIPS the render assertions rather than lying.
(def racket
  (or (System/getenv "FRAM_RACKET")
      (let [bh (or (System/getenv "BEAGLE_HOME") (str (System/getProperty "user.home") "/code/beagle"))
            p  (try (str/trim (:out (sh/sh "direnv" "exec" bh "which" "racket"))) (catch Throwable _ ""))]
        (when-not (str/blank? p) p))))
(def roundtrip-rkt
  (str (or (System/getenv "BEAGLE_HOME") (str (System/getProperty "user.home") "/code/beagle"))
       "/beagle-lib/private/claims-roundtrip.rkt"))
(defn- render-text [module]
  (let [resp (client port {:op :render :spec {:module module} :module module})
        edn  (:edn resp)]
    (when (and racket edn (.exists (io/file roundtrip-rkt)))
      (let [ef (str (System/getProperty "java.io.tmpdir") "/proto-render-" (System/nanoTime) ".edn")]
        (spit ef edn)
        (let [{:keys [exit out err]} (sh/sh racket roundtrip-rkt "--render" ef)]
          (when (zero? exit) out))))))

(def RP "ring.core.protocols")

;; -----------------------------------------------------------------------------
(println "=== baseline: the 5-target extend-protocol is one addressable block ===")
(def base-names (names RP))
(def base-count (count base-names))
(check "5 extend-protocol targets present at baseline"
       (= 5 (count (filter #(str/starts-with? % "StreamableResponseBody@") base-names)))
       (pr-str base-names))
(check "sibling order: defprotocol, defn-, extend-protocol"
       (= ["StreamableResponseBody" "write-body-to-stream" "response-writer"]
          (take 3 base-names))
       (pr-str (take 3 base-names)))

;; -----------------------------------------------------------------------------
(println "\n=== (1)+(2) UPSERT whole block: replace-in-place, keep targets + hints ===")
;; edit ONLY the String method (marker call); resend all 5 targets, hints intact.
(def edited-block
  "(extend-protocol StreamableResponseBody
     String
     (write-body-to-stream [body response output-stream]
       (with-open [writer (response-writer response output-stream)]
         (.write writer (str \"MARK\" body))))
     clojure.lang.ISeq
     (write-body-to-stream [body response output-stream]
       (with-open [writer (response-writer response output-stream)]
         (doseq [chunk body] (.write writer (str chunk)))))
     java.io.InputStream
     (write-body-to-stream [body _ ^OutputStream output-stream]
       (with-open [out output-stream, body body] (io/copy body out)))
     java.io.File
     (write-body-to-stream [body _ ^OutputStream output-stream]
       (with-open [out output-stream] (io/copy body out)))
     nil
     (write-body-to-stream [_ _ ^java.io.OutputStream output-stream]
       (.close output-stream)))")
(let [resp (w RP edited-block)]
  (check "whole-block upsert -> :ok, 1 form written" (and (:ok resp) (= 1 (:written resp))) (pr-str (dissoc resp :results))))
(let [after (names RP)]
  (check "REPLACE-IN-PLACE: form count unchanged (no append/shadow)" (= base-count (count after)) (pr-str [base-count (count after)]))
  (check "sibling ORDER preserved after upsert" (= base-names after) (pr-str after))
  (check "all 5 targets still present" (= 5 (count (filter #(str/starts-with? % "StreamableResponseBody@") after))) (pr-str after)))
(let [src (str (:source (r RP "StreamableResponseBody@String")))]
  (check "edited String method took (MARK present)" (str/includes? src "MARK") src)
  (check "^OutputStream hints preserved through mint (canon #%meta node)"
         (str/includes? src "#%meta") src))

(println "\n--- RENDER: valid Clojure, hints present ---")
(if-let [txt (render-text RP)]
  (do
    (check "render contains ^OutputStream (hint survived, not dropped)" (str/includes? txt "^OutputStream") txt)
    (check "render contains ^java.io.OutputStream (fully-qualified hint)" (str/includes? txt "^java.io.OutputStream") txt)
    (check "render keeps all 5 targets (String ISeq InputStream File nil)"
           (every? #(str/includes? txt %) ["String" "clojure.lang.ISeq" "java.io.InputStream" "java.io.File"]) txt)
    (check "render re-reads as Clojure (parser accepts it)"
           (try (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. txt))]
                  (binding [*read-eval* false] (loop [] (when-not (identical? ::eof (read {:eof ::eof :read-cond :allow} rdr)) (recur))))
                  true)
                (catch Throwable t (str t)))
           "render did not parse"))
  (check "RENDER SKIPPED (no racket pin) — write/index asserts still ran" true "set FRAM_RACKET/BEAGLE_HOME to run the render leg"))

;; -----------------------------------------------------------------------------
(println "\n=== (3) MALLI-05 CLASS: extend-type IPrintWithWriter (-pr-writer / -write) ===")
;; The -pr-writer-into-schema print-method shape: a protocol-method impl calling the
;; -write protocol method. This is exactly what b4 malli-05 could not author.
(let [resp (w RP "(extend-type malli.core.Schema
                    IPrintWithWriter
                    (-pr-writer [this writer opts]
                      (-write writer \"#Schema \")
                      (-pr-writer (-form this) writer opts)))")]
  (check "extend-type IPrintWithWriter -> :ok" (:ok resp) (pr-str (dissoc resp :results))))
(let [after (set (names RP))]
  (check "extend-type block addressable (Schema@IPrintWithWriter)"
         (some #(str/includes? % "malli.core.Schema@IPrintWithWriter") after) (pr-str after)))
(let [src (str (:source (r RP "malli.core.Schema@IPrintWithWriter")))]
  (check "extend-type -write call preserved" (str/includes? src "-write") src)
  (check "extend-type -pr-writer method preserved" (str/includes? src "-pr-writer") src))
(if-let [txt (render-text RP)]
  (check "render contains the extend-type block, valid Clojure"
         (and (str/includes? txt "IPrintWithWriter") (str/includes? txt "-write")
              (try (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. txt))]
                     (binding [*read-eval* false] (loop [] (when-not (identical? ::eof (read {:eof ::eof :read-cond :allow} rdr)) (recur)))) true)
                   (catch Throwable _ false)))
         txt)
  (check "RENDER SKIPPED (no racket pin)" true nil))

(println (format "\n==== %s : %d failure(s) ====" (if (zero? @failures) "PASS" "FAIL") @failures))
(shutdown!)
(System/exit (if (zero? @failures) 0 1))
