#!/usr/bin/env bash
set -euo pipefail

repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"

canonical_docs=(
  README.md
  docs/WHY_FRAM_EXISTS.md
  docs/architecture.md
  docs/concurrency-and-writes.md
  docs/coordinator-bind-and-wire.md
  docs/isolation-and-deployment.md
  docs/naming.md
  docs/query-reference.md
  docs/semantic-hints.md
  docs/tool-catalog.md
  deploy/cloudflare/PROCEDURE.md
)

scoped_v030_docs=(
  THREAD-FORMAT.md
  docs/coordinator-cutover.md
)

historical_docs=(
  bridge/README.md
  codegraph/README.md
  docs/VIEWS_AND_BRANCHES.md
  docs/adr/0001-claims-as-universal-substrate.md
  docs/beagle-dogfood-findings.md
  docs/claims-design.md
  docs/measurements.md
  docs/pull-reference.md
)

fail() {
  printf 'docs semantics: FAIL — %s\n' "$*" >&2
  exit 1
}

for path in "${canonical_docs[@]}" "${scoped_v030_docs[@]}" "${historical_docs[@]}"; do
  [[ -f "$path" ]] || fail "missing classified document $path"
done

for path in "${scoped_v030_docs[@]}"; do
  head -n 12 "$path" | grep -Eqi 'Status:.*Current.*v0\.3' ||
    fail "$path lacks a current-v0.3 scope banner"
done

for path in "${historical_docs[@]}"; do
  head -n 12 "$path" | grep -Eqi 'Status:.*historical' ||
    fail "$path lacks a historical banner"
done

# The naming ledger deliberately quotes rejected vocabulary. Scan the other
# current references for positive statements of superseded contracts.
scan_docs=()
for path in "${canonical_docs[@]}"; do
  [[ "$path" == docs/naming.md ]] || scan_docs+=("$path")
done

for pattern in \
  'StoredFact' \
  'TurtleRow' \
  'turtle-id' \
  'turtle log' \
  'exactly twelve' \
  'fixed twelve' \
  'twelve tools' \
  'one line of EDN' \
  'EDN wire' \
  'fact-id\(cid' \
  'accepted as an alias for `fact`' \
  'append-only `subject predicate object`' \
  'engine-terminated.*mTLS' \
  'coordination\.log'; do
  if rg -ni "$pattern" "${scan_docs[@]}"; then
    fail "superseded ontology phrase matched: $pattern"
  fi
done

grep -Fq 'Atom   := String | Int | Float | Bool | Keyword | Instant' README.md ||
  fail 'README lacks the exact Atom contract'
grep -Fq 'Term   := Atom | Triple' README.md || fail 'README lacks the Term contract'
grep -Fq 'Triple := (Term, Term, Term)' README.md || fail 'README lacks the Triple contract'
grep -Fq '`slot0`, `slot1`, and `slot2`' README.md || fail 'README lacks neutral slot names'
grep -Fq 'exactly five public data' docs/tool-catalog.md ||
  fail 'tool catalog does not pin five public verbs'
grep -Fq 'triple(slot0, slot1, slot2)' docs/query-reference.md ||
  fail 'query reference lacks the triple base relation'
grep -Fq 'occurrence(coordinate, action, proposition)' docs/query-reference.md ||
  fail 'query reference lacks the occurrence base relation'
grep -Fq 'FRAMRPC v1' docs/coordinator-bind-and-wire.md ||
  fail 'wire reference lacks FRAMRPC v1'
grep -Fq 'does not implement `rpc/pull`' docs/pull-reference.md ||
  fail 'legacy pull reference does not state the missing runtime surface'
grep -Fq 'architecture prior, never a primitive' docs/naming.md ||
  fail 'naming ledger lacks the Turtle boundary'
grep -Fq '**Current scope:** historical Worlds-service vocabulary.' docs/naming.md ||
  fail 'naming ledger leaves Worlds vocabulary unscoped'
grep -Fq '**Current scope:** historical experiment and sealed-consumer vocabulary' docs/naming.md ||
  fail 'naming ledger leaves Codegraph vocabulary unscoped'

mapfile -t readme_launchers < <(sed -n 's/^\$ \(bin\/[^ ]*\).*/\1/p' README.md)
expected_launchers=(bin/fram-up bin/fram bin/fram bin/fram bin/fram bin/fram)
[[ "${readme_launchers[*]}" == "${expected_launchers[*]}" ]] ||
  fail "README launchers drifted: ${readme_launchers[*]}"

mapfile -t readme_verbs < <(sed -n 's/^\$ bin\/fram \([^ ]*\).*/\1/p' README.md)
expected_verbs=(tell show query occurrences validate)
[[ "${readme_verbs[*]}" == "${expected_verbs[*]}" ]] ||
  fail "README native commands drifted: ${readme_verbs[*]}"

[[ -x bin/fram-up && -x bin/fram ]] || fail 'README launcher is not executable'
for verb in "${expected_verbs[@]}"; do
  grep -Eq "(^|[|[:space:]])${verb}([|)])" bin/fram ||
    fail "README command $verb is absent from bin/fram dispatch"
  grep -Fq "\"$verb\"" bin/fram-fast.clj ||
    fail "README command $verb is absent from the native CLI fixture"
done

if rg -n 'bin/fram (import|export|tools|call)' README.md; then
  fail 'README quickstart regressed to a local legacy command'
fi

printf 'docs semantics: PASS — %d canonical / %d current-v0.3 / %d historical; README %d native commands match fixtures\n' \
  "${#canonical_docs[@]}" "${#scoped_v030_docs[@]}" "${#historical_docs[@]}" \
  "${#expected_verbs[@]}"
