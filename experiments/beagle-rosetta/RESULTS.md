# #49 — Beagle breadth/coverage corpus (Rosetta-style)

Six classic algorithms (`fib`, `fact`, `gcd`, `sum-to`, `collatz-steps`, `fizzbuzz`) written ONCE in
typed Beagle (`rosetta.bclj`), then compiled by Beagle (parse→check→emit) to **all four live targets**
and **run-verified** on clj. Demonstrates breadth (does the language express + compile real programs)
AND the multi-target projection (one homoiconic source → many real languages — the talk's language-axis
differentiator), with running code.

## Compile — 4/4 targets, 0 errors (one source, `experiments/beagle-rosetta/rosetta.bclj`)
| target | result | output (`built/`) | genuine target code? |
|---|---|---|---|
| clj  | 1 built, 0 err | core.clj  | yes — Clojure |
| js   | 1 built, 0 err | core.js   | yes — `function fib(n){ return ((n<2)?n:...) }` |
| nix  | 1 built, 0 err | core.nix  | yes — `fib = n: if (n < 2) then n else ...` |
| odin | 1 built, 0 err | core.odin | yes — `fib :: proc(n: int) -> int { ... }`, `package core` |

(odin's `beagle-build-all --out` mislabels the file `core.clj`; the *content* is real Odin — saved
here as `core.odin`.)

## Run — 9/9 correct (clj target, `run.clj`)
`fib(10)=55 · fact(5)=120 · gcd2(48,36)=12 · sum-to(100)=5050 · collatz-steps(27,0)=111 ·
fizzbuzz(15)=FizzBuzz · fizzbuzz(4)=4 · fizzbuzz(9)=Fizz · fizzbuzz(10)=Buzz` — all pass.

## Honest scope
- Coverage + correctness (compile all-targets + run-on-clj), NOT a speed differentiator — both
  Beagle and a mature competitor pass standard tasks; this is a parity/breadth receipt.
- Run-verified on clj (the host-executable target); js/nix/odin verified at **compile** (genuine
  target code emitted, 0 errors) — executing the js/odin/nix binaries is a further step.
- Reproduce: `beagle-build-all rosetta.bclj --out <dir>` (swap `#lang beagle/clj` for js/nix/odin),
  then `bb -cp <dir> run.clj`.
