#!/usr/bin/env bash
# fram_code_wire_test.sh — focused test for the dual Claude/Codex MCP wiring
# shared by fram-code-on/off (bin/fram-code-wire, fram-code-wire-toml.py) and
# for bin/fram-code-status's canonical= registry read. No daemon boot, no
# Beagle ingest — exercises only the merge/unwire/status-read logic so it
# runs in well under a second. Exits 0 iff every assertion holds.
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0
assert() { local desc="$1" cond="$2"; if eval "$cond"; then echo "ok - $desc"; else echo "FAIL - $desc"; FAIL=1; fi; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP:?}"' EXIT
DIR="$TMP/repo"
mkdir -p "$DIR/.codex"

# --- pre-existing unrelated wiring in both files ----------------------------
cat >"$DIR/.mcp.json" <<'JSON'
{
  "mcpServers": {
    "other-tool": { "command": "/bin/other", "args": [], "env": {} }
  }
}
JSON
cat >"$DIR/.codex/config.toml" <<'TOML'
[projects.unrelated]
trust_level = "trusted"

[mcp_servers.other]
command = "/bin/other"
args = []
TOML
cp "$DIR/.codex/config.toml" "$TMP/config.toml.orig"

SERVER_JSON='{"command":"/fake/fram-mcp","args":[],"env":{"FRAM_CODE_PORT":"31337","FRAM_CODE_LOG":"/fake/.fram/code.log"}}'

# --- wire ON: merge, preserve unrelated keys --------------------------------
"$HERE/bin/fram-code-wire" on "$DIR" "$SERVER_JSON"

assert "mcp.json gains mcpServers.fram" \
  '[ "$(jq -r ".mcpServers.fram.command" "$DIR/.mcp.json")" = "/fake/fram-mcp" ]'
assert "mcp.json keeps unrelated mcpServers.other-tool" \
  '[ "$(jq -r ".mcpServers[\"other-tool\"].command" "$DIR/.mcp.json")" = "/bin/other" ]'
assert "config.toml gains [mcp_servers.fram]" \
  'grep -q "^\[mcp_servers.fram\]$" "$DIR/.codex/config.toml"'
assert "config.toml fram command matches" \
  'grep -A2 "^\[mcp_servers.fram\]$" "$DIR/.codex/config.toml" | grep -q "/fake/fram-mcp"'
assert "config.toml keeps unrelated [projects.unrelated]" \
  'grep -q "^\[projects.unrelated\]$" "$DIR/.codex/config.toml"'
assert "config.toml keeps unrelated [mcp_servers.other]" \
  'grep -q "^\[mcp_servers.other\]$" "$DIR/.codex/config.toml"'

# --- idempotency: re-run ON must not duplicate -------------------------------
"$HERE/bin/fram-code-wire" on "$DIR" "$SERVER_JSON"
FRAM_HEADER_COUNT="$(grep -c '^\[mcp_servers\.fram\]$' "$DIR/.codex/config.toml")"
assert "re-running wire on: exactly one [mcp_servers.fram] block" \
  '[ "$FRAM_HEADER_COUNT" = "1" ]'
assert "re-running wire on: exactly one mcpServers.fram key" \
  '[ "$(jq ".mcpServers | keys | map(select(. == \"fram\")) | length" "$DIR/.mcp.json")" = "1" ]'

# --- fram-code-status reads canonical= from the upstream registry ----------
REG="$TMP/graph-upstream-files"
printf '%s/some/file.bclj\n' "$DIR" > "$REG"
STATUS_LINE="$(GRAPH_UPSTREAM_REGISTRY="$REG" "$HERE/bin/fram-code-status" "$DIR")"
assert "fram-code-status honors GRAPH_UPSTREAM_REGISTRY override" \
  'echo "$STATUS_LINE" | grep -q "canonical=1"'
assert "bin/fram-code-status never references graph-owned-files" \
  '[ "$(grep -c "graph-owned-files" "$HERE/bin/fram-code-status")" = "0" ]'

# --- wire OFF: remove only the fram section, byte-identical unrelated toml --
"$HERE/bin/fram-code-wire" off "$DIR"

assert "mcp.json loses mcpServers.fram" \
  '! jq -e ".mcpServers.fram" "$DIR/.mcp.json" >/dev/null 2>&1'
assert "mcp.json keeps unrelated mcpServers.other-tool after off" \
  '[ "$(jq -r ".mcpServers[\"other-tool\"].command" "$DIR/.mcp.json")" = "/bin/other" ]'
assert "config.toml loses [mcp_servers.fram]" \
  '! grep -q "^\[mcp_servers.fram\]$" "$DIR/.codex/config.toml"'
assert "config.toml unrelated sections still present after off" \
  'grep -q "^\[projects.unrelated\]$" "$DIR/.codex/config.toml" && grep -q "^\[mcp_servers.other\]$" "$DIR/.codex/config.toml"'

