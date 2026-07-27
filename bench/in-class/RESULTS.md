# Fram vs SQLite durable sole-writer receipt

Observed 2026-07-28 on Fram engine main `37c1b74`, harness revision
`34da6a2`. Two complete suite receipts, two repetitions per adapter/size in
each receipt, are committed raw at
`~/code/fram/bench/in-class/results/2026-07-28-main.jsonl`.

SQLite won every headline metric at both corpus sizes. No result is elided:
all 16 raw rows reported `errors=0`, exact join row counts, and concurrent
reader progress.

| adapter | live triples | metric | samples | mean | range variance |
| --- | ---: | --- | ---: | ---: | ---: |
| Fram | 3,000 | boot to adapter-ready (ms) | 4 | 529.203 | 11.9% |
| SQLite | 3,000 | boot to adapter-ready (ms) | 4 | 0.657 | 38.1% |
| Fram | 3,000 | cold join (ms) | 4 | 27.868 | 74.8% |
| SQLite | 3,000 | cold join (ms) | 4 | 1.631 | 35.5% |
| Fram | 3,000 | writes under reads (ops/s) | 4 | 499.889 | 45.5% |
| SQLite | 3,000 | writes under reads (ops/s) | 4 | 1,149.779 | 10.6% |
| Fram | 3,000 | mixed 1W/3R (ops/s) | 4 | 58.032 | 19.5% |
| SQLite | 3,000 | mixed 1W/3R (ops/s) | 4 | 707.543 | 9.8% |
| Fram | 30,000 | boot to adapter-ready (ms) | 4 | 5,930.498 | 5.8% |
| SQLite | 30,000 | boot to adapter-ready (ms) | 4 | 0.649 | 22.1% |
| Fram | 30,000 | cold join (ms) | 4 | 314.621 | 61.4% |
| SQLite | 30,000 | cold join (ms) | 4 | 17.309 | 46.3% |
| Fram | 30,000 | writes under reads (ops/s) | 4 | 550.673 | 37.4% |
| SQLite | 30,000 | writes under reads (ops/s) | 4 | 1,299.754 | 16.6% |
| Fram | 30,000 | mixed 1W/3R (ops/s) | 4 | 5.882 | 4.2% |
| SQLite | 30,000 | mixed 1W/3R (ops/s) | 4 | 64.888 | 5.6% |

SQLite was about 17–18x faster on the cold join, 2.3–2.4x faster on durable
writes under concurrent reads, and 11–12x faster on the mixed workload.
Adapter-ready boot is not a production daemon startup comparison: the current
sandbox-safe boundary excludes Fram JVM startup, socket bind, and transport,
as specified in `~/code/fram/bench/in-class/METHODOLOGY.md`.

Absolute cold-query and Fram write-throughput variance is high on this shared
host. The ordering is stable in every raw sample, and both stored receipts pass
the committed four-metric golden. Treat the means as directional, not
laboratory-isolated.

Hardware: Linux 6.18.38, AMD Ryzen AI 9 HX 370, 24 logical CPUs, 98,653,396 kB
RAM, Python 3.13.13, SQLite 3.51.2. Receipt 1 load moved from
18.00/17.77/20.16 to 12.68/15.92/19.24; receipt 2 moved from
16.22/15.38/18.40 to 11.75/14.25/17.68.

Verification:

- receipt 1: suite exit 0; finalized golden check separately exit 0;
- receipt 2: suite exit 0 including `in-class golden: PASS (4 adapter/size
  cases, 4 metrics)`;
- both: two repetitions per adapter/size, exact expected rows, nonzero
  concurrent reads, zero adapter errors.

The historical DataScript comparison at
`9ea7c54dd188ab57a562b9421a6184e2a2fda779` remains an honest loss on its
embedded, non-durable workload. This receipt changes the comparison class; it
does not revise that result.
