#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bb -cp out tests/daemon_read_cli_test.clj

scratch="$(mktemp -d)"
daemon_pid=
cleanup() {
  if [[ -n "${daemon_pid:-}" ]]; then
    kill "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true
  fi
  rm -rf "${scratch:?}"
}
trap cleanup EXIT
mkdir -p "$scratch/threads"
printf '%s\n' \
  '{:tx 1, :op "assert", :l "@offline", :p "title", :r "cold fallback", :by "fixture"}' \
  '{:tx 2, :op "assert", :l "@019fa4d4-93aa-7447-aae5-0a5bcfca6849", :p "title", :r "latency probe", :by "fixture"}' \
  '{:tx 3, :op "assert", :l "@target", :p "title", :r "target", :by "fixture"}' \
  '{:tx 4, :op "assert", :l "@019fa4d4-93aa-7447-aae5-0a5bcfca6849", :p "depends_on", :r "@target", :by "fixture"}' \
  > "$scratch/coordination.log"

port="$(bb -e '(with-open [socket (java.net.ServerSocket. 0)] (print (.getLocalPort socket)))')"
cold_started="$(date +%s%N)"
actual="$(
  FRAM_PORT="$port" \
  FRAM_LOG="$scratch/coordination.log" \
  FRAM_THREADS="$scratch/threads" \
  "$ROOT/bin/fram" show offline
)"
cold_stopped="$(date +%s%N)"
cold_ms="$(( (cold_stopped - cold_started) / 1000000 ))"

[[ "$actual" == '  title  cold fallback' ]] || {
  printf 'daemon_read_perf_ratchet: cold fallback mismatch\n%s\n' "$actual" >&2
  exit 1
}
(( cold_ms < 5000 )) || {
  printf 'daemon_read_perf_ratchet: unreachable coordinator amplified to %dms\n' "$cold_ms" >&2
  exit 1
}

bb -cp out coord_daemon.clj serve-flat "$port" "$scratch/coordination.log" \
  >"$scratch/daemon.log" 2>&1 &
daemon_pid="$!"

ready=0
for _ in $(seq 1 200); do
  if (exec 9<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    exec 9>&-
    ready=1
    break
  fi
  sleep 0.05
done
if [[ "$ready" -ne 1 ]]; then
  printf 'daemon_read_perf_ratchet: coordinator failed to start\n' >&2
  sed -n '1,80p' "$scratch/daemon.log" >&2
  exit 1
fi

run_show() {
  FRAM_PORT="$port" \
  FRAM_LOG="$scratch/coordination.log" \
  FRAM_THREADS="$scratch/threads" \
  "$ROOT/bin/fram" show 019fa4d4-93aa-7447-aae5-0a5bcfca6849 >/dev/null
}

run_tell() {
  FRAM_PORT="$port" \
  FRAM_LOG="$scratch/coordination.log" \
  FRAM_THREADS="$scratch/threads" \
  "$ROOT/bin/fram" tell 019fa4d4-93aa-7447-aae5-0a5bcfca6849 progress "$1" >/dev/null
}

run_tell_existing() {
  FRAM_PORT="$port" \
  FRAM_LOG="$scratch/coordination.log" \
  FRAM_THREADS="$scratch/threads" \
  "$ROOT/bin/fram" tell-existing \
    019fa4d4-93aa-7447-aae5-0a5bcfca6849 progress "$1" >/dev/null
}

run_tell_existing_bare_ref() {
  FRAM_PORT="$port" \
  FRAM_LOG="$scratch/coordination.log" \
  FRAM_THREADS="$scratch/threads" \
  "$ROOT/bin/fram" tell-existing \
    019fa4d4-93aa-7447-aae5-0a5bcfca6849 depends_on target >/dev/null
}

measure_ms() {
  local started stopped
  started="$(date +%s%N)"
  "$@"
  stopped="$(date +%s%N)"
  echo "$(( (stopped - started) / 1000000 ))"
}

# Fixed N=10, nearest-rank p95 = the maximum sample. Warm each command once so
# the ratchet measures short-lived CLI startup + warm coordinator work.
run_show
show_samples=()
for _ in $(seq 1 10); do
  show_samples+=("$(measure_ms run_show)")
done

run_tell "warmup write"
tell_samples=()
for index in $(seq 1 10); do
  tell_samples+=("$(measure_ms run_tell "latency write $index")")
done

run_tell_existing "warmup existing write"
tell_existing_samples=()
for index in $(seq 1 10); do
  tell_existing_samples+=(
    "$(measure_ms run_tell_existing "latency existing write $index")"
  )
done

run_tell_existing_bare_ref
bare_existing_samples=()
for _ in $(seq 1 10); do
  bare_existing_samples+=("$(measure_ms run_tell_existing_bare_ref)")
done

show_p95="$(printf '%s\n' "${show_samples[@]}" | sort -n | tail -1)"
tell_p95="$(printf '%s\n' "${tell_samples[@]}" | sort -n | tail -1)"
tell_existing_p95="$(
  printf '%s\n' "${tell_existing_samples[@]}" | sort -n | tail -1
)"
bare_existing_p95="$(
  printf '%s\n' "${bare_existing_samples[@]}" | sort -n | tail -1
)"

(( show_p95 <= 150 )) || {
  printf 'daemon_read_perf_ratchet: exact show p95 %dms > 150ms; samples: %s\n' \
    "$show_p95" "${show_samples[*]}" >&2
  exit 1
}
(( tell_p95 <= 150 )) || {
  printf 'daemon_read_perf_ratchet: exact tell p95 %dms > 150ms; samples: %s\n' \
    "$tell_p95" "${tell_samples[*]}" >&2
  exit 1
}
(( tell_existing_p95 <= 150 )) || {
  printf 'daemon_read_perf_ratchet: existing exact tell p95 %dms > 150ms; samples: %s\n' \
    "$tell_existing_p95" "${tell_existing_samples[*]}" >&2
  exit 1
}
(( bare_existing_p95 <= 150 )) || {
  printf 'daemon_read_perf_ratchet: bare existing exact tell p95 %dms > 150ms; samples: %s\n' \
    "$bare_existing_p95" "${bare_existing_samples[*]}" >&2
  exit 1
}

printf 'daemon_read_perf_ratchet: PASS — show p95=%dms tell p95=%dms existing-tell p95=%dms bare-existing-ref p95=%dms N=10; cold fallback=%dms\n' \
  "$show_p95" "$tell_p95" "$tell_existing_p95" "$bare_existing_p95" "$cold_ms"
