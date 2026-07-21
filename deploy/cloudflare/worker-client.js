// worker-client.js — zero-dependency Fram client for Cloudflare Workers (and node).
//
// Talks to the shim (deploy/cloudflare/shim.clj) over HTTP with a bearer token;
// the shim forwards one line of EDN per request to the coordinator daemon's TCP
// socket and returns the daemon's EDN reply verbatim.
//
//   import { framClient, kw, raw, tripleQuery } from './worker-client.js';
//   const fram = framClient({ url: 'http://127.0.0.1:8787', token: '...' });
//   await fram.assert('@bench1', 'title', 'hello');      // -> { ok: 1 }
//   await fram.query(tripleQuery({ p: 'title' }));       // -> { ok: [['@bench1','hello']], ... }
//
// Transport is plain fetch(), so the SAME file runs unmodified in a Worker
// isolate and under node >= 18 (local testing / benchmark harnesses).
//
// The EDN codec below covers ONLY the coordinator wire subset actually used:
// maps, vectors, strings, integers, floats, keywords, nil, booleans (plus
// lists decoded as arrays, defensively). Sets / tagged literals / chars are
// out of scope and throw loudly.

// ---------------------------------------------------------------- EDN encode

export class Keyword {
  constructor(name) { this.name = name; }
  toString() { return ':' + this.name; }
  toJSON() { return ':' + this.name; }
}
export const kw = (name) => new Keyword(name);

// escape hatch: embed a pre-encoded EDN string verbatim (query('{:find ...}'))
export class RawEdn {
  constructor(edn) { this.edn = edn; }
}
export const raw = (edn) => new RawEdn(edn);

export function ednEncode(v) {
  if (v === null || v === undefined) return 'nil';
  if (v instanceof Keyword) return ':' + v.name;
  if (v instanceof RawEdn) return v.edn;
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error('EDN encode: non-finite number');
    return String(v); // integers print as ints, floats keep their point
  }
  // JSON string escaping (\" \\ \n and \uXXXX control escapes) is valid EDN —
  // clojure.edn reads all of it — and guarantees the request stays ONE line.
  if (typeof v === 'string') return JSON.stringify(v);
  if (Array.isArray(v)) return '[' + v.map(ednEncode).join(' ') + ']';
  if (typeof v === 'object') {
    // plain-object keys become EDN keywords — exactly how every coordinator
    // request map is keyed ({:op ... :te ... :query {:find ... :rules [...]}}).
    return '{' + Object.entries(v)
      .filter(([, val]) => val !== undefined)
      .map(([k, val]) => ':' + k + ' ' + ednEncode(val))
      .join(' ') + '}';
  }
  throw new Error('EDN encode: unsupported type ' + typeof v);
}

// ---------------------------------------------------------------- EDN decode

