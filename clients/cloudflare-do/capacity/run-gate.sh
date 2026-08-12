#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Build and exercise the deployment-shaped Worker. The memory row is stricter
# than production in one important way: the 128 MiB cgroup contains the whole
# workerd runtime subtree, not only one Worker isolate.
set -euo pipefail
export LC_ALL=C

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
client="$(cd "$here/.." && pwd)"
repo="$(cd "$client/../.." && pwd)"
plan="${FRAM_CF_CAPACITY_PLAN:-free}"
output="${1:-$here/out}"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/fram-cloudflare-capacity.XXXXXXXX")"
workerd_cgroup=""

cleanup() {
  if [[ -n "$workerd_cgroup" && -d "$workerd_cgroup" ]]; then
    if [[ -f "$workerd_cgroup/cgroup.kill" ]]; then
      printf '1\n' >"$workerd_cgroup/cgroup.kill" 2>/dev/null || true
    else
      while read -r process; do
        [[ -n "$process" ]] && kill -TERM "$process" 2>/dev/null || true
      done <"$workerd_cgroup/cgroup.procs"
    fi
    for _ in $(seq 1 50); do
      [[ ! -s "$workerd_cgroup/cgroup.procs" ]] && break
      sleep 0.1
    done
    rmdir "$workerd_cgroup" 2>/dev/null || true
  fi
  rm -rf "${scratch:?}"
}
trap cleanup EXIT

die() {
  echo "capacity-gate: $*" >&2
  exit 2
}

case "$plan" in
  free|paid) ;;
  *) die "FRAM_CF_CAPACITY_PLAN must be free or paid" ;;
esac
[[ -f /sys/fs/cgroup/cgroup.controllers ]] ||
  die "cgroup v2 is required for the enforced memory row"
uid="$(id -u)"
cgroup_parent="/sys/fs/cgroup/user.slice/user-${uid}.slice/user@${uid}.service/app.slice"
[[ -w "$cgroup_parent/cgroup.procs" ]] ||
  die "the user cgroup app.slice must delegate cgroup creation"

mkdir -p "$output" "$scratch/corpus" "$scratch/bundle"
output="$(realpath -m "$output")"
bun_binary="$(command -v bun)"

cd "$client"
bun install --frozen-lockfile
"$client/scripts/build-wasm.sh"
direnv exec "$repo" bb -cp "$repo/out" \
  "$here/generate-wiki-corpus.clj" "$scratch/corpus" "$here/corpus.json"

set +e
bunx --bun wrangler deploy --dry-run \
  --config "$here/wrangler.toml" \
  --outdir "$scratch/bundle" \
  --metafile "$scratch/wrangler-metafile.json" \
  >"$scratch/wrangler.log" 2>&1
wrangler_status=$?
set -e
if [[ "$wrangler_status" != 0 ]]; then
  cp "$scratch/wrangler.log" "$output/wrangler.log"
  die "Wrangler dry-run failed; see $output/wrangler.log"
fi

# Wrangler resolves --outdir relative to the config directory. Normalize the
# actual emitted directory from its metafile rather than guessing that base.
bundle_directory="$(
  "$bun_binary" -e '
    import { readFileSync } from "node:fs";
    import { dirname, resolve } from "node:path";
    const meta = JSON.parse(readFileSync(process.argv[1], "utf8"));
    const outputs = Object.keys(meta.outputs ?? {});
    if (outputs.length === 0) throw new Error("Wrangler metafile has no outputs");
    process.stdout.write(resolve(dirname(outputs[0])));
  ' "$scratch/wrangler-metafile.json"
)"
[[ -d "$bundle_directory" ]] || die "Wrangler emitted no bundle directory"

real_workerd="$client/node_modules/workerd/bin/workerd"
[[ -x "$real_workerd" ]] || die "the pinned workerd executable is absent"
set +e
MINIFLARE_WORKERD_PATH="$here/workerd-cgroup-wrapper.sh" \
FRAM_CF_REAL_WORKERD="$real_workerd" \
FRAM_CF_CGROUP_LOCATOR="$scratch/cgroup.locator" \
timeout 600 "$bun_binary" "$here/run-workerd.mjs" \
  "$scratch/corpus" "$scratch/functional.json" \
  >"$scratch/workerd.log" 2>&1
functional_status=$?
set -e
[[ -s "$scratch/cgroup.locator" ]] ||
  die "the workerd cgroup wrapper emitted no cgroup locator"
candidate_cgroup="$(<"$scratch/cgroup.locator")"
case "$candidate_cgroup" in
  "$cgroup_parent"/fram-cloudflare-workerd-[0-9]*) ;;
  *) die "the workerd cgroup locator escaped its delegated parent" ;;
esac
workerd_cgroup="$candidate_cgroup"
[[ -d "$workerd_cgroup" ]] || die "the workerd cgroup disappeared before measurement"
peak="$(<"$workerd_cgroup/memory.peak")"
oom_kills="$(awk '$1 == "oom_kill" { print $2 }' "$workerd_cgroup/memory.events")"
result=success
if [[ "${oom_kills:-0}" != 0 ]]; then
  result=oom-kill
elif [[ "$functional_status" != 0 ]]; then
  result=exit-code
fi
printf '%s\n' \
  "Result=$result" \
  "ExecMainStatus=$functional_status" \
  "MemoryPeak=$peak" \
  "MemoryMax=$(<"$workerd_cgroup/memory.max")" \
  "MemorySwapMax=$(<"$workerd_cgroup/memory.swap.max")" \
  >"$scratch/cgroup.properties"
if [[ "$(<"$workerd_cgroup/cgroup.procs")" != "" ]]; then
  die "workerd left a process in its capacity cgroup"
fi
rmdir "$workerd_cgroup"
workerd_cgroup=""

if [[ ! -s "$scratch/functional.json" ]]; then
  "$bun_binary" "$here/write-functional-failure.mjs" \
    "$scratch/corpus/profile.json" \
    "$scratch/cgroup.properties" \
    "$scratch/functional.json"
fi

set +e
"$bun_binary" "$here/assemble-receipt.mjs" \
  "--plan=$plan" \
  "--bundle=$bundle_directory" \
  "--wrangler-log=$scratch/wrangler.log" \
  "--functional=$scratch/functional.json" \
  "--cgroup=$scratch/cgroup.properties" \
  "--wasm-provenance=$client/lib/provenance.json" \
  "--output=$scratch/receipt.json"
receipt_status=$?
set -e

cp "$scratch/receipt.json" "$output/receipt.json"
cp "$scratch/functional.json" "$output/functional.json"
cp "$scratch/cgroup.properties" "$output/cgroup.properties"
cp "$scratch/wrangler.log" "$output/wrangler.log"
cp "$scratch/workerd.log" "$output/workerd.log"
cp "$scratch/wrangler-metafile.json" "$output/wrangler-metafile.json"

if [[ "$receipt_status" != 0 ]]; then
  die "capacity receipt failed; see $output/receipt.json"
fi
[[ "$functional_status" == 0 ]] ||
  die "workerd failed with exit $functional_status despite a passing receipt"
echo "capacity-gate: PASS $output/receipt.json"
