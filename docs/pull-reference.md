# Pull reference — nested reads over the graph

The daemon speaks a `{:op :pull}` wire query alongside `ask`. Name a root entity
and a declarative pattern, get back a nested map — no rule-writing for "give me
this thing and its dependencies' titles":

```edn
{:op :pull :root "@x" :pattern [{"depends_on" ["title"]} :* "_part_of"] :provenance true}
```

**Limit.** `pull` is daemon-only, because it needs the live index. The cold CLI
fold does not serve it.

## Pattern grammar

Per element:

| Element | Meaning |
|---|---|
| `"pred"` | flat attribute |
| `:*` | all non-reserved attributes on this node, refs rendered as name strings — no recursion |
| `{"pred" [sub-pattern]}` | recurse into a ref with a sub-pattern |
| `{"pred" N}` | recurse N levels deep |
| `{"pred" :...}` | recurse until a cap or a cycle |
| `"_pred"` | reverse ref — subjects pointing *at* this node via `pred` |

Rendering is cardinality-driven: a single-valued predicate comes back scalar, a
multi-valued one comes back a vector.

## Provenance

```edn
;; @x depends_on @dep1 ; pull nested title + per-value provenance
(pull/run store "@x" [{"depends_on" ["title"]} "status"] {:provenance true})
;; => {:fram/id "@x"
;;     "depends_on" [{:fram/id "@dep1" "title" "Design"}]
;;     "status" {:val "open" :cid 2 :by "u" :seq 2 :withdrawn false
;;               :ts "2026-07-21T04:39:34.924Z"}}
```

`:provenance true` turns each value into `{:val :cid :by :seq :withdrawn :ts}`,
plus `:withdrawn_by` / `:withdrawn_at` / `:withdrawn_reason` when withdrawn.
Per-value provenance is possible in the first place because every stored triple
is itself an addressable, reifiable entity rather than a row.

`:ts` is the asserting transaction's wall-clock instant, stamped once at commit
and identical in the log and after replay. It is display metadata only: `:as-of`
stays addressed by causal `:seq`, and the key is omitted for transactions whose
log records predate it.

## Historical reads and traversal caps

`:as-of <seq>` composes with `:provenance` to read a historical snapshot.

`:max-depth` and `:max-nodes` cap traversal. A node beyond either cap comes back
as a `{:fram/truncated true}` stub, and a cycle revisit on the current path comes
back as `{:fram/cycle true}`. Both keep `pull` total — it never hangs and never
throws — instead of erroring.
