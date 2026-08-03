# Query reference

**Status:** Current source-head and transaction-sequence query contract.

A query is structured data compiled into a closed typed plan. The CLI accepts
one EDN map; JSON adapters accept the equivalent JSON shape. Both lower Terms,
variables, rules, and controls before FRAMRPC is written. The evaluator never
parses query text.

Use `bin/fram query '<edn>'` from the CLI or the public MCP `ask` tool.

## Terms and variables

A constant can be any Fram Term:

```text
Atom   := String | Int | Float | Bool | Keyword | Instant
Term   := Atom | Triple
Triple := (Term, Term, Term)
```

In local EDN, a recursive Triple is a three-element vector and an Instant is
`{:instant [epoch-seconds nanos]}`. A variable is `{:var "name"}`. Constants
retain their types; numbers are not coerced into strings.

## Base relations

There are two materialized kernel base relations and one positive virtual
relation:

```text
triple(slot0, slot1, slot2)
occurrence(coordinate, action, proposition)
text-match(entity, attribute, needle)
```

| Relation | Arity | Rows |
|---|---:|---|
| `triple` | 3 | live proposition `slot0`, `slot1`, `slot2` |
| `occurrence` | 3 | occurrence coordinate, action, proposition |
| `text-match` | 3 | entity and attribute whose live string value contains every token in `needle` |

Every cell is a Term. A recursive Triple can therefore be matched in any
position. Ordinary current-state queries use `triple`. `occurrence` is an
explicit history projection; it is not an implicit fourth column on `triple`.

There are no `fact`, `fact-id`, `predicate`, or row-handle compatibility base
relations in the recursive query kernel.

## Full-text word match

`text-match` searches the third slot of live propositions whose value is a
String. The needle must be a string constant or a string variable bound by an
earlier positive clause. The relation is positive-only; `:neg true`, an
unbound needle variable, and an empty or punctuation-only needle are rejected.

```edn
{:find "matching-title"
 :rules
 [{:head {:rel "matching-title" :args [{:var "entity"}]}
   :body [{:rel "text-match"
           :args [{:var "entity"} :title "Quick FOX"]}]}]}
```

Tokenizer v0 takes maximal Unicode Letter/DecimalDigit runs, applies
locale-independent lowercase, and treats punctuation, whitespace, `_`, and
`-` as delimiters. Numeric tokens remain searchable. Repeated query tokens are
deduplicated, and multiple tokens are an unordered conjunction. There is no
stemming, ranking, scoring, or substring match.

Each immutable snapshot has an inverted token-to-triple-handle index. The
coordinator builds it lazily, single-flights concurrent cold readers, and
caches exact `(daemon generation, SpaceId, version)` entries. The LRU holds at
most four versions and 64 MiB; a single index over that budget fails with
`:query-text-index-limit`. Version identity is the invalidation rule—there is
no TTL or scan fallback.

## Smallest query

This derives every email relation from the live Triple projection:

```edn
{:find "emails"
 :rules
 [{:head {:rel "emails"
          :args [{:var "who"} {:var "email"}]}
   :body [{:rel "triple"
           :args [{:var "who"} :contact/email {:var "email"}]}]}]}
```

`:find` names a derived relation. `:rules` supplies one stratum. A rule has one
`:head` and an ordered vector of body clauses. Relation names are strings;
operation names such as `:gt` are keywords.

Use `:strata` instead of `:rules` when negation needs more than one stratum. A
query must provide exactly one of them.

## Recursive Terms and recursive rules

The same matcher handles a Triple constant in every slot:

```edn
{:find "members"
 :rules
 [{:head {:rel "members" :args [{:var "member"}]}
   :body [{:rel "triple"
           :args [[:team :key "ops"] :contains {:var "member"}]}]}]}
```

Rules may also recurse. Transitive reachability is two rules with the same
derived head:

```edn
{:find "reaches"
 :rules
 [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
   :body [{:rel "triple"
           :args [{:var "x"} :edge {:var "y"}]}]}
  {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
   :body [{:rel "triple"
           :args [{:var "x"} :edge {:var "y"}]}
          {:rel "reaches"
           :args [{:var "y"} {:var "z"}]}]}]}
```

## Occurrence history

The history relation has the same three-cell shape:

```edn
{:find "events"
 :rules
 [{:head {:rel "events"
          :args [{:var "where"} {:var "action"} {:var "value"}]}
   :body [{:rel "occurrence"
           :args [{:var "where"} {:var "action"} {:var "value"}]}]}]}
```

`where` is an occurrence-coordinate Triple, `action` is
`:kernel/asserts` or `:kernel/retracts`, and `value` is the proposition Triple.
The native query request carries exactly one view selector inside its
`:rpc/query` payload; FRAMRPC remains a closed 13-operation protocol:

