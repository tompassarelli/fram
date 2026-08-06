# Fram from Cloudflare Workers

The Worker is stateless. It sends authenticated JSON to the shim; the shim
validates a closed request, converts tagged JSON Terms to FRAMRPC, and opens one
private socket to the durable Fram server.

```text
Worker -- HTTPS + bearer + JSON --> shim -- private FRAMRPC --> Fram server
```

Only the shim is public. Never publish the Fram server port 7977: FRAMRPC delegates
authentication to the gateway.

## Boundary

There is one edge format: `application/json`. Configure the client with the
space identity explicitly:

```js
const fram = framClient({
  url: env.SHIM_URL,
  token: env.SHIM_TOKEN,
  space: env.FRAM_SPACE_ID,
});
```

Every Term is an exact tagged array:

```json
["string", "Alice"]
["integer", "1842"]
["float64", "3ff0000000000000"]
["boolean", true]
["keyword", "kernel/type"]
["instant", "1785580282", "123000000"]
["triple", ["string", "Alice"], ["keyword", "contact/email"], ["string", "alice@example.com"]]
```

Integers use canonical decimal strings. Float values use exact lowercase
IEEE-754 bits. Requests, pagination, errors, and responses reject unknown keys.
Neither the Worker nor the shim accepts EDN or an untyped raw escape hatch.

## Start the backend

The Fram server image compiles the Beagle-emitted JVM closure into one static
Graal executable. This is a release/deployment build; normal Fram server
development stays on `FRAM_SERVER_RUNTIME=jvm-dev` and does not run Graal.
The separate shim image remains Babashka.

From `fram:deploy/cloudflare`:

```sh
export SHIM_TOKEN=$(openssl rand -hex 32)
export FRAM_SPACE_ID=my-production-space
docker compose up -d --build
docker compose ps
```

The Fram server persists database history at `/data/history.framlog`. If this deployment has an old
flat `facts.log`, stop the old server and migrate it once before starting the new
image:

```sh
bin/fram-migrate-triple-log /path/to/facts.log my-production-space /path/to/history.framlog
```

Do not retain a dual-serving fallback after migration.

## Staged Native image

The current compose file deliberately continues to build `Dockerfile`, the
Graal release route. The additive Native image is packaged only from a completed
content-addressed `fram-native-build` artifact; it neither invokes Graal nor
carries a JVM.

A `scratch` image has no dynamic loader, so the artifact must be linked static.
`FRAM_NATIVE_STATIC=1` is what does that: it appends `-static` to the server
link line and records `link=static` in the artifact's input manifest, so a
static and a dynamic build are different cache entries. The compiler is a
static musl toolchain realized from the nixpkgs revision this repo's
`flake.lock` already pins:

```sh
rev="$(nix flake metadata --json . | jq -r '.locks.nodes.nixpkgs.locked.rev')"
cc="$(nix build --out-link /home/tom/.cache/fram/native-build/.musl-cc --print-out-paths \
  "github:NixOS/nixpkgs/$rev#pkgsStatic.stdenv.cc" | grep -v -- '-man$'
)/bin/x86_64-unknown-linux-musl-cc"
mapfile -t sources < <(sed "s#^#$PWD/#" native/core_closure_sources.txt)
artifact="$(FRAM_NATIVE_CC="$cc" FRAM_NATIVE_STATIC=1 \
  bin/fram-native-build --host server "${sources[@]}")"
bin/fram-cloudflare-native-image --artifact "$artifact" --tag fram-server-native:local
```

The `--out-link` roots the musl toolchain so nix GC cannot orphan it between builds.

The helper requires an absolute artifact path, verifies that its directory hash
and `READY` receipt agree, rejects any executable that still requests a program
interpreter, and uses that artifact directory as the complete Docker build
context. `Dockerfile.native` checks the same receipt before producing a
`scratch` runtime image, and sets `FRAM_BIND=0.0.0.0` because loopback inside
the container network namespace is unreachable through any port mapping —
publication stays governed by Docker networking, and port 7977 stays private.
That privacy guarantee now rests entirely on the `docker -p` binding: publishing
with `-p 0.0.0.0:7977:7977` exposes unauthenticated plaintext FRAMRPC, so bind
loopback or a private network only.

`FRAM_NATIVE_STATIC` is unset by default, so an ordinary checkout build is
still a dynamic host-libc link. This stages the release-image seam only; it
does not change compose, defaults, or the Graal deployment route.

## Smoke the shim

```sh
curl -sS http://127.0.0.1:8080/q \
  -H "Authorization: Bearer $SHIM_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"space":"my-production-space","op":"rpc/version","payload":["keyword","rpc/unit"]}'
```

Expected shape:

```json
{"space":"my-production-space","op":"rpc/version","servedVersion":"0","payload":["keyword","rpc/unit"]}
```

An assertion request contains the native typed write record. Applications
normally use `worker-client.js` rather than constructing that record by hand.
The shim exposes reads on `POST /q` and mutations on `POST /assert`; sending an
operation to the wrong path is rejected before Fram server contact.

## Deploy the Worker

Set `SHIM_URL` and `FRAM_SPACE_ID` in `fram:deploy/cloudflare/wrangler.toml`,
then install the same bearer token as a secret:

```sh
cd deploy/cloudflare
npx wrangler secret put SHIM_TOKEN
npx wrangler deploy
```

Smoke the example:

```sh
W=https://fram-bench.example.workers.dev
curl -sS "$W/health"
curl -sS -X POST "$W/fact" \
  -H 'Content-Type: application/json' \
  -d '{"t1":"@bench1","t2":"title","t3":"hello"}'
curl -sS "$W/facts?t2=title"
curl -sS "$W/bench?n=20&t2=title"
```

## Operational properties

- The 1 MiB limit applies independently to request JSON, FRAMRPC frames, and
  response JSON.
- Authentication runs before body parsing and uses a constant-time token
  comparison.
- `Request.space` is the database identity. Filesystem paths never cross the
  public boundary.
- Expected versions are exact logical transaction coordinates, not wall-clock
  timestamps.
- Container restart replays `history.framlog`; Workers keep no cache or session.
- TLS terminates at the reverse proxy or tunnel in front of port 8080. A plain
  Internet-facing bearer-token endpoint is not a production deployment.

Snapshot/reload administration, deployment cutovers, graph authoring, and
enforcement remain sealed-control operations. They are intentionally absent
from the public Worker client and shim allow-list.
