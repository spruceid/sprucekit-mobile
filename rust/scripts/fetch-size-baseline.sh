#!/usr/bin/env bash
# Download the newest size report from `main` (artifact `size-report-main`,
# uploaded by ci.yml on every push to main) to <out.json>. Read-only: needs a
# GH_TOKEN with `actions: read`. If there is no report, writes nothing and
# exits 0, so the caller reports sizes without a delta.
#
# Usage: rust/scripts/fetch-size-baseline.sh <out.json> [owner/repo]
set -euo pipefail

out=$1
repo=${2:-${GITHUB_REPOSITORY:-spruceid/sprucekit-mobile}}

# Newest unexpired artifact from a push to this repository's main branch, not
# from a fork branch that happens to be called main.
query='[.artifacts[] | select(.expired | not)
  | select(.workflow_run.head_branch == "main"
           and .workflow_run.head_repository_id == .workflow_run.repository_id)
  ][0].archive_download_url // empty'
# An API error (permissions, rate limit, outage) is a warning, not a failure:
# the sizes are still reported, without a delta.
if ! url=$(gh api "repos/$repo/actions/artifacts?name=size-report-main&per_page=30" --jq "$query"); then
  msg="Could not list the artifacts of $repo, so sizes are not compared with main"
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning::$msg"; else echo "warning: $msg"; fi
  exit 0
fi
if [ -z "$url" ]; then
  echo "No size-report-main artifact in $repo: no baseline."
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh api "$url" >"$tmp/artifact.zip"
unzip -q "$tmp/artifact.zip" -d "$tmp"
mkdir -p "$(dirname "$out")"
cp "$tmp/size-report.json" "$out"
echo "Baseline from $url:"
cat "$out"
