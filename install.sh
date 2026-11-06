#!/usr/bin/env bash
# Build release (R8, non-debuggable = real startup speed) and install on the USB-connected phone.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(mise which java)")")}"
ADB="$HOME/Android/Sdk/platform-tools/adb"
./gradlew -q testDebugUnitTest assembleRelease
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am start -W -n io.github.fastdaima.fintap/com.fintap.MainActivity | grep -E "TotalTime|WaitTime"
