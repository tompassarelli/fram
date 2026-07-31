# Concurrency and writes

All writes go through one coordinator, so the agents you already run can keep the
graph current concurrently without corrupting state.

It is a single-writer daemon. Clients query and assert over a localhost socket;
writes serialize through one lock with **optimistic versioning** — each assert
carries a `base_version`, and conflicts are rejected for the caller to retry.
Rule-breaking writes (dependency cycles, dangling refs) are **rejected at
commit**.

The optimistic-versioning decision lives in one place,
[`../src/coord_commit.bclj`](../src/coord_commit.bclj), as a pure function over
commit intents: `version-conflict?` is one branch, `single` is a per-predicate
flag, so the guarantee generalizes across every single-valued predicate rather
than being re-implemented per call site.

## What the rule check does and does not guarantee

The rule check guarantees **referential integrity**: references resolve, the
vocabulary is closed, structure is sound.

It does *not* judge whether a write is *semantically* what you meant. That stays
with the author.

Honest framing on scope: these properties are proven under local test load on a
single machine. This is **not** distributed consensus.

## The receipt

[`../tests/coord_test.clj`](../tests/coord_test.clj) is an adversarial
concurrency and durability suite. Its headline case races 24 concurrent writers
setting *different* values for the same single-valued `(entity, predicate)` at
the same base version, and asserts three things: exactly one racer wins, the
other 23 come back `:conflict`, and exactly one live triple remains.

The suite also covers multi-valued idempotency — an identical link asserted twice
is a no-op leaving exactly one live edge — alongside durability and replay.

## Running the daemon

```sh
bin/fram-up                                            # start the warm, multi-agent-safe daemon
bin/fram-promote                                       # clean HEAD -> restart this checkout's daemon only
bin/fram tell 2026-01-01-090700 committed 2026-06-21   # writes route through the coordinator
```

`bin/fram-promote` is the checkout-first development path: it refuses tracked or
untracked dirt, captures the exact local commit, and restarts only Fram through
that checkout's `bin/fram-up --restart`. It never builds or switches a NixOS
generation — the Nix package remains the explicit release and system baseline.
