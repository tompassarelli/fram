# Position in the Datalog database class

**Status:** assessment at v0.3.4 (`dd4aff2`, 2026-08-03). These are engineering notes situating Fram among immutable Datalog
stores, not a replacement for the guarantee contract. A feature is
only as strong as the gate or receipt cited below.

## Shared properties with the class

Fram is in the Datomic/Datahike class for the following kernel and query
properties:

- The evaluator selects from covering `SPO`, `POS`, and `OSP` tries. These are
  the same useful access directions as EAVT, AEVT, and AVET, without asserting
  that the physical layouts or contracts are identical. The rotation manifest
  test pins the three orders ([`../tests/coord_rotations_test.clj`](../tests/coord_rotations_test.clj)).
- Recursive rules use semi-naive evaluation. The v0.3.4 indexed evaluator is
  checked against its retained scan evaluator on generated programs
  ([`../tests/datalog_diff_test.clj`](../tests/datalog_diff_test.clj)).
- Reads use immutable `{version, root}` snapshots. Cursor continuation stays
  on its snapshot across commits ([`guarantees.md`](guarantees.md), Q2).
- Complete ordered results are cached by daemon generation, space, snapshot
  version, operation, and canonical request digest. Snapshot version is thus a
  structural invalidation key, rather than a TTL guess
  ([`query-reference.md`](query-reference.md)).
- Full-text word match is an additive virtual relation backed by an immutable
  snapshot index. It shares the same structural version identity and has no
  TTL or scan fallback ([`query-reference.md`](query-reference.md)).
- Rule bodies are ordered vectors. Fram does not reorder clauses with a query
  planner ([`query-reference.md`](query-reference.md)); this is the same
  explicit-clause-order stance associated with Datomic and DataScript, not a
  missing implementation accidentally presented as a feature.

v0.3.4 changed the practical floor for its direct production query: 3,000
triples produced 1,000 rows in **79.77 ms** (previously 2,192 ms), and 9,999
triples produced 3,333 rows in **137.56 ms**, without `:query-work-limit`
(previously work-limit-broken). These are one release-commit measurements, not
a general throughput claim: see `dd4aff2` and its differential and model-gate
results. The earlier capacity receipt remains historical context, not a
substitute measurement for this evaluator
([`bench/in-class/results/2026-08-02-framrpc-main.md`](../bench/in-class/results/2026-08-02-framrpc-main.md)).

## Properties specific to Fram

- The fast Datalog path has a retained differential oracle, so generated
  programs must produce the same least fixpoint as the scan implementation
  ([`../tests/datalog_diff_test.clj`](../tests/datalog_diff_test.clj)).
- FRAMRPC v1 is a closed, verified thirteen-operation vocabulary; unknown tags,
  fields, and trailing bytes are rejected ([`guarantees.md`](guarantees.md),
  N1; [`architecture.md`](architecture.md)).
- History is queryable as `occurrence(coordinate, action, proposition)`.
  Retractions and their withdrawal targets are ordinary propositions in the
  ledger, not deleted rows or tombstones ([`architecture.md`](architecture.md),
  [`query-reference.md`](query-reference.md)). Fram records assertion history;
  fact-status belongs to a selected view, never the kernel
  ([`ontology.md`](ontology.md), [`naming.md`](naming.md)).
- The native direction is program as data: the same recursive Triple language
  represents propositions, occurrence coordinates, and graph-authored program
  material ([`WHY_FRAM_EXISTS.md`](WHY_FRAM_EXISTS.md),
  [`naming.md`](naming.md)).

## Gaps, in priority order

1. **Time-travel query surface.** The kernel and native query path already
   support logical `as-of`; the missing work is the complete, supported
   time-travel surface. A supported surface is in design.
2. **Hash joins for large intermediates.** Defer this until measurements show
   that indexed nested joins, rather than another boundary, dominate.
3. **Aggregates and pull polish.** Aggregates exist in the query contract, but
   the projection surface remains partial; native `rpc/pull` is not part of
   the current wire contract ([`query-reference.md`](query-reference.md),
   [`coordinator-bind-and-wire.md`](coordinator-bind-and-wire.md)).
4. **Parsed-plan cache.** This is a minor efficiency gap. Ordered-result reuse
   is already structural; parsed-plan reuse is not yet claimed.

## Deliberate non-goals

- **Cost-based optimization.** Predictable, inspectable clause order and
  oracle-checkable evaluation are the better trade at this scale than opaque
  plan selection.
- **Distributed peers and storage tiering.** Fram is a single-machine,
  single-writer deployment model, explicitly not distributed consensus
  ([`guarantees.md`](guarantees.md)).
- **Lucene-grade relevance.** Text search v0 is word-match. Ranking, scoring,
  analyzers, and synonym machinery are outside that scope.

## License appendix

Datomic is proprietary: its free binary is not source available. It supplies
concepts only. Datahike and DataScript are EPL; XTDB is MPL. Those are weak
copyleft licenses, so they are study-only here: no code may be leveraged
without an explicit, current license decision. This assessment uses no code
from those projects.

## Sources and limits

Current implementation and contract sources are linked inline. `dd4aff2` is the v0.3.4 release commit.
No cross-engine benchmark, license compatibility decision, or claim of feature
parity beyond the named properties is made here.
