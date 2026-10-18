#!/usr/bin/env bash
# Regenerates the vendored graphify skill for Claude Code (.claude/skills/graphify) and
# OpenCode (.opencode/skills/graphify) from the installed graphify CLI. Run after
# `uv tool upgrade graphifyy`, then commit both directories.
# `graphify install --project` also rewrites CLAUDE.md, AGENTS.md, .claude/settings.json and
# .opencode/plugins/, so it runs in a throwaway repo and only the skill directories are copied.
set -euo pipefail

cd "$(dirname "$0")/.."
root=$PWD
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

for platform in claude opencode; do
  dir="$tmp/$platform"
  mkdir -p "$dir"
  (cd "$dir" && git init -q && graphify install --project --platform "$platform" >/dev/null)
  rm -rf "$root/.$platform/skills/graphify"
  mkdir -p "$root/.$platform/skills"
  cp -R "$dir/.$platform/skills/graphify" "$root/.$platform/skills/graphify"
done

echo "graphify skill $(cat .claude/skills/graphify/.graphify_version) written to .claude/skills and .opencode/skills"
