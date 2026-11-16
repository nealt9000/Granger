#!/usr/bin/env bash
# Uploads one language's African Storybook EPUBs to its GitHub release, creating it if needed (Plan KB2 Task 3.1).
# Neal runs it, once per language:
#
#   tools/pack-builder/upload_asb.sh en 2026-11-16      # release asb-en-2026-11-16, 693 files
#   tools/pack-builder/upload_asb.sh sw 2026-11-16      # release asb-sw-2026-11-16, 427 files
#
# The files are ~/Developer/Granger/vectors-work/asb-<date>/<lang>/*.epub (a third argument names another folder).
# One release per language: a GitHub release holds at most 1,000 assets.
# Resumable: files the release already holds are skipped, so a rerun after a failure (GitHub's secondary rate limit
# answers HTTP 403 when many files go at once) picks up where it stopped. Uploads go 10 at a time with a pause.
set -euo pipefail
lang="${1:?language, en or sw}"
date="${2:?build date, e.g. 2026-11-16}"
dir="${3:-$HOME/Developer/Granger/vectors-work/asb-$date/$lang}"
tag="asb-$lang-$date"
repo="nealt9000/Granger"
batch=10
pause=20

[ -d "$dir" ] || { echo "no folder $dir" >&2; exit 1; }
if ! gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  gh release create "$tag" --repo "$repo" --title "African Storybook ($lang) $date" \
    --notes "African Storybook picture books in language '$lang', from the Kiwix pack africanstorybook.org_mul_all (Plan KB2). CC BY 4.0, African Storybook Initiative (africanstorybook.org); each book's back cover credits its author and illustrator. Built by tools/pack-builder/africanstorybook.py; listed in tools/pack-builder/releases/african_storybook_${lang}_books.json."
fi
have=$(gh release view "$tag" --repo "$repo" --json assets --jq '.assets[].name')
todo=()
for f in "$dir"/*.epub; do
  name=$(basename "$f")
  grep -qxF "$name" <<<"$have" || todo+=("$f")
done
echo "${#todo[@]} to upload, $(wc -w <<<"$have" | tr -d ' ') already there"
for ((i = 0; i < ${#todo[@]}; i += batch)); do
  gh release upload "$tag" --repo "$repo" "${todo[@]:i:batch}"
  echo "uploaded $((i + ${#todo[@]:i:batch})) of ${#todo[@]}"
  ((i + batch < ${#todo[@]})) && sleep "$pause"
done
echo "done: $tag. When both languages are up, Task 3.2's catalog build and check-links follow."
