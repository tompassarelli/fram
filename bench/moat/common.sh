#!/usr/bin/env bash
# Shared scratch-only setup for the W8 receipts. Run from the Fram checkout.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
N="${MOAT_N:-500}"
WORK="$(mktemp -d /tmp/fram-moat.XXXXXX)"

ns() { date +%s%N; }
ms() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", (b-a)/1000000 }'; }
free_port() {
  local p
  while :; do
    p=$((20000 + RANDOM % 20000))
    if ! (echo >/dev/tcp/127.0.0.1/"$p") 2>/dev/null; then echo "$p"; return; fi
  done
}
make_rename_fixture() {
  mkdir -p "$WORK/graph-src"
  {
    echo '#lang beagle'
    echo '(defn target [x] x)'
    echo '(defn caller [x]'
    for _ in $(seq 1 "$N"); do echo '  (target x)'; done
    echo '  x)'
  } > "$WORK/graph-src/fixture.bclj"
}
make_chain_fixture() {
  mkdir -p "$WORK/graph-src"
  {
    echo '#lang beagle'
    echo '(defn target [x] x)'
    echo '(defn d0 [x] (target x))'
    for i in $(seq 1 "$N"); do prev=$((i - 1)); echo "(defn d$i [x] (d$prev x))"; done
  } > "$WORK/graph-src/fixture.bclj"
}
bootstrap_graph() {
  local start port
  start=$(ns)
  "$ROOT/bin/fram-ingest-code" "$WORK/graph-src" --root "$WORK/graph-src" --out "$WORK/code.log" >"$WORK/ingest.out" 2>&1
  port=$(free_port)
  (cd "$ROOT" && bb -cp out server.clj serve-flat "$port" "$WORK/code.log" >"$WORK/server.out" 2>&1 & echo $! >"$WORK/server.pid")
  for _ in $(seq 1 80); do grep -q 'reified server listening' "$WORK/server.out" && break; sleep 0.1; done
  grep -q 'reified server listening' "$WORK/server.out" || { cat "$WORK/server.out" >&2; return 1; }
  BOOT_MS=$(ms "$start" "$(ns)")
  PORT="$port"
}
stop_graph() { [[ -f "$WORK/server.pid" ]] && kill "$(cat "$WORK/server.pid")" 2>/dev/null || true; }
graph_rename() {
  local old="$1" new="$2" t0 t1
  t0=$(ns)
  FRAM_HOME="$ROOT" bb -cp out "$ROOT/bin/fram-edit-code" rename fixture --old "$old" --new "$new" --port "$PORT" --log "$WORK/code.log" --out "$WORK/rendered.bclj" >"$WORK/graph-edit.out" 2>&1
  t1=$(ns); GRAPH_EDIT_MS=$(ms "$t0" "$t1")
  GRAPH_OPS=$(sed -n 's/.*committed \([0-9][0-9]*\) ops.*/\1/p' "$WORK/graph-edit.out")
}
git_seed() {
  mkdir -p "$WORK/git"; cp "$WORK/graph-src/fixture.bclj" "$WORK/git/fixture.bclj"
  git -C "$WORK/git" init -q; git -C "$WORK/git" config user.email moat@example.invalid; git -C "$WORK/git" config user.name moat
  git -C "$WORK/git" add fixture.bclj; git -C "$WORK/git" commit -qm seed
}
text_rename_commit() {
  local old="$1" new="$2" t0 t1
  t0=$(ns); sed -i "s/${old}/${new}/g" "$WORK/git/fixture.bclj"; git -C "$WORK/git" add fixture.bclj; git -C "$WORK/git" commit -qm "rename $old"
  t1=$(ns); TEXT_COMMIT_MS=$(ms "$t0" "$t1")
}
