import type {
  FramClient,
  MutationActionResult,
  Term,
  TermInput,
} from '@tompassarelli/framrpc';

export { FRAMRPC_MAX_BATCH_ACTIONS as SCHEMA_MAX_BATCH_ACTIONS } from '@tompassarelli/framrpc';
export const SCHEMA_MAX_CONFLICT_RETRIES: 32;
export const SCHEMA_MAX_REQUIRE_UNIQUE: 247;
export const SCHEMA_MAX_GUARD_CONCURRENCY: 8;
export const SCHEMA_MAX_READ_PAGES: 2;

export const SCHEMA_ERROR_CODES: Readonly<{
  INVALID_INPUT: 'schema/invalid-input';
  INVALID_RESPONSE: 'schema/invalid-response';
  IDENTITY_EXISTS: 'schema/identity-exists';
  IDENTITY_MISSING: 'schema/identity-missing';
  DUPLICATE_IDENTITY: 'schema/duplicate-identity';
  DUPLICATE_UPDATE_TARGET: 'schema/duplicate-update-target';
  REQUIRED_IDENTITY_MISSING: 'schema/required-identity-missing';
  CURRENT_VALUE_REJECTED: 'schema/current-value-rejected';
  ACTION_LIMIT: 'schema/action-limit';
  CONFLICT_EXHAUSTED: 'schema/conflict-exhausted';
}>;

export type SchemaConstraintCode = typeof SCHEMA_ERROR_CODES[keyof typeof SCHEMA_ERROR_CODES];

export class SchemaConstraintError extends Error {
  constructor(code: SchemaConstraintCode, message: string, options?: {
    detail?: unknown;
    cause?: unknown;
  });
  readonly code: SchemaConstraintCode;
  readonly detail: unknown;
}

export type FieldCardinality = 'single' | 'multi';

export interface SchemaField {
  predicate: TermInput;
  value: TermInput;
  cardinality?: FieldCardinality;
}

export interface UniqueIdentity {
  predicate: TermInput;
  value: TermInput;
}

export interface RequiredUniqueIdentity extends UniqueIdentity {
  subject: TermInput;
}

export interface UniqueMutation {
  subject: TermInput;
  identity: UniqueIdentity;
  fields: readonly SchemaField[];
  requireUnique?: readonly RequiredUniqueIdentity[];
}

export interface SingleUpdateField {
  predicate: TermInput;
  values: readonly TermInput[];
  cardinality: 'single';
  allowedCurrent?: readonly TermInput[];
}

export interface MultiUpdateField {
  predicate: TermInput;
  values: readonly TermInput[];
  cardinality: 'multi';
  allowedCurrent?: never;
}

export type UpdateField = SingleUpdateField | MultiUpdateField;

export interface UpdateUniqueMutation {
  identity: UniqueIdentity;
  field: UpdateField;
  requireUnique?: readonly RequiredUniqueIdentity[];
}

export interface UniqueTargetUpdate {
  identity: UniqueIdentity;
  fields: readonly UpdateField[];
}

export interface UpdateUniqueManyMutation {
  updates: readonly UniqueTargetUpdate[];
  requireUnique?: readonly RequiredUniqueIdentity[];
}

export interface SchemaMutationResult {
  readonly subject: Term;
  readonly created: boolean;
  readonly changed: boolean;
  readonly servedVersion: bigint;
  readonly result: MutationActionResult[];
}

export interface SchemaBatchMutationResult {
  readonly subjects: readonly Term[];
  readonly changed: boolean;
  readonly servedVersion: bigint;
  readonly result: MutationActionResult[];
}

export interface SchemaClientOptions {
  maxConflictRetries?: number;
  queryTimeoutMs?: number;
}

export interface SchemaClient {
  replaceSingle(
    subject: TermInput,
    predicate: TermInput,
    value: TermInput,
  ): Promise<SchemaMutationResult>;
  createUnique(input: UniqueMutation): Promise<SchemaMutationResult>;
  upsertUnique(input: UniqueMutation): Promise<SchemaMutationResult>;
  updateUnique(input: UpdateUniqueMutation): Promise<SchemaMutationResult>;
  updateUniqueMany(input: UpdateUniqueManyMutation): Promise<SchemaBatchMutationResult>;
}

export function schemaClient(
  fram: Pick<FramClient, 'version' | 'query' | 'scan' | 'batch'>,
  options?: SchemaClientOptions,
): SchemaClient;
