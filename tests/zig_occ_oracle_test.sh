#!/usr/bin/env bash
set -euo pipefail

# harness patterns donated by zig-daemon-oracle@50a0c7a — that branch never lands
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

sort_fingerprint() {
  local input=$1
  local output=$2
  local prefix
  local facts
  prefix="$(mktemp "$test_dir/prefix.XXXXXX")"
  facts="$(mktemp "$test_dir/facts.XXXXXX")"
  awk -v facts="$facts" -v prefix="$prefix" \
    'index($0, "fact\t") == 1 { print > facts; next } { print > prefix }' \
    "$input"
  sort "$facts" >>"$prefix"
  mv "$prefix" "$output"
}

stop_daemon() {
  if [[ -n "${daemon_pid:-}" ]] && kill -0 "$daemon_pid" 2>/dev/null; then
    kill -TERM "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true
  fi
  daemon_pid=
}

mode=full
if [[ "${1:-}" == "--jvm-only" ]]; then
  mode=jvm-only
  shift
fi

if (( $# > 0 )); then
  corpora=("$@")
else
  corpora=(
    tests/oracle/S0.tsv tests/oracle/S1.tsv tests/oracle/S2.tsv
    tests/oracle/S3.tsv tests/oracle/S4.tsv tests/oracle/S5.tsv
    tests/oracle/S6.tsv tests/oracle/S7.tsv tests/oracle/S8.tsv
    tests/oracle/F1.tsv tests/oracle/F2.tsv tests/oracle/F3.tsv
  )
fi

cache_root="${FRAM_ZD6_CACHE_ROOT:-${HOME}/.cache/fram-zd6}"
if ! mkdir -p "$cache_root" 2>/dev/null; then
  cache_root="${TMPDIR:-/tmp}/fram-zd6"
  mkdir -p "$cache_root"
  echo "oracle cache fallback: $cache_root" >&2
fi
run_dir="$cache_root/run.$$"
mkdir -p "$run_dir/jvm" "$run_dir/zig"

run_jvm_once() {
  local corpus=$1
  local name=$2
  local attempt=$3
  local port log daemon_out raw_output normalized_unsorted
  port="$(free_port)"
  log="$run_dir/jvm/$name.attempt-$attempt.log"
  daemon_out="$run_dir/jvm/$name.daemon-$attempt.out"
  raw_output="$run_dir/jvm/$name.raw"
  normalized_unsorted="$test_dir/$name.jvm.unsorted"
  : >"$log"
  # hermetic telemetry sibling: without this the daemon folds the LIVE
  # telemetry log into its view (contaminates fingerprints, 19s boots)
  : >"$log.telemetry"

  # clojure -M, not bb: coord_daemon's serve loop never binds under bb
  # (repo praxis: tests/coord_admission_deadline_test.clj:172)
  env -u FRAM_REQUIRE_LOG_FENCE FRAM_TELEMETRY_LOG="$log.telemetry" \
    clojure -M coord_daemon.clj serve-flat "$port" "$log" \
    >"$daemon_out" 2>&1 &
  daemon_pid=$!

  local ready=
  for _ in $(seq 1 120); do
    if response="$(request "$port" '{:op :version}' 2>/dev/null)"; then
      ready=$response
      break
    fi
    sleep 0.5
  done
  if [[ -z "$ready" ]]; then
    stop_daemon
    return 1
  fi

  if ! FRAM_ORACLE_RAW_PATH="$raw_output" \
      bb tests/zig_occ_oracle_driver.clj "$corpus" "$port" \
      >"$normalized_unsorted"; then
    stop_daemon
    return 1
  fi
  sort_fingerprint "$normalized_unsorted" "$run_dir/jvm/$name.out"
  stop_daemon
}

run_zig_daemon_once() {
  local corpus=$1
  local name=$2
  local attempt=$3
  local port log daemon_out raw_output normalized_unsorted
  port="$(free_port)"
  log="$run_dir/zig/$name.attempt-$attempt.log"
  daemon_out="$run_dir/zig/$name.daemon-$attempt.out"
  raw_output="$run_dir/zig/$name.raw"
  normalized_unsorted="$test_dir/$name.zig.unsorted"
  : >"$log"

  env -u FRAM_REQUIRE_LOG_FENCE \
    "$FRAM_ZIG_DAEMON" serve-flat "$port" "$log" \
    >"$daemon_out" 2>&1 &
  daemon_pid=$!

  local ready=
  for _ in $(seq 1 120); do
    if response="$(request "$port" '{:op :version}' 2>/dev/null)"; then
      ready=$response
      break
    fi
    sleep 0.5
  done
  if [[ -z "$ready" ]]; then
    stop_daemon
    return 1
  fi

  if ! FRAM_ORACLE_RAW_PATH="$raw_output" \
      bb tests/zig_occ_oracle_driver.clj "$corpus" "$port" \
      >"$normalized_unsorted"; then
    stop_daemon
    return 1
  fi
  sort_fingerprint "$normalized_unsorted" "$run_dir/zig/$name.out"
  stop_daemon
}

for corpus in "${corpora[@]}"; do
  if [[ "$corpus" != /* ]]; then
    corpus="$repo/$corpus"
  fi
  name="$(basename "$corpus" .tsv)"
  if ! run_jvm_once "$corpus" "$name" 1; then
    if ! run_jvm_once "$corpus" "$name" 2; then
      echo "oracle daemon failed twice for $name; see $run_dir/jvm/$name.daemon-2.out" >&2
      exit 1
    fi
  fi
done

if [[ "$mode" == "jvm-only" ]]; then
  echo "jvm-only: ${#corpora[@]}/${#corpora[@]} corpora replayed"
  echo "run-dir: $run_dir"
  exit 0
fi

agreed=0
if [[ -n "${FRAM_ZIG_DAEMON:-}" ]]; then
  if [[ ! -x "$FRAM_ZIG_DAEMON" ]]; then
    echo "FRAM_ZIG_DAEMON is not executable: $FRAM_ZIG_DAEMON" >&2
    exit 1
  fi
  for corpus in "${corpora[@]}"; do
    if [[ "$corpus" != /* ]]; then
      corpus="$repo/$corpus"
    fi
    name="$(basename "$corpus" .tsv)"
    if ! run_zig_daemon_once "$corpus" "$name" 1; then
      if ! run_zig_daemon_once "$corpus" "$name" 2; then
        echo "Zig daemon failed twice for $name; see $run_dir/zig/$name.daemon-2.out" >&2
        exit 1
      fi
    fi
    diff -u "$run_dir/jvm/$name.out" "$run_dir/zig/$name.out"
    agreed=$((agreed + 1))
  done
else
  zig_replay="${FRAM_ZIG_OCC_REPLAY:-$cache_root/zig-occ-replay}"
  if [[ ! -x "$zig_replay" ]]; then
    echo "missing Zig replay executable: $zig_replay" >&2
    exit 1
  fi
  for corpus in "${corpora[@]}"; do
    if [[ "$corpus" != /* ]]; then
      corpus="$repo/$corpus"
    fi
    name="$(basename "$corpus" .tsv)"
    "$zig_replay" "$corpus" >"$test_dir/$name.zig.unsorted"
    sort_fingerprint "$test_dir/$name.zig.unsorted" "$run_dir/zig/$name.out"
    diff -u "$run_dir/jvm/$name.out" "$run_dir/zig/$name.out"
    agreed=$((agreed + 1))
  done
fi

echo "oracle: $agreed/${#corpora[@]} corpora agree"
echo "run-dir: $run_dir"
