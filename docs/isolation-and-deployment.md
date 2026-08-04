# Isolation, wire, and deployment

This document specifies the source-head trust domain, coordinator bind and FRAMRPC boundary, supported deployment shape, and v0.3 handoff.

## Trust domain and bind

Fram has no engine accounts, authorization, or tenant policy. One [SpaceId](glossary.md#storage-and-query), one FRAMLOG, one writer process/lock, and one private network boundary form a trust domain. Separate personal, client, and public-tooling data across all four; ontology fields are not tenant isolation.

`bin/fram-daemon` launches native by default and fails closed unless `FRAM_NATIVE_ARTIFACT_DIR` names a READY artifact containing `bin/fram-daemon-native`. `FRAM_DAEMON_RUNTIME=jvm-oracle` selects the sealed packaged JVM differential oracle; `jvm-dev` selects the checkout-only Clojure development route. Neither is an automatic fallback. The coordinator binds `127.0.0.1` by default. `FRAM_BIND` changes the listener intentionally, `FRAM_PORT` selects its port, and `FRAM_CONNECT` selects the client host. New logs require `FRAM_SPACE_ID`; every request carries the same identity or is rejected. `FRAM_LISTEN_FD` may pass an operator-owned INET listener without changing codec, operations, or writer authority.

The listener is plaintext. Remote deployments keep it private and terminate TLS, authentication, tenant routing, request limits, and public audit policy at a gateway or sidecar.

## FRAMRPC v1

FRAMRPC v1 is a bounded binary protocol. Each frame carries magic, version, request identity, SpaceId, one operation tag, typed controls, and a closed payload. Terms use the recursive tagged codec linked from the [glossary](glossary.md#semantic-kernel); triples are positional tagged arrays, so `t1`/`t2`/`t3` never appear on the wire.

Unknown operation, record, field, and Term tags, trailing bytes, or over-limit nesting are rejected. FRAMRPC is not EDN, JSON, HTTP, or MCP.

The native daemon accepts exactly thirteen operations:

- metadata: `rpc/version`, `rpc/status`, `rpc/validate`;
- mutation: `rpc/assert`, `rpc/retract`, `rpc/batch`;
- read: `rpc/scan`, `rpc/query`, `rpc/occurrences`;
- fencing: `rpc/lease-acquire`, `rpc/lease-renew`, `rpc/lease-release`, `rpc/lease-check`.

Query, scan, and occurrences accept page cursors; only query accepts a timeout. Mutations may carry expected logical version, reads report served version, and status reports ordered-result-cache counters. There is no native pull, import/export, graph-edit, deployment, or cutover operation; those local or sealed controls do not enlarge FRAMRPC.

The official zero-dependency [`clients/node/framrpc.mjs`](../clients/node/framrpc.mjs) client connects directly and exposes all thirteen operations with recursive Terms, batches, versions, snapshots, paging, replay, and leases.

## Edge and process shape

```text
client -- HTTPS/closed JSON --> authenticated Worker or shim
       -- private FRAMRPC --> active coordinator
       -- append --> history.framlog
```

The edge selects one SpaceId and maps tagged JSON to closed FRAMRPC records; it never forwards EDN or arbitrary daemon records. Cloudflare setup and probes live in [`../deploy/cloudflare/PROCEDURE.md`](../deploy/cloudflare/PROCEDURE.md).

- `bin/fram-daemon` is the native-first launcher for the long-lived active or standby coordinator.
- `bin/fram` is the local CLI and FRAMRPC client.
- `bin/fram-mcp` is the five-tool JSON-RPC-over-stdio edge.
- The Cloudflare shim/Worker is an optional authenticated JSON edge.

The launcher consumes only a linked executable promoted behind the native artifact's READY marker; raw Beagle projection output is not executable runtime input. Compiled Clojure under `out/` remains available only through the explicit JVM routes. The frozen Zig implementation remains a compatibility, rollback, and differential oracle held to the same semantic and thirteen-operation wire contract.

## Durable state and handoff

Back up `history.framlog` as an append-only binary artifact with its SpaceId. Inspect it through scan, query, occurrences, and validate, never text scraping. Legacy flat logs enter only through the one-shot migration against explicit quiescent source and destination paths.

Source head exposes no deployment control. Pinned v0.3 clusters use the live [coordinator cutover](coordinator-cutover.md) contract until migration; its flat-store and EDN control vocabulary is version-scoped, not kernel vocabulary. The current host instead uses systemd socket activation and a generation symlink.

Deployment worktrees stay pristine. Controller markers live in controller state, never the source tree being validated.

## Growth and retention — what the application owns

The log never deletes: every assertion and retraction is appended forever, and
that history is the product. Until compaction ships (tracked in
[guarantees](guarantees.md#profiles) as not yet gated), growth control belongs
to the application, on three measured facts:

- One operation costs ~26 bytes of framing plus **every atom's bytes verbatim,
  every time it appears** — the log carries no string dictionary. A fact whose
  subject is a 100-character identifier pays those 100 bytes on each of its
  occurrences; measured: 1,000 assertions under one 10-character subject are
  36 KB, under one 100-character subject 126 KB.
- Re-asserting a corpus appends all of it again, exactly linearly: a 15k-fact
  corpus measured 1.2 MB per generation and 12.2 MB after ten identical
  rebuilds. A rebuild-from-source lifecycle multiplies the log by the number
  of rebuilds and records rebuilds, not revisions.
- A reference saves bytes only when its token is shorter than what it
  replaces; pointing many facts at a long string id costs more than inlining.

The patterns that keep growth proportional to change:

1. **Supersede, don't rebuild.** Incremental updates from a change cursor keep
   history meaningful and the log linear in edits, not in rebuilds.
2. **One SpaceId per rebuild** when a full rebuild is genuinely needed: a new
   space is a new log file, and retiring the old one is ordinary file
   management.
3. **Short identity tokens.** Minted transaction coordinates (`txn/mint!`) are
   compact Terms; long human-readable identifiers belong in one asserted name
   fact, not in every subject position.
4. **Seal epochs** for ranges you must keep but will not touch.

## Probes

- [`../tests/fram_rpc_v1_test.clj`](../tests/fram_rpc_v1_test.clj): recursive Term records and codec.
- [`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj) and [`../tests/node_framrpc_client_test.mjs`](../tests/node_framrpc_client_test.mjs): real listener and official client.
- [`../tests/native_rpc_boundary_ratchet_test.clj`](../tests/native_rpc_boundary_ratchet_test.clj): closed operation boundary.
- [`../tests/coord_writer_authority_test.clj`](../tests/coord_writer_authority_test.clj): active/standby authority.
