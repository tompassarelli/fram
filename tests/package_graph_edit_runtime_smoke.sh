#!/usr/bin/env bash
# Hermetic contract test for the sealed graph-edit runtime package.
set -euo pipefail

package_root="${1:?usage: package_graph_edit_runtime_smoke.sh /nix/store/...-fram-graph-edit-runtime}"
bb="${FRAM_RUNTIME_TEST_BB:?FRAM_RUNTIME_TEST_BB is required}"
cmp_bin="${FRAM_RUNTIME_TEST_CMP:?FRAM_RUNTIME_TEST_CMP is required}"
env_bin="${FRAM_RUNTIME_TEST_ENV:?FRAM_RUNTIME_TEST_ENV is required}"
grep_bin="${FRAM_RUNTIME_TEST_GREP:?FRAM_RUNTIME_TEST_GREP is required}"
python="${FRAM_RUNTIME_TEST_PYTHON:?FRAM_RUNTIME_TEST_PYTHON is required}"
sleep_bin="${FRAM_RUNTIME_TEST_SLEEP:?FRAM_RUNTIME_TEST_SLEEP is required}"

entrypoint="$package_root/bin/fram-graph-edit-runtime"
manifest="$package_root/share/fram/graph-edit-runtime-core-v1.json"
case "$package_root" in
  /nix/store/*) ;;
  *) echo "graph-edit runtime smoke: non-store package root: $package_root" >&2; exit 2 ;;
esac
[[ -x "$entrypoint" ]] || { echo "graph-edit runtime smoke: missing entrypoint" >&2; exit 1; }
[[ -r "$manifest" ]] || { echo "graph-edit runtime smoke: missing core manifest" >&2; exit 1; }

work="$(mktemp -d)"
cleanup() {
  rm -rf "${work:?}"
}
trap cleanup EXIT INT TERM

"$entrypoint" manifest >"$work/manifest-a.json"
"$entrypoint" manifest >"$work/manifest-b.json"
"$cmp_bin" -s "$manifest" "$work/manifest-a.json" || {
  echo "graph-edit runtime smoke: manifest command differs from packaged bytes" >&2
  exit 1
}
"$cmp_bin" -s "$work/manifest-a.json" "$work/manifest-b.json" || {
  echo "graph-edit runtime smoke: repeated manifest output is not byte-identical" >&2
  exit 1
}

"$bb" -e '
  (require (quote [cheshire.core :as json]))
  (let [m (json/parse-string (slurp (first *command-line-args*)) true)
        roots (:storeRoots m)
        store? #(clojure.string/starts-with? (str (:path %)) "/nix/store/")]
    (when-not (and (= "fram.graph-edit-runtime-core/v1" (:manifestVersion m))
                   (= "north" (:verificationOwner m))
                   (false? (:selfAttestation m))
                   (nil? (:closureDigest m))
                   (= "graph-edit-authority-v1" (:authorityProfile m))
                   (= #{"babashka" "beagle" "fram" "jdk" "racket"}
                      (set (map :role roots)))
                   (every? store? roots))
      (binding [*out* *err*] (println (pr-str m)))
      (System/exit 1)))' "$manifest"

checkout="$work/checkout"
source_root="$checkout/src"
code_log="$checkout/.fram/code.log"
evil="$work/evil"
marker="$work/hostile-executable-ran"
mkdir -p "$source_root" "$checkout/.fram" "$evil/bin" "$evil/home/code/beagle"
printf '%s\n' '{:tx 1 :op "assert" :l "@empty#root" :p "file" :r "empty.bclj"}' >"$code_log"
printf '%s\n' '{"mcpServers":{"fram":{"command":"/definitely/hostile"}}}' >"$checkout/.mcp.json"
for name in bb racket direnv beagle-build-all; do
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" %q >>%q\nexit 99\n' "$name" "$marker" >"$evil/bin/$name"
  chmod +x "$evil/bin/$name"
done
printf 'printf "BASH_ENV\\n" >>%q\n' "$marker" >"$evil/bash-env"
printf -v exported_printf \
  'BASH_FUNC_printf%%%%=() { echo BASH_FUNCTION >>%q; builtin printf "$@"; }' \
  "$marker"

instance_id="123e4567-e89b-42d3-a456-426614174000"
lease_id="123e4567-e89b-42d3-b456-426614174001"
lease_epoch=7
runtime_digest="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

# A listener makes the no-side-effect claim observable: missing authority
# inputs must fail before any connection to the North-selected coordinator.
port_file="$work/port"
accepted="$work/coordinator-contacted"
"$python" -c '
import pathlib, socket, sys
port_file, accepted = map(pathlib.Path, sys.argv[1:])
with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    listener.settimeout(2.0)
    port_file.write_text(str(listener.getsockname()[1]))
    try:
        connection, _ = listener.accept()
    except TimeoutError:
        pass
    else:
        accepted.write_text("contacted")
        connection.close()
' "$port_file" "$accepted" &
listener_pid=$!
for ((attempt=0; attempt<100; attempt++)); do
  [[ -s "$port_file" ]] && break
  "$sleep_bin" 0.02
done
[[ -s "$port_file" ]] || { echo "graph-edit runtime smoke: listener did not start" >&2; exit 1; }
port="$(<"$port_file")"

common_hostile=(
  PATH="$evil/bin"
  HOME="$evil/home"
  BASH_ENV="$evil/bash-env"
  ENV="$evil/bash-env"
  "$exported_printf"
  BEAGLE_HOME="$evil/home/code/beagle"
  FRAM="$evil/fram"
  FRAM_BIN="$evil/bin"
  FRAM_BUILD_ALL="$evil/bin/beagle-build-all"
  FRAM_CHECK_EMIT="$evil/check-emit.rkt"
  FRAM_CODE_LOG="$evil/code.log"
  FRAM_CODE_PORT=1
  FRAM_HOME="$evil/fram"
  FRAM_LOG="$evil/facts.log"
  FRAM_MCP_PROFILE=full
  FRAM_OUT="$evil/out"
  FRAM_RACKET="$evil/bin/racket"
  FRAM_RESOLVE="$evil/resolve.clj"
  FRAM_ROUNDTRIP="$evil/roundtrip.rkt"
  FRAM_SRC="$evil/src"
  FRAM_THREADS="$evil/threads"
  FRAM_GRAPH_EDIT_SEALED_BB="$evil/bin/bb"
  FRAM_GRAPH_EDIT_SEALED_FRAM="$evil/fram"
  NORTH_FRAM_AUTHORITY_INSTANCE_ID="$instance_id"
  NORTH_FRAM_AUTHORITY_LEASE_EPOCH="$lease_epoch"
  NORTH_FRAM_CHECKOUT_ROOT="$checkout"
  NORTH_FRAM_SOURCE_ROOT="$source_root"
  NORTH_FRAM_CODE_LOG="$code_log"
  NORTH_FRAM_CODE_PORT="$port"
)

if "$env_bin" -i "${common_hostile[@]}" \
     NORTH_FRAM_RUNTIME_CLOSURE_DIGEST="$runtime_digest" \
     "$entrypoint" mcp >"$work/missing-lease.out" 2>"$work/missing-lease.err"; then
  echo "graph-edit runtime smoke: missing lease unexpectedly launched" >&2
  exit 1
fi
"$grep_bin" -Fq 'missing required North launch binding NORTH_FRAM_AUTHORITY_LEASE_ID' \
  "$work/missing-lease.err" || {
  echo "graph-edit runtime smoke: missing lease diagnostic lost" >&2
  exit 1
}

if "$env_bin" -i "${common_hostile[@]}" \
     NORTH_FRAM_AUTHORITY_LEASE_ID="$lease_id" \
     "$entrypoint" mcp >"$work/missing-runtime.out" 2>"$work/missing-runtime.err"; then
  echo "graph-edit runtime smoke: missing runtime digest unexpectedly launched" >&2
  exit 1
fi
"$grep_bin" -Fq 'missing required North launch binding NORTH_FRAM_RUNTIME_CLOSURE_DIGEST' \
  "$work/missing-runtime.err" || {
  echo "graph-edit runtime smoke: missing runtime-seal diagnostic lost" >&2
  exit 1
}

wait "$listener_pid" || true
[[ ! -e "$accepted" ]] || {
  echo "graph-edit runtime smoke: missing-input path contacted the coordinator" >&2
  exit 1
}
[[ ! -e "$marker" ]] || {
  echo "graph-edit runtime smoke: missing-input path ran a hostile executable" >&2
  exit 1
}

complete_hostile=(
  "${common_hostile[@]}"
  NORTH_FRAM_AUTHORITY_LEASE_ID="$lease_id"
  NORTH_FRAM_RUNTIME_CLOSURE_DIGEST="$runtime_digest"
)
"$env_bin" -i "${complete_hostile[@]}" "$entrypoint" preflight >"$work/preflight.json"

"$bb" -e '
  (require (quote [cheshire.core :as json]))
  (let [[path evil digest] *command-line-args*
        m (json/parse-string (slurp path) true)
        rendered (pr-str m)
        stores (concat (vals (:storeExecutables m)) (vals (:storeHelpers m)))]
    (when-not (and (= "fram.graph-edit-authority-launch/v1" (:contractVersion m))
                   (= "north" (:verificationOwner m))
                   (false? (:runtimeDigestVerifiedByEntrypoint m))
                   (= digest (get-in m [:authority :northSuppliedRuntimeClosureDigest]))
                   (every? #(clojure.string/starts-with? (str %) "/nix/store/") stores)
                   (not (clojure.string/includes? rendered evil)))
      (binding [*out* *err*] (println rendered))
      (System/exit 1)))' "$work/preflight.json" "$evil" "$runtime_digest"

# Until the separate coordinator authority slice lands, a fully bound launch
# reaches only the immutable MCP's unknown-profile fence: zero JSON-RPC replies,
# no coordinator authority implied, and no ambient executable used.
if "$env_bin" -i "${complete_hostile[@]}" "$entrypoint" mcp \
     </dev/null >"$work/mcp.out" 2>"$work/mcp.err"; then
  echo "graph-edit runtime smoke: authority MCP unexpectedly served before profile implementation" >&2
  exit 1
fi
[[ ! -s "$work/mcp.out" ]] || {
  echo "graph-edit runtime smoke: dark authority MCP emitted a protocol reply" >&2
  exit 1
}
"$grep_bin" -Fq 'unknown profile' "$work/mcp.err" || {
  echo "graph-edit runtime smoke: immutable MCP did not reach the authority-profile fence" >&2
  exit 1
}
[[ ! -e "$marker" ]] || {
  echo "graph-edit runtime smoke: hostile PATH/HOME/FRAM/BEAGLE input redirected execution" >&2
  exit 1
}

printf '%s\n' 'graph-edit runtime smoke: PASS'
