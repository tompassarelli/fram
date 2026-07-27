#!/usr/bin/env bash
# regen: bash bench/moat/k-writer-propagation.sh
# Same K=1,2,4,8 shape as bench/propagation; this receipt is intentionally pending.
set -euo pipefail
printf 'k-writer-propagation cannot-measure: bench/propagation/sweep.clj bootstraps from canonical .fram/code.log; W8 forbids that input. A scratch-only multi-writer adapter has not yet been implemented.\n'
