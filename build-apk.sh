#!/usr/bin/env bash
# Builds the debug APK with the Gradle wrapper.
#
# The old no-Gradle build (aapt2 + kotlinc + D8) cannot resolve Maven
# dependencies, and the app now depends on AndroidX Media3, so Gradle is
# required. The output path is unchanged:
# app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

cd "$(dirname "$0")"
chmod +x gradlew
./gradlew assembleDebug
