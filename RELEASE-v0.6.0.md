# v0.6.0

Fram v0.6.0 turns the native, Bun, and Cloudflare paths into independently
reproducible release surfaces and adds the application-level write and query
contracts needed to build on them. The kernel remains the same neutral store
of recursive Triples; schema constraints, authorization, and application
identity remain layers above it.

The annotated `v0.6.0` tag resolves to source commit
`4bc7689dd0ab3dee29a822db2a66cc6752c1172d`. Its release workflow completed
both the tag/smoke/reproducibility gate and the publish job successfully in
GitHub Actions run `31653551241`.

## Highlights

### Bun-first official client

`@tompassarelli/framrpc` 0.4.0 is the official checkout client. It targets Bun
1.3.13 or newer, has no runtime dependencies, and exposes the same closed
thirteen-operation FRAMRPC v1 surface through two entry points:

- the package root provides the owned TCP transport;
- `@tompassarelli/framrpc/core` accepts an injected exact-frame transport.

Both entry points ship generated TypeScript declarations. The portable core is
also the transport boundary shared by the Cloudflare adapter, so the two hosts
do not maintain separate encodings of FRAMRPC.

### Guarded application writes

The new `@tompassarelli/framrpc/schema` entry point adds application-level
mutation primitives for:

- single-value replacement;
- unique create and upsert;
- guarded single-subject and multi-subject updates;
- combined create/update transactions.

Each attempt reads one pinned snapshot, preflights the exact batch, and commits
with `expected-version` optimistic concurrency control. Mutation batches are
limited to 247 actions. The client refuses before commit when the exact
predicted receipt would exceed the wire envelope.

These guards are not engine access control. A caller using raw FRAMRPC writes
can still bypass uniqueness, cardinality, and reference constraints; hosts must
keep the raw engine boundary private when those guarantees matter.

### Deterministic ordered queries

Structured queries now accept stable `orderBy` / `:order-by` and `limit` /
`:limit`. Ordering uses natural Term order, preserves exact signed 64-bit
integer behavior, and adds a canonical full-row tie-breaker so equal sort keys
remain deterministic. Limits are global top-K limits, not per-partition or
per-page truncation.

Text-query planning now builds index rows only for attributes that are actually
bound by the query. Ordered plans remain native through execution instead of
falling back to host-side materialization.

### Native backup and restore

`bin/fram-backup` creates and verifies a checkpoint-bound backup of the
authoritative FRAMLOG prefix. The backup records the SpaceId, served version,
and exact native artifact receipt. Restore refuses non-fresh storage and any
artifact identity that differs from the recorded release.

The native build cache is keyed by compiler content and shared safely across
worktrees. Build launch snapshots the complete source closure so later worktree
edits cannot drift into an in-flight artifact. Native and Wasm release bundles
also carry their required compiler, provenance, license, and third-party notice
closure.

### Cloudflare Durable Object release path

`@tompassarelli/fram-cloudflare-do` 0.2.0 provides the typed Durable Object
adapter and seam declarations over the shared portable FRAMRPC core. It adds
exact FRAMLOG export and restore, transactional publication, and fail-closed
recovery when process replacement interrupts an image restore.

The matching Wasm release envelope is deterministic and provenance-bound. A
wiki-shaped 128 MiB capacity profile is launch-blocking, including native query
semantics and durable process-replacement certification. This release proves
the adapter, Wasm, and capacity envelope; it does not make a raw Durable Object
namespace or backup/restore interface an application-facing API.

### Mutation atomicity and native operation

Writes, batches, and successful lease mutations now encode their predicted
success response before appending. If response depth or frame size would make
that response invalid, the operation refuses without changing state or served
version.

Native storage work reduces copying in mutable term-store columns, avoids
materializing rotation bucket keys and slot rows, isolates fold-open forks, and
returns the idle memory floor to the operating system rather than only to the
allocator.

## Compatibility and migration

- Upgrade v0.6 clients and servers together. FRAMRPC remains the closed
  thirteen-operation v1 surface, with checkpoint retained as a separately
  named native operator capability, but structured-query encoding expanded for
  ordering and limits.
- The official checkout client moved from `clients/node` (Node 20+) to
  `clients/bun` (Bun 1.3.13+). Consume the released
  `@tompassarelli/framrpc` 0.4.0 archive or inject an owned transport through
  its `core` entry point.
- Nontrivial reads should paginate. The unpaged response ceiling remains 248
  rows.
- The Cloudflare adapter requires Bun 1.3.13 for its package toolchain, a
  Worker compatibility date of `2026-03-15` or newer, a named Durable Object,
  and a SpaceId no longer than 1,024 UTF-8 bytes.
- Keep the raw namespace and export/restore operations behind a separately
  authorized backend or operator boundary. Applications should receive only
  the exchange service binding they need.
- Fram remains pre-1.0 and the engine still has no built-in access control.
  Isolate native sockets by process, network, SpaceId, and FRAMLOG.

## Reproducible release assets

Every archive has one sibling receipt that records its archive digest and
source identity. The published release contains exactly these eight assets:

1. `tompassarelli-framrpc-0.4.0.tgz` — Bun FRAMRPC client, portable core,
   schema client, declarations, backup module, and licenses.
2. `tompassarelli-framrpc-0.4.0.receipt.txt` —
   `fram-bun-release-receipt/v2` provenance and archive digest.
3. `tompassarelli-fram-cloudflare-do-0.2.0.tgz` — typed Durable Object adapter
   and seam package.
4. `tompassarelli-fram-cloudflare-do-0.2.0.receipt.txt` —
   `fram-cloudflare-do-release-receipt/v1` provenance and archive digest.
5. `fram-v0.6.0-wasm32-wasm-embed.tar.gz` — deterministic Wasm engine, seam
   ledger, native provenance, licenses, and third-party notices.
6. `fram-v0.6.0-wasm32-wasm-embed.receipt.txt` —
   `fram-cloudflare-wasm-release-receipt/v2` Wasm, seam, toolchain,
   provenance, and archive digests.
7. `fram-v0.6.0-x86_64-linux-musl.tar.gz` — static native server executable
   and licenses.
8. `fram-v0.6.0-x86_64-linux-musl.receipt.txt` —
   `fram-native-release-receipt/v2` executable and provenance digests.

Verify each archive against the digest in its receipt before consuming it.

## Release evidence

- The annotated tag object is
  `8ebc1c26d57afd188ec8ddca245ec6518cc6d803`; it resolves to exact source
  commit `4bc7689dd0ab3dee29a822db2a66cc6752c1172d`.
- GitHub Actions run `31653551241` completed the tag, smoke, and reproducible
  receipt gate, then published the tagged release.
- The release contains exactly four archives and their four corresponding
  receipts; no unreceipted asset is present.
- Native, Bun, Cloudflare adapter, and Wasm packagers normalize their archives
  and bind the result to source, tag, package or target identity, and required
  compiler/toolchain provenance.
- The Cloudflare capacity gate is bound to the deployed bundle and includes
  durable process replacement rather than treating a one-process smoke as
  sufficient evidence.

## Known limitations

- Schema constraints remain application-layer guarantees and are bypassable
  through direct raw writes.
- The Cloudflare capacity result is one certified profile; it does not assert that
  every Worker plan, store shape, or workload fits the same envelope.
- Backup and restore are operator capabilities. Exposing them to ordinary
  application traffic would widen authority beyond the released application
  seam.
- Pre-1.0 compatibility remains explicit and release-bound; do not infer
  mixed-version query compatibility from the unchanged FRAMRPC v1 name.

Full changelog: `v0.5.3...v0.6.0`.
