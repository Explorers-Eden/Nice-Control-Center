#!/usr/bin/env bash
# Keeps only the newest mod release per Minecraft version: deletes every other
# release (and its tag) whose tag is mod-v<anything>-mc<MC_VERSION>.
set -euo pipefail

: "${TAG_NAME:?}" "${MC_VERSION:?}"

gh release list --limit 1000 --json tagName --jq '.[].tagName' | while read -r tag; do
  case "$tag" in
    "$TAG_NAME") ;;
    mod-v*-mc"$MC_VERSION")
      echo "Deleting older release $tag"
      gh release delete "$tag" --cleanup-tag --yes
      ;;
  esac
done
