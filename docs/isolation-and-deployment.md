# Isolation and deployment

**Status:** Current source-head deployment boundary, with the deployed v0.3
handoff called out separately.

## Trust domains

Fram has no engine-level accounts, authorization, or tenant policy. Isolation
comes from four aligned coordinates:

- one `SpaceId`;
- one binary FRAMLOG history;
- one active writer process and its lock;
- one private network boundary.

Treat that set as one trust domain. Personal data, client data, and public
tooling belong in separate spaces, logs, processes, and gateway routes. Do not
use an ontology field as a substitute for tenant isolation.

## Private engine, authenticated edge

The native coordinator speaks plaintext FRAMRPC and binds loopback by default.
Remote deployments keep that socket private. TLS, bearer-token validation,
tenant routing, request limits, and public audit policy belong at the gateway or
sidecar.

The public edge must select a SpaceId and route only to the matching private
coordinator. The daemon rejects a request whose SpaceId disagrees with its log.

## Cloudflare shape

A Cloudflare Worker is an edge client, not the durable writer. The supported
shape is:

```text
client -- HTTPS/JSON --> Worker or authenticated shim
       -- private FRAMRPC --> active Fram coordinator
       -- append --> history.framlog
```

The JSON boundary uses tagged recursive Terms and a closed operation mapping.
It does not forward EDN or arbitrary daemon records. Exact setup and probes are
in [`../deploy/cloudflare/PROCEDURE.md`](../deploy/cloudflare/PROCEDURE.md).

## Durable state

`history.framlog` is a binary FRAMLOG v1 file, not a line-oriented text log.
Back it up as an append-only durable artifact and preserve its SpaceId. Use the
engine's scan, query, occurrence, and validation surfaces to inspect semantic
content; do not scrape binary bytes with text tools.

The one-shot migration command converts a legacy flat log to the recursive
format. Run it against an explicitly chosen source and destination while the
source is quiescent. The native daemon refuses the removed flat-serving mode.

## Runtime processes

- `bin/fram-daemon` is the long-lived JVM active or standby coordinator.
- `bin/fram` is the local CLI and FRAMRPC client.
- `bin/fram-mcp` is a five-tool JSON-RPC-over-stdio data edge.
- the Cloudflare shim/Worker is an optional authenticated JSON edge.

Compiled Clojure is committed under `out/`. Beagle is required to regenerate
source projections, not to start a released daemon. A second daemon
implementation in Zig serves the same semantic and FRAMRPC contracts and is held
at that closed thirteen-operation boundary as a compatibility, rollback, and
differential-oracle implementation — not as a scheduled replacement of the JVM
coordinator. Its source ratchet runs in CI; its bootstrap and oracle suites are
toolchain-owned aggregates that `ci.yml` does not itself execute.

## Deployment handoff

Source-head FRAMRPC does not expose deployment control. The currently deployed
v0.3 generations use the operator-owned
[`coordinator-cutover.md`](coordinator-cutover.md) protocol for blue/green
writer transfer. That document is live for v0.3 until cluster migration; its
flat-store terminology is not part of the recursive kernel model.

Keep runtime deployment worktrees pristine. A deployment marker belongs in the
controller's state, never inside the source worktree the runtime validates.
