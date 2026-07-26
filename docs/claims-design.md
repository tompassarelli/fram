# fram.claims — assertion under verification

**Status: design draft, spec-first.** The contract is already executable:
[`tests/claims_spec_test.clj`](../tests/claims_spec_test.clj) — 90 bars, 13 green
(the substrate self-check) and 77 red by absence. The module does not exist yet;
the bars define it. Run it from the repo root:

```
bb -cp out tests/claims_spec_test.clj
```

## The thesis

**A claim is an ordinary fact plus participation in a verification discipline
expressed as more facts.**

There is no claim atom, no marker predicate, and — this is the headline — **no
engine change**. The module is a predicate vocabulary, a handful of Datalog
rules, and the views mechanics the engine already has: `(view selects @cid)`
([VIEWS_AND_BRANCHES §8](VIEWS_AND_BRANCHES.md), `coord.clj` `select!`). Section 0
of the spec drives the whole lifecycle — claim, cite, verify, un-verify, dispute,
re-verify, across a two-version world — through ops that exist *today*, and it
passes today. If section 0 is green and everything else is red, the only missing
thing is the module.

## Four ratified decisions

1. **Scoped claim vocabulary.** "Claim" is legal only inside this module's
   namespace. The substrate atom stays a *fact*; the ratchet enforces the border.
2. **Derived status.** `pending / verified / rejected / superseded` are computed
   from view membership and liveness. No status is ever stored — the words
   `verified`, `pending`, `rejected` are never even interned as values.
3. **Rejection is a view convention.** A rejection is a selection into the
   rejected-view family plus a reason fact. No new engine primitive.
4. **In-repo, worlds-optional.** `fram.claims` ships in this repo beside
   `fram.world`, and works with no world in sight. `evidence.world` is the one
   optional predicate; omit it and everything except the transition rule is
   byte-identical.

## The vocabulary

Predicates are dotted lowercase in the `claim` / `evidence` namespaces, matching
`world.head` / `world.sealed`.

| name | subject → object | meaning |
| --- | --- | --- |
| `claim.evidence` | claim fact **cid** → evidence node | this claim cites that evidence |
| `claim.reason` | claim fact **cid** → text | why a verdict went the way it did |
| `evidence.source` | evidence node → artifact id | which file/artifact the evidence came from |
| `evidence.region` | evidence node → app locator | where in it (span, region coords — app-defined, opaque here) |
| `evidence.fingerprint` | evidence node → content hash | what the cited content *was* |
| `evidence.world` | evidence node → VersionId | which world version it was extracted against (**optional**) |
| `@view:claim.verified` | view → claim cid | the verified-view family root |
| `@view:claim.rejected` | view → claim cid | the rejected-view family root |

A view name matches itself and its `:`-scoped children, so
`@view:claim.verified:alice` is a member of the verified family. That is how
provenance comes for free: a selection fact's **writing agent** is the selecting
view's subject, so a verifier-scoped verdict view records the verifier with no
extra schema and no extra write.

Derived states, in precedence order:

- **superseded** — the claim fact itself is not live (existing SUP mechanics, unchanged).
- **verified / rejected** — the live verdict selection, whichever view family selects the cid.
- **pending** — evidence exists, no verdict. Derived, never stored.
- **nil** — no evidence and no verdict: an ordinary fact, not a claim. *Claimhood is
  a consequence of participation, not a flag.*

Un-verifying is superseding the *selection*, not the claim: status flips back to
`pending`, the claim fact and its evidence untouched, the withdrawn verdict still
in the log.

### The transition rule

The crown jewel, and the reason `evidence.world` exists. Given world versions
A → B:

> **verified claims needing re-verification** = verified claims citing evidence
> extracted against A whose cited slot does not resolve identically at B (edited,
> or deleted).

One Datalog rule over `claim.evidence` + the verdict selections, with the slots
that changed between A and B entering as rule constants (a manifest diff — a
world read, not a graph query). Directional: B → A yields nothing, because no
evidence was extracted against B. Worlds-optional falls straight out — evidence
with no `evidence.world` never participates, so a store that never called a world
verb gets an empty answer instead of a false alarm.

