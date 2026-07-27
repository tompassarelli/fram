# Durable sole-writer benchmark methodology

This fixture compares storage systems in the class Fram actually occupies:
durable stores with one authoritative writer and concurrent readers. It does
not compare Fram to embedded, non-durable immutable databases.

## Scenario contract

`~/code/fram/bench/in-class/scenario-contract.edn` is authoritative. Corpus
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
- sustained writes under concurrent reads: 120 individually durable commits
  by the sole writer while another reader repeats the join;
- mixed read/write: 40 fixed cycles of one durable write followed by three
  joins.

## Measurement boundary

The managed worktree sandbox denies loopback `bind(2)`. The current Fram
adapter therefore runs `boot-flat!` and `handle` in-process against a scratch
log, using the same durable serve-flat state machine but excluding JVM startup,
TCP bind, and EDN transport. SQLite uses Python's standard `sqlite3` binding
with WAL, `synchronous=FULL`, one writer connection, and independent reader
connections. Its seed transaction is complete and checkpointed before the boot
timer begins.

Consequently, `boot-to-serving-ms` currently means durable corpus activation to
adapter readiness, not full production daemon process startup. Do not cite it
as a socket-serving cold-start number. A socket-capable future adapter may add
transport metadata, but must not silently mix those rows with this boundary.

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

## Golden ratchet

Run the default landing gate from a clean Fram checkout:

```sh
BENCH_RUNS=2 BENCH_SIZES=3000,30000 \
  ~/code/fram/bench/in-class/run.sh
```

`~/code/fram/bench/in-class/golden.edn` stores the accepted median for the four
headline metrics for every default adapter/size pair. The runner invokes
`~/code/fram/bench/in-class/check-golden.bb` automatically. A landing fails if
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
`~/code/fram/bench/in-class/adapters/README.md`. They remain explicit stubs
until their real durable services, exact revisions, and licenses are available.
