# FRAM repository instructions

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

- 2026-07-29 — Beagle packaged-input revision
  `989fff80824f0e5a8936ac0d7e0ceba33b810890` is MIT OR Apache-2.0. It is
  the current `flake.nix` graph-authoring runtime pin and is license-compatible
  with FRAM; update this dated record when the package pin advances.
