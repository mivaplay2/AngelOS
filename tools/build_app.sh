#!/bin/sh
# Build + sign an Angel OS app without Gradle.  usage: build_app.sh work/apps/<name> out.apk
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; T="$ROOT/work/tools"
export JAVA_HOME="$T/jdk/Contents/Home"; J="$JAVA_HOME/bin"
BT="$T/android-sdk/build-tools/34.0.0"; AJ="$T/android-sdk/platforms/android-30/android.jar"
SRC="$(cd "$1" && pwd)"; OUT="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
B="$SRC/build"; rm -rf "$B"; mkdir -p "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir "$SRC/res" -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" --manifest "$SRC/AndroidManifest.xml" -I "$AJ" "$B/res.zip" --auto-add-overlay
"$J/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -d "$B/classes" $(find "$SRC/src" -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$AJ" --output "$B/dex" $(find "$B/classes" -name '*.class')
(cd "$B/dex" && zip -q "$B/base.apk" classes.dex)
"$BT/zipalign" -f 4 "$B/base.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$ROOT/work/keys/angel-apps.jks" --ks-pass pass:angelapps --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT"
echo "built $OUT"
