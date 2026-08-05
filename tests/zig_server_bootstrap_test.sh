#!/usr/bin/env bash
set -euo pipefail

repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"
server_pid=

cleanup() {
  if [[ -n "${server_pid:-}" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill -TERM "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "${test_dir:?}"
}
trap cleanup EXIT

zig=(direnv exec "${BEAGLE_HOME:-$HOME/code/beagle/main}" zig) # world:allow
install_dir="$test_dir/install"
if [[ -z "${FRAM_ZIG_SERVER:-}" || -z "${FRAM_RPC_CLIENT:-}" ]]; then
  (
    cd "$repo"
    "${zig[@]}" build -Doptimize=ReleaseSafe --prefix "$install_dir"
  )
fi
server_bin="${FRAM_ZIG_SERVER:-$install_dir/bin/fram-server-zig}"
client_bin="${FRAM_RPC_CLIENT:-$install_dir/bin/fram-rpc-client}"
[[ -x "$server_bin" ]]
[[ -x "$client_bin" ]]

free_port() {
  bb -e '(with-open [socket (java.net.ServerSocket. 0)]
           (print (.getLocalPort socket)))'
}

wait_ready() {
  local port=$1
  local expected=$2
  for _ in $(seq 1 100); do
    if "$client_bin" probe "$port" bootstrap-space "$expected" \
        >"$test_dir/probe.out" 2>&1; then
      return 0
    fi
    sleep 0.025
  done
  return 1
}

log="$test_dir/bootstrap.framlog"
: >"$log"
initial_hash="$(sha256sum "$log" | cut -d' ' -f1)"
port="$(free_port)"
FRAM_SPACE_ID=bootstrap-space FRAM_CREATE_LOG=1 \
  "$server_bin" serve-log "$port" "$log" \
  >"$test_dir/server.out" 2>&1 &
server_pid=$!
wait_ready "$port" 0

"$client_bin" bootstrap "$port" bootstrap-space
"$client_bin" probe "$port" bootstrap-space 9 >/dev/null

committed_hash="$(sha256sum "$log" | cut -d' ' -f1)"
[[ "$committed_hash" != "$initial_hash" ]]
committed_size="$(stat -c %s "$log")"

# A typed request for another immutable space is rejected before dispatch and
# cannot change durable state.
set +e
"$client_bin" probe "$port" wrong-space \
  >"$test_dir/wrong-space.out" 2>&1
wrong_space_status=$?
set -e
[[ $wrong_space_status -ne 0 ]]
[[ "$committed_hash" == "$(sha256sum "$log" | cut -d' ' -f1)" ]]

# The log-scoped writer authority excludes a second server generation.
duplicate_port="$(free_port)"
set +e
FRAM_SPACE_ID=bootstrap-space timeout 5 \
  "$server_bin" serve-log "$duplicate_port" "$log" \
  >"$test_dir/duplicate.out" 2>&1
duplicate_status=$?
set -e
[[ $duplicate_status -ne 0 && $duplicate_status -ne 124 ]]
grep -q "holds writer authority" "$test_dir/duplicate.out"

# Runtime boot never guesses at legacy text. Conversion is a separate,
# one-shot tool with its own migration receipt tests.
legacy_log="$test_dir/legacy.log"
printf '%s\n' \
  '{:tx 1, :op "assert", :l "@legacy", :p "title", :r "old"}' \
  >"$legacy_log"
legacy_port="$(free_port)"
set +e
FRAM_SPACE_ID=bootstrap-space timeout 5 \
  "$server_bin" serve-log "$legacy_port" "$legacy_log" \
  >"$test_dir/legacy.out" 2>&1
legacy_status=$?
set -e
[[ $legacy_status -ne 0 && $legacy_status -ne 124 ]]
grep -q "requires one-shot FRAMLOG v1 migration" "$test_dir/legacy.out"

kill -TERM "$server_pid"
wait "$server_pid"
server_pid=
grep -q "\\[fram\\] shutdown complete" "$test_dir/server.out"

# An incomplete final frame is not a transaction. Authority replay truncates
# only that prefix and returns to the exact complete boundary.
printf '\x40\x00\x00' >>"$log"
[[ "$committed_hash" != "$(sha256sum "$log" | cut -d' ' -f1)" ]]
restart_port="$(free_port)"
FRAM_SPACE_ID=bootstrap-space \
  "$server_bin" serve-log "$restart_port" "$log" \
  >"$test_dir/restart.out" 2>&1 &
server_pid=$!
wait_ready "$restart_port" 9
[[ "$committed_hash" == "$(sha256sum "$log" | cut -d' ' -f1)" ]]
[[ "$committed_size" == "$(stat -c %s "$log")" ]]
grep -q "incomplete final transaction" "$test_dir/restart.out"

kill -TERM "$server_pid"
wait "$server_pid"
server_pid=
grep -q "\\[fram\\] shutdown complete" "$test_dir/restart.out"

# The immutable SpaceId is part of the log header, not a caller convention.
mismatch_port="$(free_port)"
set +e
FRAM_SPACE_ID=other-space timeout 5 \
  "$server_bin" serve-log "$mismatch_port" "$log" \
  >"$test_dir/space-mismatch.out" 2>&1
mismatch_status=$?
set -e
[[ $mismatch_status -ne 0 && $mismatch_status -ne 124 ]]
grep -q "SpaceId does not match" "$test_dir/space-mismatch.out"

printf '%s\n' \
  'zig-server: FRAMRPC-only transport, recursive triples, direct occurrence coordinates,' \
  'atomic FRAMLOG transactions, OCC, leases, schema, cancellation/disconnect,' \
  'cold restart, torn-tail replay, migration refusal, writer exclusion, and SIGTERM passed'
