;; A branch ref is boot-critical mutable state: it must round-trip exactly and
;; refuse every shape it cannot vouch for, rather than fold a guessed chain.
;; Run from the repository root: bb -cp out tests/framref_codec_test.clj
(require '[fram.branch :as branch])

(def checks (atom []))
(defn check! [label ok]
  (println (str (if ok "  [PASS] " "  [FAIL] ") label))
  (swap! checks conj [label ok]))

(defn error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (or (:fram/code (ex-data error)) (:type (ex-data error))))))

(def hash-a (apply str (repeat 64 "a")))
(def hash-b (apply str (repeat 64 "b")))

(def document
  (branch/->RefDocument
   "ref-codec-space"
   [(branch/->SegmentRecord hash-a 1 4096)
    (branch/->SegmentRecord hash-b 9 128)]))

(def text (branch/print-ref document))

(println "branch ref codec:")
(println (str "  printed:\n" text))

(check! "a printed ref parses back to the identical document"
        (= document (branch/parse-ref text)))
(check! "an empty chain round-trips"
        (let [empty-doc (branch/empty-ref "ref-codec-space")]
          (= empty-doc (branch/parse-ref (branch/print-ref empty-doc)))))
(check! "the printed ref names its format, space, segments, and CRC"
        (= ["framref/v1"
            "space ref-codec-space"
            (str "segment " hash-a " 1 4096")
            (str "segment " hash-b " 9 128")]
           (vec (butlast (clojure.string/split-lines text)))))

(check! "an unknown ref format is refused by name"
        (= :unsupported-branch-ref-version
           (error-code
            #(branch/parse-ref
              (clojure.string/replace text "framref/v1" "framref/v2")))))
(check! "a duplicate segment line is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref
              (branch/print-ref
               (branch/->RefDocument
                "ref-codec-space"
                [(branch/->SegmentRecord hash-a 1 4096)
                 (branch/->SegmentRecord hash-a 12 128)]))))))
(check! "a malformed segment name is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref
              (clojure.string/replace text hash-a (str "z" (subs hash-a 1)))))))
(check! "a truncated segment name is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref
              (clojure.string/replace text hash-a (subs hash-a 1))))))
(check! "a non-decimal byte count is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref
              (clojure.string/replace text "1 4096" "1 4096x")))))
(check! "an edited ref whose CRC no longer matches is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref
              (clojure.string/replace text "9 128" "9 129")))))
(check! "a missing CRC line is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref
              (clojure.string/join
               "\n" (conj (vec (butlast (clojure.string/split-lines text)))
                          ""))))))
(check! "an unknown line is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref
              (clojure.string/replace text "space ref-codec-space"
                                      "space ref-codec-space\nworld w1")))))
