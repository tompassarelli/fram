#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fram_up_source="${FRAM_UP_UNDER_TEST:-$repo_root/bin/fram-up}"
scratch="$(mktemp -d)"

cleanup() {
  rm -rf "${scratch:?}"
}
trap cleanup EXIT INT TERM

fail() {
  echo "fram-up readiness: FAIL: $*" >&2
  exit 1
}

make_case() {
  local name="$1"
  local case_dir="$scratch/$name"
  mkdir -p "$case_dir/bin" "$case_dir/out" "$case_dir/work" "$case_dir/state"
  cp "$fram_up_source" "$case_dir/bin/fram-up"
  chmod +x "$case_dir/bin/fram-up"

  cat >"$case_dir/bin/bb" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$TEST_STATE/bb.calls"
case "$*" in
  *doctor*|*read-log*|*fold*)
    echo "heavyweight readiness path invoked: $*" >&2
    exit 99
    ;;
esac
[[ "$*" == *coord-version-for-log* ]] || exit 98
[[ "${TEST_MODE:-}" != "unavailable" ]] || exit 1
[[ -s "$TEST_STATE/served-log" ]] || exit 1
[[ "$(readlink -f "$FRAM_PROBE_LOG")" == "$(cat "$TEST_STATE/served-log")" ]] || exit 1
STUB

  cat >"$case_dir/bin/fram-daemon" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s|%s\n' "$1" "$2" >>"$TEST_STATE/daemon.calls"
if [[ "${TEST_MODE:-}" != "unavailable" ]]; then
  readlink -f "$2" >"$TEST_STATE/served-log"
fi
STUB

  cat >"$case_dir/bin/fram" <<'STUB'
#!/usr/bin/env bash
echo "heavyweight fram CLI invoked: $*" >&2
exit 97
STUB

  cat >"$case_dir/bin/ss" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB

  cat >"$case_dir/bin/sleep" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB

  chmod +x "$case_dir/bin/bb" "$case_dir/bin/fram-daemon" \
    "$case_dir/bin/fram" "$case_dir/bin/ss" "$case_dir/bin/sleep"
  : >"$case_dir/state/bb.calls"
  : >"$case_dir/state/daemon.calls"
  printf '%s\n' "$case_dir"
}

run_up() {
  local case_dir="$1"
  local mode="$2"
  local log="$3"
  (
    cd "$case_dir/work"
    PATH="$case_dir/bin:$PATH" \
      TEST_STATE="$case_dir/state" \
      TEST_MODE="$mode" \
      FRAM_PORT=43129 \
      FRAM_LOG="$log" \
      "$case_dir/bin/fram-up"
  )
}

ready_dir="$(make_case ready)"
ready_log="$ready_dir/work/coordination.log"
: >"$ready_log"
readlink -f "$ready_log" >"$ready_dir/state/served-log"
ready_output="$(run_up "$ready_dir" ready "$ready_log")" ||
  fail "exact-log ready daemon was rejected"
[[ "$ready_output" == *"coordinator already up"* ]] ||
  fail "exact-log ready daemon was not recognized"
[[ ! -s "$ready_dir/state/daemon.calls" ]] ||
  fail "exact-log ready daemon was unnecessarily restarted"

wrong_dir="$(make_case wrong-log)"
wrong_log="$wrong_dir/work/coordination.log"
other_log="$wrong_dir/work/other.log"
: >"$wrong_log"
: >"$other_log"
readlink -f "$other_log" >"$wrong_dir/state/served-log"
wrong_output="$(run_up "$wrong_dir" wrong-log "$wrong_log")" ||
  fail "startup after wrong-log rejection did not become ready"
[[ "$wrong_output" != *"coordinator already up"* ]] ||
  fail "wrong-log daemon was accepted as already ready"
[[ "$wrong_output" == *"starting coordinator"* &&
   "$wrong_output" == *"coordinator up"* ]] ||
  fail "wrong-log daemon did not take the startup path"
[[ -s "$wrong_dir/state/daemon.calls" ]] ||
  fail "wrong-log daemon did not trigger a fresh start"

down_dir="$(make_case unavailable)"
down_log="$down_dir/work/coordination.log"
: >"$down_log"
if run_up "$down_dir" unavailable "$down_log" >"$down_dir/state/output" 2>&1; then
  fail "unavailable daemon was accepted as ready"
fi
grep -q "daemon did not come up" "$down_dir/state/output" ||
  fail "unavailable daemon did not report the readiness deadline"

for calls in "$ready_dir/state/bb.calls" \
             "$wrong_dir/state/bb.calls" \
             "$down_dir/state/bb.calls"; do
  [[ -s "$calls" ]] || fail "lightweight runtime probe was not invoked"
  if grep -Eq 'doctor|read-log|fold' "$calls"; then
    fail "readiness invoked a heavyweight CLI/log path"
  fi
done

echo "fram-up readiness: PASS"
