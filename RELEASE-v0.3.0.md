# Fram v0.3.0 — warm coordination and bounded checkpoints

> Draft as of 2026-07-29 04:04 CST. This is a release-note draft plus the
> release gate; do not tag from the current branch until every unchecked item
> in the readiness appendix is satisfied.

Fram v0.3.0 moves ordinary coordination reads onto the warm daemon, makes
`query` agree with the rest of North's merged graph, bounds checkpoint growth,
and adds the instrumentation needed to identify the remaining intermittent
read stalls. This is a minor release because the query result surface changes
deliberately and observably, while the wire and persisted-log formats remain
compatible.

## Highlights

### Warm coordinator reads

The release moves exact reads, validation, and checkpoint publication out of
the global mutation critical section; makes exact existing writes atomic; adds
an exact-subject index; and routes exact `show`, `tell`, `retract`, and `query`
operations through the warm coordinator.

Commit `96485c6` routes `query` through that path:

- `process_outcome` on the live corpus fell from **15,706 ms to 665 ms**
  (about **24× faster**).
- The result now uses the coordinator's semantic merged
  coordination-plus-telemetry view, matching `health`, `agents`, `trace`, and
  `show`.
- That correction returned **1,477 rows instead of 768**. The additional
  **709 `@run:*` rows** are telemetry facts that the former
  coordination-log-only cold fold could not see.
- Daemon-unreachable, daemon-error, and malformed-query diagnostics retain
  their existing cold fallback.
- `daemon_read_cli` passed 62/62. Reverting only the `-main` query dispatch
  reduced that to 60/62, demonstrating that the routing checks exercise the
  production seam.

### Bounded checkpoint retention

Commit `7668b89` retains the newest three durable checkpoints and prunes older
ones only after the replacement image is durable:

- A dry run on the real state directory reduced **102 checkpoints to 3**.
- It selected **99 files / 3,645 MiB** for deletion and retained **119 MiB**.
- `snapshots-to-prune` is a pure public selector, tested before its result is
  passed to deletion.
- Images and sidecars are grouped by sequence, ordered by monotonic sequence
  rather than mtime, and unknown filenames are never selected.
- The unrecognized-file safety property is mutation-tested: loosening the
  filename matcher makes the focused test fail.
- `FRAM_SNAPSHOT_RETAIN` can override the default, with a floor of one.

### Coordinator resilience and graph-native core

Since v0.2.2, Fram also gained:

- captured read/status and validation paths detached from the mutation
  monitor;
- checkpoint image and sidecar publication phased outside that monitor;
- deterministic contention and disconnect-cancellation coverage;
- atomic edit transactions and version-fenced batch writes;
- split-log snapshot boot and zero-downtime listener handover/drain;
- continued migration of the runtime, resolver, coordinator read/commit/wire,
  and tail-decision strata to graph-authored Beagle modules.

Commit `be3933e` preserves two coordinator recovery semantics in CI: a missing
`:assert-at-version` base reaches the handler's typed `:invalid-base`
rejection, and torn-tail repair is checked for preservation of the complete
prefix plus removal of the torn suffix without incorrectly rejecting
subsequent durable snapshot metadata.

### Stall attribution

Commit `4ee3409` records slow-read phase timing for `reload`, `lock-wait`, and
`execute`, emitting only above `FRAM_SLOW_READ_MS` (default 1,000 ms). This
adds two `nanoTime` calls to a read and keeps healthy operation silent.

## Compatibility

The persisted log and coordinator wire remain compatible. The visible
compatibility change is intentional: warm `query` sees the same merged
coordination-plus-telemetry graph as the other North read surfaces. Consumers
that assumed `query` omitted telemetry-only subjects must update that
assumption.

There is no repository SemVer field to bump. Existing v0.1.x and v0.2.x
releases are identified by annotated Git tags and GitHub releases. The Nix
package retains its established `0-unstable-2026-06-28` version, and MCP
`serverInfo.version = "0.1"` is an unchanged server/protocol identifier rather
than the release version.

## Known limitations

- The observed **23% intermittent stall rate remains unexplained**. Phase
  attribution is now present, but it has not yet produced enough live evidence
  to name the cause.
