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
or the thirteen-operation FRAMRPC boundary. **Partly superseded 2026-08-02:** the
agent skill named `codegraph` is retired and its read-side faculty now lives in
`code-as-facts` (see the skill-names entry below). The revealed-preference argument
here still decides what the *subsystem* is called; it no longer names a live skill.

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

Atoms honestly terminate the recursion. `/` carries no kernel semantics and does not
privilege the middle slot. (**Reconciled 2026-08-03:** the original entry offered
`plangrep/page` as acceptable "grounding vocabulary"; the normalization principle in
[`ontology.md`](ontology.md) supersedes that clause — a grouping that a namespaced
spelling implies must be asserted as a Triple, or the spelling is hiding a join. The
rest of this paragraph already said so.) Use an atom when a value is genuinely atomic.
When its components need to be queried or described, represent that structure with
more Triples rather than hiding it in an opaque compound string.

Physical implementations may use tagged term handles, atom tables, and `TripleRow`
records for finite storage. Those are private representations of the one recursive
model, not semantic identity. Accordingly, code uses `Term`, `Triple`, `TripleRow`, and
`slot0`/`slot1`/`slot2`; names such as `TurtleRow`, `turtle-id`, and “turtle log” are
category errors.

## agent skill names — fram-modeling, code-as-facts — chosen 2026-08-02

The ruling in one line: **the kernel substrate is the typed Triple** — an asserted
Triple *is* a proposition — and **"fact" is the coordination layer's word**, the one
North's rows speak. Agent-facing skill names are chosen against that split, not
against whichever layer's vocabulary happened to be current when the skill was
written. Two consequences, both applied here:

- **`fact-modeling` → `fram-modeling`.** The skill teaches how to model data and
  logic *in Fram*: rent the engine, assert, supersede, query the live view, derive
  with Datalog. Naming it after a layer's word for the stored thing had already
  dated it twice (`fact-authoring` → `fact-modeling`), and the recursive-Triple cut
  would have dated it a third time. Name the **tool**, which does not move.
- **The `codegraph` skill is retired into `code-as-facts`.** They were one surface
  described twice: `code-as-facts` taught the write side of the code graph (the
  graph-edit verbs behind the upstream guard) and `codegraph` taught the read side
  (who-calls, blast radius) of the same projected AST in the same store. Two skill
  files over one substrate is a routing coin-flip for a model, and the read side
  was pinned to a subsystem this ledger already scoped as historical. One skill,
  two faculties, and the stale entry points (`codegraph/src/*.clj`, experiment-era
  gate numbers) dropped rather than carried forward.

The rejected bench:

- **code-as-triples** — the accurate *kernel* word, rejected deliberately. This name
  is a live coordination-layer identifier, not prose: North's composer requires a
  skill's frontmatter `name:` to equal its directory, and the code-upstream-guard's
  denial text, `bin/fram-primer`, and the profile's greenfield rule all send agents
  to **code-as-facts** by that exact string. Renaming the coordination-facing handle
  to the kernel's word would break every one of those and buy nothing: at that layer
  "fact" is the correct word, and this skill is the thing an agent opts into, not a
  storage contract.
