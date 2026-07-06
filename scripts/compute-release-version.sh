#!/usr/bin/env bash
set -euo pipefail

# Ensure required environment variables are present (fallback to defaults if running locally/testing)
REF_TYPE="${GITHUB_REF_TYPE:-}"
REF_NAME="${GITHUB_REF_NAME:-}"
REF="${GITHUB_REF:-}"
RUN_NUMBER="${GITHUB_RUN_NUMBER:-1}"
OUTPUT_FILE="${GITHUB_OUTPUT:-/dev/null}"
ENV_FILE="${GITHUB_ENV:-/dev/null}"

if [ -z "$REF_TYPE" ] || [ -z "$REF_NAME" ] || [ -z "$REF" ]; then
  echo "Error: Missing required GitHub Actions environment variables (GITHUB_REF_TYPE, GITHUB_REF_NAME, GITHUB_REF)" >&2
  exit 1
fi

if [ "$REF_TYPE" = "tag" ]; then
  if [[ "$REF_NAME" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-RC[a-zA-Z0-9]+$ ]]; then
    VERSION_NAME="${REF_NAME#v}"
    RELEASE_KIND="beta"
    PLAY_TRACK="beta"
  elif [[ "$REF_NAME" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    VERSION_NAME="${REF_NAME#v}"
    RELEASE_KIND="prod"
    PLAY_TRACK="production"
  else
    echo "Release tags must match vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-RC* (got: $REF_NAME)" >&2
    exit 1
  fi
else
  if [ "$REF" != "refs/heads/main" ]; then
    echo "Alpha releases must target main; got: $REF" >&2
    exit 1
  fi
  
  # Try to find the latest tag merged into current HEAD
  LATEST_TAG="$(git tag --merged HEAD --list 'v*' --sort=-version:refname | grep -Em1 '^v[0-9]+\.[0-9]+\.[0-9]+$' || true)"
  if [ -n "$LATEST_TAG" ]; then
    BASE_VERSION="${LATEST_TAG#v}"
  else
    # Fallback to baseVersionName in gradle.properties
    if [ -f "gradle.properties" ]; then
      BASE_VERSION="$(sed -n 's/^baseVersionName=//p' gradle.properties)"
    else
      BASE_VERSION="0.1.0"
    fi
  fi
  
  if [ -z "$BASE_VERSION" ]; then
    echo "Error: Could not resolve base version name" >&2
    exit 1
  fi
  
  VERSION_NAME="${BASE_VERSION}-alpha${RUN_NUMBER}"
  RELEASE_KIND="alpha"
  PLAY_TRACK="alpha"
fi

VERSION_CODE="$((100000 + RUN_NUMBER))"

echo "Resolved versionName=$VERSION_NAME versionCode=$VERSION_CODE kind=$RELEASE_KIND track=$PLAY_TRACK"

{
  echo "version_name=$VERSION_NAME"
  echo "version_code=$VERSION_CODE"
  echo "release_kind=$RELEASE_KIND"
  echo "play_track=$PLAY_TRACK"
} >> "$OUTPUT_FILE"

{
  echo "READYLYTICS_VERSION_NAME=$VERSION_NAME"
  echo "READYLYTICS_VERSION_CODE=$VERSION_CODE"
  echo "PLAY_TRACK=$PLAY_TRACK"
} >> "$ENV_FILE"
