# proto-addr — S-profile addressability fixture

Backs `tests/cnf_addressable_forms_test.clj`. A warm-daemon fixture proving the
INDEX/READ-DEF addressability invariant on real Clojure protocol/extension shapes
(EXP-025 p1c ring-01 defect fix).

- `code.claimlog` — the ingested claim log (booted by the test on a scratch port).
  Named `.claimlog`, not `.log`, so `.gitignore`'s `*.log` doesn't drop it.
  Regenerate: `bin/fram-ingest-code src/ring/core/protocols.bclj src/fixt/shapes.bclj --root src --out code.claimlog`
  (with the BEAGLE_HOME / FRAM_RACKET pin from `docs/private/`).
- `src/fixt/shapes.bclj` — hand-written; covers defrecord / defprotocol+member /
  extend-type / defmulti / defmethod.
- `src/ring/core/protocols.bclj` — the ingest input for `ring.core.protocols`
  (`.clj` renamed to `.bclj`; the AST is emit-target-independent). Covers
  defprotocol (metadata-annotated name) + member, a `^hint`-named `defn-`, and a
  multi-target `extend-protocol`.

## Provenance / license

`src/ring/core/protocols.bclj` is derived from **ring-core** `ring/core/protocols.clj`
(https://github.com/ring-clojure/ring), **MIT License** —
Copyright (c) 2009-2010 Mark McGranaghan, Copyright (c) 2009-2026 James Reeves.
Included here as a test fixture under the MIT permission to use/copy/redistribute
with this notice retained. Not part of fram's shipped product.
