#!/usr/bin/env bash
set -euo pipefail

repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"
daemon_pid=

cleanup() {
  if [[ -n "${daemon_pid:-}" ]] && kill -0 "$daemon_pid" 2>/dev/null; then
    kill -TERM "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true
  fi
  rm -rf "${test_dir:?}"
}
trap cleanup EXIT

zig=(direnv exec /home/tom/code/beagle/main zig)
"${zig[@]}" build-exe "$repo/src/zig/daemon.zig" \
  -OReleaseSafe \
  "-femit-bin=$test_dir/fram-daemon-zig" \
  --cache-dir "$test_dir/cache" \
  --global-cache-dir "$test_dir/global-cache"

free_port() {
  bb -e '(with-open [socket (java.net.ServerSocket. 0)]
           (print (.getLocalPort socket)))'
}

request() {
  local port=$1
  local payload=$2
  FRAM_TEST_PORT="$port" FRAM_TEST_REQUEST="$payload" \
    bb -e '
      (require (quote [clojure.edn :as edn])
               (quote [clojure.java.io :as io]))
      (with-open [socket (java.net.Socket.)]
        (.connect socket
                  (java.net.InetSocketAddress.
                   "127.0.0.1"
                   (Integer/parseInt (System/getenv "FRAM_TEST_PORT")))
                  500)
        (.setSoTimeout socket 2000)
        (with-open [writer (io/writer (.getOutputStream socket))
                    reader (java.io.PushbackReader.
                            (io/reader (.getInputStream socket)))]
          (.write writer (str (System/getenv "FRAM_TEST_REQUEST") "\n"))
          (.flush writer)
          (prn (edn/read reader))))'
}

assert_response() {
  local response=$1
  local predicate=$2
  FRAM_TEST_RESPONSE="$response" FRAM_TEST_PREDICATE="$predicate" \
    bb -e '
      (require (quote [clojure.edn :as edn]))
      (let [response (edn/read-string (System/getenv "FRAM_TEST_RESPONSE"))
            predicate (eval
                       (edn/read-string
                        (System/getenv "FRAM_TEST_PREDICATE")))]
        (when-not (predicate response)
          (binding [*out* *err*]
            (println "unexpected response:" (pr-str response)))
          (System/exit 1)))'
}

fenced_request() {
  local port=$1
  local expected_log=$2
  local nested=$3
  request "$port" \
    "{:op :for-log :expected-log \"$expected_log\" :request $nested}"
}

log_a="$test_dir/a.log"
log_b="$test_dir/b.log"
printf '%s\n' \
  '{:tx 1, :op "assert", :l "@seed", :p "title", :r "one"}' \
  '{:tx 3, :op "assert", :l "@seed", :p "note", :r "two"}' \
  '{:tx 3, :op "assert", :l "@a", :p "depends_on", :r "@b"}' \
  '{:tx 3, :op "assert", :l "@b", :p "depends_on", :r "@c"}' \
  '{:tx 3, :op "assert", :l "@c", :p "depends_on", :r "@d"}' \
  '{:tx 3, :op "assert", :l "@title", :p "predicate_name", :r "title"}' \
  '{:tx 3, :op "assert", :l "@title", :p "predicate_alias", :r ":title"}' \
  '{:tx 3, :op "assert", :l "@title", :p "cardinality", :r "single"}' \
  '{:tx 3, :op "assert", :l "@title", :p "value_kind", :r "literal"}' \
  >"$log_a"
: >"$log_b"
canonical_a="$(realpath "$log_a")"
canonical_b="$(realpath "$log_b")"
initial_hash="$(sha256sum "$log_a" | cut -d' ' -f1)"

port="$(free_port)"
FRAM_REQUIRE_LOG_FENCE=1 \
  "$test_dir/fram-daemon-zig" serve-flat "$port" "$log_a" \
  >"$test_dir/daemon.out" 2>&1 &
daemon_pid=$!

ready=
for _ in $(seq 1 100); do
  if response="$(fenced_request "$port" "$canonical_a" "{:op :version}" \
      2>/dev/null)"; then
    ready=$response
    break
  fi
  sleep 0.025
done
[[ -n "$ready" ]]
assert_response "$ready" '(fn [r] (= 3 (:version r)))'

status="$(fenced_request "$port" "$canonical_a" "{:op :status}")"
assert_response "$status" \
  "(fn [r] (and (= 3 (:version r))
                (= \"$canonical_a\" (:log r))
                (true? (get-in r [:writer-authority :write-authorized]))))"

