"""Reads tools/release_infos.yml and exposes it as GitHub Actions step outputs."""
import json
import os

import yaml

with open("tools/release_infos.yml", "r", encoding="utf-8") as f:
    info = yaml.safe_load(f)

mod_id = info.get("Mod-ID")
slug = info.get("Slug")
name = info.get("Name")
project_id = info.get("Project-ID") or ""
meta = info.get("Meta Data", {}) or {}
game_versions = [str(v) for v in meta.get("Versions", [])]
loaders = [str(v).lower() for v in meta.get("Loaders", ["fabric"])]
details = info.get("Details", {}) or {}

version_type = str(details.get("Version type", "Release")).strip().lower()
version_number = str(details.get("Version number", "")).strip()
release_name = str(details.get("Version subtitle", "")).strip()

if version_type not in {"release", "beta", "alpha"}:
    raise ValueError("Version type must be Release, Beta, or Alpha")

for field, value in [("Mod-ID", mod_id), ("Slug", slug), ("Name", name)]:
    if not value:
        raise ValueError(f"{field} missing in release_infos.yml")
if not game_versions:
    raise ValueError("Meta Data -> Versions missing in release_infos.yml")
if not version_number:
    raise ValueError("Details -> Version number missing in release_infos.yml")
if not release_name:
    raise ValueError("Details -> Version subtitle missing in release_infos.yml")

changelog_path = next((p for p in ["Changelog.log", "changelog.log"] if os.path.exists(p)), None)
if not changelog_path:
    raise FileNotFoundError("Changelog.log or changelog.log not found")

# The jar must be built for the Minecraft version listed first.
with open("gradle.properties", "r", encoding="utf-8") as f:
    props = dict(line.strip().split("=", 1) for line in f if "=" in line and not line.lstrip().startswith("#"))
if props.get("minecraft_version") != game_versions[0]:
    raise ValueError(
        f"gradle.properties builds for Minecraft {props.get('minecraft_version')}, "
        f"but release_infos.yml lists {game_versions[0]} first"
    )

outputs = {
    "project_id": project_id,
    "mod_id": mod_id,
    "slug": slug,
    "name": name,
    "version_number": version_number,
    "release_name": release_name,
    # The Minecraft version is part of the tag so older releases for the same
    # version can be cleaned up (see delete-older-releases.sh).
    "mc_version": game_versions[0],
    "tag_name": f"mod-v{version_number}-mc{game_versions[0]}",
    "jar_name": f"{slug}-{version_number}-mc{game_versions[0]}.jar",
    "built_jar": f"{slug}-{version_number}.jar",
    "version_type": version_type,
    "is_prerelease": "true" if version_type in {"beta", "alpha"} else "false",
    "changelog_path": changelog_path,
    "game_versions_json": json.dumps(game_versions),
    "loaders_json": json.dumps(loaders),
}

with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
    for key, value in outputs.items():
        out.write(f"{key}={value}\n")
        print(f"{key}={value}")
