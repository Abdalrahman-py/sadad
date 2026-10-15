#!/usr/bin/env bash
# Setup script for the Claude Code cloud environment that works on Sadad.
# Paste this whole file into the environment's "Setup script" field on claude.ai/code.
# The cloud image (Ubuntu 24.04, run as root) already has JDK 21, Gradle, Node 22, npm, python3
# and gh; this adds Deno and the Android SDK. The environment needs Custom network access: the
# Trusted defaults plus dl.google.com (Google Maven and the Android SDK).
# The cloud UI shows no setup output, so everything goes to /root/cloud-setup.log, and the script
# always exits 0: a failed step must not stop the session, which then reads the log.
# See docs/agents/cloud-sessions.md.
set -uo pipefail
LOG=/root/cloud-setup.log
exec > >(tee -a "$LOG") 2>&1
echo "== cloud-setup $(date -u +%FT%TZ)"

DENO_VERSION=2.7.12
CMDLINE_TOOLS=commandlinetools-linux-16111833_latest.zip # cmdline-tools 23.0, stable channel
SDK=/opt/android-sdk

# unzip is not in the image's tool list; python3 is.
extract() { python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$1" "$2"; }

install_deno() {
  command -v deno >/dev/null && return 0
  curl -fsSL -o /tmp/deno.zip \
    "https://github.com/denoland/deno/releases/download/v$DENO_VERSION/deno-x86_64-unknown-linux-gnu.zip" &&
    extract /tmp/deno.zip /usr/local/bin && chmod +x /usr/local/bin/deno && rm -f /tmp/deno.zip
}

install_android() {
  if [[ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]]; then
    mkdir -p "$SDK/cmdline-tools" &&
      curl -fsSL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$CMDLINE_TOOLS" &&
      extract /tmp/cmdline-tools.zip /tmp/cmdline-tools &&
      mv /tmp/cmdline-tools/cmdline-tools "$SDK/cmdline-tools/latest" &&
      chmod +x "$SDK"/cmdline-tools/latest/bin/* &&
      rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip || return 1
  fi
  # android-36 builds main before ticket 30; android-37.0 builds it after.
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null &&
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platforms;android-36" "platforms;android-37.0" "build-tools;36.0.0"
}

install_deno || echo "STEP FAILED: deno"
install_android || echo "STEP FAILED: android sdk"

deno --version 2>&1 | head -1
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --list_installed 2>&1 | grep -E 'platforms|build-tools'
echo "== cloud-setup done"
exit 0
