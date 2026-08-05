;; Original-first deterministic oracle for M5 Cut D.
;;
;; The queue is preloaded before the appender starts, so its first take drains a
;; stable pending set. The output records coalescing, FIFO order, ticket
;; admission, and the existing outer group-io / inner append-admission order.
(require '[fram.rt :as rt])
(load-file "database.clj")

(let [dir (or (System/getenv "FRAM_GROUP_GOLDEN_DIR")
              (throw (ex-info "FRAM_GROUP_GOLDEN_DIR is required" {})))
      path-a (str dir "/a.log")
      path-b (str dir "/b.log")
      q database/group-q
      events (atom [])
      lock-events (atom [])
      ticket (fn [] (promise))
      t-a1 (ticket)
      t-b1 (ticket)
      t-a2 (ticket)
      t-barrier (ticket)
      callback (fn [label]
                 (fn [ctx]
                   (swap! events conj
                          {:label label
                           :ctx ctx
                           :group-lock-held
                           (Thread/holdsLock database/group-io-lock)})))
      path-label (fn [path]
                   (cond
                     (= path path-a) :a
                     (= path path-b) :b
                     :else :unknown))]
  (.clear q)
  (spit path-a "")
  (spit path-b "")
  (.put q {:path path-a :lines ["a1\n"] :ticket t-a1
           :on-flushed (callback :a1)})
  (.put q {:path path-b :lines ["b1\n"] :ticket t-b1
           :on-flushed (callback :b1)})
  (.put q {:path path-a :lines ["a2\n"] :ticket t-a2
           :on-flushed (callback :a2)})
  (.put q {:path nil :lines [] :ticket t-barrier
           :on-flushed (callback :barrier)})
  (with-redefs [rt/with-append-admission
                (fn [path f]
                  (swap! lock-events conj
                         [:enter (path-label path)
                          (Thread/holdsLock database/group-io-lock)])
                  (let [result (f)]
                    (swap! lock-events conj
                           [:exit (path-label path)
                            (Thread/holdsLock database/group-io-lock)])
                    result))]
    (database/ensure-group-appender!)
    (let [initial-results (mapv deref [t-a1 t-b1 t-a2 t-barrier])
          initial-events @events
          ctx-by-label (into {} (map (juxt :label :ctx) initial-events))
          deferred-tickets (atom [])
          deferred-return
          (binding [database/*durable-tickets* deferred-tickets]
            (database/enqueue-durable! path-a ["a3\n"] (callback :a3)))
          deferred-result (deref deferred-return)
          inline-result
          (binding [database/*durable-tickets* nil]
            (database/enqueue-durable! path-b ["b2\n"] (callback :b2)))]
      (prn
       {:scenario :pending-coalesce-fifo
        :preloaded-items 4
        :initial-results initial-results
        :initial-callback-order (mapv :label initial-events)
        :same-path-coalesced
        (= (get ctx-by-label :a1) (get ctx-by-label :a2))
        :callback-held-group-lock
        (mapv :group-lock-held initial-events)
        :lock-order @lock-events
        :queue-empty-after-barrier (.isEmpty q)
        :files {:a (slurp path-a) :b (slurp path-b)}
        :deferred {:returned-ticket (instance? clojure.lang.IDeref
                                                deferred-return)
                   :collected (count @deferred-tickets)
                   :same-ticket (identical? deferred-return
                                            (first @deferred-tickets))
                   :result deferred-result}
        :inline {:result inline-result}}))))
