#!/usr/bin/env bash
# fram.fri-replay over every oracle corpus; the driver itself fails when the
# folded TermStore disagrees with the replay model.
set -euo pipefail

repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"

cleanup() {
  rm -rf "${test_dir:?}"
}
trap cleanup EXIT

if (( $# > 0 )); then
  corpora=("$@")
else
  corpora=(
    tests/oracle/S0.tsv tests/oracle/S1.tsv tests/oracle/S2.tsv
    tests/oracle/S3.tsv tests/oracle/S4.tsv tests/oracle/S5.tsv
    tests/oracle/S6.tsv tests/oracle/S7.tsv tests/oracle/S8.tsv
    tests/oracle/F1.tsv tests/oracle/F2.tsv tests/oracle/F3.tsv
  )
fi

agreed=0
for corpus in "${corpora[@]}"; do
  if [[ "$corpus" != /* ]]; then
    corpus="$repo/$corpus"
  fi
  name="$(basename "$corpus" .tsv)"
  space="oracle-$name"
  beagle_dir="$test_dir/$name.beagle"
  mkdir -p "$beagle_dir"

  (cd "$repo" && bb -cp out tests/fri2_replay_driver.clj "$corpus" "$space" "$beagle_dir" >/dev/null)
  [[ -s "$beagle_dir/summary" ]]  # an empty summary would make the run vacuous
  grep -Eq '^final-version\b' "$beagle_dir/normalized"
  agreed=$((agreed + 1))
done

printf 'fri2-replay: %d/%d oracle corpora replay and fold in agreement\n' \
  "$agreed" "${#corpora[@]}"
