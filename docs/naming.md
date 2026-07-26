# The naming ledger

Why load-bearing names in Fram are what they are — recorded so no fight has to be
re-fought. Devlog style: dated entries, appended when a name earns (or defends) its
place. The ledger is append-only, like everything else here.

## The rule

Fram's primary readers include LLMs. A repository is effectively a system prompt for
every consumer's AI — whatever vocabulary the committed files use is the vocabulary
that comes back out of the next model that reads them. So names are chosen by **prior
alignment**: pick the word whose *strongest existing prior* matches the semantics, so a
model (or a human) reaching for the word gets it right with zero instruction.

A name that needs a paragraph of correction is a bug. The correction loses to the
prior eventually — every time.

## fact — chosen 2026-06, reaffirmed the hard way 2026-07-26

The substrate atom is a **fact**: an immutable, addressable `(subject predicate object)`
triple. Chosen over "claim" for the Datalog / Datomic / Prolog prior — in every
logic-programming tradition the stored tuple has always been a *fact*, so a model
writing Datalog against Fram lands on the right word unprompted.

The epistemics live in the docs, not the name: a Fram fact records what was
**asserted**, not what is verified. It can be wrong, disputed, and coexist with its
rival. Nothing is silently overwritten; supersession retires without deleting.

The fun-fact that reaffirmed it: for a while the repo still carried leftover "claim"
vocabulary — a dead prompt file, some old chartroom headlines. An external integrator's
AI read the repo, absorbed the residue, and re-coined an entire "claims" layer in its
own product. Live proof of the rule above, run in reverse: the one instructional
document still teaching the old word is exactly what came back out. The residue is gone
now, and `tests/vocab_ratchet_test.sh` keeps it gone — new claim-vocabulary can't enter
the tree; removals always pass.

"Claim" isn't banished — it's *reserved*. It is the right name for an app-layer
assertion-under-verification lifecycle (a plausible future optional module), and the
wrong name for the substrate atom. One sentence holds the treaty: **claims are verified
against facts.**

(Pleasant recursion: this document necessarily contains the word "claim" — it records
the fight — so the ratchet baseline had to be extended to admit the doc that explains
the ratchet.)

## world — chosen 2026-07-26

The primitive: a named, forkable lineage of immutable versions. A version fixes *which
facts are in scope* — the thing you evaluate queries "at." It deliberately does **not**
guarantee those facts agree with each other: rival assertions coexist inside a world by
design. A world fixes scope, not harmony.

Chosen for the **possible-worlds prior** (modal logic, Kripke semantics): a world is "a
way things could stand, at which propositions are evaluated" — precisely what a version
does for facts. Best prior alignment available; a model gets "fork a world" and
"evaluated at this world" right with no instruction.

The rejected bench, one sentence each:

- **consistent-plane** — asserts a property the primitive deliberately lacks (worlds
  may contain contradictions), and compound names lose the naming war before it starts.
- **plane** — control-plane/data-plane owns that word for exactly the infrastructure
  audience integrating Fram.
- **reality** — claims truth; a world can be entirely wrong and still be a perfectly
  good world. Same honesty rule that kept "fact" from meaning "true."
- **universe** — claims totality; a version is deliberately partial (a sparse overlay
  over a base).
- **branch** — mechanics-true (fork, head, diverge) but semantically empty — a branch
  of *what?* — and collides with git in a system that lives beside git.

Practical tie-breakers, all of which "world" wins: it compounds cleanly (`world.head`,
`world.sealed`, `fork-head`), pluralizes naturally ("the two worlds diverged"), and
verbs naturally ("fork a world"). Try `reality.sealed` with a straight face.

## Appending an entry

When a name fight happens, record: what the thing *is* (its actual semantics, including
what it deliberately does not promise), the prior that decided it, the rejected bench
with one honest sentence each, and the date. Future readers — human and model — inherit
the verdict instead of the argument.
