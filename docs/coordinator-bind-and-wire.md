# Coordinator bind and wire contract

**Status:** Current source-head native coordinator boundary.

## Bind

`bin/fram-daemon` listens on `127.0.0.1` by default. Set `FRAM_BIND` only when
the surrounding network policy is intentional. `FRAM_PORT` selects the client
port; `FRAM_CONNECT` may select a different client host.

The coordinator owns one immutable `SpaceId` and one FRAMLOG. A new log requires
`FRAM_SPACE_ID`; every request also carries a SpaceId, and a mismatch is
rejected.

The listener is plaintext. Keep it on loopback or a private network and put an
authenticated TLS gateway or sidecar in front. There is no engine-level user
authentication in this boundary.

## FRAMRPC v1

The live wire is binary FRAMRPC v1. Each bounded frame carries protocol magic,
version, request identity, SpaceId, one operation tag, typed controls, and a
closed payload record. Terms use the shared recursive tagged codec:

```text
String | Int | Float | Bool | Keyword | Instant | Triple(Term, Term, Term)
```

Unknown operation tags, record tags, fields, term tags, trailing bytes, and
over-limit nesting are rejected. The protocol has request/body, term-node, and
term-depth limits. It is not an EDN line protocol.

## Closed operation set

The native daemon accepts exactly thirteen operations:

- metadata: `rpc/version`, `rpc/status`, `rpc/validate`;
- mutations: `rpc/assert`, `rpc/retract`, `rpc/batch`;
- reads: `rpc/scan`, `rpc/query`, `rpc/occurrences`;
- fencing: `rpc/lease-acquire`, `rpc/lease-renew`, `rpc/lease-release`,
  `rpc/lease-check`.

`rpc/query`, `rpc/scan`, and `rpc/occurrences` accept a page cursor; only
`rpc/query` accepts the timeout control. Mutations may carry an
expected logical version. Read responses report the logical version they
served.

There is no native `rpc/pull`, import/export, graph-edit, deployment, or cutover
operation. Import/export and legacy projections are local utilities. Graph and
deployment controls have separate authority boundaries.

## Listener handoff

`FRAM_LISTEN_FD` allows an operator-owned launcher to pass an inherited listener
to the JVM coordinator. It does not change the FRAMRPC codec or add public
operations. Writer authority remains a separate per-log lock.

A pinned v0.3 deployment still runs a blue/green controller with its own
versioned private protocol; the current host does not — it selects a generation
with systemd socket activation and a generation symlink. That controller's
operational contract is
[`coordinator-cutover.md`](coordinator-cutover.md), retained for the pinned v0.3
runtime only; it must not be inferred from FRAMRPC v1.

## Probes

- [`../tests/fram_rpc_v1_test.clj`](../tests/fram_rpc_v1_test.clj) verifies
  record and recursive Term encoding.
- [`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj)
  exercises the real listener.
- [`../tests/native_rpc_boundary_ratchet_test.clj`](../tests/native_rpc_boundary_ratchet_test.clj)
  prevents operation drift and legacy parsers from returning.
- [`../tests/coord_writer_authority_test.clj`](../tests/coord_writer_authority_test.clj)
  verifies active/standby write authority.
