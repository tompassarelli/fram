# Naming ledger

This ledger preserves each load-bearing naming verdict, its deciding prior, and its rejected bench so the argument is not repeated.

Entries stay dated. When a later ruling supersedes wording, reconcile that entry in place and point to the successor; git preserves the former text.

## Rule

Fram's readers include LLMs, so repository vocabulary becomes generated vocabulary. Choose by **prior alignment**: the strongest established meaning must match the thing without a correction paragraph.

## fact — chosen 2026-06; superseded as a primitive 2026-08-01

The June ruling named a stored `(subject predicate object)` record **fact**. The recursive-Triple cut superseded that primitive: [Triple](glossary.md#semantic-kernel) is the sole structural semantic primitive, while fact is valid only for a proposition admitted by a view. Datalog and coordination projections may say fact; storage and kernel APIs say Term, Triple, proposition, and occurrence. This keeps truth and endorsement out of storage identity: one proposition may be asserted, disputed, withdrawn, or selected differently without changing its Triple.

The deciding prior is Datalog: a fact is a proposition present in the evaluated relation. The rejected primitive use is **fact-as-stored-row** — it falsely merges structure, assertion act, and view status.

## world — chosen 2026-07-26

**Current scope:** historical Worlds-service vocabulary. The service left the public recursive-kernel runtime, so `world` and `version` are not kernel primitives or FRAMRPC operations.

The thing was a named, forkable lineage of immutable versions that fixed which facts a query saw without promising consistency. The possible-worlds prior decided it: propositions are evaluated at a way things could stand.

Rejected bench:

- **consistent-plane** — promises consistency that rival assertions deliberately violate.
- **plane** — collides with infrastructure control/data planes.
- **reality** — claims truth a world need not have.
- **universe** — claims totality although a version may be partial.
- **branch** — describes mechanics but not what is branching, and collides with git.

## codegraph — chosen 2026-07-26

**Current scope:** historical experiment and sealed-consumer vocabulary, not a public data primitive. **Partly superseded 2026-08-02:** the `codegraph` skill was retired into `code-as-facts`; the subsystem verdict remains.

The thing was the code-intelligence surface that projected Beagle source to queryable relations for who-calls, blast radius, and rename analysis; it did not promise source authoring, formatting preservation, or non-Beagle input. Revealed preference decided it: users independently reached for `codegraph`, while `chartroom` required its experiment story. The lexical resolver was engine machinery and moved out; its former shim is gone and the Beagle resolver modules now build to `out/resolve.clj`.

Rejected bench:

- **chartroom** — an experiment codename costs an explanation at every call site.
- **callgraph** — names only one narrower relation and was already a module name.
- **codeintel** — imports IDE and vendor expectations this surface did not serve.
- **code-as-facts** as the subsystem directory — names the bet and live skill well, but is too long for the module.

The old `chartroom` name survives only where provenance would otherwise become false.

## Turtle — architecture prior, never a primitive — chosen 2026-08-01

**Turtle** invokes “turtles all the way down”: use ordinary recursive Triples for data, coordinates, history, and metadata when the model permits. It never names a record, identifier, log, or second stored type; the literal semantic vocabulary is linked from the [glossary](glossary.md#semantic-kernel).

Roles remain ontology conventions, not kernel positions. Grouping implied by spelling must be asserted, as [ontology](ontology.md#normalization) specifies.

Atoms terminate recursion honestly. When components need queries or descriptions, use more Triples instead of opaque compounds. Tagged handles, tables, and rows remain private representations; `TurtleRow`, `turtle-id`, and “turtle log” remain category errors.

## agent skill names — fram-modeling, code-as-facts — chosen 2026-08-02

The thing was an unambiguous routing vocabulary for agent skills. The layer split decided it: Triple names kernel structure, while fact is the coordination projection's word. Therefore `fact-modeling` became **`fram-modeling`**, named for the stable tool, and the read-side `codegraph` skill merged into **`code-as-facts`**, one write/read faculty over one projected AST.

Rejected bench:

- **code-as-triples** — kernel-accurate but wrong for the coordination handle already wired into North and the upstream guard.
- **codegraph** for the merged skill — routes users toward one historical consumer instead of the authoring faculty.
- **fram-authoring** — collides with `beagle-authoring` and the graph-edit channel.
- **fram-data** — omits Datalog derivation, half the faculty.

Skills are named for the tool (`fram-modeling`, `beagle-authoring`) or the bet (`code-as-facts`), never the current layer's stored-thing word.

## normalized example — settled 2026-08-03

The thing is one copyable positive example, defined only in [ontology](ontology.md#normalization). It replaced the earlier `:contact/email` example whose spelling contradicted the new rule. The deciding prior is copy behavior: readers reproduce a concrete example more reliably than a prohibition.

The ruling is unnamespaced domain vocabulary with grouping asserted as an ordinary Triple. `:kernel/*` occurrence predicates are primitive-exempt; closed `:rpc/*` tags are wire syntax; `SPO`/`POS`/`OSP` are private rotations.

Rejected bench:

- **`:contact-email`** — hides the same grouping behind different punctuation.
- **value reification** — invents an entity and joins where recursive proposition annotation already works.
- **prohibition-only documentation** — loses to any nearby copyable counterexample.

## Beagle fact projections — identifiers retained; contracts split — chosen 2026-08-03

The thing is two Beagle projections sharing a family word but not fidelity: `bin/beagle-facts`/`emit-facts.rkt` emits a compact lossy CNF analysis view, while `beagle facts-roundtrip`/`facts-roundtrip.rkt` emits a verbose program-lossless view preserving reader-datum, not byte, identity. `.fram/corpus.facts` materializes the compact view.

Datalog's admitted-proposition prior decided **fact** here; it names projection status, not Fram storage. Keep the existing identifiers and always state which fidelity contract applies.

Rejected bench:

- **`beagle-triples` / `emit-triples.rkt` / `corpus.triples`** — names row shape but erases projection purpose and still conflates fidelity.
- **`beagle-codegraph`** — names one historical consumer, not either projection.
- **`program-roundtrip`** — fits only the lossless half and severs the shared source↔fact prior.
- **one “lossless CNF fact-triple” description** — is observably false for `emit-facts.rkt`.
- **`claims`** — belongs to assertion acts, not projected views.

## namespaced vocabulary outside fixtures — scoped 2026-08-04

The thing was every remaining `ns/name` family after normalization. An inventory at `a488892` found 26 non-`:kernel/*`/`:rpc/*` families across 692 occurrences and separated them by contract.

- **Semantic fixtures** (`ontology/slot`, `plangrep/*`, `example/*`, `builder/*`, `growth/entry`, `building/id`, `deep/*`, `agent/lane`) became unnamespaced because none exercised grouping. The kernel slash probe and ExceptionInfo tag `:test/rejected-plan` remain intentionally namespaced.
- **Historical code** (`provider/*`, `world/*`, `worlds/*`) retained spelling because rewriting a service awaiting retirement buys no queryable structure.
- **Closed wire tags** (`authority/*`, `fram/*`, `fram.defcheck/*`, `lease/*`, `query/*`) retained spelling because a change is wire versioning, not ontology normalization.

The deciding prior is boundary ownership: at that revision no in-scope family was written verbatim to a configured live store, so nine fixtures and zero stores changed. Replan if a configured non-test `worlds/invoke-plan-to!` caller, an external corpus carrying `worlds/*` or `provider/*`, or a FRAMRPC tag rename appears.

## positions of the Triple — t1/t2/t3 — chosen 2026-08-04

The thing is the three neutral positional addresses of `Triple := (Term, Term, Term)`. **t1**, **t2**, and **t3** follow 1-based tuple coordinates: projections π1/π2/π3, Prolog `arg(1,...)`, Erlang `element(1,...)`, RDF `rdf:_1`, and Scala `_1`; **t** comes directly from the kernel grammar, so t1 is the first Term.

The names do not change the binary wire, where triples encode as positional tagged arrays. They apply consistently across the client API, engine, generated output, tests, and documentation. `TripleRow` and the `SPO`/`POS`/`OSP` tries remain private storage mechanics, but their three coordinates use the same t1/t2/t3 vocabulary.

Rejected bench:

- **zero-based slot vocabulary** — combines a frame-language word with array-offset indexing, a hybrid with no single tradition behind it.
- **`s0`/`s1`/`s2`** — an abbreviation that needs a correction sentence.
- **bare `0`/`1`/`2`** — ungreppable and unpronounceable in prose.
- **one-based slot vocabulary** — keeps the invented word while changing only the defensible part.

## profile — and the EAV reading — chosen 2026-08-04

The thing is the word for an optional, stored contract above the unchanged kernel, and the role vocabulary of the first one. **Profile** follows the standards prior (Bluetooth profiles, OWL 2 profiles): a named usage convention that constrains and reads one substrate without changing it. The relational profile's reading of the positions is **entity (t1), attribute (t2), value (t3)** — the database literature's EAV prior. Role words are profile vocabulary: profile documentation and profile-aware surfaces such as `text-match(entity, attribute, needle)` may speak them; kernel and wire vocabulary stays t1/t2/t3.

Rejected bench:

- **world** — retired to the historical Worlds service; reuse reopens a settled verdict.
- **schema** — implies stored enforcement types the kernel refuses to own.
- **dialect** — implies a different language rather than a convention over one.
- **layer / mode** — generic altitude words, and mode reads as a runtime switch.
- **renaming the stored `"relational"` anchor to `"eav"`** — a stored-value migration purchasing a spelling; the reading is recorded instead.

## time lives in the log, not the Triple — architecture ruling — chosen 2026-08-04

The thing is where time attaches. A Triple is timeless content; time is the occurrence's log coordinate, and any time that must be data is an ordinary Triple, per the [glossary](glossary.md#semantic-kernel). Two priors decide it. Recursion: a quoted Triple must be timeless, or `((a, :works-for, b), :supported-by, doc)` cannot say which version it quotes — Datomic's datom fuses `(e, a, v)` with `(tx, added)` and therefore cannot nest, reifying statements as entities instead. Type–token: separate occurrences keep equal propositions asserted independently as two corroborating events, where Datomic elides the redundant datom. The accepted cost is that as-of filtering rides snapshot machinery at the query boundary instead of a time field in every index row.

Rejected bench:

- **datom-style fused tuple** — breaks quotation and collapses independent corroboration.
- **a fourth time position** — makes the arity a lie and assigns a kernel role to a slot.
- **validity-interval fields** — closing an interval mutates history that must stay append-only.

## Appending an entry

Record the date, what the thing is (including what it does not promise), the prior that decided it, and each rejected candidate with one honest sentence. Reconcile superseded rulings in place and link the successor; never let obsolete vocabulary pose as current doctrine.
