#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
sdk_directory="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk_directory:?Set ANDROID_HOME to an SDK with platform 35 and build-tools 35.0.0}"
tools_directory="$sdk_directory/build-tools/35.0.0"
android_jar="$sdk_directory/platforms/android-35/android.jar"
source_directory=app/src/main
output_directory=app/build/outputs/apk/debug
mkdir -p "$output_directory"
build_directory="$(mktemp -d)"
trap 'rm -rf "$build_directory"' EXIT
mkdir -p "$build_directory/classes" "$build_directory/dex"
sed 's/<manifest /<manifest package="com.sadique.iptv" /' \
    "$source_directory/AndroidManifest.xml" > "$build_directory/AndroidManifest.xml"

"$tools_directory/aapt2" compile --dir "$source_directory/res" -o "$build_directory/resources.zip"
"$tools_directory/aapt2" link -I "$android_jar" -o "$build_directory/unsigned.apk" \
    --manifest "$build_directory/AndroidManifest.xml" --min-sdk-version 23 \
    --target-sdk-version 34 --version-code 1 --version-name 1.0 \
    "$build_directory/resources.zip"
mapfile -t sources < <(find "$source_directory/java" -name '*.java' -print)
javac --release 8 -classpath "$android_jar" -d "$build_directory/classes" "${sources[@]}"
jar cf "$build_directory/classes.jar" -C "$build_directory/classes" .
"$tools_directory/d8" --lib "$android_jar" --min-api 23 --output "$build_directory/dex" \
    "$build_directory/classes.jar"
zip -jq "$build_directory/unsigned.apk" "$build_directory/dex/classes.dex"
"$tools_directory/zipalign" -f 4 "$build_directory/unsigned.apk" "$build_directory/aligned.apk"

debug_key="${IPTV_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
mkdir -p "$(dirname "$debug_key")"
if [[ ! -f "$debug_key" ]]; then
    keytool -genkeypair -keystore "$debug_key" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Android Debug,O=Android,C=US'
fi
"$tools_directory/apksigner" sign --ks "$debug_key" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$output_directory/app-debug.apk" \
    "$build_directory/aligned.apk"
"$tools_directory/apksigner" verify "$output_directory/app-debug.apk"
