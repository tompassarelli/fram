import assert from 'node:assert/strict';
import {
  framClient,
  tripleQuery,
} from '../deploy/cloudflare/worker-client.js';

const checks = [];
const check = (label, fn) => checks.push(Promise.resolve().then(fn)
  .then(() => console.log(`  [PASS] ${label}`)));

check('default mode preserves the exact EDN version request', async () => {
  let request;
  const fram = framClient({
    url: 'https://shim.test',
    token: 'secret',
    fetch: async (_url, init) => {
      request = init;
      return new Response('{:version 12}\n', {
        headers: { 'content-type': 'application/edn' },
      });
    },
  });

  assert.deepEqual(await fram.version(), { version: 12 });
  assert.equal(request.body, '{:op :version}');
  assert.equal(request.headers.accept, undefined);
  assert.equal(request.headers['content-type'], 'application/edn');
});

check('JSON mode negotiates the response without changing request encoding', async () => {
  let request;
  const fram = framClient({
    url: 'https://shim.test/',
    token: 'secret',
    format: 'json',
    fetch: async (url, init) => {
      request = { url, ...init };
      return new Response('{"version":12}\n', {
        headers: { 'content-type': 'application/json; charset=utf-8' },
      });
    },
  });

  assert.deepEqual(await fram.version(), { version: 12 });
  assert.equal(request.url, 'https://shim.test/q');
  assert.equal(request.body, '{:op :version :fmt :json}');
  assert.equal(request.headers.accept, 'application/json');
  assert.equal(request.headers['content-type'], 'application/edn');
});

check('JSON mode preserves a pre-encoded raw EDN query', async () => {
  let body;
  const rawQuery = '{:find "out" :rules [{:head {:rel "out" :args [{:var "r"}]} :body [{:rel "triple" :args ["@a" "title" {:var "r"}]}]}]}';
  const fram = framClient({
    url: 'https://shim.test',
    token: 'secret',
    format: 'json',
    fetch: async (_url, init) => {
      body = init.body;
      return new Response('{"ok":[["hello"]],"version":12,"engine":"index"}', {
        headers: { 'content-type': 'application/json' },
      });
    },
  });

  assert.deepEqual((await fram.query(rawQuery)).ok, [['hello']]);
  assert.ok(body.includes(`:query ${rawQuery}`));
  assert.ok(body.endsWith(':fmt :json}'));
});

check('JSON mode decodes a large result without the EDN decoder', async () => {
  const rows = Array.from({ length: 1000 }, (_, i) =>
    [`@node-${i}`, `value-${i}`]);
  const fram = framClient({
    url: 'https://shim.test',
    token: 'secret',
    format: 'json',
    fetch: async () => new Response(JSON.stringify({
      ok: rows,
      version: 1000,
      engine: 'index',
    }), { headers: { 'content-type': 'application/json' } }),
  });

  const result = await fram.query(tripleQuery({ p: 'title' }));
  assert.equal(result.ok.length, 1000);
  assert.deepEqual(result.ok[999], ['@node-999', 'value-999']);
});

check('negotiated shim errors remain structured and inspectable', async () => {
  const fram = framClient({
    url: 'https://shim.test',
    token: 'wrong',
    format: 'json',
    fetch: async () => new Response('{"error":"unauthorized"}\n', {
      status: 401,
      headers: { 'content-type': 'application/json' },
    }),
  });

  await assert.rejects(
    fram.version(),
    error => error.status === 401
      && error.body?.error === 'unauthorized'
      && /HTTP 401/.test(error.message));
});

check('response content-type, not requested mode, selects the decoder', async () => {
  const fram = framClient({
    url: 'https://old-shim.test',
    token: 'secret',
    format: 'json',
    fetch: async () => new Response('{:version 9}\n', {
      headers: { 'content-type': 'application/edn' },
    }),
  });

  assert.deepEqual(await fram.version(), { version: 9 });
});

check('unknown response modes fail before any network request', async () => {
  assert.throws(
    () => framClient({ token: 'secret', format: 'yaml' }),
    /format must be 'edn' or 'json'/);
});

await Promise.all(checks);
console.log(`\ncloudflare worker client: ${checks.length}/${checks.length} PASS`);