(check! "an empty SpaceId is refused"
        (= :invalid-branch-ref
           (error-code
            #(branch/parse-ref (branch/print-ref (branch/empty-ref ""))))))

;; A ref and the segments it names must agree about their space before any
;; fold begins; the chain is otherwise two stores' bytes in one order.
(defn- member [start end bytes continuation space torn]
  (branch/->ChainMember start end bytes continuation space torn))

(check! "a sound chain reports no fault"
        (nil? (branch/chain-fault
               document
               [(member 1 8 4096 false "ref-codec-space" false)
                (member 9 11 128 true "ref-codec-space" false)]
               (member 12 14 96 true "ref-codec-space" false))))
(check! "a segment from another SpaceId is refused"
        (= "FRAMLOG segment belongs to a different SpaceId"
           (branch/chain-fault
            document
            [(member 1 8 4096 false "other-space" false)
             (member 9 11 128 true "ref-codec-space" false)]
            (member 12 14 96 true "ref-codec-space" false))))
(check! "a tail from another SpaceId is refused"
        (= "FRAMLOG tail belongs to a different SpaceId"
           (branch/chain-fault
            document
            [(member 1 8 4096 false "ref-codec-space" false)
             (member 9 11 128 true "ref-codec-space" false)]
            (member 12 14 96 true "other-space" false))))
(check! "a segment whose size differs from its record is refused"
        (= "FRAMLOG segment size does not match its branch ref record"
           (branch/chain-fault
            document
            [(member 1 8 4095 false "ref-codec-space" false)
             (member 9 11 128 true "ref-codec-space" false)]
            (member 12 14 96 true "ref-codec-space" false))))
(check! "a chain the ref does not name is refused"
        (= "FRAMLOG branch ref does not name the segments that were read"
           (branch/chain-fault
            document
            [(member 1 8 4096 false "ref-codec-space" false)]
            (member 12 14 96 true "ref-codec-space" false))))
(check! "a segment that does not continue the previous one is refused"
        (= "FRAMLOG chain segment does not continue the previous transaction sequence"
           (branch/chain-fault
            (branch/->RefDocument
             "ref-codec-space"
             [(branch/->SegmentRecord hash-a 1 4096)
              (branch/->SegmentRecord hash-b 12 128)])
            [(member 1 8 4096 false "ref-codec-space" false)
             (member 12 14 128 true "ref-codec-space" false)]
            (member 15 15 96 true "ref-codec-space" false))))
(check! "a tail that does not continue the sealed chain is refused"
        (= "FRAMLOG branch tail does not continue the sealed chain"
           (branch/chain-fault
            document
            [(member 1 8 4096 false "ref-codec-space" false)
             (member 9 11 128 true "ref-codec-space" false)]
            (member 40 41 96 true "ref-codec-space" false))))
(check! "a torn sealed segment is refused"
        (= "FRAMLOG segment ends inside a transaction frame"
           (branch/chain-fault
            document
            [(member 1 8 4096 false "ref-codec-space" true)
             (member 9 11 128 true "ref-codec-space" false)]
            (member 12 14 96 true "ref-codec-space" false))))
(check! "a chained segment without the continuation flag is refused"
        (= "FRAMLOG chain segment after the base segment must carry the continuation flag"
           (branch/chain-fault
            document
            [(member 1 8 4096 false "ref-codec-space" false)
             (member 9 11 128 false "ref-codec-space" false)]
            (member 12 14 96 true "ref-codec-space" false))))
(check! "a base segment carrying the continuation flag is refused"
        (= "FRAMLOG base chain segment must not carry the continuation flag"
           (branch/chain-fault
            document
            [(member 1 8 4096 true "ref-codec-space" false)
             (member 9 11 128 true "ref-codec-space" false)]
            (member 12 14 96 true "ref-codec-space" false))))
(check! "a chained tail without the continuation flag is refused"
        (= "FRAMLOG branch tail must carry the continuation flag"
           (branch/chain-fault
            document
            [(member 1 8 4096 false "ref-codec-space" false)
             (member 9 11 128 true "ref-codec-space" false)]
            (member 12 14 96 false "ref-codec-space" false))))

(check! "a fork plan appends the sealed segment to the parent chain"
        (= [hash-a hash-b]
           (mapv branch/segmentrecord-sha256
                 (branch/refdocument-segments
                  (branch/forkplan-document
                   (branch/fork-plan
                    (branch/->RefDocument
                     "ref-codec-space" [(branch/->SegmentRecord hash-a 1 4096)])
                    (branch/->SegmentRecord hash-b 9 128)
                    11))))))
(check! "a fork plan refuses a segment the parent chain already names"
        (= :segment-already-sealed
           (error-code
            #(branch/fork-plan
              document (branch/->SegmentRecord hash-b 20 64) 19))))

(check! "branch names that cannot address a ref file are refused"
        (= [false false false false false true true]
           (mapv branch/valid-branch-name?
                 ["" "a/b" ".." "a..b" "-lead" "default" "lane-2.a"])))
(check! "layout puts segments, refs, and branch tails beside the store"
        (= ["/s/log.segments" "/s/log.refs" "/s/log.branches"
            (str "/s/log.segments/" hash-a)
            "/s/log.refs/child" "/s/log.branches/child" "/s/log"
            "/s/log.snapshot"]
           [(branch/segments-directory "/s/log")
            (branch/refs-directory "/s/log")
            (branch/branches-directory "/s/log")
            (branch/segment-path "/s/log" hash-a)
            (branch/ref-path "/s/log" "child")
            (branch/branch-tail-path "/s/log" "child")
            (branch/branch-tail-path "/s/log" branch/default-branch)
            (branch/snapshot-path "/s/log")]))

(let [failures (remove second @checks)]
  (if (empty? failures)
    (do
      (println "\nbranch ref codec:" (count @checks) "/" (count @checks) "PASS")
      (shutdown-agents))
    (do
      (println "\nbranch ref codec:" (count failures) "FAILED")
      (shutdown-agents)
      (System/exit 1))))
