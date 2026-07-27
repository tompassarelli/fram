#!/usr/bin/env bash
# Differential oracle for the fri.clj -> Beagle port.
#
# Capture MUST run while coord_daemon.clj still loads the original root fri.clj.
# Verification runs the same public mmap-image test after each port cut.
set -euo pipefail

mode="${1:-verify}"
golden_dir="${2:-tests/goldens/fri}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

normalize() {
  sed -E 's/ in [0-9]+ ms$/ in <ms>/'
}

set +e
BB_CLJ_IN_PROCESS=1 bb -cp out tests/coord_mmap_image_test.clj \
  >"$tmp_dir/raw.out" 2>"$tmp_dir/raw.err"
rc=$?
set -e

normalize <"$tmp_dir/raw.out" >"$tmp_dir/actual.out"
normalize <"$tmp_dir/raw.err" >"$tmp_dir/actual.err"
printf '%s\n' "$rc" >"$tmp_dir/actual.rc"

case "$mode" in
  record)
    mkdir -p "$golden_dir"
    cp "$tmp_dir/actual.out" "$golden_dir/coord_mmap_image.out"
    cp "$tmp_dir/actual.err" "$golden_dir/coord_mmap_image.err"
    cp "$tmp_dir/actual.rc" "$golden_dir/coord_mmap_image.rc"
    echo "fri_golden: recorded original Clojure oracle in $golden_dir"
    ;;
  verify)
    diff -u "$golden_dir/coord_mmap_image.out" "$tmp_dir/actual.out"
    diff -u "$golden_dir/coord_mmap_image.err" "$tmp_dir/actual.err"
    diff -u "$golden_dir/coord_mmap_image.rc" "$tmp_dir/actual.rc"
    echo "fri_golden: stdout/stderr/rc byte-identical after timing normalization"
    ;;
  *)
    echo "usage: tests/fri_golden.sh [record|verify] [golden-dir]" >&2
    exit 2
    ;;
esac
