#!/bin/sh
# Publishes GrangerCore.xcframework for the iPhone app's dev builds (Plan I1 Task 8):
#   1. builds the Release XCFramework from this checkout,
#   2. zips it and computes SwiftPM's checksum,
#   3. creates the GitHub release ios-core-<date> with the zip,
#   4. writes the release URL and checksum into ios/GrangerCore/Package.swift.
# Dry run by default: it builds, zips and prints what it would do. --publish uploads (a push: Neal's OK each time).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
PUBLISH=0
[ "${1:-}" = "--publish" ] && PUBLISH=1
TAG="ios-core-$(date +%Y-%m-%d)"
REPO=$(cd "$ROOT" && gh repo view --json nameWithOwner -q .nameWithOwner)
OUT=$(mktemp -d)

(cd "$ROOT" && ./gradlew -q :shared:assembleGrangerCoreReleaseXCFramework)
(cd "$ROOT/shared/build/XCFrameworks/release" && ditto -c -k --keepParent GrangerCore.xcframework "$OUT/GrangerCore.xcframework.zip")
CHECKSUM=$(cd "$ROOT/ios/GrangerCore" && swift package compute-checksum "$OUT/GrangerCore.xcframework.zip")
URL="https://github.com/$REPO/releases/download/$TAG/GrangerCore.xcframework.zip"
SIZE=$(stat -f %z "$OUT/GrangerCore.xcframework.zip")
COMMIT=$(cd "$ROOT" && git rev-parse --short HEAD)

echo "zip:      $OUT/GrangerCore.xcframework.zip ($SIZE bytes)"
echo "checksum: $CHECKSUM"
echo "release:  $TAG on $REPO (from $COMMIT)"
echo "url:      $URL"

if [ "$PUBLISH" -ne 1 ]; then
    echo "Dry run: nothing uploaded. Run with --publish to create the release and pin it."
    exit 0
fi

(cd "$ROOT" && gh release create "$TAG" "$OUT/GrangerCore.xcframework.zip" \
    --title "GrangerCore $TAG" \
    --notes "The shared Kotlin core for Granger's iPhone app, built from $COMMIT. Xcode fetches it through ios/GrangerCore/Package.swift.")
sed -i '' -e "s|^let releaseURL = \".*\"|let releaseURL = \"$URL\"|" -e "s|^let releaseChecksum = \".*\"|let releaseChecksum = \"$CHECKSUM\"|" "$ROOT/ios/GrangerCore/Package.swift"
echo "Pinned in ios/GrangerCore/Package.swift. Commit it: git commit -am \"GrangerCore $TAG\""
