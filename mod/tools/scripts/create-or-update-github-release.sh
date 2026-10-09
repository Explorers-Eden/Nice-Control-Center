#!/usr/bin/env bash
set -euo pipefail

PRERELEASE_FLAG=""
if [ "${IS_PRERELEASE}" = "true" ]; then
  PRERELEASE_FLAG="--prerelease"
fi

if gh release view "$TAG_NAME" >/dev/null 2>&1; then
  gh release edit "$TAG_NAME" \
    --title "$RELEASE_NAME" \
    --notes-file "$CHANGELOG_PATH" \
    --latest="${IS_LATEST:-false}" \
    $PRERELEASE_FLAG
else
  gh release create "$TAG_NAME" \
    --target "${TARGET_SHA:-$GITHUB_SHA}" \
    --title "$RELEASE_NAME" \
    --notes-file "$CHANGELOG_PATH" \
    --latest="${IS_LATEST:-false}" \
    $PRERELEASE_FLAG
fi

# Gradle names the jar <slug>-<version>.jar; the release file also carries the Minecraft version.
cp "build/libs/$BUILT_JAR" "build/libs/$JAR_NAME"
gh release upload "$TAG_NAME" "build/libs/$JAR_NAME" --clobber
