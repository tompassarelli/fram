(ns coord-commit
  (:require [fram.types :as t]))

(defn ^Boolean version-conflict? [^Boolean single base-version expected-version]
  (if (nil? expected-version) false (and single (> base-version expected-version))))

(defn ^Boolean expected-value-match? [live-values expected-value]
  (if (nil? expected-value) false (contains? live-values expected-value)))

(defrecord CommitIntent [index pred kind r single base-version expected-version live-values expected-value cycle])

(defn commitintent-index [r] (:index r))

(defn commitintent-pred [r] (:pred r))

(defn commitintent-kind [r] (:kind r))

(defn commitintent-r [r] (:r r))

(defn commitintent-single [r] (:single r))

(defn commitintent-base-version [r] (:base-version r))

(defn commitintent-expected-version [r] (:expected-version r))

(defn commitintent-live-values [r] (:live-values r))

(defn commitintent-expected-value [r] (:expected-value r))

(defn commitintent-cycle [r] (:cycle r))

(defn commit-plan [head-version intents]
  (loop [remaining intents
   writes []
   idempotent []]
  (if (empty? remaining) {:writes writes :idempotent idempotent} (let [intent (first remaining)
   pred (:pred intent)]
  (cond
  (version-conflict? (:single intent) (:base-version intent) (:expected-version intent)) {:reject :conflict :version head-version :at (:index intent) :pred pred}
  (:cycle intent) {:reject [(str pred " cycle")] :version head-version :at (:index intent) :pred pred}
  (and (not (:single intent)) (expected-value-match? (:live-values intent) (:expected-value intent))) (recur (vec (rest remaining)) writes (conj idempotent pred))
  :else (recur (vec (rest remaining)) (conj writes {:pred pred :kind (:kind intent) :r (:r intent)}) idempotent))))))

(defrecord GroupBatchItem [path])

(defn groupbatchitem-path [r] (:path r))

(defrecord GroupBatch [path indices])

(defn groupbatch-path [r] (:path r))

(defn groupbatch-indices [r] (:indices r))

(defn- group-index [groups path]
  (loop [remaining groups
   index 0]
  (if (empty? remaining) nil (if (= (:path (first remaining)) path) index (recur (vec (rest remaining)) (inc index))))))

(defn group-batch-plan [items]
  (loop [remaining items
   index 0
   groups []]
  (if (empty? remaining) groups (let [item (first remaining)
   group-index (group-index groups (:path item))]
  (if (nil? group-index) (recur (vec (rest remaining)) (inc index) (conj groups (->GroupBatch (:path item) [index]))) (let [group (nth groups group-index)]
  (recur (vec (rest remaining)) (inc index) (assoc groups group-index (->GroupBatch (:path group) (conj (:indices group) index))))))))))

(defrecord LeaseSnapshot [holder exp epoch])

(defn leasesnapshot-holder [r] (:holder r))

(defn leasesnapshot-exp [r] (:exp r))

(defn leasesnapshot-epoch [r] (:epoch r))

(defn ^Boolean lease-expired? [lease now-ms]
  (if (nil? lease) false (<= (:exp lease) now-ms)))

(defn ^Boolean valid-lease-request? [holder resource ttl-ms now-ms max-ms]
  (and (string? holder) (not (.isBlank holder)) (not (.contains holder "|")) (string? resource) (not (.isBlank resource)) (integer? ttl-ms) (pos? ttl-ms) (<= ttl-ms (- max-ms now-ms))))

(defn ^Boolean valid-lease-epoch? [epoch max-epoch]
  (and (integer? epoch) (pos? epoch) (<= epoch max-epoch)))

(defn ^Boolean lease-fence-ok? [lease holder epoch now-ms]
  (and (not (nil? lease)) (> (:exp lease) now-ms) (= (:holder lease) holder) (= (:epoch lease) epoch)))

(defn ^Boolean lease-held? [lease now-ms]
  (if (nil? lease) false (> (:exp lease) now-ms)))

