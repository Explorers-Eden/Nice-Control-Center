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

# The release notes: only this version's part of the changelog (its first "## " section), never the whole
# history; GitHub refuses notes over 125000 characters.
NOTES_FILE="$(mktemp)"
awk 'BEGIN { n = 0 } /^## / { n++; if (n > 1) exit } n == 1 || /^# / { print }' "$CHANGELOG_PATH" > "$NOTES_FILE"
if [ ! -s "$NOTES_FILE" ]; then cp "$CHANGELOG_PATH" "$NOTES_FILE"; fi
if [ "$(wc -c < "$NOTES_FILE")" -gt 120000 ]; then
  head -c 120000 "$NOTES_FILE" > "$NOTES_FILE.cut" && mv "$NOTES_FILE.cut" "$NOTES_FILE"
fi

publish() {
  if gh release view "$TAG_NAME" >/dev/null 2>&1; then
    gh release edit "$TAG_NAME" \
      --title "$RELEASE_NAME" \
      --notes-file "$NOTES_FILE" \
      --latest="${IS_LATEST:-false}" \
      $PRERELEASE_FLAG
  else
    gh release create "$TAG_NAME" \
      --target "${TARGET_SHA:-$GITHUB_SHA}" \
      --title "$RELEASE_NAME" \
      --notes-file "$NOTES_FILE" \
      --latest="${IS_LATEST:-false}" \
      $PRERELEASE_FLAG
  fi
}

retry publish

# Gradle names the jar <slug>-<version>.jar; the release file also carries the Minecraft version.
cp "build/libs/$BUILT_JAR" "build/libs/$JAR_NAME"
retry gh release upload "$TAG_NAME" "build/libs/$JAR_NAME" --clobber
