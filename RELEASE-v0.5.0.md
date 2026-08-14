# v0.5.0

Fram v0.5.0 completes the native cutover. The engine is compiled through
Beagle's release gate, published as one embedding ABI, and served from three
deployment shapes — a host process, a container, and a wasm module an isolate
embeds with no server at all. The wasm build answers byte-for-byte what the
native build answers, on the same frames and into the same FRAMLOG bytes.

It also fixes the two defects that made the previous engine unsafe to embed:
the commit wall that killed an instance around the 236th commit, and a since
window that quietly answered outside itself. A snapshot image lets a restart
resume from a watermark instead of folding from byte zero.

This is a minor release because the deployment routes and the embedding surface
change intentionally, and because a since-windowed query now returns different —
correct — rows.

## Highlights

### Native compilation through Beagle's release gate

- `bin/fram-native-build` takes `--host server | embed | wasm-embed | program`
  and a target `--abi`. The ABI profile joins the cache manifest, so two
  profiles over one source closure can never share a cache entry, and each
  profile takes its own QBE frontier scope. The gate requires C17 and ratchets
  QBE refusals rather than degrading silently.
- The generated-module contract is at ABI 3; the adapter verifies it before
  the host runs.
- Beagle's build-stage vocabulary crosses the seam as runtime string
  contracts: `stage source-freeze ACCEPTED`, and the frontier refusal
  `native program is not frozen`. The retired Worlds subsystem is deleted from
  the tree, and the authoring checker speaks program and module-overlay. Both
  verdicts are recorded in the [naming ledger](docs/naming.md).

### Three deployment shapes over one engine

- **Host process** — `bin/fram-server` running the native artifact, taking a
  `flock` on the FRAMLOG.
- **Container** — the Cloudflare route is now the static musl native artifact
  on `scratch`, packaged from a completed content-addressed artifact and run
  unprivileged as uid/gid 65534. The Graal image recipe is deleted; Graal
  survives only as the differential oracle for native selfcheck, not as a
  deployment route.
- **Embedded in an isolate** — `--host wasm-embed --abi wasm32` links the same
  ABI into `lib/libfram.wasm`, a reactor with nine exports whose host table is
  built from named imports. No socket, no filesystem, no ambient capability.

