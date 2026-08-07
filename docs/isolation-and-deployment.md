# Isolation, wire, and deployment

This document specifies the source-head database trust domain, the server bind and FRAMRPC boundary, the three supported deployment shapes, and the contract an embedder faces.

## Trust domain and bind

Fram has no engine accounts, authorization, or tenant policy. One [SpaceId](glossary.md#storage-and-query), one FRAMLOG, one writer, and one private network boundary form a trust domain. Separate personal, client, and public-tooling data across all four; ontology fields are not tenant isolation. What makes the writer sole is regime-specific and is stated under [deployment shapes](#deployment-shapes).

`bin/fram-server` launches native by default and fails closed unless `FRAM_NATIVE_ARTIFACT_DIR` names a READY artifact containing `bin/fram-server-native`. `FRAM_SERVER_RUNTIME=graal` selects the transitional self-contained server at the absolute `FRAM_GRAAL_ARTIFACT` path without presenting it as a native program artifact. `jvm-oracle` selects the sealed packaged JVM differential oracle; `jvm-dev` selects the checkout-only Clojure development route. None is an automatic fallback. The server binds `127.0.0.1` by default. `FRAM_BIND` changes the listener intentionally, `FRAM_SERVER_PORT` selects its port, and `FRAM_SERVER_CONNECT` selects the client host. New databases require `FRAM_SPACE_ID`; every request carries the same identity or is rejected. `FRAM_LISTEN_FD` may pass an operator-owned INET listener without changing codec, operations, or writer authority.

The Cloudflare server image carries one statically linked musl native artifact
on `scratch`, packaged from its READY receipt rather than compiled in the image;
the authenticated HTTP shim remains a separate Babashka container.

The listener is plaintext. Remote deployments keep it private and terminate TLS, authentication, tenant routing, request limits, and public audit policy at a gateway or sidecar.

## FRAMRPC v1

FRAMRPC v1 is a bounded binary protocol. Each frame carries magic, version, request identity, SpaceId, one operation tag, typed controls, and a closed payload. Terms use the recursive tagged codec linked from the [glossary](glossary.md#semantic-kernel); triples are positional tagged arrays, so `t1`/`t2`/`t3` never appear on the wire.

Unknown operation, record, field, and Term tags, trailing bytes, or over-limit nesting are rejected. FRAMRPC is not EDN, JSON, HTTP, or MCP.

The data surface is exactly thirteen operations:

- metadata: `rpc/version`, `rpc/status`, `rpc/validate`;
- mutation: `rpc/assert`, `rpc/retract`, `rpc/batch`;
- read: `rpc/scan`, `rpc/query`, `rpc/occurrences`;
- fencing: `rpc/lease-acquire`, `rpc/lease-renew`, `rpc/lease-release`, `rpc/lease-check`.

Query, scan, and occurrences accept page cursors; only query accepts a timeout. Mutations may carry expected logical version, reads report served version, and status reports ordered-result-cache counters. There is no FRAMRPC pull, import/export, graph-edit, deployment, or cutover operation; those local or sealed controls do not enlarge FRAMRPC.

The native engine answers one operation beyond that data surface: `rpc/checkpoint`
takes `:rpc/unit`, refuses a page cursor, writes the
[snapshot image](glossary.md#storage-and-query) to the second storage object,
appends nothing to the FRAMLOG, changes no store state, and answers sequence,
watermark, stamp, fingerprint, and image byte count. It is an operator and
embedder control, not application traffic: the JVM oracle route, the Node
client, the shim, and `bin/fram` all stay at the thirteen data operations.
[`../tests/fram_snapshot_boot_test.sh`](../tests/fram_snapshot_boot_test.sh)
gates the operation and the boot route it feeds.

The official zero-dependency [`clients/node/framrpc.mjs`](../clients/node/framrpc.mjs) client connects directly and exposes all thirteen operations with recursive Terms, batches, versions, snapshots, paging, replay, and leases.

## Deployment shapes

Three shapes serve the same engine. They differ in who owns the process, who
owns the log bytes, and what makes the writer sole.

| Shape | Engine | Log bytes | Exclusivity |
|---|---|---|---|
| Host-managed server | `bin/fram-server` running `bin/fram-server-native` | POSIX file, `history.framlog` | `flock(LOCK_EX\|LOCK_NB)` on the log descriptor, taken at boot |
| Container | the same static musl artifact on `scratch` | container volume, `/data/history.framlog` | the same flock, inside one container |
| Embedded in an isolate | `lib/libfram.wasm`, linked `--host wasm-embed` | whatever the embedder's storage hooks write | the embedder's: a Durable Object id, a lease, or another single-instance grant |

The first two are one process holding a socket and speaking FRAMRPC. The third
has no socket and no process of its own: the embedder instantiates the module
and calls `fram_transact`, `fram_query`, or `fram_snapshot` with one canonical
FRAMRPC v1 request frame in linear memory, receiving one canonical response
frame. Framing, codec, operations, and refusals are the same in all three, and
the wasm engine answers byte-for-byte what the native embed library answers on
the same frames — including the FRAMLOG bytes both write
([`../tests/fram_wasm_embed_smoke.sh`](../tests/fram_wasm_embed_smoke.sh)).

Exclusivity per regime:

- **POSIX storage** (both server shapes): the engine takes the lock itself. A
  second server on the same log fails closed with `canonical FRAMLOG writer
  authority is unavailable`.
- **Host storage table** (`fram_open` with a host, native or wasm): a successful
  open transfers storage-*close* responsibility to Fram, but exclusivity stays
  the embedder's obligation. A wasi build cannot take the lock at all —
  wasi-libc declares `flock` and links no implementation — so the guard stands
  down there rather than pretending.
- **Durable Object**: the platform runs one instance per object id, so the id
  is the exclusivity. One id, one database, one writer; a second concurrent
  writer for that SpaceId is a deployment error the engine cannot detect.

The engine's `expected-version` OCC check and lease fencing are unchanged in
every regime; neither substitutes for sole-writer exclusivity.

## Edge and process shape

```text
client -- HTTPS/closed JSON --> authenticated Worker or shim
       -- private FRAMRPC --> active Fram server
       -- append --> database (SpaceId + FRAMLOG)
```

The edge selects one SpaceId and maps tagged JSON to closed FRAMRPC records; it never forwards EDN or arbitrary server records. Cloudflare setup and probes live in [`../deploy/cloudflare/PROCEDURE.md`](../deploy/cloudflare/PROCEDURE.md).

- `bin/fram-server` is the native-first launcher for the single active server. Native production exposes no standby-serving mode. It rebuilds state at boot by installing a valid snapshot image and replaying the tail past its watermark, or by folding the whole FRAMLOG when there is no usable image.
- `bin/fram` is the local CLI and FRAMRPC client.
- `bin/fram-mcp` is the five-tool JSON-RPC-over-stdio edge.
- The Cloudflare shim/Worker is an optional authenticated JSON edge.

The native route consumes only a linked executable promoted behind the native artifact's READY marker. The Graal route consumes the absolute executable named by `FRAM_GRAAL_ARTIFACT`; it is not a native program artifact, and it is no longer a deployment route — the container image is built from the native artifact and Graal survives only as the differential oracle for native selfcheck. Raw Beagle projection output is not executable runtime input. Compiled Clojure under `out/` remains available only through the explicit JVM routes. QBE remains the direct-native cross-check.

## The wasm embed contract

`bin/fram-native-build --host wasm-embed --abi wasm32` links the engine into a
wasm32 reactor, `lib/libfram.wasm`. The module is the whole contract: no
socket, no filesystem, no ambient capability. Everything it needs, an embedder
supplies as named imports.

[`../native/wasm-embed.seams`](../native/wasm-embed.seams) is the authority. It
pins every import with its resolved signature and every export of the linked
module; the build compares each link against it and dies on any difference, and
`--regen-wasm-seams` is the only way to move it. A new line there is a new
capability demand on every embedder and must be argued in the comments the
regeneration preserves.

**Host hooks.** Module `fram_host_v1` carries nine imports, one per
`fram_host_v1` field in [`../native/fram.h`](../native/fram.h), each named for
its field and typed by the wasm32 lowering of its prototype:

```text
allocate  deallocate                     memory for response buffers
clock_milliseconds                       the engine's wall clock
storage_size  storage_read  storage_truncate
storage_append  storage_sync  storage_close
```

Two storage objects ride those same storage hooks under a storage-context
argument: context `0` is the FRAMLOG, context `1` is the snapshot image. An
embedder that offers no image simply never sees context `1`, and the engine
folds the whole log instead of installing one. An import reports failure by
returning nonzero; a *trapping* import unwinds the guest uncleaned, so a trap
is instance-fatal.

**WASI.** Seven `wasi_snapshot_preview1` imports are linked; two are live.
`clock_time_get` backs the engine's monotonic query clock, and
`environ_sizes_get` backs its `getenv` — wasi-libc calls `_Exit(71)` if the
sizes call is refused, so answer it with an empty environment. The remaining
five (`environ_get`, `fd_write`, `fd_close`, `fd_seek`, `proc_exit`) are
wasi-libc stdio and abort residue. The smoke test measures this rather than
asserting it: over the full frame matrix those five record zero calls.

**Exports.** Nine ABI functions plus `_initialize` and `memory`:
`fram_abi_version`, `fram_open`, `fram_transact`, `fram_query`,
`fram_snapshot`, `fram_close`, `fram_buffer_release`, `fram_wasm_alloc`,
`fram_wasm_free`. `fram_wasm_alloc`/`fram_wasm_free` stage embedder-owned
request frames, options, and error structs; they never free a response, which
is released only through `fram_buffer_release`. Identity is the instance: one
instance binds one database, and host contexts are the storage-object numbers
above.

**Budget.** `fram_open_options_v1.memory_budget_bytes` derives arena growth,
compaction increment, and generation count from one number; zero leaves every
engine limit at its default, and a boot that names a budget reports the limits
it derived. The budget tunes compaction cadence — it does not soften
exhaustion. Running out of arena is a trap, never a typed response: the embed
boundary registers a trap reporter that writes one
`fram: engine trap code=… status=…` line to file descriptor 2 before the abort
lands, naming `FRAM_OUT_OF_MEMORY` for arena exhaustion and `FRAM_HOST_ERROR`
for host I/O. It is a report, not a recovery — the instance is gone, and an
embedder that answers no `fd_write` sees only the trap. Size the store to the
budget;
the measured shapes are in [`../RELEASE-v0.5.0.md`](../RELEASE-v0.5.0.md).

## Durable state and handoff

Back up `history.framlog` as an append-only binary artifact with its SpaceId. Inspect it through scan, query, occurrences, and validate, never text scraping. Legacy flat logs enter only through the one-shot migration against explicit quiescent source and destination paths.

Source head exposes no deployment-control operation. Runtime publication uses
systemd socket activation and a generation symlink outside the data protocol.

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
  36 KB, under one 100-character subject 126 KB. A generation created with
  `{:deflate? true}` compresses each frame (measured 17.8× on a repeated-long-id
  corpus, write and fold time unchanged); the flag is per-generation and set at
  `create-triple-log!`.
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
- [`../tests/native_rpc_server_test.clj`](../tests/native_rpc_server_test.clj) and [`../tests/node_framrpc_client_test.mjs`](../tests/node_framrpc_client_test.mjs): real listener and official client.
- [`../tests/native_rpc_boundary_ratchet_test.clj`](../tests/native_rpc_boundary_ratchet_test.clj): closed operation boundary.
- [`../tests/writer_authority_test.clj`](../tests/writer_authority_test.clj): writer-authority and JVM-oracle compatibility behavior.
- [`../tests/fram_wasm_embed_smoke.sh`](../tests/fram_wasm_embed_smoke.sh): the wasm host-import regime end to end — pinned seams, native/wasm response and FRAMLOG byte identity, the snapshot image, the unpaged codec bound, and the WASI call tally.
- [`../tests/fram_snapshot_boot_test.sh`](../tests/fram_snapshot_boot_test.sh): `rpc/checkpoint`, snapshot boot, and the degrade-to-fold path for a damaged image.
