import { readFileSync } from 'node:fs';
import os from 'node:os';
import process from 'node:process';

import {
  ednDecode,
  ednEncode,
  kw,
} from '../../deploy/cloudflare/worker-client.js';

const WARMUP_ITERATIONS = 100;
const CASES = [
  {
    name: 'one-row-response',
    rows: [['@bulk', 'value-0']],
    iterations: 10_000,
    batches: 7,
  },
  {
    name: 'thousand-row-response',
    rows: Array.from({ length: 1_000 }, (_, i) => ['@bulk', `value-${i}`]),
    iterations: 100,
    batches: 5,
  },
];

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function round(value) {
  return Number(value.toFixed(6));
}

function timeBatches(operation, iterations, batches) {
  let checksum = 0;
  for (let i = 0; i < WARMUP_ITERATIONS; i++) checksum += operation();

  const samples = [];
  for (let batch = 0; batch < batches; batch++) {
    const start = process.hrtime.bigint();
    for (let i = 0; i < iterations; i++) checksum += operation();
    const elapsedNs = Number(process.hrtime.bigint() - start);
    samples.push(elapsedNs / 1_000_000 / iterations);
  }

  if (checksum === 0) throw new Error('benchmark result was not consumed');
  return {
    samples_ms_per_operation: samples.map(round),
    median_ms_per_operation: round(median(samples)),
    min_ms_per_operation: round(Math.min(...samples)),
    max_ms_per_operation: round(Math.max(...samples)),
  };
}

function benchmarkCase({ name, rows, iterations, batches }) {
  // These are the same logical server reply. Keywords are EDN values but
  // become unqualified strings in the server's Cheshire JSON response.
  const ednValue = { ok: rows, version: 1_000, engine: kw('index') };
  const jsonValue = { ok: rows, version: 1_000, engine: 'index' };
  const ednWire = ednEncode(ednValue);
  const jsonWire = JSON.stringify(jsonValue);

  const edn = timeBatches(() => {
    const decoded = ednDecode(ednEncode(ednValue));
    return decoded.ok.length + decoded.version;
  }, iterations, batches);
  const json = timeBatches(() => {
    const decoded = JSON.parse(JSON.stringify(jsonValue));
    return decoded.ok.length + decoded.version;
  }, iterations, batches);

  if (ednDecode(ednWire).ok.length !== rows.length
      || JSON.parse(jsonWire).ok.length !== rows.length) {
    throw new Error(`${name}: codec round-trip changed the row count`);
  }

  return {
    name,
    rows: rows.length,
    iterations_per_batch: iterations,
    batches,
    edn_bytes: Buffer.byteLength(ednWire),
    json_bytes: Buffer.byteLength(jsonWire),
    edn,
    json,
    edn_over_json_median: round(
      edn.median_ms_per_operation / json.median_ms_per_operation),
  };
}

function procField(name) {
  try {
    const line = readFileSync('/proc/self/status', 'utf8')
      .split('\n')
      .find((entry) => entry.startsWith(`${name}:`));
    return line?.slice(line.indexOf(':') + 1).trim() ?? null;
  } catch {
    return null;
  }
}

const report = {
  schema: 'fram-cloudflare-codec-v1',
  measured_at: new Date().toISOString(),
  boundary: 'in-process JavaScript serialization plus parsing; no shim, JVM, network, Worker isolate, or query execution',
  source: '~/code/fram/main/deploy/cloudflare/worker-client.js',
  environment: {
    node: process.version,
    platform: process.platform,
    architecture: process.arch,
    cpu_model: os.cpus()[0]?.model ?? null,
    logical_cpu_count: os.cpus().length,
    cpus_allowed_list: procField('Cpus_allowed_list'),
    load_average: os.loadavg().map(round),
  },
  warmup_iterations: WARMUP_ITERATIONS,
  cases: CASES.map(benchmarkCase),
};

process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
