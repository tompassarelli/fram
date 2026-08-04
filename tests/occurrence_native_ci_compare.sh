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
    $1 != "catalog-drift" && $1 != "vocab-residue" && $1 != "latency-delay-hook" {
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

for producer in catalog-drift vocab-residue latency-delay-hook; do
  awk -F '\t' -v producer="$producer" '$1 == producer {print $2}' \
    "$scratch/pristine" > "$scratch/pristine.$producer"
  awk -F '\t' -v producer="$producer" '$1 == producer {print $2}' \
    "$scratch/candidate" > "$scratch/candidate.$producer"
done

status=0
comm -23 "$scratch/pristine.catalog-drift" "$scratch/candidate.catalog-drift" \
  > "$scratch/catalog.missing"
comm -13 "$scratch/pristine.catalog-drift" "$scratch/candidate.catalog-drift" \
  > "$scratch/catalog.new"
if [ -s "$scratch/catalog.missing" ] || [ -s "$scratch/catalog.new" ]; then
  echo "known failure comparison: catalog-drift must equal pristine" >&2
  while IFS= read -r failure; do
    [ -n "$failure" ] && printf '  MISSING  catalog-drift\t%s\n' "$failure" >&2
  done < "$scratch/catalog.missing"
  while IFS= read -r failure; do
    [ -n "$failure" ] && printf '  NEW      catalog-drift\t%s\n' "$failure" >&2
  done < "$scratch/catalog.new"
  status=1
fi

comm -23 "$scratch/pristine.latency-delay-hook" "$scratch/candidate.latency-delay-hook" \
  > "$scratch/latency.missing"
comm -13 "$scratch/pristine.latency-delay-hook" "$scratch/candidate.latency-delay-hook" \
  > "$scratch/latency.new"
if [ -s "$scratch/latency.missing" ] || [ -s "$scratch/latency.new" ]; then
  echo "known failure comparison: latency-delay-hook must equal pristine" >&2
  while IFS= read -r failure; do
    [ -n "$failure" ] && printf '  MISSING  latency-delay-hook\t%s\n' "$failure" >&2
  done < "$scratch/latency.missing"
  while IFS= read -r failure; do
    [ -n "$failure" ] && printf '  NEW      latency-delay-hook\t%s\n' "$failure" >&2
  done < "$scratch/latency.new"
  status=1
fi

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
  printf 'known failure comparison: PASS — catalog exact; vocab candidate is a pristine subset (%s present, %s resolved)\n' \
    "$present" "$resolved"
fi
exit "$status"
