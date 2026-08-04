#!/usr/bin/env bash
# Exercise the release image itself: a FRAMRPC mutation must survive a cold
# container restart from the durable FRAMLOG.
set -euo pipefail

image="${1:?usage: graal_release_smoke.sh IMAGE}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
docker_bin="${DOCKER:-docker}"
node_bin="${NODE:-node}"

command -v "$docker_bin" >/dev/null 2>&1 || {
  echo "graal release smoke: docker is required" >&2
  exit 2
}
command -v "$node_bin" >/dev/null 2>&1 || {
  echo "graal release smoke: node is required" >&2
  exit 2
}
"$docker_bin" image inspect "$image" >/dev/null

name="fram-graal-release-$RANDOM-$$"
volume="$name-data"
space="graal-release-smoke"
port=""
cleanup() {
  "$docker_bin" rm -f "$name" >/dev/null 2>&1 || true
  "$docker_bin" volume rm -f "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

start() {
  local attempt
  "$docker_bin" volume inspect "$volume" >/dev/null 2>&1 ||
    "$docker_bin" volume create "$volume" >/dev/null
  for attempt in 1 2 3 4 5; do
    "$docker_bin" rm -f "$name" >/dev/null 2>&1 || true
    if "$docker_bin" run -d --name "$name" \
      -p 127.0.0.1::7977 \
      -v "$volume:/data" \
      -e "FRAM_SPACE_ID=$space" \
      "$image" serve 7977 /data/history.framlog >/dev/null; then
      break
    fi
    [[ "$attempt" -eq 5 ]] && {
      echo "graal release smoke: could not publish coordinator port" >&2
      exit 1
    }
  done
  port="$("$docker_bin" inspect --format '{{(index (index .NetworkSettings.Ports "7977/tcp") 0).HostPort}}' "$name")"
  [[ "$port" =~ ^[0-9]+$ ]] || {
    echo "graal release smoke: could not resolve mapped coordinator port" >&2
    "$docker_bin" logs "$name" >&2 || true
    exit 1
  }
}

probe() {
  local expected_version="$1" expect_fact="$2"
  FRAM_RELEASE_CLIENT="$repo/clients/node/framrpc.mjs" \
    FRAM_RELEASE_PORT="$port" \
    FRAM_RELEASE_SPACE="$space" \
    FRAM_RELEASE_EXPECTED_VERSION="$expected_version" \
    FRAM_RELEASE_EXPECT_FACT="$expect_fact" \
    "$node_bin" --input-type=module <<'NODE'
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const { framClient, keywordTerm } = await import(pathToFileURL(process.env.FRAM_RELEASE_CLIENT).href);
const fram = framClient({
  host: '127.0.0.1',
  port: Number(process.env.FRAM_RELEASE_PORT),
  space: process.env.FRAM_RELEASE_SPACE,
  requestTimeoutMs: 2000,
});
const expectedVersion = BigInt(process.env.FRAM_RELEASE_EXPECTED_VERSION);
const expectFact = process.env.FRAM_RELEASE_EXPECT_FACT === '1';

let status;
let lastError;
for (let attempt = 0; attempt < 120; attempt += 1) {
  try {
    status = await fram.status();
    break;
  } catch (error) {
    lastError = error;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
}
assert(status, `coordinator never became ready: ${lastError?.stack || lastError}`);
assert.equal(status.result.engine, 'rpc/graal', 'release image did not boot the Graal coordinator');
assert.equal((await fram.version()).servedVersion, expectedVersion, 'unexpected durable version');

if (expectFact) {
  const scan = await fram.scan({
    t1: '@graal-release', t2: keywordTerm('release/smoke'), t3: 'replayed',
  });
  assert.equal(scan.result.length, 1, 'restart replay lost the release mutation');
}
NODE
}

start
probe 0 0

FRAM_RELEASE_CLIENT="$repo/clients/node/framrpc.mjs" \
  FRAM_RELEASE_PORT="$port" \
  FRAM_RELEASE_SPACE="$space" \
  "$node_bin" --input-type=module <<'NODE'
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const { framClient, keywordTerm } = await import(pathToFileURL(process.env.FRAM_RELEASE_CLIENT).href);
const fram = framClient({
  host: '127.0.0.1', port: Number(process.env.FRAM_RELEASE_PORT),
  space: process.env.FRAM_RELEASE_SPACE,
});
const written = await fram.assert('@graal-release', keywordTerm('release/smoke'), 'replayed', {
  expectedVersion: 0n,
});
assert.equal(written.servedVersion, 1n, 'release mutation did not commit at version 1');
assert.equal(written.result[0].changed, true, 'release mutation was not applied');
assert.equal((await fram.validate()).result.valid, true, 'release mutation made the store invalid');
NODE

"$docker_bin" rm -f "$name" >/dev/null
start
probe 1 1

echo "graal release smoke: mutation and restart replay passed"
