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

(defn ^Boolean lease-expired? [lease now-ms]
  (if (nil? lease) false (<= (:exp lease) now-ms)))

(defn ^Boolean valid-lease-request? [holder resource ttl-ms now-ms max-ms]
  (and (string? holder) (not (.isBlank holder)) (not (.contains holder "|")) (string? resource) (not (.isBlank resource)) (integer? ttl-ms) (pos? ttl-ms) (<= ttl-ms (- max-ms now-ms))))

(defn ^Boolean valid-lease-epoch? [epoch max-epoch]
  (and (integer? epoch) (pos? epoch) (<= epoch max-epoch)))

(defn ^Boolean lease-fence-ok? [lease holder epoch now-ms]
  (and (not (nil? lease)) (> (:exp lease) now-ms) (= (:holder lease) holder) (= (:epoch lease) epoch)))

(defrecord LeaseSnapshot [holder exp epoch])

(defn leasesnapshot-holder [r] (:holder r))

(defn leasesnapshot-exp [r] (:exp r))

(defn leasesnapshot-epoch [r] (:epoch r))

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
