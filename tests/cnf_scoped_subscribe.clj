;; scoped-subscribe (G2 primitive 4): a :subscribe may carry a filter {:p <pred> :r [<v>..]};
;; an agent is woken ONLY on commits matching its scope. No filter => firehose (back-compat).
;; Proves: firehose sub gets ALL commits; filtered sub gets ONLY (p,r)-matching commits.
;; Run: bb -cp out tests/cnf_scoped_subscribe.clj   (NEVER 7977)
(require '[clojure.edn :as edn])
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def port 8166)
(spit "/tmp/scoped-sub.log" "")
(boot! "/tmp/scoped-sub.log")
(register-pred! @co "to" "multi" "literal")
(register-pred! @co "owner" "single" "literal")
(def srv (future (serve port)))
(Thread/sleep 500)

(defn subscriber [filt]
  (let [s (java.net.Socket.) acc (atom [])]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (.setSoTimeout s 1500)
    (let [w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream s)))
          r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str (if filt {:op :subscribe :filter filt} {:op :subscribe}))) (.newLine w) (.flush w)
      (future
        (try (loop [] (when-let [ln (.readLine r)] (swap! acc conj (edn/read-string ln)) (recur)))
             (catch Throwable _ nil))
        (try (.close s) (catch Throwable _ nil)))
      acc)))

(def fire (subscriber nil))                                  ; firehose
(def scoped (subscriber {:p "to" :r ["me" "*"]}))            ; only `to me` / `to *`
(Thread/sleep 400)                                           ; let both register

;; 4 commits: 2 match the scope (to me / to *), 2 do NOT (to other / owner me)
(client port {:op :assert :te "@msg1" :p "to" :r "me"    :base 0})   ; MATCH
(client port {:op :assert :te "@msg2" :p "to" :r "other" :base 0})   ; no (r not in set)
(client port {:op :assert :te "@msg3" :p "to" :r "*"     :base 0})   ; MATCH
(client port {:op :assert :te "@X"    :p "owner" :r "me" :base 0})   ; no (p != "to")
(Thread/sleep 2000)                                          ; delivery (executor) + read soTimeout
(future-cancel srv)

(defn commits [acc] (->> @acc (filter #(= :commit (:event %))) (mapv (fn [e] [(:p e) (:r e)]))))
(def fc (commits fire))
(def sc (commits scoped))
(println "  firehose received:" fc)
(println "  scoped   received:" sc)
(def results (atom []))
(defn chk [l ok] (swap! results conj ok) (println (if ok "  [PASS]" "  [FAIL]") l))
(chk "firehose (no filter) receives ALL 4 commits" (= 4 (count fc)))
(chk "scoped receives ONLY the 2 (p=to, r in {me,*}) commits" (= #{["to" "me"] ["to" "*"]} (set sc)))
(chk "scoped did NOT receive to/other" (not (some #(= ["to" "other"] %) sc)))
(chk "scoped did NOT receive owner/me (wrong pred)" (not (some #(= ["owner" "me"] %) sc)))
(let [fails (count (remove identity @results))]
  (println (format "\n=== scoped-subscribe: %d/%d PASS ===" (- (count @results) fails) (count @results)))
  (System/exit (if (pos? fails) 1 0)))
