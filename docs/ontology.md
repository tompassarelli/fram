# Ontology

This document is the current modeling contract: it assigns roles to the [glossary vocabulary](glossary.md), supplies the one normalized example, and governs profiles and semantic hints.

## Kernel boundary

The kernel has one hard opinion: each of `t1`, `t2`, and `t3` accepts any Term, recursively, and history is occurrence-addressed. It does not adjudicate truth, consistency, uniqueness, cardinality, or domain sense. Those are projections and profile rules above the kernel.

Triple, proposition, occurrence, and fact occupy distinct layers. Structure is a Triple; statement content is a proposition; assertion or retraction is an occurrence; fact is a status conferred by a view. Assertions can be disputed or withdrawn without changing proposition content.

## Perspective and graph roles

An occurrence already records its asserter and logical time. Put a holder in proposition content only when that perspective is itself the subject matter: Alice asserting `(tom, :holds, (p, :category, :hobby))` records Alice at the act layer and Tom in content. Repeating the asserter inside content is denormalization; omitting a content-level holder when perspective is the subject falsely implies objectivity.

Nodes and edges are roles, not kinds. Any Term may be graph structure in one proposition and subject matter in another; a Triple nested in another Triple makes that shift explicit.

Positional and named records are projections of the same information. A schema maps `t1`/`t2`/`t3` to domain roles; named assertions expand those roles into separate propositions. Profiles may choose either discipline without forking the kernel.

## Normalization

Structure hidden in an Atom is structure the store cannot query. Namespaces, compound identifiers, and opaque structured strings imply joins that were never asserted. Domain vocabulary earns grouping through propositions, not spelling.

This is the sole canonical normalized example in current documentation:

```text
(:email, :grouped-under, :contact)
("Alice", :email, "alice@example.com")
```

Once grouping is asserted, `:email` is opaque; Keyword versus String carries type, not grouping semantics. Different punctuation does not repair hidden structure.

Recursive annotation avoids compulsory value reification:

```text
(("Alice", :email, "alice@example.com"), :verified-by, "mail-checker-1")
```

Give a value its own Term only when the domain gives it identity, not merely to attach metadata. Who and when already belong to the assertion occurrence.

The regress ends at the engine's documented `:kernel/*` occurrence vocabulary: the engine mints those predicates about its own acts. Closed wire tags are protocol syntax. Everything else earns structure by assertion.

## Profiles and anchoring

Meaning needs one relational anchor. A space opts into an optional profile with the primitive `:kernel/profile` proposition; the profile's remaining rules are ordinary stored propositions:

```text
(space-id, :kernel/profile, (profile-id, "relational", "observe"))
(profile-id, "includes", "R1")
...
(profile-id, "includes", "R4")
```

Only the anchoring proposition is bootstrap-exempt. Listing `R5` additionally requires a namespaced non-`:kernel/*` predicate to have an asserted grouping; omitting R5 preserves the space's prior verdicts. Current enforcement status is in [guarantees](guarantees.md#profiles).

## Semantic hints and similarity

Similarity is retrieval guidance, never identity or a kernel primitive:

1. Prefer explicit shared structure. Relate items to the same domain Term and derive relatedness by a deterministic join.
2. When affinity is genuine but not co-reference, assert it as an ordinary proposition and attach score and provenance to its occurrence.
3. Keep embeddings in a disposable index versioned to its source snapshot; FRAMLOG remains authoritative.

For example, after declaring `:similar-to` with the normalization rule above:

```text
hint := (left, :similar-to, right)
(op, :kernel/asserts, hint)
(op, :similarity-score, 0.86)
(op, :scored-by, model-run)
```

The score describes one occurrence, so another model may assert or retract independently. Never merge identities solely from similarity. Strict queries may ignore hints; exploratory projections may select approved model runs and thresholds without assigning semantics to any Triple position.

Naming verdicts and rejected alternatives remain in the [naming ledger](naming.md); executable behavior and gates remain in [guarantees](guarantees.md).
