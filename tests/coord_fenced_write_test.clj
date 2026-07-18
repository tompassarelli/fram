;; coord_fenced_write_test.clj — real-socket proof that lease validation and a
;; fact mutation share one coordinator turn. A stale holder must never mutate
;; after release, expiry, or takeover.
;;
;; Run: bb -cp out tests/coord_fenced_write_test.clj
(require '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn client [port request]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader.
                      (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str request) "\n"))
    (.flush writer)
    (edn/read reader)))

(defn eventually [f]
  (loop [remaining 200]
    (cond
      (try (f) (catch Exception _ false)) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(defn values-of [port subject predicate]
  (set (:values (client port {:op :resolved :te subject :p predicate}))))

(defn acquire! [port resource holder ttl-ms]
  (client port {:op :acquire-lease
                :res resource :holder holder :ttl-ms ttl-ms}))

(defn renew! [port resource holder epoch ttl-ms]
  (client port {:op :renew-lease
                :res resource :holder holder :epoch epoch :ttl-ms ttl-ms}))

(defn fenced-assert [port lease subject predicate value]
  (client port {:op :assert-with-fence
                :res (:resource lease)
                :holder (:holder lease)
                :epoch (:epoch lease)
                :te subject :p predicate :r value}))

(defn fenced-retract [port lease subject predicate value]
  (client port {:op :retract-with-fence
                :res (:resource lease)
                :holder (:holder lease)
                :epoch (:epoch lease)
                :te subject :p predicate :r value}))

(let [port (free-port)
      dir (.toFile
           (java.nio.file.Files/createTempDirectory
            "fram-fenced-write"
            (make-array java.nio.file.attribute.FileAttribute 0)))
      log (io/file dir "facts.log")
      _ (spit log "")
      daemon
      (proc/process
       {:dir root :out :string :err :string}
       "bb" "-cp" "out" "coord_daemon.clj" "serve-flat"
       (str port) (.getPath log))
      checks (atom [])
      check! (fn [label value]
               (swap! checks conj [label (boolean value)]))]
  (try
    (check! "real socket daemon starts"
            (eventually #(integer? (:version
                                   (client port {:op :version})))))

    (let [resource "fenced-write:active"
          holder "active-holder"
          acquired (acquire! port resource holder 5000)
          lease {:resource resource :holder holder :epoch (:epoch acquired)}
          accepted (fenced-assert port lease "@active" "marker" "winner")
          wrong-epoch (fenced-assert
                       port (update lease :epoch inc)
                       "@active" "marker" "wrong")]
      (check! "current holder can assert"
              (and (:ok acquired) (:ok accepted)
                   (= #{"winner"} (values-of port "@active" "marker"))))
      (check! "wrong epoch rejects before mutation"
              (and (= :fence-lost (:reject wrong-epoch))
                   (= #{"winner"} (values-of port "@active" "marker")))))

    (let [resource "renew-wire"
          holder "renew-holder"
          acquired (acquire! port resource holder 5000)
          wrong-holder (renew! port resource "other" (:epoch acquired) 10000)
          wrong-epoch (renew! port resource holder (inc (:epoch acquired)) 10000)
          invalid-ttl (renew! port resource holder (:epoch acquired) 0)
          invalid-epoch (renew! port resource holder 0 10000)
          renewed (renew! port resource holder (:epoch acquired) 10000)
          old-lease {:resource resource :holder holder :epoch (:epoch acquired)}
          renewed-lease {:resource resource :holder holder :epoch (:epoch renewed)}
          old-write (fenced-assert port old-lease "@renewed-write" "marker" "stale")
          renewed-write (fenced-assert port renewed-lease "@renewed-write" "marker" "fresh")
          renewed-values (values-of port "@renewed-write" "marker")
          old-fence (client port {:op :fence-ok
                                  :res resource :holder holder :epoch (:epoch acquired)})
          fresh-fence (client port {:op :fence-ok
                                    :res resource :holder holder :epoch (:epoch renewed)})
          stale-release (client port {:op :release-lease
                                      :res resource :holder holder :epoch (:epoch acquired)})
          fresh-after-stale-release (client port {:op :fence-ok
                                                  :res resource :holder holder :epoch (:epoch renewed)})
          fresh-release (client port {:op :release-lease
                                      :res resource :holder holder :epoch (:epoch renewed)})
          after-release (client port {:op :fence-ok
                                      :res resource :holder holder :epoch (:epoch renewed)})]
      (check! "socket renew rejects wrong holder, wrong epoch, and hostile numeric input"
              (and (= :fence-lost (:reject wrong-holder))
                   (= :fence-lost (:reject wrong-epoch))
                   (= :invalid-lease-request (:reject invalid-ttl))
                   (= :invalid-lease-request (:reject invalid-epoch))))
      (check! "socket renew returns a coherent globally newer epoch"
              (and (:ok renewed)
                   (= (:ok renewed) (:epoch renewed))
                   (> (:epoch renewed) (:epoch acquired))
                   (> (:exp renewed) (:exp acquired))))
      (check! "socket renew invalidates the old fence and stale release"
              (and (false? (:fence-ok old-fence))
                   (:fence-ok fresh-fence)
                   (:noop stale-release)
                   (:fence-ok fresh-after-stale-release)))
      (check! "only the renewed epoch can perform an atomic fenced write"
              (and (= :fence-lost (:reject old-write))
                   (:ok renewed-write)
                   (= #{"fresh"} renewed-values)))
      (check! "socket release accepts only the renewed epoch"
              (and (:ok fresh-release)
                   (false? (:fence-ok after-release)))))

    (let [resource "fenced-write:takeover"
          stale-holder "stale-holder"
          first (acquire! port resource stale-holder 5000)
          stale {:resource resource :holder stale-holder :epoch (:epoch first)}
          _ (client port {:op :release-lease
                          :res resource :holder stale-holder})
          winner-holder "winner-holder"
          second (acquire! port resource winner-holder 5000)
          winner {:resource resource :holder winner-holder :epoch (:epoch second)}
          seeded (fenced-assert port winner "@takeover" "marker" "winner")
          stale-assert (fenced-assert
                        port stale "@takeover" "marker" "loser")
          stale-retract (fenced-retract
                         port stale "@takeover" "marker" "winner")]
      (check! "successor takes the released lease and can write"
              (and (:ok first) (:ok second)
                   (= winner-holder (:holder second))
                   (:ok seeded)))
      (check! "released holder cannot assert after takeover"
              (= :fence-lost (:reject stale-assert)))
      (check! "released holder cannot retract after takeover"
              (and (= :fence-lost (:reject stale-retract))
                   (= #{"winner"} (values-of port "@takeover" "marker")))))

    (let [resource "fenced-write:same-holder-aba"
          holder "same-holder"
          first (acquire! port resource holder 5000)
          released
          (client port {:op :release-lease
                        :res resource :holder holder :epoch (:epoch first)})
          second (acquire! port resource holder 5000)
          successor
          {:resource resource :holder holder :epoch (:epoch second)}
          stale
          {:resource resource :holder holder :epoch (:epoch first)}
          seeded
          (fenced-assert port successor "@same-holder-aba" "marker" "successor")
          stale-release
          (client port {:op :release-lease
                        :res resource :holder holder :epoch (:epoch first)})
          successor-still-current
          (client port {:op :fence-ok
                        :res resource :holder holder :epoch (:epoch second)})
          stale-assert
          (fenced-assert port stale "@same-holder-aba" "marker" "stale")
          stale-retract
          (fenced-retract port stale "@same-holder-aba" "marker" "successor")]
      (check! "same-holder reacquire receives a globally newer epoch"
              (and (:ok first) (:ok released) (:ok second)
                   (> (:epoch second) (:epoch first))))
      (check! "stale same-holder release cannot remove successor"
              (and (:noop stale-release)
                   (:fence-ok successor-still-current)
                   (:ok seeded)))
      (check! "stale same-holder epoch rejects assert and retract"
              (and (= :fence-lost (:reject stale-assert))
                   (= :fence-lost (:reject stale-retract))
                   (= #{"successor"}
                      (values-of port "@same-holder-aba" "marker")))))

    (let [resource "fenced-write:expiry"
          stale-holder "expired-holder"
          first (acquire! port resource stale-holder 20)
          stale {:resource resource :holder stale-holder :epoch (:epoch first)}
          _ (Thread/sleep 40)
          winner-holder "post-expiry-holder"
          second (acquire! port resource winner-holder 5000)
          winner {:resource resource :holder winner-holder :epoch (:epoch second)}
          seeded (fenced-assert port winner "@expiry" "marker" "winner")
          stale-assert (fenced-assert port stale "@expiry" "marker" "loser")
          stale-retract
          (fenced-retract port stale "@expiry" "marker" "winner")]
      (check! "expired lease is replaced at a higher epoch"
              (and (:ok first) (:ok second)
                   (> (:epoch second) (:epoch first))
                   (:ok seeded)))
      (check! "expired holder cannot mutate after takeover"
              (and (= :fence-lost (:reject stale-assert))
                   (= :fence-lost (:reject stale-retract))
                   (= #{"winner"} (values-of port "@expiry" "marker")))))

    (let [resource "fenced-write:global-cas"
          holder "proof-holder"
          acquired (acquire! port resource holder 5000)
          lease {:resource resource :holder holder :epoch (:epoch acquired)}
          base (:version (client port {:op :version}))
          _ (client port {:op :assert
                          :te "@unrelated" :p "sample" :r "moved"})
          conflict
          (client port {:op :assert-at-version-with-fence
                        :res resource :holder holder :epoch (:epoch lease)
                        :te "@proof" :p "marker" :r "stale" :base base})
          after-conflict (values-of port "@proof" "marker")
          fresh-base (:version (client port {:op :version}))
          accepted
          (client port {:op :assert-at-version-with-fence
                        :res resource :holder holder :epoch (:epoch lease)
                        :te "@proof" :p "marker" :r "current"
                        :base fresh-base})]
      (check! "fenced global assertion still rejects a stale graph snapshot"
              (and (= :conflict (:reject conflict))
                   (empty? after-conflict)))
      (check! "fenced global assertion accepts current lease and graph version"
              (and (:ok accepted)
                   (= #{"current"} (values-of port "@proof" "marker")))))

    (let [malformed
          (client port {:op :assert-with-fence
                        :res "missing" :holder "nobody" :epoch nil
                        :te "@malformed" :p "marker" :r "never"})]
      (check! "malformed or absent fence rejects without mutation"
              (and (= :fence-lost (:reject malformed))
                   (empty? (values-of port "@malformed" "marker")))))

    (finally
      (proc/destroy-tree daemon)
      (try @daemon (catch Exception _ nil))
      (doseq [[label ok?] @checks]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [failed (remove second @checks)]
        (println (format "\n%d/%d passed"
                         (- (count @checks) (count failed))
                         (count @checks)))
        (when (seq failed) (System/exit 1))))))