```text
:query/current
:query/as-of U
:query/since L upper       upper := :query/current | :query/as-of U
```

`:query/current` pins the head sequence `U` at request start. `:query/as-of U`
reads state after transaction `U`, inclusive. In both views, `triple` is the
live state at `U` and `occurrence` contains rows whose transaction sequence is
at most `U`. `:query/since L upper` keeps the same state at the resolved upper
bound and restricts `occurrence` to the deterministic interval `(L,U]`.
Transaction sequence is the selector; wall-clock Instants remain metadata.

Negative, future, or reversed bounds fail as `:query-invalid-snapshot`.
Unavailable sealed history is retryable `:query/archive-unavailable`; history
removed by an explicit retention decision is non-retryable
`:query/snapshot-expired`. Completed epochs are retained indefinitely by
default.

## Negation

Set `:neg true` on a relation clause. Every variable read by a negated clause
must already be bound by a positive clause in that rule. A negated dependency
must point to an earlier stratum; unstratified negation is rejected during
compilation.

```edn
{:find "terminal"
 :strata
 [[{:head {:rel "outgoing" :args [{:var "node"}]}
    :body [{:rel "triple"
            :args [{:var "node"} :edge {:var "next"}]}]}]
  [{:head {:rel "terminal" :args [{:var "node"}]}
    :body [{:rel "triple"
            :args [{:var "prior"} :edge {:var "node"}]}
           {:rel "outgoing" :args [{:var "node"}] :neg true}]}]]}
```

## Comparisons and arithmetic

A comparison filters a row and never binds a variable:

```edn
{:pred :gt :args [{:var "count"} 100]}
```

Supported comparison operations are `:eq`, `:ne`, `:lt`, `:le`, `:gt`, and
`:ge`. Variables must already be bound. Equality uses Term equality; ordering
operations require numeric operands and drop a nonnumeric row.

An arithmetic clause binds one fresh variable:

```edn
{:fn :+ :args [{:var "count"} 1] :bind "next"}
```

Supported operations are `:+`, `:-`, `:*`, `:/`, and `:mod`. Inputs must be
bound variables or numeric constants. Invalid arithmetic drops the row.
Arithmetic clauses are rejected in recursive rules so a fixpoint cannot grow an
unbounded stream of computed values.

## Aggregates

An aggregate `:find` groups the completed relation:

```edn
{:find {:rel "degree"
        :group [0]
        :agg [{:op :count}]
        :having [{:op :gt :agg 0 :val 5}]}
 :rules
 [{:head {:rel "degree" :args [{:var "node"} {:var "next"}]}
   :body [{:rel "triple"
           :args [{:var "node"} :edge {:var "next"}]}]}]}
```

Supported aggregates are `:count`, `:count-distinct`, `:sum`, `:avg`, `:min`,
and `:max`. All except `:count` require `:arg`, the zero-based input position.
Numeric aggregates accept numeric Terms and reject a nonnumeric selected
position. `:having` clauses address aggregate entries by index. Aggregate finds
are not pageable.

## Validation, limits, and paging

Compilation rejects malformed terms, unknown relations, arity disagreement,
undefined derived relations, unbound head/filter/negation variables, invalid
strata, recursive arithmetic, and invalid `text-match` needles or polarity.
Execution has step, time, result-count, and wire-byte limits.

Nonaggregate results have deterministic Term ordering. Page cursors encode the
last row key, and the coordinator pins continuation reads to the same snapshot.
A cursor is opaque to clients and binds both the resolved upper sequence and
the lower-exclusive occurrence bound. The coordinator caches each complete
ordered result by daemon generation, SpaceId, resolved view, operation, and
canonical request digest; current and as-of selectors resolved to the same
view share an entry, while distinct `since` lower bounds do not. A continuation
slices that vector rather than rerunning the plan. Cache eviction changes
execution cost, not the pinned answer.

Historical state is reconstructed from the newest FRI2 checkpoint at or before
`U` whose `{SpaceId, canonical-prefix sha256, valid-bytes}` binding validates,
then by replaying only its transaction tail. Corrupt, stale, or missing derived
checkpoints fall back to canonical replay. Sealed epochs use the same exact
prefix binding through a fingerprinted binary range manifest.

The executable contract is
[`../tests/triple_query_test.clj`](../tests/triple_query_test.clj); native
lowering and paging are covered by
[`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj).
Full-text semantics, snapshot residency, differential agreement, and the
memory/latency bars are covered by `text_match_test.clj`,
`text_index_cache_test.clj`, `datalog_diff_test.clj`, and
`text_index_perf_test.clj` in the same directory.
