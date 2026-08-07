# Fram

*Fram is a persistent engine for recursive, typed triples with neutral,
position-addressed structure.*

[![license](https://img.shields.io/badge/license-MIT_OR_Apache--2.0-blue.svg)](LICENSE)

The semantic kernel has three definitions:

```text
Atom   := String | Int | Float | Bool | Keyword | Instant
Term   := Atom | Triple
Triple := (Term, Term, Term)
```

The positions are `t1`, `t2`, and `t3`. The kernel does not impose
subject/predicate/object roles, and any Triple can occupy any position of another
Triple. `Atom`, `Term`, and `Triple` are the public semantic vocabulary;
`TripleRow` and integer term handles are private storage mechanics.

A proposition is one Triple. Its place in history is another Triple, not a
fourth field attached to the proposition:

```text
vocabulary  := (:email, :grouped-under, :contact)
proposition := ("Alice", :email, "alice@example.com")
tx          := ("demo-space", :kernel/tx-sequence, 1)
occurrence  := (tx, :kernel/op-ordinal, 0)

(occurrence, :kernel/asserts, proposition)
(tx, :kernel/recorded-at, Instant(...))
```

Domain vocabulary earns its structure by assertion: the grouping a
namespaced spelling would smuggle into a slash is stored as an ordinary
Triple instead, where the query engine can join on it. The canonical
normalized example lives in the [ontology](docs/ontology.md).

Equal propositions can have distinct assertion occurrences. Retractions and
withdrawals are ordinary Triples too. Logical transaction order is intrinsic to
the occurrence coordinate; wall-clock, valid, and observation time are related
metadata and never part of proposition identity.

“Turtles” names the architectural prior—*turtles all the way down*: prefer the
same recursive Triple language for data, identity coordinates, history, and
metadata whenever the model permits. It is not a second primitive or a code
type. See the [naming ledger](docs/naming.md).

## Current documentation

- [Architecture](docs/architecture.md) — semantic kernel, physical rows, database, server, and projections.
- [Glossary](docs/glossary.md) — the single vocabulary source for current documents.
- [Query reference](docs/query-reference.md) — `triple` and `occurrence`, recursion, filters, arithmetic, and aggregates.
- [Ontology](docs/ontology.md) — modeling rules, the canonical normalized example, profiles, and semantic hints.
- [Guarantees](docs/guarantees.md) — guarantees, concurrency, workload envelope, and client obligations.
- [Naming ledger](docs/naming.md) — durable naming verdicts and rejected alternatives.
- [Node FRAMRPC client](clients/node/README.md) — the complete direct builder and application data plane.
- [Isolation and deployment](docs/isolation-and-deployment.md) — trust domains, the three deployment shapes, and the wasm embed contract.
- [Tool catalog](docs/tool-catalog.md) — exactly five public MCP data verbs.

The rationale and positioning essays, plus the old pull, claims, and Codegraph documents, live under
[`docs/archive/`](docs/archive/README.md). Each one carries a `HISTORICAL`
banner, is retained as design evidence only, and is never a recursive-kernel
runtime reference.

## Quickstart

The checkout runtime needs Babashka for the CLI and Clojure/JVM for the server.
Beagle is needed only when rebuilding graph-authored source; compiled Clojure is
committed under `out/`.

```console
$ git clone https://github.com/Autonymy/fram && cd fram
$ export FRAM_SPACE_ID=fram-demo
$ export FRAM_LOG=/tmp/fram-demo.framlog
$ export FRAM_SERVER_RUNTIME=jvm-dev  # explicit checkout fallback
$ bin/fram-up
$ bin/fram tell :email :grouped-under :contact
$ bin/fram tell Alice :email alice@example.com
$ bin/fram show Alice
$ bin/fram query '{:find "emails" :rules [{:head {:rel "emails" :args [{:var "who"} {:var "email"}]} :body [{:rel "triple" :args [{:var "who"} :email {:var "email"}]}]}]}'
$ bin/fram occurrences
$ bin/fram validate
```

Bare `Alice` is local CLI shorthand for the String `"@Alice"`; keywords,
numbers, recursive three-element vectors, and `{:instant [seconds nanos]}` are
lowered to Terms before the socket opens. EDN is only human CLI syntax. The live
engine wire is binary FRAMRPC.

## Runtime surfaces

- `bin/fram-server` is the native-first server launcher. Its default route
  requires `FRAM_NATIVE_ARTIFACT_DIR` to name a READY artifact containing
  `bin/fram-server-native`; it never falls back silently. `jvm-oracle` and
  `jvm-dev` are explicit retained routes. The launched server owns one database
  (`SpaceId` plus `history.framlog`), accepts the closed thirteen-operation
  FRAMRPC v1 set, and holds writer authority for its active lifetime.
- `bin/fram` routes public data commands (`tell`, `retract`, `show`, `query`,
  `scan`, `occurrences`, `version`, `status`, and `validate`) over FRAMRPC.
  Explicit local migration/projection/admin commands are separate from that
  wire path.
- `bin/fram-mcp` is a JSON-RPC-over-stdio edge with exactly five public data
  tools: `tell`, `retract`, `show`, `ask`, and `validate`. Graph authoring and
  deployment control are separate sealed services.
- `clients/node/framrpc.mjs` is the official zero-dependency Node client for
  direct builder and application traffic. It preserves recursive Terms,
  batches, versions, occurrence replay, paging/cursors, snapshot selectors,
  and leases across all thirteen FRAMRPC v1 operations.
- The Cloudflare shim accepts closed JSON with tagged recursive Terms and lowers
  it to FRAMRPC. It does not accept EDN or an untyped escape hatch.
- The engine also links as a library: `native/fram.h` publishes embedding ABI
  v1 (`libfram.a`, `libfram.so`), and `--host wasm-embed` links the same ABI
  into a wasm32 module an isolate embeds with no server and no socket. Both
  take one canonical FRAMRPC v1 frame in and give one out, and the wasm engine
  answers byte-for-byte what the native library answers. See
  [isolation and deployment](docs/isolation-and-deployment.md#the-wasm-embed-contract).

## Why own the engine?

Fram's differentiator is not “a triple plus an id.” It is the uniform recursive
term model: a Triple is itself a Term, so a relationship, an identity
coordinate, an assertion occurrence, and metadata can all use the same three
positions without a privileged attribute position or bolt-on statement entity.

The storage implementation interns Atoms and Triples and keeps compact
`TripleRow`/operation tables, but those handles are deliberately not semantic
identity. Querying is position-neutral and history remains addressable after a
withdrawal. The exact executable contracts live in
[`tests/triple_kernel_test.clj`](tests/triple_kernel_test.clj),
[`tests/database_test.clj`](tests/database_test.clj), and
[`tests/triple_query_test.clj`](tests/triple_query_test.clj).

Fram is pre-1.0. There is no engine access control: isolate by process, network,
SpaceId, and FRAMLOG, and put authenticated public edges in front. The
concurrency receipts cover one machine and one writer; they are not distributed
consensus.

## License

Fram is dual-licensed under your choice of the [MIT License](LICENSE-MIT) or
the [Apache License, Version 2.0](LICENSE-APACHE)
(`MIT OR Apache-2.0`).
