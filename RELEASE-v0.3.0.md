# v0.3.0

Fram v0.3.0 moves ordinary coordination work onto the warm daemon, aligns
queries with North's complete graph, bounds checkpoint storage, and improves
the coordinator's behavior under reload, contention, and deployment.

This is a minor release because `query` has an intentional visible semantic
change. The persisted log and coordinator wire formats remain compatible.

## Highlights

- `show`, `tell`, `retract`, and `query` use the warm coordinator path. On the
  live corpus used during development, a representative `process_outcome`
  query fell from 15,706 ms to 665 ms, about 24× faster. The release-candidate
  latency ratchet kept warm operations below 65 ms p95.
- `query` now sees the same merged coordination-plus-telemetry graph as
  `health`, `agents`, `trace`, and `show`. In the measured corpus this returned
  1,477 rows instead of 768; the additional 709 `@run:*` rows were telemetry
  facts omitted by the former coordination-only cold fold.
- Split-log reloads apply new tails incrementally, concurrent cold query-cache
  builds are single-flight, and lease traffic keeps the warm cache current.
  Read evaluation is detached from the mutation monitor, reducing contention
  between queries, writes, reloads, and checkpoint work.
- Checkpoint retention now keeps the newest three durable sequences by
  default, pruning only recognized older image/sidecar pairs after a
  replacement is durable. `FRAM_SNAPSHOT_RETAIN` remains configurable with a
  minimum of one. A dry run against accumulated development state selected 99
  of 102 checkpoint files for pruning, reducing 3,645 MiB to 119 MiB retained.
- Slow reads now report separate reload, lock-wait, and execution timing above
  `FRAM_SLOW_READ_MS`. The daemon-read timeout is also long enough to avoid
  abandoning a healthy cold-cache response only to perform a slower cold fold.
- Atomic batch-at-version writes, graph edit transactions, fenced writer
  authority, bounded shutdown, and blue/green prepare-demote-promote support
  strengthen coordinator mutation and handover behavior.
- Generated coordinator, runtime, and resolver artifacts were refreshed from
  their graph-authored sources. Release harnesses now isolate scratch corpora,
  snapshot writers, writer-authority probes, and Beagle tool paths so they test
  the intended behavior rather than ambient machine state.

## Compatibility

The deliberate compatibility change is the `query` result surface: telemetry
subjects are now included when the daemon serves a merged split-log corpus.
Consumers that assumed `query` omitted telemetry-only subjects must update
that assumption.

Cold fallback remains available when the daemon is unreachable or rejects a
request, and existing diagnostics are preserved. Persisted logs and the
coordinator wire protocol do not require migration.

## Known limitations

- First startup with no usable checkpoint still folds the complete corpus. A
  cold start over a roughly 114 MB split corpus was observed taking about 37
  seconds to readiness, so first boot can still take tens of seconds.
- The experimental startup-polling change explored during release preparation
  is not included in v0.3.0.
- Slow-read attribution and reload improvements make stalls diagnosable and
  reduce common causes, but this release does not claim every long-tail stall
  has been eliminated.
