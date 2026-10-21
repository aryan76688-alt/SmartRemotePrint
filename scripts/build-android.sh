#!/usr/bin/env bash
set -e
echo "Building Smart Remote Print Android APK..."
ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../android-app" && pwd)"
JAVA_HOME="/home/aryan/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2" "$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" assembleDebug
echo "Android APK successfully built at: $ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
