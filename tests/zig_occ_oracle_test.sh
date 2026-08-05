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
  server_out="$test_dir/$name.server.out"
  : >"$log"

  port="$(free_port)"
  FRAM_SPACE_ID="$space" FRAM_CREATE_LOG=1 \
    "$server_bin" serve-log "$port" "$log" \
    >"$server_out" 2>&1 &
  server_pid=$!

  ready=
  for _ in $(seq 1 100); do
    if "$client_bin" probe "$port" "$space" 0 >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 0.025
  done
  if [[ -z "$ready" ]]; then
    cat "$server_out" >&2
    exit 1
  fi

  "$client_bin" oracle "$port" "$space" "$corpus"
  kill -TERM "$server_pid"
  wait "$server_pid"
  server_pid=
  grep -q "\\[fram\\] shutdown complete" "$server_out"
  agreed=$((agreed + 1))
done

printf 'oracle: %d/%d FRAMRPC corpora agree with the independent native model\n' \
  "$agreed" "${#corpora[@]}"
