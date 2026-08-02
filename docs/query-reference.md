# Query reference

**Status:** Current source-head query contract.

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

There are exactly two kernel base relations:

```text
triple(slot0, slot1, slot2)
occurrence(coordinate, action, proposition)
```

| Relation | Arity | Rows |
|---|---:|---|
| `triple` | 3 | live proposition `slot0`, `slot1`, `slot2` |
| `occurrence` | 3 | occurrence coordinate, action, proposition |

Every cell is a Term. A recursive Triple can therefore be matched in any
position. Ordinary current-state queries use `triple`. `occurrence` is an
explicit history projection; it is not an implicit fourth column on `triple`.

There are no `fact`, `fact-id`, `predicate`, or row-handle compatibility base
relations in the recursive query kernel.

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
The native query request may select a logical `as-of` sequence. Current-state is
the default.

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
strata, and recursive arithmetic. Execution has step, time, result-count, and
wire-byte limits.

Nonaggregate results have deterministic Term ordering. Page cursors encode the
last row key, and the coordinator pins continuation reads to the same snapshot.
A cursor is opaque to clients. The coordinator caches each complete ordered
result by daemon generation, SpaceId, snapshot version, operation, and canonical
request digest; a continuation slices that vector rather than rerunning the
plan. Cache eviction changes execution cost, not the pinned answer.

The executable contract is
[`../tests/triple_query_test.clj`](../tests/triple_query_test.clj); native
lowering and paging are covered by
[`../tests/native_rpc_daemon_test.clj`](../tests/native_rpc_daemon_test.clj).
