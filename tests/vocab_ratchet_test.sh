#!/usr/bin/env bash
# vocab_ratchet_test.sh — a SUBSET RATCHET on "claim" residue.
#
# Fram's substrate atom is a FACT (README.md "Terminology — it's a *fact*"). The
# claims->facts rename is finished in the substrate vocabulary; what remains is
# plain-English "claim" (an assertion), dated historical records, and license
# text. This gate does not forbid the word — it forbids REGROWTH: every file may
# carry at most as many `claim` lines as the baseline records, and a file absent
# from the baseline must carry none. Decreases always pass, so deleting residue
# never requires touching the baseline; only `--regen` (a deliberate act) may
# raise a number.
#
# METRIC: `grep -ioc claim <file>` — case-insensitive MATCHING LINES per file
# (grep's -c counts lines, not occurrences, even under -o). Baseline and check
# use the identical command, so the comparison is self-consistent; the metric is
# a regrowth tripwire, not a census.
#
# EXCLUDED: legal text, the vocabulary baseline, and CI inventory files that
# necessarily name paths rather than using those paths as substrate vocabulary.
#
#   bash tests/vocab_ratchet_test.sh            # gate; exit 0 iff no regrowth
#   bash tests/vocab_ratchet_test.sh --regen    # rewrite the baseline
set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE" || exit 2
BASELINE_REL="tests/vocab_ratchet_baseline.txt"
BASELINE="$HERE/$BASELINE_REL"
CI_MANIFEST_REL="tests/occurrence_native_ci_manifest.txt"
CI_FAILURES_REL="tests/occurrence_native_ci_failures.txt"

# count<TAB>path for every tracked-or-untracked, non-exempt file with at least
# one hit, sorted by PATH so the baseline diffs cleanly when a count changes.
# Untracked (non-ignored) files are INCLUDED deliberately: scanning only
# `git ls-files` let a new claim-bearing file pass vacuously when the baseline
# was regenerated before `git add` — the gate then went red on main at commit
# (fd63c83). Same file set in scan and regen keeps the comparison honest.
scan() {
  git ls-files -z --cached --others --exclude-standard | while IFS= read -r -d '' f; do
    case "$f" in
      LICENSE*|*/LICENSE*) continue ;;
      "$BASELINE_REL")     continue ;;
      "$CI_MANIFEST_REL"|"$CI_FAILURES_REL") continue ;;
    esac
    [ -f "$f" ] || continue
    n=$(grep -ioc claim -- "$f" 2>/dev/null)
    [ -n "$n" ] || n=0
    [ "$n" -gt 0 ] 2>/dev/null && printf '%s\t%s\n' "$n" "$f"
  done | LC_ALL=C sort -t"$(printf '\t')" -k2,2
}

if [ "${1:-}" = "--regen" ]; then
  scan > "$BASELINE"
  echo "vocab ratchet: baseline regenerated -> $BASELINE_REL ($(wc -l < "$BASELINE") files)"
  exit 0
fi

if [ ! -r "$BASELINE" ]; then
  echo "vocab ratchet: missing baseline $BASELINE_REL (run with --regen)" >&2
  exit 2
fi

declare -A BASE=()
while IFS=$'\t' read -r n f; do
  [ -n "${f:-}" ] || continue
  BASE["$f"]="$n"
done < "$BASELINE"

offenders=0
checked=0
while IFS=$'\t' read -r n f; do
  [ -n "${f:-}" ] || continue
  checked=$((checked + 1))
  allowed="${BASE[$f]:-0}"
  if [ "$n" -gt "$allowed" ]; then
    if [ "$allowed" -eq 0 ]; then
      echo "  REGROWTH  $f: $n claim-line(s); this file is not in the baseline (allowed 0)"
    else
      echo "  REGROWTH  $f: $n claim-line(s) > baseline $allowed"
    fi
    offenders=$((offenders + 1))
  fi
done < <(scan)

if [ "$offenders" -gt 0 ]; then
  echo "vocab ratchet: FAIL — $offenders file(s) grew 'claim' residue." >&2
  echo "  Fram's substrate atom is a FACT: use fact/facts for the stored triple," >&2
  echo "  the store, and tool/file/API names. Plain-English 'claim' (an assertion)" >&2
  echo "  is fine — if that is what you added, run --regen and say so in the commit." >&2
  exit 1
fi

echo "vocab ratchet: PASS — $checked file(s) carry 'claim', none above baseline."
exit 0
