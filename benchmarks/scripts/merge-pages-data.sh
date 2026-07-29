#!/usr/bin/env bash
# Merges the currently published GitHub Pages data into a freshly assembled site
# directory before deployment. actions/deploy-pages always replaces the whole site,
# so every deploy has to carry forward the parts it does not regenerate itself:
# the main-branch trend data and the per-PR payloads of all other active PRs.
#
# Usage:
#   merge-pages-data.sh --site <dir> --base-url <published pages url> \
#       [--preserve-main] [--add-pr <number>] [--remove-pr <number>]
#
#   --preserve-main  Overwrite the site's data/{latest,comparison,history}.json and
#                    data/data.js with the live published versions. Used by PR deploys
#                    and close-cleanup, whose artifacts carry PR data, not main data.
#   --add-pr <n>     The site dir already contains fresh data/prs/<n>/ payloads for
#                    this PR (from the benchmark artifact); refresh its index entry.
#   --remove-pr <n>  Drop this PR from the index and do not carry its data forward.
#
# Fetch failures other than HTTP 404 abort the merge: deploying without previously
# published data would silently wipe it.
set -euo pipefail

SITE_DIR=""
BASE_URL=""
PRESERVE_MAIN=false
ADD_PR=""
REMOVE_PR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --site) SITE_DIR="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --preserve-main) PRESERVE_MAIN=true; shift ;;
    --add-pr) ADD_PR="$2"; shift 2 ;;
    --remove-pr) REMOVE_PR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$SITE_DIR" ] && [ -d "$SITE_DIR" ] || { echo "--site must point to the assembled site directory" >&2; exit 2; }
[ -n "$BASE_URL" ] || { echo "--base-url is required" >&2; exit 2; }
BASE_URL="${BASE_URL%/}"

# PR numbers are interpolated into paths that feed mkdir -p, curl -o and rm -rf,
# so anything non-numeric (e.g. "../../..") must never get that far.
is_pr_number() {
  case "$1" in
    '' | *[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

if [ -n "$ADD_PR" ] && ! is_pr_number "$ADD_PR"; then
  echo "--add-pr must be a positive integer, got '$ADD_PR'" >&2
  exit 2
fi
if [ -n "$REMOVE_PR" ] && ! is_pr_number "$REMOVE_PR"; then
  echo "--remove-pr must be a positive integer, got '$REMOVE_PR'" >&2
  exit 2
fi

# fetch_url <url> <dest> -> exit status 0 = fetched, 1 = absent (HTTP 404).
# Any other outcome aborts the whole script.
#
# The result is reported through the exit status rather than stdout on purpose: a
# previous version echoed "ok"/"missing" and aborted with `exit 1`, but every call
# site captured stdout with $(...), so the abort only ever killed the command
# substitution's subshell and the script sailed on with an empty result string.
fetch_url() {
  local url="$1" dest="$2" status
  status=$(curl -sSL --proto '=https' --max-redirs 3 \
    --connect-timeout 15 --max-time 300 --retry 3 --retry-all-errors \
    -o "$dest" -w "%{http_code}" "$url") || status=000
  case "$status" in
    200) return 0 ;;
    404) rm -f "$dest"; return 1 ;;
    *)
      echo "::error::Failed to fetch $url (HTTP $status); aborting to avoid wiping published data." >&2
      exit 1
      ;;
  esac
}

DATA_DIR="$SITE_DIR/data"
PRS_DIR="$DATA_DIR/prs"
mkdir -p "$PRS_DIR"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

# --- live PR index -----------------------------------------------------------
live_index="$workdir/live-index.json"
if ! fetch_url "$BASE_URL/data/prs/index.json" "$live_index"; then
  echo '{"prs":[]}' > "$live_index"
  echo "No published PR index yet; starting fresh."
fi

# --- carry forward other PRs' data ------------------------------------------
entries="$workdir/entries.ndjson"
: > "$entries"

# Materialise the number list first: `for n in $(jq ...)` would word-split the
# output and, worse, discard a jq failure because a substitution in a for-list is
# exempt from set -e.
live_numbers="$workdir/live-numbers.txt"
jq -r '.prs[].number | select(type == "number")' "$live_index" > "$live_numbers"

while IFS= read -r number; do
  if [ "$number" = "${ADD_PR:-}" ] || [ "$number" = "${REMOVE_PR:-}" ]; then
    continue
  fi
  if ! is_pr_number "$number"; then
    echo "::warning::Skipping malformed PR id '$number' from the published index."
    continue
  fi
  pr_dir="$PRS_DIR/$number"
  mkdir -p "$pr_dir"
  complete=true
  for name in history comparison latest; do
    if ! fetch_url "$BASE_URL/data/prs/$number/$name.json" "$pr_dir/$name.json"; then
      complete=false
    fi
  done
  if [ "$complete" = true ]; then
    jq -c --argjson n "$number" '.prs[] | select(.number == $n)' "$live_index" >> "$entries"
  else
    echo "::warning::Published data for PR #$number is incomplete; dropping it from the index."
    rm -rf "$pr_dir"
  fi
done < "$live_numbers"

if [ -n "$REMOVE_PR" ]; then
  rm -rf "$PRS_DIR/$REMOVE_PR"
  echo "Removed PR #$REMOVE_PR from the site."
fi

# --- index entry for the PR added/updated by this run ------------------------
if [ -n "$ADD_PR" ]; then
  pr_history="$PRS_DIR/$ADD_PR/history.json"
  [ -f "$pr_history" ] || { echo "--add-pr $ADD_PR given but $pr_history is missing from the site dir" >&2; exit 1; }
  jq -c --argjson n "$ADD_PR" \
    '{number: $n, refName: (.runs[-1].refName // ""), updatedAt: (.runs[-1].generatedAt // ""), runs: (.runs | length)}' \
    "$pr_history" >> "$entries"
fi

jq -s '{schemaVersion: 1, prs: (. | sort_by(.number))}' "$entries" > "$PRS_DIR/index.json"
echo "PR index now lists $(jq '.prs | length' "$PRS_DIR/index.json") pull request(s)."

# --- preserve live main-branch data ------------------------------------------
if [ "$PRESERVE_MAIN" = true ]; then
  preserved=true
  for name in latest.json comparison.json history.json data.js; do
    live_file="$workdir/main-$name"
    if fetch_url "$BASE_URL/data/$name" "$live_file"; then
      mv "$live_file" "$DATA_DIR/$name"
    else
      preserved=false
    fi
  done
  if [ "$preserved" = true ]; then
    echo "Preserved live main-branch data."
  else
    echo "::warning::Some main-branch data is not published yet; the artifact's own data was kept for the missing files."
  fi
fi
