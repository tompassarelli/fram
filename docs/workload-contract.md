# Workload contract

**Status:** v1 envelope. Bounds marked *observed* come from operator receipts
(2026-07-29/31); bounds marked *TBD* land with the in-class re-baseline and are
invalid to cite until they carry a gate.

"Functions to specification" is falsifiable only against a described workload.
This document describes the reference workload and the envelope Fram commits to
serve under it. The division of blame is the point: a failure **inside** the
envelope is a Fram defect and lands on a line of [`guarantees.md`](guarantees.md); a
usage pattern **outside** the envelope is a client defect against this
document. Either way, the failure lands somewhere named — never nowhere.

## Reference workload NW-1 — coordination substrate (north)

The shape a coordination client actually imposes, as measured:

- **Corpus:** ~350k live triples *observed*, append-only growth; second
  independently fenced telemetry space under its own log and port.
- **Writes:** dominantly single-fact assert/retract issued through the batch
  verbs; bursty lane traffic (admission, identity, telemetry, evidence
  records per agent lane); occasional multi-fact atomic batches (message
  publish). OCC `expected-version` guards on contended subjects.
- **Targeted reads:** single-subject `show`, subject+predicate resolution,
  single-literal indexed queries — the 1–2 ms class *observed* on a healthy
  daemon.
- **Bulk reads:** paged query drains for projections and boards. (The
  historical whole-corpus fetch — ~34 MB per call *observed* — is an
  anti-pattern, excluded below, and being removed from the client.)
- **Concurrency:** ≤ 8 concurrent bulk-verb client processes (client-side
  gate), plus one persistent subscribe firehose, one listener socket per
  agent (lease renew every 40 s), and SDK lane clients; *observed* peak ~57
  concurrent client processes against one daemon.
- **Cadence:** interactive command bursts; a sweep every 5 minutes issuing
  many targeted reads; lease renewals as a steady background tick.

## Contracted envelope (head, FRAMRPC v1)

| Dimension | Commitment |
|---|---|
| Request size | body ≤ 1 MiB; oversize is rejected from the header, typed |
| Response size | frames ≤ 1 MiB; bulk reads MUST paginate (`:rpc/query` pages, ≤ 4096 rows). `:rpc/scan` and `:rpc/occurrences` are bounded to ~250 rows by the term-depth cap until their pagination lands — [`guarantees.md`](guarantees.md) N3 |
| Targeted read latency | p95 ≤ *TBD* at 500k triples with ≤ 8 concurrent clients |
| Durable write throughput | ≥ *TBD* single-fact tx/s sustained; batches amortize to one frame + one fsync per batch |
| OCC conflicts | `:rpc/conflict` is contract behavior under contention, not an error; the client retries with a fresh base |
| Restart | replay is O(full log) at head; readiness is probed (`:rpc/status`), never assumed; boot cost bound *TBD* per 100k triples |
| Overload | **unspecified** until bounded admission lands ([`guarantees.md`](guarantees.md), capacity section); until then saturation behavior is explicitly outside the contract |

## Anti-patterns — observed in the wild, excluded from the contract

Each of these was implicated in a real incident. Fram does not commit to
serving them well, and the contract names them so the blame lands correctly:

1. **Whole-corpus fetch with client-side filtering.** Fetching the full live
   view to answer a targeted question multiplies daemon allocation by
   concurrency and produced the GC-wedge class of incident. Use targeted ops
   or pages.
2. **Timeouts below real latency with a silent fallback that changes the
   answer.** A client that times out and substitutes a differently-scoped
   local computation returns *wrong data that looks like database flakiness*.
   A timeout must fail loudly or retry the same question — never answer a
   different one.
3. **One-shot requests with no transport retry.** Daemon restart, cutover, and
   GC pauses are contract-visible windows; a client without bounded-backoff
   retry converts every window into a user-visible failure.
4. **Fail-closed reactions to transient read flicker.** A missing row on one
   read is not evidence of loss (see occurrence semantics); consumers that
   fail closed on a single read amplify flicker into outages.

## Client obligations

- Retry transient socket errors with bounded backoff; treat `:rpc/conflict`
  as retry-with-fresh-base.
- Paginate every read that is not provably small.
- Never substitute a differently-scoped answer on timeout.
- Probe readiness after restart; do not infer daemon death from one refused
  connection.

## Change law

Every numeric bound in this document must cite its gate (a golden-ratchet
entry or a manifest-dispositioned test) once it leaves *TBD*. Changing a bound
without moving its gate is a defect. Adding a new client usage pattern means
adding it to NW-1 *and* extending the envelope — silently exceeding the
envelope forfeits the contract for that traffic.
