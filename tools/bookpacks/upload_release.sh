#!/usr/bin/env bash
# Uploads book manifests (.gbooks) to a GitHub release, creating it if needed (Plan KB2). Neal runs it.
#
#   tools/bookpacks/upload_release.sh bookpacks-2026-11-16 ~/Developer/Granger/vectors-work/bookpacks-2026-11-16
#
# Resumable: files the release already holds are skipped, so a rerun after a failure (GitHub's secondary rate limit
# answers HTTP 403 when many files go at once) picks up where it stopped. Uploads go about 10 at a time with a pause.
set -euo pipefail
tag="${1:?release tag, e.g. bookpacks-2026-11-16}"
dir="${2:?folder holding the .gbooks files}"
repo="nealt9000/Granger"
batch=10
pause=20

if ! gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  gh release create "$tag" --repo "$repo" --title "Book manifests $tag" \
    --notes "Book manifests (.gbooks) for Kiwix packs shown as book collections (Plan KB2). Built by tools/bookpacks/build.py; each is valid for the exact ZIM its meta.sourceSha256 names."
fi
have=$(gh release view "$tag" --repo "$repo" --json assets --jq '.assets[].name')
todo=()
for f in "$dir"/*.gbooks; do
  name=$(basename "$f")
  grep -qxF "$name" <<<"$have" || todo+=("$f")
done
echo "${#todo[@]} to upload, $(wc -w <<<"$have" | tr -d ' ') already there"
for ((i = 0; i < ${#todo[@]}; i += batch)); do
  gh release upload "$tag" --repo "$repo" "${todo[@]:i:batch}"
  echo "uploaded $((i + ${#todo[@]:i:batch})) of ${#todo[@]}"
  ((i + batch < ${#todo[@]})) && sleep "$pause"
done
echo "done: now run catalog_gen.py check-links on catalog/catalog.json"
