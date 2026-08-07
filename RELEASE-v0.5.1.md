# v0.5.1

Fram v0.5.1 gives a store a second line: forking seals the active tail into a
content-addressed segment and hands parent and child a fresh tail each, so a
branch splits off without copying or merging anything. It also fixes two
native memory defects that made large stores unbootable or unsnapshottable,
revives codegraph onto the occurrence store behind a CI gate that runs it
instead of only reading its source, repairs a CI pipeline ordering bug that
had broken every push since the beagle pin landed (including the v0.5.0 tag
push itself), and declares three previously undeclared tests in the CI
manifest — each excluded with evidence, still not run — plus a fourth,
newly declared and running.

This is a patch release: the store format is unchanged for anything that has
never forked, and every fix below is a defect correction against v0.5.0
behavior, not a new surface.

## Highlights

### Store forking

A store forks by sealing its tail into a content-addressed segment and giving
both parent and child a fresh tail over the shared chain. Sealing is a
rename: a segment stays byte-identical to the FRAMLOG it was, so the existing
frame CRC and decode path cover it unchanged. Fork is O(1) in store size —
the cost is one SHA-256 of the active tail, a constant that seal policy
bounds — because no triple is copied and no log is rewritten. A never-forked
store gains no files and keeps its tail at flag 0, so it still boots down
exactly the path it did before forking existed.

Branch refs (`<log>.refs/`, `framref/v1`) carry the segment list explicitly,
since segments embed no parent pointer; adding one would change segment bytes
and cost the seal-is-a-rename property. Fork holds the lifetime lock for the
store and both tails and refuses when a writer holds one; every file it
installs is built at a pending name first, with a `framfork/v1` marker
bracketing the rename sequence so a crash mid-fork is legible on open and
finished by replaying the remaining renames in order. Two consecutive forks
with no write between them refuse with `:segment-already-sealed`, because the
two empty continuation tails hash alike and a ref may not name one segment
twice.

