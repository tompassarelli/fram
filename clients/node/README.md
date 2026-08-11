# `@tompassarelli/framrpc`

The official Node.js client for Fram's binary FRAMRPC v1 data plane. It is an
ES module with no runtime dependencies and exposes all thirteen frozen native
operations.

Use this client for application and builder traffic that needs exact recursive
Terms, atomic batches, optimistic versions, occurrence replay, or pinned
pagination. `fram-mcp` remains the narrower agent-facing JSON-RPC surface.

## Install from a Fram checkout

```console
$ npm install /path/to/fram/clients/node
```

Node.js 20 or newer is required.

## Connect

```js
import { framClient, keywordTerm } from '@tompassarelli/framrpc';

const fram = framClient({
  host: process.env.FRAM_SERVER_CONNECT || '127.0.0.1',
  port: Number(process.env.FRAM_SERVER_PORT || 7977),
  space: process.env.FRAM_SPACE_ID,
});

const version = await fram.version();

await fram.batch([
  {
    op: 'assert',
    t1: '@document-1',
    t2: keywordTerm('title'),
    t3: 'Running with Wolves',
  },
  {
    op: 'assert',
    t1: '@document-1',
    t2: keywordTerm('kind'),
    t3: keywordTerm('note'),
  },
], { expectedVersion: version.servedVersion });
```

Every response includes the exact `servedVersion`, optional page metadata, the
decoded operation result, and the original typed payload:

```js
{
  space,
  operation,
  servedVersion, // bigint
  page,          // { ordinal, nextCursor, done } or null
  result,        // operation-specific decoded result
  payload        // exact recursive Term
}
```

## Structured queries

`query` accepts the same structured query object as Fram's public `ask`
boundary. Constants use the typed Term constructors when their type is not a
String, Int, Float, or Bool.

```js
const response = await fram.query({
  find: 'matches',
  rules: [{
    head: { rel: 'matches', args: [{ var: 'entity' }] },
    body: [{
      rel: 'text-match',
      args: [{ var: 'entity' }, keywordTerm('title'), 'running'],
    }],
  }],
}, {
  timeoutMs: 5000,
  page: { limit: 100 },
});

for (const row of response.result) console.log(row);
```

Pass `asOf` for an immutable historical snapshot or `since` for an occurrence
window:

```js
await fram.query(query, { asOf: 42n });
await fram.query(query, { since: { lowerExclusive: 10n, upper: 42n } });
```

Continue a page by returning its cursor unchanged with the same request:

```js
let cursor;
do {
  const page = await fram.scan(
    { t2: keywordTerm('title') },
    { page: { limit: 256, ...(cursor ? { cursor } : {}) } },
  );
  consume(page.result);
  cursor = page.page.done ? null : page.page.nextCursor;
} while (cursor);
```

The cursor pins the original snapshot; a continuation reports that pinned
`servedVersion` even when newer writes have committed.

## Term representation

Terms use the same exact tagged arrays as Fram's JSON edge:

| Fram type | Node representation |
|---|---|
| String | `['string', value]` |
| Int | `['integer', canonicalDecimal]` |
| Float | `['float64', ieee754Hex]` |
| Bool | `['boolean', value]` |
| Keyword | `['keyword', spelling]` |
| Instant | `['instant', epochSeconds, nanos]` |
| Triple | `['triple', t1, t2, t3]` |

Use `stringTerm`, `integerTerm`, `float64Term`, `booleanTerm`, `keywordTerm`,
`instantTerm`, and `tripleTerm` to construct them. Plain strings, safe integer
numbers, bigints, non-integer numbers, booleans, and `Date` values are lowered
automatically. Integers decode as canonical decimal strings inside Terms and as
`bigint` in response metadata so no i64 precision is lost.

## Operations

The client has no raw operation escape hatch. Its methods are the frozen
FRAMRPC v1 set:

- `version`, `status`, `validate`
- `assert`, `retract`, `batch`
- `scan`, `query`, `occurrences`
- `leaseAcquire`, `leaseRenew`, `leaseRelease`, `leaseCheck`

Server errors throw `FramRpcError` with `code`, `retryable`, `detail`,
`servedVersion`, and operation identity. Transport and malformed-frame errors
use `FramTransportError` and `FramProtocolError`.

`FRAMRPC_MAX_BATCH_ACTIONS` is the canonical 247-action mutation-response depth
ceiling. The client rejects a larger batch before transport. Mutation receipts
carry action indexes and occurrence coordinates, not the submitted action
Terms, so large action Terms do not enlarge the response. FRAM still
exact-preflights the response frame before commit because SpaceId size and the
receipt-envelope width determine its encoded bytes.

## Schema-aware application writes

The optional `@tompassarelli/framrpc/schema` entry point builds reusable
application constraints over an injected official client. It adds no FRAMRPC
operation and assigns no domain role to the neutral kernel.

```js
import { framClient, keywordTerm } from '@tompassarelli/framrpc';
import { schemaClient } from '@tompassarelli/framrpc/schema';

const fram = framClient({
  space: process.env.FRAM_SPACE_ID,
});
const schema = schemaClient(fram, {
  maxConflictRetries: 4,
  queryTimeoutMs: 5000,
});

const page = await schema.upsertUnique({
  subject: '@page-1',
  identity: { predicate: keywordTerm('page/slug'), value: 'home' },
  fields: [
    {
      predicate: keywordTerm('page/title'),
      value: 'Home',
      cardinality: 'single',
    },
    {
      predicate: keywordTerm('page/tag'),
      value: 'wiki',
      cardinality: 'multi',
    },
  ],
});

console.log(page.subject, page.servedVersion, page.changed);

await schema.updateUnique({
  identity: { predicate: keywordTerm('page/slug'), value: 'home' },
  field: {
    predicate: keywordTerm('page/state'),
    values: [keywordTerm('canonical')],
    cardinality: 'single',
    allowedCurrent: [keywordTerm('draft')],
  },
  requireUnique: [{
    subject: '@user-1',
    predicate: keywordTerm('user/email'),
    value: 'editor@example.test',
  }],
});

await schema.updateUniqueMany({
  updates: [
    {
      identity: { predicate: keywordTerm('revision/id'), value: 'rev-1' },
      fields: [{
        predicate: keywordTerm('revision/state'),
        values: [keywordTerm('canonical')],
        cardinality: 'single',
        allowedCurrent: [keywordTerm('draft')],
      }],
    },
    {
      identity: { predicate: keywordTerm('page/slug'), value: 'home' },
      fields: [{
        predicate: keywordTerm('page/canonical-revision'),
        values: ['@revision-1'],
        cardinality: 'single',
        allowedCurrent: [],
      }],
    },
  ],
});
```

The wrapper exposes five mutations:

- `replaceSingle(subject, predicate, value)` retracts every current value and
  asserts the requested value once.
- `createUnique({ subject, identity, fields })` rejects an identity that
  already belongs to any subject, then creates it atomically with its fields.
- `upsertUnique(...)` updates the sole subject owning the identity, or uses the
  supplied subject as the create candidate when no owner exists.
- `updateUnique({ identity, field, requireUnique })` requires one source owner,
  checks optional unique-reference guards, and replaces the whole field in one
  OCC batch.
- `updateUniqueMany({ updates, requireUnique })` resolves every source and
  replaces multiple fields across multiple subjects in one guarded OCC batch.

`fields[].cardinality` defaults to `single`. An update scans every live
occurrence of a single-valued field, including duplicate equal propositions;
`multi` fields append an assertion. Creation asserts every field directly. Do
not repeat the identity predicate in `fields`. Tagged Terms pass through
unchanged, including recursive Triple, Instant, integer, and float
representations.