facts="$(fenced_request "$port" "$canonical_a" '{:op :facts}')"
assert_response "$facts" \
  "(fn [r] (and (= 3 (:version r)) (= \"$canonical_a\" (:log r))
                (= #{[\"@seed\" \"title\" \"one\"] [\"@seed\" \"note\" \"two\"]
                     [\"@a\" \"depends_on\" \"@b\"]
                     [\"@b\" \"depends_on\" \"@c\"]
                     [\"@c\" \"depends_on\" \"@d\"]
                     [\"@title\" \"predicate_name\" \"title\"]
                     [\"@title\" \"predicate_alias\" \":title\"]
                     [\"@title\" \"cardinality\" \"single\"]
                     [\"@title\" \"value_kind\" \"literal\"]}
                   (set (:facts r)))))"
scoped_seed="$(fenced_request "$port" "$canonical_a" \
  '{:op :facts-for-subjects :subjects ["@seed"]}')"
assert_response "$scoped_seed" \
  "(fn [r] (and (= 3 (:version r)) (= \"$canonical_a\" (:log r))
                (= [[\"@seed\" \"title\" \"one\"] [\"@seed\" \"note\" \"two\"]]
                   (:facts r))))"

joined="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "seed-row" :rules [{:head {:rel "seed-row" :args [{:var "subject"} {:var "title"} {:var "note"}]} :body [{:rel "fact" :args [{:var "subject"} "title" {:var "title"}]} {:rel "fact" :args [{:var "subject"} "note" {:var "note"}]}]}]}}')"
assert_response "$joined" \
  '(fn [r] (and (= [["@seed" "one" "two"]] (:ok r))
                (= 3 (:version r))
                (= "scan" (:engine r))))'

triple_joined="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "seed-row" :rules [{:head {:rel "seed-row" :args [{:var "subject"} {:var "title"} {:var "note"}]} :body [{:rel "triple" :args [{:var "subject"} "title" {:var "title"}]} {:rel "triple" :args [{:var "subject"} "note" {:var "note"}]}]}]}}')"
FRAM_FACT_QUERY="$joined" FRAM_TRIPLE_QUERY="$triple_joined" bb -e '
  (require (quote [clojure.edn :as edn]))
  (let [fact-result (edn/read-string (System/getenv "FRAM_FACT_QUERY"))
        triple-result (edn/read-string (System/getenv "FRAM_TRIPLE_QUERY"))]
    (when-not (= (:ok fact-result) (:ok triple-result))
      (binding [*out* *err*]
        (println "fact/triple query mismatch:" (pr-str fact-result) (pr-str triple-result)))
      (System/exit 1)))'

predicate_metadata="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "predicate-meta" :rules [{:head {:rel "predicate-meta" :args [{:var "predicate"} {:var "pid"} {:var "alias"} {:var "canonical"} {:var "cardinality"} {:var "kind"}]} :body [{:rel "fact" :args ["@seed" {:var "predicate"} "one"]} {:rel "predicate" :args [{:var "pid"} {:var "predicate"} {:var "canonical"} {:var "cardinality"} {:var "kind"}]} {:rel "predicate" :args [{:var "pid"} {:var "alias"} {:var "canonical"} {:var "cardinality"} {:var "kind"}]}]} {:head {:rel "predicate-meta" :args [{:var "predicate"} {:var "pid"} {:var "alias"} {:var "canonical"} {:var "cardinality"} {:var "kind"}]} :body [{:rel "fact" :args ["@a" {:var "predicate"} "@b"]} {:rel "predicate" :args [{:var "pid"} {:var "predicate"} {:var "canonical"} {:var "cardinality"} {:var "kind"}]} {:rel "predicate" :args [{:var "pid"} {:var "alias"} {:var "canonical"} {:var "cardinality"} {:var "kind"}]}]}]}}')"
assert_response "$predicate_metadata" \
  '(fn [r] (= [["depends_on" "@depends_on" "depends_on" "depends_on" "multi" "ref"]
               ["title" "@title" ":title" "title" "single" "literal"]
               ["title" "@title" "title" "title" "single" "literal"]]
              (:ok r)))'

fact_ids="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "identified" :rules [{:head {:rel "identified" :args [{:var "cid"} {:var "predicate"} {:var "value"}]} :body [{:rel "fact-id" :args [{:var "cid"} "@seed" {:var "predicate"} {:var "value"}]}]}]}}')"
assert_response "$fact_ids" \
  '(fn [r] (= [["c0" "title" "one"]
               ["c1" "note" "two"]]
              (:ok r)))'

