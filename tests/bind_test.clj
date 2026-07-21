;; bind_test.clj — the coordinator's configurable bind (FRAM_BIND). Asserts the two
;; modes from the gateway hand-off: default loopback (unchanged), and FRAM_BIND=0.0.0.0
;; binding all interfaces while local loopback clients keep working + a startup warning.
;;   bb bind_test.clj   (run from the repo root)
(require '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str] '[clojure.java.io :as io])
(load-file "tests/log_split_readiness_lib.clj")

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-bind-test-" (System/nanoTime)))
(.mkdirs (io/file tmp))
(def log (str tmp "/facts.log"))
(spit log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"test\"}\n")

(defn ask [port m]
  (with-open [s (java.net.Socket.)]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout s 1000)
    (let [w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream s)))
          r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

(defn version-ready? [port]
  (try (some? (:version (ask port {:op :version})))
       (catch Exception _ false)))

(defn ss-addr [port]
  (try (let [out (:out (p/shell {:out :string :err :string :continue true} "ss" "-tlnH" (str "sport = :" port)))]
         (str/trim out))
       (catch Exception _ ::no-ss)))

(def startup-deadline-ms 6000) ; unchanged: the old 40 × 150ms readiness budget

(defn exercise-daemon [label bind]
  (let [port (free-port)
        err-path (str tmp "/" label ".err")
        opts (cond-> {:out (io/file (str tmp "/" label ".out"))
                      :err (io/file err-path)}
               bind (assoc :extra-env {"FRAM_BIND" bind}))
        child (p/process ["bin/fram-daemon" (str port) log] opts)]
    (try
      ;; These are independent scenarios. Running them one at a time avoids
      ;; making two cold JVM boots contend and then calling that contention a
      ;; bind failure. await-ready also distinguishes a dead child from a live,
      ;; slow child without weakening the original startup deadline.
      (await-ready child port version-ready?
                   :deadline-ms startup-deadline-ms :poll-ms 150)
      (Thread/sleep 200)
      {:port port
       :listening true
       :version-ok (version-ready? port)
       :addr (ss-addr port)
       :err (slurp err-path)}
      (finally
        (p/destroy-tree child)))))

(def resultA (exercise-daemon "a" nil))
(def resultB (exercise-daemon "b" "0.0.0.0"))

(chk "default daemon is listening (loopback)" (:listening resultA))
(chk "FRAM_BIND=0.0.0.0 daemon is listening" (:listening resultB))

;; loopback clients work in BOTH modes (the recommended-approach guarantee)
(chk "default: :version answers on 127.0.0.1" (:version-ok resultA))
(chk "0.0.0.0: :version STILL answers on 127.0.0.1 (loopback preserved)"
     (:version-ok resultB))

;; ss bind-address check (skip gracefully if ss unavailable)
(let [a (:addr resultA) b (:addr resultB)]
  (if (or (= a ::no-ss) (= b ::no-ss))
    (do (chk "ss check skipped (ss unavailable)" true)
        (binding [*out* *err*] (println "  (note: ss not available; bind-address check skipped)")))
    (do (chk "default bind address is loopback (127.0.0.1 in ss)" (str/includes? a "127.0.0.1"))
        (chk "FRAM_BIND=0.0.0.0 binds all interfaces (0.0.0.0 / * in ss)"
             (or (str/includes? b "0.0.0.0") (str/includes? b "*"))))))

;; warning only on non-loopback
(chk "non-loopback logs the UNAUTHENTICATED warning" (str/includes? (:err resultB) "UNAUTHENTICATED"))
(chk "default (loopback) logs NO unauthenticated warning" (not (str/includes? (:err resultA) "UNAUTHENTICATED")))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram bind:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram bind:" (count fails) "FAILED")
        (println "--- a.err ---") (println (:err resultA))
        (println "--- b.err ---") (println (:err resultB))
        (System/exit 1))))