(defn lease-grant-decision [lease holder resource ttl-ms now-ms max-ms version]
  (cond
  (not (valid-lease-request? holder resource ttl-ms now-ms max-ms)) {:reject :invalid-lease-request :version version}
  (and (lease-held? lease now-ms) (not= (:holder lease) holder)) {:reject :held :holder (:holder lease) :exp (:exp lease) :version version}
  :else {:persist true}))

(defn lease-renew-decision [lease holder resource expected-epoch ttl-ms now-ms max-ms max-epoch version]
  (cond
  (or (not (valid-lease-request? holder resource ttl-ms now-ms max-ms)) (not (valid-lease-epoch? expected-epoch max-epoch))) {:reject :invalid-lease-request :version version}
  (and (lease-held? lease now-ms) (= (:holder lease) holder) (= (:epoch lease) expected-epoch)) {:persist true}
  :else {:reject :fence-lost :version version}))

(defn lease-release-decision [lease holder epoch ^Boolean require-epoch version]
  (if (and (not (nil? lease)) (= (:holder lease) holder) (or (not require-epoch) (= (:epoch lease) epoch))) {:retract true} {:ok version :noop true}))

(defrecord GroupFlushPolicy [min-items drain-limit])

(defn groupflushpolicy-min-items [r] (:min-items r))

(defn groupflushpolicy-drain-limit [r] (:drain-limit r))

(defn ^GroupFlushPolicy group-flush-policy [pending-count]
  (->GroupFlushPolicy 1 2147483647))

(defn ^Boolean group-flush-ready? [^GroupFlushPolicy policy batch-count]
  (>= batch-count (:min-items policy)))

(defn queue-admission-decision [^Boolean deferred]
  (if deferred :defer :await))

(defn group-lock-order []
  [:group-io :append-admission])

(defn snapshot-replay-decision [^Boolean enabled validation-reason]
  (if (or (not enabled) (some? validation-reason)) :fold :replay))

(defn boot-install-decision [^Boolean candidate-present ^Boolean verification-enabled ^Boolean verification-ok]
  (if (and candidate-present (or (not verification-enabled) verification-ok)) :snapshot :fold))

(defn sidecar-validation-decision [^Boolean sidecar-map ^Boolean seq-valid ^Boolean watermark-valid ^Boolean watermark-agrees ^Boolean log-set-matches ^Boolean offsets-valid ^Boolean stamped ^Boolean fingerprint-computable ^Boolean fingerprint-matches bad-identity past-eof]
  (cond
  (not sidecar-map) {:reason :no-sidecar}
  (not seq-valid) {:reason :seq-malformed}
  (not watermark-valid) {:reason :watermark-malformed}
  (not watermark-agrees) {:reason :watermark-disagrees}
  (not log-set-matches) {:reason :log-set-mismatch}
  (not offsets-valid) {:reason :offset-malformed}
  (not stamped) {:reason :unstamped}
  (not fingerprint-computable) {:reason :fingerprint-uncomputable}
  (not fingerprint-matches) {:reason :fingerprint-mismatch}
  (some? bad-identity) {:reason :identity-mismatch :label bad-identity}
  (some? past-eof) {:reason :past-eof :label past-eof}
  :else {:reason nil}))

(defn reload-entry-decision [^Boolean nonblocking-if-active active-reloads]
  (if (and nonblocking-if-active (pos? active-reloads)) :in-progress :participate))

(defn reload-watermark-decision [^Boolean split-logs from-tx tail-max log-max]
  (if split-logs (if (< log-max from-tx) :refused :whole) (cond
  (> tail-max from-tx) :tail
  (< log-max from-tx) :refused
  :else :whole)))

(defn reload-install-decision [candidate-mode ^Boolean same-target ^Boolean exact-base ^Boolean generation-advanced ^Boolean stamps-converged]
  (cond
  (= candidate-mode :raced) :retry
  (and same-target exact-base (= candidate-mode :refused)) :refused
  (and same-target exact-base (= candidate-mode :install)) :installed
  (and same-target generation-advanced stamps-converged) :superseded
  :else :retry))

(defn reload-result-decision [result attempt max-attempts]
  (if (= result :retry) (if (< attempt (dec max-attempts)) :retry :exhausted) :return))
