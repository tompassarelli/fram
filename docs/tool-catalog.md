# Public tool catalog

**Status:** Current source-head MCP data surface.

`bin/fram-mcp` advertises exactly five public data verbs:

| Tool | Contract |
|---|---|
| `tell` | assert one Triple |
| `retract` | retract one exact Triple |
| `show` | return live Triples whose `slot0` matches |
| `ask` | run one validated structured query |
| `validate` | report structural integrity |

That advertised list is closed. Missing arguments and unknown names fail at the
boundary; dispatch additionally answers two unadvertised spellings of verbs
already in the list — `untell` for `retract`, and `query` as the internal name
`ask` normalizes to. The public process does not link or advertise
graph-authoring verbs. Those belong to a separate sealed control service.

## Current MCP value boundary

The kernel supports recursive typed Terms, but the current MCP write schemas
intentionally expose `subject`, `predicate`, and `object` as strings. `show`
takes one subject string. `ask` accepts a structured JSON query; its advertised
constant schema currently covers strings and numbers. This is a real edge
limitation, not a second kernel model.

Use the native CLI or the tagged Cloudflare JSON API when a request must carry
recursive Triples, Keywords, Bools, or Instants without string projection. Do
not send an undocumented JSON shape to MCP and assume its current permissive
decoder is a compatibility promise.

## `ask`

`ask` accepts the JSON equivalent of the structured query in
[`query-reference.md`](query-reference.md). It is lowered to the typed query
plan and sent over FRAMRPC; it is not a string query language.

```json
{
  "find": "titles",
  "rules": [
    {
      "head": {"rel": "titles", "args": [{"var": "item"}, {"var": "title"}]},
      "body": [
        {"rel": "triple", "args": [{"var": "item"}, ":title", {"var": "title"}]}
      ]
    }
  ]
}
```

## CLI is not the MCP catalog

`bin/fram` exposes human commands such as `scan`, `occurrences`, `version`, and
`status` in addition to the five data concepts above. Those commands map to the
closed native FRAMRPC protocol; they are not extra MCP tools. Local
migration/projection/admin commands are compatibility utilities and likewise do
not enlarge the public MCP catalog.

The executable catalog contract is
[`../tests/mcp_test.clj`](../tests/mcp_test.clj). The native operation boundary
is separately ratcheted by
[`../tests/native_rpc_boundary_ratchet_test.clj`](../tests/native_rpc_boundary_ratchet_test.clj).
