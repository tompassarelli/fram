// worker-example.js — minimal runnable Cloudflare Worker over a Fram coordinator.
//
// Routes:
//   GET  /            usage
//   GET  /health      coordinator rpc/status (proves the whole chain)
//   POST /fact        body {"slot0":"@bench1","slot1":"title","slot2":"hello"}
//   GET  /facts?slot1=X   matching rows (&slot0=@id also supported)
//   GET  /bench?n=20  n sequential query round-trips, timing summary
//
// Config: SHIM_URL + FRAM_SPACE_ID vars, SHIM_TOKEN secret.
// The Worker holds NO state — every isolate, cold or warm, is one fetch() away
// from the durable coordinator behind the shim.
import { framClient, listValues, recordFields, tripleQuery } from './worker-client.js';

const json = (v, status = 200) =>
  new Response(JSON.stringify(v, null, 1) + '\n',
    { status, headers: { 'content-type': 'application/json' } });

export default {
  async fetch(request, env) {
    const fram = framClient({
      url: env.SHIM_URL,
      token: env.SHIM_TOKEN,
      space: env.FRAM_SPACE_ID,
    });
    const u = new URL(request.url);
    try {
      if (u.pathname === '/health') return json(await fram.status());

      if (u.pathname === '/fact' && request.method === 'POST') {
        const body = await request.json();
        const slot0 = body.slot0;
        const slot1 = body.slot1;
        const slot2 = body.slot2;
        return json(await fram.assert(slot0, slot1, slot2,
          { expectedVersion: body.expectedVersion }));
      }

      if (u.pathname === '/facts') {
        const pat = {
          slot0: u.searchParams.get('slot0') ?? u.searchParams.get('l'),
          slot1: u.searchParams.get('slot1') ?? u.searchParams.get('p'),
        };
        return json(await fram.query(tripleQuery(pat)));
      }

      if (u.pathname === '/bench') {
        const n = Math.min(200, Number(u.searchParams.get('n')) || 20);
        const q = tripleQuery({ slot1: u.searchParams.get('slot1') || 'title' });
        const times = [];
        let rows = 0;
        for (let i = 0; i < n; i++) {
          const t0 = Date.now();
          const res = await fram.query(q);
          times.push(Date.now() - t0);
          if (res.payload) {
            const [encodedRows] = recordFields(res.payload, 'query/rows', 1);
            rows = listValues(encodedRows).length;
          }
        }
        times.sort((a, b) => a - b);
        return json({
          n, rows,
          p50_ms: times[Math.floor(n / 2)],
          min_ms: times[0], max_ms: times[n - 1],
          note: 'each round-trip = Worker -> shim (HTTP) -> coordinator (TCP) -> back',
        });
      }

      return new Response(
        'fram worker\n' +
        '  GET  /health\n' +
        '  POST /fact   {"slot0":"@bench1","slot1":"title","slot2":"hello"}\n' +
        '  GET  /facts?slot1=title\n' +
        '  GET  /bench?n=20&slot1=title\n');
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 502);
    }
  },
};
