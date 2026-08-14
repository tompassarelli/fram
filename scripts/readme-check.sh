#!/usr/bin/env bash
# readme-check.sh — the README anti-rot gate. Run by CI; run locally with --local.
#
# A README rots silently: a renamed verb, a moved test, a stale org in a clone URL,
# a measured number no one re-ran. This gate fails CI on the mechanical ones, so the
# README stays true to the engine instead of drifting from it.
#
#   (1) stale repo URLs   — the confirmed-wrong org/repo forms must not appear.
#   (2) engine verbs      — every `bin/fram <verb>` named in README must be a real verb.
#   (3) bin entrypoints   — every `bin/fram-*` named in README must exist + be executable.
#   (4) referenced paths  — every relative link/path in README must exist.
#   (5) licensing        — canonical texts, chooser, README, and package metadata agree.
#   (6) the core loop runs — README data verbs against an isolated FRAMRPC server
#                            and scratch FRAMLOG (canonical state is never touched).
#   --local additionally checks the toolchain (bb / clojure / java) is on PATH.
set -uo pipefail
cd "$(dirname "$0")/.."                      # repo root
README=README.md
fail=0
note() { printf '  %s\n' "$*"; }
bad()  { printf 'FAIL: %s\n' "$*"; fail=1; }

# (1) stale repo URLs — wrong org/repo forms. (tompassarelli/north is CORRECT; not listed.)
echo "== (1) repo URLs =="
BANNED='(^|[^@[:alnum:]_.-])tompassarelli/(fram|beagle|codegraph|chartroom)|(^|[^@[:alnum:]_.-])Autonymy/(north|codegraph|chartroom)'
if hits=$(grep -rnE "$BANNED" "$README" .github 2>/dev/null); then
  bad "stale/wrong repo URL(s):"; printf '%s\n' "$hits" | sed 's/^/    /'
else note "ok — no stale org/repo forms"; fi

# (2) engine verbs — `bin/fram <verb>` in README must appear in the no-arg usage.
echo "== (2) engine verbs =="
usage=$(bin/fram 2>&1 || true)
for v in $(grep -oE 'bin/fram [a-z][a-z-]*' "$README" | awk '{print $2}' | sort -u); do
  if printf '%s' "$usage" | grep -qw -- "$v"; then note "ok — bin/fram $v"
  else bad "README references 'bin/fram $v' but it is not in 'bin/fram' usage"; fi
done

# (3) bin entrypoints — `bin/fram-*` referenced in README must exist + be executable.
echo "== (3) bin entrypoints =="
for b in $(grep -oE 'bin/fram-[a-z]+' "$README" | sort -u); do
  if [ -x "$b" ]; then note "ok — $b"; else bad "README names $b but it is missing/not executable"; fi
done

# (4) referenced paths — relative markdown links + the layout paths must exist.
echo "== (4) referenced paths =="
for p in $(grep -oE '\]\(([^)#]+)\)' "$README" | sed -E 's/^\]\(//; s/\)$//' \
            | grep -vE '^https?://' | sort -u); do
  if [ -e "$p" ]; then note "ok — $p"; else bad "README links a missing path: $p"; fi
done

# (5) dual-license contract — fail closed when texts, choosers, or metadata drift.
echo "== (5) dual-license contract =="
expect_sha() {
  actual=$(sha256sum "$1" | awk '{print $1}')
  if [ "$actual" = "$2" ]; then note "ok — $1"
  else bad "$1 license text/chooser drifted (got $actual)"; fi
}
expect_text() {
  if grep -Fq -- "$2" "$1"; then note "ok — $1 declares $2"
  else bad "$1 is missing: $2"; fi
}
expect_sha LICENSE 51bd50bac830296b4e643a0fb74995b6a36592aca2a039c5587cdae0fa4115dd
expect_sha LICENSE-APACHE 997f18d91914283787c07673bad98cfdeb38628e02c9a7e07a3b21d99a4b86d7
expect_sha LICENSE-MIT 51adc9bf9e72be82d08c2a694bcca11a6ac1b9e520bb537e1100a158d7d0d06d
expect_sha codegraph/LICENSE 361f8dc2cdf2e37f8ec56468127d0f54d679b78f450ca72ac0b226a46cccc3de
expect_sha codegraph/LICENSE-APACHE cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30
expect_sha codegraph/LICENSE-MIT 51adc9bf9e72be82d08c2a694bcca11a6ac1b9e520bb537e1100a158d7d0d06d
expect_text README.md '[MIT License](LICENSE-MIT)'
expect_text README.md '[Apache License, Version 2.0](LICENSE-APACHE)'
expect_text README.md '`MIT OR Apache-2.0`'
# The Codegraph README is archived under docs/archive/; its license links point
# back at the retained codegraph/ subtree, which still holds the license texts.
CG_README=docs/archive/codegraph-README.md
expect_text "$CG_README" '[MIT License](../../codegraph/LICENSE-MIT)'
expect_text "$CG_README" '[Apache License, Version 2.0](../../codegraph/LICENSE-APACHE)'
expect_text "$CG_README" '`MIT OR Apache-2.0`'
expect_text README.md 'license-MIT_OR_Apache--2.0-blue.svg'
expect_text deploy/cloudflare/package.json '"license": "MIT OR Apache-2.0"'
expect_text flake.nix 'license = with licenses; [ mit asl20 ];'

