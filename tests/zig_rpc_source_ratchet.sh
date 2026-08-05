#!/usr/bin/env bash
set -euo pipefail

repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

legacy='MapFields|Parser|parseMap|parseOperation|decodeEdnString|writeEdnString|InvalidEdn|FRAM_REQUIRE_LOG_FENCE|handleRequest|renderOk|renderConflict'
if rg -n "$legacy" "$repo/src/zig"; then
  echo "zig FRAMRPC source ratchet: legacy text request transport remains" >&2
  exit 1
fi

primitive_alias='TurtleRow|turtle-id|turtle_log|turtle log'
if rg -n "$primitive_alias" "$repo/src/zig"; then
  echo "zig FRAMRPC source ratchet: Turtle was used as a second primitive name" >&2
  exit 1
fi

required_ops=(
  version status assert retract batch scan query occurrences
  lease-acquire lease-renew lease-release lease-check validate
)
for operation in "${required_ops[@]}"; do
  rg -q "rpc/$operation" "$repo/src/zig/server.zig"
done

printf 'zig FRAMRPC source ratchet: framed-only transport, one Triple vocabulary, 13/13 operations\n'
