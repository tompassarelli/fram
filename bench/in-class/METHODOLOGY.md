# Durable sole-writer benchmark methodology

This fixture compares storage systems in the class Fram actually occupies:
durable stores with one authoritative writer and concurrent readers. It does
not compare Fram to embedded, non-durable immutable databases.

## Scenario contract

`~/code/fram/main/bench/in-class/scenario-contract.edn` is authoritative. Corpus
sizes are live triple counts and must be positive multiples of three. The
default sizes are 3,000 and 30,000 triples; override them with
`BENCH_SIZES=3000,30000,300000`.

The deterministic corpus has three triples per subject:

1. `kind = thread`
2. `title = title-N`
3. `owner = @owner-(N mod 32)`

Both adapters execute the same two-literal join: subjects with `kind=thread`
joined to their `title`. A correct result contains exactly one row per three
input triples. The benchmark stops on a missing result field or nonzero
`errors`.

The measured scenarios are:

- boot-to-serving: durable corpus activation through the adapter-ready probe;
- cold-start query: the first kind/title join before any warmup;
- sustained writes under concurrent reads: 1,200 individually durable commits
  by the sole writer while another reader repeats the join for the entire
  writer interval;
- mixed read/write: 40 fixed cycles of one durable write followed by three
  joins.

## Measurement boundary

The Fram adapter seeds a scratch FRAMLOG through the current server engine, then
times FRAMLOG replay plus the first successful `rpc/status` request over a real
loopback FRAMRPC v1 socket. It loads the server in the benchmark JVM and binds
the listener between those two timed steps, so JVM startup and TCP bind remain
outside `boot-to-serving-ms`; steady reads and writes do cross the socket and
use the production binary protocol. SQLite uses Python's standard `sqlite3`
binding with WAL, `synchronous=FULL`, one writer connection, and independent
reader connections. Its seed transaction is complete and checkpointed before
the boot timer begins.

Consequently, `boot-to-serving-ms` means durable FRAMLOG replay plus an
adapter-ready FRAMRPC probe, not full process cold start. Do not cite it as a
server executable startup number or compare it with results from the retired
flat-log/EDN adapter.

Every measured write is an individual acknowledged durable commit. Corpus
generation and initial SQLite seeding are outside the timers. Scratch state is
created below `/tmp`; the suite never reads Fram's live log and never connects
to its live port.

## Warmup and ordering

Boot and cold-query measurements happen first. Only then does each adapter run
30 durable warmup writes and 10 warmup joins. Warmup results are discarded.
The sustained and mixed phases follow in that order. This keeps "cold" honest
while giving the steady phases the same first-touch policy.

Each landing run executes every adapter/size pair twice in alternating adapter
order. The report records range variance as `(max - min) / mean`. Shared-host
load is recorded with the result and unexplained high variance is reported, not
averaged away.

## Hardware and corpus provenance

Every committed result records:

- UTC timestamp and Fram git revision;
- kernel (`uname -srmo`);
- CPU model and logical processor count (`lscpu`, `nproc`);
- memory total (`/proc/meminfo`);
- load before and after (`/proc/loadavg`);
- Python and SQLite versions;
- exact corpus sizes, run count, and scenario-contract version.

The corpus generator is committed in both current adapters and is defined
above. It has no random input. A row's `corpus-triples` plus contract version
fully identifies the logical corpus.

## Index-architecture extension

`~/code/fram/bench/in-class/run-index.sh` compares the two representations in
the H2 decision without changing either production implementation:

- `store-id-hash` uses `fram.store`'s canonical integer value and fact IDs, with
  fact IDs posted at the leaves of benchmark-local SPO/POS/OSP hash-prefix
  tries;
- `mmap-rotations` uses `rotations/write-set!` and `rotations/open-set`, then
  binary-searches exact prefixes in the production immutable SPO/POS/OSP
  segments.

Each contender runs in its own Babashka process so its retained RSS and heap
measurements do not include the other representation. Corpus construction and
index preparation are reported separately as `prepare-ms`; `query-ms` covers
one functionally checked query. Each JSONL row repeats the revision, contract,
engine, result cardinality, RSS/heap, hardware, and load metadata needed to
interpret it without a sidecar.

The coordinator aggregate scenario cites decision section
`Workload derivation / Coordinator aggregate/projection scans` and probes the
bound `title` predicate. It returns one row per corpus subject, so lookup stays
selective while output remains honestly O(K).

For this extension only, the first 32 deterministic subjects use `kind=agent`;
the remaining subjects keep `kind=thread`. The staffing scenario cites decision
section `Workload derivation / Staffing`, selects those 32 subjects through the
POS `(?,kind,agent)` prefix, then projects each subject through SPO. Its fixed
96-fact result separates lookup behavior from a growing output.

The point lookup scenario cites decision section
`Workload derivation / Point lookup` and probes the exact
`(@corpus-0,title,?)` SPO prefix. It always returns one fact across
3k/30k/300k, making the fixed-result scaling test explicit.

The compound scenario cites decision section
`Workload derivation / Datalog joins`. It binds `title-0` through POS, then
re-probes the substituted subject through the exact `kind=agent` and
`owner=@owner-0` prefixes. Its one-row result measures compound-prefix work
rather than growing result construction.

The rotation-outage scenario cites decision section `Benchmark interpretation`
and fixes the corpus at exactly 350,701 live facts: 116,900 three-fact subjects,
one pad fact, and `lead` in place of `owner` for the first 1,623 subjects. Its
two-rule shape first selects every `lead`, then joins each substituted subject
to `title`, reproducing the historical result cardinality of 1,623. Every row
carries the committed historical observation (5,004 ms timeout to 241 ms,
source `~/code/fram/bench/index-rotations/README.md:46-79`) as provenance-only
fields; `query-ms` is the new observation and is never presented as a rerun of
those old wall-clock values.

## Golden ratchet

Run the default landing gate from a clean Fram checkout:

```sh
BENCH_RUNS=2 BENCH_SIZES=3000,30000 \
  ~/code/fram/main/bench/in-class/run.sh
```

`~/code/fram/main/bench/in-class/golden.edn` stores the accepted median for the four
headline metrics for every default adapter/size pair. The runner invokes
`~/code/fram/main/bench/in-class/check-golden.bb` automatically. A landing fails if
a latency exceeds 1.50 times its accepted median, throughput falls below 0.67
times its accepted median, an expected adapter/size case is absent, a row is
malformed, or any adapter reports an error.

After an intentional performance change, rerun twice, inspect raw rows and
variance, commit the observed table whether Fram wins or loses, and move each
golden only toward the newly accepted median. Never adjust a golden merely to
make a failing landing green; the observed behavior is authoritative.

## Prior DataScript result

Historical commit `9ea7c54dd188ab57a562b9421a6184e2a2fda779`
(`bench: comparative benchmark fram ... vs DataScript 1.7.3`) is intentionally
not used as this baseline. It recorded DataScript winning every measured row,
including roughly 10–100x advantages on several queries and writes. That
history also records why it was out of class: the DataScript arm was embedded,
in-memory, non-durable, and had no server/process boundary. The loss remains
valid evidence for that workload; replacing it with SQLite here narrows the
comparison class rather than rewriting the result.

Datomic-transactor-class and XTDB adapter obligations are pinned in
`~/code/fram/main/bench/in-class/adapters/README.md`. They remain explicit stubs
until their real durable services, exact revisions, and licenses are available.
