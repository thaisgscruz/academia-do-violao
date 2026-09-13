#!/usr/bin/env bash
set -e
./gradlew assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
