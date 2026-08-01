# Semantic hints and similarity

**Status:** Current modeling guidance.

Similarity is a retrieval aid, not a kernel primitive. Use three layers in this
order.

## 1. Prefer explicit shared structure

Two things are often related because both reference the same domain object,
topic, region, or source. Give that shared object a stable Term and relate both
items to it with ordinary Triples. Datalog can then derive relatedness by a
deterministic join.

That structure is more explainable and durable than a pairwise similarity
score. If an explicit hub describes the domain honestly, model the hub first.

## 2. Represent a genuine hint as a proposition

Some affinity is not co-reference. In that case, make the suggested relation an
ordinary proposition and attach provenance to its assertion occurrence:

```text
hint := (left, :semantic/similar-to, right)
(op, :kernel/asserts, hint)
(op, :semantic/score, 0.86)
(op, :semantic/model, model-run)
```

The score and model run describe one assertion occurrence, not all equal
proposition content forever. A later model can assert another occurrence or
retract the earlier one without merging the two domain objects.

Never hard-merge identities solely because a model reports similarity. A bad
hint is retractable; a mistaken identity merge is much harder to repair.

## 3. Keep embeddings in a rebuildable index

Vector indexes find likely entry points and map them back to Fram Terms. They
are derived retrieval state: disposable, versioned against their source
snapshot, and rebuildable. The FRAMLOG remains the durable assertion history;
the vector index does not become another source of truth.

A strict query can ignore hint propositions. A semantic exploration can select
assertions from approved model runs above a chosen threshold. That policy lives
in the query or serving projection rather than in `slot0`, `slot1`, or `slot2`.
