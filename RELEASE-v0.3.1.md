# v0.3.1

Fram v0.3.1 is a maintenance release for the v0.3 coordinator runtime. It
repairs checkpoint replay, contains interval-rotation failures, binds long
PREPARE work to the request that initiated it, and makes bounded whole-corpus
paging reuse an immutable ordered snapshot instead of replaying Datalog for
every page.

The persisted log and v0.3 coordinator wire formats remain compatible. No data
migration is required.

## Highlights

- Snapshot replay now preserves transaction identity when migration, tail, and
  schema writes update compact `StoredTx` vectors. Malformed checkpoint IDs are
  rejected with a coded, nonblank error instead of reaching an unhelpful null
  failure. A checkpoint written by the repaired runtime restarted in snapshot
  mode on a copied production corpus, and an exact-subject read completed in
  83 ms.
- Interval rotation projects canonical values through Store accessors, validates
  its input domain, and sorts with a deterministic type-and-value key. Failures
  are attributed to a stable stage, partial resources and temporary files are
  closed or removed, and repeated interval failures back off within a fixed
  bound.
- Rotation cache reconstruction no longer holds the read-fencing lock. It
  captures immutable Store and schema roots, builds or joins the matching cache
  outside the lock, and rechecks both identities before publication. A raced or
  cold snapshot is contained without publishing stale derived state.
- The legacy v0.3 cutover `PREPARE` operation now shares the request's deadline
  and cancellation control. Client disconnects and request timeouts interrupt
  cooperative work and cannot publish a late prepared state; an exact replay of
  a successful prepare ID returns its stored response without rebuilding.
- Whole-corpus `query-page` requests now cache one canonical ordered fact vector
  per immutable coordinator version and locate continuation cursors by binary
  search. Pages retain their snapshot version, ordering, log fence, and 1 MiB
  response bound while avoiding a full Datalog projection and sort on every
  page. Other query shapes keep the existing evaluator.

## Compatibility

- Existing v0.3 logs, checkpoints, and coordinator clients remain compatible.
- Query results and cursor ordering are unchanged; the whole-corpus paging
  change replaces repeated internal work rather than changing the relation.
- More specific snapshot, rotation, and request-lifecycle failures may now
  expose coded diagnostics where v0.3.0 returned a blank or generic error.
- The legacy cutover operations remain on the v0.3 wire for compatibility. This
  release does not require or recommend a blue/green deployment topology.

## Scope

This release contains the seven support-line commits after `v0.3.0`, ending at
`118286babfaf72dcd2af3346775fe22241e2f59d`, plus this release note. It does not
pull the newer FRAMRPC main-line runtime into the v0.3 support branch.
