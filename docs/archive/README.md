# Archive — historical design provenance

Every document in this directory describes an architecture generation that has
been removed from Fram — including rationale and positioning essays, the old
pull API, verification drafts, and Codegraph — and is
retained only as evidence of how the design evolved. Nothing here describes the
current engine: its vocabulary, wire shapes, and module names are superseded,
and no statement in these files is a runtime reference, an operator procedure,
or a supported contract. The live contract starts at
[`docs/architecture.md`](../architecture.md) and
[`docs/guarantees.md`](../guarantees.md), with the full current set listed under
"Current documentation" in the [repository README](../../README.md). Each file
here carries a `HISTORICAL — design provenance only.` banner as its first
content, and `tests/docs_semantics_ratchet.sh` fails if a banner is missing or
if a historical document is filed outside this directory.
