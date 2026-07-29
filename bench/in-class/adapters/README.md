# In-class adapter contract

An adapter is an executable invoked as `ADAPTER LIVE_TRIPLES RUN_ID`. It must
emit exactly one line beginning `BENCHROW ` followed by a JSON object satisfying
`~/code/fram/main/bench/in-class/scenario-contract.edn`.

Every adapter must:

- stage the deterministic corpus from `corpus_fact(tx)`: triples repeat
  `kind=thread`, `title=title-N`, and `owner=@owner-(N mod 32)` for each subject;
- expose the same two-literal `kind=thread` plus `title` join;
- acknowledge a write only after its durable commit;
- use exactly one writer while allowing independent concurrent readers;
- run on scratch state and never attach to Fram's live log or port;
- count row mismatches, rejected writes, or failed reads in `errors`.

The Fram and SQLite adapters implement the contract now. The following adapter
slots are intentionally documented stubs, not measured systems:

- `datomic-transactor`: run a real separately managed transactor and durable
  storage service; submit each triple as `[subject predicate object]`, wait for
  transaction completion before acknowledging, and use peer/client readers
  concurrently. Embedded DataScript is not a substitute. Before landing the
  adapter, record the exact artifact revision and license terms.
- `xtdb`: run a durable local node with its document/transaction stores on
  scratch disk; represent each triple without coalescing duplicate subjects,
  await transaction synchronization before acknowledging, and query through
  independent clients. Record the exact XTDB revision, storage modules, and
  licenses before landing.

Both future adapters must retain the JSON keys and scenario counts in
`~/code/fram/main/bench/in-class/scenario-contract.edn`; a new storage system does
not get a bespoke, easier workload.
