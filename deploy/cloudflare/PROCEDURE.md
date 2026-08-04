# Fram from Cloudflare Workers

The Worker is stateless. It sends authenticated JSON to the shim; the shim
validates a closed request, converts tagged JSON Terms to FRAMRPC, and opens one
private socket to the durable coordinator.

```text
Worker -- HTTPS + bearer + JSON --> shim -- private FRAMRPC --> coordinator
```

Only the shim is public. Never publish coordinator port 7977: FRAMRPC delegates
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

The coordinator image compiles the Beagle-emitted JVM closure into one static
Graal executable. This is a release/deployment build; normal coordinator
development stays on `FRAM_DAEMON_RUNTIME=jvm-dev` and does not run Graal.
The separate shim image remains Babashka.

From `fram:deploy/cloudflare`:

```sh
export SHIM_TOKEN=$(openssl rand -hex 32)
export FRAM_SPACE_ID=my-production-space
docker compose up -d --build
docker compose ps
```

The coordinator persists `/data/history.framlog`. If this deployment has an old
flat `facts.log`, stop the old daemon and migrate it once before starting the new
image:

```sh
bin/fram-migrate-triple-log /path/to/facts.log my-production-space /path/to/history.framlog
```

Do not retain a dual-serving fallback after migration.

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
operation to the wrong path is rejected before coordinator contact.

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