# --- byte-for-byte: unrelated config.toml content restored exactly after off,
#     not merely "unrelated sections still grep-able" ------------------------
assert "config.toml is byte-for-byte identical to pre-wire original after off" \
  'cmp -s "$DIR/.codex/config.toml" "$TMP/config.toml.orig"'

# --- every EOF shape round-trips byte-for-byte -----------------------------
roundtrip_toml() {
  local name="$1" repo="$2"
  cp "$repo/.codex/config.toml" "$repo/config.toml.orig"
  "$HERE/bin/fram-code-wire" on "$repo" "$SERVER_JSON"
  "$HERE/bin/fram-code-wire" on "$repo" "$SERVER_JSON"
  if [ "$(grep -c '^# >>> fram-code-wire managed mcp_servers\.fram ' "$repo/.codex/config.toml")" = "1" ] &&
     [ "$(grep -c '^# <<< fram-code-wire managed mcp_servers\.fram$' "$repo/.codex/config.toml")" = "1" ]; then
    echo "ok - $name has one owned marker pair after repeated on"
  else
    echo "FAIL - $name has one owned marker pair after repeated on"
    FAIL=1
  fi
  "$HERE/bin/fram-code-wire" off "$repo"
  if cmp -s "$repo/.codex/config.toml" "$repo/config.toml.orig"; then
    echo "ok - $name restores config.toml byte-for-byte"
  else
    echo "FAIL - $name restores config.toml byte-for-byte"
    FAIL=1
  fi
}

ROUNDTRIP_ROOT="$TMP/roundtrip"
mkdir -p "$ROUNDTRIP_ROOT/nonblank-newline/.codex" \
         "$ROUNDTRIP_ROOT/one-blank-line/.codex" \
         "$ROUNDTRIP_ROOT/multiple-blank-lines/.codex" \
         "$ROUNDTRIP_ROOT/no-final-newline/.codex"
printf '[features]\nfoo = true\n' > "$ROUNDTRIP_ROOT/nonblank-newline/.codex/config.toml"
printf '[features]\nfoo = true\n\n' > "$ROUNDTRIP_ROOT/one-blank-line/.codex/config.toml"
printf '[features]\nfoo = true\n\n\n\n' > "$ROUNDTRIP_ROOT/multiple-blank-lines/.codex/config.toml"
printf '[features]\nfoo = true' > "$ROUNDTRIP_ROOT/no-final-newline/.codex/config.toml"
roundtrip_toml "nonblank newline" "$ROUNDTRIP_ROOT/nonblank-newline"
roundtrip_toml "one blank line" "$ROUNDTRIP_ROOT/one-blank-line"
roundtrip_toml "multiple blank lines" "$ROUNDTRIP_ROOT/multiple-blank-lines"
roundtrip_toml "no final newline" "$ROUNDTRIP_ROOT/no-final-newline"

# --- fram-only .mcp.json is removed entirely on off -------------------------
DIR2="$TMP/repo2"
mkdir -p "$DIR2"
"$HERE/bin/fram-code-wire" on "$DIR2" "$SERVER_JSON"
assert "fram-only .mcp.json created" '[ -f "$DIR2/.mcp.json" ]'
assert "fram-only config.toml created" '[ -f "$DIR2/.codex/config.toml" ]'
assert "fram-only config.toml has owned marker" \
  'grep -q "^# >>> fram-code-wire managed mcp_servers\.fram separator=0$" "$DIR2/.codex/config.toml"'
"$HERE/bin/fram-code-wire" off "$DIR2"
assert "fram-only .mcp.json removed on off" '[ ! -f "$DIR2/.mcp.json" ]'
assert "fram-only config.toml removed on off" '[ ! -f "$DIR2/.codex/config.toml" ]'

# --- mcp.json: unrelated ROOT key (not just mcpServers) survives off, and
#     an emptied mcpServers is dropped entirely rather than left as {} -------
DIR3="$TMP/repo3"
mkdir -p "$DIR3"
cat >"$DIR3/.mcp.json" <<'JSON'
{
  "$schema": "https://example.com/mcp.schema.json"
}
JSON
"$HERE/bin/fram-code-wire" on "$DIR3" "$SERVER_JSON"
"$HERE/bin/fram-code-wire" off "$DIR3"
assert "mcp.json keeps unrelated root key after off" \
  '[ "$(jq -r ".\"\$schema\"" "$DIR3/.mcp.json")" = "https://example.com/mcp.schema.json" ]'
assert "mcp.json drops mcpServers key entirely (not empty {}) when emptied" \
  '[ "$(jq "has(\"mcpServers\")" "$DIR3/.mcp.json")" = "false" ]'

if [ "$FAIL" = 0 ]; then
  echo "fram_code_wire_test.sh: all assertions passed"
else
  echo "fram_code_wire_test.sh: FAILURES ABOVE" >&2
fi
exit "$FAIL"
