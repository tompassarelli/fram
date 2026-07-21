#!/usr/bin/env bash
# Closure-level smoke for the installed Fram package. The daemon is launched
# from an empty workspace and HOME; only the package output may supply code.
set -euo pipefail

package_root="${1:?usage: package_daemon_smoke.sh /nix/store/...-fram}"
bb="${FRAM_SMOKE_BB:?FRAM_SMOKE_BB is required}"
env_bin="${FRAM_SMOKE_ENV:?FRAM_SMOKE_ENV is required}"
grep_bin="${FRAM_SMOKE_GREP:?FRAM_SMOKE_GREP is required}"
readlink_bin="${FRAM_SMOKE_READLINK:?FRAM_SMOKE_READLINK is required}"
tr_bin="${FRAM_SMOKE_TR:?FRAM_SMOKE_TR is required}"
require_proc="${FRAM_SMOKE_REQUIRE_PROC:-0}"

case "$package_root" in
  /nix/store/*) ;;
  *)
    echo "fram package smoke: refusing non-store package root: $package_root" >&2
    exit 2
    ;;
esac

required=(
  "$package_root/bin/fram-daemon"
  "$package_root/bin/fram"
  "$package_root/bin/fram-mcp"
  "$package_root/bin/fram-primer"
  "$package_root/libexec/fram/coord.clj"
  "$package_root/libexec/fram/coord_daemon.clj"
  "$package_root/libexec/fram/pull.clj"
  "$package_root/libexec/fram/defcheck_gate.clj"
  "$package_root/libexec/fram/chartroom/src/resolve.clj"
  "$package_root/libexec/fram/daemon.classpath"
)
for path in "${required[@]}"; do
  if [[ ! -e "$path" ]]; then
    echo "fram package smoke: missing runtime asset: $path" >&2
    exit 1
  fi
done
hidden_commands=(
  fram-code-author
  fram-code-off
  fram-code-on
  fram-code-status
  fram-commit-code
  fram-defcheck
  fram-defcheck-server.rkt
  fram-edit-code
  fram-ingest-code
  fram-modules-of-log
  fram-render-code
  fram-render-code-all
  fram-up
)
for name in "${hidden_commands[@]}"; do
  if [[ -e "$package_root/bin/$name" ]]; then
    echo "fram package smoke: non-core helper exposed as public command: $name" >&2
    exit 1
  fi
done
if [[ -e "$package_root/libexec/fram/.cpcache" ]]; then
  echo "fram package smoke: tools.deps build cache leaked into the runtime output" >&2
  exit 1
fi
if checkout_hits="$("$grep_bin" -R -a -n -m 5 -F "/home/" "$package_root" 2>/dev/null)"; then
  echo "fram package smoke: checkout-local /home path leaked into the runtime output" >&2
  printf '%s\n' "$checkout_hits" >&2
  exit 1
fi

work="$(mktemp -d)"
home="$work/home"
facts="$work/facts.log"
daemon_output="$work/daemon.out"
mkdir -p "$home" "$work/cwd"
: >"$facts"

pid=
cleanup() {
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 100); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.05
    done
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT INT TERM

port="$("$bb" -e '(with-open [s (java.net.ServerSocket. 0)] (println (.getLocalPort s)))')"

(
  cd "$work/cwd"
  exec "$env_bin" -i \
    HOME="$home" \
    XDG_CACHE_HOME="$home/.cache" \
    FRAM_BIND="127.0.0.1" \
    "$package_root/bin/fram-daemon" serve-flat "$port" "$facts"
) >"$daemon_output" 2>&1 &
pid=$!

probe='
(require (quote [clojure.edn :as edn])
         (quote [clojure.java.io :as io]))
(let [port (parse-long (first *command-line-args*))]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 250)
      (.setSoTimeout socket 1000)
      (with-open [writer (io/writer (.getOutputStream socket))
                  reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
        (.write writer (str (pr-str {:op :version}) "\n"))
        (.flush writer)
        (let [response (edn/read reader)]
          (if (and (map? response)
                   (integer? (:version response))
                   (not (:reject response)))
            (println (pr-str response))
            (System/exit 1)))))
    (catch Throwable _ (System/exit 1))))'

response=
for _ in $(seq 1 150); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "fram package smoke: daemon exited before becoming ready" >&2
    sed -n '1,200p' "$daemon_output" >&2
    exit 1
  fi
  if response="$("$bb" -e "$probe" "$port" 2>/dev/null)"; then
    break
  fi
  response=
  sleep 0.1
done

if [[ -z "$response" ]]; then
  echo "fram package smoke: no valid :version response before deadline" >&2
  sed -n '1,200p' "$daemon_output" >&2
  exit 1
fi

lease_probe='
(require (quote [clojure.edn :as edn])
         (quote [clojure.java.io :as io]))
(defn request [port value]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              writer (io/writer (.getOutputStream socket))
              reader (java.io.PushbackReader. (io/reader (.getInputStream socket)))]
    (.write writer (str (pr-str value) "\n"))
    (.flush writer)
    (edn/read reader)))
(let [port (parse-long (first *command-line-args*))
      resource "package-renew"
      holder "package-holder"
      acquired (request port {:op :acquire-lease :res resource :holder holder :ttl-ms 5000})
      renewed (request port {:op :renew-lease :res resource :holder holder
                             :epoch (:epoch acquired) :ttl-ms 10000})
      old-fence (request port {:op :fence-ok :res resource :holder holder
                               :epoch (:epoch acquired)})
      stale-release (request port {:op :release-lease :res resource :holder holder
                                   :epoch (:epoch acquired)})
      fresh-fence (request port {:op :fence-ok :res resource :holder holder
                                 :epoch (:epoch renewed)})
      fresh-release (request port {:op :release-lease :res resource :holder holder
                                   :epoch (:epoch renewed)})
      after-release (request port {:op :fence-ok :res resource :holder holder
                                   :epoch (:epoch renewed)})]
  (if (and (:ok acquired)
           (:ok renewed)
           (= (:ok renewed) (:epoch renewed))
           (> (:epoch renewed) (:epoch acquired))
           (false? (:fence-ok old-fence))
           (:noop stale-release)
           (:fence-ok fresh-fence)
           (:ok fresh-release)
           (false? (:fence-ok after-release)))
    (println (pr-str {:acquired (:epoch acquired)
                      :renewed (:epoch renewed)
                      :released true}))
    (System/exit 1)))'
if ! lease_receipt="$("$bb" -e "$lease_probe" "$port" 2>/dev/null)"; then
  echo "fram package smoke: packaged daemon failed exact-epoch lease renewal" >&2
  sed -n '1,200p' "$daemon_output" >&2
  exit 1
fi

if [[ "$require_proc" == "1" ]]; then
  if [[ ! -r "/proc/$pid/cmdline" || ! -e "/proc/$pid/cwd" ]]; then
    echo "fram package smoke: /proc evidence unavailable for daemon $pid" >&2
    exit 1
  fi
  cmdline="$("$tr_bin" '\0' '\n' <"/proc/$pid/cmdline")"
  if "$grep_bin" -Fq "/home/tom" <<<"$cmdline"; then
    echo "fram package smoke: daemon cmdline escaped into /home/tom" >&2
    printf '%s\n' "$cmdline" >&2
    exit 1
  fi
  if ! "$grep_bin" -Fq "$package_root" <<<"$cmdline"; then
    echo "fram package smoke: daemon cmdline does not reference the package root" >&2
    printf '%s\n' "$cmdline" >&2
    exit 1
  fi
  daemon_cwd="$("$readlink_bin" "/proc/$pid/cwd")"
  if [[ "$daemon_cwd" != "$package_root/libexec/fram" ]]; then
    echo "fram package smoke: unexpected daemon cwd: $daemon_cwd" >&2
    exit 1
  fi
fi

# The live daemon serves corpus A. A fresh CLI/MCP pointed at corpus B on the
# same port must report a mismatch and must not append either file.
other_facts="$work/other.log"
: >"$other_facts"
cp "$facts" "$work/facts.before"
cp "$other_facts" "$work/other.before"
mismatch_doctor="$("$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$other_facts" \
  "$package_root/bin/fram" doctor)"
if ! "$grep_bin" -Fq "coordinator WRONG LOG" <<<"$mismatch_doctor" ||
   "$grep_bin" -Fq "coordinator UP" <<<"$mismatch_doctor"; then
  echo "fram package smoke: doctor did not fail closed on a live wrong-log daemon" >&2
  printf '%s\n' "$mismatch_doctor" >&2
  exit 1
fi

mismatch_tell="$("$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$other_facts" \
  "$package_root/bin/fram" tell wrong note never)"
if ! "$grep_bin" -Fq "different log" <<<"$mismatch_tell"; then
  echo "fram package smoke: CLI tell did not reject a live wrong-log daemon" >&2
  printf '%s\n' "$mismatch_tell" >&2
  exit 1
fi
mismatch_retract="$("$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$other_facts" \
  "$package_root/bin/fram" retract wrong note never)"
if ! "$grep_bin" -Fq "different log" <<<"$mismatch_retract"; then
  echo "fram package smoke: CLI retract did not reject a live wrong-log daemon" >&2
  printf '%s\n' "$mismatch_retract" >&2
  exit 1
fi

mcp_write_request='{"jsonrpc":"2.0","id":91,"method":"tools/call","params":{"name":"tell","arguments":{"subject":"wrong","predicate":"note","object":"never"}}}'
mismatch_mcp="$(printf '%s\n' "$mcp_write_request" | "$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$other_facts" \
  "$package_root/bin/fram-mcp" 2>"$work/mismatch-mcp.err")"
if ! "$grep_bin" -Fq '"isError":true' <<<"$mismatch_mcp" ||
   ! "$grep_bin" -Fq "different log" <<<"$mismatch_mcp"; then
  echo "fram package smoke: MCP tell did not reject a live wrong-log daemon" >&2
  sed -n '1,120p' "$work/mismatch-mcp.err" >&2
  printf '%s\n' "$mismatch_mcp" >&2
  exit 1
fi

mcp_edit_request='{"jsonrpc":"2.0","id":92,"method":"tools/call","params":{"name":"set-body","arguments":{"module":"missing","name":"missing","body":"(+ 1 2)"}}}'
mismatch_mcp_edit="$(printf '%s\n' "$mcp_edit_request" | "$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$other_facts" \
  FRAM_FLIP="1" \
  FRAM_CODE_PORT="$port" \
  FRAM_CODE_LOG="$other_facts" \
  "$package_root/bin/fram-mcp" 2>"$work/mismatch-mcp-edit.err")"
if ! "$grep_bin" -Fq '"isError":true' <<<"$mismatch_mcp_edit" ||
   ! "$grep_bin" -Fq "log mismatch" <<<"$mismatch_mcp_edit"; then
  echo "fram package smoke: MCP direct edit-min did not reject a live wrong-log daemon" >&2
  sed -n '1,120p' "$work/mismatch-mcp-edit.err" >&2
  printf '%s\n' "$mismatch_mcp_edit" >&2
  exit 1
fi
if ! cmp -s "$work/facts.before" "$facts" ||
   ! cmp -s "$work/other.before" "$other_facts"; then
  echo "fram package smoke: wrong-log CLI/MCP probe mutated corpus A or B" >&2
  exit 1
fi

# Canonical identity, not spelling: a symlink to A is the same corpus and must
# retain the exact healthy first-line contract used by North's lifecycle probe.
alias_facts="$work/alias.log"
ln -s "$facts" "$alias_facts"
matching_doctor="$("$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$alias_facts" \
  "$package_root/bin/fram" doctor)"
matching_first="${matching_doctor%%$'\n'*}"
if ! "$grep_bin" -Eq "^coordinator UP on 127\\.0\\.0\\.1:${port} \\(v[0-9]+\\)$" <<<"$matching_first"; then
  echo "fram package smoke: canonical-equivalent doctor lost its healthy contract" >&2
  printf '%s\n' "$matching_doctor" >&2
  exit 1
fi
matching_tell="$("$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$alias_facts" \
  "$package_root/bin/fram" tell right note landed)"
if ! "$grep_bin" -Fq "committed via coordinator" <<<"$matching_tell" ||
   [[ ! -s "$facts" || -s "$other_facts" ]]; then
  echo "fram package smoke: canonical-equivalent write did not land only in corpus A" >&2
  printf '%s\n' "$matching_tell" >&2
  exit 1
fi

kill "$pid"
for _ in $(seq 1 100); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.05
done
if kill -0 "$pid" 2>/dev/null; then
  echo "fram package smoke: daemon ignored SIGTERM" >&2
  exit 1
fi
wait "$pid" 2>/dev/null || true
pid=

# Explicit paths are a complete hermetic configuration: HOME/XDG are optional
# when callers supply the corpus and thread roots themselves.
homeless_threads="$work/homeless-threads"
mkdir -p "$homeless_threads"
if ! homeless_cli="$("$env_bin" -i \
  FRAM_PORT="$port" \
  FRAM_LOG="$facts" \
  FRAM_THREADS="$homeless_threads" \
  "$package_root/bin/fram" doctor)"; then
  echo "fram package smoke: home-less CLI with explicit paths failed" >&2
  exit 1
fi
if ! "$grep_bin" -Fq "coordinator DOWN" <<<"$homeless_cli"; then
  echo "fram package smoke: home-less CLI did not reach its doctor handshake" >&2
  printf '%s\n' "$homeless_cli" >&2
  exit 1
fi

homeless_mcp_request='{"jsonrpc":"2.0","id":101,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"package-homeless-smoke","version":"0"}}}'
if ! homeless_mcp="$(printf '%s\n' "$homeless_mcp_request" | "$env_bin" -i \
  FRAM_LOG="$facts" \
  FRAM_THREADS="$homeless_threads" \
  "$package_root/bin/fram-mcp" 2>"$work/homeless-mcp.err")"; then
  echo "fram package smoke: home-less MCP with explicit paths failed" >&2
  sed -n '1,120p' "$work/homeless-mcp.err" >&2
  exit 1
fi
if ! "$grep_bin" -Fq '"serverInfo":{"name":"fram"' <<<"$homeless_mcp"; then
  echo "fram package smoke: home-less MCP initialize handshake failed" >&2
  printf '%s\n' "$homeless_mcp" >&2
  exit 1
fi

state_only="$work/explicit-state"
if ! state_only_cli="$("$env_bin" -i \
  FRAM_STATE_DIR="$state_only" \
  FRAM_PORT="$port" \
  "$package_root/bin/fram" doctor)"; then
  echo "fram package smoke: home-less CLI with FRAM_STATE_DIR failed" >&2
  exit 1
fi
if ! "$grep_bin" -Fq "coordinator DOWN" <<<"$state_only_cli" ||
   [[ ! -d "$state_only/threads" ]]; then
  echo "fram package smoke: FRAM_STATE_DIR did not supply CLI defaults" >&2
  printf '%s\n' "$state_only_cli" >&2
  exit 1
fi

homeless_daemon_port="$("$bb" -e '(with-open [s (java.net.ServerSocket. 0)] (println (.getLocalPort s)))')"
homeless_daemon_output="$work/homeless-daemon.out"
(
  cd "$work/cwd"
  exec "$env_bin" -i \
    FRAM_BIND="127.0.0.1" \
    "$package_root/bin/fram-daemon" serve-flat "$homeless_daemon_port" "$facts"
) >"$homeless_daemon_output" 2>&1 &
pid=$!

homeless_daemon_response=
for _ in $(seq 1 150); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "fram package smoke: home-less positional-log daemon exited before readiness" >&2
    sed -n '1,200p' "$homeless_daemon_output" >&2
    exit 1
  fi
  if homeless_daemon_response="$("$bb" -e "$probe" "$homeless_daemon_port" 2>/dev/null)"; then
    break
  fi
  homeless_daemon_response=
  sleep 0.1
done
if [[ -z "$homeless_daemon_response" ]]; then
  echo "fram package smoke: home-less positional-log daemon never became ready" >&2
  sed -n '1,200p' "$homeless_daemon_output" >&2
  exit 1
fi
kill "$pid"
for _ in $(seq 1 100); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.05
done
if kill -0 "$pid" 2>/dev/null; then
  echo "fram package smoke: home-less positional-log daemon ignored SIGTERM" >&2
  exit 1
fi
wait "$pid" 2>/dev/null || true
pid=

# A no-log packaged launch must select writable user state, never libexec.
state_port="$("$bb" -e '(with-open [s (java.net.ServerSocket. 0)] (println (.getLocalPort s)))')"
state_output="$work/state-daemon.out"
(
  cd "$work/cwd"
  exec "$env_bin" -i \
    HOME="$home" \
    XDG_CACHE_HOME="$home/.cache" \
    FRAM_BIND="127.0.0.1" \
    "$package_root/bin/fram-daemon" serve-flat "$state_port"
) >"$state_output" 2>&1 &
pid=$!

state_response=
for _ in $(seq 1 150); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "fram package smoke: default-state daemon exited before readiness" >&2
    sed -n '1,200p' "$state_output" >&2
    exit 1
  fi
  if state_response="$("$bb" -e "$probe" "$state_port" 2>/dev/null)"; then
    break
  fi
  state_response=
  sleep 0.1
done
if [[ -z "$state_response" ]]; then
  echo "fram package smoke: default-state daemon never became ready" >&2
  sed -n '1,200p' "$state_output" >&2
  exit 1
fi
expected_state_log="$home/.local/state/fram/coordination.log"
if [[ ! -f "$expected_state_log" ]]; then
  echo "fram package smoke: packaged daemon did not create writable state log" >&2
  exit 1
fi
# Legacy retirement: the packaged default must be coordination.log, never the
# retired facts.log alias (2026-07-16 split incident). A fresh state dir stays
# free of the legacy path.
if [[ -e "$home/.local/state/fram/facts.log" ]]; then
  echo "fram package smoke: packaged daemon created the retired legacy facts.log" >&2
  exit 1
fi
if [[ -e "$package_root/coordination.log" ||
      -e "$package_root/libexec/fram/coordination.log" ]]; then
  echo "fram package smoke: packaged daemon wrote state into the Nix output" >&2
  exit 1
fi
kill "$pid"
for _ in $(seq 1 100); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.05
done
if kill -0 "$pid" 2>/dev/null; then
  echo "fram package smoke: default-state daemon ignored SIGTERM" >&2
  exit 1
fi
wait "$pid" 2>/dev/null || true
pid=

cli_output="$("$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_PORT="$port" \
  FRAM_LOG="$facts" \
  "$package_root/bin/fram" doctor)"
if ! "$grep_bin" -Fq "coordinator DOWN" <<<"$cli_output"; then
  echo "fram package smoke: CLI doctor did not reach its parser/handshake" >&2
  printf '%s\n' "$cli_output" >&2
  exit 1
fi

primer_output="$("$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  "$package_root/bin/fram-primer" --beagle-catalog)"
if ! "$grep_bin" -Fq "define STDLIB-FRAM" <<<"$primer_output"; then
  echo "fram package smoke: primer did not read packaged Fram sources" >&2
  exit 1
fi

mcp_request='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"package-smoke","version":"0"}}}'
mcp_output="$(printf '%s\n' "$mcp_request" | "$env_bin" -i \
  HOME="$home" \
  XDG_CACHE_HOME="$home/.cache" \
  FRAM_LOG="$facts" \
  "$package_root/bin/fram-mcp" 2>"$work/mcp.err")"
if ! "$grep_bin" -Fq '"serverInfo":{"name":"fram"' <<<"$mcp_output"; then
  echo "fram package smoke: MCP initialize handshake failed" >&2
  sed -n '1,120p' "$work/mcp.err" >&2
  printf '%s\n' "$mcp_output" >&2
  exit 1
fi

echo "fram package smoke: raw :version response $response"
echo "fram package smoke: exact-epoch renewal receipt $lease_receipt"
echo "fram package smoke: home-less explicit paths, writable default state, CLI, MCP, and primer surfaces reached"
