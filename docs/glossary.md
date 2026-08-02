# Operational glossary

**fact (v0.3 sense)** — On the deployed v0.3 line, a fact is one stored subject–predicate–object record; in the current kernel, “fact” is not a stored type and means only a proposition included by a particular view’s rules.

**Term** — A Term is any value Fram can place in a Triple: either an Atom or another Triple.

**Atom** — An Atom is a non-recursive leaf value: a string, integer, floating-point number, Boolean, keyword, or instant.

**Triple** — A Triple is exactly three neutral Term slots, any of which may contain another Triple, and the kernel assigns none of the slots a fixed subject, predicate, or object role.

**proposition** — A proposition is a Triple used as statement content, independent of when or how many times that content is asserted.

**occurrence** — An occurrence is one assertion or retraction at an exact log position identified by its transaction and operation numbers, so equal propositions recorded at different positions remain distinguishable.

**live set** — The live set is the current view of assertion occurrences still in force after retractions and explicit replacements, while the full operation history remains preserved.

**fold** — A fold replays ordered log transactions to reconstruct the store’s history, live occurrences, live propositions, and current logical version.

**fold-boot** — A fold-boot starts a Fram server by folding its complete authoritative log history into memory instead of starting from a checkpoint.

**snapshot boot (v0.3 sense)** — A snapshot boot restores a validated checkpoint and replays only the later log tail, falling back to a fold-boot if the checkpoint cannot be proved to match the logs and fold logic; the recursive-Term coordinator implements no checkpoint path and always fold-boots.

**checkpoint (v0.3 sense)** — A checkpoint is an on-disk image of the folded store at one logical version, with log identities and byte offsets that identify the later records still needing replay.

**rotation** — A rotation is a disposable index of live assertion occurrences by individual Triple slots and slot pairs, so many queries can find matches without scanning the whole live set.

**projection** — A projection is a derived, rebuildable view of stored Terms or occurrences, such as live query relations or indexes, rather than authoritative history.

**world** — A world is the historical name for a named history that can branch into immutable versions, each deciding which facts a query sees without promising those facts agree; it is not a current kernel primitive or FRAMRPC operation.

**epoch (planned)** — An epoch is a planned archival cut that will keep only the data chosen for retention in smaller active stores while preserving the original logs in a reversible cold archive.

**Fram server** — A Fram server is a long-running process that loads one store, serves its private data protocol, and appends accepted changes only when it holds writer authority.

**writer/standby (authority roles)** — The writer is the one Fram server generation allowed to append to a store, while a standby stays read-only, refreshes from the same durable history, and can be prepared to take authority.

**work store** — The work store is the Fram-backed history containing operator-facing work records, intentions, and schema, kept separate from high-volume machine telemetry.

**telemetry store** — The telemetry store is the separate Fram-backed history for runs, sessions, measurements, and other high-volume machine output.

**blue/green generation** — A blue or green generation is one of two side-by-side deployment copies of the Fram servers, allowing one copy to serve while the other is prepared and checked.

**selector** — The selector is the operator-owned front end that can hold and drain public connections, check both deployment copies, and switch all store routes together.

**promote (deployment)** — To promote is to give a prepared standby writer authority after it matches the previous writer’s final durable marker; public traffic moves only after the new writer passes its checks.

**wire skew** — Wire skew means a client and server speak different protocol versions or formats, so a healthy server may still reject, drop, or hang the client’s requests.

**FRAMRPC** — FRAMRPC is Fram’s private binary protocol, carrying typed recursive Terms and a closed set of thirteen data operations between clients and a Fram server.

**v0.3 line** — The v0.3 line is the still-deployed compatibility release series that uses the older flat-store, line-protocol runtime and its blue/green cutover contract until migration to the recursive-Term FRAMRPC runtime.
