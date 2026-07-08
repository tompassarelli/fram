#!/usr/bin/env bash
# ============================================================================
# cnf_render_crdt_receipt.sh — #36 render-correctness: a CRDT-keyed mid-insert
#   renders to clean consecutive INTEGER fN in the .bclj view.
#
# THE BUG this guards (found by running the full racket render end-to-end):
#   extract-file!'s wrapper-form renumber was gated to the DELETE path only. On a
#   normal insert (no deletes), the wrapper's CRDT-keyed edge "f<path>~<tie>"
#   passed through RAW. racket --render only understands integer fN, so it
#   SILENTLY DROPPED the inserted form from the rendered .bclj. Fix: always
#   renumber wrapper form-edges to consecutive integers (the graph keeps CRDT
#   keys; the .bclj is the clean-integer VIEW). Idempotent on un-edited modules.
#
# ASSERTS, end-to-end through the real render CLI:
#   (1) EDN projection has NO raw CRDT-keyed wrapper edge   (the fix itself)
#   (2) .bclj contains the inserted probe, in order          (racket EDN->text)
#   (3) +1 top-level form and balanced parens                (no corruption)
#
# SAFE: isolated /tmp copy of .fram/code.log; never 7977 / canonical log.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"
export BEAGLE_HOME="${BEAGLE_HOME:-$HOME/code/beagle}"
LOG=/tmp/cnf-render-test.log
EDNDIR=/tmp/cnf-render-crdt-edn
BCLJ=/tmp/cnf-render-crdt.bclj
rm -rf "$EDNDIR"; mkdir -p "$EDNDIR"

echo "=== insert a probe via the CRDT mid-insert verb (-> $LOG) ==="
bb -cp out cnf_render_insert.clj    # writes $LOG with one inserted (def fram_render_probe 42)

echo "=== render the CRDT-keyed module through the real CLI (racket EDN->text) ==="
RESOLVE_OUT="$EDNDIR" bb -cp out bin/fram-render-code kernel --log "$LOG" --out "$BCLJ"
EDN="$EDNDIR/resolved-kernel.bclj.edn"

fail=0
echo "=== (1) EDN: no RAW CRDT-keyed wrapper edge survived (all renumbered) ==="
if grep -qE '\[[0-9]+ "f[0-9.]+~[0-9]+" [0-9]+\]' "$EDN"; then
  echo "  FAIL — a raw CRDT-keyed wrapper edge survived into the EDN:"; grep -E '\[[0-9]+ "f[0-9.]+~[0-9]+" [0-9]+\]' "$EDN" | head; fail=1
else echo "  ok — every wrapper form-edge is a clean integer fN"; fi

echo "=== (2) .bclj: the inserted probe rendered, in order ==="
if grep -q 'fram_render_probe' "$BCLJ"; then
  echo "  ok — $(grep -n fram_render_probe "$BCLJ")"
else echo "  FAIL — probe missing from rendered .bclj (the original bug)"; fail=1; fi

echo "=== (3) .bclj: balanced parens, non-empty ==="
op=$(tr -cd '(' < "$BCLJ" | wc -c); cp=$(tr -cd ')' < "$BCLJ" | wc -c)
forms=$(grep -cE '^\(' "$BCLJ")
echo "  top-level forms: $forms  open: $op  close: $cp"
[ "$op" = "$cp" ] && [ "$op" -gt 0 ] || { echo "  FAIL — unbalanced/empty render"; fail=1; }

echo
if [ "$fail" = 0 ]; then
  echo "PASS — a CRDT-keyed mid-insert renders to clean consecutive integer fN in the .bclj,"
  echo "       the inserted form appears in order, parens balanced. Render-correctness MET end-to-end."
else
  echo "FAIL — see above."; exit 1
fi
