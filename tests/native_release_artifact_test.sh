#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
packager="$repo/scripts/package-native-release.sh"
scratch="$(mktemp -d)"
cleanup() {
  rm -rf "${scratch:?}"
}
trap cleanup EXIT INT TERM

fail() {
  echo "native release artifact test: FAIL: $*" >&2
  exit 1
}

for command in cmp git gzip readelf sha256sum tar; do
  command -v "$command" >/dev/null 2>&1 || fail "missing command: $command"
done
source_seed="$scratch/source-seed"
mkdir -p "$source_seed"
git -C "$source_seed" init -q
printf '%s\n' 'release source' >"$source_seed/source.txt"
cat >"$source_seed/server.c" <<'C'
int main(void) { return 0; }
C
cp "$repo/LICENSE" "$repo/LICENSE-MIT" "$repo/LICENSE-APACHE" "$source_seed/"
git -C "$source_seed" add source.txt server.c LICENSE LICENSE-MIT LICENSE-APACHE
GIT_AUTHOR_NAME=Fram GIT_AUTHOR_EMAIL=fram@example.invalid \
GIT_AUTHOR_DATE='2026-01-02T03:04:05Z' \
GIT_COMMITTER_NAME=Fram GIT_COMMITTER_EMAIL=fram@example.invalid \
GIT_COMMITTER_DATE='2026-01-02T03:04:05Z' \
  git -C "$source_seed" commit -q -m release
git -C "$source_seed" tag v1.2.3
source_commit="$(git -C "$source_seed" rev-parse HEAD)"

git clone -q --no-local "$source_seed" "$scratch/work-a"
git clone -q --no-local "$source_seed" "$scratch/different/depth/work-b"

cc="${CC:-cc}"
command -v "$cc" >/dev/null 2>&1 || fail "missing C compiler: $cc"

artifact_identity="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
for work in "$scratch/work-a" "$scratch/different/depth/work-b"; do
  mkdir -p "$work/artifacts/$artifact_identity/bin"
  server="$work/artifacts/$artifact_identity/bin/fram-server-native"
  "$cc" -static "$work/server.c" -o "$server" ||
    fail "$cc cannot produce the static release-test executable"
  readelf -l "$server" | grep -Fq 'Requesting program interpreter' &&
    fail "release-test executable is dynamically linked"
  printf 'fram-native-build/v1 %s\n' "$artifact_identity" \
    >"$work/artifacts/$artifact_identity/READY"
done
reference_server="$scratch/work-a/artifacts/$artifact_identity/bin/fram-server-native"

mapfile -t files_a < <(
  "$packager" --source-root "$scratch/work-a" \
    --artifact "$scratch/work-a/artifacts/$artifact_identity" \
    --output "$scratch/out-a" --version v1.2.3
)
mapfile -t files_b < <(
  "$packager" --source-root "$scratch/different/depth/work-b" \
    --artifact "$scratch/different/depth/work-b/artifacts/$artifact_identity" \
    --output "$scratch/different/out-b" --version v1.2.3
)
[[ "${#files_a[@]}" == 2 && "${#files_b[@]}" == 2 ]] ||
  fail "packager did not return one archive and one receipt"
cmp -s "${files_a[0]}" "${files_b[0]}" ||
  fail "normalized archives differ across source and artifact workdirs"
cmp -s "${files_a[1]}" "${files_b[1]}" ||
  fail "release receipts differ across source and artifact workdirs"

for forbidden_path in "$scratch/work-a" "$scratch/different/depth/work-b"; do
  ! grep -Fq "$forbidden_path" "${files_a[1]}" ||
    fail "receipt leaked a checkout-local path: $forbidden_path"
done
grep -Fxq "source-commit $source_commit" "${files_a[1]}" ||
  fail "receipt omitted the exact source commit"
archive_sha256="$(sha256sum "${files_a[0]}" | awk '{print $1}')"
grep -Fxq "archive-sha256 $archive_sha256" "${files_a[1]}" ||
  fail "receipt does not hash the shipped archive"

