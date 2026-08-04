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
  host: process.env.FRAM_CONNECT || '127.0.0.1',
  port: Number(process.env.FRAM_PORT || 7977),
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

Daemon errors throw `FramRpcError` with `code`, `retryable`, `detail`,
`servedVersion`, and operation identity. Transport and malformed-frame errors
use `FramTransportError` and `FramProtocolError`.

FRAMRPC v1 serves one request per TCP connection. The client follows that
contract directly without HTTP, JSON, MCP, or a Clojure shim. The daemon socket
is plaintext and unauthenticated; keep it on loopback or a private network, or
put an authenticated TLS boundary in front of it.