- **codegraph** (as the merged skill's name) — names the module, not the faculty, and
  the module is a retained historical analysis surface. A live skill named after it
  routes the model into `codegraph/` instead of the engine entry points.
- **fram-authoring** — collides head-on with `beagle-authoring` and with the
  graph-edit authoring channel that `code-as-facts` owns.
- **fram-data** — undersells the half that earns the engine its keep: Datalog
  derivation, not storage.

The discipline this leaves behind: a skill is named for the **tool** it teaches
(`fram-modeling`, `beagle-authoring`) or for the **bet** an agent is opting into
(`code-as-facts`) — never for the layer-of-the-month word for the stored thing.

## the normalized example — settled 2026-08-03

The thing: one canonical worked example of normalized vocabulary, defined in
[`ontology.md`](ontology.md) and linked from everywhere else. The contamination
vector it closes: `:contact/email` entered the README and rationale as a positive
example on 2026-08-01 (5c4499e); the normalization principle landed 2026-08-02
(1853c72) without repairing the earlier examples; and every reader — human and
model — copied the concrete positive example over the abstract negative rule.
A prohibition without a copyable replacement loses to the prior every time; this
is the same law as the rule at the top of this file, applied to examples instead
of names.

The ruling: current documents carry exactly one canonical normalized example.
Domain vocabulary in examples is unnamespaced (`:email`), and its grouping is
asserted — `(:email, :grouped-under, :contact)` — never spelled. The engine's
`:kernel/*` occurrence vocabulary stays primitive-exempt, as the ontology's
regress rule already records. Closed wire tags (`:rpc/*`) are protocol syntax,
not domain vocabulary. Physical rotation names (`SPO`/`POS`/`OSP` tries) are
private storage mechanics in the same category as `TripleRow`, never kernel
vocabulary.

The rejected bench:

- **`:contact-email`** (re-spell without the slash) — hides the same structure
  behind different punctuation; the defect was never the `/`, it was the
  grouping that existed only in spelling.
- **value reification** (`("Alice", :has, email-1)` plus `(email-1, :value, ...)`)
  — the workaround stores without recursion need for statement metadata. Fram's
  recursion annotates the proposition directly and the occurrence already
  carries who/when; the intermediate entity only dilutes the middle slot into a
  generic verb and triples the join count. Give a value its own Term when the
  domain gives it identity, never to satisfy normalization.
- **prohibition-only documentation** — the state this entry repairs; a negative
  rule with no positive example is a bug with a delay on it.

## Beagle fact projections — identifiers retained; contracts split — chosen 2026-08-03

The thing: Beagle exposes two projections that share the fact layer but do not
share a fidelity contract. `bin/beagle-facts`, implemented by
`beagle-lib/private/emit-facts.rkt`, emits a compact analysis view of the parsed
AST for Datalog queries. Its special-form overlays deliberately omit
reconstructive detail, so it is lossy. `beagle facts-roundtrip`, backed by
`beagle-lib/private/facts-roundtrip.rkt`, emits a verbose program view that
preserves reader-datum identity and can render source. `.fram/corpus.facts`
materializes the compact analysis view for program-inspection queries.

The ruling: keep `beagle-facts`, `facts-roundtrip`, `emit-facts.rkt`, and the
`.facts` extension. Here **fact** names projection status: a proposition selected
into an analysis or live-program view. It does not name Fram's stored structure;
**Triple** remains the sole structural primitive. The lossless code-as-facts
projection is also within the boundary because its selected live triples
constitute the program.

The two projections must not share one descriptive sentence. Current prose uses:

- **compact analysis projection:** "a compact, lossy projection of the parsed AST
  into CNF analysis facts, represented as three-slot vectors";
- **program roundtrip projection:** "a verbose, program-lossless source↔fact
  projection; lossless means reader-datum identity, not byte identity."

The named concession is that `facts` remains a family word across two contracts.
Every description where both are in scope therefore carries the contract:
compact/lossy analysis or verbose/program-lossless roundtrip.

The prior that decides it is Datalog and coordination usage: a fact is a
proposition admitted to the current view. Renaming the family to the kernel word
would describe row shape while erasing why those rows exist.

The rejected bench:

- **`beagle-triples`, `triples-roundtrip.rkt`, `emit-triples.rkt`,
  `corpus.triples`** — structurally true but layer-wrong. It names the kernel
  representation instead of the projection's selected propositions and still
  fails to distinguish the two fidelity contracts.
- **`beagle-codegraph` / `emit-codegraph.rkt`** — names one historical consumer
  and analysis subsystem, not the projection; it misroutes the lossless
  code-as-facts authoring surface.
- **`program-roundtrip`** — accurate only for the lossless half and severs the
  established source↔fact prior without improving the compact analysis half.
- **"lossless CNF fact-triple projection" for both paths** — rejected because it
  is observably false for `emit-facts.rkt` and collapses two views into one
  supposed artifact.
- **`claims`** — belongs to the occurrence/act layer; neither projection is an
  assertion event.

## Appending an entry

When a name fight happens, record: what the thing *is* (its actual semantics, including
what it deliberately does not promise), the prior that decided it, the rejected bench
with one honest sentence each, and the date. Future readers — human and model — inherit
the verdict instead of the argument.
