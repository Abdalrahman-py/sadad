#!/usr/bin/env bash
# Setup script for the Claude Code cloud environment that works on Sadad.
# Paste this whole file into the environment's "Setup script" field on claude.ai/code.
# The cloud image already has JDK 21, Gradle, Node 22, npm and gh; this adds Deno and the
# Android SDK. The environment needs Custom network access: the Trusted defaults plus
# dl.google.com (Google Maven and the Android SDK). See docs/agents/cloud-sessions.md.
set -euo pipefail

DENO_VERSION=2.7.12
CMDLINE_TOOLS=commandlinetools-linux-16111833_latest.zip # cmdline-tools 23.0, stable channel
SDK=/opt/android-sdk

if ! command -v deno >/dev/null; then
  curl -fsSL -o /tmp/deno.zip \
    "https://github.com/denoland/deno/releases/download/v$DENO_VERSION/deno-x86_64-unknown-linux-gnu.zip"
  unzip -q -o /tmp/deno.zip -d /usr/local/bin
  rm /tmp/deno.zip
fi

if [[ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -fsSL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$CMDLINE_TOOLS"
  unzip -q -o /tmp/cmdline-tools.zip -d /tmp/cmdline-tools
  mv /tmp/cmdline-tools/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip
fi

# android-36 builds main before ticket 30; android-37.0 builds it after.
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platforms;android-36" "platforms;android-37.0" "build-tools;36.0.0" >/dev/null

deno --version | head -1
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --list_installed | grep -E 'platforms|build-tools'