extract="$scratch/extract"
mkdir -p "$extract"
tar -xzf "${files_a[0]}" -C "$extract"
shipped="$extract/fram-v1.2.3-x86_64-linux-musl/bin/fram-server-native"
cmp -s "$reference_server" "$shipped" ||
  fail "archive does not contain the exact native executable"
executable_sha256="$(sha256sum "$shipped" | awk '{print $1}')"
grep -Fxq "executable-sha256 $executable_sha256" "${files_a[1]}" ||
  fail "receipt does not hash the executable extracted from the archive"

archive_entries="$(tar -tzf "${files_a[0]}")"
[[ "$archive_entries" == $'fram-v1.2.3-x86_64-linux-musl/\nfram-v1.2.3-x86_64-linux-musl/LICENSE\nfram-v1.2.3-x86_64-linux-musl/LICENSE-APACHE\nfram-v1.2.3-x86_64-linux-musl/LICENSE-MIT\nfram-v1.2.3-x86_64-linux-musl/bin/\nfram-v1.2.3-x86_64-linux-musl/bin/fram-server-native' ]] ||
  fail "archive member set or order is not canonical"

# Artifact and executable paths are identity-bearing inputs, never links that
# can be retargeted between validation and copy.
symlink_artifact_parent="$scratch/symlink-artifact"
mkdir -p "$symlink_artifact_parent"
ln -s "$scratch/work-a/artifacts/$artifact_identity" \
  "$symlink_artifact_parent/$artifact_identity"
if "$packager" --source-root "$scratch/work-a" \
    --artifact "$symlink_artifact_parent/$artifact_identity" \
    --output "$scratch/out-symlink-artifact" --version v1.2.3 \
    >"$scratch/symlink-artifact.stdout" 2>"$scratch/symlink-artifact.stderr"; then
  fail "packager accepted a symlink artifact path"
fi
grep -Fq 'artifact path must not be a symlink' \
  "$scratch/symlink-artifact.stderr" ||
  fail "symlink artifact path did not fail exactly"

symlink_server_artifact="$scratch/symlink-server/$artifact_identity"
mkdir -p "$symlink_server_artifact/bin"
printf 'fram-native-build/v1 %s\n' "$artifact_identity" \
  >"$symlink_server_artifact/READY"
ln -s "$reference_server" \
  "$symlink_server_artifact/bin/fram-server-native"
if "$packager" --source-root "$scratch/work-a" \
    --artifact "$symlink_server_artifact" \
    --output "$scratch/out-symlink-server" --version v1.2.3 \
    >"$scratch/symlink-server.stdout" 2>"$scratch/symlink-server.stderr"; then
  fail "packager accepted a symlink executable"
fi
grep -Fq 'artifact executable must not be a symlink' \
  "$scratch/symlink-server.stderr" ||
  fail "symlink executable did not fail exactly"

# The receipt must follow shipped bytes, not the READY directory name.
changed_artifact="$scratch/changed/$artifact_identity"
mkdir -p "$changed_artifact/bin"
cat >"$scratch/changed-server.c" <<'C'
int main(void) { return 7; }
C
"$cc" -static "$scratch/changed-server.c" \
  -o "$changed_artifact/bin/fram-server-native" ||
  fail "$cc cannot produce the changed static release-test executable"
set +e
"$changed_artifact/bin/fram-server-native"
changed_status=$?
set -e
[[ "$changed_status" == 7 ]] ||
  fail "changed release-test executable did not run with its expected result"
printf 'fram-native-build/v1 %s\n' "$artifact_identity" >"$changed_artifact/READY"
mapfile -t changed_files < <(
  "$packager" --source-root "$scratch/work-a" --artifact "$changed_artifact" \
    --output "$scratch/out-changed" --version v1.2.3
)
! cmp -s "${files_a[0]}" "${changed_files[0]}" ||
  fail "changed executable did not change the archive"
! cmp -s "${files_a[1]}" "${changed_files[1]}" ||
  fail "changed executable did not change the receipt"

printf 'native release artifact test: PASS commit=%s archive=%s\n' \
  "$source_commit" "$archive_sha256"
