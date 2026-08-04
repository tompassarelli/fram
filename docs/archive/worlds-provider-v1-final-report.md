> **HISTORICAL — design provenance only.**
> Final lane report for the retired Worlds provider service, retained as
> evidence of that generation's verification bars. Nothing in it is a
> current runtime reference; start at [`docs/architecture.md`](../architecture.md).

**Status: historical Worlds-service lane report. Not a current runtime reference.**

# Provider Worlds v1 final continuation report

## Result

PASS. The provider Worlds lane is rebased onto current local Fram main and all
declared bars are green. The final report commit is the branch HEAD; the fully
verified code HEAD immediately before this report commit was
`600fa44da5ccbd3a0bfdb0553a457085b60364b5`.

- Pristine current-main baseline: `d2f83aa355be2ea47c52b2c117d2f147eeb46a48`
- Rebased provider commits:
  - `d42d71b02876d3b37ace9b36babc8602b30e6edf providers: add recursive worlds contract`
  - `600fa44da5ccbd3a0bfdb0553a457085b60364b5 providers: bind worlds identities and promotion receipts`
- New main commits included by the rebase:
  - `871f546a0a670c112adfbd1c5a82583c5b00a65e build: advance Beagle projector pin`
  - `01d1f72ddecd97d11f3f27c920c0de8d7361383d build: regenerate Beagle projections`
  - `d2f83aa355be2ea47c52b2c117d2f147eeb46a48 docs: add operator glossary`
- Push/landing/production contact: none.

## Rebase and conflict inventory

Probe:

```sh
git rebase d2f83aa355be2ea47c52b2c117d2f147eeb46a48
```

Observed:

```text
Rebasing (1/2)
Rebasing (2/2)
Successfully rebased and updated refs/heads/provider-worlds-v1.
```

There were no conflicts. No graph-upstream text edit, graph re-authoring,
generated-file conflict resolution, old-base merge, or `.fram/code.log` write
was needed during this continuation.

## Pristine-main baseline

The shared suites were run first from `/home/tom/code/fram/main` at `d2f83aa`.
The provider and Worlds suites are branch-only because their modules and tests
do not exist on pristine main.

### Native boundary

Probe:

```sh
env -u FRAM_TELEMETRY_LOG bb -cp out tests/native_rpc_boundary_ratchet_test.clj
```

Observed:

```text
native RPC boundary ratchet: 15 / 15 PASS
exit 0
```

Current main has 15 checks, not the original brief's older 14-check count. The
additional current-main check is inherited baseline drift; all 15 pass.

### JVM daemon and restart

Probe:

```sh
env -u FRAM_TELEMETRY_LOG clojure -M tests/native_rpc_daemon_test.clj
```

Observed:

```text
[PASS] restart replays native RPC mutations from FRAMLOG
FRAMRPC v1 JVM daemon: all checks passed
exit 0
```

### MCP

Repository-owned runner probe:

```sh
env -u FRAM_TELEMETRY_LOG bb -cp out tests/mcp_test.clj
```

Observed:

```text
[PASS] tools/list is exactly the five public data verbs
fram MCP FRAMRPC: 19 / 19 PASS
exit 0
```

The baseline failure set is empty. Two earlier command-discovery invocations
failed before loading the product (Clojure CLI lacked Babashka libraries, then
bare `bb` lacked the `out` classpath). The checked-in CI command in
`.github/workflows/ci.yml`, `bb -cp out`, is the discriminating probe above.

## Done-bar evidence

### Bar 1: descendant and clean committed tree

Rebase ancestry probe:

```sh
git merge-base --is-ancestor d2f83aa355be2ea47c52b2c117d2f147eeb46a48 HEAD
```

Observed before the report commit: exit 0. The final post-commit status and HEAD
are recorded after committing this report and quoted in the handoff response.

### Bar 2: graph-log integrity and graph level

The pre-rebase and post-verification probes were identical:

```sh
stat --printf='bytes=%s inode=%i mtime=%y\n' .fram/code.log
sha256sum .fram/code.log
bin/fram-code-status /home/tom/code/fram/wt-provider-worlds-v1
```

Observed:

```text
bytes=113614910 inode=330190942 mtime=2026-08-01 23:58:31.944971151 +0800
c57801a449f705b8a99b4ad7db14d6dfb487ba422787562f0ef3526258fa68d1  .fram/code.log
level=3 src=80 log=/home/tom/code/fram/wt-provider-worlds-v1/.fram/code.log facts=789056 mcp=present port=35337 coord=alive canonical=27
```

The log is 129,743 bytes above the required `113485167` lower bound and stayed
byte-, inode-, and mtime-identical across the rebase, tests, regeneration, and
Nix build.

### Bar 3: provider, Worlds, boundary, daemon restart, and MCP

Provider probe:

```sh
env -u FRAM_TELEMETRY_LOG bb -cp out tests/provider_host_test.clj
```

Observed: 13 `[PASS]` checks, `provider host: all checks passed`, exit 0.

Worlds probe:

```sh
env -u FRAM_TELEMETRY_LOG bb -cp out tests/worlds_provider_test.clj
```

