#!/usr/bin/env bash
set -uo pipefail

repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compare="$repo/tests/occurrence_native_ci_compare.sh"
scratch="$(mktemp -d)"
trap 'rm -rf "${scratch:?}"' EXIT INT TERM

printf '%s\n' \
  $'vocab-residue\tdocs/archive/measurements.md' \
  $'vocab-residue\tserver.clj' \
  $'vocab-residue\tdeploy/cloudflare/PROCEDURE.md' \
  > "$scratch/pristine.tsv"
printf '%s\n' \
  $'vocab-residue\tdocs/archive/measurements.md' \
  > "$scratch/subset.tsv"
printf '%s\n' \
  $'vocab-residue\tdocs/archive/measurements.md' \
  $'vocab-residue\tnew/residue.txt' \
  > "$scratch/regrowth.tsv"

passes=0
set +e
subset_output="$(bash "$compare" "$scratch/pristine.tsv" "$scratch/subset.tsv" 2>&1)"
subset_status=$?
regrowth_output="$(bash "$compare" "$scratch/pristine.tsv" "$scratch/regrowth.tsv" 2>&1)"
regrowth_status=$?
set -e

if [ "$subset_status" -eq 0 ]; then
  echo "  [PASS] a candidate vocabulary subset is accepted"
  passes=$((passes + 1))
else
  echo "  [FAIL] a candidate vocabulary subset was rejected" >&2
fi
if grep -Fq $'RESOLVED vocab-residue\tserver.clj' <<< "$subset_output"; then
  echo "  [PASS] resolved server.clj is reported"
  passes=$((passes + 1))
else
  echo "  [FAIL] resolved server.clj was silent" >&2
fi
if grep -Fq $'RESOLVED vocab-residue\tdeploy/cloudflare/PROCEDURE.md' <<< "$subset_output"; then
  echo "  [PASS] resolved Cloudflare procedure is reported"
  passes=$((passes + 1))
else
  echo "  [FAIL] resolved Cloudflare procedure was silent" >&2
fi
if [ "$regrowth_status" -ne 0 ]; then
  echo "  [PASS] a new vocabulary failure is rejected"
  passes=$((passes + 1))
else
  echo "  [FAIL] a new vocabulary failure was accepted" >&2
fi
if grep -Fq $'NEW      vocab-residue\tnew/residue.txt' <<< "$regrowth_output"; then
  echo "  [PASS] the new vocabulary failure is named"
  passes=$((passes + 1))
else
  echo "  [FAIL] the new vocabulary failure was unnamed" >&2
fi
if [ "$passes" -ne 5 ]; then
  printf 'occurrence-native failure comparison: %d/5 PASS\n' "$passes" >&2
  exit 1
fi
echo "occurrence-native failure comparison: 5/5 PASS"
