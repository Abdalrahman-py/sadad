#!/bin/bash
# Setup script for the Sadad cloud environment on claude.ai/code: adds Deno and the Android SDK.
# Needs Custom network access (Trusted defaults + dl.google.com) and ANDROID_HOME=/opt/android-sdk.
# The cloud UI hides setup output, so it goes to /root/cloud-setup.log. No `set -e`: a failed step
# must not stop the session; the session reads the log.
exec >/root/cloud-setup.log 2>&1

npm install -g deno@2.7.12

apt-get update -qq && apt-get install -y -qq unzip
curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
mkdir -p /opt/android-sdk/cmdline-tools
unzip -q /tmp/tools.zip -d /tmp/tools && mv /tmp/tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;36.0.0"

exit 0
