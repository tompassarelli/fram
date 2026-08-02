# The naming ledger

Why load-bearing names in Fram are what they are — recorded so no fight has to be
re-fought. Entries stay dated, but a superseded ruling is marked and reconciled in
place so obsolete vocabulary cannot masquerade as current doctrine; git preserves the
original wording.

## The rule

Fram's primary readers include LLMs. A repository is effectively a system prompt for
every consumer's AI — whatever vocabulary the committed files use is the vocabulary
that comes back out of the next model that reads them. So names are chosen by **prior
alignment**: pick the word whose *strongest existing prior* matches the semantics, so a
model (or a human) reaching for the word gets it right with zero instruction.

A name that needs a paragraph of correction is a bug. The correction loses to the
prior eventually — every time.

## fact — chosen 2026-06; superseded as a primitive 2026-08-01

The June ruling used **fact** for a stored `(subject predicate object)` record. The
recursive-Triple cut supersedes that ruling: **Triple is Fram's sole structural semantic
primitive**, its positions are neutral, and no stored `Fact` record or fact-as-synonym
for Triple belongs in the kernel vocabulary.

**Fact** remains valid only as a derived or query-level notion: a proposition represented
by a Triple that is present in a particular world or view under that projection's
rules. Datalog may therefore expose facts, and a world may select which facts are in
scope, without introducing a second stored thing. Storage and kernel APIs say `Term`,
`Triple`, and assertion occurrence; projections may say “fact” when they mean a
proposition currently admitted by the projection.

This boundary also keeps epistemics out of storage identity. A proposition can be
asserted, disputed, withdrawn, or selected by different worlds without changing what
its Triple is; each assertion occurrence is another ordinary Triple that relates an
occurrence coordinate to that proposition.

## world — chosen 2026-07-26

**Current scope:** historical Worlds-service vocabulary. The Worlds service has
moved out of the public recursive-kernel runtime, so `world` and `version` are
not kernel primitives or FRAMRPC data operations. The entry below preserves the
prior that governed that service and its retained design records.

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

## codegraph — chosen 2026-07-26

**Current scope:** historical experiment and sealed-consumer vocabulary, not a
public data primitive. The retained Codegraph code and receipts may project
recursive Triples for analysis, but they do not enlarge the five-tool MCP edge
or the thirteen-operation FRAMRPC boundary.

The thing: the code-intelligence surface. Point the engine at a Beagle source tree,
project it to facts, and *derive* the answers — scope-correct who-calls, transitive
blast radius, safe rename — as Datalog over a reference graph instead of as bespoke
passes over text. What it does **not** promise: to be the authoring path (that's the
engine's edit verbs), to preserve formatting, or to work on anything but Beagle.

It was called **chartroom** because that was the name of the *experiment* — a nautical
room where you spread the charts out and plot the course, cute for a bet on "can a graph
beat grep." The bet cleared its kill lines, the experiment shipped and got folded into
fram (ADR 0001), and the museum placard stayed nailed to the door.

The prior decided it, and the prior had already spoken: the agent skill fronting this
surface was named **codegraph** by the same hands that wrote `chartroom/`, without
deliberation, because that's the word you reach for when you have to *use* the thing
rather than remember its origin story. That is revealed preference, and under the rule
at the top of this file it is the strongest evidence available — a name nobody had to be
taught. "chartroom" is a metaphor you must be told; "codegraph" is a description you
already know. `codegraph` also compounds without apology (`codegraph/src`, "the code
graph") and tells an LLM what it holds in one token pair.

The rejected bench:

- **chartroom** — an experiment's codename doing a shipped subsystem's job. Nautical
  charm buys nothing at the call site and costs a sentence of explanation forever.
- **callgraph** — already taken, one layer down (`codegraph/src/callgraph.bclj`), and
  narrower than the surface: call edges are one query the graph answers, not the graph.
- **codeintel** — accurate and joyless; also an IDE-vendor prior (LSP, Sourcegraph) that
  drags in editor-plumbing expectations this surface doesn't serve.
- **code-as-facts** — the right description of the *bet*, which is why it survives as
  prose and as the skill's tagline; too long and too hyphenated to be a directory.

The same move split the directory: `resolve.clj` — the lexical resolver and the
minimal-op AST edit verbs the graph-authoring commands `load-file` — was never
code-intelligence. It is tier-3 engine code that happened to be born in the experiment's
folder, so it was promoted out of `codegraph/` into the engine's own source set, where
its address says what it is. (Superseded in detail, not in ruling: the root
`resolve.clj` shim is gone; the resolver is now the Beagle modules
`src/resolve_*.bclj`, built to `out/resolve.clj`.) `codegraph/` keeps only the analysis
surface.

One honest sentence for keeping "chartroom" nowhere load-bearing: an experiment earns a
verdict, not a permanent namespace, and the only places the old name survives are the
records that would be lies without it — ADR 0001, the codegraph experiment's own
retained records, the skill line that says which repo got folded in, and this entry.

## Turtle — an architecture prior, never a primitive — chosen 2026-08-01

When a Fram document says **Turtle**, the intended prior is the phrase **“turtles all
the way down.”** It names a design philosophy: prefer uniform ordinary recursive
triples wherever the model permits, so metadata and higher-order structure use the same
machinery as the statements they describe. It does not name a record, identifier, log
format, or second kind of stored thing.

The semantic vocabulary is deliberately literal:

```text
Atom   := String | Int | Float | Bool | Keyword | Instant
Term   := Atom | Triple
Triple := (Term, Term, Term)
```

**Slot-addressable ontology** means `slot0`, `slot1`, and `slot2` are stable neutral
addresses, while roles are expressed by ordinary ontology patterns and never assigned
by kernel position. A transaction coordinate such as
`(space, kernel/tx-sequence, 1842)` is therefore a Triple, not a transaction primitive.
A Triple can occupy any slot of another Triple.

Atoms honestly terminate the recursion. Namespaced atoms such as `plangrep/page` are
grounding vocabulary; `/` carries no kernel semantics and does not privilege the
middle slot. Use an atom when a value is genuinely atomic. When its components need to
be queried or described, represent that structure with more Triples rather than hiding
it in an opaque compound string.

Physical implementations may use tagged term handles, atom tables, and `TripleRow`
records for finite storage. Those are private representations of the one recursive
model, not semantic identity. Accordingly, code uses `Term`, `Triple`, `TripleRow`, and
`slot0`/`slot1`/`slot2`; names such as `TurtleRow`, `turtle-id`, and “turtle log” are
category errors.

## Appending an entry

When a name fight happens, record: what the thing *is* (its actual semantics, including
what it deliberately does not promise), the prior that decided it, the rejected bench
with one honest sentence each, and the date. Future readers — human and model — inherit
the verdict instead of the argument.
