#!/usr/bin/env bash
# Original-first one-case OCC decision oracle for coord.clj M5 Cut B.
#
# Raw stdout, stderr, and exit status are compared with zero masks.
set -euo pipefail

mode="${1:-verify}"
golden_dir="${2:-tests/goldens/coord_commit_decision}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

set +e
bb -cp out tests/coord_commit_decision_golden.clj \
  >"$tmp_dir/same-group-stale.out" 2>"$tmp_dir/same-group-stale.err"
rc=$?
set -e
printf '%s\n' "$rc" >"$tmp_dir/same-group-stale.rc"

case "$mode" in
  capture)
    mkdir -p "$golden_dir"
    cp "$tmp_dir/same-group-stale.out" "$golden_dir/same-group-stale.out"
    cp "$tmp_dir/same-group-stale.err" "$golden_dir/same-group-stale.err"
    cp "$tmp_dir/same-group-stale.rc" "$golden_dir/same-group-stale.rc"
    echo "coord_commit_decision_golden: captured original conflict scenario -> $golden_dir"
    ;;
  verify)
    diff -u "$golden_dir/same-group-stale.out" "$tmp_dir/same-group-stale.out"
    diff -u "$golden_dir/same-group-stale.err" "$tmp_dir/same-group-stale.err"
    diff -u "$golden_dir/same-group-stale.rc" "$tmp_dir/same-group-stale.rc"
    echo "coord_commit_decision_golden: original conflict scenario byte-identical"
    ;;
  *)
    echo "usage: tests/coord_commit_decision_golden.sh [capture|verify] [golden-dir]" >&2
    exit 2
    ;;
esac
