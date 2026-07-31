# The tool catalog — closed, O(1), vocabulary-as-data

The primary query author is a model, so the surface is tuned for what a model
emits correctly with zero examples: a **closed, O(1) tool catalog** plus a
structured query escape hatch. The catalog is a fixed twelve tools. It is never
minted per-predicate, because the vocabulary is **data in the graph**, not tools.

Run `bin/fram tools` for the live catalog — count and signatures. That command is
the source of truth; the summary below is not.

## The closed TELL/ASK catalog — exactly twelve tools

`tell` (assert a triple) · `retract` (remove one) · `show` (everything on a
subject) · `ask` (structured query) · `validate`, plus the seven code-authoring
verbs the resolver adds: `add-def` · `set-body` · `rename-def` · `insert-after` ·
`insert-before` · `replace-in-body` · `edit-transaction`.

A single-valued predicate replaces its value; a multi-valued one accumulates —
and **cardinality is itself a fact** (`tell <pred> cardinality single|multi`), so
`tell` = assert subsumes the older `set-P` / `add-P` pairs with no per-predicate
tools.

Predicates are entities. `show <pred>` reveals a predicate's `cardinality` /
`value_kind` / `acyclic` facts, and `ask` enumerates the vocabulary through the
`predicate` base relation. The tool count stays O(1) while the vocabulary lives
in the graph as data.

A missing required parameter is **rejected server-side**.

## `ask` — a structured Datalog escape hatch

For multi-hop questions no read covers. The model emits **data**, not text — the
shape *is* the engine's internal rule data — so the only added layer is total
validation at the boundary: a query cannot parse-fail, reference an undefined
relation, leave a head variable unbound, or smuggle in unstratified negation.
Same fixpoint as everything else (recursion plus stratified negation), no
query-library dependency. Full surface: [query-reference.md](query-reference.md).

```sh
bin/fram tools            # the closed catalog (count + signatures)
bin/fram query '{:find "po" :rules [{:head {:rel "po" :args [{:var "x"} {:var "y"}]}
                                     :body [{:rel "fact" :args [{:var "x"} "part_of" {:var "y"}]}]}]}'
```

## Why closed, and not generated

A large generated per-predicate catalog is a per-session context tax that buys no
safety the engine does not already provide:

- every write is serialized and rule-checked at the coordinator
  ([concurrency-and-writes.md](concurrency-and-writes.md));
- single-vs-multi cardinality is a fact in the log, so a cold CLI fold and the
  warm daemon classify identically.

So the surface stays closed, and the vocabulary is reached through `show` and
`ask` rather than through the tool list.

## Transports

The catalog is served over **MCP** by `bin/fram-mcp` (JSON-RPC over stdio). The
CLI — `bin/fram tools`, `bin/fram call <tool> <edn>`, `bin/fram query <edn>` — is
the same surface for humans. `tools/call` accepts `untell` as an alias for
`retract`, and `query` for `ask`.
