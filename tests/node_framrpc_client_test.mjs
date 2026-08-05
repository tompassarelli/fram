import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  FramRpcError,
  float64Term,
  float64Value,
  framClient,
  instantTerm,
  integerTerm,
  keywordTerm,
  lowerQueryPlan,
  stringTerm,
  tripleQuery,
  tripleTerm,
} from '../clients/node/framrpc.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const checks = [];
const I64_MIN = -(1n << 63n);
const expectedEngine = process.env.FRAM_EXPECTED_ENGINE ?? 'rpc/jvm';

async function check(label, body) {
  await body();
  checks.push(label);
  console.log(`  [PASS] ${label}`);
}

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolveListen);
  });
  const { port } = server.address();
  await new Promise((resolveClose, reject) => server.close(error => error ? reject(error) : resolveClose()));
  return port;
}

async function startDaemon(port, log, space) {
  const child = spawn(resolve(root, 'bin/fram-server'), ['serve', String(port), log, space], {
    cwd: root,
    env: {
      ...process.env,
      FRAM_SPACE_ID: space,
      FRAM_GRAPH_OPS_LOG: 'off',
      FRAM_DAEMON_QUIET: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  let ready = false;
  const startup = new Promise((resolveReady, reject) => {
    const timer = setTimeout(() => reject(new Error(`daemon startup timeout\n${output}`)), 60000);
    const consume = chunk => {
      output += chunk.toString();
      if (!ready && output.includes('Fram server listening')) {
        ready = true;
        clearTimeout(timer);
        resolveReady();
      }
    };
    child.stdout.on('data', consume);
    child.stderr.on('data', consume);
    child.once('exit', code => {
      if (!ready) {
        clearTimeout(timer);
        reject(new Error(`daemon exited during startup (${code})\n${output}`));
      }
    });
  });
  await startup;
  return {
    child,
    output: () => output,
    async stop() {
      if (child.exitCode !== null) return;
      child.kill('SIGTERM');
      const exited = new Promise(resolveExit => child.once('exit', resolveExit));
      const forced = new Promise(resolveForce => setTimeout(() => {
        if (child.exitCode === null) child.kill('SIGKILL');
        resolveForce();
      }, 10000));
      await Promise.race([exited, forced]);
    },
  };
}

const tmp = await mkdtemp(resolve(tmpdir(), 'fram-node-client-'));
const port = await freePort();
const space = `node-client-${process.pid}`;
const daemon = await startDaemon(port, resolve(tmp, 'history.framlog'), space);
const fram = framClient({ host: '127.0.0.1', port, space, requestTimeoutMs: 30000 });

try {
  await check('Term constructors preserve i64, float, recursive Triple, and Instant identity', async () => {
    assert.deepEqual(integerTerm(I64_MIN), ['integer', '-9223372036854775808']);
    assert(Object.is(float64Value(float64Term(-0)), -0));
    assert.deepEqual(
      tripleTerm('nested', keywordTerm('edge'), instantTerm(-2, 3)),
      ['triple', ['string', 'nested'], ['keyword', 'edge'], ['instant', '-2', '3']],
    );
    assert.throws(() => keywordTerm(''), /nonempty/);
    assert.throws(() => tripleTerm('\ud800', 'p', 'r'), /surrogate/);
    assert.throws(() => tripleQuery({ l: 'legacy' }), /unknown/);
  });

  await check('structured query lowering emits the closed query/plan record', async () => {
    const plan = lowerQueryPlan({
      find: 'titles',
      rules: [{
        head: { rel: 'titles', args: [{ var: 'entity' }, { var: 'title' }] },
        body: [{ rel: 'triple', args: [{ var: 'entity' }, keywordTerm('title'), { var: 'title' }] }],
      }],
    });
    assert.equal(plan[0], 'triple');
    assert.deepEqual(plan[1], keywordTerm('query/plan'));
  });

  let firstVersion;
  await check('version and status use direct binary FRAMRPC', async () => {
    const version = await fram.version();
    const status = await fram.status();
    assert.equal(version.servedVersion, 0n);
    assert.equal(status.result.engine, expectedEngine);
    assert.equal(status.result.liveCount, 0n);
  });

  await check('expected-version assert preserves recursive Terms exactly', async () => {
    const response = await fram.assert('@doc-a', keywordTerm('title'), 'Running with Wolves', {
      expectedVersion: 0n,
    });
    assert.equal(response.servedVersion, 1n);
    assert.equal(response.result.length, 1);
    assert.equal(response.result[0].changed, true);
    firstVersion = response.servedVersion;

    const nested = await fram.assert(
      tripleTerm('nested', keywordTerm('edge'), integerTerm(I64_MIN)),
      keywordTerm('observed-at'),
      instantTerm(-2, 3),
      { expectedVersion: firstVersion },
    );
    assert.equal(nested.servedVersion, 2n);
  });

  await check('batch is atomic and stale expected-version writes fail typed', async () => {
    const batch = await fram.batch([
      { op: 'assert', t1: '@doc-b', t2: keywordTerm('title'), t3: 'Runner Notes' },
      { op: 'assert', t1: '@doc-c', t2: keywordTerm('title'), t3: 'Other Notes' },
    ], { expectedVersion: 2n });
    assert.equal(batch.servedVersion, 3n);
    assert.deepEqual(batch.result.map(result => result.changed), [true, true]);

    await assert.rejects(
      fram.assert('@stale', keywordTerm('title'), 'must not land', { expectedVersion: 0n }),
      error => error instanceof FramRpcError && error.code === 'rpc/conflict'
        && error.retryable === true && error.servedVersion === 3n,
    );
    assert.equal((await fram.version()).servedVersion, 3n);
  });

  await check('scan pagination pins the served snapshot and carries its exact cursor', async () => {
    const pattern = { t2: keywordTerm('title') };
    const first = await fram.scan(pattern, { page: { limit: 2 } });
    assert.equal(first.servedVersion, 3n);
    assert.equal(first.result.length, 2);
    assert.equal(first.page.done, false);
    assert(first.page.nextCursor);

    const second = await fram.scan(pattern, {
      page: { limit: 2, cursor: first.page.nextCursor },
    });
    assert.equal(second.servedVersion, first.servedVersion);
    assert.equal(second.result.length, 1);
    assert.equal(second.page.done, true);
  });

  const titleQuery = {
    find: 'titles',
    rules: [{
      head: { rel: 'titles', args: [{ var: 'entity' }, { var: 'title' }] },
      body: [{ rel: 'triple', args: [{ var: 'entity' }, keywordTerm('title'), { var: 'title' }] }],
    }],
  };

  await check('recursive ask supports current and historical structured queries', async () => {
    const current = await fram.query(titleQuery, { timeoutMs: 5000 });
    assert.equal(current.result.length, 3);
    assert.equal(current.servedVersion, 3n);

    const historical = await fram.query(titleQuery, { asOf: firstVersion, timeoutMs: 5000 });
    assert.equal(historical.servedVersion, firstVersion);
    assert.deepEqual(historical.result, [[stringTerm('@doc-a'), stringTerm('Running with Wolves')]]);

    const events = await fram.query({
      find: 'events',
      rules: [{
        head: { rel: 'events', args: [{ var: 'where' }, { var: 'action' }, { var: 'value' }] },
        body: [{ rel: 'occurrence', args: [{ var: 'where' }, { var: 'action' }, { var: 'value' }] }],
      }],
    }, { since: { lowerExclusive: 1n, upper: 3n } });
    assert.equal(events.servedVersion, 3n);
    assert.equal(events.result.length, 3);
  });

  await check('v0.3.5 text-match runs through the same direct query operation', async () => {
    const response = await fram.query({
      find: 'matches',
      rules: [{
        head: { rel: 'matches', args: [{ var: 'entity' }] },
        body: [{
          rel: 'text-match',
          args: [{ var: 'entity' }, keywordTerm('title'), 'running'],
        }],
      }],
    });
    assert.deepEqual(response.result, [[stringTerm('@doc-a')]]);
  });

  await check('occurrence replay and validate retain typed payloads', async () => {
    const occurrences = await fram.occurrences({ page: { limit: 16 } });
    const validation = await fram.validate();
    assert(occurrences.result.length >= 4);
    assert.equal(validation.result.valid, true);
    assert(Array.isArray(validation.result.violations));
  });

  await check('lease acquire, check, renew, release, and stale check are exact', async () => {
    const acquired = await fram.leaseAcquire('builder', 'plangrep', 30000);
    assert(acquired.result.fence);
    const checked = await fram.leaseCheck(acquired.result.fence);
    assert.equal(checked.result.valid, true);

    const renewed = await fram.leaseRenew(acquired.result.fence, 30000);
    const released = await fram.leaseRelease(renewed.result.fence);
    assert.equal(released.result.released, true);
    const stale = await fram.leaseCheck(renewed.result.fence);
    assert.equal(stale.result.valid, false);
  });

  await check('retract completes the closed thirteen-operation client surface', async () => {
    const response = await fram.retract('@doc-c', keywordTerm('title'), 'Other Notes');
    assert.equal(response.result[0].changed, true);
    const scan = await fram.scan({ t1: '@doc-c' });
    assert.equal(scan.result.length, 0);
  });

  console.log(`\nnode FRAMRPC client: ${checks.length}/${checks.length} PASS`);
} catch (error) {
  console.error(`\nnode FRAMRPC client: FAILED\n${error.stack || error}\n${daemon.output()}`);
  process.exitCode = 1;
} finally {
  await daemon.stop();
  await rm(tmp, { recursive: true, force: true });
}
