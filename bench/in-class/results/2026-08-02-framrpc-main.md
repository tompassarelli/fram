# Fram (FRAMRPC v1) vs SQLite durable sole-writer receipt

Observed 2026-08-02 on Fram engine main `e90e93d639e33db5b299b5a1a4f690d4f79569fa`.
This is the first current-engine receipt: the prior `bench/in-class/adapters/fram.clj`
called the retired flat engine (`boot-flat!`) and was dead. The adapter now
speaks FRAMRPC v1 over a real loopback socket to `coord_daemon.clj` (the
TermStore v2 coordinator), run under `clojure -M` per that file's own
"never Babashka" header — bb's interpreter overhead swamped op timings by
roughly two orders of magnitude in side-by-side measurement and was rejected.

One complete suite receipt, two repetitions per adapter at 3,000 live
triples, is committed raw at
`~/code/fram/main/bench/in-class/results/2026-08-02-framrpc-main.jsonl`.
30,000 was not run: see "Why 30k was skipped" below.

SQLite won every headline metric, by a much wider margin than the retired
flat-engine's 2026-07-28 receipt. All 8 raw rows reported `errors=0`, exact
join row counts (1,000 of 1,000), and nonzero concurrent reader progress.

| adapter | live triples | metric | samples | mean | range variance |
| --- | ---: | --- | ---: | ---: | ---: |
| Fram | 3,000 | boot to adapter-ready (ms) | 2 | 370.100 | 8.3% |
| SQLite | 3,000 | boot to adapter-ready (ms) | 2 | 0.539 | 3.7% |
| Fram | 3,000 | cold join (ms) | 2 | 7,376.240 | 3.7% |
| SQLite | 3,000 | cold join (ms) | 2 | 1.739 | 40.4% |
| Fram | 3,000 | writes under reads (ops/s) | 2 | 65.894 | 10.3% |
| SQLite | 3,000 | writes under reads (ops/s) | 2 | 1,462.302 | 5.9% |
| Fram | 3,000 | mixed 1W/3R (ops/s) | 2 | 0.128 | 2.8% |
| SQLite | 3,000 | mixed 1W/3R (ops/s) | 2 | 757.950 | 1.9% |

SQLite was about 690x faster on the cold join, 22x faster on durable writes
under concurrent reads, and roughly 5,900x faster on the mixed workload.
`boot-to-serving-ms` here means durable FRAMLOG replay (`boot!`) plus the
first `rpc/status` probe; the ServerSocket bind between them is excluded,
matching the retired adapter's boundary, per
`~/code/fram/main/bench/in-class/METHODOLOGY.md`.

## What is actually being measured

The two-literal `kind=thread` + `title` join is not a "direct" (single
relation-body) query, so `coord_daemon.clj`'s per-page snapshot-root cache
(`retain-query-page-root!`) never applies to it: every `:rpc/query` page,
including every subsequent page of the same logical read, re-runs the whole
general Datalog engine (`query/project-with-occurrences` over the full live
corpus, then `query/run-plan-projected!`) from scratch. A single "read" of
this join at 3,000 triples costs roughly 1.4-1.6s per page attempt, and a
1,000-row result needs 5 pages at the page size this adapter is forced to
use (below), so one cold or mixed-cycle read costs 7-10 seconds. This is the
dominant cost in every Fram row above; it is a real, reproducible property
of the current engine's general-query path, not an adapter artifact.

## Known engine limit, wider than documented

`TermCodecV1`'s recursive-list row encoding has a 256-level depth bound
(`rpc-v1-max-term-depth`). The existing note that `:rpc/scan` and
`:rpc/occurrences` fail past ~250 rows undersells the actual boundary: any
`:rpc/query` page whose row count lands anywhere near 250-300 also fails
with `:term-depth-exceeded`, confirmed directly at 3,000 live triples with a
300-row result and a 4096-row page request (the protocol maximum). This
adapter uses a 200-row page limit to stay clear of the cliff; the
scenario-contract's stated "≤4096 rows/page" ceiling is not actually usable
for a join returning more than roughly a quarter of that.

## Why 30k was skipped

Corpus seeding, sustained writes, and read cost all grow with live corpus
size under the current engine (an unbatched `coord/live-propositions` /
`term-store` intern-and-index rebuild on the hot commit and query paths).
Measured at JVM speed: 3,000 individual durable seed writes take ~14s;
2,000 fresh writes in one `:rpc/batch` transaction still take ~37s on an
otherwise-empty store (the per-operation cost does not meaningfully improve
with batching). Extrapolating the observed per-read cost (~1.5s per general
-query page attempt, cost roughly proportional to live corpus size) to
30,000 triples and the contract's fixed 1,200 sustained-write / 160
mixed-cycle operation counts puts a single fram/30000 run at on the order of
hours, not minutes. The full 3,000-triple paired receipt above already took
about 46 minutes wall clock for two adapters, two runs each; 30k was never
attempted end to end and this estimate is not a rerun of any observed
30k number.

## Comparison to the retired flat-engine receipt

The 2026-07-28 receipt (`results/2026-07-28-main.jsonl`) measured the now
-removed flat engine in-process (`boot-flat!`/`handle`, no socket, no
FRAMLOG replay, no general-query re-execution per page): 500-600 durable
writes/s and 20-30ms cold joins at 3k/30k. Those numbers describe a system
that no longer exists in this repository and are not a valid baseline for
the current TermStore v2 coordinator. This receipt is the first for the
engine actually running today.

## Hardware and provenance

Linux 6.18.38 x86_64, AMD Ryzen AI 9 HX 370, 24 logical CPUs, 98,653,400 kB
RAM, Python 3.13.13, SQLite 3.51.2. Load moved from 4.43/4.32/4.57 to
2.81/4.13/5.64 across the ~46-minute receipt window (12:36:11Z-13:22:01Z),
full metadata in `results/2026-08-02-framrpc-main.meta`.

Verification: `bb bench/in-class/report.bb
bench/in-class/results/2026-08-02-framrpc-main.jsonl` reproduces the table
above; all 8 raw rows report `errors=0` and exact expected join
cardinality. The golden check was intentionally **not** run against
`golden.edn` (that baseline describes the retired flat-engine boundary and
is not comparable); a proposed replacement baseline for this measurement
boundary is at `bench/in-class/proposed-golden-framrpc.edn`, generated from
the medians of this receipt's raw rows. Accepting it into `golden.edn` is
an orchestrator decision, not made here.
