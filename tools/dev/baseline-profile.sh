#!/usr/bin/env bash
# Generates Granger's Baseline Profile on a connected phone (Plan E1 item 6) and writes it to
# app/src/main/baseline-prof.txt, which the release build compiles in and profileinstaller installs.
#
# It installs app.granger.benchmark (the `benchmark` build: release code without R8, its own empty data) and the
# test app beside the phone's Granger, runs the first-run journeys, then uninstalls both. app.granger is never
# installed over, opened or cleared. Pick the phone with ANDROID_SERIAL when more than one is connected.
#   tools/dev/baseline-profile.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME

cleanup() {
  adb uninstall app.granger.benchmark >/dev/null 2>&1 || true
  adb uninstall app.granger.baselineprofile >/dev/null 2>&1 || true
}
trap cleanup EXIT

./gradlew :baselineprofile:connectedBenchmarkAndroidTest

out=$(find baselineprofile/build/outputs/connected_android_test_additional_output \( -name '*-baseline-prof.txt' -o -name '*-startup-prof.txt' \) -print0 | xargs -0 ls -t | head -1)
[[ -n "$out" ]] || { echo "No profile was written" >&2; exit 1; }
# With includeInStartupProfile the rule names its one file *-startup-prof.txt; it holds the full profile.
# The rule collects for the benchmark app; the classes are the app's own, whatever the package suffix.
cp "$out" app/src/main/baseline-prof.txt
echo "Wrote app/src/main/baseline-prof.txt ($(wc -l < app/src/main/baseline-prof.txt) rules) from $out"
