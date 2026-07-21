# Fram from Cloudflare Workers — exact deployment procedure

Goal: an app on **ephemeral Cloudflare Workers** uses a **Fram coordinator**
(this repo's fact engine) as its durable fact store. The Worker holds no
state; one Docker'd coordinator is the single writer; a tiny authenticated
shim bridges the Worker's `fetch()` to the coordinator's one-line-EDN TCP
protocol.

```
Worker (ephemeral isolate)
   │  fetch() POST /q | /assert   Authorization: Bearer <token>
   ▼
shim  (bb shim.clj, Docker)  ── the ONLY public ingress
   │  one line of EDN over TCP, private Docker network
   ▼
coordinator (JVM daemon, Docker)  ── durable /data/facts.log on a volume
```

**Why a shim and not a direct socket** — decided on evidence, 2026-07:
Workers *can* open outbound TCP (`connect()` from `cloudflare:sockets`,
<https://developers.cloudflare.com/workers/runtime-apis/tcp-sockets/>) and
speak TLS (`secureTransport: "on"`), but they **cannot present a client
certificate on a raw socket** — the `mtls_certificates` binding attaches
client certs to `fetch()` only
(<https://developers.cloudflare.com/workers/runtime-apis/bindings/mtls/>),
and the tcp-sockets API has no client-cert option. Fram's engine-terminated
TLS *requires* a verified client cert (`setNeedClientAuth(true)` —
`docs/coordinator-bind-and-wire.md`), and the plaintext protocol is
unauthenticated by design and must never be published. So the working
topology today is the shim. (Alternative, see the last section.)

Files in this kit (`deploy/cloudflare/`):

| file | role |
|---|---|
| `Dockerfile` | coordinator image (JVM daemon, volume'd fact log, healthcheck) |
| `Dockerfile.shim` | shim image (babashka) |
| `docker-compose.yml` | coordinator + shim, private network, volume |
| `shim.clj` | bearer-token HTTP→TCP bridge (`POST /q`, `POST /assert`) |
| `worker-client.js` | zero-dep JS client + minimal EDN codec (Workers **and** node) |
| `worker-example.js` | runnable example Worker |
| `wrangler.toml` | deploys the example |

---

## 1. Build and start the backend (on your Docker host)

```sh
git clone https://github.com/tompassarelli/fram && cd fram/deploy/cloudflare
export SHIM_TOKEN=$(openssl rand -hex 32)   # SAVE THIS — the Worker needs it
docker compose up -d --build
```

Expected: two services build and start; `docker compose ps` shows
`coordinator` **healthy** (healthcheck speaks the real protocol: one
`{:op :version}` line must answer) and `shim` up, with only host port
**8080** published. First coordinator boot takes ~10–30 s (JVM + log fold).

## 2. Smoke the shim locally (still on the Docker host)

```sh
curl -s -X POST http://127.0.0.1:8080/q \
  -H "Authorization: Bearer $SHIM_TOKEN" -d '{:op :version}'
# -> {:version 0}

curl -s -X POST http://127.0.0.1:8080/assert \
  -H "Authorization: Bearer $SHIM_TOKEN" \
  -d '{:op :assert :te "@smoke" :p "title" :r "hello"}'
# -> {:ok 1}

curl -s -X POST http://127.0.0.1:8080/q -d '{:op :version}'
# -> {:error "unauthorized"}        (no token: shim refuses — expected)
```

## 3. Expose the shim to Cloudflare

Pick one:

- **A (recommended):** put your usual reverse proxy / tunnel in front of the
  shim with TLS — `https://fram-shim.example.com` → `127.0.0.1:8080`.
- **B (quick benchmark):** use plain `http://<host>:8080` directly. 8080 is
  on Cloudflare's supported HTTP port list, so Worker `fetch()` can reach it;
  the bearer token then travels unencrypted — fine for a throwaway benchmark
  token, not for real data.

Never expose the coordinator's raw port (7977) — the EDN protocol has no auth.

## 4. Deploy the Worker

```sh
cd deploy/cloudflare
# edit wrangler.toml: set SHIM_URL to your URL from step 3
npx wrangler secret put SHIM_TOKEN     # paste the token from step 1
npx wrangler deploy
```

Expected: wrangler prints the deployed URL, e.g.
`https://fram-bench.<you>.workers.dev`.

## 5. Smoke the Worker end-to-end

```sh
W=https://fram-bench.<you>.workers.dev
curl -s $W/health                          # -> coordinator :status JSON (version, facts, log path)
curl -s -X POST $W/fact -d '{"l":"@bench1","p":"title","r":"hello from workers"}'
# -> {"ok": 2}
curl -s "$W/facts?p=title"                 # -> {"ok":[["@smoke","hello"],["@bench1","hello from workers"]], "engine":"index", ...}
curl -s "$W/bench?n=20&p=title"            # -> p50/min/max ms for 20 round-trips
```

## 6. Observed local smoke test (no Docker — the same stack on one machine)

This exact transcript was produced against a real daemon
(`bin/fram-daemon serve-flat 7999 <scratch>/facts.log`), the real shim
(`FRAM_PORT=7999 SHIM_PORT=8799 SHIM_TOKEN=... bb shim.clj`), driving
`worker-client.js` under **node 24** — three asserts, two queries, an auth
probe:

```
assert 1 -> {"ok":1}
assert 2 -> {"ok":2}
assert 3 -> {"ok":3}
query {p: title} -> {"ok":[["@bench1","hello from workers"],["@bench2","second subject"]],"version":3,"engine":"index"}
query {l: @bench1} -> {"ok":[["title","hello from workers"],["kind","bench"]],"version":3,"engine":"index"}
version (round-trip 3ms) -> {"version":3}
wrong token -> fram shim HTTP 401: {:error "unauthorized"}
```

The fact log after those writes (this is the whole durable state — three
greppable lines):

```
{:tx 1, :op "assert", :l "@bench1", :p "title", :r "hello from workers", :ts "2026-07-21T03:30:31.119839991Z", :by "coord"}
{:tx 2, :op "assert", :l "@bench1", :p "kind", :r "bench", :ts "2026-07-21T03:30:31.143726670Z", :by "coord"}
{:tx 3, :op "assert", :l "@bench2", :p "title", :r "second subject", :ts "2026-07-21T03:30:31.150143064Z", :by "coord"}
```

Restart durability, observed: the daemon was killed and restarted on the same
log — it answered `{:version 3}` and the same query returned the same rows
(`engine "index"`). That is exactly what a container restart does with the
compose volume.

To reproduce locally: `bin/fram-daemon serve-flat 7999 /tmp/facts.log`, then
`FRAM_PORT=7999 SHIM_PORT=8799 SHIM_TOKEN=t bb deploy/cloudflare/shim.clj`,
then drive `framClient({url:'http://127.0.0.1:8799', token:'t'})` from node.

---

## The ephemerality story

- **Workers hold NO state.** Every isolate — cold or warm — reconstructs
  nothing: `framClient` is a stateless wrapper around `fetch()`. There is no
  connection pool, no cache, no session. A cold start costs exactly **one
  HTTP round-trip to the shim** (which internally opens one TCP connection
  to the coordinator — the protocol is one connection per request by
  design), nothing else.
- **The coordinator container is the durable single writer.** All state is
  the append-only `/data/facts.log` on the compose volume. Restarts refold
  (or snapshot-boot) from it — observed above. Concurrent writers serialize
  through the daemon's one lock; optimistic `:base` versioning rejects
  conflicts instead of corrupting (`{:reject ...}` → retry).
- **Back up by copying one file.** `docker compose cp coordinator:/data/facts.log .`
  while running, or snapshot the volume. It's plain EDN text — `grep` works.

## Benchmark fairly

Comparing this against an **embedded/bespoke nodes-edges SQL DB**:

- **You are measuring a network hop; he is measuring a function call.** Every
  Fram operation here pays Worker→shim (public internet or tunnel) +
  shim→coordinator (sub-ms Docker bridge). If his SQL DB is queried in-process
  or over loopback, subtract transport: benchmark the shim from the same
  network position as his DB endpoint, or compare *both* through Workers.
- **Placement dominates.** Put the Docker host near the Workers' region if
  possible; report p50/p95 of `GET /bench?n=...` separately from a
  host-local `curl` of the shim, so engine time and transport time are
  separated. The observed engine-side round-trip above (client→shim→daemon→
  back, one machine) was **3 ms**.
- **Match the query class.** `tripleQuery` shapes (single rule, all-`triple`
  body) ride the daemon's index fast path (`"engine":"index"` in the reply);
  arbitrary Datalog falls to the scan engine (`"engine":"scan"`). Report
  which engine served each benchmark. Multi-hop graph traversals are
  recursive Datalog rules — bench those against his recursive SQL, not
  against single-edge lookups.
- **Writes serialize by design** (single-writer + fsync per commit). Bench
  write throughput as sequential round-trips, not parallel blast — parallel
  clients measure the lock queue, which is the intended semantics, but say
  so.
- **Limits to know:** 1 MiB per request/response line, 5 s default query
  timeout (`FRAM_QUERY_TIMEOUT_MS`), responses carry `:version` so reads are
  snapshot-consistent per request.

## Alternative topology (not shipped here)

If Cloudflare ships client certificates for raw sockets, the shim disappears:
run the daemon with `FRAM_TLS_KEYSTORE`/`FRAM_TLS_TRUSTSTORE`/`FRAM_TLS_PASS`
(engine-terminated mTLS, `docs/coordinator-bind-and-wire.md`), publish 7977
via TLS only, and have the Worker `connect()` with `secureTransport: "on"` +
its client cert, writing the same one-line EDN the client already encodes. As
of 2026-07 the tcp-sockets API offers no client-cert mechanism (the
2023 launch post said custom TLS credentials were "coming soon"; no doc since
confirms it shipped), so that path is closed — hence the shim.