## Mapping: Plangrep's production layer → fram.claims

| his piece | fram.claims equivalent | stays app-side (+ why) |
| --- | --- | --- |
| states pending / verified / rejected / superseded | derived status (view membership + liveness) | — |
| `ClaimDraft` | an ordinary fact + `claim.evidence` edges | draft *authoring* UX |
| `ClaimEvidenceReference` (fingerprints, citations, source spans / region coords) | evidence node: `evidence.source` / `.region` / `.fingerprint` / `.world` | the *locator format* — the region string is opaque to the module |
| `submitProjectClaims` | assert claim facts + evidence edges (one batch) | batching policy, retries, request shape |
| `finalizeProjectClaim` | a verified-view selection (one write) | who may finalize, and when |
| `verifiedClaimsForTransition` | the transition rule | — |
| `materializeGeneration` | — | generation/materialization is a pipeline stage, not a model |
| `compileEvidenceFrame` | — | frame assembly is extraction machinery |
| `claimVerificationMaterial` | — | what a human verifier is *shown* is a rendering concern |
| claim ledger table | — (delete it: the facts are the ledger) | — |
| verification queue table | — | **machinery**: ordering, assignment, SLAs, retry — work scheduling, not truth |
| outbox table | — | **machinery**: delivery semantics belong to the transport |
| citation policy (how many, how strong) | — | **policy**: a product decision, and it changes faster than a substrate |

The line: **model in the graph, machinery in the app.** If it answers "what is
claimed, on what evidence, judged by whom, and what does a world change
invalidate" it belongs here. If it answers "who works on it next, and did the
message get delivered" it does not.

Success criterion, and the only one:

> *The integrator can delete their claim tables and keep their verification
> queue.*

## The one integration seam

Evidence edges and reason facts hang off a **fact cid** as subject. The store has
always allowed this — cids are first-class subjects in one flat id space
(VIEWS §8) and `retract!` already writes `withdrawn_by` on a victim cid — but
`coord.clj`'s public entry points (`commit!`, `retract!`) are *name*-oriented and
cannot name a cid. So an app writes a fact-about-a-fact the way `retract!` does
internally: `fram.schema/link!` (or `assert!`) with the cid as subject, inside one
coord tx. The spec's fixture does exactly that, in ~10 lines.

If that gets tedious in production, the fix is a generic coordinator verb
(subject-is-a-cid `about!`) — app-generic plumbing, *not* claims vocabulary and
not an engine change. It is deliberately out of this module's scope. Same story
for un-verifying: superseding one selection fact is a public store write today,
just not reachable from `retract!`'s signature.

## The treaty

> **Claims are verified against facts.**

The substrate atom is a **fact** — an immutable `(subject predicate object)`
triple recording what was *asserted*, not what is true
([docs/naming.md](naming.md)). "Claim" is not banished; it is *reserved*, and this
is the reservation being spent: the right name for an assertion-under-verification
lifecycle, and the wrong name for the stored triple. The word therefore lives only
in this module's namespace and its own predicate names — `fram.claims`,
`claim.evidence`, `@view:claim.verified` — and never leaks into the store, the
engine, the MCP surface or the docs' vocabulary for what the graph holds.
`tests/vocab_ratchet_test.sh` is the border guard: any file may carry at most its
baselined count of `claim` lines, and a file not in the baseline must carry none,
so the vocabulary cannot regrow by accident. This document and the spec suite were
admitted to that baseline deliberately, in the commit that introduced them.

## Revise by PR

This is a draft on purpose. It was written from first principles plus one
integrator's implementation notes — not from running a verification pipeline at
scale. **Production experience outranks first principles here.** Where the model
is wrong, the bars are the place to argue: change a bar in
`tests/claims_spec_test.clj`, say why in the PR, and the design follows the
contract. Pull requests welcome, including ones that delete something.
