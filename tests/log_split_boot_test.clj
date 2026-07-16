;; Regression checks for split-log boot path selection. Loads the daemon as a
;; library (no command args => no listener) and exercises its private boot gate.
(require '[clojure.java.io :as io])

(load-file "coord_daemon.clj")

(def checks (atom []))
(defn chk [name ok] (swap! checks conj [name ok]))
(def reaim (ns-resolve 'user 'reaim-split))
(def telemetry-var (ns-resolve 'user 'telemetry-log))
(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-log-split-" (System/nanoTime)))
(.mkdirs (io/file tmp))
(def coord (str tmp "/coordination.log"))
(def telemetry (str tmp "/telemetry.log"))
(def legacy (str tmp "/facts.log"))
(def alternate (str tmp "/alternate.log"))
(spit coord "")
(spit legacy "")

(reset! (var-get telemetry-var) nil)
(chk "coordination.log activates its sibling telemetry.log"
     (and (= (.getCanonicalPath (io/file coord))
             (.getCanonicalPath (io/file (reaim coord))))
          (= (.getCanonicalPath (io/file telemetry))
             (.getCanonicalPath (io/file @(var-get telemetry-var))))))

(reset! (var-get telemetry-var) nil)
(chk "legacy facts.log re-aims to coordination.log"
     (= (.getCanonicalPath (io/file coord))
        (.getCanonicalPath (io/file (reaim legacy)))))

(reset! (var-get telemetry-var) nil)
(chk "unrelated logs are never re-aimed"
     (= (.getCanonicalPath (io/file alternate))
        (.getCanonicalPath (io/file (reaim alternate)))))

(reset! (var-get telemetry-var) (str tmp "/wrong.log"))
(chk "an explicit non-sibling telemetry path fails closed"
     (try (reaim coord) false (catch clojure.lang.ExceptionInfo _ true)))

(let [fails (remove second @checks)]
  (doseq [[name ok] @checks] (println (if ok "  [PASS]" "  [FAIL]") name))
  (when (seq fails) (System/exit 1))
  (println "\nlog-split boot:" (count @checks) "/" (count @checks) "PASS"))