The default branch — the one whose tail is the store file itself, unnamed in
`<log>.branches/` — is spelled **main**, on the git prior for a checkout's
starting line. No store has forked before this release, so there is nothing
on disk the respelling migrates. Branch, fork, segment, and sealed are
ledgered in the [naming ledger](docs/naming.md#branch--chosen-2026-08-08).

Tests: `framref_codec_test.clj` (38 checks), `framlog_fork_test.clj` (40),
and `framlog_chain_boot_test.clj` (15) all pass. The chain-boot suite pins
the equivalence oracle — a commit script folded through one file and through
a segment-plus-tail chain must produce the identical store image — and a
never-forked store's behavior is unchanged structurally, not just by
assertion.

### Two native memory defects, found and fixed

**Boot fold.** `fold-store!` sliced the borrowed FRAMLOG vector into 1 MB
subvecs for the streaming API, and two store update paths — active-bucket
positions and derived operation liveness — copied a whole vector per
operation during a fold. On the 936k-operation, 138 MB reference framlog,
RSS grew convexly past 56–60 GB without binding; the process was killed.
After the fix (whole-vector fold, cell-backed positions and liveness during
the fold only), the same log binds at 7.28 GB peak / 6.25 GB serving RSS in
161.6 s. `tests/native_stage6_compare.sh` — the harness's own name for this
check — takes a pre-fix and a post-fix observation directory and byte-diffs
all 7 store artifacts each run produces (history, live occurrences, live
propositions, invalid-coordinate and malformed-term corpus records, the
term-store dump, and their digest manifest); all 7 agree, so the fold
rewrite changed how the fold runs, not what the store observes afterward.

**Snapshot encode.** `rpc/checkpoint` on the same class of store drove RSS
from 6.25 GB to over 20 GB in under 7 seconds while writing zero image bytes
— observed as high as 44 GB in production. `frame-record!` built the image
with `swap! ... into`, which lowers to a fresh arena vector holding the whole
accumulation on every record; a payload staged in its own growing vector cost
the arena a second full copy of the image on top of that. Row records now
declare their payload length and write straight into the output encoder,
with `finish-record!` CRCing the range it just wrote and refusing a writer
whose bytes disagree with its declaration. After the fix, the same
checkpoint costs +2.04 GB over serving baseline and writes a
100,863,531-byte image in 42.7 s, run against a store folded in the same
encode-fix session — the 159.7 s / 6.55 GB fold that is also the image-boot
comparison baseline below. The fixed encoder's image has
the same fingerprint as the main-tip encoder's image on the same store,
modulo the sidecar stamp — a cross-encoder check on the fixed generation,
not a before/after comparison against the pre-fix encoder, which produced no
usable image to compare against.

**Snapshot image boot is now the fast restart path.** On the same store,
image boot takes 117.4 s at 4.3 GB serving RSS afterward, against 159.7 s and
6.55 GB for a full fold measured in this same encode-fix run — a separate
fold pass from the 161.6 s / 6.25 GB boot-fold figure above, run to produce
a fresh comparison baseline rather than reused from it. Before the encode
fix, a checkpoint was not a viable way to reach that image at this store
size at all.

These numbers are measured on one reference corpus on one machine; they are
not the certified limits matrix and make no claim about other store shapes.

### Codegraph revived onto the occurrence store

The five codegraph modules that rented the removed fact-and-CID store
(`new-store` / `entity!` / `value!` / `fact!` / `by-lp` / `by-pr` /
`fact-of` / `live?` / `current-facts`) had not run since it was removed. They
are ported onto the kernel that replaced it, where the unit of write is a
Triple of Terms committed in a transaction and the unit of read is a live
proposition. Node identity now comes from the thing each node already is —
the fact stream's own id, or a def's own name — instead of an opaque id the
old store minted. Supersession follows the kernel: a rename retracts the old
proposition and asserts the renamed one in one transaction, and the store's
withdrawal link stands in for the old reified `supersedes` fact.

The sixth module in the family, `callgraph`, never rented the removed store
and needed no port; it stays in scope here only because the new execution
gate loads and runs it alongside the five that did. All six build and run on
the occurrence store. Goldens are byte-identical to the pre-removal build
except three justified diffs: the elapsed-ms field, node-id allocation order
(now follows the input stream instead of the old store's allocation order),
and supersession output whose golden printed types the removal deleted.

**The execution gate.** No manifest row had ever run a codegraph module:
`codegraph_seam_test.clj` reads sources for forbidden namespaces but never
loads them, so when the fact-and-CID store was removed the five renting
modules died on load with no gate catching it. `codegraph_exec_test.sh`
closes that class of failure. Its unconditional tier loads all six
namespaces from `out/` under bb — exactly the class that rotted, needing
nothing outside the repo. Its gated tier drives three modules end to end
against real oracles on `FRAM_BEAGLE`: `roundtrip_fram` against
`beagle-roundtrip --verify`; `rename` against both `faith.rkt`'s rename trap
and its own rc=3 collision refusal; and `supersession_check` against its
live/withdrawn verdict. Three deliberately provoked failures fail loudly
under the gate, confirming it catches the class it was built for.

### CI pipeline repair

Both CI workflows sourced Beagle's `bin/_beagle-racket` before `raco pkg
install --link` registered the checkout, so the raco-make freshness gate
inside it ran against an unlinked collection and died on "collection not
found for module path: beagle/lang/reader-impl" — every push from
2026-08-05 through the repair, including the v0.5.0 tag push itself
(GitHub runs 31163165620 and 31219968815; first green after the fix,
31222644762). The fix links the
packages first and sources `_beagle-racket` after, so the gate resolves and
warms every `.zo` outside the per-row timeouts the suite applies
(`.github/workflows/ci.yml`, link-before-source step).

Separately, `tests/fram_snapshot_boot_test.sh` builds the complete native
server through `bin/fram-native-build --host server`, measured in the
release-readiness pass at 9m18s under concurrent load and 560 s uncontended
on a 24-core workstation; a 2-core hosted runner cannot fit that inside the
240s per-row timeout under any honest reading. The manifest now carries a new
disposition, `exclude-runner`, for a gate whose cost exceeds a hosted
runner: the row is excluded from the hosted CI run, its evidence is the
`--host server` build it drives, and it still runs in the flake devShell and
before a release (`tests/occurrence_native_ci_manifest.txt`, runner-capacity
row).

### CI manifest exhaustiveness

`cascade_test.clj`, `server_telemetry_shed_test.clj`, and
`snapshot_honesty_pass_test.clj` existed in the tree but were undeclared in
the CI manifest, and all three fail for real against current code —
`cascade_test.clj` calls a socket harness that no longer exists,
`server_telemetry_shed_test.clj` references a function that was never
implemented, and `snapshot_honesty_pass_test.clj` depends on a load-order
side effect that no longer holds. Each is declared as an exclude with
evidence pointing at its own source: `cascade_test.clj` under
`removed-socket-harness`, `server_telemetry_shed_test.clj` under
`removed-telemetry-shed`, and `snapshot_honesty_pass_test.clj` under
`moved-graph-control` — the same class the manifest already uses for tests
stranded by the earlier graph-control move — rather than leaving any of the
three silently absent from the count. None of the three runs; declaring them
makes the gap visible instead of closing it. `triple_log_renumber_test.clj`
was also unlisted before this release and is now classified as a run-bb row.
The suite at tip runs 48 green manifest rows under `run-bb`.

## Compatibility and migration

- **The FRAMLOG is unchanged for a store that has never forked.** A v0.5.0
  log opens and folds exactly as before.
- **Forking is new, additive on-disk state.** `<log>.refs/` and
  `<log>.branches/` appear only once a store forks; nothing is written to
  them otherwise.
- **The default branch is named `main`, not `default`.** No store has
  forked under either spelling before this release, so there is no stored
  data to migrate; a fork on this release names the branch `main` from the
  start.
- **Codegraph's node-id allocation order changed** for the ported modules,
  tracking the input stream instead of the old store's allocation order. A
  consumer that depended on the old ordering, rather than on node identity
  alone, should re-check its assumptions.

## Release evidence

- `framref_codec_test.clj` (38 checks), `framlog_fork_test.clj` (40),
  `framlog_chain_boot_test.clj` (15): PASS.
- Boot fold fix: 936k-op / 138 MB reference framlog binds at 7.28 GB peak /
  6.25 GB serving RSS in 161.6 s, where the pre-fix engine grew past 56–60 GB
  unbound and was killed. Liveness byte-identical under the
  `native_stage6_compare.sh` differential, 7/7 artifacts.
- Snapshot encode fix: same-class checkpoint writes a 100,863,531-byte image
  in 42.7 s at +2.04 GB over serving baseline, where the pre-fix encoder
  reached >20 GB in under 7 s writing zero bytes (observed to 44 GB in
  production). Image fingerprint identical to the main-tip encoder's output
  on the same store, modulo the sidecar stamp — a cross-encoder check on the
  fixed generation, not a comparison against the pre-fix encoder, which
  produced no usable image. Image boot 117.4 s / 4.3 GB serving RSS against
  a separately measured full fold of 159.7 s / 6.55 GB on the same store.
- Codegraph revival: all six modules build and run on the occurrence store;
  goldens byte-identical except the three justified diffs named above.
  `codegraph_exec_test.sh` load tier and gated tier both green; three
  provoked failures fail loudly under the gate.
- CI pipeline: the link-before-source fix and the runner-capacity exclusion
  land in `.github/workflows/ci.yml` and
  `tests/occurrence_native_ci_manifest.txt` respectively.
- Manifest at tip: 48 `run-bb` rows green.

## Known limitations

- **The boot-fold and snapshot-encode fixes are measured on one reference
  corpus** (936k operations, 138 MB framlog) on one machine; they are not a
  re-run of the v0.5.0 limits-table harness and make no claim about other
  store shapes or sizes.
- **The two remaining boot quadratics named in v0.5.0 — live-set copy per
  retraction and active-bucket copy per re-assertion outside a fold — are
  unaffected by this release's fold fix**, which addresses the fold-only
  path. A boot dominated by either still grows superlinearly. Unmeasured
  here.
- **Fork is not yet exercised at production scale.** The fork test suite
  covers correctness and the O(1) cost claim structurally; it does not
  measure a long-lived multi-branch store under sustained write load.
- **callgraph, codegraph, and rep_jurisdiction stay load-only** in the CI
  gate: their goldens are measured against the gjoa corpus, which is not in
  this tree.
- **`tests/fram_snapshot_boot_test.sh` no longer runs on hosted CI**: it is
  excluded as `runner-capacity`, and now runs only in the flake devShell and
  before a release.
