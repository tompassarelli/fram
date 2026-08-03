#!/usr/bin/env bash
set -euo pipefail

if (( $# < 2 )); then
  printf 'usage: %s OBSERVATION_DIR OBSERVATION_DIR [...]\n' "$0" >&2
  exit 2
fi

artifacts=(
  history.hex
  invalid-coordinate.tsv
  live-occurrences.hex
  live-propositions.hex
  malformed-term.tsv
  term-store-dump.bin
  digests.tsv
)

verify_dir() {
  local directory="$1"
  local count=0
  [[ -d "$directory" ]]
  for artifact in "${artifacts[@]}"; do
    [[ -f "$directory/$artifact" ]]
  done
  while IFS=$'\t' read -r name expected_size expected_digest; do
    [[ -n "$name" && "$name" != "digests.tsv" ]]
    [[ -f "$directory/$name" ]]
    actual_size="$(wc -c <"$directory/$name")"
    actual_digest="$(sha256sum "$directory/$name")"
    actual_digest="${actual_digest%% *}"
    [[ "$actual_size" == "$expected_size" ]]
    [[ "$actual_digest" == "$expected_digest" ]]
    count=$((count + 1))
  done <"$directory/digests.tsv"
  [[ "$count" == 6 ]]
}

reference="$1"
verify_dir "$reference"
shift
implementations=1
for candidate in "$@"; do
  verify_dir "$candidate"
  for artifact in "${artifacts[@]}"; do
    if ! cmp -s "$reference/$artifact" "$candidate/$artifact"; then
      printf 'native Stage 6 differential: %s differs between %s and %s\n' \
        "$artifact" "$reference" "$candidate" >&2
      exit 1
    fi
  done
  implementations=$((implementations + 1))
done

printf 'native Stage 6 differential: %d observation directories agree on 7/7 artifacts\n' \
  "$implementations"
