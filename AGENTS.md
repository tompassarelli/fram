# FRAM repository instructions

## `docs/archive/**` is historical provenance only

Everything under `docs/archive/` documents a removed architecture generation.
Never cite it as current behavior, never derive an implementation, procedure, or
test expectation from it, and never repair a live surface to agree with it — if
it disagrees with the engine, the archive is the stale side. The current
contract surface is exactly the "Current documentation" list in `README.md`;
start at `docs/architecture.md` and `docs/guarantees.md`. Retiring a document
means moving it into `docs/archive/` with the standard banner, not editing it in
place; `tests/docs_semantics_ratchet.sh` enforces both the banner and the
location.

## Reference material and licenses

Before using any external or `~/code/reference/` repository, re-check its
current revision and license files. Record the check date, exact revision,
license identifier, and practical implication for FRAM. A dated record applies
only to the revision checked; never treat it as evergreen.

- 2026-07-22 — Datahike revision
  `7dd5324781ee8454608418c7e7d7b26c48b5fe2e` is EPL-1.0; FRAM revision
  `b45f6bb7ae2b75140dff069b4558737c7e4008ea` is MIT OR Apache-2.0. Use
  Datahike for ideas and mechanisms only: copy or derive no source expression
  without a fresh license-compatibility review.

- 2026-07-29 — Beagle revision
  `0d6e712f5b829545bb82cb3a9e0b77853a9521a0` is MIT OR Apache-2.0. FRAM
  uses this compatible local revision as its compiler and coherent-world
  checker oracle; compiler output and protocol integration may be consumed by
  FRAM under either offered license.

- 2026-08-11 — Beagle packaged-input revision
  `b7662922b8fb5eb65c5d64a5addd352e90a75519` is MIT OR Apache-2.0. It is
  the current `flake.nix` graph-authoring runtime pin and is license-compatible
  with FRAM; update this dated record when the package pin advances.

- 2026-08-11 — TypeScript npm package `6.0.3`, source revision
  `050880ce59e30b356b686bd3144efe24f875ebc8`, is Apache-2.0. FRAM uses it
  only as the pinned CI compiler for the packed Node declaration-surface gate;
  it is not a shipped or runtime dependency.
