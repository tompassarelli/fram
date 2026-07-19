# R-1 apparatus — pre-production, test-only

Reification R-1 (North thread `019f79eb-f6a4`). Authored and executed BEFORE any
production code, against the completed B2 contract (`019f79eb-e8e0`, §1-§5). This
directory is **test-only apparatus**: it exercises **no** production/runtime/
package source. It lives under `tests/` and is therefore excluded from the Nix
closure (the package copies only `tests/fram_mcp.clj`), never touches `out/`, the
daemon, port 7977, canonical logs, or user state.

## What it proves

Two independent reference models — `:b2` (the accepted contract) and `:bprime`
(the rejected, revoked shape) — implemented in `lib/r1/model.clj`. The cells
assert each is **RED against B-prime** and **GREEN against B2**; any deviation
exits nonzero (`lib/r1/harness.clj`), and a negative-control cell proves the
harness itself fails loud.

| cell | file | claim |
|---|---|---|
| Cross-runtime identity oracle | `identity/kev.clj` (bb+JVM), `identity/kev.mjs` (node) | raw-byte `K_ev` = `[tx, bytes(unsigned-lex), rank]` is byte-identical across **bb == JVM == JS** |
| S1 post-flip byte-identical append | `cells/sensitivity.clj` | B2 preserves the legitimate later append (tx=2 multiplicity 2); B-prime suppresses it (1) |
| S2 in-flight fd-transfer admission | `cells/sensitivity.clj` | B2 = named residual (never silent); B-prime = silent loss of an acked write |
| S3 daemon-start + rename revert | `cells/sensitivity.clj` | B2 = no acked write revertible (fence-through-dirsync); B-prime = revertible |
| S4 exact-mode restore | `cells/sensitivity.clj`, `cells/mode_cells.clj` | B2 restores exact 0600/0660; B-prime clobbers to 0644 (real `chmod`) |
| Generation-bound residual oracle | `cells/residual.clj` | recorded `:src_telem_bytes`/`:src_telem_sha` exactly measure the consumed prefix; legit identical append beyond the boundary is authored |
| K0-K8 kill matrix + reverse-pin | `cells/kmatrix.clj` | doctor roll-forward/back by inode/sha (never `:phase`); projection invariant modulo one flip event; post-revert append preserved |
| External kill runner + acked ledger | `cells/kill_ledger.clj`, `cells/actor.clj` | real SIGKILL/SIGSTOP; every acked write durable (A6); SIGSTOP holder detected + reaped |
| 104k reference perf/RSS | `cells/perf_104k.clj` | fact-fold Δ ≤ 5%; lazy event index ≤ 3.5 s; index retained heap ≤ 512 MiB |

## Run

```sh
tests/apparatus_r1/run.sh        # all cells + tri-runtime diff + negative control; exit 0 = green
```

Individual cells:

```sh
bb -cp tests/apparatus_r1/lib tests/apparatus_r1/cells/sensitivity.clj
clojure -J-Xmx512m -Sdeps '{:paths ["tests/apparatus_r1/lib"]}' -M tests/apparatus_r1/cells/perf_104k.clj /tmp/r1-perf
```

All corpora + processes are scratch under a single `/tmp` dir, cleaned on success,
failure, and kill (`run.sh` trap). Ports, if ever needed, start at 8500; nothing
here binds one.

## Deliberate scope boundaries (limitations)

- The admission/daemon-start cells (S2/S3, "old/new daemon-start race",
  "unavailable admission surface") are proven at the **reference-model** verdict
  level, NOT by starting a real `fram` daemon — starting one would touch
  production binaries/ports/state, which is out of bounds for R-1. Real process
  evidence (kill, SIGSTOP, fd-transfer, acked-ledger completeness, cleanup) is
  supplied by `cells/kill_ledger.clj` against scratch logs.
- The `:bprime`/`:b2` models are independent oracles of the contract laws, not
  extractions of production code. They intentionally do not link `out/`.
- The 104k perf numbers are **reference** figures on a synthetic corpus under
  the JVM (compiled), not a production benchmark. Whole-process VmHWM is reported
  as a note (it carries the irreducible JVM baseline); the asserted memory bar is
  the index's **retained heap** measured via `Runtime` after GC.
- The node `K_ev` oracle extracts `:tx` and the generation fields via the fixed
  wire grammar (regex), which is exact for the contract's literal record template.
