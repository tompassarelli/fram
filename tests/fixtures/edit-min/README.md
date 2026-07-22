# edit-min hermetic fixture

`schema.code.factlog` is a **disposable, self-contained** code log carrying
EXACTLY ONE canonical module — `fram.schema` (render/ingest @root key `schema`)
— freshly ingested from `src/fram/schema.bclj`. It is the sole corpus the
`coord_edit_min_*` receipts boot over.

## Why it exists (the regression it fixes)

The five edit-min receipts used to copy the worktree's live `.fram/code.log`
into `/tmp` and boot over it. That live, append-only log had accumulated the
schema module under **two source identities** (`schema` and `fram.schema`) from
historical re-ingests/renames — so `scope "schema"` matched **2 source files**
and the SAFE-on-spelling verbs (`upsert-form`/`set-body`) refused as ambiguous,
failing the receipts. The failure was live-log contamination, not a production
defect: only one `schema.bclj` exists in-tree. A fresh single-module ingest has
the module exactly once, so the scope is unambiguous by construction.

Booting over a committed fixture (never the live `.fram/code.log`) also removes
the dependence on ambient FRAM telemetry (`FRAM_BIN` deployment, live
coordinator), test order, and any other lane's runtime state — and the receipts
fail loudly instead of silently `SKIP`ping when a required fixture/tool is
missing.

## Captured provenance

- Source revision: `dbf9d2dfc0c08c64fc5e9e62ae929a4467d7fbaf`
- Source bytes (`~/code/fram/src/fram/schema.bclj`):
  `fc7b319b95bb421e8ff10888c19bde2872b96e177f3e44afe0844718d6800016`
- Fixture bytes (`~/code/fram/tests/fixtures/edit-min/schema.code.factlog`):
  `2740b116148e64319b1762f873bb53569e289e2d7bcd09420498df6389633b76`
- Exact live module-root set:
  `#{["@schema#root" "src/fram/schema.bclj"]}`

The ingest log carries transaction timestamps, so regeneration is not expected
to reproduce the log's metadata bytes. Its semantic projection is deterministic:
`~/code/fram/tests/coord_edit_min_byte_identical.clj` renders the fixture before
editing and requires that render to be byte-identical to the captured source.
Every receipt independently requires the exact singleton module-root set above,
so a duplicate `fram.schema`/substring-resolved module cannot enter unnoticed.

## Regenerate

```sh
cd ~/code/fram
bb -cp ~/code/fram/out ~/code/fram/bin/fram-ingest-code src/fram/schema.bclj \
  --module schema \
  --out ~/code/fram/tests/fixtures/edit-min/schema.code.factlog
```

`.factlog` (not `.log`) so it escapes the repo-wide `*.log` gitignore, matching
the `proto-addr` fixture convention.
