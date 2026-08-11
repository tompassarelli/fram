# `@tompassarelli/fram-cloudflare-do`

Run the Fram engine *inside* a Cloudflare Durable Object. The engine is
`lib/libfram.wasm`, built by `bin/fram-native-build --host wasm-embed`; this
package is the host half of that seam — it answers all nine `fram_host_v1`
imports out of DurableObjectStorage, so a Durable Object becomes a Fram
database with no server, no socket, and no shim in front of it.

This is a library an application embeds, not a deployment. Nothing here is
deployed on its own; `wrangler.example.toml` shows the shape your Worker needs.

For a Worker that talks to a Fram *server* over FRAMRPC, use
`clients/bun` behind a shim instead — that is a different regime with
different guarantees.

## Build the engine module

```console
$ export FRAM_WASI_CC=/path/to/wasm32-unknown-wasi-clang
$ scripts/build-wasm.sh
build-wasm: lib/libfram.wasm <- <artifact-address> (<bytes> bytes, sha256 <hash>)
```

`scripts/build-wasm.sh` copies `lib/libfram.wasm` out of a content-addressed
`fram-native-build` artifact and writes `lib/provenance.json` beside it, which
pins the artifact address, the wasm's sha256, and the Fram commit. Both files
are build outputs and are not tracked; `scripts/check-seams.mjs` re-derives the
hash and refuses a wasm that does not match its own provenance.

## Use it

```js
import { FramDurableObjectBase } from '@tompassarelli/fram-cloudflare-do';
import framModule from '../lib/libfram.wasm';

export class FramLog extends FramDurableObjectBase {
  constructor(state, env) {
    super(state, env, framModule, { spaceId: env.FRAM_SPACE_ID });
  }

  async fetch(request) {
    const frame = new Uint8Array(await request.arrayBuffer());
    const { status, response } = await this.query(frame);
    return new Response(response, { headers: { 'x-fram-status': status } });
  }
}
```

The four call surfaces take a canonical FRAMRPC v1 request frame and answer
with one response frame:

| method | engine entry point | for |
| --- | --- | --- |
| `query(frame)` | `fram_query` | reads at the current version |
| `transact(frame)` | `fram_transact` | writes |
| `snapshot(frame)` | `fram_snapshot` | reads as-of a version or instant |
| `checkpoint(frame)` | `fram_transact` | `:rpc/checkpoint`, plus `imageBytes` |

Frame encoding is not this package's job: the frames are the same canonical
FRAMRPC v1 bytes every other client speaks.

## What it does with storage

One Durable Object owns two chunked key ranges in its own storage:

| range | holds |
| --- | --- |
| `framlog/` | the FRAMLOG, which is the authority |
| `framimage/` | the snapshot image a checkpoint writes |

Both are sliced into 64 KiB chunks under a `meta` key that carries the
authoritative length, because DO caps one value at 128 KiB. A commit rewrites
only the chunks at or after the lowest byte the guest modified, deletes the
chunks a shrinking object left behind, and publishes **both ranges in one
transaction** — fram boots through the image and replays the log tail, so the
pair must never be observed half-published.

Fram's storage hooks must return before the guest call unwinds, and DO storage
is async. So both objects are resident in isolate memory for the duration of a
guest call, and storage is touched only on either side of it: one load at open,
one commit after the call returns.

## Durability, exactly

`storage_sync()` returns 0 to the guest while the bytes are still only in the
isolate's heap. The guarantee is therefore scoped to this client's methods,
not to the engine's own sync:

- **Every public method awaits the storage commit before it resolves.** A
  caller that awaits `transact()` before responding cannot ack a commit that is
  not yet durable.
- **Commits are atomic and serialised.** Fram replays the log from byte zero, so
  a torn tail is unrecoverable where a short one is not. An isolate lost after
  `storage_sync` but before the commit lands leaves a *short* log, and the next
  request reopens it and answers.
- **A rejected commit fences the instance.** The dirty range is restored, the
  instance refuses every later call, and the Durable Object drops it, so the
  next request reopens from the bytes that actually landed. Nothing is acked
  from an instance whose relation to storage is unknown.
- **One boot per object, one guest call at a time.** Concurrent requests join a
  single boot rather than building two images over one storage, and calls into
  the guest are serialised because guest pointers live across the awaits inside
  a call.

What this does *not* give you is the local-disk regime's D1: there is no
`force(true)` barrier here, and durability is Cloudflare's storage commit.

## The seam is checked at startup

`FramInstance.instantiate` compares the module's real import and export lists
against `src/seams.mjs`, which mirrors `native/wasm-embed.seams` line for line,
and refuses a module that demands anything else. `scripts/check-seams.mjs` runs
the same comparison against the ledger at build time, where signatures are
visible too. A tenth host hook cannot reach this adapter silently.

## Test it

```console
$ npm install                        # miniflare, for the workerd half
$ scripts/build-wasm.sh
$ node test/pack-frames.mjs
$ npm run test:node                  # durability, fencing, multi-chunk
$ npm run test:workerd               # the frame matrix inside workerd
```

`tests/fram_do_client_smoke.sh` is the same harness as one CI row: it builds
the wasm and the native lp64 oracle, runs both halves, and compares the
transcript and the FRAMLOG bytes the Durable Object wrote against the oracle's.
It SKIPs cleanly when node, miniflare, or the wasi compiler is absent.