- Lock-wait timing is landed; **lock-holder/lock-hold instrumentation is the
  next diagnostic step**.
- **Delta guards are not yet landed.**

These limitations constrain the claim: v0.3.0 materially improves the common
path and adds the missing diagnostic boundary, but it does not claim that
every long-tail stall has been eliminated.

---

## Release-readiness appendix (remove before publishing)

### Candidate topology and live status

The required release history is:

```text
6671edb  warm exact-subject show
  └─ 96485c6  warm semantic query
       └─ 7668b89  checkpoint retention
            ├─ be3933e  CI/recovery-semantics repair
            └─ 4ee3409  slow-read phase attribution
```

`be3933e` and `4ee3409` are sibling commits. The final release SHA must contain
both, normally by applying `be3933e` onto `main` at `4ee3409`, then testing the
resulting descendant.

At 2026-07-29 04:04 CST:

- `origin/main` and the Firn Fram pin were still
  `6671edb79108c7c9e29d3ce9dd4314d0de2ba7b1`.
- The running `north-coord.service` was therefore still on `6671edb`.
- `96485c6`, `7668b89`, `be3933e`, and `4ee3409` were **not yet live**.
- Rebuild intent `b38fab35-435d-46f7-ba2e-8ffbd932bd54` was holding for the
  query, retention, slow-read, and `be3933e` batch.

### Evidence already green

- [x] Ancestry: `6671edb → 96485c6 → 7668b89`.
- [x] Ancestry: `7668b89 → be3933e`.
- [x] `bb -cp out tests/coord_assert_at_version_test.clj`: 10/10.
- [x] `bb -cp out tests/coord_torn_tail_repair_test.clj`: 16/16.
- [x] `bb -cp out tests/snapshot_retention_test.clj`: 9/9.
- [x] On `main@4ee3409`,
  `bb -cp out tests/slow_read_attribution_test.clj`: 9/9.
- [x] The query commit records `daemon_read_cli`: 62/62, with the production
  routing branch revert-tested at 60/62.

### Remaining gates

- [ ] Reconcile `be3933e` and `4ee3409` into one clean final release SHA.
- [ ] Confirm the final SHA contains all four required commits:

  ```bash
  git merge-base --is-ancestor 96485c6 HEAD
  git merge-base --is-ancestor 7668b89 HEAD
  git merge-base --is-ancestor be3933e HEAD
  git merge-base --is-ancestor 4ee3409 HEAD
  git status --porcelain
  ```

- [ ] Run the focused behavior and performance gates on that exact SHA:

  ```bash
  bb -cp out tests/coord_assert_at_version_test.clj
  bb -cp out tests/coord_torn_tail_repair_test.clj
  bb -cp out tests/daemon_read_cli_test.clj
  bb -cp out tests/snapshot_retention_test.clj
  bb -cp out tests/slow_read_attribution_test.clj
  bash tests/daemon_read_perf_ratchet.sh
  bash tests/vocab_ratchet_test.sh
  nix build --no-link .#fram
  ```

- [ ] Push the final SHA to `origin/main` and require the `ci` workflow to
  finish green on that exact SHA. The last remote run, at `6671edb`, was red
  and skipped the downstream performance ratchet after its unit failure; it is
  not release evidence.
- [ ] Refresh the Firn Fram pin to the final SHA, commit the pin, perform the
  coordinated rebuild, and verify the running coordinator reports that exact
  revision.
- [ ] Live-canary warm `show`, `tell`, `retract`, and `query`; confirm merged
  query semantics; observe at least one checkpoint interval; verify only three
  recognized checkpoint sequences remain and no unrecognized file was
  removed.
- [ ] Preserve rollback to the current known-running generation until the
  canary is complete.

### Release commands

Only after the exact pushed SHA is green in CI and the live canary passes:

```bash
git tag -a v0.3.0 -m "v0.3.0 — warm coordination and bounded checkpoints"
safe-push --tag v0.3.0
gh release create v0.3.0 \
  --repo tompassarelli/fram \
  --title v0.3.0 \
  --notes-file RELEASE-v0.3.0.md
```

Before `gh release create`, remove this readiness appendix so the published
body contains only the release notes above.
