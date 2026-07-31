# Isolation and deployment

One coordinator process owns the writes; clients connect over a socket. The same
design runs on your laptop, on a server you own, or in a service you host —
**one coordinator plus one log per account**. Only the transport differs.

## Be honest about what isolates what

Fram has **no access control**. Isolation is **process, log, and network** only:

- the coordinator binds loopback (`127.0.0.1`) by default;
- remote or multi-tenant hosting puts an authenticated gateway in front —
  bearer token → tenant → that tenant's coordinator.

The rule is **one graph per trust domain**. A personal life-graph, a client's
data, and public code tooling are *separate logs in separate processes*, never
one. Share machinery across domains freely; never share data.

## Hosting from an edge platform (Cloudflare Workers)

Workers are ephemeral and hold no state, so a Worker cannot be the writer. Put
the coordinator in a Docker container as the durable single writer, and a small
bearer-token HTTP shim in front of it to bridge the Worker's `fetch()` to the
coordinator's TCP protocol. Workers cannot present a client certificate on a raw
socket, so engine-terminated mTLS is not reachable directly from a Worker today.

Exact procedure, Dockerfiles, and an observed local smoke test:
[`../deploy/cloudflare/PROCEDURE.md`](../deploy/cloudflare/PROCEDURE.md).

## What your data is

Two plain-text things you can `grep`: your Markdown, and an append-only
`coordination.log`. No proprietary format, no telemetry, no lock-in.

The log is the recoverable history. Each line records *who* and *when*;
`fram history <id>` replays an entity's timeline in `tx` order.

## Runtime shape

Nothing to build — compiled Clojure is committed under `out/`.

- The **CLI and MCP server run on [babashka](https://babashka.org)**, for fast
  startup.
- The long-lived **coordinator runs on the JVM**: real threads, and
  `SSLServerSocket` for engine-terminated **mTLS**.
- An optional GraalVM native binary (`native/build.sh`) targets ~0.2 s per
  command.
