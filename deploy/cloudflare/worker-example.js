// worker-example.js — minimal runnable Cloudflare Worker over a Fram coordinator.
//
// Routes:
//   GET  /            usage
//   GET  /health      coordinator :status (proves the whole chain)
//   POST /fact        body {"l":"@bench1","p":"title","r":"hello","base":0?} -> assert
//   GET  /facts?p=X   all (l r) rows for predicate X   (&l=@id also supported)
//   GET  /bench?n=20  n sequential query round-trips, timing summary
//
// Config: SHIM_URL var (wrangler.toml) + SHIM_TOKEN secret (wrangler secret put).
// The Worker holds NO state — every isolate, cold or warm, is one fetch() away
// from the durable coordinator behind the shim.
import { framClient, tripleQuery } from './worker-client.js';

const json = (v, status = 200) =>
  new Response(JSON.stringify(v, null, 1) + '\n',
    { status, headers: { 'content-type': 'application/json' } });

export default {
  async fetch(request, env) {
    const fram = framClient({ url: env.SHIM_URL, token: env.SHIM_TOKEN });
    const u = new URL(request.url);
    try {
      if (u.pathname === '/health') return json(await fram.status());

      if (u.pathname === '/fact' && request.method === 'POST') {
        const { l, p, r, base } = await request.json();
        return json(await fram.assert(l, p, r, { base }));
      }

      if (u.pathname === '/facts') {
        const pat = { l: u.searchParams.get('l'), p: u.searchParams.get('p') };
        return json(await fram.query(tripleQuery(pat)));
      }

      if (u.pathname === '/bench') {
        const n = Math.min(200, Number(u.searchParams.get('n')) || 20);
        const q = tripleQuery({ p: u.searchParams.get('p') || 'title' });
        const times = [];
        let rows = 0;
        for (let i = 0; i < n; i++) {
          const t0 = Date.now();
          const res = await fram.query(q);
          times.push(Date.now() - t0);
          rows = res.ok ? res.ok.length : 0;
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
        '  POST /fact   {"l":"@bench1","p":"title","r":"hello"}\n' +
        '  GET  /facts?p=title\n' +
        '  GET  /bench?n=20&p=title\n');
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 502);
    }
  },
};
