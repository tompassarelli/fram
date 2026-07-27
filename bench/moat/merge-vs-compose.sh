#!/usr/bin/env bash
# regen: bash bench/moat/merge-vs-compose.sh
# A receipt must drive the repository's world-composition API, not simulate it.
set -euo pipefail
printf 'merge-vs-compose cannot-measure: no stable repo-local CLI/API for composing divergent world lineages was found in this checkout; a shell selection simulation would not be a Fram receipt.\n'
