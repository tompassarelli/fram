# H2 index-architecture receipt

Observed 2026-07-28 on harness revision `e641128`. The complete 52-row JSONL
receipt is committed at
`~/code/fram/bench/in-class/results/2026-07-28-index-architecture.jsonl`.
Every engine/size/scenario pair ran twice in alternating engine order, reported
zero errors, and matched its fixed logical cardinality.

| scenario | live facts | Store-ID hash mean | mmap rotations mean |
| --- | ---: | ---: | ---: |
| coordinator aggregate scan | 300,000 | 360.997 ms | 9,415.464 ms |
| staffing projection (96 facts) | 300,000 | 0.515 ms | 27.641 ms |
| point lookup (1 fact) | 300,000 | 0.014 ms | 0.634 ms |
| compound join (1 row) | 300,000 | 0.035 ms | 2.361 ms |
| rotation outage shape (1,623 rows) | 350,701 | 18.571 ms | 1,233.483 ms |

The fixed-result cases discriminate lookup from output: Store-ID hash timings
stay effectively flat from 3k to 300k, while mmap binary searches remain
sub-millisecond for a point and pay row decoding/re-probe overhead for staffing
and joins. The 300k aggregate returns 100,000 facts, so both measurements
include honest O(K) output work.

Memory is the opposite tradeoff. At 350,701 facts, Store plus the benchmark
hash tries retained 509,607,936 heap bytes and 630,012 KiB mean RSS. The opened
mmap rotation set retained 20,447,232 heap bytes and 178,616 KiB mean RSS, with
28,769,625 bytes of immutable dictionary/segment storage. These are
shared-host directional measurements, not object-layout accounting.

The `rotation-outage-350701` rows reproduce the committed query shape and exact
1,623-row cardinality. They carry the historical 5,004 ms timeout to 241 ms
rotation result from
`~/code/fram/bench/index-rotations/README.md:46-79` as provenance-only fields;
the fresh 18.571/1,233.483 ms values measure the two representation adapters
directly and do not revise that daemon wall-clock receipt.

Worst-fit clue: production `rotations/open-set` loads its dictionary into a heap
vector, and this benchmark-local mmap reader decodes rows through Babashka.
Therefore the receipt discriminates current exact-prefix behavior and retained
shape, but cannot establish the rival's ultimate native-reader latency or
minimum possible heap.
