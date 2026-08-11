import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { access, mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = resolve(root, 'clients/node');
const scratch = await mkdtemp(resolve(tmpdir(), 'framrpc-types-'));

function run(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
  assert.equal(
    result.status,
    0,
    `${command} ${args.join(' ')} failed\n${result.stdout}${result.stderr}`,
  );
  return result.stdout;
}

function usable(command) {
  const result = spawnSync(command, ['--version'], { encoding: 'utf8' });
  return result.status === 0;
}

async function typescriptCompiler() {
  if (process.env.FRAM_TSC) {
    await access(process.env.FRAM_TSC);
    return process.env.FRAM_TSC;
  }
  const northTsc = resolve(
    homedir(),
    'code/north/main/sdk/node_modules/typescript/bin/tsc',
  );
  try {
    await access(northTsc);
    return northTsc;
  } catch {
    if (usable('tsc')) return 'tsc';
    throw new Error(
      'TypeScript is unavailable; set FRAM_TSC to an explicit compiler path',
    );
  }
}

try {
  run('npm', ['pack', '--json', '--pack-destination', scratch], packageRoot);
  const archives = (await readdir(scratch)).filter(path => path.endsWith('.tgz'));
  assert.equal(archives.length, 1, 'npm pack must produce exactly one archive');

  await writeFile(resolve(scratch, 'package.json'), JSON.stringify({
    name: 'framrpc-types-consumer',
    private: true,
    type: 'module',
  }));
  await writeFile(resolve(scratch, 'tsconfig.json'), JSON.stringify({
    compilerOptions: {
      target: 'ES2022',
      module: 'NodeNext',
      moduleResolution: 'NodeNext',
      strict: true,
      noEmit: true,
      skipLibCheck: false,
      exactOptionalPropertyTypes: true,
    },
    include: ['consumer.mts'],
  }));
  await writeFile(resolve(scratch, 'consumer.mts'), `
import {
  FRAMRPC_MAX_BATCH_ACTIONS,
  keywordTerm,
} from '@tompassarelli/framrpc';
import type { FramClient, Term } from '@tompassarelli/framrpc';
import {
  SCHEMA_MAX_BATCH_ACTIONS,
  SCHEMA_MAX_READ_PAGES,
  SchemaConstraintError,
  schemaClient,
} from '@tompassarelli/framrpc/schema';
import type {
  SchemaConstraintCode,
  UpdateUniqueManyMutation,
  UpdateUniqueMutation,
} from '@tompassarelli/framrpc/schema';

declare const fram: FramClient;
const schema = schemaClient(fram);
const state: Term = keywordTerm('draft');
const update: UpdateUniqueMutation = {
  identity: { predicate: keywordTerm('page/slug'), value: 'home' },
  field: {
    predicate: keywordTerm('page/state'),
    values: [keywordTerm('canonical')],
    cardinality: 'single',
    allowedCurrent: [state],
  },
};
const many: UpdateUniqueManyMutation = {
  updates: [{
    identity: update.identity,
    fields: [{
      predicate: keywordTerm('page/tag'),
      values: ['wiki'],
      cardinality: 'multi',
    }],
  }],
};
const code: SchemaConstraintCode = 'schema/current-value-rejected';
const protocolActions: 247 = FRAMRPC_MAX_BATCH_ACTIONS;
const schemaActions: typeof FRAMRPC_MAX_BATCH_ACTIONS = SCHEMA_MAX_BATCH_ACTIONS;
const pages: 2 = SCHEMA_MAX_READ_PAGES;
void schema.updateUnique(update);
void schema.updateUniqueMany(many);
void SchemaConstraintError;
void code;
void protocolActions;
void schemaActions;
void pages;
`);

  run('npm', [
    'install',
    '--ignore-scripts',
    '--no-audit',
    '--no-fund',
    '--package-lock=false',
    resolve(scratch, archives[0]),
  ], scratch);
  const tsc = await typescriptCompiler();
  run(tsc, ['--project', resolve(scratch, 'tsconfig.json')], scratch);
  console.log('node package types: packed NodeNext consumer passed');
} finally {
  await rm(scratch, { recursive: true, force: true });
}
