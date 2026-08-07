#!/usr/bin/env bash
# Compare candidate CI failures with the pristine cutover baseline.
set -uo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: occurrence_native_ci_compare.sh <pristine.tsv> <candidate.tsv>" >&2
  exit 2
fi

pristine=$1
candidate=$2
scratch="$(mktemp -d)"
trap 'rm -rf "${scratch:?}"' EXIT INT TERM

normalize() {
  local source=$1
  awk -F '\t' '
    /^[[:space:]]*$/ || /^#/ { next }
    NF != 2 || $2 == "" {
      printf "%s:%d: expected producer<TAB>failure-id\n", FILENAME, FNR > "/dev/stderr"
      invalid = 1
      next
    }
    $1 != "vocab-residue" {
      printf "%s:%d: unknown failure producer %s\n", FILENAME, FNR, $1 > "/dev/stderr"
      invalid = 1
      next
    }
    { print $1 "\t" $2 }
    END { exit invalid }
  ' "$source"
}

for side in pristine candidate; do
  source_path=$pristine
  [ "$side" = candidate ] && source_path=$candidate
  if [ ! -r "$source_path" ]; then
    echo "known failure comparison: missing $side set $source_path" >&2
    exit 2
  fi
  if ! normalize "$source_path" | LC_ALL=C sort > "$scratch/$side"; then
    exit 2
  fi
  uniq -d "$scratch/$side" > "$scratch/$side.duplicates"
  if [ -s "$scratch/$side.duplicates" ]; then
    echo "known failure comparison: duplicate $side entries" >&2
    sed 's/^/  /' "$scratch/$side.duplicates" >&2
    exit 2
  fi
done

for side in pristine candidate; do
  awk -F '\t' '$1 == "vocab-residue" {print $2}' \
    "$scratch/$side" > "$scratch/$side.vocab-residue"
done

status=0
comm -13 "$scratch/pristine.vocab-residue" "$scratch/candidate.vocab-residue" \
  > "$scratch/vocab.new"
comm -23 "$scratch/pristine.vocab-residue" "$scratch/candidate.vocab-residue" \
  > "$scratch/vocab.resolved"

while IFS= read -r failure; do
  [ -n "$failure" ] && printf '  RESOLVED vocab-residue\t%s\n' "$failure"
done < "$scratch/vocab.resolved"

if [ -s "$scratch/vocab.new" ]; then
  echo "known failure comparison: candidate added vocab-residue failures" >&2
  while IFS= read -r failure; do
    [ -n "$failure" ] && printf '  NEW      vocab-residue\t%s\n' "$failure" >&2
  done < "$scratch/vocab.new"
  status=1
fi

if [ "$status" -eq 0 ]; then
  present="$(wc -l < "$scratch/candidate.vocab-residue")"
  resolved="$(wc -l < "$scratch/vocab.resolved")"
  printf 'known failure comparison: PASS — vocab candidate is a pristine subset (%s present, %s resolved)\n' \
    "$present" "$resolved"
fi
exit "$status"
