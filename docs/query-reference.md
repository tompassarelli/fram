# Query reference

The query surface is structured data, not text: a query is an EDN (or JSON, over
MCP) map that is validated in full at the boundary before anything runs. A query
cannot parse-fail halfway, reference an undefined relation, leave a head variable
unbound, or smuggle in unstratified negation — those are rejections, not runtime
errors. Evaluation is a stratified Datalog fixpoint with recursion and stratified
negation, with no query-library dependency.

Reach it from the CLI with `bin/fram query <edn>`, or as the `ask` tool over MCP.

## Base relations

| Relation | Arity | Binds |
|---|---|---|
| `fact(l, p, r)` | 3 | the three slots of a triple |
| `fact-id(cid, l, p, r)` | 4 | the triple's own address plus its three slots |
| `predicate(pid, spelling, canonical, cardinality, value-kind)` | 5 | the vocabulary, as data |

`triple` is accepted as an alias for `fact`, so older queries keep running.
`predicate` is additive — one row per canonical spelling or alias — and legacy
predicates without registry facts receive their implicit `@<name>` identity.
`fact` and `fact-id` remain byte-for-byte projections of the store.

## Aggregates — grouped counts and sums over the fixpoint

`:find` can name a **grouped aggregate spec** instead of a bare relation name. It
is applied *after* the Datalog fixpoint, so it composes with recursion and
stratified negation for free rather than having to re-derive them.

```edn
;; deg(x,y) :- fact(x,"depends_on",y) ; count out-edges per subject
{:find {:rel "deg" :group [0] :agg [{:op :count}]}
 :rules [{:head {:rel "deg" :args [{:var "x"} {:var "y"}]}
          :body [{:rel "fact" :args [{:var "x"} "depends_on" {:var "y"}]}]}]}
;; => {:ok [["@a" 2] ["@b" 1]]}
```

```edn
;; reaches = transitive closure of depends_on ; distinct reachable targets per source
{:find {:rel "reaches" :group [0] :agg [{:op :count-distinct :arg 1}]}
 :rules [...]}
;; => {:ok [["@a" 3] ["@b" 2] ["@c" 1]]}
```

Ops: `:count` (`:arg` optional), `:count-distinct`, `:sum`, `:avg`, `:min`,
`:max` (the last four take `:arg`, the group position to aggregate).

- `:group []` is one global group — a single row, no group columns.
- `count` over an empty relation is `[]`, not a zero row.
- `sum` / `avg` / `min` / `max` parse string values numerically. `sum` stays
  integer when every value is a long; `avg` is always a double.
- A non-numeric value at the aggregated position is a hard `{:error}` naming the
  position.
- Result rows are sorted canonically; the output group count is capped by
  `FRAM_MAX_RESULTS`.

### `:having` — filtering grouped rows

```edn
;; subjects with more than 5 out-edges
{:find {:rel "deg" :group [0] :agg [{:op :count}]
        :having [{:op :gt :agg 0 :val 5}]}
 :rules [...]}
```

`:having` is a vector of clauses, ANDed. A clause is
`{:op <op> :agg <i> :val <n>}`:

- the operand `{:agg i}` addresses the *i-th* `:agg` entry — layout-independent,
  not a column position;
- `:op` is one of `:eq :ne :lt :le :gt :ge`, all **numeric** here (aggregate
  outputs are always numbers, so `:eq` on `:avg` is fragile);
- `:val` is a required number.

`:having []` filters nothing; a clause set that excludes every group yields
`{:ok []}`. The cap fires **post-having** — `FRAM_MAX_RESULTS` bounds the
*survivor* count, so a query with a huge group set trimmed to a few by `:having`
returns `{:ok}`.

**Limits.** An aggregate result cannot feed back into a rule body, and an
aggregate `:find` is not pageable (`run-page` rejects it).

## Comparison predicates — filter only, never bind

A rule body can carry a filter literal alongside its `:rel` clauses:

```edn
;; big(x) :- fact(x,"count",c), c > 100
{:find "big"
 :rules [{:head {:rel "big" :args [{:var "x"}]}
          :body [{:rel "fact" :args [{:var "x"} "count" {:var "c"}]}
                 {:pred :gt :args [{:var "c"} 100]}]}]}
```

Ops: `:eq` and `:ne` are raw string equality and disequality; `:lt` `:le` `:gt`
`:ge` are numeric ordering, and a non-numeric operand silently drops the row
rather than erroring.

A predicate **never binds a variable**. Every var it reads must already be bound
by an earlier `:rel` clause in the same body — the same range-restriction rule
negation follows.

## Arithmetic fn clauses — compute and bind

A body can also carry an arithmetic **fn clause** that computes a value and binds
it to a fresh variable:

```edn
;; h = c + s  (then usable by later clauses / the head)
{:fn :+ :args [{:var "c"} {:var "s"}] :bind "h"}
```

Ops: `:+` `:-` `:*` `:/` `:mod`, all binary. Both args must be already-bound vars
or numeric constants.

The result is bound as a **canonical string**, keeping the query database
all-String: `8` → `"8"`, `3.5` → `"3.5"`, `8.0` → `"8.0"`. Integer preservation
mirrors `:sum` — `:+ :- :*` stay long when both operands parse as longs, else
fall to double; **`:/` is always double**.

Any failure — a non-numeric operand, division or modulus by zero, or `:mod` of a
non-long — **drops the row**, never errors, exactly like a predicate filter.

The fresh `:bind` var **counts as a binding** for later `:pred` / `:neg` clauses
and for head vars; its `:args` vars do not.

A fn clause is **forbidden in a recursive rule** — a rule whose head relation
reaches itself. Fn-introduced values could grow without bound through the
fixpoint, so this is rejected at validation to preserve termination.
