# Semantic hints — where similarity lives in a fact graph

The question, as an integrator asked it: *"do you see vector embeddings / similarity
search as an important aspect of Fram traversal?"* — should an agent exploring the graph
follow semantic paths, not just deterministic ones? The answer is three rules, in
priority order.

## 1. Hubs before pairwise similarity

Most "semantically related" pairs are related *because they reference the same thing*.
Canonicalize that thing — the entity, the topic, the region — as a node, point both
sides at it, and relatedness becomes a **derivable join** ("what shares this hub?"),
not a guess. A similarity score between two leaf nodes is usually a symptom of a
missing hub. Build the hub first; most of the mesh you wanted falls out of Datalog.

## 2. When a hub can't capture it, the hint is a fact

Some affinity genuinely isn't co-reference — a keynote that *reads like* a schedule
entry, two notes that describe the same obligation in different words. Record that as
an ordinary fact: a `similar-to` edge carrying its **score** and its **provenance**
(the asserter is the embedding model / matcher run that proposed it, tied to the
generation it ran against). Then let **views** decide: a traversal that wants semantic
exploration runs under a view that selects hint edges above a threshold; a strict
traversal runs under one that ignores them. Hints obey the same epistemics as
everything else in the store — asserted, defeasible, supersedable when re-embedding —
because they are assertions, and the graph already knows what to do with assertions.

The corollary that matters operationally: **never hard-merge two nodes because a model
says they match.** Assert the edge. A wrong edge is one supersession from fixed; a
wrong merge is nearly unrecoverable.

## 3. The embedding index stays outside the graph

Vector indexes are **rebuildable retrieval**, not truth: they find *entry points*
(which node does this question start at?), and they bind results back to node
identities. Keep the index beside the graph, derived from it, disposable — the
Vectorize-style pattern. The graph owns traversal; the index owns recall. An index you
can delete and rebuild never needs provenance, migration, or supersession — which is
exactly why it shouldn't live in the log.

## Summary

Deterministic structure first, hubs second, hint-facts third, and embeddings as an
external index that points *into* the graph rather than living in it. Semantic
exploration is then a **view choice at query time**, not an architectural commitment —
which is the fact-graph answer to most questions: record the assertion with its
provenance, and let a view decide what to trust.
