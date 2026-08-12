import { test } from 'bun:test';
import assert from 'node:assert/strict';
import {
  FRAMRPC_MAX_FRAME_BYTES,
  FramProtocolError,
  FramRpcError,
  FramTransportError,
  float64Term,
  framClient,
  keywordTerm,
  stringTerm,
  tripleQuery,
  tripleTerm,
} from '../clients/bun/framrpc-core.mjs';

const encoder = new TextEncoder();
const MAGIC = Uint8Array.of(0x46, 0x52, 0x41, 0x4d, 0x52, 0x50, 0x43, 0);

function concat(parts) {
  const bytes = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.length;
  }
  return bytes;
}

function integer(width, write, value) {
  const bytes = new Uint8Array(width);
  new DataView(bytes.buffer)[write](0, value, true);
  return bytes;
}

const u8 = value => integer(1, 'setUint8', value);
const u16 = value => integer(2, 'setUint16', value);
const u32 = value => integer(4, 'setUint32', value);
const i64 = value => integer(8, 'setBigInt64', value);

function textTerm(tag, text) {
  const bytes = encoder.encode(text);
  return concat([u8(tag), u32(bytes.length), bytes]);
}

function rejectedResponse(request, requestId = request.requestId) {
  const body = concat([
    textTerm(1, request.space),
    textTerm(6, request.operation),
    i64(0n),
    u8(0), // no page
    u8(1), // error present
    textTerm(6, 'test/rejected'),
    u8(0),
    textTerm(1, 'expected transport test response'),
    u8(0), // no error detail
    u8(0), // no payload
  ]);
  return concat([
    MAGIC,
    u16(1),
    u16(0),
    u8(2),
    u8(0),
    u32(body.length),
    i64(requestId),
    body,
  ]);
}

async function expectRemoteRejection(promise) {
  await assert.rejects(
    promise,
    error => error instanceof FramRpcError && error.code === 'test/rejected',
  );
}

test('runtime-neutral transport preserves the closed client surface', async () => {
  const observed = [];
  const transport = request => {
    observed.push(request);
    return rejectedResponse(request);
  };
  const fram = framClient({ space: 'worker-space', transport });
  const fence = tripleTerm(keywordTerm('rpc/fence'), 'holder', 1);

  await expectRemoteRejection(fram.version());
  await expectRemoteRejection(fram.status());
  await expectRemoteRejection(fram.validate());
  await expectRemoteRejection(fram.occurrences());
  await expectRemoteRejection(fram.scan({ t1: 'subject' }));
  await expectRemoteRejection(fram.query(tripleQuery()));
  await expectRemoteRejection(fram.assert('subject', 'predicate', 'object'));
  await expectRemoteRejection(fram.retract('subject', 'predicate', 'object'));
  await expectRemoteRejection(fram.batch([
    { op: 'assert', t1: 'subject', t2: 'predicate', t3: 'object' },
  ]));
  await expectRemoteRejection(fram.leaseAcquire('resource', 'holder', 1000));
  await expectRemoteRejection(fram.leaseRenew(fence, 1000));
  await expectRemoteRejection(fram.leaseRelease(fence));
  await expectRemoteRejection(fram.leaseCheck(fence));
  await expectRemoteRejection(fram.query(tripleQuery(), { asOf: 0 }));

  assert.equal(observed.length, 14);
  assert.deepEqual(
    observed.map(request => request.entry),
    [
      'query', 'query', 'query', 'snapshot', 'query', 'query',
      'transact', 'transact', 'transact', 'transact', 'transact',
      'transact', 'query', 'snapshot',
    ],
  );
  for (const request of observed) {
    assert.equal(Object.isFrozen(request), true);
    assert.equal(request.space, 'worker-space');
    assert(request.frame instanceof Uint8Array);
    assert(request.frame.length <= FRAMRPC_MAX_FRAME_BYTES);
    assert(request.signal instanceof AbortSignal);
  }
});

test('transport responses retain exact protocol identity checks', async () => {
  const fram = framClient({
    space: 'worker-space',
    transport: request => rejectedResponse(request, request.requestId + 1n),
  });
  await assert.rejects(
    fram.version(),
    error => error instanceof FramProtocolError
      && error.code === 'client/identity-mismatch',
  );
});

test('transport timeout and caller abort are bounded', async () => {
  const waiting = framClient({
    space: 'worker-space',
    requestTimeoutMs: 5,
    transport: () => new Promise(() => {}),
  });
  await assert.rejects(
    waiting.version(),
    error => error instanceof FramTransportError && /exceeded 5ms/.test(error.message),
  );

  const controller = new AbortController();
  controller.abort();
  await assert.rejects(
    waiting.status({ signal: controller.signal }),
    error => error instanceof FramTransportError && /aborted/.test(error.message),
  );
});

test('portable codec has no Buffer dependency', () => {
  const saved = globalThis.Buffer;
  try {
    globalThis.Buffer = undefined;
    assert.deepEqual(stringTerm('portable'), ['string', 'portable']);
    assert.deepEqual(float64Term(1.5), ['float64', '3ff8000000000000']);
  } finally {
    globalThis.Buffer = saved;
  }
});
