# Cloudflare response-codec baseline

This microbenchmark measures JavaScript serialization plus parsing for the EDN
and JSON shapes returned by the Fram server. It uses the exact EDN codec
exported by `~/code/fram/main/deploy/cloudflare/worker-client.js` and the
built-in JSON codec used by that client.

It does not measure the Babashka shim, JVM server, query engine, network,
Cloudflare Worker isolate, or application. Use it only to bound the local codec
cost; use an end-to-end deployment trace for architecture or capacity choices.

On Linux, reserve cores and record one run with:

```sh
bench-shield run 16 -- taskset -c 8-11,20-23 \
  node ~/code/fram/main/bench/cloudflare-codec/measure.mjs
```

Choose CPU IDs that exist on the machine and are included in its allowed CPU
list. The producer emits the batch samples, environment, payload sizes, and
median ratio as one JSON document. The checked-in observation is
`~/code/fram/main/bench/cloudflare-codec/results/2026-07-31.json`.
