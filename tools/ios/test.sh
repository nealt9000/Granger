#!/bin/sh
# Plan I7 Task 6: runs the iPhone tests on a Simulator with every fixture from tools/ios/test-fixtures.sh.
#
#     sh tools/ios/test.sh <fixtures folder> <simulator id or name> [xcodebuild args…]
#     sh tools/ios/test.sh ~/granger-fixtures "iPhone 17"
#     sh tools/ios/test.sh ~/granger-fixtures "iPhone 17" -only-testing:GrangerUITests/SmokeTests
#
# Uses this checkout's core (GRANGER_CORE_LOCAL=1: run tools/ios/core-local.sh first) unless GRANGER_CORE_LOCAL=0 is
# set. Screenshots some tests take go to GRANGER_SHOTS when that's set. For a full run uninstall the app first
# (xcrun simctl uninstall <id> app.granger), so nothing a previous build left changes the result.
set -eu
FIX=$(cd "${1:?usage: test.sh <fixtures folder> <simulator> [args…]}" && pwd)
SIM=${2:?usage: test.sh <fixtures folder> <simulator> [args…]}
shift 2
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
case "$SIM" in
    *-*-*-*-*) DEST="platform=iOS Simulator,id=$SIM" ;;
    *) DEST="platform=iOS Simulator,name=$SIM" ;;
esac
export GRANGER_CORE_LOCAL=${GRANGER_CORE_LOCAL:-1}
export TEST_RUNNER_GRANGER_TEST_ZIM="$FIX/based.cooking.zim"
export TEST_RUNNER_GRANGER_TEST_BOOKPACK="$FIX/knots.zim"
export TEST_RUNNER_GRANGER_TEST_EPUB="$FIX/epub/gc-pride-and-prejudice_2026-11-09.epub"
export TEST_RUNNER_GRANGER_TEST_PDF="$FIX/pdf/field-notes.pdf"
export TEST_RUNNER_GRANGER_TEST_VOICE="$FIX/voices/vits-piper-en_GB-granger_owl-medium"
export TEST_RUNNER_GRANGER_TEST_AUDIO="$FIX/audio"
export TEST_RUNNER_GRANGER_TEST_SEARCH="$FIX/ios-search"
export TEST_RUNNER_GRANGER_TEST_EMBEDDER="$FIX/embedder/embeddinggemma-2-text-270m.litertlm"
export TEST_RUNNER_GRANGER_SHOTS="${GRANGER_SHOTS:-}"
cd "$ROOT/ios/Granger"
exec xcodebuild test -project Granger.xcodeproj -scheme Granger -destination "$DEST" "$@"
