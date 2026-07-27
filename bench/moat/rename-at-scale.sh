#!/usr/bin/env bash
# regen: MOAT_N=500 bash bench/moat/rename-at-scale.sh
source "$(dirname "$0")/common.sh"
make_rename_fixture
SITES=$(grep -o '(target ' "$WORK/graph-src/fixture.bclj" | wc -l)
bootstrap_graph
graph_rename target renamed_target
git_seed
text_rename_commit target renamed_target
printf 'rename-at-scale N-sites=%s graph-bootstrap-ms=%s graph-edit-ms=%s graph-ops=%s text-rewrite+git-commit-ms=%s\n' "$SITES" "$BOOT_MS" "$GRAPH_EDIT_MS" "$GRAPH_OPS" "$TEXT_COMMIT_MS"
stop_graph
