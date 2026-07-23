;; query_projection_test.clj — the shared-PROJECTION query surface (q/project +
;; q/run-projected + q/run-page-projected) that the coordinator threads per version
;; so a scan query / page drain reuses one EDB+index instead of rebuilding it every
;; call. Proves projected == one-shot on the exact PRODUCTION shape: the stratified
;; pending-message program (direct + broadcast candidate, minus durable ack and
;; rejection settlement — negation over frozen lower strata), the query whose
;; per-page rebuild was hitting the coordinator query-time-limit.
;;   bb -cp out tests/query_projection_test.clj
(require '[fram.kernel :as k]
         '[fram.query :as q])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

;; A corpus of many messages to @me: some acked, some rejected, some broadcast,
;; plus unrelated noise — exactly the mixed inbox the production query pages.
(def recipient "@me")
(def facts
  (vec
   (concat
    ;; 60 direct messages to @me (m0..m59)
    (mapcat (fn [i]
              (let [e (str "@msg" i)]
                [(k/->Fact e "to" recipient)
                 (k/->Fact e "from" (str "@sender" (mod i 7)))
                 (k/->Fact e "body" (str "hello " i))]))
            (range 60))
    ;; ack the first 10, reject the next 5 → 45 direct remain pending
    (map (fn [i] (k/->Fact (str "@msg" i) "acked_by" recipient)) (range 10))
    (map (fn [i] (k/->Fact (str "@msg" i) "rejected_by" recipient)) (range 10 15))
    ;; 8 broadcast messages visible to @me (b0..b7), reject one
    (mapcat (fn [i]
              (let [e (str "@bc" i)]
                [(k/->Fact e "to" "@all")
                 (k/->Fact e "broadcast_to" recipient)]))
            (range 8))
    [(k/->Fact "@bc3" "rejected_by" recipient)]
    ;; noise: messages to someone else, and unrelated facts
    (mapcat (fn [i]
              [(k/->Fact (str "@other" i) "to" "@notme")
               (k/->Fact (str "@th" i) "kind" "thread")])
            (range 40)))))

;; the production pending program (message-audience/pending-query, inlined).
(def pending-q
  {:find "pending_message"
   :strata
   [[{:head {:rel "message_candidate" :args [{:var "e"}]}
      :body [{:rel "fact" :args [{:var "e"} "to" recipient]}]}
     {:head {:rel "message_candidate" :args [{:var "e"}]}
      :body [{:rel "fact" :args [{:var "e"} "broadcast_to" recipient]}
             {:rel "fact" :args [{:var "e"} "to" "@all"]}]}
     {:head {:rel "message_acknowledged" :args [{:var "e"}]}
      :body [{:rel "fact" :args [{:var "e"} "acked_by" recipient]}]}
     {:head {:rel "message_rejected" :args [{:var "e"}]}
      :body [{:rel "fact" :args [{:var "e"} "rejected_by" recipient]}]}]
    [{:head {:rel "pending_message" :args [{:var "e"}]}
      :body [{:rel "message_candidate" :args [{:var "e"}]}
             {:rel "message_acknowledged" :args [{:var "e"}] :neg true}
             {:rel "message_rejected" :args [{:var "e"}] :neg true}]}]]})

;; expected pending set: 45 direct (msg15..msg59) + 7 broadcast (bc0..7 minus bc3)
(def expected-pending
  (set (concat (map (fn [i] [(str "@msg" i)]) (range 15 60))
               (map (fn [i] [(str "@bc" i)]) (remove #{3} (range 8))))))

;; --- (1) run-projected parity with run ---------------------------------------
(def proj (q/project facts))
(let [one-shot (q/run facts pending-q)
      projected (q/run-projected proj pending-q)]
  (chk "run-projected == run (identical :ok set)"
       (= (set (:ok one-shot)) (set (:ok projected))))
  (chk "run-projected returns the exact expected pending set"
       (= expected-pending (set (:ok projected)))))

;; --- (2) run-page-projected parity with run-page (single page) ---------------
(let [a (q/run-page facts pending-q 10 nil)
      b (q/run-page-projected proj pending-q 10 nil)]
  (chk "run-page-projected == run-page (first page rows)" (= (:ok a) (:ok b)))
  (chk "run-page-projected == run-page (cursor)" (= (:next a) (:next b))))

;; --- (3) a full page drain reusing ONE projection loses/duplicates nothing ---
(defn drain [limit]
  (loop [after nil acc []]
    (let [page (q/run-page-projected proj pending-q limit after)]
      (when (:error page)
        (throw (ex-info "page error" {:page page})))
      (let [acc2 (into acc (:ok page))]
        (if (:next page) (recur (:next page) acc2) acc2)))))

(let [drained (drain 7)]
  (chk "shared-projection drain yields every pending row exactly once"
       (= expected-pending (set drained)))
  (chk "shared-projection drain has no duplicate rows"
       (= (count drained) (count (set drained))))
  (chk "shared-projection drain count == expected cardinality"
       (= (count expected-pending) (count drained))))

;; drain at a different page size reaches the identical set (order-stable cursor).
(chk "drain is page-size invariant (limit 3 == limit 50)"
     (= (set (drain 3)) (set (drain 50))))

;; --- (4) reusing the projection object never mutates it ----------------------
(let [before (:edb proj)
      _ (q/run-page-projected proj pending-q 5 nil)
      _ (q/run-projected proj pending-q)
      after (:edb proj)]
  (chk "projection EDB is immutable across reuse" (= before after)))

(def failed (filter (comp not second) @checks))
(doseq [[nm ok] @checks] (println (str "  [" (if ok "PASS" "FAIL") "]  " nm)))
(println (str "\nfram.query projection: " (- (count @checks) (count failed)) " / " (count @checks) " PASS"))
(when (seq failed) (System/exit 1))
