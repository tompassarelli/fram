;; Focused producer/validator contract for durable graph-edit receipts.
;;   bb -cp out tests/receipt_envelope_producer_test.clj
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[fram.rt :as rt]
         '[fram.schema :as s]
         '[fram.store :as c])

(binding [*command-line-args* []]
  (load-file (str (System/getProperty "user.dir") "/coord_daemon.clj")))

(defn daemon-private [sym]
  (var-get (ns-resolve 'coord-daemon sym)))

(def apply-ops (daemon-private 'apply-candidate-ops!))
(def annotate-lines (daemon-private 'annotate-edit-batch-lines))
(def make-envelope (daemon-private 'edit-batch-envelope))
(def digest-ops (daemon-private 'edit-ops-digest))
(def recover-outcome (daemon-private 'persisted-edit-outcome))

(def checks (atom []))
(defn chk [label ok] (swap! checks conj [label (boolean ok)]))

(def st (c/new-store))
(def bootstrap-tx (c/begin-tx! st "receipt-producer-test"))
(s/setup! st bootstrap-tx)
(def clone {:store st :log nil :lock (Object.)})
(def base (coord/current-seq clone))
(def no-op-retract
  [:retract "@receipt-probe#1" "f0" "@receipt-probe#2"])
(def advancing-assert
  [:assert "@receipt-probe#1" "f0" "@receipt-probe#2"])
(def applied
  (apply-ops clone [no-op-retract advancing-assert] base nil))
(def raw-lines (get-in applied [:ok :lines]))
(def installed-ops (get-in applied [:ok :installed-ops]))
(def final-version (coord/current-seq clone))

(chk "a successful no-op retract is not an installed operation"
     (= [advancing-assert] installed-ops))
(chk "only the advancing assertion produces a durable fact row"
     (= 1 (count raw-lines)))
(chk "the first durable row follows the exclusive base version"
     (= (inc base) (:tx (edn/read-string (first raw-lines)))))
(chk "final version equals base plus installed operation count"
     (= final-version (+ base (count installed-ops))))

(def idempotent
  (apply-ops clone [advancing-assert] final-version nil))
(chk "existing idempotent assert behavior remains zero-movement"
     (= {:lines [] :events [] :installed-ops []} (:ok idempotent)))
(chk "idempotent assertion leaves the clone version unchanged"
     (= final-version (coord/current-seq clone)))

(def temp-dir
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "fram-receipt-producer-"
    (make-array java.nio.file.attribute.FileAttribute 0))))
(def valid-log (str temp-dir "/valid.log"))
(def forged-log (str temp-dir "/forged.log"))
(def torn-log (str temp-dir "/torn.log"))
(def candidate "00000000-0000-4000-8000-000000000001")
(def module "src.fram.receipt-probe")
(def tracked-path (str temp-dir "/receipt-probe.bclj"))
(def ops-digest (digest-ops installed-ops))
(def edn-digest (rt/sha256-text "(def receipt-probe true)"))
(def receipt
  {:candidate candidate
   :batch candidate
   :module module
   :path tracked-path
   :base-version base
   :version final-version
   :ops (count installed-ops)
   :installed (count raw-lines)
   :ops-digest ops-digest
   :edn-digest edn-digest})
(def fact-lines (annotate-lines raw-lines receipt))
(def request
  {:candidate candidate
   :version base
   :module module
   :path tracked-path
   :ops-digest ops-digest
   :edn-digest edn-digest})

(def valid-envelope (make-envelope valid-log receipt fact-lines))
(spit valid-log
      (str (pr-str valid-envelope) "\n" (apply str fact-lines)))
(def valid-bytes (java.nio.file.Files/readAllBytes (.toPath (io/file valid-log))))
(def recovered (recover-outcome valid-log request))
(chk "producer output satisfies the closed receipt envelope validator"
     (rt/valid-edit-batch-envelope? valid-envelope))
(chk "a fresh disk scan reconstructs the exact valid receipt"
     (and (true? (:recovered recovered))
          (= candidate (:candidate recovered))
          (= final-version (:version recovered))
          (= 1 (:installed recovered))))
(chk "valid cold reconstruction is read-only"
     (java.util.Arrays/equals
      valid-bytes
      (java.nio.file.Files/readAllBytes (.toPath (io/file valid-log)))))

(def forged-base (make-envelope forged-log receipt fact-lines))
(def forged-payload
  (assoc forged-base :fram-edit-final-version base))
(def forged-envelope
  (assoc forged-payload
         :fram-edit-seal-sha (rt/edit-batch-envelope-seal forged-payload)))
(spit forged-log
      (str (pr-str forged-envelope) "\n" (apply str fact-lines)))
(def forged-bytes
  (java.nio.file.Files/readAllBytes (.toPath (io/file forged-log))))
(chk "a resealed forged version interval is rejected"
     (nil? (recover-outcome forged-log request)))
(chk "forged receipt refusal is byte-preserving"
     (java.util.Arrays/equals
      forged-bytes
      (java.nio.file.Files/readAllBytes (.toPath (io/file forged-log)))))

(def torn-envelope (make-envelope torn-log receipt fact-lines))
(def torn-fragment
  (subs (first fact-lines) 0 (quot (count (first fact-lines)) 2)))
(spit torn-log (str (pr-str torn-envelope) "\n" torn-fragment))
(def torn-bytes
  (java.nio.file.Files/readAllBytes (.toPath (io/file torn-log))))
(chk "a torn receipt batch cannot reconstruct a committed outcome"
     (nil? (recover-outcome torn-log request)))
(chk "torn receipt refusal is byte-preserving"
     (java.util.Arrays/equals
      torn-bytes
      (java.nio.file.Files/readAllBytes (.toPath (io/file torn-log)))))

(doseq [f (reverse (file-seq temp-dir))]
  (io/delete-file f true))

(let [failures (filter (comp not second) @checks)]
  (doseq [[label ok] @checks]
    (println (if ok "PASS" "FAIL") label))
  (if (seq failures)
    (do
      (println (str "receipt-envelope-producer: "
                    (count failures) " FAILED"))
      (System/exit 1))
    (println (str "receipt-envelope-producer: "
                  (count @checks) "/" (count @checks) " PASS"))))
