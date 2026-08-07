# Query reference

This document specifies the current structured Datalog plan, its relations, operators, temporal views, limits, and paging behavior.

The CLI accepts one EDN map and JSON adapters accept the equivalent JSON; both lower [Terms](glossary.md#semantic-kernel), variables, and controls before FRAMRPC. The evaluator never parses query text. Use `bin/fram query '<edn>'` or MCP `ask`.

## Values and relations

Constants are Terms. Local EDN represents a recursive Triple as a three-element vector and an Instant as `{:instant [epoch-seconds nanos]}`. A variable is `{:var "name"}`; constants retain type.

```text
triple(t1, t2, t3)
occurrence(coordinate, action, proposition)

text-match(entity, attribute, needle)
text-phrase(entity, attribute, needle)
text-substring(entity, attribute, needle)
text-stem(entity, attribute, needle)
text-search(entity, attribute, needle, score)
```

`triple` contains live propositions and `occurrence` exposes explicit history; both are materialized and position-neutral. The five text relations are positive virtual relations over live String values in the third position, and they speak the [EAV reading](ontology.md#profiles-and-anchoring) in their argument names because that reading is what a text search of a value assumes. Every cell is a Term. There are no `fact`, `fact-id`, `predicate`, or row-handle compatibility relations.

## Rules

This query derives every email relation:

```edn
{:find "emails"
 :rules
 [{:head {:rel "emails" :args [{:var "who"} {:var "email"}]}
   :body [{:rel "triple" :args [{:var "who"} :email {:var "email"}]}]}]}
```

`:find` names a derived relation. Supply exactly one of `:rules` (one stratum) or `:strata` (ordered strata). Each rule has one head and an ordered body; the evaluator does not reorder clauses.

Triple constants match in every position. Multiple rules with the same head recurse by semi-naive fixpoint:

```edn
{:find "reaches"
 :rules
 [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
   :body [{:rel "triple" :args [{:var "x"} :edge {:var "y"}]}]}
  {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
   :body [{:rel "triple" :args [{:var "x"} :edge {:var "y"}]}
          {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}]}
```

Set `:neg true` on a relation clause. All variables it reads must already be bound by positive clauses, and its dependency must be in an earlier stratum; unstratified negation is rejected.

## Text relations

All five examine the third position of live propositions and share one obligation set: the needle must be a String constant or an earlier-bound String variable, and negation, unbound needles, and empty or punctuation-only needles are rejected.

Tokenizer v0 takes maximal Unicode Letter/DecimalDigit runs, lowercases without locale, and treats punctuation, whitespace, `_`, and `-` as delimiters. Repeated tokens deduplicate.

- `text-match` — the tokens as an unordered conjunction; order-independent, and the released semantics are unchanged by everything below.
- `text-phrase` — the same tokens in order, across punctuation. `"quick brown fox"` matches where `"brown quick"` does not.
- `text-substring` — case-folded literal containment that keeps punctuation, so `OWN_FO` matches `quick-brown_fox`. Needles too short to index fall back to an exact scan.
- `text-stem` — English stemming, so one needle unifies inflected forms (`runs`, `runner`, `running` all stem to `run`). It does not change `text-match`.
- `text-search` — four arity: the fourth argument binds a score. Exact evidence outranks stem evidence, which outranks substring evidence.

One index serves all five. The analyzers behind phrase, substring, stem, and search realize on first use of their relation, so a `text-match` query pays neither their build time nor their bytes; a plan that names no text relation builds no index at all. An index build is bounded at 64 MiB and fails typed `:query-text-index-limit`.

Retention differs by route. The JVM server retains the index per immutable snapshot in a single-flight LRU keyed by server generation, SpaceId, and version, holding four versions; version identity replaces TTL, and there is no scan fallback. The native engine builds the source per query and relies on the ordered-result cache below for repeats.

## Occurrence history and views

The native query payload carries exactly one selector:

```text
:query/current
:query/as-of U
:query/since L upper       upper := :query/current | :query/as-of U
```

Current pins head sequence `U` at request start; as-of reads state after transaction `U`, inclusive. Both expose live `triple` state at `U` and occurrences through `U`. Since preserves that upper state while restricting rows to what happened in `(L,U]` — and it restricts **every** base relation, not only `occurrence`: the `triple` plan and the text relations filter their candidates by the same lower bound, so the three answer the same window. Transaction sequence is the selector; wall clock remains metadata.

Negative, future, or reversed bounds fail as `:query-invalid-snapshot`. Missing sealed history is retryable `:query/archive-unavailable`; history explicitly removed by retention is non-retryable `:query/snapshot-expired`. Completed epochs are retained by default.

## Predicates, arithmetic, and aggregates

A comparison filters without binding:

```edn
{:pred :gt :args [{:var "count"} 100]}
```

Operations are `:eq`, `:ne`, `:lt`, `:le`, `:gt`, and `:ge`. Inputs must be bound; equality uses Term equality, while ordering requires numbers and drops nonnumeric rows.

Arithmetic binds one fresh variable: `{:fn :+ :args [{:var "count"} 1] :bind "next"}`. Operations are `:+`, `:-`, `:*`, `:/`, and `:mod`; invalid arithmetic drops the row. Recursive rules may not contain arithmetic.

Aggregate finds group a completed relation:

```edn
{:find {:rel "degree" :group [0]
        :agg [{:op :count}]
        :having [{:op :gt :agg 0 :val 5}]}
 :rules
 [{:head {:rel "degree" :args [{:var "node"} {:var "next"}]}
   :body [{:rel "triple" :args [{:var "node"} :edge {:var "next"}]}]}]}
```

Supported aggregates are `:count`, `:count-distinct`, `:sum`, `:avg`, `:min`, and `:max`. All but count require zero-based input `:arg`; numeric aggregates reject nonnumeric selected values. `:having` addresses aggregate entries by index. Aggregate results are not pageable.

## Validation, limits, and paging

Compilation rejects malformed Terms, unknown relations, arity disagreement, undefined derived relations, unbound variables, invalid strata, recursive arithmetic, and invalid text-match use. Execution enforces step, time, result-count, and wire-byte budgets.

Nonaggregate rows have deterministic Term ordering. An opaque page cursor binds the last row, resolved upper sequence, and lower-exclusive occurrence bound. Continuations stay on the same immutable snapshot.

An unpaged reply is capped at 248 rows, derived from the codec's 256-deep Term budget less the response envelope, and refuses typed `:term-depth-exceeded` rather than building a response the encoder cannot represent. Paging is the escape and answers the same relation; page well under that bound.

The server caches a complete ordered result by server generation, SpaceId, resolved view, operation, and canonical request digest. Selector-equivalent current/as-of requests share an entry; different since lower bounds do not. Continuation slices the cached vector rather than rerunning the plan. Eviction changes cost, never the pinned answer.

Historical state uses the newest valid prefix-bound FRI2 checkpoint at or before `U`, then replays its tail. Corrupt or stale derived state falls back to canonical replay; sealed epochs use the same prefix proof through a fingerprinted range manifest.

The executable contract is [`../tests/triple_query_test.clj`](../tests/triple_query_test.clj); native lowering and paging are covered by [`../tests/native_rpc_server_test.clj`](../tests/native_rpc_server_test.clj). Text, cache, differential, and performance gates are the `text_match`, `text_index_cache`, `datalog_diff`, and `text_index_perf` tests in that directory.