export function ednDecode(text) {
  const s = text;
  let i = 0;
  const err = (msg) => { throw new Error(`EDN decode: ${msg} at ${i} in ${s.slice(Math.max(0, i - 20), i + 20)}`); };
  const ws = () => { while (i < s.length && /[\s,]/.test(s[i])) i++; };
  const delim = (c) => c === undefined || /[\s,\[\]{}()"]/.test(c);

  function value() {
    ws();
    if (i >= s.length) err('unexpected end of input');
    const c = s[i];
    if (c === '{') return map();
    if (c === '[') return seq(']');
    if (c === '(') return seq(')');
    if (c === '"') return string();
    if (c === ':') return keyword();
    if (c === '#') err('sets/tagged literals unsupported (out of wire subset)');
    if (c === '-' || c === '+' || (c >= '0' && c <= '9')) return number();
    return symbol();
  }
  function map() {
    i++; // {
    const out = {};
    for (;;) {
      ws();
      if (s[i] === '}') { i++; return out; }
      const k = value();
      const v = value();
      out[k instanceof Keyword ? k.name : String(k)] = v;
    }
  }
  function seq(close) {
    i++; // [ or (
    const out = [];
    for (;;) {
      ws();
      if (s[i] === close) { i++; return out; }
      if (i >= s.length) err(`unterminated ${close === ']' ? 'vector' : 'list'}`);
      out.push(value());
    }
  }
  function string() {
    i++; // "
    let out = '';
    for (;;) {
      if (i >= s.length) err('unterminated string');
      const c = s[i++];
      if (c === '"') return out;
      if (c === '\\') {
        const e = s[i++];
        if (e === 'n') out += '\n';
        else if (e === 't') out += '\t';
        else if (e === 'r') out += '\r';
        else if (e === '"') out += '"';
        else if (e === '\\') out += '\\';
        else if (e === 'u') { out += String.fromCharCode(parseInt(s.slice(i, i + 4), 16)); i += 4; }
        else err(`unknown escape \\${e}`);
      } else out += c;
    }
  }
  function keyword() {
    i++; // :
    const start = i;
    while (i < s.length && !delim(s[i])) i++;
    if (i === start) err('empty keyword');
    return new Keyword(s.slice(start, i));
  }
  function number() {
    const start = i;
    if (s[i] === '-' || s[i] === '+') i++;
    while (i < s.length && !delim(s[i])) i++;
    const tok = s.slice(start, i);
    const n = Number(tok);
    if (Number.isNaN(n)) err(`bad number '${tok}'`);
    return n;
  }
  function symbol() {
    const start = i;
    while (i < s.length && !delim(s[i])) i++;
    const tok = s.slice(start, i);
    if (tok === 'nil') return null;
    if (tok === 'true') return true;
    if (tok === 'false') return false;
    return tok; // bare symbols surface as strings (defensive; not in the wire subset)
  }

  const v = value();
  ws();
  return v;
}

// ------------------------------------------------------------- query helper

// Build the daemon's single-rule Datalog for a triple pattern. null/undefined
// slots become variables and form the result row, constants filter:
//   tripleQuery({ p: 'title' })          -> rows [l, r] of every title fact
//   tripleQuery({ l: '@bench1' })        -> rows [p, r] of one subject
//   tripleQuery({ l: '@b', p: 'title' }) -> rows [r]
// This shape ('one rule, all-triple body') rides the daemon's INDEX fast path.
export function tripleQuery({ l, p, r } = {}) {
  const vars = [];
  const arg = (v, name) => (v === null || v === undefined)
    ? (vars.push(name), { var: name }) : v;
  const args = [arg(l, 'l'), arg(p, 'p'), arg(r, 'r')];
  const head = vars.length ? vars.map((n) => ({ var: n })) : args;
  return {
    find: 'out',
    rules: [{ head: { rel: 'out', args: head },
              body: [{ rel: 'triple', args }] }],
  };
}

// ------------------------------------------------------------------- client

export function framClient({ url, host, port, token, fetch: fetchImpl } = {}) {
  if (!token) throw new Error('framClient: token required');
  const base = (url || `http://${host || '127.0.0.1'}:${port || 8787}`).replace(/\/+$/, '');
  const doFetch = fetchImpl || fetch;

  async function send(path, reqMap) {
    const res = await doFetch(base + path, {
      method: 'POST',
      headers: { authorization: 'Bearer ' + token, 'content-type': 'application/edn' },
      body: ednEncode(reqMap),
    });
    const text = (await res.text()).trim();
    if (!res.ok) throw new Error(`fram shim HTTP ${res.status}: ${text.slice(0, 300)}`);
    return ednDecode(text);
  }

  const withBase = (opts = {}) => (opts.base === undefined ? {} : { base: opts.base });

  return {
    // -> {ok: <version>} | {reject: [...], version: n}
    assert: (l, p, r, opts) => send('/assert', { op: kw('assert'), te: l, p, r, ...withBase(opts) }),
    retract: (l, p, r, opts) => send('/assert', { op: kw('retract'), te: l, p, r, ...withBase(opts) }),
    // q: a query map (plain JS object, keys keywordized) or a pre-encoded EDN
    // string. -> {ok: [[...]...], version: n, engine: 'index'|'scan'}
    query: (q, opts = {}) => send('/q', {
      op: kw('query'),
      query: typeof q === 'string' ? raw(q) : q,
      ...(opts.scan ? { scan: true } : {}),
      ...(opts.timeoutMs ? { 'query-timeout-ms': opts.timeoutMs } : {}),
    }),
    // historical read at log version seq -> {ok: [...], 'as-of': seq, ...}
    asOf: (seq, q) => send('/q', { op: kw('as-of'), seq, query: typeof q === 'string' ? raw(q) : q }),
    version: () => send('/q', { op: kw('version') }),
    status: () => send('/q', { op: kw('status') }),
    validate: () => send('/q', { op: kw('validate') }),
    raw: (path, reqMap) => send(path, reqMap), // escape hatch for other allowed ops
  };
}
