#!/usr/bin/env bash
# Regenerate chartroom/src/resolve.clj = Beagle-emitted resolver (ns resolve, from
# chartroom/src/resolve.bclj) ++ the hand-Clojure CLI tail (chartroom/src/resolve_cli.clj).
# resolve.clj is the load-target the daemon + tests (load-file ...); it's a build artifact.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle}"
tmp="$(mktemp)"
BEAGLE_EMIT_SRCLOC=0 "$BEAGLE/bin/beagle" build "$HERE/chartroom/src/resolve.bclj" "$tmp"
cat "$tmp" "$HERE/chartroom/src/resolve_cli.clj" > "$HERE/chartroom/src/resolve.clj"
rm -f "$tmp"
echo "regenerated chartroom/src/resolve.clj from resolve.bclj + resolve_cli.clj"
