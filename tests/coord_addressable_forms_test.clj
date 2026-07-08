;; ============================================================================
;; cnf_addressable_forms_test.clj — S-profile INDEX/READ-DEF addressability.
;; ============================================================================
;; Fixes the EXP-025 p1c ring-01 addressability gap: on a REAL Clojure module
;; (ring.core.protocols — a defprotocol + extend-protocol module) the old `index`
;; returned `defs=[]` because it kept only VALUE-DEFS heads and named them with a
;; meta-blind `(second children)`, so every protocol member, extension target
;; block, defmulti/defmethod, AND every `^:meta`/`^hint`-named def was INVISIBLE.
;; The model then burned 10 read-def lookups (ERR@lookup) with no `:nearest` help.
;;
;; ACCEPTANCE PROPERTY proven here (round-trip): every name `index` lists resolves
;; through `read-def` — so the index the model SEES only ever names things read-def
;; ACCEPTS. Plus: ERR@lookup now carries `:nearest` candidates.
;;
;; Boots a warm daemon over a COMMITTED fixture log (tests/fixtures/proto-addr/,
;; ingested from ring.core.protocols.bclj + a small extend-type/defmulti module)
;; on a verified-free port >= 49010, then drives the wire verbs through the socket.
;;
;;   clojure -M tests/cnf_addressable_forms_test.clj > /tmp/addr.out 2>&1; echo EXIT=$?
;; ============================================================================
(require '[clojure.string :as str] '[clojure.java.io :as io])

(def root (System/getProperty "user.dir"))
(def fixture (str root "/tests/fixtures/proto-addr/code.claimlog"))
(when-not (.exists (io/file fixture))
  (println "SKIP — missing fixture" fixture) (System/exit 0))

(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

;; --- throwaway daemon over a /tmp COPY, on a free port >= 49010 -------------
(def flat (str (System/getProperty "java.io.tmpdir") "/addr-test-" (System/nanoTime) ".code.log"))
(io/copy (io/file fixture) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) (range 49010 49040)) 49010))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 700)
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
(defn- r    [module name] (client port {:op :read-def :spec {:module module :name name}}))
(defn- idx  [module]      (client port {:op :index    :spec {:module module}}))
(defn- names [module]     (mapv :name (:defs (idx module))))

(def RP "ring.core.protocols")
(def FS "fixt.shapes")

(println "=== INDEX lists every addressable top-level form (incl. extension) ===")
(let [ix (idx RP) ns (set (names RP))]
  (check "index ring.core.protocols :ok" (:ok ix) (pr-str ix))
  (check "protocol name listed (StreamableResponseBody)" (ns "StreamableResponseBody") (pr-str ns))
  (check "protocol member listed (write-body-to-stream)" (ns "write-body-to-stream") (pr-str ns))
  (check "meta-named defn- listed (response-writer)" (ns "response-writer") (pr-str ns))
  (check "extend-protocol per-target entries present"
         (some #(str/starts-with? % "StreamableResponseBody@") ns) (pr-str ns))
  (check "String target block addressable" (ns "StreamableResponseBody@String") (pr-str ns))
  (check "nil target block addressable" (ns "StreamableResponseBody@nil") (pr-str ns)))

(let [ns (set (names FS))]
  (check "defrecord listed (Dollars)" (ns "Dollars") (pr-str ns))
  (check "extend-type target block listed (Dollars@Show)" (ns "Dollars@Show") (pr-str ns))
  (check "defmulti listed (area)" (ns "area") (pr-str ns))
  (check "defmethod entries listed (per dispatch)"
         (some #(str/starts-with? % "area:") ns) (pr-str ns)))

(println "\n=== ROUND-TRIP: read-def ACCEPTS every name index lists (the invariant) ===")
(doseq [module [RP FS]]
  (let [ns (names module)]
    (check (str module ": index non-empty") (seq ns) (pr-str ns))
    (doseq [nm ns]
      (let [resp (r module nm)]
        (check (str module " / read-def `" nm "`")
               (and (:ok resp) (not (str/blank? (str (:source resp)))))
               (pr-str resp))))))

(println "\n=== read-def on a protocol member returns its enclosing form (not a dead-end) ===")
(let [resp (r RP "write-body-to-stream")]
  (check "member read-def :ok (was: 'has no top-level form')" (:ok resp) (pr-str resp))
  (check "member source contains the protocol" (str/includes? (str (:source resp)) "StreamableResponseBody") (pr-str (:source resp))))

(println "\n=== ERR@lookup carries :nearest candidates (not only 'run index') ===")
(let [resp (r RP "write-body")]                       ; partial of write-body-to-stream
  (check "typo :stage :lookup" (= :lookup (:stage resp)) (pr-str resp))
  (check "typo :nearest non-empty" (seq (:nearest resp)) (pr-str resp))
  (check "typo :nearest surfaces write-body-to-stream (prefix match)"
         (some #{"write-body-to-stream"} (:nearest resp)) (pr-str (:nearest resp))))
(let [resp (r RP "StreamableRespons")]                ; typo of the protocol
  (check "protocol typo :nearest surfaces StreamableResponseBody"
         (some #{"StreamableResponseBody"} (:nearest resp)) (pr-str (:nearest resp))))
(let [resp (r RP "totally-absent-xyz")]
  (check "absent name still :stage :lookup with :suggestion"
         (and (= :lookup (:stage resp)) (not (str/blank? (str (:suggestion resp))))) (pr-str resp)))

(println (format "\n==== %s : %d failure(s) ====" (if (zero? @failures) "PASS" "FAIL") @failures))
(shutdown!)
(System/exit (if (zero? @failures) 0 1))
