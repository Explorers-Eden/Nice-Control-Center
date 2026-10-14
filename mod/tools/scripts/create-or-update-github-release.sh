#!/usr/bin/env bash
set -euo pipefail

PRERELEASE_FLAG=""
if [ "${IS_PRERELEASE}" = "true" ]; then
  PRERELEASE_FLAG="--prerelease"
fi

# GitHub's API sometimes answers with a 500 for no reason of ours:
# try a few times before giving up. Every step here can safely run again.
retry() {
  local n
  for n in 1 2 3 4; do
    if "$@"; then return 0; fi
    echo "Attempt $n failed: $*" >&2
    sleep $((n * 15))
  done
  "$@"
}

publish() {
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
}

retry publish

# Gradle names the jar <slug>-<version>.jar; the release file also carries the Minecraft version.
cp "build/libs/$BUILT_JAR" "build/libs/$JAR_NAME"
retry gh release upload "$TAG_NAME" "build/libs/$JAR_NAME" --clobber
