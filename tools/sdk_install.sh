#!/bin/sh
# Installs the Android SDK packages Angel Store needs. Any license prompt is yours to answer.
T="$(cd "$(dirname "$0")/.." && pwd)/work/tools"
export JAVA_HOME="$T/jdk/Contents/Home"
"$T/android-sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$T/android-sdk" "platforms;android-30" "build-tools;34.0.0"
echo; ls "$T/android-sdk/platforms" "$T/android-sdk/build-tools" 2>&1
echo "=== done ==="