# (6) the current README data loop runs against an isolated FRAMRPC server.
echo "== (6) core engine loop (scratch FRAMRPC server) =="
WD=$(mktemp -d)
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill -TERM "$server_pid" 2>/dev/null || true
    for _ in $(seq 1 100); do
      kill -0 "$server_pid" 2>/dev/null || break
      sleep 0.05
    done
    if kill -0 "$server_pid" 2>/dev/null; then
      kill -KILL "$server_pid" 2>/dev/null || true
    fi
  fi
  if [[ -n "$server_pid" ]]; then wait "$server_pid" 2>/dev/null || true; fi
  server_pid=""
  rm -rf "${WD:?}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

export FRAM_SERVER_PORT
FRAM_SERVER_PORT=$(bb -e '(with-open [socket (java.net.ServerSocket. 0)] (print (.getLocalPort socket)))')
export FRAM_SPACE_ID=readme-check-space
export FRAM_LOG="$WD/history.framlog"
unset FRAM_TELEMETRY_LOG FRAM_SERVER_TLS_KEYSTORE FRAM_SERVER_TLS_TRUSTSTORE \
  FRAM_SERVER_TLS_PASS FRAM_SERVER_TLS_PASS_FILE
export FRAM_SERVER_CONNECT=127.0.0.1
export FRAM_SERVER_CONNECT_TIMEOUT_MS=500
export FRAM_SERVER_HANDSHAKE_TIMEOUT_MS=500
export FRAM_SERVER_READ_TIMEOUT_MS=2000
env -u FRAM_JAVA -u FRAM_SERVER_CLASSPATH_FILE -u FRAM_LISTEN_FD \
  FRAM_SERVER_RUNTIME=jvm-dev FRAM_BIND=127.0.0.1 FRAM_SNAPSHOT_BOOT=0 \
  FRAM_SERVER_QUIET=1 FRAM_PACKAGED=0 \
  bin/fram-server serve "$FRAM_SERVER_PORT" "$FRAM_LOG" "$FRAM_SPACE_ID" \
  >"$WD/server.log" 2>&1 &
server_pid=$!

ready=0
startup_deadline=$((SECONDS + 30))
while (( SECONDS < startup_deadline )); do
  if version=$(timeout --foreground 1s bin/fram version 2>/dev/null) &&
     [[ "$version" == "0" ]]; then
    ready=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  sleep 0.05
done

if [[ "$ready" -ne 1 ]]; then
  bad "scratch FRAMRPC server did not become ready"
  sed -n '1,80p' "$WD/server.log" | sed 's/^/    /'
else
  run_contains() {
    local expected="$1"
    shift
    echo "   \$ $*"
    if ! "$@" >"$WD/command.out" 2>&1; then
      bad "command failed: $*"
      sed -n '1,40p' "$WD/command.out" | sed 's/^/    /'
    elif grep -Fq -- "$expected" "$WD/command.out"; then
      note "ok"
    else
      bad "command output did not contain '$expected': $*"
      sed -n '1,40p' "$WD/command.out" | sed 's/^/    /'
    fi
  }
  run_exact() {
    local expected="$1"
    shift
    echo "   \$ $*"
    if ! "$@" >"$WD/command.out" 2>&1; then
      bad "command failed: $*"
      sed -n '1,40p' "$WD/command.out" | sed 's/^/    /'
    elif grep -Fxq -- "$expected" "$WD/command.out"; then
      note "ok"
    else
      bad "command output was not exactly '$expected': $*"
      sed -n '1,40p' "$WD/command.out" | sed 's/^/    /'
    fi
  }
  run_contains 'committed via server (v1)' \
    bin/fram tell :email :member_of :contact_relations
  run_contains 'committed via server (v2)' \
    bin/fram tell Alice :email alice@example.com
  run_contains ':email  alice@example.com' bin/fram show Alice
  run_contains '["@Alice" "alice@example.com"]' \
    bin/fram query '{:find "emails" :rules [{:head {:rel "emails" :args [{:var "who"} {:var "email"}]} :body [{:rel "triple" :args [{:var "who"} :email {:var "email"}]}]}]}'
  run_contains ':kernel/asserts' bin/fram occurrences
  run_exact valid bin/fram validate
  run_contains 'committed via server (v3)' \
    bin/fram retract Alice :email alice@example.com
  run_exact 3 bin/fram version
fi

# --local: toolchain present
if [ "${1:-}" = "--local" ]; then
  echo "== (local) toolchain =="
  for c in bb clojure java; do
    if command -v "$c" >/dev/null 2>&1; then note "ok — $c"; else bad "missing tool: $c"; fi
  done
fi

echo
if [ "$fail" -eq 0 ]; then echo "README OK — engine is the source of truth, and the README agrees."; else
  echo "README rot detected (see FAIL lines above)."; fi
exit "$fail"
