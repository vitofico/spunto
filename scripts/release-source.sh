#!/usr/bin/env bash
# Every validation job and the publisher must assemble the identical planned tree.
set -euo pipefail
cd "$(dirname "$0")/.."
release_date=${1-}
expected_tree=${2-}
if [[ -n "$release_date" ]]; then
  python3 scripts/prepare-android-release.py --date "$release_date" > /dev/null
  git add -- android/gradle.properties distribution/fdroid/metadata/io.theficos.spunto.yml
fi
git diff --quiet || {
  echo 'Tracked source outside the prepared index has changed.' >&2
  exit 1
}
tree=$(git write-tree)
if [[ -n "$expected_tree" && "$tree" != "$expected_tree" ]]; then
  echo 'Prepared source differs from the tree selected for validation.' >&2
  exit 1
fi
printf '%s\n' "$tree"
