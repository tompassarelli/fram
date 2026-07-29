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
assert_response "$restart_ready" '(fn [r] (= 6 (:version r)))'
[[ "$committed_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]

live_noop="$(fenced_request "$restart_port" "$canonical_a" \
  '{:op :assert-existing :te "@mutation" :p "progress" :r "one"}')"
assert_response "$live_noop" '(fn [r] (= 6 (:ok r)))'
retracted_noop="$(fenced_request "$restart_port" "$canonical_a" \
  '{:op :retract-existing :te "@mutation" :p "beta" :r "B"}')"
assert_response "$retracted_noop" '(fn [r] (= 6 (:ok r)))'
[[ "$committed_hash" == "$(sha256sum "$log_a" | cut -d' ' -f1)" ]]

kill -TERM "$daemon_pid"
set +e
wait "$daemon_pid"
restart_status=$?
set -e
daemon_pid=
[[ $restart_status -eq 0 ]]
grep -q "\\[fram\\] shutdown complete" "$test_dir/restart.out"

printf 'zig-daemon: fenced bootstrap, durable mutation/OCC/batch/retract, replay, writer exclusion, and SIGTERM passed\n'
