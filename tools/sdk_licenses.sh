#!/bin/sh
# Shows the Android SDK licenses; you accept or decline them yourself.
T="$(cd "$(dirname "$0")/.." && pwd)/work/tools"
export JAVA_HOME="$T/jdk/Contents/Home"
"$T/android-sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$T/android-sdk" --licenses
echo; echo "=== done, you can close this tab ==="