The shapes and the host contract are specified in
[isolation and deployment](docs/isolation-and-deployment.md#deployment-shapes).

### Byte-identical engines

`native/wasm-embed.seams` pins every import with its resolved signature and
every export of the linked module; a build that differs dies with the diff, and
`--regen-wasm-seams` is the only way to move it. Nine `fram_host_v1` imports
carry allocation, a clock, and storage, with two storage objects riding the
same hooks under a context argument. Seven WASI imports are linked and two are
live — the monotonic clock and `environ_sizes_get`; the other five record zero
calls over the whole frame matrix, measured rather than asserted.

`tests/fram_wasm_embed_smoke.sh` runs the frame matrix through an external
Python/wasmtime embedder and through the native embed library, and requires
identical response transcripts and identical FRAMLOG bytes.

### The commit wall is fixed

FRAMLOG replay recursed once per frame in tail position. The C17 emitter lowers
that to a real self-call, and 256 bytes of wasm stack per frame overflowed the
64 KiB stack into the allocator's statics at about 238 frames — the 236-commit
open wall. Replay is now a loop, costing one frame regardless of log length.
Two changes harden the same area: `--stack-first` places the stack below every
static so an overflow traps at a memory boundary instead of corrupting
allocator state, and the embed boundary registers a trap reporter that names a
`fram_status` before the abort lands.

Measured after the fix, on one wasm instance with no memory cap: 620
single-triple commits from empty, all accepted. The store that killed the
pre-fix engine — 240 batched commits, which could not be opened at all in 480
seconds — opens in 533 ms.

### The since window is fixed

`requested-query-bounds!` accepted a since lower bound for every plan, but only
the occurrence candidate source ever received it. A `triple` query under
`since(L,U]` therefore answered the whole relation, and so did every text
relation. Both candidate sources now filter by the same lower bound, so the
three plans read the same window. A windowed query that previously matched its
unwindowed twin byte for byte now answers the window; if you built anything on
the old behavior, it was reading outside its bounds.

### Snapshot v1

`rpc/checkpoint` encodes the served store as an image beside the FRAMLOG: a
header, a flat stream of length-prefixed CRC-checked row records in position
order, and a trailer carrying sequence, watermark, log set, offsets, stamp, and
fingerprint. Position order is fold order, so re-loading the image assigns
handle for handle what a full fold assigns.

A boot with a valid image installs it and replays only the tail past its
watermark. An image that fails to install, or whose tail does not continue,
degrades to the full fold and reports it — the boot never fails on account of
an image, and a checkpoint appends nothing to the log.

### Codec bounds derived, not guessed

Both list bounds now come from the codec's 256-deep Term budget: a request list
is capped at 250 values and an unpaged reply at 248 rows, each refusing typed
`:term-depth-exceeded` up front instead of letting the encoder discover the
cliff downstream.

## The measured limits table

Certified against `9f00313` with the Beagle pin the flake declares. Toolchain:
wasi clang 21.1.8, wasm-tools 1.249.0, python-wasmtime 45.0.0. Wasm timings
ride wasmtime host calls from Python; in-isolate numbers are lower.

**Read this section's provenance before quoting it.** The budget and limits
matrix below was certified on the pre-fix engine, before the store
materialization fix (`5721164`, `3df8efe`) landed. Boot cost *was* re-measured
after that fix, and the **Boot** table carries the new numbers. Every other row
here still describes the pre-fix engine, which makes each of them conservative:
the shipped engine boots faster and holds less memory at the same store size,
so the store sizes named below are floors rather than ceilings. Re-certifying
the full matrix on the fixed engine is queued and not done.

**Write lifecycle** — one wasm instance, single-triple commits from empty,
uncapped:

| Measure | Value |
|---|---|
| Commits accepted | 620 of 620 |
| Per-commit latency | p50 0.70 ms, p90 1.01 ms, p99 1.59 ms |
| Compaction crossings | survived as pauses: 37 ms at commit 257, 143 ms at commit 513 |
| Memory | 20 MiB → 150 MiB over 620 commits, stepping at compactions |
| Cold reopen of the 620-commit log | 203 ms fold, 199 ms snapshot+tail |
| Log density | 36.7 bytes per triple |

Commit count is not bounded by the engine. Capacity is bounded by memory.

**Under a hard memory cap** — wasm `StoreLimits` cap with
`memory_budget_bytes` set to the same number, page limit 16, lean 4 MiB
response arena:

| Budget | Writes | Long-lived reads |
|---|---|---|
| 96 MiB | 480 commits, then a trap on 481 | 300 triples good (2.0-2.3 ms/page steady, 62-98 ms compaction pauses, memory flat at 56 MiB); 500 fits but thrashes at the cap (~135 ms/page); 600 traps |
| 128 MiB | 512 commits, then a trap on 513 | 600 triples pass; 800 traps |
| 192 MiB | — | 1,000 triples still traps on read; `rpc/status` answers wherever the open fits (1,000 triples opens at 119-120 MiB) |

Page limit is not free: at a limit of 64 the first page cost +139 MiB at 300
triples and trapped at a 128 MiB cap. A page limit of 16 is the certified read
shape.

**Boot** — re-measured after the store materialization fix, so this table,
unlike the rest of this section, describes the engine that ships. Fold /
snapshot+tail, one store per size:

| Live triples | Native fold | Native snap | Wasm fold | Wasm snap | Wasm pages, fold | Native RSS, fold |
|---:|---|---|---|---|---:|---|
| 300 | 19.5 ms | 21.0 ms | 8.7 ms | 11.2 ms | 29 | 4.7 MB |
| 1,000 | 71.6 ms | 79.9 ms | 28.6 ms | 43.6 ms | 94 | 8.6 MB |
| 3,000 | 230.0 ms | 242.1 ms | 107.7 ms | 121.5 ms | 289 | 20.6 MB |
| 5,000 | 380.4 ms | 404.3 ms | 176.7 ms | 207.5 ms | 475 | 31.5 MB |

The fix moved the shape, not only the constant. Fitting log(y) on log(n) over
all four sizes, pre-fix against shipped:

| Quantity | Path | Before | After |
|---|---|---:|---:|
| Native time | fold | 2.05 | 1.06 |
| Native time | snap | 2.01 | 1.05 |
| Wasm time | fold | 2.04 | 1.09 |
| Wasm time | snap | 2.06 | 1.03 |
| Wasm pages | fold | 1.95 | 1.00 |
| Wasm pages | snap | 1.96 | 1.00 |
| Native RSS | fold | 1.90 | 0.68 |
| Native RSS | snap | 1.89 | 0.61 |

Both boot paths and both targets move from about n^2 to about n^1 in time, and
to n^1 or below in memory. The sub-linear memory exponents are the engine's
fixed floor — roughly 3 MB native, 322 wasm pages — still dominating at 300
triples. At 5,000 triples the shipped engine folds in 380 ms against 28.1 s,
in 31.5 MB of RSS against 3.85 GB, and in 475 wasm pages against 45,627.

Snapshot+tail still equals fold within run-to-run noise at every measured size,
because boot cost *is* store materialization: the fix made that materialization
linear, it did not take it off the boot path.

The **~6,100-triple wasm ceiling** this table quoted before was a consequence
of the quadratic page growth, and it no longer describes the engine. A new
wasm32 ceiling has not been measured, and none is claimed here.

Provenance of this before/after pair: stores fed through `fram-server-native`
(assert-only, five triples per entity, arena space, batch 200), native taken as
the median of three runs and wasm as the faster of two under wasmtime. Before is
`ae1c83e`, after is `3df8efe`, both on one machine in one sitting — that shared
harness is what makes the comparison sound. It is *not* a re-run of the
`9f00313` certification harness above, and its "before" column does not
reproduce the pre-fix boot row this document previously carried; compare the two
columns to each other, not across harnesses. Two further caveats: the wasm
millisecond columns ride Python/wasmtime host calls and so bound engine time
from above, while the page counts are exact; and the corpus is assert-only, so
these curves do not price a retraction-heavy or re-assertion-heavy boot, which
remains superlinear.

**Checkpoint and portability.** Wasm checkpoints work to 1,050 triples
(569-664 ms) and trap at 1,100 and above — a wasm-only bound in the snapshot
serializer's arena allocation. Native checkpoints complete at 1,500 and 2,000
quickly, and at 5,000 they finish (419.7 KB image) past a client's read
deadline. Images are portable in both directions: a wasm-written image installs
natively, and a native-written image boots a 1,500-triple wasm instance.

**Wire bounds.** Largest batch that round-trips: 247 actions. 248-250 commit
and then fail to encode their answer (`FRAM_ENGINE_ERROR`, `generated response
encode failed`, instance intact); 251 and above are refused typed at client
encode. Largest unpaged reply: 248 rows, refused typed beyond that, with paging
answering the same relation.

**Invariants reconfirmed.** Commit shape does not change the store: 1,000
triples written as 5, 10, or 20 commits produce identical 2,096 pages.

**Durable Object shape** — carried from the adapter lane's workerd run on a
pre-fix build, and therefore the one block here that should be re-measured
before it is quoted as current: isolate boot 151 ms, `fram_open` p50 8 ms,
per-frame p50 0-3 ms in-isolate against about 276 ms for the HTTP round trip
outside it, and 4.78 MB of linear memory for a 5.4 KB log. Guidance for a
128 MiB isolate on the fixed engine: keep a store at or under 600 triples for
reads and about 500 commits per instance, page at 16, use a lean response
arena, and set the budget equal to the cap.

## Compatibility and migration

- **The FRAMLOG is unchanged.** The header is still `FRAMLOG\0` version 1, so a
  v0.4 log opens as-is.
- **Since-windowed results change.** A `triple` or text query under
  `:query/since` now returns only its window. Callers that compensated for the
  old whole-relation answer must drop that compensation.
- **Graal is no longer a deployment route.** `FRAM_SERVER_RUNTIME=graal` still
  launches the transitional server for oracle use, but the container image is
  built from the native artifact by
  `deploy/cloudflare/build-native-image.sh`, and compose consumes that tag
  rather than building a server.
- **The container runs unprivileged.** A bind-mounted `/data` must be pre-owned
  by uid/gid 65534 on the host.
- **Embedders take a new obligation.** Under a host storage table the engine
  cannot lock anything: exclusivity, and — where the host's commit is
  asynchronous — the durability barrier are the embedder's
  ([`D6`](docs/guarantees.md#durability)). Await the store's commit before
  acknowledging a write, land one commit atomically, and never let a commit
  overtake an earlier one.
- **A dev shell exists.** v0.4 declared no development environment, so the
  wasm toolchain had to be assembled by hand. `nix develop` now supplies the
  wasi C compiler, `wasm-tools`, and a Python with wasmtime, which is what the
  wasm gates skip without.

## Release evidence

- `tests/fram_wasm_embed_smoke.sh` PASS on `9f00313`: 54 frames plus a 12-frame
  depth matrix, wasm responses and FRAMLOG bytes identical to the native embed
  oracle, the typed depth refusal landing on exactly the over-limit frame, and
  the host-held snapshot image booting a third pass.
- `tests/fram_snapshot_boot_test.sh` PASS: fold boot, image boot, and damaged-
  image boot answer byte-identically, with the damaged arm reporting its
  degrade to a full fold.
- `tests/docs_semantics_ratchet.sh` PASS over the current document set.
- The limits table above was produced against `9f00313` with content-addressed
  artifacts resolved from cache: the wasm-embed module, the native server, and
  the native embed oracle.

## Known limitations

- **Store materialization was the scale limit of this release, and the fix for
  it is in this release.** Boot time and boot memory grew as roughly the square
  of live triples; after `5721164` and `3df8efe` both grow about linearly on the
  measured corpus. The budget and limits matrix was certified before that fix
  and has not been re-certified, so the store sizes it names bind more tightly
  than the shipped engine actually requires.
- **The measured boot corpus is assert-only, and retraction-heavy boots are
  still superlinear.** Two known quadratics in the store are untouched by the
  fix and unexercised by the curve: the live-set copy per retraction, and the
  active-bucket copy per re-assertion. A boot dominated by either still grows
  as roughly the square of the work. Unmeasured, not fixed.
- **Wasm checkpoints stop at about 1,050 triples**, trapping in the snapshot
  serializer's arena allocation. Native checkpoints do not have this bound.
  Measured pre-fix and not re-measured.
- **Reads of a 1,000-triple store trap even at 192 MiB.** Read cost, not write
  cost, is what makes a small isolate budget bind. Measured pre-fix and not
  re-measured; the read path is not what the fix touched, but the boot memory
  it shares is much smaller now.
- **A hard-cap overrun is a trap, never a typed error.** The memory budget
  tunes compaction cadence; it does not soften exhaustion. A trap is
  instance-fatal, and the status line before the abort is a report, not a
  recovery.
- **Write-path transients are worse than boot, and they were not re-measured.**
  On the pre-fix engine, feeding 5,000 triples through the native server peaked
  at 7.8 GB RSS against 3.94 GB to boot the result. Boot for that store is now
  31.5 MB; the write-path peak is unknown on the fixed engine.
- **The Durable Object numbers are carried from a pre-fix build.** Re-bench
  before quoting them as current.
- **Wasm timings here ride Python/wasmtime host calls.** In-isolate per-frame
  latency is lower; do not compare the two columns as if they were one.
- Fram remains single-machine and single-writer with no engine access control.
  These receipts are not distributed consensus.
