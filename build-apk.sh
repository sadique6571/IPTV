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
mkdir -p "$build_directory/dex"
sed 's/<manifest /<manifest package="com.sadique.iptv" /' \
    "$source_directory/AndroidManifest.xml" > "$build_directory/AndroidManifest.xml"

"$tools_directory/aapt2" compile --dir "$source_directory/res" -o "$build_directory/resources.zip"
"$tools_directory/aapt2" link -I "$android_jar" -o "$build_directory/unsigned.apk" \
    --manifest "$build_directory/AndroidManifest.xml" --min-sdk-version 23 \
    --target-sdk-version 34 --version-code 2 --version-name 1.1 \
    "$build_directory/resources.zip"
kotlin_compiler="${KOTLINC:-kotlinc}"
kotlin_stdlib="${KOTLIN_STDLIB:-$(dirname "$(command -v "$kotlin_compiler")")/../lib/kotlin-stdlib.jar}"
"$kotlin_compiler" -version 2>&1 | grep -q 'kotlinc-jvm 2\.4\.21' || {
    echo 'Kotlin compiler 2.4.21 is required.' >&2
    exit 1
}
mapfile -t sources < <(find "$source_directory/kotlin" -name '*.kt' -print)
"$kotlin_compiler" -no-stdlib -no-reflect -jvm-target 1.8 \
    -classpath "$android_jar:$kotlin_stdlib" -d "$build_directory/classes.jar" "${sources[@]}"
r8_jar="${R8_JAR:-$build_directory/r8.jar}"
if [[ ! -f "$r8_jar" ]]; then
    curl -fLsS --retry 2 \
        https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.5.33/r8-9.5.33.jar \
        -o "$r8_jar"
fi
java -cp "$r8_jar" com.android.tools.r8.D8 --lib "$android_jar" --min-api 23 --output "$build_directory/dex" \
    "$build_directory/classes.jar" "$kotlin_stdlib"
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
