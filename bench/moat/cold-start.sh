#!/usr/bin/env bash
# regen: MOAT_N=500 bash bench/moat/cold-start.sh
source "$(dirname "$0")/common.sh"
make_rename_fixture
bootstrap_graph
graph_rename target renamed_target
git_seed
t0=$(ns)
git clone -q "$WORK/git" "$WORK/git-clone"
GIT_CLONE_MS=$(ms "$t0" "$(ns)")
text_rename_commit target renamed_target
printf 'cold-start graph-bootstrap+fold-ms=%s git-clone+checkout-ms=%s graph-append+index-ms=%s git-commit-ms=%s corpus-sites=%s\n' "$BOOT_MS" "$GIT_CLONE_MS" "$GRAPH_EDIT_MS" "$TEXT_COMMIT_MS" "$(grep -o '(target ' "$WORK/graph-src/fixture.bclj" | wc -l)"
stop_graph
