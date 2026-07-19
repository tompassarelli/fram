;; fram-selfcheck-probe.clj — the probe half of `fram selfcheck --deep`.
;;
;; bin/fram-selfcheck (bash) owns the lifecycle: it boots the isolated scratch
;; coordinators (plaintext + mTLS), mints scratch TLS material, and traps
;; deterministic cleanup. THIS file (run on babashka, cwd = repo/package root so
;; coord_daemon.clj's relative load-files resolve) runs the eight named probes
;; against those scratch coordinators and prints one terse pass/fail line per
;; subsystem. Exit 0 iff every subsystem passes; nonzero on any failure.
;;
;; It NEVER contacts the live coordinator: every port/log is a scratch value
;; handed in via FRAM_SC_* env. Config is env-only so *command-line-args* stays
;; empty (loading coord_daemon.clj then runs nothing but definitions).
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str]
         '[fram.store :as c] '[fram.schema :as s] '[fram.fold :as fold] '[fram.rt]
         '[babashka.process :as bp])
(binding [*command-line-args* []] (load-file "coord_daemon.clj"))

(defn env [k] (System/getenv k))
(def SCRATCH (env "FRAM_SC_SCRATCH"))
(def HERE    (env "FRAM_SC_HERE"))
(def PLAIN   (Integer/parseInt (env "FRAM_SC_PORT_PLAIN")))
(def TLS     (Integer/parseInt (env "FRAM_SC_PORT_TLS")))
(def DEAD    (Integer/parseInt (env "FRAM_SC_PORT_DEAD")))
(def L1      (env "FRAM_SC_LOG_PLAIN"))
(def TLSDIR  (env "FRAM_SC_TLS_DIR"))
(def PW      (env "FRAM_SC_TLS_PW"))
(def FAULT   (env "FRAM_SELFCHECK_FAULT"))
(defn tpath [f] (str TLSDIR "/" f))

;; --- socket + TLS clients (one line EDN request/response over the wire) --------
(defn ask
  ([port req] (ask port req 3000))
  ([port req timeout]
   (with-open [s (java.net.Socket.)]
     (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1500)
     (.setSoTimeout s (int timeout))
     (let [w (io/writer (.getOutputStream s))
           r (java.io.PushbackReader. (io/reader (.getInputStream s)))]
       (.write w (str (pr-str req) "\n")) (.flush w) (edn/read r)))))

(defn ssl-ctx [ks ts]
  (let [pw (.toCharArray PW)
        load (fn [pth] (with-open [in (io/input-stream pth)]
                         (doto (java.security.KeyStore/getInstance "PKCS12") (.load in pw))))
        kmf (doto (javax.net.ssl.KeyManagerFactory/getInstance (javax.net.ssl.KeyManagerFactory/getDefaultAlgorithm)) (.init (load ks) pw))
        tmf (doto (javax.net.ssl.TrustManagerFactory/getInstance (javax.net.ssl.TrustManagerFactory/getDefaultAlgorithm)) (.init (load ts)))]
    (doto (javax.net.ssl.SSLContext/getInstance "TLS") (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil))))

(defn tls-ask [ks ts req]
  (with-open [s (.createSocket (.getSocketFactory (ssl-ctx ks ts)))]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int TLS)) 1500)
    (.setSoTimeout s 3000) (.startHandshake s)
    (let [w (io/writer (.getOutputStream s)) r (io/reader (.getInputStream s))]
      (.write w (str (pr-str req) "\n")) (.flush w) (edn/read-string (.readLine r)))))

(defn rejected? [thunk] (try (not (map? (thunk))) (catch Throwable _ true)))

;; --- in-process corpus helpers -----------------------------------------------
(defn ln [tx op l p r] (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "selfcheck"}))
(defn lines! [path & ls] (spit path (str (str/join "\n" ls) "\n")))
(defn append! [path & ls] (spit path (str (str/join "\n" ls) "\n") :append true))
;; boot-flat! logs a "[fram] boot(flat): ..." line to *out*; keep the operator
;; output terse by swallowing it during the in-process probes.
(defn qboot [log] (binding [*out* (java.io.StringWriter.)] (boot-flat! log)))