deterministic="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "seed-facts" :rules [{:head {:rel "seed-facts" :args [{:var "predicate"} {:var "value"}]} :body [{:rel "fact" :args ["@seed" {:var "predicate"} {:var "value"}]}]}]}}')"
assert_response "$deterministic" \
  '(fn [r] (= [["note" "two"] ["title" "one"]]
              (:ok r)))'

closure="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "reaches" :rules [{:head {:rel "reaches" :args [{:var "from"} {:var "to"}]} :body [{:rel "fact" :args [{:var "from"} "depends_on" {:var "to"}]}]} {:head {:rel "reaches" :args [{:var "from"} {:var "to"}]} :body [{:rel "reaches" :args [{:var "from"} {:var "via"}]} {:rel "fact" :args [{:var "via"} "depends_on" {:var "to"}]}]}]}}')"
assert_response "$closure" \
  '(fn [r] (= [["@a" "@b"] ["@a" "@c"] ["@a" "@d"]
               ["@b" "@c"] ["@b" "@d"] ["@c" "@d"]]
              (:ok r)))'

mutual="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "right" :rules [{:head {:rel "left" :args [{:var "subject"}]} :body [{:rel "fact" :args [{:var "subject"} "title" {:var "title"}]}]} {:head {:rel "right" :args [{:var "subject"}]} :body [{:rel "left" :args [{:var "subject"}]}]} {:head {:rel "left" :args [{:var "subject"}]} :body [{:rel "right" :args [{:var "subject"}]}]}]}}')"
assert_response "$mutual" '(fn [r] (= [["@seed"]] (:ok r)))'

unbound_recursive_head="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "bad" :rules [{:head {:rel "bad" :args [{:var "unbound"}]} :body [{:rel "bad" :args [{:var "bound"}]}]}]}}')"
assert_response "$unbound_recursive_head" '(fn [r] (= :invalid-query (:code r)))'

stratified_anti_join="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "open" :strata [[{:head {:rel "done" :args [{:var "subject"}]} :body [{:rel "fact" :args [{:var "subject"} "depends_on" "@c"]}]}] [{:head {:rel "open" :args [{:var "subject"}]} :body [{:rel "fact" :args [{:var "subject"} "depends_on" {:var "target"}]} {:rel "done" :args [{:var "subject"}] :neg true}]}]]}}')"
assert_response "$stratified_anti_join" \
  '(fn [r] (= [["@a"] ["@c"]] (:ok r)))'

unstratified="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find "q" :strata [[{:head {:rel "p" :args [{:var "subject"}]} :body [{:rel "fact" :args [{:var "subject"} "depends_on" "@c"]}]}] [{:head {:rel "q" :args [{:var "subject"}]} :body [{:rel "fact" :args [{:var "subject"} "depends_on" {:var "target"}]} {:rel "p" :args [{:var "subject"}] :neg true}]} {:head {:rel "p" :args [{:var "subject"}]} :body [{:rel "q" :args [{:var "subject"}]}]}]]}}')"
assert_response "$unstratified" '(fn [r] (= :invalid-query (:code r)))'

aggregate="$(fenced_request "$port" "$canonical_a" \
  '{:op :query :query {:find {:rel "values" :group [0] :agg [{:op :count}]} :rules [{:head {:rel "values" :args [{:var "subject"}]} :body [{:rel "fact" :args [{:var "subject"} "title" {:var "title"}]}]}]}}')"
assert_response "$aggregate" '(fn [r] (= :unsupported-query (:code r)))'

