#!/usr/bin/env bash
# Original-first differential oracle for coord.clj Cut A, the pure reader layer.
#
# The eleven cases compare stdout, stderr, and exit status with no filtering,
# sorting, timing normalization, or other masks.
set -euo pipefail

mode="${1:-verify}"
golden_dir="${2:-tests/goldens/coord_reader}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

cases=(
  live-basics
  provenance
  as-of
  as-of-group
  withdrawal
  live-members
  view-selects
  elect-main
  elect-views
  elect-causal
  empty
)

for case_name in "${cases[@]}"; do
  set +e
  bb -cp out tests/coord_reader_golden.clj "$case_name" \
    >"$tmp_dir/$case_name.out" 2>"$tmp_dir/$case_name.err"
  rc=$?
  set -e
  printf '%s\n' "$rc" >"$tmp_dir/$case_name.rc"
done

case "$mode" in
  capture)
    mkdir -p "$golden_dir"
    for case_name in "${cases[@]}"; do
      cp "$tmp_dir/$case_name.out" "$golden_dir/$case_name.out"
      cp "$tmp_dir/$case_name.err" "$golden_dir/$case_name.err"
      cp "$tmp_dir/$case_name.rc" "$golden_dir/$case_name.rc"
    done
    echo "coord_reader_golden: captured ${#cases[@]} original-Clojure cases -> $golden_dir"
    ;;
  verify)
    for case_name in "${cases[@]}"; do
      diff -u "$golden_dir/$case_name.out" "$tmp_dir/$case_name.out"
      diff -u "$golden_dir/$case_name.err" "$tmp_dir/$case_name.err"
      diff -u "$golden_dir/$case_name.rc" "$tmp_dir/$case_name.rc"
    done
    echo "coord_reader_golden: ALL ${#cases[@]} cases byte-identical"
    ;;
  *)
    echo "usage: tests/coord_reader_golden.sh [capture|verify] [golden-dir]" >&2
    exit 2
    ;;
esac
