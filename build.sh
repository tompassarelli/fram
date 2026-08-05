#!/usr/bin/env bash
# Recompile Fram's hosted Beagle (.bclj) sources to Clojure into out/.
#
# You do NOT need this to run Fram — the compiled Clojure in out/ is
# committed and runs on babashka (bin/fram). You only need this to rebuild
# from the hosted .bclj sources, which requires Beagle at $BEAGLE_HOME
# (default ~/code/beagle/main), entered via direnv. Canonical .bgl Native Core
# modules are built through fram:bin/fram-native-build instead.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle/main}"
MANIFEST_DIR="$HERE/build/generated-targets.d"

shopt -s nullglob
fragments=("$MANIFEST_DIR"/*.tsv)
shopt -u nullglob
if ((${#fragments[@]} == 0)); then
  echo "build.sh: no generation manifests in $MANIFEST_DIR" >&2
  exit 1
fi

for fragment in "${fragments[@]}"; do
  line_number=0
  while IFS=$'\t' read -r kind source destination extra ||
        [[ -n "$kind$source$destination$extra" ]]; do
    ((line_number += 1))
    [[ -z "$kind" || "$kind" == \#* ]] && continue
    if [[ -n "$extra" || -z "$source" || -z "$destination" ]]; then
      echo "build.sh: invalid manifest row at $fragment:$line_number" >&2
      exit 1
    fi

    source_path="$HERE/$source"
    destination_path="$HERE/$destination"
    mkdir -p "$(dirname "$destination_path")"
    case "$kind" in
      beagle)
        BEAGLE_EMIT_SRCLOC=0 direnv exec "$BEAGLE" "$BEAGLE/bin/beagle-build" \
          "$source_path" "$destination_path" >/dev/null
        label="${destination#out/}"
        echo "  built ${label%.clj}"
        ;;
      copy)
        cp "$source_path" "$destination_path"
        ;;
      *)
        echo "build.sh: unknown generation kind '$kind' at $fragment:$line_number" >&2
        exit 1
        ;;
    esac
  done < "$fragment"
done
echo "fram built -> $OUT"
