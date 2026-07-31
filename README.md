# Fram

*Fram is a slot-addressable, typed-triple substrate: an append-only store whose
stored unit is a triple that carries its own address.*

[![license](https://img.shields.io/badge/license-MIT_OR_Apache--2.0-blue.svg)](LICENSE)

The stored unit is `(id, l, p, r)` — one identity plus three slots
([`src/fram/types.bclj`](src/fram/types.bclj)). Its values come from a declared
sum type — string, integer, boolean, keyword, id-vector — and that declaration is
an ABI commitment, not a convention: no map crosses into native code. That
is the *typed* half. Five indexes over the slots (`l`, `p`, `r`, `lp`, `pr`) are
written on every assert, which is the *slot-addressable* half. Because each
triple is minted as an entity in the same space it lives in, the query surface
offers `fact-id(cid, l, p, r)` next to `fact(l, p, r)`: one clause binds a
triple's own address alongside its three slots. Reification is a base relation
here, not a modelling pattern layered on afterwards.

Anything with a domain flavour — coordination threads, code ASTs, versioned
worlds, claims under verification — is a module over that substrate rather than
part of it.

## Documentation

- [Why Fram exists](docs/WHY_FRAM_EXISTS.md) — the long argument, negative space conceded.
- [Architecture and project layout](docs/architecture.md) — the fold, the consumers, what lives where.
- [Query reference](docs/query-reference.md) — base relations, aggregates, filters, arithmetic.
- [Pull reference](docs/pull-reference.md) — nested reads, per-value provenance, `:as-of`.
- [Concurrency and writes](docs/concurrency-and-writes.md) — sole writer, optimistic versioning, commit-time checks.
- [Isolation and deployment](docs/isolation-and-deployment.md) — trust domains, hosting, the edge recipe.
- [Tool catalog](docs/tool-catalog.md) · [Measurements](docs/measurements.md) · [Naming ledger](docs/naming.md) · [ADRs](docs/adr/) · [Thread format](THREAD-FORMAT.md)

## Quickstart

The only prerequisite is [babashka](https://babashka.org). The engine is authored
in [Beagle](https://github.com/Autonymy/beagle), a typed Lisp that compiles to
Clojure, but the compiled output ships committed under `out/` — Beagle is needed
to rebuild Fram, never to run it, and the cold loop needs no daemon.

```console
$ git clone https://github.com/Autonymy/fram && cd fram
$ ./demo.sh
```

`demo.sh` copies the bundled example corpus — a fictional "launch a personal
website" project, no personal data — into a temporary directory before it touches
anything, so the committed corpus stays clean. This is the loop it drives:

```console
$ bin/fram import                       # fold Markdown into the triple store
imported -> 162 facts -> /tmp/…/facts.log

$ bin/fram show 2026-01-01-090500       # one entity, as the triples it became
  body  …
  committed  2026-01-01
  depends_on  @2026-01-01-090200
  depends_on  @2026-01-01-090400
  part_of  @2026-01-01-090000
  title  Deploy the site to production

$ bin/fram query '{:find "dep" :rules [{:head {:rel "dep" :args [{:var "cid"} {:var "x"} {:var "y"}]} :body [{:rel "fact-id" :args [{:var "cid"} {:var "x"} "depends_on" {:var "y"}]}]}]}'
  ["c87" "@2026-01-01-090600" "@2026-01-01-090500"]
  ["c73" "@2026-01-01-090500" "@2026-01-01-090400"]
  ["c72" "@2026-01-01-090500" "@2026-01-01-090200"]

$ bin/fram validate                     # cycles, dangling refs, closed vocabulary
OK — 17 threads, no violations.

$ bin/fram export /tmp/regen            # regenerate the Markdown from the store
exported 17 threads -> /tmp/regen
```

`c87` in that result is the address of the `depends_on` triple itself, bound in
the same clause as its three slots. Hang anything you like off it — provenance,
supersession, a verification status — using nothing but more triples.

## Why?

- **Per-triple identity and reification are in the primitive.** A triple is minted
  with its own id and enters the object space, so it is addressable without a
  side table or a reification vocabulary
  ([`src/fram/store.bclj`](src/fram/store.bclj), [`src/fram/query.bclj`](src/fram/query.bclj)).
- **Typing is data in the same triple space.** `cardinality` and `value_kind` are
  ordinary triples asserted about the predicate entity
  ([`src/fram/schema.bclj`](src/fram/schema.bclj)). Precedence is
  triple > environment > fallback, so a warm daemon and a cold CLI folding the
  same log classify identically even when they booted with different environments
  ([`src/fram/kernel.bclj`](src/fram/kernel.bclj)).
- **Files are a view you can walk away with.** `import` → `export` → `import`
  yields the same triple set, guarded by
  [`tests/roundtrip_test.clj`](tests/roundtrip_test.clj).
- **One write path, adversarially receipted.** Optimistic version checking lives
  in a single place ([`src/coord_commit.bclj`](src/coord_commit.bclj)); 24
  concurrent writers racing the same base yield exactly one winner and 23
  rejections ([`tests/coord_test.clj`](tests/coord_test.clj)).
- **The query boundary rejects rather than runs.** Unknown relations, unbound head
  variables, and unstratified negation are refused before evaluation
  ([`tests/query_test.clj`](tests/query_test.clj)).
- **Fram's own source lives in a Fram graph.** 25 Beagle modules carry
  `;; @upstream:graph`: the `.bclj` text is a rendered view, and the graph is the
  upstream you edit ([`build.sh`](build.sh), [`codegraph/`](codegraph/)).
- **A second daemon implementation in Zig serves as a parity oracle.**
  [`src/zig/daemon.zig`](src/zig/daemon.zig) speaks the same wire protocol, and
  [`tests/zig_occ_oracle_test.sh`](tests/zig_occ_oracle_test.sh) replays corpora
  through both to compare their log fingerprints.

## Status

Fram is pre-1.0 and removes rather than deprecates — there are no
back-compatibility shims, so any surface can change between releases. Two limits
are structural rather than provisional: there is no access control (isolation is
process, log, and network, one graph per trust domain), and the concurrency
guarantees are proven under local test load on one machine, not by distributed
consensus.

What does hold is mechanically enforced.
[`scripts/readme-check.sh`](scripts/readme-check.sh) fails CI when a verb, a
linked path, or a licence claim in this file stops being true, and re-runs the
core loop above against a scratch copy of the corpus;
[`bench/propagation/`](bench/propagation) fails CI on a propagation-latency
regression ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)).

For the argument behind all of it, start with
[docs/WHY_FRAM_EXISTS.md](docs/WHY_FRAM_EXISTS.md).

## License

Fram is dual-licensed under your choice of the [MIT License](LICENSE-MIT) or
the [Apache License, Version 2.0](LICENSE-APACHE)
(`MIT OR Apache-2.0`).