mismatch="$(fenced_request "$port" "$canonical_b" "{:op :version}")"
assert_response "$mismatch" \
  "(fn [r] (and (= :log-mismatch (:code r))
                (= \"$canonical_b\" (:expected-log r))
                (= \"$canonical_a\" (:served-log r))))"

unwrapped="$(request "$port" '{:op :version}')"
assert_response "$unwrapped" \
  "(fn [r] (and (= :log-fence-required (:code r))
                (= \"$canonical_a\" (:served-log r))))"

single="$(fenced_request "$port" "$canonical_a" \
  '{:op :assert :te "@mutation" :p "progress" :r "one"}')"
assert_response "$single" '(fn [r] (= 4 (:ok r)))'

batch="$(fenced_request "$port" "$canonical_a" \
  '{:op :assert-batch :te "@mutation" :facts [{:p "alpha" :r "A"} {:p "beta" :r "B"}]}')"
assert_response "$batch" \
  '(fn [r] (and (= 5 (:ok r))
                (= ["alpha" "beta"] (:written r))
                (empty? (:idempotent r))
                (true? (:batch r))))'

FRAM_TEST_LOG="$log_a" FRAM_TEST_TX=5 bb -e '
  (require (quote [clojure.edn :as edn])
           (quote [clojure.string :as str]))
  (let [tx (parse-long (System/getenv "FRAM_TEST_TX"))
        records (->> (str/split-lines (slurp (System/getenv "FRAM_TEST_LOG")))
                     (map edn/read-string)
                     (filter #(and (= tx (:tx %))
                                   (= "@mutation" (:l %))
                                   (#{"alpha" "beta"} (:p %))))
                     vec)]
    (when-not (and (= 2 (count records))
                   (= #{tx} (set (map :tx records))))
      (binding [*out* *err*]
        (println "batch was not one fsync-visible transaction:" (pr-str records)))
      (System/exit 1)))'

before_stale_hash="$(sha256sum "$log_a" | cut -d' ' -f1)"
before_stale_size="$(stat -c %s "$log_a")"
stale="$(fenced_request "$port" "$canonical_a" \
  '{:op :assert-at-version :te "@stale" :p "marker" :r "must-not-land" :base 4}')"
assert_response "$stale" \
  '(fn [r] (and (= :conflict (:reject r)) (= 5 (:version r))))'
[[ "$before_stale_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]
[[ "$before_stale_size" == "$(stat -c %s "$log_a")" ]]

invalid="$(fenced_request "$port" "$canonical_a" \
  '{:op :assert :te "@mutation" :p "progress" :r "bad" :bogus true}')"
assert_response "$invalid" '(fn [r] (= :invalid-request (:code r)))'
[[ "$before_stale_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]

retracted="$(fenced_request "$port" "$canonical_a" \
  '{:op :retract :te "@mutation" :p "beta" :r "B"}')"
assert_response "$retracted" '(fn [r] (= 6 (:ok r)))'

scoped_mutation="$(fenced_request "$port" "$canonical_a" \
  '{:op :facts-for-subjects :subjects ["@mutation" "@missing"]}')"
assert_response "$scoped_mutation" \
  "(fn [r] (and (= 6 (:version r)) (= \"$canonical_a\" (:log r))
                (= #{[\"@mutation\" \"progress\" \"one\"] [\"@mutation\" \"alpha\" \"A\"]}
                   (set (:facts r)))))"

lease="$(fenced_request "$port" "$canonical_a" \
  '{:op :acquire-lease :res "native-write" :holder "holder-a" :ttl-ms 5000}')"
assert_response "$lease" \
  '(fn [r] (and (= 7 (:ok r)) (= "holder-a" (:holder r)) (= (:ok r) (:epoch r))))'

lease_epoch="$(FRAM_TEST_RESPONSE="$lease" bb -e '(require (quote [clojure.edn :as edn])) (print (:epoch (edn/read-string (System/getenv "FRAM_TEST_RESPONSE"))))')"
fenced_write="$(fenced_request "$port" "$canonical_a" \
  "{:op :assert-with-fence :res \"native-write\" :holder \"holder-a\" :epoch $lease_epoch :te \"@lease-proof\" :p \"marker\" :r \"accepted\"}")"
assert_response "$fenced_write" '(fn [r] (= 8 (:ok r)))'

before_fence_reject_hash="$(sha256sum "$log_a" | cut -d' ' -f1)"
stale_fenced_write="$(fenced_request "$port" "$canonical_a" \
  "{:op :assert-with-fence :res \"native-write\" :holder \"holder-a\" :epoch 1 :te \"@lease-proof\" :p \"marker\" :r \"rejected\"}")"
assert_response "$stale_fenced_write" '(fn [r] (= :fence-lost (:reject r)))'
[[ "$before_fence_reject_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]

renewed="$(fenced_request "$port" "$canonical_a" \
  "{:op :renew-lease :res \"native-write\" :holder \"holder-a\" :epoch $lease_epoch :ttl-ms 5000}")"
assert_response "$renewed" '(fn [r] (and (= 9 (:ok r)) (= (:ok r) (:epoch r))))'
renewed_epoch="$(FRAM_TEST_RESPONSE="$renewed" bb -e '(require (quote [clojure.edn :as edn])) (print (:epoch (edn/read-string (System/getenv "FRAM_TEST_RESPONSE"))))')"
released="$(fenced_request "$port" "$canonical_a" \
  "{:op :release-lease :res \"native-write\" :holder \"holder-a\" :epoch $renewed_epoch}")"
assert_response "$released" '(fn [r] (= 10 (:ok r)))'

expiring="$(fenced_request "$port" "$canonical_a" \
  '{:op :acquire-lease :res "native-expiry" :holder "old-holder" :ttl-ms 20}')"
assert_response "$expiring" '(fn [r] (= 11 (:ok r)))'
old_epoch="$(FRAM_TEST_RESPONSE="$expiring" bb -e '(require (quote [clojure.edn :as edn])) (print (:epoch (edn/read-string (System/getenv "FRAM_TEST_RESPONSE"))))')"
sleep 0.05
successor="$(fenced_request "$port" "$canonical_a" \
  '{:op :acquire-lease :res "native-expiry" :holder "new-holder" :ttl-ms 5000}')"
assert_response "$successor" '(fn [r] (and (= 12 (:ok r)) (= "new-holder" (:holder r))))'
before_expired_reject_hash="$(sha256sum "$log_a" | cut -d' ' -f1)"
expired_write="$(fenced_request "$port" "$canonical_a" \
  "{:op :assert-with-fence :res \"native-expiry\" :holder \"old-holder\" :epoch $old_epoch :te \"@expired-proof\" :p \"marker\" :r \"rejected\"}")"
assert_response "$expired_write" '(fn [r] (= :fence-lost (:reject r)))'
[[ "$before_expired_reject_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]
successor_epoch="$(FRAM_TEST_RESPONSE="$successor" bb -e '(require (quote [clojure.edn :as edn])) (print (:epoch (edn/read-string (System/getenv "FRAM_TEST_RESPONSE"))))')"
successor_release="$(fenced_request "$port" "$canonical_a" \
  "{:op :release-lease :res \"native-expiry\" :holder \"new-holder\" :epoch $successor_epoch}")"
assert_response "$successor_release" '(fn [r] (= 13 (:ok r)))'

duplicate_port="$(free_port)"
set +e
FRAM_REQUIRE_LOG_FENCE=1 timeout 5 \
  "$test_dir/fram-daemon-zig" serve-flat "$duplicate_port" "$log_a" \
  >"$test_dir/duplicate.out" 2>&1
duplicate_status=$?
set -e
[[ $duplicate_status -ne 0 && $duplicate_status -ne 124 ]]
grep -q "holds writer authority" "$test_dir/duplicate.out"

committed_hash="$(sha256sum "$log_a" | cut -d' ' -f1)"
[[ "$committed_hash" != "$initial_hash" ]]

kill -TERM "$daemon_pid"
set +e
wait "$daemon_pid"
shutdown_status=$?
set -e
daemon_pid=
[[ $shutdown_status -eq 0 ]]
grep -q "\\[fram\\] shutdown complete" "$test_dir/daemon.out"

restart_port="$(free_port)"
FRAM_REQUIRE_LOG_FENCE=1 \
  "$test_dir/fram-daemon-zig" serve-flat "$restart_port" "$log_a" \
  >"$test_dir/restart.out" 2>&1 &
daemon_pid=$!

restart_ready=
for _ in $(seq 1 100); do
  if response="$(fenced_request \
      "$restart_port" "$canonical_a" "{:op :version}" 2>/dev/null)"; then
    restart_ready=$response
    break
  fi
  sleep 0.025
done
[[ -n "$restart_ready" ]]
assert_response "$restart_ready" '(fn [r] (= 13 (:version r)))'
[[ "$committed_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]

live_noop="$(fenced_request "$restart_port" "$canonical_a" \
  '{:op :assert-existing :te "@mutation" :p "progress" :r "one"}')"
assert_response "$live_noop" '(fn [r] (= 13 (:ok r)))'
retracted_noop="$(fenced_request "$restart_port" "$canonical_a" \
  '{:op :retract-existing :te "@mutation" :p "beta" :r "B"}')"
assert_response "$retracted_noop" '(fn [r] (= 13 (:ok r)))'
[[ "$committed_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]

kill -TERM "$daemon_pid"
set +e
wait "$daemon_pid"
restart_status=$?
set -e
daemon_pid=
[[ $restart_status -eq 0 ]]
grep -q "\\[fram\\] shutdown complete" "$test_dir/restart.out"

printf 'zig-daemon: fenced bootstrap, fact/triple/fact-id/predicate queries, durable mutation/OCC/batch/retract, leases/fenced writes, replay, writer exclusion, and SIGTERM passed\n'