;; --- section harness ----------------------------------------------------------
(def results (atom []))
(defn section [nm thunk]
  (let [[ok detail] (try (thunk) (catch Throwable t [false (str "exception: " (.getMessage t))]))]
    (swap! results conj [nm (boolean ok) (str detail)])))

;; 1. socket — connect + version/status roundtrip against the scratch daemon.
(section "socket"
  #(let [port (if (= FAULT "socket") DEAD PLAIN)
         v  (ask port {:op :version})
         st (ask port {:op :status})]
     [(and (integer? (:version v)) (integer? (:version st))
           (integer? (:facts st)) (string? (:log st)))
      (str "version=" (:version v) " status{facts=" (:facts st) " log=" (:log st) "}")]))

;; 2. cold-fold — cold CLI fold of the scratch log == warm daemon state (same CLI,
;;    daemon port vs a dead port that forces the cold whole-log fold).
(section "cold-fold"
  #(let [run (fn [port]
               (-> (bp/shell {:out :string :err :string :continue true
                              :extra-env {"FRAM_LOG" L1 "FRAM_THREADS" (str SCRATCH "/threads")
                                          "FRAM_PORT" (str port)}}
                             (str HERE "/bin/fram") "show" "sc-cold")
                   :out str/trim))
         warm (run PLAIN)
         cold (run DEAD)]
     [(and (seq warm) (= warm cold) (str/includes? warm "cold-fold-canary"))
      (str "warm(daemon)==cold(fold), " (count (str/split-lines warm)) " line(s)")]))

;; 3. fencing — a stale-epoch fenced write is rejected before mutation.
(section "fencing"
  #(let [res "sc-fence" holder "h"
         acq (ask PLAIN {:op :acquire-lease :res res :holder holder :ttl-ms 60000})
         ep  (:epoch acq)
         good (ask PLAIN {:op :assert-with-fence :res res :holder holder :epoch ep
                          :te "@sc-fence" :p "marker" :r "winner"})
         stale (ask PLAIN {:op :assert-with-fence :res res :holder holder :epoch (inc ep)
                           :te "@sc-fence" :p "marker" :r "loser"})
         vals (set (:values (ask PLAIN {:op :resolved :te "@sc-fence" :p "marker"})))]
     [(and (:ok acq) (:ok good) (= :fence-lost (:reject stale)) (= #{"winner"} vals))
      (str "epoch " ep " writes; stale epoch " (inc ep) " -> " (:reject stale) ", no mutation")]))

;; 4. exact-epoch lease — renewal rotates the token; only the renewed epoch releases.
(section "lease"
  #(let [res "sc-lease" holder "h"
         acq (ask PLAIN {:op :acquire-lease :res res :holder holder :ttl-ms 60000})
         e0 (:epoch acq)
         ren (ask PLAIN {:op :renew-lease :res res :holder holder :epoch e0 :ttl-ms 120000})
         e1 (:epoch ren)
         stale-rel (ask PLAIN {:op :release-lease :res res :holder holder :epoch e0})
         old-fence (ask PLAIN {:op :fence-ok :res res :holder holder :epoch e0})
         fresh-rel (ask PLAIN {:op :release-lease :res res :holder holder :epoch e1})
         after     (ask PLAIN {:op :fence-ok :res res :holder holder :epoch e1})]
     [(and (:ok acq) (:ok ren) (> e1 e0) (> (:exp ren) (:exp acq))
           (:noop stale-rel) (false? (:fence-ok old-fence))
           (:ok fresh-rel)   (false? (:fence-ok after)))
      (str "epoch " e0 "->" e1 "; stale-epoch release noop; epoch-exact release ok")]))

;; 5. mTLS/admission — trusted client cert accepted; plaintext + rogue cert rejected.
(section "mtls"
  #(let [happy     (tls-ask (tpath "client.p12") (tpath "clienttrust.p12") {:op :version})
         plain-rej (rejected? (fn [] (ask TLS {:op :version})))
         rogue-rej (rejected? (fn [] (tls-ask (tpath "rogue.p12") (tpath "clienttrust.p12") {:op :version})))]
     [(and (some? (:version happy)) plain-rej rogue-rej)
      (str "trusted->version " (:version happy) "; plaintext-rejected=" plain-rej "; rogue-rejected=" rogue-rej)]))

