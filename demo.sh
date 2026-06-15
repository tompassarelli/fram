#!/usr/bin/env bash
# Chelonia demo — the value loop over the bundled fictional "launch a personal
# website" threads. Nobody maintains a board; it's all computed from the same
# Markdown you'd write anyway.
#
# Record a cast:  asciinema rec -c ./demo.sh demo.cast  &&  agg demo.cast demo.gif
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
export CHELONIA_THREADS="$HERE/threads" CHELONIA_LOG="/tmp/chelonia-demo.log"
rm -f "$CHELONIA_LOG"

pause() { [ -t 1 ] && sleep 1 || true; }   # pace for recording; instant non-interactively
run()   { printf '\n\033[1;36m$ chelonia %s\033[0m\n' "$*"; "$HERE/bin/chelonia" "$@"; pause; }

printf '\033[2m# Chelonia — a queryable dependency graph for work + life, computed from Markdown\033[0m\n'
run import      # fold the Markdown threads into a claim graph
run ready       # what is actually actionable now
run blocked     # what is waiting, and on what
run leverage    # the boring keystone that unblocks the most — a flat list CANNOT show this
run next        # ranked: leverage + deadline + momentum
printf '\n\033[2m# Nobody updated a board. leverage just named the unglamorous task that frees the most work.\033[0m\n'
rm -f "$CHELONIA_LOG"
