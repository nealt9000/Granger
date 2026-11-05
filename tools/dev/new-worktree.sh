#!/usr/bin/env bash
# A new worktree that builds straight away: the branch, local.properties (git-ignored, so a fresh worktree has none)
# and the TagLib submodule, which a fresh worktree also lacks.
#
#   tools/dev/new-worktree.sh <name> <branch> [base]     # base defaults to main
#
# Creates .claude/worktrees/<name> on a new branch <branch> from <base>. Run it from the main checkout or any
# worktree; paths are taken from the main checkout.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi
name=$1
branch=$2
base=${3:-main}

main=$(git rev-parse --path-format=absolute --git-common-dir | sed 's#/\.git$##')
dir="$main/.claude/worktrees/$name"
[[ -e "$dir" ]] && { echo "$dir already exists" >&2; exit 1; }

git -C "$main" worktree add "$dir" -b "$branch" "$base"

if [[ -f "$main/local.properties" ]]; then
  cp "$main/local.properties" "$dir/local.properties"
else
  echo "warning: $main/local.properties is missing; see tools/dev/MAC-SETUP.md" >&2
fi

git -C "$dir" submodule update --init --recursive --quiet

if [[ -z "${JAVA_HOME:-}" || -z "${ANDROID_HOME:-}" ]]; then
  echo "note: JAVA_HOME or ANDROID_HOME isn't set in this shell; see tools/dev/MAC-SETUP.md (Shell setup)" >&2
fi
echo "ready: $dir on $branch (from $base)"