;; 6. snapshot/tail — checkpoint + tail-fold boot equals the whole-log fold (state
;;    AND version, torn tail counted).
(section "snapshot"
  #(let [log (str SCRATCH "/snap.log")]
     (reset! snapshot-boot-enabled? true)
     (lines! log (ln 1 "assert" "@T1" "title" "First")
                 (ln 2 "assert" "@T1" "tag" "a")
                 (ln 3 "assert" "@T2" "title" "Two"))
     (qboot log)
     (write-snapshot! @co log)
     (let [base (current-seq @co)]
       (append! log (ln (+ base 1) "assert"  "@T1" "title" "Updated")
                    (ln (+ base 2) "assert"  "@T3" "title" "Three")
                    (ln (+ base 3) "retract" "@T1" "tag" "a"))
       (append! log (pr-str {:tx (+ base 4) :op "assert" :l "@torn" :p "title" :ts "t" :by "selfcheck"})))
     (let [truth   (live-name-triples (migrate-flat->co log))
           truth-v (:version (fold/fold (fram.rt/read-log log)))]
       (qboot log)
       [(and (= :snapshot (:mode @last-boot))
             (= (live-name-triples @co) truth)
             (= (current-seq @co) truth-v)
             (:ok (snapshot-reconcile @co log)))
        (str "boot=" (name (:mode @last-boot)) "; state==whole-fold; version=" (current-seq @co)
             "==" truth-v "; reconcile ok")])))

;; 7. identity — a durable bound_to edge survives a cold restart (synthetic corpus).
(section "identity"
  #(let [log (str SCRATCH "/id.log")
         bound (fn []
                 (let [st (:store @co) BND (c/value-id st "bound_to") L (s/resolve-name st "@ref")]
                   (when (and BND L)
                     (let [cids (c/by-lp st L BND)]
                       (when (seq cids)
                         (let [rid (:r (c/fact-of st (first cids)))]
                           {:name (s/name-of st rid) :id rid :tgt (s/resolve-name st "@tgt")}))))))]
     (lines! log (ln 1 "assert" "@tgt" "title" "Target")
                 (ln 2 "assert" "@ref" "bound_to" "@tgt"))
     (qboot log)
     (let [b0 (bound)]
       (qboot log)                       ; cold restart from the durable log
       (let [b1 (bound)]
         [(and b0 b1 (= "@tgt" (:name b0)) (= (:name b0) (:name b1))
               (= (:id b1) (:tgt b1)))         ; edge still points at the live @tgt node identity
          (str "bound_to->" (:name b1) " survives cold restart; resolves to @tgt id " (:tgt b1))]))))

;; 8. log/store reconciliation — the live incremental store == a from-scratch whole
;;    migrate of the same log (snapshot-reconcile gate).
(section "reconcile"
  #(let [log (str SCRATCH "/rec.log")]
     (lines! log (ln 1 "assert" "@r1" "title" "One")
                 (ln 2 "assert" "@r1" "tag" "x")
                 (ln 3 "assert" "@r2" "title" "Two"))
     (qboot log)
     (let [rc (snapshot-reconcile @co log)]
       [(and (:ok rc) (= (:inc rc) (:fresh rc)))
        (str "store==log migrate; inc=" (:inc rc) " fresh=" (:fresh rc))])))

;; --- report -------------------------------------------------------------------
(doseq [[nm ok detail] @results]
  (println (format "  [%s] %-10s %s" (if ok "PASS" "FAIL") nm detail)))
(let [n (count @results) fails (count (remove second @results))]
  (if (zero? fails)
    (do (println (format "\nselfcheck --deep: %d/%d PASS" n n)) (flush) (System/exit 0))
    (do (println (format "\nselfcheck --deep: %d subsystem(s) FAILED (of %d)" fails n)) (flush) (System/exit 1))))
