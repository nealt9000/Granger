#!/bin/sh
# Builds GrangerCore.xcframework from this checkout's Kotlin and puts it where ios/GrangerCore's local mode reads it.
# Needs JDK 17. Then build the iOS app with GRANGER_CORE_LOCAL=1 (or before any release is pinned, as is).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
CONFIG=${1:-Release}
(cd "$ROOT" && ./gradlew -q ":shared:assembleGrangerCore${CONFIG}XCFramework")
LOWER=$(echo "$CONFIG" | tr '[:upper:]' '[:lower:]')
mkdir -p "$ROOT/ios/GrangerCore/Local"
rm -rf "$ROOT/ios/GrangerCore/Local/GrangerCore.xcframework"
cp -R "$ROOT/shared/build/XCFrameworks/$LOWER/GrangerCore.xcframework" "$ROOT/ios/GrangerCore/Local/"
echo "ios/GrangerCore/Local/GrangerCore.xcframework ($CONFIG) ready"
