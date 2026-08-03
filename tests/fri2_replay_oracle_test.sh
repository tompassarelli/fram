#!/usr/bin/env bash
# The Beagle FRI2 replay module against the frozen Zig oracle, per corpus:
#   1. the frozen harness leg — zig daemon + `fram-rpc-client oracle`, whose
#      independent native model is verified against the daemon on every line;
#   2. the Beagle leg — fram.fri-replay decides the same corpus and folds the
#      accepted transactions through fram.store;
#   3. three text diffs — the summary line, the final version + live data facts
#      the Zig daemon persisted to its FRAMLOG, and the per-transaction ops.
#
# Harness patterns (free port, readiness probe, fingerprint sort) follow
# tests/zig_occ_oracle_test.sh; the Zig binaries are used, never rebuilt here
# when FRAM_ZIG_DAEMON / FRAM_RPC_CLIENT are supplied.
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

zig=(direnv exec "${BEAGLE_HOME:-$HOME/code/beagle/main}" zig) # world:allow
install_dir="$test_dir/install"
if [[ -z "${FRAM_ZIG_DAEMON:-}" || -z "${FRAM_RPC_CLIENT:-}" ]]; then
  (
    cd "$repo"
    "${zig[@]}" build -Doptimize=ReleaseSafe --prefix "$install_dir"
  )
fi
daemon_bin="${FRAM_ZIG_DAEMON:-$install_dir/bin/fram-daemon-zig}"
client_bin="${FRAM_RPC_CLIENT:-$install_dir/bin/fram-rpc-client}"
[[ -x "$daemon_bin" ]]
[[ -x "$client_bin" ]]

free_port() {
  bb -e '(with-open [socket (java.net.ServerSocket. 0)]
           (print (.getLocalPort socket)))'
}

# Only the fingerprint facts are order-free; outcome and transaction lines are
# compared in the order the replay produced them.
sort_facts() {
  local input=$1 output=$2 prefix facts
  prefix="$(mktemp "$test_dir/prefix.XXXXXX")"
  facts="$(mktemp "$test_dir/facts.XXXXXX")"
  awk -v facts="$facts" -v prefix="$prefix" \
    'index($0, "fact\t") == 1 { print > facts; next } { print > prefix }' \
    "$input"
  sort "$facts" >>"$prefix"
  mv "$prefix" "$output"
}

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

agreed=0
for corpus in "${corpora[@]}"; do
  if [[ "$corpus" != /* ]]; then
    corpus="$repo/$corpus"
  fi
  name="$(basename "$corpus" .tsv)"
  space="oracle-$name"
  log="$test_dir/$name.framlog"
  daemon_out="$test_dir/$name.daemon.out"
  zig_dir="$test_dir/$name.zig"
  beagle_dir="$test_dir/$name.beagle"
  : >"$log"

  port="$(free_port)"
  FRAM_SPACE_ID="$space" FRAM_CREATE_LOG=1 \
    "$daemon_bin" serve-log "$port" "$log" \
    >"$daemon_out" 2>&1 &
  daemon_pid=$!

  ready=
  for _ in $(seq 1 100); do
    if "$client_bin" probe "$port" "$space" 0 >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 0.025
  done
  if [[ -z "$ready" ]]; then
    cat "$daemon_out" >&2
    exit 1
  fi

  "$client_bin" oracle "$port" "$space" "$corpus" 2>"$test_dir/$name.oracle.out"
  kill -TERM "$daemon_pid"
  wait "$daemon_pid"
  daemon_pid=
  grep -q "\[fram\] shutdown complete" "$daemon_out"

  mkdir -p "$zig_dir" "$beagle_dir"
  grep '^oracle ' "$test_dir/$name.oracle.out" >"$zig_dir/summary"
  [[ -s "$zig_dir/summary" ]]  # an empty reference would make every diff vacuous
  (cd "$repo" && bb -cp out tests/fri2_replay_zig_state.clj "$log" "$zig_dir" >/dev/null)
  (cd "$repo" && bb -cp out tests/fri2_replay_driver.clj "$corpus" "$space" "$beagle_dir" >/dev/null)

  # The Beagle normalized output carries its own per-operation outcomes; the
  # comparable prefix against the persisted FRAMLOG is version + facts.
  grep -E '^(final-version|fact)\b' "$beagle_dir/normalized" >"$beagle_dir/state"
  sort_facts "$beagle_dir/state" "$beagle_dir/state.sorted"
  sort_facts "$zig_dir/state" "$zig_dir/state.sorted"

  diff -u "$zig_dir/summary" "$beagle_dir/summary"
  (cd "$repo" && bb tests/fri2_replay_compare.clj \
     "$zig_dir/state.sorted" "$beagle_dir/state.sorted")
  agreed=$((agreed + 1))
done

printf 'fri2-replay: %d/%d oracle corpora agree with the frozen Zig oracle\n' \
  "$agreed" "${#corpora[@]}"