`updateUnique` requires exactly one desired `values` entry for `single` and
accepts zero or more for `multi`. Duplicate desired Terms are asserted once.
Every existing occurrence is retracted, including duplicate equal
propositions. When `allowedCurrent` is present, the current occurrences must
either be absent when the allowed set is empty, or represent exactly one
distinct value in a nonempty allowed set. `allowedCurrent: []` is therefore an
absence compare-and-set. `allowedCurrent` is valid only with `single`;
multi-valued replacement has no transition guard. An exact single occurrence
already equal to the desired single value is a no-op. Duplicate equal
occurrences still retract in full and collapse to one assertion. The updated
field predicate must differ from the lookup identity predicate, which is
immutable through this operation. Each
`requireUnique` entry is `{ subject, predicate, value }`; its predicate/value
identity must resolve to that subject alone. `createUnique` and `upsertUnique`
accept the same optional guard array.

`updateUniqueMany` requires a nonempty `updates` array and at least one field
per update. Its `subjects` result is aligned with `updates`. Duplicate resolved
`(subject, predicate)` targets are rejected, including aliases that select the
same subject, and no update may replace any lookup identity used for that
subject. Source identities, command-level `requireUnique` guards, current
field values, and every generated retraction all come from one pinned snapshot.
One failed guard prevents the entire command from writing.

Each attempt reads a version, resolves identity with a structured query at that
exact snapshot, and accepts current scans only when they serve the same version.
It then submits one batch with that `expectedVersion`. A current scan that races
ahead causes a fresh no-write attempt. The only remote error retried is a typed,
retryable `FramRpcError` whose code is `rpc/conflict`; the default is four
retries after the initial attempt and the configurable hard ceiling is 32.
Duplicate identity owners are never selected arbitrarily.

Schema batches are capped at 247 actions, the FRAMRPC v1 mutation-response depth
ceiling exported by the base client. FRAM also exact-preflights the encoded
mutation response size and may reject a long-SpaceId or wide receipt envelope
atomically before commit. Submitted action Terms are absent from that response,
and the schema client does not guess its byte size. The depth cap applies to the
complete `updateUniqueMany` command and it is never split. Inputs are
bounded before any FRAM call: unique creation accepts at most 246 fields because
its identity is also an action, updates accept at most 247 targets, fields,
desired values, or `allowedCurrent` entries, and `requireUnique` accepts at most
247 entries. Identity and current-field reads
run at most eight at a time. Each read accepts at most two 128-row pages, which
is enough to observe the 248th occurrence that proves an action-limit failure;
unfinished pages with repeated cursors are rejected. These fixed
limits are exported as `SCHEMA_MAX_BATCH_ACTIONS`,
`SCHEMA_MAX_REQUIRE_UNIQUE`, `SCHEMA_MAX_GUARD_CONCURRENCY`, and
`SCHEMA_MAX_READ_PAGES`. Constraint failures throw `SchemaConstraintError`
with one of these stable codes:

- `schema/invalid-input`
- `schema/invalid-response`
- `schema/identity-exists`
- `schema/identity-missing`
- `schema/duplicate-identity`
- `schema/duplicate-update-target`
- `schema/required-identity-missing`
- `schema/current-value-rejected`
- `schema/action-limit`
- `schema/conflict-exhausted`

Single-subject methods return
`{ subject, created, changed, servedVersion, result }`.
`subject` is the exact selected Term and `result` is the official batch action
receipt array. A mutation needing no batch returns an empty result at its pinned
version. `updateUniqueMany` instead returns
`{ subjects, changed, servedVersion, result }`.

FRAMRPC v1 serves one request per TCP connection. The client follows that
contract directly without HTTP, JSON, MCP, or a Clojure shim. The server socket
is plaintext and unauthenticated; keep it on loopback or a private network, or
put an authenticated TLS boundary in front of it.

## License

Licensed under either the [MIT License](LICENSE-MIT) or the
[Apache License, Version 2.0](LICENSE-APACHE), at your option. See the
[license chooser](LICENSE).
