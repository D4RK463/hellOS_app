#!/usr/bin/env bash
# Prints the next version (MAJOR.MINOR.PATCH, without "v") derived from the Conventional Commits
# since the last tag v*. Prints nothing if there is no commit since the last tag.
#
#   feat!: / fix!: ... or "BREAKING CHANGE:" in the body -> major
#   feat:                                               -> minor
#   everything else                                     -> patch
#
# Without any tag the first version is INITIAL_VERSION (default 0.1.0).
set -euo pipefail

initial="${INITIAL_VERSION:-0.1.0}"

last_tag="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || true)"
if [[ -z "$last_tag" ]]; then
  echo "$initial"
  exit 0
fi

subjects="$(git log --format='%s' "${last_tag}..HEAD")"
bodies="$(git log --format='%b' "${last_tag}..HEAD")"
if [[ -z "$subjects" ]]; then
  exit 0
fi

IFS=. read -r major minor patch <<< "${last_tag#v}"

if grep -qE '^[a-z]+(\([^)]*\))?!:' <<< "$subjects" \
   || grep -qE '^BREAKING[ -]CHANGE:' <<< "$bodies"; then
  echo "$((major + 1)).0.0"
elif grep -qE '^feat(\([^)]*\))?:' <<< "$subjects"; then
  echo "${major}.$((minor + 1)).0"
else
  echo "${major}.${minor}.$((patch + 1))"
fi
