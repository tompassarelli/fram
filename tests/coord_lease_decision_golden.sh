#!/usr/bin/env bash
# Original-first lease + terminal-cascade oracle for M5 Cut C / R3.
#
# Raw stdout, stderr, and exit status are compared with zero masks.
set -euo pipefail

mode="${1:-verify}"
golden_dir="${2:-tests/goldens/coord_lease_decision}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

run_case() {
  local name="$1"
  shift
  set +e
  "$@" >"$tmp_dir/$name.out" 2>"$tmp_dir/$name.err"
  local rc=$?
  set -e
  printf '%s\n' "$rc" >"$tmp_dir/$name.rc"
}

run_case lease-decisions bb -cp out tests/coord_lease_decision_golden.clj
run_case terminal-cascade bb -cp out tests/cascade_test.clj

case "$mode" in
  capture)
    mkdir -p "$golden_dir"
    cp "$tmp_dir"/* "$golden_dir/"
    echo "coord_lease_decision_golden: captured original lease + cascade scenarios -> $golden_dir"
    ;;
  verify)
    diff -ru "$golden_dir" "$tmp_dir"
    echo "coord_lease_decision_golden: lease + cascade scenarios byte-identical"
    ;;
  *)
    echo "usage: tests/coord_lease_decision_golden.sh [capture|verify] [golden-dir]" >&2
    exit 2
    ;;
esac
