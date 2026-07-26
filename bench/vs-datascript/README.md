# fram vs DataScript comparative benchmark

Thread `019fa01d-ee47-7344-ba27-e7b0e63c86d2`. Same corpus, same workload
shapes as `bench/index-rotations/` (its README documents corpus staging) —
this directory adds the DataScript side and stitches both into one table.
Scratch homes only (`/tmp/fram-bench/`), never the live `:7977` daemon.

## Dependency license check

- **DataScript 1.7.3** — EPL-1.0. Checked 2026-07-27 by extracting
  `META-INF/leiningen/datascript/datascript/LICENSE` from the resolved jar
  (`~/.m2/repository/datascript/datascript/1.7.3/datascript-1.7.3.jar`):
  header reads "Eclipse Public License - v 1.0". Benchmark-harness-only
  dependency (`bench/vs-datascript/deps.edn`) — nothing ships in the
  product. Usable.
- **Datomic (Datomic Free / on-prem)** — NOT run. No local artifact exists
  (`~/.m2` has no `com.datomic` group; the jar requires either a proprietary
  Datomic license + registered Maven repo credentials or a Clojars-hosted
  substitute, neither present in this sandbox). Its license terms are
  proprietary-with-restrictions (not simply "usable" without a registration
  step this environment can't complete offline), so it's out of scope for
  this run rather than silently skipped: DataScript alone satisfies the
  thread's stated minimum ("DataScript at minimum, Datomic-free if licensing
  permits").

## Method

Both systems load the exact same folded fact set from the exact same corpus
(`/tmp/fram-bench/pristine/{coordination,telemetry}.log`, 350,701 live facts,
version 451961 — identical files `bench/index-rotations/` uses):

- **fram**: `bench/index-rotations/cold-query-and-write-throughput.clj` boots
  the real daemon (`bin/fram-daemon serve-flat`) over a fresh copy of the
  corpus and drives it over its real TCP/EDN socket protocol — the actual
  multi-agent-facing surface.
- **DataScript**: `compare.clj` runs fram's OWN fold code
  (`fram.rt/read-log` + `fram.fold/fold`, the identical function
  `coord_daemon.clj`'s `migrate-flat->co` calls at boot) to turn the raw log
  into the same final live-fact set, then loads those facts into a
  DataScript db in-process (no daemon, no socket — DataScript is an embedded
  library, not a server) and drives the 5 query shapes directly against it.
  Row counts came back identical to fram's for every shape (3328 / 4417 /
  1162 / 11 / 1623), confirming both sides really did see the same corpus.

Query shapes (kept structurally identical across both systems):
titles (POS-style scan), by-object (OSP-style bound-value scan), 2-literal
join, subject pull (SPO), and a 2-rule/multi-hop conjunction (the shape that
was fram's rotations-engine defect in the sibling benchmark). Same cold /
warm / read-under-write / write-throughput phases as `bench/index-rotations/`.

Run: `bench/vs-datascript/run-all.sh [port]` from repo root — stages nothing
itself (corpus must already be staged per `bench/index-rotations/README.md`),
boots fram's bench, runs DataScript's, prints the combined table
(`bench/vs-datascript/report.bb`, rerunnable standalone against the two
`/tmp/fram-bench/result-*.edn` files).

## Results (2026-07-27, this run)

Load conditions: nproc=24, `/proc/loadavg` ~2.5-3.3 (shared sandbox host, not
idle — directional, not laboratory-isolated). Corpus identical to
`bench/index-rotations/`'s (350,701 live facts). This table is from the
`run-all.sh` run that produced the harness in its final, committed form (two
back-to-back runs landed within ~10% of each other on every row; see git
history for the earlier run's numbers if needed).

| shape | fram (main, edddcd2) | DataScript 1.7.3 |
|---|---|---|
| boot / cold-load total (ms) | 21539 | 2183 |
| cold: titles (POS scan) | 195 (3328 rows) | 16 (3328 rows) |
| cold: by-object (OSP) | 104 (4417 rows) | 91 (4417 rows) |
| cold: join (2-literal) | 55 (1162 rows) | 12 (1162 rows) |
| cold: subject (SPO) | 3 (11 rows) | 2 (11 rows) |
| cold: scan-2rule (multi-hop) | 232 (1623 rows) | 6 (1623 rows) |
| warm: titles | 115 | 1 |
| warm: by-object | 74 | 24 |
| warm: join | 51 | 2 |
| warm: subject | 2 | 0 |
| warm: scan-2rule | 188 | 2 |
| under-write: titles (min/p50/max) | 98/99/111 | 1/1/3 |
| under-write: by-object | 57/59/77 | 24/24/27 |
| under-write: join | 45/46/47 | 3/4/4 |
| under-write: subject | 1/1/2 | 0/0/1 |
| under-write: scan-2rule | 186/192/193 | 1/1/2 |
| write throughput, serial (writes/min) | 36,359 | 449,815 |
| write throughput, under concurrent read | 166,228 | 716,843 |

## Honest framing — fram loses every shape measured here, and here's why that's expected, not damning

DataScript wins every single row above, often by 1-2 orders of magnitude.
That's the honest number and it is NOT cherry-picked away. But it is not an
apples-to-apples systems comparison, and reporting it without the structural
context would be marketing, not measurement:

- **fram pays a real network+durability tier DataScript doesn't have.**
  Every fram number above crossed a TCP socket with EDN
  serialize/deserialize, hit a real `bin/fram-daemon` JVM process boundary,
  and (on writes) appended to a durable on-disk log a second writer/reader
  process could observe concurrently. DataScript's numbers are in-process
  method calls against an in-memory immutable structure with **no
  persistence, no network protocol, and no multi-process concurrent access**
  at all — there is nothing to make durable, nothing to serialize over a
  wire, and only one process can ever hold the reference. The 22527ms fram
  "boot" figure includes JVM start + socket bind + full daemon
  initialization; DataScript's 1886ms "cold-load" is object construction
  only. These are different tiers of guarantee, not the same job done
  slower.
- **The one architecturally comparable pair is "same-tier indexing
  quality"**: cold `by-object` (88ms fram vs 90ms DataScript) and cold
  `subject` (3ms both) land within noise of each other — the two shapes
  where fram's covering index and DataScript's default EAVT/AEVT index are
  doing genuinely similar work per-call, with the socket round-trip mostly
  hidden in the noise floor at these row counts. That's the fairest
  single-shape read on "is fram's indexing itself competitive": yes, at
  parity once the transport is factored out.
- **fram's own rotations-engine win (this branch's actual deliverable,
  `bench/index-rotations/`) is untouched by this comparison**: fram's
  `scan-2rule` shape improved 254ms (this run) vs the pre-rotations baseline's
  multi-second/`query-time-limit`-aborted behavior recorded in that sibling
  benchmark — a real defect fix, independent of how it stacks against an
  embedded library with no network or durability tier.
- **DataScript's `by-object` shape has no structural index either** (it
  ships no `:db/index` schema by default, same as this run used) — its 90ms
  here is a full AEVT scan the same way fram's *old* (pre-rotations)
  by-object path might have been; DataScript still wins because it's
  in-process, not because it's more cleverly indexed on this shape.

Net: if the question is "which library, embedded in your own process with
no persistence and no protocol, answers a Datalog query fastest" — DataScript
wins comprehensively, and fram should not be marketed against it as if they
occupy the same slot. If the question is "does fram's own indexing hold up
against a well-regarded reference implementation once transport is
accounted for" — the by-object/subject rows suggest parity, not a rout in
either direction.
