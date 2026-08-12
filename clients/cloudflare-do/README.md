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
import {
  FramDurableObjectBase,
  framDataPlaneEntrypoint,
  framDurableObjectTransport,
} from '@tompassarelli/fram-cloudflare-do';
import { framClient } from '@tompassarelli/framrpc/core';
import { DurableObject, WorkerEntrypoint } from 'cloudflare:workers';
import framModule from '../lib/libfram.wasm';

const SPACE = 'wiki.greywrought.com';

// Raw storage owner in a backend Worker. Never bind FRAM into the wiki Worker.
export class FramLog extends DurableObject {
  #fram;

  constructor(state, env) {
    super(state, env);
    this.#fram = new FramDurableObjectBase(
      state, env, framModule, { spaceId: SPACE },
    );
  }

  exchange(frame, options) {
    return this.#fram.exchange(frame, options);
  }
}

// The backend Worker's public service entrypoint delegates only exchange.
export class DataPlane extends WorkerEntrypoint {
  exchange(frame, options) {
    return framDataPlaneEntrypoint(this.env.FRAM, SPACE)
      .exchange(frame, options);
  }
}

// The wiki Worker receives DATA_PLANE as a service binding, not env.FRAM.
export function client(DATA_PLANE) {
  return framClient({
    space: SPACE,
    transport: framDurableObjectTransport(DATA_PLANE),
  });
}
```

The raw storage-owning object takes a canonical FRAMRPC v1 request frame and
answers with one response frame only after any write is durable:

| method | validates | dispatches |
| --- | --- | --- |
| `exchange(frame, { entry, space })` | frame bound and envelope, exact SpaceId, closed operation, operation/entry agreement | `fram_query`, `fram_transact`, or `fram_snapshot` |

Only the backend Worker holds the raw namespace. Its data WorkerEntrypoint
resolves the object with `env.FRAM.getByName(SPACE)` and delegates only
`exchange`; the wiki Worker receives only that service binding. A separately
protected admin entrypoint may delegate export/restore to the same object, so
both capabilities still address one store. Frame encoding and result decoding
come from `@tompassarelli/framrpc/core`; the same official application client
therefore works over TCP or a Durable Object.

The Worker compatibility date must be `2026-03-15` or newer. The raw object
requires `state.id.name === spaceId`; unnamed and string-reconstructed object
IDs fail closed. This deployment regime therefore limits SpaceIds to 1,024
UTF-8 bytes, Cloudflare's maximum for exposing the stable object name, even
though FRAMRPC itself allows 4,096.

`exchange` refuses `rpc/checkpoint`. Checkpoint, export, and restore are
operator capabilities and must be hosted behind a separately authorized
WorkerEntrypoint; they never appear on the wiki's data-plane service binding.

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

- **Every exchange awaits the storage commit before it resolves.** A caller
  that awaits `exchange()` before responding cannot ack a commit that is
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

A timeout or abort after a mutation has reached the Durable Object is
ambiguous: the commit may have landed even when the caller no longer receives
the response. Applications must recover by reading their idempotency receipt;
they must never blindly retry an ambiguous mutation.

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
