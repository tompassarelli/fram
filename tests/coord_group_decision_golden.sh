#!/usr/bin/env bash
# Original-first group-commit/queue/lock-order oracle for M5 Cut D.
#
# Raw stdout, stderr, and exit status are compared with zero masks.
set -euo pipefail

mode="${1:-verify}"
golden_dir="${2:-tests/goldens/coord_group_decision}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir:?}"' EXIT
mkdir -p "$tmp_dir/scenario"

set +e
FRAM_GROUP_GOLDEN_DIR="$tmp_dir/scenario" \
  bb -cp out tests/coord_group_decision_golden.clj \
  >"$tmp_dir/group-queue.out" 2>"$tmp_dir/group-queue.err"
rc=$?
set -e
printf '%s\n' "$rc" >"$tmp_dir/group-queue.rc"
rm -rf "${tmp_dir:?}/scenario"

case "$mode" in
  capture)
    mkdir -p "$golden_dir"
    cp "$tmp_dir/group-queue.out" "$golden_dir/group-queue.out"
    cp "$tmp_dir/group-queue.err" "$golden_dir/group-queue.err"
    cp "$tmp_dir/group-queue.rc" "$golden_dir/group-queue.rc"
    echo "coord_group_decision_golden: captured original batching + queue scenario -> $golden_dir"
    ;;
  verify)
    diff -u "$golden_dir/group-queue.out" "$tmp_dir/group-queue.out"
    diff -u "$golden_dir/group-queue.err" "$tmp_dir/group-queue.err"
    diff -u "$golden_dir/group-queue.rc" "$tmp_dir/group-queue.rc"
    echo "coord_group_decision_golden: batching + queue scenario byte-identical"
    ;;
  *)
    echo "usage: tests/coord_group_decision_golden.sh [capture|verify] [golden-dir]" >&2
    exit 2
    ;;
esac