Observed: 30 `[PASS]` checks, `worlds/v1 provider: all checks passed`, exit 0.
This includes exact TermCodec SHA-256 identities, forged seal/build/receipt
rejection, receipt-bound promotion, lost-response recovery, replay refusal,
restart replay, and stale-version rejection.

Shared boundary probe:

```sh
env -u FRAM_TELEMETRY_LOG bb -cp out tests/native_rpc_boundary_ratchet_test.clj
```

Observed: `native RPC boundary ratchet: 15 / 15 PASS`, exit 0, identical to
current main's empty failure set. The brief's older 14/14 total has drifted to
15/15 on current main.

Daemon/restart probe:

```sh
env -u FRAM_TELEMETRY_LOG clojure -M tests/native_rpc_daemon_test.clj
```

Observed: all 22 checks passed, including
`restart replays native RPC mutations from FRAMLOG`; exit 0.

MCP probe:

```sh
env -u FRAM_TELEMETRY_LOG bb -cp out tests/mcp_test.clj
```

Observed: `fram MCP FRAMRPC: 19 / 19 PASS`, exactly
`[tell retract show ask validate]`, exit 0, identical to current main's empty
failure set.

### Bar 4: pinned Beagle check and byte-stable projections

Authoring-loop handshake:

```sh
/home/tom/code/beagle/main/bin/beagle doctor --deep
```

Observed: `Authoring loop: ok`, all functional canaries healthy, exit 0.

Compiler identity probes:

```sh
git -C /home/tom/code/beagle/main rev-parse HEAD
nix flake metadata --json . | jq -r '.locks.nodes.beagle.locked.rev'
```

Both reported `309c6f216392648f7ec10dfeb7bb7e234c08e60c`.

Type-check probe:

```sh
/home/tom/code/beagle/main/bin/beagle check \
  src/fram/provider_host.bclj src/fram/worlds_provider.bclj
```

Observed:

```text
src/fram/provider_host.bclj ok
src/fram/worlds_provider.bclj ok
2 file(s), 0 error(s)
```

The inherited `legacy-annotation-marker`, typed-catalog, and `declare` notes
remain warnings/notes rather than errors.

Canonical regeneration probe, run twice:

```sh
./build.sh
```

Both full `build/generated-targets.d/*.tsv` passes exited 0. The second pass
observed:

```text
fram built -> /home/tom/code/fram/wt-provider-worlds-v1/out
before-provider=e6fedd3991f0be34a532d4a62f0ec81707636fd6d3e510814ff00e2dccb118bc
before-worlds=aa30829c185badcfbae814fa62df631de7e52fd38cb424546b80b389dff203ef
after-provider=e6fedd3991f0be34a532d4a62f0ec81707636fd6d3e510814ff00e2dccb118bc
after-worlds=aa30829c185badcfbae814fa62df631de7e52fd38cb424546b80b389dff203ef
tracked-status-bytes=0
```

The branch's manifest fragment already contained both provider rows, so no
manifest edit was needed.

The continuation prompt expected source-location metadata, but the checked-in
current-main entrypoint is authoritative and does the opposite:
`build.sh` sets `BEAGLE_EMIT_SRCLOC=0`, and `01d1f72` removes the previous
absolute-path `^{:line ... :file ...}` metadata from main's projections. The
canonical pinned regeneration therefore retained metadata-free provider bytes;
no alternate emitter mode was substituted.

### Bar 5: Nix build

Probe:

```sh
nix build --no-link
```

Observed:

```text
building '/nix/store/cjmcqggiwp23sxdsb1ja8msfzzpijhvq-fram-0-unstable-2026-08-01-600fa44.drv'...
exit 0
```

### Bar 6: public-boundary closure

Diff-footprint probe:

```sh
git diff --name-only d2f83aa355be2ea47c52b2c117d2f147eeb46a48..HEAD -- \
  bin coord_daemon.clj deploy docs src/fram/rt.clj tests/fram_mcp.clj | wc -l
```

Observed: `0`.

Provider-source forbidden-surface probe:

```sh
rg -n 'clojure\.edn|edn/read|ServerSocket|serve-flat|fram\.store|value!|fact!|current-facts|all-facts|:rpc/(worlds|raw)|\bCID\b' \
  src/fram/provider_host.bclj src/fram/worlds_provider.bclj | wc -l
```

Observed: `0`.

The 15/15 native-boundary ratchet independently proved the daemon's closed
thirteen-operation FRAMRPC set, exactly five public MCP data verbs, no public
EDN/socket compatibility path, and no graph-control implementation in the MCP
runtime. The branch changes only its provider sources/projections, focused
tests, and generation-manifest fragment. No public socket, MCP verb, EDN path,
or Store/CID surface was added.

## Baseline deviations

- Shared-suite failures: none on pristine main, none on the lane.
- Boundary count: current main and lane are both 15/15; the original brief's
  14/14 count is stale by one inherited passing check.
- Projection source metadata: current main's canonical entrypoint disables and
  removes it, contrary to the continuation prompt's expectation. The lane
  follows the checked-in entrypoint and matches its byte-stable format.
- Rebase conflicts: none.
- Graph-authoring changes in this continuation: none; the predecessor's graph
  transaction remains authoritative and `.fram/code.log` stayed byte-identical.
