# Ontology

**Status:** the current semantic foundation. This document says what the
stored things *are* and which words mean what, so that nobody has to
re-derive it. The executable side of every statement here lives in
[`guarantees.md`](guarantees.md); naming decisions and their rejected
alternatives live in [`naming.md`](naming.md).

## The kernel holds exactly one hard opinion

Three slots. Each slot holds any Term — a typed Atom from a closed set, or
another Triple, recursively. Slots carry no imposed roles: subject/predicate/
object is a reading a domain brings, never kernel law, and query is
slot-neutral. History is occurrence-addressed: every assertion and every
withdrawal is an event with an exact logical coordinate, and nothing is ever
erased. That is the whole opinion. Everything else in this document is built
*on* it, and can be rebuilt differently without touching it.

The kernel never adjudicates truth. It holds no invariant that live
propositions are true, consistent, unique, or sensible beyond structure.
Fram is a propositional ledger with exact history, not a truth-maintenance
system — which is exactly why its promises are provable.

## Three words, three layers: content, act, status

The vocabulary follows a commitment gradient. Each word says something
objectively different about how you relate to the stored thing; none of them
may colonize the others' layers.

- **Triple** — the structure. The sentence-shape, before anyone says it.
- **Proposition** — a Triple in its content role: the bearer of a
  truth-value, attitude-free. A proposition implies *evaluability*, not
  evidence and not endorsement. It is what gets asserted, denied, believed,
  or doubted, and is none of those things itself.
- **Occurrence** — the *act*: a reified assertion event, carrying who
  (`:kernel/asserted-by`), when (`:kernel/recorded-at`), and where in
  logical history (its coordinate). An occurrence is what everyday language
  calls *making* a statement. The commitment lives here, not in the content.
- **Fact** — a *status* a domain confers on a proposition, never something
  the engine can mint. The word is legitimate exactly where assertions are
  performative — a coordination ledger whose rows constitute the state they
  describe ("this lease is held": writing it makes it so). It over-reaches
  wherever content describes the external world, because the world drifts
  out from under assertions; there, fact-hood is a defeasible status that
  needs maintenance — confirmed-at, source, revalidation — all of which are
  ordinary triples about occurrences, defined by the domain.

The historical vocabulary journey resolves without a winner: "claims" was
the right idea for the occurrence layer (a claim is an assertion event by an
agent — the kernel kept the mechanism and dropped the word), "fact" is the
right word for the coordination layer's performative rows, and
"proposition" is the right word for content. The old fights were one
layer's word being applied to the whole substrate.

## Nodes and edges are roles, not kinds

There is no intrinsic difference between an edge and a node. Occupying a
slot in some assertion confers a role relative to *that assertion*; the
recursion makes this literal — a Triple sitting in another Triple's slot is
an edge being spoken of as a node. Any term can be graph-structure in one
assertion and subject-matter in the next. Perspective, not essence.

## Positional and named are projections of the same information

A coordinate `(1, 2, 3)` and the assertions `x = 1`, `y = 2`, `z = 3` carry
the same information under two projections; the schema is the decoder that
maps positions to names. A positional record is compressed named relations,
and composition is nothing more than the record's entity appearing as a term
inside further assertions. Neither projection is the true one; profiles
(below) let a space declare which one it speaks.

## Meaning needs one anchoring assertion

A store of bare values means nothing — numbers alone do not say what they
are *for*. Interpretation is anchored by at least one relational assertion
that says how to read the rest. In Fram this bootstrap is concrete: a
space's profile declaration is itself a triple in the space
(`:space/*` vocabulary, per the space-profiles design), and the admission
gate always accepts profile vocabulary precisely so the anchoring assertion
can exist before the contract it declares begins to bind. The circularity is
real and it is closed deliberately, in one place.

## Paradigm is a profile, never a kernel fork

"Relational typed triples," freeform structures, positional records — these
are *disciplines a space declares*, validated above the kernel, each an
explicit contract with its own gates. The kernel underneath stays the one
thing every profile shares and every theorem is proved against. What a
domain builds with the three slots is its own; what the engine promises
about the three slots is everyone's.
